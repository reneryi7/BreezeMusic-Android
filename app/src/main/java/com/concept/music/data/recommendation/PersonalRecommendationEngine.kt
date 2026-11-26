package com.concept.music.data.recommendation

import com.concept.music.data.kugou.LocalBlacklistFilter
import com.concept.music.data.kugou.MusicApiRepository
import com.concept.music.data.kugou.fetchYouthSingleCardRecommend
import com.concept.music.data.kugou.fetchYouthTagCardRecommend
import com.concept.music.data.model.Song
import com.concept.music.data.repository.LocalListenStatsRepository
import com.concept.music.data.repository.isSameMusicTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 端云混合「猜你喜欢」核心推荐引擎 + 每日推荐硬屏蔽过滤器：
 * 1. 模式感知多路云端召回（官方私人 FM + 协同过滤 + 概念版 AI/单卡/曲风盲盒 + 风格推荐 + 本地久违熟歌池 + 常听歌手遗珠）；
 * 2. 批量真实语种/形态/流派补全（优先命中磁盘缓存 SongTagMetadataCache）；
 * 3. 统一硬约束过滤（song_lang == 0 + 云端/本地黑名单 + 72h 展示冷却 / 7d 近期播放 / 30d 秒切冷却）；
 * 4. 综合精排打分（云端先验 + 时间衰减长期亲和 + 语种/内容软权重 + 4 时段 ±10% 微调 - 秒切惩罚）；
 * 5. 三模式精准分桶 + 尝鲜受控破圈探针（10%~15%，上限 2 首，随连续拒绝 0.45^streak 收敛）+ MMR 多样性交错重排。
 */
class PersonalRecommendationEngine {

    private val batchCursor = AtomicInteger(0)

    private val discoveryStyleTagGroups = listOf(
        "9",   // 独立 / Indie
        "14",  // 爵士 / Jazz
        "12",  // 民谣 / Folk
        "11",  // R&B / Soul
        "15",  // 电子 / Electronic
        "13",  // 摇滚 / Post-Rock
        "26",  // 纯音乐 / 氛围器乐
        "19"   // 复古 / City Pop / 世界音乐
    )

    internal enum class CandidateSource(val priorWeight: Double) {
        PERSONAL_FM(1.00),
        AI_CF_SEED(0.95),
        YOUTH_PERSONAL(0.92),
        CONCEPT_AI(0.88),
        SINGLE_CARD(0.84),
        YOUTH_DISCOVERY(0.90),
        YOUTH_TAG_BLIND_BOX(0.92),
        STYLE_REC(0.82),
        ARTIST_DEEP_CUT(0.86),
        FAMILIAR_REDISCOVERY(0.96)
    }

    internal data class ScoredCandidate(
        val song: Song,
        val songKey: String,
        val meta: SongTagMeta,
        val source: CandidateSource,
        val score: Double,
        val isFamiliarRediscovery: Boolean,
        val isReducedLanguageProbe: Boolean,
        val isHabitualArtist: Boolean
    )

