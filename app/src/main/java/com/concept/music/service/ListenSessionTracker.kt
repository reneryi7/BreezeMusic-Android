package com.concept.music.service

import android.os.SystemClock
import androidx.media3.common.Player
import com.concept.music.data.model.Song
import com.concept.music.data.repository.LocalListenStatsRepository
import com.concept.music.data.repository.isSameMusicTrack
import com.concept.music.data.repository.mergeTrackHigherQuality
import com.concept.music.data.vip.VipManager
import java.util.LinkedHashMap
import java.util.UUID

/**
 * 独立于 UI 与酷狗 CSCC 60s 心跳的纯 Kotlin 本机听歌统计会话状态机：
 *
 * 1. **核心有效收听判定（Last.fm / ListenBrainz 业界黄金标准）**：
 *    - 常规曲目（duration >= 30s）：单次会话内真实有效收听的内容时长（实耗时间 × 倍速）
 *      达到 `min(曲长 × 50%, 4 分钟)` 即判定为 1 次有效播放；
 *    - 短曲（0 < duration < 30s）：有效收听达到 `曲长 × 80%` 或自然播完（需满足最小出声保护）；
 *    - 未知时长（duration <= 0）：有效收听满 60 秒，或自然播完且有效收听 >= 15 秒；
 *      待 ExoPlayer 解析出真实时长后立即切换为正式门槛公式。
 * 2. **严格「一播一会话」防重**：
 *    - 同一会话内最多计 1 次，拖动进度条、暂停/恢复、缓冲、CSCC 60s 心跳、切音质、切伴奏、
 *      解码错误原位重试、前后台切换与 Activity 重建均延续同一会话绝不重复计次；
 *    - 仅切换他曲、单曲循环自然播完进入下一遍、显式从列表重新点播或账号切换才结算并开启新会话。
 * 3. **达标即时 +1 + 结束增量回补时长**：
 *    - 跨过门槛瞬间立即调用 `recordPlay` 记入 1 次播放并持有唯一 `eventId`；
 *    - 后续继续播放及暂停/切歌/播完/销毁时通过 `appendPlayedDuration` 将剩余真实墙上听歌耗时
 *      精准回补到同一条事件与曲目总时长（不重复加次数）。
 */
