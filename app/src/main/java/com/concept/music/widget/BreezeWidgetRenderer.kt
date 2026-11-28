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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
 * - 全版本（Android 8–16）像素级精准绘制克制圆角卡底、幽邃微蓝/水彩浸润晕染、流光封面三色模糊底与 4×4 杂志级舞台分层；
 * - 精准复刻三大 App 原生视觉图腾：黑胶封套舞台、旋转 45° 菱形棱镜光晕、队列错位水彩叠层；
 * - 以真贝塞尔圆角多边形与立体柔光质感绘制圆润饱满的播放/暂停、切歌、红心、播放模式控钮及胶囊进度条。
 */
internal object BreezeWidgetRenderer {

    /**
     * 绘制小组件全幅克制圆角背景底图（严格控制像素规模，防止 RemoteViews Binder 内存超限）。
     */
    fun renderCardBackground(
        widthPx: Int,
        heightPx: Int,
        cornerRadiusPx: Float,
        style: BreezeWidgetStyle,
        palette: BreezeWidgetPalette,
        coverBitmap: Bitmap?,
        backgroundAlpha: Float,
        isStage4x4: Boolean = false
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
        val radius = cornerRadiusPx.coerceIn(8f, min(w, h) * 0.34f)

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
                drawVinylSleeveBackdropGlows(canvas, w.toFloat(), h.toFloat(), palette, alphaFactor, isStage4x4)
            }
            BreezeWidgetStyle.AURORA_COVER -> {
                drawAuroraBackdrop(canvas, w.toFloat(), h.toFloat(), palette, coverBitmap, alphaFactor, isStage4x4)
            }
            BreezeWidgetStyle.WATERCOLOR_STACK -> {
                drawWatercolorStackBackdrop(canvas, w.toFloat(), h.toFloat(), palette, alphaFactor, isStage4x4)
            }
        }

        // 2. 4×4 杂志沉浸舞台专属：底部无边框半透明控台色调沉降 + 极细柔和过渡光缝，强化上下视觉层次
        if (isStage4x4) {
            drawStage4x4ArchitecturalDepth(canvas, w.toFloat(), h.toFloat(), palette, alphaFactor)
        }

        // 3. 顶部柔和玻璃感微细内高光（无生硬矩形边框，提升深浅壁纸上的分离质感）
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = max(1.2f, min(w, h) * 0.0065f)
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                withAlpha(palette.hairlineHighlight, alphaFactor * 0.90f),
                withAlpha(palette.hairlineHighlight, alphaFactor * 0.16f),
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

    private fun drawStage4x4ArchitecturalDepth(
        canvas: Canvas,
        w: Float,
        h: Float,
        palette: BreezeWidgetPalette,
        alphaFactor: Float
    ) {
        // 顶部左侧唱片区聚光晕（为左封面 + 右大字构成明确光影引导）
        val spotAlpha = (if (palette.isDarkSurface) 0.24f else 0.15f) * alphaFactor
        val spotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.26f, h * 0.24f, w * 0.58f,
                intArrayOf(
                    withAlpha(palette.accentPrimary, spotAlpha),
                    withAlpha(palette.accentSecondary, spotAlpha * 0.35f),
                    0x00000000
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h * 0.65f, spotPaint)

        // 底部播控区无边框柔雾沉降带（71%..100% 柔和渐变，不画嵌套方框）
        val dockTop = h * 0.71f
        val dockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = if (palette.isDarkSurface) {
                LinearGradient(
                    0f, dockTop, 0f, h,
                    intArrayOf(
                        0x00000000,
                        withAlpha(Color(0xFF040812), 0.26f * alphaFactor),
                        withAlpha(Color(0xFF03060E), 0.42f * alphaFactor)
                    ),
                    floatArrayOf(0f, 0.35f, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                LinearGradient(
                    0f, dockTop, 0f, h,
                    intArrayOf(
                        0x00FFFFFF,
                        withAlpha(Color(0xFFFFFFFF), 0.48f * alphaFactor),
                        withAlpha(Color(0xFFF6F9FF), 0.72f * alphaFactor)
                    ),
                    floatArrayOf(0f, 0.35f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        }
        canvas.drawRect(0f, dockTop, w, h, dockPaint)

        // 舞台区与控台区之间极克制的微光渐变分隔线（两端自然消隐）
        val seamY = h * 0.725f
        val seamColor = if (palette.isDarkSurface) Color.White else palette.accentPrimary
        val seamAlpha = (if (palette.isDarkSurface) 0.11f else 0.12f) * alphaFactor
        val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                w * 0.08f, seamY, w * 0.92f, seamY,
                intArrayOf(
                    0x00000000,
                    withAlpha(seamColor, seamAlpha),
                    withAlpha(seamColor, seamAlpha),
                    0x00000000
                ),
                floatArrayOf(0f, 0.22f, 0.78f, 1f),
                Shader.TileMode.CLAMP
            )
            strokeWidth = 1.2f
        }
        canvas.drawLine(w * 0.08f, seamY, w * 0.92f, seamY, seamPaint)
    }

    private fun drawVinylSleeveBackdropGlows(
        canvas: Canvas,
        w: Float,
        h: Float,
        palette: BreezeWidgetPalette,
        alphaFactor: Float,
        isStage4x4: Boolean
    ) {
        val g1Alpha = (if (palette.isDarkSurface) 0.30f else 0.16f) * if (isStage4x4) 1.08f else 1f
        val g2Alpha = (if (palette.isDarkSurface) 0.24f else 0.13f) * if (isStage4x4) 1.05f else 1f
        val glow1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.20f, h * 0.20f, max(w, h) * 0.72f,
                intArrayOf(withAlpha(palette.glowPrimary, g1Alpha * alphaFactor), 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val glow2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.86f, h * 0.84f, max(w, h) * 0.68f,
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
        alphaFactor: Float,
        isStage4x4: Boolean
    ) {
        // 封面快速软模糊柔光铺底（纯 Kotlin StackBlur，全版本零 RenderScript 依赖）
        if (coverBitmap != null && !coverBitmap.isRecycled) {
            val blurred = createFastBlurredBackdrop(coverBitmap)
            if (blurred != null) {
                val baseCoverOpacity = if (palette.isDarkSurface) {
                    if (isStage4x4) 0.62f else 0.54f
                } else {
                    if (isStage4x4) 0.28f else 0.24f
                }
                val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = (baseCoverOpacity * alphaFactor * 255f).roundToInt().coerceIn(0, 255)
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
            Triple(w * 0.56f, h * 0.88f, palette.glowTertiary)
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
                    withAlpha(Color(0xFF0A101E), 0.46f * alphaFactor),
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
        alphaFactor: Float,
        isStage4x4: Boolean
    ) {
        val boost = if (isStage4x4) 1.08f else 1f
        val washA = (if (palette.isDarkSurface) 0.34f else 0.28f) * alphaFactor * boost
        val washB = (if (palette.isDarkSurface) 0.24f else 0.22f) * alphaFactor * boost
        val washC = (if (palette.isDarkSurface) 0.18f else 0.18f) * alphaFactor * boost
        val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.18f, h * 0.36f, max(w, h) * 0.62f,
                intArrayOf(withAlpha(palette.glowPrimary, washA), 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.85f, h * 0.18f, max(w, h) * 0.58f,
                intArrayOf(withAlpha(palette.glowSecondary, washB), 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val p3 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.58f, h * 0.86f, max(w, h) * 0.60f,
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
     * 绘制 4×4 沉浸歌词当前句左侧的圆润竖向流光呼吸条（零边框聚焦引导）。
     */
    fun renderLyricAccentBar(
        palette: BreezeWidgetPalette,
        widthPx: Int = 10,
        heightPx: Int = 56
    ): Bitmap {
        val w = widthPx.coerceIn(6, 16)
        val h = heightPx.coerceIn(28, 96)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val barW = w * 0.62f
        val left = (w - barW) * 0.5f
        val rect = RectF(left, 2f, left + barW, h - 2f)
        val r = barW * 0.5f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                palette.progressActiveStart.toArgb(),
                palette.progressActiveEnd.toArgb(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, r, r, paint)
        return out
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
                widthPx = 236,
                heightPx = 158,
                style = style,
                primaryCover = primaryCover,
                nextCover1 = nextCover1,
                nextCover2 = nextCover2,
                palette = palette,
                isPlaying = isPlaying
            )
            BreezeWidgetSizeClass.CARD_4X2 -> renderSquareOrStageArtwork(
                widthPx = 264,
                heightPx = 200,
                style = style,
                primaryCover = primaryCover,
                nextCover1 = nextCover1,
                nextCover2 = nextCover2,
                palette = palette,
                isPlaying = isPlaying
            )
            BreezeWidgetSizeClass.STAGE_4X4 -> renderSquareOrStageArtwork(
                widthPx = 336,
                heightPx = 264,
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
        val size = 132
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
                // 克制圆角封面 + 微光光环（与 16dp 外卡角光学同心）
                val pad = size * 0.10f
                val rect = RectF(pad, pad, size - pad, size - pad)
                val innerCorner = size * 0.17f
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = size * 0.044f
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
                    alpha = if (isPlaying) 220 else 150
                }
                canvas.drawRoundRect(
                    RectF(pad - 3f, pad - 3f, size - pad + 3f, size - pad + 3f),
                    innerCorner + 3f, innerCorner + 3f, glowPaint
                )
                drawRoundedArtwork(canvas, primaryCover, rect, innerCorner, palette)
            }
            BreezeWidgetStyle.WATERCOLOR_STACK -> {
                // 轻盈双层错位圆角卡
                val corner = size * 0.17f
                val backRect = RectF(size * 0.20f, size * 0.14f, size * 0.94f, size * 0.88f)
                val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(palette.accentPrimary, if (palette.isDarkSurface) 0.45f else 0.30f)
                }
                canvas.drawRoundRect(backRect, corner, corner, backPaint)
                val frontRect = RectF(size * 0.06f, size * 0.08f, size * 0.82f, size * 0.84f)
                drawRoundedArtwork(canvas, primaryCover, frontRect, corner, palette)
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
                // 发现页「猜你喜欢」同源：左侧克制圆角封套 + 右侧探出约 36% 拟真黑胶唱片
                val sleeveSize = min(h * 0.88f, w * 0.65f)
                val totalVisualW = sleeveSize * 1.36f
                val startX = (w - totalVisualW) * 0.5f
                val topY = (h - sleeveSize) * 0.5f
                val sleeveCorner = sleeveSize * 0.135f

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
                    color = if (palette.isDarkSurface) 0x72000000 else 0x2E0A1931
                }
                canvas.drawRoundRect(shadowRect, sleeveCorner, sleeveCorner, shadowPaint)

                // 3. 前景专辑封套
                val sleeveRect = RectF(startX, topY, startX + sleeveSize, topY + sleeveSize)
                drawRoundedArtwork(canvas, primaryCover, sleeveRect, sleeveCorner, palette)

                // 封套微光内沿
                val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = 1.5f
                    color = if (palette.isDarkSurface) 0x2CFFFFFF else 0x62FFFFFF
                }
                canvas.drawRoundRect(sleeveRect, sleeveCorner, sleeveCorner, rimPaint)
            }

            BreezeWidgetStyle.AURORA_COVER -> {
                // 全屏沉浸播放页同源：居中封面 + 45° 旋转圆角菱形棱镜光晕框
                val coverSize = min(w, h) * 0.68f
                val cx = w * 0.5f
                val cy = h * 0.5f
                val corner = coverSize * 0.165f

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
                val frontSize = min(h * 0.87f, w * 0.66f)
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
                    corner = s3 * 0.145f,
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
                    corner = s2 * 0.145f,
                    fallbackStart = lerp(palette.accentSecondary, palette.cardBase, 0.18f),
                    fallbackEnd = lerp(palette.accentPrimary, palette.cardGradientEnd, 0.28f),
                    dimOverlayAlpha = if (palette.isDarkSurface) 0.16f else 0.08f
                )

                // 主封面阴影
                val frontCorner = frontSize * 0.145f
                val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (palette.isDarkSurface) 0x66000000 else 0x24102542
                }
                canvas.drawRoundRect(
                    RectF(left0 + 4f, top0 + 5f, left0 + frontSize + 5f, top0 + frontSize + 6f),
                    frontCorner, frontCorner, shadowPaint
                )

                // 第 1 层（当前主封面）
                val rect1 = RectF(left0, top0, left0 + frontSize, top0 + frontSize)
                drawRoundedArtwork(canvas, primaryCover, rect1, frontCorner, palette)
                val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = 1.6f
                    color = if (palette.isDarkSurface) 0x36FFFFFF else 0x99FFFFFF.toInt()
                }
                canvas.drawRoundRect(rect1, frontCorner, frontCorner, rim)
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

        // 45° 斜向拉丝反光扇形高光（高级黑胶光泽）
        val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = SweepGradient(
                cx, cy,
                intArrayOf(
                    0x00FFFFFF,
                    0x26FFFFFF,
                    0x04FFFFFF,
                    0x00FFFFFF,
                    0x22FFFFFF,
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
            color = 0x20FFFFFF
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

        canvas.save()
        canvas.rotate(45f, cx, cy)
        val diamondSide = coverSize * 0.89f
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
            val outset = (ring - 1) * 4.2f
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (4.0f - ring * 0.85f).coerceAtLeast(1.3f)
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
            val accentStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.4f
                color = 0x33FFFFFF
            }
            canvas.drawRoundRect(rect, corner, corner, accentStroke)
        }
    }

    /**
     * 绘制圆润饱满、触感立体的播放/暂停主圆形按钮：
     * - 更大实体圆径 + 柔和色晕底座 + 顶部丝绸内高光，营造温润鹅卵石触感；
     * - 播放三角使用真贝塞尔圆弧倒角绘制（零尖锐折角），暂停采用双圆润饱满胶囊柱。
     */
    fun renderPlayButton(
        sizePx: Int,
        isPlaying: Boolean,
        palette: BreezeWidgetPalette,
        withProgressRing: Boolean = false,
        progressFraction: Float = 0f
    ): Bitmap {
        val s = sizePx.coerceIn(64, 168)
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val cx = s * 0.5f
        val cy = s * 0.5f

        val circleR = if (withProgressRing) s * 0.405f else s * 0.455f

        // 1. 外圈立体微光底座 / 圆润进度环
        if (withProgressRing) {
            val ringR = s * 0.462f
            val strokeW = max(3.6f, s * 0.056f)
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
            // 底部偏下主题漫反射软阴影，强化实体悬浮饱满度
            val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx, cy + s * 0.035f, s * 0.495f,
                    intArrayOf(
                        withAlpha(palette.playButtonStart, if (palette.isDarkSurface) 0.44f else 0.34f),
                        0x00000000
                    ),
                    floatArrayOf(0.62f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(cx, cy + s * 0.035f, s * 0.495f, ambientPaint)
        }

        // 2. 饱满三段流光主圆底
        val midColor = lerp(palette.playButtonStart, palette.playButtonEnd, 0.48f)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx - circleR * 0.85f, cy - circleR, cx + circleR * 0.85f, cy + circleR,
                intArrayOf(
                    palette.playButtonStart.toArgb(),
                    midColor.toArgb(),
                    palette.playButtonEnd.toArgb()
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, circleR, bgPaint)

        // 3. 顶部柔光球面高光罩 + 温润玻璃内环（塑造圆润立体微凸手感）
        val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx - circleR * 0.18f, cy - circleR * 0.42f, circleR * 1.15f,
                intArrayOf(0x38FFFFFF, 0x0CFFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, circleR, sheenPaint)

        val rimStroke = max(1.5f, s * 0.018f)
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = rimStroke
            shader = LinearGradient(
                cx, cy - circleR, cx, cy + circleR,
                0x52FFFFFF,
                0x0CFFFFFF,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, circleR - rimStroke * 0.5f, rimPaint)

        // 4. 圆润饱满几何播放/暂停图标
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.playButtonIcon.toArgb()
            style = Paint.Style.FILL
        }
        if (isPlaying) {
            val barW = circleR * 0.305f
            val barH = circleR * 0.80f
            val gap = circleR * 0.135f
            val r = barW * 0.5f
            val leftBar = RectF(cx - gap - barW, cy - barH * 0.5f, cx - gap, cy + barH * 0.5f)
            val rightBar = RectF(cx + gap, cy - barH * 0.5f, cx + gap + barW, cy + barH * 0.5f)
            canvas.drawRoundRect(leftBar, r, r, iconPaint)
            canvas.drawRoundRect(rightBar, r, r, iconPaint)
        } else {
            // 真贝塞尔圆角饱满三角（光学右偏，三顶角充分圆润无尖刺）
            val triR = circleR * 0.50f
            val shiftX = circleR * 0.075f
            val playPath = createRoundedPolygonPath(
                points = listOf(
                    (cx - triR * 0.66f + shiftX) to (cy - triR * 0.86f),
                    (cx + triR * 1.00f + shiftX) to cy,
                    (cx - triR * 0.66f + shiftX) to (cy + triR * 0.86f)
                ),
                cornerRadius = triR * 0.32f
            )
            canvas.drawPath(playPath, iconPaint)
        }
        return out
    }

    /**
     * 绘制圆润饱满辅助控制按钮（上一首 / 下一首 / 红心喜欢 / 播放模式），配备立体微透圆枕底衬。
     */
    fun renderControlButton(
        icon: BreezeWidgetControlIcon,
        sizePx: Int,
        palette: BreezeWidgetPalette,
        withSubtleCircleBg: Boolean = true,
        softBackgroundOnly: Boolean = false
    ): Bitmap {
        val s = sizePx.coerceIn(52, 132)
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val cx = s * 0.5f
        val cy = s * 0.5f

        if (withSubtleCircleBg) {
            val pillR = s * 0.455f
            // 浅色主题下补一层极淡柔影，提升纯白圆枕的悬浮立体感
            if (!palette.isDarkSurface && !softBackgroundOnly) {
                val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        cx, cy + s * 0.03f, pillR * 1.08f,
                        intArrayOf(0x1C0E2444, 0x000E2444),
                        floatArrayOf(0.68f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(cx, cy + s * 0.03f, pillR * 1.08f, shadowPaint)
            }

            val isFavActive = icon == BreezeWidgetControlIcon.FAVORITE_ON
            val isModeActive = icon == BreezeWidgetControlIcon.PLAY_MODE_SINGLE ||
                icon == BreezeWidgetControlIcon.PLAY_MODE_SHUFFLE
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = when {
                    isFavActive -> LinearGradient(
                        cx, cy - pillR, cx, cy + pillR,
                        withAlpha(palette.favoriteActive, if (palette.isDarkSurface) 0.30f else 0.20f),
                        withAlpha(palette.favoriteActive, if (palette.isDarkSurface) 0.16f else 0.10f),
                        Shader.TileMode.CLAMP
                    )
                    isModeActive -> LinearGradient(
                        cx, cy - pillR, cx, cy + pillR,
                        withAlpha(palette.accentPrimary, if (palette.isDarkSurface) 0.26f else 0.18f),
                        withAlpha(palette.accentPrimary, if (palette.isDarkSurface) 0.14f else 0.09f),
                        Shader.TileMode.CLAMP
                    )
                    palette.isDarkSurface -> LinearGradient(
                        cx, cy - pillR, cx, cy + pillR,
                        withAlpha(Color.White, if (softBackgroundOnly) 0.12f else 0.18f),
                        withAlpha(Color.White, if (softBackgroundOnly) 0.06f else 0.10f),
                        Shader.TileMode.CLAMP
                    )
                    else -> LinearGradient(
                        cx, cy - pillR, cx, cy + pillR,
                        withAlpha(palette.controlButtonBg, if (softBackgroundOnly) 0.72f else 0.96f),
                        withAlpha(lerp(palette.controlButtonBg, palette.cardGradientEnd, 0.25f), if (softBackgroundOnly) 0.56f else 0.86f),
                        Shader.TileMode.CLAMP
                    )
                }
            }
            canvas.drawCircle(cx, cy, pillR, bgPaint)

            // 精致顶部微光内沿
            val rimStroke = max(1.2f, s * 0.016f)
            val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = rimStroke
                shader = LinearGradient(
                    cx, cy - pillR, cx, cy + pillR,
                    when {
                        isFavActive -> withAlpha(palette.favoriteActive, 0.45f)
                        palette.isDarkSurface -> 0x30FFFFFF
                        else -> 0xD8FFFFFF.toInt()
                    },
                    when {
                        palette.isDarkSurface -> 0x08FFFFFF
                        else -> 0x1A1B2E48
                    },
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(cx, cy, pillR - rimStroke * 0.5f, rimPaint)
        }

        // 图标核心尺寸提升至 0.275 * s，更圆润扎实、一眼可辨
        val unit = s * 0.275f
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
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tintArgb
            style = Paint.Style.FILL
        }
        val dir = if (isNext) 1f else -1f

        // 真贝塞尔圆角饱满箭头主体（紧贴圆润竖柱，比例和谐厚实）
        val triPath = createRoundedPolygonPath(
            points = listOf(
                (cx - dir * unit * 0.78f) to (cy - unit * 0.68f),
                (cx + dir * unit * 0.34f) to cy,
                (cx - dir * unit * 0.78f) to (cy + unit * 0.68f)
            ),
            cornerRadius = unit * 0.28f
        )
        canvas.drawPath(triPath, fillPaint)

        // 圆润饱满胶囊挡柱
        val barW = unit * 0.34f
        val barH = unit * 1.38f
        val barX = if (isNext) cx + unit * 0.44f else cx - unit * 0.44f - barW
        canvas.drawRoundRect(
            RectF(barX, cy - barH * 0.5f, barX + barW, cy + barH * 0.5f),
            barW * 0.5f, barW * 0.5f, fillPaint
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
        val w = unit * 2.15f
        val h = unit * 2.02f
        val left = cx - w * 0.5f
        val top = cy - h * 0.47f

        // 顶部双瓣更饱满圆滑、底部柔和收圆（杜绝尖刺底角）
        val path = Path().apply {
            moveTo(cx, top + h * 0.89f)
            cubicTo(
                left - w * 0.05f, top + h * 0.60f,
                left - w * 0.04f, top + h * 0.12f,
                left + w * 0.27f, top + h * 0.09f
            )
            cubicTo(
                left + w * 0.41f, top + h * 0.07f,
                left + w * 0.48f, top + h * 0.19f,
                cx, top + h * 0.28f
            )
            cubicTo(
                left + w * 0.52f, top + h * 0.19f,
                left + w * 0.59f, top + h * 0.07f,
                left + w * 0.73f, top + h * 0.09f
            )
            cubicTo(
                left + w * 1.04f, top + h * 0.12f,
                left + w * 1.05f, top + h * 0.60f,
                cx, top + h * 0.89f
            )
            close()
        }
        if (filled) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    left, top, left + w, top + h,
                    intArrayOf(0xFFFF8EB0.toInt(), 0xFFFF5283.toInt(), 0xFFE32F65.toInt()),
                    floatArrayOf(0f, 0.50f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawPath(path, fillPaint)
            // 左上温润釉面高光短弧
            val gloss = Path().apply {
                moveTo(left + w * 0.21f, top + h * 0.37f)
                cubicTo(left + w * 0.19f, top + h * 0.24f, left + w * 0.27f, top + h * 0.18f, left + w * 0.36f, top + h * 0.20f)
            }
            val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(2.2f, unit * 0.15f)
                strokeCap = Paint.Cap.ROUND
                color = 0x82FFFFFF.toInt()
            }
            canvas.drawPath(gloss, glossPaint)
        } else {
            // 未喜欢态：柔和内衬淡彩 + 厚实圆角描边，拒绝干瘪细线
            val softFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = withAlpha(palette.controlIconTint, if (palette.isDarkSurface) 0.11f else 0.08f)
            }
            canvas.drawPath(path, softFill)

            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(2.8f, unit * 0.22f)
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
        val stroke = max(2.8f, unit * 0.22f)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tintArgb
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tintArgb
            style = Paint.Style.FILL
        }

        when (mode) {
            PlayMode.SHUFFLE -> {
                val p1 = Path().apply {
                    moveTo(cx - unit * 0.84f, cy + unit * 0.50f)
                    cubicTo(cx - unit * 0.16f, cy + unit * 0.50f, cx + unit * 0.10f, cy - unit * 0.50f, cx + unit * 0.62f, cy - unit * 0.50f)
                }
                val p2 = Path().apply {
                    moveTo(cx - unit * 0.84f, cy - unit * 0.50f)
                    cubicTo(cx - unit * 0.16f, cy - unit * 0.50f, cx + unit * 0.10f, cy + unit * 0.50f, cx + unit * 0.62f, cy + unit * 0.50f)
                }
                canvas.drawPath(p1, strokePaint)
                canvas.drawPath(p2, strokePaint)

                // 饱满实心圆角小箭头替代生硬折线
                drawRoundedArrowHead(canvas, cx + unit * 0.64f, cy - unit * 0.50f, unit * 0.36f, pointingRight = true, headPaint)
                drawRoundedArrowHead(canvas, cx + unit * 0.64f, cy + unit * 0.50f, unit * 0.36f, pointingRight = true, headPaint)
            }
            PlayMode.REPEAT_ALL, PlayMode.REPEAT_ONE -> {
                val halfW = unit * 0.78f
                val halfH = unit * 0.56f
                val cornerR = unit * 0.36f
                val topY = cy - halfH
                val botY = cy + halfH

                // 上半圆润跑道弧 + 向右饱满箭头
                val topArc = Path().apply {
                    moveTo(cx - halfW, cy - unit * 0.08f)
                    lineTo(cx - halfW, topY + cornerR)
                    quadTo(cx - halfW, topY, cx - halfW + cornerR, topY)
                    lineTo(cx + unit * 0.54f, topY)
                }
                // 下半圆润跑道弧 + 向左饱满箭头
                val botArc = Path().apply {
                    moveTo(cx + halfW, cy + unit * 0.08f)
                    lineTo(cx + halfW, botY - cornerR)
                    quadTo(cx + halfW, botY, cx + halfW - cornerR, botY)
                    lineTo(cx - unit * 0.54f, botY)
                }
                canvas.drawPath(topArc, strokePaint)
                canvas.drawPath(botArc, strokePaint)

                drawRoundedArrowHead(canvas, cx + unit * 0.56f, topY, unit * 0.34f, pointingRight = true, headPaint)
                drawRoundedArrowHead(canvas, cx - unit * 0.56f, botY, unit * 0.34f, pointingRight = false, headPaint)

                if (mode == PlayMode.REPEAT_ONE) {
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = tintArgb
                        textSize = unit * 0.88f
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

    private fun drawRoundedArrowHead(
        canvas: Canvas,
        baseX: Float,
        centerY: Float,
        size: Float,
        pointingRight: Boolean,
        paint: Paint
    ) {
        val dir = if (pointingRight) 1f else -1f
        val path = createRoundedPolygonPath(
            points = listOf(
                (baseX - dir * size * 0.25f) to (centerY - size * 0.88f),
                (baseX + dir * size * 0.95f) to centerY,
                (baseX - dir * size * 0.25f) to (centerY + size * 0.88f)
            ),
            cornerRadius = size * 0.26f
        )
        canvas.drawPath(path, paint)
    }

    /**
     * 通过二次贝塞尔圆角构造真正圆润饱满的多边形路径（彻底消除尖角和描边膨胀锯齿）。
     */
    private fun createRoundedPolygonPath(
        points: List<Pair<Float, Float>>,
        cornerRadius: Float
    ): Path {
        val path = Path()
        val n = points.size
        if (n < 3) return path
        for (i in 0 until n) {
            val prev = points[(i - 1 + n) % n]
            val curr = points[i]
            val next = points[(i + 1) % n]

            val dx1 = prev.first - curr.first
            val dy1 = prev.second - curr.second
            val len1 = hypot(dx1, dy1).coerceAtLeast(1e-3f)

            val dx2 = next.first - curr.first
            val dy2 = next.second - curr.second
            val len2 = hypot(dx2, dy2).coerceAtLeast(1e-3f)

            val r = min(cornerRadius, min(len1, len2) * 0.42f)
            val startX = curr.first + (dx1 / len1) * r
            val startY = curr.second + (dy1 / len1) * r
            val endX = curr.first + (dx2 / len2) * r
            val endY = curr.second + (dy2 / len2) * r

            if (i == 0) {
                path.moveTo(startX, startY)
            } else {
                path.lineTo(startX, startY)
            }
            path.quadTo(curr.first, curr.second, endX, endY)
        }
        path.close()
        return path
    }

    /**
     * 绘制圆润饱满胶囊进度条（含内发光激活轨道与柔光珍珠端钮）
     */
    fun renderProgressBar(
        widthPx: Int,
        heightPx: Int,
        progressFraction: Float,
        palette: BreezeWidgetPalette
    ): Bitmap {
        val w = widthPx.coerceIn(120, 460)
        val h = heightPx.coerceIn(12, 28)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val trackH = (h * 0.44f).coerceAtLeast(4.5f)
        val cy = h * 0.5f
        val padX = h * 0.48f
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

            // 柔光珍珠端点：外圈主题柔晕 + 饱满圆点 + 中心微亮核
            val knobR = (trackH * 0.88f).coerceAtMost(h * 0.44f)
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    activeRight, cy, knobR * 1.45f,
                    intArrayOf(withAlpha(palette.progressActiveEnd, 0.46f), 0x00000000),
                    floatArrayOf(0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(activeRight, cy, knobR * 1.45f, haloPaint)

            val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.progressActiveEnd.toArgb()
            }
            canvas.drawCircle(activeRight, cy, knobR, knobPaint)

            val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xE6FFFFFF.toInt()
            }
            canvas.drawCircle(activeRight, cy, knobR * 0.44f, corePaint)
        }
        return out
    }

    /**
     * 绘制饱满全胶囊音质徽标（Hi-Res / SQ / HQ / FLAC），保证不同主题下色彩统一与零裁剪
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
            textSize = heightPx * 0.55f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val textW = textPaint.measureText(clean)
        val padH = heightPx * 0.46f
        val w = (textW + padH * 2f).roundToInt().coerceIn(heightPx + 8, 196)
        val out = Bitmap.createBitmap(w, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val radius = heightPx * 0.5f
        val rect = RectF(0f, 0f, w.toFloat(), heightPx.toFloat())
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor.toArgb()
        }
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
            color = withAlpha(fgColor, if (palette.isDarkSurface) 0.28f else 0.18f)
        }
        canvas.drawRoundRect(RectF(0.6f, 0.6f, w - 0.6f, heightPx - 0.6f), radius, radius, borderPaint)

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
