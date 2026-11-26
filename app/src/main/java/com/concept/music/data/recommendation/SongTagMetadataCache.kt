package com.concept.music.data.recommendation

import android.content.Context
import com.concept.music.data.kugou.KugouSigner
import com.concept.music.data.kugou.MusicApiRepository
import com.concept.music.data.kugou.batchFetchSongLanguageAndTags
import com.concept.music.data.kugou.isAccompanimentSong
import com.concept.music.data.kugou.isCoverOrRemixSong
import com.concept.music.data.kugou.isLiveVersionSong
import com.concept.music.data.model.Song
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import java.util.Locale

/**
 * 曲目真实语种、内容形态（DJ / 翻唱 / 现场 Live / AI 歌曲 / 纯音乐）与风格标签本地持久化缓存。
 * 优先复用酷狗 `/kmr/v2/albums` 与 `/kmr/v2/audio` 返回的权威 `language` 与 `tags`，并持久化至磁盘实现 0ms 复用。
 */
class SongTagMetadataCache(
    private val context: Context?,
    private val customCacheFile: File? = null
) {
    companion object {
        private const val MAX_CACHE_ENTRIES = 5_000
        private val djOnlyRegex = Regex(
            "(?:\\bdj\\b|\\bremix\\b|慢摇|串烧|蹦迪|车载电音|重低音dj|夜店|电音版)",
            RegexOption.IGNORE_CASE
        )
        private val coverOnlyRegex = Regex(
            "(?:翻唱|\\bcover\\b|女声版|男声版|童声版|烟嗓|0\\.[6-9]x|1\\.[1-5]x|加速版|慢速版|降调|升调|喊麦)",
            RegexOption.IGNORE_CASE
        )
        private val aiSongRegex = Regex(
            "(?:\\bai\\s*翻唱|\\bai\\s*演唱|\\bai\\s*生成|\\bai\\s*版|\\bai\\s*歌手|虚拟\\s*ai|ai作词|ai作曲|\\(ai\\)|（ai）|【ai】)",
            RegexOption.IGNORE_CASE
        )
        private val instrumentalRegex = Regex(
            "(?:纯音乐|轻音乐|钢琴曲|钢琴版|大提琴|古筝|笛子|小提琴|器乐|\\binstrumental\\b|\\bpiano\\s*ver|\\bost\\s*score|bgm)",
            RegexOption.IGNORE_CASE
        )
        private val cantoneseMarkerRegex = Regex(
            "(?:粤语|粤版|港乐|广东话|\\bcantonese\\b)",
            RegexOption.IGNORE_CASE
        )

        fun mapRawLanguageToCode(rawLanguage: String?): String {
            val clean = rawLanguage?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (clean.isBlank()) return ""
            return when {
                clean.contains("纯音乐") || clean.contains("轻音乐") || clean.contains("器乐") || clean.contains("instrumental") ->
                    SongLangTag.INSTRUMENTAL.code
                clean.contains("粤") || clean.contains("canton") || clean.contains("广东") ->
                    SongLangTag.CANTONESE.code
                clean.contains("国") || clean.contains("普通话") || clean.contains("华语") ||
                    clean.contains("中文") || clean.contains("mandarin") || clean.contains("chinese") ||
                    clean.contains("闽南") || clean.contains("台语") ->
                    SongLangTag.MANDARIN.code
                clean.contains("日") || clean.contains("japan") || clean == "jp" ->
                    SongLangTag.JAPANESE.code
                clean.contains("韩") || clean.contains("korea") || clean == "kr" ->
                    SongLangTag.KOREAN.code
                clean.contains("英") || clean.contains("欧美") || clean.contains("english") ||
                    clean == "en" || clean.contains("美") || clean.contains("欧") ->
                    SongLangTag.ENGLISH.code
                else -> ""
            }
        }

        fun detectContentFlags(
            song: Song,
            rawLanguage: String = "",
            rawTags: Collection<String> = emptyList()
        ): Set<String> {
            val combined = "${song.title} ${song.versionTag} ${song.album} ${rawTags.joinToString(" ")}".trim()
            val flags = LinkedHashSet<String>()
            if (isLiveVersionSong(song) || rawTags.any { it.contains("现场", ignoreCase = true) || it.equals("live", ignoreCase = true) }) {
                flags.add(SongLangTag.LIVE.code)
            }
            if (djOnlyRegex.containsMatchIn(combined)) {
                flags.add(SongLangTag.DJ.code)
            }
            if (coverOnlyRegex.containsMatchIn(combined) ||
                (isCoverOrRemixSong(song) && !djOnlyRegex.containsMatchIn(combined))
            ) {
                flags.add(SongLangTag.COVER.code)
            }
            if (aiSongRegex.containsMatchIn(combined) ||
                song.artist.contains("AI", ignoreCase = false) && song.artist.length <= 14 &&
                (song.artist.startsWith("AI") || song.artist.contains("AI孙燕姿") || song.artist.contains("AI歌手"))
            ) {
                flags.add(SongLangTag.AI_SONG.code)
            }
            val langCode = mapRawLanguageToCode(rawLanguage)
            if (langCode == SongLangTag.INSTRUMENTAL.code ||
                isAccompanimentSong(song) ||
                instrumentalRegex.containsMatchIn(combined)
            ) {
                flags.add(SongLangTag.INSTRUMENTAL.code)
            }
            return flags
        }

        fun inferLanguageConservatively(song: Song, contentFlags: Set<String>): Pair<String, Boolean> {
            if (SongLangTag.INSTRUMENTAL.code in contentFlags) {
                return SongLangTag.INSTRUMENTAL.code to false
            }
            val text = "${song.title} ${song.artist} ${song.album}".trim()
            if (cantoneseMarkerRegex.containsMatchIn(text)) {
                return SongLangTag.CANTONESE.code to false
            }
            val hasKana = text.any { ch ->
                ch.code in 0x3040..0x309F || ch.code in 0x30A0..0x30FF || ch.code in 0x31F0..0x31FF
            }
            if (hasKana) return SongLangTag.JAPANESE.code to true
            val hasHangul = text.any { ch ->
                ch.code in 0xAC00..0xD7AF || ch.code in 0x1100..0x11FF || ch.code in 0x3130..0x318F
            }
            if (hasHangul) return SongLangTag.KOREAN.code to true

            var hanCount = 0
            var latinCount = 0
            for (ch in song.title) {
                when {
                    ch.code in 0x4E00..0x9FFF -> hanCount++
                    ch in 'a'..'z' || ch in 'A'..'Z' -> latinCount++
                }
            }
            val artistHasHan = song.artist.any { it.code in 0x4E00..0x9FFF }
            return when {
                hanCount > 0 -> SongLangTag.MANDARIN.code to true
                latinCount >= 3 && !artistHasHan -> SongLangTag.ENGLISH.code to true
                artistHasHan -> SongLangTag.MANDARIN.code to true
                else -> "" to true
            }
        }
    }

    private val cacheFile: File? by lazy {
        customCacheFile ?: runCatching {
            context?.applicationContext?.cacheDir?.resolve("song_tag_meta_cache_v1.json")
        }.getOrNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val lruMap: MutableMap<String, SongTagMeta> = Collections.synchronizedMap(
        object : LinkedHashMap<String, SongTagMeta>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SongTagMeta>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    )

    @Volatile
    private var loadedFromDisk = false

    private fun ensureLoaded() {
        if (loadedFromDisk) return
        synchronized(this) {
            if (loadedFromDisk) return
            loadedFromDisk = true
            val f = cacheFile ?: return
            if (!f.exists()) return
            runCatching {
                val raw = f.readText(Charsets.UTF_8)
                val type = object : TypeToken<Map<String, SongTagMeta>>() {}.type
                val parsed: Map<String, SongTagMeta>? = KugouSigner.gson.fromJson(raw, type)
                if (!parsed.isNullOrEmpty()) {
                    synchronized(lruMap) {
                        parsed.entries.take(MAX_CACHE_ENTRIES).forEach { (k, v) ->
                            if (k.isNotBlank()) lruMap[k] = v
                        }
                    }
                }
            }
        }
    }

    private fun persistAsync() {
        val f = cacheFile ?: return
        val snapshot: Map<String, SongTagMeta> = synchronized(lruMap) { LinkedHashMap(lruMap) }
        ioScope.launch {
            runCatching {
                f.parentFile?.let { if (!it.exists()) it.mkdirs() }
                val tmp = File(f.parentFile, "${f.name}.tmp")
                val json = KugouSigner.gson.toJson(snapshot)
                tmp.writeText(json, Charsets.UTF_8)
                if (!tmp.renameTo(f)) {
                    f.writeText(json, Charsets.UTF_8)
                    runCatching { tmp.delete() }
                }
            }
        }
    }

    internal fun candidateCacheKeys(song: Song): List<String> = buildList {
        val cleanHash = song.hash.trim().lowercase(Locale.ROOT)
        if (cleanHash.isNotBlank()) add("h:$cleanHash")
        if (song.albumAudioId > 0L) add("aa:${song.albumAudioId}")
        val aid = song.albumId.trim()
        if (aid.isNotBlank() && aid != "0") add("alb:$aid")
    }

    fun peek(song: Song): SongTagMeta? {
        ensureLoaded()
        synchronized(lruMap) {
            for (key in candidateCacheKeys(song)) {
                lruMap[key]?.let { return it }
            }
        }
        return null
    }

    fun put(song: Song, meta: SongTagMeta, persist: Boolean = true) {
        ensureLoaded()
        val keys = candidateCacheKeys(song)
        if (keys.isEmpty()) return
        synchronized(lruMap) {
            keys.forEach { k -> lruMap[k] = meta }
        }
        if (persist) persistAsync()
    }

    /**
     * 快速本地解析（不发起网络请求）：优先命中缓存，否则返回基于规则和字符集的保守元数据
     */
    fun resolveFast(song: Song): SongTagMeta {
        peek(song)?.let { cached ->
            val localFlags = detectContentFlags(song, cached.rawLanguage)
            return if (localFlags.all { it in cached.contentFlags }) cached
            else cached.copy(contentFlags = cached.contentFlags + localFlags)
        }
        val flags = detectContentFlags(song)
        val (inferredLang, inferred) = inferLanguageConservatively(song, flags)
        val styles = StylePrefTag.matchLabelsFromRawTags(listOf(song.title, song.album, song.versionTag))
        return SongTagMeta(
            languageCode = inferredLang,
            isInferredLanguage = inferred,
            contentFlags = flags,
            styleIds = styles,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    /**
     * 批量补全候选曲目的真实语种与流派标签（命中磁盘缓存 0ms，未命中项单次并发查询 `/kmr/v2/albums` + `/kmr/v2/audio`）
     */
    suspend fun enrichAndResolveBatch(
        songs: List<Song>,
        musicRepo: MusicApiRepository
    ): Map<String, SongTagMeta> {
        ensureLoaded()
        if (songs.isEmpty()) return emptyMap()

        val result = LinkedHashMap<String, SongTagMeta>(songs.size)
        val needCloudFetch = mutableListOf<Song>()

        for (song in songs) {
            val songKey = songPrimaryKey(song)
            val cached = peek(song)
            if (cached != null && (cached.hasConfirmedLanguage() || cached.styleIds.isNotEmpty())) {
                val mergedFlags = cached.contentFlags + detectContentFlags(song, cached.rawLanguage)
                result[songKey] = if (mergedFlags.size == cached.contentFlags.size) cached
                else cached.copy(contentFlags = mergedFlags)
            } else {
                needCloudFetch.add(song)
            }
        }

        var cacheMutated = false
        if (needCloudFetch.isNotEmpty()) {
            val cloudMap = runCatching {
                musicRepo.batchFetchSongLanguageAndTags(needCloudFetch)
            }.getOrDefault(emptyMap())

            val now = System.currentTimeMillis()
            for (song in needCloudFetch) {
                val songKey = songPrimaryKey(song)
                val cloudInfo = cloudMap[songKey]
                    ?: cloudMap["aa:${song.albumAudioId}"]
                    ?: cloudMap["alb:${song.albumId.trim()}"]
                val rawLang = cloudInfo?.first.orEmpty()
                val rawTags = cloudInfo?.second.orEmpty()
                val flags = detectContentFlags(song, rawLang, rawTags)
                val mappedCloudLang = mapRawLanguageToCode(rawLang)
                val (finalLang, isInferred) = if (mappedCloudLang.isNotBlank()) {
                    mappedCloudLang to false
                } else {
                    inferLanguageConservatively(song, flags)
                }
                val styleIds = StylePrefTag.matchLabelsFromRawTags(
                    rawTags + listOf(song.title, song.album, song.versionTag)
                )
                val meta = SongTagMeta(
                    languageCode = finalLang,
                    isInferredLanguage = isInferred,
                    contentFlags = flags,
                    styleIds = styleIds,
                    rawLanguage = rawLang,
                    updatedAtMs = now
                )
                result[songKey] = meta
                if (!isInferred || styleIds.isNotEmpty() || flags.isNotEmpty()) {
                    put(song, meta, persist = false)
                    cacheMutated = true
                }
            }
            if (cacheMutated) persistAsync()
        }

        return result
    }

    fun clear() {
        synchronized(lruMap) { lruMap.clear() }
        cacheFile?.let { f ->
            ioScope.launch { runCatching { if (f.exists()) f.delete() } }
        }
    }

    fun songPrimaryKey(song: Song): String =
        song.hash.ifBlank { song.id }.trim().lowercase(Locale.ROOT)
}
