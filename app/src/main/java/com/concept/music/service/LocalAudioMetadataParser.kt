package com.concept.music.service

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.concept.music.data.model.LocalAudioQualityTier
import com.concept.music.data.model.LocalMusicQueryEngine
import com.concept.music.data.model.stripKnownAudioFileExtension
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale
import kotlin.math.min

/**
 * 本地音频解析结果（零外部重型库依赖）：
 * 同时捕获真实容器/编码、精确采样率、位深、时长、码率、标签信息、内嵌封面字节与内嵌歌词文本。
 */
data class ParsedLocalAudioMetadata(
    val title: String = "",
    val artist: String = "",
    val artists: List<String> = emptyList(),
    val album: String = "",
    val year: String = "",
    val format: String = "MP3",
    val qualityTier: LocalAudioQualityTier = LocalAudioQualityTier.LOSSY,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val bitrateKbps: Int = 0,
    val durationMs: Long = 0L,
    val embeddedLyric: String = "",
    val coverBytes: ByteArray? = null
)

/**
 * 零外部重型依赖的本地音频流式元数据解析引擎：
 * - 纯流式读取头部与元数据块，对大体积 FLAC/WAV/DSD 零整文件内存拷贝，防 OOM；
 * - 精准支持 FLAC (STREAMINFO + VorbisComment + Picture)、WAV (RIFF/WAVE fmt + data + LIST INFO + id3)、
 *   MP3 (ID3v2.2/2.3/2.4 含 UTF-8/UTF-16/GB18030 防乱码 + USLT/SYLT/TXXX/APIC + MPEG 帧头)、
 *   M4A/MP4 (区分 ALAC 无损与 AAC 有损 + ilst 标签/歌词/封面)、裸 AAC (ADTS)、OGG (Vorbis/Opus)；
 * - 在 Android 端自动结合 MediaMetadataRetriever 补齐非标封装的时长、码率与缺失字段。
 */
object LocalAudioMetadataParser {

    val SUPPORTED_AUDIO_EXTENSIONS = setOf(
        "flac", "wav", "mp3", "m4a", "aac", "ogg", "opus", "alac", "ape", "dsf", "dff", "wma"
    )

    val SUPPORTED_LYRIC_EXTENSIONS = setOf("lrc", "krc")

    private val LOSSLESS_FORMATS = setOf("FLAC", "WAV", "ALAC", "APE", "DSF", "DFF", "DSD")

    private const val MAX_TEXT_METADATA_BYTES = 2 * 1024 * 1024
    private const val MAX_COVER_BYTES = 12 * 1024 * 1024
    private const val MAX_ID3_TAG_BYTES = 16 * 1024 * 1024
    private const val MAX_MP4_CONTAINER_BYTES = 16 * 1024 * 1024

    fun isSupportedAudioFileName(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in SUPPORTED_AUDIO_EXTENSIONS
    }

    fun isSupportedLyricFileName(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in SUPPORTED_LYRIC_EXTENSIONS
    }

    /**
     * 严谨判定本地音频物理音质层级（主流播放器口径）：
     * - 无损格式且 (位深 >= 24-bit 或 采样率 > 48000 Hz) -> HI_RES
     * - 其他无损格式 -> LOSSLESS
     * - 其余 -> LOSSY
     */
    fun classifyQualityTier(
        format: String,
        sampleRateHz: Int,
        bitDepth: Int
    ): LocalAudioQualityTier {
        val fmt = format.uppercase(Locale.ROOT)
        if (fmt !in LOSSLESS_FORMATS) return LocalAudioQualityTier.LOSSY
        if (fmt == "DSD" || fmt == "DSF" || fmt == "DFF") return LocalAudioQualityTier.HI_RES
        return if (bitDepth >= 24 || sampleRateHz > 48_000) {
            LocalAudioQualityTier.HI_RES
        } else {
            LocalAudioQualityTier.LOSSLESS
        }
    }

    /**
     * 当音频内嵌标签缺失标题/歌手时，智能从文件名推断（例如 "周杰伦 - 晴天.flac"）。
     */
    fun parseTitleArtistFromFileName(fileName: String): Pair<String, String> {
        val base = stripKnownAudioFileExtension(fileName).trim()
        if (base.isBlank()) return "未知歌曲" to "未知歌手"
        // 清理前导序号如 "01. 晴天" / "01 - 晴天"
        val cleaned = base.replace(Regex("""^\d{1,3}\s*[.\-_、]\s+"""), "").trim().ifBlank { base }
        val sepIndex = cleaned.indexOf(" - ")
        if (sepIndex > 0 && sepIndex + 3 < cleaned.length) {
            val left = cleaned.substring(0, sepIndex).trim()
            val right = cleaned.substring(sepIndex + 3).trim()
            if (left.isNotBlank() && right.isNotBlank()) {
                return right to left
            }
        }
        return cleaned to "未知歌手"
    }

    /**
     * 自动探测字节数组字符编码并解码（支持 UTF-8 BOM / 合法 UTF-8 / UTF-16LE / UTF-16BE / GB18030），
     * 避免中文 LRC 歌词或旧版 ID3 标签出现乱码。
     */
    fun decodeSmartTextBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // 严格 UTF-8 校验：任何非法 UTF-8 字节序列立即回退 GB18030（覆盖 GBK / GB2312）
        val utf8Decoded = runCatching {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (utf8Decoded != null) return utf8Decoded

        val gbCharset = runCatching { Charset.forName("GB18030") }
            .recoverCatching { Charset.forName("GBK") }
            .getOrDefault(Charsets.ISO_8859_1)
        return String(bytes, gbCharset)
    }

