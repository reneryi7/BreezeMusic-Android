package com.concept.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.concept.music.ui.theme.LocalIsDarkTheme

/**
 * 「我的」页 2×2 核心快捷区与 VIP 权益中心的无边框柔光实心矢量图标库：
 * - 彻底摒弃生硬矩形外边框与单薄描边线条；
 * - 采用无边框弥散渐变底衬 + 饱满现代双色实心几何造型 + 柔和内高光，深浅双主题均温润高级。
 */
enum class LibraryShortcutGlyph {
    FAVORITE,
    HISTORY,
    CLOUD_DISK,
    LISTEN_RANK
}

@Composable
fun RefinedShortcutBadge(
    glyph: LibraryShortcutGlyph,
    accentColor: Color,
    size: Dp = 42.dp,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val baseBg = if (isDark) {
        accentColor.copy(alpha = 0.14f)
    } else {
        accentColor.copy(alpha = 0.11f)
    }

    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(14.dp),
        color = baseBg,
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = this.size.width
                val h = this.size.height

                // 无边框柔光弥散背景（左上微亮 -> 右下沉稳，无任何描边外框）
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            accentColor.copy(alpha = if (isDark) 0.22f else 0.16f),
                            accentColor.copy(alpha = if (isDark) 0.06f else 0.04f)
                        ),
                        start = Offset.Zero,
                        end = Offset(w, h)
                    ),
                    cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx())
                )

                when (glyph) {
                    LibraryShortcutGlyph.FAVORITE -> drawSolidFavoriteGlyph(w, h, accentColor, isDark)
                    LibraryShortcutGlyph.HISTORY -> drawSolidHistoryGlyph(w, h, accentColor, isDark)
                    LibraryShortcutGlyph.CLOUD_DISK -> drawSolidCloudMusicGlyph(w, h, accentColor, isDark)
                    LibraryShortcutGlyph.LISTEN_RANK -> drawSolidListenRankGlyph(w, h, accentColor, isDark)
                }
            }
        }
    }
}

/**
 * 我喜欢：饱满双色渐变实心红心 + 温润高光弧
 */
private fun DrawScope.drawSolidFavoriteGlyph(
    w: Float,
    h: Float,
    accent: Color,
    isDark: Boolean
) {
    val brightTop = if (isDark) Color(0xFFFF85A8) else Color(0xFFFF6B93)
    val deepBottom = if (isDark) Color(0xFFE83A6F) else accent

    val heart = Path().apply {
        moveTo(w * 0.50f, h * 0.77f)
        cubicTo(w * 0.18f, h * 0.57f, w * 0.17f, h * 0.28f, w * 0.35f, h * 0.25f)
        cubicTo(w * 0.435f, h * 0.235f, w * 0.485f, h * 0.295f, w * 0.50f, h * 0.345f)
        cubicTo(w * 0.515f, h * 0.295f, w * 0.565f, h * 0.235f, w * 0.65f, h * 0.25f)
        cubicTo(w * 0.83f, h * 0.28f, w * 0.82f, h * 0.57f, w * 0.50f, h * 0.77f)
        close()
    }
    drawPath(
        path = heart,
        brush = Brush.linearGradient(
            colors = listOf(brightTop, deepBottom),
            start = Offset(w * 0.28f, h * 0.24f),
            end = Offset(w * 0.72f, h * 0.78f)
        )
    )

    // 左上柔和玻璃感高光短弧，增添饱满立体感
    val gloss = Path().apply {
        moveTo(w * 0.305f, h * 0.41f)
        cubicTo(w * 0.295f, h * 0.34f, w * 0.34f, h * 0.305f, w * 0.39f, h * 0.315f)
    }
    drawPath(
        path = gloss,
        color = Color.White.copy(alpha = 0.42f),
        style = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round)
    )
}

/**
 * 最近播放：饱满实心晴空蓝圆盘 + 醒目圆润亮色指针
 */
