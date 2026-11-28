package com.concept.music.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.concept.music.data.kugou.suspendRunCatching
import com.concept.music.data.model.AudioQuality
import com.concept.music.data.model.LyricData
import com.concept.music.data.model.LyricLine
import com.concept.music.data.model.Playlist
import com.concept.music.data.model.Song
import com.concept.music.data.repository.LocalPlaylistRepository
import com.concept.music.data.vip.VipManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 车载蓝牙 AVRCP 高定呼吸排版输出对：
 * - `avrTitle` / `avrArtist`：映射至 `METADATA_KEY_TITLE` / `METADATA_KEY_ARTIST`，供车载蓝牙 AVRCP 提取展示在中控屏、仪表盘与 HUD；
 * - `notificationTitle` / `notificationArtist`：映射至 `METADATA_KEY_DISPLAY_TITLE` / `METADATA_KEY_DISPLAY_SUBTITLE` 及通知栏 Provider，
 *   确保手机本机系统通知栏与锁屏卡片始终稳定显示原歌名与原歌手，彻底杜绝闪烁跳动。
 */
data class CarAvrcpMetadataSnapshot(
    val avrTitle: String,
    val avrArtist: String,
    val notificationTitle: String,
    val notificationArtist: String
)

/**
 * 车载蓝牙歌词高定呼吸排版器与行级低功耗唤醒计算引擎
 */
object CarAvrcpLyricFormatter {

    // 精准剥离歌曲前奏常见的词曲编混制作人员流水账行（仅针对歌曲前 50 秒内的制作鸣谢行生效，绝不误伤正常唱词）
    private val CREDIT_PREFIX_REGEX = Regex(
        pattern = "^\\s*(?:" +
            "作词|作曲|编曲|词|曲|词曲|填词|谱曲|原词|原曲|改编|混音|母带|录音|制作人|制作|监制|出品人|出品|统筹|企划|宣传|发行|推广|" +
            "和声编写|和声|和音|吉他|木吉他|电吉他|贝斯|鼓|爵士鼓|打击乐|钢琴|键盘|合成器|弦乐编写|弦乐|小提琴|大提琴|笛子|古筝|二胡|唢呐|萨克斯|" +
            "人声编辑|音频编辑|配唱制作人|录音师|混音师|母带工程师|录音室|混音室|版权|鸣谢|联合出品|特别鸣谢|" +
            "op|sp|lyrics?\\s*by|composed\\s*by|arranged\\s*by|produced\\s*by|mixed\\s*by|mastered\\s*by|recorded\\s*by|vocal\\s*directed\\s*by" +
            ")\\s*[:：/丨|\\-—]\\s*.+$",
        option = RegexOption.IGNORE_CASE
    )

    fun isCreditMetadataLine(
        line: LyricLine,
        songTitle: String = "",
        songArtist: String = ""
    ): Boolean {
        val raw = line.text.trim()
        if (raw.isEmpty()) return true
        // 超过 55 秒后的正常唱词不再按鸣谢头过滤，防止歌词中恰巧出现“词：”等台词
        if (line.startTimeMs > 55_000L) return false
        if (CREDIT_PREFIX_REGEX.matches(raw)) return true

        // 过滤第一行常见的「歌名 - 歌手」或「歌手 - 歌名」标题复读行
        if (line.startTimeMs <= 12_000L && songTitle.isNotBlank()) {
            val cleanLine = raw.replace(" ", "").lowercase(Locale.ROOT)
            val cleanTitle = songTitle.trim().replace(" ", "").lowercase(Locale.ROOT)
            val cleanArtist = songArtist.trim().replace(" ", "").lowercase(Locale.ROOT)
            if (cleanArtist.isNotBlank()) {
                if (cleanLine == "$cleanTitle-$cleanArtist" ||
                    cleanLine == "$cleanArtist-$cleanTitle" ||
                    cleanLine == "$cleanTitle—$cleanArtist" ||
                    cleanLine == "$cleanTitle·$cleanArtist"
                ) {
                    return true
                }
            }
        }
        return false
    }

    fun formatSongArtistFooter(songTitle: String, songArtist: String): String {
        val t = songTitle.trim().ifBlank { "未知歌曲" }
        val a = songArtist.trim()
        return if (a.isNotBlank() && !a.equals("未知歌手", ignoreCase = true)) {
            "$t · $a"
        } else {
            t
        }
    }

