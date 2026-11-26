package com.concept.music.data.recommendation

import androidx.compose.runtime.Immutable
import com.concept.music.data.model.BlacklistEntry
import com.concept.music.data.model.UserListeningPreference
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/**
 * 猜你喜欢推荐模式（严格对齐酷狗云端 user_conf.mode: 0 默认平衡 / 1 偏好熟悉 / 2 更多尝鲜）
 */
enum class RecommendationMode(
    val code: Int,
    val label: String,
    val shortDesc: String
) {
    BALANCED(0, "平衡", "久违熟歌锚点与精准新发现均衡搭配"),
    FAMILIAR(1, "熟悉", "重温久违红心与常听歌手遗珠，高契合低打扰"),
    DISCOVERY(2, "尝鲜", "避开常听熟歌，在偏好语种内跨流派探索惊喜");

    companion object {
        fun fromCode(code: Int): RecommendationMode = when (code) {
            1 -> FAMILIAR
            2 -> DISCOVERY
            else -> BALANCED
        }
    }
}

/**
 * 官方 song_lang 推荐强度四档（兼容云端任意 0..100 权重值）
 */
enum class PreferenceIntensity(val weight: Int, val label: String) {
    BLOCKED(0, "屏蔽"),
    REDUCED(25, "减少"),
    DEFAULT(50, "默认"),
    BOOSTED(100, "加大");

    companion object {
        fun fromWeight(weight: Int): PreferenceIntensity = when {
            weight <= 0 -> BLOCKED
            weight < 50 -> REDUCED
            weight > 50 -> BOOSTED
            else -> DEFAULT
        }
    }
}

/**
 * 酷狗官方偏好设置 song_lang 支持的 10 个强度标签（6 大语种 + 4 类内容形态）
 */
enum class SongLangTag(
    val code: String,
    val label: String,
    val isLanguage: Boolean
) {
    MANDARIN("L1", "国语", true),
    CANTONESE("L5", "粤语", true),
    ENGLISH("L2", "英语", true),
    JAPANESE("L3", "日语", true),
    KOREAN("L4", "韩语", true),
    INSTRUMENTAL("L6", "纯音乐", true),
    LIVE("N2", "现场 Live", false),
    COVER("T1", "翻唱", false),
    DJ("S9", "DJ", false),
    AI_SONG("S28", "AI 歌曲", false);

    companion object {
        val languageTags: List<SongLangTag> = entries.filter { it.isLanguage }
        val contentTags: List<SongLangTag> = entries.filterNot { it.isLanguage }
        private val byCode = entries.associateBy { it.code.uppercase(Locale.ROOT) }

        fun fromCode(code: String?): SongLangTag? =
            code?.trim()?.uppercase(Locale.ROOT)?.let { byCode[it] }
    }
}

/**
 * 酷狗官方偏好设置 style 基础曲风标签（对齐标准版安卓标签 ID 表）
 */
enum class StylePrefTag(
    val id: String,
    val label: String,
    val everydayTagId: String = ""
) {
    POP("1", "流行", "1"),
    ROCK("2", "摇滚", "13"),
    ELECTRONIC("3", "电子", "15"),
    CLASSICAL("4", "古典乐", "26"),
    DANCE("5", "舞曲", "15"),
    HIPHOP("6", "嘻哈", "11"),
    JAZZ("7", "爵士", "14"),
    PURE_MUSIC("9", "纯音乐", "26"),
    FOLK("10", "民谣", "12"),
    COUNTRY("12", "乡村", "12"),
    RNB("14", "R&B", "11"),
    DJ("15", "DJ", "15"),
    ACG("16", "ACG", "19"),
    GUOFENG("21", "国风", "9"),
    TRADITIONAL_FOLK("23", "传统民歌", "12");

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): StylePrefTag? = byId[id.trim()]

        fun matchLabelsFromRawTags(rawTags: Collection<String>): Set<String> {
            if (rawTags.isEmpty()) return emptySet()
            val joined = rawTags.joinToString(" ").lowercase(Locale.ROOT)
            val hits = LinkedHashSet<String>()
            if (joined.contains("流行") || joined.contains("pop")) hits.add(POP.id)
            if (joined.contains("摇滚") || joined.contains("rock") || joined.contains("乐队")) hits.add(ROCK.id)
            if (joined.contains("电子") || joined.contains("电音") || joined.contains("electronic") || joined.contains("edm")) hits.add(ELECTRONIC.id)
            if (joined.contains("古典") || joined.contains("交响") || joined.contains("classical")) hits.add(CLASSICAL.id)
            if (joined.contains("舞曲") || joined.contains("dance") || joined.contains("disco")) hits.add(DANCE.id)
            if (joined.contains("嘻哈") || joined.contains("说唱") || joined.contains("rap") || joined.contains("hiphop") || joined.contains("hip-hop")) hits.add(HIPHOP.id)
            if (joined.contains("爵士") || joined.contains("jazz") || joined.contains("bossa")) hits.add(JAZZ.id)
            if (joined.contains("纯音乐") || joined.contains("轻音乐") || joined.contains("钢琴") || joined.contains("器乐")) hits.add(PURE_MUSIC.id)
            if (joined.contains("民谣") || joined.contains("folk") || joined.contains("独立")) hits.add(FOLK.id)
            if (joined.contains("乡村") || joined.contains("country")) hits.add(COUNTRY.id)
            if (joined.contains("r&b") || joined.contains("节奏布鲁斯") || joined.contains("soul") || joined.contains("蓝调")) hits.add(RNB.id)
            if (joined.contains("dj") || joined.contains("慢摇") || joined.contains("串烧")) hits.add(DJ.id)
            if (joined.contains("acg") || joined.contains("二次元") || joined.contains("动漫") || joined.contains("番剧")) hits.add(ACG.id)
            if (joined.contains("国风") || joined.contains("古风") || joined.contains("戏腔") || joined.contains("中国风")) hits.add(GUOFENG.id)
            if (joined.contains("民歌") || joined.contains("戏曲")) hits.add(TRADITIONAL_FOLK.id)
            return hits
        }
    }
}

