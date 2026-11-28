package com.concept.music.service

import android.content.Context
import com.concept.music.data.model.Song
import com.concept.music.ui.viewmodel.PlayMode
import com.concept.music.widget.BaseBreezeWidgetProvider
import com.concept.music.widget.BreezeWidgetActions
import com.concept.music.widget.BreezeWidgetManager
import com.concept.music.widget.BreezeWidgetSizeClass

/**
 * 桌面小组件兼容门面：
 * - 完整委托至全新 com.concept.music.widget.BreezeWidgetManager 四尺寸渲染与低功耗调度引擎；
 * - 保留原有常量与方法签名确保调用方与历史用户平滑升级零冲突。
 */
object ConceptMusicWidgetUpdater {
    const val ACTION_WIDGET_TOGGLE_PLAY = BreezeWidgetActions.ACTION_TOGGLE_PLAY
    const val ACTION_WIDGET_NEXT = BreezeWidgetActions.ACTION_NEXT
    const val ACTION_WIDGET_PREV = BreezeWidgetActions.ACTION_PREV
    const val ACTION_WIDGET_TOGGLE_FAVORITE = BreezeWidgetActions.ACTION_TOGGLE_FAVORITE
    const val ACTION_WIDGET_CYCLE_PLAY_MODE = BreezeWidgetActions.ACTION_CYCLE_PLAY_MODE
    const val EXTRA_OPEN_NOW_PLAYING = BreezeWidgetActions.EXTRA_OPEN_NOW_PLAYING

    var onWidgetCommand: ((String) -> Unit)?
        get() = BreezeWidgetManager.onWidgetCommand
        set(value) {
            BreezeWidgetManager.onWidgetCommand = value
        }

    var onToggleFavorite: (() -> Unit)?
        get() = BreezeWidgetManager.onToggleFavoriteCommand
        set(value) {
            BreezeWidgetManager.onToggleFavoriteCommand = value
        }

    var onCyclePlayMode: (() -> Unit)?
        get() = BreezeWidgetManager.onCyclePlayModeCommand
        set(value) {
            BreezeWidgetManager.onCyclePlayModeCommand = value
        }

    fun dispatchCommand(context: Context, action: String) {
        BreezeWidgetManager.dispatchCommand(context, action)
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
        BreezeWidgetManager.updateFromSong(
            context = context,
            song = song,
            nextSong1 = nextSong1,
            nextSong2 = nextSong2,
            isPlaying = isPlaying,
            isFavorite = isFavorite,
            playMode = playMode,
            positionMs = positionMs,
            durationMs = durationMs,
            clearLyrics = clearLyrics
        )
    }

    fun updateState(
        context: Context,
        songTitle: String? = null,
        artist: String? = null,
        lyricLine: String? = null,
        prevLyricLine: String? = null,
        nextLyricLine: String? = null,
        isPlaying: Boolean? = null,
        isFavorite: Boolean? = null,
        playMode: PlayMode? = null
    ) {
        BreezeWidgetManager.updatePartialState(
            context = context,
            songTitle = songTitle,
            artist = artist,
            prevLyric = prevLyricLine,
            currentLyric = lyricLine,
            nextLyric = nextLyricLine,
            isPlaying = isPlaying,
            isFavorite = isFavorite,
            playMode = playMode
        )
    }

    fun updateProgressThrottled(
        context: Context,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        BreezeWidgetManager.updateProgressThrottled(
            context = context,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying
        )
    }

    fun requestUpdate(context: Context) {
        BreezeWidgetManager.requestRefreshAll(context)
    }
}

/**
 * 保留原类名作为全新的「4×2 黄金主力卡」桌面小组件 Provider，
 * 确保老版本已添加旧小组件的用户升级后自动原地蜕变为新版设计，绝不出现损坏框。
 */
class ConceptMusicWidgetProvider : BaseBreezeWidgetProvider(BreezeWidgetSizeClass.CARD_4X2)
