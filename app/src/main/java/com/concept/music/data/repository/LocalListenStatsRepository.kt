package com.concept.music.data.repository

import android.content.Context
import com.concept.music.data.kugou.KugouSigner
import com.concept.music.data.model.ArtistListenRankItem
import com.concept.music.data.model.ListenRankItem
import com.concept.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 微风本地单次有效收听事件（预留 deviceId / syncSource / eventId 以备未来跨设备续播合并记录）
 */
data class LocalListenEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val songKey: String = "",
    val timestampMs: Long = 0L,
    val playedMs: Long = 0L,
    val deviceId: String = "local_android",
    val syncSource: String = "local"
)

/**
 * 单首曲目的历史累计统计与最新元数据快照
 */
data class LocalListenTrackRecord(
    val songKey: String = "",
    val song: Song = Song(id = "", title = "", artist = ""),
    val totalPlayCount: Int = 0,
    val totalPlayedMs: Long = 0L,
    val lastPlayedAtMs: Long = 0L
)

/**
 * 账号级微风本地听歌统计存储结构
 */
data class LocalListenStatsStore(
    val version: Int = LocalListenStatsRepository.CURRENT_SCHEMA_VERSION,
    val deviceId: String = "local_android",
    val tracks: MutableMap<String, LocalListenTrackRecord> = LinkedHashMap(),
    val events: MutableList<LocalListenEvent> = ArrayList()
)

/**
 * 按周期（0=近一周 / 1=近一月 / 2=总排行）聚合后的听歌排行快照
 */
data class LocalListenRankSnapshot(
    val period: Int,
    val songs: List<ListenRankItem>,
    val artists: List<ArtistListenRankItem>,
    val totalPlayCount: Int,
    val totalPlayedMs: Long
)

/**
 * 微风本机听歌排行与收听记录仓库：
 * - 完全基于微风客户端真实播放行为本地统计，不依赖酷狗云端听歌排行接口，未登录亦可直接查看；
 * - 永久保留累计曲目总次数/总时长（支撑「总排行」），同时维护近 35 天明细事件日志（精准支撑「近一周 / 近一月」）；
 * - 模型内预留 `deviceId`、`syncSource`、`eventId` 字段，便于日后打通跨设备续播后统一微风播放记录。
 */
