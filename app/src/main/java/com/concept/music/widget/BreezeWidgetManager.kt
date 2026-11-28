package com.concept.music.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import com.concept.music.data.model.Song
import com.concept.music.service.ConceptMusicWidgetProvider
import com.concept.music.service.PlaybackService
import com.concept.music.ui.theme.ThemeManager
import com.concept.music.ui.viewmodel.PlayMode
import com.concept.music.util.SecurityGuards
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

object BreezeWidgetActions {
    const val ACTION_TOGGLE_PLAY = "com.concept.music.ACTION_WIDGET_TOGGLE_PLAY"
    const val ACTION_NEXT = "com.concept.music.ACTION_WIDGET_NEXT"
    const val ACTION_PREV = "com.concept.music.ACTION_WIDGET_PREV"
    const val ACTION_TOGGLE_FAVORITE = "com.concept.music.ACTION_WIDGET_TOGGLE_FAVORITE"
    const val ACTION_CYCLE_PLAY_MODE = "com.concept.music.ACTION_WIDGET_CYCLE_PLAY_MODE"
    const val ACTION_OPEN_NOW_PLAYING = "com.concept.music.ACTION_WIDGET_OPEN_NOW_PLAYING"
    const val EXTRA_OPEN_NOW_PLAYING = "extra_widget_open_now_playing"
    const val ACTION_WIDGET_PINNED = "com.concept.music.ACTION_WIDGET_PINNED"
    const val EXTRA_PINNED_SIZE_CLASS = "extra_widget_pinned_size_class"
}

/**
 * 微风音乐桌面小组件全局调度中枢：
 * - 管理 4 款独立尺寸卡片（4×2 / 4×1 / 2×2 / 4×4）的统一状态同步与两阶段秒绘（缓存/占位 0ms 直出 + 异步补封面）；
 * - 歌词换行走 AppWidgetManager.partiallyUpdateAppWidget 零闪烁轻量局部刷新，触发 4×4 原生 ViewFlipper 平滑滚动过渡；
 * - 无已添加小组件时 O(1) 短路零开销；熄屏期间挂起 IPC 推送，亮屏恢复时一次性补齐。
 */
object BreezeWidgetManager {
    private const val STATE_PREFS_NAME = "breeze_widget_playback_snapshot_v2"
    private const val PROGRESS_PUSH_INTERVAL_MS = 12_000L
    private const val PROGRESS_JUMP_THRESHOLD_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var snapshotLoaded = false

    @Volatile
    private var cachedSnapshot = BreezeWidgetPlaybackSnapshot()

    @Volatile
    private var lastProgressPushElapsedMs = 0L

    @Volatile
    private var lastProgressPushedPosMs = -1L

    @Volatile
    private var pendingRefreshWhileScreenOff = false

    @Volatile
    private var scheduledRefreshJob: Job? = null

    @Volatile
    private var lyricFlipJob: Job? = null

    private val stageFlipperSlot = AtomicInteger(0)

    private val _widgetPinnedEvents = MutableSharedFlow<BreezeWidgetSizeClass>(extraBufferCapacity = 4)
    val widgetPinnedEvents: SharedFlow<BreezeWidgetSizeClass> = _widgetPinnedEvents.asSharedFlow()

    private val pinConfirmLock = Any()

    @Volatile
    private var pendingPinBaselineCount: Int = -1

    @Volatile
    private var pendingPinTimestampMs: Long = 0L

    @Volatile
    private var lastPinnedEmitElapsedMs: Long = 0L

    @Volatile
    var onWidgetCommand: ((String) -> Unit)? = null

    @Volatile
    var onToggleFavoriteCommand: (() -> Unit)? = null

    @Volatile
    var onCyclePlayModeCommand: (() -> Unit)? = null