/**
 * 四个自然听歌时段（用于轻度 ±10% 时段精排微调）
 */
enum class ListenTimeSlot(val key: String, val label: String) {
    MORNING("MORNING", "清晨/上午"),
    AFTERNOON("AFTERNOON", "午后/下午"),
    EVENING("EVENING", "傍晚/晚间"),
    LATE_NIGHT("LATE_NIGHT", "深夜");

    companion object {
        fun fromHour(hourOfDay: Int): ListenTimeSlot = when (hourOfDay) {
            in 5..11 -> MORNING
            in 12..17 -> AFTERNOON
            in 18..22 -> EVENING
            else -> LATE_NIGHT
        }
    }
}

/**
 * 曲目语种、内容形态与流派元信息
 */
@Immutable
data class SongTagMeta(
    val languageCode: String = "",
    val isInferredLanguage: Boolean = false,
    val contentFlags: Set<String> = emptySet(),
    val styleIds: Set<String> = emptySet(),
    val rawLanguage: String = "",
    val updatedAtMs: Long = 0L
) {
    fun hasConfirmedLanguage(): Boolean = languageCode.isNotBlank() && !isInferredLanguage
}

/**
 * 官方偏好强度的合法性校验结果
 */
data class PreferenceValidationResult(
    val valid: Boolean,
    val message: String = ""
)

/**
 * 结构化用户听歌偏好快照（与酷狗云端 /userpreferservice/v1/get_user_conf & update_user_conf 双向无损对齐）
 */
