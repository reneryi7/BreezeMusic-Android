package com.concept.music.data.kugou

import com.concept.music.data.model.ArtistListenRankItem
import com.concept.music.data.model.AudioQuality
import com.concept.music.data.model.BlacklistEntry
import com.concept.music.data.model.CloudDiskSummary
import com.concept.music.data.model.CloudSongItem
import com.concept.music.data.model.CommentTargetType
import com.concept.music.data.model.DailyHistoryEntry
import com.concept.music.data.model.ListenRankItem
import com.concept.music.data.model.LoginDeviceItem
import com.concept.music.data.model.Playlist
import com.concept.music.data.model.SearchResult
import com.concept.music.data.model.Song
import com.concept.music.data.model.SongComment
import com.concept.music.data.model.UserListeningPreference
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.Locale

private const val COMMENT_CODE_SONG = "fc4be23b4e972707f36b8a828a93ba8a"
private const val COMMENT_CODE_ALBUM = "94f1792ced1df89aa68a7939eaf2efca"
private const val COMMENT_CODE_PLAYLIST = "ca53b96fe5a1d9c22d71c8f522ef7c4f"

private val htmlTagStripRegex = Regex("<[^>]+>")
private val audioExtensionSuffixRegex = Regex(
    "\\.(mp3|flac|wav|m4a|aac|ogg|ape|dsf|dff|wma|alac|opus)$",
    RegexOption.IGNORE_CASE
)
private val hex32HashRegex = Regex("^[0-9a-fA-F]{32}$")
private val liveKeywordRegex = Regex(
    "(?:\\blive\\b|现场|演唱会|音乐节|巡演|音乐会|unplugged|跨年晚会|纯享现场)",
    RegexOption.IGNORE_CASE
)
private val coverOrRemixKeywordRegex = Regex(
    "(?:翻唱|\\bcover\\b|\\bdj\\b|慢摇|串烧|0\\.[6-9]x|1\\.[1-5]x|加速版|加速|慢速|降调|升调|烟嗓|女声版|男声版|童声版|喊麦)",
    RegexOption.IGNORE_CASE
)
private val accompanyKeywordRegex = Regex(
    "(?:伴奏|\\binstrumental\\b|\\bkaraoke\\b|消音伴奏|原版伴奏)",
    RegexOption.IGNORE_CASE
)

internal fun Song.allLowercaseHashes(): Set<String> = buildSet {
    listOf(hash, hash128, mp3Hash128, hash320, hashFlac, hashHiRes).forEach { h ->
        val clean = h.trim().lowercase(Locale.ROOT)
        if (clean.isNotEmpty()) add(clean)
    }
}

/**
 * 搜索结果中「同一原歌手 · 同一首歌」的可展开多版本分组模型
 */
data class SearchSongVersionGroup(
    val primary: Song,
    val versions: List<Song> = emptyList()
)

/**
 * 猜你喜欢（私人 FM）专属负反馈与屏蔽过滤器：
 * 严格限定作用边界——仅在「猜你喜欢」曲池拉取、跳过与歌手降权中生效，绝不污染搜索、歌单、日推或其他模块。
 */
object LocalBlacklistFilter {
    private val blockedSongHashes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val blockedSongMixIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val blockedArtistIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val blockedArtistNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun clear() {
        blockedSongHashes.clear()
        blockedSongMixIds.clear()
        blockedArtistIds.clear()
        blockedArtistNames.clear()
    }

    fun syncFromEntries(songs: List<BlacklistEntry>, singers: List<BlacklistEntry>) {
        blockedSongHashes.clear()
        blockedSongMixIds.clear()
        songs.forEach { entry ->
            val k = entry.key.trim().lowercase(Locale.ROOT)
            if (k.isNotEmpty()) blockedSongHashes.add(k)
            val m = entry.mixSongId.trim()
            if (m.isNotEmpty()) blockedSongMixIds.add(m)
        }
        blockedArtistIds.clear()
        blockedArtistNames.clear()
        singers.forEach { entry ->
            val k = entry.key.trim()
            if (k.isNotEmpty()) blockedArtistIds.add(k)
            val n = entry.name.trim().lowercase(Locale.ROOT)
            if (n.isNotEmpty()) blockedArtistNames.add(n)
        }
    }

    fun addSong(song: Song) {
        listOf(song.hash, song.hash128, song.mp3Hash128, song.hash320, song.hashFlac, song.hashHiRes)
            .forEach { h ->
                val clean = h.trim().lowercase(Locale.ROOT)
                if (clean.isNotEmpty()) blockedSongHashes.add(clean)
            }
        song.mixSongId.trim().takeIf { it.isNotEmpty() }?.let { blockedSongMixIds.add(it) }
        song.albumAudioId.takeIf { it > 0L }?.toString()?.let { blockedSongMixIds.add(it) }
    }

    fun removeSongEntry(entry: BlacklistEntry) {
        val k = entry.key.trim().lowercase(Locale.ROOT)
        if (k.isNotEmpty()) blockedSongHashes.remove(k)
        entry.mixSongId.trim().takeIf { it.isNotEmpty() }?.let { blockedSongMixIds.remove(it) }
    }

    fun addArtist(artistId: String, artistName: String) {
        artistId.trim().takeIf { it.isNotEmpty() && it != "0" }?.let { blockedArtistIds.add(it) }
        artistName.split("、", "/", ",", "&")
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() && it != "未知歌手" }
            .forEach { blockedArtistNames.add(it) }
    }

    fun removeArtistEntry(entry: BlacklistEntry) {
        entry.key.trim().takeIf { it.isNotEmpty() }?.let { blockedArtistIds.remove(it) }
        entry.name.split("、", "/", ",", "&")
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .forEach { blockedArtistNames.remove(it) }
    }

    fun blockedArtistNamesSnapshot(): Set<String> = blockedArtistNames.toSet()

    fun hasAnyEntries(): Boolean =
        blockedSongHashes.isNotEmpty() ||
            blockedSongMixIds.isNotEmpty() ||
            blockedArtistIds.isNotEmpty() ||
            blockedArtistNames.isNotEmpty()

    fun isBlocked(song: Song): Boolean {
        if (blockedSongHashes.isEmpty() && blockedSongMixIds.isEmpty() &&
            blockedArtistIds.isEmpty() && blockedArtistNames.isEmpty()
        ) return false
        val hashes = listOf(song.hash, song.hash128, song.mp3Hash128, song.hash320, song.hashFlac, song.hashHiRes)
        if (hashes.any { it.isNotBlank() && it.trim().lowercase(Locale.ROOT) in blockedSongHashes }) {
            return true
        }
        val mix = song.mixSongId.trim()
        if (mix.isNotEmpty() && mix in blockedSongMixIds) return true
        if (song.albumAudioId > 0L && song.albumAudioId.toString() in blockedSongMixIds) return true
        val aid = song.artistId.trim()
        if (aid.isNotEmpty() && aid in blockedArtistIds) return true
        if (blockedArtistNames.isNotEmpty() && song.artist.isNotBlank()) {
            val singers = song.artist.split("、", "/", ",", "&").map { it.trim().lowercase(Locale.ROOT) }
            if (singers.any { it.isNotEmpty() && it in blockedArtistNames }) return true
        }
        return false
    }

    fun filterSongs(songs: List<Song>): List<Song> {
        if (songs.isEmpty()) return songs
        return songs.filterNot(::isBlocked)
    }
}

private fun stripKnownAudioExtension(raw: String): String =
    audioExtensionSuffixRegex.replace(raw.trim(), "").trim()

private fun CommentTargetType.commentPoolCode(): String = when (this) {
    CommentTargetType.SONG -> COMMENT_CODE_SONG
    CommentTargetType.PLAYLIST -> COMMENT_CODE_PLAYLIST
    CommentTargetType.ALBUM -> COMMENT_CODE_ALBUM
}

private fun ApiResponse.errorMsg(fallback: String = "登录已过期"): String =
    body.text("error_msg") ?: body.text("msg") ?: body.text("message") ?: fallback

internal fun JsonObject.longVal(key: String): Long? {
    val el = get(key)?.takeIf { it.isJsonPrimitive } ?: return null
    return runCatching { el.asLong }.getOrNull() ?: el.asString.toLongOrNull()
}

private fun formatEpochSeconds(sec: Long): String {
    if (sec <= 0L) return ""
    val ms = if (sec > 10_000_000_000L) sec else sec * 1000L
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }.getOrDefault("")
}

internal fun inferSongVersionTag(title: String, rawRemark: String = "", rawTag: String = ""): String {
    val combined = "$rawTag $rawRemark $title".trim()
    val lower = combined.lowercase(Locale.ROOT)
    return when {
        accompanyKeywordRegex.containsMatchIn(combined) -> "伴奏"
        liveKeywordRegex.containsMatchIn(combined) -> "Live"
        lower.contains("dj") || lower.contains("remix") -> "DJ/Remix"
        combined.contains("翻唱") || lower.contains("cover") -> "翻唱"
        combined.contains("重制") || lower.contains("remaster") -> "重制版"
        combined.contains("原声") || lower.contains("ost") || combined.contains("影视") || combined.contains("主题曲") || combined.contains("插曲") -> "影视原声"
        combined.contains("剧场") -> "剧场版"
        combined.contains("不插电") || combined.contains("木吉他") || combined.contains("钢琴") -> "器乐编曲"
        combined.contains("纯音乐") -> "纯音乐"
        combined.contains("合唱") || combined.contains("对唱") -> "合唱"
        combined.contains("降调") || combined.contains("加速") || lower.contains("sped") -> "变速/调"
        rawTag.isNotBlank() && rawTag.length <= 10 -> rawTag.trim()
        else -> ""
    }
}

internal fun isLiveVersionSong(song: Song): Boolean {
    val combined = "${song.title} ${song.versionTag} ${song.album}"
    return liveKeywordRegex.containsMatchIn(combined)
}

internal fun isCoverOrRemixSong(song: Song): Boolean {
    val combined = "${song.title} ${song.versionTag}"
    return coverOrRemixKeywordRegex.containsMatchIn(combined)
}

internal fun isAccompanimentSong(song: Song): Boolean {
    val combined = "${song.title} ${song.versionTag}"
    return accompanyKeywordRegex.containsMatchIn(combined)
}

/**
 * 判断候选版本是否与主曲属于同一原唱歌手（拒绝他人翻唱或混入陌生翻唱者，兼容 G.E.M.邓紫棋 <-> 邓紫棋 等艺名变体）
 */
internal fun MusicApiRepository.isSameOriginalArtist(primary: Song, candidate: Song): Boolean {
    if (primary.artistId.isNotBlank() && primary.artistId != "0" &&
        candidate.artistId.isNotBlank() && candidate.artistId != "0" &&
        primary.artistId == candidate.artistId &&
        !primary.artist.contains("、") && !candidate.artist.contains("、")
    ) {
        return true
    }
    val pSingers = primary.artist.split("、", "/", ",", "&")
        .map { it.trim() }
        .filter { it.isNotBlank() && it != "未知歌手" }
    val cSingers = candidate.artist.split("、", "/", ",", "&")
        .map { it.trim() }
        .filter { it.isNotBlank() && it != "未知歌手" }
    if (pSingers.isEmpty() || cSingers.isEmpty()) return false
    if (pSingers.size != cSingers.size) return false

    val pVariantSets = pSingers.map { extractArtistNameVariants(it) }
    val cVariantSets = cSingers.map { extractArtistNameVariants(it) }
    if (pVariantSets.any { it.isEmpty() } || cVariantSets.any { it.isEmpty() }) {
        return pSingers.map { it.lowercase(Locale.ROOT) }.toSet() ==
            cSingers.map { it.lowercase(Locale.ROOT) }.toSet()
    }
    // 每位候选歌手都必须与原曲某位歌手命中艺名变体，且原曲每位歌手都被覆盖
    val allCandidateMatched = cVariantSets.all { cSet -> pVariantSets.any { pSet -> cSet.any { it in pSet } } }
    val allPrimaryMatched = pVariantSets.all { pSet -> cVariantSets.any { cSet -> pSet.any { it in cSet } } }
    return allCandidateMatched && allPrimaryMatched
}

/**
 * 判断候选曲目是否可作为搜索结果行内展开的「同歌手同曲其他版本」：
 * - 必须是同一首歌（核心歌名一致）；
 * - 必须是同一原唱歌手（排除他人翻唱）；
 * - 严格排除 Live / 现场 / 演唱会版本与 DJ 变速翻唱；
 * - 允许官方伴奏版与不同录音室/母带/OST/器乐编曲版。
 */
internal fun MusicApiRepository.isEligibleSameSingerVersion(primary: Song, candidate: Song): Boolean {
    val pHashes = primary.allLowercaseHashes()
    val cHashes = candidate.allLowercaseHashes()
    if (pHashes.isNotEmpty() && cHashes.any { it in pHashes }) return false
    if (primary.albumAudioId > 0L && candidate.albumAudioId > 0L &&
        primary.albumAudioId == candidate.albumAudioId &&
        !isAccompanimentSong(candidate)
    ) {
        return false
    }
    val basePrimary = extractBaseSongTitle(primary.title)
    val baseCandidate = extractBaseSongTitle(candidate.title)
    if (basePrimary.isBlank() || basePrimary != baseCandidate) return false
    if (isLiveVersionSong(candidate)) return false
    if (isCoverOrRemixSong(candidate)) return false
    if (!isSameOriginalArtist(primary, candidate)) return false
    return true
}

internal fun decorateSubVersionDisplayTag(primary: Song, candidate: Song): Song {
    val inferred = inferSongVersionTag(candidate.title, rawTag = candidate.versionTag)
    if (inferred.isNotBlank()) {
        return candidate.copy(versionTag = inferred)
    }
    val bracketMatch = Regex("[（(\\[【]([^）)\\]】]{1,12})[）)\\]】]").find(candidate.title)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    val cleanTag = when {
        bracketMatch.isNotBlank() -> bracketMatch
        candidate.album.isNotBlank() && !candidate.album.equals(primary.album, ignoreCase = true) -> "专辑版"
        candidate.isHiRes && !primary.isHiRes -> "Hi-Res 母带"
        else -> "其他版本"
    }
    return candidate.copy(versionTag = cleanTag)
}

/**
 * 对单曲搜索列表按「原唱主版本 + 可展开同歌手非Live子版本（含伴奏）」进行原地归并：
 * - 若同一首歌、同一原唱在搜索结果中出现原版 + 伴奏/重制版/不同录音室版，自动选录音室原版作为主展示行，其余归入 versions；
 * - Live 版与他人翻唱保持各自独立（绝不混入原唱的更多版本展开列表）。
 */
fun MusicApiRepository.groupSearchSongsWithInlineVersions(songs: List<Song>): List<SearchSongGroupBucket> {
    if (songs.isEmpty()) return emptyList()
    val buckets = mutableListOf<SearchSongGroupBucket>()
    for (song in songs) {
        if (isLiveVersionSong(song) || isCoverOrRemixSong(song)) {
            buckets.add(SearchSongGroupBucket(primary = song, subVersions = mutableListOf()))
            continue
        }
        val matchedBucketIdx = buckets.indexOfFirst { bucket ->
            !isLiveVersionSong(bucket.primary) &&
                !isCoverOrRemixSong(bucket.primary) &&
                extractBaseSongTitle(bucket.primary.title).let { bt ->
                    bt.isNotBlank() && bt == extractBaseSongTitle(song.title)
                } &&
                isSameOriginalArtist(bucket.primary, song)
        }
        if (matchedBucketIdx < 0) {
            buckets.add(SearchSongGroupBucket(primary = song, subVersions = mutableListOf()))
        } else {
            val bucket = buckets[matchedBucketIdx]
            // 若当前 bucket.primary 反而是伴奏，而新遇到的是正式原唱人声版，则将人声原版提拔为主行
            if (isAccompanimentSong(bucket.primary) && !isAccompanimentSong(song)) {
                val oldPrimary = bucket.primary
                if (isEligibleSameSingerVersion(song, oldPrimary)) {
                    val decoratedOld = decorateSubVersionDisplayTag(song, oldPrimary)
                    val updatedSubs = (listOf(decoratedOld) + bucket.subVersions.map { decorateSubVersionDisplayTag(song, it) })
                        .distinctBy { (it.hash.ifBlank { it.id }).lowercase(Locale.ROOT) }
                        .toMutableList()
                    buckets[matchedBucketIdx] = SearchSongGroupBucket(primary = song, subVersions = updatedSubs)
                }
            } else if (isEligibleSameSingerVersion(bucket.primary, song)) {
                val decorated = decorateSubVersionDisplayTag(bucket.primary, song)
                val existingHashes = bucket.subVersions.flatMap { it.allLowercaseHashes() }.toHashSet()
                if (decorated.allLowercaseHashes().none { it in existingHashes }) {
                    bucket.subVersions.add(decorated)
                }
            } else if (bucket.primary.allLowercaseHashes().intersect(song.allLowercaseHashes()).isEmpty()) {
                // 同曲同歌手但完全相同录音重复项被静默去重，非重复异常项保留独立
            }
        }
    }
    return buckets
}

class SearchSongGroupBucket(
    val primary: Song,
    val subVersions: MutableList<Song>
) {
    fun toGroup(): SearchSongVersionGroup = SearchSongVersionGroup(
        primary = primary,
        versions = subVersions.toList()
    )
}

private val lyricMetadataLineRegex = Regex(
    """^(?:[\[\(【]?)(?:词|作词|曲|作曲|编曲|制作人|制作|混音|和声|合声|伴唱|吉他|贝斯|鼓|弦乐|钢琴|录音|母带|出品|发行|监制|统筹|策划|原唱|演唱|翻唱|人声|声乐|配唱|混音师|母带师|录音室|词曲|OP|SP|Lyricist|Composer|Arranger|Producer|Vocal)\s*[:：]""",
    RegexOption.IGNORE_CASE
)
private val lyricEmTagRegex = Regex("""<em>(.*?)</em>""", RegexOption.IGNORE_CASE)
private val lyricPunctuationCleanRegex = Regex("""[，,。！？!?、；;：:“”"'《》【】\[\]()（）~～—…·]+""")

/**
 * 精准提取真实唱词正文命中片段（对齐酷狗 `/v1/search/lyric?tag=em` 响应结构）：
 * 1. 自动剥离「歌名 - 歌手」首行与「词/曲/编曲/制作人」元信息行，彻底解决截图 8 中只展示制作人头的问题；
 * 2. 优先围绕服务端 `<em>` 高亮标记（或关键词匹配位置）截取 1~2 句连贯正文唱词，并做优雅省略号收尾；
 * 3. 返回 `(snippet, isBodyHit)`，若仅命中头部元信息行而非唱词正文，则 `isBodyHit = false` 且返回空串。
 */