    /**
     * 生成当前播放时刻的高定呼吸排版蓝牙元数据快照
     */
    fun formatMetadataSnapshot(
        song: Song?,
        lyricData: LyricData,
        adjustedPositionMs: Long,
        enableCarLyric: Boolean,
        interludeStyle: CarInterludeStyle = CarInterludeStyle.BREATHING_NOTES
    ): CarAvrcpMetadataSnapshot {
        val origTitle = song?.title?.trim()?.ifBlank { "微风音乐" } ?: "微风音乐"
        val origArtist = song?.artist?.trim()?.ifBlank { "未知歌手" } ?: "未知歌手"

        if (!enableCarLyric || song == null || lyricData.lines.isEmpty()) {
            return CarAvrcpMetadataSnapshot(
                avrTitle = origTitle,
                avrArtist = origArtist,
                notificationTitle = origTitle,
                notificationArtist = origArtist
            )
        }

        val lines = lyricData.lines
        val firstVocalIndex = lines.indexOfFirst { !isCreditMetadataLine(it, origTitle, origArtist) }
        if (firstVocalIndex < 0) {
            return CarAvrcpMetadataSnapshot(
                avrTitle = origTitle,
                avrArtist = origArtist,
                notificationTitle = origTitle,
                notificationArtist = origArtist
            )
        }

        val firstVocalStartMs = lines[firstVocalIndex].startTimeMs
        // 1. 前奏净空态：在第一句真正人声唱出之前，安静展示原歌名与原歌手，绝不刷屏作词作曲流水账
        if (adjustedPositionMs < firstVocalStartMs) {
            return CarAvrcpMetadataSnapshot(
                avrTitle = origTitle,
                avrArtist = origArtist,
                notificationTitle = origTitle,
                notificationArtist = origArtist
            )
        }

        val lineIdx = lyricData.getLineIndexAt(adjustedPositionMs)
        if (lineIdx < 0) {
            return CarAvrcpMetadataSnapshot(
                avrTitle = origTitle,
                avrArtist = origArtist,
                notificationTitle = origTitle,
                notificationArtist = origArtist
            )
        }

        val footer = formatSongArtistFooter(origTitle, origArtist)

        // 2. 长间奏音符呼吸态（>= 5000ms 无词器乐间奏）
        val interludeProgress = lyricData.getInterludeProgressAt(
            positionMs = adjustedPositionMs,
            currentIndex = lineIdx,
            minGapMs = 5_000L
        )
        if (interludeProgress >= 0f) {
            val interludeText = when (interludeStyle) {
                CarInterludeStyle.BREATHING_NOTES -> "♪  •••  ♪"
                CarInterludeStyle.SONG_TITLE_NOTES -> "♪  $origTitle  ♪"
            }
            return CarAvrcpMetadataSnapshot(
                avrTitle = interludeText,
                avrArtist = footer,
                notificationTitle = origTitle,
                notificationArtist = origArtist
            )
        }

        // 3. 若当前命中行恰好夹杂了制作信息行，向前回退最近一句真实唱词，若无则回退原歌名
        var resolvedVocalLine: LyricLine? = null
        for (i in lineIdx downTo firstVocalIndex) {
            val candidate = lines[i]
            if (!isCreditMetadataLine(candidate, origTitle, origArtist)) {
                resolvedVocalLine = candidate
                break
            }
        }
        val lyricText = resolvedVocalLine?.text?.trim().orEmpty()
        if (lyricText.isBlank()) {
            return CarAvrcpMetadataSnapshot(
                avrTitle = origTitle,
                avrArtist = origArtist,
                notificationTitle = origTitle,
                notificationArtist = origArtist
            )
        }

        return CarAvrcpMetadataSnapshot(
            avrTitle = lyricText,
            avrArtist = footer,
            notificationTitle = origTitle,
            notificationArtist = origArtist
        )
    }

