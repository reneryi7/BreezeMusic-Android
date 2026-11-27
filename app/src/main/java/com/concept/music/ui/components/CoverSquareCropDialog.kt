package com.concept.music.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.concept.music.ui.theme.ConceptMotion
import com.concept.music.ui.theme.ConceptPrimary
import com.concept.music.util.SecurityGuards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 沉浸式 1:1 歌单封面拖动缩放裁剪弹窗：
 * - 安全解码（API 28+ 自动校正 EXIF 旋转并防图像解压炸弹，最大预览边 2048px）；
 * - 支持单指拖动、双指捏合缩放（1x ~ 4x）、双击快速复位，并实时约束图片边缘严丝合缝贴合 1:1 取景框；
 * - 拖动/缩放期间淡入精致 3×3 三分构图线，确认后按所见即所得矩阵导出高清 1:1 JPEG（≤ 1000×1000）。
 */
@Composable
fun CoverSquareCropDialog(
    imageUri: Uri,
    title: String = "裁剪歌单封面",
    accentColor: Color = ConceptPrimary,
    onDismiss: () -> Unit,
    onConfirmCroppedJpeg: (ByteArray) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var sourceBitmap by remember(imageUri) { mutableStateOf<Bitmap?>(null) }
    var loadError by remember(imageUri) { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    LaunchedEffect(imageUri) {
        loadError = null
        val decoded = withContext(Dispatchers.IO) {
            decodeSafeCropBitmap(context, imageUri)
        }
        if (decoded != null) {
            sourceBitmap = decoded
        } else {
            loadError = "无法解析所选图片，请换一张 JPG/PNG/WebP 图片重试"
        }
    }

    DisposableEffect(sourceBitmap) {
        val current = sourceBitmap
        onDispose {
            if (current != null && !current.isRecycled) {
                runCatching { current.recycle() }
            }
        }
    }

    var userZoom by remember(imageUri) { mutableFloatStateOf(1f) }
    var offsetX by remember(imageUri) { mutableFloatStateOf(0f) }
    var offsetY by remember(imageUri) { mutableFloatStateOf(0f) }
    var isInteracting by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(interactionTick) {
        if (interactionTick > 0) {
            isInteracting = true
            delay(520L)
            isInteracting = false
        }
    }

    val gridAlpha by animateFloatAsState(
        targetValue = if (isInteracting) 0.58f else 0.14f,
        animationSpec = tween(durationMillis = 220),
        label = "cropGridAlpha"
    )

    Dialog(
        onDismissRequest = { if (!isExporting) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF070A12))
                .systemBarsPadding()
        ) {
            val isLandscape = maxWidth > maxHeight
            val cropSideDp = if (isLandscape) {
                (maxHeight * 0.64f).coerceIn(220.dp, 400.dp)
            } else {
                (maxWidth * 0.84f).coerceIn(240.dp, 420.dp)
            }
            val cropSidePx = with(density) { cropSideDp.toPx() }

            fun clampOffsets(rawX: Float, rawY: Float, zoom: Float, bmp: Bitmap): Pair<Float, Float> {
                val baseScale = max(cropSidePx / bmp.width.toFloat(), cropSidePx / bmp.height.toFloat())
                val totalScale = baseScale * zoom
                val drawnW = bmp.width * totalScale
                val drawnH = bmp.height * totalScale
                val maxX = ((drawnW - cropSidePx) / 2f).coerceAtLeast(0f)
                val maxY = ((drawnH - cropSidePx) / 2f).coerceAtLeast(0f)
                return rawX.coerceIn(-maxX, maxX) to rawY.coerceIn(-maxY, maxY)
            }

            // 顶部工具栏
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    onClick = { if (!isExporting) onDismiss() },
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.11f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "取消裁剪",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "双指缩放 · 拖动调整 1:1 构图",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 11.5.sp
                    )
                }

                Surface(
                    onClick = {
                        userZoom = 1f
                        offsetX = 0f
                        offsetY = 0f
                        interactionTick++
                    },
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.11f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "重置构图",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            // 中央裁剪画布舞台
            val bmp = sourceBitmap
            if (bmp != null && !bmp.isRecycled) {
                val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                val baseScale = remember(bmp, cropSidePx) {
                    max(cropSidePx / bmp.width.toFloat(), cropSidePx / bmp.height.toFloat())
                }
                val baseDrawWidthDp = with(density) { (bmp.width * baseScale).toDp() }
                val baseDrawHeightDp = with(density) { (bmp.height * baseScale).toDp() }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 78.dp)
                        .clipToBounds()
                        .pointerInput(bmp, cropSidePx) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (userZoom > 1.05f) {
                                        userZoom = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                    } else {
                                        userZoom = 2f
                                        val (cx, cy) = clampOffsets(offsetX, offsetY, 2f, bmp)
                                        offsetX = cx
                                        offsetY = cy
                                    }
                                    interactionTick++
                                }
                            )
                        }
                        .pointerInput(bmp, cropSidePx) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val nextZoom = (userZoom * zoom).coerceIn(1f, 4f)
                                val (clampedX, clampedY) = clampOffsets(
                                    rawX = offsetX + pan.x,
                                    rawY = offsetY + pan.y,
                                    zoom = nextZoom,
                                    bmp = bmp
                                )
                                userZoom = nextZoom
                                offsetX = clampedX
                                offsetY = clampedY
                                interactionTick++
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .requiredSize(width = baseDrawWidthDp, height = baseDrawHeightDp)
                            .graphicsLayer {
                                scaleX = userZoom
                                scaleY = userZoom
                                translationX = offsetX
                                translationY = offsetY
                            }
                    )

                    // 暗角镂空遮罩 + 圆角 1:1 框 + 三分参考线
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    ) {
                        val left = (size.width - cropSidePx) / 2f
                        val top = (size.height - cropSidePx) / 2f
                        val cornerPx = 22.dp.toPx()

                        drawRect(color = Color.Black.copy(alpha = 0.64f))
                        drawRoundRect(
                            color = Color.Transparent,
                            topLeft = Offset(left, top),
                            size = Size(cropSidePx, cropSidePx),
                            cornerRadius = CornerRadius(cornerPx, cornerPx),
                            blendMode = BlendMode.Clear
                        )

                        // 三分构图细线
                        val third = cropSidePx / 3f
                        val gridColor = Color.White.copy(alpha = gridAlpha)
                        for (i in 1..2) {
                            drawLine(
                                color = gridColor,
                                start = Offset(left + third * i, top + 8.dp.toPx()),
                                end = Offset(left + third * i, top + cropSidePx - 8.dp.toPx()),
                                strokeWidth = 0.9.dp.toPx()
                            )
                            drawLine(
                                color = gridColor,
                                start = Offset(left + 8.dp.toPx(), top + third * i),
                                end = Offset(left + cropSidePx - 8.dp.toPx(), top + third * i),
                                strokeWidth = 0.9.dp.toPx()
                            )
                        }

                        // 外边框描边
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.86f),
                            topLeft = Offset(left, top),
                            size = Size(cropSidePx, cropSidePx),
                            cornerRadius = CornerRadius(cornerPx, cornerPx),
                            style = Stroke(width = 1.6.dp.toPx())
                        )
                    }
                }
            } else if (loadError != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text = loadError ?: "图片加载失败", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White)
                    ) {
                        Text("返回", fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                CircularProgressIndicator(
                    color = accentColor,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // 底部确认操作栏
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = { if (!isExporting) onDismiss() },
                    shape = RoundedCornerShape(999.dp),
                    color = Color.White.copy(alpha = 0.12f),
                    modifier = Modifier.height(46.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "取消",
                            color = Color.White.copy(alpha = 0.92f),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                }

                Surface(
                    onClick = {
                        val activeBmp = sourceBitmap
                        if (activeBmp == null || activeBmp.isRecycled || isExporting) return@Surface
                        isExporting = true
                        scope.launch {
                            val jpegBytes = withContext(Dispatchers.Default) {
                                renderCroppedSquareJpeg(
                                    source = activeBmp,
                                    cropSidePx = cropSidePx,
                                    userZoom = userZoom,
                                    offsetX = offsetX,
                                    offsetY = offsetY,
                                    maxOutputSidePx = 1000
                                )
                            }
                            isExporting = false
                            if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                                onConfirmCroppedJpeg(jpegBytes)
                            } else {
                                loadError = "封面裁剪导出失败，请重试"
                            }
                        }
                    },
                    enabled = sourceBitmap != null && !isExporting,
                    shape = RoundedCornerShape(999.dp),
                    color = accentColor,
                    modifier = Modifier.height(46.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 26.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(17.dp)
                            )
                            Text("处理中...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                            Text("确认使用", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun decodeSafeCropBitmap(context: Context, uri: Uri): Bitmap? {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val rawW = info.size.width
                val rawH = info.size.height
                if (rawW <= 0 || rawH <= 0 ||
                    rawW > SecurityGuards.MAX_IMAGE_DIMENSION_PX ||
                    rawH > SecurityGuards.MAX_IMAGE_DIMENSION_PX ||
                    rawW.toLong() * rawH.toLong() > SecurityGuards.MAX_IMAGE_TOTAL_PIXELS
                ) {
                    throw IllegalArgumentException("图片尺寸超过安全上限")
                }
                val maxSide = max(rawW, rawH)
                if (maxSide > 2048) {
                    val ratio = 2048f / maxSide.toFloat()
                    decoder.setTargetSize(
                        (rawW * ratio).roundToInt().coerceAtLeast(1),
                        (rawH * ratio).roundToInt().coerceAtLeast(1)
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } else {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                SecurityGuards.readBoundedBytes(input, SecurityGuards.MAX_COVER_IMAGE_BYTES)
            } ?: return@runCatching null
            SecurityGuards.decodeBoundedBitmap(
                bytes = bytes,
                maxBytes = SecurityGuards.MAX_COVER_IMAGE_BYTES,
                targetMaxDimensionPx = 2048
            )
        }
    }.getOrNull()
}

internal fun renderCroppedSquareJpeg(
    source: Bitmap,
    cropSidePx: Float,
    userZoom: Float,
    offsetX: Float,
    offsetY: Float,
    maxOutputSidePx: Int = 1000
): ByteArray? {
    if (source.isRecycled || source.width <= 0 || source.height <= 0 || cropSidePx <= 0f) return null
    return runCatching {
        val imgW = source.width.toFloat()
        val imgH = source.height.toFloat()
        val baseScale = max(cropSidePx / imgW, cropSidePx / imgH)
        val totalScale = (baseScale * userZoom.coerceIn(1f, 4f)).coerceAtLeast(0.001f)
        val drawnW = imgW * totalScale
        val drawnH = imgH * totalScale

        val cropLeftDrawn = (drawnW - cropSidePx) / 2f - offsetX
        val cropTopDrawn = (drawnH - cropSidePx) / 2f - offsetY

        val srcLeft = (cropLeftDrawn / totalScale).roundToInt().coerceIn(0, source.width - 1)
        val srcTop = (cropTopDrawn / totalScale).roundToInt().coerceIn(0, source.height - 1)
        val maxAvailSide = min(source.width - srcLeft, source.height - srcTop).coerceAtLeast(1)
        val srcSide = (cropSidePx / totalScale).roundToInt().coerceIn(1, maxAvailSide)

        val outSide = srcSide.coerceIn(1, maxOutputSidePx)
        val output = Bitmap.createBitmap(outSide, outSide, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            // 先铺底色，避免透明 PNG 转 JPEG 时出现异常边缘噪点
            canvas.drawColor(android.graphics.Color.BLACK)
            val srcRect = Rect(srcLeft, srcTop, srcLeft + srcSide, srcTop + srcSide)
            val dstRect = Rect(0, 0, outSide, outSide)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(source, srcRect, dstRect, paint)

            val baos = ByteArrayOutputStream(128 * 1024)
            if (!output.compress(Bitmap.CompressFormat.JPEG, 90, baos)) return@runCatching null
            baos.toByteArray()
        } finally {
            runCatching { output.recycle() }
        }
    }.getOrNull()
}
