package com.concept.music.service

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.Immutable
import com.concept.music.data.kugou.NetworkClientProvider
import com.concept.music.data.model.AudioQuality
import com.concept.music.data.model.Song
import com.concept.music.data.vip.VipManager
import com.concept.music.util.SecurityGuards
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

enum class DownloadTaskStatus(val label: String) {
    QUEUED("排队中"),
    RESOLVING("解析音源..."),
    DOWNLOADING("下载音频..."),
    EMBEDDING("内嵌封面与歌词..."),
    COMPLETED("已完成"),
    SKIPPED_EXISTING("本地已存在"),
    FAILED("下载失败")
}

@Immutable
data class DownloadTaskItem(
    val taskId: String,
    val song: Song,
    val requestedQuality: AudioQuality,
    val actualQuality: AudioQuality = requestedQuality,
    val status: DownloadTaskStatus = DownloadTaskStatus.QUEUED,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val fileName: String = "",
    val savedAudioPath: String = "",
    val savedLrcPath: String = "",
    val embeddedCover: Boolean = false,
    val embeddedLyric: Boolean = false,
    val embeddedMetadata: Boolean = false,
    val errorMessage: String? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Immutable
data class DownloadShortcutBadgeState(
    val activeCount: Int = 0,
    val overallProgress: Float = 0f,
    val completedInSessionCount: Int = 0,
    val failedInSessionCount: Int = 0,
    val hasUnseenSessionCompletion: Boolean = false
)

@Immutable
data class MusicDownloadConfig(
    val downloadDirectoryPath: String = MusicDownloadManager.DEFAULT_AUDIO_DOWNLOAD_PATH,
    val lyricDirectoryPath: String = MusicDownloadManager.DEFAULT_LYRIC_DOWNLOAD_PATH,
    val defaultDownloadQuality: AudioQuality = AudioQuality.HI_RES,
    val embedHdCover: Boolean = true,
    val embedLyrics: Boolean = true,
    val saveStandaloneLrcFile: Boolean = false,
    val includeTranslationInLyric: Boolean = true,
    val skipExistingFiles: Boolean = true
)

/**
 * 酷狗概念版多音质音乐下载与元数据/高清封面/歌词内嵌管理器
 * 参考实现：lisalee23042605/kgmusicbot & MakcRe/KuGouMusicApi
 *
 * 核心特性：
 * 1. 多音质自动解析与平滑降级：支持 Hi-Res (res_hash) -> SQ 无损 FLAC (sq_hash) -> 高品 320K (hq_hash) -> 标准 128K；
 * 2. 歌曲详细信息自动内嵌：标题、歌手、专辑名、发行日期、音质与 Hash 备注写入 ID3v2.3 / FLAC Vorbis Comment；
 * 3. 高清封面默认内嵌：优先升级酷狗封面 URL 为 1000x1000 高清大图（或通过 album_id 查询专辑封面），内嵌至 APIC / FLAC Picture；
 * 4. 歌词默认内嵌 + 可选独立保存 `.lrc` 文件：自动获取 LRC/KRC 双语时间轴歌词，默认内嵌至 USLT / LYRICS + UNSYNCEDLYRICS，
 *    当开启「同时独立保存 .lrc 歌词文件」时，自动写入用户自定义的歌词保存路径；
 * 5. 自定义下载保存路径与歌词保存路径：支持预设目录、手动输入路径与系统文件夹选择器 (SAF Tree Uri 转换)，兼容 Android Scoped Storage 与 MediaStore。
 */
object MusicDownloadManager {
    private const val PREFS_NAME = "concept_download_prefs"
    private const val KEY_AUDIO_DIR = "download_audio_dir"
    private const val KEY_LYRIC_DIR = "download_lyric_dir"
    private const val KEY_DEFAULT_QUALITY = "download_default_quality"
    private const val KEY_EMBED_HD_COVER = "download_embed_hd_cover"
    private const val KEY_EMBED_LYRICS = "download_embed_lyrics"
    private const val KEY_SAVE_STANDALONE_LRC = "download_save_standalone_lrc"
    private const val KEY_INCLUDE_TRANSLATION_LRC = "download_include_translation_lrc"
    private const val KEY_SKIP_EXISTING = "download_skip_existing"
    private const val KEY_HISTORY_JSON = "download_tasks_history_json"

    const val DEFAULT_AUDIO_DOWNLOAD_PATH = "/storage/emulated/0/Music/BreezeMusic"
    const val DEFAULT_LYRIC_DOWNLOAD_PATH = "/storage/emulated/0/Music/BreezeMusic/Lyrics"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 有界并发信号量：限制同时最多 2 路音频流下载与标签内嵌，避免批量下载数十首时触发接口限流或内存抖动
    private val downloadSemaphore = Semaphore(permits = 2)
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    private val _config = MutableStateFlow(MusicDownloadConfig())
    val config = _config.asStateFlow()

    private val _tasks = MutableStateFlow<List<DownloadTaskItem>>(emptyList())
    val tasks = _tasks.asStateFlow()

    private val _shortcutBadgeState = MutableStateFlow(DownloadShortcutBadgeState())
    val shortcutBadgeState = _shortcutBadgeState.asStateFlow()

    private val sessionTrackedTaskIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    internal fun isActiveStatus(status: DownloadTaskStatus): Boolean =
        status == DownloadTaskStatus.QUEUED ||
            status == DownloadTaskStatus.RESOLVING ||
            status == DownloadTaskStatus.DOWNLOADING ||
            status == DownloadTaskStatus.EMBEDDING

    private fun refreshShortcutBadgeState(triggerUnseenOnIdle: Boolean = false) {
        val allTasks = _tasks.value
        val activeTasks = allTasks.filter { isActiveStatus(it.status) }
        val activeCount = activeTasks.size
        val avgProgress = if (activeTasks.isNotEmpty()) {
            activeTasks.map { it.progress.coerceIn(0f, 1f) }.average().toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        val trackedSnapshot = synchronized(sessionTrackedTaskIds) { sessionTrackedTaskIds.toSet() }
        val sessionTasks = if (trackedSnapshot.isEmpty()) emptyList() else allTasks.filter { it.taskId in trackedSnapshot }
        val completedCnt = sessionTasks.count {
            it.status == DownloadTaskStatus.COMPLETED || it.status == DownloadTaskStatus.SKIPPED_EXISTING
        }
        val failedCnt = sessionTasks.count { it.status == DownloadTaskStatus.FAILED }
        val prevUnseen = _shortcutBadgeState.value.hasUnseenSessionCompletion
        val nextUnseen = when {
            activeCount > 0 -> false
            trackedSnapshot.isEmpty() -> false
            triggerUnseenOnIdle && (completedCnt > 0 || failedCnt > 0) -> true
            else -> prevUnseen && (completedCnt > 0 || failedCnt > 0)
        }
        _shortcutBadgeState.value = DownloadShortcutBadgeState(
            activeCount = activeCount,
            overallProgress = avgProgress,
            completedInSessionCount = completedCnt,
            failedInSessionCount = failedCnt,
            hasUnseenSessionCompletion = nextUnseen
        )
    }

    fun markDownloadQueueViewed() {
        val activeIds = _tasks.value.filter { isActiveStatus(it.status) }.map { it.taskId }.toHashSet()
        synchronized(sessionTrackedTaskIds) {
            sessionTrackedTaskIds.retainAll(activeIds)
        }
        refreshShortcutBadgeState(triggerUnseenOnIdle = false)
    }

    private val KUGOU_THUMB_SIZE_REGEX = Regex("""/(100|120|150|200|240|300|400|480)/""")

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p

        val defaultAudioPath = resolveDefaultPublicMusicDir("BreezeMusic")
        val defaultLyricPath = resolveDefaultPublicMusicDir("BreezeMusic/Lyrics")
        val appDataDir = runCatching { ctx.dataDir?.absolutePath }.getOrNull()

        val savedAudioDir = SecurityGuards.sanitizeSafeExportDirectoryPath(
            rawPath = p.getString(KEY_AUDIO_DIR, defaultAudioPath),
            defaultPath = defaultAudioPath,
            appDataDirPath = appDataDir
        )
        val savedLyricDir = SecurityGuards.sanitizeSafeExportDirectoryPath(
            rawPath = p.getString(KEY_LYRIC_DIR, defaultLyricPath),
            defaultPath = defaultLyricPath,
            appDataDirPath = appDataDir
        )
        val savedQuality = runCatching {
            AudioQuality.valueOf(p.getString(KEY_DEFAULT_QUALITY, AudioQuality.HI_RES.name) ?: AudioQuality.HI_RES.name)
        }.getOrDefault(AudioQuality.HI_RES)
        val savedEmbedCover = p.getBoolean(KEY_EMBED_HD_COVER, true)
        val savedEmbedLyrics = p.getBoolean(KEY_EMBED_LYRICS, true)
        val savedStandaloneLrc = p.getBoolean(KEY_SAVE_STANDALONE_LRC, false)
        val savedIncludeTrans = p.getBoolean(KEY_INCLUDE_TRANSLATION_LRC, true)
        val savedSkipExisting = p.getBoolean(KEY_SKIP_EXISTING, true)

        _config.value = MusicDownloadConfig(
            downloadDirectoryPath = savedAudioDir,
            lyricDirectoryPath = savedLyricDir,
            defaultDownloadQuality = savedQuality,
            embedHdCover = savedEmbedCover,
            embedLyrics = savedEmbedLyrics,
            saveStandaloneLrcFile = savedStandaloneLrc,
            includeTranslationInLyric = savedIncludeTrans,
            skipExistingFiles = savedSkipExisting
        )

        val historyJson = p.getString(KEY_HISTORY_JSON, null)
        if (!historyJson.isNullOrBlank()) {
            runCatching {
                val type = object : TypeToken<List<DownloadTaskItem>>() {}.type
                val restored: List<DownloadTaskItem>? = com.concept.music.data.kugou.KugouSigner.gson.fromJson(historyJson, type)
                if (!restored.isNullOrEmpty()) {
                    _tasks.value = restored.take(100)
                }
            }
        }
    }

    fun resetForTest() {
        _config.value = MusicDownloadConfig()
        _tasks.value = emptyList()
        synchronized(sessionTrackedTaskIds) { sessionTrackedTaskIds.clear() }
        _shortcutBadgeState.value = DownloadShortcutBadgeState()
    }

    internal fun applyTasksForTest(
        tasks: List<DownloadTaskItem>,
        trackedIds: Set<String> = emptySet(),
        triggerUnseenOnIdle: Boolean = false
    ) {
        _tasks.value = tasks
        synchronized(sessionTrackedTaskIds) {
            sessionTrackedTaskIds.clear()
            sessionTrackedTaskIds.addAll(trackedIds)
        }
        refreshShortcutBadgeState(triggerUnseenOnIdle = triggerUnseenOnIdle)
    }

    private fun resolveDefaultPublicMusicDir(subFolder: String): String {
        return runCatching {
            val pubMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            if (pubMusic != null && pubMusic.absolutePath.isNotBlank()) {
                File(pubMusic, subFolder).absolutePath
            } else {
                "/storage/emulated/0/Music/$subFolder"
            }
        }.getOrDefault("/storage/emulated/0/Music/$subFolder")
    }

    /**
     * 将系统文件夹选择器 (`ActivityResultContracts.OpenDocumentTree`) 返回的 `content://` Tree Uri
     * 解析为可读写的手机存储文件夹路径（如 `/storage/emulated/0/Music/MySongs`），并过滤路径穿越与系统目录
     */
    fun resolveTreeUriToFilePath(context: Context, treeUri: Uri): String? {
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val split = docId.split(":")
            if (split.isNotEmpty()) {
                val type = split[0].trim()
                if (!type.matches(Regex("^[a-zA-Z0-9_-]{1,32}$"))) return@runCatching null
                val relativePath = if (split.size > 1) split[1].trimStart('/') else ""
                val baseRoot = if (type.equals("primary", ignoreCase = true)) {
                    runCatching { Environment.getExternalStorageDirectory().absolutePath }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: "/storage/emulated/0"
                } else {
                    "/storage/$type"
                }
                val rawResolved = if (relativePath.isBlank()) baseRoot else "$baseRoot/$relativePath"
                val sanitized = SecurityGuards.sanitizeSafeExportDirectoryPath(
                    rawPath = rawResolved,
                    defaultPath = "",
                    appDataDirPath = runCatching { context.dataDir?.absolutePath }.getOrNull()
                )
                sanitized.takeIf { it.isNotBlank() }
            } else null
        }.getOrNull()
    }

    fun setDownloadDirectoryPath(path: String) {
        val cleaned = SecurityGuards.sanitizeSafeExportDirectoryPath(
            rawPath = path,
            defaultPath = DEFAULT_AUDIO_DOWNLOAD_PATH,
            appDataDirPath = runCatching { appContext?.dataDir?.absolutePath }.getOrNull()
        )
        _config.update { it.copy(downloadDirectoryPath = cleaned) }
        prefs?.edit()?.putString(KEY_AUDIO_DIR, cleaned)?.apply()
    }

    fun setLyricDirectoryPath(path: String) {
        val cleaned = SecurityGuards.sanitizeSafeExportDirectoryPath(
            rawPath = path,
            defaultPath = DEFAULT_LYRIC_DOWNLOAD_PATH,
            appDataDirPath = runCatching { appContext?.dataDir?.absolutePath }.getOrNull()
        )
        _config.update { it.copy(lyricDirectoryPath = cleaned) }
        prefs?.edit()?.putString(KEY_LYRIC_DIR, cleaned)?.apply()
    }

    fun resetDirectoriesToDefault() {
        val defaultAudio = resolveDefaultPublicMusicDir("BreezeMusic")
        val defaultLyric = resolveDefaultPublicMusicDir("BreezeMusic/Lyrics")
        _config.update {
            it.copy(
                downloadDirectoryPath = defaultAudio,
                lyricDirectoryPath = defaultLyric
            )
        }
        prefs?.edit()
            ?.putString(KEY_AUDIO_DIR, defaultAudio)
            ?.putString(KEY_LYRIC_DIR, defaultLyric)
            ?.apply()
    }

    fun setDefaultDownloadQuality(quality: AudioQuality) {
        _config.update { it.copy(defaultDownloadQuality = quality) }
        prefs?.edit()?.putString(KEY_DEFAULT_QUALITY, quality.name)?.apply()
    }

    fun setEmbedHdCover(enabled: Boolean) {
        _config.update { it.copy(embedHdCover = enabled) }
        prefs?.edit()?.putBoolean(KEY_EMBED_HD_COVER, enabled)?.apply()
    }

    fun setEmbedLyrics(enabled: Boolean) {
        _config.update { it.copy(embedLyrics = enabled) }
        prefs?.edit()?.putBoolean(KEY_EMBED_LYRICS, enabled)?.apply()
    }

    fun setSaveStandaloneLrcFile(enabled: Boolean) {
        _config.update { it.copy(saveStandaloneLrcFile = enabled) }
        prefs?.edit()?.putBoolean(KEY_SAVE_STANDALONE_LRC, enabled)?.apply()
    }

    fun setIncludeTranslationInLyric(enabled: Boolean) {
        _config.update { it.copy(includeTranslationInLyric = enabled) }
        prefs?.edit()?.putBoolean(KEY_INCLUDE_TRANSLATION_LRC, enabled)?.apply()
    }

    fun setSkipExistingFiles(enabled: Boolean) {
        _config.update { it.copy(skipExistingFiles = enabled) }
        prefs?.edit()?.putBoolean(KEY_SKIP_EXISTING, enabled)?.apply()
    }

    fun clearCompletedTasks() {
        _tasks.update { list ->
            list.filter {
                it.status == DownloadTaskStatus.QUEUED ||
                    it.status == DownloadTaskStatus.RESOLVING ||
                    it.status == DownloadTaskStatus.DOWNLOADING ||
                    it.status == DownloadTaskStatus.EMBEDDING
            }
        }
        persistTasksHistory()
    }

    private fun persistTasksHistory() {
        val p = prefs ?: return
        runCatching {
            val snapshot = _tasks.value.take(60)
            val json = com.concept.music.data.kugou.KugouSigner.gson.toJson(snapshot)
            p.edit().putString(KEY_HISTORY_JSON, json).apply()
        }
    }

    /**
     * 生成安全规范的「歌手 - 歌名」基础文件名（过滤非法字符、路径穿越段并限制 UTF-8 文件名长度防 ENAMETOOLONG）
     */
    internal fun buildSafeBaseFileName(artist: String, title: String): String {
        val cleanArtist = artist.trim().ifBlank { "未知歌手" }.take(32)
        val cleanTitle = title.trim().ifBlank { "未知歌曲" }.take(44)
        val raw = "$cleanArtist - $cleanTitle"
        return raw
            .replace(Regex("""[/\\:*?"<>|\u0000-\u001F]+"""), "_")
            .replace("、", "_")
            .replace("..", "_")
            .trim()
            .trimEnd('.')
            .take(80)
            .ifBlank { "BreezeMusic_Track_${System.currentTimeMillis()}" }
    }

    /**
     * 将酷狗封面地址升级为高清封面 URL（优先 1000px，回退 480px，对齐 kgmusicbot 的 get_album_cover 逻辑与项目海报高清策略）
     */
    internal fun buildHighDefCoverUrlCandidates(rawCoverUrl: String): List<String> {
        val trimmed = rawCoverUrl.trim()
        if (trimmed.isBlank()) return emptyList()
        val url1000 = trimmed.replace("{size}", "1000").let { u ->
            if (KUGOU_THUMB_SIZE_REGEX.containsMatchIn(u)) KUGOU_THUMB_SIZE_REGEX.replace(u, "/1000/") else u
        }
        val url480 = trimmed.replace("{size}", "480").let { u ->
            if (KUGOU_THUMB_SIZE_REGEX.containsMatchIn(u)) KUGOU_THUMB_SIZE_REGEX.replace(u, "/480/") else u
        }
        return listOf(url1000, url480, trimmed)
            .distinct()
            .filter { SecurityGuards.isSafeExternalMediaUrl(it) }
    }

    /**
     * 根据解析出的播放 URL 与实际音质推断文件扩展名（.flac / .mp3 / .ogg / .m4a）
     */
    internal fun deduceAudioFileExtension(url: String, actualQuality: AudioQuality): String {
        val cleanPath = url.substringBefore('?').lowercase(Locale.ROOT)
        return when {
            cleanPath.endsWith(".flac") -> ".flac"
            cleanPath.endsWith(".mp3") -> ".mp3"
            cleanPath.endsWith(".ogg") -> ".ogg"
            cleanPath.endsWith(".m4a") -> ".m4a"
            cleanPath.endsWith(".aac") -> ".aac"
            cleanPath.endsWith(".wav") -> ".wav"
            cleanPath.endsWith(".ape") -> ".ape"
            actualQuality == AudioQuality.HI_RES || actualQuality == AudioQuality.LOSSLESS -> ".flac"
            else -> ".mp3"
        }
    }

    internal fun songDownloadDedupeKey(song: Song): String {
        val primaryHash = song.hash.trim().lowercase(Locale.ROOT)
        if (primaryHash.isNotBlank()) return "h:$primaryHash"
        val cleanId = song.id.trim().lowercase(Locale.ROOT)
        if (cleanId.isNotBlank()) return "id:$cleanId"
        return "ta:${song.artist.trim().lowercase(Locale.ROOT)}_${song.title.trim().lowercase(Locale.ROOT)}"
    }

    internal fun filterPendingBatchDownloadSongs(
        requestedSongs: List<Song>,
        existingTasks: List<DownloadTaskItem> = _tasks.value
    ): List<Song> {
        val activeKeys = existingTasks.asSequence()
            .filter {
                it.status == DownloadTaskStatus.QUEUED ||
                    it.status == DownloadTaskStatus.RESOLVING ||
                    it.status == DownloadTaskStatus.DOWNLOADING ||
                    it.status == DownloadTaskStatus.EMBEDDING
            }
            .map { songDownloadDedupeKey(it.song) }
            .toHashSet()
        val seenInBatch = HashSet<String>()
        return requestedSongs.filter { song ->
            val key = songDownloadDedupeKey(song)
            key !in activeKeys && seenInBatch.add(key)
        }
    }

    /**
     * 触发多曲批量下载任务（严格受权限管理 MUSIC_DOWNLOAD 开关管控，内置队列去重、2 路有界并发及汇总提示）
     * @return 成功加入下载队列的曲目数量
     */
    fun enqueueBatchDownload(
        context: Context,
        songs: List<Song>,
        quality: AudioQuality = _config.value.defaultDownloadQuality,
        onPermissionRequired: ((Array<String>) -> Unit)? = null
    ): Int {
        if (songs.isEmpty()) return 0
        if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.NETWORK_ACCESS)) {
            Toast.makeText(context, "已在权限管理中关闭「网络访问」权限，无法下载音乐", Toast.LENGTH_SHORT).show()
            return 0
        }
        if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.MUSIC_DOWNLOAD)) {
            Toast.makeText(context, "音乐下载默认关闭，请先在「设置 - 权限管理」中手动开启「音乐下载与歌词保存」开关", Toast.LENGTH_LONG).show()
            return 0
        }
        if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.STORAGE_EXPORT)) {
            Toast.makeText(context, "已在权限管理中关闭「存储读写与相册导出」权限，请先在设置-权限管理中开启", Toast.LENGTH_LONG).show()
            return 0
        }
        if (!AppPermissionManager.hasMusicDownloadStoragePermission(context)) {
            val perms = AppPermissionManager.requiredStorageRuntimePermissions()
            if (onPermissionRequired != null && perms.isNotEmpty()) {
                onPermissionRequired(perms)
                return 0
            }
        }

        val toEnqueue = filterPendingBatchDownloadSongs(songs)
        if (toEnqueue.isEmpty()) {
            Toast.makeText(context, "所选歌曲均已在下载队列中", Toast.LENGTH_SHORT).show()
            return 0
        }

        val batchBaseTs = System.currentTimeMillis()
        val newTasks = toEnqueue.mapIndexed { idx, song ->
            val safeSongToken = SecurityGuards.sanitizeStagingToken(song.id.ifBlank { song.hash }, fallback = "song")
            DownloadTaskItem(
                taskId = "${safeSongToken}_${quality.apiValue}_${batchBaseTs}_$idx",
                song = song,
                requestedQuality = quality,
                status = DownloadTaskStatus.QUEUED,
                createdAtMs = batchBaseTs + idx
            )
        }
        synchronized(sessionTrackedTaskIds) {
            newTasks.forEach { sessionTrackedTaskIds.add(it.taskId) }
        }
        _tasks.update { newTasks + it }
        refreshShortcutBadgeState(triggerUnseenOnIdle = false)
        Toast.makeText(
            context,
            "已将 ${newTasks.size} 首歌曲加入下载队列 (${quality.label})，可在「我的」页右上角或「设置 → 下载」查看进度",
            Toast.LENGTH_LONG
        ).show()

        val appCtx = context.applicationContext
        scope.launch {
            val outcomes = coroutineScope {
                newTasks.map { task ->
                    async {
                        executeDownloadTask(appCtx, task, silentToasts = true)
                    }
                }.awaitAll()
            }
            val completedCnt = outcomes.count { it == DownloadTaskStatus.COMPLETED }
            val skippedCnt = outcomes.count { it == DownloadTaskStatus.SKIPPED_EXISTING }
            val failedCnt = outcomes.count { it == DownloadTaskStatus.FAILED }
            val summary = buildString {
                append("批量下载完成：")
                val parts = mutableListOf<String>()
                if (completedCnt > 0) parts.add("成功 $completedCnt 首")
                if (skippedCnt > 0) parts.add("已存在跳过 $skippedCnt 首")
                if (failedCnt > 0) parts.add("失败 $failedCnt 首")
                append(parts.joinToString("，").ifBlank { "共处理 ${newTasks.size} 首" })
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(appCtx, summary, Toast.LENGTH_LONG).show()
            }
        }
        return newTasks.size
    }

    /**
     * 触发单曲下载任务（含存储与网络权限检查、多音质解析、音频流下载、高清封面与歌词内嵌、独立 .lrc 保存）
     */
    fun enqueueDownload(
        context: Context,
        song: Song,
        quality: AudioQuality = _config.value.defaultDownloadQuality,
        onPermissionRequired: ((Array<String>) -> Unit)? = null
    ) {
        if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.NETWORK_ACCESS)) {
            Toast.makeText(context, "已在权限管理中关闭「网络访问」权限，无法下载音乐", Toast.LENGTH_SHORT).show()
            return
        }
        if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.MUSIC_DOWNLOAD)) {
            Toast.makeText(context, "音乐下载默认关闭，请先在「设置 - 权限管理」中手动开启「音乐下载与歌词保存」开关", Toast.LENGTH_LONG).show()
            return
        }
        if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.STORAGE_EXPORT)) {
            Toast.makeText(context, "已在权限管理中关闭「存储读写与相册导出」权限，请先在设置-权限管理中开启", Toast.LENGTH_LONG).show()
            return
        }
        if (!AppPermissionManager.hasMusicDownloadStoragePermission(context)) {
            val perms = AppPermissionManager.requiredStorageRuntimePermissions()
            if (onPermissionRequired != null && perms.isNotEmpty()) {
                onPermissionRequired(perms)
                return
            }
        }

        val safeSongToken = SecurityGuards.sanitizeStagingToken(song.id.ifBlank { song.hash }, fallback = "song")
        val taskId = "${safeSongToken}_${quality.apiValue}_${System.currentTimeMillis()}"
        val newTask = DownloadTaskItem(
            taskId = taskId,
            song = song,
            requestedQuality = quality,
            status = DownloadTaskStatus.QUEUED
        )
        synchronized(sessionTrackedTaskIds) {
            sessionTrackedTaskIds.add(newTask.taskId)
        }
        _tasks.update { listOf(newTask) + it }
        refreshShortcutBadgeState(triggerUnseenOnIdle = false)
        Toast.makeText(
            context,
            "已开始下载：《${song.title}》(${quality.label})",
            Toast.LENGTH_SHORT
        ).show()

        scope.launch {
            executeDownloadTask(context.applicationContext, newTask, silentToasts = false)
        }
    }

    private fun updateTask(taskId: String, transform: (DownloadTaskItem) -> DownloadTaskItem) {
        var becameTerminal = false
        _tasks.update { list ->
            list.map { item ->
                if (item.taskId == taskId) {
                    val next = transform(item)
                    if (isActiveStatus(item.status) && !isActiveStatus(next.status)) {
                        becameTerminal = true
                    }
                    next
                } else item
            }
        }
        refreshShortcutBadgeState(triggerUnseenOnIdle = becameTerminal)
    }

    private suspend fun executeDownloadTask(
        context: Context,
        initialTask: DownloadTaskItem,
        silentToasts: Boolean = false
    ): DownloadTaskStatus = downloadSemaphore.withPermit {
        val taskId = initialTask.taskId
        val currentCfg = _config.value
        try {
            updateTask(taskId) { it.copy(status = DownloadTaskStatus.RESOLVING, progress = 0.05f) }

            // 1. 补全歌曲完整音质规格 (res_hash / sq_hash / 320_hash) 与真实专辑详情（对齐 kgmusicbot fetch_gateway_track_info）
            val enrichedSong = runCatching {
                VipManager.music.enrichSongsPrivilegeLite(listOf(initialTask.song)).firstOrNull()
            }.getOrNull() ?: initialTask.song

            val (resolvedAlbumName, resolvedAlbumId) = runCatching {
                VipManager.music.resolveSongAlbumMeta(enrichedSong)
            }.getOrDefault(enrichedSong.album to enrichedSong.albumId)

            val fullSong = enrichedSong.copy(
                album = resolvedAlbumName.ifBlank { enrichedSong.album },
                albumId = resolvedAlbumId.ifBlank { enrichedSong.albumId }
            )

            // 2. 按请求音质解析最佳可用下载流（内部支持 Hi-Res -> SQ 无损 -> 高品 320K -> 标准 128K 自动降级）
            val playbackResult = VipManager.music.resolvePlayback(
                song = fullSong,
                preferredQuality = initialTask.requestedQuality,
                useAccompaniment = false
            )
            if (playbackResult.url.isBlank()) {
                throw IllegalStateException("未能获取到有效的音频下载链接")
            }

            val actualQuality = playbackResult.actualQuality
            val ext = deduceAudioFileExtension(playbackResult.url, actualQuality)
            val safeBaseName = buildSafeBaseFileName(fullSong.artist, fullSong.title)
            val audioFileName = "$safeBaseName$ext"
            val appDataDir = runCatching { context.applicationInfo?.dataDir }.getOrNull()
            val safeAudioDirPath = SecurityGuards.sanitizeSafeExportDirectoryPath(
                rawPath = currentCfg.downloadDirectoryPath,
                defaultPath = DEFAULT_AUDIO_DOWNLOAD_PATH,
                appDataDirPath = appDataDir
            )
            val safeLyricDirPath = SecurityGuards.sanitizeSafeExportDirectoryPath(
                rawPath = currentCfg.lyricDirectoryPath,
                defaultPath = DEFAULT_LYRIC_DOWNLOAD_PATH,
                appDataDirPath = appDataDir
            )

            val targetAudioDir = File(safeAudioDirPath)
            val directAudioFile = SecurityGuards.resolveSafeChildFile(targetAudioDir, audioFileName)

            // 检查本地是否已存在同名文件（对齐 kgmusicbot: 本地已存在，跳过下载）
            if (currentCfg.skipExistingFiles && directAudioFile.exists() && directAudioFile.length() > 128 * 1024L) {
                var savedLrcPath = ""
                if (currentCfg.saveStandaloneLrcFile) {
                    val lrcText = runCatching {
                        VipManager.lyric.getLrcTextForExport(fullSong, includeTranslation = currentCfg.includeTranslationInLyric)
                    }.getOrDefault("")
                    if (lrcText.isNotBlank()) {
                        savedLrcPath = saveStandaloneLrcFile(
                            context = context,
                            lyricDirPath = safeLyricDirPath,
                            safeBaseName = safeBaseName,
                            lrcText = lrcText
                        )
                    }
                }
                updateTask(taskId) {
                    it.copy(
                        song = fullSong,
                        actualQuality = actualQuality,
                        status = DownloadTaskStatus.SKIPPED_EXISTING,
                        progress = 1f,
                        fileName = audioFileName,
                        savedAudioPath = directAudioFile.absolutePath,
                        savedLrcPath = savedLrcPath,
                        embeddedCover = currentCfg.embedHdCover,
                        embeddedLyric = currentCfg.embedLyrics,
                        embeddedMetadata = true
                    )
                }
                persistTasksHistory()
                if (!silentToasts) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "本地已存在：《${fullSong.title}》(${actualQuality.label})", Toast.LENGTH_SHORT).show()
                    }
                }
                return@withPermit DownloadTaskStatus.SKIPPED_EXISTING
            }

            // 3. 先下载音频流至应用缓存临时文件，以便高速完成二进制标签、高清封面与歌词内嵌，再落盘至目标自定义路径
            updateTask(taskId) {
                it.copy(
                    song = fullSong,
                    actualQuality = actualQuality,
                    status = DownloadTaskStatus.DOWNLOADING,
                    fileName = audioFileName,
                    progress = 0.10f
                )
            }

            val tempDir = File(context.cacheDir, "music_downloads_staging").apply { mkdirs() }
            val safeTaskToken = SecurityGuards.sanitizeStagingToken(taskId)
            val stagingAudioFile = SecurityGuards.resolveSafeChildFile(tempDir, "${safeTaskToken}_$audioFileName")

            val finalAudioFileName: String
            val finalSavedAudioPath: String
            val hdCoverBytes: ByteArray?
            val lrcText: String
            val embedSuccess: Boolean

            try {
                downloadAudioStreamToFile(
                    url = playbackResult.url,
                    destFile = stagingAudioFile,
                    expectedSize = playbackResult.fileSizeByte
                ) { downloaded, total ->
                    val frac = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.5f
                    updateTask(taskId) {
                        it.copy(
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            progress = (0.10f + frac * 0.72f).coerceIn(0.10f, 0.82f)
                        )
                    }
                }

                // 根据下载后文件的真实魔数修正扩展名（例如酷狗部分 128K 接口返回 OGG 容器）
                val detectedContainer = AudioMetadataEmbedder.detectFormat(stagingAudioFile, ext)
                val finalExt = when (detectedContainer) {
                    AudioMetadataEmbedder.AudioContainerFormat.FLAC -> ".flac"
                    AudioMetadataEmbedder.AudioContainerFormat.MP3 -> ".mp3"
                    AudioMetadataEmbedder.AudioContainerFormat.OGG -> ".ogg"
                    AudioMetadataEmbedder.AudioContainerFormat.OTHER -> ext
                }
                finalAudioFileName = "$safeBaseName$finalExt"

                updateTask(taskId) {
                    it.copy(
                        status = DownloadTaskStatus.EMBEDDING,
                        fileName = finalAudioFileName,
                        progress = 0.85f
                    )
                }

                // 4. 获取高清专辑封面字节流（默认内嵌高清封面）
                hdCoverBytes = if (currentCfg.embedHdCover) {
                    fetchHighDefCoverBytes(fullSong)
                } else null

                // 5. 获取 LRC 歌词文本（用于默认内嵌音频文件 及 可选独立保存 .lrc 文件）
                val needLyricText = currentCfg.embedLyrics || currentCfg.saveStandaloneLrcFile
                lrcText = if (needLyricText) {
                    runCatching {
                        VipManager.lyric.getLrcTextForExport(
                            song = fullSong,
                            includeTranslation = currentCfg.includeTranslationInLyric
                        )
                    }.getOrDefault("")
                } else ""

                // 6. 将歌曲详细信息、高清封面、歌词内嵌到音频文件
                val tagMeta = AudioTagMetadata(
                    title = fullSong.title,
                    artist = fullSong.artist,
                    album = fullSong.album,
                    publishDate = fullSong.publishDate,
                    songHash = playbackResult.resolvedHash.ifBlank { fullSong.hash },
                    qualityLabel = actualQuality.label,
                    coverBytes = hdCoverBytes,
                    coverMimeType = AudioMetadataEmbedder.detectImageMimeType(hdCoverBytes),
                    lrcText = if (currentCfg.embedLyrics) lrcText else ""
                )
                embedSuccess = runCatching {
                    AudioMetadataEmbedder.embedMetadata(stagingAudioFile, tagMeta)
                }.getOrDefault(false)

                // 7. 将内嵌完成的音频文件保存到用户设定的下载保存路径
                finalSavedAudioPath = exportFileToTargetDirectory(
                    context = context,
                    sourceFile = stagingAudioFile,
                    targetDirPath = safeAudioDirPath,
                    fileName = finalAudioFileName,
                    mimeType = when (finalExt) {
                        ".flac" -> "audio/flac"
                        ".ogg" -> "audio/ogg"
                        ".wav" -> "audio/wav"
                        else -> "audio/mpeg"
                    },
                    isAudio = true
                )
            } finally {
                runCatching { stagingAudioFile.delete() }
            }

            // 8. 若开启了「同时独立保存 .lrc 歌词文件」，将歌词保存到自定义歌词路径
            var finalSavedLrcPath = ""
            if (currentCfg.saveStandaloneLrcFile && lrcText.isNotBlank()) {
                finalSavedLrcPath = saveStandaloneLrcFile(
                    context = context,
                    lyricDirPath = safeLyricDirPath,
                    safeBaseName = safeBaseName,
                    lrcText = lrcText
                )
            }

            updateTask(taskId) {
                it.copy(
                    song = fullSong,
                    actualQuality = actualQuality,
                    status = DownloadTaskStatus.COMPLETED,
                    progress = 1f,
                    fileName = finalAudioFileName,
                    savedAudioPath = finalSavedAudioPath,
                    savedLrcPath = finalSavedLrcPath,
                    embeddedCover = hdCoverBytes != null && hdCoverBytes.isNotEmpty() && embedSuccess,
                    embeddedLyric = currentCfg.embedLyrics && lrcText.isNotBlank() && embedSuccess,
                    embeddedMetadata = embedSuccess
                )
            }
            persistTasksHistory()

            if (!silentToasts) {
                withContext(Dispatchers.Main) {
                    val lrcNote = when {
                        finalSavedLrcPath.isNotBlank() && currentCfg.embedLyrics -> "（封面/歌词已内嵌 + 独立.lrc已保存）"
                        currentCfg.embedLyrics && lrcText.isNotBlank() -> "（高清封面与歌词已内嵌）"
                        else -> ""
                    }
                    Toast.makeText(
                        context,
                        "下载完成：《${fullSong.title}》[${actualQuality.label}] $lrcNote",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            DownloadTaskStatus.COMPLETED
        } catch (e: Exception) {
            updateTask(taskId) {
                it.copy(
                    status = DownloadTaskStatus.FAILED,
                    errorMessage = e.message ?: "下载失败，请检查网络或存储权限"
                )
            }
            persistTasksHistory()
            if (!silentToasts) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "《${initialTask.song.title}》下载失败：${e.message ?: "请检查网络或权限"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            DownloadTaskStatus.FAILED
        }
    }

    internal fun downloadAudioStreamToFile(
        url: String,
        destFile: File,
        expectedSize: Long,
        allowEntryLoopbackForTest: Boolean = false,
        onProgress: (Long, Long) -> Unit
    ) {
        if (!SecurityGuards.isSafeExternalMediaUrl(url, allowLoopbackForTest = allowEntryLoopbackForTest)) {
            throw SecurityException("拒绝下载非公网或不安全的音频流地址")
        }
        SecurityGuards.executeSafeExternalMediaGet(
            baseClient = NetworkClientProvider.baseClient,
            initialUrl = url,
            headers = mapOf(
                "User-Agent" to "Android15-1070-11440-46-0-DiscoveryDRADProtocol-wifi",
                "Referer" to "https://www.kugou.com/"
            ),
            maxRedirects = 5,
            allowEntryLoopbackForTest = allowEntryLoopbackForTest
        ).use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("音频下载响应异常 (HTTP ${resp.code})")
            }
            val body = resp.body ?: throw IllegalStateException("音频响应体为空")
            val contentLength = body.contentLength()
            if (contentLength > SecurityGuards.MAX_AUDIO_STREAM_BYTES) {
                throw IOException("音频响应大小异常 (${contentLength} bytes)，已阻断写入")
            }
            val total = contentLength.takeIf { it > 0L } ?: expectedSize
            var downloaded = 0L
            var lastNotifyBytes = 0L
            destFile.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (read == 0) continue
                        if (downloaded + read > SecurityGuards.MAX_AUDIO_STREAM_BYTES) {
                            throw IOException("音频流超出最大允许体积上限，已中止下载")
                        }
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastNotifyBytes >= 128 * 1024L || downloaded == total) {
                            lastNotifyBytes = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                    output.flush()
                }
            }
        }
    }

    /**
     * 获取歌曲高清专辑封面图片字节（优先 1000x1000 原图，其次 480x480，若 Song 缺少 coverUrl 则通过 albumId 查询专辑详情，对齐 kgmusicbot get_album_cover）
     */
    private suspend fun fetchHighDefCoverBytes(song: Song): ByteArray? = withContext(Dispatchers.IO) {
        var baseCoverUrl = song.coverUrl.trim()
        if (baseCoverUrl.isBlank() && song.albumId.isNotBlank() && song.albumId != "0") {
            baseCoverUrl = runCatching {
                VipManager.music.albumDetail(song.albumId)?.coverUrl.orEmpty()
            }.getOrDefault("")
        }
        val candidates = buildHighDefCoverUrlCandidates(baseCoverUrl)
        for (url in candidates) {
            if (!SecurityGuards.isSafeExternalMediaUrl(url)) continue
            val bytes = runCatching {
                SecurityGuards.executeSafeExternalMediaGet(
                    baseClient = NetworkClientProvider.baseClient,
                    initialUrl = url,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 14)",
                        "Referer" to "https://www.kugou.com/"
                    ),
                    maxRedirects = 4
                ).use { resp ->
                    if (resp.isSuccessful) {
                        SecurityGuards.readBoundedBytes(resp.body, SecurityGuards.MAX_COVER_IMAGE_BYTES)
                            .takeIf { it.size > 512 && SecurityGuards.isSafeImagePayload(it) }
                    } else null
                }
            }.getOrNull()
            if (bytes != null) return@withContext bytes
        }
        null
    }

    /**
     * 将临时文件导出到用户自定义的保存路径：
     * 1. 优先尝试直接通过 `java.io.File` 写入目标自定义目录并通知系统 `MediaScannerConnection` 扫描入库；
     * 2. 若在 Android 10+ 未授予全盘文件访问且直接写入受 Scoped Storage 限制，则自动映射 `RELATIVE_PATH` 通过 `MediaStore` 写入对应目录。
     */
    private fun exportFileToTargetDirectory(
        context: Context,
        sourceFile: File,
        targetDirPath: String,
        fileName: String,
        mimeType: String,
        isAudio: Boolean
    ): String {
        val defaultDir = if (isAudio) DEFAULT_AUDIO_DOWNLOAD_PATH else DEFAULT_LYRIC_DOWNLOAD_PATH
        val safeTargetDirPath = SecurityGuards.sanitizeSafeExportDirectoryPath(
            rawPath = targetDirPath,
            defaultPath = defaultDir,
            appDataDirPath = runCatching { context.applicationInfo?.dataDir }.getOrNull()
        )
        val targetDir = File(safeTargetDirPath)
        val targetFile = SecurityGuards.resolveSafeChildFile(targetDir, fileName)
        val safeLeafFileName = targetFile.name

        // 1. 尝试直接文件系统写入
        val directSuccess = runCatching {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            sourceFile.copyTo(targetFile, overwrite = true)
            targetFile.exists() && targetFile.length() > 0L
        }.getOrDefault(false)

        if (directSuccess) {
            runCatching {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf(mimeType),
                    null
                )
            }
            return targetFile.absolutePath
        }

        // 2. Android 10+ (API 29+) MediaStore 兜底写入（将自定义路径映射为公共目录相对路径）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = resolveMediaStoreRelativePath(safeTargetDirPath, isAudio = isAudio)
            val musicRoot = Environment.DIRECTORY_MUSIC ?: "Music"
            val resolver = context.contentResolver
            val collection = if (isAudio && relativePath.startsWith(musicRoot)) {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeLeafFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    } ?: error("无法打开 MediaStore 输出流")
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null
                    )
                    return "/storage/emulated/0/$relativePath/$safeLeafFileName".replace("//", "/")
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            }
        }

        // 3. 最终兜底：应用专属外部 Music 目录（免任何权限 100% 可写）
        val musicRoot = Environment.DIRECTORY_MUSIC ?: "Music"
        val appExternalDir = File(context.getExternalFilesDir(musicRoot), "BreezeMusic").apply { mkdirs() }
        val fallbackFile = SecurityGuards.resolveSafeChildFile(appExternalDir, safeLeafFileName)
        sourceFile.copyTo(fallbackFile, overwrite = true)
        return fallbackFile.absolutePath
    }

    /**
     * 独立保存 `.lrc` 歌词文件到用户自定义的歌词保存路径
     */
    internal fun saveStandaloneLrcFile(
        context: Context,
        lyricDirPath: String,
        safeBaseName: String,
        lrcText: String
    ): String {
        if (lrcText.isBlank()) return ""
        val sanitizedBase = safeBaseName
            .replace('/', '_')
            .replace('\\', '_')
            .replace('\u0000', '_')
            .replace("..", "_")
            .trim()
            .ifBlank { "BreezeMusic_${System.currentTimeMillis()}" }
        val lrcFileName = "$sanitizedBase.lrc"
        val tempDir = File(context.cacheDir, "music_downloads_staging").apply { mkdirs() }
        val tempLrc = SecurityGuards.resolveSafeChildFile(tempDir, "tmp_$lrcFileName")
        return try {
            tempLrc.writeText(lrcText, Charsets.UTF_8)
            exportFileToTargetDirectory(
                context = context,
                sourceFile = tempLrc,
                targetDirPath = lyricDirPath,
                fileName = lrcFileName,
                mimeType = "application/octet-stream",
                isAudio = false
            )
        } catch (_: Exception) {
            ""
        } finally {
            runCatching { tempLrc.delete() }
        }
    }

    internal fun resolveMediaStoreRelativePath(rawPath: String, isAudio: Boolean): String {
        val musicRoot = Environment.DIRECTORY_MUSIC ?: "Music"
        val downloadsRoot = Environment.DIRECTORY_DOWNLOADS ?: "Download"
        val documentsRoot = Environment.DIRECTORY_DOCUMENTS ?: "Documents"
        val podcastsRoot = Environment.DIRECTORY_PODCASTS ?: "Podcasts"
        val defaultRelative = if (isAudio) "$musicRoot/BreezeMusic" else "$downloadsRoot/BreezeMusic/Lyrics"

        if (rawPath.any { it == '\u0000' || it.code < 0x20 }) {
            return defaultRelative
        }

        val normalized = rawPath.trim()
            .replace('\\', '/')
            .removePrefix("/storage/emulated/0/")
            .removePrefix("/sdcard/")
            .trim('/')

        if (normalized.isBlank()) {
            return defaultRelative
        }

        val rawSegments = normalized.split('/').filter { it.isNotEmpty() }
        // 遵循 OWASP MediaStore 防穿越规范：一旦包含 "." / ".." 穿越段或指向系统/应用沙盒目录，立即拒绝并回退默认安全相对路径
        if (rawSegments.isEmpty() || rawSegments.any { it == "." || it == ".." }) {
            return defaultRelative
        }
        val firstSegmentLower = rawSegments.first().lowercase(Locale.ROOT)
        if (firstSegmentLower in setOf("android", "data", "proc", "sys", "dev", "system", "etc", "apex", "vendor")) {
            return defaultRelative
        }

        val safeSegments = rawSegments.map { seg ->
            seg.replace(Regex("""[:*?"<>|]"""), "_").trim().ifBlank { "BreezeMusic" }
        }
        val cleaned = safeSegments.joinToString("/")

        val allowedRoots = listOf(musicRoot, downloadsRoot, documentsRoot, podcastsRoot)
        if (allowedRoots.any { root ->
                cleaned.equals(root, ignoreCase = true) || cleaned.startsWith("$root/", ignoreCase = true)
            }
        ) {
            return cleaned
        }
        return if (isAudio) {
            "$musicRoot/$cleaned"
        } else {
            "$downloadsRoot/$cleaned"
        }
    }
}