    /**
     * 完整端云混合推荐入口（面向「猜你喜欢」刷新、切换模式与无限续播）
     */
    suspend fun recommendPersonalFm(
        userId: String?,
        mode: RecommendationMode,
        size: Int = 20,
        musicRepo: MusicApiRepository,
        preferenceRepo: RecommendationPreferenceRepository,
        tasteProfileRepo: PersonalTasteProfileRepository,
        tagCache: SongTagMetadataCache,
        listenStats: LocalListenStatsRepository,
        likedSongs: List<Song>,
        recentHistory: List<Song>,
        extraExcludeKeys: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
        random: Random = Random.Default
    ): List<Song> = coroutineScope {
        val targetSize = size.coerceIn(1, 40)
        val batchIdx = batchCursor.getAndIncrement()
        val preference = preferenceRepo.currentPreference(userId)

        val snapshot = tasteProfileRepo.buildScoringSnapshot(
            userId = userId,
            listenStats = listenStats,
            likedSongs = likedSongs,
            recentHistory = recentHistory,
            tagCache = tagCache,
            preference = preference,
            nowMs = nowMs
        )

        // 若本地长期画像尚为空（冷启动新号/首次迁移），补充拉取一次云端最近播放作为种子底色
        val effectiveSeeds = if (snapshot.topTasteSeeds.isNotEmpty()) {
            snapshot.topTasteSeeds
        } else {
            runCatching { musicRepo.latestListenSongs(pageSize = 30) }.getOrDefault(emptyList())
        }
        val rotatedSeeds = if (effectiveSeeds.isNotEmpty()) {
            val offset = (batchIdx * 7) % effectiveSeeds.size
            (effectiveSeeds.drop(offset) + effectiveSeeds.take(offset)).take(12)
        } else emptyList()

        // 1. 模式感知多路并发召回
        val rawPool = mutableListOf<Pair<Song, CandidateSource>>()
        val pfmAnchor = if (mode == RecommendationMode.DISCOVERY) null else rotatedSeeds.firstOrNull()
        val pfmDef1 = async {
            runCatching {
                musicRepo.recallPersonalFmStream(
                    mode = mode,
                    anchorSong = pfmAnchor,
                    batchIdx = batchIdx,
                    subIdx = 0
                )
            }.getOrDefault(emptyList())
        }
        val pfmDef2 = async {
            runCatching {
                musicRepo.recallPersonalFmStream(
                    mode = mode,
                    anchorSong = if (mode == RecommendationMode.DISCOVERY) null else rotatedSeeds.getOrNull(3),
                    batchIdx = batchIdx,
                    subIdx = 1
                )
            }.getOrDefault(emptyList())
        }
        val cfDef = async {
            if (mode == RecommendationMode.DISCOVERY || rotatedSeeds.isEmpty()) return@async emptyList<Song>()
            runCatching { musicRepo.recallAiSeedRecommend(rotatedSeeds) }.getOrDefault(emptyList())
        }
        val conceptAiDef = async {
            val page = if (mode == RecommendationMode.DISCOVERY) ((batchIdx % 6) + 3) else ((batchIdx % 5) + 1)
            runCatching { musicRepo.recallConceptAiSongs(page = page) }.getOrDefault(emptyList())
        }
        val youthCardDef = async {
            val cardId = when (mode) {
                RecommendationMode.FAMILIAR -> if (batchIdx % 2 == 0) 3001 else 3014
                RecommendationMode.DISCOVERY -> if (batchIdx % 2 == 0) 3004 else 3005
                RecommendationMode.BALANCED -> if (batchIdx % 2 == 0) 3001 else 3004
            }
            runCatching { musicRepo.fetchYouthSingleCardRecommend(cardId = cardId, pageSize = 30) }.getOrDefault(emptyList())
        }
        val youthBlindBoxDef = async {
            if (mode == RecommendationMode.FAMILIAR) return@async emptyList<Song>()
            runCatching { musicRepo.fetchYouthTagCardRecommend() }.getOrDefault(emptyList())
        }
        val legacySingleCardDef = async {
            val cardId = when (mode) {
                RecommendationMode.FAMILIAR -> "1"
                RecommendationMode.DISCOVERY -> "3"
                RecommendationMode.BALANCED -> ((batchIdx % 2) + 1).toString()
            }
            runCatching {
                musicRepo.recallLegacySingleCard(
                    cardId = cardId,
                    seedSongs = if (mode == RecommendationMode.DISCOVERY) emptyList() else rotatedSeeds.take(6)
                )
            }.getOrDefault(emptyList())
        }
        val styleDef = async {
            val tagArg = resolveEverydayStyleTagArg(mode, preference, batchIdx)
            runCatching { musicRepo.recallEverydayStyleSongs(tagArg) }.getOrDefault(emptyList())
        }
        val artistDeepCutDef = async {
            if (mode == RecommendationMode.DISCOVERY) return@async emptyList<Song>()
            recallArtistDeepCuts(rotatedSeeds, batchIdx, musicRepo)
        }

        pfmDef1.await().forEach { rawPool.add(it to CandidateSource.PERSONAL_FM) }
        pfmDef2.await().forEach { rawPool.add(it to CandidateSource.PERSONAL_FM) }
        cfDef.await().forEach { rawPool.add(it to CandidateSource.AI_CF_SEED) }
        youthCardDef.await().forEach {
            val src = if (mode == RecommendationMode.DISCOVERY) CandidateSource.YOUTH_DISCOVERY else CandidateSource.YOUTH_PERSONAL
            rawPool.add(it to src)
        }
        youthBlindBoxDef.await().forEach { rawPool.add(it to CandidateSource.YOUTH_TAG_BLIND_BOX) }
        conceptAiDef.await().forEach { rawPool.add(it to CandidateSource.CONCEPT_AI) }
        legacySingleCardDef.await().forEach { rawPool.add(it to CandidateSource.SINGLE_CARD) }
        styleDef.await().forEach { rawPool.add(it to CandidateSource.STYLE_REC) }
        artistDeepCutDef.await().forEach { rawPool.add(it to CandidateSource.ARTIST_DEEP_CUT) }

        // 本地久违熟歌池（仅熟悉 / 平衡模式注入）
        if (mode != RecommendationMode.DISCOVERY) {
            snapshot.familiarRediscoveryCandidates.forEach {
                rawPool.add(it to CandidateSource.FAMILIAR_REDISCOVERY)
            }
        }

        // 2. 去重并批量补全真实语种/流派元数据
        val dedupedCandidates = LinkedHashMap<String, Pair<Song, CandidateSource>>()
        for ((song, src) in rawPool) {
            if (song.title.isBlank() || song.title == "未知歌曲") continue
            val sk = PersonalTasteProfileRepository.normalizeSongKey(song)
            val prev = dedupedCandidates[sk]
            if (prev == null || src == CandidateSource.FAMILIAR_REDISCOVERY) {
                dedupedCandidates[sk] = song to src
            }
        }
        val uniqueSongs = dedupedCandidates.values.map { it.first }
        val metaByPrimaryKey = tagCache.enrichAndResolveBatch(uniqueSongs, musicRepo)

        // 3. 精排、硬约束拦截、分桶配比与 MMR 重排
        val selected = rankAndSelectCandidates(
            candidates = dedupedCandidates.values.toList(),
            metaResolver = { s ->
                metaByPrimaryKey[tagCache.songPrimaryKey(s)] ?: tagCache.resolveFast(s)
            },
            mode = mode,
            size = targetSize,
            preference = preference,
            preferenceRepo = preferenceRepo,
            snapshot = snapshot,
            extraExcludeKeys = extraExcludeKeys,
            random = random
        )

        val enriched = runCatching { musicRepo.enrichSongsPrivilegeLite(selected) }.getOrDefault(selected)
        // 二次安全核查硬屏蔽（防止 enrich 后新字段命中黑名单）
        val finalSafe = enriched.filterNot { song ->
            val m = metaByPrimaryKey[tagCache.songPrimaryKey(song)] ?: tagCache.resolveFast(song)
            preferenceRepo.isHardBlocked(song, m, preference)
        }.ifEmpty { enriched }

        tasteProfileRepo.markRecommendedShown(userId, finalSafe, nowMs = nowMs)
        finalSafe
    }

