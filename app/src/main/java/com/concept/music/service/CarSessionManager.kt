package com.concept.music.service

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.compose.runtime.Immutable
import com.concept.music.BuildConfig
import com.concept.music.data.kugou.KugouSigner
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * 64-bit 车载座舱专属声学模式（仅在手机端确认连接车机后动态挂载，断开车机后自动无感还原）
 */
enum class CarCabinAcousticMode(
    val label: String,
    val shortBadge: String,
    val subtitle: String,
    val leftItdDelayMs: Float,
    val rightDistanceTrimDb: Float,
    val stageLiftDb: Float,
    val roadNoiseSubBoostDb: Float,
    val doorAntiBoomCutDb: Float,
    val stereoWidthBoost: Float
) {
    DRIVER_SWEET_SPOT(
        label = "主驾皇帝位",
        shortBadge = "主驾皇帝位",
        subtitle = "左声道 2.25ms 先导微延迟 + 右侧声压补偿 + 仪表台声像抬升 + 胎噪低频补偿",
        leftItdDelayMs = 2.25f,
        rightDistanceTrimDb = 1.40f,
        stageLiftDb = 2.60f,
        roadNoiseSubBoostDb = 3.20f,
        doorAntiBoomCutDb = -2.00f,
        stereoWidthBoost = 0.18f
    ),
    FULL_CABIN_SURROUND(
        label = "全车均衡环绕",
        shortBadge = "全车环绕",
        subtitle = "四座对称宽广声场 + 仪表台声像抬升 + 行车胎噪低频补偿，适合多人同乘",
        leftItdDelayMs = 0f,
        rightDistanceTrimDb = 0f,
        stageLiftDb = 2.40f,
        roadNoiseSubBoostDb = 3.00f,
        doorAntiBoomCutDb = -1.80f,
        stereoWidthBoost = 0.34f
    ),
    CAR_SUBWOOFER(
        label = "车载重低音炮",
        shortBadge = "重低音炮",
        subtitle = "强化 60Hz 深沉胸腔共鸣与鼓点弹性 + 235Hz 车门内饰防共振净空",
        leftItdDelayMs = 0f,
        rightDistanceTrimDb = 0f,
        stageLiftDb = 1.80f,
        roadNoiseSubBoostDb = 5.20f,
        doorAntiBoomCutDb = -2.60f,
        stereoWidthBoost = 0.22f
    ),
    DIRECT_BYPASS(
        label = "原车音响直通",
        shortBadge = "原音直通",
        subtitle = "零附加车载滤波染色，交由原厂高端多路功放原生解码",
        leftItdDelayMs = 0f,
        rightDistanceTrimDb = 0f,
        stageLiftDb = 0f,
        roadNoiseSubBoostDb = 0f,
        doorAntiBoomCutDb = 0f,
        stereoWidthBoost = 0f
    )
}

enum class CarConnectionSource(val label: String) {
    NONE("未连接车机"),
    STANDALONE_CAR_UNIT("智能座舱原生直连"),
    PROJECTION_HOST("车联投屏互联"),
    CONFIRMED_BLUETOOTH("车载蓝牙直连"),
    MANUAL_PREVIEW("手动车载预览")
}

enum class CarDeviceClassification {
    @SerializedName("CONFIRMED_CAR")
    CONFIRMED_CAR,

    @SerializedName("IGNORED_NON_CAR")
    IGNORED_NON_CAR
}

enum class CarInterludeStyle(val label: String, val subtitle: String) {
    BREATHING_NOTES("♪  •••  ♪ 音符呼吸", "超过 5 秒器乐间奏时优雅展示呼吸音符，防止车机屏幕空白塌陷"),
    SONG_TITLE_NOTES("♪  歌名  ♪", "超过 5 秒器乐间奏时展示带音符的歌曲原名")
}

@Immutable
data class CarBluetoothDeviceRecord(
    @SerializedName("deviceKey") val deviceKey: String = "",
    @SerializedName("displayName") val displayName: String = "",
    @SerializedName("macAddress") val macAddress: String = "",
    @SerializedName("classification") val classification: CarDeviceClassification = CarDeviceClassification.IGNORED_NON_CAR,
    @SerializedName("updatedAtMs") val updatedAtMs: Long = System.currentTimeMillis()
)

@Immutable
data class PendingCarPromptCandidate(
    val deviceKey: String,
    val displayName: String,
    val macAddress: String = "",
    val matchedReason: String = ""
)

@Immutable
data class ConnectedBluetoothAudioSink(
    val deviceKey: String,
    val displayName: String,
    val macAddress: String = "",
    val isHardwareCarAudioClass: Boolean = false
)

/**
 * 持久化保存上车前的手机日常音效、声道平衡、响度均衡与音频焦点快照：
 * 即使行车途中进程被系统回收或手机重启，下车后再次启动也能 100% 自愈还原日常状态。
 */
@Immutable
data class PreCarEverydaySnapshot(
    @SerializedName("effectEnabled") val effectEnabled: Boolean = false,
    @SerializedName("presetName") val presetName: String = EffectPreset.OFF.name,
    @SerializedName("bassBoost") val bassBoost: Int = 0,
    @SerializedName("bassPunch") val bassPunch: Int = 0,
    @SerializedName("bassCutoffHz") val bassCutoffHz: Int = 60,
    @SerializedName("virt") val virt: Int = 0,
    @SerializedName("depth") val depth: Int = 0,
    @SerializedName("crossfeed") val crossfeed: Int = 0,
    @SerializedName("clarity") val clarity: Int = 0,
    @SerializedName("deEsser") val deEsser: Int = 0,
    @SerializedName("air") val air: Int = 0,
    @SerializedName("warmth") val warmth: Int = 0,
    @SerializedName("transientAttack") val transientAttack: Int = 0,
    @SerializedName("rotSpeed") val rotSpeed: Int = 0,
    @SerializedName("loudness") val loudness: Int = 0,
    @SerializedName("preampDb") val preampDb: Float = 0f,
    @SerializedName("channelBalance") val channelBalance: Float = 0f,
    @SerializedName("antiClip") val antiClip: Boolean = true,
    @SerializedName("eqLoudness") val eqLoudness: Boolean = false,
    @SerializedName("volumeNormalization") val volumeNormalization: Boolean = true,
    @SerializedName("reverbName") val reverbName: String = ReverbMode.NONE.name,
    @SerializedName("reverbWet") val reverbWet: Int = 0,
    @SerializedName("reverbPreDelay") val reverbPreDelay: Int = 0,
    @SerializedName("reverbDamp") val reverbDamp: Int = 0,
    @SerializedName("bandLevels") val bandLevels: List<Int> = listOf(0, 0, 0, 0, 0),
    @SerializedName("tenBandLevels") val tenBandLevels: List<Int> = List(10) { 0 },
    @SerializedName("tenBandQFactors") val tenBandQFactors: List<Float> = AudioEffectManager.DEFAULT_TEN_BAND_Q,
    @SerializedName("tenBandCenterHz") val tenBandCenterHz: List<Float> = AudioEffectManager.DEFAULT_TEN_BAND_HZ,
    @SerializedName("tenBandFilterTypeNames") val tenBandFilterTypeNames: List<String> = List(10) { PeqFilterType.PEAK.name },
    @SerializedName("highPassCutoffHz") val highPassCutoffHz: Int = 0,
    @SerializedName("lowPassCutoffHz") val lowPassCutoffHz: Int = 20000,
    @SerializedName("audioFocusModeName") val audioFocusModeName: String = AudioFocusMode.EXCLUSIVE.name
)