    internal val providerSpecs: List<Pair<BreezeWidgetSizeClass, Class<*>>>
        get() = if (com.concept.music.BuildConfig.IS_CAR_FLAVOR) {
            listOf(BreezeWidgetSizeClass.CARD_4X2 to ConceptMusicWidgetProvider::class.java)
        } else {
            listOf(
                BreezeWidgetSizeClass.CARD_4X2 to ConceptMusicWidgetProvider::class.java,
                BreezeWidgetSizeClass.CAPSULE_4X1 to BreezeCompactWidgetProvider::class.java,
                BreezeWidgetSizeClass.SQUARE_2X2 to BreezeSquareWidgetProvider::class.java,
                BreezeWidgetSizeClass.STAGE_4X4 to BreezeStageWidgetProvider::class.java
            )
        }

    fun providerClassFor(sizeClass: BreezeWidgetSizeClass): Class<*> {
        return providerSpecs.firstOrNull { it.first == sizeClass }?.second
            ?: ConceptMusicWidgetProvider::class.java
    }

    fun getCurrentSnapshot(context: Context): BreezeWidgetPlaybackSnapshot {
        ensureSnapshotLoaded(context.applicationContext)
        return cachedSnapshot
    }

    fun countActiveWidgets(context: Context): Int {
        return runCatching {
            val appCtx = context.applicationContext
            val manager = AppWidgetManager.getInstance(appCtx)
            providerSpecs.sumOf { (_, cls) ->
                manager.getAppWidgetIds(ComponentName(appCtx, cls))?.size ?: 0
            }
        }.getOrDefault(0)
    }

    fun hasAnyActiveWidgets(context: Context): Boolean = countActiveWidgets(context) > 0

    /**
     * 判断当前桌面是否存在需要实时歌词同步的小组件（4×4 / 4×2 / 4×1 且配置已开启歌词显示）
     */
    fun hasActiveLyricWidget(context: Context): Boolean {
        val appCtx = context.applicationContext
        BreezeWidgetPrefs.init(appCtx)
        if (!BreezeWidgetPrefs.config.value.showLyric) return false
        return runCatching {
            val manager = AppWidgetManager.getInstance(appCtx)
            providerSpecs.any { (sizeClass, cls) ->
                sizeClass != BreezeWidgetSizeClass.SQUARE_2X2 &&
                    (manager.getAppWidgetIds(ComponentName(appCtx, cls))?.isNotEmpty() == true)
            }
        }.getOrDefault(false)
    }

    fun isPinAppWidgetSupported(context: Context): Boolean {
        return runCatching {
            val host = BreezeWidgetPinAdapter.findHostActivity(context) ?: context.applicationContext
            AppWidgetManager.getInstance(host).isRequestPinAppWidgetSupported
        }.getOrDefault(false)
    }

    fun requestPinWidget(context: Context, sizeClass: BreezeWidgetSizeClass): Boolean {
        return requestPinWidgetAdapted(context, sizeClass).requestAccepted
    }

    /**
     * 全厂商适配的一键添加入口：
     * 使用前台 Activity 上下文 + extras = null（避免大 Bitmap RemoteViews 导致 ColorOS 桌面跨进程异步丢弃）发起请求，
     * 并记录添加前小组件基线数量，确保即使系统丢弃 successCallback 或走极简直通通道也能准确感知落地。
     */
    fun requestPinWidgetAdapted(
        context: Context,
        sizeClass: BreezeWidgetSizeClass,
        strategy: WidgetPinStrategy = WidgetPinStrategy.STANDARD_CLEAN
    ): WidgetPinAttemptResult {
        val result = BreezeWidgetPinAdapter.requestPinWidgetAdapted(
            context = context,
            sizeClass = sizeClass,
            previewRemoteViews = null,
            strategy = strategy
        )
        if (result.requestAccepted) {
            synchronized(pinConfirmLock) {
                val now = SystemClock.elapsedRealtime()
                if (pendingPinBaselineCount < 0 || (now - pendingPinTimestampMs) > 45_000L) {
                    pendingPinBaselineCount = result.beforeWidgetCount
                }
                pendingPinTimestampMs = now
            }
        }
        return result
    }