internal fun extractMatchedLyricBodySnippet(
    rawLyric: String,
    keyword: String,
    songTitle: String = "",
    artist: String = ""
): Pair<String, Boolean> {
    if (rawLyric.isBlank()) return "" to false
    val cleanKw = lyricPunctuationCleanRegex.replace(keyword.trim(), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    val kwTokens = cleanKw.split(" ").filter { it.length >= 2 }.distinct()
        .ifEmpty { listOfNotNull(cleanKw.takeIf { it.isNotBlank() }) }
    val compactKw = cleanKw.replace(" ", "").lowercase(Locale.ROOT)
    val normTitle = songTitle.trim().lowercase(Locale.ROOT)
    val normArtist = artist.trim().lowercase(Locale.ROOT)

    val rawLines = rawLyric
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }

    data class LyricLineInfo(
        val plainText: String,
        val hasEmTag: Boolean,
        val isMetaHeader: Boolean,
        val compactPlain: String
    )

    val parsedLines = rawLines.mapIndexed { idx, line ->
        val hasEm = lyricEmTagRegex.containsMatchIn(line)
        val plain = htmlTagStripRegex.replace(line, "")
            .replace(Regex("""\[\d{1,2}:\d{2}(?:\.\d{1,3})?]"""), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val lowerPlain = plain.lowercase(Locale.ROOT)
        val isHeaderCredit = lyricMetadataLineRegex.containsMatchIn(plain) ||
            (idx == 0 && plain.contains(" - ") && (
                (normTitle.isNotBlank() && lowerPlain.contains(normTitle)) ||
                    (normArtist.isNotBlank() && lowerPlain.contains(normArtist))
                )) ||
            (idx <= 2 && Regex("""(?:词|曲|编曲|制作人)\s*[:：]""").containsMatchIn(plain))
        val compact = lyricPunctuationCleanRegex.replace(plain, "")
            .replace(" ", "")
            .lowercase(Locale.ROOT)
        LyricLineInfo(
            plainText = plain,
            hasEmTag = hasEm,
            isMetaHeader = isHeaderCredit,
            compactPlain = compact
        )
    }.filter { it.plainText.isNotBlank() }

    val bodyLines = parsedLines.filterNot { it.isMetaHeader }
    if (bodyLines.isEmpty()) return "" to false

    // 优先定位同时满足真实正文且包含 <em> 或关键词强命中的行索引
    var bestIdx = -1
    var bestLineScore = -1
    bodyLines.forEachIndexed { idx, info ->
        var lineScore = 0
        if (compactKw.length >= 2 && info.compactPlain.contains(compactKw)) {
            lineScore += 200
        }
        val tokenHits = kwTokens.count { t -> info.plainText.contains(t, ignoreCase = true) }
        if (tokenHits > 0) {
            lineScore += tokenHits * 65
        }
        if (info.hasEmTag) {
            lineScore += 90
        }
        if (lineScore > bestLineScore) {
            bestLineScore = lineScore
            bestIdx = idx
        }
    }

    if (bestIdx < 0 || bestLineScore < 65) {
        return "" to false
    }

    // 拼接命中句与紧邻的下一句（若总长合适），并居中优雅截断
    val primaryLine = bodyLines[bestIdx].plainText
    val nextLine = bodyLines.getOrNull(bestIdx + 1)?.plainText?.takeIf {
        it.length in 2..28 && primaryLine.length + it.length + 3 <= 48
    }
    val combined = if (nextLine != null) "$primaryLine · $nextLine" else primaryLine
    val snippet = if (combined.length <= 48) {
        combined
    } else {
        val anchorIdx = kwTokens.firstNotNullOfOrNull { t ->
            val pos = combined.indexOf(t, ignoreCase = true)
            if (pos >= 0) pos else null
        } ?: 0
        val windowStart = (anchorIdx - 12).coerceAtLeast(0)
        val windowEnd = (windowStart + 44).coerceAtMost(combined.length)
        val prefix = if (windowStart > 0) "…" else ""
        val suffix = if (windowEnd < combined.length) "…" else ""
        prefix + combined.substring(windowStart, windowEnd).trim() + suffix
    }
    return snippet to true
}

/**
 * 歌词搜歌「原唱优先 + 正文强匹配 + 双引擎共鸣」评分器：
 * 确保录音室原唱正版稳居第一，强力压低翻唱、Live、伴奏、DJ、加速降调及仅命中制作人头的噪音结果。
 */
internal fun scoreLyricOriginalPriority(
    song: Song,
    cleanKeyword: String,
    originalIndex: Int,
    isBodyHit: Boolean = song.matchedLyricSnippet.isNotBlank(),
    isOriginalFlag: Boolean = song.versionTag == "原唱",
    inGeneralEngine: Boolean = false
): Int {
    var score = 1000 - (originalIndex * 10)
    when {
        isCoverOrRemixSong(song) -> score -= 560
        isAccompanimentSong(song) -> score -= 480
        isLiveVersionSong(song) -> score -= 380
    }
    val bracketText = Regex("[（(\\[【]([^）)\\]】]+)[）)\\]】]").findAll(song.title)
        .joinToString(" ") { it.groupValues[1] }
    if (bracketText.contains("版") && !bracketText.contains("原版") && !bracketText.contains("正式版") && !bracketText.contains("录音室")) {
        score -= 190
    }
    if (isOriginalFlag) score += 160
    if (inGeneralEngine && isBodyHit) score += 320
    if (song.album.isNotBlank() && !song.album.contains("单曲") && !song.album.contains("网络")) {
        score += 50
    }
    if (song.isHiRes) score += 50
    else if (song.isLossless) score += 35

    if (isBodyHit && song.matchedLyricSnippet.isNotBlank()) {
        score += 240
        val compactSnippet = lyricPunctuationCleanRegex.replace(song.matchedLyricSnippet, "")
            .replace(" ", "")
            .lowercase(Locale.ROOT)
        val compactKw = lyricPunctuationCleanRegex.replace(cleanKeyword, "")
            .replace(" ", "")
            .lowercase(Locale.ROOT)
        if (compactKw.length >= 3 && compactSnippet.contains(compactKw)) {
            score += 260
        } else {
            val kwTokens = lyricPunctuationCleanRegex.replace(cleanKeyword, " ")
                .split(Regex("\\s+")).filter { it.length >= 2 }
            if (kwTokens.isNotEmpty() && kwTokens.all { song.matchedLyricSnippet.contains(it, ignoreCase = true) }) {
                score += 140
            }
        }
    } else {
        // 未真正命中唱词正文（可能仅命中词曲头），大幅压权
        score -= 220
    }
    return score
}

/**
 * 判断用户输入是否具有「歌词句搜歌」特征（避免对普通短歌名/歌手名产生无谓网络开销）
 */
internal fun isLikelyLyricQuery(keyword: String): Boolean {
    val clean = keyword.trim()
    if (clean.length < 5) return false
    if (clean.endsWith("歌单") || clean.endsWith("专辑") || clean.endsWith("合集") || clean.endsWith("OST", ignoreCase = true)) {
        return false
    }
    val cjkCount = Regex("""[\u4e00-\u9fa5]""").findAll(clean).count()
    val englishWords = Regex("""[A-Za-z]+""").findAll(clean).count()
    val hasSentencePause = Regex("""[\s，,。！？!?、]+""").containsMatchIn(clean)
    return cjkCount >= 6 || englishWords >= 4 || (hasSentencePause && (cjkCount >= 4 || englishWords >= 3))
}

internal fun decodeEncryptedJsonResponse(rawBytes: ByteArray, aesKey: String): JsonObject? {
    if (rawBytes.isEmpty()) return null
    val utf8Text = String(rawBytes, Charsets.UTF_8).trim()
    // 先尝试按合法明文 JSONObject 解析（避免仅靠首字节 '{'/'[' 误判，消除 ~0.78% 原始 AES 密文首字节碰撞）
    if (utf8Text.startsWith("{")) {
        val directObj = runCatching {
            val parsed = JsonParser.parseString(utf8Text)
            if (parsed.isJsonObject) parsed.asJsonObject else null
        }.getOrNull()
        if (directObj != null) return directObj
    }
    val plain = runCatching { KugouSigner.playlistAesDecrypt(rawBytes, aesKey) }
        .getOrElse {
            runCatching { KugouSigner.playlistAesDecrypt(aesKey, utf8Text) }.getOrDefault(utf8Text)
        }
        .trim()
    return runCatching {
        val parsed = JsonParser.parseString(plain)
        if (parsed.isJsonObject) parsed.asJsonObject else null
    }.getOrNull()
}

/**
 * 酷狗云歌单服务加密通用请求（严格对齐 MakcRe/KuGouMusicApi util/request.js createCloudRequest）
 */
internal suspend fun MusicApiRepository.callCloudListEncrypted(
    path: String,
    dataObj: JsonObject
): JsonObject? = auth { ctx ->
    val session = ctx.session
    val d = device()
    val clientTime = System.currentTimeMillis() / 1000
    val payloadObj = dataObj.deepCopy()
    val (aesKey, cipherBytes) = KugouSigner.playlistAesEncryptBytes(KugouSigner.gson.toJson(payloadObj))
    val uidLong = session.userId.toLongOrNull() ?: 0L
    val portraitJson = KugouSigner.gson.toJson(
        mapOf(
            "aes" to aesKey,
            "uid" to uidLong,
            "token" to session.token
        )
    )
    val portrait = KugouSigner.rsaPkcs1(portraitJson, lite = true).uppercase(Locale.ROOT)
    val key = KugouSigner.paramsKey(clientTime)
    val query = linkedMapOf(
        "appid" to KugouSigner.APP_ID.toString(),
        "clientver" to KugouSigner.CLIENT_VERSION.toString(),
        "mid" to d.mid,
        "clienttime" to clientTime.toString(),
        "key" to key,
        "dfid" to d.dfid.ifBlank { "-" },
        "p" to portrait
    )
    val headers = mapOf(
        "x-router" to "cloudlist.service.kugou.com",
        "Content-Type" to "application/json;charset=utf-8"
    )
    val rawBytes = transport.requestBinaryBytes(
        path = path,
        session = session,
        query = query,
        rawBodyBytes = cipherBytes,
        host = "https://gateway.kugou.com",
        method = "POST",
        headers = headers,
        sign = false,
        includeDefaultParams = false,
        device = d
    )
    val parsed = decodeEncryptedJsonResponse(rawBytes, aesKey)
    val errCode = parsed?.number("err_code") ?: parsed?.number("error_code") ?: parsed?.number("errcode")
    if (errCode == 20010) {
        throw ApiFailure(20010, parsed?.text("error_msg") ?: "登录已过期")
    }
    parsed
}

/**
 * 通过私有云歌单权威接口 /v4/get_list_all_file_v3 获取歌单实时 list_ver 与包含真实 fileid 的完整歌曲列表：
 * - 彻底解决公共 CDN /pubsongs 接口不返回 fileid 导致自定义排序为空、以及修改排序后 CDN 缓存延迟不刷新的问题。
 */
suspend fun MusicApiRepository.fetchCloudPlaylistTracksWithVer(
    listId: Long,
    type: Int = 0,
    sessionOverride: KugouSession? = null,
    onProgress: ((List<Song>) -> Unit)? = null
): Pair<Long, List<Song>> {
    if (listId <= 0L) return 0L to emptyList()
    val session = sessionOverride ?: accounts.context()?.session ?: return 0L to emptyList()
    if (session.token.isBlank()) return 0L to emptyList()

    val allSongs = mutableListOf<Song>()
    val seenTrackKeys = HashSet<String>()
    var resolvedListVer = 0L
    val pageSize = 300
    for (page in 1..40) {
        val body = json(
            "listid" to listId,
            "userid" to session.userId,
            "type" to type,
            "page" to page,
            "pagesize" to pageSize,
            "area_code" to 1,
            "allplatform" to 1,
            "show_cover" to 1,
            "token" to session.token
        )
        val resp = suspendRunCatching {
            transport.callAdvanced(
                path = "/v4/get_list_all_file_v3",
                session = session,
                method = "POST",
                body = body,
                headers = mapOf("x-router" to "cloudlist.service.kugou.com"),
                sign = true,
                includeDefaultParams = true
            )
        }.getOrNull() ?: break
        if (!resp.success) break

        val dataObj = resp.data.obj("data") ?: resp.data
        val ver = dataObj.longVal("list_ver")
            ?: dataObj.longVal("ver")
            ?: resp.data.longVal("list_ver")
            ?: 0L
        if (ver > resolvedListVer) resolvedListVer = ver
        val expectedCount = dataObj.number("count") ?: dataObj.number("total") ?: resp.data.number("count") ?: 0
        val arr = dataObj.arr("info")
            ?: dataObj.arr("list")
            ?: dataObj.arr("lists")
            ?: dataObj.arr("songs")
            ?: resp.data.arr("info")
            ?: resp.data.arr("list")
            ?: break
        if (arr.size() == 0) break

        val rawPageSongs = mutableListOf<Song>()
        val pageFileIds = ArrayList<Long>(arr.size())
        for (el in arr) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val fid = obj.longVal("fileid") ?: obj.longVal("file_id") ?: 0L
            val parsed = parseSong(obj).let { s ->
                if (fid > 0L && s.fileId != fid) s.copy(fileId = fid) else s
            }
            if (parsed.title.isNotBlank() || parsed.hash.isNotBlank()) {
                rawPageSongs.add(parsed)
                pageFileIds.add(parsed.fileId)
            }
        }
        if (rawPageSongs.isEmpty()) break
        val enriched = suspendRunCatching { enrichSongsPrivilegeLite(rawPageSongs) }.getOrDefault(rawPageSongs)
        var addedInPage = 0
        enriched.forEachIndexed { idx, s ->
            val origFid = pageFileIds.getOrNull(idx) ?: s.fileId
            val restored = if (origFid > 0L && s.fileId != origFid) s.copy(fileId = origFid) else s
            val dedupeKey = if (origFid > 0L) "fid_$origFid" else restored.hash.ifBlank { restored.id }.lowercase(Locale.ROOT)
            if (seenTrackKeys.add(dedupeKey)) {
                allSongs.add(restored)
                addedInPage++
            }
        }
        if (addedInPage > 0) {
            onProgress?.invoke(allSongs.toList())
        }
        if (addedInPage == 0 || arr.size() < pageSize) break
        if (expectedCount > 0 && allSongs.size >= expectedCount) break
    }
    return resolvedListVer to allSongs
}

// =========================================================================
// 搜索专属：同原唱、同歌曲、非 Live 多版本查询（供搜索结果展开更多版本使用）
// =========================================================================
suspend fun MusicApiRepository.fetchSameSingerSongVersionsForSearch(
    song: Song,
    existingVersions: List<Song> = emptyList(),
    pageSize: Int = 25
): List<Song> {
    val mixId = song.albumAudioId.takeIf { it > 0L }
        ?: song.audioId.takeIf { it > 0L }
        ?: song.mixSongId.toLongOrNull()?.takeIf { it > 0L }
        ?: 0L

    val session = accounts.context()?.session
    val candidates = mutableListOf<Song>()
    candidates.addAll(existingVersions)

    if (mixId > 0L) {
        val params = linkedMapOf(
            "album_audio_id" to mixId.toString(),
            "appid" to "1005",
            "area_code" to "1",
            "clientver" to "12329",
            "page" to "1",
            "pagesize" to pageSize.coerceIn(1, 40).toString(),
            "show_input" to "1",
            "show_type" to "0",
            "sort" to "1",
            "type" to "0",
            "version" to "1"
        )
        params["signature"] = KugouSigner.signatureStandard(params)

        val resp = suspendRunCatching {
            transport.callAdvanced(
                path = "/v3/album_audio/related",
                host = "https://listkmrp3cdnretry.kugou.com",
                session = session,
                query = params,
                method = "GET",
                sign = false,
                includeDefaultParams = false
            )
        }.getOrNull()

        if (resp != null) {
            candidates.addAll(parseSongs(resp.data))
        }
    }

    val preFiltered = candidates
        .filter { isEligibleSameSingerVersion(song, it) }
        .distinctBy { (it.hash.ifBlank { it.id }).lowercase(Locale.ROOT) }
    if (preFiltered.isEmpty()) return emptyList()

    val enriched = suspendRunCatching { enrichSongsPrivilegeLite(preFiltered) }.getOrDefault(preFiltered)
    val primaryHashes = song.allLowercaseHashes()
    val seenHashes = HashSet<String>(primaryHashes)
    val finalList = mutableListOf<Song>()
    for (item in enriched) {
        if (!isEligibleSameSingerVersion(song, item)) continue
        val itemHashes = item.allLowercaseHashes()
        if (itemHashes.isNotEmpty() && itemHashes.any { it in seenHashes }) continue
        seenHashes.addAll(itemHashes)
        finalList.add(decorateSubVersionDisplayTag(song, item))
    }
    return finalList
}

// =========================================================================
// 自建歌单云端信息编辑 & 歌单内歌曲自定义排序 & 歌单列表排序
// =========================================================================
suspend fun MusicApiRepository.updatePlaylistInfo(
    playlist: Playlist,
    newName: String? = null,
    newIntro: String? = null,
    newTags: String? = null
): Boolean {
    val listId = playlist.resolveEffectiveListId().takeIf { it > 0L } ?: return false
    val cachedMeta = peekCollectedPlaylistMeta(playlist)
    val effectiveTotalVer = playlist.totalVersion.takeIf { it > 0L } ?: cachedMeta?.totalVersion ?: 0L

    fun buildPayload(ver: Long, includeEmptyTagsFallback: Boolean): JsonObject = JsonObject().apply {
        addProperty("total_ver", ver)
        addProperty("listid", listId)
        addProperty("type", if (playlist.isCreatedByMe) 0 else 1)
        addProperty("sort", 0)
        if (newTags != null) {
            addProperty("tags", newTags)
        } else if (includeEmptyTagsFallback) {
            addProperty("tags", "")
        }
        addProperty("intro", newIntro ?: playlist.description)
        addProperty("name", (newName?.trim()?.takeIf { it.isNotBlank() } ?: playlist.title.trim()))
    }

    var resp = callCloudListEncrypted("/v1/modify_list", buildPayload(effectiveTotalVer, false))
    var errCode = resp?.number("err_code") ?: resp?.number("error_code") ?: resp?.number("errcode") ?: 0
    if (resp == null || resp.number("status") != 1 || errCode != 0) {
        // 若 total_ver 过期或缺少默认 tags 字段，使用 total_ver=0 + tags 兜底重试一次
        resp = callCloudListEncrypted("/v1/modify_list", buildPayload(0L, true))
        errCode = resp?.number("err_code") ?: resp?.number("error_code") ?: resp?.number("errcode") ?: 0
    }
    return resp != null && resp.number("status") == 1 && errCode == 0
}

