package com.concept.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 车机独立包四大自适应窗口形态：
 * 1. ULTRA_WIDE_COCKPIT：21:9 / 8:3 超宽带鱼屏，45:55 居中限宽画布防拉伸；
 * 2. STANDARD_LANDSCAPE_COCKPIT：主流 16:9 / 16:10 中控横屏，经典 45:55 非对称驾驶舞台；
 * 3. PORTRAIT_COCKPIT：比亚迪 DiLink 旋转竖屏 / 大尺寸竖屏，上封面盲操 + 下巨幕歌词；
 * 4. COMPACT_SPLIT_CARD：高德/百度 1/3 或 1/2 导航分屏及窄小窗，自动折叠为极简高对比度竖向悬浮卡。
 */
enum class CarCockpitLayoutMode {
    ULTRA_WIDE_COCKPIT,
    STANDARD_LANDSCAPE_COCKPIT,
    PORTRAIT_COCKPIT,
    COMPACT_SPLIT_CARD
}

@Immutable
data class CarCockpitLayoutSpec(
    val mode: CarCockpitLayoutMode,
    val widthDp: Int,
    val heightDp: Int,
    val leftPaneWeight: Float,
    val maxStageWidth: Dp,
    val primaryPlayButtonDp: Dp,
    val secondaryControlButtonDp: Dp,
    val lyricActiveFontSp: Float,
    val lyricInactiveFontSp: Float,
    val drawerWidthFraction: Float,
    val horizontalPadding: Dp,
    val verticalPadding: Dp
) {
    val isDualPaneLandscape: Boolean
        get() = mode == CarCockpitLayoutMode.STANDARD_LANDSCAPE_COCKPIT ||
            mode == CarCockpitLayoutMode.ULTRA_WIDE_COCKPIT
}

fun computeCarCockpitLayoutSpec(widthDp: Int, heightDp: Int): CarCockpitLayoutSpec {
    val safeW = widthDp.coerceAtLeast(260)
    val safeH = heightDp.coerceAtLeast(260)
    val isLandscape = safeW > safeH
    val aspectRatio = safeW.toFloat() / safeH.toFloat()

    val mode = when {
        isLandscape && safeW >= 720 && safeH >= 400 -> {
            if (aspectRatio >= 1.95f && safeW >= 1100) {
                CarCockpitLayoutMode.ULTRA_WIDE_COCKPIT
            } else {
                CarCockpitLayoutMode.STANDARD_LANDSCAPE_COCKPIT
            }
        }
        !isLandscape && safeW >= 560 && safeH >= 760 -> {
            CarCockpitLayoutMode.PORTRAIT_COCKPIT
        }
        else -> {
            CarCockpitLayoutMode.COMPACT_SPLIT_CARD
        }
    }

    return when (mode) {
        CarCockpitLayoutMode.ULTRA_WIDE_COCKPIT -> CarCockpitLayoutSpec(
            mode = mode,
            widthDp = safeW,
            heightDp = safeH,
            leftPaneWeight = 0.44f,
            maxStageWidth = 1220.dp,
            primaryPlayButtonDp = 82.dp,
            secondaryControlButtonDp = 68.dp,
            lyricActiveFontSp = 33f,
            lyricInactiveFontSp = 22f,
            drawerWidthFraction = 0.48f,
            horizontalPadding = 40.dp,
            verticalPadding = 24.dp
        )
        CarCockpitLayoutMode.STANDARD_LANDSCAPE_COCKPIT -> CarCockpitLayoutSpec(
            mode = mode,
            widthDp = safeW,
            heightDp = safeH,
            leftPaneWeight = 0.45f,
            maxStageWidth = Dp.Unspecified,
            primaryPlayButtonDp = if (safeH >= 600) 78.dp else 70.dp,
            secondaryControlButtonDp = if (safeH >= 600) 64.dp else 58.dp,
            lyricActiveFontSp = if (safeH >= 600) 31f else 27f,
            lyricInactiveFontSp = if (safeH >= 600) 21f else 18.5f,
            drawerWidthFraction = 0.54f,
            horizontalPadding = 28.dp,
            verticalPadding = 18.dp
        )
        CarCockpitLayoutMode.PORTRAIT_COCKPIT -> CarCockpitLayoutSpec(
            mode = mode,
            widthDp = safeW,
            heightDp = safeH,
            leftPaneWeight = 0.48f,
            maxStageWidth = 760.dp,
            primaryPlayButtonDp = 76.dp,
            secondaryControlButtonDp = 62.dp,
            lyricActiveFontSp = 29f,
            lyricInactiveFontSp = 20f,
            drawerWidthFraction = 0.86f,
            horizontalPadding = 28.dp,
            verticalPadding = 20.dp
        )
        CarCockpitLayoutMode.COMPACT_SPLIT_CARD -> CarCockpitLayoutSpec(
            mode = mode,
            widthDp = safeW,
            heightDp = safeH,
            leftPaneWeight = 1f,
            maxStageWidth = Dp.Unspecified,
            primaryPlayButtonDp = if (safeW >= 420) 68.dp else 60.dp,
            secondaryControlButtonDp = if (safeW >= 420) 54.dp else 48.dp,
            lyricActiveFontSp = if (safeW >= 420) 22f else 19f,
            lyricInactiveFontSp = 15.5f,
            drawerWidthFraction = 0.96f,
            horizontalPadding = 16.dp,
            verticalPadding = 14.dp
        )
    }
}

@Composable
fun rememberCarCockpitLayoutSpec(): CarCockpitLayoutSpec {
    val cfg = LocalConfiguration.current
    val w = cfg.screenWidthDp
    val h = cfg.screenHeightDp
    return remember(w, h) {
        computeCarCockpitLayoutSpec(widthDp = w, heightDp = h)
    }
}