    internal fun onWidgetPinnedConfirmed(context: Context, sizeClass: BreezeWidgetSizeClass) {
        emitWidgetPinnedOnce(sizeClass)
        requestRefreshAll(context.applicationContext)
        PlaybackService.refreshWidgetLyricTickerIfActive()
    }

    /**
     * 当桌面完成绑定触发 Provider.onEnabled / onUpdate 时，若处于近期一键添加窗口且桌面实例数量增加，
     * 立即通知工坊页面添加成功（兼容 successCallback = null 极简通道以及不回调 PendingIntent 的国产 ROM）。
     */
    internal fun onProviderWidgetsUpdated(context: Context, sizeClass: BreezeWidgetSizeClass) {
        val shouldCheck = synchronized(pinConfirmLock) {
            pendingPinBaselineCount >= 0 && (SystemClock.elapsedRealtime() - pendingPinTimestampMs) <= 60_000L
        }
        if (!shouldCheck) return
        val nowCount = countActiveWidgets(context)
        val detectedAdded = synchronized(pinConfirmLock) {
            if (pendingPinBaselineCount >= 0 && nowCount > pendingPinBaselineCount) {
                pendingPinBaselineCount = -1
                pendingPinTimestampMs = 0L
                true
            } else {
                false
            }
        }
        if (detectedAdded) {
            emitWidgetPinnedOnce(sizeClass)
        }
    }

    private fun emitWidgetPinnedOnce(sizeClass: BreezeWidgetSizeClass) {
        val shouldEmit = synchronized(pinConfirmLock) {
            pendingPinBaselineCount = -1
            pendingPinTimestampMs = 0L
            val now = SystemClock.elapsedRealtime()
            if (now - lastPinnedEmitElapsedMs < 1_800L) {
                false
            } else {
                lastPinnedEmitElapsedMs = now
                true
            }
        }
        if (shouldEmit) {
            _widgetPinnedEvents.tryEmit(sizeClass)
        }
    }

    private fun ensureSnapshotLoaded(context: Context) {
        if (snapshotLoaded) return
        synchronized(this) {
            if (snapshotLoaded) return
            snapshotLoaded = true
            runCatching {
                val sp = context.getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
                val savedTitle = sp.getString("title", "")?.trim().orEmpty()
                if (savedTitle.isNotEmpty()) {
                    cachedSnapshot = BreezeWidgetPlaybackSnapshot(
                        songId = sp.getString("song_id", "").orEmpty(),
                        title = savedTitle,
                        artist = sp.getString("artist", "").orEmpty().ifBlank { BreezeWidgetPlaybackSnapshot.DEFAULT_IDLE_SUBTITLE },
                        album = sp.getString("album", "").orEmpty(),
                        coverUrl = sp.getString("cover_url", "").orEmpty(),
                        nextCoverUrl1 = sp.getString("next_cover_1", "").orEmpty(),
                        nextCoverUrl2 = sp.getString("next_cover_2", "").orEmpty(),
                        qualityBadge = sp.getString("quality_badge", "").orEmpty(),
                        isPlaying = false,
                        isFavorite = sp.getBoolean("is_favorite", false),
                        playMode = runCatching {
                            PlayMode.valueOf(sp.getString("play_mode", PlayMode.REPEAT_ALL.name) ?: PlayMode.REPEAT_ALL.name)
                        }.getOrDefault(PlayMode.REPEAT_ALL),
                        prevLyric = sp.getString("prev_lyric", "").orEmpty(),
                        currentLyric = sp.getString("current_lyric", "").orEmpty(),
                        nextLyric = sp.getString("next_lyric", "").orEmpty(),
                        positionMs = sp.getLong("position_ms", 0L).coerceAtLeast(0L),
                        durationMs = sp.getLong("duration_ms", 0L).coerceAtLeast(0L),
                        isLocalSong = sp.getBoolean("is_local", false),
                        localUri = sp.getString("local_uri", "").orEmpty()
                    )
                }
            }
        }
    }

