package com.concept.music.data.repository

import android.content.Context
import com.concept.music.data.model.Playlist
import com.concept.music.data.model.Song
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import java.util.UUID

data class LocalOnlyPlaylistRecord(
    @SerializedName("id") val id: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("coverUrl") val coverUrl: String = "",
    @SerializedName("createdAtMs") val createdAtMs: Long = 0L,
    @SerializedName("updatedAtMs") val updatedAtMs: Long = 0L,
    @SerializedName("songs") val songs: List<Song> = emptyList()
) {
    fun toPlaylist(): Playlist {
        val cleanSongs = songs.map { it.sanitizeFromJson() }
        val resolvedCover = coverUrl.ifBlank {
            cleanSongs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty()
        }
        return Playlist(
            id = id,
            title = title,
            coverUrl = resolvedCover,
            trackCount = cleanSongs.size,
            description = description,
            creatorName = "本机歌单",
            isCreatedByMe = true,
            isFavorite = false,
            isLiked = false,
            listId = 0L,
            isLocalOnly = true
        )
    }
}

private data class PersistedLocalPlaylistStore(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("localPlaylists") val localPlaylists: List<LocalOnlyPlaylistRecord> = emptyList(),
    @SerializedName("cloudPlaylistLocalTracks") val cloudPlaylistLocalTracks: Map<String, List<Song>> = emptyMap(),
    @SerializedName("cloudPlaylistMergedOrders") val cloudPlaylistMergedOrders: Map<String, List<String>> = emptyMap()
)

/**
 * 纯本地歌单与「云端自建歌单本地曲目挂载」持久化仓库：
 * 1. 数据独立落盘至 `filesDir/local_playlists_store_v1.json`（原子写入），绝不受「清除缓存」影响；
 * 2. 严格实现云端零同步隔离：
 *    - 纯本地歌单 (`local_pl_*` / `isLocalOnly = true`) 的创建、重命名、编辑简介、增删曲目、排序与删除完全在本机完成；
 *    - 添加到现有云端自建歌单里的本地歌曲 (`song.isLocalSong == true`) 仅保存在本机映射表中，不上传云端；
 *      云端歌单刷新时自动将本地歌曲与云端曲目无损合并并保持顺序。
 */
