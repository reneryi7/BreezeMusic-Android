package com.concept.music.service

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.concept.music.data.kugou.KugouSigner
import com.concept.music.data.kugou.suspendRunCatching
import com.concept.music.data.model.AudioQuality
import com.concept.music.data.model.Song
import com.concept.music.data.vip.VipManager
import com.concept.music.ui.viewmodel.PlayMode
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Application/Service 级常驻后台播放与持久化协调器：
 * 1. 解耦 `MainActivity` / `MusicPlayerViewModel` 生命周期与后台播放控制；
 * 2. 当 `MainActivity` 在后台被回收或桌面微件冷启动唤醒服务时，接管播放/暂停与上下首切歌；
 * 3. 提供独立于 `viewModelScope` 的单线程串行 IO 协程池，保障大队列序列化不阻塞主线程且退出时落盘不中断。
 */
object PlaybackCoordinator {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val persistDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val ioScope = CoroutineScope(SupervisorJob() + persistDispatcher)

    @Volatile
    var onTogglePlayDelegate: (() -> Boolean)? = null

    @Volatile
    var onExplicitPlayDelegate: (() -> Boolean)? = null

    @Volatile
    var onExplicitPauseDelegate: (() -> Boolean)? = null

    @Volatile
    var onResumePlayDelegate: (() -> Boolean)? = null

    @Volatile
    var onSkipToPreviousDelegate: (() -> Boolean)? = null

    @Volatile
    var onSkipToNextDelegate: (() -> Boolean)? = null

    @Volatile
    var onExternalCarSongSelectedDelegate: ((resolvedSong: Song, queue: List<Song>, index: Int, isPersonalFm: Boolean) -> Unit)? = null

    fun adoptCarSelectedSongInBackground(
        context: Context,
        resolvedSong: Song,
        queue: List<Song>,
        isPersonalFm: Boolean
    ) {
        val appCtx = context.applicationContext
        val idx = queue.indexOfFirst { it.id == resolvedSong.id }.coerceAtLeast(0)
        val prefs = appCtx.getSharedPreferences("concept_player_prefs", Context.MODE_PRIVATE)
        val modeName = prefs.getString("play_mode", PlayMode.REPEAT_ALL.name) ?: PlayMode.REPEAT_ALL.name
        val playMode = runCatching { PlayMode.valueOf(modeName) }.getOrDefault(PlayMode.REPEAT_ALL)
        syncQueueSnapshot(queue, idx, playMode.name)
        ListenStatsCoordinator.registerSongs(queue)
        ListenStatsCoordinator.prepareMediaItemLoad(resolvedSong, preserveSession = false)

        val delegate = onExternalCarSongSelectedDelegate
        if (delegate != null) {
            mainScope.launch {
                delegate(resolvedSong, queue, idx, isPersonalFm)
            }
            return
        }

        // 若前台 ViewModel 未挂载（如手机锁屏状态下直接从车机大屏选歌点播），由后台协调器独立拉取歌词与持久化队列
        val userScopeKey = runCatching { VipManager.accounts.state.value.currentId ?: "guest" }.getOrDefault("guest")
        persistAsync(prefs) {
            val cleanQueue = queue.map { s -> if (s.isLocalSong) s else s.copy(playUrl = "") }
            val qJson = KugouSigner.gson.toJson(cleanQueue)
            putString("saved_queue_json_$userScopeKey", qJson)
            putInt("saved_current_index_$userScopeKey", idx)
            putLong("saved_position_ms_$userScopeKey", 0L)
            putInt("saved_current_index", idx)
            putLong("saved_position_ms", 0L)
        }
        val next1 = if (queue.size > 1) queue[(idx + 1) % queue.size].takeIf { it.id != resolvedSong.id } else null
        val next2 = if (queue.size > 2) queue[(idx + 2) % queue.size].takeIf { it.id != resolvedSong.id && it.id != next1?.id } else null
        val likedList = runCatching { VipManager.discoverCache.getLikedSongs(userScopeKey.takeIf { it != "guest" }) }.getOrDefault(emptyList())
        val isLikedNow = likedList.any { com.concept.music.data.repository.isSameMusicTrack(it, resolvedSong) } || resolvedSong.isFavorite
        ConceptMusicWidgetUpdater.updateFromSong(
            context = appCtx,
            song = resolvedSong,
            nextSong1 = next1,
            nextSong2 = next2,
            isPlaying = true,
            isFavorite = isLikedNow,
            playMode = playMode,
            positionMs = 0L,
            durationMs = resolvedSong.durationMs,
            clearLyrics = true
        )
        SystemLyricBridge.updatePlaybackSnapshot(
            song = resolvedSong,
            lyricData = com.concept.music.data.model.LyricData.Empty,
            currentLyricIndex = -1,
            currentPositionMs = 0L,
            isPlaying = true,
            playMode = playMode
        )
        PlaybackService.notifyCarAvrcpLyricDataUpdated()
        mainScope.launch {
            val fetchedLyric = withContext(Dispatchers.IO) {
                suspendRunCatching { VipManager.lyric.getLyric(resolvedSong) }.getOrNull()
            } ?: com.concept.music.data.model.LyricData.Empty
            SystemLyricBridge.updatePlaybackSnapshot(
                song = resolvedSong,
                lyricData = fetchedLyric,
                currentLyricIndex = if (fetchedLyric.lines.isNotEmpty()) fetchedLyric.getLineIndexAt(0L) else -1,
                currentPositionMs = 0L,
                isPlaying = true,
                playMode = playMode
            )
            PlaybackService.notifyCarAvrcpLyricDataUpdated()
        }
    }

