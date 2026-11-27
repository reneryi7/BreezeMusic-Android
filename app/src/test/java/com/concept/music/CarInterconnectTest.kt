package com.concept.music

import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.media3.common.audio.AudioProcessor
import com.concept.music.data.model.LyricData
import com.concept.music.data.model.LyricLine
import com.concept.music.data.model.LyricWord
import com.concept.music.data.model.Song
import com.concept.music.service.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 手机端车载互联、座舱声学 DSP、智能导航平滑闪避与蓝牙呼吸歌词专项单元测试
 */
class CarInterconnectTest {

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

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) { removals.remove(key); staged[key] = value }
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) { removals.remove(key); staged[key] = values?.toSet() }
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) { removals.remove(key); staged[key] = value }
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) { removals.remove(key); staged[key] = value }
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) { removals.remove(key); staged[key] = value }
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) { removals.remove(key); staged[key] = value }
            }
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) { staged.remove(key); removals.add(key) }
            }
            override fun clear(): SharedPreferences.Editor = apply {
                clearAll = true
                staged.clear()
                removals.clear()
            }
            override fun commit(): Boolean {
                if (clearAll) map.clear()
                removals.forEach { map.remove(it) }
                staged.forEach { (k, v) ->
                    if (v == null) map.remove(k) else map[k] = v
                }
                return true
            }
            override fun apply() {
                commit()
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Before
    fun setUp() {
        AppPermissionManager.resetForTest()
        AudioEffectManager.resetForTest()
        CarSessionManager.resetForTest()
        CarSessionManager.isTabletFlavorOverrideForTest = false
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
        AppPermissionManager.resetForTest()
    }

    @Test
    fun `车载蓝牙特征词与耳机排除词库识别精准度验证`() {
        // 1. 常见蓝牙耳机/个人穿戴绝不误弹车机提示
        val headphones = listOf(
            "AirPods Pro",
            "HUAWEI FreeBuds Pro 3",
            "WH-1000XM5",
            "Samsung Galaxy Buds3 Pro",
            "Bose QuietComfort Ultra",
            "Shokz OpenRun Pro",
            "漫步者 LolliPods"
        )
        for (hp in headphones) {
            val (suspected, _) = CarSessionManager.isSuspectedCarDevice(hp, isHardwareCarAudioClass = false)
            assertFalse("耳机设备「$hp」绝不能误识别为车载蓝牙", suspected)
        }

        // 2. 主流原厂车机与后装中控精准识别
        val carSystems = listOf(
            "BMW 530Li",
            "BYD DiLink",
            "Xiaomi SU7",
            "AITO 问界 M9",
            "CAR MULTIMEDIA",
            "Mercedes-Benz Bluetooth",
            "Zeekr 001",
            "理想 L7"
        )
        for (car in carSystems) {
            val (suspected, reason) = CarSessionManager.isSuspectedCarDevice(car, isHardwareCarAudioClass = false)
            assertTrue("车机设备「$car」应命中疑似车载识别", suspected)
            assertTrue(reason.isNotBlank())
        }

        // 3. 硬件类别码 AUDIO_VIDEO_CAR_AUDIO 兜底识别
        val (hwSuspected, _) = CarSessionManager.isSuspectedCarDevice("BT-8820", isHardwareCarAudioClass = true)
        assertTrue(hwSuspected)

        // 4. 单词边界防误伤（如 Cartoon / Card 不应误匹配 car）
        val (falseWordHit, _) = CarSessionManager.isSuspectedCarDevice("Cartoon Speaker", isHardwareCarAudioClass = false)
        assertFalse(falseWordHit)
    }

    @Test
    fun `上下车日常音效快照原子还原与进程被杀冷启动自愈及自动续播默认关闭验证`() {
        val fakePrefs = InMemorySharedPreferences()
        CarSessionManager.initWithSharedPreferencesForTest(fakePrefs)

        // 设定用户日常耳机音效参数
        AudioEffectManager.setBassBoostStrength(360)
        AudioEffectManager.setChannelBalance(-0.30f)
        AudioEffectManager.setVolumeNormalizationEnabled(false)
        PlaybackService.updateAudioFocusMode(AudioFocusMode.EXCLUSIVE)
        assertTrue(AudioEffectManager.state.value.isEnabled)

        var autoPlayTriggeredCount = 0
        CarSessionManager.onTriggerCarAutoPlayCallback = { autoPlayTriggeredCount++ }

        val bmwSink = ConnectedBluetoothAudioSink(
            deviceKey = CarSessionManager.normalizeDeviceKey("AA:BB:CC:11:22:33", "BMW iDrive"),
            displayName = "BMW iDrive",
            macAddress = "AA:BB:CC:11:22:33",
            isHardwareCarAudioClass = true
        )

        // 首次连上未分类车载蓝牙 -> 仅弹出单次询问候选，尚未强行切入车载模式
        CarSessionManager.onBluetoothSinksUpdated(
            context = null,
            sinks = listOf(bmwSink),
            newlyAddedBluetooth = true
        )
        assertFalse(CarSessionManager.state.value.isCarModeActive)
        assertNotNull(CarSessionManager.state.value.pendingPromptCandidate)
        assertEquals("BMW iDrive", CarSessionManager.state.value.pendingPromptCandidate?.displayName)

        // 用户点击「设为我的车机」-> 立即激活主驾皇帝位声场 + 智能导航闪避焦点 DUCK，且默认不触发自动续播
        CarSessionManager.confirmPendingPromptAsCar(context = null)
        assertTrue(CarSessionManager.state.value.isCarModeActive)
        assertNull(CarSessionManager.state.value.pendingPromptCandidate)
        assertEquals(CarConnectionSource.CONFIRMED_BLUETOOTH, CarSessionManager.state.value.activeConnectionSource)
        assertTrue(AudioEffectManager.carCabinOverlay.active)
        assertEquals(CarCabinAcousticMode.DRIVER_SWEET_SPOT, AudioEffectManager.carCabinOverlay.mode)
        assertTrue(AudioEffectManager.state.value.volumeNormalizationEnabled)
        assertEquals(AudioFocusMode.DUCK, PlaybackService.currentAudioFocusMode)
        assertTrue(PlaybackService.headsetButtonController.bypassMultiClickDelay)
        assertFalse("连接车机自动续播默认必须关闭", CarSessionManager.state.value.autoPlayOnCarConnect)
        assertEquals(0, autoPlayTriggeredCount)

        // 下车熄火断开蓝牙 -> 100% 还原上车前的手机日常音效、声道平衡、响度开关与独占焦点
        CarSessionManager.onBluetoothSinksUpdated(
            context = null,
            sinks = emptyList(),
            newlyAddedBluetooth = false
        )
        assertFalse(CarSessionManager.state.value.isCarModeActive)
        assertFalse(AudioEffectManager.carCabinOverlay.active)
        assertTrue(AudioEffectManager.state.value.isEnabled)
        assertEquals(360, AudioEffectManager.state.value.bassBoostStrength)
        assertEquals(-0.30f, AudioEffectManager.state.value.channelBalance, 0.001f)
        assertFalse(AudioEffectManager.state.value.volumeNormalizationEnabled)
        assertEquals(AudioFocusMode.EXCLUSIVE, PlaybackService.currentAudioFocusMode)
        assertFalse(PlaybackService.headsetButtonController.bypassMultiClickDelay)

        // 模拟行车途中进程被系统杀死：先再次连上车机挂载车载参数，随后在不执行断开回调的情况下模拟冷启动且无蓝牙连接
        CarSessionManager.onBluetoothSinksUpdated(
            context = null,
            sinks = listOf(bmwSink),
            newlyAddedBluetooth = true
        )
        assertTrue(CarSessionManager.state.value.isCarModeActive)
        assertTrue("行车途中必须落盘日常快照防崩溃丢失", fakePrefs.contains("car_pending_everyday_snapshot_json"))

        // 模拟第二天日常冷启动（未连车机） -> 自动发现遗留快照并完成自愈回滚
        CarSessionManager.onBluetoothSinksUpdated(
            context = null,
            sinks = emptyList(),
            newlyAddedBluetooth = false,
            isInitialBoot = true
        )
        CarSessionManager.initWithSharedPreferencesForTest(fakePrefs)
        assertFalse(fakePrefs.contains("car_pending_everyday_snapshot_json"))
        assertEquals(-0.30f, AudioEffectManager.state.value.channelBalance, 0.001f)
        assertEquals(AudioFocusMode.EXCLUSIVE, PlaybackService.currentAudioFocusMode)
    }

    @Test
    fun `平板构建变体严格静默旁路车载检测与弹窗验证`() {
        val fakePrefs = InMemorySharedPreferences()
        CarSessionManager.isTabletFlavorOverrideForTest = true
        CarSessionManager.initWithSharedPreferencesForTest(fakePrefs)

        assertFalse(CarSessionManager.isPhoneCarFeatureSupported())
        assertFalse(CarSessionManager.state.value.featureEnabled)

        val carSink = ConnectedBluetoothAudioSink(
            deviceKey = "MAC|11:22:33:44:55:66",
            displayName = "Tesla Model 3",
            macAddress = "11:22:33:44:55:66",
            isHardwareCarAudioClass = true
        )
        CarSessionManager.onBluetoothSinksUpdated(
            context = null,
            sinks = listOf(carSink),
            newlyAddedBluetooth = true
        )
        assertFalse("平板变体绝不可激活手机车载模式", CarSessionManager.state.value.isCarModeActive)
        assertNull("平板变体绝不可弹出疑似车机询问浮层", CarSessionManager.state.value.pendingPromptCandidate)
    }

    @Test
    fun `64位母带处理器主驾皇帝位左声道ITD微延迟与导航平滑闪避包络验证`() {
        val fakePrefs = InMemorySharedPreferences()
        CarSessionManager.initWithSharedPreferencesForTest(fakePrefs)
        CarSessionManager.setCabinAcousticMode(CarCabinAcousticMode.DRIVER_SWEET_SPOT)
        CarSessionManager.setManualPreviewActive(null, true)
        assertTrue(CarSessionManager.state.value.isCarModeActive)

        val processor = ConceptMasteringAudioProcessor()
        val format = AudioProcessor.AudioFormat(48_000, 2, androidx.media3.common.C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        // 输入单脉冲立体声信号（第 0 帧 L=20000, R=20000，后续全 0），验证左声道相对右声道精准延迟 round(48000 * 0.00225) = 108 采样点
        val frameCount = 256
        val inputBuf = ByteBuffer.allocateDirect(frameCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        inputBuf.putShort(20_000.toShort())
        inputBuf.putShort(20_000.toShort())
        for (i in 1 until frameCount) {
            inputBuf.putShort(0)
            inputBuf.putShort(0)
        }
        inputBuf.flip()

        processor.queueInput(inputBuf)
        val outBuf = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        var peakIdxL = -1
        var peakValL = 0
        var peakIdxR = -1
        var peakValR = 0
        for (i in 0 until frameCount) {
            val l = abs(outBuf.short.toInt())
            val r = abs(outBuf.short.toInt())
            if (l > peakValL) { peakValL = l; peakIdxL = i }
            if (r > peakValR) { peakValR = r; peakIdxR = i }
        }
        assertEquals("右声道脉冲峰值应保持在起始帧附近", 0, peakIdxR)
        assertEquals("主驾皇帝位左声道应产生 2.25ms (48kHz 下 108 采样点) 先导延迟", 108, peakIdxL)

        // 验证智能导航语音平滑闪避包络（无阶跃爆音，平滑收敛至目标增益，恢复后平滑回升）
        PlaybackService.audioFocusDuckGain = 0.28f
        val sineFrames = 48_000 // 1 秒音频
        val sineBuf = ByteBuffer.allocateDirect(sineFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sineFrames) {
            val s = (sin(2.0 * PI * 1850.0 * i / 48_000.0) * 12_000.0).toInt().toShort()
            sineBuf.putShort(s)
            sineBuf.putShort(s)
        }
        sineBuf.flip()
        processor.queueInput(sineBuf)
        val duckedOut = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        assertTrue("1秒导航闪避后包络应收敛至约 0.28", processor.currentDuckEnvelopeForTest() in 0.27f..0.295f)

        // 统计闪避稳态末尾 2000 帧的 RMS，确保明显低于原始 12000 振幅
        duckedOut.position((sineFrames - 2000) * 4)
        var sumSq = 0.0
        for (i in 0 until 2000) {
            val l = duckedOut.short.toDouble()
            duckedOut.short
            sumSq += l * l
        }
        val rmsDucked = sqrt(sumSq / 2000.0)
        assertTrue("闪避 + 1.85kHz 避让后人声频段能量应显著降低 (rms=$rmsDucked)", rmsDucked < 3500.0)

        // 导航结束恢复 1.0f
        PlaybackService.audioFocusDuckGain = 1.0f
        val releaseBuf = ByteBuffer.allocateDirect(sineFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sineFrames) {
            releaseBuf.putShort(8000)
            releaseBuf.putShort(8000)
        }
        releaseBuf.flip()
        processor.queueInput(releaseBuf)
        processor.output
        assertTrue("导航播报结束后包络应平滑回升至接近 1.0", processor.currentDuckEnvelopeForTest() > 0.90f)
    }

    @Test
    fun `车载蓝牙呼吸歌词前奏鸣谢剥离与长间奏呼吸及本机通知栏双轨隔离验证`() {
        val song = Song(id = "s_jay", title = "晴天", artist = "周杰伦")
        val lyric = LyricData(
            isKrc = true,
            lines = listOf(
                LyricLine(0L, 2_000L, "晴天 - 周杰伦"),
                LyricLine(2_000L, 2_000L, "作词：周杰伦"),
                LyricLine(4_000L, 2_000L, "作曲：周杰伦"),
                LyricLine(6_000L, 2_000L, "编曲：洪敬尧"),
                LyricLine(
                    startTimeMs = 15_000L,
                    durationMs = 3_500L,
                    text = "故事的小黄花",
                    words = listOf(LyricWord(15_000L, 3_500L, "故事的小黄花"))
                ),
                LyricLine(
                    startTimeMs = 26_000L,
                    durationMs = 4_000L,
                    text = "从出生那年就飘着",
                    words = listOf(LyricWord(26_000L, 4_000L, "从出生那年就飘着"))
                )
            )
        )

        // 1. 前奏鸣谢阶段（0~15s）：彻底剥离作词作曲编曲行，车机屏幕安静显示原歌名与原歌手
        val introSnap = CarAvrcpLyricFormatter.formatMetadataSnapshot(
            song = song,
            lyricData = lyric,
            adjustedPositionMs = 4_500L,
            enableCarLyric = true
        )
        assertEquals("晴天", introSnap.avrTitle)
        assertEquals("周杰伦", introSnap.avrArtist)
        assertEquals("晴天", introSnap.notificationTitle)
        assertEquals("周杰伦", introSnap.notificationArtist)

        // 2. 正式演唱阶段（16s）：车机 Title 推送实时唱词，Artist 推送「歌名 · 歌手」，而本机通知栏保持原歌名绝不跳动
        val vocalSnap = CarAvrcpLyricFormatter.formatMetadataSnapshot(
            song = song,
            lyricData = lyric,
            adjustedPositionMs = 16_200L,
            enableCarLyric = true
        )
        assertEquals("故事的小黄花", vocalSnap.avrTitle)
        assertEquals("晴天 · 周杰伦", vocalSnap.avrArtist)
        assertEquals("晴天", vocalSnap.notificationTitle)
        assertEquals("周杰伦", vocalSnap.notificationArtist)

        // 3. 超过 5s 的器乐长间奏阶段（18.5s ~ 26s，间隙 7.5s >= 5s）：自动切换音符呼吸态
        val interludeSnap = CarAvrcpLyricFormatter.formatMetadataSnapshot(
            song = song,
            lyricData = lyric,
            adjustedPositionMs = 21_000L,
            enableCarLyric = true,
            interludeStyle = CarInterludeStyle.BREATHING_NOTES
        )
        assertEquals("♪  •••  ♪", interludeSnap.avrTitle)
        assertEquals("晴天 · 周杰伦", interludeSnap.avrArtist)
        assertEquals("晴天", interludeSnap.notificationTitle)

        // 4. 自适应低功耗唤醒周期校验与 PlayerUiState 车载延迟叠加校验
        val delayToInterlude = CarAvrcpLyricFormatter.computeNextWakeupDelayMs(lyric, 16_000L, 1.0f)
        assertTrue(delayToInterlude in 260L..4_500L)

        val carUiState = com.concept.music.ui.viewmodel.PlayerUiState(
            lyricOffsetMs = 100L,
            isCarModeActive = true,
            carLyricOffsetMs = -250L
        )
        assertEquals(-150L, carUiState.effectiveLyricOffsetMs)
    }

    @Test
    fun `方向盘按键零延迟直通与车联媒体浏览树结构验证`() {
        var toggleCount = 0
        var scheduledCount = 0
        val controller = HeadsetMediaButtonController(
            multiClickTimeoutMs = 350L,
            nowMsProvider = { 10_000L },
            scheduleDelayed = { _, _ -> scheduledCount++ },
            cancelScheduled = {},
            onTogglePlay = { toggleCount++ },
            onSkipToNext = {},
            onSkipToPrevious = {}
        )
        controller.multiClickSkipEnabled = true
        controller.bypassMultiClickDelay = true

        val handled = controller.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            action = KeyEvent.ACTION_DOWN,
            downTimeMs = 100L
        )
        assertTrue(handled)
        assertEquals("开启方向盘零延迟后单击应 0ms 立即执行，不进入 350ms 延迟调度", 1, toggleCount)
        assertEquals(0, scheduledCount)

        // 验证车联一级目录树五大核心栏目与点播反查
        val tabs = CarMediaLibraryHelper.buildTopLevelBrowsableTabs()
        assertEquals(5, tabs.size)
        assertEquals(CarMediaLibraryHelper.TAB_PERSONAL_FM, tabs[0].mediaId)
        assertEquals(CarMediaLibraryHelper.TAB_DAILY_RECOMMEND, tabs[1].mediaId)
        assertEquals(CarMediaLibraryHelper.TAB_LIKED_SONGS, tabs[2].mediaId)
        assertEquals(CarMediaLibraryHelper.TAB_USER_PLAYLISTS, tabs[3].mediaId)
        assertEquals(CarMediaLibraryHelper.TAB_RECENT_HISTORY, tabs[4].mediaId)

        val sampleQueue = listOf(
            Song(id = "car_1", title = "夜曲", artist = "周杰伦"),
            Song(id = "car_2", title = "稻香", artist = "周杰伦")
        )
        CarMediaLibraryHelper.registerTestQueueForLookup(CarMediaLibraryHelper.TAB_DAILY_RECOMMEND, sampleQueue)
        val playableItem = CarMediaLibraryHelper.buildPlayableSongMediaItem(
            CarMediaLibraryHelper.TAB_DAILY_RECOMMEND,
            sampleQueue[1]
        )
        val lookup = CarMediaLibraryHelper.lookupSongAndContextQueue(playableItem.mediaId)
        assertNotNull(lookup)
        assertEquals("稻香", lookup?.first?.title)
        assertEquals(2, lookup?.second?.size)
    }
}
