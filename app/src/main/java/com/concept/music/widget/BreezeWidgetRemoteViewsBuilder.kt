package com.concept.music.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.concept.music.MainActivity
import com.concept.music.R
import com.concept.music.service.ConceptMusicWidgetProvider
import com.concept.music.ui.viewmodel.PlayMode
import com.concept.music.util.SecurityGuards

internal object BreezeWidgetRemoteViewsBuilder {

    fun build(
        context: Context,
        sizeClass: BreezeWidgetSizeClass,
        providerClass: Class<*>,
        appWidgetId: Int,
        config: BreezeWidgetConfig,
        snapshot: BreezeWidgetPlaybackSnapshot,
        palette: BreezeWidgetPalette,
        artwork: WidgetLoadedArtwork,
        widgetOptions: Bundle? = null
    ): RemoteViews {
        val minWidthDp = widgetOptions?.getInt("appWidgetMinWidth", 0) ?: 0
        val minHeightDp = widgetOptions?.getInt("appWidgetMinHeight", 0) ?: 0
        val bgAlpha = config.sanitizedBackgroundAlpha

        val layoutRes = when (sizeClass) {
            BreezeWidgetSizeClass.CAPSULE_4X1 -> R.layout.widget_breeze_compact
            BreezeWidgetSizeClass.SQUARE_2X2 -> R.layout.widget_breeze_square
            BreezeWidgetSizeClass.CARD_4X2 -> R.layout.widget_concept_music
            BreezeWidgetSizeClass.STAGE_4X4 -> R.layout.widget_breeze_stage
        }
        val views = RemoteViews(context.packageName, layoutRes)

        // 1. 离屏精绘背景卡底
        val (bgW, bgH, bgCorner) = when (sizeClass) {
            BreezeWidgetSizeClass.CAPSULE_4X1 -> Triple(440, 108, 54f)
            BreezeWidgetSizeClass.SQUARE_2X2 -> Triple(260, 260, 44f)
            BreezeWidgetSizeClass.CARD_4X2 -> Triple(440, 212, 42f)
            BreezeWidgetSizeClass.STAGE_4X4 -> Triple(440, 440, 44f)
        }
        val bgBitmap = BreezeWidgetRenderer.renderCardBackground(
            widthPx = bgW,
            heightPx = bgH,
            cornerRadiusPx = bgCorner,
            style = config.style,
            palette = palette,
            coverBitmap = artwork.primaryCover,
            backgroundAlpha = bgAlpha
        )
        views.setImageViewBitmap(R.id.widget_bg_image, bgBitmap)

        // 2. 离屏精绘核心舞台图（黑胶封套 / 菱形光晕封面 / 水彩队列叠卡）
        val stageBitmap = BreezeWidgetRenderer.renderArtworkStage(
            sizeClass = sizeClass,
            style = config.style,
            primaryCover = artwork.primaryCover,
            nextCover1 = artwork.nextCover1,
            nextCover2 = artwork.nextCover2,
            palette = palette,
            isPlaying = snapshot.isPlaying
        )
        views.setImageViewBitmap(R.id.widget_artwork_stage, stageBitmap)

        // 3. 文本内容与主题对比度着色
        val displayTitle = snapshot.title.ifBlank { BreezeWidgetPlaybackSnapshot.DEFAULT_IDLE_TITLE }
        val displayArtist = snapshot.artist.ifBlank { BreezeWidgetPlaybackSnapshot.DEFAULT_IDLE_SUBTITLE }
        val displayLyric = when {
            snapshot.currentLyric.isNotBlank() -> snapshot.currentLyric
            !snapshot.hasActiveSong -> BreezeWidgetPlaybackSnapshot.DEFAULT_IDLE_LYRIC
            else -> ""
        }

        views.setTextViewText(R.id.widget_song_title, displayTitle)
        views.setTextColor(R.id.widget_song_title, palette.primaryTextArgb)

        views.setTextViewText(R.id.widget_song_artist, displayArtist)
        views.setTextColor(R.id.widget_song_artist, palette.secondaryTextArgb)

        // 4. 分尺寸填充专属元素与控钮
        val showQuality = config.showQualityBadge && snapshot.qualityBadge.isNotBlank()
        val showProgress = config.showProgressBar && snapshot.hasActiveSong

        when (sizeClass) {
            BreezeWidgetSizeClass.CAPSULE_4X1 -> {
                // 4×1：若开启歌词且有当前歌词，副标题智能切为高亮歌词；否则展示歌手名
                val useLyricAsSubtitle = config.showLyric && snapshot.currentLyric.isNotBlank()
                views.setTextViewText(
                    R.id.widget_song_artist,
                    if (useLyricAsSubtitle) snapshot.currentLyric else displayArtist
                )
                views.setTextColor(
                    R.id.widget_song_artist,
                    if (useLyricAsSubtitle) palette.lyricActiveTextArgb else palette.secondaryTextArgb
                )

                bindQualityBadge(views, showQuality && minWidthDp !in 1..220, snapshot.qualityBadge, palette)

                val playBmp = BreezeWidgetRenderer.renderPlayButton(
                    sizePx = 92,
                    isPlaying = snapshot.isPlaying,
                    palette = palette,
                    withProgressRing = config.showProgressBar,
                    progressFraction = snapshot.progressFraction
                )
                val prevBmp = BreezeWidgetRenderer.renderControlButton(
                    icon = BreezeWidgetControlIcon.PREV,
                    sizePx = 72,
                    palette = palette,
                    withSubtleCircleBg = false
                )
                val nextBmp = BreezeWidgetRenderer.renderControlButton(
                    icon = BreezeWidgetControlIcon.NEXT,
                    sizePx = 72,
                    palette = palette,
                    withSubtleCircleBg = false
                )
                views.setImageViewBitmap(R.id.widget_btn_play, playBmp)
                views.setImageViewBitmap(R.id.widget_btn_prev, prevBmp)
                views.setImageViewBitmap(R.id.widget_btn_next, nextBmp)

                bindBroadcastClick(context, views, R.id.widget_btn_prev, providerClass, BreezeWidgetActions.ACTION_PREV, appWidgetId, 11)
                bindBroadcastClick(context, views, R.id.widget_btn_play, providerClass, BreezeWidgetActions.ACTION_TOGGLE_PLAY, appWidgetId, 12)
                bindBroadcastClick(context, views, R.id.widget_btn_next, providerClass, BreezeWidgetActions.ACTION_NEXT, appWidgetId, 13)
            }

            BreezeWidgetSizeClass.SQUARE_2X2 -> {
                val playBmp = BreezeWidgetRenderer.renderPlayButton(
                    sizePx = 96,
                    isPlaying = snapshot.isPlaying,
                    palette = palette,
                    withProgressRing = config.showProgressBar && snapshot.hasActiveSong,
                    progressFraction = snapshot.progressFraction
                )
                views.setImageViewBitmap(R.id.widget_btn_play, playBmp)
                bindBroadcastClick(context, views, R.id.widget_btn_play, providerClass, BreezeWidgetActions.ACTION_TOGGLE_PLAY, appWidgetId, 21)
            }

            BreezeWidgetSizeClass.CARD_4X2 -> {
                bindQualityBadge(views, showQuality, snapshot.qualityBadge, palette)

                val compactHeight = minHeightDp in 1..95
                val showLyricNow = config.showLyric && displayLyric.isNotBlank() && !compactHeight
                views.setViewVisibility(R.id.widget_lyric_line, if (showLyricNow) View.VISIBLE else View.GONE)
                if (showLyricNow) {
                    views.setTextViewText(R.id.widget_lyric_line, displayLyric)
                    views.setTextColor(R.id.widget_lyric_line, palette.lyricActiveTextArgb)
                }

                val showProgressNow = showProgress && !compactHeight
                views.setViewVisibility(R.id.widget_progress_image, if (showProgressNow) View.VISIBLE else View.GONE)
                if (showProgressNow) {
                    views.setImageViewBitmap(
                        R.id.widget_progress_image,
                        BreezeWidgetRenderer.renderProgressBar(340, 14, snapshot.progressFraction, palette)
                    )
                }

                val favIcon = if (snapshot.isFavorite) {
                    BreezeWidgetControlIcon.FAVORITE_ON
                } else {
                    BreezeWidgetControlIcon.FAVORITE_OFF
                }
                views.setImageViewBitmap(
                    R.id.widget_btn_favorite,
                    BreezeWidgetRenderer.renderControlButton(favIcon, 76, palette, withSubtleCircleBg = true)
                )
                views.setImageViewBitmap(
                    R.id.widget_btn_prev,
                    BreezeWidgetRenderer.renderControlButton(BreezeWidgetControlIcon.PREV, 76, palette, withSubtleCircleBg = true)
                )
                views.setImageViewBitmap(
                    R.id.widget_btn_play,
                    BreezeWidgetRenderer.renderPlayButton(102, snapshot.isPlaying, palette)
                )
                views.setImageViewBitmap(
                    R.id.widget_btn_next,
                    BreezeWidgetRenderer.renderControlButton(BreezeWidgetControlIcon.NEXT, 76, palette, withSubtleCircleBg = true)
                )

                bindBroadcastClick(context, views, R.id.widget_btn_favorite, providerClass, BreezeWidgetActions.ACTION_TOGGLE_FAVORITE, appWidgetId, 30)
                bindBroadcastClick(context, views, R.id.widget_btn_prev, providerClass, BreezeWidgetActions.ACTION_PREV, appWidgetId, 31)
                bindBroadcastClick(context, views, R.id.widget_btn_play, providerClass, BreezeWidgetActions.ACTION_TOGGLE_PLAY, appWidgetId, 32)
                bindBroadcastClick(context, views, R.id.widget_btn_next, providerClass, BreezeWidgetActions.ACTION_NEXT, appWidgetId, 33)
            }

            BreezeWidgetSizeClass.STAGE_4X4 -> {
                bindQualityBadge(views, showQuality, snapshot.qualityBadge, palette)

                val showLyricsBlock = config.showLyric
                views.setViewVisibility(R.id.widget_lyric_prev, if (showLyricsBlock && snapshot.prevLyric.isNotBlank()) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.widget_lyric_line, if (showLyricsBlock && displayLyric.isNotBlank()) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.widget_lyric_next, if (showLyricsBlock && snapshot.nextLyric.isNotBlank()) View.VISIBLE else View.GONE)

                if (showLyricsBlock) {
                    views.setTextViewText(R.id.widget_lyric_prev, snapshot.prevLyric)
                    views.setTextColor(R.id.widget_lyric_prev, palette.lyricDimTextArgb)
                    views.setTextViewText(R.id.widget_lyric_line, displayLyric)
                    views.setTextColor(R.id.widget_lyric_line, palette.lyricActiveTextArgb)
                    views.setTextViewText(R.id.widget_lyric_next, snapshot.nextLyric)
                    views.setTextColor(R.id.widget_lyric_next, palette.lyricDimTextArgb)
                }

                views.setViewVisibility(R.id.widget_progress_image, if (showProgress) View.VISIBLE else View.GONE)
                if (showProgress) {
                    views.setImageViewBitmap(
                        R.id.widget_progress_image,
                        BreezeWidgetRenderer.renderProgressBar(390, 16, snapshot.progressFraction, palette)
                    )
                }

                val modeIcon = when (snapshot.playMode) {
                    PlayMode.REPEAT_ALL -> BreezeWidgetControlIcon.PLAY_MODE_LIST
                    PlayMode.REPEAT_ONE -> BreezeWidgetControlIcon.PLAY_MODE_SINGLE
                    PlayMode.SHUFFLE -> BreezeWidgetControlIcon.PLAY_MODE_SHUFFLE
                }
                val favIcon = if (snapshot.isFavorite) {
                    BreezeWidgetControlIcon.FAVORITE_ON
                } else {
                    BreezeWidgetControlIcon.FAVORITE_OFF
                }
                views.setImageViewBitmap(
                    R.id.widget_btn_play_mode,
                    BreezeWidgetRenderer.renderControlButton(modeIcon, 78, palette, withSubtleCircleBg = true)
                )
                views.setImageViewBitmap(
                    R.id.widget_btn_prev,
                    BreezeWidgetRenderer.renderControlButton(BreezeWidgetControlIcon.PREV, 80, palette, withSubtleCircleBg = true)
                )
                views.setImageViewBitmap(
                    R.id.widget_btn_play,
                    BreezeWidgetRenderer.renderPlayButton(112, snapshot.isPlaying, palette)
                )
                views.setImageViewBitmap(
                    R.id.widget_btn_next,
                    BreezeWidgetRenderer.renderControlButton(BreezeWidgetControlIcon.NEXT, 80, palette, withSubtleCircleBg = true)
                )
                views.setImageViewBitmap(
                    R.id.widget_btn_favorite,
                    BreezeWidgetRenderer.renderControlButton(favIcon, 78, palette, withSubtleCircleBg = true)
                )

                bindBroadcastClick(context, views, R.id.widget_btn_play_mode, providerClass, BreezeWidgetActions.ACTION_CYCLE_PLAY_MODE, appWidgetId, 40)
                bindBroadcastClick(context, views, R.id.widget_btn_prev, providerClass, BreezeWidgetActions.ACTION_PREV, appWidgetId, 41)
                bindBroadcastClick(context, views, R.id.widget_btn_play, providerClass, BreezeWidgetActions.ACTION_TOGGLE_PLAY, appWidgetId, 42)
                bindBroadcastClick(context, views, R.id.widget_btn_next, providerClass, BreezeWidgetActions.ACTION_NEXT, appWidgetId, 43)
                bindBroadcastClick(context, views, R.id.widget_btn_favorite, providerClass, BreezeWidgetActions.ACTION_TOGGLE_FAVORITE, appWidgetId, 44)
            }
        }

        // 5. 卡片主区域点击直达全屏沉浸播放页（附 256-bit 内部 IPC 能力令牌）
        val openNowPlayingPending = buildOpenNowPlayingPendingIntent(context, appWidgetId, sizeClass.ordinal)
        views.setOnClickPendingIntent(R.id.widget_root, openNowPlayingPending)
        views.setOnClickPendingIntent(R.id.widget_artwork_stage, openNowPlayingPending)

        return views
    }

