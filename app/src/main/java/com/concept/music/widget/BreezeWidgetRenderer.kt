package com.concept.music.widget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.concept.music.ui.viewmodel.PlayMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class BreezeWidgetControlIcon {
    PREV,
    NEXT,
    FAVORITE_OFF,
    FAVORITE_ON,
    PLAY_MODE_LIST,
    PLAY_MODE_SINGLE,
    PLAY_MODE_SHUFFLE
}

/**
 * 原生 Android Canvas 离屏精绘引擎（供桌面 RemoteViews 与设置页「桌面小组件工坊」1:1 共用）：
 * - 全版本（Android 8–16）像素级精准绘制圆角卡底、幽邃微蓝/水彩浸润晕染、流光封面三色模糊底；
 * - 精准复刻三大 App 原生视觉图腾：黑胶封套舞台、旋转 45° 菱形棱镜光晕、队列错位水彩叠层；
 * - 彻底摒弃粗糙的系统默认图标，绘制微风圆润饱满几何控钮、柔光红心与胶囊进度条。
 */
internal object BreezeWidgetRenderer {

    /**
     * 绘制小组件全幅大圆角背景底图（严格控制像素规模，防止 RemoteViews Binder 内存超限）。
     */
    fun renderCardBackground(
        widthPx: Int,
        heightPx: Int,
        cornerRadiusPx: Float,
        style: BreezeWidgetStyle,
        palette: BreezeWidgetPalette,
        coverBitmap: Bitmap?,
        backgroundAlpha: Float
    ): Bitmap {
        val w = widthPx.coerceIn(64, 520)
        val h = heightPx.coerceIn(48, 520)
        val alphaFactor = backgroundAlpha.coerceIn(
            BreezeWidgetConfig.MIN_BG_ALPHA,
            BreezeWidgetConfig.MAX_BG_ALPHA
        )
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val radius = cornerRadiusPx.coerceIn(8f, min(w, h) * 0.5f)

        val clipPath = Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clipPath)