    private fun persistSnapshot(context: Context, snap: BreezeWidgetPlaybackSnapshot) {
        runCatching {
            context.applicationContext.getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("song_id", snap.songId)
                .putString("title", snap.title)
                .putString("artist", snap.artist)
                .putString("album", snap.album)
                .putString("cover_url", snap.coverUrl)
                .putString("next_cover_1", snap.nextCoverUrl1)
                .putString("next_cover_2", snap.nextCoverUrl2)
                .putString("quality_badge", snap.qualityBadge)
                .putBoolean("is_favorite", snap.isFavorite)
                .putString("play_mode", snap.playMode.name)
                .putString("prev_lyric", snap.prevLyric)
                .putString("current_lyric", snap.currentLyric)
                .putString("next_lyric", snap.nextLyric)
                .putLong("position_ms", snap.positionMs)
                .putLong("duration_ms", snap.durationMs)
                .putBoolean("is_local", snap.isLocalSong)
                .putString("local_uri", snap.localUri)
                .apply()
        }
    }

    fun updateFromSong(
        context: Context,
        song: Song?,
        nextSong1: Song? = null,
        nextSong2: Song? = null,
        isPlaying: Boolean? = null,
        isFavorite: Boolean? = null,
        playMode: PlayMode? = null,
        positionMs: Long? = null,
        durationMs: Long? = null,
        clearLyrics: Boolean = false
    ) {
        val appCtx = context.applicationContext
        ensureSnapshotLoaded(appCtx)
        val prev = cachedSnapshot
        val next = if (song == null) {
            BreezeWidgetPlaybackSnapshot(
                isPlaying = false,
                playMode = playMode ?: prev.playMode
            )
        } else {
            val songChanged = song.id != prev.songId
            val resolvedPrevLyric = if (clearLyrics || songChanged) "" else prev.prevLyric
            val resolvedCurLyric = if (clearLyrics || songChanged) "" else prev.currentLyric
            val resolvedNextLyric = if (clearLyrics || songChanged) "" else prev.nextLyric
            val resolvedFav = isFavorite ?: if (songChanged) song.isFavorite else prev.isFavorite
            val resolvedPos = positionMs ?: if (songChanged) 0L else prev.positionMs
            val resolvedDur = (durationMs ?: song.durationMs).takeIf { it > 0L }
                ?: if (songChanged) 0L else prev.durationMs
            BreezeWidgetPlaybackSnapshot.fromSong(
                song = song,
                nextSong1 = nextSong1,
                nextSong2 = nextSong2,
                isPlaying = isPlaying ?: prev.isPlaying,
                isFavorite = resolvedFav,
                playMode = playMode ?: prev.playMode,
                prevLyric = resolvedPrevLyric,
                currentLyric = resolvedCurLyric,
                nextLyric = resolvedNextLyric,
                positionMs = resolvedPos,
                durationMs = resolvedDur
            )
        }
        if (next == prev) return
        cachedSnapshot = next
        lastProgressPushedPosMs = next.positionMs
        lastProgressPushElapsedMs = SystemClock.elapsedRealtime()
        persistSnapshot(appCtx, next)
        scheduleRefreshAll(appCtx, debounceMs = 40L)
    }