    /**
     * 计算距离下一次蓝牙歌词文本发生变化的精确毫秒数（息屏零轮询自适应调度）：
     * - 仅在下一句开唱边界或长间奏起点唤醒一次主线程；
     * - 钳制在 `[260ms, 4500ms]`，兼顾拖动进度条后的自校准与息屏超低功耗。
     */
    fun computeNextWakeupDelayMs(
        lyricData: LyricData,
        adjustedPositionMs: Long,
        playbackSpeed: Float = 1.0f
    ): Long {
        val lines = lyricData.lines
        if (lines.isEmpty()) return 3_000L
        val safeSpeed = playbackSpeed.coerceIn(0.5f, 2.0f)
        val idx = lyricData.getLineIndexAt(adjustedPositionMs)

        val nextTargetContentMs: Long = if (idx < 0) {
            lines.first().startTimeMs
        } else {
            val curLine = lines[idx]
            val nextLine = lines.getOrNull(idx + 1)
            val vocalEndMs = when {
                curLine.words.isNotEmpty() -> curLine.effectiveEndTimeMs
                lyricData.isKrc && curLine.durationMs > 0L -> curLine.startTimeMs + curLine.durationMs
                else -> -1L
            }
            val hasLongInterlude = nextLine != null && vocalEndMs > curLine.startTimeMs &&
                (nextLine.startTimeMs - vocalEndMs) >= 5_000L
            when {
                hasLongInterlude && adjustedPositionMs < vocalEndMs -> vocalEndMs
                nextLine != null -> nextLine.startTimeMs
                else -> adjustedPositionMs + 4_500L
            }
        }

        val rawDeltaMs = ((nextTargetContentMs - adjustedPositionMs) / safeSpeed).toLong() + 25L
        return rawDeltaMs.coerceIn(260L, 4_500L)
    }
}

/**
 * `MediaLibraryService` 车载媒体目录树与受信任车联宿主鉴权辅助中心：
 * 支持 Android Auto、Android Automotive OS、华为 HiCar、小米 CarWith、ICCOA Carlink、OPPO Car+、vivo Jovi InCar 与百度 CarLife。
 */
object CarMediaLibraryHelper {

    const val ROOT_ID = "breeze_car_root"
    const val TAB_PERSONAL_FM = "breeze_tab_personal_fm"
    const val TAB_DAILY_RECOMMEND = "breeze_tab_daily_recommend"
    const val TAB_LIKED_SONGS = "breeze_tab_liked_songs"
    const val TAB_USER_PLAYLISTS = "breeze_tab_user_playlists"
    const val TAB_RECENT_HISTORY = "breeze_tab_recent_history"
    const val PREFIX_PLAYLIST = "breeze_playlist:"
    const val PREFIX_TRACK = "breeze_car_track|"
    const val PREFIX_VOICE_SEARCH = "breeze_voice_search:"

    private val voiceSearchResultsByKey = ConcurrentHashMap<String, List<MediaItem>>()

    /**
     * 规范化解析车载语音助手（小迪 / 小爱 / 小艺 / Google Assistant）下发的点歌查询与结构化 Extras：
     * - 优先组合 `EXTRA_MEDIA_ARTIST` + `EXTRA_MEDIA_TITLE` / `EXTRA_MEDIA_ALBUM`；
     * - 自动剥离口语前缀（“播放”“放一首”“我想听”等）；
     * - 返回空串时代表“随便放点音乐”，上层将自动切入猜你喜欢私人 FM 或恢复上次播放队列。
     */
    fun extractVoiceSearchKeyword(
        rawQuery: String?,
        artistExtra: String? = null,
        titleExtra: String? = null,
        albumExtra: String? = null
    ): String {
        val cleanArtist = artistExtra?.trim().orEmpty()
        val cleanTitle = titleExtra?.trim().orEmpty()
        val cleanAlbum = albumExtra?.trim().orEmpty()
        val structured = listOf(cleanArtist, cleanTitle, cleanAlbum)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .trim()
        val base = structured.ifBlank { rawQuery?.trim().orEmpty() }
        if (base.isBlank()) return ""
        val stripped = base
            .replace(Regex("^(?:请|帮我|给我)*\\s*(?:打开微风音乐(?:车机)?|用微风音乐(?:车机)?)?\\s*(?:播放|放一首|放首|放点|来一首|来首|我想听|听一下|听一首|搜一下|搜索)\\s*"), "")
            .replace(Regex("(?:的歌|这首歌|的音乐)$"), "")
            .trim()
        val genericMusicQueries = setOf("音乐", "歌曲", "歌", "好听的歌", "随便听听", "music", "songs", "play music")
        if (stripped.lowercase(Locale.ROOT) in genericMusicQueries) {
            return ""
        }
        return stripped
    }

