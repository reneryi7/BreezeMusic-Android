package com.concept.music.data.kugou

import com.concept.music.data.model.AudioQuality
import com.concept.music.data.model.BlacklistEntry
import com.concept.music.data.model.CloudDiskSummary
import com.concept.music.data.model.CloudSongItem
import com.concept.music.data.model.CommentTargetType
import com.concept.music.data.model.ContinuePlaySnapshot
import com.concept.music.data.model.CrossDeviceSession
import com.concept.music.data.model.DailyHistoryEntry
import com.concept.music.data.model.FollowedArtistNewRelease
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
import java.io.IOException
import java.text.SimpleDateFormat
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

private fun Song.allLowercaseHashes(): Set<String> = buildSet {
    listOf(hash, hash128, mp3Hash128, hash320, hashFlac, hashHiRes).forEach { h ->
        val clean = h.trim().lowercase(Locale.ROOT)
        if (clean.isNotEmpty()) add(clean)
    }
}

/**
 * 进程内跨 ViewModel 实时共享的推荐黑名单过滤器：
 * 当用户在发现页 FM 负反馈、播放页/操作面板屏蔽歌曲或歌手、或在黑名单管理页移除条目时实时同步，
 * 确保客户端 FM 与日推列表立刻生效且重启/切号不串号。
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
        artistId.trim().takeIf { it.isNotEmpty() }?.let { blockedArtistIds.add(it) }
        artistName.trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }?.let { blockedArtistNames.add(it) }
    }

    fun removeArtistEntry(entry: BlacklistEntry) {
        entry.key.trim().takeIf { it.isNotEmpty() }?.let { blockedArtistIds.remove(it) }
        entry.name.trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }?.let { blockedArtistNames.remove(it) }
    }

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

private fun JsonObject.longVal(key: String): Long? {
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
        rawTag.isNotBlank() && rawTag.length <= 10 -> rawTag.trim()
        lower.contains("live") || combined.contains("现场") || combined.contains("演唱会") -> "Live"
        combined.contains("伴奏") || lower.contains("instrumental") || lower.contains("karaoke") -> "伴奏"
        lower.contains("dj") || lower.contains("remix") -> "DJ/Remix"
        combined.contains("翻唱") || lower.contains("cover") -> "翻唱"
        combined.contains("纯音乐") || combined.contains("钢琴") || combined.contains("吉他") -> "器乐"
        combined.contains("合唱") || combined.contains("对唱") -> "合唱"
        combined.contains("降调") || combined.contains("加速") || combined.contains("sped") -> "变速/调"
        else -> ""
    }
}

internal fun normalizeCrossDeviceSource(rawSource: String?, rawDeviceName: String? = null): Pair<String, String> {
    val devName = rawDeviceName?.trim().orEmpty()
    val clean = rawSource?.trim()?.lowercase(Locale.ROOT).orEmpty()
    val sourceType = when {
        clean.contains("pc") || clean.contains("win") || clean.contains("mac") || clean.contains("desktop") || clean.contains("web") -> "pc"
        clean.contains("car") || clean.contains("auto") || clean.contains("vehicle") -> "car"
        clean.contains("tv") || clean.contains("box") -> "tv"
        clean.contains("pad") || clean.contains("tablet") -> "tablet"
        clean.contains("mobile") || clean.contains("android") || clean.contains("ios") || clean.contains("phone") || clean.contains("app") -> "mobile"
        devName.contains("win", ignoreCase = true) || devName.contains("pc", ignoreCase = true) || devName.contains("mac", ignoreCase = true) || devName.contains("desktop", ignoreCase = true) -> "pc"
        devName.contains("car", ignoreCase = true) || devName.contains("车") -> "car"
        devName.contains("tv", ignoreCase = true) || devName.contains("电视") -> "tv"
        devName.contains("pad", ignoreCase = true) || devName.contains("平板") -> "tablet"
        else -> "mobile"
    }
    val defaultLabel = when (sourceType) {
        "pc" -> "桌面端 · PC"
        "car" -> "车载设备"
        "tv" -> "电视大屏"
        "tablet" -> "平板端"
        else -> "移动端"
    }
    val label = if (devName.isNotBlank() && devName.length <= 24) devName else defaultLabel
    return sourceType to label
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
 * 酷狗云歌单服务加密通用请求（接入 accounts.authenticated 自动处理 20010 Token 续期）
 */