    /**
     * 纯函数化核心打分、硬过滤、分桶配比与多样性重排（便于单元测试 100% 精准断言）
     */
    internal fun rankAndSelectCandidates(
        candidates: List<Pair<Song, CandidateSource>>,
        metaResolver: (Song) -> SongTagMeta,
        mode: RecommendationMode,
        size: Int,
        preference: StructuredListeningPreference,
        preferenceRepo: RecommendationPreferenceRepository,
        snapshot: TasteScoringSnapshot,
        extraExcludeKeys: Set<String> = emptySet(),
        random: Random = Random.Default
    ): List<Song> {
        val familiarKeySet = snapshot.familiarRediscoveryCandidates
            .map { PersonalTasteProfileRepository.normalizeSongKey(it) }
            .toHashSet()
        val reducedLangCodes = preference.reducedLanguageCodes()
        val strictExcludedKeys = snapshot.cooldownSongKeys + snapshot.recentlyPlayed7dKeys + extraExcludeKeys

        val scoredStrict = mutableListOf<ScoredCandidate>()
        val scoredRelaxedCooldown = mutableListOf<ScoredCandidate>()

        candidates.forEachIndexed { idx, (song, source) ->
            val sk = PersonalTasteProfileRepository.normalizeSongKey(song)
            val meta = metaResolver(song)

            // 硬约束（绝对底线：权重=0 语种/内容形态 + 歌曲/歌手黑名单），任何模式绝不突破
            if (preferenceRepo.isHardBlocked(song, meta, preference)) {
                return@forEachIndexed
            }

            val isFamiliar = source == CandidateSource.FAMILIAR_REDISCOVERY || sk in familiarKeySet
            val isReducedProbe = meta.languageCode.isNotBlank() && meta.languageCode in reducedLangCodes
            val artistsLower = PersonalTasteProfileRepository.splitNormalizedArtists(song.artist)
                .map { it.lowercase(Locale.ROOT) }
            val isHabitual = artistsLower.any { it in snapshot.habitualTopArtistsLower }

            val rankPrior = (1.0 - (idx.toDouble() / (candidates.size.coerceAtLeast(1) * 1.4))).coerceIn(0.30, 1.0)
            val cloudScore = source.priorWeight * rankPrior

            val artistAff = artistsLower.maxOfOrNull { snapshot.artistAffinity[it] ?: 0.0 } ?: 0.0
            val langAff = if (meta.languageCode.isNotBlank()) (snapshot.languageAffinity[meta.languageCode] ?: 0.0) else 0.0
            val styleAff = meta.styleIds.maxOfOrNull { snapshot.styleAffinity[it] ?: 0.0 } ?: 0.0
            val tasteScore = 0.55 * artistAff + 0.25 * langAff + 0.20 * styleAff

            val langWeight = if (meta.languageCode.isNotBlank()) preference.weightForCode(meta.languageCode) else 50
            val langMod = when {
                langWeight > 50 -> 0.85
                langWeight in 1..49 -> if (mode == RecommendationMode.DISCOVERY) -0.25 else -2.80
                else -> 0.0
            }

            var contentMod = 0.0
            for (flag in meta.contentFlags) {
                val fw = preference.weightForCode(flag)
                if (fw in 1..49) contentMod -= 1.60
                else if (fw > 50) contentMod += 0.45
            }

            // 4 时段轻度精排微调（严格封顶 ±0.10）
            var slotDelta = 0.0
            if (snapshot.timeSlotEnabled) {
                val deltas = mutableListOf<Double>()
                if (meta.languageCode.isNotBlank()) {
                    snapshot.timeSlotDeltaByFeature["lang:${meta.languageCode}"]?.let(deltas::add)
                }
                meta.styleIds.forEach { sid ->
                    snapshot.timeSlotDeltaByFeature["style:$sid"]?.let(deltas::add)
                }
                if (deltas.isNotEmpty()) {
                    slotDelta = deltas.average().coerceIn(-PersonalTasteProfileRepository.TIME_SLOT_MAX_DELTA, PersonalTasteProfileRepository.TIME_SLOT_MAX_DELTA)
                }
            }

            val skipPenalty = (artistsLower.maxOfOrNull { snapshot.artistPenalty[it] ?: 0.0 } ?: 0.0) * 0.45
            val jitter = (random.nextDouble() - 0.5) * 0.14

            val totalScore = when (mode) {
                RecommendationMode.FAMILIAR ->
                    0.90 * cloudScore + 1.45 * tasteScore + langMod + contentMod + slotDelta - skipPenalty + jitter
                RecommendationMode.BALANCED ->
                    1.05 * cloudScore + 1.10 * tasteScore + langMod + contentMod + slotDelta - skipPenalty + jitter
                RecommendationMode.DISCOVERY ->
                    1.15 * cloudScore + 0.40 * (0.3 * langAff + 0.7 * styleAff) + langMod + contentMod + slotDelta - skipPenalty + jitter
            }

            val sc = ScoredCandidate(
                song = song,
                songKey = sk,
                meta = meta,
                source = source,
                score = totalScore,
                isFamiliarRediscovery = isFamiliar,
                isReducedLanguageProbe = isReducedProbe,
                isHabitualArtist = isHabitual
            )

            if (sk !in strictExcludedKeys) {
                scoredStrict.add(sc)
            } else if (sk !in extraExcludeKeys) {
                // 仅在极端小样本账号冷却池耗尽时作为二级保底（仍 100% 遵守硬屏蔽！）
                scoredRelaxedCooldown.add(sc)
            }
        }

        val primaryPicked = assembleByModeQuotas(
            pool = scoredStrict,
            mode = mode,
            targetSize = size,
            snapshot = snapshot,
            random = random
        )

        if (primaryPicked.size >= size) {
            return primaryPicked.take(size)
        }

        // 极端小样本保底：仅放宽冷却去重，绝不放宽硬屏蔽，尝鲜仍尽量避开熟歌与常听歌手
        val alreadyKeys = primaryPicked.map { PersonalTasteProfileRepository.normalizeSongKey(it) }.toHashSet()
        val extraPool = scoredRelaxedCooldown.filterNot { it.songKey in alreadyKeys }
            .filter { cand ->
                when (mode) {
                    RecommendationMode.DISCOVERY -> !cand.isFamiliarRediscovery && !cand.isReducedLanguageProbe
                    else -> !cand.isReducedLanguageProbe
                }
            }
            .sortedByDescending { it.score }

        val merged = ArrayList<Song>(primaryPicked)
        for (cand in extraPool) {
            if (merged.size >= size) break
            if (merged.none { isSameMusicTrack(it, cand.song) }) {
                merged.add(cand.song)
            }
        }
        return merged.take(size)
    }

