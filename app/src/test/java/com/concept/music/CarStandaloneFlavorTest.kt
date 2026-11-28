package com.concept.music

import android.content.SharedPreferences
import android.os.Build
import com.concept.music.service.*
import com.concept.music.ui.settings.UpdateCheckResult
import com.concept.music.ui.settings.resolveUpdateCheckFromApiOrRedirect
import com.concept.music.ui.theme.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 独立车机包（`car` 变体 / `com.concept.music.car`）核心专项单元测试：
 * 1. 验证 CarSessionManager 常驻原生车机模式（零蓝牙猜测、跳过手机日常快照、默认 64-bit 座舱声学、仪表盘歌词默认关闭）；
 * 2. 验证车机三档硬件算力自适应与热降频引擎（CarPerformanceProfile）；
 * 3. 验证车机四大自适应窗口形态（带鱼屏 / 16:9 横屏 / DiLink 旋转竖屏 / 1/3 导航分屏）；
 * 4. 验证车载语音点歌关键词与结构化槽位解析（CarMediaLibraryHelper.extractVoiceSearchKeyword）。
 */
class CarStandaloneFlavorTest {

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = LinkedHashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val staged = LinkedHashMap<String, Any?>()
            private val removals = HashSet<String>()
            private var clearAll = false
            override fun putString(key: String?, value: String?) = apply { if (key != null) { removals.remove(key); staged[key] = value } }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) { removals.remove(key); staged[key] = values?.toSet() } }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) { removals.remove(key); staged[key] = value } }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) { removals.remove(key); staged[key] = value } }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) { removals.remove(key); staged[key] = value } }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) { removals.remove(key); staged[key] = value } }
            override fun remove(key: String?) = apply { if (key != null) { staged.remove(key); removals.add(key) } }
            override fun clear() = apply { clearAll = true; staged.clear(); removals.clear() }
            override fun commit(): Boolean {
                if (clearAll) map.clear()
                removals.forEach { map.remove(it) }
                staged.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
                return true
            }
            override fun apply() { commit() }
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Before
    fun setUp() {
        CarSessionManager.resetForTest()
        AudioEffectManager.resetForTest()
        PlaybackService.audioFocusDuckGain = 1.0f
        PlaybackService.updateAudioFocusMode(AudioFocusMode.EXCLUSIVE)
        PlaybackService.updateSteeringWheelZeroLatency(false)
    }

    @After
    fun tearDown() {
        CarSessionManager.resetForTest()
        AudioEffectManager.resetForTest()
        PlaybackService.audioFocusDuckGain = 1.0f
        PlaybackService.updateAudioFocusMode(AudioFocusMode.EXCLUSIVE)
        PlaybackService.updateSteeringWheelZeroLatency(false)
    }

    @Test
    fun standaloneCarSession_isAlwaysActiveWithDefaultsAndZeroPhoneSnapshot() {
        val prefs = InMemorySharedPreferences()
        CarSessionManager.isTabletFlavorOverrideForTest = false
        CarSessionManager.isCarFlavorOverrideForTest = true
        var appliedFocus: AudioFocusMode? = null
        CarSessionManager.onApplyAudioFocusModeCallback = { appliedFocus = it }

        CarSessionManager.initWithSharedPreferencesForTest(prefs)
        val st = CarSessionManager.state.value

        assertTrue("车机独立包应常驻激活车载模式", st.isCarModeActive)
        assertEquals(CarConnectionSource.STANDALONE_CAR_UNIT, st.activeConnectionSource)
        assertNull("车机独立包绝不弹出手机蓝牙疑似车机询问卡", st.pendingPromptCandidate)
        assertEquals(CarCabinAcousticMode.DRIVER_SWEET_SPOT, st.cabinAcousticMode)
        assertTrue("车机独立包默认开启智能导航平滑闪避", st.smartNavDuckingEnabled)
        assertFalse("车机独立包仪表盘歌词推送默认关闭", st.carBluetoothLyricEnabled)
        assertEquals("车机本地直连默认 0ms 蓝牙延迟偏移", 0L, st.carLyricOffsetMs)
        assertTrue("车机独立包默认开启方向盘按键 0ms 直通", st.steeringWheelZeroLatencyEnabled)
        assertEquals(AudioFocusMode.DUCK, appliedFocus)
        assertTrue("64-bit 主驾皇帝位声场应自动挂载", AudioEffectManager.carCabinOverlay.active)
        assertFalse("车机独立包不应落盘手机日常音效快照", prefs.contains("car_pending_everyday_snapshot_json"))

        // 切换至原车音响直通应立即解除 DSP 覆盖但仍维持车载激活态
        CarSessionManager.setCabinAcousticMode(CarCabinAcousticMode.DIRECT_BYPASS)
        assertTrue(CarSessionManager.state.value.isCarModeActive)
        assertFalse(AudioEffectManager.carCabinOverlay.active)
    }

    @Test
    fun carPerformanceProfile_selectsCorrectTierAcrossHardwareAndThermalStates() {
        // 1. 骁龙 8295 / 高配 8155（8GB RAM, 8核, Android 12+）
        val high = computeCarRenderCapabilities(
            mode = CarPerformanceMode.AUTO,
            totalRamMb = 8_192L,
            cpuCores = 8,
            isLowRamDevice = false,
            sdkInt = Build.VERSION_CODES.S,
            isThermalThrottled = false
        )
        assertEquals(CarResolvedPerformanceTier.HIGH, high.tier)
        assertEquals(30, high.backgroundMaxFps)
        assertTrue(high.enableLyricDepthBlur)
        assertFalse("车机抽屉禁止高开销双通道实时模糊", high.enableDrawerLiveBlur)

        // 2. 主流 4GB RAM Android 11（不支持 RenderEffect）
        val balanced = computeCarRenderCapabilities(
            mode = CarPerformanceMode.AUTO,
            totalRamMb = 4_096L,
            cpuCores = 8,
            isLowRamDevice = false,
            sdkInt = Build.VERSION_CODES.R,
            isThermalThrottled = false
        )
        assertEquals(CarResolvedPerformanceTier.BALANCED, balanced.tier)
        assertEquals(20, balanced.backgroundMaxFps)
        assertFalse(balanced.enableLyricDepthBlur)

        // 3. 老旧 4 核 3GB 低内存车机
        val eco = computeCarRenderCapabilities(
            mode = CarPerformanceMode.AUTO,
            totalRamMb = 3_072L,
            cpuCores = 4,
            isLowRamDevice = true,
            sdkInt = Build.VERSION_CODES.P,
            isThermalThrottled = false
        )
        assertEquals(CarResolvedPerformanceTier.ECO, eco.tier)
        assertEquals(0, eco.backgroundMaxFps)
        assertFalse(eco.enableAnimatedBackground)
        assertEquals(0.08, eco.coilMemoryCachePercent, 0.0001)

        // 4. 高配车机遭遇高温热降频时自动降级保护
        val thermal = computeCarRenderCapabilities(
            mode = CarPerformanceMode.HIGH_QUALITY,
            totalRamMb = 12_288L,
            cpuCores = 8,
            isLowRamDevice = false,
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            isThermalThrottled = true
        )
        assertEquals(CarResolvedPerformanceTier.BALANCED, thermal.tier)
    }

    @Test
    fun carAdaptiveLayout_detectsAllFourCockpitFormFactors() {
        // 1. 21:9 超宽带鱼屏（1920x720）
        val ultrawide = computeCarCockpitLayoutSpec(widthDp = 1440, heightDp = 640)
        assertEquals(CarCockpitLayoutMode.ULTRA_WIDE_COCKPIT, ultrawide.mode)
        assertTrue(ultrawide.isDualPaneLandscape)

        // 2. 主流 16:9 车机横屏（1280x720）
        val landscape = computeCarCockpitLayoutSpec(widthDp = 1024, heightDp = 600)
        assertEquals(CarCockpitLayoutMode.STANDARD_LANDSCAPE_COCKPIT, landscape.mode)
        assertEquals(0.45f, landscape.leftPaneWeight, 0.001f)

        // 3. 比亚迪 DiLink 旋转竖屏（720x1280）
        val portrait = computeCarCockpitLayoutSpec(widthDp = 720, heightDp = 1200)
        assertEquals(CarCockpitLayoutMode.PORTRAIT_COCKPIT, portrait.mode)
        assertFalse(portrait.isDualPaneLandscape)

        // 4. 高德地图 1/3 导航分屏（420x720）
        val split = computeCarCockpitLayoutSpec(widthDp = 420, heightDp = 720)
        assertEquals(CarCockpitLayoutMode.COMPACT_SPLIT_CARD, split.mode)
        assertTrue(split.drawerWidthFraction >= 0.90f)
    }

    @Test
    fun voiceSearchKeywordExtraction_parsesStructuredSlotsAndNaturalChinesePhrases() {
        assertEquals(
            "周杰伦 晴天",
            CarMediaLibraryHelper.extractVoiceSearchKeyword(
                rawQuery = "播放周杰伦的晴天",
                artistExtra = "周杰伦",
                titleExtra = "晴天"
            )
        )
        assertEquals(
            "林俊杰 江南",
            CarMediaLibraryHelper.extractVoiceSearchKeyword("请帮我播放林俊杰 江南这首歌")
        )
        assertEquals(
            "",
            CarMediaLibraryHelper.extractVoiceSearchKeyword("放点音乐")
        )
        assertEquals(
            "",
            CarMediaLibraryHelper.extractVoiceSearchKeyword("   ")
        )
    }

    @Test
    fun updateResolver_matchesCarApkAssetOnCarFlavor() {
        if (!BuildConfig.IS_CAR_FLAVOR) return
        val json = """
            {
              "tag_name": "v9.9.9",
              "name": "BreezeMusic v9.9.9",
              "html_url": "https://github.com/reneryi7/BreezeMusic-Android/releases/tag/v9.9.9",
              "assets": [
                {"name": "BreezeMusic-phone-v9.9.9.apk", "browser_download_url": "https://github.com/reneryi7/BreezeMusic-Android/releases/download/v9.9.9/BreezeMusic-phone-v9.9.9.apk"},
                {"name": "BreezeMusic-tablet-v9.9.9.apk", "browser_download_url": "https://github.com/reneryi7/BreezeMusic-Android/releases/download/v9.9.9/BreezeMusic-tablet-v9.9.9.apk"},
                {"name": "BreezeMusic-car-v9.9.9.apk", "browser_download_url": "https://github.com/reneryi7/BreezeMusic-Android/releases/download/v9.9.9/BreezeMusic-car-v9.9.9.apk"}
              ]
            }
        """.trimIndent()
        val res = resolveUpdateCheckFromApiOrRedirect(
            localVersionName = "1.5.0-car",
            apiHttpCode = 200,
            apiBodyJson = json
        )
        assertTrue(res is UpdateCheckResult.HasUpdate)
        val upd = res as UpdateCheckResult.HasUpdate
        assertTrue("car 变体应精准匹配 car APK 资产", upd.downloadUrl.contains("BreezeMusic-car-v9.9.9.apk"))
    }

    @Test
    fun threeFlavors_manifestAndSigningIsolation_haveZeroCrossAppInterference() {
        var root = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            if (!java.io.File(root, "settings.gradle.kts").exists()) {
                root = root.parentFile ?: root
            }
        }
        val gradle = java.io.File(root, "app/build.gradle.kts").readText(Charsets.UTF_8)
        assertTrue(gradle.contains("""applicationId = "com.concept.music""""))
        assertTrue(gradle.contains("""applicationIdSuffix = ".tablet""""))
        assertTrue(gradle.contains("""applicationIdSuffix = ".car""""))
        val expectedAppId = when {
            BuildConfig.IS_CAR_FLAVOR -> "com.concept.music.car"
            BuildConfig.IS_TABLET_FLAVOR -> "com.concept.music.tablet"
            else -> "com.concept.music"
        }
        assertEquals(expectedAppId, BuildConfig.APPLICATION_ID)

        val mainManifest = java.io.File(root, "app/src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        val phoneManifest = java.io.File(root, "app/src/phone/AndroidManifest.xml").readText(Charsets.UTF_8)
        val tabletManifest = java.io.File(root, "app/src/tablet/AndroidManifest.xml").readText(Charsets.UTF_8)
        val carManifest = java.io.File(root, "app/src/car/AndroidManifest.xml").readText(Charsets.UTF_8)

        // ACTION_SEND 分享链接解析仅归属 phone 与 tablet，避免车机包同机共存时污染系统分享面板
        assertFalse("main 清单不应向 car 透传 ACTION_SEND", mainManifest.contains("android.intent.action.SEND"))
        assertTrue("phone 清单应保留 ACTION_SEND 分享入口", phoneManifest.contains("android.intent.action.SEND"))
        assertTrue("tablet 清单应保留 ACTION_SEND 分享入口", tabletManifest.contains("android.intent.action.SEND"))
        assertFalse("car 清单不应暴露 ACTION_SEND", carManifest.contains("android.intent.action.SEND"))

        // 车机清单严格剥离悬浮窗/蓝牙/多余3款小组件，且绝不强制 automotive required=true
        assertTrue(carManifest.contains("""android:name="android.hardware.type.automotive""""))
        assertTrue(carManifest.contains("""android:required="false""""))
        assertTrue(carManifest.contains("SYSTEM_ALERT_WINDOW") && carManifest.contains("""tools:node="remove""""))
        assertTrue(carManifest.contains("BreezeCompactWidgetProvider"))
        assertTrue(carManifest.contains("BreezeSquareWidgetProvider"))
        assertTrue(carManifest.contains("BreezeStageWidgetProvider"))

        // 运行期小组件注册数量隔离：车机仅 1 款 4×2，手机/平板完整保留 4 款
        val expectedWidgetCount = if (BuildConfig.IS_CAR_FLAVOR) 1 else 4
        assertEquals(expectedWidgetCount, com.concept.music.widget.BreezeWidgetManager.providerSpecs.size)
    }
}