@Immutable
data class CarInterconnectState(
    val featureEnabled: Boolean = true,
    val isCarModeActive: Boolean = false,
    val activeConnectionSource: CarConnectionSource = CarConnectionSource.NONE,
    val activeCarDeviceName: String = "",
    val manualPreviewActive: Boolean = false,
    val pendingPromptCandidate: PendingCarPromptCandidate? = null,
    val currentBluetoothSinks: List<ConnectedBluetoothAudioSink> = emptyList(),
    val pairedBluetoothDevices: List<ConnectedBluetoothAudioSink> = emptyList(),
    val knownDevices: List<CarBluetoothDeviceRecord> = emptyList(),
    val cabinAcousticMode: CarCabinAcousticMode = CarCabinAcousticMode.DRIVER_SWEET_SPOT,
    val smartNavDuckingEnabled: Boolean = true,
    val navDuckingTargetGain: Float = 0.28f,
    val navSpectralDuckingEnabled: Boolean = true,
    val carBluetoothLyricEnabled: Boolean = true,
    val carInterludeStyle: CarInterludeStyle = CarInterludeStyle.BREATHING_NOTES,
    val carLyricOffsetMs: Long = -250L,
    val autoPlayOnCarConnect: Boolean = false,
    val steeringWheelZeroLatencyEnabled: Boolean = true
) {
    val confirmedCarCount: Int
        get() = knownDevices.count { it.classification == CarDeviceClassification.CONFIRMED_CAR }
}

/**
 * 手机端车载互联、蓝牙车机白名单识别与上下车状态快照自愈协调中心：
 * 1. 严格限制在 `phone` 构建变体生效（`tablet` 变体全程静默旁路，保持纯净平板体验）；
 * 2. 支持「零权限系统音频路由名称兜底 + 可选 `BLUETOOTH_CONNECT` 硬件类别码与离线配对列表」双轨识别；
 * 3. 首次命中疑似车载蓝牙时弹出单次底部询问卡，一次决策永久记住（白名单自动挂载 / 忽略名单永不打扰）；
 * 4. 上车原子落盘日常音效与音频焦点快照，下车或进程重启冷启动自动无感还原。
 */
object CarSessionManager {
    private const val PREFS_NAME = "concept_car_interconnect_prefs"
    private const val KEY_FEATURE_ENABLED = "car_feature_enabled"
    private const val KEY_CABIN_MODE = "car_cabin_acoustic_mode"
    private const val KEY_SMART_DUCK_ENABLED = "car_smart_nav_duck_enabled"
    private const val KEY_NAV_DUCK_GAIN = "car_nav_duck_target_gain"
    private const val KEY_SPECTRAL_DUCK_ENABLED = "car_nav_spectral_duck_enabled"
    private const val KEY_BT_LYRIC_ENABLED = "car_bt_lyric_enabled"
    private const val KEY_INTERLUDE_STYLE = "car_lyric_interlude_style"
    private const val KEY_CAR_LYRIC_OFFSET_MS = "car_lyric_offset_ms"
    private const val KEY_AUTO_PLAY_CAR = "car_auto_play_on_connect"
    private const val KEY_STEERING_ZERO_LATENCY = "car_steering_zero_latency"
    private const val KEY_KNOWN_DEVICES_JSON = "car_known_devices_json"
    private const val KEY_PENDING_SNAPSHOT_JSON = "car_pending_everyday_snapshot_json"

    internal var isTabletFlavorOverrideForTest: Boolean? = null
    internal var isCarFlavorOverrideForTest: Boolean? = null

    fun isStandaloneCarFlavor(): Boolean {
        return isCarFlavorOverrideForTest ?: BuildConfig.IS_CAR_FLAVOR
    }

    fun isPhoneCarFeatureSupported(): Boolean {
        val isTablet = isTabletFlavorOverrideForTest ?: BuildConfig.IS_TABLET_FLAVOR
        return !isTablet
    }

    private var prefs: SharedPreferences? = null
    @Volatile
    private var isInitialized: Boolean = false
    private val activeProjectionCounts = LinkedHashMap<String, Int>()
    private val temporarilyDismissedBluetoothKeys = LinkedHashSet<String>()
    private var temporarilyDismissedProjection: Boolean = false
    private var pendingSnapshotCache: PreCarEverydaySnapshot? = null

    @Volatile
    private var isUiModeCar: Boolean = false

    var onCarModeChangedCallback: ((isActive: Boolean) -> Unit)? = null
    var onApplyAudioFocusModeCallback: ((AudioFocusMode) -> Unit)? = null
    var onTriggerCarAutoPlayCallback: ((Context) -> Unit)? = null

    private val _state = MutableStateFlow(CarInterconnectState())
    val state: StateFlow<CarInterconnectState> = _state.asStateFlow()

    // 明确的耳机/个人穿戴设备排除词库（优先排除，绝不误弹车机提示）
    private val HEADPHONE_EXCLUSION_KEYWORDS = listOf(
        "airpods", "earpods", "galaxy buds", "buds pro", "buds2", "buds3", "buds fe", "buds live",
        "freebuds", "freelace", "enco", "flipbuds", "redmi buds", "xiaomi buds", "vivo tws", "iqoo tws",
        "wh-1000x", "wf-1000x", "linkbuds", "wi-1000x", "ult wear",
        "quietcomfort", "bose qc", "bose 700", "soundlink", "soundwear",
        "beats", "powerbeats", "solo3", "solo4", "studio3", "studio pro",
        "sennheiser", "momentum", "accentum", "shokz", "aftershokz", "openrun", "openfit", "openswim",
        "nothing ear", "cmf buds", "edifier", "漫步者", "jabra", "anker", "soundcore", "space a40", "liberty ",
        "fiio", "moondrop", "水月雨", "hiby", "shure", "akg ", "marshall major", "marshall minor", "marshall motif",
        "headphone", "earphone", "earbud", "headset", "neckband", "耳机", "骨传导", "watch", "手表", "手环"
    )