    /**
     * Android 端完整入口：先执行高精度二进制流解析，再对缺失字段用 MediaMetadataRetriever 补全。
     */
    fun parseFromContentUri(
        context: Context,
        uri: Uri,
        fileName: String,
        fileSizeBytes: Long
    ): ParsedLocalAudioMetadata {
        val streamParsed = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                parseFromStream(input, fileName, fileSizeBytes)
            }
        }.getOrNull() ?: defaultMetadataForFileName(fileName, fileSizeBytes)

        val needMmrFallback = streamParsed.durationMs <= 0L ||
            streamParsed.title.isBlank() ||
            streamParsed.artist.isBlank() ||
            streamParsed.artist == "未知歌手" ||
            streamParsed.coverBytes == null ||
            streamParsed.bitrateKbps <= 0

        if (!needMmrFallback) {
            return finalizeMetadata(streamParsed, fileName, fileSizeBytes)
        }

        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val mmrTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim()
            val mmrArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim()
            val mmrAlbumArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty().trim()
            val mmrAlbum = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim()
            val mmrYear = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR).orEmpty().trim()
            val mmrDuration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val mmrBitrateBps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            val mmrSampleRate = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            } else 0
            val mmrBitDepth = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
            } else 0
            val mmrCover = if (streamParsed.coverBytes == null) {
                runCatching { mmr.embeddedPicture?.takeIf { it.size <= MAX_COVER_BYTES } }.getOrNull()
            } else null

            val merged = streamParsed.copy(
                title = streamParsed.title.ifBlank { mmrTitle },
                artist = streamParsed.artist.takeIf { it.isNotBlank() && it != "未知歌手" }
                    ?: mmrArtist.ifBlank { mmrAlbumArtist },
                album = streamParsed.album.ifBlank { mmrAlbum },
                year = streamParsed.year.ifBlank { mmrYear },
                durationMs = if (streamParsed.durationMs > 0L) streamParsed.durationMs else mmrDuration,
                bitrateKbps = if (streamParsed.bitrateKbps > 0) streamParsed.bitrateKbps else (mmrBitrateBps / 1000L).toInt(),
                sampleRateHz = if (streamParsed.sampleRateHz > 0) streamParsed.sampleRateHz else mmrSampleRate,
                bitDepth = if (streamParsed.bitDepth > 0) streamParsed.bitDepth else mmrBitDepth,
                coverBytes = streamParsed.coverBytes ?: mmrCover
            )
            finalizeMetadata(merged, fileName, fileSizeBytes)
        } catch (_: Throwable) {
            finalizeMetadata(streamParsed, fileName, fileSizeBytes)
        } finally {
            runCatching { mmr.release() }
        }
    }

    /**
     * 纯 JVM 流式入口：依据文件魔数与扩展名路由至对应无依赖解析器（供线上与 JVM 单元测试共用）。
     */
    fun parseFromStream(
        rawStream: InputStream,
        fileName: String,
        fileSizeBytes: Long = 0L
    ): ParsedLocalAudioMetadata {
        val stream = if (rawStream.markSupported()) rawStream else BufferedInputStream(rawStream, 64 * 1024)
        stream.mark(32)
        val probe = ByteArray(16)
        val probeRead = readUpTo(stream, probe, 0, probe.size)
        stream.reset()

        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val parsed = when {
            probeRead >= 4 && matchAscii(probe, 0, "fLaC") -> parseFlacStream(stream, fileSizeBytes)
            probeRead >= 12 && matchAscii(probe, 0, "RIFF") && matchAscii(probe, 8, "WAVE") -> parseWavStream(stream, fileSizeBytes)
            probeRead >= 8 && matchAscii(probe, 4, "ftyp") -> parseMp4Stream(stream, fileSizeBytes, ext)
            probeRead >= 4 && matchAscii(probe, 0, "OggS") -> parseOggStream(stream, fileSizeBytes)
            probeRead >= 3 && matchAscii(probe, 0, "ID3") -> {
                if (ext == "flac") {
                    // 极少数 FLAC 前部误带 ID3v2 头，仍先跳过/解析 ID3 再继续
                    parseMp3Stream(stream, fileSizeBytes, fallbackFormat = "MP3")
                } else {
                    parseMp3Stream(stream, fileSizeBytes, fallbackFormat = if (ext == "aac") "AAC" else "MP3")
                }
            }
            probeRead >= 4 && matchAscii(probe, 0, "MAC ") -> defaultMetadataForFileName(fileName, fileSizeBytes, format = "APE")
            probeRead >= 4 && (matchAscii(probe, 0, "DSD ") || matchAscii(probe, 0, "FRM8")) ->
                defaultMetadataForFileName(fileName, fileSizeBytes, format = "DSD")
            ext == "aac" -> parseAdtsAacStream(stream, fileSizeBytes)
            ext == "mp3" -> parseMp3Stream(stream, fileSizeBytes, fallbackFormat = "MP3")
            ext == "flac" -> parseFlacStream(stream, fileSizeBytes)
            ext == "wav" -> parseWavStream(stream, fileSizeBytes)
            ext == "m4a" || ext == "alac" -> parseMp4Stream(stream, fileSizeBytes, ext)
            ext == "ogg" || ext == "opus" -> parseOggStream(stream, fileSizeBytes)
            else -> defaultMetadataForFileName(fileName, fileSizeBytes)
        }
        return finalizeMetadata(parsed, fileName, fileSizeBytes)
    }

    private fun defaultMetadataForFileName(
        fileName: String,
        fileSizeBytes: Long,
        format: String? = null
    ): ParsedLocalAudioMetadata {
        val ext = fileName.substringAfterLast('.', "").uppercase(Locale.ROOT)
        val resolvedFmt = format ?: when (ext) {
            "M4A" -> "AAC"
            "DSF", "DFF" -> "DSD"
            "" -> "MP3"
            else -> ext
        }
        val (inferredTitle, inferredArtist) = parseTitleArtistFromFileName(fileName)
        return ParsedLocalAudioMetadata(
            title = inferredTitle,
            artist = inferredArtist,
            artists = LocalMusicQueryEngine.splitArtistNames(inferredArtist),
            format = resolvedFmt,
            qualityTier = classifyQualityTier(resolvedFmt, 0, 0)
        )
    }

    private fun finalizeMetadata(
        raw: ParsedLocalAudioMetadata,
        fileName: String,
        fileSizeBytes: Long
    ): ParsedLocalAudioMetadata {
        val (fallbackTitle, fallbackArtist) = parseTitleArtistFromFileName(fileName)
        val cleanTitle = raw.title.trim().ifBlank { fallbackTitle }
        val cleanArtist = raw.artist.trim().ifBlank { fallbackArtist }
        val splitArtists = raw.artists.ifEmpty { LocalMusicQueryEngine.splitArtistNames(cleanArtist) }
        val computedBitrate = when {
            raw.bitrateKbps > 0 -> raw.bitrateKbps
            raw.durationMs > 1000L && fileSizeBytes > 0L ->
                ((fileSizeBytes * 8L) / raw.durationMs).toInt().coerceIn(32, 25_000)
            else -> 0
        }
        val fmt = raw.format.uppercase(Locale.ROOT).ifBlank { "MP3" }
        val tier = classifyQualityTier(fmt, raw.sampleRateHz, raw.bitDepth)
        return raw.copy(
            title = cleanTitle,
            artist = cleanArtist,
            artists = splitArtists,
            album = raw.album.trim(),
            year = raw.year.trim().take(10),
            format = fmt,
            qualityTier = tier,
            bitrateKbps = computedBitrate,
            embeddedLyric = raw.embeddedLyric.trim()
        )
    }

    // =========================================================================
    // 1. FLAC 流式解析器（STREAMINFO + VORBIS_COMMENT + PICTURE）
    // =========================================================================

    private fun parseFlacStream(stream: InputStream, fileSizeBytes: Long): ParsedLocalAudioMetadata {
        val magic = ByteArray(4)
        if (readUpTo(stream, magic, 0, 4) < 4 || !matchAscii(magic, 0, "fLaC")) {
            return ParsedLocalAudioMetadata(format = "FLAC")
        }

        var sampleRate = 0
        var bitDepth = 0
        var durationMs = 0L
        var title = ""
        val artistList = mutableListOf<String>()
        var albumArtist = ""
        var album = ""
        var year = ""
        var lyric = ""
        var coverBytes: ByteArray? = null
        var coverIsFront = false

        var isLast = false
        val header = ByteArray(4)
        var blockGuard = 0

        while (!isLast && blockGuard++ < 64) {
            if (readUpTo(stream, header, 0, 4) < 4) break
            val b0 = header[0].toInt() and 0xFF
            isLast = (b0 and 0x80) != 0
            val blockType = b0 and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)

            if (length < 0) break

            when (blockType) {
                0 -> { // STREAMINFO
                    if (length in 18..1024) {
                        val buf = readExactBytes(stream, length) ?: break
                        sampleRate = ((buf[10].toInt() and 0xFF) shl 12) or
                            ((buf[11].toInt() and 0xFF) shl 4) or
                            ((buf[12].toInt() and 0xF0) ushr 4)
                        bitDepth = (((buf[12].toInt() and 0x01) shl 4) or
                            ((buf[13].toInt() and 0xF0) ushr 4)) + 1
                        val totalSamples = (((buf[13].toInt() and 0x0F).toLong() shl 32) or
                            ((buf[14].toInt() and 0xFF).toLong() shl 24) or
                            ((buf[15].toInt() and 0xFF).toLong() shl 16) or
                            ((buf[16].toInt() and 0xFF).toLong() shl 8) or
                            (buf[17].toInt() and 0xFF).toLong())
                        if (sampleRate > 0 && totalSamples > 0L) {
                            durationMs = (totalSamples * 1000L) / sampleRate.toLong()
                        }
                    } else {
                        skipFully(stream, length.toLong())
                    }
                }
                4 -> { // VORBIS_COMMENT
                    if (length <= MAX_TEXT_METADATA_BYTES) {
                        val buf = readExactBytes(stream, length) ?: break
                        val comments = parseVorbisCommentBytes(buf)
                        title = comments.title.ifBlank { title }
                        artistList.addAll(comments.artists)
                        albumArtist = comments.albumArtist.ifBlank { albumArtist }
                        album = comments.album.ifBlank { album }
                        year = comments.year.ifBlank { year }
                        if (lyric.isBlank() && comments.lyric.isNotBlank()) {
                            lyric = comments.lyric
                        }
                        if (coverBytes == null && comments.pictureBytes != null) {
                            coverBytes = comments.pictureBytes
                        }
                    } else {
                        skipFully(stream, length.toLong())
                    }
                }
                6 -> { // PICTURE
                    if (length in 32..MAX_COVER_BYTES && !coverIsFront) {
                        val buf = readExactBytes(stream, length) ?: break
                        val (picType, imgBytes) = parseFlacPictureBlock(buf)
                        if (imgBytes != null && imgBytes.isNotEmpty()) {
                            if (picType == 3 || coverBytes == null) {
                                coverBytes = imgBytes
                                if (picType == 3) coverIsFront = true
                            }
                        }
                    } else {
                        skipFully(stream, length.toLong())
                    }
                }
                else -> {
                    skipFully(stream, length.toLong())
                }
            }
        }

        val combinedArtist = artistList.distinct().joinToString(" / ").ifBlank { albumArtist }
        val bitrateKbps = if (durationMs > 1000L && fileSizeBytes > 0L) {
            ((fileSizeBytes * 8L) / durationMs).toInt()
        } else 0

        return ParsedLocalAudioMetadata(
            title = title,
            artist = combinedArtist,
            album = album,
            year = year,
            format = "FLAC",
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            bitrateKbps = bitrateKbps,
            durationMs = durationMs,
            embeddedLyric = lyric,
            coverBytes = coverBytes
        )
    }

    private data class VorbisParsed(
        val title: String = "",
        val artists: List<String> = emptyList(),
        val albumArtist: String = "",
        val album: String = "",
        val year: String = "",
        val lyric: String = "",
        val pictureBytes: ByteArray? = null
    )

    private fun parseVorbisCommentBytes(buf: ByteArray): VorbisParsed {
        if (buf.size < 8) return VorbisParsed()
        var pos = 0
        val vendorLen = readInt32LE(buf, pos)
        pos += 4
        if (vendorLen < 0 || pos + vendorLen + 4 > buf.size) return VorbisParsed()
        pos += vendorLen
        val count = readInt32LE(buf, pos).coerceIn(0, 4096)
        pos += 4

        var title = ""
        val artists = mutableListOf<String>()
        var albumArtist = ""
        var album = ""
        var year = ""
        var lyric = ""
        var picBytes: ByteArray? = null

        for (i in 0 until count) {
            if (pos + 4 > buf.size) break
            val len = readInt32LE(buf, pos)
            pos += 4
            if (len < 0 || pos + len > buf.size) break
            val entry = String(buf, pos, len, Charsets.UTF_8)
            pos += len
            val eqIdx = entry.indexOf('=')
            if (eqIdx <= 0) continue
            val key = entry.substring(0, eqIdx).trim().uppercase(Locale.ROOT)
            val value = entry.substring(eqIdx + 1).trim()
            if (value.isBlank()) continue
            when (key) {
                "TITLE" -> if (title.isBlank()) title = value
                "ARTIST" -> artists.add(value)
                "ALBUMARTIST", "ALBUM ARTIST" -> if (albumArtist.isBlank()) albumArtist = value
                "ALBUM" -> if (album.isBlank()) album = value
                "DATE", "YEAR" -> if (year.isBlank()) year = value
                "LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS", "LYRIC" -> {
                    if (lyric.isBlank() || (value.contains("[0") && !lyric.contains("[0"))) {
                        lyric = value
                    }
                }
                "METADATA_BLOCK_PICTURE" -> {
                    if (picBytes == null && value.length <= MAX_COVER_BYTES * 4 / 3) {
                        runCatching {
                            val raw = Base64.getMimeDecoder().decode(value)
                            picBytes = parseFlacPictureBlock(raw).second
                        }
                    }
                }
            }
        }
        return VorbisParsed(title, artists, albumArtist, album, year, lyric, picBytes)
    }

    private fun parseFlacPictureBlock(buf: ByteArray): Pair<Int, ByteArray?> {
        if (buf.size < 32) return 0 to null
        var pos = 0
        val picType = readInt32BE(buf, pos); pos += 4
        val mimeLen = readInt32BE(buf, pos); pos += 4
        if (mimeLen < 0 || pos + mimeLen + 4 > buf.size) return picType to null
        pos += mimeLen
        val descLen = readInt32BE(buf, pos); pos += 4
        if (descLen < 0 || pos + descLen + 20 > buf.size) return picType to null
        pos += descLen
        pos += 16 // width, height, depth, indexed colors
        val dataLen = readInt32BE(buf, pos); pos += 4
        if (dataLen <= 0 || dataLen > MAX_COVER_BYTES || pos + dataLen > buf.size) return picType to null
        return picType to buf.copyOfRange(pos, pos + dataLen)
    }

    // =========================================================================
    // 2. WAV 流式解析器（RIFF/WAVE + fmt + data + LIST INFO + id3 ）
    // =========================================================================

    private fun parseWavStream(stream: InputStream, fileSizeBytes: Long): ParsedLocalAudioMetadata {
        val riffHeader = ByteArray(12)
        if (readUpTo(stream, riffHeader, 0, 12) < 12 ||
            !matchAscii(riffHeader, 0, "RIFF") ||
            !matchAscii(riffHeader, 8, "WAVE")
        ) {
            return ParsedLocalAudioMetadata(format = "WAV")
        }

        var sampleRate = 0
        var bitDepth = 0
        var channels = 0
        var byteRate = 0L
        var dataSize = 0L
        var title = ""
        var artist = ""
        var album = ""
        var year = ""
        var lyric = ""
        var coverBytes: ByteArray? = null

        val chunkHeader = ByteArray(8)
        var guard = 0
        while (guard++ < 128) {
            if (readUpTo(stream, chunkHeader, 0, 8) < 8) break
            val chunkId = String(chunkHeader, 0, 4, Charsets.ISO_8859_1)
            val chunkSize = readUInt32LE(chunkHeader, 4)
            val paddedSkip = chunkSize + (chunkSize and 1L)

            when (chunkId) {
                "fmt " -> {
                    if (chunkSize in 16L..4096L) {
                        val fmt = readExactBytes(stream, chunkSize.toInt()) ?: break
                        val audioFormat = readUInt16LE(fmt, 0)
                        channels = readUInt16LE(fmt, 2)
                        sampleRate = readUInt32LE(fmt, 4).toInt()
                        byteRate = readUInt32LE(fmt, 8)
                        bitDepth = readUInt16LE(fmt, 14)
                        if (audioFormat == 0xFFFE && fmt.size >= 22) {
                            val validBits = readUInt16LE(fmt, 18)
                            if (validBits > 0) bitDepth = validBits
                        }
                        if ((chunkSize and 1L) == 1L) skipFully(stream, 1L)
                    } else {
                        skipFully(stream, paddedSkip)
                    }
                }
                "data" -> {
                    dataSize = chunkSize
                    skipFully(stream, paddedSkip)
                }
                "LIST" -> {
                    if (chunkSize in 4L..MAX_TEXT_METADATA_BYTES.toLong()) {
                        val listBytes = readExactBytes(stream, chunkSize.toInt()) ?: break
                        if ((chunkSize and 1L) == 1L) skipFully(stream, 1L)
                        if (listBytes.size >= 4 && matchAscii(listBytes, 0, "INFO")) {
                            var pos = 4
                            while (pos + 8 <= listBytes.size) {
                                val subId = String(listBytes, pos, 4, Charsets.ISO_8859_1)
                                val subSize = readUInt32LE(listBytes, pos + 4).toInt()
                                pos += 8
                                if (subSize < 0 || pos + subSize > listBytes.size) break
                                val rawVal = listBytes.copyOfRange(pos, pos + subSize)
                                val cleanEnd = rawVal.indexOfFirst { it == 0.toByte() }.let { if (it < 0) rawVal.size else it }
                                val text = decodeSmartTextBytes(rawVal.copyOf(cleanEnd)).trim()
                                when (subId) {
                                    "INAM" -> if (title.isBlank()) title = text
                                    "IART" -> if (artist.isBlank()) artist = text
                                    "IPRD" -> if (album.isBlank()) album = text
                                    "ICRD" -> if (year.isBlank()) year = text
                                }
                                pos += subSize + (subSize and 1)
                            }
                        }
                    } else {
                        skipFully(stream, paddedSkip)
                    }
                }
                "id3 ", "ID3 " -> {
                    if (chunkSize in 10L..MAX_ID3_TAG_BYTES.toLong()) {
                        val id3Bytes = readExactBytes(stream, chunkSize.toInt()) ?: break
                        if ((chunkSize and 1L) == 1L) skipFully(stream, 1L)
                        val parsedId3 = parseId3v2TagBytes(id3Bytes)
                        if (parsedId3.title.isNotBlank()) title = parsedId3.title
                        if (parsedId3.artist.isNotBlank()) artist = parsedId3.artist
                        if (parsedId3.album.isNotBlank()) album = parsedId3.album
                        if (parsedId3.year.isNotBlank()) year = parsedId3.year
                        if (parsedId3.lyric.isNotBlank()) lyric = parsedId3.lyric
                        if (parsedId3.coverBytes != null) coverBytes = parsedId3.coverBytes
                    } else {
                        skipFully(stream, paddedSkip)
                    }
                }
                else -> {
                    if (paddedSkip > 0L) skipFully(stream, paddedSkip) else break
                }
            }
        }

        val durationMs = when {
            byteRate > 0L && dataSize > 0L -> (dataSize * 1000L) / byteRate
            sampleRate > 0 && channels > 0 && bitDepth > 0 && dataSize > 0L ->
                (dataSize * 8000L) / (sampleRate.toLong() * channels * bitDepth)
            else -> 0L
        }
        val bitrateKbps = when {
            byteRate > 0L -> ((byteRate * 8L) / 1000L).toInt()
            sampleRate > 0 && channels > 0 && bitDepth > 0 -> (sampleRate.toLong() * channels * bitDepth / 1000L).toInt()
            else -> 0
        }

        return ParsedLocalAudioMetadata(
            title = title,
            artist = artist,
            album = album,
            year = year,
            format = "WAV",
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            bitrateKbps = bitrateKbps,
            durationMs = durationMs,
            embeddedLyric = lyric,
            coverBytes = coverBytes
        )
    }

    // =========================================================================
    // 3. MP3 + ID3v2.2 / v2.3 / v2.4 + MPEG 帧头解析器
    // =========================================================================

    private data class Id3Parsed(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val year: String = "",
        val lyric: String = "",
        val coverBytes: ByteArray? = null
    )

    private fun parseMp3Stream(
        stream: InputStream,
        fileSizeBytes: Long,
        fallbackFormat: String
    ): ParsedLocalAudioMetadata {
        val peek = ByteArray(10)
        stream.mark(16)
        val n = readUpTo(stream, peek, 0, 10)
        var id3 = Id3Parsed()
        var audioStartOffset = 0L

        if (n == 10 && matchAscii(peek, 0, "ID3")) {
            val tagSize = decodeSynchsafe32(peek, 6)
            val totalTagBytes = 10 + tagSize
            if (tagSize in 1..MAX_ID3_TAG_BYTES) {
                val body = readExactBytes(stream, tagSize)
                if (body != null) {
                    val fullTag = ByteArray(10 + body.size)
                    System.arraycopy(peek, 0, fullTag, 0, 10)
                    System.arraycopy(body, 0, fullTag, 10, body.size)
                    id3 = parseId3v2TagBytes(fullTag)
                }
                audioStartOffset = totalTagBytes.toLong()
            } else {
                skipFully(stream, tagSize.toLong().coerceAtLeast(0L))
                audioStartOffset = totalTagBytes.toLong()
            }
        } else {
            stream.reset()
        }

        // 扫描前 64KB 寻找首个有效 MPEG 帧头提取采样率与码率
        val scanBuf = ByteArray(64 * 1024)
        val scanned = readUpTo(stream, scanBuf, 0, scanBuf.size)
        val mpeg = parseFirstMpegFrame(scanBuf, scanned, (fileSizeBytes - audioStartOffset).coerceAtLeast(0L))

        return ParsedLocalAudioMetadata(
            title = id3.title,
            artist = id3.artist,
            album = id3.album,
            year = id3.year,
            format = fallbackFormat,
            sampleRateHz = mpeg.sampleRateHz,
            bitDepth = 0,
            bitrateKbps = mpeg.bitrateKbps,
            durationMs = mpeg.durationMs,
            embeddedLyric = id3.lyric,
            coverBytes = id3.coverBytes
        )
    }

    private fun parseId3v2TagBytes(tagBytes: ByteArray): Id3Parsed {
        if (tagBytes.size < 10 || !matchAscii(tagBytes, 0, "ID3")) return Id3Parsed()
        val major = tagBytes[3].toInt() and 0xFF
        val flags = tagBytes[5].toInt() and 0xFF
        val unsync = (flags and 0x80) != 0
        val hasExtendedHeader = (flags and 0x40) != 0
        val declaredTagSize = decodeSynchsafe32(tagBytes, 6)
        val endLimit = min(tagBytes.size, 10 + declaredTagSize)

        val bodyBytes = if (unsync && major < 4) {
            removeUnsynchronization(tagBytes, 10, endLimit)
        } else {
            tagBytes.copyOfRange(10, endLimit)
        }

        var pos = 0
        if (hasExtendedHeader && bodyBytes.size >= 4) {
            val extSize = if (major >= 4) decodeSynchsafe32(bodyBytes, 0) else readInt32BE(bodyBytes, 0) + 4
            if (extSize in 4..bodyBytes.size) pos += extSize
        }

        var title = ""
        var artist = ""
        var albumArtist = ""
        var album = ""
        var year = ""
        var lyric = ""
        var coverBytes: ByteArray? = null
        var coverIsFront = false

        if (major == 2) {
            // ID3v2.2: 3B frameId + 3B size
            while (pos + 6 <= bodyBytes.size) {
                if (bodyBytes[pos] == 0.toByte()) break
                val fid = String(bodyBytes, pos, 3, Charsets.ISO_8859_1)
                val fsize = ((bodyBytes[pos + 3].toInt() and 0xFF) shl 16) or
                    ((bodyBytes[pos + 4].toInt() and 0xFF) shl 8) or
                    (bodyBytes[pos + 5].toInt() and 0xFF)
                pos += 6
                if (fsize <= 0 || pos + fsize > bodyBytes.size) break
                val payload = bodyBytes.copyOfRange(pos, pos + fsize)
                pos += fsize
                when (fid) {
                    "TT2" -> if (title.isBlank()) title = decodeId3TextFrame(payload)
                    "TP1" -> if (artist.isBlank()) artist = decodeId3TextFrame(payload, joinMulti = true)
                    "TP2" -> if (albumArtist.isBlank()) albumArtist = decodeId3TextFrame(payload, joinMulti = true)
                    "TAL" -> if (album.isBlank()) album = decodeId3TextFrame(payload)
                    "TYE" -> if (year.isBlank()) year = decodeId3TextFrame(payload)
                    "ULT" -> {
                        val text = decodeId3UsltFrame(payload)
                        if (text.isNotBlank() && lyric.isBlank()) lyric = text
                    }
                    "PIC" -> {
                        if (!coverIsFront) {
                            val (pt, img) = decodeId3PicV22(payload)
                            if (img != null && (pt == 3 || coverBytes == null)) {
                                coverBytes = img
                                if (pt == 3) coverIsFront = true
                            }
                        }
                    }
                }
            }
        } else {
            // ID3v2.3 / ID3v2.4: 4B frameId + 4B size + 2B flags
            while (pos + 10 <= bodyBytes.size) {
                if (bodyBytes[pos] == 0.toByte()) break
                val fid = String(bodyBytes, pos, 4, Charsets.ISO_8859_1)
                if (!fid.all { it in 'A'..'Z' || it in '0'..'9' }) break
                var fsize = if (major >= 4) decodeSynchsafe32(bodyBytes, pos + 4) else readInt32BE(bodyBytes, pos + 4)
                val frameFlags = readUInt16BE(bodyBytes, pos + 8)
                pos += 10
                if (fsize <= 0 || pos + fsize > bodyBytes.size) {
                    // 兼容部分未用 synchsafe 的 ID3v2.4 写入器
                    if (major >= 4) {
                        val altSize = readInt32BE(bodyBytes, pos - 6)
                        if (altSize in 1..(bodyBytes.size - pos)) {
                            fsize = altSize
                        } else break
                    } else break
                }
                var payload = bodyBytes.copyOfRange(pos, pos + fsize)
                pos += fsize

                if (major >= 4) {
                    val frameUnsync = (frameFlags and 0x0002) != 0
                    val hasDataLenIndicator = (frameFlags and 0x0001) != 0
                    if (hasDataLenIndicator && payload.size >= 4) {
                        payload = payload.copyOfRange(4, payload.size)
                    }
                    if (frameUnsync || unsync) {
                        payload = removeUnsynchronization(payload, 0, payload.size)
                    }
                }

                when (fid) {
                    "TIT2" -> if (title.isBlank()) title = decodeId3TextFrame(payload)
                    "TPE1" -> if (artist.isBlank()) artist = decodeId3TextFrame(payload, joinMulti = true)
                    "TPE2" -> if (albumArtist.isBlank()) albumArtist = decodeId3TextFrame(payload, joinMulti = true)
                    "TALB" -> if (album.isBlank()) album = decodeId3TextFrame(payload)
                    "TDRC", "TYER" -> if (year.isBlank()) year = decodeId3TextFrame(payload)
                    "USLT" -> {
                        val text = decodeId3UsltFrame(payload)
                        if (text.isNotBlank() && (lyric.isBlank() || (text.contains("[0") && !lyric.contains("[0")))) {
                            lyric = text
                        }
                    }
                    "SYLT" -> {
                        val text = decodeId3SyltFrame(payload)
                        if (text.isNotBlank()) lyric = text
                    }
                    "TXXX" -> {
                        val (desc, value) = decodeId3TxxxFrame(payload)
                        val key = desc.uppercase(Locale.ROOT)
                        if (key in setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS", "LYRIC") && value.isNotBlank()) {
                            if (lyric.isBlank() || (value.contains("[0") && !lyric.contains("[0"))) {
                                lyric = value
                            }
                        }
                    }
                    "APIC" -> {
                        if (!coverIsFront && payload.size <= MAX_COVER_BYTES) {
                            val (pt, img) = decodeId3ApicFrame(payload)
                            if (img != null && img.isNotEmpty() && (pt == 3 || coverBytes == null)) {
                                coverBytes = img
                                if (pt == 3) coverIsFront = true
                            }
                        }
                    }
                }
            }
        }

        return Id3Parsed(
            title = title,
            artist = artist.ifBlank { albumArtist },
            album = album,
            year = year,
            lyric = lyric,
            coverBytes = coverBytes
        )
    }

    private fun decodeId3String(encoding: Int, bytes: ByteArray, offset: Int, length: Int): String {
        if (length <= 0 || offset >= bytes.size) return ""
        val actualLen = min(length, bytes.size - offset)
        val slice = bytes.copyOfRange(offset, offset + actualLen)
        return when (encoding) {
            0 -> decodeSmartTextBytes(slice) // 中文曲库中 encoding=0 经常实为 GBK/UTF-8，智能解码零乱码
            1 -> decodeUtf16WithBom(slice)
            2 -> String(slice, Charsets.UTF_16BE)
            3 -> String(slice, Charsets.UTF_8)
            else -> decodeSmartTextBytes(slice)
        }.trim('\u0000', ' ', '\r', '\n')
    }

    private fun decodeUtf16WithBom(slice: ByteArray): String {
        if (slice.size >= 2 && slice[0] == 0xFF.toByte() && slice[1] == 0xFE.toByte()) {
            return String(slice, 2, slice.size - 2, Charsets.UTF_16LE)
        }
        if (slice.size >= 2 && slice[0] == 0xFE.toByte() && slice[1] == 0xFF.toByte()) {
            return String(slice, 2, slice.size - 2, Charsets.UTF_16BE)
        }
        return String(slice, Charsets.UTF_16)
    }

    private fun decodeId3TextFrame(payload: ByteArray, joinMulti: Boolean = false): String {
        if (payload.size <= 1) return ""
        val enc = payload[0].toInt() and 0xFF
        val raw = when (enc) {
            0 -> decodeSmartTextBytes(payload.copyOfRange(1, payload.size))
            1 -> decodeUtf16WithBom(payload.copyOfRange(1, payload.size))
            2 -> String(payload, 1, payload.size - 1, Charsets.UTF_16BE)
            3 -> String(payload, 1, payload.size - 1, Charsets.UTF_8)
            else -> decodeSmartTextBytes(payload.copyOfRange(1, payload.size))
        }
        return if (joinMulti && raw.contains('\u0000')) {
            raw.split('\u0000').map { it.trim() }.filter { it.isNotBlank() }.joinToString(" / ")
        } else {
            raw.substringBefore('\u0000').trim()
        }
    }

    private fun decodeId3UsltFrame(payload: ByteArray): String {
        if (payload.size <= 5) return ""
        val enc = payload[0].toInt() and 0xFF
        // 1..3: language (3 bytes), 4..: description + NUL + lyric
        val termEnd = findTerminatorEnd(payload, 4, isDoubleByte = (enc == 1 || enc == 2))
        if (termEnd >= payload.size) return ""
        return decodeId3String(enc, payload, termEnd, payload.size - termEnd)
    }

    private fun decodeId3SyltFrame(payload: ByteArray): String {
        if (payload.size <= 7) return ""
        val enc = payload[0].toInt() and 0xFF
        val timestampFormat = payload[4].toInt() and 0xFF // 2 = milliseconds
        if (timestampFormat != 2) return ""
        var pos = findTerminatorEnd(payload, 6, isDoubleByte = (enc == 1 || enc == 2))
        val isTwoByte = (enc == 1 || enc == 2)
        val sb = StringBuilder()
        while (pos < payload.size) {
            val nextTerm = findTerminatorStart(payload, pos, isTwoByte)
            val text = decodeId3String(enc, payload, pos, nextTerm - pos).trim()
            val afterTerm = nextTerm + if (isTwoByte) 2 else 1
            if (afterTerm + 4 > payload.size) break
            val ms = readUInt32BE(payload, afterTerm)
            pos = afterTerm + 4
            if (text.isNotBlank()) {
                val mins = ms / 60_000L
                val secs = (ms % 60_000L) / 1000L
                val centis = (ms % 1000L) / 10L
                sb.append(String.format(Locale.US, "[%02d:%02d.%02d]%s\n", mins, secs, centis, text))
            }
        }
        return sb.toString().trim()
    }

    private fun decodeId3TxxxFrame(payload: ByteArray): Pair<String, String> {
        if (payload.size <= 2) return "" to ""
        val enc = payload[0].toInt() and 0xFF
        val isTwoByte = (enc == 1 || enc == 2)
        val termStart = findTerminatorStart(payload, 1, isTwoByte)
        val desc = decodeId3String(enc, payload, 1, termStart - 1)
        val valueStart = termStart + if (isTwoByte) 2 else 1
        val value = if (valueStart < payload.size) {
            decodeId3String(enc, payload, valueStart, payload.size - valueStart)
        } else ""
        return desc to value
    }

    private fun decodeId3ApicFrame(payload: ByteArray): Pair<Int, ByteArray?> {
        if (payload.size < 8) return 0 to null
        val enc = payload[0].toInt() and 0xFF
        val mimeEnd = findTerminatorEnd(payload, 1, isDoubleByte = false)
        if (mimeEnd >= payload.size) return 0 to null
        val picType = payload[mimeEnd].toInt() and 0xFF
        val descEnd = findTerminatorEnd(payload, mimeEnd + 1, isDoubleByte = (enc == 1 || enc == 2))
        if (descEnd >= payload.size) return picType to null
        return picType to payload.copyOfRange(descEnd, payload.size)
    }

    private fun decodeId3PicV22(payload: ByteArray): Pair<Int, ByteArray?> {
        if (payload.size < 7) return 0 to null
        val enc = payload[0].toInt() and 0xFF
        // 1..3: image format (3 bytes), 4: picType
        val picType = payload[4].toInt() and 0xFF
        val descEnd = findTerminatorEnd(payload, 5, isDoubleByte = (enc == 1 || enc == 2))
        if (descEnd >= payload.size) return picType to null
        return picType to payload.copyOfRange(descEnd, payload.size)
    }

    private data class MpegInfo(
        val sampleRateHz: Int = 0,
        val bitrateKbps: Int = 0,
        val durationMs: Long = 0L
    )

    private fun parseFirstMpegFrame(buf: ByteArray, len: Int, audioBytes: Long): MpegInfo {
        val srV1 = intArrayOf(44100, 48000, 32000, 0)
        val srV2 = intArrayOf(22050, 24000, 16000, 0)
        val srV25 = intArrayOf(11025, 12000, 8000, 0)
        val brV1L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        val brV2L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

        var i = 0
        while (i + 4 <= len) {
            val b0 = buf[i].toInt() and 0xFF
            val b1 = buf[i + 1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                val versionBits = (b1 ushr 3) and 0x03
                val layerBits = (b1 ushr 1) and 0x03
                val b2 = buf[i + 2].toInt() and 0xFF
                val bitrateIdx = (b2 ushr 4) and 0x0F
                val srIdx = (b2 ushr 2) and 0x03
                val channelMode = (buf[i + 3].toInt() ushr 6) and 0x03

                if (versionBits != 1 && layerBits == 1 && bitrateIdx in 1..14 && srIdx in 0..2) {
                    val sr = when (versionBits) {
                        3 -> srV1[srIdx]
                        2 -> srV2[srIdx]
                        0 -> srV25[srIdx]
                        else -> 0
                    }
                    val br = if (versionBits == 3) brV1L3[bitrateIdx] else brV2L3[bitrateIdx]
                    val samplesPerFrame = if (versionBits == 3) 1152 else 576
                    if (sr > 0 && br > 0) {
                        // 检查 Xing / Info VBR 头以精确计算 VBR 时长
                        val sideInfoSize = when {
                            versionBits == 3 && channelMode != 3 -> 32
                            versionBits == 3 && channelMode == 3 -> 17
                            channelMode != 3 -> 17
                            else -> 9
                        }
                        val xingOffset = i + 4 + sideInfoSize
                        var durationMs = 0L
                        var effectiveBr = br
                        if (xingOffset + 12 <= len &&
                            (matchAscii(buf, xingOffset, "Xing") || matchAscii(buf, xingOffset, "Info"))
                        ) {
                            val xingFlags = readInt32BE(buf, xingOffset + 4)
                            if ((xingFlags and 0x01) != 0) {
                                val frames = readUInt32BE(buf, xingOffset + 8)
                                if (frames > 0L) {
                                    durationMs = (frames * samplesPerFrame.toLong() * 1000L) / sr.toLong()
                                    if (durationMs > 1000L && audioBytes > 0L) {
                                        effectiveBr = ((audioBytes * 8L) / durationMs).toInt().coerceIn(32, 320)
                                    }
                                }
                            }
                        }
                        if (durationMs <= 0L && audioBytes > 0L) {
                            durationMs = (audioBytes * 8L) / br.toLong()
                        }
                        return MpegInfo(sampleRateHz = sr, bitrateKbps = effectiveBr, durationMs = durationMs)
                    }
                }
            }
            i++
        }
        return MpegInfo()
    }

    // =========================================================================
    // 4. M4A / MP4 / ALAC / AAC (ISO BMFF Atom 解析器)
    // =========================================================================

    private class Mp4Collector {
        var title: String = ""
        var artist: String = ""
        var albumArtist: String = ""
        var album: String = ""
        var year: String = ""
        var lyric: String = ""
        var coverBytes: ByteArray? = null
        var format: String = "AAC"
        var sampleRateHz: Int = 0
        var bitDepth: Int = 0
        var durationMs: Long = 0L
    }

    private fun parseMp4Stream(
        stream: InputStream,
        fileSizeBytes: Long,
        ext: String
    ): ParsedLocalAudioMetadata {
        val collector = Mp4Collector()
        if (ext == "alac") collector.format = "ALAC"

        val boxHeader = ByteArray(8)
        var guard = 0
        while (guard++ < 128) {
            if (readUpTo(stream, boxHeader, 0, 8) < 8) break
            var size = readUInt32BE(boxHeader, 0)
            val type = String(boxHeader, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8L
            if (size == 1L) {
                val extSizeBuf = ByteArray(8)
                if (readUpTo(stream, extSizeBuf, 0, 8) < 8) break
                size = readInt64BE(extSizeBuf, 0)
                headerSize = 16L
            } else if (size == 0L) {
                break
            }
            val payloadSize = size - headerSize
            if (payloadSize < 0L) break

            if (type == "moov" && payloadSize <= MAX_MP4_CONTAINER_BYTES.toLong()) {
                val moovBytes = readExactBytes(stream, payloadSize.toInt()) ?: break
                walkMp4Atoms(moovBytes, 0, moovBytes.size, collector, depth = 0)
                break // moov 包含全部轨道参数与元数据，解析完立即停止，跳过庞大 mdat
            } else {
                skipFully(stream, payloadSize)
            }
        }

        val bitrateKbps = if (collector.durationMs > 1000L && fileSizeBytes > 0L) {
            ((fileSizeBytes * 8L) / collector.durationMs).toInt()
        } else 0

        return ParsedLocalAudioMetadata(
            title = collector.title,
            artist = collector.artist.ifBlank { collector.albumArtist },
            album = collector.album,
            year = collector.year,
            format = collector.format,
            sampleRateHz = collector.sampleRateHz,
            bitDepth = collector.bitDepth,
            bitrateKbps = bitrateKbps,
            durationMs = collector.durationMs,
            embeddedLyric = collector.lyric,
            coverBytes = collector.coverBytes
        )
    }

    private fun walkMp4Atoms(
        buf: ByteArray,
        start: Int,
        end: Int,
        out: Mp4Collector,
        depth: Int
    ) {
        if (depth > 12) return
        var pos = start
        while (pos + 8 <= end) {
            var size = readUInt32BE(buf, pos)
            val type = String(buf, pos + 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8
            if (size == 1L && pos + 16 <= end) {
                size = readInt64BE(buf, pos + 8)
                headerLen = 16
            } else if (size == 0L) {
                size = (end - pos).toLong()
            }
            if (size < headerLen.toLong()) break
            val atomEnd = min(end, pos + size.toInt())
            val contentStart = pos + headerLen

            when (type) {
                "trak", "mdia", "minf", "stbl", "udta" -> {
                    walkMp4Atoms(buf, contentStart, atomEnd, out, depth + 1)
                }
                "meta" -> {
                    // MP4 meta box has 4-byte fullbox version/flags before child atoms
                    if (contentStart + 4 <= atomEnd) {
                        walkMp4Atoms(buf, contentStart + 4, atomEnd, out, depth + 1)
                    }
                }
                "mvhd" -> {
                    parseMp4Mvhd(buf, contentStart, atomEnd, out)
                }
                "stsd" -> {
                    parseMp4Stsd(buf, contentStart, atomEnd, out)
                }
                "ilst" -> {
                    parseMp4Ilst(buf, contentStart, atomEnd, out)
                }
            }
            pos = atomEnd
        }
    }

    private fun parseMp4Mvhd(buf: ByteArray, start: Int, end: Int, out: Mp4Collector) {
        if (start + 20 > end) return
        val version = buf[start].toInt() and 0xFF
        val (timescale, duration) = if (version == 1 && start + 32 <= end) {
            val ts = readUInt32BE(buf, start + 20)
            val dur = readInt64BE(buf, start + 24)
            ts to dur
        } else if (start + 20 <= end) {
            val ts = readUInt32BE(buf, start + 12)
            val dur = readUInt32BE(buf, start + 16)
            ts to dur
        } else 0L to 0L
        if (timescale > 0L && duration > 0L) {
            out.durationMs = (duration * 1000L) / timescale
        }
    }

    private fun parseMp4Stsd(buf: ByteArray, start: Int, end: Int, out: Mp4Collector) {
        if (start + 16 > end) return
        // 4B version/flags + 4B entryCount
        var pos = start + 8
        val entryCount = readUInt32BE(buf, start + 4).coerceAtMost(8L).toInt()
        for (i in 0 until entryCount) {
            if (pos + 8 > end) break
            val entrySize = readUInt32BE(buf, pos).toInt()
            val codec = String(buf, pos + 4, 4, Charsets.ISO_8859_1)
            val entryEnd = min(end, pos + entrySize)
            if (entrySize < 8) break
            when (codec) {
                "alac" -> {
                    out.format = "ALAC"
                    // AudioSampleEntry: offset 24..27 = sampleSize (16b) at +26? Actually offset 18 = sampleSize(16b), offset 24 = sampleRate(16.16)
                    if (pos + 36 <= entryEnd) {
                        val headerSampleSize = readUInt16BE(buf, pos + 26)
                        val headerSr = readUInt16BE(buf, pos + 32)
                        if (headerSr > 0) out.sampleRateHz = headerSr
                        if (headerSampleSize > 0) out.bitDepth = headerSampleSize
                    }
                    // Parse nested "alac" magic cookie atom inside sample entry (offset +36)
                    var subPos = pos + 36
                    while (subPos + 8 <= entryEnd) {
                        val subSize = readUInt32BE(buf, subPos).toInt()
                        val subType = String(buf, subPos + 4, 4, Charsets.ISO_8859_1)
                        if (subSize < 8) break
                        if (subType == "alac" && subPos + 36 <= entryEnd) {
                            // 4B size + 4B 'alac' + 4B version/flags + 24B ALACSpecificConfig:
                            // config[5] = bitDepth, config[20..23] = sampleRate (32-bit BE)
                            val cfgStart = subPos + 12
                            val cfgBitDepth = buf[cfgStart + 5].toInt() and 0xFF
                            val cfgSampleRate = readUInt32BE(buf, cfgStart + 20).toInt()
                            if (cfgBitDepth in 8..32) out.bitDepth = cfgBitDepth
                            if (cfgSampleRate in 8_000..768_000) out.sampleRateHz = cfgSampleRate
                        }
                        subPos += subSize
                    }
                }
                "mp4a" -> {
                    if (out.format != "ALAC") out.format = "AAC"
                    if (pos + 36 <= entryEnd) {
                        val headerSr = readUInt16BE(buf, pos + 32)
                        if (headerSr > 0 && out.sampleRateHz == 0) out.sampleRateHz = headerSr
                    }
                }
                "fLaC" -> {
                    out.format = "FLAC"
                    if (pos + 36 <= entryEnd) {
                        val headerSr = readUInt16BE(buf, pos + 32)
                        if (headerSr > 0 && out.sampleRateHz == 0) out.sampleRateHz = headerSr
                    }
                }
            }
            pos = entryEnd
        }
    }

    private fun parseMp4Ilst(buf: ByteArray, start: Int, end: Int, out: Mp4Collector) {
        var pos = start
        while (pos + 8 <= end) {
            val itemSize = readUInt32BE(buf, pos).toInt()
            if (itemSize < 8) break
            val itemEnd = min(end, pos + itemSize)
            val b0 = buf[pos + 4].toInt() and 0xFF
            val type3 = String(buf, pos + 5, 3, Charsets.ISO_8859_1)
            val fullType = String(buf, pos + 4, 4, Charsets.ISO_8859_1)

            // Find child 'data' atom
            val dataBytes = findMp4ChildDataAtom(buf, pos + 8, itemEnd)
            if (dataBytes != null && dataBytes.isNotEmpty()) {
                when {
                    b0 == 0xA9 && type3 == "nam" -> if (out.title.isBlank()) out.title = decodeSmartTextBytes(dataBytes).trim()
                    b0 == 0xA9 && type3 == "ART" -> if (out.artist.isBlank()) out.artist = decodeSmartTextBytes(dataBytes).trim()
                    fullType == "aART" -> if (out.albumArtist.isBlank()) out.albumArtist = decodeSmartTextBytes(dataBytes).trim()
                    b0 == 0xA9 && type3 == "alb" -> if (out.album.isBlank()) out.album = decodeSmartTextBytes(dataBytes).trim()
                    b0 == 0xA9 && type3 == "day" -> if (out.year.isBlank()) out.year = decodeSmartTextBytes(dataBytes).trim()
                    b0 == 0xA9 && type3 == "lyr" -> if (out.lyric.isBlank()) out.lyric = decodeSmartTextBytes(dataBytes).trim()
                    fullType == "covr" -> if (out.coverBytes == null && dataBytes.size <= MAX_COVER_BYTES) out.coverBytes = dataBytes
                }
            }
            pos = itemEnd
        }
    }

    private fun findMp4ChildDataAtom(buf: ByteArray, start: Int, end: Int): ByteArray? {
        var pos = start
        while (pos + 8 <= end) {
            val size = readUInt32BE(buf, pos).toInt()
            val type = String(buf, pos + 4, 4, Charsets.ISO_8859_1)
            if (size < 8) break
            val childEnd = min(end, pos + size)
            if (type == "data" && pos + 16 <= childEnd) {
                return buf.copyOfRange(pos + 16, childEnd)
            }
            pos = childEnd
        }
        return null
    }

    // =========================================================================
    // 5. 裸 AAC (ADTS) & OGG (Vorbis / Opus) 解析器
    // =========================================================================

    private fun parseAdtsAacStream(stream: InputStream, fileSizeBytes: Long): ParsedLocalAudioMetadata {
        val srTable = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        val buf = ByteArray(16 * 1024)
        val n = readUpTo(stream, buf, 0, buf.size)
        var sr = 0
        for (i in 0 until n - 6) {
            val b0 = buf[i].toInt() and 0xFF
            val b1 = buf[i + 1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xF0) == 0xF0) {
                val srIdx = (buf[i + 2].toInt() ushr 2) and 0x0F
                if (srIdx in srTable.indices) {
                    sr = srTable[srIdx]
                    break
                }
            }
        }
        return ParsedLocalAudioMetadata(format = "AAC", sampleRateHz = sr)
    }

    private fun parseOggStream(stream: InputStream, fileSizeBytes: Long): ParsedLocalAudioMetadata {
        val buf = ByteArray(256 * 1024)
        val n = readUpTo(stream, buf, 0, buf.size)
        if (n < 32) return ParsedLocalAudioMetadata(format = "OGG")

        // 拼接前若干个 Ogg 页面负载，查找 Vorbis / Opus 头和评论块
        val packets = ByteArrayOutputStream()
        var pos = 0
        var pageCount = 0
        while (pos + 27 <= n && pageCount++ < 16) {
            if (!matchAscii(buf, pos, "OggS")) break
            val segCount = buf[pos + 26].toInt() and 0xFF
            val segTableStart = pos + 27
            if (segTableStart + segCount > n) break
            var bodyLen = 0
            for (s in 0 until segCount) {
                bodyLen += buf[segTableStart + s].toInt() and 0xFF
            }
            val bodyStart = segTableStart + segCount
            if (bodyStart + bodyLen > n) break
            packets.write(buf, bodyStart, bodyLen)
            pos = bodyStart + bodyLen
        }

        val combined = packets.toByteArray()
        var sampleRate = 0
        var format = "OGG"
        var comments = VorbisParsed()

        val vorbisIdIdx = indexOfBytes(combined, byteArrayOf(1, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte()))
        if (vorbisIdIdx >= 0 && vorbisIdIdx + 16 <= combined.size) {
            sampleRate = readInt32LE(combined, vorbisIdIdx + 12)
        }
        val opusHeadIdx = indexOfBytes(combined, "OpusHead".toByteArray(Charsets.ISO_8859_1))
        if (opusHeadIdx >= 0) {
            format = "OPUS"
            sampleRate = 48000
        }

        val vorbisCommentIdx = indexOfBytes(combined, byteArrayOf(3, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte()))
        val opusTagsIdx = indexOfBytes(combined, "OpusTags".toByteArray(Charsets.ISO_8859_1))
        when {
            vorbisCommentIdx >= 0 && vorbisCommentIdx + 15 <= combined.size -> {
                comments = parseVorbisCommentBytes(combined.copyOfRange(vorbisCommentIdx + 7, combined.size))
            }
            opusTagsIdx >= 0 && opusTagsIdx + 16 <= combined.size -> {
                comments = parseVorbisCommentBytes(combined.copyOfRange(opusTagsIdx + 8, combined.size))
            }
        }

        return ParsedLocalAudioMetadata(
            title = comments.title,
            artist = comments.artists.distinct().joinToString(" / ").ifBlank { comments.albumArtist },
            album = comments.album,
            year = comments.year,
            format = format,
            sampleRateHz = sampleRate,
            embeddedLyric = comments.lyric,
            coverBytes = comments.pictureBytes
        )
    }

    // =========================================================================
    // 底层字节与流辅助工具
    // =========================================================================

    private fun readUpTo(stream: InputStream, dst: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = stream.read(dst, offset + total, length - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    private fun readExactBytes(stream: InputStream, length: Int): ByteArray? {
        if (length < 0) return null
        val out = ByteArray(length)
        val got = readUpTo(stream, out, 0, length)
        return if (got == length) out else null
    }

    private fun skipFully(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val scratch = ByteArray(8192)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val r = stream.read(scratch, 0, min(scratch.size.toLong(), remaining).toInt())
                if (r <= 0) break
                remaining -= r.toLong()
            }
        }
    }

    private fun matchAscii(buf: ByteArray, offset: Int, text: String): Boolean {
        if (offset < 0 || offset + text.length > buf.size) return false
        for (i in text.indices) {
            if (buf[offset + i].toInt().toChar() != text[i]) return false
        }
        return true
    }

    private fun decodeSynchsafe32(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0x7F) shl 21) or
            ((buf[offset + 1].toInt() and 0x7F) shl 14) or
            ((buf[offset + 2].toInt() and 0x7F) shl 7) or
            (buf[offset + 3].toInt() and 0x7F)
    }

    private fun removeUnsynchronization(src: ByteArray, start: Int, end: Int): ByteArray {
        val out = ByteArrayOutputStream(end - start)
        var i = start
        while (i < end) {
            val b = src[i]
            out.write(b.toInt() and 0xFF)
            if (b == 0xFF.toByte() && i + 1 < end && src[i + 1] == 0x00.toByte()) {
                i += 2
            } else {
                i++
            }
        }
        return out.toByteArray()
    }

    private fun findTerminatorStart(buf: ByteArray, start: Int, isDoubleByte: Boolean): Int {
        if (!isDoubleByte) {
            for (i in start until buf.size) {
                if (buf[i] == 0.toByte()) return i
            }
            return buf.size
        }
        var i = start
        while (i + 1 < buf.size) {
            if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte()) return i
            i += 2
        }
        return buf.size
    }

    private fun findTerminatorEnd(buf: ByteArray, start: Int, isDoubleByte: Boolean): Int {
        val termStart = findTerminatorStart(buf, start, isDoubleByte)
        return min(buf.size, termStart + if (isDoubleByte) 2 else 1)
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun readUInt16LE(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun readUInt16BE(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun readInt32LE(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun readUInt32LE(b: ByteArray, o: Int): Long = readInt32LE(b, o).toLong() and 0xFFFFFFFFL

    private fun readInt32BE(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    private fun readUInt32BE(b: ByteArray, o: Int): Long = readInt32BE(b, o).toLong() and 0xFFFFFFFFL

    private fun readInt64BE(b: ByteArray, o: Int): Long {
        var r = 0L
        for (i in 0 until 8) {
            r = (r shl 8) or (b[o + i].toLong() and 0xFFL)
        }
        return r
    }
}