    private fun assembleByModeQuotas(
        pool: List<ScoredCandidate>,
        mode: RecommendationMode,
        targetSize: Int,
        snapshot: TasteScoringSnapshot,
        random: Random
    ): List<Song> {
        if (pool.isEmpty()) return emptyList()

        val familiarBucket = pool
            .filter { it.isFamiliarRediscovery && !it.isReducedLanguageProbe }
            .sortedByDescending { it.score }
        val precisionBucket = pool
            .filter {
                !it.isFamiliarRediscovery && !it.isReducedLanguageProbe &&
                    (it.source in setOf(
                        CandidateSource.AI_CF_SEED,
                        CandidateSource.PERSONAL_FM,
                        CandidateSource.YOUTH_PERSONAL,
                        CandidateSource.ARTIST_DEEP_CUT
                    ) || it.isHabitualArtist)
            }
            .sortedByDescending { it.score }
        val freshBucket = pool
            .filter { !it.isFamiliarRediscovery && !it.isReducedLanguageProbe }
            .sortedByDescending { it.score }
        val discoveryStrictFreshBucket = freshBucket.filterNot { it.isHabitualArtist }
        val probeBucket = pool
            .filter { it.isReducedLanguageProbe && !it.isFamiliarRediscovery && !it.isHabitualArtist }
            .sortedByDescending { it.score }

        val chosenKeys = HashSet<String>()
        val artistCount = HashMap<String, Int>()
        val maxPerArtist = if (mode == RecommendationMode.DISCOVERY) 1 else 2

        fun canTakeArtist(song: Song, relaxArtistCap: Boolean): Boolean {
            val artists = PersonalTasteProfileRepository.splitNormalizedArtists(song.artist)
                .map { it.lowercase(Locale.ROOT) }
            if (artists.isEmpty()) return true
            val limit = if (relaxArtistCap) maxPerArtist + 1 else maxPerArtist
            return artists.all { (artistCount[it] ?: 0) < limit }
        }

        fun markTaken(cand: ScoredCandidate) {
            chosenKeys.add(cand.songKey)
            PersonalTasteProfileRepository.splitNormalizedArtists(cand.song.artist)
                .map { it.lowercase(Locale.ROOT) }
                .forEach { a -> artistCount[a] = (artistCount[a] ?: 0) + 1 }
        }

        fun pickFrom(
            candidates: List<ScoredCandidate>,
            quota: Int,
            relaxArtistCap: Boolean = false
        ): List<ScoredCandidate> {
            if (quota <= 0 || candidates.isEmpty()) return emptyList()
            val picked = ArrayList<ScoredCandidate>(quota)
            for (c in candidates) {
                if (picked.size >= quota) break
                if (c.songKey in chosenKeys) continue
                if (!canTakeArtist(c.song, relaxArtistCap)) continue
                markTaken(c)
                picked.add(c)
            }
            return picked
        }

        return when (mode) {
            RecommendationMode.FAMILIAR -> {
                val qFam = (targetSize * 0.35).roundToInt().coerceAtLeast(1)
                val qExp = (targetSize * 0.05).roundToInt().coerceAtMost(1)
                val qPrec = (targetSize - qFam - qExp).coerceAtLeast(1)

                val famList = pickFrom(familiarBucket, qFam)
                val precList = pickFrom(precisionBucket, qPrec)
                val expList = pickFrom(freshBucket.filterNot { it.isHabitualArtist }, qExp)
                val combined = interleaveWithSpacing(
                    primary = precList,
                    secondary = famList,
                    tertiary = expList,
                    minArtistGap = 3
                ).toMutableList()

                if (combined.size < targetSize) {
                    val remaining = targetSize - combined.size
                    val fill = pickFrom(precisionBucket + familiarBucket + freshBucket, remaining, relaxArtistCap = true)
                    combined.addAll(fill.map { it.song })
                }
                combined.take(targetSize)
            }

            RecommendationMode.BALANCED -> {
                val qFam = (targetSize * 0.18).roundToInt().coerceIn(0, 4)
                val qExp = (targetSize * 0.18).roundToInt().coerceIn(1, 4)
                val qPrec = (targetSize - qFam - qExp).coerceAtLeast(1)

                val famList = pickFrom(familiarBucket, qFam)
                val precList = pickFrom(precisionBucket, qPrec)
                val expList = pickFrom(discoveryStrictFreshBucket.ifEmpty { freshBucket }, qExp)
                val combined = interleaveWithSpacing(
                    primary = precList,
                    secondary = famList,
                    tertiary = expList,
                    minArtistGap = 4
                ).toMutableList()

                if (combined.size < targetSize) {
                    val remaining = targetSize - combined.size
                    val fill = pickFrom(freshBucket + familiarBucket, remaining, relaxArtistCap = true)
                    combined.addAll(fill.map { it.song })
                }
                combined.take(targetSize)
            }

            RecommendationMode.DISCOVERY -> {
                // 尝鲜模式：0% 熟歌 & 避开常听歌手；85%~90% 偏好/默认语种内新流派探索 + 10%~15%（最多 1~2 首）可收敛降权语种探针
                val maxProbeSlots = ceil(targetSize * 0.10).toInt().coerceIn(0, 2)
                val eligibleProbes = probeBucket.filter { cand ->
                    val streak = snapshot.probeRejectStreaks[cand.meta.languageCode] ?: 0
                    if (streak >= 3) {
                        false
                    } else {
                        val mult = PersonalTasteProfileRepository.computeProbeMultiplier(streak)
                        random.nextDouble() <= mult
                    }
                }
                val probeList = pickFrom(eligibleProbes, maxProbeSlots)
                val freshQuota = (targetSize - probeList.size).coerceAtLeast(1)
                val mainFresh = pickFrom(discoveryStrictFreshBucket, freshQuota)

                // 把受控探针自然穿插到中后段（而非首曲突兀出现）
                val ordered = ArrayList<ScoredCandidate>(mainFresh)
                probeList.forEachIndexed { idx, probe ->
                    val insertPos = ((ordered.size / (probeList.size + 1)) * (idx + 1) + 2)
                        .coerceIn(0, ordered.size)
                    ordered.add(insertPos, probe)
                }

                val diversified = enforceArtistSpacing(ordered, minGap = 5).toMutableList()
                if (diversified.size < targetSize) {
                    val fill = pickFrom(discoveryStrictFreshBucket, targetSize - diversified.size, relaxArtistCap = false)
                    diversified.addAll(fill.map { it.song })
                }
                if (diversified.size < targetSize) {
                    val fallbackNonFam = pickFrom(freshBucket, targetSize - diversified.size, relaxArtistCap = true)
                    diversified.addAll(fallbackNonFam.map { it.song })
                }
                diversified.take(targetSize)
            }
        }
    }

