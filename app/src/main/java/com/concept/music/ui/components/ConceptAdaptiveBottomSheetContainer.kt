package com.concept.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.concept.music.ui.theme.ConceptAnimatedOverlaySheet
import com.concept.music.ui.theme.ConceptOverlaySheetScope
import com.concept.music.ui.theme.LocalConceptAdaptiveInfo
import com.concept.music.ui.theme.conceptSheetContainerColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Stable
class ConceptAdaptiveSheetScope(
    private val overlayScope: ConceptOverlaySheetScope,
    private val columnScope: ColumnScope
) {
    val requestDismiss: () -> Unit
        get() = overlayScope.requestDismiss

    fun Modifier.sheetVerticalDragGesture(): Modifier =
        with(overlayScope) { this@sheetVerticalDragGesture.sheetVerticalDragGesture() }

    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier =
        with(columnScope) { this@weight.weight(weight, fill) }
}

/**
 * 全应用统一的手机/平板自适应浮层抽屉容器：
 * - 自动感知 `LocalConceptAdaptiveInfo.isLargeScreenDevice` 与横屏状态；
 * - 新增 `wrapContentHeight` 支持：短菜单类抽屉（如「歌单管理」）按内容真实高度紧贴渲染，杜绝底部大片留白。
 */
@Composable
fun ConceptAdaptiveBottomSheetContainer(
    onDismiss: () -> Unit,
    onDismissStart: () -> Unit = {},
    zIndex: Float = 90f,
    maxCardWidth: Dp = 560.dp,
    portraitMinHeight: Dp = 320.dp,
    portraitMaxHeight: Dp = 620.dp,
    wrapContentHeight: Boolean = false,
    containerColor: Color = conceptSheetContainerColor(),
    showDragHandle: Boolean = true,
    content: @Composable ConceptAdaptiveSheetScope.(isCardMode: Boolean) -> Unit
) {
    val configuration = LocalConfiguration.current
    val adaptiveInfo = LocalConceptAdaptiveInfo.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val isCardMode = isLandscape || adaptiveInfo.isLargeScreenDevice
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant

    val panelShape = if (isCardMode) {
        RoundedCornerShape(26.dp)
    } else {
        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    }

    ConceptAnimatedOverlaySheet(
        onDismiss = onDismiss,
        onDismissStart = onDismissStart,
        isLandscape = isCardMode,
        contentAlignment = if (isCardMode) Alignment.Center else Alignment.BottomCenter,
        zIndex = zIndex
    ) {
        Surface(
            shape = panelShape,
            color = containerColor,
            contentColor = textPrimary,
            shadowElevation = 24.dp,
            modifier = Modifier
                .then(
                    if (isCardMode) {
                        Modifier
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                            .widthIn(max = maxCardWidth)
                            .fillMaxWidth()
                            .then(
                                if (wrapContentHeight) {
                                    Modifier.heightIn(max = 660.dp).wrapContentHeight()
                                } else {
                                    Modifier
                                        .heightIn(min = portraitMinHeight, max = 660.dp)
                                        .fillMaxHeight(if (isLandscape) 0.88f else 0.78f)
                                }
                            )
                    } else {
                        Modifier
                            .widthIn(max = maxCardWidth)
                            .fillMaxWidth()
                            .then(
                                if (wrapContentHeight) {
                                    Modifier.heightIn(max = portraitMaxHeight).wrapContentHeight()
                                } else {
                                    Modifier.heightIn(min = portraitMinHeight, max = portraitMaxHeight)
                                }
                            )
                    }
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            CompositionLocalProvider(LocalContentColor provides textPrimary) {
                Column(
                    modifier = Modifier
                        .then(if (wrapContentHeight) Modifier.fillMaxWidth().wrapContentHeight() else Modifier.fillMaxSize())
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                        .then(if (!isCardMode) Modifier.navigationBarsPadding() else Modifier)
                ) {
                    if (showDragHandle && !isCardMode) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .sheetVerticalDragGesture()
                                .padding(top = 4.dp, bottom = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(textSecondary.copy(alpha = 0.28f))
                            )
                        }
                    }
                    val adaptiveSheetScope = remember(this@ConceptAnimatedOverlaySheet, this@Column) {
                        ConceptAdaptiveSheetScope(
                            overlayScope = this@ConceptAnimatedOverlaySheet,
                            columnScope = this@Column
                        )
                    }
                    adaptiveSheetScope.content(isCardMode)
                }
            }
        }
    }
}