internal suspend fun MusicApiRepository.callCloudListEncrypted(
    path: String,
    dataObj: JsonObject
): JsonObject? = auth { ctx ->
    val session = ctx.session
    val d = device()
    val clientTime = System.currentTimeMillis() / 1000
    val payloadObj = dataObj.deepCopy().apply {
        if (!has("userid")) addProperty("userid", session.userId.toLongOrNull() ?: 0L)
        if (!has("token")) addProperty("token", session.token)
    }
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
        "last_time" to clientTime.toString(),
        "last_area" to "gztx",
        "key" to key,
        "dfid" to d.dfid,
        "p" to portrait
    )
    val headers = mapOf(
        "x-router" to "cloudlist.service.kugou.com",
        "Content-Type" to "application/octet-stream"
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

// =========================================================================
// Tier 1 #1：歌曲更多版本（Live / 翻唱 / DJ / 伴奏 / 原版）— audio_related.js
// =========================================================================
suspend fun MusicApiRepository.fetchRelatedSongVersions(
    song: Song,
    sort: String = "all",
    page: Int = 1,
    pageSize: Int = 30
): List<Song> {
    val mixId = song.albumAudioId.takeIf { it > 0L }
        ?: song.audioId.takeIf { it > 0L }
        ?: song.mixSongId.toLongOrNull()?.takeIf { it > 0L }
        ?: suspendRunCatching { resolveCommentMixSongId(song) }.getOrDefault(0L)

    val sortVal = when (sort.lowercase(Locale.ROOT)) {
        "hot" -> 2
        "new" -> 3
        else -> 1
    }
    val session = accounts.context()?.session
    val resultList = mutableListOf<Song>()

    if (mixId > 0L) {
        val params = linkedMapOf(
            "album_audio_id" to mixId.toString(),
            "appid" to "1005",
            "area_code" to "1",
            "clientver" to "12329",
            "page" to page.coerceAtLeast(1).toString(),
            "pagesize" to pageSize.coerceIn(1, 50).toString(),
            "show_input" to "1",
            "show_type" to "0",
            "sort" to sortVal.toString(),
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
            val parsed = parseSongs(resp.data).map { item ->
                val tag = inferSongVersionTag(item.title, rawTag = item.versionTag)
                if (tag.isNotBlank() && item.versionTag.isBlank()) item.copy(versionTag = tag) else item
            }
            resultList.addAll(parsed)
        }
    }

    // 兜底：若接口无结果且为首页，用「洁净歌名 + 主歌手」搜索匹配不同版本，避免同名不同曲混入
    if (resultList.isEmpty() && page == 1 && song.title.isNotBlank()) {
        val cleanTitle = song.title.substringBefore("(").substringBefore("（").trim().ifBlank { song.title }
        val primaryArtist = song.artist.split("、", "/", ",", "&").firstOrNull()?.trim().orEmpty()
        val fallbackQuery = if (primaryArtist.isNotBlank() && !cleanTitle.contains(primaryArtist, ignoreCase = true)) {
            "$cleanTitle $primaryArtist"
        } else {
            cleanTitle
        }
        val fallback = suspendRunCatching { searchSongs(fallbackQuery, page = 1, pageSize = 20).songs }.getOrDefault(emptyList())
        resultList.addAll(
            fallback.map { item ->
                val tag = inferSongVersionTag(item.title, rawTag = item.versionTag)
                if (tag.isNotBlank()) item.copy(versionTag = tag) else item
            }
        )
    }

    val preDeduped = resultList.distinctBy { (it.hash.ifBlank { it.id }).lowercase(Locale.ROOT) }
    val enriched = suspendRunCatching { enrichSongsPrivilegeLite(preDeduped) }.getOrDefault(preDeduped)
    // 音质补全完成后，再基于原曲全档位 hash 集合与 albumAudioId 精准剔除自身，防止高/低音质 hash 不同导致的重复展示
    val currentHashes = song.allLowercaseHashes()
    return enriched
        .distinctBy { (it.hash.ifBlank { it.id }).lowercase(Locale.ROOT) }
        .filterNot { candidate ->
            val candidateHashes = candidate.allLowercaseHashes()
            (currentHashes.isNotEmpty() && candidateHashes.any { it in currentHashes }) ||
                (song.albumAudioId > 0L && candidate.albumAudioId > 0L && candidate.albumAudioId == song.albumAudioId)
        }
}

// =========================================================================
// Tier 1 #2：歌单信息编辑 & 歌单内歌曲排序 & 歌单列表排序
// =========================================================================
suspend fun MusicApiRepository.updatePlaylistInfo(
    playlist: Playlist,
    newName: String? = null,
    newIntro: String? = null,
    newTags: String? = null
): Boolean {
    val listId = playlist.listId.takeIf { it > 0L }
        ?: playlist.listCreateListid.toLongOrNull()
        ?: playlist.id.toLongOrNull()
        ?: return false
    val dataObj = JsonObject().apply {
        addProperty("total_ver", playlist.totalVersion)
        addProperty("listid", listId)
        addProperty("type", if (playlist.isCreatedByMe) 0 else 1)
        addProperty("sort", 0)
        // 仅在显式提供 newTags 时下发 tags 字段，避免仅改名/简介时清空云端原有标签
        if (newTags != null) {
            addProperty("tags", newTags)
        }
        addProperty("intro", newIntro ?: playlist.description)
        if (!newName.isNullOrBlank()) {
            addProperty("name", newName.trim())
        }
    }
    val resp = callCloudListEncrypted("/v1/modify_list", dataObj)
    val errCode = resp?.number("err_code") ?: resp?.number("error_code") ?: resp?.number("errcode") ?: 0
    return resp != null && resp.number("status") == 1 && errCode == 0
}

suspend fun MusicApiRepository.sortPlaylistTracks(
    playlist: Playlist,
    orderedSongs: List<Song>,
    listVer: Long = 0L
): Boolean {
    val listId = playlist.listId.takeIf { it > 0L }
        ?: playlist.listCreateListid.toLongOrNull()
        ?: playlist.id.toLongOrNull()
        ?: return false
    val session = accounts.context()?.session
    // 若存在刚入单导致本地缺失 fileId 的曲目，先反查云端歌单曲目补齐 fileId，防止排序跳号或静默丢失
    val fallbackFileIdByHash = HashMap<String, Long>()
    val fallbackFileIdByAudioId = HashMap<Long, Long>()
    if (orderedSongs.any { it.fileId <= 0L } && session != null) {
        val lookupId = playlist.globalCollectionId.ifBlank { playlist.listCreateGid.ifBlank { playlist.id } }
        if (lookupId.isNotBlank()) {
            val remoteTracks = suspendRunCatching { fetchPlaylistSongsAll(lookupId, session) }.getOrDefault(emptyList())
            for (t in remoteTracks) {
                if (t.fileId <= 0L) continue
                t.allLowercaseHashes().forEach { h -> fallbackFileIdByHash.putIfAbsent(h, t.fileId) }
                if (t.albumAudioId > 0L) fallbackFileIdByAudioId.putIfAbsent(t.albumAudioId, t.fileId)
            }
        }
    }
    val resourceArr = JsonArray()
    var sortIdx = 0
    orderedSongs.forEach { song ->
        val fid = song.fileId.takeIf { it > 0L }
            ?: song.allLowercaseHashes().firstNotNullOfOrNull { fallbackFileIdByHash[it] }
            ?: song.albumAudioId.takeIf { it > 0L }?.let { fallbackFileIdByAudioId[it] }
            ?: 0L
        if (fid > 0L) {
            sortIdx += 1
            resourceArr.add(JsonObject().apply {
                addProperty("fileid", fid)
                addProperty("sort", sortIdx)
            })
        }
    }
    if (resourceArr.size() == 0) return false
    val dataObj = JsonObject().apply {
        addProperty("listid", listId)
        addProperty("list_ver", listVer)
        addProperty("type", if (playlist.isCreatedByMe) 0 else 1)
        add("data", resourceArr)
    }
    val resp = callCloudListEncrypted("/v1/modify_song_sort", dataObj)
    val errCode = resp?.number("err_code") ?: resp?.number("error_code") ?: resp?.number("errcode") ?: 0
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
        val lid = pl.listId.takeIf { it > 0L }
            ?: pl.listCreateListid.toLongOrNull()
            ?: pl.id.toLongOrNull()
            ?: 0L
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
// Tier 1 #3：按歌词搜歌（search.js type=lyric）
// =========================================================================
suspend fun MusicApiRepository.searchByLyric(
    keyword: String,
    page: Int = 1,
    pageSize: Int = 30
): SearchResult {
    val cleanKeyword = keyword.trim()
    if (cleanKeyword.isEmpty()) return SearchResult(emptyList(), 0, page, pageSize)
    val session = accounts.context()?.session
    val resp = suspendRunCatching {
        transport.call(
            path = "/v1/search/lyric",
            session = session,
            method = "GET",
            query = mapOf(
                "albumhide" to "0",
                "iscorrection" to "1",
                "keyword" to cleanKeyword,
                "nocollect" to "0",
                "page" to page.coerceAtLeast(1).toString(),
                "pagesize" to pageSize.coerceIn(1, 50).toString(),
                "platform" to "AndroidFilter"
            ),
            headers = mapOf("x-router" to "complexsearch.kugou.com")
        )
    }.getOrNull() ?: return SearchResult(emptyList(), 0, page, pageSize)

    val dataObj = resp.data
    val total = dataObj.number("total") ?: resp.body.number("total") ?: 0
    val listsArr = dataObj.arr("lists") ?: dataObj.arr("info") ?: resp.body.arr("lists")
    val songs = mutableListOf<Song>()
    if (listsArr != null) {
        for (el in listsArr) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val baseSong = parseSong(obj)
            val rawLyric = obj.text("lyric")
                ?: obj.text("Lyric")
                ?: obj.text("Lyrics")
                ?: obj.text("matched_lyric")
                ?: obj.text("highlight_lyric")
                ?: ""
            val cleanSnippet = htmlTagStripRegex.replace(rawLyric, "")
                .replace("\r", " ")
                .replace("\n", " · ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(120)
            if (baseSong.title.isNotBlank() || baseSong.hash.isNotBlank()) {
                songs.add(baseSong.copy(matchedLyricSnippet = cleanSnippet))
            }
        }
    }
    val enriched = suspendRunCatching { enrichSongsPrivilegeLite(songs) }.getOrDefault(songs)
    // 严格按 1:1 位置映射 + 全档位 hash 兜底恢复 matchedLyricSnippet，避免音质升级改写主 hash 后查表失配
    val snippetByAnyHash = HashMap<String, String>()
    songs.forEach { s ->
        if (s.matchedLyricSnippet.isNotBlank()) {
            s.allLowercaseHashes().forEach { h -> snippetByAnyHash.putIfAbsent(h, s.matchedLyricSnippet) }
            if (s.id.isNotBlank()) snippetByAnyHash.putIfAbsent(s.id.lowercase(Locale.ROOT), s.matchedLyricSnippet)
        }
    }
    val finalSongs = enriched.mapIndexed { idx, s ->
        val origSnippet = songs.getOrNull(idx)?.matchedLyricSnippet
            ?.takeIf { it.isNotBlank() }
            ?: s.allLowercaseHashes().firstNotNullOfOrNull { snippetByAnyHash[it] }
            ?: snippetByAnyHash[s.id.lowercase(Locale.ROOT)]
            ?: ""
        if (origSnippet.isNotBlank() && s.matchedLyricSnippet.isBlank()) {
            s.copy(matchedLyricSnippet = origSnippet)
        } else {
            s
        }
    }
    return SearchResult(
        songs = finalSongs,
        total = maxOf(total, finalSongs.size),
        page = page,
        pageSize = pageSize
    )
}

// =========================================================================
// Tier 1 #4：历史每日推荐回溯（everyday_history.js）
// =========================================================================
suspend fun MusicApiRepository.fetchDailyRecommendHistoryList(): List<DailyHistoryEntry> {
    val session = accounts.context()?.session ?: return emptyList()
    val resp = suspendRunCatching {
        transport.call(
            path = "/everyday/api/v1/get_history",
            session = session,
            method = "POST",
            query = mapOf("mode" to "list", "platform" to "ios"),
            headers = mapOf("x-router" to "everydayrec.service.kugou.com")
        )
    }.getOrNull() ?: return emptyList()

    val entries = mutableListOf<DailyHistoryEntry>()
    val root = resp.data
    val candidateArrays = listOfNotNull(
        root.arr("history_list"),
        root.arr("list"),
        root.arr("lists"),
        root.arr("dates"),
        root.arr("info"),
        resp.body.arr("data")
    )
    for (arr in candidateArrays) {
        for (el in arr) {
            if (el.isJsonPrimitive) {
                val raw = el.asString.trim()
                if (raw.isNotBlank()) {
                    entries.add(DailyHistoryEntry(historyName = raw, dateLabel = raw, displayTitle = raw))
                }
            } else if (el.isJsonObject) {
                val obj = el.asJsonObject
                val hName = obj.text("history_name")
                    ?: obj.text("name")
                    ?: obj.text("date")
                    ?: obj.text("day")
                    ?: continue
                val dateLabel = obj.text("date") ?: obj.text("day") ?: hName
                val title = obj.text("title") ?: obj.text("show_date") ?: dateLabel
                entries.add(DailyHistoryEntry(historyName = hName, dateLabel = dateLabel, displayTitle = title))
            }
        }
        if (entries.isNotEmpty()) break
    }
    return entries.distinctBy { it.historyName }
}

suspend fun MusicApiRepository.fetchDailyRecommendHistorySongs(
    historyName: String,
    date: String = ""
): List<Song> {
    val session = accounts.context()?.session ?: return emptyList()
    val query = linkedMapOf(
        "mode" to "song",
        "platform" to "ios"
    )
    if (historyName.isNotBlank()) query["history_name"] = historyName
    if (date.isNotBlank()) query["date"] = date
    val resp = suspendRunCatching {
        transport.call(
            path = "/everyday/api/v1/get_history",
            session = session,
            method = "POST",
            query = query,
            headers = mapOf("x-router" to "everydayrec.service.kugou.com")
        )
    }.getOrNull() ?: return emptyList()
    val songs = parseSongs(resp.data)
    return suspendRunCatching { enrichSongsPrivilegeLite(songs) }.getOrDefault(songs)
}

// =========================================================================
// Tier 1 #5：已关注歌手新歌动态（artist_follow_newsongs.js）
// =========================================================================
suspend fun MusicApiRepository.fetchFollowedArtistNewSongs(
    lastAlbumId: Long = 0L,
    pageSize: Int = 30,
    sort: Int = 1
): List<FollowedArtistNewRelease> {
    val session = accounts.context()?.session ?: return emptyList()
    val optSort = if (sort == 2) 2 else 1
    val resp = suspendRunCatching {
        transport.call(
            path = "/feed/v1/follow/newsong_album_list",
            session = session,
            method = "POST",
            query = mapOf(
                "last_album_id" to lastAlbumId.toString(),
                "page_size" to pageSize.coerceIn(1, 50).toString(),
                "opt_sort" to optSort.toString()
            ),
            body = json("last_album_id" to lastAlbumId)
        )
    }.getOrNull() ?: return emptyList()

    val releases = mutableListOf<FollowedArtistNewRelease>()
    val dataObj = resp.data
    val arr = dataObj.arr("list")
        ?: dataObj.arr("album_list")
        ?: dataObj.arr("songs")
        ?: dataObj.arr("info")
        ?: resp.body.arr("data")

    if (arr != null) {
        for (el in arr) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val albumId = obj.text("album_id") ?: obj.text("albumid") ?: ""
            val nestedSongs = obj.arr("songs") ?: obj.arr("audio_list")
            val hasNestedSongs = (nestedSongs?.size() ?: 0) > 0
            val isAlbum = (obj.number("type") == 2 || obj.number("res_type") == 2 || hasNestedSongs) && albumId.isNotBlank()
            val firstSongObj = nestedSongs?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            val parsedSong = when {
                firstSongObj != null -> parseSong(firstSongObj)
                obj.has("hash") || obj.has("FileHash") || obj.has("audio_info") || obj.has("songname") || obj.has("song_name") -> parseSong(obj)
                else -> null
            }
            val title = obj.text("album_name")
                ?: obj.text("title")
                ?: obj.text("songname")
                ?: obj.text("song_name")
                ?: parsedSong?.title
                ?: continue
            val artistName = obj.text("singer_name")
                ?: obj.text("author_name")
                ?: obj.text("singername")
                ?: parsedSong?.artist
                ?: "关注歌手"
            val artistId = obj.text("singer_id") ?: obj.text("author_id") ?: parsedSong?.artistId.orEmpty()
            val rawCover = obj.text("sizable_cover")
                ?: obj.text("cover")
                ?: obj.text("img")
                ?: parsedSong?.coverUrl
                ?: ""
            val cover = imageUrl(rawCover)
            val publishDate = obj.text("publish_date")
                ?: obj.text("publish_time")
                ?: obj.text("release_date")
                ?: parsedSong?.publishDate
                ?: ""
            val uniqueSongKey = parsedSong?.hash?.takeIf { it.isNotBlank() } ?: parsedSong?.id.orEmpty()
            val id = if (isAlbum) {
                "album_$albumId"
            } else {
                uniqueSongKey.ifBlank { albumId.ifBlank { "${artistId}_${title.hashCode()}" } }
            }
            releases.add(
                FollowedArtistNewRelease(
                    id = id,
                    title = title,
                    artistName = artistName,
                    artistId = artistId,
                    coverUrl = cover,
                    publishDate = publishDate,
                    isAlbum = isAlbum,
                    albumId = albumId,
                    song = parsedSong?.copy(
                        coverUrl = parsedSong.coverUrl.ifBlank { cover },
                        artist = parsedSong.artist.ifBlank { artistName },
                        artistId = parsedSong.artistId.ifBlank { artistId },
                        albumId = parsedSong.albumId.ifBlank { albumId },
                        publishDate = parsedSong.publishDate.ifBlank { publishDate }
                    )
                )
            )
        }
    }

    // 批量补齐单曲音质信息
    val songIndices = releases.mapIndexedNotNull { idx, item -> item.song?.let { idx to it } }
    if (songIndices.isNotEmpty()) {
        val enrichedSongs = suspendRunCatching {
            enrichSongsPrivilegeLite(songIndices.map { it.second })
        }.getOrDefault(songIndices.map { it.second })
        songIndices.forEachIndexed { pos, (origIdx, _) ->
            val enriched = enrichedSongs.getOrNull(pos) ?: return@forEachIndexed
            releases[origIdx] = releases[origIdx].copy(song = enriched)
        }
    }
    return releases.distinctBy { it.id }
}

// =========================================================================
// Tier 1 #6：评论删除 + 歌单/专辑评论（_comment.js / comment_music_del.js / comment_playlist.js / comment_album.js）
// =========================================================================
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
    // 对于单曲评论，若传入的 targetId 只是 32 位 hex hash 而非数字 mixSongId，优先通过 resolveCommentMixSongId 获取真实 childrenid
    val preferredTargetId = if (targetType == CommentTargetType.SONG && cleanTargetId != null && hex32HashRegex.matches(cleanTargetId)) {
        null
    } else {
        cleanTargetId
    }
    val resolvedChildrenId = commentSpecialChildIdCache[commentId]?.takeIf { it.isNotBlank() && it != "0" }
        ?: preferredTargetId
        ?: song?.let { suspendRunCatching { resolveCommentMixSongId(it) }.getOrDefault(0L).takeIf { id -> id > 0L }?.toString() }
        ?: cleanTargetId
        ?: song?.hash?.trim()?.lowercase(Locale.ROOT)
        ?: return@auth false

    val query = linkedMapOf(
        "r" to "commentsv2/delcomment",
        "code" to code,
        "childrenid" to resolvedChildrenId,
        "cid" to commentId,
        "kugouid" to session.userId,
        "ver" to "6",
        "clienttoken" to session.token,
        "appid" to KugouSigner.APP_ID.toString(),
        "clientver" to KugouSigner.CLIENT_VERSION.toString(),
        "mid" to d.mid,
        "clienttime" to clientTime.toString(),
        "key" to KugouSigner.paramsKey(clientTime, d.mid),
        "uuid" to "-",
        "dfid" to d.dfid
    )
    if (!tid.isNullOrBlank()) {
        query["tid"] = tid
    }
    val resp = transport.callAdvanced(
        path = "/index.php",
        session = session,
        query = query,
        method = "GET",
        headers = mapOf("x-router" to "m.comment.service.kugou.com"),
        sign = false,
        includeDefaultParams = false
    )
    if (resp.code == 20010) {
        throw ApiFailure(20010, resp.errorMsg())
    }
    val isMockEmpty = resp.body.entrySet().isEmpty() && resp.httpCode in 200..299
    resp.success || isMockEmpty
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
    val total = listOfNotNull(
        resp.body.number("count"),
        resp.body.number("total"),
        resp.data.number("count"),
        resp.data.number("total"),
        resp.data.number("total_count")
    ).maxOrNull() ?: 0
    val list = parseCommentList(resp.data, preferHotList = isHotTab, page = page)
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
    val clientTime = System.currentTimeMillis() / 1000
    val code = targetType.commentPoolCode()

    val resp = if (replyToCommentId != null) {
        val query = linkedMapOf(
            "r" to "commentsv2/reply",
            "code" to code,
            "childrenid" to resourceId,
            "childrenname" to resourceName,
            "kugouid" to session.userId,
            "ver" to "6",
            "clienttoken" to session.token,
            "appid" to KugouSigner.APP_ID.toString(),
            "clientver" to KugouSigner.CLIENT_VERSION.toString(),
            "mid" to d.mid,
            "clienttime" to clientTime.toString(),
            "key" to KugouSigner.paramsKey(clientTime, d.mid),
            "uuid" to "-",
            "dfid" to d.dfid,
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
            "childrenid" to resourceId,
            "childrenname" to resourceName,
            "kugouid" to session.userId,
            "ver" to "6",
            "clienttoken" to session.token,
            "appid" to KugouSigner.APP_ID.toString(),
            "clientver" to KugouSigner.CLIENT_VERSION.toString(),
            "mid" to d.mid,
            "clienttime" to clientTime.toString(),
            "key" to KugouSigner.paramsKey(clientTime, "${d.mid}$payloadJson"),
            "uuid" to "-",
            "dfid" to d.dfid
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
    val createdId = respData.text("id") ?: respData.text("cid") ?: "c_${targetType.name.lowercase()}_${System.currentTimeMillis()}"
    commentSpecialChildIdCache[createdId] = resourceId
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
        specialChildId = resourceId,
        timestampMs = System.currentTimeMillis()
    )
}

// =========================================================================
// Tier 2 #7：个人音乐云盘（user_cloud.js / user_cloud_url.js / user_cloud_del.js）
// =========================================================================
suspend fun MusicApiRepository.fetchUserCloudDisk(
    page: Int = 1,
    pageSize: Int = 50
): CloudDiskSummary {
    if (accounts.context()?.session == null) return CloudDiskSummary()
    return auth { ctx ->
        val session = ctx.session
        val d = device()
        val clientTime = System.currentTimeMillis() / 1000
        val dataObj = JsonObject().apply {
            addProperty("page", page.coerceAtLeast(1))
            addProperty("pagesize", pageSize.coerceIn(1, 100))
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
        val items = mutableListOf<CloudSongItem>()
        if (listArr != null) {
            for (el in listArr) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val hash = (obj.text("hash") ?: obj.text("file_hash") ?: "").trim()
                if (hash.isBlank()) continue
                val rawName = obj.text("name") ?: obj.text("filename") ?: obj.text("songname") ?: "未命名云盘曲目"
                val parts = rawName.split(" - ", limit = 2)
                val artist = obj.text("singer_name")
                    ?: obj.text("author_name")
                    ?: if (parts.size == 2) parts[0].trim() else "云盘曲目"
                val title = obj.text("song_name")
                    ?: obj.text("songname")
                    ?: if (parts.size == 2) stripKnownAudioExtension(parts[1]) else stripKnownAudioExtension(rawName)
                val kvId = obj.longVal("kv_id") ?: obj.longVal("fileid") ?: obj.longVal("id") ?: 0L
                val fSize = obj.longVal("size") ?: obj.longVal("filesize") ?: 0L
                val durRaw = obj.longVal("timelen") ?: obj.longVal("duration") ?: 0L
                val durMs = if (durRaw in 1L..10_000L) durRaw * 1000L else durRaw
                val brRaw = obj.number("bitrate") ?: 0
                val brKbps = if (brRaw > 10_000) brRaw / 1000 else brRaw
                val ext = obj.text("extname") ?: rawName.substringAfterLast('.', "").takeIf { it.length <= 5 }.orEmpty()
                val cover = imageUrl(obj.text("cover") ?: obj.text("sizable_cover") ?: obj.text("union_cover") ?: "")
                val addTimeSec = obj.longVal("addtime") ?: obj.longVal("add_time") ?: 0L
                items.add(
                    CloudSongItem(
                        kvId = kvId,
                        hash = hash,
                        name = rawName,
                        title = title.ifBlank { rawName },
                        artist = artist.ifBlank { "云盘曲目" },
                        album = obj.text("album_name") ?: "",
                        fileSize = fSize,
                        durationMs = durMs,
                        bitrate = brKbps,
                        extName = ext,
                        albumAudioId = obj.longVal("album_audio_id") ?: obj.longVal("mixsongid") ?: 0L,
                        audioId = obj.longVal("audio_id") ?: 0L,
                        coverUrl = cover,
                        addTime = formatEpochSeconds(addTimeSec)
                    )
                )
            }
        }
        val total = dataNode.number("total") ?: dataNode.number("count") ?: root.number("total") ?: items.size
        val used = dataNode.longVal("used_size") ?: dataNode.longVal("used_space") ?: 0L
        val max = dataNode.longVal("max_size") ?: dataNode.longVal("total_space") ?: 0L
        CloudDiskSummary(
            songs = items,
            totalCount = maxOf(total, items.size),
            usedBytes = used,
            maxBytes = max,
            hasMore = items.size >= pageSize && page * pageSize < total
        )
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
    val clientTime = System.currentTimeMillis() / 1000
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
        path = "/v1/del_files",
        session = session,
        query = query,
        rawBodyBytes = cipherBytes,
        host = "https://mcloudservice.kugou.com",
        method = "POST",
        sign = false,
        includeDefaultParams = false,
        device = d
    )
    val resp = decodeEncryptedJsonResponse(rawBytes, aesKey) ?: return@auth false
    val errCode = resp.number("err_code") ?: resp.number("error_code") ?: resp.number("errcode") ?: 0
    if (errCode == 20010) {
        throw ApiFailure(20010, resp.text("error_msg") ?: "登录已过期")
    }
    resp.number("status") == 1 && errCode == 0
}

// =========================================================================
// Tier 2 #8：个人听歌排行（近一周 / 全部累计）— user_listen.js
// =========================================================================
suspend fun MusicApiRepository.fetchUserListenRank(type: Int = 0): List<ListenRankItem> {
    if (accounts.context()?.session == null) return emptyList()
    return auth { ctx ->
        val session = ctx.session
        val clientTime = System.currentTimeMillis() / 1000
        val uidLong = session.userId.toLongOrNull() ?: 0L
        val pPayload = KugouSigner.gson.toJson(mapOf("clienttime" to clientTime, "token" to session.token))
        val p = KugouSigner.rsa(pPayload, useStandardKey = false).uppercase(Locale.ROOT)
        val body = JsonObject().apply {
            addProperty("t_userid", uidLong)
            addProperty("userid", uidLong)
            addProperty("list_type", if (type == 1) 1 else 0)
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
                    ?: 0
                if (song.title.isNotBlank() || song.hash.isNotBlank()) {
                    rawPairs.add(song.copy(playCount = count) to count)
                }
            }
        }
        if (rawPairs.isEmpty()) return@auth emptyList()
        val enriched = suspendRunCatching { enrichSongsPrivilegeLite(rawPairs.map { it.first }) }
            .getOrDefault(rawPairs.map { it.first })
        enriched.mapIndexed { idx, s ->
            val count = rawPairs.getOrNull(idx)?.second ?: s.playCount
            ListenRankItem(rank = idx + 1, song = s.copy(playCount = count), playCount = count)
        }
    }
}