class LocalPlaylistRepository(
    context: Context?,
    private val customStoreFile: File? = null
) {
    companion object {
        const val LOCAL_PLAYLIST_ID_PREFIX = "local_pl_"
        private const val STORE_FILE_NAME = "local_playlists_store_v1.json"

        fun isPureLocalPlaylistId(rawId: String?): Boolean {
            return rawId?.trim()?.startsWith(LOCAL_PLAYLIST_ID_PREFIX) == true
        }

        fun songIdentityKey(song: Song): String {
            if (song.isLocalSong) {
                val cleanId = song.id.trim().lowercase(Locale.ROOT)
                if (cleanId.isNotEmpty()) return "local:$cleanId"
                val cleanUri = song.localUri.trim().lowercase(Locale.ROOT)
                if (cleanUri.isNotEmpty()) return "local_uri:$cleanUri"
            }
            val cleanHash = song.hash.trim().lowercase(Locale.ROOT)
            if (cleanHash.length == 32) return "h:$cleanHash"
            val cleanId = song.id.trim().lowercase(Locale.ROOT)
            if (cleanId.isNotEmpty()) return "id:$cleanId"
            return "ta:${song.artist.trim().lowercase(Locale.ROOT)}|${song.title.trim().lowercase(Locale.ROOT)}"
        }

        fun buildCloudPlaylistBindingKeys(userId: String?, playlist: Playlist): List<String> {
            if (playlist.isPureLocalPlaylist) return emptyList()
            val uid = userId?.trim()?.takeIf { it.isNotEmpty() } ?: "guest"
            val result = LinkedHashSet<String>()
            val effectiveLid = playlist.resolveEffectiveListId()
            if (effectiveLid > 0L) {
                result.add("${uid}_lid_$effectiveLid")
            }
            val cleanId = playlist.id.trim().lowercase(Locale.ROOT)
            if (cleanId.isNotEmpty() && cleanId != "0") {
                result.add("${uid}_id_$cleanId")
            }
            val cleanGid = playlist.globalCollectionId.trim().lowercase(Locale.ROOT)
            if (cleanGid.isNotEmpty() && cleanGid != "0") {
                result.add("${uid}_gid_$cleanGid")
            }
            return result.toList()
        }

        fun primaryCloudPlaylistBindingKey(userId: String?, playlist: Playlist): String? {
            return buildCloudPlaylistBindingKeys(userId, playlist).firstOrNull()
        }
    }

    private val appContext: Context? = context?.applicationContext
    private val gson = Gson()
    private val lock = Any()

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private var localPlaylists: MutableList<LocalOnlyPlaylistRecord> = mutableListOf()
    private var cloudPlaylistLocalTracks: MutableMap<String, List<Song>> = linkedMapOf()
    private var cloudPlaylistMergedOrders: MutableMap<String, List<String>> = linkedMapOf()

    init {
        loadFromDiskLocked()
    }

    private fun resolveStoreFile(): File? {
        if (customStoreFile != null) return customStoreFile
        val ctx = appContext ?: return null
        return File(ctx.filesDir, STORE_FILE_NAME)
    }

    private fun loadFromDiskLocked() {
        synchronized(lock) {
            val file = resolveStoreFile() ?: return
            if (!file.exists()) return
            runCatching {
                val raw = file.readText(Charsets.UTF_8)
                if (raw.isBlank()) return@runCatching
                val parsed = gson.fromJson(raw, PersistedLocalPlaylistStore::class.java) ?: return@runCatching
                localPlaylists = parsed.localPlaylists
                    .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                    .map { rec ->
                        rec.copy(songs = rec.songs.map { it.sanitizeFromJson() })
                    }
                    .toMutableList()
                cloudPlaylistLocalTracks = linkedMapOf<String, List<Song>>().apply {
                    parsed.cloudPlaylistLocalTracks.forEach { (k, v) ->
                        val cleaned = v.map { it.sanitizeFromJson() }.filter { it.isLocalSong }
                        if (k.isNotBlank() && cleaned.isNotEmpty()) {
                            put(k, cleaned)
                        }
                    }
                }
                cloudPlaylistMergedOrders = linkedMapOf<String, List<String>>().apply {
                    parsed.cloudPlaylistMergedOrders.forEach { (k, v) ->
                        if (k.isNotBlank() && v.isNotEmpty()) {
                            put(k, v)
                        }
                    }
                }
            }
        }
    }

    private fun persistToDiskLocked() {
        _version.value = _version.value + 1L
        val file = resolveStoreFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val snapshot = PersistedLocalPlaylistStore(
                version = 1,
                localPlaylists = localPlaylists.toList(),
                cloudPlaylistLocalTracks = cloudPlaylistLocalTracks.toMap(),
                cloudPlaylistMergedOrders = cloudPlaylistMergedOrders.toMap()
            )
            val json = gson.toJson(snapshot)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(json, Charsets.UTF_8)
                runCatching { tmp.delete() }
            }
        }
    }

    // ==================== 纯本地歌单 CRUD ====================

    fun getLocalPlaylists(): List<Playlist> = synchronized(lock) {
        localPlaylists.map { it.toPlaylist() }
    }

    fun getLocalPlaylist(playlistId: String): Playlist? = synchronized(lock) {
        localPlaylists.firstOrNull { it.id == playlistId }?.toPlaylist()
    }

    fun getLocalPlaylistTracks(
        playlistId: String,
        localMusicRepo: LocalMusicRepository? = null
    ): List<Song> = synchronized(lock) {
        val rec = localPlaylists.firstOrNull { it.id == playlistId } ?: return emptyList()
        rec.songs.map { refreshLocalSongMetadata(it, localMusicRepo) }
    }

    fun createLocalPlaylist(
        title: String,
        initialSongs: List<Song> = emptyList(),
        description: String = ""
    ): Playlist = synchronized(lock) {
        val cleanTitle = title.trim()
        val now = System.currentTimeMillis()
        val deduped = deduplicateSongsPreserveOrder(initialSongs.map { it.sanitizeFromJson() })
        val id = "${LOCAL_PLAYLIST_ID_PREFIX}${now}_${UUID.randomUUID().toString().take(8)}"
        val record = LocalOnlyPlaylistRecord(
            id = id,
            title = cleanTitle,
            description = description.trim(),
            coverUrl = deduped.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty(),
            createdAtMs = now,
            updatedAtMs = now,
            songs = deduped
        )
        localPlaylists.add(0, record)
        persistToDiskLocked()
        record.toPlaylist()
    }

    fun addSongsToLocalPlaylist(playlistId: String, songs: List<Song>): Int = synchronized(lock) {
        val idx = localPlaylists.indexOfFirst { it.id == playlistId }
        if (idx < 0 || songs.isEmpty()) return 0
        val current = localPlaylists[idx]
        val existingKeys = current.songs.map { songIdentityKey(it) }.toHashSet()
        val toAdd = mutableListOf<Song>()
        for (raw in songs) {
            val sanitized = raw.sanitizeFromJson()
            val key = songIdentityKey(sanitized)
            if (existingKeys.add(key)) {
                toAdd.add(sanitized)
            }
        }
        if (toAdd.isEmpty()) return 0
        val merged = toAdd + current.songs
        val updated = current.copy(
            songs = merged,
            coverUrl = merged.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty(),
            updatedAtMs = System.currentTimeMillis()
        )
        localPlaylists[idx] = updated
        persistToDiskLocked()
        toAdd.size
    }

    fun refreshLocalPlaylistAutoCover(
        playlistId: String,
        localMusicRepo: LocalMusicRepository? = null
    ): String = synchronized(lock) {
        val idx = localPlaylists.indexOfFirst { it.id == playlistId }
        if (idx < 0) return ""
        val current = localPlaylists[idx]
        val refreshedSongs = current.songs.map { refreshLocalSongMetadata(it, localMusicRepo) }
        val resolvedCover = refreshedSongs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty()
        if (resolvedCover != current.coverUrl || refreshedSongs != current.songs) {
            localPlaylists[idx] = current.copy(
                songs = refreshedSongs,
                coverUrl = resolvedCover,
                updatedAtMs = System.currentTimeMillis()
            )
            persistToDiskLocked()
        }
        resolvedCover
    }

    fun removeSongsFromLocalPlaylist(playlistId: String, songsToRemove: List<Song>): Int = synchronized(lock) {
        val idx = localPlaylists.indexOfFirst { it.id == playlistId }
        if (idx < 0 || songsToRemove.isEmpty()) return 0
        val current = localPlaylists[idx]
        val removeKeys = songsToRemove.map { songIdentityKey(it) }.toHashSet()
        val remaining = current.songs.filterNot { candidate ->
            songIdentityKey(candidate) in removeKeys || songsToRemove.any { isSameMusicTrack(it, candidate) }
        }
        val removedCount = current.songs.size - remaining.size
        if (removedCount <= 0) return 0
        val updated = current.copy(
            songs = remaining,
            coverUrl = remaining.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty(),
            updatedAtMs = System.currentTimeMillis()
        )
        localPlaylists[idx] = updated
        persistToDiskLocked()
        removedCount
    }

    fun updateLocalPlaylistMeta(playlistId: String, newTitle: String, newDescription: String): Boolean = synchronized(lock) {
        val idx = localPlaylists.indexOfFirst { it.id == playlistId }
        if (idx < 0) return false
        val cleanTitle = newTitle.trim()
        if (cleanTitle.isBlank()) return false
        val current = localPlaylists[idx]
        localPlaylists[idx] = current.copy(
            title = cleanTitle,
            description = newDescription.trim(),
            updatedAtMs = System.currentTimeMillis()
        )
        persistToDiskLocked()
        true
    }

    fun reorderLocalPlaylistTracks(playlistId: String, orderedSongs: List<Song>): Boolean = synchronized(lock) {
        val idx = localPlaylists.indexOfFirst { it.id == playlistId }
        if (idx < 0 || orderedSongs.isEmpty()) return false
        val current = localPlaylists[idx]
        val deduped = deduplicateSongsPreserveOrder(orderedSongs.map { it.sanitizeFromJson() })
        localPlaylists[idx] = current.copy(
            songs = deduped,
            updatedAtMs = System.currentTimeMillis()
        )
        persistToDiskLocked()
        true
    }

    fun deleteLocalPlaylist(playlistId: String): Boolean = synchronized(lock) {
        val removed = localPlaylists.removeAll { it.id == playlistId }
        if (removed) {
            persistToDiskLocked()
        }
        removed
    }

    // ==================== 云端自建歌单的本机歌曲挂载（零云同步） ====================

    private fun resolveExistingBindingKeyLocked(userId: String?, playlist: Playlist): String? {
        val keys = buildCloudPlaylistBindingKeys(userId, playlist)
        return keys.firstOrNull { cloudPlaylistLocalTracks.containsKey(it) }
            ?: keys.firstOrNull { cloudPlaylistMergedOrders.containsKey(it) }
            ?: keys.firstOrNull()
    }

    fun getAttachedLocalSongsForCloudPlaylist(
        userId: String?,
        playlist: Playlist,
        localMusicRepo: LocalMusicRepository? = null
    ): List<Song> = synchronized(lock) {
        if (playlist.isPureLocalPlaylist) return emptyList()
        val keys = buildCloudPlaylistBindingKeys(userId, playlist)
        for (k in keys) {
            val found = cloudPlaylistLocalTracks[k]
            if (!found.isNullOrEmpty()) {
                return found.map { refreshLocalSongMetadata(it, localMusicRepo) }
            }
        }
        emptyList()
    }

    fun getAttachedLocalTrackCount(userId: String?, playlist: Playlist): Int = synchronized(lock) {
        if (playlist.isPureLocalPlaylist) return 0
        val keys = buildCloudPlaylistBindingKeys(userId, playlist)
        for (k in keys) {
            val found = cloudPlaylistLocalTracks[k]
            if (!found.isNullOrEmpty()) return found.size
        }
        0
    }

    fun attachLocalSongsToCloudPlaylist(
        userId: String?,
        playlist: Playlist,
        songs: List<Song>
    ): Int = synchronized(lock) {
        if (playlist.isPureLocalPlaylist) return 0
        val onlyLocal = songs.map { it.sanitizeFromJson() }.filter { it.isLocalSong }
        if (onlyLocal.isEmpty()) return 0
        val targetKey = resolveExistingBindingKeyLocked(userId, playlist) ?: return 0
        val existing = cloudPlaylistLocalTracks[targetKey].orEmpty()
        val existingKeys = existing.map { songIdentityKey(it) }.toHashSet()
        val newOnes = mutableListOf<Song>()
        for (s in onlyLocal) {
            val k = songIdentityKey(s)
            if (existingKeys.add(k)) {
                newOnes.add(s)
            }
        }
        if (newOnes.isEmpty()) return 0
        cloudPlaylistLocalTracks[targetKey] = newOnes + existing
        // 若此前保存了混合自定义顺序，将新增本地歌曲自然置顶于混合顺序前排
        cloudPlaylistMergedOrders[targetKey]?.let { prevOrder ->
            val newKeys = newOnes.map { songIdentityKey(it) }
            cloudPlaylistMergedOrders[targetKey] = newKeys + prevOrder.filterNot { it in newKeys }
        }
        persistToDiskLocked()
        newOnes.size
    }

    fun detachLocalSongsFromCloudPlaylist(
        userId: String?,
        playlist: Playlist,
        songsToRemove: List<Song>
    ): Int = synchronized(lock) {
        if (playlist.isPureLocalPlaylist || songsToRemove.isEmpty()) return 0
        val keys = buildCloudPlaylistBindingKeys(userId, playlist)
        val matchedKey = keys.firstOrNull { cloudPlaylistLocalTracks.containsKey(it) } ?: return 0
        val existing = cloudPlaylistLocalTracks[matchedKey].orEmpty()
        val removeKeys = songsToRemove.map { songIdentityKey(it) }.toHashSet()
        val remaining = existing.filterNot { candidate ->
            songIdentityKey(candidate) in removeKeys || songsToRemove.any { isSameMusicTrack(it, candidate) }
        }
        val removed = existing.size - remaining.size
        if (removed <= 0) return 0
        if (remaining.isEmpty()) {
            cloudPlaylistLocalTracks.remove(matchedKey)
            cloudPlaylistMergedOrders.remove(matchedKey)
        } else {
            cloudPlaylistLocalTracks[matchedKey] = remaining
            cloudPlaylistMergedOrders[matchedKey]?.let { prevOrder ->
                cloudPlaylistMergedOrders[matchedKey] = prevOrder.filterNot { it in removeKeys }
            }
        }
        persistToDiskLocked()
        removed
    }

    fun clearCloudPlaylistAttachments(userId: String?, playlist: Playlist) = synchronized(lock) {
        if (playlist.isPureLocalPlaylist) return
        val keys = buildCloudPlaylistBindingKeys(userId, playlist)
        var changed = false
        for (k in keys) {
            if (cloudPlaylistLocalTracks.remove(k) != null) changed = true
            if (cloudPlaylistMergedOrders.remove(k) != null) changed = true
        }
        if (changed) {
            persistToDiskLocked()
        }
    }

    fun saveCloudPlaylistMergedOrder(
        userId: String?,
        playlist: Playlist,
        orderedSongs: List<Song>
    ) = synchronized(lock) {
        if (playlist.isPureLocalPlaylist) return
        val targetKey = resolveExistingBindingKeyLocked(userId, playlist) ?: return
        val localSongsInOrder = orderedSongs.filter { it.isLocalSong }
        if (localSongsInOrder.isEmpty()) {
            cloudPlaylistMergedOrders.remove(targetKey)
            persistToDiskLocked()
            return
        }
        cloudPlaylistLocalTracks[targetKey] = deduplicateSongsPreserveOrder(localSongsInOrder)
        cloudPlaylistMergedOrders[targetKey] = orderedSongs.map { songIdentityKey(it) }.distinct()
        persistToDiskLocked()
    }

    /**
     * 将云端曲目列表与该歌单本机挂载的本地歌曲合并：
     * - 无本地挂载歌曲时原样返回云端曲目；
     * - 若用户曾对混合歌单执行过拖拽自定义排序，按混合顺序精确还原；
     * - 否则将本机挂载的本地歌曲稳定置于前排，云端曲目紧随其后（去重不丢失）。
     */
    fun mergeCloudAndAttachedLocalTracks(
        userId: String?,
        playlist: Playlist,
        cloudTracks: List<Song>,
        localMusicRepo: LocalMusicRepository? = null
    ): List<Song> = synchronized(lock) {
        if (playlist.isPureLocalPlaylist) {
            return getLocalPlaylistTracks(playlist.id, localMusicRepo)
        }
        val keys = buildCloudPlaylistBindingKeys(userId, playlist)
        val matchedKey = keys.firstOrNull { cloudPlaylistLocalTracks.containsKey(it) } ?: return cloudTracks
        val attachedLocal = cloudPlaylistLocalTracks[matchedKey].orEmpty()
            .map { refreshLocalSongMetadata(it, localMusicRepo) }
        if (attachedLocal.isEmpty()) return cloudTracks

        val cleanCloud = cloudTracks.filterNot { it.isLocalSong }
        val savedOrder = cloudPlaylistMergedOrders[matchedKey]
        if (savedOrder.isNullOrEmpty()) {
            return deduplicateSongsPreserveOrder(attachedLocal + cleanCloud)
        }

        val allPool = deduplicateSongsPreserveOrder(attachedLocal + cleanCloud)
        val byKey = LinkedHashMap<String, Song>(allPool.size)
        for (s in allPool) {
            byKey.putIfAbsent(songIdentityKey(s), s)
        }
        val ordered = mutableListOf<Song>()
        val placedKeys = HashSet<String>()
        for (k in savedOrder) {
            val found = byKey[k]
            if (found != null && placedKeys.add(k)) {
                ordered.add(found)
            }
        }
        for (s in allPool) {
            val k = songIdentityKey(s)
            if (placedKeys.add(k)) {
                ordered.add(s)
            }
        }
        ordered
    }

    /**
     * 将云端歌单列表与本机纯本地歌单以及本地附加曲目计数合并：
     * - 为挂载了本地歌曲的云端自建歌单累加本地曲目数与封面兜底；
     * - 保留全部纯本地歌单（未登录状态下也能完整可见与管理）。
     */
    fun mergeWithCloudPlaylists(
        userId: String?,
        cloudPlaylists: List<Playlist>,
        localMusicRepo: LocalMusicRepository? = null
    ): List<Playlist> = synchronized(lock) {
        val enrichedCloud = cloudPlaylists
            .filterNot { it.isPureLocalPlaylist }
            .map { pl ->
                val attached = getAttachedLocalSongsForCloudPlaylist(userId, pl, localMusicRepo)
                if (attached.isEmpty()) {
                    pl
                } else {
                    val baseCover = pl.coverUrl.ifBlank {
                        attached.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty()
                    }
                    pl.copy(
                        trackCount = pl.trackCount + attached.size,
                        coverUrl = baseCover
                    )
                }
            }
        val pureLocal = localPlaylists.map { rec ->
            val refreshedSongs = rec.songs.map { refreshLocalSongMetadata(it, localMusicRepo) }
            val cover = refreshedSongs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl
                ?.ifBlank { rec.coverUrl }
                ?: rec.coverUrl
            rec.copy(songs = refreshedSongs, coverUrl = cover).toPlaylist()
        }
        val liked = enrichedCloud.filter { it.isLiked }
        val createdCloud = enrichedCloud.filter { it.isCreatedByMe && !it.isLiked }
        val otherCloud = enrichedCloud.filterNot { it.isCreatedByMe || it.isLiked }
        liked + pureLocal + createdCloud + otherCloud
    }

    private fun refreshLocalSongMetadata(song: Song, localMusicRepo: LocalMusicRepository?): Song {
        if (!song.isLocalSong || localMusicRepo == null) return song
        val freshTrack = localMusicRepo.findTrackBySongId(song.id) ?: return song
        return freshTrack.toSong()
    }

    private fun deduplicateSongsPreserveOrder(songs: List<Song>): List<Song> {
        if (songs.size <= 1) return songs
        val seen = HashSet<String>(songs.size)
        val result = ArrayList<Song>(songs.size)
        for (s in songs) {
            if (seen.add(songIdentityKey(s))) {
                result.add(s)
            }
        }
        return result
    }
}