    @Volatile
    private var cachedQueue: List<Song> = emptyList()

    @Volatile
    private var cachedIndex: Int = 0

    @Volatile
    private var cachedPlayModeName: String = PlayMode.REPEAT_ALL.name

    @Volatile
    private var bgResolveJob: Job? = null

    fun syncQueueSnapshot(queue: List<Song>, currentIndex: Int, playModeName: String) {
        cachedQueue = queue
        cachedIndex = currentIndex
        cachedPlayModeName = playModeName
    }

    /**
     * 当 App 在后台通过系统通知栏或 OPPO 流体云快捷开启桌面歌词时，
     * 确保 `SystemLyricBridge` 已持有当前播放曲目与歌词快照。
     */
    fun ensureDesktopLyricSongSnapshot(context: Context) {
        if (SystemLyricBridge.currentSongSnapshot != null && SystemLyricBridge.currentLyricSnapshot.lines.isNotEmpty()) {
            return
        }
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences("concept_player_prefs", Context.MODE_PRIVATE)
        val userScopeKey = runCatching {
            VipManager.initialize(appCtx)
            VipManager.accounts.state.value.currentId ?: "guest"
        }.getOrDefault("guest")

        val queue: List<Song> = cachedQueue.ifEmpty {
            val queueJson = prefs.getString("saved_queue_json_$userScopeKey", null)
                ?: prefs.getString("saved_queue_json", null)
            if (queueJson.isNullOrBlank()) {
                emptyList()
            } else {
                runCatching {
                    val type = object : TypeToken<List<Song>>() {}.type
                    KugouSigner.gson.fromJson<List<Song>>(queueJson, type)
                        ?.map { it.sanitizeFromJson() }
                        .orEmpty()
                }.getOrDefault(emptyList())
            }
        }
        if (queue.isEmpty()) return
        val idx = (if (cachedQueue.isNotEmpty()) cachedIndex else {
            prefs.getInt("saved_current_index_$userScopeKey", prefs.getInt("saved_current_index", 0))
        }).coerceIn(0, queue.lastIndex)
        val targetSong = queue.getOrNull(idx) ?: return
        val savedPos = prefs.getLong("saved_position_ms_$userScopeKey", prefs.getLong("saved_position_ms", 0L)).coerceAtLeast(0L)

        if (SystemLyricBridge.currentSongSnapshot == null) {
            SystemLyricBridge.updatePlaybackSnapshot(
                song = targetSong,
                lyricData = SystemLyricBridge.currentLyricSnapshot,
                currentLyricIndex = SystemLyricBridge.currentLineIndexSnapshot,
                currentPositionMs = savedPos,
                isPlaying = SystemLyricBridge.isPlayingSnapshot
            )
        }
        if (SystemLyricBridge.currentLyricSnapshot.lines.isEmpty()) {
            mainScope.launch {
                val fetchedLyric = withContext(Dispatchers.IO) {
                    suspendRunCatching { VipManager.lyric.getLyric(targetSong) }.getOrNull()
                }
                if (fetchedLyric != null && fetchedLyric.lines.isNotEmpty()) {
                    val lineIdx = fetchedLyric.getLineIndexAt(SystemLyricBridge.currentPositionMsSnapshot).coerceAtLeast(0)
                    SystemLyricBridge.updatePlaybackSnapshot(
                        song = targetSong,
                        lyricData = fetchedLyric,
                        currentLyricIndex = lineIdx,
                        currentPositionMs = SystemLyricBridge.currentPositionMsSnapshot,
                        isPlaying = SystemLyricBridge.isPlayingSnapshot
                    )
                }
            }
        }
    }