private fun DrawScope.drawSolidHistoryGlyph(
    w: Float,
    h: Float,
    accent: Color,
    isDark: Boolean
) {
    val lightBlue = if (isDark) Color(0xFF72BAFF) else Color(0xFF4BA2FF)
    val deepBlue = if (isDark) Color(0xFF2E7CE8) else accent
    val center = Offset(w * 0.50f, h * 0.50f)
    val r = w * 0.285f

    // 饱满实心渐变表盘
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(lightBlue, deepBlue),
            start = Offset(center.x - r, center.y - r),
            end = Offset(center.x + r, center.y + r)
        ),
        radius = r,
        center = center
    )

    // 轻微内圈光泽环
    drawCircle(
        color = Color.White.copy(alpha = 0.14f),
        radius = r * 0.74f,
        center = center,
        style = Stroke(width = 0.9.dp.toPx())
    )

    // 醒目圆角双指针
    val handColor = if (isDark) Color(0xFF0A192D) else Color.White
    val hands = Path().apply {
        moveTo(center.x, center.y - r * 0.48f)
        lineTo(center.x, center.y)
        lineTo(center.x + r * 0.40f, center.y + r * 0.22f)
    }
    drawPath(
        path = hands,
        color = handColor,
        style = Stroke(width = 2.25.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawCircle(
        color = handColor,
        radius = 1.8.dp.toPx(),
        center = center
    )
}

/**
 * 音乐云盘：饱满翡翠渐变实心云朵 + 云内精致镂空音符（告别细线框与雨滴竖线）
 */
private fun DrawScope.drawSolidCloudMusicGlyph(
    w: Float,
    h: Float,
    accent: Color,
    isDark: Boolean
) {
    val topMint = if (isDark) Color(0xFF5CEFD8) else Color(0xFF2CDAB9)
    val bottomTeal = if (isDark) Color(0xFF15B59B) else accent

    val cloud = Path().apply {
        moveTo(w * 0.30f, h * 0.71f)
        cubicTo(w * 0.16f, h * 0.71f, w * 0.14f, h * 0.50f, w * 0.275f, h * 0.465f)
        cubicTo(w * 0.30f, h * 0.285f, w * 0.535f, h * 0.24f, w * 0.625f, h * 0.385f)
        cubicTo(w * 0.72f, h * 0.365f, w * 0.82f, h * 0.45f, w * 0.805f, h * 0.565f)
        cubicTo(w * 0.84f, h * 0.635f, w * 0.785f, h * 0.71f, w * 0.695f, h * 0.71f)
        close()
    }
    drawPath(
        path = cloud,
        brush = Brush.linearGradient(
            colors = listOf(topMint, bottomTeal),
            start = Offset(w * 0.22f, h * 0.28f),
            end = Offset(w * 0.78f, h * 0.74f)
        )
    )

    // 云朵中央内嵌清晰音符
    val noteTint = if (isDark) Color(0xFF062520) else Color.White
    val stemX = w * 0.525f
    val headCenter = Offset(w * 0.475f, h * 0.605f)
    drawCircle(
        color = noteTint,
        radius = w * 0.055f,
        center = headCenter
    )
    drawLine(
        color = noteTint,
        start = Offset(stemX, h * 0.605f),
        end = Offset(stemX, h * 0.435f),
        strokeWidth = 2.0.dp.toPx(),
        cap = StrokeCap.Round
    )
    val flag = Path().apply {
        moveTo(stemX, h * 0.435f)
        quadraticBezierTo(w * 0.61f, h * 0.455f, w * 0.605f, h * 0.515f)
    }
    drawPath(
        path = flag,
        color = noteTint,
        style = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round)
    )
}

/**
 * 听歌排行：饱满流金圆角三阶领奖柱 + 柔和错落层次（无细瘦火柴棒感）
 */
