package com.concept.music.data.model

import androidx.compose.runtime.Immutable
import java.text.Collator
import java.util.Locale

/**
 * 「本地/云盘」独立双页中心的默认展示页偏好。
 * 优化后默认展示「本地音乐」，同时允许用户自由切换为默认展示「音乐云盘」。
 */
enum class LocalCloudDefaultTab(val key: String, val label: String) {
    LOCAL("local", "本地音乐"),
    CLOUD("cloud", "音乐云盘");

    companion object {
        fun fromKey(raw: String?): LocalCloudDefaultTab = when (raw?.lowercase(Locale.ROOT)) {
            "cloud" -> CLOUD
            else -> LOCAL
        }
    }
}

/**
 * 本地音频真实物理音质层级（严格互斥统计）：
 * - HI_RES：无损容器/编码且满足 位深 >= 24-bit 或 采样率 > 48kHz（主流播放器口径）
 * - LOSSLESS：其余 16-bit 无损格式（如 16-bit/44.1kHz、48kHz FLAC/WAV/ALAC）
 * - LOSSY：有损压缩格式（MP3 / AAC / OGG / OPUS / WMA 等）
 */
enum class LocalAudioQualityTier(val label: String) {
    HI_RES("Hi-Res"),
    LOSSLESS("无损"),
    LOSSY("有损")
}

/**
 * 本地音乐一级筛选维度
 */
enum class LocalPrimaryTab(val key: String, val label: String) {
    ALL_SONGS("all", "全部歌曲"),
    FOLDERS("folders", "文件夹"),
    ARTISTS("artists", "歌手"),
    ALBUMS("albums", "专辑")
}

/**
 * 本地音乐二级筛选 — 音质格式过滤（与主流播放器一致的筛选并集口径）
 */
enum class LocalQualityFilter(
    val key: String,
    val label: String,
    val shortHint: String
) {
    ALL("all", "全部格式", "展示全部本地曲目"),
    HI_RES("hi_res", "Hi-Res", "≥24bit 或 >48kHz 高解析无损"),
    FLAC_WAV("flac_wav", "FLAC/WAV", "全部 FLAC 与 WAV 无损（含 Hi-Res 级）"),
    MP3_AAC("mp3_aac", "MP3/AAC", "MP3、AAC 及 M4A(AAC) 有损格式")
}

/**
 * 本地音乐二级筛选 — 排序维度
 */
enum class LocalSortOption(
    val key: String,
    val label: String,
    val shortHint: String
) {
    DEFAULT("default", "默认", "按导入与文件夹扫描顺序"),
    TITLE("title", "歌名", "按歌曲名称自然顺序 A-Z"),
    ARTIST("artist", "歌手", "按歌手名称自然顺序 A-Z"),
    FILE_SIZE("file_size", "体积", "按文件体积从大到小"),
    DURATION("duration", "时长", "按曲目时长从长到短")
}

@Immutable
data class LocalMusicFolder(
    val id: String,
    val treeUri: String,
    val displayName: String,
    val relativePathLabel: String = "",
    val addedAtMs: Long = 0L,
    val lastScanAtMs: Long = 0L,
    val trackCount: Int = 0,
    val hiResCount: Int = 0,
    val losslessCount: Int = 0,
    val totalBytes: Long = 0L
)

@Immutable
data class LocalLyricFolder(
    val id: String,
    val treeUri: String,
    val displayName: String,
    val relativePathLabel: String = "",
    val lyricFileCount: Int = 0,
    val addedAtMs: Long = 0L
)

