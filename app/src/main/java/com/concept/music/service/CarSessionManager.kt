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
import com.google.gson.reflect.TypeToken
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
    PROJECTION_HOST("车联投屏互联"),
    CONFIRMED_BLUETOOTH("车载蓝牙直连"),
    MANUAL_PREVIEW("手动车载预览")
}

enum class CarDeviceClassification {
    CONFIRMED_CAR,
    IGNORED_NON_CAR
}

enum class CarInterludeStyle(val label: String, val subtitle: String) {
    BREATHING_NOTES("♪  •••  ♪ 音符呼吸", "超过 5 秒器乐间奏时优雅展示呼吸音符，防止车机屏幕空白塌陷"),
    SONG_TITLE_NOTES("♪  歌名  ♪", "超过 5 秒器乐间奏时展示带音符的歌曲原名")
}

@Immutable
data class CarBluetoothDeviceRecord(
    val deviceKey: String,
    val displayName: String,
    val macAddress: String = "",
    val classification: CarDeviceClassification,
    val updatedAtMs: Long = System.currentTimeMillis()
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
    val effectEnabled: Boolean,
    val presetName: String,
    val bassBoost: Int,
    val bassPunch: Int,
    val bassCutoffHz: Int,
    val virt: Int,
    val depth: Int,
    val crossfeed: Int,
    val clarity: Int,
    val deEsser: Int,
    val air: Int,
    val warmth: Int,
    val transientAttack: Int,
    val rotSpeed: Int,
    val loudness: Int,
    val preampDb: Float,
    val channelBalance: Float,
    val antiClip: Boolean,
    val eqLoudness: Boolean,
    val volumeNormalization: Boolean,
    val reverbName: String,
    val reverbWet: Int,
    val reverbPreDelay: Int,
    val reverbDamp: Int,
    val bandLevels: List<Int>,
    val tenBandLevels: List<Int>,
    val tenBandQFactors: List<Float>,
    val tenBandCenterHz: List<Float>,
    val tenBandFilterTypeNames: List<String>,
    val highPassCutoffHz: Int,
    val lowPassCutoffHz: Int,
    val audioFocusModeName: String
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

    fun isPhoneCarFeatureSupported(): Boolean {
        val isTablet = isTabletFlavorOverrideForTest ?: BuildConfig.IS_TABLET_FLAVOR
        return !isTablet
    }

    private var prefs: SharedPreferences? = null
    private val activeProjectionPackages = LinkedHashSet<String>()

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

    @Synchronized
    fun init(context: Context) {
        if (!isPhoneCarFeatureSupported()) {
            _state.value = CarInterconnectState(featureEnabled = false, isCarModeActive = false)
            AudioEffectManager.clearCarCabinOverlay()
            return
        }
        val appCtx = context.applicationContext ?: context
        if (prefs == null) {
            prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        loadPersistedSettings()
        detectSystemCarUiMode(appCtx)
        reevaluateActiveConnections(appCtx, isInitialBoot = true)
    }

    internal fun resetForTest() {
        prefs = null
        isTabletFlavorOverrideForTest = null
        synchronized(activeProjectionPackages) {
            activeProjectionPackages.clear()
        }
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
        synchronized(activeProjectionPackages) {
            activeProjectionPackages.clear()
        }
        isUiModeCar = false
        _state.value = CarInterconnectState()
        loadPersistedSettings()
        if (!isPhoneCarFeatureSupported()) {
            _state.value = _state.value.copy(featureEnabled = false, isCarModeActive = false)
            AudioEffectManager.clearCarCabinOverlay()
        } else {
            // 冷启动自愈校验：若未连接任何车机但存在遗留快照，立即回滚日常音效与焦点
            restoreEverydaySnapshotIfPending()
        }
    }

    private fun loadPersistedSettings() {
        val p = prefs ?: return
        val enabled = p.getBoolean(KEY_FEATURE_ENABLED, true)
        val cabinMode = runCatching {
            CarCabinAcousticMode.valueOf(
                p.getString(KEY_CABIN_MODE, CarCabinAcousticMode.DRIVER_SWEET_SPOT.name)
                    ?: CarCabinAcousticMode.DRIVER_SWEET_SPOT.name
            )
        }.getOrDefault(CarCabinAcousticMode.DRIVER_SWEET_SPOT)
        val smartDuck = p.getBoolean(KEY_SMART_DUCK_ENABLED, true)
        val duckGain = p.getFloat(KEY_NAV_DUCK_GAIN, 0.28f).coerceIn(0.15f, 0.50f)
        val spectralDuck = p.getBoolean(KEY_SPECTRAL_DUCK_ENABLED, true)
        val btLyric = p.getBoolean(KEY_BT_LYRIC_ENABLED, true)
        val interludeStyle = runCatching {
            CarInterludeStyle.valueOf(
                p.getString(KEY_INTERLUDE_STYLE, CarInterludeStyle.BREATHING_NOTES.name)
                    ?: CarInterludeStyle.BREATHING_NOTES.name
            )
        }.getOrDefault(CarInterludeStyle.BREATHING_NOTES)
        val lyricOffset = p.getLong(KEY_CAR_LYRIC_OFFSET_MS, -250L).coerceIn(-1500L, 1000L)
        val autoPlayCar = p.getBoolean(KEY_AUTO_PLAY_CAR, false)
        val zeroLatency = p.getBoolean(KEY_STEERING_ZERO_LATENCY, true)

        val knownJson = p.getString(KEY_KNOWN_DEVICES_JSON, null)
        val knownList: List<CarBluetoothDeviceRecord> = if (!knownJson.isNullOrBlank()) {
            runCatching {
                val type = object : TypeToken<List<CarBluetoothDeviceRecord>>() {}.type
                KugouSigner.gson.fromJson<List<CarBluetoothDeviceRecord>>(knownJson, type).orEmpty()
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

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
        synchronized(activeProjectionPackages) {
            activeProjectionPackages.add(packageName)
        }
        evaluateAndApplyCarSession(context, newlyAddedBluetooth = false, isInitialBoot = false)
    }

    fun onProjectionControllerDisconnected(context: Context?, packageName: String) {
        if (!isPhoneCarFeatureSupported()) return
        synchronized(activeProjectionPackages) {
            activeProjectionPackages.remove(packageName)
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
            val rawAddress = if (canUseBtApi) {
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
        val cur = _state.value
        val wasActive = cur.isCarModeActive

        if (!isPhoneCarFeatureSupported() || !cur.featureEnabled) {
            deactivateCarSessionInternal(clearManualPreview = true)
            return
        }

        val projectionPkg = synchronized(activeProjectionPackages) { activeProjectionPackages.firstOrNull() }
        val confirmedSink = if (AppPermissionManager.isPermissionEnabled(AppPermissionType.BLUETOOTH_CAR_INTERCONNECT)) {
            cur.currentBluetoothSinks.firstOrNull { sink ->
                cur.knownDevices.any { rec ->
                    rec.classification == CarDeviceClassification.CONFIRMED_CAR && matchesDeviceRecord(rec, sink)
                }
            }
        } else null

        // 计算是否需要弹出首次疑似车载蓝牙询问卡
        val nextPrompt: PendingCarPromptCandidate? = if (
            projectionPkg == null &&
            !isUiModeCar &&
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
            projectionPkg != null -> CarConnectionSource.PROJECTION_HOST to resolveFriendlyHostName(projectionPkg)
            isUiModeCar -> CarConnectionSource.PROJECTION_HOST to "车载系统投屏"
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
        // 车载模式下自动启用跨曲目响度均衡（避免行车途中不同音源音量忽大忽小）
        if (cur.cabinAcousticMode != CarCabinAcousticMode.DIRECT_BYPASS) {
            AudioEffectManager.setVolumeNormalizationTransientForCar(true)
        }
        if (cur.smartNavDuckingEnabled) {
            onApplyAudioFocusModeCallback?.invoke(AudioFocusMode.DUCK)
                ?: PlaybackService.updateAudioFocusMode(AudioFocusMode.DUCK)
        }
        PlaybackService.updateSteeringWheelZeroLatency(cur.steeringWheelZeroLatencyEnabled)
    }

    private fun captureEverydaySnapshotIfNeeded() {
        val p = prefs ?: return
        if (!p.getString(KEY_PENDING_SNAPSHOT_JSON, null).isNullOrBlank()) {
            // 已有未归还的日常快照（例如车载途中切换了另一个蓝牙或重启恢复），绝不覆盖原始日常快照
            return
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
        runCatching {
            p.edit().putString(KEY_PENDING_SNAPSHOT_JSON, KugouSigner.gson.toJson(snapshot)).commit()
        }
    }

    private fun restoreEverydaySnapshotIfPending() {
        val p = prefs ?: return
        val rawJson = p.getString(KEY_PENDING_SNAPSHOT_JSON, null)
        if (rawJson.isNullOrBlank()) return
        val snapshot = runCatching {
            KugouSigner.gson.fromJson(rawJson, PreCarEverydaySnapshot::class.java)
        }.getOrNull()
        p.edit().remove(KEY_PENDING_SNAPSHOT_JSON).commit()
        if (snapshot != null) {
            AudioEffectManager.restoreFromEverydaySnapshot(snapshot)
            val restoredFocus = runCatching { AudioFocusMode.valueOf(snapshot.audioFocusModeName) }
                .getOrDefault(AudioFocusMode.EXCLUSIVE)
            onApplyAudioFocusModeCallback?.invoke(restoredFocus)
                ?: PlaybackService.updateAudioFocusMode(restoredFocus)
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
        classification: CarDeviceClassification
    ) {
        val normalizedKey = deviceKey.ifBlank { normalizeDeviceKey(macAddress, displayName) }
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
            newlyAddedBluetooth = classification == CarDeviceClassification.CONFIRMED_CAR,
            isInitialBoot = false
        )
    }

    fun removeKnownDevice(context: Context?, deviceKey: String) {
        val updated = _state.value.knownDevices.filterNot { it.deviceKey == deviceKey }
        _state.update { it.copy(knownDevices = updated) }
        saveKnownDevices(updated)
        evaluateAndApplyCarSession(context, newlyAddedBluetooth = false, isInitialBoot = false)
    }

    fun setFeatureEnabled(context: Context?, enabled: Boolean) {
        if (!isPhoneCarFeatureSupported()) return
        prefs?.edit()?.putBoolean(KEY_FEATURE_ENABLED, enabled)?.apply()
        _state.update { it.copy(featureEnabled = enabled) }
        evaluateAndApplyCarSession(context, newlyAddedBluetooth = false, isInitialBoot = false)
    }

    fun setManualPreviewActive(context: Context?, active: Boolean) {
        if (!isPhoneCarFeatureSupported()) return
        _state.update { it.copy(manualPreviewActive = active) }
        evaluateAndApplyCarSession(context, newlyAddedBluetooth = false, isInitialBoot = false)
    }

    fun disconnectCurrentCarSessionTemporarily(context: Context?) {
        val cur = _state.value
        if (cur.manualPreviewActive) {
            setManualPreviewActive(context, false)
            return
        }
        // 若当前通过已认证蓝牙连接，支持一键从车机白名单切回普通蓝牙设备并立即还原日常状态
        val activeSink = cur.currentBluetoothSinks.firstOrNull { sink ->
            cur.knownDevices.any { rec ->
                rec.classification == CarDeviceClassification.CONFIRMED_CAR && matchesDeviceRecord(rec, sink)
            }
        }
        if (activeSink != null) {
            setDeviceClassification(
                context = context,
                deviceKey = activeSink.deviceKey,
                displayName = activeSink.displayName,
                macAddress = activeSink.macAddress,
                classification = CarDeviceClassification.IGNORED_NON_CAR
            )
        }
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
                onApplyAudioFocusModeCallback?.invoke(AudioFocusMode.EXCLUSIVE)
                    ?: PlaybackService.updateAudioFocusMode(AudioFocusMode.EXCLUSIVE)
            }
            AudioEffectManager.dspConfigVersion.incrementAndGet()
        }
    }

    fun setNavDuckingTargetGain(gain: Float) {
        val clamped = gain.coerceIn(0.15f, 0.50f)
        prefs?.edit()?.putFloat(KEY_NAV_DUCK_GAIN, clamped)?.apply()
        _state.update { it.copy(navDuckingTargetGain = clamped) }
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