    fun updatePartialState(
        context: Context,
        songTitle: String? = null,
        artist: String? = null,
        prevLyric: String? = null,
        currentLyric: String? = null,
        nextLyric: String? = null,
        isPlaying: Boolean? = null,
        isFavorite: Boolean? = null,
        playMode: PlayMode? = null
    ) {
        val appCtx = context.applicationContext
        ensureSnapshotLoaded(appCtx)
        val prev = cachedSnapshot
        val next = prev.copy(
            title = songTitle?.let { it.ifBlank { BreezeWidgetPlaybackSnapshot.DEFAULT_IDLE_TITLE } } ?: prev.title,
            artist = artist?.let { it.ifBlank { BreezeWidgetPlaybackSnapshot.DEFAULT_IDLE_SUBTITLE } } ?: prev.artist,
            prevLyric = prevLyric ?: prev.prevLyric,
            currentLyric = currentLyric ?: prev.currentLyric,
            nextLyric = nextLyric ?: prev.nextLyric,
            isPlaying = isPlaying ?: prev.isPlaying,
            isFavorite = isFavorite ?: prev.isFavorite,
            playMode = playMode ?: prev.playMode
        )
        if (next == prev) return

        val onlyLyricsChanged = next.title == prev.title &&
            next.artist == prev.artist &&
            next.isPlaying == prev.isPlaying &&
            next.isFavorite == prev.isFavorite &&
            next.playMode == prev.playMode &&
            (next.prevLyric != prev.prevLyric || next.currentLyric != prev.currentLyric || next.nextLyric != prev.nextLyric)

        cachedSnapshot = next
        persistSnapshot(appCtx, next)

        if (onlyLyricsChanged) {
            pushPartialLyricTransition(appCtx, next)
        } else {
            scheduleRefreshAll(appCtx, debounceMs = 60L)
        }
    }

    /**
     * 歌词换行专属极速局部推送通道：
     * 仅更新歌词槽并切换 4×4 ViewFlipper 触发丝滑上推滚动动画，不重建背景与封面，彻底杜绝闪烁与卡顿。
     */
    fun updateLyricLine(
        context: Context,
        songTitle: String?,
        artist: String?,
        prevLyric: String,
        currentLyric: String,
        nextLyric: String,
        isPlaying: Boolean
    ) {
        updatePartialState(
            context = context,
            songTitle = songTitle,
            artist = artist,
            prevLyric = prevLyric,
            currentLyric = currentLyric,
            nextLyric = nextLyric,
            isPlaying = isPlaying
        )
    }

    private fun pushPartialLyricTransition(
        appCtx: Context,
        snap: BreezeWidgetPlaybackSnapshot
    ) {
        if (!isScreenInteractive(appCtx)) {
            pendingRefreshWhileScreenOff = true
            return
        }
        if (!hasActiveLyricWidget(appCtx)) return
        lyricFlipJob?.cancel()
        lyricFlipJob = scope.launch {
            runCatching {
                BreezeWidgetPrefs.init(appCtx)
                val config = BreezeWidgetPrefs.config.value
                if (!config.showLyric) return@launch
                val isAppDark = ThemeManager.isDarkTheme(appCtx)
                val (cachedArt, _) = BreezeWidgetArtPipeline.peekCachedArtwork(snap, needStackedNextCovers = false)
                val palette = resolveBreezeWidgetPalette(
                    config = config,
                    isAppDark = isAppDark,
                    coverSeedColor = cachedArt.extractedPalette?.dominantAccent,
                    coverSecondaryColor = cachedArt.extractedPalette?.secondaryAccent,
                    coverTertiaryColor = cachedArt.extractedPalette?.tertiaryAccent
                )
                val manager = AppWidgetManager.getInstance(appCtx)
                val nextSlot = stageFlipperSlot.updateAndGet { (it + 1) and 1 }
                providerSpecs.forEach { (sizeClass, cls) ->
                    if (sizeClass == BreezeWidgetSizeClass.SQUARE_2X2) return@forEach
                    val ids = runCatching {
                        manager.getAppWidgetIds(ComponentName(appCtx, cls))
                    }.getOrNull() ?: return@forEach
                    if (ids.isEmpty()) return@forEach
                    ids.forEach { id ->
                        val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
                        val deltaViews = BreezeWidgetRemoteViewsBuilder.buildPartialLyricUpdate(
                            context = appCtx,
                            sizeClass = sizeClass,
                            config = config,
                            snapshot = snap,
                            palette = palette,
                            widgetOptions = options,
                            targetStageSlotIndex = nextSlot
                        )
                        if (deltaViews != null) {
                            manager.partiallyUpdateAppWidget(id, deltaViews)
                        }
                    }
                }
            }
        }
    }