@Immutable
data class LocalAudioTrack(
    val id: String,
    val uri: String,
    val fileName: String,
    val folderId: String,
    val folderName: String,
    val title: String,
    val artist: String,
    val artists: List<String> = emptyList(),
    val album: String = "",
    val year: String = "",
    val format: String = "MP3",
    val qualityTier: LocalAudioQualityTier = LocalAudioQualityTier.LOSSY,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val bitrateKbps: Int = 0,
    val durationMs: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val lastModifiedMs: Long = 0L,
    val addedOrder: Long = 0L,
    val coverFilePath: String = "",
    val embeddedLyric: String = "",
    val sidecarLyricUri: String = "",
    val manualLyricUri: String = ""
) {
    val isHiRes: Boolean
        get() = qualityTier == LocalAudioQualityTier.HI_RES

    val isLossless: Boolean
        get() = qualityTier == LocalAudioQualityTier.HI_RES || qualityTier == LocalAudioQualityTier.LOSSLESS

    val effectiveLyricUri: String
        get() = manualLyricUri.ifBlank { sidecarLyricUri }

    val hasLocalLyric: Boolean
        get() = manualLyricUri.isNotBlank() || sidecarLyricUri.isNotBlank() || embeddedLyric.isNotBlank()

    val coverModelUrl: String
        get() = coverFilePath.trim().takeIf { it.isNotBlank() }?.let { path ->
            if (path.startsWith("file://") || path.startsWith("content://") || path.startsWith("http")) path
            else "file://$path"
        }.orEmpty()

    val technicalSpecLabel: String
        get() {
            val parts = mutableListOf<String>()
            if (sampleRateHz > 0) parts.add(LocalMusicQueryEngine.formatSampleRate(sampleRateHz))
            if (bitDepth > 0) parts.add("${bitDepth}bit")
            if (parts.isEmpty() && bitrateKbps > 0) parts.add("${bitrateKbps}kbps")
            return parts.joinToString(" / ")
        }

    val playerQualityCapsuleLabel: String
        get() {
            val fmt = format.uppercase(Locale.ROOT).ifBlank { "LOCAL" }
            val sr = if (sampleRateHz > 0) LocalMusicQueryEngine.formatSampleRateShort(sampleRateHz) else ""
            return when (qualityTier) {
                LocalAudioQualityTier.HI_RES -> {
                    when {
                        sr.isNotBlank() && bitDepth > 0 -> "Hi-Res $sr/${bitDepth}bit"
                        sr.isNotBlank() -> "Hi-Res $sr"
                        bitDepth > 0 -> "Hi-Res ${bitDepth}bit"
                        else -> "Hi-Res $fmt"
                    }
                }
                LocalAudioQualityTier.LOSSLESS -> {
                    when {
                        bitrateKbps > 0 -> "$fmt ${bitrateKbps}k"
                        sr.isNotBlank() && bitDepth > 0 -> "$fmt $sr/${bitDepth}bit"
                        else -> "$fmt 无损"
                    }
                }
                LocalAudioQualityTier.LOSSY -> {
                    if (bitrateKbps > 0) "$fmt ${bitrateKbps}k" else fmt
                }
            }
        }

    fun toSong(): Song {
        val nativeQuality = when (qualityTier) {
            LocalAudioQualityTier.HI_RES -> AudioQuality.HI_RES
            LocalAudioQualityTier.LOSSLESS -> AudioQuality.LOSSLESS
            LocalAudioQualityTier.LOSSY -> if (bitrateKbps >= 256) AudioQuality.HIGH else AudioQuality.STANDARD
        }
        val cleanId = if (id.startsWith("local_")) id else "local_$id"
        return Song(
            id = cleanId,
            hash = cleanId,
            title = title.ifBlank { stripKnownAudioFileExtension(fileName) },
            artist = artist.ifBlank { "未知歌手" },
            album = album,
            coverUrl = coverModelUrl,
            durationMs = durationMs,
            isVip = false,
            isFreePrivilege = true,
            isLossless = isLossless,
            playUrl = uri,
            quality = nativeQuality.apiValue,
            isHiRes = isHiRes,
            availableQualities = listOf(nativeQuality),
            actualPlayQuality = nativeQuality,
            bitrateKbps = bitrateKbps,
            fileSizeByte = fileSizeBytes,
            publishDate = year,
            addTimeSec = lastModifiedMs / 1000L,
            localUri = uri,
            localFolderId = folderId,
            localFolderName = folderName,
            localFormat = format.uppercase(Locale.ROOT),
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            localLyricUri = effectiveLyricUri
        )
    }
}

@Immutable
data class LocalLibrarySummary(
    val totalCount: Int = 0,
    val hiResCount: Int = 0,
    val losslessCount: Int = 0,
    val lossyCount: Int = 0,
    val totalBytes: Long = 0L,
    val folderCount: Int = 0,
    val lyricFolderCount: Int = 0
)

@Immutable
data class LocalScanProgress(
    val isScanning: Boolean = false,
    val currentFolderName: String = "",
    val scannedFiles: Int = 0,
    val importedTracks: Int = 0,
    val message: String = ""
)