class ListenSessionTracker(
    private val statsRepoProvider: () -> LocalListenStatsRepository,
    private val userIdProvider: () -> String?,
    private val elapsedRealtimeProvider: () -> Long = { safeElapsedRealtimeMs() },
    private val wallClockProvider: () -> Long = { System.currentTimeMillis() },
    private val maxSingleTickDeltaMs: Long = DEFAULT_MAX_SINGLE_TICK_DELTA_MS
) {
    companion object {
        const val SHORT_TRACK_MAX_DURATION_MS = 30_000L
        const val MAX_THRESHOLD_MS = 240_000L
        const val UNKNOWN_DURATION_THRESHOLD_MS = 60_000L
        const val UNKNOWN_DURATION_COMPLETE_MIN_MS = 15_000L
        const val MIN_SHORT_TRACK_PLAY_MS = 2_000L

        const val PRE_COMMIT_MIN_TICK_DELAY_MS = 1_000L
        const val PRE_COMMIT_MAX_TICK_DELAY_MS = 10_000L
        const val POST_COMMIT_FLUSH_INTERVAL_MS = 30_000L
        const val INTENT_WINDOW_MS = 15_000L
        const val DEFAULT_MAX_SINGLE_TICK_DELTA_MS = 15L * 60_000L
        private const val MAX_REGISTERED_SONGS = 256

        fun safeElapsedRealtimeMs(): Long =
            runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L).takeIf { it > 0L }
                ?: System.currentTimeMillis()

        /**
         * 计算给定曲长对应的「有效收听内容时长门槛」
         */
        fun computeRequiredContentMs(durationMs: Long): Long {
            if (durationMs <= 0L) return UNKNOWN_DURATION_THRESHOLD_MS
            if (durationMs < SHORT_TRACK_MAX_DURATION_MS) {
                val minGuard = minOf(MIN_SHORT_TRACK_PLAY_MS, durationMs)
                return (durationMs * 8L / 10L).coerceAtLeast(minGuard)
            }
            return minOf(durationMs / 2L, MAX_THRESHOLD_MS)
        }

        /**
         * 自然播完时的有效收听补充判定（短曲允许轻微计时误差，常规曲目仍严格要求达到真实有效收听门槛，防止快进到尾作弊）
         */
        fun isEligibleOnNaturalCompletion(durationMs: Long, effectiveContentMs: Long): Boolean {
            if (effectiveContentMs >= computeRequiredContentMs(durationMs)) return true
            if (durationMs in 1L until SHORT_TRACK_MAX_DURATION_MS) {
                val minGuard = minOf(MIN_SHORT_TRACK_PLAY_MS, durationMs)
                return effectiveContentMs >= minGuard && effectiveContentMs >= (durationMs / 2L)
            }
            if (durationMs <= 0L) {
                return effectiveContentMs >= UNKNOWN_DURATION_COMPLETE_MIN_MS
            }
            return false
        }
    }

    data class ActiveSessionSnapshot(
        val sessionId: String,
        val userId: String?,
        val mediaId: String,
        val song: Song,
        val durationMs: Long,
        val effectiveContentMs: Long,
        val totalWallPlayedMs: Long,
        val reportedWallPlayedMs: Long,
        val committedEventId: String?,
        val isAudiblyPlaying: Boolean,
        val currentSpeed: Float
    )

    private data class ActiveSession(
        val sessionId: String = UUID.randomUUID().toString(),
        val userId: String?,
        var mediaId: String,
        var song: Song,
        var durationMs: Long,
        var effectiveContentMs: Long = 0L,
        var totalWallPlayedMs: Long = 0L,
        var reportedWallPlayedMs: Long = 0L,
        var committedEventId: String? = null,
        var isAudiblyPlaying: Boolean = false,
        var lastTickElapsedMs: Long = 0L,
        var currentSpeed: Float = 1.0f
    ) {
        fun toSnapshot(): ActiveSessionSnapshot = ActiveSessionSnapshot(
            sessionId = sessionId,
            userId = userId,
            mediaId = mediaId,
            song = song,
            durationMs = durationMs,
            effectiveContentMs = effectiveContentMs,
            totalWallPlayedMs = totalWallPlayedMs,
            reportedWallPlayedMs = reportedWallPlayedMs,
            committedEventId = committedEventId,
            isAudiblyPlaying = isAudiblyPlaying,
            currentSpeed = currentSpeed
        )
    }

    private val songRegistry = object : LinkedHashMap<String, Song>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>?): Boolean {
            return size > MAX_REGISTERED_SONGS
        }
    }

    private var activeSession: ActiveSession? = null

    private var preserveReloadMediaId: String? = null
    private var preserveReloadSong: Song? = null
    private var preserveReloadUntilElapsedMs: Long = 0L

    private var forceNewSessionMediaId: String? = null
    private var forceNewSessionUntilElapsedMs: Long = 0L

    @Synchronized
    fun currentSessionSnapshot(): ActiveSessionSnapshot? = activeSession?.toSnapshot()

    @Synchronized
    fun registerSong(song: Song) {
        if (song.id.isBlank()) return
        val existing = songRegistry[song.id]
        songRegistry[song.id] = if (existing != null) mergeTrackHigherQuality(song, existing) else song
    }

    @Synchronized
    fun registerSongs(songs: Collection<Song>) {
        for (s in songs) {
            if (s.id.isNotBlank()) {
                val existing = songRegistry[s.id]
                songRegistry[s.id] = if (existing != null) mergeTrackHigherQuality(s, existing) else s
            }
        }
    }

    @Synchronized
    fun resolveSong(mediaId: String?): Song? {
        if (mediaId.isNullOrBlank()) return null
        return songRegistry[mediaId]
    }

    /**
     * 在向 ExoPlayer 执行 `setMediaItem` 前声明业务意图：
     * - `preserveSession = true`：切音质、切 KTV 伴奏、解码错误原位重试 -> 保持现有听歌会话不重置、不重复计次；
     * - `preserveSession = false`：用户显式点播（包括在列表中再次点击同一首歌重播）或切歌 -> 开启全新会话。
     */
    @Synchronized
    fun prepareMediaItemLoad(song: Song, preserveSession: Boolean) {
        registerSong(song)
        val now = elapsedRealtimeProvider()
        if (preserveSession) {
            preserveReloadMediaId = song.id
            preserveReloadSong = song
            preserveReloadUntilElapsedMs = now + INTENT_WINDOW_MS
            forceNewSessionMediaId = null
            forceNewSessionUntilElapsedMs = 0L
        } else {
            forceNewSessionMediaId = song.id
            forceNewSessionUntilElapsedMs = now + INTENT_WINDOW_MS
            preserveReloadMediaId = null
            preserveReloadSong = null
            preserveReloadUntilElapsedMs = 0L
        }
    }

    /**
     * 响应 ExoPlayer `onMediaItemTransition(mediaItem, reason)`（唯一真相源）
     */
    @Synchronized
    fun onMediaItemTransition(
        mediaId: String?,
        songFallback: Song?,
        reason: Int,
        durationMs: Long = 0L,
        speed: Float = 1.0f,
        isPlaying: Boolean = false
    ) {
        val nowElapsed = elapsedRealtimeProvider()
        accumulateActiveSession(nowElapsed)

        if (mediaId.isNullOrBlank()) {
            settleAndCloseActiveSession(notifyCompleted = false, nowElapsed = nowElapsed)
            clearIntents()
            return
        }

        val resolvedSong = resolveEffectiveSong(mediaId, songFallback, durationMs)
        if (resolvedSong == null) {
            settleAndCloseActiveSession(notifyCompleted = false, nowElapsed = nowElapsed)
            clearIntents()
            return
        }

        val current = activeSession
        val safeSpeed = speed.coerceIn(0.25f, 4.0f)
        val effectiveDur = resolveBestDuration(durationMs, resolvedSong.durationMs)

        // 1. 单曲循环自然重播 / 播放完自动切下一首：先按「自然播完」结算上一轮，再开启全新会话
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        ) {
            clearIntents()
            settleAndCloseActiveSession(notifyCompleted = true, nowElapsed = nowElapsed)
            openNewSession(
                mediaId = mediaId,
                song = resolvedSong,
                durationMs = effectiveDur,
                speed = safeSpeed,
                isPlaying = isPlaying,
                nowElapsed = nowElapsed
            )
            return
        }

        val preserveValid = current != null &&
            preserveReloadUntilElapsedMs >= nowElapsed &&
            (preserveReloadMediaId == mediaId ||
                (preserveReloadSong != null && isSameMusicTrack(current.song, preserveReloadSong!!)))

        val forceNewValid = forceNewSessionUntilElapsedMs >= nowElapsed &&
            forceNewSessionMediaId == mediaId

        // 2. 切音质 / 切伴奏 / 解码错误重试等原位重载：保留当前会话与已累计时长/已计次状态
        if (current != null &&
            preserveValid &&
            !forceNewValid &&
            (current.mediaId == mediaId || isSameMusicTrack(current.song, resolvedSong))
        ) {
            clearIntents()
            current.mediaId = mediaId
            current.song = mergeTrackHigherQuality(resolvedSong, current.song)
            if (effectiveDur > 0L) {
                current.durationMs = effectiveDur
            }
            current.currentSpeed = safeSpeed
            current.isAudiblyPlaying = isPlaying
            current.lastTickElapsedMs = if (isPlaying) nowElapsed else 0L
            evaluateAndFlushSession(current, forceFlushDuration = !isPlaying)
            return
        }

        // 3. 同一首曲目内部的 SEEK 过渡：严格延续当前会话（拖动进度条绝不重开会话）
        if (current != null &&
            !forceNewValid &&
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
            (current.mediaId == mediaId || isSameMusicTrack(current.song, resolvedSong))
        ) {
            current.song = mergeTrackHigherQuality(resolvedSong, current.song)
            if (effectiveDur > 0L) {
                current.durationMs = effectiveDur
            }
            current.currentSpeed = safeSpeed
            current.isAudiblyPlaying = isPlaying
            current.lastTickElapsedMs = if (isPlaying) nowElapsed else 0L
            evaluateAndFlushSession(current, forceFlushDuration = !isPlaying)
            return
        }

        // 4. 切换到不同曲目或用户显式重新点播：结算旧会话并开启全新会话
        clearIntents()
        settleAndCloseActiveSession(notifyCompleted = false, nowElapsed = nowElapsed)
        openNewSession(
            mediaId = mediaId,
            song = resolvedSong,
            durationMs = effectiveDur,
            speed = safeSpeed,
            isPlaying = isPlaying,
            nowElapsed = nowElapsed
        )
    }

    /**
     * 响应 ExoPlayer `onIsPlayingChanged(isPlaying)`
     */
    @Synchronized
    fun onPlayingStateChanged(
        isPlaying: Boolean,
        mediaId: String?,
        songFallback: Song?,
        durationMs: Long = 0L,
        speed: Float = 1.0f
    ) {
        val nowElapsed = elapsedRealtimeProvider()
        val safeSpeed = speed.coerceIn(0.25f, 4.0f)
        val current = activeSession

        if (isPlaying) {
            if (current == null) {
                if (!mediaId.isNullOrBlank()) {
                    val resolvedSong = resolveEffectiveSong(mediaId, songFallback, durationMs) ?: return
                    val effectiveDur = resolveBestDuration(durationMs, resolvedSong.durationMs)
                    openNewSession(
                        mediaId = mediaId,
                        song = resolvedSong,
                        durationMs = effectiveDur,
                        speed = safeSpeed,
                        isPlaying = true,
                        nowElapsed = nowElapsed
                    )
                }
                return
            }

            if (!mediaId.isNullOrBlank() && current.mediaId != mediaId) {
                // 兜底：若切曲回调遗漏而直接以新 mediaId 启播，先结算旧会话再开启新会话
                val resolvedSong = resolveEffectiveSong(mediaId, songFallback, durationMs)
                if (resolvedSong != null) {
                    val preserveValid = preserveReloadUntilElapsedMs >= nowElapsed &&
                        (preserveReloadMediaId == mediaId ||
                            (preserveReloadSong != null && isSameMusicTrack(current.song, preserveReloadSong!!)))
                    if (preserveValid && isSameMusicTrack(current.song, resolvedSong)) {
                        clearIntents()
                        accumulateActiveSession(nowElapsed)
                        current.mediaId = mediaId
                        current.song = mergeTrackHigherQuality(resolvedSong, current.song)
                        val effectiveDur = resolveBestDuration(durationMs, resolvedSong.durationMs)
                        if (effectiveDur > 0L) current.durationMs = effectiveDur
                        current.currentSpeed = safeSpeed
                        current.isAudiblyPlaying = true
                        current.lastTickElapsedMs = nowElapsed
                        evaluateAndFlushSession(current, forceFlushDuration = false)
                    } else {
                        clearIntents()
                        accumulateActiveSession(nowElapsed)
                        settleAndCloseActiveSession(notifyCompleted = false, nowElapsed = nowElapsed)
                        openNewSession(
                            mediaId = mediaId,
                            song = resolvedSong,
                            durationMs = resolveBestDuration(durationMs, resolvedSong.durationMs),
                            speed = safeSpeed,
                            isPlaying = true,
                            nowElapsed = nowElapsed
                        )
                    }
                }
                return
            }

            // 暂停后恢复播放同一会话：延续原有有效收听进度与已计次状态
            if (current.isAudiblyPlaying) {
                accumulateActiveSession(nowElapsed)
            } else {
                current.isAudiblyPlaying = true
                current.lastTickElapsedMs = nowElapsed
            }
            val effectiveDur = resolveBestDuration(durationMs, current.durationMs)
            if (effectiveDur > 0L) {
                current.durationMs = effectiveDur
                if (current.song.durationMs <= 0L) {
                    current.song = current.song.copy(durationMs = effectiveDur)
                }
            }
            current.currentSpeed = safeSpeed
            evaluateAndFlushSession(current, forceFlushDuration = false)
        } else {
            // 暂停 / 缓冲 / 停止：立刻落算本段已播时间并回补剩余时长，但保持会话存活以支持无缝续播
            if (current != null) {
                accumulateActiveSession(nowElapsed)
                current.isAudiblyPlaying = false
                current.lastTickElapsedMs = 0L
                evaluateAndFlushSession(current, forceFlushDuration = true)
            }
        }
    }

    /**
     * 响应 ExoPlayer `STATE_READY` 获取到真实音频总时长
     */
    @Synchronized
    fun onTrackDurationUpdated(mediaId: String?, durationMs: Long) {
        if (durationMs <= 0L) return
        val current = activeSession ?: return
        if (mediaId != null && current.mediaId != mediaId) return
        val nowElapsed = elapsedRealtimeProvider()
        accumulateActiveSession(nowElapsed)
        current.durationMs = durationMs
        if (current.song.durationMs <= 0L) {
            current.song = current.song.copy(durationMs = durationMs)
        }
        evaluateAndFlushSession(current, forceFlushDuration = false)
    }

    /**
     * 响应 ExoPlayer `onPlaybackParametersChanged(playbackParameters)` 倍速变更：
     * 先按旧倍速结算此前已播放片段，再切换为新倍速。
     */
    @Synchronized
    fun onPlaybackSpeedChanged(speed: Float) {
        val current = activeSession ?: return
        val nowElapsed = elapsedRealtimeProvider()
        accumulateActiveSession(nowElapsed)
        current.currentSpeed = speed.coerceIn(0.25f, 4.0f)
        evaluateAndFlushSession(current, forceFlushDuration = false)
    }

    /**
     * 响应低功耗定时检查点：累计出声时长、达标即时落盘、周期回补时长，并返回下一次建议唤醒间隔（ms）；
     * 若当前未处于播放态则返回 -1L（暂停态零唤醒）。
     */
    @Synchronized
    fun onPeriodicTick(
        mediaId: String? = null,
        durationMs: Long = 0L,
        speed: Float? = null
    ): Long {
        val current = activeSession ?: return -1L
        if (!current.isAudiblyPlaying) return -1L
        if (mediaId != null && current.mediaId != mediaId) return -1L

        val nowElapsed = elapsedRealtimeProvider()
        accumulateActiveSession(nowElapsed)

        if (durationMs > 0L && current.durationMs != durationMs) {
            current.durationMs = durationMs
            if (current.song.durationMs <= 0L) {
                current.song = current.song.copy(durationMs = durationMs)
            }
        }
        if (speed != null) {
            current.currentSpeed = speed.coerceIn(0.25f, 4.0f)
        }

        val pendingWallDelta = current.totalWallPlayedMs - current.reportedWallPlayedMs
        val shouldFlushPostCommit = current.committedEventId != null &&
            pendingWallDelta >= POST_COMMIT_FLUSH_INTERVAL_MS
        evaluateAndFlushSession(current, forceFlushDuration = shouldFlushPostCommit)

        return computeNextTickDelayMs()
    }

    /**
     * 计算当前活动会话的下一次低功耗定时检查延迟（未播放返回 -1L）
     */
    @Synchronized
    fun computeNextTickDelayMs(): Long {
        val current = activeSession ?: return -1L
        if (!current.isAudiblyPlaying) return -1L
        if (current.committedEventId != null) {
            return POST_COMMIT_FLUSH_INTERVAL_MS
        }
        val requiredContentMs = computeRequiredContentMs(current.durationMs)
        val remainContentMs = (requiredContentMs - current.effectiveContentMs).coerceAtLeast(0L)
        val safeSpeed = current.currentSpeed.coerceIn(0.25f, 4.0f)
        val remainWallMs = (remainContentMs / safeSpeed).toLong()
        return (remainWallMs + 150L).coerceIn(PRE_COMMIT_MIN_TICK_DELAY_MS, PRE_COMMIT_MAX_TICK_DELAY_MS)
    }

    /**
     * 响应 ExoPlayer `STATE_ENDED` 自然播完：结算并关闭当前会话（再次重播将开启全新会话）
     */
    @Synchronized
    fun onPlaybackEnded() {
        val nowElapsed = elapsedRealtimeProvider()
        accumulateActiveSession(nowElapsed)
        settleAndCloseActiveSession(notifyCompleted = true, nowElapsed = nowElapsed)
    }

    /**
     * 账号切换前将原账号正在播放的会话结算并关闭，避免跨账号串账
     */
    @Synchronized
    fun onAccountChanged(previousUserId: String?, newUserId: String?) {
        if (previousUserId == newUserId) return
        val nowElapsed = elapsedRealtimeProvider()
        accumulateActiveSession(nowElapsed)
        settleAndCloseActiveSession(notifyCompleted = false, nowElapsed = nowElapsed)
        clearIntents()
    }

    /**
     * 后台播放服务销毁时兜底落盘剩余时长并清理状态
     */
    @Synchronized
    fun onServiceDestroyed() {
        val nowElapsed = elapsedRealtimeProvider()
        accumulateActiveSession(nowElapsed)
        settleAndCloseActiveSession(notifyCompleted = false, nowElapsed = nowElapsed)
        clearIntents()
    }

    @Synchronized
    internal fun resetForTest() {
        activeSession = null
        songRegistry.clear()
        clearIntents()
    }

    private fun clearIntents() {
        preserveReloadMediaId = null
        preserveReloadSong = null
        preserveReloadUntilElapsedMs = 0L
        forceNewSessionMediaId = null
        forceNewSessionUntilElapsedMs = 0L
    }

    private fun resolveEffectiveSong(mediaId: String, fallback: Song?, durationMs: Long): Song? {
        val registered = songRegistry[mediaId]
        val base = when {
            registered != null && fallback != null -> mergeTrackHigherQuality(registered, fallback)
            registered != null -> registered
            fallback != null -> fallback
            else -> null
        } ?: return null
        val bestDur = resolveBestDuration(durationMs, base.durationMs)
        return if (bestDur > 0L && base.durationMs <= 0L) base.copy(durationMs = bestDur) else base
    }

    private fun resolveBestDuration(playerDurationMs: Long, songDurationMs: Long): Long = when {
        playerDurationMs > 0L -> playerDurationMs
        songDurationMs > 0L -> songDurationMs
        else -> 0L
    }

    private fun openNewSession(
        mediaId: String,
        song: Song,
        durationMs: Long,
        speed: Float,
        isPlaying: Boolean,
        nowElapsed: Long
    ) {
        activeSession = ActiveSession(
            userId = userIdProvider(),
            mediaId = mediaId,
            song = song,
            durationMs = durationMs,
            isAudiblyPlaying = isPlaying,
            lastTickElapsedMs = if (isPlaying) nowElapsed else 0L,
            currentSpeed = speed
        )
    }

    private fun accumulateActiveSession(nowElapsed: Long) {
        val current = activeSession ?: return
        if (!current.isAudiblyPlaying) return
        val lastTick = current.lastTickElapsedMs
        if (lastTick <= 0L) {
            current.lastTickElapsedMs = nowElapsed
            return
        }
        if (nowElapsed <= lastTick) return
        val rawDelta = nowElapsed - lastTick
        val safeDeltaWallMs = rawDelta.coerceIn(0L, maxSingleTickDeltaMs)
        current.totalWallPlayedMs += safeDeltaWallMs
        current.effectiveContentMs += (safeDeltaWallMs * current.currentSpeed).toLong()
        current.lastTickElapsedMs = nowElapsed
    }

    private fun evaluateAndFlushSession(
        session: ActiveSession,
        forceFlushDuration: Boolean,
        notifyCompleted: Boolean = false,
        isFinalSettle: Boolean = false
    ) {
        val repo = statsRepoProvider()
        val nowWall = wallClockProvider()

        if (session.committedEventId == null) {
            val eligible = if (notifyCompleted) {
                isEligibleOnNaturalCompletion(session.durationMs, session.effectiveContentMs)
            } else {
                session.effectiveContentMs >= computeRequiredContentMs(session.durationMs)
            }
            if (eligible) {
                val initialWallMs = session.totalWallPlayedMs.coerceAtLeast(1_000L)
                val eventId = repo.recordPlay(
                    userId = session.userId,
                    song = session.song,
                    playedMs = initialWallMs,
                    nowMs = nowWall
                )
                if (!eventId.isNullOrBlank()) {
                    session.committedEventId = eventId
                    session.reportedWallPlayedMs = session.totalWallPlayedMs
                }
            }
            return
        }

        if (forceFlushDuration) {
            val minDelta = if (isFinalSettle) 1L else 1_000L
            val deltaWall = session.totalWallPlayedMs - session.reportedWallPlayedMs
            if (deltaWall >= minDelta) {
                val eventId = session.committedEventId ?: return
                repo.appendPlayedDuration(
                    userId = session.userId,
                    eventId = eventId,
                    deltaPlayedMs = deltaWall,
                    nowMs = nowWall
                )
                session.reportedWallPlayedMs = session.totalWallPlayedMs
            }
        }
    }

    private fun settleAndCloseActiveSession(notifyCompleted: Boolean, nowElapsed: Long) {
        val current = activeSession ?: return
        if (current.isAudiblyPlaying) {
            accumulateActiveSession(nowElapsed)
            current.isAudiblyPlaying = false
            current.lastTickElapsedMs = 0L
        }
        evaluateAndFlushSession(
            session = current,
            forceFlushDuration = true,
            notifyCompleted = notifyCompleted,
            isFinalSettle = true
        )
        activeSession = null
    }
}