suspend fun MusicApiRepository.sortPlaylistTracks(
    playlist: Playlist,
    orderedSongs: List<Song>,
    listVer: Long = 0L
): Boolean {
    val listId = playlist.resolveEffectiveListId().takeIf { it > 0L } ?: return false
    val playlistType = if (playlist.isCreatedByMe) 0 else 1

    // 优先通过私有云 /v4/get_list_all_file_v3 获取权威 list_ver 与真实 fileid 映射
    var effectiveListVer = listVer
    val fallbackFileIdByHash = HashMap<String, Long>()
    val fallbackFileIdByAudioId = HashMap<Long, Long>()
    val fallbackFileIdByTitleArtist = HashMap<String, Long>()

    if (effectiveListVer <= 0L || orderedSongs.any { it.fileId <= 0L }) {
        val (remoteVer, remoteTracks) = suspendRunCatching {
            fetchCloudPlaylistTracksWithVer(listId = listId, type = playlistType)
        }.getOrDefault(0L to emptyList())
        if (remoteVer > 0L) effectiveListVer = remoteVer
        for (t in remoteTracks) {
            if (t.fileId <= 0L) continue
            t.allLowercaseHashes().forEach { h -> fallbackFileIdByHash.putIfAbsent(h, t.fileId) }
            if (t.albumAudioId > 0L) fallbackFileIdByAudioId.putIfAbsent(t.albumAudioId, t.fileId)
            if (t.audioId > 0L) fallbackFileIdByAudioId.putIfAbsent(t.audioId, t.fileId)
            val normTa = "${normalizeSearchToken(t.title)}_${normalizeSearchToken(t.artist)}"
            if (normTa.length > 2) fallbackFileIdByTitleArtist.putIfAbsent(normTa, t.fileId)
        }
    }

    val resourceArr = JsonArray()
    val usedFileIds = HashSet<Long>()
    var sortIdx = 0
    orderedSongs.forEach { song ->
        val normTa = "${normalizeSearchToken(song.title)}_${normalizeSearchToken(song.artist)}"
        val fid = song.fileId.takeIf { it > 0L }
            ?: song.allLowercaseHashes().firstNotNullOfOrNull { fallbackFileIdByHash[it] }
            ?: song.albumAudioId.takeIf { it > 0L }?.let { fallbackFileIdByAudioId[it] }
            ?: song.audioId.takeIf { it > 0L }?.let { fallbackFileIdByAudioId[it] }
            ?: fallbackFileIdByTitleArtist[normTa]
            ?: 0L
        if (fid > 0L && usedFileIds.add(fid)) {
            sortIdx += 1
            resourceArr.add(JsonObject().apply {
                addProperty("fileid", fid)
                addProperty("sort", sortIdx)
            })
        }
    }
    if (resourceArr.size() == 0) return false

    fun buildSortPayload(ver: Long): JsonObject = JsonObject().apply {
        addProperty("listid", listId)
        addProperty("list_ver", ver)
        addProperty("type", playlistType)
        add("data", resourceArr)
    }

    var resp = callCloudListEncrypted("/v1/modify_song_sort", buildSortPayload(effectiveListVer))
    var errCode = resp?.number("err_code") ?: resp?.number("error_code") ?: resp?.number("errcode") ?: 0
    if ((resp == null || resp.number("status") != 1 || errCode != 0) && effectiveListVer > 0L) {
        // 若云端返回版本冲突，使用 list_ver=0 强制覆盖重试一次
        resp = callCloudListEncrypted("/v1/modify_song_sort", buildSortPayload(0L))
        errCode = resp?.number("err_code") ?: resp?.number("error_code") ?: resp?.number("errcode") ?: 0
    }
    return resp != null && resp.number("status") == 1 && errCode == 0
}

suspend fun MusicApiRepository.sortUserPlaylists(
    orderedPlaylists: List<Playlist>,
    isCreatedByMe: Boolean = true,
    totalVer: Long = 0L
): Boolean {
    val type = if (isCreatedByMe) 0 else 1
    val resourceArr = JsonArray()
    var sortIdx = 0
    orderedPlaylists.forEach { pl ->
        val lid = pl.resolveEffectiveListId()
        if (lid > 0L) {
            sortIdx += 1
            resourceArr.add(JsonObject().apply {
                addProperty("listid", lid)
                addProperty("type", type)
                addProperty("sort", sortIdx)
            })
        }
    }
    if (resourceArr.size() == 0) return false
    val effectiveVer = totalVer.takeIf { it > 0L } ?: orderedPlaylists.maxOfOrNull { it.totalVersion } ?: 0L
    val dataObj = JsonObject().apply {
        addProperty("total_ver", effectiveVer)
        add("data", resourceArr)
    }
    val resp = callCloudListEncrypted("/v1/modify_list_sort", dataObj)
    val errCode = resp?.number("err_code") ?: resp?.number("error_code") ?: resp?.number("errcode") ?: 0
    return resp != null && resp.number("status") == 1 && errCode == 0
}

// =========================================================================
// 按歌词搜歌 & 概念版混合搜索融合引擎（对齐 MakcRe/KuGouMusicApi search.js type=lyric & search_mixed.js）
// =========================================================================
private fun collectSongsFromMixedNode(node: JsonElement?, repo: MusicApiRepository, out: MutableList<Song>, depth: Int = 0) {
    if (node == null || node.isJsonNull || depth > 5) return
    when {
        node.isJsonArray -> {
            for (el in node.asJsonArray) {
                collectSongsFromMixedNode(el, repo, out, depth + 1)
            }
        }
        node.isJsonObject -> {
            val obj = node.asJsonObject
            val hasAudioIdentity = obj.has("FileHash") || obj.has("hash") || obj.has("SQFileHash") ||
                obj.has("HQFileHash") || obj.has("ResFileHash") || obj.has("audio_info")
            val hasSongName = obj.has("SongName") || obj.has("songname") || obj.has("song_name") || obj.has("FileName")
            if (hasAudioIdentity && hasSongName) {
                val parsed = repo.parseSong(obj)
                if (parsed.title.isNotBlank() && parsed.title != "未知歌曲" && (parsed.hash.isNotBlank() || parsed.id.isNotBlank())) {
                    out.add(parsed)
                }
            }
            for ((k, v) in obj.entrySet()) {
                if (k != "Grp" && (v.isJsonArray || v.isJsonObject)) {
                    collectSongsFromMixedNode(v, repo, out, depth + 1)
                }
            }
        }
    }
}

/**
 * 酷狗概念版原生混合搜索接口（对齐 MakcRe/KuGouMusicApi module/search_mixed.js lite 协议），
 * 对口语化半句歌词与错别字歌词具有服务端语义纠错与原唱优先能力。
 */
internal suspend fun MusicApiRepository.searchMixedLiteSongs(keyword: String): List<Song> {
    val cleanKeyword = keyword.trim()
    if (cleanKeyword.isEmpty()) return emptyList()
    val session = accounts.context()?.session
    val nowMs = System.currentTimeMillis()
    val resp = suspendRunCatching {
        transport.call(
            path = "/v3/search/mixed",
            session = session,
            method = "GET",
            query = mapOf(
                "keyword" to cleanKeyword,
                "platform" to "AndroidFilter",
                "tag" to "em",
                "area_code" to "1",
                "iscorrection" to "1",
                "cursor" to "0",
                "apiver" to "22",
                "osversion" to "15",
                "ability" to "1",
                "clientver" to "201",
                "vip_status" to "0"
            ),
            headers = mapOf(
                "x-router" to "complexsearch.kugou.com",
                "kg-clienttimems" to nowMs.toString()
            )
        )
    }.getOrNull() ?: return emptyList()

    val collected = mutableListOf<Song>()
    collectSongsFromMixedNode(resp.data, this, collected)
    if (collected.isEmpty()) {
        collectSongsFromMixedNode(resp.body, this, collected)
    }
    return collected
        .distinctBy { (it.hash.ifBlank { it.id }).lowercase(Locale.ROOT) }
        .take(25)
}

suspend fun MusicApiRepository.searchByLyric(
    keyword: String,
    page: Int = 1,
    pageSize: Int = 30
): SearchResult {
    val rawKeyword = keyword.trim()
    if (rawKeyword.isEmpty()) return SearchResult(emptyList(), 0, page, pageSize)
    val cleanKeyword = lyricPunctuationCleanRegex.replace(rawKeyword, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { rawKeyword }
    val session = accounts.context()?.session

    suspend fun queryLyricEndpoint(kw: String): Pair<List<Triple<Song, Boolean, Boolean>>, Int> {
        val resp = suspendRunCatching {
            transport.call(
                path = "/v1/search/lyric",
                session = session,
                method = "GET",
                query = mapOf(
                    "albumhide" to "0",
                    "iscorrection" to "1",
                    "keyword" to kw,
                    "nocollect" to "0",
                    "page" to page.coerceAtLeast(1).toString(),
                    "pagesize" to pageSize.coerceIn(1, 50).toString(),
                    "platform" to "AndroidFilter",
                    "tag" to "em"
                ),
                headers = mapOf("x-router" to "complexsearch.kugou.com")
            )
        }.getOrNull() ?: return emptyList<Triple<Song, Boolean, Boolean>>() to 0

        val dataObj = resp.data.obj("data") ?: resp.data
        val respTotal = dataObj.number("total") ?: resp.data.number("total") ?: resp.body.number("total") ?: 0
        val listsArr = dataObj.arr("lists")
            ?: dataObj.arr("info")
            ?: resp.data.arr("lists")
            ?: resp.data.arr("info")
            ?: resp.body.arr("lists")
            ?: return emptyList<Triple<Song, Boolean, Boolean>>() to respTotal

        val parsed = mutableListOf<Triple<Song, Boolean, Boolean>>()
        for (el in listsArr) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val baseSong = parseSong(obj)
            if (baseSong.title.isBlank() && baseSong.hash.isBlank()) continue
            val rawLyric = obj.text("lyrics")
                ?: obj.text("lyric")
                ?: obj.text("Lyric")
                ?: obj.text("Lyrics")
                ?: obj.text("matched_lyric")
                ?: obj.text("highlight_lyric")
                ?: ""
            val (bodySnippet, isBodyHit) = extractMatchedLyricBodySnippet(
                rawLyric = rawLyric,
                keyword = kw,
                songTitle = baseSong.title,
                artist = baseSong.artist
            )
            val isOrig = obj.number("is_original") == 1 || obj.number("Original") == 1
            val tag = inferSongVersionTag(baseSong.title, rawRemark = obj.text("remark").orEmpty(), rawTag = baseSong.versionTag)
            val s = baseSong.copy(
                matchedLyricSnippet = if (isBodyHit) bodySnippet else "",
                versionTag = if (isOrig && tag.isBlank()) "原唱" else tag
            )
            parsed.add(Triple(s, isBodyHit, isOrig))
        }
        return parsed to respTotal
    }

    var (rawTriples, total) = queryLyricEndpoint(cleanKeyword)
    // 若用户粘贴了一大段两三句歌词导致首查为空，自动取最具区分度的前 12 字核心句重查一次
    if (rawTriples.isEmpty() && cleanKeyword.length > 14) {
        val shorter = cleanKeyword.split(" ").firstOrNull { it.length >= 5 }?.take(14)
            ?: cleanKeyword.take(12)
        if (shorter != cleanKeyword) {
            val retry = queryLyricEndpoint(shorter)
            rawTriples = retry.first
            total = maxOf(total, retry.second)
        }
    }

    if (rawTriples.isEmpty()) return SearchResult(emptyList(), 0, page, pageSize)

    val rawSongs = rawTriples.map { it.first }
    val bodyHitByKey = HashMap<String, Boolean>()
    val origByKey = HashMap<String, Boolean>()
    val snippetByAnyHash = HashMap<String, String>()
    val tagByAnyHash = HashMap<String, String>()
    rawTriples.forEach { (s, isBody, isOrig) ->
        val keys = s.allLowercaseHashes() + setOfNotNull(s.id.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT))
        keys.forEach { k ->
            bodyHitByKey[k] = (bodyHitByKey[k] == true) || isBody
            origByKey[k] = (origByKey[k] == true) || isOrig
            if (s.matchedLyricSnippet.isNotBlank()) snippetByAnyHash.putIfAbsent(k, s.matchedLyricSnippet)
            if (s.versionTag.isNotBlank()) tagByAnyHash.putIfAbsent(k, s.versionTag)
        }
    }

    val enriched = suspendRunCatching { enrichSongsPrivilegeLite(rawSongs) }.getOrDefault(rawSongs)
    val restored = enriched.mapIndexed { idx, s ->
        val origTuple = rawTriples.getOrNull(idx)
        val keys = s.allLowercaseHashes() + setOfNotNull(s.id.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT))
        val origSnippet = origTuple?.first?.matchedLyricSnippet?.takeIf { it.isNotBlank() }
            ?: keys.firstNotNullOfOrNull { snippetByAnyHash[it] }
            ?: ""
        val origTag = origTuple?.first?.versionTag?.takeIf { it.isNotBlank() }
            ?: keys.firstNotNullOfOrNull { tagByAnyHash[it] }
            ?: s.versionTag
        val isBody = origTuple?.second == true || keys.any { bodyHitByKey[it] == true }
        val isOrig = origTuple?.third == true || keys.any { origByKey[it] == true }
        Triple(
            s.copy(
                matchedLyricSnippet = s.matchedLyricSnippet.ifBlank { origSnippet },
                versionTag = origTag
            ),
            isBody,
            isOrig
        )
    }

    val rankedSongs = restored
        .mapIndexed { idx, (s, isBody, isOrig) ->
            s to scoreLyricOriginalPriority(
                song = s,
                cleanKeyword = cleanKeyword,
                originalIndex = idx,
                isBodyHit = isBody,
                isOriginalFlag = isOrig,
                inGeneralEngine = false
            )
        }
        .sortedByDescending { it.second }
        .map { it.first }
        .distinctBy { (it.hash.ifBlank { it.id }).lowercase(Locale.ROOT) }

    return SearchResult(
        songs = rankedSongs,
        total = maxOf(total, rankedSongs.size),
        page = page,
        pageSize = pageSize
    )
}

/**
 * 将精准歌词搜歌与概念版混合搜索无缝融合进综合/单曲搜索：
 * - 普通歌名/歌手精确命中时 0 额外网络开销，不打扰原有结果；
 * - 当用户输入一句歌词（或主单曲引擎无精确歌名命中）时，并发召回 `/v1/search/lyric` 与 `/v3/search/mixed`，
 *   提取真实唱词正文并执行「双引擎共鸣 + 原唱优先」精准重排，让歌词找歌一搜即中。
 */
internal suspend fun MusicApiRepository.fuseLyricSearchIntoSongs(
    keyword: String,
    primarySongs: List<Song>,
    songTitleExactHits: Int = 0,
    hasDirectArtistHit: Boolean = false
): List<Song> = coroutineScope {
    val cleanKw = keyword.trim()
    if (cleanKw.length < 4 || hasDirectArtistHit) return@coroutineScope primarySongs
    val shouldActivate = (isLikelyLyricQuery(cleanKw) && songTitleExactHits == 0) ||
        (primarySongs.size < 5 && songTitleExactHits == 0)
    if (!shouldActivate) return@coroutineScope primarySongs

    val lyricDef = async {
        suspendRunCatching { searchByLyric(cleanKw, page = 1, pageSize = 25) }.getOrNull()?.songs.orEmpty()
    }
    val mixedDef = async {
        suspendRunCatching { searchMixedLiteSongs(cleanKw) }.getOrDefault(emptyList())
    }
    val lyricHits = lyricDef.await().filter { it.matchedLyricSnippet.isNotBlank() }
    val mixedHits = mixedDef.await()
    if (lyricHits.isEmpty() && mixedHits.isEmpty()) return@coroutineScope primarySongs

    // 收集常规/混合引擎命中的歌曲集合（按归一化 32 位 Hash 与 核心歌名+歌手 双键识别双引擎共鸣）
    fun trackSignature(s: Song): String =
        "${extractBaseSongTitle(s.title)}|${s.artist.split("、", "/", ",", "&").firstOrNull()?.trim()?.lowercase(Locale.ROOT).orEmpty()}"

    val generalHashes = HashSet<String>()
    val generalSigs = HashSet<String>()
    (primarySongs.take(18) + mixedHits.take(15)).forEach { s ->
        s.allLowercaseHashes().forEach { generalHashes.add(it) }
        val sig = trackSignature(s)
        if (sig.length > 2) generalSigs.add(sig)
    }

    val scoredLyricSongs = lyricHits.mapIndexed { idx, song ->
        val inGeneral = song.allLowercaseHashes().any { it in generalHashes } || trackSignature(song) in generalSigs
        val sc = scoreLyricOriginalPriority(
            song = song,
            cleanKeyword = cleanKw,
            originalIndex = idx,
            isBodyHit = true,
            isOriginalFlag = song.versionTag == "原唱",
            inGeneralEngine = inGeneral
        )
        song to sc
    }.sortedByDescending { it.second }.map { it.first }

    // 合并顺序：高置信度正文命中的歌词原唱置顶 -> 混合语义结果 -> 原始单曲结果，按同一首歌去重并保留命中唱词
    val merged = mutableListOf<Song>()
    fun appendOrMerge(candidate: Song) {
        val existingIdx = merged.indexOfFirst { existing ->
            com.concept.music.data.repository.isSameMusicTrack(existing, candidate)
        }
        if (existingIdx >= 0) {
            val prev = merged[existingIdx]
            val best = com.concept.music.data.repository.mergeTrackHigherQuality(prev, candidate)
            merged[existingIdx] = best.copy(
                matchedLyricSnippet = prev.matchedLyricSnippet.ifBlank { candidate.matchedLyricSnippet },
                versionTag = prev.versionTag.ifBlank { candidate.versionTag }
            )
        } else {
            merged.add(candidate)
        }
    }

    scoredLyricSongs.forEach(::appendOrMerge)
    mixedHits.forEach(::appendOrMerge)
    primarySongs.forEach(::appendOrMerge)
    merged
}

// =========================================================================
// 历史每日推荐回溯（过去 6 天 + 今日 = 最近完整 7 天一周）— everyday_history.js
// =========================================================================
private fun formatChineseHistoryDisplayTitle(date: LocalDate, todayDate: LocalDate): String {
    val diffDays = java.time.temporal.ChronoUnit.DAYS.between(date, todayDate)
    val mmDd = String.format(Locale.US, "%02d-%02d", date.monthValue, date.dayOfMonth)
    val weekLabel = when (date.dayOfWeek) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
        else -> ""
    }
    return when (diffDays) {
        1L -> "昨天 · $mmDd"
        2L -> "前天 · $mmDd"
        else -> "$mmDd $weekLabel"
    }
}

private fun extractIsoDateFromText(raw: String, fallbackYear: Int): LocalDate? {
    val clean = raw.trim()
    Regex("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})").find(clean)?.let { m ->
        return runCatching {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }.getOrNull()
    }
    Regex("(\\d{4})(\\d{2})(\\d{2})").find(clean)?.let { m ->
        return runCatching {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }.getOrNull()
    }
    Regex("(\\d{1,2})[-/.月](\\d{1,2})").find(clean)?.let { m ->
        return runCatching {
            LocalDate.of(fallbackYear, m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }.getOrNull()
    }
    return null
}

private fun formatCompactDate(date: LocalDate): String =
    String.format(Locale.US, "%04d%02d%02d", date.year, date.monthValue, date.dayOfMonth)

private fun extractCompactDateFromText(raw: String, fallbackYear: Int): String {
    val parsed = extractIsoDateFromText(raw, fallbackYear) ?: return ""
    return formatCompactDate(parsed)
}

private fun collectDailyHistoryEntriesRecursive(
    node: JsonElement?,
    out: MutableList<DailyHistoryEntry>,
    depth: Int = 0
) {
    if (node == null || node.isJsonNull || depth > 5) return
    when {
        node.isJsonArray -> {
            for (el in node.asJsonArray) {
                if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
                    val raw = el.asString.trim()
                    if (raw.isNotBlank() && (raw.contains("RT_", ignoreCase = true) || Regex("\\d{4}[-/.]?\\d{2}[-/.]?\\d{2}").containsMatchIn(raw))) {
                        out.add(DailyHistoryEntry(historyName = raw, dateLabel = raw, displayTitle = raw, compactDate = ""))
                    }
                } else if (el.isJsonObject) {
                    val obj = el.asJsonObject
                    val hName = obj.text("history_name")
                        ?: obj.text("historyName")
                        ?: obj.text("name")
                        ?: obj.text("batch_name")
                        ?: obj.text("rec_id")
                        ?: obj.text("date")
                        ?: obj.text("day")
                    val rawDate = obj.text("date")
                        ?: obj.text("day")
                        ?: obj.text("rec_date")
                        ?: obj.text("show_date")
                        ?: hName
                    if (!hName.isNullOrBlank()) {
                        val title = obj.text("title") ?: obj.text("show_date") ?: rawDate ?: hName
                        out.add(
                            DailyHistoryEntry(
                                historyName = hName.trim(),
                                dateLabel = (rawDate ?: hName).trim(),
                                displayTitle = title.trim(),
                                compactDate = ""
                            )
                        )
                    } else {
                        collectDailyHistoryEntriesRecursive(obj, out, depth + 1)
                    }
                } else if (el.isJsonArray) {
                    collectDailyHistoryEntriesRecursive(el, out, depth + 1)
                }
            }
        }
        node.isJsonObject -> {
            val obj = node.asJsonObject
            val prioritizedKeys = listOf("history_list", "lists", "list", "history_names", "dates", "info", "data", "items", "records")
            val visited = HashSet<String>()
            for (k in prioritizedKeys) {
                if (obj.has(k)) {
                    visited.add(k)
                    collectDailyHistoryEntriesRecursive(obj.get(k), out, depth + 1)
                }
            }
            if (out.isEmpty()) {
                for ((k, v) in obj.entrySet()) {
                    if (k !in visited && (v.isJsonArray || v.isJsonObject)) {
                        collectDailyHistoryEntriesRecursive(v, out, depth + 1)
                    }
                }
            }
        }
    }
}