// =========================================================================
// Tier 2 #9：私人 FM 负反馈调教 & 听歌偏好 & 内容黑名单
// =========================================================================
suspend fun MusicApiRepository.sendPersonalFmFeedback(
    action: String,
    song: Song? = null,
    songPoolId: Int = 0
): List<Song> {
    val session = accounts.context()?.session
    val d = device()
    // 严格使用 10 位秒级时间戳，与 MusicRepository.personalFm 及 transport query/header 保持一致
    val nowSec = System.currentTimeMillis() / 1000L
    val body = JsonObject().apply {
        addProperty("appid", KugouSigner.APP_ID)
        addProperty("clienttime", nowSec)
        addProperty("mid", d.mid)
        addProperty("action", action)
        addProperty("recommend_source_locked", 0)
        addProperty("song_pool_id", songPoolId.coerceIn(0, 2))
        addProperty("callerid", 0)
        addProperty("m_type", 1)
        addProperty("platform", "ios")
        addProperty("area_code", 1)
        addProperty("remain_songcnt", 0)
        addProperty("clientver", KugouSigner.CLIENT_VERSION)
        addProperty("is_overplay", 0)
        addProperty("mode", "normal")
        addProperty("fakem", "ca981cfc583a4c37f28d2d49000013c16a0a")
        addProperty("key", KugouSigner.paramsKey(nowSec))
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

suspend fun MusicApiRepository.fetchUserListeningPreference(): UserListeningPreference? {
    val session = accounts.context()?.session ?: return null
    val d = device()
    val clientTime = System.currentTimeMillis() / 1000
    val strG2 = KugouSigner.random(16)
    val pPayload = KugouSigner.gson.toJson(mapOf("key" to strG2, "clienttime" to clientTime.toString()))
    val p = KugouSigner.rsaPkcs1(pPayload, lite = true).uppercase(Locale.ROOT)
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
                "dfid" to d.dfid,
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
        rawJson = KugouSigner.gson.toJson(dataObj)
    )
}

suspend fun MusicApiRepository.updateUserListeningPreference(
    mode: Int? = null,
    langRaw: String? = null,
    styleRaw: String? = null,
    songLangRaw: String? = null
): Boolean = auth { ctx ->
    val session = ctx.session
    val d = device()
    val clientTime = System.currentTimeMillis() / 1000
    val dataObj = JsonObject().apply {
        if (mode != null) addProperty("mode", mode.coerceIn(0, 2).toString())
        if (langRaw != null) addProperty("lang", langRaw)
        if (styleRaw != null) addProperty("style", styleRaw)
        if (songLangRaw != null) addProperty("song_lang", songLangRaw)
    }
    if (dataObj.size() == 0) return@auth false
    val strG2 = KugouSigner.random(16)
    val pPayload = KugouSigner.gson.toJson(mapOf("key" to strG2, "clienttime" to clientTime.toString()))
    val p = KugouSigner.rsaPkcs1(pPayload, lite = true).uppercase(Locale.ROOT)
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
            "dfid" to d.dfid,
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
        // 对齐同域名 relationuser.kugou.com 已验证实现：PKCS#1 v1.5 + 大写 HEX + 网关转发容灾
        val p = KugouSigner.rsaPkcs1(pPayload, lite = true).uppercase(Locale.ROOT)
        val body = JsonObject().apply {
            addProperty("userid", session.userId.toLongOrNull() ?: 0L)
            addProperty("source", source)
            addProperty("label", normalizedLabel)
            addProperty("p", p)
            addProperty("page", page.coerceAtLeast(1))
            addProperty("pagesize", pageSize.coerceIn(1, 500))
        }
        val resp = transport.call(
            path = "/v2/get_list_items",
            session = session,
            method = "POST",
            body = body,
            headers = mapOf(
                "x-router" to "relationuser.kugou.com",
                "KG-TID" to "473"
            )
        )
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
    moduleId: Int = 473
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
    val p = KugouSigner.rsaPkcs1(pPayload, lite = true).uppercase(Locale.ROOT)
    val body = JsonObject().apply {
        addProperty("userid", session.userId.toLongOrNull() ?: 0L)
        addProperty("source", source)
        addProperty("label", normalizedLabel)
        add("items", itemArr)
        addProperty("action", if (isDelete) "delete" else "add")
        addProperty("p", p)
    }
    val resp = transport.call(
        path = "/v1/edit_list_items",
        session = session,
        method = "POST",
        body = body,
        headers = mapOf(
            "x-router" to "relationuser.kugou.com",
            "KG-TID" to moduleId.toString()
        )
    )
    if (resp.code == 20010) throw ApiFailure(20010, resp.errorMsg())
    resp.success
}

// =========================================================================
// Tier 2 #11 ⭐ 重点：跨设备「继续播放」接力 & 登录设备管理
// =========================================================================
internal fun MusicApiRepository.parseCrossDeviceContinueSnapshot(
    root: JsonObject,
    nowMs: Long = System.currentTimeMillis()
): ContinuePlaySnapshot {
    val sessions = mutableListOf<CrossDeviceSession>()
    val allSongs = mutableListOf<Song>()

    fun addSongsForSource(rawSource: String?, rawDeviceName: String?, arr: JsonArray?, fallbackTs: Long = 0L) {
        if (arr == null || arr.size() == 0) return
        val (sType, sLabel) = normalizeCrossDeviceSource(rawSource, rawDeviceName)
        val parsedWithTs = mutableListOf<Pair<Song, Long>>()
        var latestTs = fallbackTs
        var hasExplicitItemTs = false
        for (el in arr) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val itemSource = obj.text("source") ?: obj.text("client") ?: obj.text("plat") ?: rawSource
            val itemDevName = obj.text("device_name") ?: obj.text("dev_name") ?: obj.text("client_name") ?: rawDeviceName
            val (_, itemLabel) = normalizeCrossDeviceSource(itemSource, itemDevName)
            val ts = obj.longVal("ot") ?: obj.longVal("play_time") ?: obj.longVal("update_time") ?: 0L
            val tsMs = if (ts in 1L..9_999_999_999L) ts * 1000L else ts
            if (tsMs > 0L) hasExplicitItemTs = true
            if (tsMs > latestTs) latestTs = tsMs
            val s = parseSong(obj)
            if (s.title.isNotBlank() || s.hash.isNotBlank()) {
                parsedWithTs.add(s.copy(sourceDeviceLabel = itemLabel) to tsMs)
            }
        }
        val ordered = if (hasExplicitItemTs) {
            parsedWithTs.sortedByDescending { it.second }.map { it.first }
        } else {
            parsedWithTs.map { it.first }
        }
        val unique = ordered.distinctBy { (it.hash.ifBlank { it.id }).lowercase(Locale.ROOT) }
        if (unique.isNotEmpty()) {
            sessions.add(
                CrossDeviceSession(
                    sourceType = sType,
                    sourceLabel = sLabel,
                    songs = unique,
                    latestSong = unique.first(),
                    updatedAtMs = latestTs
                )
            )
            allSongs.addAll(unique)
        }
    }

    // 1. 尝试识别按来源类型分桶结构（如 data.pc / data.mobile / data.tv / data.car 或 data.sources[] / data.devices[]）
    val dataObj = root.obj("data") ?: root
    for (bucketKey in listOf("pc", "mobile", "tablet", "tv", "car")) {
        val bucketEl: JsonElement? = dataObj.get(bucketKey)
        when {
            bucketEl == null -> Unit
            bucketEl.isJsonArray -> addSongsForSource(bucketKey, null, bucketEl.asJsonArray)
            bucketEl.isJsonObject -> {
                val bObj = bucketEl.asJsonObject
                val devName = bObj.text("device_name") ?: bObj.text("dev_name") ?: bObj.text("name")
                val ts = bObj.longVal("update_time") ?: bObj.longVal("ot") ?: 0L
                val innerArr = bObj.arr("songs") ?: bObj.arr("list") ?: bObj.arr("info") ?: bObj.arr("audios")
                addSongsForSource(bucketKey, devName, innerArr, if (ts in 1L..9_999_999_999L) ts * 1000L else ts)
            }
        }
    }

    // 2. 尝试识别设备对象数组（含顶层 {"status":1,"data":[...]} 以及 devices/queues/sources/list）
    val containerArr = dataObj.arr("devices")
        ?: dataObj.arr("queues")
        ?: dataObj.arr("sources")
        ?: dataObj.arr("list")
        ?: root.arr("data")
    if (sessions.isEmpty() && containerArr != null) {
        val groupedBySource = LinkedHashMap<Pair<String, String>, MutableList<Pair<Song, Long>>>()
        val groupedLatestTs = HashMap<Pair<String, String>, Long>()
        for (el in containerArr) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val nestedSongs = obj.arr("songs") ?: obj.arr("song_list") ?: obj.arr("audios")
            if (nestedSongs != null && nestedSongs.size() > 0) {
                val src = obj.text("source") ?: obj.text("client_type") ?: obj.text("plat")
                val dev = obj.text("device_name") ?: obj.text("dev_name") ?: obj.text("title")
                val ts = obj.longVal("update_time") ?: obj.longVal("ot") ?: 0L
                addSongsForSource(src, dev, nestedSongs, if (ts in 1L..9_999_999_999L) ts * 1000L else ts)
            } else {
                val src = obj.text("source") ?: obj.text("client_type") ?: obj.text("plat")
                val dev = obj.text("device_name") ?: obj.text("dev_name")
                val key = normalizeCrossDeviceSource(src, dev)
                val ts = obj.longVal("ot") ?: obj.longVal("play_time") ?: obj.longVal("update_time") ?: 0L
                val tsMs = if (ts in 1L..9_999_999_999L) ts * 1000L else ts
                val s = parseSong(obj)
                if (s.title.isNotBlank() || s.hash.isNotBlank()) {
                    groupedBySource.getOrPut(key) { mutableListOf() }.add(s.copy(sourceDeviceLabel = key.second) to tsMs)
                    groupedLatestTs[key] = maxOf(groupedLatestTs[key] ?: 0L, tsMs)
                }
            }
        }
        groupedBySource.forEach { (key, list) ->
            val hasTs = list.any { it.second > 0L }
            val ordered = if (hasTs) list.sortedByDescending { it.second }.map { it.first } else list.map { it.first }
            val unique = ordered.distinctBy { (it.hash.ifBlank { it.id }).lowercase(Locale.ROOT) }
            if (unique.isNotEmpty()) {
                sessions.add(
                    CrossDeviceSession(
                        sourceType = key.first,
                        sourceLabel = key.second,
                        songs = unique,
                        latestSong = unique.first(),
                        updatedAtMs = groupedLatestTs[key] ?: 0L
                    )
                )
                allSongs.addAll(unique)
            }
        }
    }

    // 3. 通用兜底解析（兼容扁平 song 列表）
    if (allSongs.isEmpty()) {
        val fallbackSongs = parseSongs(dataObj).map {
            if (it.sourceDeviceLabel.isBlank()) it.copy(sourceDeviceLabel = "云端跨设备队列") else it
        }
        if (fallbackSongs.isNotEmpty()) {
            allSongs.addAll(fallbackSongs)
            sessions.add(
                CrossDeviceSession(
                    sourceType = "mobile",
                    sourceLabel = "云端跨设备队列",
                    songs = fallbackSongs,
                    latestSong = fallbackSongs.first(),
                    updatedAtMs = nowMs
                )
            )
        }
    }

    val sortedSessions = sessions.sortedWith(
        compareByDescending<CrossDeviceSession> { it.updatedAtMs }
            .thenBy { if (it.sourceType == "pc") 0 else if (it.sourceType == "tablet") 1 else if (it.sourceType == "car") 2 else 3 }
    )
    // 合并队列严格按照活跃设备顺序生成，确保 primarySong 与 queue.first() 一致
    val mergedQueue = (if (sortedSessions.isNotEmpty()) sortedSessions.flatMap { it.songs } else allSongs)
        .distinctBy { (it.hash.ifBlank { it.id }).lowercase(Locale.ROOT) }
    val primarySession = sortedSessions.firstOrNull()
    val primarySong = primarySession?.latestSong ?: mergedQueue.firstOrNull()
    return ContinuePlaySnapshot(
        primarySong = primarySong,
        primaryDeviceLabel = primarySession?.sourceLabel ?: primarySong?.sourceDeviceLabel.orEmpty().ifBlank { "云端同步" },
        primarySourceType = primarySession?.sourceType ?: "mobile",
        queue = mergedQueue,
        deviceGroups = sortedSessions,
        fetchedAtMs = nowMs
    )
}

