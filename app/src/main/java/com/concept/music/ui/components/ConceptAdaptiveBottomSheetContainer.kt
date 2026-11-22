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
 * - 自动感知 `LocalConceptAdaptiveInfo.isLargeScreenDevice` 与横屏状态：
 *   - 平板横竖屏或手机横屏：居中悬浮大圆角卡片（宽度上限 <= 560dp，符合 Material 3 大屏浮层规范），带安全区留白；
 *   - 手机竖屏：底部 28dp 顶圆角抽屉，内置顶部下拉关闭拖拽条与 `navigationBarsPadding()`；
 * - 统一暴露 `onDismissStart`，确保抽屉收起动画第 0ms 即与底部导航栏 / MiniPlayerBar 同步联动。
 */
@Composable
fun ConceptAdaptiveBottomSheetContainer(
    onDismiss: () -> Unit,
    onDismissStart: () -> Unit = {},
    zIndex: Float = 90f,
    maxCardWidth: Dp = 560.dp,
    portraitMinHeight: Dp = 320.dp,
    portraitMaxHeight: Dp = 620.dp,
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
                            .heightIn(min = portraitMinHeight, max = 660.dp)
                            .fillMaxHeight(if (isLandscape) 0.88f else 0.78f)
                    } else {
                        Modifier
                            .widthIn(max = maxCardWidth)
                            .fillMaxWidth()
                            .heightIn(min = portraitMinHeight, max = portraitMaxHeight)
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
                        .fillMaxSize()
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
 * 复用 `PlayQueueBottomSheet` 同源物理手感的轻量 LazyColumn 长按拖拽重排状态控制器：
 * 支持边缘自动滚屏、邻项平滑让位偏移以及释放弹簧落位动画。
 */
@Stable
class ConceptListDragReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val defaultRowHeightPx: Float,
    private val itemCountProvider: () -> Int,
    private val onCommitMove: (fromIndex: Int, toIndex: Int) -> Unit
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

    val isDragging: Boolean
        get() = dragStartIndex != null

    fun resolveRowStridePx(): Float {
        val visible = listState.layoutInfo.visibleItemsInfo
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
        while (rawShift - currentShift >= 0.50f && startIdx + currentShift < lastIdx) {
            currentShift += 1
        }
        while (rawShift - currentShift <= -0.50f && startIdx + currentShift > 0) {
            currentShift -= 1
        }
        dragTargetIndex = (startIdx + currentShift).coerceIn(0, lastIdx)
    }

    fun startDrag(index: Int) {
        val count = itemCountProvider()
        if (isSettlingDrop || index !in 0 until count) return
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

        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val startInfo = visibleItems.firstOrNull { it.index == startIdx }
        if (startInfo != null) {
            val vpStart = listState.layoutInfo.viewportStartOffset
            val vpEnd = listState.layoutInfo.viewportEndOffset
            val centerY = startInfo.offset + dragOffsetY + startInfo.size / 2f
            val edgeZone = stride * 1.15f
            if (centerY < vpStart + edgeZone && listState.canScrollBackward) {
                scope.launch {
                    val consumed = listState.scrollBy(-16f)
                    if (consumed != 0f && dragStartIndex != null && !isSettlingDrop) {
                        dragOffsetY += consumed
                        updateTargetFromOffset()
                    }
                }
            } else if (centerY > vpEnd - edgeZone && listState.canScrollForward) {
                scope.launch {
                    val consumed = listState.scrollBy(16f)
                    if (consumed != 0f && dragStartIndex != null && !isSettlingDrop) {
                        dragOffsetY += consumed
                        updateTargetFromOffset()
                    }
                }
            }
        }
    }

    fun finishDrag() {
        val startIdx = dragStartIndex ?: return
        val lastIdx = (itemCountProvider() - 1).coerceAtLeast(0)
        val targetIdx = (dragTargetIndex ?: startIdx).coerceIn(0, lastIdx)
        if (isSettlingDrop) return
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

    fun translationYForIndex(index: Int): Float {
        val startIdx = dragStartIndex ?: return 0f
        val targetIdx = dragTargetIndex ?: startIdx
        val stride = resolveRowStridePx()
        return when {
            index == startIdx -> if (isSettlingDrop) settleDropAnim.value else dragOffsetY
            startIdx < targetIdx && index in (startIdx + 1)..targetIdx -> -stride
            targetIdx < startIdx && index in targetIdx until startIdx -> stride
            else -> 0f
        }
    }
}