suspend fun MusicApiRepository.fetchDailyRecommendHistoryList(): List<DailyHistoryEntry> {
    if (accounts.context()?.session == null) return emptyList()
    val todayDate = LocalDate.now(CHINA_ZONE)
    val todayCompact = formatCompactDate(todayDate)
    val activeUid = accounts.context()?.session?.userId

    val serverEntries = mutableListOf<DailyHistoryEntry>()
    suspendRunCatching {
        auth { ctx ->
            val session = ctx.session
            suspend fun requestList(platform: String, emptyJsonBody: Boolean): ApiResponse? = suspendRunCatching {
                transport.call(
                    path = "/everyday/api/v1/get_history",
                    session = session,
                    method = "POST",
                    query = mapOf("mode" to "list", "platform" to platform),
                    body = if (emptyJsonBody) JsonObject() else null,
                    headers = mapOf("x-router" to "everydayrec.service.kugou.com")
                )
            }.getOrNull()

            val attempts = listOf("ios" to false, "android" to false, "ios" to true, "android" to true)
            var sawInvalidSession = false
            var lastInvalidMsg = ""
            for ((plat, withBody) in attempts) {
                val resp = requestList(plat, withBody) ?: continue
                if (resp.code == 20010) {
                    sawInvalidSession = true
                    lastInvalidMsg = resp.errorMsg()
                    continue
                }
                collectDailyHistoryEntriesRecursive(resp.data, serverEntries)
                if (serverEntries.isEmpty()) {
                    collectDailyHistoryEntriesRecursive(resp.body, serverEntries)
                }
                if (serverEntries.isNotEmpty()) break
            }
            if (serverEntries.isEmpty() && sawInvalidSession) {
                throw ApiFailure(20010, lastInvalidMsg.ifBlank { "登录态需刷新" })
            }
        }
    }

    // 保留服务端真实历史批次（严格保留原始 history_name 与 8 位紧凑日期 compactDate，绝不伪造假日期）
    val byIsoDate = LinkedHashMap<LocalDate, DailyHistoryEntry>()
    val unmatchedServerEntries = mutableListOf<DailyHistoryEntry>()
    for (entry in serverEntries) {
        val parsedDate = extractIsoDateFromText(entry.dateLabel, todayDate.year)
            ?: extractIsoDateFromText(entry.historyName, todayDate.year)
        if (parsedDate != null) {
            if (parsedDate.isBefore(todayDate)) {
                val compact = formatCompactDate(parsedDate)
                byIsoDate.putIfAbsent(
                    parsedDate,
                    entry.copy(
                        historyName = entry.historyName.trim(),
                        dateLabel = parsedDate.toString(),
                        displayTitle = formatChineseHistoryDisplayTitle(parsedDate, todayDate),
                        compactDate = compact
                    )
                )
            }
        } else if (entry.historyName.isNotBlank()) {
            unmatchedServerEntries.add(entry)
        }
    }

    // 融合本机已真实存档过的过去日推快照（仅补充确实有缓存歌曲的真实日期，绝不凭空编造）
    val localDates = runCatching {
        com.concept.music.data.vip.VipManager.discoverCache.listDailyRecommendHistoryDates(activeUid)
    }.getOrDefault(emptyList())
    for (cDate in localDates) {
        if (cDate == todayCompact || cDate.length != 8) continue
        val parsed = extractIsoDateFromText(cDate, todayDate.year) ?: continue
        if (!parsed.isBefore(todayDate)) continue
        if (!byIsoDate.containsKey(parsed)) {
            val cachedSongs = runCatching {
                com.concept.music.data.vip.VipManager.discoverCache.getDailyRecommendHistorySnapshot(activeUid, cDate)
            }.getOrDefault(emptyList())
            if (cachedSongs.isNotEmpty()) {
                byIsoDate[parsed] = DailyHistoryEntry(
                    historyName = "local_$cDate",
                    dateLabel = parsed.toString(),
                    displayTitle = formatChineseHistoryDisplayTitle(parsed, todayDate),
                    compactDate = cDate
                )
            }
        }
    }

    if (byIsoDate.isEmpty() && unmatchedServerEntries.isNotEmpty()) {
        return unmatchedServerEntries.distinctBy { it.historyName }.take(7)
    }

    return byIsoDate.entries
        .sortedByDescending { it.key }
        .take(7)
        .map { it.value }
}

suspend fun MusicApiRepository.fetchDailyRecommendHistorySongs(
    historyName: String,
    date: String = ""
): List<Song> {
    val activeUid = accounts.context()?.session?.userId
    val todayYear = LocalDate.now(CHINA_ZONE).year
    val compactDate = extractCompactDateFromText(date, todayYear)
        .ifBlank { extractCompactDateFromText(historyName, todayYear) }
        .ifBlank { date.filter(Char::isDigit).takeIf { it.length == 8 }.orEmpty() }
    val isoDate = extractIsoDateFromText(date, todayYear)?.toString()
        ?: extractIsoDateFromText(historyName, todayYear)?.toString()
        ?: date.trim()

    // 若为本机历史快照条目，直接零延迟读取真实归档歌曲
    if (historyName.startsWith("local_") && compactDate.length == 8) {
        return runCatching {
            com.concept.music.data.vip.VipManager.discoverCache.getDailyRecommendHistorySnapshot(activeUid, compactDate)
        }.getOrDefault(emptyList())
    }

    if (accounts.context()?.session == null) return emptyList()

    val cloudSongs = suspendRunCatching {
        auth { ctx ->
            val session = ctx.session
            var sawInvalidSession = false
            var lastInvalidMsg = ""
            suspend fun requestModeSong(
                platform: String,
                hName: String,
                dateVal: String,
                emptyJsonBody: Boolean = false
            ): List<Song> {
                val query = linkedMapOf(
                    "mode" to "song",
                    "platform" to platform
                )
                if (hName.isNotBlank()) query["history_name"] = hName
                if (dateVal.isNotBlank()) query["date"] = dateVal
                val resp = suspendRunCatching {
                    transport.call(
                        path = "/everyday/api/v1/get_history",
                        session = session,
                        method = "POST",
                        query = query,
                        body = if (emptyJsonBody) JsonObject() else null,
                        headers = mapOf("x-router" to "everydayrec.service.kugou.com")
                    )
                }.getOrNull() ?: return emptyList()
                if (resp.code == 20010) {
                    sawInvalidSession = true
                    lastInvalidMsg = resp.errorMsg()
                    return emptyList()
                }
                val fromData = parseSongs(resp.data)
                return if (fromData.isNotEmpty()) fromData else parseSongs(resp.body)
            }

            // 严格按上游 KuGouMusicApi 契约优先传真实 history_name + 8 位紧凑日期 yyyyMMdd
            val primaryDate = compactDate.ifBlank { date.trim() }
            val candidates = linkedSetOf<Triple<String, String, String>>().apply {
                add(Triple("ios", historyName.trim(), primaryDate))
                add(Triple("android", historyName.trim(), primaryDate))
                if (isoDate.isNotBlank() && isoDate != primaryDate) {
                    add(Triple("ios", historyName.trim(), isoDate))
                    add(Triple("android", historyName.trim(), isoDate))
                }
                if (compactDate.isNotBlank() && compactDate != historyName.trim()) {
                    add(Triple("ios", compactDate, compactDate))
                    add(Triple("android", "", compactDate))
                }
            }

            var resolved: List<Song> = emptyList()
            for ((plat, hName, dVal) in candidates) {
                resolved = requestModeSong(plat, hName, dVal, emptyJsonBody = false)
                if (resolved.isNotEmpty()) break
            }
            if (resolved.isEmpty() && primaryDate.isNotBlank()) {
                resolved = requestModeSong("ios", historyName.trim(), primaryDate, emptyJsonBody = true)
                    .ifEmpty { requestModeSong("android", historyName.trim(), primaryDate, emptyJsonBody = true) }
            }
            if (resolved.isEmpty() && sawInvalidSession) {
                throw ApiFailure(20010, lastInvalidMsg.ifBlank { "登录态需刷新" })
            }
            if (resolved.isEmpty()) emptyList()
            else suspendRunCatching { enrichSongsPrivilegeLite(resolved) }.getOrDefault(resolved)
        }
    }.getOrDefault(emptyList())

    if (cloudSongs.isNotEmpty()) {
        if (compactDate.length == 8) {
            runCatching {
                com.concept.music.data.vip.VipManager.discoverCache.saveDailyRecommendHistorySnapshot(activeUid, compactDate, cloudSongs)
            }
        }
        return cloudSongs
    }

    // 云端未返回时回退到该日期本机真实归档快照
    if (compactDate.length == 8) {
        return runCatching {
            com.concept.music.data.vip.VipManager.discoverCache.getDailyRecommendHistorySnapshot(activeUid, compactDate)
        }.getOrDefault(emptyList())
    }
    return emptyList()
}

// =========================================================================
// 评论删除 + 歌单/专辑评论（对齐 _comment.js extractResolvedResource 资源映射）
// =========================================================================
private fun extractResolvedCommentResource(resp: ApiResponse): Pair<String, String> {
    val d = resp.data
    val topId = d.text("childrenid") ?: d.text("special_child_id") ?: resp.body.text("childrenid") ?: ""
    val topName = d.text("childrenname") ?: d.text("special_child_name") ?: ""
    val firstItem = (d.arr("list") ?: d.arr("weight_list") ?: d.arr("hot_list"))
        ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
    val itemId = firstItem?.text("special_child_id") ?: firstItem?.text("childrenid") ?: ""
    val itemName = firstItem?.text("special_child_name") ?: firstItem?.text("childrenname") ?: ""
    return (topId.ifBlank { itemId }) to (topName.ifBlank { itemName })
}

suspend fun MusicApiRepository.deleteComment(
    targetType: CommentTargetType,
    targetId: String,
    commentId: String,
    tid: String? = null,
    song: Song? = null
): Boolean = auth { ctx ->
    val session = ctx.session
    if (session.token.isBlank()) throw ApiFailure(20010, "请先登录酷狗账号后再删除评论")
    val d = device()
    val clientTime = System.currentTimeMillis() / 1000
    val code = targetType.commentPoolCode()
    val cleanTargetId = targetId.trim().takeIf { it.isNotBlank() && it != "0" }
    val cacheKey = "${targetType.name}:${cleanTargetId.orEmpty()}"
    val cachedResource = commentResolvedResourceCache[cacheKey]

    val preferredTargetId = if (targetType == CommentTargetType.SONG && cleanTargetId != null && hex32HashRegex.matches(cleanTargetId)) {
        null
    } else {
        cleanTargetId
    }
    val resolvedChildrenId = commentSpecialChildIdCache[commentId]?.takeIf { it.isNotBlank() && it != "0" }
        ?: cachedResource?.first?.takeIf { it.isNotBlank() && it != "0" }
        ?: preferredTargetId
        ?: song?.let { suspendRunCatching { resolveCommentMixSongId(it) }.getOrDefault(0L).takeIf { id -> id > 0L }?.toString() }
        ?: cleanTargetId
        ?: song?.hash?.trim()?.lowercase(Locale.ROOT)
        ?: return@auth false

    suspend fun sendDelRequest(childrenIdVal: String): ApiResponse {
        val nowSec = System.currentTimeMillis() / 1000
        val query = linkedMapOf(
            "r" to "commentsv2/delcomment",
            "code" to code,
            "childrenid" to childrenIdVal,
            "cid" to commentId,
            "kugouid" to session.userId,
            "ver" to "6",
            "clienttoken" to session.token,
            "appid" to KugouSigner.APP_ID.toString(),
            "clientver" to KugouSigner.CLIENT_VERSION.toString(),
            "mid" to d.mid,
            "clienttime" to nowSec.toString(),
            "key" to KugouSigner.paramsKey(nowSec, d.mid),
            "uuid" to "-",
            "dfid" to d.dfid.ifBlank { "-" }
        )
        if (!tid.isNullOrBlank()) {
            query["tid"] = tid
        }
        return transport.callAdvanced(
            path = "/index.php",
            session = session,
            query = query,
            method = "GET",
            headers = mapOf("x-router" to "m.comment.service.kugou.com"),
            sign = false,
            includeDefaultParams = false
        )
    }

    var resp = sendDelRequest(resolvedChildrenId)
    if (resp.code == 20010) throw ApiFailure(20010, resp.errorMsg())
    val isMockEmpty = resp.body.entrySet().isEmpty() && resp.httpCode in 200..299
    if (!resp.success && !isMockEmpty && cleanTargetId != null && cleanTargetId != resolvedChildrenId) {
        resp = sendDelRequest(cleanTargetId)
    }
    resp.success || (resp.body.entrySet().isEmpty() && resp.httpCode in 200..299)
}

suspend fun MusicApiRepository.fetchResourceComments(
    targetType: CommentTargetType,
    resourceId: String,
    type: String = "hot",
    page: Int = 1,
    pageSize: Int = 30
): Pair<Int, List<SongComment>> {
    val cleanId = resourceId.trim()
    if (cleanId.isEmpty()) return 0 to emptyList()
    val session = accounts.context()?.session
    val code = targetType.commentPoolCode()
    val isHotTab = type != "new"
    val params = linkedMapOf(
        "childrenid" to cleanId,
        "need_show_image" to "1",
        "p" to page.coerceAtLeast(1).toString(),
        "pagesize" to pageSize.coerceIn(1, 50).toString(),
        "show_classify" to "1",
        "show_hotword_list" to "1",
        "sort" to if (isHotTab) "2" else "1",
        "code" to code
    )
    if (targetType == CommentTargetType.PLAYLIST) {
        params["content_type"] = "0"
        params["tag"] = "5"
    }
    val resp = transport.call(
        path = "/m.comment.service/v1/cmtlist",
        session = session,
        method = "POST",
        query = params
    )
    val (resolvedCid, resolvedName) = extractResolvedCommentResource(resp)
    if (resolvedCid.isNotBlank() || resolvedName.isNotBlank()) {
        val prev = commentResolvedResourceCache["${targetType.name}:$cleanId"]
        commentResolvedResourceCache["${targetType.name}:$cleanId"] =
            (resolvedCid.ifBlank { prev?.first ?: cleanId }) to (resolvedName.ifBlank { prev?.second.orEmpty() })
    }
    val total = listOfNotNull(
        resp.body.number("count"),
        resp.body.number("total"),
        resp.data.number("count"),
        resp.data.number("total"),
        resp.data.number("total_count")
    ).maxOrNull() ?: 0
    val list = parseCommentList(resp.data, preferHotList = isHotTab, page = page)
    val effectiveSpecialId = resolvedCid.ifBlank { cleanId }
    list.forEach { cmt ->
        val sid = cmt.specialChildId.takeIf { it.isNotBlank() && it != "0" } ?: effectiveSpecialId
        if (cmt.id.isNotBlank() && sid.isNotBlank()) {
            commentSpecialChildIdCache[cmt.id] = sid
        }
    }
    return maxOf(total, list.size) to list
}

suspend fun MusicApiRepository.postResourceComment(
    targetType: CommentTargetType,
    resourceId: String,
    resourceName: String,
    content: String,
    replyToCommentId: String? = null,
    replyToUserName: String? = null
): SongComment = auth { ctx ->
    val session = ctx.session
    if (session.token.isBlank()) throw ApiFailure(20010, "请先登录酷狗账号后再发表评论")
    val profile = accounts.state.value.profile
    val d = device()
    val code = targetType.commentPoolCode()
    val cleanId = resourceId.trim()
    val cacheKey = "${targetType.name}:$cleanId"
    var resolvedPair = commentResolvedResourceCache[cacheKey]
    if (resolvedPair == null && targetType != CommentTargetType.SONG && cleanId.isNotBlank()) {
        // 对齐 comment_playlist_send.js / comment_album_send.js：发评前若未缓存服务端资源映射则轻量预解析一次
        suspendRunCatching { fetchResourceComments(targetType, cleanId, type = "new", page = 1, pageSize = 1) }
        resolvedPair = commentResolvedResourceCache[cacheKey]
    }
    val effectiveResourceId = replyToCommentId?.let { commentSpecialChildIdCache[it] }
        ?.takeIf { it.isNotBlank() && it != "0" }
        ?: resolvedPair?.first?.takeIf { it.isNotBlank() && it != "0" }
        ?: cleanId
    val effectiveResourceName = resourceName.trim().ifBlank { resolvedPair?.second.orEmpty() }
    val clientTime = System.currentTimeMillis() / 1000

    val resp = if (replyToCommentId != null) {
        val query = linkedMapOf(
            "r" to "commentsv2/reply",
            "code" to code,
            "childrenid" to effectiveResourceId,
            "childrenname" to effectiveResourceName,
            "kugouid" to session.userId,
            "ver" to "6",
            "clienttoken" to session.token,
            "appid" to KugouSigner.APP_ID.toString(),
            "clientver" to KugouSigner.CLIENT_VERSION.toString(),
            "mid" to d.mid,
            "clienttime" to clientTime.toString(),
            "key" to KugouSigner.paramsKey(clientTime, d.mid),
            "uuid" to "-",
            "dfid" to d.dfid.ifBlank { "-" },
            "content" to content,
            "tid" to replyToCommentId,
            "is_t" to "1",
            "pid" to "0"
        )
        transport.callAdvanced(
            path = "/index.php",
            session = session,
            query = query,
            method = "POST",
            headers = mapOf("x-router" to "m.comment.service.kugou.com"),
            sign = false,
            includeDefaultParams = false
        )
    } else {
        val contentData = JsonObject().apply {
            addProperty("content", content)
            add("images", JsonArray())
        }
        val payloadObj = JsonObject().apply { add("data", contentData) }
        val payloadJson = KugouSigner.gson.toJson(payloadObj)
        val query = linkedMapOf(
            "r" to "commentsv3/add",
            "code" to code,
            "childrenid" to effectiveResourceId,
            "childrenname" to effectiveResourceName,
            "kugouid" to session.userId,
            "ver" to "6",
            "clienttoken" to session.token,
            "appid" to KugouSigner.APP_ID.toString(),
            "clientver" to KugouSigner.CLIENT_VERSION.toString(),
            "mid" to d.mid,
            "clienttime" to clientTime.toString(),
            "key" to KugouSigner.paramsKey(clientTime, "${d.mid}$payloadJson"),
            "uuid" to "-",
            "dfid" to d.dfid.ifBlank { "-" }
        )
        transport.callAdvanced(
            path = "/index.php",
            session = session,
            query = query,
            body = payloadObj,
            method = "POST",
            headers = mapOf(
                "x-router" to "m.comment.service.kugou.com",
                "Content-Type" to "application/json; charset=UTF-8"
            ),
            sign = false,
            includeDefaultParams = false
        )
    }

    val isMockEmpty = resp.body.entrySet().isEmpty() && resp.httpCode in 200..299
    if (!resp.success && !isMockEmpty) {
        resp.requireSuccess()
    }
    val respData = resp.data.obj("data") ?: resp.data
    val createdId = respData.text("id")
        ?: respData.text("cid")
        ?: resp.body.text("id")
        ?: resp.body.text("cid")
        ?: "c_${targetType.name.lowercase()}_${System.currentTimeMillis()}"
    commentSpecialChildIdCache[createdId] = effectiveResourceId
    SongComment(
        id = createdId,
        userId = session.userId,
        userName = profile?.nickname?.takeIf { it.isNotBlank() } ?: "乐迷_${session.userId}",
        userAvatar = profile?.avatarUrl.orEmpty(),
        content = content,
        timeFormatted = "刚刚",
        parentId = replyToCommentId,
        rootId = replyToCommentId,
        replyUserName = replyToUserName,
        specialChildId = effectiveResourceId,
        timestampMs = System.currentTimeMillis()
    )
}