    // 主流原厂车机、后装中控与车载蓝牙模块特征词库
    private val CAR_BLUETOOTH_KEYWORDS = listOf(
        "car audio", "car multimedia", "car bt", "carkit", "car kit", "carplay", "my car", "my_car", "my-car",
        "handsfree", "hands-free", "hft", "uconnect", "sync", "mylink", "intellilink", "entune", "carwings",
        "mb bluetooth", "mercedes", "bmw", "mini ", "audi", "vw ", "volkswagen", "porsche", "skoda",
        "toyota", "lexus", "honda", "acura", "nissan", "infiniti", "mazda", "subaru", "mitsubishi", "suzuki",
        "tesla", "byd", "dilink", "仰望", "腾势", "denza", "方程豹",
        "geely", "吉利", "lynk", "领克", "zeekr", "极氪", "flyme auto", "polestar", "volvo",
        "xiaomi su", "xiaomi yu", "小米汽车", "huawei", "hicar", "aito", "问界", "智界", "享界", "尊界", "harmonyos cockpit",
        "li auto", "理想", "nio", "蔚来", "onvo", "乐道", "xpeng", "小鹏", "leapmotor", "零跑", "avatr", "阿维塔", "deepal", "深蓝",
        "changan", "长安", "gac", "广汽", "aion", "埃安", "trumpchi", "传祺", "chery", "奇瑞", "icar", "星途", "捷途",
        "haval", "哈弗", "tank", "坦克", "wey", "魏牌", "ora", "欧拉", "great wall", "roewe", "荣威", "mg ", "名爵",
        "hongqi", "红旗", "voyah", "岚图", "im motors", "智己", "arcfox", "极狐", "baic", "wuling", "五菱", "baojun", "宝骏",
        "ford", "lincoln", "chevrolet", "buick", "cadillac", "jeep", "dodge", "hyundai", "kia", "genesis",
        "peugeot", "citroen", "renault", "land rover", "jaguar", "maserati", "alfa romeo",
        "pioneer", "kenwood", "alpine", "jvc ", "sony xav", "车载", "车机", "行车", "汽车"
    )