@Immutable
data class StructuredListeningPreference(
    val mode: RecommendationMode = RecommendationMode.BALANCED,
    val songLangWeights: Map<String, Int> = emptyMap(),
    val enabledStyleIds: Set<String> = emptySet(),
    val extraStyleEntries: Map<String, Int> = emptyMap(),
    val langRaw: String = "",
    val gender: String = "",
    val age: String = "",
    val inactiveSingers: List<String> = emptyList(),
    val syncedFromCloudAtMs: Long = 0L
) {
    fun weightFor(tag: SongLangTag): Int =
        (songLangWeights[tag.code] ?: PreferenceIntensity.DEFAULT.weight).coerceIn(0, 100)

    fun weightForCode(code: String): Int =
        (songLangWeights[code.trim().uppercase(Locale.ROOT)] ?: PreferenceIntensity.DEFAULT.weight).coerceIn(0, 100)

    fun intensityFor(tag: SongLangTag): PreferenceIntensity =
        PreferenceIntensity.fromWeight(weightFor(tag))

    fun blockedCodes(): Set<String> =
        SongLangTag.entries.filter { weightFor(it) <= 0 }.map { it.code }.toSet()

    fun reducedLanguageCodes(): Set<String> =
        SongLangTag.languageTags.filter { weightFor(it) in 1..49 }.map { it.code }.toSet()

    fun boostedLanguageCodes(): Set<String> =
        SongLangTag.languageTags.filter { weightFor(it) > 50 }.map { it.code }.toSet()

    fun toSongLangJson(): String {
        val obj = JsonObject()
        // 保留已知 10 项（含非默认值及显式设定值）以及来自云端的其他扩展 key
        val keys = (SongLangTag.entries.map { it.code } + songLangWeights.keys).distinct()
        for (k in keys) {
            val w = songLangWeights[k]
            if (w != null) {
                obj.addProperty(k, w.coerceIn(0, 100))
            }
        }
        return if (obj.size() == 0) "" else obj.toString()
    }

    fun toStyleJson(): String {
        val obj = JsonObject()
        for ((k, v) in extraStyleEntries) {
            if (k !in enabledStyleIds && v <= 50) {
                obj.addProperty(k, v)
            }
        }
        for (id in enabledStyleIds.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }) {
            obj.addProperty(id, 100)
        }
        return if (obj.size() == 0) "" else obj.toString()
    }

    fun toInactiveSingerListString(): String =
        inactiveSingers.map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(",")

    companion object {
        /**
         * 校验官方酷狗偏好互斥规则：
         * 1. 6 项语种（L1~L6）不可全部屏蔽；
         * 2. 加大 DJ（S9 > 50）时不可同时屏蔽翻唱（T1 == 0）。
         */
        fun validateSongLangWeights(weights: Map<String, Int>): PreferenceValidationResult {
            val allLangBlocked = SongLangTag.languageTags.all { tag ->
                (weights[tag.code] ?: PreferenceIntensity.DEFAULT.weight) <= 0
            }
            if (allLangBlocked) {
                return PreferenceValidationResult(false, "至少需要保留 1 项语种，不可全部屏蔽")
            }
            val djWeight = weights[SongLangTag.DJ.code] ?: PreferenceIntensity.DEFAULT.weight
            val coverWeight = weights[SongLangTag.COVER.code] ?: PreferenceIntensity.DEFAULT.weight
            if (djWeight > 50 && coverWeight <= 0) {
                return PreferenceValidationResult(false, "加大「DJ」推荐强度时不可同时屏蔽「翻唱」")
            }
            return PreferenceValidationResult(true)
        }

        fun fromRaw(raw: UserListeningPreference, nowMs: Long = System.currentTimeMillis()): StructuredListeningPreference {
            val parsedSongLang = parseIntWeightMap(raw.songLangRaw)
            val parsedStyleMap = parseIntWeightMap(raw.styleRaw)
            val enabledStyles = parsedStyleMap.filterValues { it > 50 }.keys.toSet()
            val inactiveList = raw.inactiveSingerList
                .split(",", "，", "|", ";")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            return StructuredListeningPreference(
                mode = RecommendationMode.fromCode(raw.mode),
                songLangWeights = parsedSongLang,
                enabledStyleIds = enabledStyles,
                extraStyleEntries = parsedStyleMap,
                langRaw = raw.langRaw,
                gender = raw.gender,
                age = raw.age,
                inactiveSingers = inactiveList,
                syncedFromCloudAtMs = nowMs
            )
        }

        internal fun parseIntWeightMap(rawJson: String): Map<String, Int> {
            val trimmed = rawJson.trim()
            if (trimmed.isBlank() || !trimmed.startsWith("{")) return emptyMap()
            return runCatching {
                val obj = JsonParser.parseString(trimmed).asJsonObject
                val map = LinkedHashMap<String, Int>()
                for ((k, v) in obj.entrySet()) {
                    val cleanKey = k.trim()
                    if (cleanKey.isBlank()) continue
                    val num = runCatching {
                        when {
                            v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asInt
                            v.isJsonPrimitive -> v.asString.trim().toDoubleOrNull()?.toInt()
                            else -> null
                        }
                    }.getOrNull()
                    if (num != null) {
                        map[cleanKey] = num.coerceIn(0, 100)
                    }
                }
                map
            }.getOrDefault(emptyMap())
        }
    }
}

/**
 * 供设置页与发现页观测的聚合偏好状态
 */
@Immutable
data class RecommendationPreferenceUiState(
    val userId: String? = null,
    val preference: StructuredListeningPreference = StructuredListeningPreference(),
    val blacklistedSongs: List<BlacklistEntry> = emptyList(),
    val blacklistedSingers: List<BlacklistEntry> = emptyList(),
    val isSyncing: Boolean = false,
    val isLoggedIn: Boolean = false
) {
    val blockedPreferenceCount: Int
        get() = preference.blockedCodes().size

    val totalBlacklistCount: Int
        get() = blacklistedSongs.size + blacklistedSingers.size
}

/**
 * 设置页展示的微风本地长期画像摘要
 */
@Immutable
data class TasteProfileSummary(
    val languageShares: List<Pair<String, Float>> = emptyList(),
    val topArtists: List<String> = emptyList(),
    val activeTimeSlot: ListenTimeSlot = ListenTimeSlot.EVENING,
    val timeSlotAdjustmentActive: Boolean = false,
    val activeTimeSlotHint: String = "",
    val tightenedProbeLanguages: List<String> = emptyList(),
    val quickSkipCount: Int = 0,
    val trackedPositiveCount: Int = 0
)