    private fun interleaveWithSpacing(
        primary: List<ScoredCandidate>,
        secondary: List<ScoredCandidate>,
        tertiary: List<ScoredCandidate>,
        minArtistGap: Int
    ): List<Song> {
        val mixed = ArrayList<ScoredCandidate>(primary.size + secondary.size + tertiary.size)
        val pQueue = ArrayDeque(primary)
        val sQueue = ArrayDeque(secondary)
        val tQueue = ArrayDeque(tertiary)
        var step = 0
        while (pQueue.isNotEmpty() || sQueue.isNotEmpty() || tQueue.isNotEmpty()) {
            when (step % 4) {
                0, 2 -> {
                    pQueue.removeFirstOrNull()?.let(mixed::add)
                        ?: sQueue.removeFirstOrNull()?.let(mixed::add)
                        ?: tQueue.removeFirstOrNull()?.let(mixed::add)
                }
                1 -> {
                    sQueue.removeFirstOrNull()?.let(mixed::add)
                        ?: pQueue.removeFirstOrNull()?.let(mixed::add)
                        ?: tQueue.removeFirstOrNull()?.let(mixed::add)
                }
                else -> {
                    tQueue.removeFirstOrNull()?.let(mixed::add)
                        ?: pQueue.removeFirstOrNull()?.let(mixed::add)
                        ?: sQueue.removeFirstOrNull()?.let(mixed::add)
                }
            }
            step++
        }
        return enforceArtistSpacing(mixed, minGap = minArtistGap)
    }