suspend fun MusicApiRepository.fetchCrossDeviceContinuePlay(pageSize: Int = 30): ContinuePlaySnapshot {
    val session = accounts.context()?.session ?: return ContinuePlaySnapshot()
    val body = JsonObject().apply {
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
        addProperty("pagesize", pageSize.coerceIn(1, 50))
    }

    var devQueErr: Throwable? = null
    val devQueResp = suspendRunCatching {
        transport.call(
            path = "/playque/devque/v1/get_latest_songs",
            session = session,
            method = "POST",
            body = body
        )
    }.onFailure { devQueErr = it }.getOrNull()

    var snapshot = devQueResp?.let { parseCrossDeviceContinueSnapshot(it.body) } ?: ContinuePlaySnapshot()

    // 兜底：若 devque 暂无跨端队列，降级拉取云端最近播放列表，保障「继续播放」始终可用
    if (!snapshot.hasPlayableContent) {
        var historyErr: Throwable? = null
        val historyResp = suspendRunCatching {
            transport.call(
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
        }.onFailure { historyErr = it }.getOrNull()
        if (devQueResp == null && historyResp == null) {
            throw (devQueErr ?: historyErr ?: IOException("拉取跨设备队列失败，请检查网络连接"))
        }
        val historySongs = historyResp?.let { parseSongs(it.data) }.orEmpty()
            .take(pageSize.coerceIn(1, 30))
            .map { it.copy(sourceDeviceLabel = "云端最近播放") }
        if (historySongs.isNotEmpty()) {
            snapshot = ContinuePlaySnapshot(
                primarySong = historySongs.first(),
                primaryDeviceLabel = "云端最近播放",
                primarySourceType = "mobile",
                queue = historySongs,
                deviceGroups = listOf(
                    CrossDeviceSession(
                        sourceType = "mobile",
                        sourceLabel = "云端最近播放",
                        songs = historySongs,
                        latestSong = historySongs.first(),
                        updatedAtMs = System.currentTimeMillis()
                    )
                ),
                fetchedAtMs = System.currentTimeMillis()
            )
        }
    }

    if (!snapshot.hasPlayableContent) return snapshot

    // 为跨设备队列批量补齐高音质 Hash 与封面：用「原队列全档位 hash + id」建立映射表，
    // 彻底避免 enrichSongsPrivilegeLite 升级主 hash 为 FLAC/Hi-Res 后按旧 hash 查表 miss 丢弃补全结果！
    val enrichedQueue = suspendRunCatching { enrichSongsPrivilegeLite(snapshot.queue) }.getOrDefault(snapshot.queue)
    val enrichedByAnyKey = HashMap<String, Song>()
    snapshot.queue.forEachIndexed { idx, orig ->
        val enriched = enrichedQueue.getOrNull(idx) ?: return@forEachIndexed
        val mergedSong = enriched.copy(sourceDeviceLabel = orig.sourceDeviceLabel.ifBlank { enriched.sourceDeviceLabel })
        orig.allLowercaseHashes().forEach { h -> enrichedByAnyKey[h] = mergedSong }
        enriched.allLowercaseHashes().forEach { h -> enrichedByAnyKey.putIfAbsent(h, mergedSong) }
        if (orig.id.isNotBlank()) enrichedByAnyKey[orig.id.lowercase(Locale.ROOT)] = mergedSong
        if (enriched.id.isNotBlank()) enrichedByAnyKey.putIfAbsent(enriched.id.lowercase(Locale.ROOT), mergedSong)
    }
    fun mapEnriched(s: Song): Song {
        val hit = s.allLowercaseHashes().firstNotNullOfOrNull { enrichedByAnyKey[it] }
            ?: s.id.takeIf { it.isNotBlank() }?.let { enrichedByAnyKey[it.lowercase(Locale.ROOT)] }
            ?: return s
        return hit.copy(sourceDeviceLabel = s.sourceDeviceLabel.ifBlank { hit.sourceDeviceLabel })
    }
    val updatedGroups = snapshot.deviceGroups.map { grp ->
        val mapped = grp.songs.map(::mapEnriched)
        grp.copy(songs = mapped, latestSong = mapped.first())
    }
    val updatedPrimary = snapshot.primarySong?.let(::mapEnriched) ?: enrichedQueue.firstOrNull()
    return snapshot.copy(
        primarySong = updatedPrimary,
        queue = snapshot.queue.map(::mapEnriched),
        deviceGroups = updatedGroups
    )
}

suspend fun MusicApiRepository.fetchLoginDevices(): List<LoginDeviceItem> {
    if (accounts.context()?.session == null) return emptyList()
    return auth { ctx ->
        val session = ctx.session
        val currentMid = device().mid
        val clientTimeMs = System.currentTimeMillis()
        val encrypted = KugouSigner.encrypt(KugouSigner.gson.toJson(mapOf("token" to session.token)))
        val pkPayload = KugouSigner.gson.toJson(mapOf("clienttime_ms" to clientTimeMs, "key" to encrypted.key))
        val pk = KugouSigner.rsa(pkPayload, useStandardKey = false).uppercase(Locale.ROOT)
        val body = JsonObject().apply {
            addProperty("plat", 1)
            addProperty("userid", session.userId.toLongOrNull() ?: 0L)
            addProperty("clienttime_ms", clientTimeMs)
            addProperty("pk", pk)
            addProperty("params", encrypted.ciphertext)
        }
        val resp = transport.call(
            path = "/v2/get_dev",
            host = "https://userinfoservice.kugou.com",
            session = session,
            method = "POST",
            body = body
        )
        if (resp.code == 20010) throw ApiFailure(20010, resp.errorMsg())

        val items = mutableListOf<LoginDeviceItem>()
        val arr = resp.data.arr("list")
            ?: resp.data.arr("dev_list")
            ?: resp.data.arr("devices")
            ?: resp.body.arr("data")
        if (arr != null) {
            for (el in arr) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val mid = obj.text("mid") ?: obj.text("t_mid") ?: ""
                val devName = obj.text("dev_name")
                    ?: obj.text("device_name")
                    ?: obj.text("model")
                    ?: obj.text("dev")
                    ?: "酷狗客户端设备"
                val appId = obj.text("appid") ?: obj.text("t_appid") ?: ""
                val clientVer = obj.text("clientver") ?: obj.text("t_clientver") ?: ""
                val platRaw = obj.text("plat") ?: obj.text("platform") ?: ""
                val platLabel = when {
                    appId == "3116" -> "酷狗概念版"
                    platRaw.contains("win", ignoreCase = true) || platRaw == "0" -> "Windows / PC"
                    platRaw.contains("ios", ignoreCase = true) || platRaw == "2" -> "iOS"
                    platRaw.contains("mac", ignoreCase = true) -> "macOS"
                    else -> "Android"
                }
                val loginTimeSec = obj.longVal("login_time") ?: obj.longVal("time") ?: obj.longVal("t") ?: 0L
                val loginTimeStr = obj.text("login_time_str")
                    ?: if (loginTimeSec > 0L) formatEpochSeconds(loginTimeSec) else ""
                val tVal = obj.text("t") ?: loginTimeSec.takeIf { it > 0L }?.toString().orEmpty()
                items.add(
                    LoginDeviceItem(
                        mid = mid,
                        deviceName = devName,
                        platform = platLabel,
                        appId = appId,
                        clientVer = clientVer,
                        loginTime = loginTimeStr,
                        t = tVal,
                        isCurrentDevice = mid.isNotBlank() && mid == currentMid
                    )
                )
            }
        }
        items.sortedByDescending { it.isCurrentDevice }
    }
}