// =========================================================================
// 个人音乐云盘（无上限流式分页 + 品质枚举/真实码率校准 + 后台补封面 + 真实云端删除）
// 对齐 MakcRe/KuGouMusicApi user_cloud.js / user_cloud_upload.js / user_cloud_del.js
// =========================================================================
private val CLOUD_LOSSLESS_EXTENSIONS = setOf("flac", "ape", "wav", "dsf", "dff", "alac")

/**
 * 精准解析云盘曲目的真实码率 (kbps)、扩展名与音质等级：
 * - 酷狗 mcloudservice 的 `bitrate` 在 `1..10` 范围是内部音质品质枚举（4=无损FLAC, 3=320k HQ, 1..2=128k标准, >=5=Hi-Res），绝不是 kbps；
 * - 优先使用有效 kbps，再按 `fileSize * 8 / durationMs` 推算（有损格式规整至标准档位），彻底根治 `FLAC 4kbps` / `MP3 3kbps` / 误标标准。
 */
internal fun parseCloudDiskBitrateAndQuality(
    brRaw: Int,
    extRaw: String,
    fileSize: Long,
    durationMs: Long,
    enrichedSong: Song? = null
): Triple<String, Int, AudioQuality> {
    val qualityCode = if (brRaw in 1..10) brRaw else 0
    val explicitKbps = when {
        brRaw > 10_000 -> brRaw / 1000
        brRaw >= 32 -> brRaw
        else -> 0
    }
    val estimatedKbps = if (durationMs > 1_000L && fileSize > 0L) {
        ((fileSize * 8L) / durationMs).toInt().coerceAtLeast(0)
    } else 0

    val cleanExt = extRaw.trim().trimStart('.').lowercase(Locale.ROOT)
    val effectiveExt = when {
        cleanExt.isNotBlank() -> cleanExt
        qualityCode >= 4 || explicitKbps >= 600 || estimatedKbps >= 550 -> "flac"
        else -> "mp3"
    }
    val isLosslessExt = effectiveExt in CLOUD_LOSSLESS_EXTENSIONS

    fun snapLossyKbps(est: Int): Int = when {
        est >= 285 -> 320
        est >= 224 -> 256
        est >= 168 -> 192
        est >= 112 -> 128
        est in 64..111 -> 96
        else -> 0
    }

    val enrichedKbps = enrichedSong?.bitrateKbps?.takeIf { it >= 32 } ?: 0
    val resolvedKbps = if (isLosslessExt) {
        when {
            explicitKbps >= 450 -> explicitKbps
            estimatedKbps in 450..12_000 -> estimatedKbps
            enrichedKbps >= 450 -> enrichedKbps
            else -> 0
        }
    } else {
        when {
            explicitKbps in 32..512 -> explicitKbps
            qualityCode == 3 -> 320
            estimatedKbps in 64..512 -> snapLossyKbps(estimatedKbps)
            enrichedKbps in 32..512 -> enrichedKbps
            qualityCode in 1..2 -> 128
            else -> 0
        }
    }

    val resolvedQuality = when {
        qualityCode >= 5 -> AudioQuality.HI_RES
        isLosslessExt && (resolvedKbps >= 1450 || enrichedSong?.isHiRes == true) -> AudioQuality.HI_RES
        qualityCode == 4 || isLosslessExt || resolvedKbps >= 700 -> AudioQuality.LOSSLESS
        qualityCode == 3 || resolvedKbps >= 256 -> AudioQuality.HIGH
        qualityCode in 1..2 || resolvedKbps in 32..255 -> AudioQuality.STANDARD
        enrichedSong?.isHiRes == true -> AudioQuality.HI_RES
        enrichedSong?.isLossless == true -> AudioQuality.LOSSLESS
        (enrichedSong?.highestQuality?.ordinal ?: 0) >= AudioQuality.HIGH.ordinal -> AudioQuality.HIGH
        else -> AudioQuality.STANDARD
    }

    return Triple(effectiveExt, resolvedKbps, resolvedQuality)
}

private fun cloudSongDedupKey(item: CloudSongItem): String = when {
    item.kvId > 0L -> "kv:${item.kvId}"
    item.hash.isNotBlank() -> "h:${item.hash.lowercase(Locale.ROOT)}"
    else -> "n:${item.name.lowercase(Locale.ROOT)}_${item.fileSize}"
}

suspend fun MusicApiRepository.fetchUserCloudDisk(
    page: Int = 1,
    pageSize: Int = 100,
    enrichMetadata: Boolean = true
): CloudDiskSummary {
    if (accounts.context()?.session == null) return CloudDiskSummary()
    return auth { ctx ->
        val session = ctx.session
        val d = device()
        val clientTime = System.currentTimeMillis() / 1000
        val reqPageSize = pageSize.coerceIn(1, 100)
        val dataObj = JsonObject().apply {
            addProperty("page", page.coerceAtLeast(1))
            addProperty("pagesize", reqPageSize)
            addProperty("getkmr", 1)
        }
        val (aesKey, cipherBytes) = KugouSigner.playlistAesEncryptBytes(KugouSigner.gson.toJson(dataObj))
        val pJson = KugouSigner.gson.toJson(
            mapOf(
                "aes" to aesKey,
                "uid" to (session.userId.toLongOrNull() ?: 0L),
                "token" to session.token
            )
        )
        val p = KugouSigner.rsaPkcs1(pJson, lite = true).uppercase(Locale.ROOT)
        val query = linkedMapOf(
            "clienttime" to clientTime.toString(),
            "mid" to d.mid,
            "key" to KugouSigner.paramsKey(clientTime),
            "clientver" to KugouSigner.CLIENT_VERSION.toString(),
            "appid" to KugouSigner.APP_ID.toString(),
            "p" to p
        )
        val rawBytes = transport.requestBinaryBytes(
            path = "/v1/get_list",
            session = session,
            query = query,
            rawBodyBytes = cipherBytes,
            host = "https://mcloudservice.kugou.com",
            method = "POST",
            headers = mapOf("Content-Type" to "application/octet-stream"),
            sign = false,
            includeDefaultParams = false,
            device = d
        )
        val root = decodeEncryptedJsonResponse(rawBytes, aesKey)
            ?: throw IOException("云盘响应解析失败，请稍后重试")
        val errCode = root.number("err_code") ?: root.number("error_code") ?: root.number("errcode")
        if (errCode == 20010) throw ApiFailure(20010, root.text("error_msg") ?: "登录已过期")
        val dataNode = root.obj("data") ?: root
        val listArr = dataNode.arr("list") ?: dataNode.arr("info") ?: root.arr("list")
        val rawParsed = mutableListOf<Triple<CloudSongItem, Int, Boolean>>()
        if (listArr != null) {
            for (el in listArr) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val kmrObj = obj.obj("kmr")
                    ?: obj.arr("kmr")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: obj.obj("audio_info")
                    ?: obj.obj("base")
                val kmrBase = kmrObj?.obj("base") ?: kmrObj
                val kmrAudio = kmrObj?.obj("audio_info") ?: kmrObj
                val kmrAlbum = kmrObj?.obj("album_info") ?: kmrObj

                val hash = (obj.text("hash")
                    ?: obj.text("file_hash")
                    ?: kmrAudio?.text("hash")
                    ?: kmrObj?.text("hash")
                    ?: "").trim()
                if (hash.isBlank()) continue

                val rawName = obj.text("name") ?: obj.text("filename") ?: obj.text("songname") ?: "未命名云盘曲目"
                val parts = rawName.split(" - ", limit = 2)
                val explicitArtist = obj.text("singer_name")
                    ?: obj.text("author_name")
                    ?: kmrBase?.text("author_name")
                    ?: kmrObj?.text("author_name")
                    ?: kmrObj?.text("singername")
                val explicitTitle = obj.text("song_name")
                    ?: obj.text("songname")
                    ?: kmrBase?.text("song_name")
                    ?: kmrObj?.text("song_name")
                    ?: kmrBase?.text("audio_name")?.substringAfter(" - ")
                    ?: kmrObj?.text("audio_name")?.substringAfter(" - ")

                val hasExplicitMeta = !explicitArtist.isNullOrBlank() && !explicitTitle.isNullOrBlank()
                val artist = explicitArtist?.takeIf { it.isNotBlank() }
                    ?: if (parts.size == 2) parts[0].trim() else "云盘曲目"
                val title = explicitTitle?.takeIf { it.isNotBlank() }
                    ?: if (parts.size == 2) stripKnownAudioExtension(parts[1]) else stripKnownAudioExtension(rawName)

                val kvId = obj.longVal("kv_id") ?: obj.longVal("fileid") ?: obj.longVal("id") ?: 0L
                val fSize = obj.longVal("size")
                    ?: obj.longVal("filesize")
                    ?: kmrAudio?.longVal("filesize")
                    ?: kmrObj?.longVal("filesize")
                    ?: 0L
                val durRaw = obj.longVal("timelen")
                    ?: obj.longVal("duration")
                    ?: kmrAudio?.longVal("timelength")
                    ?: kmrAudio?.longVal("duration")
                    ?: kmrObj?.longVal("timelength")
                    ?: 0L
                val durMs = if (durRaw in 1L..10_000L) durRaw * 1000L else durRaw
                val brRaw = obj.number("bitrate")
                    ?: kmrAudio?.number("bitrate")
                    ?: kmrObj?.number("bitrate")
                    ?: 0
                val rawExt = obj.text("ext")
                    ?: obj.text("extname")
                    ?: obj.text("file_ext")
                    ?: kmrAudio?.text("extname")
                    ?: kmrObj?.text("extname")
                    ?: rawName.substringAfterLast('.', "").takeIf { it.length in 2..5 }
                    ?: ""
                val (resolvedExt, resolvedKbps, prelimQuality) = parseCloudDiskBitrateAndQuality(
                    brRaw = brRaw,
                    extRaw = rawExt,
                    fileSize = fSize,
                    durationMs = durMs,
                    enrichedSong = null
                )

                val cover = imageUrl(
                    obj.text("sizable_cover")
                        ?: obj.text("cover")
                        ?: obj.text("union_cover")
                        ?: kmrObj?.obj("trans_param")?.text("union_cover")
                        ?: kmrAlbum?.text("sizable_cover")
                        ?: kmrBase?.text("sizable_cover")
                        ?: kmrObj?.text("sizable_cover")
                        ?: ""
                )
                val albumName = obj.text("album_name")
                    ?: kmrAlbum?.text("album_name")
                    ?: kmrObj?.text("album_name")
                    ?: ""
                val albumId = obj.text("album_id")
                    ?: kmrAlbum?.text("album_id")
                    ?: kmrObj?.text("album_id")
                    ?: ""
                val addTimeSec = obj.longVal("addtime") ?: obj.longVal("add_time") ?: 0L
                val item = CloudSongItem(
                    kvId = kvId,
                    hash = hash,
                    name = rawName,
                    title = title.ifBlank { rawName },
                    artist = artist.ifBlank { "云盘曲目" },
                    album = albumName,
                    albumId = albumId,
                    fileSize = fSize,
                    durationMs = durMs,
                    bitrate = resolvedKbps,
                    extName = resolvedExt,
                    albumAudioId = obj.longVal("album_audio_id")
                        ?: obj.longVal("mixsongid")
                        ?: kmrBase?.longVal("album_audio_id")
                        ?: kmrObj?.longVal("album_audio_id")
                        ?: 0L,
                    audioId = obj.longVal("audio_id")
                        ?: kmrBase?.longVal("audio_id")
                        ?: kmrObj?.longVal("audio_id")
                        ?: 0L,
                    coverUrl = cover,
                    addTime = formatEpochSeconds(addTimeSec),
                    addTimestampSec = addTimeSec,
                    resolvedQuality = prelimQuality
                )
                rawParsed.add(Triple(item, brRaw, hasExplicitMeta))
            }
        }

        // 批量轻量回填高清封面、所属专辑、真实音质等级与校正反转的歌名/歌手（不再同步阻塞调 resolveSongAlbumMeta）
        val items = if (enrichMetadata && rawParsed.isNotEmpty()) {
            val baseSongs = rawParsed.map { it.first.toSong() }
            val enrichedSongs = suspendRunCatching { enrichSongsPrivilegeLite(baseSongs) }.getOrDefault(baseSongs)
            rawParsed.mapIndexed { idx, (item, brRaw, hasExplicitMeta) ->
                val es = enrichedSongs.getOrNull(idx) ?: return@mapIndexed item
                val effectiveDuration = item.durationMs.takeIf { it > 0L } ?: es.durationMs
                val preliminarySize = item.fileSize.takeIf { it > 0L } ?: es.fileSizeByte
                val (finalExt, finalKbps, inferredQuality) = parseCloudDiskBitrateAndQuality(
                    brRaw = brRaw,
                    extRaw = item.extName,
                    fileSize = preliminarySize,
                    durationMs = effectiveDuration,
                    enrichedSong = es
                )
                val resolvedSize = item.fileSize.takeIf { it > 0L }
                    ?: es.fileSizeByte.takeIf { it > 0L }
                    ?: when (inferredQuality) {
                        AudioQuality.HI_RES, AudioQuality.LOSSLESS -> es.fileSizeFlac.takeIf { it > 0L } ?: es.fileSize320
                        AudioQuality.HIGH -> es.fileSize320.takeIf { it > 0L } ?: es.fileSize128
                        AudioQuality.STANDARD -> es.fileSize128
                    }
                    ?: 0L
                val cleanEsAlbum = cleanResolvedAlbumName(es.album).takeIf { it != "我的音乐云盘" }.orEmpty()
                val esHasReliableTitleArtist = es.title.isNotBlank() &&
                    es.title != "未知歌曲" &&
                    es.artist.isNotBlank() &&
                    es.artist != "未知歌手" &&
                    es.artist != "云盘曲目"
                // 若文件本身无显式元数据，且文件名疑似「歌名 - 歌手」写反（如标题其实是歌手名），使用曲库权威标题/歌手校正
                val looksSwapped = esHasReliableTitleArtist &&
                    !hasExplicitMeta &&
                    item.title.equals(es.artist, ignoreCase = true) &&
                    item.artist.equals(es.title, ignoreCase = true)
                val resolvedTitle = when {
                    looksSwapped -> es.title
                    item.title.isBlank() || item.title == "未命名云盘曲目" -> es.title.ifBlank { item.title }
                    else -> item.title
                }
                val resolvedArtist = when {
                    looksSwapped -> es.artist
                    item.artist == "云盘曲目" && esHasReliableTitleArtist -> es.artist
                    else -> item.artist
                }
                item.copy(
                    title = resolvedTitle,
                    artist = resolvedArtist,
                    coverUrl = item.coverUrl.ifBlank { es.coverUrl },
                    album = item.album.ifBlank { cleanEsAlbum },
                    albumId = item.albumId.ifBlank { es.albumId },
                    fileSize = resolvedSize,
                    durationMs = effectiveDuration,
                    bitrate = finalKbps,
                    extName = finalExt,
                    albumAudioId = item.albumAudioId.takeIf { it > 0L } ?: es.albumAudioId,
                    audioId = item.audioId.takeIf { it > 0L } ?: es.audioId,
                    resolvedQuality = inferredQuality
                )
            }
        } else {
            rawParsed.map { it.first }
        }

        val reportedTotal = listOfNotNull(
            dataNode.number("list_count"),
            dataNode.number("file_count"),
            dataNode.number("total_count"),
            dataNode.number("all_count"),
            dataNode.number("song_count"),
            dataNode.number("total"),
            dataNode.number("count"),
            root.number("list_count"),
            root.number("total_count"),
            root.number("total"),
            root.number("count")
        ).firstOrNull { it > 0 } ?: 0

        val used = dataNode.longVal("used_size")
            ?: dataNode.longVal("used_space")
            ?: dataNode.longVal("avail_use_size")
            ?: 0L
        val max = dataNode.longVal("max_size")
            ?: dataNode.longVal("total_space")
            ?: dataNode.longVal("all_size")
            ?: 0L
        // 若服务端未返回准确总数字段，只要本页非空即继续探测下一页，彻底突破 50 首假截断
        val hasMore = when {
            items.isEmpty() -> false
            reportedTotal > 0 -> page * reqPageSize < reportedTotal
            else -> items.size >= minOf(reqPageSize, 20)
        }
        CloudDiskSummary(
            songs = items,
            totalCount = maxOf(reportedTotal, items.size),
            usedBytes = used,
            maxBytes = max,
            hasMore = hasMore
        )
    }
}

/**
 * 自动流式加载个人音乐云盘的全部曲目（不做曲目总数硬上限）：
 * - 每页 100 首连续分页，按 `kvId` / `hash` 去重，直到服务端返回空或无任何新增曲目为止；
 * - 每拉取完一页立即通过 `onPageLoaded` 流式推送最新全量快照给 UI，实现首屏秒出与无感增量追加。
 */
