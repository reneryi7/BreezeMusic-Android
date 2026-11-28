package com.concept.music.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.concept.music.MainActivity
import com.concept.music.R
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 音频焦点与多应用并发模式（三档可选）：
 * 1. EXCLUSIVE：独占焦点（默认），其他应用播放音视频时自动暂停本应用，导航/短通知音自动闪避；
 * 2. DUCK：压低音量同播（智能闪避），其他应用播放音频时不暂停本应用，而是将本应用音量平滑压低至 30% 作为背景伴奏，对方结束后恢复 100%；
 * 3. MIX：完全混音同播，不抢占音频焦点且不压低音量，与其他应用保持 100% 原音量同时播放。
 */
enum class AudioFocusMode(val label: String, val subtitle: String) {
    EXCLUSIVE("独占焦点", "其他应用播放音视频时自动暂停，导航与通知音自动闪避"),
    DUCK("压低音量同播", "其他应用发声时不暂停音乐，自动压低本应用音量至 30% 伴奏"),
    MIX("完全混音同播", "完全不抢占焦点，与其他应用保持 100% 原音量同时播放")
}

/**
 * 有线耳机线控连击灵敏度（多击判定时间窗）
 */
enum class HeadsetClickSensitivity(val label: String, val subtitle: String, val timeoutMs: Long) {
    FAST("紧凑 280ms", "响应干脆，适合快速连按", 280L),
    STANDARD("标准 350ms", "默认均衡手感", 350L),
    RELAXED("宽松 450ms", "适合偏硬按键或慢速连按", 450L)
}

/**
 * 有线耳机与媒体按键多击线控状态机：
 * 解决 AndroidX Media3 默认对有线耳机单键 (`KEYCODE_HEADSETHOOK`) 双击/三击无法切歌的问题，
 * 支持自定义连击灵敏度（280ms / 350ms / 450ms）与双击/三击切歌方向交换：
 * - 单击暂停/中键：播放 / 暂停切换
 * - 双击暂停/中键：默认切换下一首（开启方向交换后为切换上一首）
 * - 三击暂停/中键：默认切换上一首（开启方向交换后为切换下一首）
 * - 独立上下曲键 (`KEYCODE_MEDIA_NEXT` / `KEYCODE_MEDIA_PREVIOUS`)：立即切歌
 */
class HeadsetMediaButtonController(
    multiClickTimeoutMs: Long = 350L,
    private val duplicateGuardWindowMs: Long = 25L,
    private val tripleClickCooldownMs: Long = 220L,
    private val nowMsProvider: () -> Long = {
        runCatching { SystemClock.uptimeMillis() }.getOrDefault(0L).takeIf { it > 0L }
            ?: System.currentTimeMillis()
    },
    private val scheduleDelayed: (delayMs: Long, action: () -> Unit) -> Unit,
    private val cancelScheduled: () -> Unit,
    private val onTogglePlay: () -> Unit,
    private val onSkipToNext: () -> Unit,
    private val onSkipToPrevious: () -> Unit,
    private val onExplicitPlay: (() -> Unit)? = null,
    private val onExplicitPause: (() -> Unit)? = null
) {
    @Volatile
    var multiClickTimeoutMs: Long = multiClickTimeoutMs

    @Volatile
    var multiClickSkipEnabled: Boolean = true

    @Volatile
    var swapDoubleTripleClick: Boolean = false

    @Volatile
    var bypassMultiClickDelay: Boolean = false

    private var pendingClickCount: Int = 0
    private var lastDownTimeMs: Long = -1L
    private var lastKeyCode: Int = -1
    private var lastHandledAtMs: Long = -1L
    private var lastTripleClickAtMs: Long = -1L

    fun isSupportedMediaKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> true
        else -> false
    }

    private fun isToggleOrHookKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> true
        else -> false
    }

    private fun triggerDoubleClickAction() {
        if (swapDoubleTripleClick) {
            onSkipToPrevious()
        } else {
            onSkipToNext()
        }
    }

    private fun triggerTripleClickAction() {
        if (swapDoubleTripleClick) {
            onSkipToNext()
        } else {
            onSkipToPrevious()
        }
    }

    fun handleKeyEvent(
        keyCode: Int,
        action: Int,
        repeatCount: Int = 0,
        downTimeMs: Long = 0L
    ): Boolean {
        if (!isSupportedMediaKey(keyCode)) return false
        if (action == KeyEvent.ACTION_UP) return true
        if (action != KeyEvent.ACTION_DOWN) return true
        if (repeatCount > 0) return true

        val now = nowMsProvider()
        if (downTimeMs > 0L && downTimeMs == lastDownTimeMs && keyCode == lastKeyCode) {
            return true
        }
        if (now > 0L && lastHandledAtMs > 0L &&
            (now - lastHandledAtMs) in 0L until duplicateGuardWindowMs &&
            keyCode == lastKeyCode
        ) {
            return true
        }
        lastDownTimeMs = downTimeMs
        lastKeyCode = keyCode
        lastHandledAtMs = now

        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                cancelScheduled()
                pendingClickCount = 0
                (onExplicitPlay ?: onTogglePlay).invoke()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                cancelScheduled()
                pendingClickCount = 0
                (onExplicitPause ?: onTogglePlay).invoke()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                cancelScheduled()
                pendingClickCount = 0
                onSkipToNext()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                cancelScheduled()
                pendingClickCount = 0
                onSkipToPrevious()
                return true
            }
        }

        if (isToggleOrHookKey(keyCode)) {
            if (!multiClickSkipEnabled || bypassMultiClickDelay) {
                cancelScheduled()
                pendingClickCount = 0
                onTogglePlay()
                return true
            }
            if (now > 0L && lastTripleClickAtMs > 0L && (now - lastTripleClickAtMs) in 0L until tripleClickCooldownMs) {
                return true
            }

            cancelScheduled()
            pendingClickCount += 1
            if (pendingClickCount >= 3) {
                pendingClickCount = 0
                lastTripleClickAtMs = now
                triggerTripleClickAction()
            } else {
                val windowMs = multiClickTimeoutMs.coerceIn(180L, 800L)
                scheduleDelayed(windowMs) {
                    val count = pendingClickCount
                    pendingClickCount = 0
                    when (count) {
                        1 -> onTogglePlay()
                        2 -> triggerDoubleClickAction()
                        else -> if (count >= 3) triggerTripleClickAction()
                    }
                }
            }
            return true
        }
        return false
    }

    fun reset() {
        cancelScheduled()
        pendingClickCount = 0
        lastDownTimeMs = -1L
        lastKeyCode = -1
        lastHandledAtMs = -1L
        lastTripleClickAtMs = -1L
    }
}

// 64-bit 纯线性双二阶母带级音频处理器已独立拆分至 ConceptMasteringAudioProcessor.kt (支持 10段 PEQ 与 hasActiveEqBands 零开销短路)

internal fun mergeCarNavigationCommands(base: Player.Commands): Player.Commands {
    return base.buildUpon()
        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_TO_NEXT)
        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        .build()
}

/**
 * 显式全量转发的 Media3 `Player.Listener` 包装器：
 * - 严禁使用 Kotlin 接口委托（`Player.Listener by delegate`），因为 `Player.Listener` 是全部回调均为 Java `default` 方法的 Java 接口，
 *   Kotlin 类委托不会为 Java `default` 方法生成转发存根，会导致 `onIsPlayingChanged` / `onPlaybackStateChanged` / `onTimelineChanged` 等
 *   全部播放状态与进度事件被吞噬，令 `MediaController` 永久停留在暂停/空闲态。
 * - 显式逐一覆写并转发全部 37 个回调，同时在车载蓝牙呼吸歌词激活时拦截 `onMediaMetadataChanged`，并在 `onAvailableCommandsChanged` 中保持上下曲可用。
 */
@OptIn(UnstableApi::class)
internal class CarLyricForwardingListener(
    private val forwardingPlayer: Player,
    private val delegate: Player.Listener,
    private val isCarBluetoothLyricActive: () -> Boolean,
    private val resolveEffectiveMetadata: () -> MediaMetadata,
    private val transformAvailableCommands: (Player.Commands) -> Player.Commands = ::mergeCarNavigationCommands
) : Player.Listener {

    override fun onEvents(player: Player, events: Player.Events) {
        delegate.onEvents(forwardingPlayer, events)
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        delegate.onTimelineChanged(timeline, reason)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        delegate.onMediaItemTransition(mediaItem, reason)
    }

    override fun onTracksChanged(tracks: Tracks) {
        delegate.onTracksChanged(tracks)
    }

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        val effectiveMeta = if (isCarBluetoothLyricActive()) resolveEffectiveMetadata() else mediaMetadata
        delegate.onMediaMetadataChanged(effectiveMeta)
    }

    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {
        delegate.onPlaylistMetadataChanged(mediaMetadata)
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
        delegate.onIsLoadingChanged(isLoading)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onLoadingChanged(isLoading: Boolean) {
        delegate.onLoadingChanged(isLoading)
    }

    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
        delegate.onAvailableCommandsChanged(transformAvailableCommands(availableCommands))
    }

    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
        delegate.onTrackSelectionParametersChanged(parameters)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
        delegate.onPlayerStateChanged(playWhenReady, playbackState)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        delegate.onPlaybackStateChanged(playbackState)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        delegate.onPlayWhenReadyChanged(playWhenReady, reason)
    }

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        delegate.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        delegate.onIsPlayingChanged(isPlaying)
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        delegate.onRepeatModeChanged(repeatMode)
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        delegate.onShuffleModeEnabledChanged(shuffleModeEnabled)
    }

    override fun onPlayerError(error: PlaybackException) {
        delegate.onPlayerError(error)
    }

    override fun onPlayerErrorChanged(error: PlaybackException?) {
        delegate.onPlayerErrorChanged(error)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onPositionDiscontinuity(reason: Int) {
        delegate.onPositionDiscontinuity(reason)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        delegate.onPositionDiscontinuity(oldPosition, newPosition, reason)
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        delegate.onPlaybackParametersChanged(playbackParameters)
    }

    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) {
        delegate.onSeekBackIncrementChanged(seekBackIncrementMs)
    }

    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) {
        delegate.onSeekForwardIncrementChanged(seekForwardIncrementMs)
    }

    override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) {
        delegate.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        delegate.onAudioSessionIdChanged(audioSessionId)
    }

    override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) {
        delegate.onAudioAttributesChanged(audioAttributes)
    }

    override fun onVolumeChanged(volume: Float) {
        delegate.onVolumeChanged(volume)
    }

    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
        delegate.onSkipSilenceEnabledChanged(skipSilenceEnabled)
    }

    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
        delegate.onDeviceInfoChanged(deviceInfo)
    }

    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
        delegate.onDeviceVolumeChanged(volume, muted)
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        delegate.onVideoSizeChanged(videoSize)
    }

    override fun onSurfaceSizeChanged(width: Int, height: Int) {
        delegate.onSurfaceSizeChanged(width, height)
    }

    override fun onRenderedFirstFrame() {
        delegate.onRenderedFirstFrame()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCues(cues: MutableList<Cue>) {
        delegate.onCues(cues)
    }

    override fun onCues(cueGroup: CueGroup) {
        delegate.onCues(cueGroup)
    }

    override fun onMetadata(metadata: Metadata) {
        delegate.onMetadata(metadata)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CarLyricForwardingListener) return false
        return forwardingPlayer === other.forwardingPlayer && delegate == other.delegate
    }

    override fun hashCode(): Int {
        var result = System.identityHashCode(forwardingPlayer)
        result = 31 * result + delegate.hashCode()
        return result
    }
}

