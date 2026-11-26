package com.concept.music.data.recommendation

import android.content.Context
import com.concept.music.data.kugou.KugouSigner
import com.concept.music.data.kugou.LocalBlacklistFilter
import com.concept.music.data.kugou.MusicApiRepository
import com.concept.music.data.kugou.editContentBlacklist
import com.concept.music.data.kugou.fetchContentBlacklist
import com.concept.music.data.kugou.fetchUserListeningPreference
import com.concept.music.data.kugou.sendPersonalFmFeedback
import com.concept.music.data.kugou.suspendRunCatching
import com.concept.music.data.kugou.updateUserListeningPreference
import com.concept.music.data.model.BlacklistEntry
import com.concept.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal data class PersistedPreferenceRecord(
    val version: Int = 1,
    val modeCode: Int = 0,
    val songLangWeights: Map<String, Int> = emptyMap(),
    val enabledStyleIds: List<String> = emptyList(),
    val extraStyleEntries: Map<String, Int> = emptyMap(),
    val langRaw: String = "",
    val gender: String = "",
    val age: String = "",
    val inactiveSingers: List<String> = emptyList(),
    val blacklistedSongs: List<BlacklistEntry> = emptyList(),
    val blacklistedSingers: List<BlacklistEntry> = emptyList(),
    val syncedFromCloudAtMs: Long = 0L
)

/**
 * 推荐偏好与内容屏蔽统一仓库（按账号 userId 隔离持久化 + 酷狗云端双向同步）：
 * - 冷启动 0ms 从磁盘水合上次同步的 mode / song_lang / style / 黑名单，彻底消除冷启动模式竞态；
 * - 统一承载官方互斥校验（语种不可全部屏蔽；加大 DJ 时不可同时屏蔽翻唱）；
 * - 提供唯一的硬约束判定 [isHardBlocked]，同时服务于「猜你喜欢」与「今日每日推荐」；
 * - 修复「少推该歌手」时直接覆盖云端 inactive_singerlist 的问题，改为读取后去重追加/移除。
 */
