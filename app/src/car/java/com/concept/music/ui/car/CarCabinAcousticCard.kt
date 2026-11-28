package com.concept.music.ui.car

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.concept.music.service.CarCabinAcousticMode
import com.concept.music.service.CarSessionManager
import com.concept.music.ui.theme.CarRenderCapabilities
import com.concept.music.ui.theme.ConceptCoralAccent
import com.concept.music.ui.theme.ConceptCyanAccent
import com.concept.music.ui.theme.ConceptMintAccent
import com.concept.music.ui.theme.ConceptPrimary
import com.concept.music.ui.theme.rememberIsLifecycleStarted
import com.concept.music.ui.theme.softCardClickable
import kotlin.math.abs
import kotlin.math.sin

/**
 * 64-bit 车机座舱声学可视化卡片（俯视「车厢皇帝位」声场光环 + 智能导航语音闪避快捷调节）：
 * - 严格践行车机极简安全原则：只呈现 4 大车载声学模式与 3 档导航闪避，彻底剥离 26 款手机蝰蛇与 10 段工程滑杆；
 * - 车厢轮廓与四座几何通过 `drawWithCache` 缓存，ECO 省电档自动静态化零帧循环唤醒。
 */
@Composable
internal fun CarCabinAcousticOverlayCard(
    renderCaps: CarRenderCapabilities,
    accentColor: Color,
    onDismiss: () -> Unit
) {
    val carState by CarSessionManager.state.collectAsState()
    val isStarted = rememberIsLifecycleStarted()
    val animateWaves = isStarted && renderCaps.enableAnimatedBackground

    val wavePhase = if (animateWaves) {
        val infinite = rememberInfiniteTransition(label = "carCabinWave")
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "carCabinWavePhase"
        ).value
    } else {
        0.35f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB8040812))
            .softCardClickable(shape = RoundedCornerShape(0.dp), clip = false, pressScale = 1f, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = Color(0xF50B1322),
            contentColor = Color.White,
            modifier = Modifier
                .widthIn(max = 860.dp)
                .fillMaxWidth(0.90f)
                .heightIn(max = 580.dp)
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(30.dp))
                .softCardClickable(shape = RoundedCornerShape(30.dp), clip = false, pressScale = 1f, onClick = {})
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 顶栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ConceptCyanAccent.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = ConceptCyanAccent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "64-bit 座舱声学矩阵",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Text(
                                text = "实时母带 DSP · 当前：${carState.cabinAcousticMode.label}",
                                fontSize = 13.sp,
                                color = ConceptCyanAccent
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.10f),
                        modifier = Modifier
                            .size(46.dp)
                            .softCardClickable(shape = CircleShape, onClick = onDismiss)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "关闭",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // 中央主体：左俯视可视化车厢声场 + 右四模式超大盲操卡
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 俯视车厢皇帝位声场画布
                    Box(
                        modifier = Modifier
                            .width(230.dp)
                            .height(270.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        accentColor.copy(alpha = 0.16f),
                                        Color(0xFF070C17)
                                    )
                                )
                            )
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        CabinTopDownAcousticCanvas(
                            mode = carState.cabinAcousticMode,
                            wavePhase = wavePhase,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        )
                    }

                    // 4 大声学模式盲操卡片
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CarCabinAcousticMode.entries.forEach { mode ->
                            val selected = carState.cabinAcousticMode == mode
                            val highlight = when (mode) {
                                CarCabinAcousticMode.DRIVER_SWEET_SPOT -> ConceptCyanAccent
                                CarCabinAcousticMode.FULL_CABIN_SURROUND -> ConceptMintAccent
                                CarCabinAcousticMode.CAR_SUBWOOFER -> ConceptCoralAccent
                                CarCabinAcousticMode.DIRECT_BYPASS -> ConceptPrimary
                            }
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (selected) highlight.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) highlight.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.07f),
                                        shape = RoundedCornerShape(18.dp)
                                    )
                                    .softCardClickable(
                                        shape = RoundedCornerShape(18.dp),
                                        onClick = { CarSessionManager.setCabinAcousticMode(mode) }
                                    )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (selected) highlight else Color.White.copy(alpha = 0.25f))
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = mode.label,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (selected) highlight else Color.White,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = mode.subtitle,
                                            fontSize = 12.sp,
                                            color = Color.White.copy(alpha = 0.68f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 底部：智能导航语音平滑闪避快捷条
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.06f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = Icons.Rounded.Navigation,
                                    contentDescription = null,
                                    tint = ConceptMintAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "智能导航语音平滑闪避",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "180ms 渐隐 · 350ms 防抽搐保持 · 650ms 渐显恢复",
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.62f)
                                    )
                                }
                            }
                            Switch(
                                checked = carState.smartNavDuckingEnabled,
                                onCheckedChange = { CarSessionManager.setSmartNavDuckingEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = ConceptMintAccent
                                )
                            )
                        }

                        if (carState.smartNavDuckingEnabled) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                listOf("轻柔 20%" to 0.20f, "均衡 28%" to 0.28f, "饱满 40%" to 0.40f).forEach { (label, gain) ->
                                    val active = abs(carState.navDuckingTargetGain - gain) < 0.025f
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (active) ConceptMintAccent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.07f),
                                        modifier = Modifier
                                            .weight(1f)
                                            .softCardClickable(
                                                shape = RoundedCornerShape(14.dp),
                                                onClick = { CarSessionManager.setNavDuckingTargetGain(gain) }
                                            )
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (active) ConceptMintAccent else Color.White.copy(alpha = 0.80f)
                                            )
                                        }
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (carState.navSpectralDuckingEnabled) {
                                        ConceptCyanAccent.copy(alpha = 0.22f)
                                    } else {
                                        Color.White.copy(alpha = 0.07f)
                                    },
                                    modifier = Modifier
                                        .softCardClickable(
                                            shape = RoundedCornerShape(14.dp),
                                            onClick = {
                                                CarSessionManager.setNavSpectralDuckingEnabled(!carState.navSpectralDuckingEnabled)
                                            }
                                        )
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "1.85kHz 人声避让",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (carState.navSpectralDuckingEnabled) ConceptCyanAccent else Color.White.copy(alpha = 0.65f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CabinTopDownAcousticCanvas(
    mode: CarCabinAcousticMode,
    wavePhase: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val bodyLeft = w * 0.16f
            val bodyRight = w * 0.84f
            val bodyTop = h * 0.06f
            val bodyBottom = h * 0.94f
            val bodyW = bodyRight - bodyLeft
            val bodyH = bodyBottom - bodyTop

            val windshieldPath = Path().apply {
                moveTo(bodyLeft + bodyW * 0.14f, bodyTop + bodyH * 0.24f)
                quadraticBezierTo(w * 0.50f, bodyTop + bodyH * 0.14f, bodyRight - bodyW * 0.14f, bodyTop + bodyH * 0.24f)
            }
            val rearGlassPath = Path().apply {
                moveTo(bodyLeft + bodyW * 0.18f, bodyBottom - bodyH * 0.18f)
                quadraticBezierTo(w * 0.50f, bodyBottom - bodyH * 0.12f, bodyRight - bodyW * 0.18f, bodyBottom - bodyH * 0.18f)
            }

            val seatW = bodyW * 0.26f
            val seatH = bodyH * 0.17f
            val frontY = bodyTop + bodyH * 0.34f
            val rearY = bodyTop + bodyH * 0.60f
            val leftSeatX = bodyLeft + bodyW * 0.15f
            val rightSeatX = bodyRight - bodyW * 0.15f - seatW

            val driverCenter = Offset(leftSeatX + seatW * 0.5f, frontY + seatH * 0.5f)
            val cabinCenter = Offset(w * 0.5f, bodyTop + bodyH * 0.52f)
            val trunkCenter = Offset(w * 0.5f, bodyBottom - bodyH * 0.12f)

            onDrawBehind {
                // 车身外轮廓
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.05f),
                    topLeft = Offset(bodyLeft, bodyTop),
                    size = Size(bodyW, bodyH),
                    cornerRadius = CornerRadius(bodyW * 0.32f, bodyW * 0.32f)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.24f),
                    topLeft = Offset(bodyLeft, bodyTop),
                    size = Size(bodyW, bodyH),
                    cornerRadius = CornerRadius(bodyW * 0.32f, bodyW * 0.32f),
                    style = Stroke(width = 1.6.dp.toPx())
                )
                // 前后风挡弧线
                drawPath(
                    path = windshieldPath,
                    color = Color.White.copy(alpha = 0.22f),
                    style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
                )
                drawPath(
                    path = rearGlassPath,
                    color = Color.White.copy(alpha = 0.15f),
                    style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
                )

                // 四张座椅轮廓
                val seats = listOf(
                    Offset(leftSeatX, frontY) to true,
                    Offset(rightSeatX, frontY) to false,
                    Offset(leftSeatX, rearY) to false,
                    Offset(rightSeatX, rearY) to false
                )
                seats.forEach { (topLeft, isDriver) ->
                    val seatHighlight = when {
                        mode == CarCabinAcousticMode.DRIVER_SWEET_SPOT && isDriver -> ConceptCyanAccent
                        mode == CarCabinAcousticMode.FULL_CABIN_SURROUND -> ConceptMintAccent
                        mode == CarCabinAcousticMode.CAR_SUBWOOFER -> ConceptCoralAccent
                        else -> Color.White.copy(alpha = 0.45f)
                    }
                    drawRoundRect(
                        color = seatHighlight.copy(alpha = if (isDriver || mode == CarCabinAcousticMode.FULL_CABIN_SURROUND) 0.24f else 0.08f),
                        topLeft = topLeft,
                        size = Size(seatW, seatH),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                    )
                    drawRoundRect(
                        color = seatHighlight.copy(alpha = 0.55f),
                        topLeft = topLeft,
                        size = Size(seatW, seatH),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                        style = Stroke(width = 1.1.dp.toPx())
                    )
                }

                // 动态声场波纹
                when (mode) {
                    CarCabinAcousticMode.DRIVER_SWEET_SPOT -> {
                        for (i in 0 until 3) {
                            val p = (wavePhase + i / 3f) % 1f
                            val r = bodyW * (0.14f + p * 0.48f)
                            val a = (1f - p).coerceIn(0f, 1f) * 0.65f
                            drawCircle(
                                color = ConceptCyanAccent.copy(alpha = a),
                                radius = r,
                                center = driverCenter,
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                        // 仪表台声像抬升光弧
                        drawPath(
                            path = windshieldPath,
                            color = ConceptCyanAccent.copy(alpha = 0.72f),
                            style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
                        )
                        drawCircle(
                            color = ConceptCyanAccent,
                            radius = 5.dp.toPx(),
                            center = driverCenter
                        )
                    }
                    CarCabinAcousticMode.FULL_CABIN_SURROUND -> {
                        for (i in 0 until 3) {
                            val p = (wavePhase + i / 3f) % 1f
                            val r = bodyW * (0.18f + p * 0.62f)
                            val a = (1f - p).coerceIn(0f, 1f) * 0.62f
                            drawCircle(
                                color = ConceptMintAccent.copy(alpha = a),
                                radius = r,
                                center = cabinCenter,
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                        drawCircle(
                            color = ConceptMintAccent,
                            radius = 5.dp.toPx(),
                            center = cabinCenter
                        )
                    }
                    CarCabinAcousticMode.CAR_SUBWOOFER -> {
                        for (i in 0 until 3) {
                            val p = (wavePhase + i / 3f) % 1f
                            val r = bodyW * (0.16f + p * 0.60f)
                            val a = (1f - p).coerceIn(0f, 1f) * 0.68f
                            drawCircle(
                                color = ConceptCoralAccent.copy(alpha = a),
                                radius = r,
                                center = trunkCenter,
                                style = Stroke(width = 2.6.dp.toPx())
                            )
                        }
                        // 左右车门低频共鸣光点
                        drawCircle(ConceptCoralAccent.copy(alpha = 0.75f), 5.dp.toPx(), Offset(bodyLeft + 6.dp.toPx(), frontY + seatH * 0.5f))
                        drawCircle(ConceptCoralAccent.copy(alpha = 0.75f), 5.dp.toPx(), Offset(bodyRight - 6.dp.toPx(), frontY + seatH * 0.5f))
                    }
                    CarCabinAcousticMode.DIRECT_BYPASS -> {
                        val y = cabinCenter.y
                        val wavePath = Path().apply {
                            val startX = bodyLeft + 14.dp.toPx()
                            val endX = bodyRight - 14.dp.toPx()
                            val steps = 28
                            for (i in 0..steps) {
                                val t = i / steps.toFloat()
                                val x = startX + (endX - startX) * t
                                val amp = 10.dp.toPx() * sin((t * 3f + wavePhase * 2f) * Math.PI).toFloat()
                                if (i == 0) moveTo(x, y + amp) else lineTo(x, y + amp)
                            }
                        }
                        drawPath(
                            path = wavePath,
                            color = ConceptPrimary.copy(alpha = 0.85f),
                            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
            }
        }
    )
}
