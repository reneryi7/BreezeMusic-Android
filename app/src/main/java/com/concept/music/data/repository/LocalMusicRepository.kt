package com.concept.music.data.repository

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import com.concept.music.data.kugou.KrcDecoder
import com.concept.music.data.model.LocalAudioTrack
import com.concept.music.data.model.LocalCloudDefaultTab
import com.concept.music.data.model.LocalLibrarySummary
import com.concept.music.data.model.LocalLyricFolder
import com.concept.music.data.model.LocalMusicFolder
import com.concept.music.data.model.LocalMusicQueryEngine
import com.concept.music.data.model.LocalScanProgress
import com.concept.music.data.model.LyricData
import com.concept.music.data.model.Song
import com.concept.music.data.model.stripKnownAudioFileExtension
import com.concept.music.service.AppPermissionManager
import com.concept.music.service.AppPermissionType
import com.concept.music.service.LocalAudioMetadataParser
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Base64
import java.util.Locale

data class LocalLyricResolution(
    val lyricData: LyricData,
    val sourceLabel: String
)

private data class PersistedLocalMusicIndex(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("folders") val folders: List<LocalMusicFolder> = emptyList(),
    @SerializedName("lyricFolders") val lyricFolders: List<LocalLyricFolder> = emptyList(),
    @SerializedName("tracks") val tracks: List<LocalAudioTrack> = emptyList(),
    @SerializedName("externalLyricIndex") val externalLyricIndex: Map<String, String> = emptyMap(),
    @SerializedName("nextOrder") val nextOrder: Long = 1L
)

private data class ScannedDocEntry(
    val uri: Uri,
    val displayName: String,
    val parentDocId: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)

/**
 * 本地音乐仓储与高速 SAF 增量扫描引擎：
 * - 基于 DocumentsContract 直接游标 BFS 扫描（跳过慢速 DocumentFile）；
 * - 持久化曲库索引至 `filesDir/local_music_index_v1.json`，封面持久化至 `filesDir/local_music_covers/`（不受清缓存影响）；
 * - 支持多音乐目录、独立歌词文件夹与单曲手动歌词绑定；
 * - 严格实现 5 级歌词优先级前 4 级本地匹配（单曲手动绑定 → 同目录同名 → 独立歌词目录 → 音频内嵌歌词）。
 */
class LocalMusicRepository(private val context: Context?) {

    companion object {
        private const val PREFS_NAME = "concept_local_music_prefs"
        private const val KEY_DEFAULT_TAB = "local_cloud_default_tab"
        private const val KEY_ONLINE_LYRIC_FALLBACK = "local_online_lyric_fallback"
        private const val INDEX_FILE_NAME = "local_music_index_v1.json"
        private const val COVER_DIR_NAME = "local_music_covers"
        private const val MAX_LYRIC_FILE_BYTES = 2 * 1024 * 1024
        private const val COVER_MAX_DIMENSION = 640

        /**
         * 归一化歌词/音轨匹配键（去掉扩展名、空白与常见符号差异，兼容 "歌手 - 歌名" 与 "歌名"）。
         */
        fun normalizeMatchKey(raw: String): String {
            return stripKnownAudioFileExtension(raw)
                .replace(Regex("""\.(lrc|krc|txt)$""", RegexOption.IGNORE_CASE), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("""[\s\-_·.,，。'"()\[\]（）【】!&/]+"""), "")
                .trim()
        }

        /**
         * 将纯原始字节流（可能是二进制 krc1、Base64 krc 或 UTF-8/UTF-16/GB18030 LRC）解析为 LyricData。
         */
        fun parseLyricBytes(rawBytes: ByteArray, fileNameHint: String = ""): LyricData {
            if (rawBytes.isEmpty()) return LyricData()
            val isBinaryKrc = rawBytes.size > 4 &&
                rawBytes[0] == 'k'.code.toByte() &&
                rawBytes[1] == 'r'.code.toByte() &&
                rawBytes[2] == 'c'.code.toByte() &&
                rawBytes[3] == '1'.code.toByte()
            if (isBinaryKrc) {
                val b64 = Base64.getEncoder().encodeToString(rawBytes)
                val decoded = KrcDecoder.decodeKrc(b64)
                if (decoded.isNotBlank()) {
                    val parsed = KrcDecoder.parseKrcText(decoded)
                    if (parsed.lines.isNotEmpty()) return parsed
                }
            }
            val text = LocalAudioMetadataParser.decodeSmartTextBytes(rawBytes).trim()
            if (text.isBlank()) return LyricData()
            return parseLyricString(text, isKrcHint = fileNameHint.endsWith(".krc", ignoreCase = true))
        }

        fun parseLyricString(text: String, isKrcHint: Boolean = false): LyricData {
            val trimmed = text.trim()
            if (trimmed.isBlank()) return LyricData()
            val auto = KrcDecoder.parseAuto(trimmed, isBase64Krc = isKrcHint)
            if (auto.lines.isNotEmpty()) return auto
            // 兜底处理无时间戳纯文本内嵌歌词：按行合成可滚动预览
            val plainLines = trimmed.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (plainLines.isEmpty()) return LyricData()
            val synthetic = plainLines.mapIndexed { idx, line ->
                com.concept.music.data.model.LyricLine(
                    startTimeMs = idx * 3500L,
                    durationMs = 3500L,
                    text = line
                )
            }
            return LyricData(lines = synthetic, rawText = trimmed, isKrc = false)
        }
    }