private fun DrawScope.drawSolidListenRankGlyph(
    w: Float,
    h: Float,
    accent: Color,
    isDark: Boolean
) {
    val goldTop = if (isDark) Color(0xFFFFDF7A) else Color(0xFFFFBE3D)
    val goldDeep = if (isDark) Color(0xFFE0961A) else accent
    val sideMintGold = if (isDark) Color(0xFFFFCC5C) else accent

    val barW = w * 0.165f
    val radius = CornerRadius(barW * 0.45f, barW * 0.45f)
    val bottom = h * 0.75f

    // 左柱（No.2）
    val leftTop = h * 0.46f
    drawRoundRect(
        color = sideMintGold.copy(alpha = if (isDark) 0.58f else 0.52f),
        topLeft = Offset(w * 0.21f, leftTop),
        size = Size(barW, bottom - leftTop),
        cornerRadius = radius
    )
    // 中央冠军主柱（No.1，饱满流金渐变）
    val midTop = h * 0.28f
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(goldTop, goldDeep),
            startY = midTop,
            endY = bottom
        ),
        topLeft = Offset(w * 0.418f, midTop),
        size = Size(barW, bottom - midTop),
        cornerRadius = radius
    )
    // 右柱（No.3）
    val rightTop = h * 0.55f
    drawRoundRect(
        color = sideMintGold.copy(alpha = if (isDark) 0.38f else 0.36f),
        topLeft = Offset(w * 0.625f, rightTop),
        size = Size(barW, bottom - rightTop),
        cornerRadius = radius
    )
}

/**
 * VIP 权益中心左侧无边框实心立体流金皇冠徽章
 */
@Composable
fun RefinedVipCrownEmblem(
    size: Dp = 42.dp,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val goldBright = if (isDark) Color(0xFFFFE79A) else Color(0xFFFFC13B)
    val goldMid = if (isDark) Color(0xFFF3B73F) else Color(0xFFE49408)
    val goldDeep = if (isDark) Color(0xFFCA8312) else Color(0xFFB86B00)
    val bgTint = if (isDark) Color(0xFF2E2312) else Color(0xFFFFF3D4)

    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(14.dp),
        color = bgTint,
        shadowElevation = 0.dp
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height

            // 无边框香槟金柔和弥散底衬
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        goldBright.copy(alpha = if (isDark) 0.20f else 0.28f),
                        goldDeep.copy(alpha = if (isDark) 0.04f else 0.06f)
                    ),
                    start = Offset.Zero,
                    end = Offset(w, h)
                ),
                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx())
            )

            // 饱满实心流金皇冠主体
            val crown = Path().apply {
                moveTo(w * 0.23f, h * 0.68f)
                lineTo(w * 0.185f, h * 0.38f)
                lineTo(w * 0.365f, h * 0.495f)
                lineTo(w * 0.50f, h * 0.275f)
                lineTo(w * 0.635f, h * 0.495f)
                lineTo(w * 0.815f, h * 0.38f)
                lineTo(w * 0.77f, h * 0.68f)
                close()
            }
            drawPath(
                path = crown,
                brush = Brush.linearGradient(
                    colors = listOf(goldBright, goldMid, goldDeep),
                    start = Offset(w * 0.22f, h * 0.26f),
                    end = Offset(w * 0.78f, h * 0.74f)
                )
            )

            // 皇冠左半面轻盈高光切面，增强宝石立体光泽
            val highlightFacet = Path().apply {
                moveTo(w * 0.50f, h * 0.275f)
                lineTo(w * 0.365f, h * 0.495f)
                lineTo(w * 0.23f, h * 0.68f)
                lineTo(w * 0.50f, h * 0.68f)
                close()
            }
            drawPath(
                path = highlightFacet,
                color = Color.White.copy(alpha = if (isDark) 0.16f else 0.22f)
            )

            // 圆润底座托条与三颗饱满冠顶金珠
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(goldDeep, goldBright, goldDeep)),
                topLeft = Offset(w * 0.24f, h * 0.725f),
                size = Size(w * 0.52f, h * 0.068f),
                cornerRadius = CornerRadius(h * 0.034f, h * 0.034f)
            )
            drawCircle(color = goldBright, radius = w * 0.044f, center = Offset(w * 0.50f, h * 0.225f))
            drawCircle(color = goldBright, radius = w * 0.036f, center = Offset(w * 0.18f, h * 0.335f))
            drawCircle(color = goldBright, radius = w * 0.036f, center = Offset(w * 0.82f, h * 0.335f))
        }
    }
}