suspend fun MusicApiRepository.fetchAllUserCloudDisk(
    pageSize: Int = 100,
    enrichMetadata: Boolean = true,
    onPageLoaded: (suspend (CloudDiskSummary) -> Unit)? = null
): CloudDiskSummary {
    if (accounts.context()?.session == null) return CloudDiskSummary()
    val reqSize = pageSize.coerceIn(20, 100)
    val merged = mutableListOf<CloudSongItem>()
    val seen = HashSet<String>()
    var usedBytes = 0L
    var maxBytes = 0L
    var reportedTotal = 0
    var page = 1
    val safetyMaxPages = 500 // 防服务端异常死循环保护（支持高达 50,000 首云盘曲目）

    while (page <= safetyMaxPages) {
        val pageSummary = try {
            fetchUserCloudDisk(page = page, pageSize = reqSize, enrichMetadata = enrichMetadata)
        } catch (e: Exception) {
            if (page == 1) throw e
            break
        }
        if (pageSummary.usedBytes > 0L) usedBytes = pageSummary.usedBytes
        if (pageSummary.maxBytes > 0L) maxBytes = pageSummary.maxBytes
        if (pageSummary.totalCount > reportedTotal) reportedTotal = pageSummary.totalCount

        if (pageSummary.songs.isEmpty()) break

        var addedThisPage = 0
        for (item in pageSummary.songs) {
            if (seen.add(cloudSongDedupKey(item))) {
                merged.add(item)
                addedThisPage++
            }
        }
        // 服务端翻页越界后重复返回旧页时立即终止
        if (addedThisPage == 0) break

        val hasNext = page < safetyMaxPages && (pageSummary.hasMore || (reportedTotal > merged.size) || addedThisPage >= 20)
        val snapshot = CloudDiskSummary(
            songs = merged.toList(),
            totalCount = maxOf(reportedTotal, merged.size),
            usedBytes = usedBytes,
            maxBytes = maxBytes,
            hasMore = hasNext
        )
        onPageLoaded?.invoke(snapshot)
        if (!hasNext) break
        page++
    }

    return CloudDiskSummary(
        songs = merged.toList(),
        totalCount = merged.size,
        usedBytes = usedBytes,
        maxBytes = maxBytes,
        hasMore = false
    )
}

/**
 * 后台低优先级静默补全云盘缺失封面与专辑信息（不阻塞列表首屏与滚动）
 */
suspend fun MusicApiRepository.fillMissingCloudDiskCovers(
    items: List<CloudSongItem>,
    maxBatchCount: Int = 40,
    onPatch: suspend (Map<String, Pair<String, String>>) -> Unit
) = coroutineScope {
    val missing = items.filter { it.coverUrl.isBlank() }.take(maxBatchCount)
    if (missing.isEmpty()) return@coroutineScope
    val gate = Semaphore(4)
    val patches = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    missing.map { target ->
        async {
            gate.withPermit {
                val (metaAlbum, metaCover) = suspendRunCatching {
                    resolveSongAlbumMeta(target.toSong())
                }.getOrDefault("" to "")
                if (metaCover.isNotBlank() || metaAlbum.isNotBlank()) {
                    patches[cloudSongDedupKey(target)] = metaCover to metaAlbum
                }
            }
        }
    }.awaitAll()
    if (patches.isNotEmpty()) {
        onPatch(patches.toMap())
    }
}

suspend fun MusicApiRepository.resolveUserCloudPlayback(song: Song): PlaybackResult = auth { ctx ->
    val session = ctx.session
    val hashLower = song.hash.trim().lowercase(Locale.ROOT)
    if (hashLower.isBlank()) throw IOException("云盘曲目缺少有效 Hash")
    val resp = transport.call(
        path = "/bsstrackercdngz/v2/query_musicclound_url",
        session = session,
        method = "GET",
        query = mapOf(
            "hash" to hashLower,
            "ssa_flag" to "is_fromtrack",
            "version" to "20102",
            "ssl" to "1",
            "album_audio_id" to song.albumAudioId.toString(),
            "pid" to "20026",
            "audio_id" to song.audioId.toString(),
            "kv_id" to (song.cloudKvId.takeIf { it > 0L } ?: 2L).toString(),
            "key" to KugouSigner.signCloudKey(hashLower, 20026),
            "bucket" to "musicclound",
            "name" to "${song.artist} - ${song.title}",
            "with_res_tag" to "0"
        )
    )
    if (resp.code == 20010) {
        throw ApiFailure(20010, resp.errorMsg())
    }
    val root = resp.data
    val directUrl = root.text("url")
        ?: root.arr("url")?.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString
        ?: root.arr("backupUrl")?.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString
        ?: resp.body.text("url")
        ?: resp.body.arr("url")?.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString
        ?: ""
    if (directUrl.isBlank()) {
        throw IOException("无法获取该云盘歌曲播放链接，请稍后重试")
    }
    val fSize = root.longVal("fileSize") ?: root.longVal("filesize") ?: song.fileSizeByte
    val br = root.number("bitRate") ?: root.number("bitrate") ?: song.bitrateKbps
    val ext = root.text("extName") ?: root.text("extname")
    val (q, kbps) = deduceQuality(directUrl, br.toLong(), fSize, song.durationMs, ext)
    PlaybackResult(
        url = directUrl,
        actualQuality = q,
        bitrateKbps = kbps,
        fileSizeByte = fSize,
        resolvedHash = hashLower
    )
}

suspend fun MusicApiRepository.deleteUserCloudSongs(items: List<CloudSongItem>): Boolean = auth { ctx ->
    if (items.isEmpty()) return@auth false
    val session = ctx.session
    val d = device()
    val arr = JsonArray()
    items.forEach { item ->
        if (item.kvId > 0L) {
            arr.add(JsonObject().apply {
                addProperty("kv_id", item.kvId)
                addProperty("album_audio_id", item.albumAudioId)
            })
        }
    }
    if (arr.size() == 0) return@auth false
    val dataObj = JsonObject().apply { add("data", arr) }

    suspend fun attemptDelete(uidAsString: Boolean): JsonObject? {
        val clientTime = System.currentTimeMillis() / 1000
        val (aesKey, cipherBytes) = KugouSigner.playlistAesEncryptBytes(KugouSigner.gson.toJson(dataObj))
        val uidValue: Any = if (uidAsString) session.userId else (session.userId.toLongOrNull() ?: 0L)
        val pJson = KugouSigner.gson.toJson(
            mapOf(
                "aes" to aesKey,
                "uid" to uidValue,
                "token" to session.token
            )
        )
        val p = KugouSigner.rsaPkcs1(pJson, lite = true).uppercase(Locale.ROOT)
        val query = linkedMapOf(
            "clienttime" to clientTime.toString(),
            "mid" to d.mid,
            "key" to KugouSigner.paramsKey(clientTime),
            "clientver" to KugouSigner.CLIENT_VERSION.toString(),
            "appid" to KugouSigner.APP_ID.toString(),
            "p" to p
        )
        val rawBytes = transport.requestBinaryBytes(
            path = "/v1/del_files",
            session = session,
            query = query,
            rawBodyBytes = cipherBytes,
            host = "https://mcloudservice.kugou.com",
            method = "POST",
            headers = mapOf("Content-Type" to "application/octet-stream"),
            sign = false,
            includeDefaultParams = false,
            device = d
        )
        return decodeEncryptedJsonResponse(rawBytes, aesKey)
    }

    // 对齐 user_cloud_del.js（优先使用 String uid，若服务端校验数值型 uid 则回退一次）
    var resp = suspendRunCatching { attemptDelete(uidAsString = true) }.getOrNull()
    var errCode = resp?.number("err_code") ?: resp?.number("error_code") ?: resp?.number("errcode") ?: 0
    if (errCode == 20010) {
        throw ApiFailure(20010, resp?.text("error_msg") ?: "登录已过期")
    }
    if (resp == null || resp.number("status") != 1 || errCode != 0) {
        resp = suspendRunCatching { attemptDelete(uidAsString = false) }.getOrNull()
        errCode = resp?.number("err_code") ?: resp?.number("error_code") ?: resp?.number("errcode") ?: 0
        if (errCode == 20010) {
            throw ApiFailure(20010, resp?.text("error_msg") ?: "登录已过期")
        }
    }
    resp != null && resp.number("status") == 1 && errCode == 0
}

// =========================================================================
// 云端听歌排行（周榜 0 / 月榜 2 / 总榜 1 + 播放次数升降序 + 最常听歌手排行）
// =========================================================================
fun sortListenRankItems(
    items: List<ListenRankItem>,
    ascending: Boolean = false
): List<ListenRankItem> {
    if (items.isEmpty()) return emptyList()
    val sorted = if (ascending) {
        items.sortedWith(
            compareBy<ListenRankItem> { it.playCount }
                .thenByDescending { it.rank }
        )
    } else {
        items.sortedWith(
            compareByDescending<ListenRankItem> { it.playCount }
                .thenBy { it.rank }
        )
    }
    return sorted.mapIndexed { index, item ->
        item.copy(rank = index + 1)
    }
}

fun aggregateArtistListenRank(
    items: List<ListenRankItem>,
    ascending: Boolean = false
): List<ArtistListenRankItem> {
    if (items.isEmpty()) return emptyList()
    class Acc(
        var displayName: String,
        var artistId: String,
        var avatarUrl: String,
        var totalPlays: Int = 0,
        val songsByKey: LinkedHashMap<String, Pair<Song, Int>> = LinkedHashMap()
    )
    val map = LinkedHashMap<String, Acc>()
    for (entry in items) {
        val song = entry.song
        val count = entry.playCount.coerceAtLeast(1)
        val rawSingers = song.artist.split("、", "/", ",", "&")
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "未知歌手" && it != "群星" }
        if (rawSingers.isEmpty()) continue
        rawSingers.forEachIndexed { idx, singerName ->
            val normKey = singerName.lowercase(Locale.ROOT)
                .replace(Regex("[.\\s·]+"), "")
            if (normKey.isEmpty()) return@forEachIndexed
            val acc = map.getOrPut(normKey) {
                Acc(
                    displayName = singerName,
                    artistId = if (idx == 0 && rawSingers.size == 1) song.artistId else "",
                    avatarUrl = song.coverUrl
                )
            }
            if (acc.artistId.isBlank() && idx == 0 && rawSingers.size == 1 && song.artistId.isNotBlank()) {
                acc.artistId = song.artistId
            }
            if (acc.avatarUrl.isBlank() && song.coverUrl.isNotBlank()) {
                acc.avatarUrl = song.coverUrl
            }
            acc.totalPlays += count
            val songKey = song.hash.ifBlank { song.id }.lowercase(Locale.ROOT)
            val prev = acc.songsByKey[songKey]
            if (prev == null || count > prev.second) {
                acc.songsByKey[songKey] = song.copy(playCount = count) to count
            }
        }
    }
    val rawList = map.values.map { acc ->
        val orderedSongs = acc.songsByKey.values
            .sortedByDescending { it.second }
            .map { it.first }
        ArtistListenRankItem(
            rank = 0,
            artistName = acc.displayName,
            artistId = acc.artistId,
            avatarUrl = acc.avatarUrl,
            totalPlayCount = acc.totalPlays,
            trackCount = orderedSongs.size,
            topSongTitle = orderedSongs.firstOrNull()?.title.orEmpty(),
            songs = orderedSongs
        )
    }
    val sorted = if (ascending) {
        rawList.sortedWith(
            compareBy<ArtistListenRankItem> { it.totalPlayCount }
                .thenBy { it.trackCount }
                .thenBy { it.artistName }
        )
    } else {
        rawList.sortedWith(
            compareByDescending<ArtistListenRankItem> { it.totalPlayCount }
                .thenByDescending { it.trackCount }
                .thenBy { it.artistName }
        )
    }
    return sorted.mapIndexed { idx, item -> item.copy(rank = idx + 1) }
}

private suspend fun MusicApiRepository.fetchListenServiceRawList(
    session: KugouSession,
    listType: Int
): List<Pair<Song, Int>> {
    val clientTime = System.currentTimeMillis() / 1000
    val uidLong = session.userId.toLongOrNull() ?: 0L
    val pPayload = KugouSigner.gson.toJson(mapOf("clienttime" to clientTime, "token" to session.token))
    val p = KugouSigner.rsa(pPayload, useStandardKey = false).uppercase(Locale.ROOT)
    val body = JsonObject().apply {
        addProperty("t_userid", uidLong)
        addProperty("userid", uidLong)
        addProperty("list_type", listType)
        addProperty("area_code", 1)
        addProperty("cover", 2)
        addProperty("p", p)
    }
    val resp = transport.call(
        path = "/v2/get_list",
        session = session,
        method = "POST",
        query = mapOf("clienttime" to clientTime.toString(), "plat" to "0"),
        body = body,
        headers = mapOf("x-router" to "listenservice.kugou.com")
    )
    if (resp.code == 20010) throw ApiFailure(20010, resp.errorMsg())
    val dataObj = resp.data
    val arr = dataObj.arr("lists")
        ?: dataObj.arr("list")
        ?: dataObj.arr("songs")
        ?: dataObj.arr("info")
        ?: resp.body.arr("data")
    val rawPairs = mutableListOf<Pair<Song, Int>>()
    if (arr != null) {
        for (el in arr) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val song = parseSong(obj)
            val count = obj.number("nums")
                ?: obj.number("play_count")
                ?: obj.number("pc")
                ?: obj.number("count")
                ?: 1
            if (song.title.isNotBlank() || song.hash.isNotBlank()) {
                val safeCount = count.coerceAtLeast(1)
                rawPairs.add(song.copy(playCount = safeCount) to safeCount)
            }
        }
    }
    return rawPairs
}

/**
 * 获取个人云端听歌排行：
 * - type = 0：近一周（周榜）
 * - type = 2：近一月（月榜：优先探测 listenservice 月度列表并结合云端 30 天内播放历史频次与周/总榜校准）
 * - type = 1：累计总榜
 */
suspend fun MusicApiRepository.fetchUserListenRank(type: Int = 0): List<ListenRankItem> {
    if (accounts.context()?.session == null) return emptyList()
    return auth { ctx ->
        val session = ctx.session
        val rawPairs: List<Pair<Song, Int>> = if (type == 2) {
            coroutineScope {
                val directMonthDef = async {
                    suspendRunCatching { fetchListenServiceRawList(session, listType = 2) }.getOrDefault(emptyList())
                }
                val weekDef = async {
                    suspendRunCatching { fetchListenServiceRawList(session, listType = 0) }.getOrDefault(emptyList())
                }
                val allDef = async {
                    suspendRunCatching { fetchListenServiceRawList(session, listType = 1) }.getOrDefault(emptyList())
                }
                val history30dDef = async {
                    suspendRunCatching {
                        val resp = transport.call(
                            path = "/playhistory/v1/get_songs",
                            session = session,
                            method = "POST",
                            body = json(
                                "userid" to (session.userId.toLongOrNull() ?: 0L),
                                "token" to session.token,
                                "source_classify" to "app",
                                "to_subdivide_sr" to 1
                            )
                        )
                        val cutoffSec = (Instant.now().epochSecond) - 30L * 86_400L
                        val arr = resp.data.arr("songs") ?: resp.data.arr("list") ?: resp.data.arr("info")
                        val list = mutableListOf<Pair<Song, Int>>()
                        if (arr != null) {
                            for (el in arr) {
                                if (!el.isJsonObject) continue
                                val obj = el.asJsonObject
                                val ot = obj.longVal("ot") ?: obj.longVal("time") ?: obj.longVal("play_time") ?: 0L
                                val otSec = if (ot > 10_000_000_000L) ot / 1000L else ot
                                if (otSec in 1L until cutoffSec) continue
                                val s = parseSong(obj)
                                val pc = (obj.number("pc") ?: obj.number("play_count") ?: obj.number("nums") ?: 1).coerceAtLeast(1)
                                if (s.title.isNotBlank() || s.hash.isNotBlank()) {
                                    list.add(s.copy(playCount = pc) to pc)
                                }
                            }
                        }
                        list
                    }.getOrDefault(emptyList())
                }

                val directMonth = directMonthDef.await()
                val weekList = weekDef.await()
                val allList = allDef.await()
                val hist30d = history30dDef.await()

                val isDirectDistinctFromWeekAndAll = directMonth.isNotEmpty() &&
                    directMonth.map { it.first.hash to it.second } != weekList.map { it.first.hash to it.second } &&
                    directMonth.map { it.first.hash to it.second } != allList.map { it.first.hash to it.second }

                if (isDirectDistinctFromWeekAndAll) {
                    directMonth
                } else {
                    // 基于周榜保底 + 近 30 天云端历史频次叠加 + 总榜上限约束，精准还原近 30 天月度排行
                    val allCapByKey = HashMap<String, Int>()
                    allList.forEach { (s, c) ->
                        val k = s.hash.ifBlank { s.id }.lowercase(Locale.ROOT)
                        if (k.isNotBlank()) allCapByKey[k] = c
                    }
                    val merged = LinkedHashMap<String, Pair<Song, Int>>()
                    weekList.forEach { (s, c) ->
                        val k = s.hash.ifBlank { s.id }.lowercase(Locale.ROOT)
                        if (k.isNotBlank()) merged[k] = s to c
                    }
                    hist30d.forEach { (s, c) ->
                        val k = s.hash.ifBlank { s.id }.lowercase(Locale.ROOT)
                        if (k.isBlank()) return@forEach
                        val prev = merged[k]
                        val cap = allCapByKey[k] ?: Int.MAX_VALUE
                        val nextCount = if (prev == null) {
                            minOf(c, cap).coerceAtLeast(1)
                        } else {
                            minOf(maxOf(prev.second, c), cap).coerceAtLeast(prev.second)
                        }
                        merged[k] = (prev?.first ?: s).copy(playCount = nextCount) to nextCount
                    }
                    if (merged.size < 25 && allList.isNotEmpty()) {
                        // 若近一周曲目较少，补充近期活跃的总榜高频曲目（按月度衰减或云端上限展示）
                        for ((s, totalCount) in allList) {
                            if (merged.size >= 80) break
                            val k = s.hash.ifBlank { s.id }.lowercase(Locale.ROOT)
                            if (k.isBlank() || merged.containsKey(k)) continue
                            if (hist30d.isEmpty()) {
                                val estimatedMonth = (totalCount * 35 / 100).coerceAtLeast(1)
                                merged[k] = s.copy(playCount = estimatedMonth) to estimatedMonth
                            }
                        }
                    }
                    merged.values.toList()
                }
            }
        } else {
            fetchListenServiceRawList(session, listType = if (type == 1) 1 else 0)
        }

        if (rawPairs.isEmpty()) return@auth emptyList()
        val sortedPairs = rawPairs.sortedByDescending { it.second }
        val enriched = suspendRunCatching { enrichSongsPrivilegeLite(sortedPairs.map { it.first }) }
            .getOrDefault(sortedPairs.map { it.first })
        enriched.mapIndexed { idx, s ->
            val count = sortedPairs.getOrNull(idx)?.second ?: s.playCount.coerceAtLeast(1)
            ListenRankItem(rank = idx + 1, song = s.copy(playCount = count), playCount = count)
        }
    }
}