    private val gson = Gson()
    private val scanMutex = Mutex()
    private val prefs = context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _folders = MutableStateFlow<List<LocalMusicFolder>>(emptyList())
    val folders: StateFlow<List<LocalMusicFolder>> = _folders.asStateFlow()

    private val _lyricFolders = MutableStateFlow<List<LocalLyricFolder>>(emptyList())
    val lyricFolders: StateFlow<List<LocalLyricFolder>> = _lyricFolders.asStateFlow()

    private val _tracks = MutableStateFlow<List<LocalAudioTrack>>(emptyList())
    val tracks: StateFlow<List<LocalAudioTrack>> = _tracks.asStateFlow()

    private val _summary = MutableStateFlow(LocalLibrarySummary())
    val summary: StateFlow<LocalLibrarySummary> = _summary.asStateFlow()

    private val _scanProgress = MutableStateFlow(LocalScanProgress())
    val scanProgress: StateFlow<LocalScanProgress> = _scanProgress.asStateFlow()

    private val _defaultTab = MutableStateFlow(
        LocalCloudDefaultTab.fromKey(prefs?.getString(KEY_DEFAULT_TAB, LocalCloudDefaultTab.LOCAL.key))
    )
    val defaultTab: StateFlow<LocalCloudDefaultTab> = _defaultTab.asStateFlow()

    private val _onlineLyricFallbackEnabled = MutableStateFlow(
        prefs?.getBoolean(KEY_ONLINE_LYRIC_FALLBACK, true) ?: true
    )
    val onlineLyricFallbackEnabled: StateFlow<Boolean> = _onlineLyricFallbackEnabled.asStateFlow()

    @Volatile
    private var externalLyricIndex: Map<String, String> = emptyMap()

    @Volatile
    private var nextAddedOrder: Long = 1L

    init {
        loadIndexSync()
    }

    fun setDefaultTab(tab: LocalCloudDefaultTab) {
        _defaultTab.value = tab
        prefs?.edit()?.putString(KEY_DEFAULT_TAB, tab.key)?.apply()
    }

