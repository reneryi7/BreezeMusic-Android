package com.concept.music

import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
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
        CarSessionManager.isCarFlavorOverrideForTest = false
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

        // 验证不切歌关闭车载后再开启主驾皇帝位时，ITD 缓冲已清零，输入静音帧不得吐出残留旧声波
        CarSessionManager.setManualPreviewActive(null, false)
        val silenceFrames = 256
        val silence1 = ByteBuffer.allocateDirect(silenceFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until silenceFrames * 2) silence1.putShort(0)
        silence1.flip()
        processor.queueInput(silence1)
        processor.output

        CarSessionManager.setManualPreviewActive(null, true)
        val silence2 = ByteBuffer.allocateDirect(silenceFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until silenceFrames * 2) silence2.putShort(0)
        silence2.flip()
        processor.queueInput(silence2)
        val silenceOut = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        var maxResidual = 0
        for (i in 0 until silenceFrames * 2) {
            val v = abs(silenceOut.short.toInt())
            if (v > maxResidual) maxResidual = v
        }
        assertTrue("重新启用主驾皇帝位时 ITD 缓冲应无残留旧音频爆音 (maxResidual=$maxResidual)", maxResidual < 50)
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

    @Test
    fun `R8泛型擦除Double列表与历史旧版混淆脏快照还原绝不崩溃验证`() {
        val fakePrefs = InMemorySharedPreferences()
        CarSessionManager.initWithSharedPreferencesForTest(fakePrefs)

        // 1. 构造真实 R8 Full Mode 剥离 List<Int>/List<Float> 泛型后产生的“有毒”快照（内部列表实际装入 Double）
        @Suppress("UNCHECKED_CAST")
        val poisonedSnapshot = PreCarEverydaySnapshot(
            effectEnabled = true,
            presetName = EffectPreset.VIPER_3D.name,
            bassBoost = 420,
            bassPunch = 200,
            bassCutoffHz = 65,
            virt = 300,
            depth = 180,
            crossfeed = 90,
            clarity = 240,
            deEsser = 120,
            air = 150,
            warmth = 110,
            transientAttack = 140,
            rotSpeed = 12,
            loudness = 260,
            preampDb = -1.5f,
            channelBalance = -0.25f,
            antiClip = true,
            eqLoudness = false,
            volumeNormalization = false,
            reverbName = ReverbMode.SMALL_ROOM.name,
            reverbWet = 210,
            reverbPreDelay = 18,
            reverbDamp = 360,
            bandLevels = listOf(100.0, 40.0, -20.0, 60.0, 80.0) as List<Int>,
            tenBandLevels = listOf(100.0, 80.0, 40.0, 0.0, -20.0, 20.0, 60.0, 80.0, 100.0, 120.0) as List<Int>,
            tenBandQFactors = List(10) { 1.35 } as List<Float>,
            tenBandCenterHz = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0) as List<Float>,
            tenBandFilterTypeNames = List(10) { PeqFilterType.PEAK.name },
            highPassCutoffHz = 25,
            lowPassCutoffHz = 19000,
            audioFocusModeName = AudioFocusMode.MIX.name
        )

        // 必须完整完成类型清洗并安全还原，不抛出 ClassCastException
        AudioEffectManager.restoreFromEverydaySnapshot(poisonedSnapshot)
        val restored = AudioEffectManager.state.value
        assertTrue(restored.isEnabled)
        assertEquals(EffectPreset.VIPER_3D, restored.currentPreset)
        assertEquals(listOf(100, 40, -20, 60, 80), restored.bandLevels)
        assertEquals(listOf(100, 80, 40, 0, -20, 20, 60, 80, 100, 120), restored.tenBandLevels)
        assertEquals(1.35f, restored.tenBandQFactors[0], 0.001f)
        assertEquals(16000f, restored.tenBandCenterHz[9], 0.1f)
        assertEquals(-0.25f, restored.channelBalance, 0.001f)

        // 2. 模拟旧版混淆包遗留的损坏快照 JSON（字段为 a..F）与损坏白名单记录，冷启动自愈必须安全忽略且不污染日常音效
        fakePrefs.edit()
            .putString("car_pending_everyday_snapshot_json", """{"a":false,"b":"OFF"}""")
            .putString("car_known_devices_json", """[{"a":"MAC|11:22:33:44:55:66","b":"OLD"},{"deviceKey":"MAC|AA:BB:CC:DD:EE:FF","displayName":"BYD DiLink","macAddress":"AA:BB:CC:DD:EE:FF","classification":"CONFIRMED_CAR","updatedAtMs":1000}]""")
            .commit()

        CarSessionManager.initWithSharedPreferencesForTest(fakePrefs)
        assertFalse("损坏的旧版混淆快照应被安全清理", fakePrefs.contains("car_pending_everyday_snapshot_json"))
        assertEquals("损坏旧快照绝不能覆写用户现有日常音效", EffectPreset.VIPER_3D, AudioEffectManager.state.value.currentPreset)
        assertEquals("白名单应过滤旧版残缺项并保留有效车机记录", 1, CarSessionManager.state.value.knownDevices.size)
        assertEquals("BYD DiLink", CarSessionManager.state.value.knownDevices.first().displayName)
    }

    @Test
    fun `临时退出车载不破坏白名单且支持投屏多连接引用计数与原车直通和焦点还原验证`() {
        val fakePrefs = InMemorySharedPreferences()
        CarSessionManager.initWithSharedPreferencesForTest(fakePrefs)

        // 设定用户日常状态：关闭响度均衡 + 混音同播 (MIX)
        AudioEffectManager.setVolumeNormalizationEnabled(false)
        PlaybackService.updateAudioFocusMode(AudioFocusMode.MIX)
        CarSessionManager.setAutoPlayOnCarConnect(true)

        var autoPlayCount = 0
        CarSessionManager.onTriggerCarAutoPlayCallback = { autoPlayCount++ }

        val carSink = ConnectedBluetoothAudioSink(
            deviceKey = "MAC|10:20:30:40:50:60",
            displayName = "Zeekr 001",
            macAddress = "10:20:30:40:50:60",
            isHardwareCarAudioClass = true
        )
        CarSessionManager.onBluetoothSinksUpdated(null, listOf(carSink), newlyAddedBluetooth = false)

        // 1. 设置页手动设为车机默认不突兀触发自动续播
        CarSessionManager.setDeviceClassification(
            context = null,
            deviceKey = carSink.deviceKey,
            displayName = carSink.displayName,
            macAddress = carSink.macAddress,
            classification = CarDeviceClassification.CONFIRMED_CAR
        )
        assertTrue(CarSessionManager.state.value.isCarModeActive)
        assertEquals("手动在设置页绑定车机不应触发意外自动续播", 0, autoPlayCount)
        assertTrue("主驾皇帝位期间应临时开启响度均衡", AudioEffectManager.state.value.volumeNormalizationEnabled)
        assertEquals(AudioFocusMode.DUCK, PlaybackService.currentAudioFocusMode)

        // 2. 切至「原车音响直通」时应还原上车前的响度均衡原值 (false)
        CarSessionManager.setCabinAcousticMode(CarCabinAcousticMode.DIRECT_BYPASS)
        assertFalse("原车音响直通不应继续保留临时强制开启的响度均衡", AudioEffectManager.state.value.volumeNormalizationEnabled)

        // 3. 车载期间关闭「智能导航闪避」应还原回日常 MIX 焦点而非硬编码 EXCLUSIVE
        CarSessionManager.setSmartNavDuckingEnabled(false)
        assertEquals(AudioFocusMode.MIX, PlaybackService.currentAudioFocusMode)
        CarSessionManager.setSmartNavDuckingEnabled(true)
        assertEquals(AudioFocusMode.DUCK, PlaybackService.currentAudioFocusMode)

        // 4. 点击「临时退出车载模式」：立即退出并还原日常，且绝不把爱车篡改为 IGNORED_NON_CAR
        CarSessionManager.disconnectCurrentCarSessionTemporarily(null)
        assertFalse(CarSessionManager.state.value.isCarModeActive)
        assertEquals(AudioFocusMode.MIX, PlaybackService.currentAudioFocusMode)
        assertEquals(
            "临时退出绝不能篡改已认证车机白名单",
            CarDeviceClassification.CONFIRMED_CAR,
            CarSessionManager.state.value.knownDevices.first().classification
        )
        assertNull("临时退出后的白名单车机绝不能误弹首次询问卡", CarSessionManager.state.value.pendingPromptCandidate)

        // 断开蓝牙再重新连上 -> 自动恢复车载感知
        CarSessionManager.onBluetoothSinksUpdated(null, emptyList(), newlyAddedBluetooth = false)
        CarSessionManager.onBluetoothSinksUpdated(null, listOf(carSink), newlyAddedBluetooth = true)
        assertTrue("蓝牙断开重连后应自动重新激活车载模式", CarSessionManager.state.value.isCarModeActive)
        CarSessionManager.onBluetoothSinksUpdated(null, emptyList(), newlyAddedBluetooth = false)
        assertFalse(CarSessionManager.state.value.isCarModeActive)

        // 5. 多 Controller 投屏宿主引用计数与投屏临时退出验证
        CarSessionManager.onProjectionControllerConnected(null, "com.huawei.hicar")
        CarSessionManager.onProjectionControllerConnected(null, "com.huawei.hicar")
        assertTrue(CarSessionManager.state.value.isCarModeActive)
        assertEquals(CarConnectionSource.PROJECTION_HOST, CarSessionManager.state.value.activeConnectionSource)

        // 断开其中一个连接（例如浏览树连接先断开）-> 另一个播控连接仍在，不应退出车载模式
        CarSessionManager.onProjectionControllerDisconnected(null, "com.huawei.hicar")
        assertTrue("同一车联宿主存在多个控制器连接时，单连接断开不应提前退出车载", CarSessionManager.state.value.isCarModeActive)

        // 投屏状态下支持一键临时退出
        CarSessionManager.disconnectCurrentCarSessionTemporarily(null)
        assertFalse("投屏互联状态下点击临时退出必须生效", CarSessionManager.state.value.isCarModeActive)

        // 彻底断开全部投屏连接再重新投屏 -> 自动恢复激活
        CarSessionManager.onProjectionControllerDisconnected(null, "com.huawei.hicar")
        CarSessionManager.onProjectionControllerConnected(null, "com.huawei.hicar")
        assertTrue("投屏断开重连后应自动重新进入车载模式", CarSessionManager.state.value.isCarModeActive)
        CarSessionManager.onProjectionControllerDisconnected(null, "com.huawei.hicar")
        assertFalse(CarSessionManager.state.value.isCarModeActive)
    }

    @Test
    fun `ProGuard混淆规则必须长期保留车载与纯本地歌单持久化模型`() {
        val proguardCandidates = listOf(
            java.io.File("proguard-rules.pro"),
            java.io.File("app/proguard-rules.pro")
        )
        val proguardFile = proguardCandidates.firstOrNull { it.exists() }
        assertNotNull("应能定位到 app/proguard-rules.pro", proguardFile)
        val content = proguardFile!!.readText(Charsets.UTF_8)
        listOf(
            "com.concept.music.service.PreCarEverydaySnapshot",
            "com.concept.music.service.CarBluetoothDeviceRecord",
            "com.concept.music.service.CarDeviceClassification",
            "com.concept.music.data.repository.LocalOnlyPlaylistRecord",
            "com.concept.music.data.repository.PersistedLocalPlaylistStore"
        ).forEach { cls ->
            assertTrue("proguard-rules.pro 必须保留 $cls 防止 R8 Full Mode 剥离泛型与字段名", content.contains(cls))
        }
    }

    @Test
    fun `CarLyricForwardingListener必须显式覆写PlayerListener全部回调防止Kotlin委托吞噬事件`() {
        fun methodKey(m: java.lang.reflect.Method): String =
            "${m.name}(${m.parameterTypes.joinToString(",") { it.name }})"

        val expectedInterfaceMethods = Player.Listener::class.java.declaredMethods
            .filter { !it.isSynthetic && !it.isBridge && !Modifier.isStatic(it.modifiers) }
            .map(::methodKey)
            .toSet()

        val actualDeclaredMethods = CarLyricForwardingListener::class.java.declaredMethods
            .filter { !it.isSynthetic && !it.isBridge && !Modifier.isStatic(it.modifiers) }
            .map(::methodKey)
            .toSet()

        val missing = expectedInterfaceMethods - actualDeclaredMethods
        assertTrue(
            "CarLyricForwardingListener 漏实现 Player.Listener 回调，会导致 MediaSession 收不到播放状态/进度变更: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `CarLyricForwardingListener完整透传播放状态进度命令与车载元数据且支持精确removeListener`() {
        val registeredListeners = mutableListOf<Player.Listener>()
        val rawPlayer = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, method, args ->
            when (method.name) {
                "addListener" -> {
                    registeredListeners.add(args[0] as Player.Listener)
                    null
                }
                "removeListener" -> {
                    registeredListeners.remove(args[0] as Player.Listener)
                    null
                }
                "getAvailableCommands" -> Player.Commands.EMPTY
                "getMediaMetadata" -> MediaMetadata.EMPTY
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    else -> null
                }
            }
        } as Player

        var carLyricActive = false
        val carMetadata = MediaMetadata.Builder()
            .setTitle("故事的小黄花")
            .setArtist("晴天 · 周杰伦")
            .setDisplayTitle("晴天")
            .setSubtitle("周杰伦")
            .build()
        val expectedMergedCommands = mergeCarNavigationCommands(Player.Commands.EMPTY)
        var capturedCommandTransformInput: Player.Commands? = null

        val forwardingPlayer = object : ForwardingPlayer(rawPlayer) {
            override fun addListener(listener: Player.Listener) {
                super.addListener(
                    CarLyricForwardingListener(
                        forwardingPlayer = this,
                        delegate = listener,
                        isCarBluetoothLyricActive = { carLyricActive },
                        resolveEffectiveMetadata = { carMetadata },
                        transformAvailableCommands = { incoming ->
                            capturedCommandTransformInput = incoming
                            expectedMergedCommands
                        }
                    )
                )
            }

            override fun removeListener(listener: Player.Listener) {
                super.removeListener(
                    CarLyricForwardingListener(
                        forwardingPlayer = this,
                        delegate = listener,
                        isCarBluetoothLyricActive = { carLyricActive },
                        resolveEffectiveMetadata = { carMetadata },
                        transformAvailableCommands = { incoming ->
                            capturedCommandTransformInput = incoming
                            expectedMergedCommands
                        }
                    )
                )
            }
        }

        val observedIsPlaying = mutableListOf<Boolean>()
        val observedStates = mutableListOf<Int>()
        val observedPlayWhenReady = mutableListOf<Pair<Boolean, Int>>()
        var timelineChangedCount = 0
        var transitionedMediaId: String? = null
        var discontinuityReason: Int? = null
        var observedSpeed = 0f
        var observedCommands: Player.Commands? = null
        var observedErrorCode: Int? = null
        val observedMetadataTitles = mutableListOf<String>()
        var eventsPlayerRef: Player? = null

        val downstreamListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                observedIsPlaying.add(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                observedStates.add(playbackState)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                observedPlayWhenReady.add(playWhenReady to reason)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                timelineChangedCount++
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                transitionedMediaId = mediaItem?.mediaId
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                discontinuityReason = reason
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                observedSpeed = playbackParameters.speed
            }

            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                observedCommands = availableCommands
            }

            override fun onPlayerError(error: PlaybackException) {
                observedErrorCode = error.errorCode
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                observedMetadataTitles.add(mediaMetadata.title?.toString().orEmpty())
            }

            override fun onEvents(player: Player, events: Player.Events) {
                eventsPlayerRef = player
            }
        }

        forwardingPlayer.addListener(downstreamListener)
        assertEquals("向 ForwardingPlayer 注册后底层应挂接 1 个包装监听", 1, registeredListeners.size)

        val wrappedOnRawPlayer = registeredListeners.first()
        val sampleItem = MediaItem.Builder().setMediaId("song_150").build()
        val posInfo = Player.PositionInfo(null, 0, sampleItem, null, 0, 12_000L, 12_000L, -1, -1)
        val rawMetadata = MediaMetadata.Builder().setTitle("晴天").setArtist("周杰伦").build()
        val sampleEvents = Player.Events(FlagSet.Builder().add(Player.EVENT_IS_PLAYING_CHANGED).build())

        wrappedOnRawPlayer.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        wrappedOnRawPlayer.onMediaItemTransition(sampleItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        wrappedOnRawPlayer.onPlaybackStateChanged(Player.STATE_BUFFERING)
        wrappedOnRawPlayer.onPlaybackStateChanged(Player.STATE_READY)
        wrappedOnRawPlayer.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        wrappedOnRawPlayer.onIsPlayingChanged(true)
        wrappedOnRawPlayer.onPositionDiscontinuity(posInfo, posInfo, Player.DISCONTINUITY_REASON_SEEK)
        wrappedOnRawPlayer.onPlaybackParametersChanged(PlaybackParameters(1.25f))
        wrappedOnRawPlayer.onAvailableCommandsChanged(Player.Commands.EMPTY)
        wrappedOnRawPlayer.onPlayerError(
            PlaybackException("test", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        )
        wrappedOnRawPlayer.onMediaMetadataChanged(rawMetadata)
        carLyricActive = true
        wrappedOnRawPlayer.onMediaMetadataChanged(rawMetadata)
        wrappedOnRawPlayer.onEvents(rawPlayer, sampleEvents)

        assertEquals(listOf(true), observedIsPlaying)
        assertEquals(listOf(Player.STATE_BUFFERING, Player.STATE_READY), observedStates)
        assertEquals(listOf(true to Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST), observedPlayWhenReady)
        assertEquals(1, timelineChangedCount)
        assertEquals("song_150", transitionedMediaId)
        assertEquals(Player.DISCONTINUITY_REASON_SEEK, discontinuityReason)
        assertEquals(1.25f, observedSpeed, 0.001f)
        assertSame(Player.Commands.EMPTY, capturedCommandTransformInput)
        assertSame(expectedMergedCommands, observedCommands)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, observedErrorCode)
        assertEquals(listOf("晴天", "故事的小黄花"), observedMetadataTitles)
        assertSame("onEvents 必须将 ForwardingPlayer 作为事件源传递给下游", forwardingPlayer, eventsPlayerRef)

        // 验证 removeListener 依赖 equals/hashCode 精确解绑，不再产生重复或残留监听
        forwardingPlayer.removeListener(downstreamListener)
        assertTrue("调用 removeListener 后底层监听列表必须清空", registeredListeners.isEmpty())
    }
}
