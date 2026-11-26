package com.concept.music.data.model

import androidx.compose.runtime.Immutable

/**
 * 历史每日推荐日期批次条目
 */
@Immutable
data class DailyHistoryEntry(
    val historyName: String,
    val dateLabel: String,
    val displayTitle: String = dateLabel,
    val compactDate: String = dateLabel.replace("-", "")
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
    val albumId: String = "",
    val fileSize: Long = 0L,
    val durationMs: Long = 0L,
    val bitrate: Int = 0,
    val extName: String = "",
    val albumAudioId: Long = 0L,
    val audioId: Long = 0L,
    val coverUrl: String = "",
    val addTime: String = "",
    val addTimestampSec: Long = 0L,
    val resolvedQuality: AudioQuality? = null
) {
    val effectiveQuality: AudioQuality
        get() = resolvedQuality ?: when {
            (extName.equals("flac", ignoreCase = true) || bitrate >= 700) && bitrate >= 1450 -> AudioQuality.HI_RES
            extName.equals("flac", ignoreCase = true) || extName.equals("ape", ignoreCase = true) ||
                extName.equals("wav", ignoreCase = true) || bitrate >= 700 -> AudioQuality.LOSSLESS
            bitrate >= 256 -> AudioQuality.HIGH
            else -> AudioQuality.STANDARD
        }

    val qualityBadgeLabel: String
        get() = when (effectiveQuality) {
            AudioQuality.HI_RES -> "Hi-Res"
            AudioQuality.LOSSLESS -> "SQ"
            AudioQuality.HIGH -> "HQ"
            AudioQuality.STANDARD -> "标准"
        }

    val formattedSize: String
        get() {
            if (fileSize <= 0L) return ""
            val mb = fileSize.toDouble() / (1024.0 * 1024.0)
            return if (mb >= 1.0) String.format(java.util.Locale.US, "%.1f MB", mb)
            else String.format(java.util.Locale.US, "%.0f KB", fileSize.toDouble() / 1024.0)
        }

    /**
     * 规范化的格式与码率展示文案（杜绝将酷狗内部品质枚举码 1..10 误展示为 4kbps / 3kbps）
     */
    val formattedCodecAndBitrate: String
        get() {
            val fmt = extName.trim().uppercase(java.util.Locale.ROOT)
            val validKbps = bitrate.takeIf { it >= 32 } ?: 0
            return when {
                fmt.isNotBlank() && validKbps > 0 -> "$fmt ${validKbps}kbps"
                fmt.isNotBlank() -> fmt
                validKbps > 0 -> "${validKbps}kbps"
                else -> ""
            }
        }

    fun toSong(): Song {
        val eq = effectiveQuality
        val isHiRes = eq == AudioQuality.HI_RES
        val isFlac = isHiRes || eq == AudioQuality.LOSSLESS
        val qualityStr = when (eq) {
            AudioQuality.HI_RES -> "high"
            AudioQuality.LOSSLESS -> "flac"
            AudioQuality.HIGH -> "320"
            AudioQuality.STANDARD -> "128"
        }
        return Song(
            id = "cloud_${kvId}_${hash.take(8).lowercase()}",
            hash = hash,
            hash128 = hash,
            hash320 = if (eq.ordinal >= AudioQuality.HIGH.ordinal) hash else "",
            hashFlac = if (isFlac) hash else "",
            hashHiRes = if (isHiRes) hash else "",
            title = title.ifBlank { name },
            artist = artist.ifBlank { "云盘曲目" },
            album = album.ifBlank { "我的音乐云盘" },
            albumId = albumId,
            coverUrl = coverUrl,
            durationMs = durationMs,
            isLossless = isFlac,
            isHiRes = isHiRes,
            albumAudioId = albumAudioId,
            audioId = audioId,
            mixSongId = albumAudioId.takeIf { it > 0L }?.toString()
                ?: audioId.takeIf { it > 0L }?.toString().orEmpty(),
            quality = qualityStr,
            availableQualities = listOf(eq),
            actualPlayQuality = eq,
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
 * 个人听歌排行条目（周榜 / 月榜 / 累计总榜）
 */
@Immutable
data class ListenRankItem(
    val rank: Int,
    val song: Song,
    val playCount: Int = 0,
    val playedMs: Long = 0L,
    val lastPlayedAtMs: Long = 0L
)

/**
 * 最常听歌手排行条目（基于所选排行周期聚合歌手总播放次数与上榜曲数）
 */
@Immutable
data class ArtistListenRankItem(
    val rank: Int,
    val artistName: String,
    val artistId: String = "",
    val avatarUrl: String = "",
    val totalPlayCount: Int = 0,
    val trackCount: Int = 0,
    val topSongTitle: String = "",
    val songs: List<Song> = emptyList(),
    val totalPlayedMs: Long = 0L
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
    val inactiveSingerList: String = "",
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
 * 账号已登录设备信息
 */
@Immutable
data class LoginDeviceItem(
    val mid: String,
    val deviceName: String,
    val platform: String = "",
    val deviceType: String = "mobile",
    val appId: String = "",
    val clientVer: String = "",
    val loginTime: String = "",
    val loginTimestampSec: Long = 0L,
    val t: String = "",
    val isCurrentDevice: Boolean = false,
    val canKickOut: Boolean = true,
    val detectedViaStandardChannel: Boolean = false
)