    private fun bindQualityBadge(
        views: RemoteViews,
        visible: Boolean,
        badgeText: String,
        palette: BreezeWidgetPalette
    ) {
        views.setViewVisibility(R.id.widget_quality_badge_img, if (visible) View.VISIBLE else View.GONE)
        if (visible) {
            views.setImageViewBitmap(
                R.id.widget_quality_badge_img,
                BreezeWidgetRenderer.renderQualityBadge(badgeText, palette, heightPx = 32)
            )
        }
    }

    private fun buildOpenNowPlayingPendingIntent(
        context: Context,
        appWidgetId: Int,
        slotOffset: Int
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = BreezeWidgetActions.ACTION_OPEN_NOW_PLAYING
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(BreezeWidgetActions.EXTRA_OPEN_NOW_PLAYING, true)
            SecurityGuards.signInternalIntent(context, this)
        }
        val reqCode = 30_000 + (appWidgetId and 0x0FFF) * 8 + slotOffset
        return PendingIntent.getActivity(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun bindBroadcastClick(
        context: Context,
        views: RemoteViews,
        viewId: Int,
        providerClass: Class<*>,
        action: String,
        appWidgetId: Int,
        actionSlot: Int
    ) {
        val targetClass = if (providerClass == ConceptMusicWidgetProvider::class.java) {
            ConceptMusicWidgetProvider::class.java
        } else {
            providerClass
        }
        val intent = Intent(context, targetClass).apply {
            this.action = action
            setPackage(context.packageName)
            SecurityGuards.signInternalIntent(context, this)
        }
        val reqCode = 10_000 + (appWidgetId and 0x0FFF) * 64 + actionSlot
        val pending = PendingIntent.getBroadcast(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(viewId, pending)
    }
}