    fun persistAsync(prefs: SharedPreferences, block: SharedPreferences.Editor.() -> Unit): Job {
        return ioScope.launch {
            runCatching {
                val editor = prefs.edit()
                editor.block()
                editor.apply()
            }
        }
    }

    fun handleTogglePlay(context: Context, player: Player?): Boolean {
        onTogglePlayDelegate?.let { delegate ->
            if (delegate()) return true
        }
        val activePlayer = player ?: return false
        if (activePlayer.mediaItemCount > 0) {
            val targetPlay = !activePlayer.isPlaying
            if (targetPlay) {
                if (activePlayer.playbackState == Player.STATE_ENDED) {
                    activePlayer.seekTo(0L)
                } else if (activePlayer.playbackState == Player.STATE_IDLE) {
                    activePlayer.prepare()
                }
                activePlayer.play()
            } else {
                activePlayer.pause()
            }
            AmbientSoundManager.onMusicPlayStateChanged(targetPlay)
            ConceptMusicWidgetUpdater.updateState(context.applicationContext, isPlaying = targetPlay)
            return true
        }
        return playFromBackgroundQueue(context, activePlayer, offsetDelta = 0)
    }

    /**
     * 当插入有线耳机或连接蓝牙耳机时触发自动开始播放（仅在当前未播放时启动，绝不误暂停正在播放的音乐）
     */
    fun handleResumeOnHeadsetConnected(context: Context, player: Player?): Boolean {
        if (player?.isPlaying == true) return false
        onResumePlayDelegate?.let { delegate ->
            if (delegate()) return true
        }
        val activePlayer = player ?: return false
        if (activePlayer.isPlaying) return false
        if (activePlayer.mediaItemCount > 0) {
            if (activePlayer.playbackState == Player.STATE_ENDED) {
                activePlayer.seekTo(0L)
            } else if (activePlayer.playbackState == Player.STATE_IDLE) {
                activePlayer.prepare()
            }
            AmbientSoundManager.prepareForAppMusicPlayback()
            activePlayer.play()
            AmbientSoundManager.onMusicPlayStateChanged(true)
            ConceptMusicWidgetUpdater.updateState(context.applicationContext, isPlaying = true)
            return true
        }
        return playFromBackgroundQueue(context, activePlayer, offsetDelta = 0)
    }

    fun handleExplicitPlay(context: Context, player: Player?): Boolean {
        onExplicitPlayDelegate?.let { delegate ->
            if (delegate()) return true
        }
        if (player?.isPlaying == true) return true
        return handleResumeOnHeadsetConnected(context, player)
    }

