package com.concept.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
        widgetOptions: Bundle? = null,
        stageLyricSlotIndex: Int = 0,
        maxBgEdgeOverridePx: Int? = null
    ): RemoteViews {
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val minW = widgetOptions?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val maxW = widgetOptions?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0
        val minH = widgetOptions?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        val maxH = widgetOptions?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
        val hostWidthDp = (if (isLandscape && maxW > 0) maxW else minW).takeIf { it > 0 } ?: maxW
        val hostHeightDp = (if (!isLandscape && maxH > 0) maxH else minH).takeIf { it > 0 } ?: maxH
        val bgAlpha = config.sanitizedBackgroundAlpha

        val layoutRes = when (sizeClass) {
            BreezeWidgetSizeClass.CAPSULE_4X1 -> R.layout.widget_breeze_compact
            BreezeWidgetSizeClass.SQUARE_2X2 -> R.layout.widget_breeze_square
            BreezeWidgetSizeClass.CARD_4X2 -> R.layout.widget_concept_music
            BreezeWidgetSizeClass.STAGE_4X4 -> R.layout.widget_breeze_stage
        }
        val views = RemoteViews(context.packageName, layoutRes)

        // 1. 离屏精绘等比克制圆角卡底（按桌面网格当前横竖屏实时宽高比动态匹配，杜绝拉伸圆角变形）
        val rawBgSpec = resolveWidgetBackgroundSpec(
            sizeClass = sizeClass,
            hostWidthDp = hostWidthDp,
            hostHeightDp = hostHeightDp
        )
        val bgScaleDown = if (maxBgEdgeOverridePx != null) {
            val maxSide = maxOf(rawBgSpec.widthPx, rawBgSpec.heightPx).coerceAtLeast(1)
            if (maxSide > maxBgEdgeOverridePx) maxBgEdgeOverridePx.toFloat() / maxSide.toFloat() else 1f
        } else 1f
        val bgBitmap = BreezeWidgetRenderer.renderCardBackground(
            widthPx = (rawBgSpec.widthPx * bgScaleDown).toInt().coerceAtLeast(64),
            heightPx = (rawBgSpec.heightPx * bgScaleDown).toInt().coerceAtLeast(48),
            cornerRadiusPx = rawBgSpec.cornerRadiusPx * bgScaleDown,
            style = config.style,
            palette = palette,
            coverBitmap = artwork.primaryCover,
            backgroundAlpha = bgAlpha,
            isStage4x4 = sizeClass == BreezeWidgetSizeClass.STAGE_4X4
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
        val displayLyric = resolveDisplayLyric(snapshot)

        views.setTextViewText(R.id.widget_song_title, displayTitle)
        views.setTextColor(R.id.widget_song_title, palette.primaryTextArgb)

        views.setTextViewText(R.id.widget_song_artist, displayArtist)
        views.setTextColor(R.id.widget_song_artist, palette.secondaryTextArgb)

        // 4. 分尺寸填充专属元素与圆润饱满控钮
        val showQuality = config.showQualityBadge && snapshot.qualityBadge.isNotBlank()
        val showProgress = config.showProgressBar && snapshot.hasActiveSong

        when (sizeClass) {
            BreezeWidgetSizeClass.CAPSULE_4X1 -> {
                val useLyricAsSubtitle = config.showLyric && snapshot.currentLyric.isNotBlank()
                views.setTextViewText(
                    R.id.widget_song_artist,
                    if (useLyricAsSubtitle) snapshot.currentLyric else displayArtist
                )
                views.setTextColor(
                    R.id.widget_song_artist,
                    if (useLyricAsSubtitle) palette.lyricActiveTextArgb else palette.secondaryTextArgb
                )

                bindQualityBadge(views, showQuality && hostWidthDp !in 1..220, snapshot.qualityBadge, palette)

                val playBmp = BreezeWidgetRenderer.renderPlayButton(
                    sizePx = 104,
                    isPlaying = snapshot.isPlaying,
                    palette = palette,
                    withProgressRing = config.showProgressBar,
                    progressFraction = snapshot.progressFraction
                )
                val prevBmp = BreezeWidgetRenderer.renderControlButton(
                    icon = BreezeWidgetControlIcon.PREV,
                    sizePx = 80,
                    palette = palette,
                    withSubtleCircleBg = true,
                    softBackgroundOnly = true
                )
                val nextBmp = BreezeWidgetRenderer.renderControlButton(
                    icon = BreezeWidgetControlIcon.NEXT,
                    sizePx = 80,
                    palette = palette,
                    withSubtleCircleBg = true,
                    softBackgroundOnly = true
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
                    sizePx = 112,
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

                val compactHeight = hostHeightDp in 1..95
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
                        BreezeWidgetRenderer.renderProgressBar(360, 18, snapshot.progressFraction, palette)
                    )
                }

                val favIcon = if (snapshot.isFavorite) {
                    BreezeWidgetControlIcon.FAVORITE_ON
                } else {
                    BreezeWidgetControlIcon.FAVORITE_OFF
                }
                views.setImageViewBitmap(
                    R.id.widget_btn_favorite,
                    BreezeWidgetRenderer.renderControlButton(favIcon, 86, palette, withSubtleCircleBg = true)
                )
                views.setImageViewBitmap(
                    R.id.widget_btn_prev,
                    BreezeWidgetRenderer.renderControlButton(BreezeWidgetControlIcon.PREV, 86, palette, withSubtleCircleBg = true)
                )
                views.setImageViewBitmap(
                    R.id.widget_btn_play,
                    BreezeWidgetRenderer.renderPlayButton(114, snapshot.isPlaying, palette)
                )
                views.setImageViewBitmap(
                    R.id.widget_btn_next,
                    BreezeWidgetRenderer.renderControlButton(BreezeWidgetControlIcon.NEXT, 86, palette, withSubtleCircleBg = true)
                )

                bindBroadcastClick(context, views, R.id.widget_btn_favorite, providerClass, BreezeWidgetActions.ACTION_TOGGLE_FAVORITE, appWidgetId, 30)
                bindBroadcastClick(context, views, R.id.widget_btn_prev, providerClass, BreezeWidgetActions.ACTION_PREV, appWidgetId, 31)
                bindBroadcastClick(context, views, R.id.widget_btn_play, providerClass, BreezeWidgetActions.ACTION_TOGGLE_PLAY, appWidgetId, 32)
                bindBroadcastClick(context, views, R.id.widget_btn_next, providerClass, BreezeWidgetActions.ACTION_NEXT, appWidgetId, 33)
            }

            BreezeWidgetSizeClass.STAGE_4X4 -> {
                bindQualityBadge(views, showQuality, snapshot.qualityBadge, palette, heightPx = 34)

                // 上排右侧专辑副标题（有专辑信息时展示，清爽不堆砌）
                val albumText = snapshot.album.trim()
                val showAlbum = albumText.isNotBlank() && albumText != displayTitle
                views.setViewVisibility(R.id.widget_song_album, if (showAlbum) View.VISIBLE else View.GONE)
                if (showAlbum) {
                    views.setTextViewText(R.id.widget_song_album, albumText)
                    views.setTextColor(R.id.widget_song_album, palette.lyricDimTextArgb)
                }

                // 中央双槽 ViewFlipper 沉浸歌词舞台：全量刷新时静默同步当前双槽，避免进度刷新误触滚动动画
                val compactStageHeight = hostHeightDp in 1..270
                val safeSlot = stageLyricSlotIndex and 1
                bindStageLyricSlot(views, slot = 0, config = config, snapshot = snapshot, palette = palette, compactStageHeight = compactStageHeight)
                bindStageLyricSlot(views, slot = 1, config = config, snapshot = snapshot, palette = palette, compactStageHeight = compactStageHeight)
                views.setDisplayedChild(R.id.widget_lyric_flipper, safeSlot)

                // 进度条 + 等宽数字时间轴
                views.setViewVisibility(R.id.widget_progress_row, if (showProgress) View.VISIBLE else View.GONE)
                if (showProgress) {
                    views.setTextViewText(R.id.widget_time_current, formatWidgetDuration(snapshot.positionMs))
                    views.setTextColor(R.id.widget_time_current, palette.lyricDimTextArgb)
                    views.setImageViewBitmap(
                        R.id.widget_progress_image,
                        BreezeWidgetRenderer.renderProgressBar(380, 20, snapshot.progressFraction, palette)
                    )
                    views.setTextViewText(R.id.widget_time_total, formatWidgetDuration(snapshot.durationMs))
                    views.setTextColor(R.id.widget_time_total, palette.lyricDimTextArgb)
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
                    BreezeWidgetRenderer.renderControlButton(modeIcon, 92, palette, withSubtleCircleBg = true)
                )
                views.setImageViewBitmap(
                    R.id.widget_btn_prev,
                    BreezeWidgetRenderer.renderControlButton(BreezeWidgetControlIcon.PREV, 98, palette, withSubtleCircleBg = true)
                )
                views.setImageViewBitmap(
                    R.id.widget_btn_play,
                    BreezeWidgetRenderer.renderPlayButton(128, snapshot.isPlaying, palette)
                )
                views.setImageViewBitmap(
                    R.id.widget_btn_next,
                    BreezeWidgetRenderer.renderControlButton(BreezeWidgetControlIcon.NEXT, 98, palette, withSubtleCircleBg = true)
                )
                views.setImageViewBitmap(
                    R.id.widget_btn_favorite,
                    BreezeWidgetRenderer.renderControlButton(favIcon, 92, palette, withSubtleCircleBg = true)
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

    /**
     * 轻量局部歌词刷新 RemoteViews（专供 AppWidgetManager.partiallyUpdateAppWidget 使用）：
     * - 4×4 仅写入非活跃缓冲槽并切换 ViewFlipper，触发原生 GPU 平滑上推滚动动效，背景/封面/按钮零闪烁、~0.5ms 完成；
     * - 4×2 / 4×1 仅替换单行歌词文本，极大降低高频换行功耗。
     */
    fun buildPartialLyricUpdate(
        context: Context,
        sizeClass: BreezeWidgetSizeClass,
        config: BreezeWidgetConfig,
        snapshot: BreezeWidgetPlaybackSnapshot,
        palette: BreezeWidgetPalette,
        widgetOptions: Bundle?,
        targetStageSlotIndex: Int
    ): RemoteViews? {
        if (!config.showLyric) return null
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val minH = widgetOptions?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        val maxH = widgetOptions?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
        val hostHeightDp = (if (!isLandscape && maxH > 0) maxH else minH).takeIf { it > 0 } ?: maxH
        val displayLyric = resolveDisplayLyric(snapshot)

        return when (sizeClass) {
            BreezeWidgetSizeClass.STAGE_4X4 -> {
                val views = RemoteViews(context.packageName, R.layout.widget_breeze_stage)
                val compactStageHeight = hostHeightDp in 1..270
                val nextSlot = targetStageSlotIndex and 1
                bindStageLyricSlot(
                    views = views,
                    slot = nextSlot,
                    config = config,
                    snapshot = snapshot,
                    palette = palette,
                    compactStageHeight = compactStageHeight
                )
                views.setDisplayedChild(R.id.widget_lyric_flipper, nextSlot)
                views
            }
            BreezeWidgetSizeClass.CARD_4X2 -> {
                val compactHeight = hostHeightDp in 1..95
                val views = RemoteViews(context.packageName, R.layout.widget_concept_music)
                val showNow = displayLyric.isNotBlank() && !compactHeight
                views.setViewVisibility(R.id.widget_lyric_line, if (showNow) View.VISIBLE else View.GONE)
                if (showNow) {
                    views.setTextViewText(R.id.widget_lyric_line, displayLyric)
                    views.setTextColor(R.id.widget_lyric_line, palette.lyricActiveTextArgb)
                }
                views
            }
            BreezeWidgetSizeClass.CAPSULE_4X1 -> {
                val views = RemoteViews(context.packageName, R.layout.widget_breeze_compact)
                val useLyric = snapshot.currentLyric.isNotBlank()
                val displayArtist = snapshot.artist.ifBlank { BreezeWidgetPlaybackSnapshot.DEFAULT_IDLE_SUBTITLE }
                views.setTextViewText(
                    R.id.widget_song_artist,
                    if (useLyric) snapshot.currentLyric else displayArtist
                )
                views.setTextColor(
                    R.id.widget_song_artist,
                    if (useLyric) palette.lyricActiveTextArgb else palette.secondaryTextArgb
                )
                views
            }
            BreezeWidgetSizeClass.SQUARE_2X2 -> null
        }
    }

    /**
     * 返回纯静态零 Bitmap 预览 RemoteViews（仅引用轻量 widget_preview_* XML，杜绝 Binder 载荷过大）。
     */
    fun buildStaticPinPreview(
        context: Context,
        sizeClass: BreezeWidgetSizeClass
    ): RemoteViews {
        val previewLayoutRes = when (sizeClass) {
            BreezeWidgetSizeClass.CAPSULE_4X1 -> R.layout.widget_preview_4x1
            BreezeWidgetSizeClass.SQUARE_2X2 -> R.layout.widget_preview_2x2
            BreezeWidgetSizeClass.CARD_4X2 -> R.layout.widget_preview_4x2
            BreezeWidgetSizeClass.STAGE_4X4 -> R.layout.widget_preview_4x4
        }
        return RemoteViews(context.packageName, previewLayoutRes)
    }

    private fun resolveDisplayLyric(snapshot: BreezeWidgetPlaybackSnapshot): String = when {
        snapshot.currentLyric.isNotBlank() -> snapshot.currentLyric
        !snapshot.hasActiveSong -> BreezeWidgetPlaybackSnapshot.DEFAULT_IDLE_LYRIC
        else -> ""
    }

    private fun bindStageLyricSlot(
        views: RemoteViews,
        slot: Int,
        config: BreezeWidgetConfig,
        snapshot: BreezeWidgetPlaybackSnapshot,
        palette: BreezeWidgetPalette,
        compactStageHeight: Boolean
    ) {
        val prevId = if (slot == 0) R.id.widget_lyric_prev_0 else R.id.widget_lyric_prev_1
        val activeRowId = if (slot == 0) R.id.widget_lyric_active_row_0 else R.id.widget_lyric_active_row_1
        val barId = if (slot == 0) R.id.widget_lyric_accent_bar_0 else R.id.widget_lyric_accent_bar_1
        val curId = if (slot == 0) R.id.widget_lyric_line_0 else R.id.widget_lyric_line_1
        val nextId = if (slot == 0) R.id.widget_lyric_next_0 else R.id.widget_lyric_next_1

        val displayLyric = resolveDisplayLyric(snapshot)
        val showLyricsBlock = config.showLyric
        val showPrev = showLyricsBlock && !compactStageHeight && snapshot.prevLyric.isNotBlank()
        val showCur = showLyricsBlock && displayLyric.isNotBlank()
        val showNext = showLyricsBlock && !compactStageHeight && snapshot.nextLyric.isNotBlank()

        views.setViewVisibility(prevId, if (showPrev) View.VISIBLE else View.GONE)
        views.setViewVisibility(activeRowId, if (showCur) View.VISIBLE else View.GONE)
        views.setViewVisibility(nextId, if (showNext) View.VISIBLE else View.GONE)

        if (showPrev) {
            views.setTextViewText(prevId, snapshot.prevLyric)
            views.setTextColor(prevId, palette.lyricDimTextArgb)
        }
        if (showCur) {
            views.setImageViewBitmap(barId, BreezeWidgetRenderer.renderLyricAccentBar(palette))
            views.setTextViewText(curId, displayLyric)
            views.setTextColor(curId, palette.lyricActiveTextArgb)
        }
        if (showNext) {
            views.setTextViewText(nextId, snapshot.nextLyric)
            views.setTextColor(nextId, palette.lyricDimTextArgb)
        }
    }

    private fun bindQualityBadge(
        views: RemoteViews,
        visible: Boolean,
        badgeText: String,
        palette: BreezeWidgetPalette,
        heightPx: Int = 32
    ) {
        views.setViewVisibility(R.id.widget_quality_badge_img, if (visible) View.VISIBLE else View.GONE)
        if (visible) {
            views.setImageViewBitmap(
                R.id.widget_quality_badge_img,
                BreezeWidgetRenderer.renderQualityBadge(badgeText, palette, heightPx = heightPx)
            )
        }
    }

    private fun buildOpenNowPlayingPendingIntent(
        context: Context,
        appWidgetId: Int,
        slotOffset: Int
    ): PendingIntent {
        // 配合 MainActivity 的 singleTask 启动模式：仅唤醒已有任务栈并走 onNewIntent，
        // 去掉 FLAG_ACTIVITY_CLEAR_TOP，彻底避免 ColorOS / Android 14+ 清理栈或按返回直退桌面
        val intent = Intent(context, MainActivity::class.java).apply {
            action = BreezeWidgetActions.ACTION_OPEN_NOW_PLAYING
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
