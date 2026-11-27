package com.concept.music.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.concept.music.data.kugou.KugouSigner
import com.concept.music.data.model.AudioQuality
import com.concept.music.data.model.CloudDiskSummary
import com.concept.music.data.model.FmChannel
import com.concept.music.data.model.Playlist
import com.concept.music.data.model.PurchasedAlbumItem
import com.concept.music.data.model.PurchasedSongItem
import com.concept.music.data.model.RankItem
import com.concept.music.data.model.Song
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 判断两首 Song 是否代表同一首歌曲（跨音质档位、跨接口来源去重）
 */
fun isSameMusicTrack(a: Song, b: Song): Boolean {
    if (a.id.isNotBlank() && b.id.isNotBlank() && a.id.equals(b.id, ignoreCase = true)) return true
    if (a.audioId > 0L && b.audioId > 0L && a.audioId == b.audioId) return true
    if (a.albumAudioId > 0L && b.albumAudioId > 0L && a.albumAudioId == b.albumAudioId) return true
    val aHashes = listOf(a.hash, a.hash128, a.hash320, a.hashFlac, a.hashHiRes)
        .map { it.trim().lowercase() }
        .filter { it.length == 32 }
    val bHashes = listOf(b.hash, b.hash128, b.hash320, b.hashFlac, b.hashHiRes)
        .map { it.trim().lowercase() }
        .filter { it.length == 32 }
    if (aHashes.isNotEmpty() && bHashes.isNotEmpty() && aHashes.any { it in bHashes }) return true
    val normTitleA = a.title.trim().lowercase()
    val normTitleB = b.title.trim().lowercase()
    val normArtistA = a.artist.trim().lowercase()
    val normArtistB = b.artist.trim().lowercase()
    if (normTitleA.isNotBlank() && normTitleA == normTitleB && normArtistA.isNotBlank() && normArtistA == normArtistB) {
        val normAlbumA = a.album.trim().lowercase()
        val normAlbumB = b.album.trim().lowercase()
        if (normAlbumA.isBlank() || normAlbumB.isBlank() || normAlbumA == normAlbumB) return true
    }
    return false
}

/**
 * 合并同一首歌曲在两个不同来源下的最高音质与最全元数据（避免同一首歌同时出现 Hi-Res 与 SQ 两条记录）
 */
fun mergeTrackHigherQuality(preferredOrderSong: Song, otherSong: Song): Song {
    val bestHiResHash = preferredOrderSong.hashHiRes.ifBlank { otherSong.hashHiRes }
    val bestFlacHash = preferredOrderSong.hashFlac.ifBlank { otherSong.hashFlac }
    val best320Hash = preferredOrderSong.hash320.ifBlank { otherSong.hash320 }
    val best128Hash = preferredOrderSong.hash128.ifBlank { otherSong.hash128 }
    val hasHiRes = preferredOrderSong.isHiRes || otherSong.isHiRes || bestHiResHash.isNotBlank()
    val hasFlac = preferredOrderSong.isLossless || otherSong.isLossless || bestFlacHash.isNotBlank() || hasHiRes
    val has320 = best320Hash.isNotBlank() || preferredOrderSong.quality == "320" || otherSong.quality == "320" || hasFlac
    val mergedQualities = buildList {
        add(AudioQuality.STANDARD)
        if (has320) add(AudioQuality.HIGH)
        if (hasFlac) add(AudioQuality.LOSSLESS)
        if (hasHiRes) add(AudioQuality.HI_RES)
    }
    val bestHash = when {
        hasHiRes && bestHiResHash.isNotBlank() -> bestHiResHash
        hasFlac && bestFlacHash.isNotBlank() -> bestFlacHash
        has320 && best320Hash.isNotBlank() -> best320Hash
        best128Hash.isNotBlank() -> best128Hash
        else -> preferredOrderSong.hash.ifBlank { otherSong.hash }
    }
    return preferredOrderSong.copy(
        id = preferredOrderSong.id.ifBlank { otherSong.id },
        hash = bestHash,
        hash128 = best128Hash,
        hash320 = best320Hash,
        hashFlac = bestFlacHash,
        hashHiRes = bestHiResHash,
        album = preferredOrderSong.album.ifBlank { otherSong.album },
        albumId = preferredOrderSong.albumId.ifBlank { otherSong.albumId },
        coverUrl = preferredOrderSong.coverUrl.ifBlank { otherSong.coverUrl },
        durationMs = preferredOrderSong.durationMs.takeIf { it > 0L } ?: otherSong.durationMs,
        isVip = preferredOrderSong.isVip || otherSong.isVip,
        isLossless = hasFlac,
        isHiRes = hasHiRes,
        albumAudioId = preferredOrderSong.albumAudioId.takeIf { it > 0L } ?: otherSong.albumAudioId,
        audioId = preferredOrderSong.audioId.takeIf { it > 0L } ?: otherSong.audioId,
        mixSongId = preferredOrderSong.mixSongId.ifBlank { otherSong.mixSongId },
        quality = if (hasHiRes) "high" else if (hasFlac) "flac" else if (has320) "320" else "128",
        availableQualities = mergedQualities,
        bitrateKbps = maxOf(preferredOrderSong.bitrateKbps, otherSong.bitrateKbps),
        fileSizeByte = maxOf(preferredOrderSong.fileSizeByte, otherSong.fileSizeByte),
        publishDate = preferredOrderSong.publishDate.ifBlank { otherSong.publishDate }
    )
}