/**
 * AndroidX Media3 官方后台播放与车载媒体树服务 (`MediaLibraryService`)
 * 提供锁屏控制、通知栏控制面板、有线/蓝牙耳机与方向盘零延迟按键拦截、三档音频焦点与车载导航平滑闪避、
 * 车载蓝牙高定呼吸歌词推送以及 Android Auto / HiCar / CarWith / Carlink 媒体浏览树服务。
 */
class PlaybackService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaLibrarySession? = null
    private var carForwardingPlayer: CarLyricForwardingPlayer? = null
    private val libraryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var audioManager: AudioManager? = null
    private var duckFocusRequest: AudioFocusRequest? = null
    private var duckReleaseHoldRunnable: Runnable? = null
    private var hasActiveDuckFocus: Boolean = false
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var bgAutoAdvanceRunnable: Runnable? = null
    private var carNetworkRetryRunnable: Runnable? = null
    private var carNetworkRetryCount: Int = 0
    private var initialDeviceEnumerationDone: Boolean = false
    private val connectedWiredIds = mutableSetOf<Int>()
    private val connectedBluetoothIds = mutableSetOf<Int>()
    private val connectedCarBusIds = mutableSetOf<Int>()
    private val verifiedCarControllers = CopyOnWriteArraySet<MediaSession.ControllerInfo>()

    private val duckAudioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        duckReleaseHoldRunnable?.let { mainHandler?.removeCallbacks(it) }
        duckReleaseHoldRunnable = null
        if (currentAudioFocusMode != AudioFocusMode.DUCK) {
            audioFocusDuckGain = 1.0f
            return@OnAudioFocusChangeListener
        }
        val carState = CarSessionManager.state.value
        val useCarSmartDuck = CarSessionManager.isPhoneCarFeatureSupported() &&
            carState.isCarModeActive &&
            carState.smartNavDuckingEnabled
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 永久失去焦点（如用户主动切到其他音乐/视频播放器）：平滑暂停并复位音量增益，避免卡死 30% 音量
                audioFocusDuckGain = 1.0f
                player?.pause()
                abandonDuckAudioFocusRequest()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 临时闪避（导航/语音/短通知）：车载模式支持自定义压低比例与频段避让，日常模式默认 30%
                audioFocusDuckGain = if (useCarSmartDuck) {
                    carState.navDuckingTargetGain.coerceIn(0.15f, 0.50f)
                } else {
                    0.30f
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (useCarSmartDuck) {
                    // 车载智能防呼吸保持期（350ms Hold）：连续两段导航语音播报停顿间保持平滑压低，消除音量忽大忽小抽搐
                    val holdRunnable = Runnable {
                        duckReleaseHoldRunnable = null
                        if (currentAudioFocusMode == AudioFocusMode.DUCK) {
                            audioFocusDuckGain = 1.0f
                        }
                    }
                    duckReleaseHoldRunnable = holdRunnable
                    val h = mainHandler
                    if (h != null) {
                        h.postDelayed(holdRunnable, 350L)
                    } else {
                        audioFocusDuckGain = 1.0f
                    }
                } else {
                    audioFocusDuckGain = 1.0f
                }
            }
        }
    }

    data class ServiceSleepTimerState(
        val remainingSec: Long = 0L,
        val stopAfterCurrentSong: Boolean = false,
        val pendingStopAtTrackEnd: Boolean = false
    ) {
        val isInFinalFade: Boolean
            get() = remainingSec in 1L..5L
    }

    companion object {
        @Volatile
        var isAppPlaybackActive: Boolean = false

        private val serviceSleepScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var serviceSleepJob: Job? = null
        private val _sleepTimerState = MutableStateFlow(ServiceSleepTimerState())
        val sleepTimerState: StateFlow<ServiceSleepTimerState> = _sleepTimerState.asStateFlow()

        /**
         * 防止前台播放时 Service 的 ExoPlayer 监听先消费 `pendingStopAtTrackEnd`，导致稍后到达
         * MediaController IPC 回调的 `MusicPlayerViewModel.onTrackEnded()` 误判为未开启定时而续播下一首。
         */
        @Volatile
        private var pendingViewModelTrackEndStopAckUntilMs: Long = 0L

        fun clearViewModelTrackEndStopAck() {
            pendingViewModelTrackEndStopAckUntilMs = 0L
        }

        fun consumeViewModelTrackEndStopAck(): Boolean {
            val deadline = pendingViewModelTrackEndStopAckUntilMs
            if (deadline <= 0L) return false
            pendingViewModelTrackEndStopAckUntilMs = 0L
            return safeServiceElapsedRealtime() <= deadline
        }

        private fun safeServiceElapsedRealtime(): Long =
            runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L).takeIf { it > 0L }
                ?: System.currentTimeMillis()

        fun startServiceSleepTimer(minutes: Int, stopAfterCurrentSong: Boolean) {
            serviceSleepJob?.cancel()
            serviceSleepJob = null
            pendingViewModelTrackEndStopAckUntilMs = 0L
            val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
            fun onPlayerMain(action: (ExoPlayer) -> Unit) {
                val p = activeServiceInstance?.player ?: return
                if (mainLooper == null || Looper.myLooper() == mainLooper) {
                    runCatching { action(p) }
                } else {
                    Handler(mainLooper).post { runCatching { action(p) } }
                }
            }
            if (minutes <= 0) {
                onPlayerMain { p ->
                    p.volume = 1f
                    if (stopAfterCurrentSong) {
                        while (p.mediaItemCount > p.currentMediaItemIndex + 1) {
                            p.removeMediaItem(p.mediaItemCount - 1)
                        }
                    }
                }
                _sleepTimerState.value = ServiceSleepTimerState(
                    remainingSec = 0L,
                    stopAfterCurrentSong = stopAfterCurrentSong,
                    pendingStopAtTrackEnd = stopAfterCurrentSong
                )
                return
            }
            val totalSec = minutes.toLong() * 60L
            _sleepTimerState.value = ServiceSleepTimerState(
                remainingSec = totalSec,
                stopAfterCurrentSong = stopAfterCurrentSong,
                pendingStopAtTrackEnd = stopAfterCurrentSong
            )
            serviceSleepJob = serviceSleepScope.launch {
                val endElapsedMs = safeServiceElapsedRealtime() + totalSec * 1000L
                while (true) {
                    val remMs = (endElapsedMs - safeServiceElapsedRealtime()).coerceAtLeast(0L)
                    val remSec = (remMs + 999L) / 1000L
                    var stopPrefSnapshot = false
                    _sleepTimerState.update { cur ->
                        stopPrefSnapshot = cur.stopAfterCurrentSong
                        cur.copy(remainingSec = remSec)
                    }
                    if (remSec <= 0L) break
                    if (remSec in 1L..5L && !stopPrefSnapshot) {
                        val fadeVol = (remSec / 5f).coerceIn(0.10f, 1f)
                        onPlayerMain { it.volume = fadeVol }
                    }
                    delay(1000L)
                }
                var shouldWaitTrackEnd = false
                _sleepTimerState.update { latest ->
                    shouldWaitTrackEnd = latest.stopAfterCurrentSong
                    latest.copy(
                        remainingSec = 0L,
                        pendingStopAtTrackEnd = latest.stopAfterCurrentSong
                    )
                }
                if (shouldWaitTrackEnd) {
                    onPlayerMain { playerInst ->
                        playerInst.volume = 1f
                        while (playerInst.mediaItemCount > playerInst.currentMediaItemIndex + 1) {
                            playerInst.removeMediaItem(playerInst.mediaItemCount - 1)
                        }
                    }
                } else {
                    onPlayerMain { playerInst ->
                        playerInst.pause()
                        playerInst.volume = 1f
                    }
                }
            }
        }

        fun updateSleepStopAfterCurrentSongPref(stopAfter: Boolean) {
            _sleepTimerState.update { cur ->
                val nextPending = if (cur.remainingSec > 0L) stopAfter else cur.pendingStopAtTrackEnd && stopAfter
                cur.copy(
                    stopAfterCurrentSong = stopAfter,
                    pendingStopAtTrackEnd = nextPending
                )
            }
        }

        fun cancelServiceSleepTimer() {
            serviceSleepJob?.cancel()
            serviceSleepJob = null
            pendingViewModelTrackEndStopAckUntilMs = 0L
            val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
            activeServiceInstance?.player?.let { p ->
                if (mainLooper == null || Looper.myLooper() == mainLooper) {
                    runCatching { p.volume = 1f }
                } else {
                    Handler(mainLooper).post { runCatching { p.volume = 1f } }
                }
            }
            _sleepTimerState.update {
                it.copy(
                    remainingSec = 0L,
                    pendingStopAtTrackEnd = false
                )
            }
        }

        fun consumePendingTrackEndStop(markViewModelAck: Boolean = false): Boolean {
            var consumed = false
            _sleepTimerState.update { cur ->
                if (!cur.pendingStopAtTrackEnd || cur.remainingSec > 0L) {
                    cur
                } else {
                    consumed = true
                    cur.copy(pendingStopAtTrackEnd = false)
                }
            }
            if (consumed && markViewModelAck) {
                pendingViewModelTrackEndStopAckUntilMs = safeServiceElapsedRealtime() + 4_000L
            }
            return consumed
        }

        const val ACTION_CUSTOM_TOGGLE_DESKTOP_LYRIC = "com.concept.music.action.TOGGLE_DESKTOP_LYRIC"
        const val ACTION_CUSTOM_TOGGLE_DESKTOP_LYRIC_LOCK = "com.concept.music.action.TOGGLE_DESKTOP_LYRIC_LOCK"
        const val ACTION_CAR_BOOT_AUTO_RESUME = "com.concept.music.action.CAR_BOOT_AUTO_RESUME"

        val SESSION_COMMAND_TOGGLE_DESKTOP_LYRIC: SessionCommand
            get() = SessionCommand(ACTION_CUSTOM_TOGGLE_DESKTOP_LYRIC, Bundle())
        val SESSION_COMMAND_TOGGLE_DESKTOP_LYRIC_LOCK: SessionCommand
            get() = SessionCommand(ACTION_CUSTOM_TOGGLE_DESKTOP_LYRIC_LOCK, Bundle())

        /**
         * 构建适配 Android 13~16 系统媒体通知栏与 OPPO ColorOS 流体云（Fluid Cloud）/ 灵动岛的自定义控制按钮布局：
         * - 车机独立包（IS_CAR_FLAVOR）已剥离跨应用悬浮窗桌面歌词，返回空列表以保持车机系统媒体通知干净纯粹；
         * - 手机/平板保留桌面歌词开关与锁定双按钮。
         */
        fun buildNotificationCustomLayout(
            lyricEnabled: Boolean = SystemLyricBridge.state.value.floatingCapsuleEnabled,
            lyricLocked: Boolean = SystemLyricBridge.state.value.floatingCapsuleLocked
        ): List<CommandButton> {
            if (com.concept.music.BuildConfig.IS_CAR_FLAVOR) {
                return emptyList()
            }
            if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.NOTIFICATION_MEDIA)) {
                return emptyList()
            }
            val toggleLyricBtn = CommandButton.Builder()
                .setDisplayName(if (lyricEnabled) "关闭桌面歌词" else "开启桌面歌词")
                .setSessionCommand(SESSION_COMMAND_TOGGLE_DESKTOP_LYRIC)
                .setIconResId(if (lyricEnabled) R.drawable.ic_notif_lyric_on else R.drawable.ic_notif_lyric_off)
                .setEnabled(true)
                .build()

            val toggleLockBtn = CommandButton.Builder()
                .setDisplayName(if (lyricLocked) "解锁桌面歌词" else "锁定桌面歌词")
                .setSessionCommand(SESSION_COMMAND_TOGGLE_DESKTOP_LYRIC_LOCK)
                .setIconResId(if (lyricLocked) R.drawable.ic_notif_lyric_locked else R.drawable.ic_notif_lyric_unlocked)
                .setEnabled(true)
                .build()

            return listOf(toggleLyricBtn, toggleLockBtn)
        }

        fun refreshMediaNotificationCustomLayout() {
            val svc = activeServiceInstance ?: return
            runCatching {
                val mainLooper = Looper.getMainLooper()
                if (mainLooper == null || Looper.myLooper() == mainLooper) {
                    svc.refreshMediaSessionCustomLayoutInternal()
                } else {
                    mainHandler?.post {
                        svc.refreshMediaSessionCustomLayoutInternal()
                    }
                }
            }
        }

        private val mainHandler: Handler? by lazy {
            runCatching {
                val looper = Looper.getMainLooper()
                if (looper != null) Handler(looper) else null
            }.getOrNull()
        }
        private var pendingHeadsetRunnable: Runnable? = null

        @Volatile
        private var activeServiceInstance: PlaybackService? = null

        @Volatile
        private var fallbackAppContext: Context? = null

        @Volatile
        var currentAudioFocusMode: AudioFocusMode = AudioFocusMode.EXCLUSIVE
            private set

        @Volatile
        var audioFocusDuckGain: Float = 1.0f
            internal set

        @Volatile
        var autoPlayOnHeadsetConnectEnabled: Boolean = false

        @Volatile
        var autoPlayOnWiredConnectEnabled: Boolean = true

        @Volatile
        var autoPlayOnBluetoothConnectEnabled: Boolean = true

        @Volatile
        var keepPlayingOnTaskRemovedEnabled: Boolean = true

        @Volatile
        var isBluetoothAudioOutputConnected: Boolean = false
            internal set

        @Volatile
        var onBluetoothConnectionChanged: ((Boolean) -> Unit)? = null

        @Volatile
        var onSkipToPreviousIntercepted: (() -> Boolean)? = null
            set(value) {
                field = value
                PlaybackCoordinator.onSkipToPreviousDelegate = value
            }

        @Volatile
        var onSkipToNextIntercepted: (() -> Boolean)? = null
            set(value) {
                field = value
                PlaybackCoordinator.onSkipToNextDelegate = value
            }

        internal val headsetButtonController = HeadsetMediaButtonController(
            scheduleDelayed = { delayMs, action ->
                val handler = mainHandler
                if (handler != null) {
                    pendingHeadsetRunnable?.let { handler.removeCallbacks(it) }
                    val r = Runnable { action() }
                    pendingHeadsetRunnable = r
                    handler.postDelayed(r, delayMs)
                } else {
                    action()
                }
            },
            cancelScheduled = {
                val handler = mainHandler
                pendingHeadsetRunnable?.let { r -> handler?.removeCallbacks(r) }
                pendingHeadsetRunnable = null
            },
            onTogglePlay = {
                val ctx = activeServiceInstance?.applicationContext ?: fallbackAppContext
                if (ctx != null) {
                    PlaybackCoordinator.handleTogglePlay(ctx, activeServiceInstance?.player)
                } else {
                    PlaybackCoordinator.onTogglePlayDelegate?.invoke()
                }
            },
            onSkipToNext = {
                val ctx = activeServiceInstance?.applicationContext ?: fallbackAppContext
                if (ctx != null) {
                    PlaybackCoordinator.handleSkipToNext(ctx, activeServiceInstance?.player)
                } else {
                    PlaybackCoordinator.onSkipToNextDelegate?.invoke()
                }
            },
            onSkipToPrevious = {
                val ctx = activeServiceInstance?.applicationContext ?: fallbackAppContext
                if (ctx != null) {
                    PlaybackCoordinator.handleSkipToPrevious(ctx, activeServiceInstance?.player)
                } else {
                    PlaybackCoordinator.onSkipToPreviousDelegate?.invoke()
                }
            },
            onExplicitPlay = {
                val ctx = activeServiceInstance?.applicationContext ?: fallbackAppContext
                if (ctx != null) {
                    PlaybackCoordinator.handleExplicitPlay(ctx, activeServiceInstance?.player)
                } else {
                    PlaybackCoordinator.onResumePlayDelegate?.invoke()
                }
            },
            onExplicitPause = {
                val ctx = activeServiceInstance?.applicationContext ?: fallbackAppContext
                if (ctx != null) {
                    PlaybackCoordinator.handleExplicitPause(ctx, activeServiceInstance?.player)
                } else {
                    activeServiceInstance?.player?.pause()
                }
            }
        )

        internal fun isWiredHeadsetDeviceType(type: Int): Boolean = when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> true
            else -> false
        }

        internal fun isBluetoothAudioDeviceType(type: Int): Boolean = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> true
            else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type == AudioDeviceInfo.TYPE_HEARING_AID
        }

        internal fun resolveActiveBluetoothAudioOutput(am: AudioManager, fallbackConnected: Boolean): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val routed = runCatching {
                    val attrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    am.getAudioDevicesForAttributes(attrs)
                }.getOrNull()
                if (!routed.isNullOrEmpty()) {
                    return routed.any { isBluetoothAudioDeviceType(it.type) }
                }
            }
            return fallbackConnected
        }

        /**
         * 实时刷新并回补蓝牙音频输出路由连接状态（解决冷启动 ViewModel 先于/晚于 Service 初始化、
         * 或系统媒体输出切换器在不中断蓝牙连接的情况下切回扬声器导致的状态滞后）。
         */
        fun refreshBluetoothConnectionSnapshot(context: Context): Boolean {
            val am = runCatching {
                context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            }.getOrNull() ?: return isBluetoothAudioOutputConnected
            val connectedByEnumeration = runCatching {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .any { it.isSink && isBluetoothAudioDeviceType(it.type) }
            }.getOrDefault(isBluetoothAudioOutputConnected)
            val activeBt = resolveActiveBluetoothAudioOutput(am, connectedByEnumeration)
            if (isBluetoothAudioOutputConnected != activeBt) {
                isBluetoothAudioOutputConnected = activeBt
                onBluetoothConnectionChanged?.invoke(activeBt)
                SystemLyricBridge.refreshBackgroundEffectiveOffset(context.applicationContext)
            }
            return activeBt
        }

        internal fun isCarBusAudioDeviceType(type: Int): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_BUS
        }

        fun updateHandleAudioBecomingNoisy(enabled: Boolean) {
            val effectiveEnabled = enabled && AppPermissionManager.isPermissionEnabled(AppPermissionType.AUDIO_HARDWARE)
            val svc = activeServiceInstance ?: return
            runCatching {
                val mainLooper = Looper.getMainLooper()
                if (mainLooper == null || Looper.myLooper() == mainLooper) {
                    svc.player?.setHandleAudioBecomingNoisy(effectiveEnabled)
                } else {
                    mainHandler?.post {
                        svc.player?.setHandleAudioBecomingNoisy(effectiveEnabled)
                    }
                }
            }
        }

        fun updateAudioFocusDucking(enabled: Boolean) {
            val svc = activeServiceInstance ?: return
            runCatching {
                val mainLooper = Looper.getMainLooper()
                if (mainLooper == null || Looper.myLooper() == mainLooper) {
                    val p = svc.player ?: return
                    p.setAudioAttributes(p.audioAttributes, enabled)
                } else {
                    mainHandler?.post {
                        val p = svc.player ?: return@post
                        p.setAudioAttributes(p.audioAttributes, enabled)
                    }
                }
            }
        }

        /**
         * 更新三档音频焦点与混音闪避模式
         */
        fun updateAudioFocusMode(mode: AudioFocusMode) {
            currentAudioFocusMode = mode
            if (mode != AudioFocusMode.DUCK) {
                audioFocusDuckGain = 1.0f
            }
            val svc = activeServiceInstance ?: return
            runCatching {
                val mainLooper = Looper.getMainLooper()
                val applyBlock = {
                    svc.applyAudioFocusModeInternal(mode)
                }
                if (mainLooper == null || Looper.myLooper() == mainLooper) {
                    applyBlock()
                } else {
                    mainHandler?.post(applyBlock)
                }
            }
        }

        /**
         * 设置是否允许与其他应用同时播放（兼容布尔开关与三档模式联动）
         */
        fun updateAllowPlayWithOtherApps(allowMix: Boolean) {
            val targetMode = if (allowMix) {
                if (currentAudioFocusMode == AudioFocusMode.EXCLUSIVE) AudioFocusMode.MIX else currentAudioFocusMode
            } else {
                AudioFocusMode.EXCLUSIVE
            }
            updateAudioFocusMode(targetMode)
        }

        /**
         * 设置是否启用有线耳机线控多击切歌（双击暂停键下一首 / 三击暂停键上一首）
         */
        fun updateWiredHeadsetControlEnabled(enabled: Boolean) {
            headsetButtonController.multiClickSkipEnabled = enabled
        }

        /**
         * 设置是否交换有线耳机双击与三击的切歌方向
         */
        fun updateSwapHeadsetDoubleTripleClick(swapped: Boolean) {
            headsetButtonController.swapDoubleTripleClick = swapped
        }

        /**
         * 设置有线耳机多击判定灵敏度
         */
        fun updateHeadsetClickSensitivity(sensitivity: HeadsetClickSensitivity) {
            headsetButtonController.multiClickTimeoutMs = sensitivity.timeoutMs
        }

        /**
         * 车载模式方向盘媒体键零延迟响应开关：
         * 开启后跳过有线耳机单键 350ms 双击等待窗口，实现方向盘播放/暂停与上下曲物理按键 0ms 即按即响。
         */
        fun updateSteeringWheelZeroLatency(enabled: Boolean) {
            headsetButtonController.bypassMultiClickDelay = enabled
        }

        /**
         * 通知后台服务歌词数据、偏移量或车载模式已更新，立即刷新蓝牙 AVRCP 呼吸歌词调度器
         */
        fun notifyCarAvrcpLyricDataUpdated() {
            val svc = activeServiceInstance ?: return
            runCatching {
                val mainLooper = Looper.getMainLooper()
                if (mainLooper == null || Looper.myLooper() == mainLooper) {
                    svc.refreshCarAvrcpLyricTickerAndMetadata(forceImmediatePush = true)
                } else {
                    mainHandler?.post {
                        svc.refreshCarAvrcpLyricTickerAndMetadata(forceImmediatePush = true)
                    }
                }
            }
        }

        fun refreshWidgetLyricTickerIfActive() {
            val svc = activeServiceInstance ?: return
            runCatching {
                val mainLooper = Looper.getMainLooper()
                if (mainLooper == null || Looper.myLooper() == mainLooper) {
                    svc.refreshWidgetLyricTicker()
                } else {
                    mainHandler?.post { svc.refreshWidgetLyricTicker() }
                }
            }
        }

        fun updateHeadsetAutoPlayConfig(
            enabled: Boolean,
            wiredEnabled: Boolean = autoPlayOnWiredConnectEnabled,
            bluetoothEnabled: Boolean = autoPlayOnBluetoothConnectEnabled
        ) {
            autoPlayOnHeadsetConnectEnabled = enabled
            autoPlayOnWiredConnectEnabled = wiredEnabled
            autoPlayOnBluetoothConnectEnabled = bluetoothEnabled
        }

        fun updateKeepPlayingOnTaskRemoved(enabled: Boolean) {
            keepPlayingOnTaskRemovedEnabled = enabled
        }

        fun handleHeadsetKeyEvent(context: Context?, event: KeyEvent?): Boolean {
            if (event == null) return false
            if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.AUDIO_HARDWARE)) return false
            if (context != null) {
                fallbackAppContext = context.applicationContext ?: context
            }
            return headsetButtonController.handleKeyEvent(
                keyCode = event.keyCode,
                action = event.action,
                repeatCount = event.repeatCount,
                downTimeMs = event.downTime
            )
        }

        internal fun extractMediaKeyEvent(intent: Intent?): KeyEvent? {
            if (intent == null) return null
            return runCatching {
                if (intent.action != Intent.ACTION_MEDIA_BUTTON) return@runCatching null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
            }.getOrNull()
        }

        internal fun toggleActivePlayerPlayPause(): Boolean {
            val svc = activeServiceInstance ?: return false
            return PlaybackCoordinator.handleTogglePlay(svc.applicationContext, svc.player)
        }
    }

    private fun applyAudioFocusModeInternal(mode: AudioFocusMode) {
        val p = player ?: return
        when (mode) {
            AudioFocusMode.EXCLUSIVE -> {
                abandonDuckAudioFocusRequest()
                audioFocusDuckGain = 1.0f
                p.setAudioAttributes(p.audioAttributes, true)
            }
            AudioFocusMode.DUCK -> {
                p.setAudioAttributes(p.audioAttributes, false)
                if (p.isPlaying || p.playWhenReady) {
                    requestDuckAudioFocus()
                }
            }
            AudioFocusMode.MIX -> {
                abandonDuckAudioFocusRequest()
                audioFocusDuckGain = 1.0f
                p.setAudioAttributes(p.audioAttributes, false)
            }
        }
    }

    private fun requestDuckAudioFocus() {
        val am = audioManager ?: (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.also { audioManager = it } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = duckFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(duckAudioFocusChangeListener, mainHandler ?: Handler(Looper.getMainLooper()))
                .build()
                .also { duckFocusRequest = it }
            runCatching { am.requestAudioFocus(req) }
                .onSuccess { res ->
                    if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        hasActiveDuckFocus = true
                    }
                }
        }
    }

    private fun abandonDuckAudioFocusRequest() {
        duckReleaseHoldRunnable?.let { mainHandler?.removeCallbacks(it) }
        duckReleaseHoldRunnable = null
        hasActiveDuckFocus = false
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            duckFocusRequest?.let { req ->
                runCatching { am.abandonAudioFocusRequest(req) }
            }
        }
    }

    private fun setupAudioDeviceMonitor() {
        val am = (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.also { audioManager = it } ?: return
        // 初始化当前已连接设备快照
        runCatching {
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            connectedWiredIds.clear()
            connectedBluetoothIds.clear()
            connectedCarBusIds.clear()
            for (dev in outputs) {
                if (!dev.isSink) continue
                if (isWiredHeadsetDeviceType(dev.type)) connectedWiredIds.add(dev.id)
                if (isBluetoothAudioDeviceType(dev.type)) connectedBluetoothIds.add(dev.id)
                if (isCarBusAudioDeviceType(dev.type)) connectedCarBusIds.add(dev.id)
            }
            val btConnected = resolveActiveBluetoothAudioOutput(am, connectedBluetoothIds.isNotEmpty())
            isBluetoothAudioOutputConnected = btConnected
            onBluetoothConnectionChanged?.invoke(btConnected)
            SystemLyricBridge.refreshBackgroundEffectiveOffset(applicationContext)
        }

        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                if (addedDevices == null) return
                var newlyAddedWired = false
                var newlyAddedBluetooth = false
                var newlyAddedCarBus = false
                for (dev in addedDevices) {
                    if (!dev.isSink) continue
                    if (isWiredHeadsetDeviceType(dev.type)) {
                        if (connectedWiredIds.add(dev.id) && initialDeviceEnumerationDone) {
                            newlyAddedWired = true
                        }
                    }
                    if (isBluetoothAudioDeviceType(dev.type)) {
                        if (connectedBluetoothIds.add(dev.id) && initialDeviceEnumerationDone) {
                            newlyAddedBluetooth = true
                        }
                    }
                    if (isCarBusAudioDeviceType(dev.type)) {
                        if (connectedCarBusIds.add(dev.id) && initialDeviceEnumerationDone) {
                            newlyAddedCarBus = true
                        }
                    }
                }
                initialDeviceEnumerationDone = true
                val btConnectedNow = resolveActiveBluetoothAudioOutput(am, connectedBluetoothIds.isNotEmpty())
                if (isBluetoothAudioOutputConnected != btConnectedNow) {
                    isBluetoothAudioOutputConnected = btConnectedNow
                    onBluetoothConnectionChanged?.invoke(btConnectedNow)
                    SystemLyricBridge.refreshBackgroundEffectiveOffset(applicationContext)
                }

                val newlyAddedCarSink = newlyAddedBluetooth || newlyAddedCarBus
                CarSessionManager.reevaluateActiveConnections(
                    context = applicationContext,
                    newlyAddedBluetooth = newlyAddedCarSink,
                    isInitialBoot = false
                )

                // 若新增蓝牙/车机总线设备已归属认证车机白名单，严格交由车载专属自动续播开关 (`autoPlayOnCarConnect`) 独立控制
                val handledByCarPolicy = newlyAddedCarSink &&
                    CarSessionManager.state.value.activeConnectionSource == CarConnectionSource.CONFIRMED_BLUETOOTH

                if (!handledByCarPolicy &&
                    autoPlayOnHeadsetConnectEnabled &&
                    AppPermissionManager.isPermissionEnabled(AppPermissionType.AUDIO_HARDWARE)
                ) {
                    val shouldTriggerWired = newlyAddedWired && autoPlayOnWiredConnectEnabled
                    val shouldTriggerBt = newlyAddedBluetooth && autoPlayOnBluetoothConnectEnabled
                    if (shouldTriggerWired || shouldTriggerBt) {
                        PlaybackCoordinator.handleResumeOnHeadsetConnected(applicationContext, player)
                    }
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                if (removedDevices == null) return
                for (dev in removedDevices) {
                    connectedWiredIds.remove(dev.id)
                    connectedBluetoothIds.remove(dev.id)
                    connectedCarBusIds.remove(dev.id)
                }
                val btConnectedNow = resolveActiveBluetoothAudioOutput(am, connectedBluetoothIds.isNotEmpty())
                if (isBluetoothAudioOutputConnected != btConnectedNow) {
                    isBluetoothAudioOutputConnected = btConnectedNow
                    onBluetoothConnectionChanged?.invoke(btConnectedNow)
                    SystemLyricBridge.refreshBackgroundEffectiveOffset(applicationContext)
                }
                CarSessionManager.reevaluateActiveConnections(
                    context = applicationContext,
                    newlyAddedBluetooth = false,
                    isInitialBoot = false
                )
            }
        }
        audioDeviceCallback = callback
        runCatching {
            am.registerAudioDeviceCallback(callback, mainHandler)
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        fallbackAppContext = applicationContext
        com.concept.music.data.vip.VipManager.initialize(applicationContext)
        val isStandaloneCar = com.concept.music.BuildConfig.IS_CAR_FLAVOR
        if (isStandaloneCar) {
            // 车机冷启动后台服务场景（语音点歌/点火续播）先于 Activity 启动时，确保 64-bit 声学与导航闪避立刻就绪
            runCatching { CarSessionManager.init(applicationContext) }
        }

        val prefs = applicationContext.getSharedPreferences("concept_player_prefs", Context.MODE_PRIVATE)
        val pauseOnHeadsetDisconnect = prefs.getBoolean("pause_on_headset_disconnect", true)
        val allowPlayWithOtherApps = prefs.getBoolean("allow_play_with_other_apps", false)
        val savedFocusModeName = prefs.getString("audio_focus_mode", null)
        val initialFocusMode = if (isStandaloneCar && CarSessionManager.state.value.smartNavDuckingEnabled) {
            AudioFocusMode.DUCK
        } else if (savedFocusModeName != null) {
            runCatching { AudioFocusMode.valueOf(savedFocusModeName) }.getOrDefault(
                if (allowPlayWithOtherApps) AudioFocusMode.MIX else AudioFocusMode.EXCLUSIVE
            )
        } else {
            if (allowPlayWithOtherApps) AudioFocusMode.MIX else AudioFocusMode.EXCLUSIVE
        }
        currentAudioFocusMode = initialFocusMode

        val wiredHeadsetControlEnabled = prefs.getBoolean("wired_headset_control_enabled", true)
        val swapHeadsetButtons = prefs.getBoolean("swap_headset_double_triple_click", false)
        val savedSensitivityName = prefs.getString("headset_click_sensitivity", HeadsetClickSensitivity.STANDARD.name)
            ?: HeadsetClickSensitivity.STANDARD.name
        val sensitivity = runCatching { HeadsetClickSensitivity.valueOf(savedSensitivityName) }
            .getOrDefault(HeadsetClickSensitivity.STANDARD)

        headsetButtonController.multiClickSkipEnabled = wiredHeadsetControlEnabled
        headsetButtonController.swapDoubleTripleClick = swapHeadsetButtons
        headsetButtonController.multiClickTimeoutMs = sensitivity.timeoutMs

        autoPlayOnHeadsetConnectEnabled = prefs.getBoolean("auto_play_on_headset_connect", false)
        autoPlayOnWiredConnectEnabled = prefs.getBoolean("auto_play_on_wired_connect", true)
        autoPlayOnBluetoothConnectEnabled = prefs.getBoolean("auto_play_on_bluetooth_connect", true)
        keepPlayingOnTaskRemovedEnabled = prefs.getBoolean("keep_playing_on_task_removed", true)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // 优化 AudioTrack 硬件 PCM 缓冲窗口 (120ms~360ms)，确保切换蝰蛇音效预设与拖动调音台滑杆时零延迟即时生效
        val bufferSizeProvider = DefaultAudioTrackBufferSizeProvider.Builder()
            .setMinPcmBufferDurationUs(120_000)
            .setMaxPcmBufferDurationUs(360_000)
            .setPcmBufferMultiplicationFactor(2)
            .build()

        val masteringProcessor = ConceptMasteringAudioProcessor()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParameters: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioTrackBufferSizeProvider(bufferSizeProvider)
                    .setAudioProcessors(arrayOf(masteringProcessor))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(false)
                    .build()
            }
        }.setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ if (isStandaloneCar) 45_000 else 30_000,
                /* maxBufferMs = */ if (isStandaloneCar) 180_000 else 120_000,
                /* bufferForPlaybackMs = */ if (isStandaloneCar) 400 else 280,
                /* bufferForPlaybackAfterRebufferMs = */ if (isStandaloneCar) 1_200 else 800
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(AudioCacheManager.getDataSourceFactory(this))

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, initialFocusMode == AudioFocusMode.EXCLUSIVE)
            .setHandleAudioBecomingNoisy(
                !isStandaloneCar && pauseOnHeadsetDisconnect && AppPermissionManager.isPermissionEnabled(AppPermissionType.AUDIO_HARDWARE)
            )
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .apply {
                if (isStandaloneCar) {
                    setWakeMode(C.WAKE_MODE_NETWORK)
                }
            }
            .build()
        player = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isAppPlaybackActive = isPlaying
                if (isPlaying) {
                    carNetworkRetryCount = 0
                    carNetworkRetryRunnable?.let { mainHandler?.removeCallbacks(it) }
                    carNetworkRetryRunnable = null
                }
                val currentItem = exoPlayer.currentMediaItem
                val playerDur = exoPlayer.duration.takeIf { it > 0L } ?: 0L
                if (!com.concept.music.BuildConfig.IS_CAR_FLAVOR) {
                    ListenStatsCoordinator.onPlayingStateChanged(
                        isPlaying = isPlaying,
                        mediaId = currentItem?.mediaId,
                        songFallback = buildFallbackSongFromMediaItem(currentItem, playerDur),
                        durationMs = playerDur,
                        speed = exoPlayer.playbackParameters.speed
                    )
                }
                if (isPlaying) {
                    if (currentAudioFocusMode == AudioFocusMode.DUCK) {
                        // 仅当当前未持有 DUCK 焦点时才申请，避免缓冲/切歌瞬间强抢导航语音焦点
                        if (!hasActiveDuckFocus) {
                            audioFocusDuckGain = 1.0f
                            requestDuckAudioFocus()
                        }
                    } else {
                        audioFocusDuckGain = 1.0f
                    }
                    if (!com.concept.music.BuildConfig.IS_CAR_FLAVOR) {
                        startBackgroundDesktopLyricProgressTicker()
                        scheduleListenStatsTick()
                    }
                } else {
                    // 仅在真正暂停/停止（!playWhenReady）时弃焦；缓冲与切歌过渡期间保持现有焦点与闪避状态
                    if (currentAudioFocusMode == AudioFocusMode.DUCK && !exoPlayer.playWhenReady) {
                        abandonDuckAudioFocusRequest()
                    }
                    stopBackgroundDesktopLyricProgressTicker()
                    stopListenStatsTick()
                    if (!com.concept.music.BuildConfig.IS_CAR_FLAVOR) {
                        SystemLyricBridge.syncProgressFromService(exoPlayer.currentPosition, false)
                    }
                }
                refreshCarAvrcpLyricTickerAndMetadata(forceImmediatePush = false)
                refreshMediaSessionCustomLayoutInternal()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (!com.concept.music.BuildConfig.IS_CAR_FLAVOR) return
                // 车机休眠唤醒或穿越隧道/地库后 4G 模块尚未就绪时执行指数退避静默重连（1s -> 2.5s -> 5s）
                if (carNetworkRetryCount < 3 && exoPlayer.currentMediaItem != null) {
                    val delayMs = when (carNetworkRetryCount) {
                        0 -> 1_000L
                        1 -> 2_500L
                        else -> 5_000L
                    }
                    carNetworkRetryCount += 1
                    carNetworkRetryRunnable?.let { mainHandler?.removeCallbacks(it) }
                    val retryRunnable = Runnable {
                        val activeP = player ?: return@Runnable
                        if (activeP === exoPlayer && activeP.currentMediaItem != null) {
                            runCatching {
                                activeP.prepare()
                                activeP.playWhenReady = true
                            }
                        }
                    }
                    carNetworkRetryRunnable = retryRunnable
                    mainHandler?.postDelayed(retryRunnable, delayMs)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    refreshCarAvrcpLyricTickerAndMetadata(forceImmediatePush = true)
                }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // 托管的「播完当前歌曲再停」在单曲循环或自动切歌时直接拦截停播，并通知 ViewModel 不得续播下一首
                var stoppedBySleep = false
                if ((reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) &&
                    consumePendingTrackEndStop(markViewModelAck = PlaybackCoordinator.onSkipToNextDelegate != null)
                ) {
                    stoppedBySleep = true
                    exoPlayer.pause()
                    exoPlayer.volume = 1f
                }
                val playerDur = exoPlayer.duration.takeIf { it > 0L } ?: 0L
                val isEffectivelyPlaying = exoPlayer.isPlaying && !stoppedBySleep
                ListenStatsCoordinator.onMediaItemTransition(
                    mediaId = mediaItem?.mediaId,
                    songFallback = buildFallbackSongFromMediaItem(mediaItem, playerDur),
                    reason = reason,
                    durationMs = playerDur,
                    speed = exoPlayer.playbackParameters.speed,
                    isPlaying = isEffectivelyPlaying
                )
                if (isEffectivelyPlaying) {
                    scheduleListenStatsTick()
                } else {
                    stopListenStatsTick()
                }
                refreshCarAvrcpLyricTickerAndMetadata(forceImmediatePush = true)
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                ListenStatsCoordinator.onPlaybackSpeedChanged(playbackParameters.speed)
                if (exoPlayer.isPlaying) {
                    scheduleListenStatsTick()
                }
                refreshCarAvrcpLyricTickerAndMetadata(forceImmediatePush = false)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                bgAutoAdvanceRunnable?.let { mainHandler?.removeCallbacks(it) }
                bgAutoAdvanceRunnable = null
                if (playbackState == Player.STATE_READY) {
                    val playerDur = exoPlayer.duration.takeIf { it > 0L } ?: 0L
                    if (playerDur > 0L) {
                        ListenStatsCoordinator.onTrackDurationUpdated(
                            mediaId = exoPlayer.currentMediaItem?.mediaId,
                            durationMs = playerDur
                        )
                    }
                    if (exoPlayer.isPlaying) {
                        scheduleListenStatsTick()
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    stopListenStatsTick()
                    ListenStatsCoordinator.onPlaybackEnded()
                    if (consumePendingTrackEndStop(markViewModelAck = PlaybackCoordinator.onSkipToNextDelegate != null)) {
                        exoPlayer.pause()
                        exoPlayer.volume = 1f
                        return
                    }
                    // 当划卡保活或系统回收 Activity 后 ViewModel 已脱离，由 Service 兜底自动续播下一首
                    if (PlaybackCoordinator.onSkipToNextDelegate == null && exoPlayer.playWhenReady) {
                        val r = Runnable {
                            val activeP = player ?: return@Runnable
                            if (activeP === exoPlayer &&
                                activeP.playbackState == Player.STATE_ENDED &&
                                PlaybackCoordinator.onSkipToNextDelegate == null
                            ) {
                                PlaybackCoordinator.handleSkipToNext(applicationContext, activeP)
                            }
                        }
                        bgAutoAdvanceRunnable = r
                        mainHandler?.postDelayed(r, 380L)
                    }
                }
            }
        })

        AudioEffectManager.init(applicationContext)
        AudioEffectManager.onAuxEffectChanged = { effectId, sendLevel ->
            runCatching {
                player?.setAuxEffectInfo(AuxEffectInfo(effectId, sendLevel))
            }
        }
        AudioEffectManager.attachSession(exoPlayer.audioSessionId)
        exoPlayer.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioSessionIdChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                audioSessionId: Int
            ) {
                AudioEffectManager.attachSession(audioSessionId)
            }
        })

        CarSessionManager.onTriggerCarAutoPlayCallback = { ctx ->
            if (AppPermissionManager.isPermissionEnabled(AppPermissionType.AUDIO_HARDWARE)) {
                PlaybackCoordinator.handleResumeOnHeadsetConnected(ctx, player)
            }
        }
        CarSessionManager.onCarModeChangedCallback = {
            SystemLyricBridge.refreshBackgroundEffectiveOffset(applicationContext)
            refreshCarAvrcpLyricTickerAndMetadata(forceImmediatePush = true)
        }
        setupAudioDeviceMonitor()
        CarSessionManager.init(applicationContext)

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val forwardingPlayer = CarLyricForwardingPlayer(exoPlayer).also {
            carForwardingPlayer = it
        }

        runCatching {
            @Suppress("DEPRECATION")
            val stableNotificationProvider = object : DefaultMediaNotificationProvider(applicationContext) {
                override fun getNotificationContentTitle(metadata: MediaMetadata): CharSequence? {
                    if (isCarBluetoothLyricActive()) {
                        val rawItemMeta = player?.currentMediaItem?.mediaMetadata
                        val stableTitle = metadata.displayTitle?.takeIf { it.isNotBlank() }
                            ?: rawItemMeta?.displayTitle?.takeIf { it.isNotBlank() }
                            ?: rawItemMeta?.title?.takeIf { it.isNotBlank() }
                            ?: SystemLyricBridge.currentSongSnapshot?.title?.takeIf { it.isNotBlank() }
                        if (stableTitle != null) return stableTitle
                    }
                    return super.getNotificationContentTitle(metadata)
                }

                override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? {
                    if (isCarBluetoothLyricActive()) {
                        val rawItemMeta = player?.currentMediaItem?.mediaMetadata
                        val stableArtist = metadata.subtitle?.takeIf { it.isNotBlank() }
                            ?: rawItemMeta?.subtitle?.takeIf { it.isNotBlank() }
                            ?: rawItemMeta?.artist?.takeIf { it.isNotBlank() }
                            ?: SystemLyricBridge.currentSongSnapshot?.artist?.takeIf { it.isNotBlank() }
                        if (stableArtist != null) return stableArtist
                    }
                    return super.getNotificationContentText(metadata)
                }
            }.apply {
                setSmallIcon(R.drawable.ic_launcher_concept)
            }
            setMediaNotificationProvider(stableNotificationProvider)
        }

        SystemLyricBridge.init(applicationContext)
        SystemLyricBridge.onDesktopLyricNotificationStateChanged = {
            refreshMediaSessionCustomLayoutInternal()
            if (SystemLyricBridge.state.value.floatingCapsuleEnabled && exoPlayer.isPlaying) {
                startBackgroundDesktopLyricProgressTicker()
            }
        }

        val sessionCallback = object : MediaLibrarySession.Callback {
            @OptIn(UnstableApi::class)
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val isSelfOrSystem = isVerifiedSystemOrSelfController(controller)
                val isCarHost = isVerifiedCarHostController(controller)
                if (isCarHost && verifiedCarControllers.add(controller)) {
                    CarSessionManager.onProjectionControllerConnected(applicationContext, controller.packageName)
                }
                val customButtons = buildNotificationCustomLayout()
                return when {
                    isSelfOrSystem -> {
                        val availableSessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                            .buildUpon()
                            .add(SESSION_COMMAND_TOGGLE_DESKTOP_LYRIC)
                            .add(SESSION_COMMAND_TOGGLE_DESKTOP_LYRIC_LOCK)
                            .build()
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailableSessionCommands(availableSessionCommands)
                            .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                            .setCustomLayout(customButtons)
                            .build()
                    }
                    isCarHost -> {
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
                            .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                            .build()
                    }
                    else -> {
                        val restrictedPlayerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                            .buildUpon()
                            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                            .remove(Player.COMMAND_SET_MEDIA_ITEM)
                            .build()
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS)
                            .setAvailablePlayerCommands(restrictedPlayerCommands)
                            .build()
                    }
                }
            }

            override fun onDisconnected(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ) {
                if (verifiedCarControllers.remove(controller)) {
                    CarSessionManager.onProjectionControllerDisconnected(applicationContext, controller.packageName)
                }
                super.onDisconnected(session, controller)
            }

            override fun onPostConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ) {
                super.onPostConnect(session, controller)
                if (!isVerifiedSystemOrSelfController(controller)) return
                val customButtons = buildNotificationCustomLayout()
                runCatching {
                    session.setCustomLayout(controller, customButtons)
                    session.setCustomLayout(customButtons)
                }
            }

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                if (!isVerifiedSystemOrSelfController(browser) && !isVerifiedCarHostController(browser)) {
                    return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
                }
                return Futures.immediateFuture(
                    LibraryResult.ofItem(CarMediaLibraryHelper.buildRootMediaItem(), params)
                )
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                if (!isVerifiedSystemOrSelfController(browser) && !isVerifiedCarHostController(browser)) {
                    return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
                }
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                libraryScope.launch {
                    val allItems = runCatching {
                        CarMediaLibraryHelper.loadChildrenForParent(applicationContext, parentId)
                    }.getOrDefault(emptyList())
                    val paged = if (page >= 0 && pageSize > 0 && pageSize < Int.MAX_VALUE / 2) {
                        val from = (page.toLong() * pageSize.toLong()).coerceAtMost(allItems.size.toLong()).toInt()
                        val to = ((page.toLong() + 1L) * pageSize.toLong()).coerceAtMost(allItems.size.toLong()).toInt()
                        if (from < to) allItems.subList(from, to) else emptyList()
                    } else {
                        allItems
                    }
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(paged), params))
                }
                return future
            }

            @OptIn(UnstableApi::class)
            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val safeIdx = if (startIndex in mediaItems.indices) startIndex else 0
                val targetRawItem = mediaItems.getOrNull(safeIdx)
                val rawMediaId = targetRawItem?.mediaId.orEmpty()
                if (!rawMediaId.startsWith(CarMediaLibraryHelper.PREFIX_TRACK)) {
                    return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
                }
                if (!isVerifiedSystemOrSelfController(controller) && !isVerifiedCarHostController(controller)) {
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
                    )
                }
                val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                libraryScope.launch {
                    val lookup = CarMediaLibraryHelper.lookupSongAndContextQueue(rawMediaId)
                    val resolvedPair = if (lookup != null) {
                        CarMediaLibraryHelper.resolvePlayableItemForCarSelection(applicationContext, lookup.first)
                    } else null
                    if (lookup != null && resolvedPair != null) {
                        val (resolvedSong, playableMediaItem) = resolvedPair
                        val parentKey = CarMediaLibraryHelper.parseCarTrackMediaId(rawMediaId)?.first.orEmpty()
                        val isFm = parentKey == CarMediaLibraryHelper.TAB_PERSONAL_FM
                        PlaybackCoordinator.adoptCarSelectedSongInBackground(
                            context = applicationContext,
                            resolvedSong = resolvedSong,
                            queue = lookup.second,
                            isPersonalFm = isFm
                        )
                        future.set(
                            MediaSession.MediaItemsWithStartPosition(
                                listOf(playableMediaItem),
                                0,
                                startPositionMs.coerceAtLeast(0L)
                            )
                        )
                    } else {
                        future.set(
                            MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
                        )
                    }
                }
                return future
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                val hasCarBrowseItem = mediaItems.any { it.mediaId.startsWith(CarMediaLibraryHelper.PREFIX_TRACK) }
                if (!hasCarBrowseItem) {
                    return super.onAddMediaItems(mediaSession, controller, mediaItems)
                }
                if (!isVerifiedSystemOrSelfController(controller) && !isVerifiedCarHostController(controller)) {
                    return Futures.immediateFuture(mutableListOf())
                }
                val future = SettableFuture.create<MutableList<MediaItem>>()
                libraryScope.launch {
                    val out = mutableListOf<MediaItem>()
                    for (rawItem in mediaItems) {
                        if (rawItem.mediaId.startsWith(CarMediaLibraryHelper.PREFIX_TRACK)) {
                            val lookup = CarMediaLibraryHelper.lookupSongAndContextQueue(rawItem.mediaId)
                            val resolved = lookup?.let {
                                CarMediaLibraryHelper.resolvePlayableItemForCarSelection(applicationContext, it.first)
                            }
                            if (lookup != null && resolved != null) {
                                val parentKey = CarMediaLibraryHelper.parseCarTrackMediaId(rawItem.mediaId)?.first.orEmpty()
                                PlaybackCoordinator.adoptCarSelectedSongInBackground(
                                    context = applicationContext,
                                    resolvedSong = resolved.first,
                                    queue = lookup.second,
                                    isPersonalFm = parentKey == CarMediaLibraryHelper.TAB_PERSONAL_FM
                                )
                                out.add(resolved.second)
                            }
                        } else {
                            out.add(rawItem)
                        }
                    }
                    future.set(out)
                }
                return future
            }

            override fun onSearch(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<Void>> {
                if (!isVerifiedSystemOrSelfController(browser) && !isVerifiedCarHostController(browser)) {
                    return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
                }
                val future = SettableFuture.create<LibraryResult<Void>>()
                libraryScope.launch {
                    val items = runCatching {
                        CarMediaLibraryHelper.searchVoiceTracks(applicationContext, query, params?.extras)
                    }.getOrDefault(emptyList())
                    runCatching {
                        session.notifySearchResultChanged(browser, query, items.size, params)
                    }
                    future.set(LibraryResult.ofVoid(params))
                }
                return future
            }

            override fun onGetSearchResult(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                if (!isVerifiedSystemOrSelfController(browser) && !isVerifiedCarHostController(browser)) {
                    return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
                }
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                libraryScope.launch {
                    val cached = CarMediaLibraryHelper.getCachedVoiceSearchResults(query)
                    val allItems = if (cached.isNotEmpty()) {
                        cached
                    } else {
                        runCatching {
                            CarMediaLibraryHelper.searchVoiceTracks(applicationContext, query, params?.extras)
                        }.getOrDefault(emptyList())
                    }
                    val paged = if (page >= 0 && pageSize > 0 && pageSize < Int.MAX_VALUE / 2) {
                        val from = (page.toLong() * pageSize.toLong()).coerceAtMost(allItems.size.toLong()).toInt()
                        val to = ((page.toLong() + 1L) * pageSize.toLong()).coerceAtMost(allItems.size.toLong()).toInt()
                        if (from < to) allItems.subList(from, to) else emptyList()
                    } else {
                        allItems
                    }
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(paged), params))
                }
                return future
            }

            @OptIn(UnstableApi::class)
            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                if (!isVerifiedSystemOrSelfController(controller) && !isVerifiedCarHostController(controller)) {
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
                    )
                }
                val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                libraryScope.launch {
                    val resumed = runCatching {
                        val appCtx = applicationContext
                        val p = appCtx.getSharedPreferences("concept_player_prefs", Context.MODE_PRIVATE)
                        val uid = runCatching { com.concept.music.data.vip.VipManager.accounts.state.value.currentId ?: "guest" }
                            .getOrDefault("guest")
                        val rawJson = p.getString("saved_queue_json_$uid", null)
                            ?: p.getString("saved_queue_json", null)
                        val queue = if (!rawJson.isNullOrBlank()) {
                            runCatching {
                                val type = object : com.google.gson.reflect.TypeToken<List<com.concept.music.data.model.Song>>() {}.type
                                com.concept.music.data.kugou.KugouSigner.gson
                                    .fromJson<List<com.concept.music.data.model.Song>>(rawJson, type)
                                    ?.map { it.sanitizeFromJson() }
                                    .orEmpty()
                            }.getOrDefault(emptyList())
                        } else emptyList()
                        val idx = p.getInt("saved_current_index_$uid", p.getInt("saved_current_index", 0))
                            .coerceIn(0, (queue.size - 1).coerceAtLeast(0))
                        val savedPos = p.getLong("saved_position_ms_$uid", p.getLong("saved_position_ms", 0L))
                            .coerceAtLeast(0L)
                        val targetSong = queue.getOrNull(idx)
                        if (targetSong != null) {
                            val resolved = CarMediaLibraryHelper.resolvePlayableItemForCarSelection(appCtx, targetSong)
                            if (resolved != null) {
                                PlaybackCoordinator.adoptCarSelectedSongInBackground(
                                    context = appCtx,
                                    resolvedSong = resolved.first,
                                    queue = queue,
                                    isPersonalFm = false
                                )
                                MediaSession.MediaItemsWithStartPosition(listOf(resolved.second), 0, savedPos)
                            } else null
                        } else null
                    }.getOrNull()
                    future.set(
                        resumed ?: MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
                    )
                }
                return future
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                if (!isVerifiedSystemOrSelfController(controller)) {
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
                }
                when (customCommand.customAction) {
                    ACTION_CUSTOM_TOGGLE_DESKTOP_LYRIC -> {
                        SystemLyricBridge.handleNotificationToggleDesktopLyric(applicationContext)
                        refreshMediaSessionCustomLayoutInternal()
                        if (SystemLyricBridge.state.value.floatingCapsuleEnabled && exoPlayer.isPlaying) {
                            startBackgroundDesktopLyricProgressTicker()
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    ACTION_CUSTOM_TOGGLE_DESKTOP_LYRIC_LOCK -> {
                        SystemLyricBridge.handleNotificationToggleLock(applicationContext)
                        refreshMediaSessionCustomLayoutInternal()
                        if (SystemLyricBridge.state.value.floatingCapsuleEnabled && exoPlayer.isPlaying) {
                            startBackgroundDesktopLyricProgressTicker()
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
                return super.onCustomCommand(session, controller, customCommand, args)
            }

            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent
            ): Boolean {
                if (!isVerifiedSystemOrSelfController(controllerInfo) && !isVerifiedCarHostController(controllerInfo)) {
                    return false
                }
                val keyEvent = extractMediaKeyEvent(intent)
                if (keyEvent != null) {
                    if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.AUDIO_HARDWARE)) {
                        // 关闭外设音频与耳机线控权限后，显式消费拦截事件，不再回落交由 Media3 默认响应
                        return true
                    }
                    if (handleHeadsetKeyEvent(applicationContext, keyEvent)) {
                        return true
                    }
                }
                return super.onMediaButtonEvent(session, controllerInfo, intent)
            }
        }

        val initialCustomButtons = buildNotificationCustomLayout()
        mediaSession = MediaLibrarySession.Builder(this, forwardingPlayer, sessionCallback)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCustomLayout(initialCustomButtons)
            .build()
            .also { session ->
                runCatching { session.setCustomLayout(initialCustomButtons) }
            }
    }

    @OptIn(UnstableApi::class)
    private inner class CarLyricForwardingPlayer(
        private val exoPlayer: ExoPlayer
    ) : ForwardingPlayer(exoPlayer) {

        private val sessionListeners = CopyOnWriteArraySet<Player.Listener>()
        private var lastPushedAvrTitle: String = ""
        private var lastPushedAvrArtist: String = ""
        private var lastPushElapsedMs: Long = 0L
        private var wasCarBluetoothLyricActive: Boolean = false

        private fun wrapListener(listener: Player.Listener): Player.Listener {
            return CarLyricForwardingListener(
                forwardingPlayer = this,
                delegate = listener,
                isCarBluetoothLyricActive = { isCarBluetoothLyricActive() },
                resolveEffectiveMetadata = { getMediaMetadata() },
                transformAvailableCommands = ::mergeCarNavigationCommands
            )
        }

        override fun addListener(listener: Player.Listener) {
            sessionListeners.add(listener)
            super.addListener(wrapListener(listener))
        }

        override fun removeListener(listener: Player.Listener) {
            sessionListeners.remove(listener)
            super.removeListener(wrapListener(listener))
        }

        override fun getAvailableCommands(): Player.Commands {
            return mergeCarNavigationCommands(super.getAvailableCommands())
        }

        override fun isCommandAvailable(command: Int): Boolean {
            return when (command) {
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> true
                else -> super.isCommandAvailable(command)
            }
        }

        override fun hasPreviousMediaItem(): Boolean = true

        override fun hasNextMediaItem(): Boolean = true

        override fun play() {
            if (exoPlayer.mediaItemCount == 0) {
                if (!PlaybackCoordinator.handleTogglePlay(applicationContext, exoPlayer)) {
                    super.play()
                }
            } else {
                super.play()
            }
        }

        override fun seekToPrevious() {
            if (!PlaybackCoordinator.handleSkipToPrevious(applicationContext, exoPlayer)) {
                super.seekToPrevious()
            }
        }

        override fun seekToPreviousMediaItem() {
            if (!PlaybackCoordinator.handleSkipToPrevious(applicationContext, exoPlayer)) {
                super.seekToPreviousMediaItem()
            }
        }

        override fun seekToNext() {
            if (!PlaybackCoordinator.handleSkipToNext(applicationContext, exoPlayer)) {
                super.seekToNext()
            }
        }

        override fun seekToNextMediaItem() {
            if (!PlaybackCoordinator.handleSkipToNext(applicationContext, exoPlayer)) {
                super.seekToNextMediaItem()
            }
        }

        override fun getMediaMetadata(): MediaMetadata {
            val base = super.getMediaMetadata()
            if (!isCarBluetoothLyricActive()) {
                return base
            }
            val snap = buildCurrentCarAvrcpSnapshot(base)
            return base.buildUpon()
                .setTitle(snap.avrTitle)
                .setArtist(snap.avrArtist)
                .setDisplayTitle(snap.notificationTitle)
                .setSubtitle(snap.notificationArtist)
                .build()
        }

        fun pushAvrcpMetadataIfChanged(force: Boolean = false): Long {
            val carLyricActive = isCarBluetoothLyricActive()
            if (!carLyricActive) {
                if (!wasCarBluetoothLyricActive) {
                    return 0L
                }
                wasCarBluetoothLyricActive = false
                lastPushedAvrTitle = ""
                lastPushedAvrArtist = ""
                lastPushElapsedMs = 0L
                val restoredMeta = super.getMediaMetadata()
                val events = Player.Events(
                    FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build()
                )
                for (listener in sessionListeners) {
                    runCatching {
                        listener.onMediaMetadataChanged(restoredMeta)
                        listener.onEvents(this, events)
                    }
                }
                return 0L
            }
            wasCarBluetoothLyricActive = true
            val updatedMeta = mediaMetadata
            val nextTitle = updatedMeta.title?.toString().orEmpty()
            val nextArtist = updatedMeta.artist?.toString().orEmpty()
            val contentChanged = nextTitle != lastPushedAvrTitle || nextArtist != lastPushedAvrArtist
            val nowElapsed = safeServiceElapsedRealtime()
            val elapsedSinceLast = if (lastPushElapsedMs > 0L) (nowElapsed - lastPushElapsedMs).coerceAtLeast(0L) else -1L
            val throttleAction = CarAvrcpLyricFormatter.computeAvrcpThrottleActionMs(
                force = force,
                contentChanged = contentChanged,
                elapsedSinceLastPushMs = elapsedSinceLast
            )
            if (throttleAction < 0L) {
                return 0L
            }
            if (throttleAction > 0L) {
                // 新唱词落入防抖窗口：返回剩余等待毫秒数，交由调度器定时补发，绝不静默丢弃当前句
                return throttleAction
            }
            lastPushedAvrTitle = nextTitle
            lastPushedAvrArtist = nextArtist
            lastPushElapsedMs = nowElapsed

            val events = Player.Events(
                FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build()
            )
            for (listener in sessionListeners) {
                runCatching {
                    listener.onMediaMetadataChanged(updatedMeta)
                    listener.onEvents(this, events)
                }
            }
            return 0L
        }
    }

    private fun isCarBluetoothLyricActive(): Boolean {
        val car = CarSessionManager.state.value
        return CarSessionManager.isPhoneCarFeatureSupported() &&
            car.isCarModeActive &&
            car.carBluetoothLyricEnabled &&
            AppPermissionManager.isPermissionEnabled(AppPermissionType.NOTIFICATION_MEDIA)
    }

    private fun resolveCarAdjustedLyricPositionMs(rawPosMs: Long): Long {
        val car = CarSessionManager.state.value
        val hwAllowed = AppPermissionManager.isPermissionEnabled(AppPermissionType.AUDIO_HARDWARE)
        // 直接实时合并基础手动偏移 + 权威车载偏移，彻底消除修改车机滑块瞬间的协程快照竞态
        val baseSongOffset = if (PlaybackCoordinator.onTogglePlayDelegate != null) {
            SystemLyricBridge.manualLyricOffsetMsSnapshot
        } else {
            runCatching {
                applicationContext.getSharedPreferences("concept_player_prefs", Context.MODE_PRIVATE)
                    .getLong("lyric_offset_ms", 0L)
            }.getOrDefault(0L)
        }
        val carOffset = if (hwAllowed) {
            car.carLyricOffsetMs.coerceIn(
                CarSessionManager.CAR_LYRIC_OFFSET_MIN_MS,
                CarSessionManager.CAR_LYRIC_OFFSET_MAX_MS
            )
        } else 0L
        val effectiveOffset = (baseSongOffset + carOffset).coerceIn(-10_000L, 10_000L)
        return (rawPosMs + effectiveOffset).coerceAtLeast(0L)
    }

    private fun buildCurrentCarAvrcpSnapshot(baseMeta: MediaMetadata): CarAvrcpMetadataSnapshot {
        val p = player
        val activeMediaId = p?.currentMediaItem?.mediaId.orEmpty()
        val bridgeSong = SystemLyricBridge.currentSongSnapshot
        val matchedBridgeSong = bridgeSong?.takeIf { activeMediaId.isBlank() || it.id == activeMediaId }
        val resolvedSong = matchedBridgeSong
            ?: buildFallbackSongFromMediaItem(p?.currentMediaItem, p?.duration?.takeIf { it > 0L } ?: 0L)
        val lyricData = if (matchedBridgeSong != null) {
            SystemLyricBridge.currentLyricSnapshot
        } else {
            com.concept.music.data.model.LyricData.Empty
        }
        val adjustedPos = resolveCarAdjustedLyricPositionMs(p?.currentPosition ?: 0L)
        val carState = CarSessionManager.state.value
        val fallbackTitle = baseMeta.displayTitle?.toString()?.takeIf { it.isNotBlank() }
            ?: baseMeta.title?.toString()?.takeIf { it.isNotBlank() }
            ?: "微风音乐"
        val fallbackArtist = baseMeta.subtitle?.toString()?.takeIf { it.isNotBlank() }
            ?: baseMeta.artist?.toString()?.takeIf { it.isNotBlank() }
            ?: "未知歌手"
        if (resolvedSong == null) {
            return CarAvrcpMetadataSnapshot(
                avrTitle = fallbackTitle,
                avrArtist = fallbackArtist,
                notificationTitle = fallbackTitle,
                notificationArtist = fallbackArtist
            )
        }
        return CarAvrcpLyricFormatter.formatMetadataSnapshot(
            song = resolvedSong,
            lyricData = lyricData,
            adjustedPositionMs = adjustedPos,
            enableCarLyric = true,
            interludeStyle = carState.carInterludeStyle
        )
    }

    private val carAvrcpLyricRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!p.isPlaying || !isCarBluetoothLyricActive()) return
            val retryThrottleDelayMs = carForwardingPlayer?.pushAvrcpMetadataIfChanged(force = false) ?: 0L
            val activeMediaId = p.currentMediaItem?.mediaId.orEmpty()
            val bridgeSong = SystemLyricBridge.currentSongSnapshot
            val lyricData = if (bridgeSong != null && (activeMediaId.isBlank() || bridgeSong.id == activeMediaId)) {
                SystemLyricBridge.currentLyricSnapshot
            } else {
                com.concept.music.data.model.LyricData.Empty
            }
            val adjustedPos = resolveCarAdjustedLyricPositionMs(p.currentPosition)
            val boundaryDelayMs = CarAvrcpLyricFormatter.computeNextWakeupDelayMs(
                lyricData = lyricData,
                adjustedPositionMs = adjustedPos,
                playbackSpeed = p.playbackParameters.speed
            )
            val delayMs = if (retryThrottleDelayMs > 0L) {
                minOf(retryThrottleDelayMs, boundaryDelayMs)
            } else {
                boundaryDelayMs
            }
            mainHandler?.postDelayed(this, delayMs)
        }
    }

    internal fun refreshCarAvrcpLyricTickerAndMetadata(forceImmediatePush: Boolean = false) {
        val h = mainHandler
        h?.removeCallbacks(carAvrcpLyricRunnable)
        refreshWidgetLyricTicker()
        val retryThrottleDelayMs = carForwardingPlayer?.pushAvrcpMetadataIfChanged(force = forceImmediatePush) ?: 0L
        val p = player ?: return
        if (p.isPlaying && isCarBluetoothLyricActive()) {
            val activeMediaId = p.currentMediaItem?.mediaId.orEmpty()
            val bridgeSong = SystemLyricBridge.currentSongSnapshot
            val lyricData = if (bridgeSong != null && (activeMediaId.isBlank() || bridgeSong.id == activeMediaId)) {
                SystemLyricBridge.currentLyricSnapshot
            } else {
                com.concept.music.data.model.LyricData.Empty
            }
            val adjustedPos = resolveCarAdjustedLyricPositionMs(p.currentPosition)
            val boundaryDelayMs = CarAvrcpLyricFormatter.computeNextWakeupDelayMs(
                lyricData = lyricData,
                adjustedPositionMs = adjustedPos,
                playbackSpeed = p.playbackParameters.speed
            )
            val delayMs = if (retryThrottleDelayMs > 0L) {
                minOf(retryThrottleDelayMs, boundaryDelayMs)
            } else {
                boundaryDelayMs
            }
            h?.postDelayed(carAvrcpLyricRunnable, delayMs)
        }
    }

    private fun stopCarAvrcpLyricTicker() {
        mainHandler?.removeCallbacks(carAvrcpLyricRunnable)
    }

    private fun isVerifiedCarHostController(controller: MediaSession.ControllerInfo): Boolean {
        return CarSessionManager.isPhoneCarFeatureSupported() &&
            CarMediaLibraryHelper.isVerifiedCarProjectionController(
                packageManager = packageManager,
                packageName = controller.packageName,
                uid = controller.uid
            )
    }

    @OptIn(UnstableApi::class)
    private fun isVerifiedSystemOrSelfController(controller: MediaSession.ControllerInfo): Boolean {
        val uid = controller.uid
        if (uid == android.os.Process.myUid() ||
            uid == android.os.Process.SYSTEM_UID ||
            uid == android.os.Process.BLUETOOTH_UID ||
            uid == android.os.Process.PHONE_UID
        ) {
            return true
        }
        if (controller.isTrusted) {
            return true
        }
        if (uid <= 0) return false
        val pkg = controller.packageName
        if (pkg.isBlank()) return false
        if (pkg == packageName && uid == applicationInfo.uid) return true
        // 严格要求内核级 Binder UID > 0 且与 PackageManager 登记的系统预装应用 UID 完全一致，杜绝 uid <= 0 伪造包名越权
        return runCatching {
            val uidPackages = packageManager.getPackagesForUid(uid)
            if (uidPackages == null || pkg !in uidPackages) return@runCatching false
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            val isSystemFlagged = (appInfo.flags and (
                android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                    android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                )) != 0
            isSystemFlagged && appInfo.uid == uid
        }.getOrDefault(false)
    }

    private var screenStateReceiverRegistered = false
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // 手机熄屏锁屏后立即停止后台桌面歌词与小组件精确换行调度，消除熄屏待机耗电
                    stopBackgroundDesktopLyricProgressTicker()
                    stopWidgetLyricTicker()
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (player?.isPlaying == true && SystemLyricBridge.state.value.floatingCapsuleEnabled) {
                        startBackgroundDesktopLyricProgressTicker()
                    }
                    com.concept.music.widget.BreezeWidgetManager.onScreenInteractiveResumed(applicationContext)
                    refreshWidgetLyricTicker()
                }
            }
        }
    }

    private fun ensureScreenStateReceiverRegistered() {
        if (screenStateReceiverRegistered) return
        runCatching {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                screenStateReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenStateReceiverRegistered = true
        }
    }

    private val bgLyricProgressRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying &&
                SystemLyricBridge.state.value.floatingCapsuleEnabled &&
                SystemLyricBridge.isScreenInteractive(applicationContext)
            ) {
                SystemLyricBridge.syncProgressFromService(p.currentPosition, true)
                mainHandler?.postDelayed(this, 160L)
            }
        }
    }

    private fun startBackgroundDesktopLyricProgressTicker() {
        ensureScreenStateReceiverRegistered()
        val h = mainHandler ?: return
        h.removeCallbacks(bgLyricProgressRunnable)
        if (player?.isPlaying == true &&
            SystemLyricBridge.state.value.floatingCapsuleEnabled &&
            SystemLyricBridge.isScreenInteractive(applicationContext)
        ) {
            h.post(bgLyricProgressRunnable)
        }
    }

    private fun stopBackgroundDesktopLyricProgressTicker() {
        mainHandler?.removeCallbacks(bgLyricProgressRunnable)
    }

    /**
     * 桌面小组件精准歌词换行低功耗调度器：
     * - 仅在播放中 + 亮屏 + 桌面存在开启歌词的小组件（4×4 / 4×2 / 4×1）时运行；
     * - 精确计算至下一句歌词 startTimeMs 的剩余毫秒数休眠唤醒（零轮询空转），
     *   配合 ViewFlipper + partiallyUpdateAppWidget 实现后台同样跟手丝滑的 4×4 歌词滚动。
     */
    private val widgetLyricTickRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!p.isPlaying || !SystemLyricBridge.isScreenInteractive(applicationContext)) return
            if (!com.concept.music.widget.BreezeWidgetManager.hasActiveLyricWidget(applicationContext)) return

            val lyric = SystemLyricBridge.currentLyricSnapshot
            val song = SystemLyricBridge.currentSongSnapshot
            if (lyric.lines.isEmpty() || song == null) return

            val rawPos = p.currentPosition.coerceAtLeast(0L)
            val adjustedPos = (rawPos + SystemLyricBridge.lyricOffsetMsSnapshot).coerceAtLeast(0L)
            val idx = lyric.getLineIndexAt(adjustedPos)
            if (idx >= 0) {
                val line = lyric.lines.getOrNull(idx)
                SystemLyricBridge.onLyricLineUpdated(
                    context = applicationContext,
                    song = song,
                    lineIndex = idx,
                    line = line,
                    isPlaying = true
                )
            }

            val nextLine = lyric.lines.getOrNull(idx + 1) ?: return
            val speed = p.playbackParameters.speed.coerceIn(0.5f, 2.0f)
            val remainRawMs = (nextLine.startTimeMs - adjustedPos).coerceAtLeast(0L)
            val nextDelayMs = ((remainRawMs / speed).toLong() + 18L).coerceIn(45L, 2_500L)
            mainHandler?.postDelayed(this, nextDelayMs)
        }
    }

    internal fun refreshWidgetLyricTicker() {
        if (com.concept.music.BuildConfig.IS_CAR_FLAVOR) return
        ensureScreenStateReceiverRegistered()
        val h = mainHandler ?: return
        h.removeCallbacks(widgetLyricTickRunnable)
        val p = player ?: return
        if (!p.isPlaying || !SystemLyricBridge.isScreenInteractive(applicationContext)) return
        if (!com.concept.music.widget.BreezeWidgetManager.hasActiveLyricWidget(applicationContext)) return
        h.post(widgetLyricTickRunnable)
    }

    private fun stopWidgetLyricTicker() {
        mainHandler?.removeCallbacks(widgetLyricTickRunnable)
    }

    private fun buildFallbackSongFromMediaItem(
        item: androidx.media3.common.MediaItem?,
        durationMs: Long
    ): com.concept.music.data.model.Song? {
        val id = item?.mediaId?.takeIf { it.isNotBlank() } ?: return null
        val meta = item.mediaMetadata
        val title = meta.title?.toString()?.trim().orEmpty()
        val artist = meta.artist?.toString()?.trim().orEmpty()
        if (title.isBlank() && artist.isBlank()) return null
        return com.concept.music.data.model.Song(
            id = id,
            title = title,
            artist = artist,
            album = meta.albumTitle?.toString()?.trim().orEmpty(),
            coverUrl = meta.artworkUri?.toString().orEmpty(),
            durationMs = durationMs.coerceAtLeast(0L)
        )
    }

    /**
     * 低功耗本机听歌统计检查点定时器：
     * - 仅在真正播放出声时按需调度（达标前按剩余门槛精确唤醒 <= 10s，达标后每 30s 轻量回补时长）；
     * - 暂停/停止时彻底取消（零唤醒）；熄屏播放期间持续运行，确保息屏听歌与划卡后台续播零漏记。
     */
    private val listenStatsTickRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!p.isPlaying) return
            val nextDelayMs = ListenStatsCoordinator.onPeriodicTick(
                mediaId = p.currentMediaItem?.mediaId,
                durationMs = p.duration.takeIf { it > 0L } ?: 0L,
                speed = p.playbackParameters.speed
            )
            if (nextDelayMs > 0L && p.isPlaying) {
                mainHandler?.postDelayed(this, nextDelayMs)
            }
        }
    }

    private fun scheduleListenStatsTick() {
        val h = mainHandler ?: return
        h.removeCallbacks(listenStatsTickRunnable)
        val p = player ?: return
        if (!p.isPlaying) return
        val nextDelayMs = ListenStatsCoordinator.computeNextTickDelayMs()
        if (nextDelayMs > 0L) {
            h.postDelayed(listenStatsTickRunnable, nextDelayMs)
        }
    }

    private fun stopListenStatsTick() {
        mainHandler?.removeCallbacks(listenStatsTickRunnable)
    }

    @OptIn(UnstableApi::class)
    internal fun refreshMediaSessionCustomLayoutInternal() {
        val session = mediaSession ?: return
        val buttons = buildNotificationCustomLayout()
        runCatching {
            session.setCustomLayout(buttons)
            for (controller in session.connectedControllers) {
                if (isVerifiedSystemOrSelfController(controller)) {
                    session.setCustomLayout(controller, buttons)
                }
            }
            onUpdateNotification(session, player?.isPlaying == true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = runCatching { intent?.action }.getOrNull()
        // 仅本应用内部签发了私有 IPC 令牌的 ACTION_MEDIA_BUTTON 可直走快捷分支；外部系统蓝牙/线控统一经 super.onStartCommand 交由 MediaSession.Callback 验权处理
        if (action == Intent.ACTION_MEDIA_BUTTON &&
            com.concept.music.util.SecurityGuards.verifyInternalIntent(applicationContext, intent)
        ) {
            val keyEvent = extractMediaKeyEvent(intent)
            if (keyEvent != null && handleHeadsetKeyEvent(applicationContext, keyEvent)) {
                return START_STICKY
            }
        }
        val res = runCatching { super.onStartCommand(intent, flags, startId) }.getOrDefault(START_STICKY)
        if (action in setOf(
                ConceptMusicWidgetUpdater.ACTION_WIDGET_TOGGLE_PLAY,
                ConceptMusicWidgetUpdater.ACTION_WIDGET_NEXT,
                ConceptMusicWidgetUpdater.ACTION_WIDGET_PREV,
                ConceptMusicWidgetUpdater.ACTION_WIDGET_TOGGLE_FAVORITE,
                ConceptMusicWidgetUpdater.ACTION_WIDGET_CYCLE_PLAY_MODE,
                ACTION_CUSTOM_TOGGLE_DESKTOP_LYRIC,
                ACTION_CUSTOM_TOGGLE_DESKTOP_LYRIC_LOCK,
                ACTION_CAR_BOOT_AUTO_RESUME
            )
        ) {
            // PlaybackService 为 exported="true"，自定义内部控制 Action 必须校验本应用私有 IPC 能力令牌防外部应用伪造调用
            if (!com.concept.music.util.SecurityGuards.verifyInternalIntent(applicationContext, intent)) {
                return res
            }
            when (action) {
                ConceptMusicWidgetUpdater.ACTION_WIDGET_TOGGLE_PLAY -> {
                    PlaybackCoordinator.handleTogglePlay(applicationContext, player)
                }
                ConceptMusicWidgetUpdater.ACTION_WIDGET_NEXT -> {
                    PlaybackCoordinator.handleSkipToNext(applicationContext, player)
                }
                ConceptMusicWidgetUpdater.ACTION_WIDGET_PREV -> {
                    PlaybackCoordinator.handleSkipToPrevious(applicationContext, player)
                }
                ConceptMusicWidgetUpdater.ACTION_WIDGET_TOGGLE_FAVORITE -> {
                    PlaybackCoordinator.handleToggleFavoriteFromWidget(applicationContext)
                }
                ConceptMusicWidgetUpdater.ACTION_WIDGET_CYCLE_PLAY_MODE -> {
                    PlaybackCoordinator.handleCyclePlayModeFromWidget(applicationContext, player)
                }
                ACTION_CUSTOM_TOGGLE_DESKTOP_LYRIC -> {
                    SystemLyricBridge.handleNotificationToggleDesktopLyric(applicationContext)
                    refreshMediaSessionCustomLayoutInternal()
                }
                ACTION_CUSTOM_TOGGLE_DESKTOP_LYRIC_LOCK -> {
                    SystemLyricBridge.handleNotificationToggleLock(applicationContext)
                    refreshMediaSessionCustomLayoutInternal()
                }
                ACTION_CAR_BOOT_AUTO_RESUME -> {
                    // 若由 Android 8~13 的 startForegroundService 拉起，立即更新媒体前台通知以满足 5 秒 FGS 契约，避免隧道/弱网启动触发 RemoteServiceException
                    mediaSession?.let { activeSession ->
                        runCatching { onUpdateNotification(activeSession, true) }
                    }
                    // 车机点火开机后预留 2.2s 等待车载功放与 4G 模块就绪再恢复续播；若无持久化队列则干净退场
                    mainHandler?.postDelayed({
                        val p = player ?: return@postDelayed
                        if (!p.isPlaying) {
                            val started = PlaybackCoordinator.handleResumeOnHeadsetConnected(applicationContext, p)
                            if (!started && !p.isPlaying && !p.playWhenReady) {
                                runCatching { stopSelf() }
                            }
                        }
                    }, 2_200L)
                }
            }
        }
        return res
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        val canKeepBackground = keepPlayingOnTaskRemovedEnabled &&
            AppPermissionManager.isPermissionEnabled(AppPermissionType.BACKGROUND_GUARDIAN) &&
            AppPermissionManager.isPermissionEnabled(AppPermissionType.NOTIFICATION_MEDIA)
        if (!canKeepBackground || p == null || (!p.isPlaying && !p.playWhenReady)) {
            super.onTaskRemoved(rootIntent)
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onDestroy() {
        bgAutoAdvanceRunnable?.let { mainHandler?.removeCallbacks(it) }
        bgAutoAdvanceRunnable = null
        carNetworkRetryRunnable?.let { mainHandler?.removeCallbacks(it) }
        carNetworkRetryRunnable = null
        carNetworkRetryCount = 0
        stopCarAvrcpLyricTicker()
        stopBackgroundDesktopLyricProgressTicker()
        stopWidgetLyricTicker()
        stopListenStatsTick()
        ListenStatsCoordinator.onServiceDestroyed()
        if (screenStateReceiverRegistered) {
            runCatching { unregisterReceiver(screenStateReceiver) }
            screenStateReceiverRegistered = false
        }
        SystemLyricBridge.onDesktopLyricNotificationStateChanged = null
        val staleCarControllers = verifiedCarControllers.toList()
        verifiedCarControllers.clear()
        staleCarControllers.forEach { ctrl ->
            CarSessionManager.onProjectionControllerDisconnected(applicationContext, ctrl.packageName)
        }
        CarSessionManager.onCarModeChangedCallback = null
        CarSessionManager.onTriggerCarAutoPlayCallback = null
        headsetButtonController.reset()
        abandonDuckAudioFocusRequest()
        audioFocusDuckGain = 1.0f
        isAppPlaybackActive = false
        audioDeviceCallback?.let { cb ->
            runCatching { audioManager?.unregisterAudioDeviceCallback(cb) }
        }
        audioDeviceCallback = null
        if (activeServiceInstance === this) {
            cancelServiceSleepTimer()
            activeServiceInstance = null
        }
        AudioEffectManager.onAuxEffectChanged = null
        AudioEffectManager.releaseEffects()
        libraryScope.cancel()
        carForwardingPlayer = null
        runCatching { mediaSession?.release() }
        mediaSession = null
        runCatching { player?.release() }
        player = null
        super.onDestroy()
    }
}