    fun extractVoiceSearchKeyword(rawQuery: String?, extras: Bundle?): String {
        return extractVoiceSearchKeyword(
            rawQuery = rawQuery,
            artistExtra = extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_ARTIST),
            titleExtra = extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_TITLE),
            albumExtra = extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_ALBUM)
        )
    }

    suspend fun searchVoiceTracks(
        context: Context,
        rawQuery: String?,
        extras: Bundle? = null
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        VipManager.initialize(context.applicationContext)
        val activeUid = runCatching { VipManager.accounts.state.value.currentId }.getOrNull()
        clearLookupCachesIfAccountChanged(activeUid)

        val keyword = extractVoiceSearchKeyword(rawQuery, extras)
        val cacheKey = rawQuery?.trim().orEmpty()
        val parentId = "$PREFIX_VOICE_SEARCH${keyword.ifBlank { "fm_fallback" }}"
        val songs: List<Song> = if (keyword.isBlank()) {
            val fmCached = VipManager.discoverCache.getPersonalFm(activeUid)
            fmCached.ifEmpty {
                suspendRunCatching { VipManager.music.personalFm() }.getOrDefault(emptyList())
            }.take(30)
        } else {
            suspendRunCatching { VipManager.music.searchSongs(keyword, page = 1, pageSize = 25).songs }
                .getOrDefault(emptyList())
                .take(25)
        }
        queueLookupByParentId[parentId] = songs
        val items = songs.map { buildPlayableSongMediaItem(parentId, it) }
        voiceSearchResultsByKey[cacheKey] = items
        items
    }

    fun getCachedVoiceSearchResults(rawQuery: String?): List<MediaItem> {
        return voiceSearchResultsByKey[rawQuery?.trim().orEmpty()].orEmpty()
    }

    suspend fun resolveVoicePlayQueue(
        context: Context,
        rawQuery: String?,
        extras: Bundle? = null
    ): Pair<List<Song>, Boolean> = withContext(Dispatchers.IO) {
        VipManager.initialize(context.applicationContext)
        val activeUid = runCatching { VipManager.accounts.state.value.currentId }.getOrNull()
        val keyword = extractVoiceSearchKeyword(rawQuery, extras)
        if (keyword.isBlank()) {
            val fm = VipManager.discoverCache.getPersonalFm(activeUid).ifEmpty {
                suspendRunCatching { VipManager.music.personalFm() }.getOrDefault(emptyList())
            }.take(30)
            return@withContext fm to true
        }
        val found = suspendRunCatching { VipManager.music.searchSongs(keyword, page = 1, pageSize = 25).songs }
            .getOrDefault(emptyList())
            .take(25)
        found to false
    }

    // 主流国内外车联投屏与车载原生媒体中心包名白名单
    val TRUSTED_CAR_HOST_PACKAGES: Set<String> = setOf(
        "com.google.android.projection.gearhead", // Android Auto
        "com.android.car.media",                  // Android Automotive OS Media Center
        "com.android.car.carlauncher",            // AAOS Launcher
        "com.android.car.radio",
        "com.huawei.hicar",                       // 华为 HiCar
        "com.miui.carlink",                       // 小米 CarWith
        "com.carconnectivity.iccoa",              // ICCOA Carlink
        "com.oplus.ocar",                         // OPPO / 一加 / 真我 Car+
        "com.coloros.ocar",
        "com.vivo.car.networking",                // vivo Jovi InCar
        "com.vivo.carlauncher",
        "com.baidu.carlife",                      // 百度 CarLife+
        "com.meizu.flymelink",                    // Flyme Auto / Flyme Link
        "ecarx.launcher3",                        // 亿咖通座舱桌面
        "com.samsung.android.carlink"             // Samsung Auto
    )

    private val trackLookupById = ConcurrentHashMap<String, Song>()
    private val queueLookupByParentId = ConcurrentHashMap<String, List<Song>>()
    private val playlistMetaById = ConcurrentHashMap<String, Playlist>()

    @Volatile
    private var lastLoadedAccountUid: String? = null

    private fun clearLookupCachesIfAccountChanged(activeUid: String?) {
        val normalized = activeUid?.trim()?.takeIf { it.isNotEmpty() } ?: "guest"
        if (lastLoadedAccountUid != null && lastLoadedAccountUid != normalized) {
            trackLookupById.clear()
            queueLookupByParentId.clear()
            playlistMetaById.clear()
        }
        lastLoadedAccountUid = normalized
    }

    fun isKnownCarProjectionPackage(packageName: String): Boolean {
        val clean = packageName.trim()
        return clean in TRUSTED_CAR_HOST_PACKAGES
    }

    /**
     * 校验调用方是否为合法的车联投屏宿主应用（必须满足 Binder Kernel UID > 0 且与 PackageManager 登记 UID 一致）
     */
    fun isVerifiedCarProjectionController(
        packageManager: PackageManager,
        packageName: String,
        uid: Int
    ): Boolean {
        if (uid <= 0) return false
        val pkg = packageName.trim()
        if (!isKnownCarProjectionPackage(pkg)) return false
        return runCatching {
            val uidPackages = packageManager.getPackagesForUid(uid) ?: return@runCatching false
            if (pkg !in uidPackages) return@runCatching false
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            appInfo.uid == uid
        }.getOrDefault(false)
    }

    @OptIn(UnstableApi::class)
    fun buildRootMediaItem(): MediaItem {
        val extras = Bundle().apply {
            // 声明支持车载网格/列表混合展示与内容样式提示（对齐 Android Auto Media Styling 规范）
            putInt("android.media.browse.CONTENT_STYLE_SUPPORTED", 1)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
        }
        return MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("微风音乐 · 车载曲库")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    fun buildTopLevelBrowsableTabs(): List<MediaItem> = listOf(
        buildBrowsableFolderItem(
            mediaId = TAB_PERSONAL_FM,
            title = "猜你喜欢",
            subtitle = "私人 FM · 懂你的行车电台",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS
        ),
        buildBrowsableFolderItem(
            mediaId = TAB_DAILY_RECOMMEND,
            title = "每日推荐",
            subtitle = "今日精选 30 首好歌",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
        ),
        buildBrowsableFolderItem(
            mediaId = TAB_LIKED_SONGS,
            title = "我喜欢",
            subtitle = "红心收藏曲库",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
        ),
        buildBrowsableFolderItem(
            mediaId = TAB_USER_PLAYLISTS,
            title = "我的歌单",
            subtitle = "自建与收藏歌单",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
        ),
        buildBrowsableFolderItem(
            mediaId = TAB_RECENT_HISTORY,
            title = "最近播放",
            subtitle = "本机历史听歌记录",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        )
    )

    private fun buildBrowsableFolderItem(
        mediaId: String,
        title: String,
        subtitle: String,
        coverUrl: String = "",
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
    ): MediaItem {
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtist(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
        if (coverUrl.isNotBlank()) {
            runCatching { Uri.parse(coverUrl) }.getOrNull()?.let { metaBuilder.setArtworkUri(it) }
        }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metaBuilder.build())
            .build()
    }

    internal fun buildPlayableSongMediaItem(parentId: String, song: Song): MediaItem {
        val encodedId = "$PREFIX_TRACK$parentId|${song.id}"
        trackLookupById[song.id] = song
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(song.title.ifBlank { "未知歌曲" })
            .setArtist(song.artist.ifBlank { "未知歌手" })
            .setAlbumTitle(song.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        if (song.coverUrl.isNotBlank()) {
            runCatching { Uri.parse(song.coverUrl) }.getOrNull()?.let { metaBuilder.setArtworkUri(it) }
        }
        return MediaItem.Builder()
            .setMediaId(encodedId)
            .setMediaMetadata(metaBuilder.build())
            .build()
    }

    internal fun parseCarTrackMediaId(rawMediaId: String): Pair<String, String>? {
        if (!rawMediaId.startsWith(PREFIX_TRACK)) {
            return if (rawMediaId.isNotBlank()) "" to rawMediaId else null
        }
        val payload = rawMediaId.removePrefix(PREFIX_TRACK)
        val sep = payload.indexOf('|')
        if (sep < 0) return "" to payload
        val parentKey = payload.substring(0, sep)
        val songId = payload.substring(sep + 1)
        return if (songId.isNotBlank()) parentKey to songId else null
    }

    fun lookupSongAndContextQueue(rawMediaId: String): Pair<Song, List<Song>>? {
        val (parentKey, songId) = parseCarTrackMediaId(rawMediaId) ?: return null
        val parentQueue = if (parentKey.isNotBlank()) queueLookupByParentId[parentKey].orEmpty() else emptyList()
        val matchedSong = parentQueue.firstOrNull { it.id == songId }
            ?: trackLookupById[songId]
            ?: return null
        val effectiveQueue = if (parentQueue.isNotEmpty()) parentQueue else listOf(matchedSong)
        return matchedSong to effectiveQueue
    }

    internal fun registerTestQueueForLookup(parentId: String, songs: List<Song>) {
        queueLookupByParentId[parentId] = songs
        songs.forEach { trackLookupById[it.id] = it }
    }

    suspend fun loadChildrenForParent(context: Context, parentId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        VipManager.initialize(context.applicationContext)
        val activeUid = runCatching { VipManager.accounts.state.value.currentId }.getOrNull()
        clearLookupCachesIfAccountChanged(activeUid)

        when {
            parentId == ROOT_ID -> buildTopLevelBrowsableTabs()

            parentId == TAB_PERSONAL_FM -> {
                val cached = VipManager.discoverCache.getPersonalFm(activeUid)
                val songs = cached.ifEmpty {
                    suspendRunCatching { VipManager.music.personalFm() }.getOrDefault(emptyList())
                        .also { if (it.isNotEmpty()) VipManager.discoverCache.savePersonalFm(activeUid, it) }
                }.take(50)
                queueLookupByParentId[parentId] = songs
                songs.map { buildPlayableSongMediaItem(parentId, it) }
            }

            parentId == TAB_DAILY_RECOMMEND -> {
                val cached = VipManager.discoverCache.getDailyRecommend(activeUid)
                val songs = cached.ifEmpty {
                    suspendRunCatching { VipManager.music.dailyRecommend() }.getOrDefault(emptyList())
                        .also { if (it.isNotEmpty()) VipManager.discoverCache.saveDailyRecommend(activeUid, it) }
                }.take(50)
                queueLookupByParentId[parentId] = songs
                songs.map { buildPlayableSongMediaItem(parentId, it) }
            }

            parentId == TAB_LIKED_SONGS -> {
                val cachedLiked = VipManager.discoverCache.getLikedSongs(activeUid)
                val songs = cachedLiked.ifEmpty {
                    val playlists = VipManager.discoverCache.getUserPlaylists(activeUid).ifEmpty {
                        suspendRunCatching { VipManager.music.userPlaylists() }.getOrDefault(emptyList())
                    }
                    val likedPl = playlists.firstOrNull { it.isLiked || it.title == "我喜欢" }
                    if (likedPl != null) {
                        suspendRunCatching { VipManager.music.playlistSongs(likedPl) }.getOrDefault(emptyList())
                            .also { if (it.isNotEmpty()) VipManager.discoverCache.saveLikedSongs(activeUid, it) }
                    } else emptyList()
                }.take(80)
                queueLookupByParentId[parentId] = songs
                songs.map { buildPlayableSongMediaItem(parentId, it) }
            }

            parentId == TAB_USER_PLAYLISTS -> {
                val localOnlyPlaylists = runCatching { VipManager.localPlaylists.getLocalPlaylists() }.getOrDefault(emptyList())
                val cachedPlaylists = VipManager.discoverCache.getUserPlaylists(activeUid)
                val cloudPlaylists = cachedPlaylists.ifEmpty {
                    suspendRunCatching { VipManager.music.userPlaylists() }.getOrDefault(emptyList())
                        .also { if (it.isNotEmpty()) VipManager.discoverCache.saveUserPlaylists(activeUid, it) }
                }.filterNot { it.isLiked }

                val mergedPlaylists = (localOnlyPlaylists + cloudPlaylists)
                    .distinctBy { it.id }
                    .take(40)

                mergedPlaylists.map { pl ->
                    playlistMetaById[pl.id] = pl
                    val subLabel = when {
                        pl.isPureLocalPlaylist -> if (pl.trackCount > 0) "本机歌单 · ${pl.trackCount} 首" else "本机歌单"
                        pl.trackCount > 0 -> "${pl.trackCount} 首"
                        pl.isCreatedByMe -> "自建歌单"
                        else -> "收藏歌单"
                    }
                    buildBrowsableFolderItem(
                        mediaId = "$PREFIX_PLAYLIST${pl.id}",
                        title = pl.title.ifBlank { "未命名歌单" },
                        subtitle = subLabel,
                        coverUrl = pl.coverUrl,
                        mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST
                    )
                }
            }

            parentId.startsWith(PREFIX_PLAYLIST) -> {
                val plId = parentId.removePrefix(PREFIX_PLAYLIST)
                val songs = if (LocalPlaylistRepository.isPureLocalPlaylistId(plId)) {
                    runCatching {
                        VipManager.localPlaylists.getLocalPlaylistTracks(plId, VipManager.localMusic)
                    }.getOrDefault(emptyList()).take(100)
                } else {
                    val cachedSongs = VipManager.discoverCache.getPlaylistSongs(plId)
                    cachedSongs.ifEmpty {
                        val plMeta = playlistMetaById[plId]
                            ?: VipManager.discoverCache.getUserPlaylists(activeUid).firstOrNull { it.id == plId }
                        if (plMeta != null) {
                            suspendRunCatching { VipManager.music.playlistSongs(plMeta) }.getOrDefault(emptyList())
                                .also { if (it.isNotEmpty()) VipManager.discoverCache.savePlaylistSongs(plId, it) }
                        } else {
                            suspendRunCatching { VipManager.music.fetchPlaylistSongsAll(plId) }.getOrDefault(emptyList())
                        }
                    }.take(100)
                }
                queueLookupByParentId[parentId] = songs
                songs.map { buildPlayableSongMediaItem(parentId, it) }
            }

            parentId == TAB_RECENT_HISTORY -> {
                val songs = runCatching { VipManager.history.get(activeUid) }.getOrDefault(emptyList()).take(60)
                queueLookupByParentId[parentId] = songs
                songs.map { buildPlayableSongMediaItem(parentId, it) }
            }

            else -> emptyList()
        }
    }

    /**
     * 解析车机点播单曲的真实高音质流媒体地址与缓存 Key，返回可直接交给 ExoPlayer 播放的 MediaItem
     */
    suspend fun resolvePlayableItemForCarSelection(
        context: Context,
        song: Song
    ): Pair<Song, MediaItem>? = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        VipManager.initialize(appCtx)
        val prefs = appCtx.getSharedPreferences("concept_player_prefs", Context.MODE_PRIVATE)
        val savedQuality = runCatching {
            AudioQuality.valueOf(
                prefs.getString("preferred_quality_v2", AudioQuality.HIGH.name) ?: AudioQuality.HIGH.name
            )
        }.getOrDefault(AudioQuality.HIGH)
        val wifiOnlyLossless = prefs.getBoolean("wifi_only_lossless", false)
        val isOnCellular = runCatching {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        }.getOrDefault(false)
        val effectiveQuality = if (wifiOnlyLossless && isOnCellular &&
            (savedQuality == AudioQuality.LOSSLESS || savedQuality == AudioQuality.HI_RES)
        ) {
            AudioQuality.HIGH
        } else {
            savedQuality
        }
        val reqQ = if (effectiveQuality in song.effectiveQualities) effectiveQuality else song.highestQuality
        val playbackResult = suspendRunCatching {
            VipManager.music.resolvePlayback(song, reqQ)
        }.getOrNull() ?: return@withContext null
        if (playbackResult.url.isBlank()) return@withContext null

        val resolvedHash = playbackResult.resolvedHash.ifBlank {
            song.hashForQuality(playbackResult.actualQuality).ifBlank { song.hash }
        }
        val cacheKey = AudioCacheManager.buildCacheKey(
            hash = resolvedHash,
            qualityApiValue = playbackResult.actualQuality.apiValue,
            isFreePart = playbackResult.isFreePart
        )
        val resolvedSong = song.copy(
            playUrl = playbackResult.url,
            actualPlayQuality = playbackResult.actualQuality,
            bitrateKbps = playbackResult.bitrateKbps.takeIf { it > 0 } ?: song.bitrateKbps,
            fileSizeByte = playbackResult.fileSizeByte.takeIf { it > 0L } ?: song.fileSizeByte
        )
        val item = MediaItem.Builder()
            .setMediaId(resolvedSong.id)
            .setUri(playbackResult.url)
            .setCustomCacheKey(cacheKey)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(resolvedSong.title)
                    .setArtist(resolvedSong.artist)
                    .setDisplayTitle(resolvedSong.title)
                    .setSubtitle(resolvedSong.artist)
                    .setAlbumTitle(resolvedSong.album)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setArtworkUri(resolvedSong.coverUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .build()
            )
            .build()
        resolvedSong to item
    }
}