    fun setOnlineLyricFallbackEnabled(enabled: Boolean) {
        _onlineLyricFallbackEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_ONLINE_LYRIC_FALLBACK, enabled)?.apply()
    }

    fun getAuthorizedFolderCount(): Int = _folders.value.size + _lyricFolders.value.size

    fun findTrackBySongId(songId: String): LocalAudioTrack? {
        val normalized = songId.removePrefix("local_")
        return _tracks.value.firstOrNull { it.id == normalized || "local_${it.id}" == songId }
    }

    /**
     * 添加并立即扫描一个 SAF 音乐文件夹（支持多目录，重复选择同一目录则执行增量重扫）。
     */
    suspend fun addAndScanMusicFolder(treeUri: Uri): Result<LocalMusicFolder> = withContext(Dispatchers.IO) {
        val ctx = context?.applicationContext
            ?: return@withContext Result.failure(IllegalStateException("应用上下文未就绪"))
        if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.LOCAL_AUDIO_IMPORT)) {
            return@withContext Result.failure(
                SecurityException("已在权限管理中关闭「本地音乐与歌词目录读取」权限，请先在设置中开启")
            )
        }

        scanMutex.withLock {
            runCatching {
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val (displayName, relativePath) = resolveTreeUriLabels(treeUri)
                val folderId = sha1Hex(treeUri.toString()).take(16)
                val now = System.currentTimeMillis()
                val existingFolder = _folders.value.firstOrNull { it.id == folderId }
                val baseFolder = existingFolder?.copy(
                    displayName = displayName,
                    relativePathLabel = relativePath
                ) ?: LocalMusicFolder(
                    id = folderId,
                    treeUri = treeUri.toString(),
                    displayName = displayName,
                    relativePathLabel = relativePath,
                    addedAtMs = now
                )

                val scannedFolder = scanSingleFolderLocked(ctx, baseFolder)
                recomputeAndPersistLocked(ctx)
                scannedFolder
            }
        }
    }

    suspend fun rescanFolder(folderId: String): Result<LocalMusicFolder> = withContext(Dispatchers.IO) {
        val ctx = context?.applicationContext
            ?: return@withContext Result.failure(IllegalStateException("应用上下文未就绪"))
        if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.LOCAL_AUDIO_IMPORT)) {
            return@withContext Result.failure(
                SecurityException("已在权限管理中关闭「本地音乐与歌词目录读取」权限")
            )
        }
        scanMutex.withLock {
            runCatching {
                val target = _folders.value.firstOrNull { it.id == folderId }
                    ?: throw IllegalArgumentException("未找到该本地目录")
                val updated = scanSingleFolderLocked(ctx, target)
                recomputeAndPersistLocked(ctx)
                updated
            }
        }
    }

    suspend fun rescanAllFolders(): Result<Int> = withContext(Dispatchers.IO) {
        val ctx = context?.applicationContext
            ?: return@withContext Result.failure(IllegalStateException("应用上下文未就绪"))
        if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.LOCAL_AUDIO_IMPORT)) {
            return@withContext Result.failure(
                SecurityException("已在权限管理中关闭「本地音乐与歌词目录读取」权限")
            )
        }
        scanMutex.withLock {
            runCatching {
                val currentFolders = _folders.value.toList()
                for (folder in currentFolders) {
                    runCatching { scanSingleFolderLocked(ctx, folder) }
                }
                // 同时刷新独立歌词目录索引
                val currentLyricFolders = _lyricFolders.value.toList()
                if (currentLyricFolders.isNotEmpty()) {
                    rebuildExternalLyricIndexLocked(ctx, currentLyricFolders)
                }
                recomputeAndPersistLocked(ctx)
                _tracks.value.size
            }
        }
    }

    suspend fun removeMusicFolder(folderId: String) = withContext(Dispatchers.IO) {
        val ctx = context?.applicationContext ?: return@withContext
        scanMutex.withLock {
            val target = _folders.value.firstOrNull { it.id == folderId } ?: return@withLock
            _folders.value = _folders.value.filterNot { it.id == folderId }
            _tracks.value = _tracks.value.filterNot { it.folderId == folderId }
            // 若该目录不再被歌词目录使用，释放持久化权限
            val stillUsedByLyric = _lyricFolders.value.any { it.treeUri == target.treeUri }
            if (!stillUsedByLyric) {
                runCatching {
                    ctx.contentResolver.releasePersistableUriPermission(
                        Uri.parse(target.treeUri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            recomputeAndPersistLocked(ctx)
            gcOrphanCovers(ctx)
        }
    }

    suspend fun removeTrack(trackId: String) = withContext(Dispatchers.IO) {
        val ctx = context?.applicationContext ?: return@withContext
        val cleanId = trackId.removePrefix("local_")
        scanMutex.withLock {
            _tracks.value = _tracks.value.filterNot { it.id == cleanId }
            recomputeAndPersistLocked(ctx)
            gcOrphanCovers(ctx)
        }
    }

    /**
     * 导入独立歌词文件夹目录（递归扫描 .lrc / .krc 文件并建立快速匹配索引）。
     */
    suspend fun addLyricFolder(treeUri: Uri): Result<LocalLyricFolder> = withContext(Dispatchers.IO) {
        val ctx = context?.applicationContext
            ?: return@withContext Result.failure(IllegalStateException("应用上下文未就绪"))
        if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.LOCAL_AUDIO_IMPORT)) {
            return@withContext Result.failure(
                SecurityException("已在权限管理中关闭「本地音乐与歌词目录读取」权限")
            )
        }
        scanMutex.withLock {
            runCatching {
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val (displayName, relativePath) = resolveTreeUriLabels(treeUri)
                val folderId = sha1Hex("lrc_" + treeUri.toString()).take(16)
                val newFolder = LocalLyricFolder(
                    id = folderId,
                    treeUri = treeUri.toString(),
                    displayName = displayName,
                    relativePathLabel = relativePath,
                    addedAtMs = System.currentTimeMillis()
                )
                val nextFolders = _lyricFolders.value.filterNot { it.id == folderId } + newFolder
                val updatedFolders = rebuildExternalLyricIndexLocked(ctx, nextFolders)
                _lyricFolders.value = updatedFolders
                recomputeAndPersistLocked(ctx)
                updatedFolders.first { it.id == folderId }
            }
        }
    }

    suspend fun removeLyricFolder(folderId: String) = withContext(Dispatchers.IO) {
        val ctx = context?.applicationContext ?: return@withContext
        scanMutex.withLock {
            val target = _lyricFolders.value.firstOrNull { it.id == folderId } ?: return@withLock
            val remaining = _lyricFolders.value.filterNot { it.id == folderId }
            val updatedFolders = rebuildExternalLyricIndexLocked(ctx, remaining)
            _lyricFolders.value = updatedFolders
            val stillUsedByMusic = _folders.value.any { it.treeUri == target.treeUri }
            if (!stillUsedByMusic) {
                runCatching {
                    ctx.contentResolver.releasePersistableUriPermission(
                        Uri.parse(target.treeUri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            recomputeAndPersistLocked(ctx)
        }
    }

    /**
     * 为单首本地歌曲手动绑定独立歌词文件（最高优先级）。
     */
    suspend fun bindManualLyricToTrack(songOrTrackId: String, lyricUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val ctx = context?.applicationContext ?: return@withContext false
        if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.LOCAL_AUDIO_IMPORT)) {
            return@withContext false
        }
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                lyricUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val cleanId = songOrTrackId.removePrefix("local_")
        var matched = false
        scanMutex.withLock {
            _tracks.value = _tracks.value.map { t ->
                if (t.id == cleanId) {
                    matched = true
                    t.copy(manualLyricUri = lyricUri.toString())
                } else t
            }
            if (matched) {
                recomputeAndPersistLocked(ctx)
            }
        }
        matched
    }

    /**
     * 按 5 级管线前 4 级解析本地歌曲歌词：
     * 1. 单曲手动绑定独立歌词 (manualLyricUri)
     * 2. 同目录同名 .lrc / .krc (sidecarLyricUri)
     * 3. 已导入独立歌词文件夹匹配 (externalLyricIndex)
     * 4. 音频文件内嵌歌词 (embeddedLyric)
     */
    fun resolveLocalLyric(song: Song): LocalLyricResolution? {
        val track = findTrackBySongId(song.id)
        val candidates = buildList {
            if (track != null && track.manualLyricUri.isNotBlank()) {
                add(track.manualLyricUri to "手动绑定独立歌词")
            } else if (song.localLyricUri.isNotBlank() && track == null) {
                add(song.localLyricUri to "本地独立歌词")
            }
            if (track != null && track.sidecarLyricUri.isNotBlank()) {
                add(track.sidecarLyricUri to "同目录同名歌词")
            }
            val externalUri = findBestExternalLyricUri(
                fileName = track?.fileName.orEmpty(),
                title = track?.title ?: song.title,
                artist = track?.artist ?: song.artist
            )
            if (!externalUri.isNullOrBlank()) {
                add(externalUri to "独立歌词目录匹配")
            }
        }

        for ((uriStr, label) in candidates) {
            val parsed = readLyricDataFromUri(uriStr)
            if (parsed != null && parsed.lines.isNotEmpty()) {
                return LocalLyricResolution(parsed, label)
            }
        }

        val embedded = track?.embeddedLyric?.trim().orEmpty()
        if (embedded.isNotBlank()) {
            val parsed = parseLyricString(embedded)
            if (parsed.lines.isNotEmpty()) {
                return LocalLyricResolution(parsed, "音频内嵌歌词")
            }
        }
        return null
    }

    internal fun findBestExternalLyricUri(
        fileName: String,
        title: String,
        artist: String,
        indexOverride: Map<String, String>? = null
    ): String? {
        val idx = indexOverride ?: externalLyricIndex
        if (idx.isEmpty()) return null
        val keysToTry = buildList {
            if (fileName.isNotBlank()) add(normalizeMatchKey(fileName))
            if (artist.isNotBlank() && artist != "未知歌手" && title.isNotBlank()) {
                add(normalizeMatchKey("$artist - $title"))
                add(normalizeMatchKey("$title - $artist"))
            }
            if (title.isNotBlank()) add(normalizeMatchKey(title))
        }.filter { it.isNotBlank() }.distinct()

        for (k in keysToTry) {
            idx[k]?.let { return it }
        }
        return null
    }

    fun readLyricDataFromUri(uriString: String): LyricData? {
        val ctx = context?.applicationContext ?: return null
        if (uriString.isBlank()) return null
        return runCatching {
            val uri = Uri.parse(uriString)
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
                readBoundedBytes(input, MAX_LYRIC_FILE_BYTES)
            } ?: return@runCatching null
            parseLyricBytes(bytes, fileNameHint = uriString)
        }.getOrNull()
    }

    // =========================================================================
    // 高速 SAF 游标 BFS 扫描与增量合并
    // =========================================================================

    private fun scanSingleFolderLocked(ctx: Context, folder: LocalMusicFolder): LocalMusicFolder {
        val treeUri = Uri.parse(folder.treeUri)
        _scanProgress.value = LocalScanProgress(
            isScanning = true,
            currentFolderName = folder.displayName,
            scannedFiles = 0,
            importedTracks = 0,
            message = "正在扫描「${folder.displayName}」…"
        )

        val audioDocs = mutableListOf<ScannedDocEntry>()
        val sidecarLyrics = hashMapOf<String, String>() // key = "$parentDocId|${baseNameLower}" -> uri

        try {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val queue = ArrayDeque<String>()
            queue.add(rootDocId)
            val visited = hashSetOf(rootDocId)

            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )

            var scannedFileCount = 0
            while (queue.isNotEmpty()) {
                val parentId = queue.removeFirst()
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
                ctx.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    while (cursor.moveToNext()) {
                        val docId = if (idIdx >= 0) cursor.getString(idIdx).orEmpty() else continue
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else continue
                        if (name.isBlank() || name.startsWith(".")) continue
                        val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (visited.add(docId) && visited.size < 4096) {
                                queue.addLast(docId)
                            }
                            continue
                        }

                        scannedFileCount++
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        when {
                            LocalAudioMetadataParser.isSupportedAudioFileName(name) -> {
                                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                                val mod = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else 0L
                                audioDocs.add(ScannedDocEntry(docUri, name, parentId, size, mod))
                            }
                            LocalAudioMetadataParser.isSupportedLyricFileName(name) -> {
                                val baseLower = name.substringBeforeLast('.').lowercase(Locale.ROOT)
                                val sidecarKey = "$parentId|$baseLower"
                                // 同目录下优先 .krc 逐字歌词，其次 .lrc
                                if (!sidecarLyrics.containsKey(sidecarKey) || name.endsWith(".krc", ignoreCase = true)) {
                                    sidecarLyrics[sidecarKey] = docUri.toString()
                                }
                            }
                        }

                        if (scannedFileCount % 25 == 0) {
                            _scanProgress.value = _scanProgress.value.copy(
                                scannedFiles = scannedFileCount,
                                message = "正在检索「${folder.displayName}」($scannedFileCount 个文件)…"
                            )
                        }
                    }
                }
            }

            // 增量比对已有曲目，避免重复解析未变动文件
            val existingByUri = _tracks.value
                .filter { it.folderId == folder.id }
                .associateBy { it.uri }

            val folderTracks = mutableListOf<LocalAudioTrack>()
            audioDocs.forEachIndexed { idx, doc ->
                val uriStr = doc.uri.toString()
                val baseLower = doc.displayName.substringBeforeLast('.').lowercase(Locale.ROOT)
                val sidecarUri = sidecarLyrics["${doc.parentDocId}|$baseLower"].orEmpty()
                val existing = existingByUri[uriStr]

                val track = if (existing != null &&
                    existing.fileSizeBytes == doc.sizeBytes &&
                    existing.lastModifiedMs == doc.lastModifiedMs &&
                    (existing.coverFilePath.isBlank() || File(existing.coverFilePath).exists())
                ) {
                    existing.copy(
                        folderName = folder.displayName,
                        sidecarLyricUri = sidecarUri
                    )
                } else {
                    val trackId = sha1Hex(uriStr).take(20)
                    val parsed = LocalAudioMetadataParser.parseFromContentUri(
                        context = ctx,
                        uri = doc.uri,
                        fileName = doc.displayName,
                        fileSizeBytes = doc.sizeBytes
                    )
                    val coverPath = parsed.coverBytes?.let { bytes ->
                        saveScaledCoverToFilesDir(ctx, trackId, bytes)
                    }.orEmpty()
                    val order = existing?.addedOrder ?: nextAddedOrder++
                    LocalAudioTrack(
                        id = trackId,
                        uri = uriStr,
                        fileName = doc.displayName,
                        folderId = folder.id,
                        folderName = folder.displayName,
                        title = parsed.title,
                        artist = parsed.artist,
                        artists = parsed.artists,
                        album = parsed.album,
                        year = parsed.year,
                        format = parsed.format,
                        qualityTier = parsed.qualityTier,
                        sampleRateHz = parsed.sampleRateHz,
                        bitDepth = parsed.bitDepth,
                        bitrateKbps = parsed.bitrateKbps,
                        durationMs = parsed.durationMs,
                        fileSizeBytes = doc.sizeBytes,
                        lastModifiedMs = doc.lastModifiedMs,
                        addedOrder = order,
                        coverFilePath = coverPath,
                        embeddedLyric = parsed.embeddedLyric,
                        sidecarLyricUri = sidecarUri,
                        manualLyricUri = existing?.manualLyricUri.orEmpty()
                    )
                }
                folderTracks.add(track)
                if ((idx + 1) % 5 == 0 || idx + 1 == audioDocs.size) {
                    _scanProgress.value = LocalScanProgress(
                        isScanning = true,
                        currentFolderName = folder.displayName,
                        scannedFiles = audioDocs.size,
                        importedTracks = idx + 1,
                        message = "正在解析音质与标签 (${idx + 1}/${audioDocs.size})…"
                    )
                }
            }

            val updatedFolder = folder.copy(
                lastScanAtMs = System.currentTimeMillis(),
                trackCount = folderTracks.size,
                hiResCount = folderTracks.count { it.isHiRes },
                losslessCount = folderTracks.count { !it.isHiRes && it.isLossless },
                totalBytes = folderTracks.sumOf { it.fileSizeBytes.coerceAtLeast(0L) }
            )

            // 合并回全局列表
            val otherTracks = _tracks.value.filterNot { it.folderId == folder.id }
            _tracks.value = (otherTracks + folderTracks).sortedBy { it.addedOrder }
            _folders.value = _folders.value.filterNot { it.id == folder.id } + updatedFolder
            gcOrphanCovers(ctx)
            return updatedFolder
        } finally {
            _scanProgress.value = LocalScanProgress(isScanning = false)
        }
    }

    private fun rebuildExternalLyricIndexLocked(
        ctx: Context,
        lyricFolders: List<LocalLyricFolder>
    ): List<LocalLyricFolder> {
        val mergedIndex = linkedMapOf<String, String>()
        val updatedFolders = mutableListOf<LocalLyricFolder>()

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        for (lf in lyricFolders) {
            var count = 0
            runCatching {
                val treeUri = Uri.parse(lf.treeUri)
                val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val queue = ArrayDeque<String>()
                queue.add(rootDocId)
                val visited = hashSetOf(rootDocId)

                while (queue.isNotEmpty()) {
                    val parentId = queue.removeFirst()
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
                    ctx.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                        val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        while (cursor.moveToNext()) {
                            val docId = if (idIdx >= 0) cursor.getString(idIdx).orEmpty() else continue
                            val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else continue
                            if (name.isBlank() || name.startsWith(".")) continue
                            val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (visited.add(docId) && visited.size < 2048) queue.addLast(docId)
                            } else if (LocalAudioMetadataParser.isSupportedLyricFileName(name)) {
                                count++
                                val uriStr = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString()
                                val key = normalizeMatchKey(name)
                                if (key.isNotBlank() && (!mergedIndex.containsKey(key) || name.endsWith(".krc", ignoreCase = true))) {
                                    mergedIndex[key] = uriStr
                                }
                                // 若文件名形如 "歌手 - 歌名.lrc"，同时建立纯歌名兜底键
                                val base = name.substringBeforeLast('.')
                                val dashIdx = base.indexOf(" - ")
                                if (dashIdx > 0 && dashIdx + 3 < base.length) {
                                    val titleOnlyKey = normalizeMatchKey(base.substring(dashIdx + 3))
                                    if (titleOnlyKey.isNotBlank() && !mergedIndex.containsKey(titleOnlyKey)) {
                                        mergedIndex[titleOnlyKey] = uriStr
                                    }
                                }
                            }
                        }
                    }
                }
            }
            updatedFolders.add(lf.copy(lyricFileCount = count))
        }
        externalLyricIndex = mergedIndex
        return updatedFolders
    }

    private fun recomputeAndPersistLocked(ctx: Context) {
        val currentTracks = _tracks.value
        val byFolder = currentTracks.groupBy { it.folderId }
        val refreshedFolders = _folders.value.map { folder ->
            val list = byFolder[folder.id].orEmpty()
            folder.copy(
                trackCount = list.size,
                hiResCount = list.count { it.isHiRes },
                losslessCount = list.count { !it.isHiRes && it.isLossless },
                totalBytes = list.sumOf { it.fileSizeBytes.coerceAtLeast(0L) }
            )
        }
        _folders.value = refreshedFolders
        _summary.value = LocalMusicQueryEngine.summarize(currentTracks, refreshedFolders, _lyricFolders.value)
        saveIndexSync(ctx)
    }

    private fun loadIndexSync() {
        val ctx = context?.applicationContext ?: return
        val file = File(ctx.filesDir, INDEX_FILE_NAME)
        if (!file.exists()) return
        runCatching {
            val raw = file.readText(Charsets.UTF_8)
            val parsed = gson.fromJson(raw, PersistedLocalMusicIndex::class.java) ?: return@runCatching
            val safeTracks = parsed.tracks.map { t ->
                val safeArtists = t.artists.ifEmpty { LocalMusicQueryEngine.splitArtistNames(t.artist) }
                t.copy(artists = safeArtists)
            }
            _folders.value = parsed.folders
            _lyricFolders.value = parsed.lyricFolders
            _tracks.value = safeTracks
            externalLyricIndex = parsed.externalLyricIndex
            nextAddedOrder = parsed.nextOrder.coerceAtLeast((safeTracks.maxOfOrNull { it.addedOrder } ?: 0L) + 1L)
            _summary.value = LocalMusicQueryEngine.summarize(safeTracks, parsed.folders, parsed.lyricFolders)
        }
    }

    private fun saveIndexSync(ctx: Context) {
        runCatching {
            val payload = PersistedLocalMusicIndex(
                version = 1,
                folders = _folders.value,
                lyricFolders = _lyricFolders.value,
                tracks = _tracks.value,
                externalLyricIndex = externalLyricIndex,
                nextOrder = nextAddedOrder
            )
            val json = gson.toJson(payload)
            val tmpFile = File(ctx.filesDir, "$INDEX_FILE_NAME.tmp")
            val dstFile = File(ctx.filesDir, INDEX_FILE_NAME)
            tmpFile.writeText(json, Charsets.UTF_8)
            if (!tmpFile.renameTo(dstFile)) {
                dstFile.writeText(json, Charsets.UTF_8)
                tmpFile.delete()
            }
        }
    }

    /**
     * 将内嵌封面按最大 640px 降采样压缩并持久化至 `filesDir/local_music_covers/`，
     * 与 `cacheDir` 严格隔离，避免设置页「清理缓存」误删本地曲库封面。
     */
    private fun saveScaledCoverToFilesDir(ctx: Context, trackId: String, rawBytes: ByteArray): String? {
        return runCatching {
            val coverDir = File(ctx.filesDir, COVER_DIR_NAME).apply { if (!exists()) mkdirs() }
            val outFile = File(coverDir, "$trackId.jpg")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sampleSize = 1
            while ((bounds.outWidth / sampleSize) > COVER_MAX_DIMENSION * 2 ||
                (bounds.outHeight / sampleSize) > COVER_MAX_DIMENSION * 2
            ) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                ?: return@runCatching null
            val finalBitmap = if (decoded.width > COVER_MAX_DIMENSION || decoded.height > COVER_MAX_DIMENSION) {
                val scale = COVER_MAX_DIMENSION.toFloat() / maxOf(decoded.width, decoded.height).toFloat()
                val w = (decoded.width * scale).toInt().coerceAtLeast(1)
                val h = (decoded.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, w, h, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else decoded

            try {
                FileOutputStream(outFile).use { fos ->
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 86, fos)
                }
            } finally {
                finalBitmap.recycle()
            }
            outFile.absolutePath
        }.getOrNull()
    }

    private fun gcOrphanCovers(ctx: Context) {
        runCatching {
            val coverDir = File(ctx.filesDir, COVER_DIR_NAME)
            if (!coverDir.exists()) return
            val referenced = _tracks.value.mapNotNull { it.coverFilePath.takeIf { p -> p.isNotBlank() } }.toHashSet()
            coverDir.listFiles()?.forEach { f ->
                if (f.isFile && f.absolutePath !in referenced) {
                    f.delete()
                }
            }
        }
    }

    private fun resolveTreeUriLabels(treeUri: Uri): Pair<String, String> {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
        val decoded = Uri.decode(docId).ifBlank { Uri.decode(treeUri.lastPathSegment.orEmpty()) }
        val relative = decoded.substringAfter(':', decoded).trim('/')
        val displayName = relative.substringAfterLast('/').ifBlank {
            if (decoded.startsWith("primary", ignoreCase = true)) "内部存储根目录" else "已选文件夹"
        }
        val pathLabel = when {
            decoded.startsWith("primary:", ignoreCase = true) && relative.isNotBlank() -> "内部存储/$relative"
            relative.isNotBlank() -> relative
            else -> displayName
        }
        return displayName to pathLabel
    }

    private fun readBoundedBytes(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > maxBytes) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