@Immutable
data class LocalArtistGroup(
    val name: String,
    val normalizedKey: String,
    val tracks: List<LocalAudioTrack>,
    val coverModelUrl: String,
    val hiResCount: Int,
    val losslessCount: Int
)

@Immutable
data class LocalAlbumGroup(
    val name: String,
    val artistSummary: String,
    val normalizedKey: String,
    val tracks: List<LocalAudioTrack>,
    val coverModelUrl: String,
    val hiResCount: Int,
    val losslessCount: Int
)

/**
 * 本地曲库纯函数查询、统计、分组、筛选与排序引擎（零 Android 依赖，可完整单元测试）。
 */
object LocalMusicQueryEngine {

    /** 已知含分隔符的著名组合/乐队名，防止在多歌手拆分时被误拆 */
    private val PROTECTED_BAND_NAMES = listOf(
        "AC/DC",
        "Simon & Garfunkel",
        "Brooks & Dunn",
        "Earth, Wind & Fire",
        "Florence + The Machine",
        "Hall & Oates",
        "Daryl Hall & John Oates",
        "Above & Beyond",
        "Crosby, Stills & Nash",
        "Crosby, Stills, Nash & Young",
        "Tom Petty & The Heartbreakers",
        "Bob Marley & The Wailers",
        "Mumford & Sons",
        "Tegan and Sara",
        "Belle and Sebastian",
        "Of Monsters and Men",
        "Guns N' Roses",
        "Echo & The Bunnymen",
        "Kool & The Gang",
        "Ike & Tina Turner",
        "Nick Cave & The Bad Seeds",
        "Huey Lewis & The News",
        "Tears for Fears",
        "B.B. King",
        "S.H.E",
        "F.I.R.",
        "TFBOYS",
        "YOASOBI",
        "m-flo",
        "B'z",
        "A/B",
        "P!nk",
        "ATB",
        "R&B"
    )

    private val COLLAB_WORD_SEPARATOR_REGEX = Regex(
        """(?i)\s+(?:feat\.?|ft\.?|featuring|with|vs\.?|×|x)\s+"""
    )
    private val COLLAB_PUNCT_SEPARATOR_REGEX = Regex(
        """[、；;/&,，＆\\|]+"""
    )

    /**
     * 严谨拆分多歌手合唱字符串：
     * - 先保护已知含分隔符的乐队/组合名；
     * - 支持 `、`、`/`、`&`、`,`、`，`、`;`、`feat.`、`ft.`、`×` 等常见合唱写法；
     * - 清洗冗余空白与空段，按忽略大小写去重。
     */
    fun splitArtistNames(rawArtist: String): List<String> {
        val trimmed = rawArtist.trim()
        if (trimmed.isBlank()) return listOf("未知歌手")

        val protectedTokens = linkedMapOf<String, String>()
        var working = trimmed
        PROTECTED_BAND_NAMES.sortedByDescending { it.length }.forEachIndexed { idx, band ->
            val regex = Regex("(?i)(?<!\\p{L})${Regex.escape(band)}(?!\\p{L})")
            if (regex.containsMatchIn(working)) {
                val placeholder = "__BAND_TOKEN_${idx}__"
                protectedTokens[placeholder] = band
                working = regex.replace(working, placeholder)
            }
        }

        // 将括号内的合唱标注提取出来，例如 "晴天 (feat. 某某)" -> "晴天 / 某某"
        working = working.replace(
            Regex("""(?i)[(\[（【]\s*(?:feat\.?|ft\.?|featuring)\s+([^)\]）】]+)[)\]）】]"""),
            " / $1 "
        )

        val step1 = COLLAB_WORD_SEPARATOR_REGEX.replace(working, " / ")
        val parts = COLLAB_PUNCT_SEPARATOR_REGEX.split(step1)
            .map { token ->
                var restored = token.trim()
                protectedTokens.forEach { (ph, original) ->
                    restored = restored.replace(ph, original)
                }
                restored.trim(' ', '-', '_', '·', '(', ')', '（', '）')
            }
            .filter { it.isNotBlank() }

        if (parts.isEmpty()) return listOf(trimmed)

        val seenKeys = hashSetOf<String>()
        val result = mutableListOf<String>()
        for (name in parts) {
            val key = normalizeArtistKey(name)
            if (key.isNotBlank() && seenKeys.add(key)) {
                result.add(name)
            }
        }
        return result.ifEmpty { listOf(trimmed) }
    }

