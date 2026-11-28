package com.concept.music.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.concept.music.util.SecurityGuards

/**
 * 微风音乐四尺寸桌面小组件统一安全基类：
 * - 仅对外开放系统 APPWIDGET_UPDATE；
 * - 全部自定义控制指令严格经由 SecurityGuards 256-bit 私有能力令牌验权，阻断外部应用伪造广播。
 */
abstract class BaseBreezeWidgetProvider(
    private val sizeClass: BreezeWidgetSizeClass
) : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        BreezeWidgetManager.onProviderWidgetsUpdated(context, sizeClass)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        BreezeWidgetManager.onProviderWidgetsUpdated(context, sizeClass)
        BreezeWidgetManager.requestUpdateProvider(
            context = context,
            sizeClass = sizeClass,
            providerClass = this::class.java,
            appWidgetIds = appWidgetIds
        )
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        BreezeWidgetManager.requestUpdateSingle(
            context = context,
            sizeClass = sizeClass,
            providerClass = this::class.java,
            appWidgetId = appWidgetId,
            options = newOptions
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            super.onReceive(context, intent)
            val action = intent.action ?: return
            when (action) {
                BreezeWidgetActions.ACTION_WIDGET_PINNED -> {
                    if (SecurityGuards.verifyInternalIntent(context, intent)) {
                        BreezeWidgetManager.onWidgetPinnedConfirmed(context, sizeClass)
                    }
                }
                BreezeWidgetActions.ACTION_TOGGLE_PLAY,
                BreezeWidgetActions.ACTION_NEXT,
                BreezeWidgetActions.ACTION_PREV,
                BreezeWidgetActions.ACTION_TOGGLE_FAVORITE,
                BreezeWidgetActions.ACTION_CYCLE_PLAY_MODE -> {
                    if (SecurityGuards.verifyInternalIntent(context, intent)) {
                        BreezeWidgetManager.dispatchCommand(context, action)
                    }
                }
            }
        }
    }
}

/** 4×1 灵动胶囊条桌面小组件（MiniPlayer 同源极简风） */
class BreezeCompactWidgetProvider : BaseBreezeWidgetProvider(BreezeWidgetSizeClass.CAPSULE_4X1)

/** 2×2 精致方卡桌面小组件 */
class BreezeSquareWidgetProvider : BaseBreezeWidgetProvider(BreezeWidgetSizeClass.SQUARE_2X2)

/** 4×4 大屏与平板沉浸歌词舞台桌面小组件 */
class BreezeStageWidgetProvider : BaseBreezeWidgetProvider(BreezeWidgetSizeClass.STAGE_4X4)