    /**
     * 低功耗进度节流上报：
     * - 桌面无小组件或手机熄屏时立即短路；
     * - 仅在进度跳变 >= 5s 或距上次推送 >= 12s 时才触发轻量重绘。
     */
    fun updateProgressThrottled(
        context: Context,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        val appCtx = context.applicationContext
        if (!hasAnyActiveWidgets(appCtx)) return
        ensureSnapshotLoaded(appCtx)
        val clampedPos = positionMs.coerceAtLeast(0L)
        val clampedDur = durationMs.coerceAtLeast(0L)
        val prev = cachedSnapshot
        cachedSnapshot = prev.copy(
            positionMs = clampedPos,
            durationMs = if (clampedDur > 0L) clampedDur else prev.durationMs,
            isPlaying = isPlaying
        )
        if (!isScreenInteractive(appCtx)) {
            pendingRefreshWhileScreenOff = true
            return
        }
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastProgressPushElapsedMs
        val deltaPos = if (lastProgressPushedPosMs >= 0L) abs(clampedPos - lastProgressPushedPosMs) else Long.MAX_VALUE
        if (elapsed < PROGRESS_PUSH_INTERVAL_MS && deltaPos < PROGRESS_JUMP_THRESHOLD_MS) {
            return
        }
        lastProgressPushElapsedMs = now
        lastProgressPushedPosMs = clampedPos
        scheduleRefreshAll(appCtx, debounceMs = 80L)
    }

    fun onAppThemeChanged(context: Context) {
        val appCtx = context.applicationContext
        if (!hasAnyActiveWidgets(appCtx)) return
        scheduleRefreshAll(appCtx, debounceMs = 20L, bypassScreenCheck = true)
    }

    fun onConfigChanged(context: Context) {
        val appCtx = context.applicationContext
        if (!hasAnyActiveWidgets(appCtx)) return
        scheduleRefreshAll(appCtx, debounceMs = 20L, bypassScreenCheck = true)
        PlaybackService.refreshWidgetLyricTickerIfActive()
    }

    fun onScreenInteractiveResumed(context: Context) {
        val appCtx = context.applicationContext
        if (!pendingRefreshWhileScreenOff) return
        pendingRefreshWhileScreenOff = false
        if (!hasAnyActiveWidgets(appCtx)) return
        scheduleRefreshAll(appCtx, debounceMs = 50L, bypassScreenCheck = true)
    }

    fun requestRefreshAll(context: Context) {
        val appCtx = context.applicationContext
        if (!hasAnyActiveWidgets(appCtx)) return
        scheduleRefreshAll(appCtx, debounceMs = 20L, bypassScreenCheck = true)
    }

    fun requestUpdateProvider(
        context: Context,
        sizeClass: BreezeWidgetSizeClass,
        providerClass: Class<*>,
        appWidgetIds: IntArray
    ) {
        if (appWidgetIds.isEmpty()) return
        val appCtx = context.applicationContext
        scope.launch {
            renderAndPushWidgets(appCtx, listOf(Triple(sizeClass, providerClass, appWidgetIds)))
            PlaybackService.refreshWidgetLyricTickerIfActive()
        }
    }