    fun normalizeArtistKey(name: String): String {
        return name.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    }

    fun normalizeAlbumKey(name: String): String {
        val clean = name.trim().ifBlank { "未知专辑" }
        return clean.replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    }

    /**
     * 二级音质筛选判定（遵循主流播放器筛选并集口径）：
     * - `FLAC_WAV` 命中全部 FLAC/WAV（含 Hi-Res 级 FLAC/WAV）
     * - `HI_RES` 仅命中 Hi-Res
     * - `MP3_AAC` 命中 MP3、AAC、M4A(AAC)
     */
    fun matchesQualityFilter(track: LocalAudioTrack, filter: LocalQualityFilter): Boolean {
        val fmt = track.format.uppercase(Locale.ROOT)
        return when (filter) {
            LocalQualityFilter.ALL -> true
            LocalQualityFilter.HI_RES -> track.qualityTier == LocalAudioQualityTier.HI_RES
            LocalQualityFilter.FLAC_WAV -> fmt == "FLAC" || fmt == "WAV"
            LocalQualityFilter.MP3_AAC -> fmt == "MP3" || fmt == "AAC" || (fmt == "M4A" && track.qualityTier == LocalAudioQualityTier.LOSSY)
        }
    }

    fun countByQualityFilter(tracks: List<LocalAudioTrack>, filter: LocalQualityFilter): Int {
        return tracks.count { matchesQualityFilter(it, filter) }
    }

    fun sortTracks(tracks: List<LocalAudioTrack>, sortOption: LocalSortOption): List<LocalAudioTrack> {
        if (tracks.size <= 1) return tracks
        val collator = Collator.getInstance(Locale.CHINA).apply {
            strength = Collator.PRIMARY
        }
        return when (sortOption) {
            LocalSortOption.DEFAULT -> tracks.sortedBy { it.addedOrder }
            LocalSortOption.TITLE -> tracks.sortedWith { a, b ->
                val cmp = collator.compare(a.title, b.title)
                if (cmp != 0) cmp else a.addedOrder.compareTo(b.addedOrder)
            }
            LocalSortOption.ARTIST -> tracks.sortedWith { a, b ->
                val aPrimary = a.artists.firstOrNull().orEmpty().ifBlank { a.artist }
                val bPrimary = b.artists.firstOrNull().orEmpty().ifBlank { b.artist }
                val cmp = collator.compare(aPrimary, bPrimary)
                if (cmp != 0) cmp else collator.compare(a.title, b.title)
            }
            LocalSortOption.FILE_SIZE -> tracks.sortedWith(
                compareByDescending<LocalAudioTrack> { it.fileSizeBytes }
                    .thenComparator { a, b -> collator.compare(a.title, b.title) }
            )
            LocalSortOption.DURATION -> tracks.sortedWith(
                compareByDescending<LocalAudioTrack> { it.durationMs }
                    .thenComparator { a, b -> collator.compare(a.title, b.title) }
            )
        }
    }

    fun filterAndSortTracks(
        tracks: List<LocalAudioTrack>,
        folderId: String? = null,
        qualityFilter: LocalQualityFilter = LocalQualityFilter.ALL,
        sortOption: LocalSortOption = LocalSortOption.DEFAULT,
        searchQuery: String = ""
    ): List<LocalAudioTrack> {
        val q = searchQuery.trim().lowercase(Locale.ROOT)
        val filtered = tracks.filter { track ->
            val folderMatch = folderId.isNullOrBlank() || track.folderId == folderId
            val qualityMatch = matchesQualityFilter(track, qualityFilter)
            val searchMatch = q.isBlank() ||
                track.title.lowercase(Locale.ROOT).contains(q) ||
                track.artist.lowercase(Locale.ROOT).contains(q) ||
                track.artists.any { it.lowercase(Locale.ROOT).contains(q) } ||
                track.album.lowercase(Locale.ROOT).contains(q) ||
                track.fileName.lowercase(Locale.ROOT).contains(q) ||
                track.folderName.lowercase(Locale.ROOT).contains(q)
            folderMatch && qualityMatch && searchMatch
        }
        return sortTracks(filtered, sortOption)
    }