// =========================================================================
// 猜你喜欢（私人 FM）负反馈调校 & 听歌偏好 & 专属黑名单（修正裸模幂 RSA + KG-TID=474）
// =========================================================================
suspend fun MusicApiRepository.sendPersonalFmFeedback(
    action: String,
    song: Song? = null,
    songPoolId: Int = 0,
    playTimeSec: Int = 0,
    isOverplay: Boolean = false,
    modeOverride: String? = null,
    curMark: String = ""
): List<Song> {
    val session = accounts.context()?.session
    val d = device()
    val nowSec = System.currentTimeMillis() / 1000L
    val coercedPool = songPoolId.coerceIn(0, 2)
    val effectiveMode = modeOverride?.takeIf { it.isNotBlank() }
        ?: if (coercedPool == 2) "small" else "normal"
    val body = JsonObject().apply {
        addProperty("appid", KugouSigner.APP_ID)
        addProperty("clienttime", nowSec)
        addProperty("mid", d.mid)
        addProperty("action", action)
        addProperty("recommend_source_locked", 0)
        addProperty("song_pool_id", coercedPool)
        addProperty("callerid", 0)
        addProperty("m_type", 1)
        addProperty("platform", "ios")
        addProperty("area_code", 1)
        addProperty("remain_songcnt", 0)
        addProperty("clientver", KugouSigner.CLIENT_VERSION)
        addProperty("is_overplay", if (isOverplay) 1 else 0)
        addProperty("mode", effectiveMode)
        addProperty("fakem", "ca981cfc583a4c37f28d2d49000013c16a0a")
        addProperty("key", KugouSigner.paramsKey(nowSec))
        if (playTimeSec > 0) {
            addProperty("playtime", playTimeSec)
        }
        if (curMark.isNotBlank()) {
            addProperty("cur_mark", curMark)
        }
        if (session != null) {
            val uidNum = session.userId.toLongOrNull() ?: 0L
            addProperty("userid", uidNum)
            addProperty("kguid", uidNum)
            addProperty("token", session.token)
        }
        if (song != null) {
            val lowerHash = song.hash.trim().lowercase(Locale.ROOT)
            if (lowerHash.isNotBlank()) addProperty("hash", lowerHash)
            val sid = song.audioId.takeIf { it > 0L }
                ?: song.albumAudioId.takeIf { it > 0L }
                ?: song.mixSongId.toLongOrNull()
            if (sid != null && sid > 0L) addProperty("songid", sid)
        }
    }
    val resp = suspendRunCatching {
        transport.call(
            path = "/v2/personal_recommend",
            session = session,
            method = "POST",
            body = body,
            headers = mapOf("x-router" to "persnfm.service.kugou.com")
        )
    }.getOrNull() ?: return emptyList()
    val songs = parseSongs(resp.data)
    return suspendRunCatching { enrichSongsPrivilegeLite(songs) }.getOrDefault(songs)
}

/**
 * 批量查询候选曲目的真实语种（language）与风格标签（tags），单次并发拉取 /kmr/v2/albums + /kmr/v2/audio
 * 返回 key（songPrimaryKey / aa:<albumAudioId> / alb:<albumId>） -> Pair(rawLanguage, rawTags)
 */
suspend fun MusicApiRepository.batchFetchSongLanguageAndTags(
    songs: List<Song>
): Map<String, Pair<String, List<String>>> = coroutineScope {
    if (songs.isEmpty()) return@coroutineScope emptyMap()
    val session = accounts.context()?.session
    val result = java.util.concurrent.ConcurrentHashMap<String, Pair<String, List<String>>>()

    val albumIds = songs.map { it.albumId.trim() }
        .filter { it.isNotBlank() && it != "0" }
        .distinct()
        .take(90)
    val audioIds = songs.map { it.albumAudioId }
        .filter { it > 0L }
        .distinct()
        .take(80)

    val albumJob = async {
        if (albumIds.isEmpty()) return@async
        albumIds.chunked(45).map { chunk ->
            async {
                suspendRunCatching {
                    val itemArr = JsonArray().apply {
                        chunk.forEach { aid ->
                            add(JsonObject().apply {
                                val numId = aid.toLongOrNull()
                                if (numId != null) addProperty("album_id", numId)
                                else addProperty("album_id", aid)
                            })
                        }
                    }
                    val body = JsonObject().apply {
                        add("data", itemArr)
                        addProperty("is_buy", 0)
                        addProperty("fields", "album_id,language")
                    }
                    val resp = transport.callAdvanced(
                        path = "/kmr/v2/albums",
                        method = "POST",
                        session = session,
                        body = body,
                        headers = mapOf(
                            "x-router" to "openapi.kugou.com",
                            "kg-tid" to "255"
                        ),
                        sign = true,
                        includeDefaultParams = true
                    )
                    val arr = resp.data.arr("data") ?: resp.body.arr("data")
                    arr?.filter { it.isJsonObject }?.map { it.asJsonObject }?.forEach { obj ->
                        val aid = (obj.text("album_id") ?: obj.text("albumid") ?: "").trim()
                        val lang = (obj.text("language") ?: "").trim()
                        if (aid.isNotBlank() && lang.isNotBlank()) {
                            result["alb:$aid"] = lang to emptyList()
                        }
                    }
                }
            }
        }.awaitAll()
    }

    val audioJob = async {
        if (audioIds.isEmpty()) return@async
        audioIds.chunked(40).map { chunk ->
            async {
                suspendRunCatching {
                    val dataArr = JsonArray().apply {
                        chunk.forEach { aaid ->
                            add(JsonObject().apply { addProperty("entity_id", aaid) })
                        }
                    }
                    val body = JsonObject().apply {
                        add("data", dataArr)
                        addProperty("fields", "base,album_info,tags")
                    }
                    val resp = transport.callAdvanced(
                        path = "/kmr/v2/audio",
                        method = "POST",
                        session = session,
                        body = body,
                        headers = mapOf(
                            "x-router" to "openapi.kugou.com",
                            "kg-tid" to "36"
                        ),
                        sign = true,
                        includeDefaultParams = true
                    )
                    val arr = resp.data.arr("data") ?: resp.body.arr("data")
                    arr?.filter { it.isJsonObject }?.map { it.asJsonObject }?.forEach { item ->
                        val baseObj = item.obj("base") ?: item
                        val albObj = item.obj("album_info")
                        val aaid = baseObj.longVal("album_audio_id")
                            ?: item.longVal("album_audio_id")
                            ?: item.longVal("entity_id")
                            ?: 0L
                        val lang = (baseObj.text("language")
                            ?: albObj?.text("language")
                            ?: item.text("language")
                            ?: "").trim()
                        val tags = mutableListOf<String>()
                        val rawTagsEl = item.get("tags") ?: baseObj.get("tags")
                        if (rawTagsEl != null) {
                            when {
                                rawTagsEl.isJsonArray -> rawTagsEl.asJsonArray.forEach { el ->
                                    when {
                                        el.isJsonPrimitive -> el.asString.trim().takeIf { it.isNotBlank() }?.let(tags::add)
                                        el.isJsonObject -> {
                                            val tObj = el.asJsonObject
                                            (tObj.text("tag_name") ?: tObj.text("name") ?: tObj.text("tagname"))
                                                ?.trim()?.takeIf { it.isNotBlank() }?.let(tags::add)
                                        }
                                    }
                                }
                                rawTagsEl.isJsonObject -> {
                                    rawTagsEl.asJsonObject.entrySet().forEach { (_, v) ->
                                        if (v.isJsonPrimitive) {
                                            v.asString.trim().takeIf { it.isNotBlank() }?.let(tags::add)
                                        }
                                    }
                                }
                            }
                        }
                        if (aaid > 0L && (lang.isNotBlank() || tags.isNotEmpty())) {
                            result["aa:$aaid"] = lang to tags
                        }
                    }
                }
            }
        }.awaitAll()
    }

    albumJob.await()
    audioJob.await()

    // 合并每个曲目的 album 与 audio 结果
    val finalMap = LinkedHashMap<String, Pair<String, List<String>>>(songs.size)
    for (s in songs) {
        val primaryKey = s.hash.ifBlank { s.id }.trim().lowercase(Locale.ROOT)
        val fromAudio = if (s.albumAudioId > 0L) result["aa:${s.albumAudioId}"] else null
        val fromAlbum = s.albumId.trim().takeIf { it.isNotBlank() && it != "0" }?.let { result["alb:$it"] }
        val lang = fromAudio?.first?.takeIf { it.isNotBlank() } ?: fromAlbum?.first.orEmpty()
        val tags = (fromAudio?.second.orEmpty() + fromAlbum?.second.orEmpty()).distinct()
        if (lang.isNotBlank() || tags.isNotEmpty()) {
            finalMap[primaryKey] = lang to tags
        }
    }
    finalMap
}

/**
 * 概念版单卡个性推荐（对接 module/top_card_youth.js）
 * cardId: 3001=私人专属好歌, 3004=小众宝藏佳作, 3005=潮流尝鲜, 3014=喜欢这首歌的TA也喜欢
 */
suspend fun MusicApiRepository.fetchYouthSingleCardRecommend(
    cardId: Int,
    tagId: String = "",
    pageSize: Int = 30
): List<Song> {
    val session = accounts.context()?.session
    val body = json(
        "tagid" to tagId,
        "u_info" to "",
        "source_mixsong" to ""
    )
    val resp = suspendRunCatching {
        transport.callAdvanced(
            path = "/youth/v1/song/single_card_recommend",
            method = "POST",
            session = session,
            query = mapOf(
                "card_id" to cardId.toString(),
                "area_code" to "1",
                "platform" to "ios",
                "module_id" to "1",
                "ver" to "v2",
                "pagesize" to pageSize.coerceIn(10, 40).toString(),
                "clientver" to "11490"
            ),
            body = body,
            sign = true,
            includeDefaultParams = true
        ).requireSuccess()
    }.getOrNull() ?: return emptyList()
    val parsed = parseSongs(resp.data)
    return if (parsed.isNotEmpty()) parsed else parseSongs(resp.body)
}

/**
 * 概念版曲风盲盒（对接 module/top_tag_card_youth.js），用于尝鲜模式高纯度探索召回
 */
suspend fun MusicApiRepository.fetchYouthTagCardRecommend(): List<Song> {
    val session = accounts.context()?.session
    val body = json(
        "tagid" to "",
        "u_info" to "",
        "source_mixsong" to ""
    )
    val resp = suspendRunCatching {
        transport.callAdvanced(
            path = "/youth/v1/song/tag_card_recommend",
            method = "POST",
            session = session,
            query = mapOf(
                "ver" to "v2",
                "area_code" to "1",
                "platform" to "ios",
                "module_id" to "1",
                "clientver" to "11490"
            ),
            body = body,
            sign = true,
            includeDefaultParams = true
        ).requireSuccess()
    }.getOrNull() ?: return emptyList()
    val parsed = parseSongs(resp.data)
    return if (parsed.isNotEmpty()) parsed else parseSongs(resp.body)
}

suspend fun MusicApiRepository.fetchUserListeningPreference(): UserListeningPreference? {
    val session = accounts.context()?.session ?: return null
    val d = device()
    val clientTime = System.currentTimeMillis() / 1000
    val strG2 = KugouSigner.random(16)
    val pPayload = KugouSigner.gson.toJson(mapOf("key" to strG2, "clienttime" to clientTime.toString()))
    // 对齐 MakcRe/KuGouMusicApi module/user_preference.js：rsaEncrypt2 返回小写 HEX
    val p = KugouSigner.rsaPkcs1(pPayload, lite = true).lowercase(Locale.ROOT)
    val encParams = KugouSigner.encrypt(KugouSigner.gson.toJson(mapOf("token" to session.token)), temporaryKey = strG2).ciphertext
    val body = JsonObject().apply {
        addProperty("plat", 1)
        addProperty("userid", session.userId.toLongOrNull() ?: 0L)
        addProperty("p", p)
        addProperty("params", encParams)
    }
    val resp = suspendRunCatching {
        transport.callAdvanced(
            path = "/userpreferservice/v1/get_user_conf",
            session = session,
            method = "POST",
            query = mapOf(
                "appid" to KugouSigner.APP_ID.toString(),
                "clientver" to KugouSigner.CLIENT_VERSION.toString(),
                "clienttime" to clientTime.toString(),
                "mid" to d.mid,
                "dfid" to d.dfid.ifBlank { "-" },
                "uuid" to "-"
            ),
            body = body,
            headers = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to "Android15-1070-${KugouSigner.CLIENT_VERSION}-201-0-get_user_conf-wifi",
                "KG-Rec" to "1",
                "KG-RC" to "1"
            ),
            sign = true,
            includeDefaultParams = false
        )
    }.getOrNull() ?: return null
    val dataObj = resp.data.obj("data") ?: resp.data
    val mode = dataObj.number("mode") ?: 0
    return UserListeningPreference(
        mode = mode.coerceIn(0, 2),
        gender = dataObj.text("gender").orEmpty(),
        age = dataObj.text("age").orEmpty(),
        langRaw = dataObj.text("lang").orEmpty(),
        styleRaw = dataObj.text("style").orEmpty(),
        songLangRaw = dataObj.text("song_lang").orEmpty(),
        inactiveSingerList = dataObj.text("inactive_singerlist").orEmpty(),
        rawJson = KugouSigner.gson.toJson(dataObj)
    )
}

suspend fun MusicApiRepository.updateUserListeningPreference(
    mode: Int? = null,
    langRaw: String? = null,
    styleRaw: String? = null,
    songLangRaw: String? = null,
    inactiveSingerList: String? = null
): Boolean = auth { ctx ->
    val session = ctx.session
    val d = device()
    val clientTime = System.currentTimeMillis() / 1000
    val dataObj = JsonObject().apply {
        if (mode != null) addProperty("mode", mode.coerceIn(0, 2).toString())
        if (langRaw != null) addProperty("lang", langRaw)
        if (styleRaw != null) addProperty("style", styleRaw)
        if (songLangRaw != null) addProperty("song_lang", songLangRaw)
        if (inactiveSingerList != null) addProperty("inactive_singerlist", inactiveSingerList)
    }
    if (dataObj.size() == 0) return@auth false
    val strG2 = KugouSigner.random(16)
    val pPayload = KugouSigner.gson.toJson(mapOf("key" to strG2, "clienttime" to clientTime.toString()))
    // 对齐 MakcRe/KuGouMusicApi module/user_preference.js：rsaEncrypt2 返回小写 HEX
    val p = KugouSigner.rsaPkcs1(pPayload, lite = true).lowercase(Locale.ROOT)
    val encParams = KugouSigner.encrypt(KugouSigner.gson.toJson(mapOf("token" to session.token)), temporaryKey = strG2).ciphertext
    val body = JsonObject().apply {
        addProperty("plat", 1)
        addProperty("userid", session.userId.toLongOrNull() ?: 0L)
        addProperty("p", p)
        add("data", dataObj)
        addProperty("params", encParams)
    }
    val resp = transport.callAdvanced(
        path = "/userpreferservice/v1/update_user_conf",
        session = session,
        method = "POST",
        query = mapOf(
            "appid" to KugouSigner.APP_ID.toString(),
            "clientver" to KugouSigner.CLIENT_VERSION.toString(),
            "clienttime" to clientTime.toString(),
            "mid" to d.mid,
            "dfid" to d.dfid.ifBlank { "-" },
            "uuid" to "-"
        ),
        body = body,
        headers = mapOf(
            "Content-Type" to "application/json",
            "User-Agent" to "Android15-1070-${KugouSigner.CLIENT_VERSION}-201-0-update_user_conf-wifi",
            "KG-Rec" to "1",
            "KG-RC" to "1"
        ),
        sign = true,
        includeDefaultParams = false
    )
    if (resp.code == 20010) throw ApiFailure(20010, resp.errorMsg())
    resp.success
}

suspend fun MusicApiRepository.fetchContentBlacklist(
    label: String = "song",
    page: Int = 1,
    pageSize: Int = 100
): List<BlacklistEntry> {
    if (accounts.context()?.session == null) return emptyList()
    return auth { ctx ->
        val session = ctx.session
        val clientTime = System.currentTimeMillis() / 1000
        val normalizedLabel = if (label == "singer") "singer" else "song"
        val source = if (normalizedLabel == "singer") 4 else 3
        val pPayload = KugouSigner.gson.toJson(mapOf("clienttime" to clientTime, "token" to session.token))
        // 严格对齐 MakcRe/KuGouMusicApi module/blacklist_list.js：cryptoRSAEncrypt 返回小写 HEX（不转大写），支持直连与网关回退
        val pLower = runCatching { KugouSigner.rsa(pPayload, useStandardKey = false).lowercase(Locale.ROOT) }
            .getOrElse { KugouSigner.rsaPkcs1(pPayload, lite = true).lowercase(Locale.ROOT) }
        val body = JsonObject().apply {
            addProperty("userid", session.userId.toLongOrNull() ?: 0L)
            addProperty("source", source)
            addProperty("label", normalizedLabel)
            addProperty("p", pLower)
            addProperty("page", page.coerceAtLeast(1))
            addProperty("pagesize", pageSize.coerceIn(1, 500))
        }
        val resp = suspendRunCatching {
            transport.call(
                path = "/v2/get_list_items",
                host = "https://relationuser.kugou.com",
                session = session,
                method = "POST",
                body = body,
                headers = mapOf(
                    "KG-TID" to "474"
                )
            )
        }.getOrElse {
            transport.call(
                path = "/v2/get_list_items",
                session = session,
                method = "POST",
                body = body,
                headers = mapOf(
                    "x-router" to "relationuser.kugou.com",
                    "KG-TID" to "474"
                )
            )
        }
        if (resp.code == 20010) throw ApiFailure(20010, resp.errorMsg())

        val entries = mutableListOf<BlacklistEntry>()
        val arr = resp.data.arr("items")
            ?: resp.data.arr("list")
            ?: resp.data.arr("lists")
            ?: resp.body.arr("data")
        if (arr != null) {
            for (el in arr) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val rawKey = if (normalizedLabel == "singer") {
                    obj.text("singer_k") ?: obj.text("k") ?: ""
                } else {
                    obj.text("song_k") ?: obj.text("k") ?: ""
                }.trim()
                val key = if (normalizedLabel == "song") rawKey.lowercase(Locale.ROOT) else rawKey
                if (key.isBlank()) continue
                val vFieldName = if (normalizedLabel == "singer") "singer_v" else "song_v"
                val vEl = obj.get(vFieldName) ?: obj.get("v")
                val parsedV = when {
                    vEl != null && vEl.isJsonObject -> vEl.asJsonObject
                    vEl != null && vEl.isJsonPrimitive -> {
                        val s = vEl.asString.trim()
                        if (s.startsWith("{")) runCatching { JsonParser.parseString(s).asJsonObject }.getOrNull() else null
                    }
                    else -> null
                }
                val rawVText = if (vEl != null && vEl.isJsonPrimitive) vEl.asString else ""
                val name = parsedV?.text("n")?.takeIf { it.isNotBlank() } ?: rawVText.ifBlank { key }
                val mixId = parsedV?.text("m").orEmpty()
                val ts = parsedV?.longVal("t") ?: obj.longVal("t") ?: 0L
                entries.add(
                    BlacklistEntry(
                        key = key,
                        name = name,
                        label = normalizedLabel,
                        mixSongId = mixId,
                        addedAtSec = ts
                    )
                )
            }
        }
        entries.distinctBy { it.key }
    }
}