    fun isSuspectedCarDevice(
        displayName: String,
        isHardwareCarAudioClass: Boolean = false
    ): Pair<Boolean, String> {
        val normalized = displayName.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank()) {
            return if (isHardwareCarAudioClass) true to "蓝牙车载音频设备类别 (CAR_AUDIO)" else false to ""
        }
        val hitExclusion = HEADPHONE_EXCLUSION_KEYWORDS.firstOrNull { kw -> normalized.contains(kw) }
        if (hitExclusion != null && !isHardwareCarAudioClass) {
            return false to ""
        }
        if (isHardwareCarAudioClass) {
            return true to "蓝牙车载音响硬件类别码 (CAR_AUDIO)"
        }
        // 精确单词匹配（如 "car" / "auto" 防止误伤普通词根如 "card" / "cartoon"）
        val wordTokens = normalized.split(Regex("[^a-z0-9\\u4e00-\\u9fa5]+")).filter { it.isNotBlank() }
        if ("car" in wordTokens || "auto" in wordTokens || "vehicle" in wordTokens || "multimedia" in wordTokens) {
            return true to "车载蓝牙名称特征"
        }
        val matchedBrand = CAR_BLUETOOTH_KEYWORDS.firstOrNull { kw -> normalized.contains(kw) }
        if (matchedBrand != null) {
            return true to "匹配车型/车机特征 ($matchedBrand)"
        }
        return false to ""
    }

    fun normalizeDeviceKey(macAddress: String?, displayName: String): String {
        val cleanMac = macAddress?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (cleanMac.matches(Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")) && cleanMac != "02:00:00:00:00:00") {
            return "MAC|$cleanMac"
        }
        return "NAME|${displayName.trim().lowercase(Locale.ROOT)}"
    }

    private fun matchesDeviceRecord(record: CarBluetoothDeviceRecord, sink: ConnectedBluetoothAudioSink): Boolean {
        if (record.deviceKey == sink.deviceKey) return true
        if (record.macAddress.isNotBlank() && sink.macAddress.isNotBlank() &&
            record.macAddress.equals(sink.macAddress, ignoreCase = true)
        ) {
            return true
        }
        val recName = record.displayName.trim().lowercase(Locale.ROOT)
        val sinkName = sink.displayName.trim().lowercase(Locale.ROOT)
        return recName.isNotBlank() && recName == sinkName
    }

    private fun sanitizeKnownDeviceRecord(rec: CarBluetoothDeviceRecord?): CarBluetoothDeviceRecord? {
        if (rec == null) return null
        val rawKey = (rec.deviceKey as String?)?.trim().orEmpty()
        val rawName = (rec.displayName as String?)?.trim().orEmpty()
        val rawMac = (rec.macAddress as String?)?.trim().orEmpty()
        @Suppress("USELESS_ELVIS")
        val rawClass: CarDeviceClassification = rec.classification ?: return null
        val resolvedKey = rawKey.ifBlank {
            if (rawMac.isBlank() && rawName.isBlank()) return null
            normalizeDeviceKey(rawMac, rawName)
        }
        return CarBluetoothDeviceRecord(
            deviceKey = resolvedKey,
            displayName = rawName.ifBlank { rawMac.ifBlank { "车载蓝牙设备" } },
            macAddress = rawMac,
            classification = rawClass,
            updatedAtMs = if (rec.updatedAtMs > 0L) rec.updatedAtMs else System.currentTimeMillis()
        )
    }

    private fun isValidEverydaySnapshotJson(rawJson: String?): Boolean {
        if (rawJson.isNullOrBlank()) return false
        return runCatching {
            val root = JsonParser.parseString(rawJson)
            if (!root.isJsonObject) return@runCatching false
            val obj = root.asJsonObject
            // 必须同时存在规范字段键，彻底拦截旧版混淆包残留的 a..F 字段 JSON，避免用默认值误覆写用户日常音效
            obj.has("presetName") && obj.has("audioFocusModeName") && obj.has("tenBandLevels")
        }.getOrDefault(false)
    }

    private fun parseValidEverydaySnapshot(rawJson: String?): PreCarEverydaySnapshot? {
        if (!isValidEverydaySnapshotJson(rawJson)) return null
        val snapshot = runCatching {
            KugouSigner.gson.fromJson(rawJson, PreCarEverydaySnapshot::class.java)
        }.getOrNull() ?: return null
        return snapshot.takeIf { isValidEverydaySnapshot(it) }
    }

    private fun isValidEverydaySnapshot(snapshot: PreCarEverydaySnapshot?): Boolean {
        if (snapshot == null) return false
        val presetName = snapshot.presetName as String?
        val reverbName = snapshot.reverbName as String?
        val focusName = snapshot.audioFocusModeName as String?
        val tenBands = snapshot.tenBandLevels as List<*>?
        return !presetName.isNullOrBlank() &&
            !reverbName.isNullOrBlank() &&
            !focusName.isNullOrBlank() &&
            tenBands != null
    }

    private fun resolveEverydayFocusModeFallback(): AudioFocusMode {
        val rawName = pendingSnapshotCache?.audioFocusModeName?.trim().orEmpty()
        if (rawName.isNotBlank()) {
            runCatching { AudioFocusMode.valueOf(rawName) }.getOrNull()?.let { return it }
        }
        return AudioFocusMode.EXCLUSIVE
    }

    @Synchronized
    fun init(context: Context) {
        if (!isPhoneCarFeatureSupported()) {
            _state.value = CarInterconnectState(featureEnabled = false, isCarModeActive = false)
            AudioEffectManager.clearCarCabinOverlay()
            return
        }
        val appCtx = context.applicationContext ?: context
        // 必须在捕获/还原日常音效快照之前同步确保 AudioEffectManager 已完成磁盘加载，避免冷启动竞态抓到空默认预设
        AudioEffectManager.init(appCtx)
        if (prefs == null) {
            prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        if (isStandaloneCarFlavor()) {
            if (!isInitialized) {
                isInitialized = true
                loadPersistedSettings()
            }
            activateStandaloneCarSessionInternal()
            return
        }
        if (isInitialized) {
            reevaluateActiveConnections(appCtx, newlyAddedBluetooth = false, isInitialBoot = false)
            return
        }
        isInitialized = true
        loadPersistedSettings()
        detectSystemCarUiMode(appCtx)
        reevaluateActiveConnections(appCtx, isInitialBoot = true)
    }

    internal fun resetForTest() {
        prefs = null
        isInitialized = false
        isTabletFlavorOverrideForTest = null
        isCarFlavorOverrideForTest = null
        synchronized(activeProjectionCounts) {
            activeProjectionCounts.clear()
        }
        temporarilyDismissedBluetoothKeys.clear()
        temporarilyDismissedProjection = false
        pendingSnapshotCache = null
        isUiModeCar = false
        onCarModeChangedCallback = null
        onApplyAudioFocusModeCallback = null
        onTriggerCarAutoPlayCallback = null
        _state.value = CarInterconnectState()
        AudioEffectManager.clearCarCabinOverlay()
        PlaybackService.updateSteeringWheelZeroLatency(false)
    }

    internal fun initWithSharedPreferencesForTest(testPrefs: SharedPreferences) {
        prefs = testPrefs
        isInitialized = true
        synchronized(activeProjectionCounts) {
            activeProjectionCounts.clear()
        }
        temporarilyDismissedBluetoothKeys.clear()
        temporarilyDismissedProjection = false
        pendingSnapshotCache = null
        isUiModeCar = false
        _state.value = CarInterconnectState()
        loadPersistedSettings()
        if (!isPhoneCarFeatureSupported()) {
            _state.value = _state.value.copy(featureEnabled = false, isCarModeActive = false)
            AudioEffectManager.clearCarCabinOverlay()
        } else if (isStandaloneCarFlavor()) {
            activateStandaloneCarSessionInternal()
        } else {
            // 冷启动自愈校验：若未连接任何车机但存在遗留快照，立即回滚日常音效与焦点
            restoreEverydaySnapshotIfPending()
        }
    }

    private fun activateStandaloneCarSessionInternal() {
        val wasActive = _state.value.isCarModeActive
        _state.update {
            it.copy(
                featureEnabled = true,
                isCarModeActive = true,
                activeConnectionSource = CarConnectionSource.STANDALONE_CAR_UNIT,
                activeCarDeviceName = "智能座舱原生直连",
                pendingPromptCandidate = null
            )
        }
        syncActiveCarParametersToAudioEngine()
        if (!wasActive) {
            onCarModeChangedCallback?.invoke(true)
        }
    }

    private fun loadPersistedSettings() {
        val p = prefs ?: return
        val standaloneCar = isStandaloneCarFlavor()
        val enabled = if (standaloneCar) true else p.getBoolean(KEY_FEATURE_ENABLED, true)
        val cabinMode = runCatching {
            CarCabinAcousticMode.valueOf(
                p.getString(KEY_CABIN_MODE, CarCabinAcousticMode.DRIVER_SWEET_SPOT.name)
                    ?: CarCabinAcousticMode.DRIVER_SWEET_SPOT.name
            )
        }.getOrDefault(CarCabinAcousticMode.DRIVER_SWEET_SPOT)
        val smartDuck = p.getBoolean(KEY_SMART_DUCK_ENABLED, true)
        val duckGain = p.getFloat(KEY_NAV_DUCK_GAIN, 0.28f).coerceIn(0.15f, 0.50f)
        val spectralDuck = p.getBoolean(KEY_SPECTRAL_DUCK_ENABLED, true)
        // 车机独立包运行在本机，仪表盘/系统原生卡片歌词推送默认关闭（设置可选）；手机端蓝牙 AVRCP 歌词默认开启
        val btLyric = p.getBoolean(KEY_BT_LYRIC_ENABLED, !standaloneCar)
        val interludeStyle = runCatching {
            CarInterludeStyle.valueOf(
                p.getString(KEY_INTERLUDE_STYLE, CarInterludeStyle.BREATHING_NOTES.name)
                    ?: CarInterludeStyle.BREATHING_NOTES.name
            )
        }.getOrDefault(CarInterludeStyle.BREATHING_NOTES)
        val defaultOffset = if (standaloneCar) 0L else -250L
        val lyricOffset = p.getLong(KEY_CAR_LYRIC_OFFSET_MS, defaultOffset).coerceIn(-1500L, 1000L)
        val autoPlayCar = p.getBoolean(KEY_AUTO_PLAY_CAR, standaloneCar)
        val zeroLatency = p.getBoolean(KEY_STEERING_ZERO_LATENCY, true)

        val knownJson = p.getString(KEY_KNOWN_DEVICES_JSON, null)
        val knownList: List<CarBluetoothDeviceRecord> = if (!knownJson.isNullOrBlank()) {
            runCatching {
                val root = JsonParser.parseString(knownJson)
                if (!root.isJsonArray) return@runCatching emptyList()
                root.asJsonArray.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val obj = el.asJsonObject
                    if (!obj.has("deviceKey") || !obj.has("classification")) return@mapNotNull null
                    val parsed = runCatching {
                        KugouSigner.gson.fromJson(obj, CarBluetoothDeviceRecord::class.java)
                    }.getOrNull()
                    sanitizeKnownDeviceRecord(parsed)
                }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val pendingRaw = p.getString(KEY_PENDING_SNAPSHOT_JSON, null)
        pendingSnapshotCache = parseValidEverydaySnapshot(pendingRaw)

        _state.update { cur ->
            cur.copy(
                featureEnabled = enabled && isPhoneCarFeatureSupported(),
                cabinAcousticMode = cabinMode,
                smartNavDuckingEnabled = smartDuck,
                navDuckingTargetGain = duckGain,
                navSpectralDuckingEnabled = spectralDuck,
                carBluetoothLyricEnabled = btLyric,
                carInterludeStyle = interludeStyle,
                carLyricOffsetMs = lyricOffset,
                autoPlayOnCarConnect = autoPlayCar,
                steeringWheelZeroLatencyEnabled = zeroLatency,
                knownDevices = knownList
            )
        }
    }

    private fun saveKnownDevices(list: List<CarBluetoothDeviceRecord>) {
        val p = prefs ?: return
        runCatching {
            p.edit().putString(KEY_KNOWN_DEVICES_JSON, KugouSigner.gson.toJson(list)).apply()
        }
    }

    private fun detectSystemCarUiMode(context: Context) {
        val uiModeMgr = runCatching {
            context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        }.getOrNull()
        isUiModeCar = uiModeMgr?.currentModeType == Configuration.UI_MODE_TYPE_CAR
    }

    fun onProjectionControllerConnected(context: Context?, packageName: String) {
        if (!isPhoneCarFeatureSupported()) return
        val cleanPkg = packageName.trim()
        if (cleanPkg.isBlank()) return
        synchronized(activeProjectionCounts) {
            val prev = activeProjectionCounts[cleanPkg] ?: 0
            activeProjectionCounts[cleanPkg] = prev + 1
            if (prev == 0) {
                temporarilyDismissedProjection = false
            }
        }
        evaluateAndApplyCarSession(context, newlyAddedBluetooth = false, isInitialBoot = false)
    }

    fun onProjectionControllerDisconnected(context: Context?, packageName: String) {
        if (!isPhoneCarFeatureSupported()) return
        val cleanPkg = packageName.trim()
        if (cleanPkg.isBlank()) return
        synchronized(activeProjectionCounts) {
            val prev = activeProjectionCounts[cleanPkg] ?: 0
            if (prev <= 1) {
                activeProjectionCounts.remove(cleanPkg)
            } else {
                activeProjectionCounts[cleanPkg] = prev - 1
            }
        }
        evaluateAndApplyCarSession(context, newlyAddedBluetooth = false, isInitialBoot = false)
    }

    fun onBluetoothCarPermissionDisabled(context: Context?) {
        _state.update { it.copy(pairedBluetoothDevices = emptyList(), pendingPromptCandidate = null) }
        if (context != null) {
            reevaluateActiveConnections(context)
        } else {
            evaluateAndApplyCarSession(null, newlyAddedBluetooth = false, isInitialBoot = false)
        }
    }

    fun reevaluateActiveConnections(
        context: Context,
        newlyAddedBluetooth: Boolean = false,
        isInitialBoot: Boolean = false
    ) {
        if (!isPhoneCarFeatureSupported()) return
        detectSystemCarUiMode(context)
        val sinks = enumerateConnectedBluetoothAudioSinks(context)
        _state.update { it.copy(currentBluetoothSinks = sinks) }
        evaluateAndApplyCarSession(context, newlyAddedBluetooth = newlyAddedBluetooth, isInitialBoot = isInitialBoot)
    }

    /**
     * 供单元测试或 AudioDeviceCallback 直接注入当前连接的蓝牙音频输出设备列表
     */
    internal fun onBluetoothSinksUpdated(
        context: Context?,
        sinks: List<ConnectedBluetoothAudioSink>,
        newlyAddedBluetooth: Boolean,
        isInitialBoot: Boolean = false
    ) {
        if (!isPhoneCarFeatureSupported()) return
        _state.update { it.copy(currentBluetoothSinks = sinks) }
        evaluateAndApplyCarSession(context, newlyAddedBluetooth = newlyAddedBluetooth, isInitialBoot = isInitialBoot)
    }

    @SuppressLint("MissingPermission")
    fun enumerateConnectedBluetoothAudioSinks(context: Context): List<ConnectedBluetoothAudioSink> {
        if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.AUDIO_HARDWARE)) {
            return emptyList()
        }
        val am = runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()
            ?: return emptyList()
        val rawOutputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull()
            ?: return emptyList()

        val canUseBtApi = AppPermissionManager.isPermissionEnabled(AppPermissionType.BLUETOOTH_CAR_INTERCONNECT) &&
            AppPermissionManager.hasBluetoothConnectSystemPermission(context)

        val bondedByName = HashMap<String, BluetoothDevice>()
        val bondedByMac = HashMap<String, BluetoothDevice>()
        if (canUseBtApi) {
            runCatching {
                val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                btMgr?.adapter?.bondedDevices?.forEach { dev ->
                    val addr = runCatching { dev.address }.getOrNull().orEmpty()
                    val name = runCatching { dev.name }.getOrNull().orEmpty()
                    if (addr.isNotBlank()) bondedByMac[addr.uppercase(Locale.ROOT)] = dev
                    if (name.isNotBlank()) bondedByName[name.trim().lowercase(Locale.ROOT)] = dev
                }
            }
        }

        val result = LinkedHashMap<String, ConnectedBluetoothAudioSink>()
        for (dev in rawOutputs) {
            if (!dev.isSink) continue
            val isBtType = PlaybackService.isBluetoothAudioDeviceType(dev.type) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dev.type == AudioDeviceInfo.TYPE_BUS)
            if (!isBtType) continue

            val rawProdName = runCatching { dev.productName?.toString()?.trim() }.getOrNull().orEmpty()
            val rawAddress = if (canUseBtApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { dev.address.trim() }.getOrNull().orEmpty()
            } else {
                ""
            }
            val matchedBtDev = if (canUseBtApi) {
                bondedByMac[rawAddress.uppercase(Locale.ROOT)]
                    ?: bondedByName[rawProdName.lowercase(Locale.ROOT)]
            } else null

            val resolvedName = runCatching {
                if (canUseBtApi) matchedBtDev?.name?.trim()?.takeIf { it.isNotBlank() } else null
            }.getOrNull() ?: rawProdName.ifBlank { "蓝牙音频设备" }

            val resolvedMac = runCatching {
                if (canUseBtApi) matchedBtDev?.address?.trim().orEmpty() else ""
            }.getOrNull().orEmpty().ifBlank { rawAddress }

            val isCarClass = if (canUseBtApi && matchedBtDev != null) {
                runCatching {
                    val devCls = matchedBtDev.bluetoothClass?.deviceClass
                    devCls == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                        devCls == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
                }.getOrDefault(false)
            } else {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dev.type == AudioDeviceInfo.TYPE_BUS
            }

            val key = normalizeDeviceKey(resolvedMac, resolvedName)
            result[key] = ConnectedBluetoothAudioSink(
                deviceKey = key,
                displayName = resolvedName,
                macAddress = resolvedMac,
                isHardwareCarAudioClass = isCarClass
            )
        }
        return result.values.toList()
    }

    @SuppressLint("MissingPermission")
    fun refreshPairedBluetoothDevices(context: Context): Boolean {
        if (!isPhoneCarFeatureSupported()) return false
        if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.BLUETOOTH_CAR_INTERCONNECT)) return false
        if (!AppPermissionManager.hasBluetoothConnectSystemPermission(context)) return false

        val list = runCatching {
            val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val bonded = btMgr?.adapter?.bondedDevices.orEmpty()
            bonded.mapNotNull { dev ->
                val name = runCatching { dev.name?.trim() }.getOrNull().orEmpty()
                val mac = runCatching { dev.address?.trim() }.getOrNull().orEmpty()
                if (name.isBlank() && mac.isBlank()) return@mapNotNull null
                val majorCls = runCatching { dev.bluetoothClass?.majorDeviceClass }.getOrNull()
                // 过滤掉纯键鼠/手机/打印机等非音视频与未分类外设，保留音频/车载及通用蓝牙外设
                if (majorCls != null &&
                    majorCls != BluetoothClass.Device.Major.AUDIO_VIDEO &&
                    majorCls != BluetoothClass.Device.Major.UNCATEGORIZED &&
                    majorCls != BluetoothClass.Device.Major.MISC
                ) {
                    return@mapNotNull null
                }
                val devCls = runCatching { dev.bluetoothClass?.deviceClass }.getOrNull()
                val isCarCls = devCls == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                    devCls == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
                val display = name.ifBlank { mac }
                ConnectedBluetoothAudioSink(
                    deviceKey = normalizeDeviceKey(mac, display),
                    displayName = display,
                    macAddress = mac,
                    isHardwareCarAudioClass = isCarCls
                )
            }.sortedWith(
                compareByDescending<ConnectedBluetoothAudioSink> { it.isHardwareCarAudioClass }
                    .thenBy { it.displayName.lowercase(Locale.ROOT) }
            )
        }.getOrDefault(emptyList())

        _state.update { it.copy(pairedBluetoothDevices = list) }
        return true
    }

    @Synchronized
    private fun evaluateAndApplyCarSession(
        context: Context?,
        newlyAddedBluetooth: Boolean,
        isInitialBoot: Boolean
    ) {
        if (isStandaloneCarFlavor()) {
            activateStandaloneCarSessionInternal()
            return
        }
        val cur = _state.value
        val wasActive = cur.isCarModeActive

        if (!isPhoneCarFeatureSupported() || !cur.featureEnabled) {
            deactivateCarSessionInternal(clearManualPreview = true)
            return
        }

        val projectionPkg = synchronized(activeProjectionCounts) {
            activeProjectionCounts.entries.firstOrNull { it.value > 0 }?.key
        }
        // 清理已断开连接设备的临时退出标记，确保下次重新上车连接时自动恢复感知
        if (temporarilyDismissedBluetoothKeys.isNotEmpty()) {
            val activeKeysAndNames = buildSet {
                cur.currentBluetoothSinks.forEach { s ->
                    add(s.deviceKey)
                    cur.knownDevices.filter { matchesDeviceRecord(it, s) }.forEach { add(it.deviceKey) }
                }
            }
            temporarilyDismissedBluetoothKeys.retainAll(activeKeysAndNames)
        }
        if (projectionPkg == null && !isUiModeCar) {
            temporarilyDismissedProjection = false
        }

        val confirmedSink = if (AppPermissionManager.isPermissionEnabled(AppPermissionType.BLUETOOTH_CAR_INTERCONNECT)) {
            cur.currentBluetoothSinks.firstOrNull { sink ->
                val matchedRecord = cur.knownDevices.firstOrNull { rec ->
                    rec.classification == CarDeviceClassification.CONFIRMED_CAR && matchesDeviceRecord(rec, sink)
                }
                matchedRecord != null &&
                    sink.deviceKey !in temporarilyDismissedBluetoothKeys &&
                    matchedRecord.deviceKey !in temporarilyDismissedBluetoothKeys
            }
        } else null

        val effectiveProjectionPkg = if (temporarilyDismissedProjection) null else projectionPkg
        val effectiveUiModeCar = isUiModeCar && !temporarilyDismissedProjection

        // 计算是否需要弹出首次疑似车载蓝牙询问卡
        val nextPrompt: PendingCarPromptCandidate? = if (
            effectiveProjectionPkg == null &&
            !effectiveUiModeCar &&
            confirmedSink == null &&
            !cur.manualPreviewActive &&
            AppPermissionManager.isPermissionEnabled(AppPermissionType.BLUETOOTH_CAR_INTERCONNECT)
        ) {
            cur.currentBluetoothSinks.firstNotNullOfOrNull { sink ->
                val alreadyDecided = cur.knownDevices.any { rec -> matchesDeviceRecord(rec, sink) }
                if (alreadyDecided) {
                    null
                } else {
                    val (suspected, reason) = isSuspectedCarDevice(
                        displayName = sink.displayName,
                        isHardwareCarAudioClass = sink.isHardwareCarAudioClass
                    )
                    if (suspected) {
                        PendingCarPromptCandidate(
                            deviceKey = sink.deviceKey,
                            displayName = sink.displayName,
                            macAddress = sink.macAddress,
                            matchedReason = reason
                        )
                    } else null
                }
            }
        } else {
            null
        }

        val (targetSource, targetDeviceName) = when {
            confirmedSink != null -> CarConnectionSource.CONFIRMED_BLUETOOTH to confirmedSink.displayName
            effectiveProjectionPkg != null -> CarConnectionSource.PROJECTION_HOST to resolveFriendlyHostName(effectiveProjectionPkg)
            effectiveUiModeCar -> CarConnectionSource.PROJECTION_HOST to "车载系统投屏"
            cur.manualPreviewActive -> CarConnectionSource.MANUAL_PREVIEW to "车载声学预览"
            else -> CarConnectionSource.NONE to ""
        }

        if (targetSource != CarConnectionSource.NONE) {
            activateCarSessionInternal(
                source = targetSource,
                deviceName = targetDeviceName,
                pendingPrompt = null
            )
            if (!wasActive && !isInitialBoot && newlyAddedBluetooth &&
                targetSource == CarConnectionSource.CONFIRMED_BLUETOOTH &&
                _state.value.autoPlayOnCarConnect &&
                context != null
            ) {
                onTriggerCarAutoPlayCallback?.invoke(context)
            }
        } else {
            deactivateCarSessionInternal(clearManualPreview = false, nextPrompt = nextPrompt)
        }
    }

    private fun resolveFriendlyHostName(pkg: String): String = when {
        pkg.contains("gearhead") -> "Android Auto"
        pkg.contains("hicar") -> "华为 HiCar"
        pkg.contains("miui.carlink") -> "小米 CarWith"
        pkg.contains("iccoa") -> "ICCOA Carlink"
        pkg.contains("ocar") -> "OPPO Car+"
        pkg.contains("vivo") -> "vivo Jovi InCar"
        pkg.contains("carlife") -> "百度 CarLife"
        pkg.contains("flymelink") -> "Flyme Auto"
        else -> "车联中控"
    }

    private fun activateCarSessionInternal(
        source: CarConnectionSource,
        deviceName: String,
        pendingPrompt: PendingCarPromptCandidate?
    ) {
        val wasActive = _state.value.isCarModeActive
        captureEverydaySnapshotIfNeeded()

        _state.update {
            it.copy(
                isCarModeActive = true,
                activeConnectionSource = source,
                activeCarDeviceName = deviceName,
                pendingPromptCandidate = pendingPrompt
            )
        }

        syncActiveCarParametersToAudioEngine()
        if (!wasActive) {
            onCarModeChangedCallback?.invoke(true)
        }
    }

    private fun deactivateCarSessionInternal(
        clearManualPreview: Boolean,
        nextPrompt: PendingCarPromptCandidate? = null
    ) {
        val wasActive = _state.value.isCarModeActive
        AudioEffectManager.clearCarCabinOverlay()
        PlaybackService.updateSteeringWheelZeroLatency(false)
        restoreEverydaySnapshotIfPending()

        _state.update {
            it.copy(
                isCarModeActive = false,
                activeConnectionSource = CarConnectionSource.NONE,
                activeCarDeviceName = "",
                manualPreviewActive = if (clearManualPreview) false else it.manualPreviewActive,
                pendingPromptCandidate = nextPrompt
            )
        }
        if (wasActive) {
            onCarModeChangedCallback?.invoke(false)
        }
    }

    private fun syncActiveCarParametersToAudioEngine() {
        val cur = _state.value
        if (!cur.isCarModeActive) {
            AudioEffectManager.clearCarCabinOverlay()
            PlaybackService.updateSteeringWheelZeroLatency(false)
            return
        }
        AudioEffectManager.applyCarCabinOverlay(
            CarCabinDspOverlay(
                active = cur.cabinAcousticMode != CarCabinAcousticMode.DIRECT_BYPASS,
                mode = cur.cabinAcousticMode,
                leftChannelItdDelayMs = cur.cabinAcousticMode.leftItdDelayMs,
                rightDistanceTrimDb = cur.cabinAcousticMode.rightDistanceTrimDb,
                stageLiftDb = cur.cabinAcousticMode.stageLiftDb,
                roadNoiseSubBoostDb = cur.cabinAcousticMode.roadNoiseSubBoostDb,
                doorAntiBoomCutDb = cur.cabinAcousticMode.doorAntiBoomCutDb,
                stereoWidthBoost = cur.cabinAcousticMode.stereoWidthBoost
            )
        )
        // 车载模式下自动启用跨曲目响度均衡（避免行车途中不同音源音量忽大忽小）；切至「原车音响直通」时还原上车前响度开关
        if (cur.cabinAcousticMode != CarCabinAcousticMode.DIRECT_BYPASS) {
            AudioEffectManager.setVolumeNormalizationTransientForCar(true)
        } else {
            val originalVolNorm = pendingSnapshotCache?.volumeNormalization
                ?: AudioEffectManager.state.value.volumeNormalizationEnabled
            AudioEffectManager.setVolumeNormalizationTransientForCar(originalVolNorm)
        }
        if (cur.smartNavDuckingEnabled) {
            onApplyAudioFocusModeCallback?.invoke(AudioFocusMode.DUCK)
                ?: PlaybackService.updateAudioFocusMode(AudioFocusMode.DUCK)
        }
        PlaybackService.updateSteeringWheelZeroLatency(cur.steeringWheelZeroLatencyEnabled)
    }

    private fun captureEverydaySnapshotIfNeeded() {
        val p = prefs ?: return
        val existingRaw = p.getString(KEY_PENDING_SNAPSHOT_JSON, null)
        if (!existingRaw.isNullOrBlank()) {
            // 已有未归还的日常快照（例如车载途中切换了另一个蓝牙或重启恢复），绝不覆盖原始日常快照；若是旧版损坏快照则允许用当前有效状态覆写
            val existingValid = parseValidEverydaySnapshot(existingRaw)
            if (existingValid != null) {
                if (pendingSnapshotCache == null) {
                    pendingSnapshotCache = existingValid
                }
                return
            }
        }
        val dsp = AudioEffectManager.state.value
        val snapshot = PreCarEverydaySnapshot(
            effectEnabled = dsp.isEnabled,
            presetName = dsp.currentPreset.name,
            bassBoost = dsp.bassBoostStrength,
            bassPunch = dsp.bassPunchStrength,
            bassCutoffHz = dsp.bassCutoffHz,
            virt = dsp.virtualizerStrength,
            depth = dsp.soundstageDepthStrength,
            crossfeed = dsp.crossfeedStrength,
            clarity = dsp.vocalClarityStrength,
            deEsser = dsp.deEsserStrength,
            air = dsp.trebleAirStrength,
            warmth = dsp.analogWarmthStrength,
            transientAttack = dsp.transientAttackStrength,
            rotSpeed = dsp.surroundRotationSpeed,
            loudness = dsp.loudnessGainMb,
            preampDb = dsp.preampGainDb,
            channelBalance = dsp.channelBalance,
            antiClip = dsp.antiClippingEnabled,
            eqLoudness = dsp.equalLoudnessCompensation,
            volumeNormalization = dsp.volumeNormalizationEnabled,
            reverbName = dsp.reverbMode.name,
            reverbWet = dsp.reverbWetMix,
            reverbPreDelay = dsp.reverbPreDelayMs,
            reverbDamp = dsp.reverbDamping,
            bandLevels = dsp.bandLevels,
            tenBandLevels = dsp.tenBandLevels,
            tenBandQFactors = dsp.tenBandQFactors,
            tenBandCenterHz = dsp.tenBandCenterHz,
            tenBandFilterTypeNames = dsp.tenBandFilterTypes.map { it.name },
            highPassCutoffHz = dsp.highPassCutoffHz,
            lowPassCutoffHz = dsp.lowPassCutoffHz,
            audioFocusModeName = PlaybackService.currentAudioFocusMode.name
        )
        pendingSnapshotCache = snapshot
        runCatching {
            p.edit().putString(KEY_PENDING_SNAPSHOT_JSON, KugouSigner.gson.toJson(snapshot)).commit()
        }
    }

    private fun restoreEverydaySnapshotIfPending() {
        val p = prefs ?: return
        val rawJson = p.getString(KEY_PENDING_SNAPSHOT_JSON, null)
        if (rawJson.isNullOrBlank()) {
            pendingSnapshotCache = null
            return
        }
        val snapshot = parseValidEverydaySnapshot(rawJson)
        runCatching {
            p.edit().remove(KEY_PENDING_SNAPSHOT_JSON).commit()
        }
        pendingSnapshotCache = null
        if (snapshot != null) {
            runCatching {
                AudioEffectManager.restoreFromEverydaySnapshot(snapshot)
            }
            val restoredFocus = runCatching {
                AudioFocusMode.valueOf((snapshot.audioFocusModeName as String?).orEmpty())
            }.getOrDefault(AudioFocusMode.EXCLUSIVE)
            runCatching {
                onApplyAudioFocusModeCallback?.invoke(restoredFocus)
                    ?: PlaybackService.updateAudioFocusMode(restoredFocus)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 用户交互与设置修改接口
    // -------------------------------------------------------------------------

    fun confirmPendingPromptAsCar(context: Context?) {
        val cand = _state.value.pendingPromptCandidate ?: return
        setDeviceClassification(
            context = context,
            deviceKey = cand.deviceKey,
            displayName = cand.displayName,
            macAddress = cand.macAddress,
            classification = CarDeviceClassification.CONFIRMED_CAR
        )
    }

    fun dismissPendingPromptAsNonCar(context: Context?) {
        val cand = _state.value.pendingPromptCandidate ?: return
        setDeviceClassification(
            context = context,
            deviceKey = cand.deviceKey,
            displayName = cand.displayName,
            macAddress = cand.macAddress,
            classification = CarDeviceClassification.IGNORED_NON_CAR
        )
    }

    fun setDeviceClassification(
        context: Context?,
        deviceKey: String,
        displayName: String,
        macAddress: String = "",
        classification: CarDeviceClassification,
        triggerAutoPlayIfEligible: Boolean = false
    ) {
        val normalizedKey = deviceKey.ifBlank { normalizeDeviceKey(macAddress, displayName) }
        if (classification == CarDeviceClassification.CONFIRMED_CAR) {
            temporarilyDismissedBluetoothKeys.remove(normalizedKey)
            _state.value.currentBluetoothSinks
                .filter { it.deviceKey == normalizedKey || (macAddress.isNotBlank() && it.macAddress.equals(macAddress, ignoreCase = true)) }
                .forEach { temporarilyDismissedBluetoothKeys.remove(it.deviceKey) }
        }
        val newRecord = CarBluetoothDeviceRecord(
            deviceKey = normalizedKey,
            displayName = displayName.trim().ifBlank { "车载蓝牙设备" },
            macAddress = macAddress.trim(),
            classification = classification,
            updatedAtMs = System.currentTimeMillis()
        )
        val updatedList = buildList {
            add(newRecord)
            _state.value.knownDevices.forEach { existing ->
                val sameKey = existing.deviceKey == normalizedKey ||
                    (macAddress.isNotBlank() && existing.macAddress.equals(macAddress, ignoreCase = true))
                if (!sameKey) add(existing)
            }
        }
        _state.update { it.copy(knownDevices = updatedList, pendingPromptCandidate = null) }
        saveKnownDevices(updatedList)
        evaluateAndApplyCarSession(
            context = context,
            newlyAddedBluetooth = triggerAutoPlayIfEligible && classification == CarDeviceClassification.CONFIRMED_CAR,
            isInitialBoot = false
        )
    }

    fun removeKnownDevice(context: Context?, deviceKey: String) {
        temporarilyDismissedBluetoothKeys.remove(deviceKey)
        val updated = _state.value.knownDevices.filterNot { it.deviceKey == deviceKey }
        _state.update { it.copy(knownDevices = updated) }
        saveKnownDevices(updated)
        evaluateAndApplyCarSession(context, newlyAddedBluetooth = false, isInitialBoot = false)
    }

    fun setFeatureEnabled(context: Context?, enabled: Boolean) {
        if (!isPhoneCarFeatureSupported()) return
        if (enabled) {
            temporarilyDismissedBluetoothKeys.clear()
            temporarilyDismissedProjection = false
        }
        prefs?.edit()?.putBoolean(KEY_FEATURE_ENABLED, enabled)?.apply()
        _state.update { it.copy(featureEnabled = enabled) }
        evaluateAndApplyCarSession(context, newlyAddedBluetooth = false, isInitialBoot = false)
    }

    fun setManualPreviewActive(context: Context?, active: Boolean) {
        if (!isPhoneCarFeatureSupported()) return
        _state.update { it.copy(manualPreviewActive = active) }
        evaluateAndApplyCarSession(context, newlyAddedBluetooth = false, isInitialBoot = false)
    }

    /**
     * 临时退出本次车载会话并立即还原手机日常音效与音频焦点：
     * - 手动预览：关闭预览；
     * - 已认证车机蓝牙：仅记录本次连接会话临时跳过，绝不篡改或移除「我的车机」白名单，断开重连后自动恢复感知；
     * - 车联投屏 / 系统车机模式：标记本次投屏会话临时退出，断开投屏重连后自动恢复。
     */
    @Synchronized
    fun disconnectCurrentCarSessionTemporarily(context: Context?) {
        val cur = _state.value
        if (cur.manualPreviewActive) {
            _state.update { it.copy(manualPreviewActive = false) }
        }
        cur.currentBluetoothSinks.forEach { sink ->
            val matchedRecord = cur.knownDevices.firstOrNull { rec ->
                rec.classification == CarDeviceClassification.CONFIRMED_CAR && matchesDeviceRecord(rec, sink)
            }
            if (matchedRecord != null) {
                temporarilyDismissedBluetoothKeys.add(sink.deviceKey)
                temporarilyDismissedBluetoothKeys.add(matchedRecord.deviceKey)
            }
        }
        val hasActiveProjection = synchronized(activeProjectionCounts) {
            activeProjectionCounts.values.any { it > 0 }
        }
        if (hasActiveProjection || isUiModeCar) {
            temporarilyDismissedProjection = true
        }
        evaluateAndApplyCarSession(context, newlyAddedBluetooth = false, isInitialBoot = false)
    }

    fun setCabinAcousticMode(mode: CarCabinAcousticMode) {
        prefs?.edit()?.putString(KEY_CABIN_MODE, mode.name)?.apply()
        _state.update { it.copy(cabinAcousticMode = mode) }
        if (_state.value.isCarModeActive) {
            syncActiveCarParametersToAudioEngine()
        }
    }

    fun setSmartNavDuckingEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SMART_DUCK_ENABLED, enabled)?.apply()
        _state.update { it.copy(smartNavDuckingEnabled = enabled) }
        if (_state.value.isCarModeActive) {
            if (enabled) {
                onApplyAudioFocusModeCallback?.invoke(AudioFocusMode.DUCK)
                    ?: PlaybackService.updateAudioFocusMode(AudioFocusMode.DUCK)
            } else {
                val restoredFocus = resolveEverydayFocusModeFallback()
                onApplyAudioFocusModeCallback?.invoke(restoredFocus)
                    ?: PlaybackService.updateAudioFocusMode(restoredFocus)
            }
            AudioEffectManager.dspConfigVersion.incrementAndGet()
        }
    }

    fun setNavDuckingTargetGain(gain: Float) {
        val clamped = gain.coerceIn(0.15f, 0.50f)
        prefs?.edit()?.putFloat(KEY_NAV_DUCK_GAIN, clamped)?.apply()
        _state.update { it.copy(navDuckingTargetGain = clamped) }
        val cur = _state.value
        if (cur.isCarModeActive && cur.smartNavDuckingEnabled && PlaybackService.audioFocusDuckGain < 0.99f) {
            PlaybackService.audioFocusDuckGain = clamped
        }
    }

    fun setNavSpectralDuckingEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SPECTRAL_DUCK_ENABLED, enabled)?.apply()
        _state.update { it.copy(navSpectralDuckingEnabled = enabled) }
        AudioEffectManager.dspConfigVersion.incrementAndGet()
    }

    fun setCarBluetoothLyricEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_BT_LYRIC_ENABLED, enabled)?.apply()
        _state.update { it.copy(carBluetoothLyricEnabled = enabled) }
        onCarModeChangedCallback?.invoke(_state.value.isCarModeActive)
    }

    fun setCarInterludeStyle(style: CarInterludeStyle) {
        prefs?.edit()?.putString(KEY_INTERLUDE_STYLE, style.name)?.apply()
        _state.update { it.copy(carInterludeStyle = style) }
        onCarModeChangedCallback?.invoke(_state.value.isCarModeActive)
    }

    fun setCarLyricOffsetMs(offsetMs: Long) {
        val clamped = offsetMs.coerceIn(-1500L, 1000L)
        prefs?.edit()?.putLong(KEY_CAR_LYRIC_OFFSET_MS, clamped)?.apply()
        _state.update { it.copy(carLyricOffsetMs = clamped) }
        onCarModeChangedCallback?.invoke(_state.value.isCarModeActive)
    }

    fun setAutoPlayOnCarConnect(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_AUTO_PLAY_CAR, enabled)?.apply()
        _state.update { it.copy(autoPlayOnCarConnect = enabled) }
    }

    fun setSteeringWheelZeroLatencyEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_STEERING_ZERO_LATENCY, enabled)?.apply()
        _state.update { it.copy(steeringWheelZeroLatencyEnabled = enabled) }
        if (_state.value.isCarModeActive) {
            PlaybackService.updateSteeringWheelZeroLatency(enabled)
        }
    }
}