    fun requestUpdateSingle(
        context: Context,
        sizeClass: BreezeWidgetSizeClass,
        providerClass: Class<*>,
        appWidgetId: Int,
        options: Bundle? = null
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            renderAndPushSingleWidget(appCtx, sizeClass, providerClass, appWidgetId, options)
        }
    }

    fun dispatchCommand(context: Context, action: String) {
        val appCtx = context.applicationContext
        when (action) {
            BreezeWidgetActions.ACTION_TOGGLE_FAVORITE -> {
                val favHandler = onToggleFavoriteCommand
                if (favHandler != null) {
                    favHandler()
                    return
                }
                forwardToPlaybackService(appCtx, action)
                return
            }
            BreezeWidgetActions.ACTION_CYCLE_PLAY_MODE -> {
                val modeHandler = onCyclePlayModeCommand
                if (modeHandler != null) {
                    modeHandler()
                    return
                }
                forwardToPlaybackService(appCtx, action)
                return
            }
        }

        val handler = onWidgetCommand
        if (handler != null) {
            handler(action)
            return
        }
        val handledByService = when (action) {
            BreezeWidgetActions.ACTION_TOGGLE_PLAY -> PlaybackService.toggleActivePlayerPlayPause()
            BreezeWidgetActions.ACTION_NEXT -> PlaybackService.onSkipToNextIntercepted?.invoke() == true
            BreezeWidgetActions.ACTION_PREV -> PlaybackService.onSkipToPreviousIntercepted?.invoke() == true
            else -> false
        }
        if (!handledByService) {
            forwardToPlaybackService(appCtx, action)
        }
    }

    private fun forwardToPlaybackService(appCtx: Context, action: String) {
        runCatching {
            val serviceIntent = Intent(appCtx, PlaybackService::class.java).apply {
                this.action = action
                SecurityGuards.signInternalIntent(appCtx, this)
            }
            try {
                appCtx.startService(serviceIntent)
            } catch (_: IllegalStateException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(serviceIntent)
                } else {
                    null
                }
            }
        }
    }

    private fun scheduleRefreshAll(
        appCtx: Context,
        debounceMs: Long,
        bypassScreenCheck: Boolean = false
    ) {
        if (!bypassScreenCheck && !isScreenInteractive(appCtx)) {
            pendingRefreshWhileScreenOff = true
            return
        }
        if (!hasAnyActiveWidgets(appCtx)) return
        scheduledRefreshJob?.cancel()
        scheduledRefreshJob = scope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            val manager = AppWidgetManager.getInstance(appCtx)
            val targets = providerSpecs.mapNotNull { (sizeClass, cls) ->
                val ids = runCatching {
                    manager.getAppWidgetIds(ComponentName(appCtx, cls))
                }.getOrNull()
                if (ids != null && ids.isNotEmpty()) Triple(sizeClass, cls, ids) else null
            }
            if (targets.isEmpty()) return@launch
            renderAndPushWidgets(appCtx, targets)
        }
    }

    /**
     * 两阶段秒绘推送：
     * - 阶段一（< 12ms，零网络等待）：若内存无封面缓存，先用缓存/占位瞬间推给桌面，彻底消除 ColorOS 添加卡顿；
     * - 阶段二（异步 IO）：拉取真实专辑封面并提取 24-bin 配色后静默刷新为完整形态。
     */
    private suspend fun renderAndPushWidgets(
        appCtx: Context,
        targets: List<Triple<BreezeWidgetSizeClass, Class<*>, IntArray>>
    ) {
        runCatching {
            ensureSnapshotLoaded(appCtx)
            BreezeWidgetPrefs.init(appCtx)
            val config = BreezeWidgetPrefs.config.value
            val snap = cachedSnapshot
            val isAppDark = ThemeManager.isDarkTheme(appCtx)
            val needStacked = config.style == BreezeWidgetStyle.WATERCOLOR_STACK
            val manager = AppWidgetManager.getInstance(appCtx)
            val currentSlot = stageFlipperSlot.get() and 1

            val (cachedArt, fullyReady) = BreezeWidgetArtPipeline.peekCachedArtwork(snap, needStacked)
            pushTargetsWithArtwork(appCtx, manager, targets, config, snap, isAppDark, cachedArt, currentSlot)

            if (!fullyReady) {
                val loadedArtwork = BreezeWidgetArtPipeline.loadArtwork(
                    context = appCtx,
                    snapshot = snap,
                    needStackedNextCovers = needStacked
                )
                if (loadedArtwork.primaryCover != null || loadedArtwork.nextCover1 != null || loadedArtwork.nextCover2 != null) {
                    val latestSnap = cachedSnapshot
                    if (latestSnap.songId == snap.songId && latestSnap.coverUrl == snap.coverUrl) {
                        pushTargetsWithArtwork(
                            appCtx, manager, targets, config, latestSnap, isAppDark, loadedArtwork, stageFlipperSlot.get() and 1
                        )
                    }
                }
            }
        }
    }

    private fun pushTargetsWithArtwork(
        appCtx: Context,
        manager: AppWidgetManager,
        targets: List<Triple<BreezeWidgetSizeClass, Class<*>, IntArray>>,
        config: BreezeWidgetConfig,
        snap: BreezeWidgetPlaybackSnapshot,
        isAppDark: Boolean,
        artwork: WidgetLoadedArtwork,
        stageSlot: Int
    ) {
        val palette = resolveBreezeWidgetPalette(
            config = config,
            isAppDark = isAppDark,
            coverSeedColor = artwork.extractedPalette?.dominantAccent,
            coverSecondaryColor = artwork.extractedPalette?.secondaryAccent,
            coverTertiaryColor = artwork.extractedPalette?.tertiaryAccent
        )
        targets.forEach { (sizeClass, cls, ids) ->
            ids.forEach { id ->
                val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
                val remoteViews = BreezeWidgetRemoteViewsBuilder.build(
                    context = appCtx,
                    sizeClass = sizeClass,
                    providerClass = cls,
                    appWidgetId = id,
                    config = config,
                    snapshot = snap,
                    palette = palette,
                    artwork = artwork,
                    widgetOptions = options,
                    stageLyricSlotIndex = stageSlot
                )
                manager.updateAppWidget(id, remoteViews)
            }
        }
    }

    private suspend fun renderAndPushSingleWidget(
        appCtx: Context,
        sizeClass: BreezeWidgetSizeClass,
        providerClass: Class<*>,
        appWidgetId: Int,
        options: Bundle?
    ) {
        runCatching {
            ensureSnapshotLoaded(appCtx)
            BreezeWidgetPrefs.init(appCtx)
            val config = BreezeWidgetPrefs.config.value
            val snap = cachedSnapshot
            val isAppDark = ThemeManager.isDarkTheme(appCtx)
            val needStacked = config.style == BreezeWidgetStyle.WATERCOLOR_STACK
            val manager = AppWidgetManager.getInstance(appCtx)
            val resolvedOptions = options ?: runCatching { manager.getAppWidgetOptions(appWidgetId) }.getOrNull()

            fun pushOnce(art: WidgetLoadedArtwork, activeSnap: BreezeWidgetPlaybackSnapshot) {
                val palette = resolveBreezeWidgetPalette(
                    config = config,
                    isAppDark = isAppDark,
                    coverSeedColor = art.extractedPalette?.dominantAccent,
                    coverSecondaryColor = art.extractedPalette?.secondaryAccent,
                    coverTertiaryColor = art.extractedPalette?.tertiaryAccent
                )
                val remoteViews = BreezeWidgetRemoteViewsBuilder.build(
                    context = appCtx,
                    sizeClass = sizeClass,
                    providerClass = providerClass,
                    appWidgetId = appWidgetId,
                    config = config,
                    snapshot = activeSnap,
                    palette = palette,
                    artwork = art,
                    widgetOptions = resolvedOptions,
                    stageLyricSlotIndex = stageFlipperSlot.get() and 1
                )
                manager.updateAppWidget(appWidgetId, remoteViews)
            }

            val (cachedArt, fullyReady) = BreezeWidgetArtPipeline.peekCachedArtwork(snap, needStacked)
            pushOnce(cachedArt, snap)

            if (!fullyReady) {
                val loadedArtwork = BreezeWidgetArtPipeline.loadArtwork(
                    context = appCtx,
                    snapshot = snap,
                    needStackedNextCovers = needStacked
                )
                if (loadedArtwork.primaryCover != null || loadedArtwork.nextCover1 != null || loadedArtwork.nextCover2 != null) {
                    val latestSnap = cachedSnapshot
                    if (latestSnap.songId == snap.songId && latestSnap.coverUrl == snap.coverUrl) {
                        pushOnce(loadedArtwork, latestSnap)
                    }
                }
            }
        }
    }

    private fun isScreenInteractive(context: Context): Boolean {
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isInteractive ?: true
        }.getOrDefault(true)
    }
}