        // 1. 基底渐变画布
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                withAlpha(palette.cardBase, alphaFactor),
                withAlpha(palette.cardGradientEnd, alphaFactor),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, basePaint)

        when (style) {
            BreezeWidgetStyle.VINYL_SLEEVE -> {
                drawVinylSleeveBackdropGlows(canvas, w.toFloat(), h.toFloat(), palette, alphaFactor)
            }
            BreezeWidgetStyle.AURORA_COVER -> {
                drawAuroraBackdrop(canvas, w.toFloat(), h.toFloat(), palette, coverBitmap, alphaFactor)
            }
            BreezeWidgetStyle.WATERCOLOR_STACK -> {
                drawWatercolorStackBackdrop(canvas, w.toFloat(), h.toFloat(), palette, alphaFactor)
            }
        }

        // 顶部柔和玻璃感微细内高光（无生硬矩形边框，仅提升深浅壁纸上的分离质感）
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = max(1.2f, min(w, h) * 0.008f)
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                withAlpha(palette.hairlineHighlight, alphaFactor * 0.92f),
                withAlpha(palette.hairlineHighlight, alphaFactor * 0.14f),
                Shader.TileMode.CLAMP
            )
        }
        val inset = strokePaint.strokeWidth * 0.5f
        canvas.drawRoundRect(
            RectF(inset, inset, w - inset, h - inset),
            (radius - inset).coerceAtLeast(4f),
            (radius - inset).coerceAtLeast(4f),
            strokePaint
        )

        canvas.restore()
        return output
    }

    private fun drawVinylSleeveBackdropGlows(
        canvas: Canvas,
        w: Float,
        h: Float,
        palette: BreezeWidgetPalette,
        alphaFactor: Float
    ) {
        val g1Alpha = if (palette.isDarkSurface) 0.30f else 0.16f
        val g2Alpha = if (palette.isDarkSurface) 0.24f else 0.13f
        val glow1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.20f, h * 0.18f, max(w, h) * 0.72f,
                intArrayOf(withAlpha(palette.glowPrimary, g1Alpha * alphaFactor), 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val glow2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.86f, h * 0.86f, max(w, h) * 0.68f,
                intArrayOf(withAlpha(palette.glowSecondary, g2Alpha * alphaFactor), 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, glow1)
        canvas.drawRect(0f, 0f, w, h, glow2)
    }

    private fun drawAuroraBackdrop(
        canvas: Canvas,
        w: Float,
        h: Float,
        palette: BreezeWidgetPalette,
        coverBitmap: Bitmap?,
        alphaFactor: Float
    ) {
        // 封面快速软模糊柔光铺底（纯 Kotlin StackBlur，全版本零 RenderScript 依赖）
        if (coverBitmap != null && !coverBitmap.isRecycled) {
            val blurred = createFastBlurredBackdrop(coverBitmap)
            if (blurred != null) {
                val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = ((if (palette.isDarkSurface) 0.54f else 0.24f) * alphaFactor * 255f)
                        .roundToInt().coerceIn(0, 255)
                }
                val dst = RectF(0f, 0f, w, h)
                val src = computeCenterCropSrcRect(blurred.width, blurred.height, w, h)
                canvas.drawBitmap(blurred, src, dst, coverPaint)
                blurred.recycle()
            }
        }

        // 三色有机极光光斑
        val meshAlpha = (if (palette.isDarkSurface) 0.42f else 0.24f) * alphaFactor
        val orbs = listOf(
            Triple(w * 0.18f, h * 0.22f, palette.glowPrimary),
            Triple(w * 0.84f, h * 0.24f, palette.glowSecondary),
            Triple(w * 0.56f, h * 0.90f, palette.glowTertiary)
        )
        orbs.forEach { (cx, cy, color) ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx, cy, max(w, h) * 0.66f,
                    intArrayOf(withAlpha(color, meshAlpha), 0x00000000),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w, h, p)
        }

        // 深邃可读性保护遮罩 / 浅色柔雾遮罩
        val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = if (palette.isDarkSurface) {
                LinearGradient(
                    0f, 0f, w, h,
                    withAlpha(Color(0xFF0A101E), 0.48f * alphaFactor),
                    withAlpha(Color(0xFF080D18), 0.68f * alphaFactor),
                    Shader.TileMode.CLAMP
                )
            } else {
                LinearGradient(
                    0f, 0f, w, h,
                    withAlpha(Color(0xFFFFFFFF), 0.72f * alphaFactor),
                    withAlpha(Color(0xFFF3F8FF), 0.82f * alphaFactor),
                    Shader.TileMode.CLAMP
                )
            }
        }
        canvas.drawRect(0f, 0f, w, h, scrimPaint)
    }

    private fun drawWatercolorStackBackdrop(
        canvas: Canvas,
        w: Float,
        h: Float,
        palette: BreezeWidgetPalette,
        alphaFactor: Float
    ) {
        val washA = (if (palette.isDarkSurface) 0.34f else 0.28f) * alphaFactor
        val washB = (if (palette.isDarkSurface) 0.24f else 0.22f) * alphaFactor
        val washC = (if (palette.isDarkSurface) 0.18f else 0.18f) * alphaFactor
        val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.16f, h * 0.50f, max(w, h) * 0.62f,
                intArrayOf(withAlpha(palette.glowPrimary, washA), 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.85f, h * 0.16f, max(w, h) * 0.58f,
                intArrayOf(withAlpha(palette.glowSecondary, washB), 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val p3 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.62f, h * 0.88f, max(w, h) * 0.60f,
                intArrayOf(withAlpha(palette.glowTertiary, washC), 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, p1)
        canvas.drawRect(0f, 0f, w, h, p2)
        canvas.drawRect(0f, 0f, w, h, p3)
    }

    /**
     * 绘制小组件核心视觉舞台图（黑胶封套 / 菱形棱镜光晕封面 / 水彩队列三叠卡 / 迷你黑胶圆盘）。
     */
    fun renderArtworkStage(
        sizeClass: BreezeWidgetSizeClass,
        style: BreezeWidgetStyle,
        primaryCover: Bitmap?,
        nextCover1: Bitmap?,
        nextCover2: Bitmap?,
        palette: BreezeWidgetPalette,
        isPlaying: Boolean
    ): Bitmap {
        return when (sizeClass) {
            BreezeWidgetSizeClass.CAPSULE_4X1 -> renderCompactArtwork(style, primaryCover, palette, isPlaying)
            BreezeWidgetSizeClass.SQUARE_2X2 -> renderSquareOrStageArtwork(
                widthPx = 228,
                heightPx = 152,
                style = style,
                primaryCover = primaryCover,
                nextCover1 = nextCover1,
                nextCover2 = nextCover2,
                palette = palette,
                isPlaying = isPlaying
            )
            BreezeWidgetSizeClass.CARD_4X2 -> renderSquareOrStageArtwork(
                widthPx = 248,
                heightPx = 186,
                style = style,
                primaryCover = primaryCover,
                nextCover1 = nextCover1,
                nextCover2 = nextCover2,
                palette = palette,
                isPlaying = isPlaying
            )
            BreezeWidgetSizeClass.STAGE_4X4 -> renderSquareOrStageArtwork(
                widthPx = 320,
                heightPx = 224,
                style = style,
                primaryCover = primaryCover,
                nextCover1 = nextCover1,
                nextCover2 = nextCover2,
                palette = palette,
                isPlaying = isPlaying
            )
        }
    }

    private fun renderCompactArtwork(
        style: BreezeWidgetStyle,
        primaryCover: Bitmap?,
        palette: BreezeWidgetPalette,
        isPlaying: Boolean
    ): Bitmap {
        val size = 128
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val cx = size * 0.5f
        val cy = size * 0.5f

        when (style) {
            BreezeWidgetStyle.VINYL_SLEEVE -> {
                // MiniPlayerBar 同源黑胶圆盘
                val outerR = size * 0.47f
                val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        cx, cy, outerR,
                        intArrayOf(0xFF2B3242.toInt(), 0xFF10141D.toInt(), 0xFF0A0D14.toInt()),
                        floatArrayOf(0f, 0.72f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(cx, cy, outerR, discPaint)
                drawCircularArtwork(canvas, primaryCover, cx, cy, outerR * 0.80f, palette)
                // 中心主轴圆点
                val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (palette.isDarkSurface) 0xFF141C2E.toInt() else 0xFFFFFFFF.toInt()
                }
                canvas.drawCircle(cx, cy, outerR * 0.14f, holePaint)
            }
            BreezeWidgetStyle.AURORA_COVER -> {
                // 圆角封面 + 微光光环
                val pad = size * 0.11f
                val rect = RectF(pad, pad, size - pad, size - pad)
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = size * 0.045f
                    shader = SweepGradient(
                        cx, cy,
                        intArrayOf(
                            palette.accentSecondary.toArgb(),
                            palette.accentPrimary.toArgb(),
                            palette.glowTertiary.toArgb(),
                            palette.accentSecondary.toArgb()
                        ),
                        null
                    )
                    alpha = if (isPlaying) 215 else 145
                }
                canvas.drawRoundRect(
                    RectF(pad - 3f, pad - 3f, size - pad + 3f, size - pad + 3f),
                    size * 0.24f, size * 0.24f, glowPaint
                )
                drawRoundedArtwork(canvas, primaryCover, rect, size * 0.22f, palette)
            }
            BreezeWidgetStyle.WATERCOLOR_STACK -> {
                // 轻盈双层错位圆角卡
                val backRect = RectF(size * 0.20f, size * 0.14f, size * 0.94f, size * 0.88f)
                val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(palette.accentPrimary, if (palette.isDarkSurface) 0.45f else 0.30f)
                }
                canvas.drawRoundRect(backRect, size * 0.20f, size * 0.20f, backPaint)
                val frontRect = RectF(size * 0.06f, size * 0.08f, size * 0.82f, size * 0.84f)
                drawRoundedArtwork(canvas, primaryCover, frontRect, size * 0.20f, palette)
            }
        }
        return out
    }

    private fun renderSquareOrStageArtwork(
        widthPx: Int,
        heightPx: Int,
        style: BreezeWidgetStyle,
        primaryCover: Bitmap?,
        nextCover1: Bitmap?,
        nextCover2: Bitmap?,
        palette: BreezeWidgetPalette,
        isPlaying: Boolean
    ): Bitmap {
        val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val w = widthPx.toFloat()
        val h = heightPx.toFloat()

        when (style) {
            BreezeWidgetStyle.VINYL_SLEEVE -> {
                // 发现页「猜你喜欢」1:1 同源：左侧大圆角封套 + 右侧探出约 36% 拟真黑胶唱片
                val sleeveSize = min(h * 0.88f, w * 0.64f)
                val totalVisualW = sleeveSize * 1.36f
                val startX = (w - totalVisualW) * 0.5f
                val topY = (h - sleeveSize) * 0.5f

                // 1. 右侧黑胶唱片
                val discR = sleeveSize * 0.46f
                val discCx = startX + sleeveSize * 0.90f
                val discCy = topY + sleeveSize * 0.50f
                drawRealisticVinylDisc(canvas, discCx, discCy, discR, primaryCover, palette)

                // 2. 封套落于唱片之上的柔和立体暗影
                val shadowRect = RectF(
                    startX + 4f,
                    topY + 5f,
                    startX + sleeveSize + 5f,
                    topY + sleeveSize + 6f
                )
                val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (palette.isDarkSurface) 0x70000000 else 0x300A1931
                }
                canvas.drawRoundRect(shadowRect, sleeveSize * 0.16f, sleeveSize * 0.16f, shadowPaint)

                // 3. 前景专辑封套
                val sleeveRect = RectF(startX, topY, startX + sleeveSize, topY + sleeveSize)
                drawRoundedArtwork(canvas, primaryCover, sleeveRect, sleeveSize * 0.16f, palette)

                // 封套微光内沿
                val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = 1.4f
                    color = if (palette.isDarkSurface) 0x26FFFFFF else 0x55FFFFFF
                }
                canvas.drawRoundRect(sleeveRect, sleeveSize * 0.16f, sleeveSize * 0.16f, rimPaint)
            }

            BreezeWidgetStyle.AURORA_COVER -> {
                // 全屏沉浸播放页同源：居中大圆角封面 + 45° 旋转圆角菱形棱镜光晕框
                val coverSize = min(w, h) * 0.66f
                val cx = w * 0.5f
                val cy = h * 0.5f
                val corner = coverSize * 0.20f

                drawDiamondPrismaticAura(
                    canvas = canvas,
                    cx = cx,
                    cy = cy,
                    coverSize = coverSize,
                    cornerPx = corner,
                    palette = palette,
                    isPlaying = isPlaying
                )

                val coverRect = RectF(
                    cx - coverSize * 0.5f,
                    cy - coverSize * 0.5f,
                    cx + coverSize * 0.5f,
                    cy + coverSize * 0.5f
                )
                drawRoundedArtwork(canvas, primaryCover, coverRect, corner, palette)

                // 封面晶莹棱镜微边
                val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = 2.0f
                    shader = LinearGradient(
                        coverRect.left, coverRect.top, coverRect.right, coverRect.bottom,
                        withAlpha(palette.accentPrimary, 0.72f),
                        withAlpha(palette.accentSecondary, 0.52f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRoundRect(coverRect, corner, corner, edgePaint)
            }

            BreezeWidgetStyle.WATERCOLOR_STACK -> {
                // 发现页「每日推荐」同源：三层阶梯错位叠层封面（主封 + 下两首队列真封面/柔和拟真色卡）
                val frontSize = min(h * 0.86f, w * 0.66f)
                val totalW = frontSize * 1.30f
                val left0 = (w - totalW) * 0.5f
                val top0 = (h - frontSize) * 0.5f

                // 第 3 层（最深、最小）
                val s3 = frontSize * 0.76f
                val rect3 = RectF(
                    left0 + frontSize * 0.54f,
                    top0 + (frontSize - s3) * 0.50f,
                    left0 + frontSize * 0.54f + s3,
                    top0 + (frontSize - s3) * 0.50f + s3
                )
                drawStackedCardLayer(
                    canvas = canvas,
                    bitmap = nextCover2,
                    rect = rect3,
                    corner = s3 * 0.18f,
                    fallbackStart = lerp(palette.glowTertiary, palette.cardBase, 0.25f),
                    fallbackEnd = lerp(palette.glowSecondary, palette.cardGradientEnd, 0.35f),
                    dimOverlayAlpha = if (palette.isDarkSurface) 0.32f else 0.16f
                )

                // 第 2 层（中层）
                val s2 = frontSize * 0.88f
                val rect2 = RectF(
                    left0 + frontSize * 0.29f,
                    top0 + (frontSize - s2) * 0.50f,
                    left0 + frontSize * 0.29f + s2,
                    top0 + (frontSize - s2) * 0.50f + s2
                )
                drawStackedCardLayer(
                    canvas = canvas,
                    bitmap = nextCover1,
                    rect = rect2,
                    corner = s2 * 0.18f,
                    fallbackStart = lerp(palette.accentSecondary, palette.cardBase, 0.18f),
                    fallbackEnd = lerp(palette.accentPrimary, palette.cardGradientEnd, 0.28f),
                    dimOverlayAlpha = if (palette.isDarkSurface) 0.16f else 0.08f
                )

                // 主封面阴影
                val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (palette.isDarkSurface) 0x66000000 else 0x26102542
                }
                canvas.drawRoundRect(
                    RectF(left0 + 4f, top0 + 5f, left0 + frontSize + 5f, top0 + frontSize + 6f),
                    frontSize * 0.18f, frontSize * 0.18f, shadowPaint
                )

                // 第 1 层（当前主封面）
                val rect1 = RectF(left0, top0, left0 + frontSize, top0 + frontSize)
                drawRoundedArtwork(canvas, primaryCover, rect1, frontSize * 0.18f, palette)
                val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = 1.6f
                    color = if (palette.isDarkSurface) 0x33FFFFFF else 0x99FFFFFF.toInt()
                }
                canvas.drawRoundRect(rect1, frontSize * 0.18f, frontSize * 0.18f, rim)
            }
        }
        return out
    }

    private fun drawRealisticVinylDisc(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        centerCover: Bitmap?,
        palette: BreezeWidgetPalette
    ) {
        // 唱盘柔和外阴影
        val dropShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx + 2f, cy + 3f, radius * 1.12f,
                intArrayOf(0x55000000, 0x00000000),
                floatArrayOf(0.75f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx + 2f, cy + 3f, radius * 1.12f, dropShadow)

        // 唱片暗黑基盘
        val baseDisc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(0xFF202530.toInt(), 0xFF0E1118.toInt(), 0xFF090B10.toInt()),
                floatArrayOf(0f, 0.68f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, radius, baseDisc)

        // 45° 斜向拉丝反光扇形高光（典型高级黑胶光泽）
        val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = SweepGradient(
                cx, cy,
                intArrayOf(
                    0x00FFFFFF,
                    0x24FFFFFF,
                    0x04FFFFFF,
                    0x00FFFFFF,
                    0x20FFFFFF,
                    0x00FFFFFF
                ),
                floatArrayOf(0f, 0.11f, 0.22f, 0.50f, 0.61f, 1f)
            )
        }
        canvas.drawCircle(cx, cy, radius * 0.96f, sheenPaint)

        // 细腻同心音槽环
        val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.9f
            color = 0x1DFFFFFF
        }
        val rings = floatArrayOf(0.46f, 0.56f, 0.66f, 0.76f, 0.85f, 0.93f)
        for (ratio in rings) {
            canvas.drawCircle(cx, cy, radius * ratio, groovePaint)
        }

        // 唱片中心小封面圆标
        drawCircularArtwork(canvas, centerCover, cx, cy, radius * 0.34f, palette)

        // 中心主轴金属小环与孔
        val spindleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
            color = 0xAAFFFFFF.toInt()
        }
        canvas.drawCircle(cx, cy, radius * 0.11f, spindleRing)
        val spindleHole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (palette.isDarkSurface) 0xFF0D1320.toInt() else 0xFFF3F7FD.toInt()
        }
        canvas.drawCircle(cx, cy, radius * 0.075f, spindleHole)
    }

    private fun drawDiamondPrismaticAura(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        coverSize: Float,
        cornerPx: Float,
        palette: BreezeWidgetPalette,
        isPlaying: Boolean
    ) {
        val activeBoost = if (isPlaying) 1.0f else 0.72f
        // 底层柔和弥散氛围光晕
        val radial = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, coverSize * 0.86f,
                intArrayOf(
                    withAlpha(palette.accentPrimary, 0.34f * activeBoost),
                    withAlpha(palette.accentSecondary, 0.14f * activeBoost),
                    0x00000000
                ),
                floatArrayOf(0.25f, 0.68f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, coverSize * 0.86f, radial)

        // 标志性 45° 旋转圆角菱形多层棱镜呼吸框（与竖屏/横屏播放页视觉 1:1 呼应）
        canvas.save()
        canvas.rotate(45f, cx, cy)
        val diamondSide = coverSize * 0.90f
        val dRect = RectF(
            cx - diamondSide * 0.5f,
            cy - diamondSide * 0.5f,
            cx + diamondSide * 0.5f,
            cy + diamondSide * 0.5f
        )
        val sweep = SweepGradient(
            cx, cy,
            intArrayOf(
                withAlpha(palette.accentSecondary, 0.75f * activeBoost),
                withAlpha(palette.accentPrimary, 0.82f * activeBoost),
                withAlpha(palette.glowTertiary, 0.70f * activeBoost),
                withAlpha(Color.White, 0.65f * activeBoost),
                withAlpha(palette.accentSecondary, 0.75f * activeBoost)
            ),
            null
        )
        for (ring in 1..3) {
            val outset = (ring - 1) * 4.5f
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (4.2f - ring * 0.9f).coerceAtLeast(1.3f)
                shader = sweep
                alpha = ((0.55f / ring) * activeBoost * 255f).roundToInt().coerceIn(22, 180)
            }
            val r = RectF(
                dRect.left - outset,
                dRect.top - outset,
                dRect.right + outset,
                dRect.bottom + outset
            )
            canvas.drawRoundRect(r, cornerPx + outset, cornerPx + outset, ringPaint)
        }
        canvas.restore()
    }

    private fun drawStackedCardLayer(
        canvas: Canvas,
        bitmap: Bitmap?,
        rect: RectF,
        corner: Float,
        fallbackStart: Color,
        fallbackEnd: Color,
        dimOverlayAlpha: Float
    ) {
        if (bitmap != null && !bitmap.isRecycled) {
            drawRoundedArtworkRaw(canvas, bitmap, rect, corner)
            val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(Color.Black, dimOverlayAlpha)
            }
            canvas.drawRoundRect(rect, corner, corner, dimPaint)
        } else {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    fallbackStart.toArgb(),
                    fallbackEnd.toArgb(),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(rect, corner, corner, fillPaint)
            // 细微装饰性微光斜线
            val accentStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.4f
                color = 0x33FFFFFF
            }
            canvas.drawRoundRect(rect, corner, corner, accentStroke)
        }
    }

    /**
     * 绘制圆润饱满播放/暂停主圆形按钮（带柔和主题色渐变与居中微调三角形/双圆角柱）
     */
    fun renderPlayButton(
        sizePx: Int,
        isPlaying: Boolean,
        palette: BreezeWidgetPalette,
        withProgressRing: Boolean = false,
        progressFraction: Float = 0f
    ): Bitmap {
        val s = sizePx.coerceIn(56, 152)
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val cx = s * 0.5f
        val cy = s * 0.5f

        val circleR = if (withProgressRing) s * 0.39f else s * 0.44f

        // 外圈极简柔和光环 / 进度环（4×1 复刻 MiniPlayerBar 进度环播放钮！）
        if (withProgressRing) {
            val ringR = s * 0.46f
            val strokeW = max(2.8f, s * 0.048f)
            val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeW
                color = palette.progressTrack.toArgb()
            }
            canvas.drawCircle(cx, cy, ringR, trackPaint)
            if (progressFraction > 0.01f) {
                val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = strokeW
                    strokeCap = Paint.Cap.ROUND
                    shader = SweepGradient(
                        cx, cy,
                        intArrayOf(
                            palette.progressActiveStart.toArgb(),
                            palette.progressActiveEnd.toArgb(),
                            palette.progressActiveStart.toArgb()
                        ),
                        null
                    )
                }
                val oval = RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
                canvas.drawArc(oval, -90f, 360f * progressFraction.coerceIn(0f, 1f), false, activePaint)
            }
        } else {
            // 柔和主题外辉光
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx, cy, s * 0.50f,
                    intArrayOf(withAlpha(palette.playButtonStart, 0.32f), 0x00000000),
                    floatArrayOf(0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(cx, cy, s * 0.50f, glowPaint)
        }

        // 饱满主按钮渐变圆底
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx - circleR, cy - circleR, cx + circleR, cy + circleR,
                palette.playButtonStart.toArgb(),
                palette.playButtonEnd.toArgb(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, circleR, bgPaint)

        // 圆润几何播放/暂停图标
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.playButtonIcon.toArgb()
            style = Paint.Style.FILL
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        if (isPlaying) {
            val barW = circleR * 0.25f
            val barH = circleR * 0.78f
            val gap = circleR * 0.16f
            val r = barW * 0.5f
            val leftBar = RectF(cx - gap - barW, cy - barH * 0.5f, cx - gap, cy + barH * 0.5f)
            val rightBar = RectF(cx + gap, cy - barH * 0.5f, cx + gap + barW, cy + barH * 0.5f)
            canvas.drawRoundRect(leftBar, r, r, iconPaint)
            canvas.drawRoundRect(rightBar, r, r, iconPaint)
        } else {
            // 饱满视觉居中圆角播放三角（略微右偏保证几何重心完美）
            val triR = circleR * 0.44f
            val shiftX = circleR * 0.08f
            val path = Path().apply {
                moveTo(cx - triR * 0.68f + shiftX, cy - triR * 0.86f)
                lineTo(cx + triR * 1.02f + shiftX, cy)
                lineTo(cx - triR * 0.68f + shiftX, cy + triR * 0.86f)
                close()
            }
            val roundedPlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.playButtonIcon.toArgb()
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = circleR * 0.15f
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawPath(path, roundedPlayPaint)
        }
        return out
    }

    /**
     * 绘制圆润辅助控制按钮（上一首 / 下一首 / 红心喜欢 / 播放模式），可选轻透圆底衬。
     */
    fun renderControlButton(
        icon: BreezeWidgetControlIcon,
        sizePx: Int,
        palette: BreezeWidgetPalette,
        withSubtleCircleBg: Boolean = true
    ): Bitmap {
        val s = sizePx.coerceIn(48, 120)
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val cx = s * 0.5f
        val cy = s * 0.5f

        if (withSubtleCircleBg) {
            val bgColor = if (icon == BreezeWidgetControlIcon.FAVORITE_ON) {
                withAlpha(palette.favoriteActive, if (palette.isDarkSurface) 0.22f else 0.14f)
            } else {
                palette.controlButtonBg.toArgb()
            }
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
            canvas.drawCircle(cx, cy, s * 0.44f, bgPaint)
        }

        val unit = s * 0.24f
        when (icon) {
            BreezeWidgetControlIcon.PREV -> drawSkipIcon(canvas, cx, cy, unit, palette.controlIconTint.toArgb(), isNext = false)
            BreezeWidgetControlIcon.NEXT -> drawSkipIcon(canvas, cx, cy, unit, palette.controlIconTint.toArgb(), isNext = true)
            BreezeWidgetControlIcon.FAVORITE_OFF -> drawHeartIcon(canvas, cx, cy, unit, palette, filled = false)
            BreezeWidgetControlIcon.FAVORITE_ON -> drawHeartIcon(canvas, cx, cy, unit, palette, filled = true)
            BreezeWidgetControlIcon.PLAY_MODE_LIST -> drawPlayModeIcon(canvas, cx, cy, unit, palette.controlIconTint.toArgb(), PlayMode.REPEAT_ALL)
            BreezeWidgetControlIcon.PLAY_MODE_SINGLE -> drawPlayModeIcon(canvas, cx, cy, unit, palette.accentPrimary.toArgb(), PlayMode.REPEAT_ONE)
            BreezeWidgetControlIcon.PLAY_MODE_SHUFFLE -> drawPlayModeIcon(canvas, cx, cy, unit, palette.accentPrimary.toArgb(), PlayMode.SHUFFLE)
        }
        return out
    }

    private fun drawSkipIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        unit: Float,
        tintArgb: Int,
        isNext: Boolean
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tintArgb
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = unit * 0.22f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val dir = if (isNext) 1f else -1f
        val path = Path().apply {
            moveTo(cx - dir * unit * 0.72f, cy - unit * 0.68f)
            lineTo(cx + dir * unit * 0.36f, cy)
            lineTo(cx - dir * unit * 0.72f, cy + unit * 0.68f)
            close()
        }
        canvas.drawPath(path, paint)

        // 圆润竖条挡板
        val barW = unit * 0.28f
        val barX = if (isNext) cx + unit * 0.56f else cx - unit * 0.56f - barW
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tintArgb
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            RectF(barX, cy - unit * 0.74f, barX + barW, cy + unit * 0.74f),
            barW * 0.5f, barW * 0.5f, barPaint
        )
    }

    private fun drawHeartIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        unit: Float,
        palette: BreezeWidgetPalette,
        filled: Boolean
    ) {
        val w = unit * 2.1f
        val h = unit * 2.0f
        val left = cx - w * 0.5f
        val top = cy - h * 0.48f
        val path = Path().apply {
            moveTo(cx, top + h * 0.90f)
            cubicTo(left - w * 0.04f, top + h * 0.58f, left - w * 0.02f, top + h * 0.14f, left + w * 0.27f, top + h * 0.10f)
            cubicTo(left + w * 0.40f, top + h * 0.08f, left + w * 0.48f, top + h * 0.18f, cx, top + h * 0.27f)
            cubicTo(left + w * 0.52f, top + h * 0.18f, left + w * 0.60f, top + h * 0.08f, left + w * 0.73f, top + h * 0.10f)
            cubicTo(left + w * 1.02f, top + h * 0.14f, left + w * 1.04f, top + h * 0.58f, cx, top + h * 0.90f)
            close()
        }
        if (filled) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    left, top, left + w, top + h,
                    0xFFFF85A8.toInt(),
                    0xFFE83A6F.toInt(),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawPath(path, fillPaint)
            // 复刻 RefinedFeatureIcons 的左上温润高光短弧
            val gloss = Path().apply {
                moveTo(left + w * 0.20f, top + h * 0.36f)
                cubicTo(left + w * 0.18f, top + h * 0.24f, left + w * 0.26f, top + h * 0.18f, left + w * 0.35f, top + h * 0.20f)
            }
            val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(2.0f, unit * 0.14f)
                strokeCap = Paint.Cap.ROUND
                color = 0x70FFFFFF
            }
            canvas.drawPath(gloss, glossPaint)
        } else {
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(2.4f, unit * 0.18f)
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                color = palette.controlIconTint.toArgb()
            }
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun drawPlayModeIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        unit: Float,
        tintArgb: Int,
        mode: PlayMode
    ) {
        val stroke = max(2.4f, unit * 0.18f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tintArgb
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        when (mode) {
            PlayMode.SHUFFLE -> {
                val p1 = Path().apply {
                    moveTo(cx - unit * 0.85f, cy + unit * 0.52f)
                    cubicTo(cx - unit * 0.15f, cy + unit * 0.52f, cx + unit * 0.15f, cy - unit * 0.52f, cx + unit * 0.82f, cy - unit * 0.52f)
                }
                val p2 = Path().apply {
                    moveTo(cx - unit * 0.85f, cy - unit * 0.52f)
                    cubicTo(cx - unit * 0.15f, cy - unit * 0.52f, cx + unit * 0.15f, cy + unit * 0.52f, cx + unit * 0.82f, cy + unit * 0.52f)
                }
                canvas.drawPath(p1, paint)
                canvas.drawPath(p2, paint)
                // 右端箭头翼
                canvas.drawLine(cx + unit * 0.55f, cy - unit * 0.78f, cx + unit * 0.85f, cy - unit * 0.52f, paint)
                canvas.drawLine(cx + unit * 0.55f, cy + unit * 0.78f, cx + unit * 0.85f, cy + unit * 0.52f, paint)
            }
            PlayMode.REPEAT_ALL, PlayMode.REPEAT_ONE -> {
                val oval = RectF(cx - unit * 0.85f, cy - unit * 0.58f, cx + unit * 0.85f, cy + unit * 0.58f)
                val loopPath = Path().apply {
                    moveTo(cx - unit * 0.72f, cy - unit * 0.12f)
                    lineTo(cx - unit * 0.72f, oval.top + unit * 0.25f)
                    quadTo(cx - unit * 0.72f, oval.top, cx - unit * 0.35f, oval.top)
                    lineTo(cx + unit * 0.72f, oval.top)
                    moveTo(cx + unit * 0.72f, cy + unit * 0.12f)
                    lineTo(cx + unit * 0.72f, oval.bottom - unit * 0.25f)
                    quadTo(cx + unit * 0.72f, oval.bottom, cx + unit * 0.35f, oval.bottom)
                    lineTo(cx - unit * 0.72f, oval.bottom)
                }
                canvas.drawPath(loopPath, paint)
                canvas.drawLine(cx + unit * 0.46f, oval.top - unit * 0.26f, cx + unit * 0.76f, oval.top, paint)
                canvas.drawLine(cx + unit * 0.46f, oval.top + unit * 0.24f, cx + unit * 0.76f, oval.top, paint)
                canvas.drawLine(cx - unit * 0.46f, oval.bottom + unit * 0.26f, cx - unit * 0.76f, oval.bottom, paint)
                canvas.drawLine(cx - unit * 0.46f, oval.bottom - unit * 0.24f, cx - unit * 0.76f, oval.bottom, paint)
                if (mode == PlayMode.REPEAT_ONE) {
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = tintArgb
                        textSize = unit * 0.82f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textAlign = Paint.Align.CENTER
                    }
                    val fm = textPaint.fontMetrics
                    val textY = cy - (fm.ascent + fm.descent) * 0.5f
                    canvas.drawText("1", cx, textY, textPaint)
                }
            }
        }
    }

    /**
     * 绘制圆润胶囊细进度条
     */
    fun renderProgressBar(
        widthPx: Int,
        heightPx: Int,
        progressFraction: Float,
        palette: BreezeWidgetPalette
    ): Bitmap {
        val w = widthPx.coerceIn(120, 460)
        val h = heightPx.coerceIn(10, 24)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val trackH = (h * 0.42f).coerceAtLeast(3.5f)
        val cy = h * 0.5f
        val padX = h * 0.45f
        val trackLeft = padX
        val trackRight = w - padX
        val trackTop = cy - trackH * 0.5f
        val trackBottom = cy + trackH * 0.5f
        val r = trackH * 0.5f

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.progressTrack.toArgb()
        }
        canvas.drawRoundRect(RectF(trackLeft, trackTop, trackRight, trackBottom), r, r, trackPaint)

        val p = progressFraction.coerceIn(0f, 1f)
        if (p > 0.005f) {
            val activeRight = trackLeft + (trackRight - trackLeft) * p
            val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    trackLeft, 0f, activeRight.coerceAtLeast(trackLeft + 4f), 0f,
                    palette.progressActiveStart.toArgb(),
                    palette.progressActiveEnd.toArgb(),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(RectF(trackLeft, trackTop, activeRight, trackBottom), r, r, activePaint)

            // 精致端点柔光圆点
            val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.progressActiveEnd.toArgb()
            }
            canvas.drawCircle(activeRight, cy, (trackH * 0.82f).coerceAtMost(h * 0.44f), knobPaint)
        }
        return out
    }

    /**
     * 绘制圆润音质徽标胶囊（Hi-Res / SQ / HQ / FLAC），保证不同主题下色彩统一与零裁剪
     */
    fun renderQualityBadge(
        badgeText: String,
        palette: BreezeWidgetPalette,
        heightPx: Int = 34
    ): Bitmap {
        val clean = badgeText.trim().take(8)
        val (bgColor, fgColor) = resolveQualityBadgeColorPair(clean, palette)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fgColor.toArgb()
            textSize = heightPx * 0.56f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val textW = textPaint.measureText(clean)
        val padH = heightPx * 0.42f
        val w = (textW + padH * 2f).roundToInt().coerceIn(heightPx, 180)
        val out = Bitmap.createBitmap(w, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor.toArgb()
        }
        val radius = heightPx * 0.36f
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), heightPx.toFloat()), radius, radius, bgPaint)
        val fm = textPaint.fontMetrics
        val baseline = heightPx * 0.5f - (fm.ascent + fm.descent) * 0.5f
        canvas.drawText(clean, w * 0.5f, baseline, textPaint)
        return out
    }

    private fun drawRoundedArtwork(
        canvas: Canvas,
        bitmap: Bitmap?,
        rect: RectF,
        cornerRadius: Float,
        palette: BreezeWidgetPalette
    ) {
        if (bitmap != null && !bitmap.isRecycled) {
            drawRoundedArtworkRaw(canvas, bitmap, rect, cornerRadius)
        } else {
            drawPlaceholderArtwork(canvas, rect, cornerRadius, palette)
        }
    }

    private fun drawRoundedArtworkRaw(
        canvas: Canvas,
        bitmap: Bitmap,
        rect: RectF,
        cornerRadius: Float
    ) {
        val bw = bitmap.width.toFloat().coerceAtLeast(1f)
        val bh = bitmap.height.toFloat().coerceAtLeast(1f)
        val scale = max(rect.width() / bw, rect.height() / bh)
        val dx = rect.left + (rect.width() - bw * scale) * 0.5f
        val dy = rect.top + (rect.height() - bh * scale) * 0.5f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(matrix)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
    }

    private fun drawCircularArtwork(
        canvas: Canvas,
        bitmap: Bitmap?,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: BreezeWidgetPalette
    ) {
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        if (bitmap != null && !bitmap.isRecycled) {
            drawRoundedArtworkRaw(canvas, bitmap, rect, radius)
        } else {
            drawPlaceholderArtwork(canvas, rect, radius, palette)
        }
    }

    private fun drawPlaceholderArtwork(
        canvas: Canvas,
        rect: RectF,
        cornerRadius: Float,
        palette: BreezeWidgetPalette
    ) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                palette.playButtonStart.toArgb(),
                palette.playButtonEnd.toArgb(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

        // 柔和微风波纹 + 圆润音符图腾
        val cx = rect.centerX()
        val cy = rect.centerY()
        val s = min(rect.width(), rect.height())
        val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, s * 0.032f)
            strokeCap = Paint.Cap.ROUND
            color = 0x45FFFFFF
        }
        canvas.drawCircle(cx, cy, s * 0.30f, wavePaint)

        val noteColor = 0xE6FFFFFF.toInt()
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = noteColor
            style = Paint.Style.FILL
        }
        val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = noteColor
            style = Paint.Style.STROKE
            strokeWidth = max(2.6f, s * 0.068f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val headX = cx - s * 0.06f
        val headY = cy + s * 0.14f
        canvas.drawCircle(headX, headY, s * 0.095f, headPaint)
        val stemX = headX + s * 0.07f
        val stemPath = Path().apply {
            moveTo(stemX, headY)
            lineTo(stemX, cy - s * 0.19f)
            quadTo(stemX + s * 0.18f, cy - s * 0.15f, stemX + s * 0.16f, cy - s * 0.02f)
        }
        canvas.drawPath(stemPath, stemPaint)
    }

    /**
     * 快速纯 Kotlin 离屏低分辨率盒式均值模糊（36×36 3 次迭代 ≈ 高斯柔光，耗时 < 2ms，无废弃 API）
     */
    private fun createFastBlurredBackdrop(source: Bitmap): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        val smallW = 36
        val smallH = 36
        val small = Bitmap.createScaledBitmap(source, smallW, smallH, true)
        val pixels = IntArray(smallW * smallH)
        small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
        val temp = IntArray(pixels.size)

        // 3 轮分离式盒式模糊近似高斯核
        repeat(3) {
            boxBlurHorizontal(pixels, temp, smallW, smallH, radius = 3)
            boxBlurVertical(temp, pixels, smallW, smallH, radius = 3)
        }
        val out = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
        if (small !== source && !small.isRecycled) small.recycle()
        return out
    }

    private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val span = radius * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var rSum = 0; var gSum = 0; var bSum = 0
                for (dx in -radius..radius) {
                    val sx = (x + dx).coerceIn(0, w - 1)
                    val p = src[row + sx]
                    rSum += (p ushr 16) and 0xFF
                    gSum += (p ushr 8) and 0xFF
                    bSum += p and 0xFF
                }
                dst[row + x] = (0xFF shl 24) or ((rSum / span) shl 16) or ((gSum / span) shl 8) or (bSum / span)
            }
        }
    }

    private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val span = radius * 2 + 1
        for (x in 0 until w) {
            for (y in 0 until h) {
                var rSum = 0; var gSum = 0; var bSum = 0
                for (dy in -radius..radius) {
                    val sy = (y + dy).coerceIn(0, h - 1)
                    val p = src[sy * w + x]
                    rSum += (p ushr 16) and 0xFF
                    gSum += (p ushr 8) and 0xFF
                    bSum += p and 0xFF
                }
                dst[y * w + x] = (0xFF shl 24) or ((rSum / span) shl 16) or ((gSum / span) shl 8) or (bSum / span)
            }
        }
    }

    private fun computeCenterCropSrcRect(srcW: Int, srcH: Int, dstW: Float, dstH: Float): Rect {
        val srcAspect = srcW.toFloat() / srcH.toFloat().coerceAtLeast(1f)
        val dstAspect = dstW / dstH.coerceAtLeast(1f)
        return if (srcAspect > dstAspect) {
            val cropW = (srcH * dstAspect).roundToInt().coerceIn(1, srcW)
            val left = (srcW - cropW) / 2
            Rect(left, 0, left + cropW, srcH)
        } else {
            val cropH = (srcW / dstAspect).roundToInt().coerceIn(1, srcH)
            val top = (srcH - cropH) / 2
            Rect(0, top, srcW, top + cropH)
        }
    }

    private fun withAlpha(color: Color, alpha: Float): Int {
        return color.copy(alpha = (color.alpha * alpha).coerceIn(0f, 1f)).toArgb()
    }
}