    private fun enforceArtistSpacing(
        candidates: List<ScoredCandidate>,
        minGap: Int
    ): List<Song> {
        if (candidates.size <= 2) return candidates.map { it.song }
        val remaining = candidates.toMutableList()
        val result = ArrayList<ScoredCandidate>(candidates.size)
        while (remaining.isNotEmpty()) {
            val recentWindow = result.takeLast(minGap)
            val recentArtists = recentWindow
                .flatMap { PersonalTasteProfileRepository.splitNormalizedArtists(it.song.artist) }
                .map { it.lowercase(Locale.ROOT) }
                .toHashSet()
            val prevCover = result.lastOrNull()?.song?.coverUrl.orEmpty()
            val pickIdx = remaining.indexOfFirst { cand ->
                val cArtists = PersonalTasteProfileRepository.splitNormalizedArtists(cand.song.artist)
                    .map { it.lowercase(Locale.ROOT) }
                val artistOk = cArtists.none { it in recentArtists }
                val coverOk = prevCover.isBlank() || cand.song.coverUrl != prevCover
                artistOk && coverOk
            }.takeIf { it >= 0 } ?: remaining.indexOfFirst { cand ->
                val cArtists = PersonalTasteProfileRepository.splitNormalizedArtists(cand.song.artist)
                    .map { it.lowercase(Locale.ROOT) }
                cArtists.none { it in recentArtists }
            }.takeIf { it >= 0 } ?: 0
            result.add(remaining.removeAt(pickIdx))
        }
        return result.map { it.song }
    }

