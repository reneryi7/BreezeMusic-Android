package com.concept.music.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 车机端性能与画质档位偏好（用户可在极简设置卡一键切换）
 */
enum class CarPerformanceMode(val label: String, val subtitle: String) {
    AUTO("智能适配", "按车机芯片、内存与温度自动平衡颜值与流畅度"),
    HIGH_QUALITY("高画质", "启用动态流光背景与光学景深歌词，适合 8155 / 8295"),
    SMOOTH_ECO("流畅省电", "关闭 GPU 模糊与背景动效，极低内存与显存占用")
}

/**
 * 运行期最终解析出的车机算力等级
 */
enum class CarResolvedPerformanceTier(val label: String) {
    HIGH("高配座舱"),
    BALANCED("均衡流畅"),
    ECO("极简省电")
}

@Immutable
data class CarRenderCapabilities(
    val tier: CarResolvedPerformanceTier,
    val backgroundMaxFps: Int,
    val enableAnimatedBackground: Boolean,
    val enableLyricDepthBlur: Boolean,
    val enableDrawerLiveBlur: Boolean,
    val coilMemoryCachePercent: Double,
    val coverDecodeSizePx: Int,
    val listThumbDecodeSizePx: Int
)

/**
 * 纯 Kotlin 可单测的车机硬件算力与渲染能力决策函数：
 * - 综合 RAM 容量、低内存标识、CPU 核心数、Android API 级别（API 31+ 才具备硬件 RenderEffect）及车机热降频状态；
 * - 即使在高配车机上也将连续背景着色器节流至 30fps，把 60fps 算力优先让给歌词滚动、触控响应与导航/全景多屏同显。
 */
fun computeCarRenderCapabilities(
    mode: CarPerformanceMode,
    totalRamMb: Long,
    cpuCores: Int,
    isLowRamDevice: Boolean,
    sdkInt: Int,
    isThermalThrottled: Boolean = false
): CarRenderCapabilities {
    val resolvedTier = when (mode) {
        CarPerformanceMode.SMOOTH_ECO -> CarResolvedPerformanceTier.ECO
        CarPerformanceMode.HIGH_QUALITY -> {
            if (isThermalThrottled) CarResolvedPerformanceTier.BALANCED
            else CarResolvedPerformanceTier.HIGH
        }
        CarPerformanceMode.AUTO -> when {
            isLowRamDevice || isThermalThrottled || totalRamMb in 1L..3_500L || cpuCores <= 4 || sdkInt < Build.VERSION_CODES.Q ->
                CarResolvedPerformanceTier.ECO
            totalRamMb >= 6_000L && cpuCores >= 8 && sdkInt >= Build.VERSION_CODES.S ->
                CarResolvedPerformanceTier.HIGH
            else ->
                CarResolvedPerformanceTier.BALANCED
        }
    }

    return when (resolvedTier) {
        CarResolvedPerformanceTier.HIGH -> CarRenderCapabilities(
            tier = CarResolvedPerformanceTier.HIGH,
            backgroundMaxFps = 30,
            enableAnimatedBackground = true,
            enableLyricDepthBlur = sdkInt >= Build.VERSION_CODES.S,
            enableDrawerLiveBlur = false,
            coilMemoryCachePercent = 0.16,
            coverDecodeSizePx = 512,
            listThumbDecodeSizePx = 160
        )
        CarResolvedPerformanceTier.BALANCED -> CarRenderCapabilities(
            tier = CarResolvedPerformanceTier.BALANCED,
            backgroundMaxFps = 20,
            enableAnimatedBackground = true,
            enableLyricDepthBlur = false,
            enableDrawerLiveBlur = false,
            coilMemoryCachePercent = 0.12,
            coverDecodeSizePx = 384,
            listThumbDecodeSizePx = 160
        )
        CarResolvedPerformanceTier.ECO -> CarRenderCapabilities(
            tier = CarResolvedPerformanceTier.ECO,
            backgroundMaxFps = 0,
            enableAnimatedBackground = false,
            enableLyricDepthBlur = false,
            enableDrawerLiveBlur = false,
            coilMemoryCachePercent = 0.08,
            coverDecodeSizePx = 288,
            listThumbDecodeSizePx = 128
        )
    }
}

/**
 * 车机专项性能自适应管理器：零权限探测硬件规格与热状态，持久化用户画质档位偏好。
 */
object CarPerformanceManager {
    private const val PREFS_NAME = "concept_car_perf_prefs"
    private const val KEY_PERF_MODE = "car_performance_mode"

    private var prefs: SharedPreferences? = null
    @Volatile
    private var initialized = false

    private var cachedTotalRamMb: Long = 4_096L
    private var cachedCpuCores: Int = 8
    private var cachedLowRam: Boolean = false
    private var cachedThermalThrottled: Boolean = false

    private val _mode = MutableStateFlow(CarPerformanceMode.AUTO)
    val mode: StateFlow<CarPerformanceMode> = _mode.asStateFlow()

    private val _capabilities = MutableStateFlow(
        computeCarRenderCapabilities(
            mode = CarPerformanceMode.AUTO,
            totalRamMb = cachedTotalRamMb,
            cpuCores = cachedCpuCores,
            isLowRamDevice = cachedLowRam,
            sdkInt = Build.VERSION.SDK_INT,
            isThermalThrottled = false
        )
    )
    val capabilities: StateFlow<CarRenderCapabilities> = _capabilities.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        val appCtx = context.applicationContext ?: context
        if (prefs == null) {
            prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        if (!initialized) {
            initialized = true
            val raw = prefs?.getString(KEY_PERF_MODE, CarPerformanceMode.AUTO.name) ?: CarPerformanceMode.AUTO.name
            _mode.value = runCatching { CarPerformanceMode.valueOf(raw) }.getOrDefault(CarPerformanceMode.AUTO)
        }
        refreshHardwareAndThermal(appCtx)
    }

    fun refreshHardwareAndThermal(context: Context) {
        val appCtx = context.applicationContext ?: context
        runCatching {
            val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                cachedLowRam = am.isLowRamDevice
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                if (memInfo.totalMem > 0L) {
                    cachedTotalRamMb = memInfo.totalMem / (1024L * 1024L)
                }
            }
            cachedCpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            cachedThermalThrottled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val status = pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                status >= PowerManager.THERMAL_STATUS_MODERATE
            } else {
                false
            }
        }
        recomputeCapabilities()
    }

    fun setMode(mode: CarPerformanceMode) {
        _mode.value = mode
        prefs?.edit()?.putString(KEY_PERF_MODE, mode.name)?.apply()
        recomputeCapabilities()
    }

    private fun recomputeCapabilities() {
        _capabilities.value = computeCarRenderCapabilities(
            mode = _mode.value,
            totalRamMb = cachedTotalRamMb,
            cpuCores = cachedCpuCores,
            isLowRamDevice = cachedLowRam,
            sdkInt = Build.VERSION.SDK_INT,
            isThermalThrottled = cachedThermalThrottled
        )
    }
}
