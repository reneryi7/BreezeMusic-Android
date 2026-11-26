package com.concept.music.data.recommendation

import android.content.Context
import com.concept.music.data.kugou.KugouSigner
import com.concept.music.data.model.Song
import com.concept.music.data.repository.LocalListenStatsRepository
import com.concept.music.data.repository.isSameMusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

data class ArtistImplicitSignal(
    val displayName: String = "",
    val fmCompletedCount: Int = 0,
    val fmLikedCount: Int = 0,
    val fmQuickSkipCount: Int = 0,
    val lastQuickSkipMs: Long = 0L,
    val explicitPenalty: Double = 0.0,
    val lastUpdatedMs: Long = 0L
)

data class FmPositiveRecord(
    val songKey: String = "",
    val song: Song = Song(id = "", title = "", artist = ""),
    val score: Double = 0.0,
    val timestampMs: Long = 0L
)

internal data class PersistedTasteProfile(
    val version: Int = 1,
    val artistSignals: MutableMap<String, ArtistImplicitSignal> = LinkedHashMap(),
    val probeRejectStreaks: MutableMap<String, Int> = LinkedHashMap(),
    val probeAcceptCounts: MutableMap<String, Int> = LinkedHashMap(),
    val songCooldownUntilMs: MutableMap<String, Long> = LinkedHashMap(),
    val fmQuickSkipTotal: Int = 0,
    val fmPositiveTotal: Int = 0,
    // timeSlotKey -> (featureKey "lang:L1" or "style:1" -> weighted score)
    val timeSlotFeatureScores: MutableMap<String, MutableMap<String, Double>> = LinkedHashMap(),
    val timeSlotEventCounts: MutableMap<String, Int> = LinkedHashMap(),
    val recentFmPositives: MutableList<FmPositiveRecord> = ArrayList()
)

/**
 * 供精排与召回消费的瞬时画像快照（融合全站长期统计 + 时间衰减 + FM 隐式交互 + 4 时段分布）
 */
data class TasteScoringSnapshot(
    val userId: String?,
    val artistAffinity: Map<String, Double>,
    val languageAffinity: Map<String, Double>,
    val styleAffinity: Map<String, Double>,
    val currentSlot: ListenTimeSlot,
    val timeSlotEnabled: Boolean,
    val timeSlotDeltaByFeature: Map<String, Double>,
    val artistPenalty: Map<String, Double>,
    val probeRejectStreaks: Map<String, Int>,
    val cooldownSongKeys: Set<String>,
    val recentlyPlayed7dKeys: Set<String>,
    val familiarRediscoveryCandidates: List<Song>,
    val topTasteSeeds: List<Song>,
    val habitualTopArtistsLower: Set<String>,
    val summary: TasteProfileSummary
)

/**
 * 微风本地长期听歌画像与隐式反馈仓库（按 userId 隔离持久化）：
 * - 全站正向底色：融合本机周/月/总听歌统计 + 红心收藏 + FM 完播/循环，遵循半衰期 45 天衰减公式 `w(t) = 0.25 + 0.75 * 2^(-Δd/45)`；
 * - FM 专属隐式负反馈：仅在「猜你喜欢」播放队列内采集秒切（<15s）信号，触发 30 天单曲冷却、温和歌手降权与尝鲜探针指数收敛 `0.45^streak`；
 * - 4 时段轻度感知：单时段累计有效收听 >= 15 首后启用，精排微调严格封顶 ±10%；
 * - 久违熟歌池：自动沉淀红心/高完播且距今 >= 7 天未听的珍藏曲目，供「熟悉 / 平衡」模式自然回味；
 * - 支持一键「重置本地隐式权重」（仅清空秒切惩罚、探针收敛与冷却，不动统计/红心/黑名单）。
 */