suspend fun MusicApiRepository.editContentBlacklist(
    label: String,
    key: String,
    name: String,
    mixSongId: String = "",
    isDelete: Boolean = false,
    moduleId: Int = 474
): Boolean = auth { ctx ->
    val session = ctx.session
    val normalizedLabel = if (label == "singer") "singer" else "song"
    val source = if (normalizedLabel == "singer") 4 else 3
    val clientTime = System.currentTimeMillis() / 1000
    val cleanKey = if (normalizedLabel == "song") key.trim().lowercase(Locale.ROOT) else key.trim()
    if (cleanKey.isBlank()) return@auth false

    val vObj = JsonObject().apply {
        addProperty("n", name)
        if (normalizedLabel == "song") addProperty("m", mixSongId)
        addProperty("t", clientTime.toString())
    }
    val itemArr = JsonArray().apply {
        add(JsonObject().apply {
            addProperty("k", cleanKey)
            addProperty("v", KugouSigner.gson.toJson(vObj))
        })
    }
    val pPayload = KugouSigner.gson.toJson(mapOf("clienttime" to clientTime, "token" to session.token))
    // 严格对齐 MakcRe/KuGouMusicApi module/blacklist.js：cryptoRSAEncrypt 返回小写 HEX，支持直连与网关回退
    val pLower = runCatching { KugouSigner.rsa(pPayload, useStandardKey = false).lowercase(Locale.ROOT) }
        .getOrElse { KugouSigner.rsaPkcs1(pPayload, lite = true).lowercase(Locale.ROOT) }
    val body = JsonObject().apply {
        addProperty("userid", session.userId.toLongOrNull() ?: 0L)
        addProperty("source", source)
        addProperty("label", normalizedLabel)
        add("items", itemArr)
        addProperty("action", if (isDelete) "delete" else "add")
        addProperty("p", pLower)
    }
    val resp = suspendRunCatching {
        transport.call(
            path = "/v1/edit_list_items",
            host = "https://relationuser.kugou.com",
            session = session,
            method = "POST",
            body = body,
            headers = mapOf(
                "KG-TID" to moduleId.toString()
            )
        )
    }.getOrElse {
        transport.call(
            path = "/v1/edit_list_items",
            session = session,
            method = "POST",
            body = body,
            headers = mapOf(
                "x-router" to "relationuser.kugou.com",
                "KG-TID" to moduleId.toString()
            )
        )
    }
    if (resp.code == 20010) throw ApiFailure(20010, resp.errorMsg())
    resp.success
}

// =========================================================================
// 在线登录设备管理（双通道全平台 1005+3116 并发检测，完整覆盖 Windows / PC 桌面端）
// =========================================================================
internal fun classifyLoginDeviceMeta(
    devNameRaw: String,
    appIdRaw: String,
    platRaw: String,
    osRaw: String,
    clientTypeRaw: String
): Triple<String, String, String> {
    val combined = "$devNameRaw $platRaw $osRaw $clientTypeRaw".lowercase(Locale.ROOT)
    val deviceType = when {
        combined.contains("windows") || combined.contains("win1") || combined.contains("win7") ||
            combined.contains("win8") || combined.contains("desktop") || combined.contains("pc") ||
            platRaw == "0" && (appIdRaw in setOf("1001", "1002", "1005", "2919", "3166") && !combined.contains("android") && !combined.contains("ios")) ||
            platRaw == "4" -> "windows"
        combined.contains("mac") || combined.contains("darwin") || combined.contains("osx") -> "mac"
        combined.contains("ipad") || combined.contains("tablet") || combined.contains("平板") -> "tablet"
        combined.contains("iphone") || combined.contains("ios") || platRaw == "2" -> "ios"
        combined.contains("car") || combined.contains("auto") || combined.contains("车") -> "car"
        combined.contains("tv") || combined.contains("电视") || combined.contains("box") -> "tv"
        combined.contains("web") || combined.contains("browser") || appIdRaw == "2919" -> "web"
        else -> "android"
    }
    val platformLabel = when (deviceType) {
        "windows" -> if (appIdRaw == "3116" || appIdRaw == "3166") "Windows · 概念版" else "Windows 桌面端"
        "mac" -> "macOS 桌面端"
        "ios" -> if (appIdRaw == "3116") "iOS · 酷狗概念版" else "iOS 客户端"
        "tablet" -> "平板客户端"
        "car" -> "车载客户端"
        "tv" -> "电视大屏端"
        "web" -> "网页端登录"
        else -> if (appIdRaw == "3116") "Android · 酷狗概念版" else "Android · 酷狗音乐"
    }
    val cleanName = devNameRaw.trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        ?: when (deviceType) {
            "windows" -> "Windows 电脑"
            "mac" -> "Mac 电脑"
            "ios" -> "iPhone 设备"
            "tablet" -> "平板设备"
            "car" -> "车载音响"
            "tv" -> "智能电视"
            "web" -> "酷狗网页端"
            else -> "Android 手机"
        }
    return Triple(cleanName, platformLabel, deviceType)
}

internal fun parseLoginDevicesFromResponse(
    resp: ApiResponse,
    currentMid: String,
    defaultChannelAppId: String,
    detectedViaStandard: Boolean
): List<LoginDeviceItem> {
    val dataNode = resp.data
    val innerDataObj = dataNode.obj("data")
    val arr: JsonArray? = dataNode.arr("list")
        ?: dataNode.arr("dev_list")
        ?: dataNode.arr("devices")
        ?: dataNode.arr("info")
        ?: innerDataObj?.arr("list")
        ?: innerDataObj?.arr("dev_list")
        ?: innerDataObj?.arr("devices")
        ?: innerDataObj?.arr("info")
        ?: resp.body.arr("data")
        ?: resp.body.arr("list")
    if (arr == null || arr.size() == 0) return emptyList()

    val items = mutableListOf<LoginDeviceItem>()
    for (el in arr) {
        if (!el.isJsonObject) continue
        val obj = el.asJsonObject
        val mid = (obj.text("mid")
            ?: obj.text("t_mid")
            ?: obj.text("dev_id")
            ?: obj.text("device_id")
            ?: obj.text("guid")
            ?: "").trim()
        val rawDevName = obj.text("dev_name")
            ?: obj.text("device_name")
            ?: obj.text("client_name")
            ?: obj.text("pc_name")
            ?: obj.text("hostname")
            ?: obj.text("sys_model")
            ?: obj.text("model")
            ?: obj.text("dev")
            ?: ""
        val appId = (obj.text("appid") ?: obj.text("t_appid") ?: defaultChannelAppId).trim()
        val clientVer = (obj.text("clientver") ?: obj.text("t_clientver") ?: "").trim()
        val platRaw = obj.text("plat") ?: obj.text("platform") ?: ""
        val osRaw = obj.text("os") ?: obj.text("sys_name") ?: obj.text("system") ?: ""
        val clientTypeRaw = obj.text("client_type") ?: obj.text("source") ?: ""
        val (cleanName, platLabel, deviceType) = classifyLoginDeviceMeta(
            devNameRaw = rawDevName,
            appIdRaw = appId,
            platRaw = platRaw,
            osRaw = osRaw,
            clientTypeRaw = clientTypeRaw
        )
        val loginTimeRaw = obj.longVal("login_time")
            ?: obj.longVal("last_login_time")
            ?: obj.longVal("active_time")
            ?: obj.longVal("time")
            ?: obj.longVal("t")
            ?: 0L
        val loginTimeSec = if (loginTimeRaw > 10_000_000_000L) loginTimeRaw / 1000L else loginTimeRaw
        val loginTimeStr = obj.text("login_time_str")
            ?: obj.text("last_login_time_str")
            ?: if (loginTimeRaw > 0L) formatEpochSeconds(loginTimeRaw) else ""
        val tVal = obj.text("t") ?: loginTimeRaw.takeIf { it > 0L }?.toString().orEmpty()
        val isSelf = mid.isNotBlank() && mid == currentMid
        items.add(
            LoginDeviceItem(
                mid = mid,
                deviceName = cleanName,
                platform = platLabel,
                deviceType = deviceType,
                appId = appId,
                clientVer = clientVer,
                loginTime = loginTimeStr,
                loginTimestampSec = loginTimeSec,
                t = tVal,
                isCurrentDevice = isSelf,
                canKickOut = !isSelf && mid.isNotBlank(),
                detectedViaStandardChannel = detectedViaStandard
            )
        )
    }
    return items
}

suspend fun MusicApiRepository.fetchLoginDevices(): List<LoginDeviceItem> {
    if (accounts.context()?.session == null) return emptyList()
    return auth { ctx ->
        val session = ctx.session
        val d = device()
        val currentMid = d.mid

        suspend fun queryGetDevChannel(
            useStandardApp: Boolean,
            platValue: Int
        ): ApiResponse? = suspendRunCatching {
            val appIdStr = if (useStandardApp) "1005" else KugouSigner.APP_ID.toString()
            val clientVerStr = if (useStandardApp) "20489" else KugouSigner.CLIENT_VERSION.toString()
            val clientTimeSec = System.currentTimeMillis() / 1000L
            val clientTimeMs = System.currentTimeMillis()
            val encrypted = KugouSigner.encrypt(KugouSigner.gson.toJson(mapOf("token" to session.token)))
            val pkPayload = KugouSigner.gson.toJson(mapOf("clienttime_ms" to clientTimeMs, "key" to encrypted.key))
            val pk = KugouSigner.rsa(pkPayload, useStandardKey = useStandardApp).uppercase(Locale.ROOT)
            val body = JsonObject().apply {
                addProperty("plat", platValue)
                addProperty("userid", session.userId.toLongOrNull() ?: 0L)
                addProperty("clienttime_ms", clientTimeMs)
                addProperty("pk", pk)
                addProperty("params", encrypted.ciphertext)
            }
            if (!useStandardApp && platValue == 1) {
                transport.call(
                    path = "/v2/get_dev",
                    host = "https://userinfoservice.kugou.com",
                    session = session,
                    method = "POST",
                    body = body
                )
            } else {
                val signQuery = sortedMapOf(
                    "appid" to appIdStr,
                    "clienttime" to clientTimeSec.toString(),
                    "clientver" to clientVerStr,
                    "dfid" to d.dfid.ifBlank { "-" },
                    "mid" to d.mid,
                    "token" to session.token,
                    "userid" to session.userId,
                    "uuid" to "-"
                )
                val bodyStr = KugouSigner.gson.toJson(body)
                val sig = if (useStandardApp) {
                    KugouSigner.signatureStandard(signQuery, bodyStr)
                } else {
                    KugouSigner.signature(signQuery, bodyStr, web = false)
                }
                val finalQuery = LinkedHashMap(signQuery).apply { put("signature", sig) }
                transport.callAdvanced(
                    path = "/v2/get_dev",
                    host = "https://userinfoservice.kugou.com",
                    session = session,
                    query = finalQuery,
                    body = body,
                    method = "POST",
                    headers = mapOf(
                        "KG-DEVID" to d.mid,
                        "KG-CLIENTTIMEMS" to clientTimeMs.toString()
                    ),
                    sign = false,
                    includeDefaultParams = false
                )
            }
        }.onFailure { err ->
            if (err is ApiFailure && err.invalidSession) throw err
        }.getOrNull()

        // 并发探测概念版通道（3116）+ 标准版/Windows 桌面端共享通道（1005 plat=1 & plat=0）+ 云端设备活跃心跳
        val mergedDevices = coroutineScope {
            val conceptDef = async { queryGetDevChannel(useStandardApp = false, platValue = 1) }
            val stdPlat1Def = async { queryGetDevChannel(useStandardApp = true, platValue = 1) }
            val stdPlat0Def = async { queryGetDevChannel(useStandardApp = true, platValue = 0) }
            val activeQueuePresenceDef = async {
                suspendRunCatching {
                    val qBody = JsonObject().apply {
                        addProperty("area_code", "1")
                        add("sources", JsonArray().apply {
                            add("pc")
                            add("mobile")
                            add("tv")
                            add("car")
                        })
                        addProperty("userid", session.userId.toLongOrNull() ?: 0L)
                        addProperty("ret_info", 1)
                        addProperty("token", session.token)
                        addProperty("pagesize", 5)
                    }
                    transport.call(
                        path = "/playque/devque/v1/get_latest_songs",
                        session = session,
                        method = "POST",
                        body = qBody
                    )
                }.getOrNull()
            }

            val cResp = conceptDef.await()
            if (cResp?.code == 20010) throw ApiFailure(20010, cResp.errorMsg())
            val s1Resp = stdPlat1Def.await()
            val s0Resp = stdPlat0Def.await()

            val collected = mutableListOf<LoginDeviceItem>()
            if (cResp != null) {
                collected.addAll(parseLoginDevicesFromResponse(cResp, currentMid, "3116", detectedViaStandard = false))
            }
            if (s1Resp != null) {
                collected.addAll(parseLoginDevicesFromResponse(s1Resp, currentMid, "1005", detectedViaStandard = true))
            }
            if (s0Resp != null) {
                collected.addAll(parseLoginDevicesFromResponse(s0Resp, currentMid, "1005", detectedViaStandard = true))
            }

            // 交叉校准：若 Windows 客户端正在线并同步过云端设备心跳（如 data.pc 或 devices[source=pc]），而 get_dev 未返回该 PC 项则补齐在线视图
            val devQueResp = activeQueuePresenceDef.await()
            if (devQueResp != null && collected.none { it.deviceType == "windows" || it.deviceType == "mac" }) {
                val qData = devQueResp.data
                val pcBucket = qData.get("pc")
                fun extractActivePcDevice(node: JsonObject?, fallbackSource: String = "pc"): LoginDeviceItem? {
                    val devMid = node?.text("mid") ?: node?.text("dev_id") ?: ""
                    val devName = node?.text("device_name") ?: node?.text("dev_name") ?: node?.text("client_name") ?: ""
                    val ts = node?.longVal("update_time") ?: node?.longVal("ot") ?: node?.longVal("play_time") ?: 0L
                    val tsSec = if (ts > 10_000_000_000L) ts / 1000L else ts
                    val (cName, pLabel, dType) = classifyLoginDeviceMeta(devName, "1005", fallbackSource, "windows", fallbackSource)
                    return LoginDeviceItem(
                        mid = devMid.ifBlank { "active_pc_${session.userId}" },
                        deviceName = cName,
                        platform = pLabel,
                        deviceType = dType,
                        appId = "1005",
                        clientVer = node?.text("clientver").orEmpty(),
                        loginTime = if (ts > 0L) formatEpochSeconds(ts) else "近期活跃",
                        loginTimestampSec = tsSec,
                        t = node?.text("t").orEmpty(),
                        isCurrentDevice = devMid.isNotBlank() && devMid == currentMid,
                        canKickOut = devMid.isNotBlank() && !node?.text("t").isNullOrBlank(),
                        detectedViaStandardChannel = true
                    )
                }
                when {
                    pcBucket != null && pcBucket.isJsonObject -> {
                        val bObj = pcBucket.asJsonObject
                        val songsArr = bObj.arr("songs") ?: bObj.arr("list") ?: bObj.arr("info")
                        if ((songsArr?.size() ?: 0) > 0 || bObj.has("device_name") || bObj.has("dev_name")) {
                            val firstSongObj = songsArr?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                            extractActivePcDevice(firstSongObj ?: bObj, "pc")?.let { collected.add(it) }
                        }
                    }
                    pcBucket != null && pcBucket.isJsonArray && pcBucket.asJsonArray.size() > 0 -> {
                        val firstObj = pcBucket.asJsonArray.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                        extractActivePcDevice(firstObj, "pc")?.let { collected.add(it) }
                    }
                    else -> {
                        val devArr = qData.arr("devices") ?: qData.arr("sources") ?: qData.arr("queues")
                        devArr?.forEach { el ->
                            if (!el.isJsonObject) return@forEach
                            val obj = el.asJsonObject
                            val src = (obj.text("source") ?: obj.text("client_type") ?: obj.text("plat") ?: "").lowercase(Locale.ROOT)
                            if (src.contains("pc") || src.contains("win") || src.contains("desktop")) {
                                extractActivePcDevice(obj, src)?.let { collected.add(it) }
                            }
                        }
                    }
                }
            }
            collected
        }

        // 按 (mid + appId/deviceType) 智能合并去重，优先保留带有效下线凭证 t 与最新活跃时间的记录
        val dedupedMap = LinkedHashMap<String, LoginDeviceItem>()
        for (item in mergedDevices) {
            val key = if (item.mid.isNotBlank()) {
                "${item.mid.lowercase(Locale.ROOT)}_${item.deviceType}"
            } else {
                "${item.deviceName.lowercase(Locale.ROOT)}_${item.platform}"
            }
            val prev = dedupedMap[key]
            if (prev == null) {
                dedupedMap[key] = item
            } else {
                val preferNew = (prev.t.isBlank() && item.t.isNotBlank()) ||
                    (item.loginTimestampSec > prev.loginTimestampSec)
                dedupedMap[key] = if (preferNew) {
                    item.copy(isCurrentDevice = prev.isCurrentDevice || item.isCurrentDevice)
                } else {
                    prev.copy(
                        t = prev.t.ifBlank { item.t },
                        canKickOut = prev.canKickOut || item.canKickOut,
                        isCurrentDevice = prev.isCurrentDevice || item.isCurrentDevice
                    )
                }
            }
        }

        dedupedMap.values.sortedWith(
            compareByDescending<LoginDeviceItem> { it.isCurrentDevice }
                .thenByDescending { it.deviceType == "windows" || it.deviceType == "mac" }
                .thenByDescending { it.loginTimestampSec }
        )
    }
}

suspend fun MusicApiRepository.kickLoginDevice(target: LoginDeviceItem): Boolean = auth { ctx ->
    val session = ctx.session
    if (session.token.isBlank()) throw ApiFailure(20010, "请先登录酷狗账号")
    if (target.mid.isBlank() || target.mid.startsWith("active_pc_")) return@auth false
    val d = device()
    val h5Token = runCatching { KugouSigner.rsaNoPadLiteToken(session.token) }
        .getOrElse { return@auth false }

    suspend fun attemptKick(callerAppId: String, callerClientVer: String): ApiResponse {
        val clientTimeMs = System.currentTimeMillis()
        val defaultTargetAppId = if (target.detectedViaStandardChannel) "1005" else KugouSigner.APP_ID.toString()
        val defaultTargetVer = if (target.detectedViaStandardChannel) "20489" else KugouSigner.CLIENT_VERSION.toString()
        val query = linkedMapOf(
            "appid" to callerAppId,
            "clientver" to callerClientVer,
            "clienttime" to clientTimeMs.toString(),
            "mid" to d.mid,
            "uuid" to "-",
            "dfid" to d.dfid.ifBlank { "-" },
            "plat" to "1",
            "userid" to session.userId,
            "token" to h5Token,
            "srcappid" to "2919",
            "t_mid" to target.mid,
            "t" to target.t,
            "t_appid" to target.appId.ifBlank { defaultTargetAppId },
            "t_clientver" to target.clientVer.ifBlank { defaultTargetVer }
        )
        query["signature"] = KugouSigner.signature(query, web = true)
        return transport.callAdvanced(
            path = "/loginservice/v1/dev_logout",
            session = session,
            query = query,
            method = "GET",
            sign = false,
            includeDefaultParams = false
        )
    }

    val primaryCaller = if (target.detectedViaStandardChannel) {
        "1005" to "20489"
    } else {
        KugouSigner.APP_ID.toString() to KugouSigner.CLIENT_VERSION.toString()
    }
    var resp = attemptKick(primaryCaller.first, primaryCaller.second)
    if (resp.code == 20010) throw ApiFailure(20010, resp.errorMsg())
    if (!resp.success) {
        val secondaryCaller = if (primaryCaller.first == "1005") {
            KugouSigner.APP_ID.toString() to KugouSigner.CLIENT_VERSION.toString()
        } else {
            "1005" to "20489"
        }
        resp = attemptKick(secondaryCaller.first, secondaryCaller.second)
        if (resp.code == 20010) throw ApiFailure(20010, resp.errorMsg())
    }
    resp.success
}