    private fun resolveEverydayStyleTagArg(
        mode: RecommendationMode,
        preference: StructuredListeningPreference,
        batchIdx: Int
    ): String {
        val pureMusicBlocked = preference.weightFor(SongLangTag.INSTRUMENTAL) <= 0
        val djBlocked = preference.weightFor(SongLangTag.DJ) <= 0
        if (mode == RecommendationMode.DISCOVERY) {
            val allowedGroups = discoveryStyleTagGroups.filterNot { g ->
                (g == "26" && pureMusicBlocked) || (g == "15" && djBlocked)
            }.ifEmpty { listOf("9", "14", "12", "11") }
            val g1 = allowedGroups[batchIdx % allowedGroups.size]
            val g2 = allowedGroups[(batchIdx + 2) % allowedGroups.size]
            return if (g1 == g2) g1 else "$g1,$g2"
        }
        val userStyleTags = preference.enabledStyleIds
            .mapNotNull { StylePrefTag.fromId(it)?.everydayTagId?.takeIf { id -> id.isNotBlank() } }
            .filterNot { (it == "26" && pureMusicBlocked) || (it == "15" && djBlocked) }
            .distinct()
        return if (userStyleTags.isNotEmpty()) {
            userStyleTags.take(2).joinToString(",")
        } else ""
    }