open class PersonalTasteProfileRepository(
    private val context: Context?,
    private val customStoreDir: File? = null
) {
    companion object {
        const val DAY_MS = 86_400_000L
        const val HALF_LIFE_DAYS = 45.0
        const val FM_SHOWN_COOLDOWN_MS = 72L * 3_600_000L
        const val FM_QUICK_SKIP_COOLDOWN_MS = 30L * DAY_MS
        const val RECENTLY_PLAYED_COOLDOWN_MS = 7L * DAY_MS
        const val QUICK_SKIP_THRESHOLD_MS = 15_000L
        const val TIME_SLOT_ACTIVATION_MIN_EVENTS = 15
        const val TIME_SLOT_MAX_DELTA = 0.10
        const val PROBE_DECAY_BASE = 0.45
        private const val ARTIST_SKIP_COOLDOWN_MS = 14L * DAY_MS
        private const val MAX_COOLDOWN_ENTRIES = 1_600
        private const val MAX_FM_POSITIVES = 120
        private val CHINA_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /**
         * 时间衰减公式：w(t) = 0.25 + 0.75 * 2^(-Δd / 45)
         */
        fun computeTimeDecayWeight(elapsedMs: Long): Double {
            val safeDays = (elapsedMs.coerceAtLeast(0L).toDouble()) / DAY_MS.toDouble()
            return 0.25 + 0.75 * 2.0.pow(-safeDays / HALF_LIFE_DAYS)
        }

        /**
         * 尝鲜模式降权语种破圈探针概率衰减因子：0.45 ^ rejectStreak
         */
        fun computeProbeMultiplier(rejectStreak: Int): Double {
            if (rejectStreak <= 0) return 1.0
            return PROBE_DECAY_BASE.pow(rejectStreak.coerceAtMost(8))
        }

        fun normalizeSongKey(song: Song): String {
            val h = listOf(song.hash, song.hashHiRes, song.hashFlac, song.hash320, song.hash128)
                .map { it.trim().lowercase(Locale.ROOT) }
                .firstOrNull { it.length == 32 }
            if (!h.isNullOrBlank()) return "h:$h"
            if (song.albumAudioId > 0L) return "aa:${song.albumAudioId}"
            if (song.audioId > 0L) return "a:${song.audioId}"
            if (song.id.isNotBlank()) return "id:${song.id.trim().lowercase(Locale.ROOT)}"
            return "ta:${song.title.trim().lowercase(Locale.ROOT)}|${song.artist.trim().lowercase(Locale.ROOT)}"
        }

        fun splitNormalizedArtists(rawArtist: String): List<String> =
            rawArtist.split("、", "/", "&", ",", "，", "feat.", "Feat.", "FT.")
                .map { it.trim() }
                .filter { it.isNotBlank() && it != "未知歌手" && it != "群星" && it != "云盘曲目" }
                .distinctBy { it.lowercase(Locale.ROOT) }
    }

    private val storeDir: File? by lazy {
        customStoreDir ?: runCatching {
            context?.applicationContext?.filesDir?.resolve("personal_taste_profile")?.apply {
                if (!exists()) mkdirs()
            }
        }.getOrNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val memoryProfiles = ConcurrentHashMap<String, PersistedTasteProfile>()

    private val _profileVersion = MutableStateFlow(0L)
    val profileVersion: StateFlow<Long> = _profileVersion.asStateFlow()

    private fun userKey(userId: String?): String =
        "taste_${userId?.trim()?.takeIf { it.isNotBlank() } ?: "guest"}"

    private fun fileFor(key: String): File? {
        val dir = storeDir ?: return null
        if (!dir.exists()) runCatching { dir.mkdirs() }
        val safe = key.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        return File(dir, "$safe.json")
    }

    @Synchronized
    private fun loadProfile(userId: String?): PersistedTasteProfile {
        val key = userKey(userId)
        memoryProfiles[key]?.let { return it }
        val loaded = runCatching {
            val f = fileFor(key)
            if (f != null && f.exists()) {
                val parsed = KugouSigner.gson.fromJson(f.readText(Charsets.UTF_8), PersistedTasteProfile::class.java)
                if (parsed != null) {
                    PersistedTasteProfile(
                        version = parsed.version.coerceAtLeast(1),
                        artistSignals = LinkedHashMap(parsed.artistSignals ?: emptyMap()),
                        probeRejectStreaks = LinkedHashMap(parsed.probeRejectStreaks ?: emptyMap()),
                        probeAcceptCounts = LinkedHashMap(parsed.probeAcceptCounts ?: emptyMap()),
                        songCooldownUntilMs = LinkedHashMap(parsed.songCooldownUntilMs ?: emptyMap()),
                        fmQuickSkipTotal = parsed.fmQuickSkipTotal,
                        fmPositiveTotal = parsed.fmPositiveTotal,
                        timeSlotFeatureScores = LinkedHashMap<String, MutableMap<String, Double>>().apply {
                            (parsed.timeSlotFeatureScores ?: emptyMap()).forEach { (k, v) ->
                                put(k, LinkedHashMap(v ?: emptyMap()))
                            }
                        },
                        timeSlotEventCounts = LinkedHashMap(parsed.timeSlotEventCounts ?: emptyMap()),
                        recentFmPositives = ArrayList(parsed.recentFmPositives ?: emptyList())
                    )
                } else PersistedTasteProfile()
            } else PersistedTasteProfile()
        }.getOrDefault(PersistedTasteProfile())
        memoryProfiles[key] = loaded
        return loaded
    }

    private fun persistAsync(userId: String?, profile: PersistedTasteProfile) {
        val key = userKey(userId)
        val json = KugouSigner.gson.toJson(profile)
        _profileVersion.value = _profileVersion.value + 1L
        val f = fileFor(key) ?: return
        ioScope.launch {
            runCatching {
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(json, Charsets.UTF_8)
                if (!tmp.renameTo(f)) {
                    f.writeText(json, Charsets.UTF_8)
                    runCatching { tmp.delete() }
                }
            }
        }
    }

    private fun pruneCooldowns(profile: PersistedTasteProfile, nowMs: Long) {
        if (profile.songCooldownUntilMs.size <= MAX_COOLDOWN_ENTRIES / 2) return
        val iter = profile.songCooldownUntilMs.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value <= nowMs) iter.remove()
        }
        if (profile.songCooldownUntilMs.size > MAX_COOLDOWN_ENTRIES) {
            val overflow = profile.songCooldownUntilMs.entries
                .sortedBy { it.value }
                .take(profile.songCooldownUntilMs.size - MAX_COOLDOWN_ENTRIES)
                .map { it.key }
            overflow.forEach { profile.songCooldownUntilMs.remove(it) }
        }
    }

    private fun recordTimeSlotFeatures(
        profile: PersistedTasteProfile,
        meta: SongTagMeta,
        weight: Double,
        nowMs: Long
    ) {
        if (weight <= 0.0) return
        val hour = runCatching { Instant.ofEpochMilli(nowMs).atZone(CHINA_ZONE).hour }.getOrDefault(20)
        val slot = ListenTimeSlot.fromHour(hour)
        profile.timeSlotEventCounts[slot.key] = (profile.timeSlotEventCounts[slot.key] ?: 0) + 1
        val bucket = profile.timeSlotFeatureScores.getOrPut(slot.key) { LinkedHashMap() }
        if (meta.languageCode.isNotBlank()) {
            val lk = "lang:${meta.languageCode}"
            bucket[lk] = (bucket[lk] ?: 0.0) + weight
        }
        for (sid in meta.styleIds) {
            val sk = "style:$sid"
            bucket[sk] = (bucket[sk] ?: 0.0) + weight * 0.85
        }
    }

    /**
     * 记录单次播放结算信号（全站正向均采集，FM 秒切负反馈严格限定在 isFromPersonalFm == true 时触发）
     */
    @Synchronized
    fun recordListenSignal(
        userId: String?,
        song: Song,
        meta: SongTagMeta,
        playedMs: Long,
        durationMs: Long,
        isFromPersonalFm: Boolean,
        isNaturalComplete: Boolean,
        isRepeat: Boolean,
        isManualSkip: Boolean,
        isReducedLanguageProbe: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val profile = loadProfile(userId)
        val songKey = normalizeSongKey(song)
        val effectiveDur = durationMs.takeIf { it > 0L } ?: song.durationMs.takeIf { it > 0L } ?: 180_000L
        val completionRatio = (playedMs.toDouble() / effectiveDur.toDouble()).coerceIn(0.0, 1.5)
        val artists = splitNormalizedArtists(song.artist)

        // 1. 猜你喜欢专属隐式「秒切」负反馈：播放 < 15s 且用户主动切歌
        val isQuickSkip = isFromPersonalFm &&
            isManualSkip &&
            !isNaturalComplete &&
            playedMs in 1L until QUICK_SKIP_THRESHOLD_MS &&
            completionRatio < 0.25

        if (isQuickSkip) {
            profile.songCooldownUntilMs[songKey] = maxOf(
                profile.songCooldownUntilMs[songKey] ?: 0L,
                nowMs + FM_QUICK_SKIP_COOLDOWN_MS
            )
            var nextSkipTotal = profile.fmQuickSkipTotal + 1
            for (artistName in artists) {
                val ak = artistName.lowercase(Locale.ROOT)
                val prev = profile.artistSignals[ak] ?: ArtistImplicitSignal(displayName = artistName)
                profile.artistSignals[ak] = prev.copy(
                    displayName = prev.displayName.ifBlank { artistName },
                    fmQuickSkipCount = prev.fmQuickSkipCount + 1,
                    explicitPenalty = (prev.explicitPenalty + 0.6).coerceAtMost(8.0),
                    lastQuickSkipMs = nowMs,
                    lastUpdatedMs = nowMs
                )
            }
            if (isReducedLanguageProbe && meta.languageCode.isNotBlank()) {
                val curStreak = profile.probeRejectStreaks[meta.languageCode] ?: 0
                profile.probeRejectStreaks[meta.languageCode] = (curStreak + 1).coerceAtMost(8)
            }
            pruneCooldowns(profile, nowMs)
            val updated = profile.copy(fmQuickSkipTotal = nextSkipTotal)
            memoryProfiles[userKey(userId)] = updated
            persistAsync(userId, updated)
            return
        }

        // 2. 全站正向有效收听判定（完播 >=80% 或单曲循环或有效收听 >=30s）
        val signalWeight = when {
            isRepeat -> 2.2
            isNaturalComplete || completionRatio >= 0.80 -> 1.5
            playedMs >= 30_000L || completionRatio >= 0.45 -> 0.8
            else -> 0.0
        }
        if (signalWeight <= 0.0) return

        recordTimeSlotFeatures(profile, meta, signalWeight, nowMs)

        if (isFromPersonalFm) {
            for (artistName in artists) {
                val ak = artistName.lowercase(Locale.ROOT)
                val prev = profile.artistSignals[ak] ?: ArtistImplicitSignal(displayName = artistName)
                profile.artistSignals[ak] = prev.copy(
                    displayName = prev.displayName.ifBlank { artistName },
                    fmCompletedCount = prev.fmCompletedCount + 1,
                    explicitPenalty = (prev.explicitPenalty - 0.5).coerceAtLeast(0.0),
                    lastUpdatedMs = nowMs
                )
            }
            if (meta.languageCode.isNotBlank() && (isNaturalComplete || completionRatio >= 0.80)) {
                // 完播降权语种探针 -> 清空连续拒绝并记录接受
                if ((profile.probeRejectStreaks[meta.languageCode] ?: 0) > 0) {
                    profile.probeRejectStreaks[meta.languageCode] = 0
                }
                profile.probeAcceptCounts[meta.languageCode] =
                    (profile.probeAcceptCounts[meta.languageCode] ?: 0) + 1
            }
            profile.recentFmPositives.removeAll { it.songKey == songKey }
            profile.recentFmPositives.add(
                0,
                FmPositiveRecord(
                    songKey = songKey,
                    song = song.sanitizeFromJson(),
                    score = signalWeight,
                    timestampMs = nowMs
                )
            )
            if (profile.recentFmPositives.size > MAX_FM_POSITIVES) {
                profile.recentFmPositives.subList(MAX_FM_POSITIVES, profile.recentFmPositives.size).clear()
            }
            val updated = profile.copy(fmPositiveTotal = profile.fmPositiveTotal + 1)
            memoryProfiles[userKey(userId)] = updated
            persistAsync(userId, updated)
        } else {
            persistAsync(userId, profile)
        }
    }

    /**
     * 记录红心收藏 / 取消红心信号
     */
    @Synchronized
    fun recordFavoriteSignal(
        userId: String?,
        song: Song,
        meta: SongTagMeta,
        isLiked: Boolean,
        isFromPersonalFm: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val profile = loadProfile(userId)
        val songKey = normalizeSongKey(song)
        val artists = splitNormalizedArtists(song.artist)
        if (isLiked) {
            recordTimeSlotFeatures(profile, meta, 3.0, nowMs)
            for (artistName in artists) {
                val ak = artistName.lowercase(Locale.ROOT)
                val prev = profile.artistSignals[ak] ?: ArtistImplicitSignal(displayName = artistName)
                profile.artistSignals[ak] = prev.copy(
                    displayName = prev.displayName.ifBlank { artistName },
                    fmLikedCount = prev.fmLikedCount + 1,
                    explicitPenalty = 0.0,
                    lastUpdatedMs = nowMs
                )
            }
            if (meta.languageCode.isNotBlank()) {
                profile.probeRejectStreaks[meta.languageCode] = 0
                profile.probeAcceptCounts[meta.languageCode] =
                    (profile.probeAcceptCounts[meta.languageCode] ?: 0) + 2
            }
            profile.recentFmPositives.removeAll { it.songKey == songKey }
            profile.recentFmPositives.add(
                0,
                FmPositiveRecord(
                    songKey = songKey,
                    song = song.sanitizeFromJson().copy(isFavorite = true),
                    score = 3.0,
                    timestampMs = nowMs
                )
            )
            if (profile.recentFmPositives.size > MAX_FM_POSITIVES) {
                profile.recentFmPositives.subList(MAX_FM_POSITIVES, profile.recentFmPositives.size).clear()
            }
            val updated = if (isFromPersonalFm) {
                profile.copy(fmPositiveTotal = profile.fmPositiveTotal + 1)
            } else profile
            memoryProfiles[userKey(userId)] = updated
            persistAsync(userId, updated)
        } else {
            profile.recentFmPositives.removeAll { it.songKey == songKey }
            persistAsync(userId, profile)
        }
    }

    /**
     * 记录显式「不听这首歌 / 少推该歌手」负反馈到隐式画像（同步触发降权与探针收敛）
     */
    @Synchronized
    fun recordExplicitDislike(
        userId: String?,
        song: Song,
        meta: SongTagMeta,
        isArtistDislike: Boolean,
        isReducedLanguageProbe: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val profile = loadProfile(userId)
        val songKey = normalizeSongKey(song)
        profile.songCooldownUntilMs[songKey] = nowMs + 365L * DAY_MS
        val deltaPenalty = if (isArtistDislike) 5.0 else 1.5
        for (artistName in splitNormalizedArtists(song.artist)) {
            val ak = artistName.lowercase(Locale.ROOT)
            val prev = profile.artistSignals[ak] ?: ArtistImplicitSignal(displayName = artistName)
            profile.artistSignals[ak] = prev.copy(
                displayName = prev.displayName.ifBlank { artistName },
                explicitPenalty = (prev.explicitPenalty + deltaPenalty).coerceAtMost(10.0),
                lastQuickSkipMs = nowMs,
                lastUpdatedMs = nowMs
            )
        }
        if (isReducedLanguageProbe && meta.languageCode.isNotBlank()) {
            val cur = profile.probeRejectStreaks[meta.languageCode] ?: 0
            profile.probeRejectStreaks[meta.languageCode] = (cur + 2).coerceAtMost(8)
        }
        pruneCooldowns(profile, nowMs)
        persistAsync(userId, profile)
    }

    /**
     * 标记本批猜你喜欢曲目已曝光，写入 72 小时持久化冷却去重池
     */
    @Synchronized
    fun markRecommendedShown(
        userId: String?,
        songs: List<Song>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (songs.isEmpty()) return
        val profile = loadProfile(userId)
        val expireAt = nowMs + FM_SHOWN_COOLDOWN_MS
        songs.forEach { song ->
            val key = normalizeSongKey(song)
            profile.songCooldownUntilMs[key] = maxOf(profile.songCooldownUntilMs[key] ?: 0L, expireAt)
        }
        pruneCooldowns(profile, nowMs)
        persistAsync(userId, profile)
    }

    /**
     * 一键重置微风本地隐式权重：
     * 仅清空秒切惩罚、尝鲜探针收敛计数与推荐冷却池，绝不删除听歌统计、红心歌单或云端屏蔽名单。
     */
    @Synchronized
    fun resetImplicitWeights(userId: String?) {
        val prev = loadProfile(userId)
        val cleanedArtists = LinkedHashMap<String, ArtistImplicitSignal>()
        prev.artistSignals.forEach { (k, v) ->
            if (v.fmCompletedCount > 0 || v.fmLikedCount > 0) {
                cleanedArtists[k] = v.copy(
                    fmQuickSkipCount = 0,
                    explicitPenalty = 0.0,
                    lastQuickSkipMs = 0L
                )
            }
        }
        val reset = prev.copy(
            artistSignals = cleanedArtists,
            probeRejectStreaks = LinkedHashMap(),
            songCooldownUntilMs = LinkedHashMap(),
            fmQuickSkipTotal = 0
        )
        memoryProfiles[userKey(userId)] = reset
        persistAsync(userId, reset)
    }

    /**
     * 构建完整的精排与召回画像快照（同时生成供设置页展示的可读 TasteProfileSummary）
     */
    @Synchronized
    fun buildScoringSnapshot(
        userId: String?,
        listenStats: LocalListenStatsRepository,
        likedSongs: List<Song>,
        recentHistory: List<Song>,
        tagCache: SongTagMetadataCache,
        preference: StructuredListeningPreference,
        nowMs: Long = System.currentTimeMillis()
    ): TasteScoringSnapshot {
        val profile = loadProfile(userId)
        val allRank = listenStats.queryRank(userId, LocalListenStatsRepository.PERIOD_ALL, nowMs = nowMs)
        val weekRank = listenStats.queryRank(userId, LocalListenStatsRepository.PERIOD_WEEK, nowMs = nowMs)

        val artistScoreAcc = LinkedHashMap<String, Double>()
        val artistDisplayNames = LinkedHashMap<String, String>()
        val langScoreAcc = LinkedHashMap<String, Double>()
        val styleScoreAcc = LinkedHashMap<String, Double>()
        val seedCandidates = LinkedHashMap<String, Pair<Song, Double>>()

        // 7 天内已播放曲目集合（用于严防熟歌机械重推）
        val recentlyPlayed7d = HashSet<String>()
        val sevenDayCutoff = nowMs - RECENTLY_PLAYED_COOLDOWN_MS
        weekRank.songs.forEach { item ->
            if (item.lastPlayedAtMs >= sevenDayCutoff) {
                recentlyPlayed7d.add(normalizeSongKey(item.song))
            }
        }
        recentHistory.take(40).forEach { s ->
            recentlyPlayed7d.add(normalizeSongKey(s))
        }

        // 1. 融合本机总榜听歌统计（带 45 天半衰期时间衰减）
        val familiarPoolAcc = LinkedHashMap<String, Pair<Song, Double>>()
        for (item in allRank.songs) {
            val s = item.song
            val sk = normalizeSongKey(s)
            val elapsed = (nowMs - item.lastPlayedAtMs).coerceAtLeast(0L)
            val decay = computeTimeDecayWeight(elapsed)
            val countFactor = kotlin.math.ln(1.0 + item.playCount.coerceAtLeast(1).toDouble()) + 1.0
            val totalW = countFactor * decay * 1.4
            val meta = tagCache.resolveFast(s)

            splitNormalizedArtists(s.artist).forEach { a ->
                val ak = a.lowercase(Locale.ROOT)
                artistDisplayNames.putIfAbsent(ak, a)
                artistScoreAcc[ak] = (artistScoreAcc[ak] ?: 0.0) + totalW
            }
            if (meta.languageCode.isNotBlank()) {
                langScoreAcc[meta.languageCode] = (langScoreAcc[meta.languageCode] ?: 0.0) + totalW
            }
            meta.styleIds.forEach { sid ->
                styleScoreAcc[sid] = (styleScoreAcc[sid] ?: 0.0) + totalW
            }

            val prevSeed = seedCandidates[sk]
            if (prevSeed == null || totalW > prevSeed.second) {
                seedCandidates[sk] = s to totalW
            }

            // 久违熟歌：历史高完播（>=2次）且至少 7 天未听
            if (item.playCount >= 2 && sk !in recentlyPlayed7d && elapsed >= RECENTLY_PLAYED_COOLDOWN_MS) {
                val nostalgiaBonus = 1.0 + kotlin.math.min(elapsed.toDouble() / (30.0 * DAY_MS), 2.5) * 0.35
                familiarPoolAcc[sk] = s to (countFactor * nostalgiaBonus)
            }
        }

        // 2. 融合「我喜欢」红心歌单（强正向锚点 + 久违熟歌主库）
        likedSongs.take(300).forEachIndexed { idx, song ->
            val sk = normalizeSongKey(song)
            val recencyDecay = 0.45 + 0.55 * (1.0 - (idx.toDouble() / 320.0).coerceIn(0.0, 1.0))
            val likeW = 3.0 * recencyDecay
            val meta = tagCache.resolveFast(song)

            splitNormalizedArtists(song.artist).forEach { a ->
                val ak = a.lowercase(Locale.ROOT)
                artistDisplayNames.putIfAbsent(ak, a)
                artistScoreAcc[ak] = (artistScoreAcc[ak] ?: 0.0) + likeW
            }
            if (meta.languageCode.isNotBlank()) {
                langScoreAcc[meta.languageCode] = (langScoreAcc[meta.languageCode] ?: 0.0) + likeW
            }
            meta.styleIds.forEach { sid ->
                styleScoreAcc[sid] = (styleScoreAcc[sid] ?: 0.0) + likeW
            }
            val prevSeed = seedCandidates[sk]
            seedCandidates[sk] = song to ((prevSeed?.second ?: 0.0) + likeW)

            if (sk !in recentlyPlayed7d) {
                val prevFam = familiarPoolAcc[sk]
                familiarPoolAcc[sk] = song to ((prevFam?.second ?: 0.0) + 3.2 * (0.6 + 0.4 * recencyDecay))
            }
        }

        // 3. 融合 FM 实时正向事件（高时效权重）
        for (ev in profile.recentFmPositives) {
            val decay = computeTimeDecayWeight(nowMs - ev.timestampMs)
            val w = ev.score * decay * 1.35
            val meta = tagCache.resolveFast(ev.song)
            splitNormalizedArtists(ev.song.artist).forEach { a ->
                val ak = a.lowercase(Locale.ROOT)
                artistDisplayNames.putIfAbsent(ak, a)
                artistScoreAcc[ak] = (artistScoreAcc[ak] ?: 0.0) + w
            }
            if (meta.languageCode.isNotBlank()) {
                langScoreAcc[meta.languageCode] = (langScoreAcc[meta.languageCode] ?: 0.0) + w
            }
            val prevSeed = seedCandidates[ev.songKey]
            seedCandidates[ev.songKey] = ev.song to ((prevSeed?.second ?: 0.0) + w)
        }

        // 4. 叠加官方显式勾选的 style 曲风偏好
        preference.enabledStyleIds.forEach { sid ->
            styleScoreAcc[sid] = (styleScoreAcc[sid] ?: 0.0) + 4.0
        }

        // 5. 计算隐式歌手惩罚（连续 3 次秒切触发 14 天强降权，其余随时间衰减）
        val artistPenalty = LinkedHashMap<String, Double>()
        profile.artistSignals.forEach { (ak, sig) ->
            if (sig.explicitPenalty > 0.01) {
                val elapsed = (nowMs - sig.lastQuickSkipMs).coerceAtLeast(0L)
                val streakBoost = if (sig.fmQuickSkipCount >= 3 && elapsed < ARTIST_SKIP_COOLDOWN_MS) 1.6 else 1.0
                val decay = 2.0.pow(-(elapsed.toDouble() / (21.0 * DAY_MS)))
                val effective = (sig.explicitPenalty * streakBoost * decay).coerceIn(0.0, 10.0)
                if (effective > 0.05) {
                    artistPenalty[ak] = effective
                }
            }
        }

        // 6. 归一化向量到 [0, 1]
        fun normalizeMap(src: Map<String, Double>): Map<String, Double> {
            val maxV = src.values.maxOrNull()?.takeIf { it > 0.0 } ?: return emptyMap()
            return src.mapValues { (_, v) -> (v / maxV).coerceIn(0.0, 1.0) }
        }
        val normArtist = normalizeMap(artistScoreAcc)
        val normLang = normalizeMap(langScoreAcc)
        val normStyle = normalizeMap(styleScoreAcc)

        // 7. 4 时段轻度微调（仅在当前时段累计 >= 15 首有效收听时启用，幅度锁定 ±0.10）
        val curHour = runCatching { Instant.ofEpochMilli(nowMs).atZone(CHINA_ZONE).hour }.getOrDefault(20)
        val curSlot = ListenTimeSlot.fromHour(curHour)
        val slotCount = profile.timeSlotEventCounts[curSlot.key] ?: 0
        val slotEnabled = slotCount >= TIME_SLOT_ACTIVATION_MIN_EVENTS
        val slotDelta = LinkedHashMap<String, Double>()
        if (slotEnabled) {
            val slotScores = profile.timeSlotFeatureScores[curSlot.key].orEmpty()
            val slotTotal = slotScores.values.sum().takeIf { it > 0.0 } ?: 1.0
            val allSlotTotals = LinkedHashMap<String, Double>()
            profile.timeSlotFeatureScores.values.forEach { m ->
                m.forEach { (fk, v) -> allSlotTotals[fk] = (allSlotTotals[fk] ?: 0.0) + v }
            }
            val grandTotal = allSlotTotals.values.sum().takeIf { it > 0.0 } ?: 1.0
            for ((fk, sv) in slotScores) {
                val slotRatio = sv / slotTotal
                val globalRatio = (allSlotTotals[fk] ?: 0.0) / grandTotal
                val diff = (slotRatio - globalRatio) * 0.5
                slotDelta[fk] = diff.coerceIn(-TIME_SLOT_MAX_DELTA, TIME_SLOT_MAX_DELTA)
            }
        }

        // 8. 活跃冷却集合（72h FM 已推 + 30d 秒切）
        val activeCooldowns = profile.songCooldownUntilMs
            .filterValues { it > nowMs }
            .keys
            .toHashSet()

        // 9. 久违熟歌候选 & 顶级口味种子 & 常听 Top 20 歌手
        val familiarCandidates = familiarPoolAcc.values
            .sortedByDescending { it.second }
            .map { it.first }
            .filterNot { normalizeSongKey(it) in activeCooldowns }
            .take(80)

        val topSeeds = seedCandidates.values
            .sortedByDescending { it.second }
            .map { it.first }
            .take(60)

        val topArtistsSorted = artistScoreAcc.entries
            .sortedByDescending { it.value }
            .take(20)
        val habitualArtistsLower = topArtistsSorted.map { it.key }.toHashSet()

        // 10. 构建设置页可读画像摘要
        val langTotal = langScoreAcc.values.sum().takeIf { it > 0.0 } ?: 0.0
        val langShares = if (langTotal > 0.0) {
            langScoreAcc.entries
                .sortedByDescending { it.value }
                .take(4)
                .mapNotNull { (code, v) ->
                    val label = SongLangTag.fromCode(code)?.label ?: return@mapNotNull null
                    label to (v / langTotal).toFloat().coerceIn(0f, 1f)
                }
        } else emptyList()

        val tightenedProbes = preference.reducedLanguageCodes().mapNotNull { code ->
            val streak = profile.probeRejectStreaks[code] ?: 0
            if (streak >= 2) SongLangTag.fromCode(code)?.label else null
        }

        val summary = TasteProfileSummary(
            languageShares = langShares,
            topArtists = topArtistsSorted.take(5).map { artistDisplayNames[it.key] ?: it.key },
            activeTimeSlot = curSlot,
            timeSlotAdjustmentActive = slotEnabled,
            activeTimeSlotHint = if (slotEnabled) {
                "${curSlot.label}时段已启用轻度偏好微调"
            } else {
                "当前时段收听达 ${TIME_SLOT_ACTIVATION_MIN_EVENTS} 首后自动启用轻度微调（已累计 $slotCount 首）"
            },
            tightenedProbeLanguages = tightenedProbes,
            quickSkipCount = profile.fmQuickSkipTotal,
            trackedPositiveCount = allRank.totalPlayCount + likedSongs.size + profile.fmPositiveTotal
        )

        return TasteScoringSnapshot(
            userId = userId,
            artistAffinity = normArtist,
            languageAffinity = normLang,
            styleAffinity = normStyle,
            currentSlot = curSlot,
            timeSlotEnabled = slotEnabled,
            timeSlotDeltaByFeature = slotDelta,
            artistPenalty = artistPenalty,
            probeRejectStreaks = profile.probeRejectStreaks.toMap(),
            cooldownSongKeys = activeCooldowns,
            recentlyPlayed7dKeys = recentlyPlayed7d,
            familiarRediscoveryCandidates = familiarCandidates,
            topTasteSeeds = topSeeds,
            habitualTopArtistsLower = habitualArtistsLower,
            summary = summary
        )
    }

    /**
     * 判断给定曲目在最近推荐中是否与某候选列表重复
     */
    fun hasAnySameTrack(list: List<Song>, target: Song): Boolean =
        list.any { isSameMusicTrack(it, target) }
}