    /**
     * 按歌手聚合（同一歌手合并为一项；多歌手合唱自动拆分归入每一位歌手）。
     */
    fun groupByArtists(
        tracks: List<LocalAudioTrack>,
        qualityFilter: LocalQualityFilter = LocalQualityFilter.ALL,
        sortOption: LocalSortOption = LocalSortOption.DEFAULT,
        searchQuery: String = ""
    ): List<LocalArtistGroup> {
        val filteredTracks = filterAndSortTracks(
            tracks = tracks,
            folderId = null,
            qualityFilter = qualityFilter,
            sortOption = sortOption,
            searchQuery = ""
        )
        val buckets = linkedMapOf<String, MutableList<LocalAudioTrack>>()
        val displayNames = linkedMapOf<String, MutableMap<String, Int>>()

        for (track in filteredTracks) {
            val resolvedArtists = track.artists.ifEmpty { splitArtistNames(track.artist) }
            val addedInTrack = hashSetOf<String>()
            for (artistName in resolvedArtists) {
                val key = normalizeArtistKey(artistName)
                if (key.isBlank() || !addedInTrack.add(key)) continue
                buckets.getOrPut(key) { mutableListOf() }.add(track)
                val freq = displayNames.getOrPut(key) { linkedMapOf() }
                freq[artistName] = (freq[artistName] ?: 0) + 1
            }
        }

        val q = searchQuery.trim().lowercase(Locale.ROOT)
        val collator = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }

        return buckets.entries.mapNotNull { (key, list) ->
            val bestName = displayNames[key]?.maxByOrNull { it.value }?.key ?: list.first().artist
            if (q.isNotBlank() &&
                !bestName.lowercase(Locale.ROOT).contains(q) &&
                list.none { it.title.lowercase(Locale.ROOT).contains(q) }
            ) {
                return@mapNotNull null
            }
            val cover = list.firstOrNull { it.coverModelUrl.isNotBlank() }?.coverModelUrl.orEmpty()
            LocalArtistGroup(
                name = bestName,
                normalizedKey = key,
                tracks = list,
                coverModelUrl = cover,
                hiResCount = list.count { it.qualityTier == LocalAudioQualityTier.HI_RES },
                losslessCount = list.count { it.qualityTier == LocalAudioQualityTier.LOSSLESS }
            )
        }.sortedWith { a, b ->
            when (sortOption) {
                LocalSortOption.ARTIST, LocalSortOption.TITLE -> collator.compare(a.name, b.name)
                LocalSortOption.FILE_SIZE -> b.tracks.sumOf { it.fileSizeBytes }.compareTo(a.tracks.sumOf { it.fileSizeBytes })
                LocalSortOption.DURATION -> b.tracks.sumOf { it.durationMs }.compareTo(a.tracks.sumOf { it.durationMs })
                LocalSortOption.DEFAULT -> {
                    val cntCmp = b.tracks.size.compareTo(a.tracks.size)
                    if (cntCmp != 0) cntCmp else collator.compare(a.name, b.name)
                }
            }
        }
    }

    /**
     * 按专辑聚合（同一专辑合并为一项，空专辑归入「未知专辑」）。
     */
    fun groupByAlbums(
        tracks: List<LocalAudioTrack>,
        qualityFilter: LocalQualityFilter = LocalQualityFilter.ALL,
        sortOption: LocalSortOption = LocalSortOption.DEFAULT,
        searchQuery: String = ""
    ): List<LocalAlbumGroup> {
        val filteredTracks = filterAndSortTracks(
            tracks = tracks,
            folderId = null,
            qualityFilter = qualityFilter,
            sortOption = sortOption,
            searchQuery = ""
        )
        val buckets = linkedMapOf<String, MutableList<LocalAudioTrack>>()
        val displayNames = linkedMapOf<String, String>()

        for (track in filteredTracks) {
            val albumName = track.album.trim().ifBlank { "未知专辑" }
            val key = normalizeAlbumKey(albumName)
            buckets.getOrPut(key) { mutableListOf() }.add(track)
            if (!displayNames.containsKey(key)) {
                displayNames[key] = albumName
            }
        }

        val q = searchQuery.trim().lowercase(Locale.ROOT)
        val collator = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }

        return buckets.entries.mapNotNull { (key, list) ->
            val albumTitle = displayNames[key] ?: "未知专辑"
            val distinctArtists = list.flatMap { it.artists.ifEmpty { splitArtistNames(it.artist) } }.distinct()
            val artistSummary = when {
                distinctArtists.isEmpty() -> "未知歌手"
                distinctArtists.size <= 2 -> distinctArtists.joinToString(" / ")
                else -> "${distinctArtists.take(2).joinToString(" / ")} 等 ${distinctArtists.size} 位歌手"
            }
            if (q.isNotBlank() &&
                !albumTitle.lowercase(Locale.ROOT).contains(q) &&
                !artistSummary.lowercase(Locale.ROOT).contains(q) &&
                list.none { it.title.lowercase(Locale.ROOT).contains(q) }
            ) {
                return@mapNotNull null
            }
            val cover = list.firstOrNull { it.coverModelUrl.isNotBlank() }?.coverModelUrl.orEmpty()
            LocalAlbumGroup(
                name = albumTitle,
                artistSummary = artistSummary,
                normalizedKey = key,
                tracks = list,
                coverModelUrl = cover,
                hiResCount = list.count { it.qualityTier == LocalAudioQualityTier.HI_RES },
                losslessCount = list.count { it.qualityTier == LocalAudioQualityTier.LOSSLESS }
            )
        }.sortedWith { a, b ->
            // 未知专辑永远排在末尾
            val aUnknown = a.name == "未知专辑"
            val bUnknown = b.name == "未知专辑"
            if (aUnknown != bUnknown) return@sortedWith if (aUnknown) 1 else -1
            when (sortOption) {
                LocalSortOption.TITLE -> collator.compare(a.name, b.name)
                LocalSortOption.ARTIST -> collator.compare(a.artistSummary, b.artistSummary)
                LocalSortOption.FILE_SIZE -> b.tracks.sumOf { it.fileSizeBytes }.compareTo(a.tracks.sumOf { it.fileSizeBytes })
                LocalSortOption.DURATION -> b.tracks.sumOf { it.durationMs }.compareTo(a.tracks.sumOf { it.durationMs })
                LocalSortOption.DEFAULT -> {
                    val cntCmp = b.tracks.size.compareTo(a.tracks.size)
                    if (cntCmp != 0) cntCmp else collator.compare(a.name, b.name)
                }
            }
        }
    }

    /**
     * 汇总本地曲库统计（Hi-Res 与 无损 严格互斥，避免重复计数）。
     */
    fun summarize(
        tracks: List<LocalAudioTrack>,
        folders: List<LocalMusicFolder>,
        lyricFolders: List<LocalLyricFolder>
    ): LocalLibrarySummary {
        var hiRes = 0
        var lossless = 0
        var lossy = 0
        var bytes = 0L
        for (t in tracks) {
            bytes += t.fileSizeBytes.coerceAtLeast(0L)
            when (t.qualityTier) {
                LocalAudioQualityTier.HI_RES -> hiRes++
                LocalAudioQualityTier.LOSSLESS -> lossless++
                LocalAudioQualityTier.LOSSY -> lossy++
            }
        }
        return LocalLibrarySummary(
            totalCount = tracks.size,
            hiResCount = hiRes,
            losslessCount = lossless,
            lossyCount = lossy,
            totalBytes = bytes,
            folderCount = folders.size,
            lyricFolderCount = lyricFolders.size
        )
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            else -> String.format(Locale.US, "%.0f KB", (bytes / kb).coerceAtLeast(1.0))
        }
    }

    fun formatSampleRate(sampleRateHz: Int): String {
        if (sampleRateHz <= 0) return ""
        val khz = sampleRateHz / 1000.0
        return if (sampleRateHz % 1000 == 0) {
            "${sampleRateHz / 1000}kHz"
        } else {
            String.format(Locale.US, "%.1fkHz", khz)
        }
    }

    fun formatSampleRateShort(sampleRateHz: Int): String {
        if (sampleRateHz <= 0) return ""
        val khz = sampleRateHz / 1000.0
        return if (sampleRateHz % 1000 == 0) {
            "${sampleRateHz / 1000}k"
        } else {
            String.format(Locale.US, "%.1fk", khz)
        }
    }
}