open class RecommendationPreferenceRepository(
    private val context: Context?,
    private val customStoreDir: File? = null
) {
    private val storeDir: File? by lazy {
        customStoreDir ?: runCatching {
            context?.applicationContext?.filesDir?.resolve("recommendation_prefs")?.apply {
                if (!exists()) mkdirs()
            }
        }.getOrNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val memoryRecords = ConcurrentHashMap<String, PersistedPreferenceRecord>()

    private val _uiState = MutableStateFlow(RecommendationPreferenceUiState())
    val uiState: StateFlow<RecommendationPreferenceUiState> = _uiState.asStateFlow()

    @Volatile
    private var activeUserId: String? = null

    private fun userKey(userId: String?): String =
        "pref_${userId?.trim()?.takeIf { it.isNotBlank() } ?: "guest"}"

    private fun fileFor(key: String): File? {
        val dir = storeDir ?: return null
        if (!dir.exists()) runCatching { dir.mkdirs() }
        val safe = key.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        return File(dir, "$safe.json")
    }

    @Synchronized
    private fun loadRecord(userId: String?): PersistedPreferenceRecord {
        val key = userKey(userId)
        memoryRecords[key]?.let { return it }
        val loaded = runCatching {
            val f = fileFor(key)
            if (f != null && f.exists()) {
                KugouSigner.gson.fromJson(f.readText(Charsets.UTF_8), PersistedPreferenceRecord::class.java)
                    ?: PersistedPreferenceRecord()
            } else {
                PersistedPreferenceRecord()
            }
        }.getOrDefault(PersistedPreferenceRecord())
        memoryRecords[key] = loaded
        return loaded
    }

    @Synchronized
    private fun saveRecord(userId: String?, record: PersistedPreferenceRecord, isLoggedIn: Boolean? = null) {
        val key = userKey(userId)
        memoryRecords[key] = record
        val json = KugouSigner.gson.toJson(record)
        persistJsonAsync(key, json)
        if (activeUserId == userId || _uiState.value.userId == userId) {
            publishState(userId, record, isLoggedIn = isLoggedIn ?: _uiState.value.isLoggedIn)
        }
    }

    private fun persistJsonAsync(key: String, json: String) {
        val f = fileFor(key) ?: return
        ioScope.launch {
            runCatching {
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(json, Charsets.UTF_8)
                if (!tmp.renameTo(f)) {
                    f.writeText(json, Charsets.UTF_8)
                    runCatching { tmp.delete() }
                }
            }
        }
    }

    private fun PersistedPreferenceRecord.toStructured(): StructuredListeningPreference =
        StructuredListeningPreference(
            mode = RecommendationMode.fromCode(modeCode),
            songLangWeights = songLangWeights,
            enabledStyleIds = enabledStyleIds.toSet(),
            extraStyleEntries = extraStyleEntries,
            langRaw = langRaw,
            gender = gender,
            age = age,
            inactiveSingers = inactiveSingers,
            syncedFromCloudAtMs = syncedFromCloudAtMs
        )

    private fun publishState(
        userId: String?,
        record: PersistedPreferenceRecord,
        isLoggedIn: Boolean,
        isSyncing: Boolean = _uiState.value.isSyncing
    ) {
        LocalBlacklistFilter.syncFromEntries(record.blacklistedSongs, record.blacklistedSingers)
        _uiState.value = RecommendationPreferenceUiState(
            userId = userId,
            preference = record.toStructured(),
            blacklistedSongs = record.blacklistedSongs,
            blacklistedSingers = record.blacklistedSingers,
            isSyncing = isSyncing,
            isLoggedIn = isLoggedIn
        )
    }

    /**
     * 切换账号或冷启动时立即从本地磁盘水合偏好与黑名单（0ms 同步返回，无网络阻塞）
     */
    @Synchronized
    fun activateUser(userId: String?, isLoggedIn: Boolean): StructuredListeningPreference {
        activeUserId = userId
        val rec = loadRecord(userId)
        publishState(userId, rec, isLoggedIn = isLoggedIn, isSyncing = false)
        return rec.toStructured()
    }

    fun currentPreference(userId: String? = activeUserId): StructuredListeningPreference =
        loadRecord(userId).toStructured()

    /**
     * 从酷狗云端同步官方听歌偏好（mode / song_lang / style / inactive_singerlist）与内容黑名单（歌曲 + 歌手）。
     * 返回 Pair(是否同步成功, 关键配置是否相比本地快照发生变化)。
     */
    suspend fun syncFromCloud(
        userId: String?,
        musicRepo: MusicApiRepository
    ): Pair<Boolean, Boolean> {
        if (userId.isNullOrBlank()) return false to false
        val beforeRec = loadRecord(userId)
        if (activeUserId == userId) {
            _uiState.value = _uiState.value.copy(isSyncing = true, isLoggedIn = true)
        }
        return try {
            val (cloudPref, cloudSongs, cloudSingers) = coroutineScope {
                val pDef = async { suspendRunCatching { musicRepo.fetchUserListeningPreference() }.getOrNull() }
                val sDef = async { suspendRunCatching { musicRepo.fetchContentBlacklist("song", page = 1, pageSize = 200) }.getOrNull() }
                val aDef = async { suspendRunCatching { musicRepo.fetchContentBlacklist("singer", page = 1, pageSize = 200) }.getOrNull() }
                Triple(pDef.await(), sDef.await(), aDef.await())
            }
            if (cloudPref == null && cloudSongs == null && cloudSingers == null) {
                return false to false
            }
            val parsedCloud = cloudPref?.let { StructuredListeningPreference.fromRaw(it) }
            val mergedSongs = cloudSongs ?: beforeRec.blacklistedSongs
            val mergedSingers = cloudSingers ?: beforeRec.blacklistedSingers
            val nextRec = PersistedPreferenceRecord(
                version = 1,
                modeCode = parsedCloud?.mode?.code ?: beforeRec.modeCode,
                songLangWeights = parsedCloud?.songLangWeights ?: beforeRec.songLangWeights,
                enabledStyleIds = parsedCloud?.enabledStyleIds?.toList() ?: beforeRec.enabledStyleIds,
                extraStyleEntries = parsedCloud?.extraStyleEntries ?: beforeRec.extraStyleEntries,
                langRaw = parsedCloud?.langRaw ?: beforeRec.langRaw,
                gender = parsedCloud?.gender ?: beforeRec.gender,
                age = parsedCloud?.age ?: beforeRec.age,
                inactiveSingers = parsedCloud?.inactiveSingers ?: beforeRec.inactiveSingers,
                blacklistedSongs = mergedSongs,
                blacklistedSingers = mergedSingers,
                syncedFromCloudAtMs = System.currentTimeMillis()
            )
            val changed = nextRec.modeCode != beforeRec.modeCode ||
                nextRec.songLangWeights != beforeRec.songLangWeights ||
                nextRec.enabledStyleIds != beforeRec.enabledStyleIds ||
                nextRec.blacklistedSongs.size != beforeRec.blacklistedSongs.size ||
                nextRec.blacklistedSingers.size != beforeRec.blacklistedSingers.size
            saveRecord(userId, nextRec, isLoggedIn = true)
            true to changed
        } finally {
            if (activeUserId == userId) {
                _uiState.value = _uiState.value.copy(isSyncing = false)
            }
        }
    }

    /**
     * 更新推荐模式（0 平衡 / 1 熟悉 / 2 尝鲜），立即落盘并在已登录时双向同步云端
     */
    suspend fun updateMode(
        userId: String?,
        mode: RecommendationMode,
        musicRepo: MusicApiRepository?,
        isLoggedIn: Boolean
    ): Boolean {
        val prev = loadRecord(userId)
        val updated = prev.copy(modeCode = mode.code)
        saveRecord(userId, updated, isLoggedIn = isLoggedIn)
        if (!isLoggedIn || musicRepo == null) return true
        val prefOk = suspendRunCatching {
            musicRepo.updateUserListeningPreference(mode = mode.code)
        }.getOrDefault(false)
        suspendRunCatching {
            musicRepo.sendPersonalFmFeedback(
                action = "change_song_pool",
                song = null,
                songPoolId = mode.code
            )
        }
        return prefOk
    }

    /**
     * 更新单个官方 song_lang 强度权重，内置官方互斥校验，本地生效后同步云端
     */
    suspend fun updateSongLangWeight(
        userId: String?,
        tag: SongLangTag,
        weight: Int,
        musicRepo: MusicApiRepository?,
        isLoggedIn: Boolean
    ): PreferenceValidationResult {
        val prev = loadRecord(userId)
        val nextWeights = LinkedHashMap(prev.songLangWeights)
        nextWeights[tag.code] = weight.coerceIn(0, 100)
        val validation = StructuredListeningPreference.validateSongLangWeights(nextWeights)
        if (!validation.valid) return validation

        val updated = prev.copy(songLangWeights = nextWeights)
        saveRecord(userId, updated, isLoggedIn = isLoggedIn)

        if (isLoggedIn && musicRepo != null) {
            val payload = updated.toStructured().toSongLangJson()
            suspendRunCatching {
                musicRepo.updateUserListeningPreference(songLangRaw = payload)
            }
        }
        return PreferenceValidationResult(true)
    }

    /**
     * 切换官方基础曲风偏好（style JSON 字符串），本地立即生效并同步云端
     */
    suspend fun toggleStyleTag(
        userId: String?,
        styleTag: StylePrefTag,
        musicRepo: MusicApiRepository?,
        isLoggedIn: Boolean
    ): Boolean {
        val prev = loadRecord(userId)
        val nextEnabled = prev.enabledStyleIds.toMutableSet()
        if (styleTag.id in nextEnabled) {
            nextEnabled.remove(styleTag.id)
        } else {
            nextEnabled.add(styleTag.id)
        }
        val nextExtra = LinkedHashMap(prev.extraStyleEntries)
        if (styleTag.id in nextEnabled) {
            nextExtra[styleTag.id] = 100
        } else {
            nextExtra.remove(styleTag.id)
        }
        val updated = prev.copy(
            enabledStyleIds = nextEnabled.toList(),
            extraStyleEntries = nextExtra
        )
        saveRecord(userId, updated, isLoggedIn = isLoggedIn)
        if (!isLoggedIn || musicRepo == null) return true
        val stylePayload = updated.toStructured().toStyleJson()
        return suspendRunCatching {
            musicRepo.updateUserListeningPreference(styleRaw = stylePayload)
        }.getOrDefault(false)
    }

    /**
     * 将单曲加入屏蔽黑名单（本地立即生效 + 云端 KG-TID=474 同步）
     */
    suspend fun addBlockedSong(
        userId: String?,
        song: Song,
        musicRepo: MusicApiRepository?,
        isLoggedIn: Boolean,
        moduleId: Int = 474
    ): Boolean {
        val songKey = song.hash.ifBlank { song.mixSongId }.trim().lowercase(Locale.ROOT)
        if (songKey.isBlank()) return false
        val entry = BlacklistEntry(
            key = songKey,
            name = "${song.artist} - ${song.title}",
            label = "song",
            mixSongId = song.mixSongId,
            addedAtSec = System.currentTimeMillis() / 1000L
        )
        val prev = loadRecord(userId)
        val merged = (listOf(entry) + prev.blacklistedSongs).distinctBy { it.key.lowercase(Locale.ROOT) }
        saveRecord(userId, prev.copy(blacklistedSongs = merged), isLoggedIn = isLoggedIn)
        LocalBlacklistFilter.addSong(song)

        if (!isLoggedIn || musicRepo == null) return true
        return suspendRunCatching {
            musicRepo.editContentBlacklist(
                label = "song",
                key = songKey,
                name = entry.name,
                mixSongId = song.mixSongId,
                isDelete = false,
                moduleId = moduleId
            )
        }.getOrDefault(false)
    }

    /**
     * 将歌手加入屏蔽黑名单（本地立即生效 + 云端黑名单 + 安全追加合并 inactive_singerlist + personal_recommend black_singer）
     */
    suspend fun addBlockedArtist(
        userId: String?,
        artistId: String,
        artistName: String,
        sampleSong: Song?,
        musicRepo: MusicApiRepository?,
        isLoggedIn: Boolean,
        moduleId: Int = 474
    ): Boolean {
        val primaryName = artistName.split("、", "/", ",", "&").firstOrNull()?.trim().orEmpty().ifBlank { artistName.trim() }
        val cleanKey = artistId.trim().takeIf { it.isNotBlank() && it != "0" } ?: primaryName
        if (cleanKey.isBlank()) return false

        val entry = BlacklistEntry(
            key = cleanKey,
            name = primaryName.ifBlank { cleanKey },
            label = "singer",
            addedAtSec = System.currentTimeMillis() / 1000L
        )
        val prev = loadRecord(userId)
        val mergedSingers = (listOf(entry) + prev.blacklistedSingers).distinctBy { it.key }
        val mergedInactive = (prev.inactiveSingers + listOfNotNull(primaryName.takeIf { it.isNotBlank() }))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val updated = prev.copy(
            blacklistedSingers = mergedSingers,
            inactiveSingers = mergedInactive
        )
        saveRecord(userId, updated, isLoggedIn = isLoggedIn)
        LocalBlacklistFilter.addArtist(artistId = cleanKey, artistName = primaryName)

        if (!isLoggedIn || musicRepo == null) return true
        val ok = suspendRunCatching {
            musicRepo.editContentBlacklist(
                label = "singer",
                key = cleanKey,
                name = entry.name,
                isDelete = false,
                moduleId = moduleId
            )
        }.getOrDefault(false)
        // 追加合并已有 inactive_singerlist，不再覆盖历史数据
        suspendRunCatching {
            musicRepo.updateUserListeningPreference(
                inactiveSingerList = updated.toStructured().toInactiveSingerListString()
            )
        }
        if (sampleSong != null) {
            suspendRunCatching {
                musicRepo.sendPersonalFmFeedback(
                    action = "black_singer",
                    song = sampleSong,
                    songPoolId = updated.modeCode
                )
            }
        }
        return ok
    }

    /**
     * 从屏蔽名单移除歌曲或歌手（双向同步云端黑名单与 inactive_singerlist）
     */
    suspend fun removeBlacklistEntry(
        userId: String?,
        entry: BlacklistEntry,
        sampleSong: Song? = null,
        musicRepo: MusicApiRepository?,
        isLoggedIn: Boolean,
        moduleId: Int = 474
    ): Boolean {
        val prev = loadRecord(userId)
        val updated = if (entry.label == "singer") {
            LocalBlacklistFilter.removeArtistEntry(entry)
            val nextSingers = prev.blacklistedSingers.filterNot {
                it.key == entry.key || it.name.equals(entry.name, ignoreCase = true)
            }
            val nextInactive = prev.inactiveSingers.filterNot {
                it.equals(entry.name, ignoreCase = true) || it.equals(entry.key, ignoreCase = true)
            }
            prev.copy(blacklistedSingers = nextSingers, inactiveSingers = nextInactive)
        } else {
            LocalBlacklistFilter.removeSongEntry(entry)
            val nextSongs = prev.blacklistedSongs.filterNot {
                it.key.equals(entry.key, ignoreCase = true)
            }
            prev.copy(blacklistedSongs = nextSongs)
        }
        saveRecord(userId, updated, isLoggedIn = isLoggedIn)

        if (!isLoggedIn || musicRepo == null) return true
        val ok = suspendRunCatching {
            musicRepo.editContentBlacklist(
                label = entry.label,
                key = entry.key,
                name = entry.name,
                mixSongId = entry.mixSongId,
                isDelete = true,
                moduleId = moduleId
            )
        }.getOrDefault(false)

        if (entry.label == "singer") {
            suspendRunCatching {
                musicRepo.updateUserListeningPreference(
                    inactiveSingerList = updated.toStructured().toInactiveSingerListString()
                )
            }
            if (sampleSong != null) {
                suspendRunCatching {
                    musicRepo.sendPersonalFmFeedback(
                        action = "cancel_black_singer",
                        song = sampleSong,
                        songPoolId = updated.modeCode
                    )
                }
            }
        } else if (sampleSong != null) {
            suspendRunCatching {
                musicRepo.sendPersonalFmFeedback(
                    action = "cancel_garbage",
                    song = sampleSong,
                    songPoolId = updated.modeCode
                )
            }
        }
        return ok
    }

    /**
     * 唯一硬约束判定（作用范围：猜你喜欢全模式 + 今日每日推荐）：
     * 1. 命中歌曲或歌手黑名单（含 inactive_singerlist）；
     * 2. 命中 song_lang 权重 = 0 的内容形态（DJ / 翻唱 / Live / AI / 纯音乐）；
     * 3. 命中 song_lang 权重 = 0 的语种（云端权威语种 100% 严拦；本地字符集推断仅对高确定性的日/韩/纯英文/纯音乐严拦，避免国/粤汉字误杀）。
     */
    fun isHardBlocked(
        song: Song,
        meta: SongTagMeta,
        preference: StructuredListeningPreference = currentPreference()
    ): Boolean {
        if (LocalBlacklistFilter.isBlocked(song)) return true
        if (preference.inactiveSingers.isNotEmpty() && song.artist.isNotBlank()) {
            val inactiveLower = preference.inactiveSingers.map { it.lowercase(Locale.ROOT) }.toSet()
            val singers = song.artist.split("、", "/", ",", "&").map { it.trim().lowercase(Locale.ROOT) }
            if (singers.any { it.isNotBlank() && it in inactiveLower }) return true
        }
        // 内容形态硬屏蔽（S9 DJ / T1 翻唱 / N2 现场 / S28 AI / L6 纯音乐）
        for (flag in meta.contentFlags) {
            if (preference.weightForCode(flag) <= 0) return true
        }
        // 语种硬屏蔽（L1~L6）
        val lang = meta.languageCode
        if (lang.isNotBlank() && preference.weightForCode(lang) <= 0) {
            if (!meta.isInferredLanguage) {
                return true
            }
            // 对非汉字歧义语种（日语假名、韩语谚文、纯英文标题与歌手、纯音乐）推断准确率极高，也严格拦截；
            // 若是国语 L1 被屏蔽而曲目仅靠汉字推断（可能是粤语 L5），仅在粤语也屏蔽或无粤语线索时拦截
            val cantoneseBlocked = preference.weightFor(SongLangTag.CANTONESE) <= 0
            if (lang != SongLangTag.MANDARIN.code || cantoneseBlocked) {
                return true
            }
        }
        return false
    }
}