/**
 * 对歌曲列表按同一首歌曲进行去重并合并最高音质标识
 */
fun deduplicateAndMergeSongs(songs: List<Song>): List<Song> {
    if (songs.size <= 1) return songs
    val result = mutableListOf<Song>()
    for (candidate in songs) {
        val existingIdx = result.indexOfFirst { isSameMusicTrack(it, candidate) }
        if (existingIdx >= 0) {
            result[existingIdx] = mergeTrackHigherQuality(result[existingIdx], candidate)
        } else {
            result.add(candidate)
        }
    }
    return result
}

open class PlaybackHistoryRepository(private val context: Context?) {
    private val prefs: SharedPreferences? by lazy {
        try {
            context?.applicationContext?.getSharedPreferences("play_history", Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
    }
    private val historyDir: File? by lazy {
        try {
            context?.applicationContext?.filesDir?.resolve("play_history_store")?.apply {
                if (!exists()) mkdirs()
            }
        } catch (_: Exception) {
            null
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val memoryHistory = ConcurrentHashMap<String, List<Song>>()

    private val _historyVersion = MutableStateFlow(0)
    val historyVersion: StateFlow<Int> = _historyVersion.asStateFlow()

    private fun historyFileFor(key: String): File? {
        val dir = historyDir ?: return null
        val safeName = key.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        return File(dir, "$safeName.json")
    }

    open fun get(userId: String?): List<Song> {
        val key = "songs_${userId ?: "guest"}"
        memoryHistory[key]?.let { return it }
        return runCatching {
            val file = historyFileFor(key)
            val raw = when {
                file != null && file.exists() -> file.readText(Charsets.UTF_8)
                else -> prefs?.getString(key, "[]") ?: "[]"
            }
            val parsed = KugouSigner.gson.fromJson<List<Song>>(raw, object : TypeToken<List<Song>>() {}.type) ?: emptyList()
            val deduped = deduplicateAndMergeSongs(parsed)
            memoryHistory[key] = deduped
            deduped
        }.getOrDefault(emptyList())
    }

    private fun persistHistoryAsync(key: String, songs: List<Song>) {
        ioScope.launch {
            runCatching {
                val json = KugouSigner.gson.toJson(songs)
                val file = historyFileFor(key)
                if (file != null) {
                    file.writeText(json, Charsets.UTF_8)
                    prefs?.edit()?.remove(key)?.apply()
                } else {
                    prefs?.edit()?.putString(key, json)?.apply()
                }
            }
        }
    }

    @Synchronized
    open fun add(userId: String?, song: Song) {
        val key = "songs_${userId ?: "guest"}"
        val current = get(userId)
        val matchedExisting = current.firstOrNull { isSameMusicTrack(it, song) }
        val mergedTop = if (matchedExisting != null) mergeTrackHigherQuality(song, matchedExisting) else song
        // 移除 100 首截断上限，完整保留用户全量听歌历史记录
        val updated = listOf(mergedTop) + current.filterNot { isSameMusicTrack(it, song) }
        memoryHistory[key] = updated
        _historyVersion.value += 1
        persistHistoryAsync(key, updated)
    }

    @Synchronized
    open fun saveAll(userId: String?, songs: List<Song>) {
        val key = "songs_${userId ?: "guest"}"
        val deduped = deduplicateAndMergeSongs(songs)
        memoryHistory[key] = deduped
        _historyVersion.value += 1
        persistHistoryAsync(key, deduped)
    }

    @Synchronized
    open fun clear(userId: String?) {
        val key = "songs_${userId ?: "guest"}"
        memoryHistory.remove(key)
        _historyVersion.value += 1
        ioScope.launch {
            runCatching {
                historyFileFor(key)?.takeIf { it.exists() }?.delete()
                prefs?.edit()?.remove(key)?.apply()
            }
        }
    }
}

/**
 * 发现页、歌单、榜单与电台曲目本地持久化秒开缓存 (Stale-While-Revalidate)
 * - 使用单线程串行调度器保证并发落盘严格保序；
 * - 歌单详情缓存拆分为独立 JSON 文件，防止单 SharedPreferences XML 膨胀引发 Activity.onStop() ANR；
 * - 支持 allowEmpty=true 覆盖写入空列表，修复取消最后一条收藏或删除最后一个歌单后重启复活的 Bug。
 */
class DiscoverLocalCache(context: Context? = null) {
    private val appCtx: Context? = try { context?.applicationContext } catch (_: Exception) { null }
    private val prefs: SharedPreferences? by lazy {
        try {
            appCtx?.getSharedPreferences("concept_discover_swr_cache_v3", Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
    }
    private val playlistCacheDir: File? by lazy {
        try {
            appCtx?.cacheDir?.resolve("swr_playlists")?.apply { if (!exists()) mkdirs() }
        } catch (_: Exception) {
            null
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val cacheIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val memorySongListMap: MutableMap<String, List<Song>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<Song>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Song>>?): Boolean = size > 64
        }
    )
    private val memoryRankList = ConcurrentHashMap<String, List<RankItem>>()
    private val memoryFmChannelList = ConcurrentHashMap<String, List<FmChannel>>()
    private val memoryPlaylistList = ConcurrentHashMap<String, List<Playlist>>()
    private val memoryPurchasedAlbumList = ConcurrentHashMap<String, List<PurchasedAlbumItem>>()
    private val memoryPurchasedSongList = ConcurrentHashMap<String, List<PurchasedSongItem>>()
    private val memoryCloudDiskSummary = ConcurrentHashMap<String, CloudDiskSummary>()
    private val memoryDailyHistoryIndex = ConcurrentHashMap<String, List<String>>()

    private val songListType = object : TypeToken<List<Song>>() {}.type
    private val stringListType = object : TypeToken<List<String>>() {}.type
    private val rankListType = object : TypeToken<List<RankItem>>() {}.type
    private val fmChannelListType = object : TypeToken<List<FmChannel>>() {}.type
    private val playlistListType = object : TypeToken<List<Playlist>>() {}.type
    private val purchasedAlbumListType = object : TypeToken<List<PurchasedAlbumItem>>() {}.type
    private val purchasedSongListType = object : TypeToken<List<PurchasedSongItem>>() {}.type

    private fun sanitizeCachedSong(rawSong: Song): Song {
        val s = rawSong.sanitizeFromJson()
        if (!s.isHiRes) return s
        val isVerifiedHiRes = s.hashHiRes.isNotBlank() &&
            !s.hashHiRes.equals(s.hash320, ignoreCase = true) &&
            !s.hashHiRes.equals(s.hash128, ignoreCase = true)
        if (isVerifiedHiRes) return s
        val downgradeFlac = s.hashFlac.ifBlank { s.hashHiRes }
        val cleanedQualities = s.effectiveQualities.filter { it != AudioQuality.HI_RES }.let { q ->
            if (downgradeFlac.isNotBlank() && AudioQuality.LOSSLESS !in q) q + AudioQuality.LOSSLESS else q
        }.ifEmpty { listOf(AudioQuality.STANDARD) }
        return s.copy(
            hashHiRes = "",
            hashFlac = downgradeFlac,
            isHiRes = false,
            isLossless = downgradeFlac.isNotBlank() || s.isLossless,
            quality = if (downgradeFlac.isNotBlank() || s.isLossless) "flac" else "320",
            availableQualities = cleanedQualities
        )
    }

    private fun playlistFileFor(key: String): File? {
        val dir = playlistCacheDir ?: return null
        val safeName = key.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        return File(dir, "$safeName.json")
    }

    private fun getSongList(key: String, useDedicatedFile: Boolean = false): List<Song> {
        memorySongListMap[key]?.let { return it }
        return runCatching {
            val raw = if (useDedicatedFile && playlistCacheDir != null) {
                val f = playlistFileFor(key)
                if (f != null && f.exists()) f.readText(Charsets.UTF_8) else prefs?.getString(key, null)
            } else {
                prefs?.getString(key, null)
            } ?: return emptyList()
            val rawList: List<Song> = KugouSigner.gson.fromJson(raw, songListType) ?: emptyList()
            val list = rawList.map { sanitizeCachedSong(it) }
            memorySongListMap[key] = list
            list
        }.getOrDefault(emptyList())
    }

    private fun saveSongList(
        key: String,
        songs: List<Song>,
        maxPersistCount: Int = Int.MAX_VALUE,
        allowEmpty: Boolean = false,
        useDedicatedFile: Boolean = false
    ) {
        if (songs.isEmpty() && !allowEmpty) return
        val persisted = if (maxPersistCount < songs.size) songs.take(maxPersistCount) else songs
        memorySongListMap[key] = persisted
        cacheIoScope.launch {
            runCatching {
                val json = KugouSigner.gson.toJson(persisted)
                if (useDedicatedFile && playlistCacheDir != null) {
                    val f = playlistFileFor(key)
                    if (f != null) {
                        f.writeText(json, Charsets.UTF_8)
                        prefs?.edit()?.remove(key)?.apply()
                    }
                } else {
                    prefs?.edit()?.putString(key, json)?.apply()
                }
                Unit
            }
        }
    }

    fun getDailyRecommend(userId: String?): List<Song> =
        getSongList("daily_rec_${userId ?: "guest"}")

    fun saveDailyRecommend(userId: String?, songs: List<Song>) =
        saveSongList("daily_rec_${userId ?: "guest"}", songs)

    /**
     * 按账号 + 8位日期 (yyyyMMdd) 归档当日真实日推曲目快照（最多保留最近 10 天，保证含今日在内完整 7 天回溯不断档）
     */
    fun saveDailyRecommendHistorySnapshot(userId: String?, compactDate: String, songs: List<Song>) {
        val cleanDate = compactDate.filter { it.isDigit() }
        if (cleanDate.length != 8 || songs.isEmpty()) return
        val uid = userId?.takeIf { it.isNotBlank() } ?: "guest"
        val snapKey = "daily_rec_hist_${uid}_$cleanDate"
        saveSongList(snapKey, songs, allowEmpty = false, useDedicatedFile = true)

        val idxKey = "daily_rec_hist_dates_$uid"
        val existing = listDailyRecommendHistoryDates(userId).toMutableList()
        existing.remove(cleanDate)
        existing.add(0, cleanDate)
        val sorted = existing.distinct().sortedDescending()
        val kept = sorted.take(10)
        val dropped = sorted.drop(10)
        memoryDailyHistoryIndex[idxKey] = kept
        cacheIoScope.launch {
            runCatching {
                prefs?.edit()?.putString(idxKey, KugouSigner.gson.toJson(kept))?.apply()
                dropped.forEach { oldDate ->
                    val oldKey = "daily_rec_hist_${uid}_$oldDate"
                    memorySongListMap.remove(oldKey)
                    playlistFileFor(oldKey)?.let { if (it.exists()) runCatching { it.delete() } }
                }
            }
        }
    }

    fun getDailyRecommendHistorySnapshot(userId: String?, compactDate: String): List<Song> {
        val cleanDate = compactDate.filter { it.isDigit() }
        if (cleanDate.length != 8) return emptyList()
        val uid = userId?.takeIf { it.isNotBlank() } ?: "guest"
        return getSongList("daily_rec_hist_${uid}_$cleanDate", useDedicatedFile = true)
    }

    fun listDailyRecommendHistoryDates(userId: String?): List<String> {
        val uid = userId?.takeIf { it.isNotBlank() } ?: "guest"
        val idxKey = "daily_rec_hist_dates_$uid"
        memoryDailyHistoryIndex[idxKey]?.let { return it }
        return runCatching {
            val raw = prefs?.getString(idxKey, null) ?: return emptyList()
            val list: List<String> = KugouSigner.gson.fromJson(raw, stringListType) ?: emptyList()
            val clean = list.map { it.filter(Char::isDigit) }.filter { it.length == 8 }.distinct().sortedDescending()
            memoryDailyHistoryIndex[idxKey] = clean
            clean
        }.getOrDefault(emptyList())
    }

    /**
     * 按账号独立文件缓存完整音乐云盘曲目与容量摘要，支持秒开
     */
    fun getCloudDiskSummary(userId: String?): CloudDiskSummary? {
        val uid = userId?.takeIf { it.isNotBlank() } ?: "guest"
        val key = "cloud_disk_summary_$uid"
        memoryCloudDiskSummary[key]?.let { return it }
        return runCatching {
            val f = playlistFileFor(key)
            val raw = if (f != null && f.exists()) f.readText(Charsets.UTF_8) else return null
            val parsed = KugouSigner.gson.fromJson(raw, CloudDiskSummary::class.java) ?: return null
            memoryCloudDiskSummary[key] = parsed
            parsed
        }.getOrNull()
    }

    fun saveCloudDiskSummary(userId: String?, summary: CloudDiskSummary) {
        val uid = userId?.takeIf { it.isNotBlank() } ?: "guest"
        val key = "cloud_disk_summary_$uid"
        memoryCloudDiskSummary[key] = summary
        cacheIoScope.launch {
            runCatching {
                val f = playlistFileFor(key) ?: return@launch
                val tmp = File(f.parentFile, "${f.name}.tmp")
                val json = KugouSigner.gson.toJson(summary)
                tmp.writeText(json, Charsets.UTF_8)
                if (!tmp.renameTo(f)) {
                    f.writeText(json, Charsets.UTF_8)
                    runCatching { tmp.delete() }
                }
            }
        }
    }

    fun getPersonalFm(userId: String?): List<Song> =
        getSongList("personal_fm_${userId ?: "guest"}")

    fun savePersonalFm(userId: String?, songs: List<Song>) =
        saveSongList("personal_fm_${userId ?: "guest"}", songs)

    fun getNewSongs(periodKey: String): List<Song> =
        getSongList("new_songs_${periodKey.uppercase()}")

    fun saveNewSongs(periodKey: String, songs: List<Song>) =
        saveSongList("new_songs_${periodKey.uppercase()}", songs)

    fun getRanks(): List<RankItem> {
        memoryRankList["ranks_all"]?.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching {
            val sp = prefs ?: return emptyList()
            val raw = sp.getString("ranks_all", null) ?: return emptyList()
            val list: List<RankItem> = KugouSigner.gson.fromJson(raw, rankListType) ?: emptyList()
            if (list.isNotEmpty()) {
                memoryRankList["ranks_all"] = list
            }
            list
        }.getOrDefault(emptyList())
    }

    fun saveRanks(ranks: List<RankItem>) {
        if (ranks.isEmpty()) return
        memoryRankList["ranks_all"] = ranks
        cacheIoScope.launch {
            runCatching {
                prefs?.edit()?.putString("ranks_all", KugouSigner.gson.toJson(ranks))?.apply()
            }
        }
    }

    fun getFmChannels(): List<FmChannel> {
        memoryFmChannelList["fm_channels_all"]?.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching {
            val sp = prefs ?: return emptyList()
            val raw = sp.getString("fm_channels_all", null) ?: return emptyList()
            val list: List<FmChannel> = KugouSigner.gson.fromJson(raw, fmChannelListType) ?: emptyList()
            if (list.isNotEmpty()) {
                memoryFmChannelList["fm_channels_all"] = list
            }
            list
        }.getOrDefault(emptyList())
    }

    fun saveFmChannels(channels: List<FmChannel>) {
        if (channels.isEmpty()) return
        memoryFmChannelList["fm_channels_all"] = channels
        cacheIoScope.launch {
            runCatching {
                prefs?.edit()?.putString("fm_channels_all", KugouSigner.gson.toJson(channels))?.apply()
            }
        }
    }

    fun getRankSongs(rankId: Int): List<Song> =
        getSongList("rank_songs_$rankId", useDedicatedFile = true)

    fun saveRankSongs(rankId: Int, songs: List<Song>) =
        saveSongList("rank_songs_$rankId", songs, useDedicatedFile = true)

    fun getFmChannelSongs(channelId: String): List<Song> =
        getSongList("fm_channel_songs_$channelId", useDedicatedFile = true)

    fun saveFmChannelSongs(channelId: String, songs: List<Song>) =
        saveSongList("fm_channel_songs_$channelId", songs, useDedicatedFile = true)

    fun getPlaylistSongs(playlistId: String): List<Song> =
        getSongList("playlist_songs_$playlistId", useDedicatedFile = true)

    fun savePlaylistSongs(playlistId: String, songs: List<Song>, allowEmpty: Boolean = true) =
        saveSongList("playlist_songs_$playlistId", songs, allowEmpty = allowEmpty, useDedicatedFile = true)

    fun getUserPlaylists(userId: String?): List<Playlist> {
        val key = "user_playlists_${userId ?: "guest"}"
        memoryPlaylistList[key]?.let { list -> return list.filterNot { it.isDefaultCollect } }
        return runCatching {
            val sp = prefs ?: return emptyList()
            val raw = sp.getString(key, null) ?: return emptyList()
            val list: List<Playlist> = (KugouSigner.gson.fromJson(raw, playlistListType) ?: emptyList<Playlist>()).filterNot { it.isDefaultCollect }
            memoryPlaylistList[key] = list
            list
        }.getOrDefault(emptyList())
    }

    fun saveUserPlaylists(userId: String?, playlists: List<Playlist>, allowEmpty: Boolean = true) {
        if (playlists.isEmpty() && !allowEmpty) return
        val key = "user_playlists_${userId ?: "guest"}"
        val cleaned = playlists.filterNot { it.isDefaultCollect }; memoryPlaylistList[key] = cleaned
        cacheIoScope.launch {
            runCatching {
                prefs?.edit()?.putString(key, KugouSigner.gson.toJson(cleaned))?.apply()
            }
        }
    }

    fun getLikedSongs(userId: String?): List<Song> =
        getSongList("liked_songs_${userId ?: "guest"}", useDedicatedFile = true)

    fun saveLikedSongs(userId: String?, songs: List<Song>, allowEmpty: Boolean = true) =
        saveSongList("liked_songs_${userId ?: "guest"}", songs, allowEmpty = allowEmpty, useDedicatedFile = true)

    fun getPurchasedAlbums(userId: String?): List<PurchasedAlbumItem> {
        val key = "purchased_albums_${userId ?: "guest"}"
        memoryPurchasedAlbumList[key]?.let { return it }
        return runCatching {
            val sp = prefs ?: return emptyList()
            val raw = sp.getString(key, null) ?: return emptyList()
            val list: List<PurchasedAlbumItem> = KugouSigner.gson.fromJson(raw, purchasedAlbumListType) ?: emptyList()
            memoryPurchasedAlbumList[key] = list
            list
        }.getOrDefault(emptyList())
    }

    fun savePurchasedAlbums(userId: String?, albums: List<PurchasedAlbumItem>, allowEmpty: Boolean = false) {
        if (albums.isEmpty() && !allowEmpty) return
        val key = "purchased_albums_${userId ?: "guest"}"
        memoryPurchasedAlbumList[key] = albums
        cacheIoScope.launch {
            runCatching {
                prefs?.edit()?.putString(key, KugouSigner.gson.toJson(albums))?.apply()
            }
        }
    }

    fun getPurchasedSongs(userId: String?): List<PurchasedSongItem> {
        val key = "purchased_songs_${userId ?: "guest"}"
        memoryPurchasedSongList[key]?.let { return it }
        return runCatching {
            val sp = prefs ?: return emptyList()
            val raw = sp.getString(key, null) ?: return emptyList()
            val list: List<PurchasedSongItem> = KugouSigner.gson.fromJson(raw, purchasedSongListType) ?: emptyList()
            memoryPurchasedSongList[key] = list
            list
        }.getOrDefault(emptyList())
    }

    fun savePurchasedSongs(userId: String?, songs: List<PurchasedSongItem>, allowEmpty: Boolean = false) {
        if (songs.isEmpty() && !allowEmpty) return
        val key = "purchased_songs_${userId ?: "guest"}"
        memoryPurchasedSongList[key] = songs
        cacheIoScope.launch {
            runCatching {
                prefs?.edit()?.putString(key, KugouSigner.gson.toJson(songs))?.apply()
            }
        }
    }

    fun clearAll() {
        memorySongListMap.clear()
        memoryRankList.clear()
        memoryFmChannelList.clear()
        memoryPlaylistList.clear()
        memoryPurchasedAlbumList.clear()
        memoryPurchasedSongList.clear()
        memoryCloudDiskSummary.clear()
        memoryDailyHistoryIndex.clear()
        runCatching { prefs?.edit()?.clear()?.apply() }
        runCatching {
            playlistCacheDir?.listFiles()?.forEach { f ->
                if (f.isFile) runCatching { f.delete() }
            }
        }
    }
}