    fun handleExplicitPause(context: Context, player: Player?): Boolean {
        onExplicitPauseDelegate?.let { delegate ->
            if (delegate()) return true
        }
        val activePlayer = player ?: return false
        if (!activePlayer.isPlaying && !activePlayer.playWhenReady) return true
        activePlayer.pause()
        AmbientSoundManager.onMusicPlayStateChanged(false)
        ConceptMusicWidgetUpdater.updateState(context.applicationContext, isPlaying = false)
        return true
    }

    fun handleSkipToPrevious(context: Context, player: Player?): Boolean {
        onSkipToPreviousDelegate?.let { delegate ->
            if (delegate()) return true
        }
        val activePlayer = player ?: return false
        return playFromBackgroundQueue(context, activePlayer, offsetDelta = -1)
    }

    fun handleSkipToNext(context: Context, player: Player?): Boolean {
        onSkipToNextDelegate?.let { delegate ->
            if (delegate()) return true
        }
        val activePlayer = player ?: return false
        return playFromBackgroundQueue(context, activePlayer, offsetDelta = 1)
    }

    private fun playFromBackgroundQueue(
        context: Context,
        player: Player,
        offsetDelta: Int
    ): Boolean {
        val appCtx = context.applicationContext
        VipManager.initialize(appCtx)
        val prefs = appCtx.getSharedPreferences("concept_player_prefs", Context.MODE_PRIVATE)
        val userScopeKey = VipManager.accounts.state.value.currentId ?: "guest"

        val queue: List<Song> = cachedQueue.ifEmpty {
            val queueJson = prefs.getString("saved_queue_json_$userScopeKey", null)
                ?: prefs.getString("saved_queue_json", null)
            if (queueJson.isNullOrBlank()) {
                emptyList()
            } else {
                runCatching {
                    val type = object : TypeToken<List<Song>>() {}.type
                    KugouSigner.gson.fromJson<List<Song>>(queueJson, type)
                        ?.map { it.sanitizeFromJson() }
                        .orEmpty()
                }.getOrDefault(emptyList())
            }
        }
        if (queue.isEmpty()) return false

        val baseIndex = if (cachedQueue.isNotEmpty()) {
            cachedIndex
        } else {
            prefs.getInt("saved_current_index_$userScopeKey", prefs.getInt("saved_current_index", 0))
        }.coerceIn(0, queue.lastIndex)

        val modeName = if (cachedQueue.isNotEmpty()) {
            cachedPlayModeName
        } else {
            prefs.getString("play_mode", PlayMode.REPEAT_ALL.name) ?: PlayMode.REPEAT_ALL.name
        }
        val playMode = runCatching { PlayMode.valueOf(modeName) }.getOrDefault(PlayMode.REPEAT_ALL)

        val targetIdx = when {
            offsetDelta == 0 -> baseIndex
            playMode == PlayMode.SHUFFLE && queue.size > 1 -> {
                var r = Random.nextInt(queue.size)
                if (r == baseIndex) r = (baseIndex + 1) % queue.size
                r
            }
            offsetDelta > 0 -> (baseIndex + 1) % queue.size
            else -> if (baseIndex - 1 < 0) queue.lastIndex else baseIndex - 1
        }
        val targetSong = queue.getOrNull(targetIdx) ?: return false
        syncQueueSnapshot(queue, targetIdx, playMode.name)

        val savedQuality = runCatching {
            AudioQuality.valueOf(
                prefs.getString("preferred_quality_v2", AudioQuality.HIGH.name) ?: AudioQuality.HIGH.name
            )
        }.getOrDefault(AudioQuality.HIGH)
        val wifiOnlyLossless = prefs.getBoolean("wifi_only_lossless", false)
        val isOnCellular = runCatching {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        }.getOrDefault(false)
        val effectiveQuality = if (wifiOnlyLossless && isOnCellular &&
            (savedQuality == AudioQuality.LOSSLESS || savedQuality == AudioQuality.HI_RES)
        ) {
            AudioQuality.HIGH
        } else {
            savedQuality
        }
        val savedSpeed = prefs.getFloat("playback_speed", 1.0f).coerceIn(0.5f, 2.0f)
        val savedPitch = prefs.getFloat("playback_pitch", 1.0f).coerceIn(0.5f, 2.0f)

        persistAsync(prefs) {
            putInt("saved_current_index_$userScopeKey", targetIdx)
            putInt("saved_current_index", targetIdx)
            putLong("saved_position_ms_$userScopeKey", 0L)
            putLong("saved_position_ms", 0L)
        }

        bgResolveJob?.cancel()
        bgResolveJob = mainScope.launch {
            val playbackResult = withContext(Dispatchers.IO) {
                suspendRunCatching {
                    val reqQ = if (effectiveQuality in targetSong.effectiveQualities) {
                        effectiveQuality
                    } else {
                        targetSong.highestQuality
                    }
                    VipManager.music.resolvePlayback(targetSong, reqQ)
                }.getOrNull()
            } ?: return@launch

            if (playbackResult.url.isNotBlank()) {
                val resolvedHash = playbackResult.resolvedHash.ifBlank {
                    targetSong.hashForQuality(playbackResult.actualQuality).ifBlank { targetSong.hash }
                }
                val cacheKey = AudioCacheManager.buildCacheKey(
                    hash = resolvedHash,
                    qualityApiValue = playbackResult.actualQuality.apiValue,
                    isFreePart = playbackResult.isFreePart
                )
                val item = MediaItem.Builder()
                    .setMediaId(targetSong.id)
                    .setUri(playbackResult.url)
                    .setCustomCacheKey(cacheKey)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(targetSong.title)
                            .setArtist(targetSong.artist)
                            .setAlbumTitle(targetSong.album)
                            .setArtworkUri(targetSong.coverUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
                            .build()
                    )
                    .build()
                ListenStatsCoordinator.registerSongs(queue)
                ListenStatsCoordinator.prepareMediaItemLoad(targetSong, preserveSession = false)
                player.setMediaItem(item)
                player.playbackParameters = androidx.media3.common.PlaybackParameters(savedSpeed, savedPitch)
                player.repeatMode = if (playMode == PlayMode.REPEAT_ONE) {
                    Player.REPEAT_MODE_ONE
                } else {
                    Player.REPEAT_MODE_OFF
                }
                AmbientSoundManager.prepareForAppMusicPlayback()
                player.prepare()
                player.play()
                // 异步同步云端播放历史（排除云盘与本地音频文件），确保跨设备继续播放队列及时更新
                if (!targetSong.isCloudSong && !targetSong.isLocalSong) {
                    mainScope.launch(Dispatchers.IO) {
                        runCatching { VipManager.music.uploadPlayHistory(targetSong) }
                    }
                }
                AmbientSoundManager.onMusicPlayStateChanged(true)
                val next1 = if (queue.size > 1) queue[(targetIdx + 1) % queue.size].takeIf { it.id != targetSong.id } else null
                val next2 = if (queue.size > 2) queue[(targetIdx + 2) % queue.size].takeIf { it.id != targetSong.id && it.id != next1?.id } else null
                val likedList = runCatching { VipManager.discoverCache.getLikedSongs(userScopeKey.takeIf { it != "guest" }) }.getOrDefault(emptyList())
                val isLikedNow = likedList.any { com.concept.music.data.repository.isSameMusicTrack(it, targetSong) } || targetSong.isFavorite
                ConceptMusicWidgetUpdater.updateFromSong(
                    context = appCtx,
                    song = targetSong,
                    nextSong1 = next1,
                    nextSong2 = next2,
                    isPlaying = true,
                    isFavorite = isLikedNow,
                    playMode = playMode,
                    positionMs = 0L,
                    durationMs = targetSong.durationMs,
                    clearLyrics = true
                )
                SystemLyricBridge.updatePlaybackSnapshot(
                    song = targetSong,
                    lyricData = com.concept.music.data.model.LyricData.Empty,
                    currentLyricIndex = -1,
                    currentPositionMs = 0L,
                    isPlaying = true,
                    playMode = playMode
                )
                PlaybackService.notifyCarAvrcpLyricDataUpdated()
                val fetchedLyric = withContext(Dispatchers.IO) {
                    suspendRunCatching { VipManager.lyric.getLyric(targetSong) }.getOrNull()
                } ?: com.concept.music.data.model.LyricData.Empty
                val livePos = (player.currentPosition + SystemLyricBridge.lyricOffsetMsSnapshot).coerceAtLeast(0L)
                val lineIdx = if (fetchedLyric.lines.isNotEmpty()) fetchedLyric.getLineIndexAt(livePos) else -1
                SystemLyricBridge.updatePlaybackSnapshot(
                    song = targetSong,
                    lyricData = fetchedLyric,
                    currentLyricIndex = lineIdx,
                    currentPositionMs = livePos,
                    isPlaying = player.isPlaying,
                    playMode = playMode
                )
                PlaybackService.notifyCarAvrcpLyricDataUpdated()
            }
        }
        return true
    }