/**
 * 进程级本机听歌统计唯一协调门面：
 * - 由 `PlaybackService` 的 `ExoPlayer` 直接驱动播放生命周期事件，天然覆盖前台、熄屏、划卡后台续播与 Activity 重建；
 * - 由 `MusicPlayerViewModel` 与 `PlaybackCoordinator` 注册完整 `Song` 元数据并透传切音质/伴奏保活与显式重播意图。
 */
object ListenStatsCoordinator {
    val tracker: ListenSessionTracker = ListenSessionTracker(
        statsRepoProvider = { VipManager.listenStats },
        userIdProvider = {
            runCatching { VipManager.accounts.state.value.currentId }.getOrNull()
        }
    )

    fun registerSong(song: Song) = tracker.registerSong(song)

    fun registerSongs(songs: Collection<Song>) = tracker.registerSongs(songs)

    fun resolveSong(mediaId: String?): Song? = tracker.resolveSong(mediaId)

    fun prepareMediaItemLoad(song: Song, preserveSession: Boolean) =
        tracker.prepareMediaItemLoad(song, preserveSession)

    fun onMediaItemTransition(
        mediaId: String?,
        songFallback: Song?,
        reason: Int,
        durationMs: Long,
        speed: Float,
        isPlaying: Boolean
    ) = tracker.onMediaItemTransition(
        mediaId = mediaId,
        songFallback = songFallback,
        reason = reason,
        durationMs = durationMs,
        speed = speed,
        isPlaying = isPlaying
    )

    fun onPlayingStateChanged(
        isPlaying: Boolean,
        mediaId: String?,
        songFallback: Song?,
        durationMs: Long,
        speed: Float
    ) = tracker.onPlayingStateChanged(
        isPlaying = isPlaying,
        mediaId = mediaId,
        songFallback = songFallback,
        durationMs = durationMs,
        speed = speed
    )

    fun onTrackDurationUpdated(mediaId: String?, durationMs: Long) =
        tracker.onTrackDurationUpdated(mediaId, durationMs)

    fun onPlaybackSpeedChanged(speed: Float) =
        tracker.onPlaybackSpeedChanged(speed)

    fun onPeriodicTick(mediaId: String?, durationMs: Long, speed: Float): Long =
        tracker.onPeriodicTick(mediaId = mediaId, durationMs = durationMs, speed = speed)

    fun computeNextTickDelayMs(): Long = tracker.computeNextTickDelayMs()

    fun onPlaybackEnded() = tracker.onPlaybackEnded()

    fun onAccountChanged(previousUserId: String?, newUserId: String?) =
        tracker.onAccountChanged(previousUserId, newUserId)

    fun onServiceDestroyed() = tracker.onServiceDestroyed()
}
