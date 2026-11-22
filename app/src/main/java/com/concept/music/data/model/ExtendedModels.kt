package com.concept.music.data.model

import androidx.compose.runtime.Immutable

/**
 * 历史每日推荐日期批次条目
 */
@Immutable
data class DailyHistoryEntry(
    val historyName: String,
    val dateLabel: String,
    val displayTitle: String = dateLabel
)

/**
 * 已关注歌手的最新单曲/新专辑动态
 */
@Immutable
data class FollowedArtistNewRelease(
    val id: String,
    val title: String,
    val artistName: String,
    val artistId: String = "",
    val coverUrl: String = "",
    val publishDate: String = "",
    val isAlbum: Boolean = false,
    val albumId: String = "",
    val song: Song? = null
)

/**
 * 统一评论池目标类型（复用现有楼中楼评论页支持单曲 / 歌单 / 专辑）
 */
enum class CommentTargetType(val label: String) {
    SONG("歌曲"),
    PLAYLIST("歌单"),
    ALBUM("专辑")
}

/**
 * 酷狗个人音乐云盘单曲条目
 */
@Immutable
data class CloudSongItem(
    val kvId: Long,
    val hash: String,
    val name: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val fileSize: Long = 0L,
    val durationMs: Long = 0L,
    val bitrate: Int = 0,
    val extName: String = "",
    val albumAudioId: Long = 0L,
    val audioId: Long = 0L,
    val coverUrl: String = "",
    val addTime: String = ""
) {
    fun toSong(): Song {
        val isFlac = extName.equals("flac", ignoreCase = true) || bitrate >= 700
        val isHiRes = isFlac && bitrate >= 1500
        val qualityStr = when {
            isHiRes -> "high"
            isFlac -> "flac"
            bitrate >= 256 -> "320"
            else -> "128"
        }
        return Song(
            id = "cloud_${kvId}_${hash.take(8).lowercase()}",
            hash = hash,
            hash128 = hash,
            hash320 = if (bitrate >= 256) hash else "",
            hashFlac = if (isFlac) hash else "",
            hashHiRes = if (isHiRes) hash else "",
            title = title.ifBlank { name },
            artist = artist.ifBlank { "云盘曲目" },
            album = album.ifBlank { "我的音乐云盘" },
            coverUrl = coverUrl,
            durationMs = durationMs,
            isLossless = isFlac,
            isHiRes = isHiRes,
            albumAudioId = albumAudioId,
            audioId = audioId,
            mixSongId = albumAudioId.takeIf { it > 0L }?.toString()
                ?: audioId.takeIf { it > 0L }?.toString().orEmpty(),
            quality = qualityStr,
            bitrateKbps = bitrate,
            fileSizeByte = fileSize,
            cloudKvId = kvId
        )
    }
}

/**
 * 酷狗个人音乐云盘概览与分页结果
 */
@Immutable
data class CloudDiskSummary(
    val songs: List<CloudSongItem> = emptyList(),
    val totalCount: Int = 0,
    val usedBytes: Long = 0L,
    val maxBytes: Long = 0L,
    val hasMore: Boolean = false
)

/**
 * 个人听歌排行条目（近一周 / 全部累计）
 */
@Immutable
data class ListenRankItem(
    val rank: Int,
    val song: Song,
    val playCount: Int = 0
)

/**
 * 用户听歌偏好配置（mode: 0默认平衡 / 1偏好熟悉 / 2更多尝鲜）
 */
@Immutable
data class UserListeningPreference(
    val mode: Int = 0,
    val gender: String = "",
    val age: String = "",
    val langRaw: String = "",
    val styleRaw: String = "",
    val songLangRaw: String = "",
    val rawJson: String = ""
) {
    val modeLabel: String
        get() = when (mode) {
            1 -> "偏好熟悉"
            2 -> "更多尝鲜"
            else -> "默认平衡"
        }
}

/**
 * 内容屏蔽/黑名单条目（label: "song" 或 "singer"）
 */
@Immutable
data class BlacklistEntry(
    val key: String,
    val name: String,
    val label: String,
    val mixSongId: String = "",
    val addedAtSec: Long = 0L
)

/**
 * 单个来源设备的最近播放分组
 */
@Immutable
data class CrossDeviceSession(
    val sourceType: String,
    val sourceLabel: String,
    val songs: List<Song>,
    val latestSong: Song,
    val updatedAtMs: Long = 0L
)

/**
 * 跨设备「继续播放」接力快照
 */
@Immutable
data class ContinuePlaySnapshot(
    val primarySong: Song? = null,
    val primaryDeviceLabel: String = "",
    val primarySourceType: String = "",
    val queue: List<Song> = emptyList(),
    val deviceGroups: List<CrossDeviceSession> = emptyList(),
    val fetchedAtMs: Long = 0L
) {
    val hasPlayableContent: Boolean
        get() = primarySong != null && queue.isNotEmpty()

    /**
     * 判断跨设备快照相对于当前设备正在播放的曲目是否值得在首页展示接力条：
     * - 当前未播放任何歌曲时直接展示；
     * - 若当前正在播放，仅当远端首曲与当前曲目不同（按 hash/id 判断）时展示接力建议。
     */
    fun shouldSuggestOverCurrentSong(currentSong: Song?, isCurrentlyPlaying: Boolean): Boolean {
        val remote = primarySong ?: return false
        if (currentSong == null) return true
        val sameHash = remote.hash.isNotBlank() && currentSong.hash.isNotBlank() &&
            remote.hash.equals(currentSong.hash, ignoreCase = true)
        val sameId = remote.id.isNotBlank() && remote.id == currentSong.id
        val sameTitleArtist = remote.title.equals(currentSong.title, ignoreCase = true) &&
            remote.artist.equals(currentSong.artist, ignoreCase = true)
        if (sameHash || sameId || sameTitleArtist) return false
        return !isCurrentlyPlaying || primarySourceType == "pc" || primarySourceType == "car" || primarySourceType == "tv" || deviceGroups.size > 1
    }
}

/**
 * 账号已登录设备信息
 */
@Immutable
data class LoginDeviceItem(
    val mid: String,
    val deviceName: String,
    val platform: String = "",
    val appId: String = "",
    val clientVer: String = "",
    val loginTime: String = "",
    val t: String = "",
    val isCurrentDevice: Boolean = false
)