suspend fun MusicApiRepository.kickLoginDevice(target: LoginDeviceItem): Boolean = auth { ctx ->
    val session = ctx.session
    if (session.token.isBlank()) throw ApiFailure(20010, "请先登录酷狗账号")
    if (target.mid.isBlank()) return@auth false
    val d = device()
    val clientTimeMs = System.currentTimeMillis()
    val h5Token = runCatching { KugouSigner.rsaNoPadLiteToken(session.token) }
        .getOrElse { return@auth false }
    val query = linkedMapOf(
        "appid" to KugouSigner.APP_ID.toString(),
        "clientver" to KugouSigner.CLIENT_VERSION.toString(),
        "clienttime" to clientTimeMs.toString(),
        "mid" to d.mid,
        "uuid" to "-",
        "dfid" to d.dfid,
        "plat" to "1",
        "userid" to session.userId,
        "token" to h5Token,
        "srcappid" to "2919",
        "t_mid" to target.mid,
        "t" to target.t,
        "t_appid" to target.appId.ifBlank { KugouSigner.APP_ID.toString() },
        "t_clientver" to target.clientVer.ifBlank { KugouSigner.CLIENT_VERSION.toString() }
    )
    query["signature"] = KugouSigner.signature(query, web = true)
    val resp = transport.callAdvanced(
        path = "/loginservice/v1/dev_logout",
        session = session,
        query = query,
        method = "GET",
        sign = false,
        includeDefaultParams = false
    )
    if (resp.code == 20010) throw ApiFailure(20010, resp.errorMsg())
    resp.success
}