open class LocalListenStatsRepository(
    private val context: Context?,
    private val customStoreDir: File? = null
) {
    companion object {
        const val PERIOD_WEEK = 0
        const val PERIOD_MONTH = 1
        const val PERIOD_ALL = 2
        const val CURRENT_SCHEMA_VERSION = 2

        private const val DAY_MS = 86_400_000L
        private const val EVENT_RETENTION_MS = 35L * DAY_MS
        private const val MAX_RETAINED_EVENTS = 12_000

        // v1.4.1 旧版 CSCC 60s 切段重复事件特征阈值（用于 v1 -> v2 一次性无损去重迁移）
        private const val V1_DUP_EVENT_MIN_PLAYED_MS = 14_000L
        private const val V1_DUP_EVENT_MAX_PLAYED_MS = 25_000L
        private const val V1_CSCC_MIN_GAP_MS = 50_000L
        private const val V1_CSCC_MAX_GAP_MS = 90_000L
        private const val V1_CSCC_MIN_TRACK_DURATION_MS = 80_000L
        private const val V1_UNKNOWN_DURATION_MAX_SPAN_MS = 300_000L
        private const val V1_UNKNOWN_DURATION_MAX_CLUSTER = 5
    }

    private val storeDir: File? by lazy {
        customStoreDir ?: try {
            context?.applicationContext?.filesDir?.resolve("local_listen_stats")?.apply {
                if (!exists()) mkdirs()
            }
        } catch (_: Exception) {
            null
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val memoryStores = ConcurrentHashMap<String, LocalListenStatsStore>()

    private val _statsVersion = MutableStateFlow(0L)
    val statsVersion: StateFlow<Long> = _statsVersion.asStateFlow()

    private fun userStoreKey(userId: String?): String =
        "stats_${userId?.trim()?.takeIf { it.isNotBlank() } ?: "guest"}"

    private fun storeFileFor(key: String): File? {
        val dir = storeDir ?: return null
        if (!dir.exists()) runCatching { dir.mkdirs() }
        val safeName = key.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        return File(dir, "$safeName.json")
    }

    private fun normalizeSongKey(song: Song): String {
        val h = listOf(song.hash, song.hashHiRes, song.hashFlac, song.hash320, song.hash128)
            .map { it.trim().lowercase(Locale.ROOT) }
            .firstOrNull { it.length == 32 }
        if (!h.isNullOrBlank()) return "h:$h"
        if (song.albumAudioId > 0L) return "aa:${song.albumAudioId}"
        if (song.audioId > 0L) return "a:${song.audioId}"
        if (song.id.isNotBlank()) return "id:${song.id.trim().lowercase(Locale.ROOT)}"
        return "ta:${song.title.trim().lowercase(Locale.ROOT)}|${song.artist.trim().lowercase(Locale.ROOT)}"
    }

    @Synchronized
    private fun loadStore(userId: String?): LocalListenStatsStore {
        val key = userStoreKey(userId)
        memoryStores[key]?.let { return it }
        var needsPersistAfterMigration = false
        val loaded = runCatching {
            val file = storeFileFor(key)
            if (file != null && file.exists()) {
                val raw = file.readText(Charsets.UTF_8)
                val parsed = KugouSigner.gson.fromJson(raw, LocalListenStatsStore::class.java)
                if (parsed != null) {
                    val safeTracks = LinkedHashMap<String, LocalListenTrackRecord>()
                    @Suppress("USELESS_ELVIS")
                    (parsed.tracks ?: emptyMap()).forEach { (k, rec) ->
                        if (k.isNotBlank() && rec != null) {
                            safeTracks[k] = rec.copy(
                                songKey = rec.songKey.ifBlank { k },
                                song = rec.song.sanitizeFromJson()
                            )
                        }
                    }
                    @Suppress("USELESS_ELVIS")
                    val safeEvents = (parsed.events ?: emptyList())
                        .filterNotNull()
                        .filter { it.songKey.isNotBlank() && it.timestampMs > 0L }
                        .toMutableList()
                    val baseStore = LocalListenStatsStore(
                        version = parsed.version.coerceAtLeast(1),
                        deviceId = parsed.deviceId.ifBlank { "local_android" },
                        tracks = safeTracks,
                        events = safeEvents
                    )
                    if (baseStore.version < CURRENT_SCHEMA_VERSION) {
                        needsPersistAfterMigration = true
                        migrateV1ToV2(baseStore)
                    } else {
                        baseStore
                    }
                } else LocalListenStatsStore()
            } else {
                LocalListenStatsStore()
            }
        }.getOrDefault(LocalListenStatsStore())
        memoryStores[key] = loaded
        if (needsPersistAfterMigration) {
            persistStoreAsync(key, KugouSigner.gson.toJson(loaded))
        }
        return loaded
    }

    /**
     * 一次性无损迁移（v1 -> v2）：
     * 针对 v1.4.1 中「酷狗 CSCC 60s 检查点反复重置 15s 计次标志」导致同一首歌单次播放产生 4~5 条
     * 相邻间隔 50~90 秒、单次时长约 15~25 秒连续异常事件进行智能归并，校准播放次数与收听时长。
     */
    private fun migrateV1ToV2(v1Store: LocalListenStatsStore): LocalListenStatsStore {
        if (v1Store.events.isEmpty()) {
            return v1Store.copy(version = CURRENT_SCHEMA_VERSION)
        }

        val eventsBySong = LinkedHashMap<String, MutableList<LocalListenEvent>>()
        v1Store.events.forEach { ev ->
            eventsBySong.getOrPut(ev.songKey) { mutableListOf() }.add(ev)
        }

        val migratedTracks = LinkedHashMap(v1Store.tracks)
        val mergedAllEvents = ArrayList<LocalListenEvent>(v1Store.events.size)

        eventsBySong.forEach { (songKey, rawList) ->
            val sorted = rawList.sortedBy { it.timestampMs }
            val track = migratedTracks[songKey]
            val durationMs = track?.song?.durationMs ?: 0L
            // v1.4.1 中 CSCC 60s 切段仅在 played >= 60s 且 remain >= 20s 时触发，曲长 < 80s 不会触发切段重记
            val canHaveCsccDuplicate = durationMs <= 0L || durationMs >= V1_CSCC_MIN_TRACK_DURATION_MS
            val maxClusterSpanMs = if (durationMs > 0L) {
                (durationMs - 10_000L).coerceAtLeast(60_000L)
            } else {
                V1_UNKNOWN_DURATION_MAX_SPAN_MS
            }

            val songMergedEvents = ArrayList<LocalListenEvent>(sorted.size)
            var removedPlayCount = 0
            var removedRawPlayedMs = 0L
            var addedCalibratedMs = 0L

            var i = 0
            while (i < sorted.size) {
                val first = sorted[i]
                val isFirstCandidate = canHaveCsccDuplicate &&
                    first.playedMs in V1_DUP_EVENT_MIN_PLAYED_MS..V1_DUP_EVENT_MAX_PLAYED_MS

                if (!isFirstCandidate) {
                    songMergedEvents.add(first)
                    i++
                    continue
                }

                val cluster = mutableListOf(first)
                var j = i + 1
                while (j < sorted.size) {
                    val prev = cluster.last()
                    val cand = sorted[j]
                    val gap = cand.timestampMs - prev.timestampMs
                    val span = cand.timestampMs - first.timestampMs
                    val isCandDuplicate = cand.playedMs in V1_DUP_EVENT_MIN_PLAYED_MS..V1_DUP_EVENT_MAX_PLAYED_MS &&
                        gap in V1_CSCC_MIN_GAP_MS..V1_CSCC_MAX_GAP_MS &&
                        span <= maxClusterSpanMs &&
                        (durationMs > 0L || cluster.size < V1_UNKNOWN_DURATION_MAX_CLUSTER)
                    if (isCandDuplicate) {
                        cluster.add(cand)
                        j++
                    } else {
                        break
                    }
                }

                if (cluster.size == 1) {
                    songMergedEvents.add(first)
                } else {
                    val sumRawMs = cluster.sumOf { it.playedMs.coerceAtLeast(1_000L) }
                    val rawEstimate = (cluster.last().timestampMs - first.timestampMs) + 30_000L
                    val calibratedMs = if (durationMs > 0L) {
                        rawEstimate.coerceIn(minOf(sumRawMs, durationMs), durationMs)
                    } else {
                        rawEstimate.coerceAtLeast(sumRawMs).coerceAtMost(360_000L)
                    }
                    songMergedEvents.add(
                        first.copy(
                            playedMs = calibratedMs
                        )
                    )
                    removedPlayCount += (cluster.size - 1)
                    removedRawPlayedMs += sumRawMs
                    addedCalibratedMs += calibratedMs
                }
                i = j
            }

            mergedAllEvents.addAll(songMergedEvents)

            if (track != null && removedPlayCount > 0) {
                val minEventCount = songMergedEvents.size
                val minEventPlayedMs = songMergedEvents.sumOf { it.playedMs.coerceAtLeast(1_000L) }
                val nextTotalCount = (track.totalPlayCount - removedPlayCount).coerceAtLeast(minEventCount)
                val nextTotalPlayedMs = (track.totalPlayedMs - removedRawPlayedMs + addedCalibratedMs)
                    .coerceAtLeast(minEventPlayedMs)
                migratedTracks[songKey] = track.copy(
                    totalPlayCount = nextTotalCount,
                    totalPlayedMs = nextTotalPlayedMs
                )
            }
        }

        mergedAllEvents.sortBy { it.timestampMs }
        return LocalListenStatsStore(
            version = CURRENT_SCHEMA_VERSION,
            deviceId = v1Store.deviceId,
            tracks = migratedTracks,
            events = mergedAllEvents
        )
    }

    private fun persistStoreAsync(key: String, snapshotJson: String) {
        if (storeDir == null) return
        ioScope.launch {
            runCatching {
                val target = storeFileFor(key) ?: return@launch
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeText(snapshotJson, Charsets.UTF_8)
                if (!tmp.renameTo(target)) {
                    target.writeText(snapshotJson, Charsets.UTF_8)
                    runCatching { tmp.delete() }
                }
            }
        }
    }

    /**
     * 记录一次有效收听（由独立进程级状态机 `ListenSessionTracker` 在单曲满足有效收听门槛时调用），
     * 返回唯一 `eventId` 供同一会话在继续播完/切歌/暂停时增量回补剩余收听时长（绝不重复加次数）。
     */
    @Synchronized
    open fun recordPlay(
        userId: String?,
        song: Song,
        playedMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        deviceId: String = "local_android",
        syncSource: String = "local"
    ): String? {
        if (song.title.isBlank() && song.hash.isBlank() && song.id.isBlank()) return null
        val key = userStoreKey(userId)
        val store = loadStore(userId)
        val safePlayedMs = playedMs.coerceAtLeast(1_000L)
        val sanitizedSong = song.sanitizeFromJson()

        // 优先匹配同一首歌的已有 key（跨音质档位/不同歌单来源合并计次）
        val directKey = normalizeSongKey(sanitizedSong)
        val effectiveKey = when {
            store.tracks.containsKey(directKey) -> directKey
            else -> store.tracks.entries.firstOrNull { isSameMusicTrack(it.value.song, sanitizedSong) }?.key ?: directKey
        }

        val prev = store.tracks[effectiveKey]
        val mergedSong = if (prev != null) mergeTrackHigherQuality(sanitizedSong, prev.song) else sanitizedSong
        store.tracks[effectiveKey] = LocalListenTrackRecord(
            songKey = effectiveKey,
            song = mergedSong,
            totalPlayCount = (prev?.totalPlayCount ?: 0) + 1,
            totalPlayedMs = (prev?.totalPlayedMs ?: 0L) + safePlayedMs,
            lastPlayedAtMs = maxOf(prev?.lastPlayedAtMs ?: 0L, nowMs)
        )

        val eventId = UUID.randomUUID().toString()
        store.events.add(
            LocalListenEvent(
                eventId = eventId,
                songKey = effectiveKey,
                timestampMs = nowMs,
                playedMs = safePlayedMs,
                deviceId = deviceId.ifBlank { "local_android" },
                syncSource = syncSource.ifBlank { "local" }
            )
        )

        // 裁剪超过 35 天明细事件，避免无限膨胀（总榜由 tracks 永久保留）
        val cutoff = nowMs - EVENT_RETENTION_MS
        if (store.events.size > 64) {
            store.events.removeAll { it.timestampMs < cutoff }
        }
        if (store.events.size > MAX_RETAINED_EVENTS) {
            val dropCount = store.events.size - MAX_RETAINED_EVENTS
            store.events.subList(0, dropCount).clear()
        }

        val json = KugouSigner.gson.toJson(store)
        persistStoreAsync(key, json)
        _statsVersion.value = _statsVersion.value + 1L
        return eventId
    }

    /**
     * 向已达标落盘的同一次有效收听事件增量回补后半段真实收听时长（只累加时长，绝不增加播放次数）。
     * 若期间用户手动清空了统计导致 eventId 不再存在，则安全忽略（不复活已清空记录）。
     */
    @Synchronized
    open fun appendPlayedDuration(
        userId: String?,
        eventId: String,
        deltaPlayedMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (eventId.isBlank() || deltaPlayedMs <= 0L) return false
        val key = userStoreKey(userId)
        val store = loadStore(userId)
        val idx = store.events.indexOfLast { it.eventId == eventId }
        if (idx < 0) return false

        val existingEvent = store.events[idx]
        store.events[idx] = existingEvent.copy(
            playedMs = existingEvent.playedMs + deltaPlayedMs
        )
        val existingTrack = store.tracks[existingEvent.songKey]
        if (existingTrack != null) {
            store.tracks[existingEvent.songKey] = existingTrack.copy(
                totalPlayedMs = existingTrack.totalPlayedMs + deltaPlayedMs,
                lastPlayedAtMs = maxOf(existingTrack.lastPlayedAtMs, nowMs)
            )
        }

        val json = KugouSigner.gson.toJson(store)
        persistStoreAsync(key, json)
        _statsVersion.value = _statsVersion.value + 1L
        return true
    }

    /**
     * 查询指定周期（近一周 / 近一月 / 总排行）的本机听歌统计快照
     */
    @Synchronized
    open fun queryRank(
        userId: String?,
        period: Int,
        nowMs: Long = System.currentTimeMillis()
    ): LocalListenRankSnapshot {
        val store = loadStore(userId)

        data class TrackAcc(
            val song: Song,
            var count: Int = 0,
            var playedMs: Long = 0L,
            var lastPlayedAtMs: Long = 0L
        )

        val accMap = LinkedHashMap<String, TrackAcc>()
        if (period == PERIOD_ALL) {
            store.tracks.values.forEach { rec ->
                if (rec.totalPlayCount > 0) {
                    accMap[rec.songKey] = TrackAcc(
                        song = rec.song,
                        count = rec.totalPlayCount,
                        playedMs = rec.totalPlayedMs,
                        lastPlayedAtMs = rec.lastPlayedAtMs
                    )
                }
            }
        } else {
            val windowMs = if (period == PERIOD_WEEK) 7L * DAY_MS else 30L * DAY_MS
            val minTs = nowMs - windowMs
            store.events.forEach { ev ->
                if (ev.timestampMs >= minTs) {
                    val rec = store.tracks[ev.songKey] ?: return@forEach
                    val acc = accMap.getOrPut(ev.songKey) {
                        TrackAcc(song = rec.song, lastPlayedAtMs = ev.timestampMs)
                    }
                    acc.count += 1
                    acc.playedMs += ev.playedMs.coerceAtLeast(1_000L)
                    if (ev.timestampMs > acc.lastPlayedAtMs) {
                        acc.lastPlayedAtMs = ev.timestampMs
                    }
                }
            }
        }

        val sortedTracks = accMap.values
            .sortedWith(
                compareByDescending<TrackAcc> { it.count }
                    .thenByDescending { it.playedMs }
                    .thenByDescending { it.lastPlayedAtMs }
            )

        val songRanks = sortedTracks.mapIndexed { idx, acc ->
            ListenRankItem(
                rank = idx + 1,
                song = acc.song,
                playCount = acc.count,
                playedMs = acc.playedMs,
                lastPlayedAtMs = acc.lastPlayedAtMs
            )
        }

        val artistRanks = aggregateArtistRanks(songRanks)
        val totalCount = sortedTracks.sumOf { it.count }
        val totalMs = sortedTracks.sumOf { it.playedMs }

        return LocalListenRankSnapshot(
            period = period,
            songs = songRanks,
            artists = artistRanks,
            totalPlayCount = totalCount,
            totalPlayedMs = totalMs
        )
    }

    @Synchronized
    open fun getTotalPlayCount(userId: String?): Int {
        val store = loadStore(userId)
        return store.tracks.values.sumOf { it.totalPlayCount }
    }

    @Synchronized
    open fun clear(userId: String?) {
        val key = userStoreKey(userId)
        val empty = LocalListenStatsStore()
        memoryStores[key] = empty
        persistStoreAsync(key, KugouSigner.gson.toJson(empty))
        _statsVersion.value = _statsVersion.value + 1L
    }

    private fun aggregateArtistRanks(items: List<ListenRankItem>): List<ArtistListenRankItem> {
        if (items.isEmpty()) return emptyList()

        data class ArtistAcc(
            val name: String,
            var artistId: String = "",
            var avatarUrl: String = "",
            var totalPlayCount: Int = 0,
            var totalPlayedMs: Long = 0L,
            var topSongTitle: String = "",
            var topSongCount: Int = -1,
            val songs: MutableList<Song> = mutableListOf(),
            val seenSongKeys: MutableSet<String> = mutableSetOf()
        )

        val map = LinkedHashMap<String, ArtistAcc>()
        for (item in items) {
            val song = item.song
            val count = item.playCount.coerceAtLeast(1)
            val rawArtists = song.artist
                .split("、", "/", "&", ",", "，", "feat.", "Feat.", "FT.")
                .map { it.trim() }
                .filter { it.isNotBlank() && it != "未知歌手" && it != "群星" && it != "云盘曲目" }
                .ifEmpty { listOf(song.artist.trim().ifBlank { "未知歌手" }) }
                .distinct()

            rawArtists.forEachIndexed { idx, name ->
                val normKey = name.lowercase(Locale.ROOT)
                val acc = map.getOrPut(normKey) { ArtistAcc(name = name) }
                if (idx == 0 && acc.artistId.isBlank() && song.artistId.isNotBlank() && song.artistId != "0") {
                    acc.artistId = song.artistId
                }
                if (acc.avatarUrl.isBlank() && song.coverUrl.isNotBlank()) {
                    acc.avatarUrl = song.coverUrl
                }
                acc.totalPlayCount += count
                acc.totalPlayedMs += item.playedMs
                if (count > acc.topSongCount && song.title.isNotBlank()) {
                    acc.topSongCount = count
                    acc.topSongTitle = song.title
                }
                val sKey = song.hash.ifBlank { song.id }.lowercase(Locale.ROOT)
                if (acc.seenSongKeys.add(sKey)) {
                    acc.songs.add(song)
                }
            }
        }

        return map.values
            .sortedWith(
                compareByDescending<ArtistAcc> { it.totalPlayCount }
                    .thenByDescending { it.totalPlayedMs }
                    .thenByDescending { it.songs.size }
            )
            .mapIndexed { idx, acc ->
                ArtistListenRankItem(
                    rank = idx + 1,
                    artistName = acc.name,
                    artistId = acc.artistId,
                    avatarUrl = acc.avatarUrl,
                    totalPlayCount = acc.totalPlayCount,
                    trackCount = acc.songs.size,
                    topSongTitle = acc.topSongTitle,
                    songs = acc.songs.toList(),
                    totalPlayedMs = acc.totalPlayedMs
                )
            }
    }
}