    private suspend fun recallArtistDeepCuts(
        seeds: List<Song>,
        batchIdx: Int,
        musicRepo: MusicApiRepository
    ): List<Song> = coroutineScope {
        val artists = seeds
            .flatMap { s ->
                PersonalTasteProfileRepository.splitNormalizedArtists(s.artist).map { it to s.artistId }
            }
            .distinctBy { it.first.lowercase(Locale.ROOT) }
        if (artists.isEmpty()) return@coroutineScope emptyList()
        val chosen = (0 until minOf(3, artists.size)).map { i ->
            artists[(batchIdx * 3 + i) % artists.size]
        }
        val deepPage = (batchIdx % 3) + 2
        val lists = chosen.map { (name, artistId) ->
            async {
                val byId = if (artistId.isNotBlank() && artistId != "0") {
                    runCatching {
                        musicRepo.artistSongs(artistId, sort = if (batchIdx % 2 == 0) "new" else "hot", page = deepPage, pageSize = 15)
                    }.getOrDefault(emptyList())
                } else emptyList()
                if (byId.isNotEmpty()) byId
                else runCatching { musicRepo.searchSongs(name, page = deepPage, pageSize = 15).songs }.getOrDefault(emptyList())
            }
        }.map { it.await() }
        val merged = mutableListOf<Song>()
        val maxLen = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) {
            for (l in lists) if (i < l.size) merged.add(l[i])
        }
        merged
    }

    /**
     * 「今日每日推荐」专属端侧硬屏蔽过滤器：
     * 仅剔除权重 = 0（屏蔽）的语种/内容形态与黑名单歌曲/歌手；绝不改写历史日推快照，且提供非空兜底。
     */
    suspend fun filterDailyRecommendHardBlocked(
        songs: List<Song>,
        userId: String?,
        musicRepo: MusicApiRepository?,
        preferenceRepo: RecommendationPreferenceRepository,
        tagCache: SongTagMetadataCache
    ): List<Song> {
        if (songs.isEmpty()) return songs
        val pref = preferenceRepo.currentPreference(userId)
        val hasAnyHardBlock = pref.blockedCodes().isNotEmpty() ||
            pref.inactiveSingers.isNotEmpty() ||
            preferenceRepo.uiState.value.totalBlacklistCount > 0 ||
            LocalBlacklistFilter.hasAnyEntries()
        if (!hasAnyHardBlock) return songs

        val metaMap = if (musicRepo != null) {
            runCatching {
                tagCache.enrichAndResolveBatch(songs, musicRepo)
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        val filtered = songs.filterNot { song ->
            val meta = metaMap[tagCache.songPrimaryKey(song)] ?: tagCache.resolveFast(song)
            preferenceRepo.isHardBlocked(song, meta, pref)
        }
        return filtered.ifEmpty { songs }
    }
}