/**
 * 严格对标 `PlayQueueBottomSheet` 物理手感的 LazyColumn 平滑弹簧拖拽重排控制器：
 * - 拖拽项支持跟手位移 + `animateFloatAsState(1.028f)` 浮起 + 松手无过冲弹簧落位；
 * - 邻项通过 `targetNeighborShiftPxForIndex(idx)` 驱动各自独立 `Animatable(0f)` 弹簧平滑让位，零阶跃生硬感；
 * - 独立边缘自动滚屏协程在手指贴近顶/底边时按帧持续丝滑滚屏并同步追踪目标槽位；
 * - 跨行换位时触发轻量 `onTargetIndexChanged` 触觉反馈回调。
 */
@Stable
class ConceptListDragReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val defaultRowHeightPx: Float,
    private val itemCountProvider: () -> Int,
    private val onCommitMove: (fromIndex: Int, toIndex: Int) -> Unit,
    private val headerItemCountProvider: () -> Int = { 0 },
    private val onTargetIndexChanged: (() -> Unit)? = null
) {
    var dragStartIndex by mutableStateOf<Int?>(null)
        private set
    var dragTargetIndex by mutableStateOf<Int?>(null)
        private set
    var dragOffsetY by mutableFloatStateOf(0f)
        private set
    var isSettlingDrop by mutableStateOf(false)
        private set
    val settleDropAnim = Animatable(0f)

    private var edgeAutoScrollJob: Job? = null
    private var activeEdgeVelocityPx: Float = 0f

    val isDragging: Boolean
        get() = dragStartIndex != null

    fun resolveRowStridePx(): Float {
        val headerCount = headerItemCountProvider().coerceAtLeast(0)
        val visible = listState.layoutInfo.visibleItemsInfo.filter { it.index >= headerCount }
        if (visible.size >= 2) {
            val diff = (visible[1].offset - visible[0].offset).toFloat()
            if (diff > 24f) return diff
        }
        return (visible.firstOrNull()?.size?.toFloat() ?: defaultRowHeightPx).coerceAtLeast(40f)
    }

    private fun updateTargetFromOffset() {
        val startIdx = dragStartIndex ?: return
        val lastIdx = (itemCountProvider() - 1).coerceAtLeast(0)
        val stride = resolveRowStridePx()
        var currentShift = (dragTargetIndex ?: startIdx) - startIdx
        val rawShift = dragOffsetY / stride
        while (rawShift - currentShift >= 0.48f && startIdx + currentShift < lastIdx) {
            currentShift += 1
        }
        while (rawShift - currentShift <= -0.48f && startIdx + currentShift > 0) {
            currentShift -= 1
        }
        val nextTarget = (startIdx + currentShift).coerceIn(0, lastIdx)
        if (nextTarget != dragTargetIndex) {
            dragTargetIndex = nextTarget
            onTargetIndexChanged?.invoke()
        }
    }

    private fun updateEdgeAutoScrollVelocity() {
        val startIdx = dragStartIndex ?: run {
            stopEdgeAutoScroll()
            return
        }
        val headerCount = headerItemCountProvider().coerceAtLeast(0)
        val layoutInfo = listState.layoutInfo
        val startInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == startIdx + headerCount }
        val stride = resolveRowStridePx()
        if (startInfo == null) {
            activeEdgeVelocityPx = 0f
            return
        }
        val vpStart = layoutInfo.viewportStartOffset.toFloat()
        val vpEnd = layoutInfo.viewportEndOffset.toFloat()
        val centerY = startInfo.offset + dragOffsetY + startInfo.size / 2f
        val edgeZone = (stride * 1.45f).coerceAtLeast(88f)
        activeEdgeVelocityPx = when {
            centerY < vpStart + edgeZone && listState.canScrollBackward -> {
                val ratio = ((vpStart + edgeZone - centerY) / edgeZone).coerceIn(0.15f, 1f)
                -(10f + 16f * ratio)
            }
            centerY > vpEnd - edgeZone && listState.canScrollForward -> {
                val ratio = ((centerY - (vpEnd - edgeZone)) / edgeZone).coerceIn(0.15f, 1f)
                (10f + 16f * ratio)
            }
            else -> 0f
        }
        if (activeEdgeVelocityPx != 0f && edgeAutoScrollJob?.isActive != true) {
            edgeAutoScrollJob = scope.launch {
                while (isActive && dragStartIndex != null && !isSettlingDrop && activeEdgeVelocityPx != 0f) {
                    val step = activeEdgeVelocityPx
                    val consumed = listState.scrollBy(step)
                    if (consumed != 0f && dragStartIndex != null && !isSettlingDrop) {
                        val sIdx = dragStartIndex ?: break
                        val lastIdx = (itemCountProvider() - 1).coerceAtLeast(0)
                        val rowStride = resolveRowStridePx()
                        dragOffsetY = (dragOffsetY + consumed).coerceIn(
                            -sIdx * rowStride - rowStride * 0.25f,
                            (lastIdx - sIdx) * rowStride + rowStride * 0.25f
                        )
                        updateTargetFromOffset()
                        updateEdgeAutoScrollVelocity()
                    } else {
                        break
                    }
                    delay(16L)
                }
            }
        }
    }

    private fun stopEdgeAutoScroll() {
        activeEdgeVelocityPx = 0f
        edgeAutoScrollJob?.cancel()
        edgeAutoScrollJob = null
    }

    fun startDrag(index: Int) {
        val count = itemCountProvider()
        if (isSettlingDrop || index !in 0 until count) return
        stopEdgeAutoScroll()
        dragStartIndex = index
        dragTargetIndex = index
        dragOffsetY = 0f
    }

    fun onDragDelta(deltaY: Float) {
        val startIdx = dragStartIndex ?: return
        if (isSettlingDrop) return
        val lastIdx = (itemCountProvider() - 1).coerceAtLeast(0)
        dragOffsetY += deltaY
        val stride = resolveRowStridePx()
        val minOff = -startIdx * stride
        val maxOff = (lastIdx - startIdx) * stride
        dragOffsetY = dragOffsetY.coerceIn(minOff - stride * 0.25f, maxOff + stride * 0.25f)
        updateTargetFromOffset()
        updateEdgeAutoScrollVelocity()
    }

    fun finishDrag() {
        val startIdx = dragStartIndex ?: return
        val lastIdx = (itemCountProvider() - 1).coerceAtLeast(0)
        val targetIdx = (dragTargetIndex ?: startIdx).coerceIn(0, lastIdx)
        if (isSettlingDrop) return
        stopEdgeAutoScroll()
        val stride = resolveRowStridePx()
        val targetSettle = (targetIdx - startIdx) * stride
        isSettlingDrop = true
        scope.launch {
            settleDropAnim.snapTo(dragOffsetY)
            settleDropAnim.animateTo(
                targetValue = targetSettle,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
            if (startIdx != targetIdx && startIdx in 0..lastIdx && targetIdx in 0..lastIdx) {
                onCommitMove(startIdx, targetIdx)
            }
            dragStartIndex = null
            dragTargetIndex = null
            dragOffsetY = 0f
            isSettlingDrop = false
        }
    }

    /**
     * 供每一行计算邻项平滑让位的目标 Y 偏移（配合每行独立 `Animatable(0f)` 弹簧过渡，彻底消除阶跃卡顿）
     */
    fun targetNeighborShiftPxForIndex(index: Int): Float {
        val startIdx = dragStartIndex ?: return 0f
        val targetIdx = dragTargetIndex ?: startIdx
        if (index == startIdx) return 0f
        val stride = resolveRowStridePx()
        return when {
            startIdx < targetIdx && index in (startIdx + 1)..targetIdx -> -stride
            targetIdx < startIdx && index in targetIdx until startIdx -> stride
            else -> 0f
        }
    }

    /**
     * 拖拽过程中实时预览每一行当前对应的目标序号（0-based）
     */
    fun displayOrderIndexFor(index: Int): Int {
        val startIdx = dragStartIndex ?: return index
        val targetIdx = dragTargetIndex ?: startIdx
        return when {
            index == startIdx -> targetIdx
            startIdx < targetIdx && index in (startIdx + 1)..targetIdx -> index - 1
            targetIdx < startIdx && index in targetIdx until startIdx -> index + 1
            else -> index
        }
    }

    /**
     * 拖拽项自身的实时 Y 位移（拖拽中跟随手指，释放时跟随 `settleDropAnim` 弹簧落位）
     */
    fun activeDragTranslationY(): Float =
        if (isSettlingDrop) settleDropAnim.value else dragOffsetY

    fun translationYForIndex(index: Int): Float {
        val startIdx = dragStartIndex ?: return 0f
        return if (index == startIdx) activeDragTranslationY() else targetNeighborShiftPxForIndex(index)
    }
}