    /**
     * 后台桌面小组件红心喜欢/取消喜欢兜底通道（当 App UI 未在前台时直接与云端「我喜欢」歌单同步）
     */
    fun handleToggleFavoriteFromWidget(context: Context) {
        val appCtx = context.applicationContext
        mainScope.launch(Dispatchers.IO) {
            runCatching {
                VipManager.initialize(appCtx)
                val targetSong = SystemLyricBridge.currentSongSnapshot
                    ?: cachedQueue.getOrNull(cachedIndex)
                    ?: return@launch
                val uid = VipManager.accounts.state.value.currentId ?: return@launch
                if (VipManager.accounts.state.value.auth != com.concept.music.data.kugou.AuthState.READY) {
                    return@launch
                }
                val likedPl = VipManager.music.userPlaylists().firstOrNull { it.isLiked } ?: return@launch
                if (likedPl.listId <= 0L) return@launch
                val cachedLiked = VipManager.discoverCache.getLikedSongs(uid)
                val currentlyLiked = cachedLiked.any { com.concept.music.data.repository.isSameMusicTrack(it, targetSong) }
                if (currentlyLiked) {
                    val matched = cachedLiked.firstOrNull { com.concept.music.data.repository.isSameMusicTrack(it, targetSong) } ?: targetSong
                    val ok = runCatching { VipManager.music.removeSongFromPlaylist(likedPl.listId, matched) }.getOrDefault(false)
                    if (ok) {
                        val updated = cachedLiked.filterNot { com.concept.music.data.repository.isSameMusicTrack(it, targetSong) }
                        VipManager.discoverCache.saveLikedSongs(uid, updated)
                        ConceptMusicWidgetUpdater.updateState(appCtx, isFavorite = false)
                    }
                } else {
                    val ok = runCatching { VipManager.music.addSongToPlaylist(likedPl.listId, targetSong) }.getOrDefault(false)
                    if (ok) {
                        val added = targetSong.copy(isFavorite = true)
                        VipManager.discoverCache.saveLikedSongs(uid, listOf(added) + cachedLiked)
                        ConceptMusicWidgetUpdater.updateState(appCtx, isFavorite = true)
                    }
                }
            }
        }
    }

    /**
     * 后台桌面小组件切换播放模式兜底通道（4×4 沉浸台）
     */
    fun handleCyclePlayModeFromWidget(context: Context, player: Player?) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences("concept_player_prefs", Context.MODE_PRIVATE)
        val curMode = runCatching {
            PlayMode.valueOf(prefs.getString("play_mode", cachedPlayModeName) ?: cachedPlayModeName)
        }.getOrDefault(PlayMode.REPEAT_ALL)
        val nextMode = when (curMode) {
            PlayMode.REPEAT_ALL -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.REPEAT_ALL
        }
        cachedPlayModeName = nextMode.name
        persistAsync(prefs) {
            putString("play_mode", nextMode.name)
        }
        mainScope.launch(Dispatchers.Main) {
            player?.repeatMode = if (nextMode == PlayMode.REPEAT_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
        SystemLyricBridge.syncFromAppSettings(playMode = nextMode)
        ConceptMusicWidgetUpdater.updateState(appCtx, playMode = nextMode)
    }
}
