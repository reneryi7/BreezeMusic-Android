package com.concept.music.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.concept.music.data.model.Playlist
import com.concept.music.ui.components.ConceptAdaptiveBottomSheetContainer
import com.concept.music.ui.components.ConceptListDragReorderState
import com.concept.music.ui.theme.ConceptPrimary
import com.concept.music.ui.theme.conceptElevatedSurfaceColor
import com.concept.music.ui.theme.softClickable

/**
 * 调整云端歌单顺序抽屉：
 * - 支持长按拖拽排序 + 40dp 上移/下移按钮 + TalkBack 无障碍 CustomActions；
 * - 未改动顺序时禁用保存按钮，有未保存改动关闭前提示确认。
 */
@Composable
fun PlaylistOrderSortSheet(
    title: String,
    playlists: List<Playlist>,
    onSaveOrder: (List<Playlist>, (Boolean) -> Unit) -> Unit,
    onDismissStart: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val cardBg = conceptElevatedSurfaceColor()
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var workingList by remember(playlists) { mutableStateOf(playlists) }
    var saving by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    val hasChanges = remember(workingList, playlists) {
        workingList.map { it.id } != playlists.map { it.id }
    }

    fun moveItem(fromIdx: Int, toIdx: Int) {
        if (fromIdx == toIdx || fromIdx !in workingList.indices || toIdx !in workingList.indices) return
        val next = workingList.toMutableList()
        val moved = next.removeAt(fromIdx)
        next.add(toIdx, moved)
        workingList = next
    }

    val reorderState = remember(listState, scope) {
        ConceptListDragReorderState(
            listState = listState,
            scope = scope,
            defaultRowHeightPx = with(density) { 64.dp.toPx() },
            itemCountProvider = { workingList.size },
            onCommitMove = { from, to -> moveItem(from, to) }
        )
    }

    ConceptAdaptiveBottomSheetContainer(
        onDismiss = onDismiss,
        onDismissStart = onDismissStart,
        zIndex = 92f
    ) { _ ->
        val safeDismiss = {
            if (hasChanges && !saving) {
                showDiscardDialog = true
            } else {
                requestDismiss()
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sheetVerticalDragGesture(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = textPrimary)
                Text("长按右侧手柄上下拖拽，或点击箭头微调顺序", fontSize = 11.sp, color = textSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !saving && workingList.size >= 2 && hasChanges,
                    onClick = {
                        saving = true
                        onSaveOrder(workingList) { ok ->
                            saving = false
                            if (ok) requestDismiss()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (saving) "同步中..." else "保存云端排序", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Surface(
                    shape = CircleShape,
                    color = cardBg,
                    modifier = Modifier
                        .size(38.dp)
                        .softClickable(onClick = safeDismiss)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            state = listState,
            userScrollEnabled = !reorderState.isDragging,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            itemsIndexed(
                items = workingList,
                key = { _, pl -> "sort_pl_${pl.listId}_${pl.id}" }
            ) { idx, pl ->
                val itemKey = "sort_pl_${pl.listId}_${pl.id}"
                val isDragged = reorderState.dragStartIndex == idx
                val isActiveReorder = reorderState.isDragging
                val targetShiftPx = reorderState.targetNeighborShiftPxForIndex(idx)
                val neighborOffsetAnim = remember(itemKey) { Animatable(0f) }
                LaunchedEffect(isActiveReorder, targetShiftPx) {
                    if (!isActiveReorder) {
                        neighborOffsetAnim.snapTo(0f)
                    } else {
                        neighborOffsetAnim.animateTo(
                            targetValue = targetShiftPx,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                    }
                }
                val dragScale by animateFloatAsState(
                    targetValue = if (isDragged && !reorderState.isSettlingDrop) 1.028f else 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                    label = "plSortDragScale"
                )
                val dragElevation by animateDpAsState(
                    targetValue = if (isDragged && !reorderState.isSettlingDrop) 12.dp else 0.dp,
                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    label = "plSortDragElev"
                )
                Surface(
                    shape = RoundedCornerShape(15.dp),
                    color = if (isDragged) ConceptPrimary.copy(alpha = 0.18f) else cardBg,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragged) 10f else 0f)
                        .graphicsLayer {
                            translationY = when {
                                !isActiveReorder -> 0f
                                isDragged -> reorderState.activeDragTranslationY()
                                else -> neighborOffsetAnim.value
                            }
                            scaleX = dragScale
                            scaleY = dragScale
                            shadowElevation = dragElevation.toPx()
                            shape = RoundedCornerShape(15.dp)
                            clip = false
                        }
                        .semantics {
                            contentDescription = "${pl.title}，第 ${idx + 1} 位，共 ${workingList.size} 项"
                            customActions = buildList {
                                if (idx > 0) {
                                    add(CustomAccessibilityAction("上移一位") { moveItem(idx, idx - 1); true })
                                }
                                if (idx < workingList.lastIndex) {
                                    add(CustomAccessibilityAction("下移一位") { moveItem(idx, idx + 1); true })
                                }
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${idx + 1}",
                            color = ConceptPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.width(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pl.title,
                                color = textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${pl.trackCount} 首歌曲",
                                color = textSecondary,
                                fontSize = 11.5.sp
                            )
                        }
                        IconButton(
                            enabled = idx > 0 && !saving,
                            onClick = { moveItem(idx, idx - 1) },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "上移", modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            enabled = idx < workingList.lastIndex && !saving,
                            onClick = { moveItem(idx, idx + 1) },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "下移", modifier = Modifier.size(20.dp))
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(42.dp)
                                .pointerInput(pl.id, workingList.size) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            val liveIdx = workingList.indexOfFirst { it.id == pl.id }
                                            if (liveIdx >= 0) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                reorderState.startDrag(liveIdx)
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            reorderState.onDragDelta(dragAmount.y)
                                        },
                                        onDragEnd = { reorderState.finishDrag() },
                                        onDragCancel = { reorderState.finishDrag() }
                                    )
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DragHandle,
                                contentDescription = "长按拖动排序",
                                tint = if (isDragged) ConceptPrimary else textSecondary.copy(alpha = 0.72f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃排序修改？", fontWeight = FontWeight.Bold) },
            text = { Text("当前歌单顺序尚未同步云端，关闭后修改将不会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onDismiss()
                }) {
                    Text("放弃修改", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            }
        )
    }
}
