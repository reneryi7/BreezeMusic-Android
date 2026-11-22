package com.concept.music.ui.library

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import com.concept.music.data.model.BlacklistEntry
import com.concept.music.data.model.CloudDiskSummary
import com.concept.music.data.model.CloudSongItem
import com.concept.music.data.model.Playlist
import com.concept.music.data.model.UserListeningPreference
import com.concept.music.ui.components.BadgeTag
import com.concept.music.ui.components.ConceptAdaptiveBottomSheetContainer
import com.concept.music.ui.components.ConceptListDragReorderState
import com.concept.music.ui.components.rememberOptimizedAsyncImageModel
import com.concept.music.ui.theme.ConceptCategoryTabItem
import com.concept.music.ui.theme.ConceptCoralAccent
import com.concept.music.ui.theme.ConceptFluidRefreshIcon
import com.concept.music.ui.theme.ConceptMintAccent
import com.concept.music.ui.theme.ConceptPrimary
import com.concept.music.ui.theme.ConceptSlidingCategoryTabs
import com.concept.music.ui.theme.conceptCyanAccentColor
import com.concept.music.ui.theme.conceptElevatedSurfaceColor
import com.concept.music.ui.theme.conceptGoldAccentColor
import com.concept.music.ui.theme.softCardClickable
import com.concept.music.ui.theme.softClickable
import java.util.Locale

private fun formatCloudBytesReadable(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        String.format(Locale.getDefault(), "%.2f GB", gb)
    } else {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        String.format(Locale.getDefault(), "%.1f MB", mb)
    }
}

/**
 * 个人音乐云盘自适应抽屉：
 * - 支持容量可视化进度条、「播放全部」、触底自动分页加载更多、网络错态重试与 40dp+ 安全热区；
 * - 统一使用 ConceptAdaptiveBottomSheetContainer（支持平板横竖屏居中卡与 MiniPlayer 同步联动）。
 */
@Composable
fun CloudDiskBottomSheet(
    summary: CloudDiskSummary,
    loading: Boolean,
    loadingMore: Boolean = false,
    errorMessage: String? = null,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit = {},
    onPlaySong: (CloudSongItem, List<CloudSongItem>) -> Unit,
    onDeleteSong: (CloudSongItem) -> Unit,
    onDismissStart: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val cardBg = conceptElevatedSurfaceColor()
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant
    val accentCyan = conceptCyanAccentColor()
    val accentGold = conceptGoldAccentColor()
    var pendingDelete by remember { mutableStateOf<CloudSongItem?>(null) }
    var refreshSpin by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    val shouldLoadMore by remember(summary.hasMore, loading, loadingMore, summary.songs.size) {
        derivedStateOf {
            if (!summary.hasMore || loading || loadingMore || summary.songs.isEmpty()) return@derivedStateOf false
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= summary.songs.lastIndex - 5
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    ConceptAdaptiveBottomSheetContainer(
        onDismiss = onDismiss,
        onDismissStart = onDismissStart,
        zIndex = 92f
    ) { _ ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sheetVerticalDragGesture(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("我的音乐云盘", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = textPrimary)
                val spaceText = if (summary.maxBytes > 0L) {
                    "已用 ${formatCloudBytesReadable(summary.usedBytes)} / ${formatCloudBytesReadable(summary.maxBytes)} · 共 ${summary.totalCount} 首"
                } else {
                    "云端私有曲库 · 共 ${summary.totalCount} 首"
                }
                Text(spaceText, fontSize = 11.5.sp, color = textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = cardBg,
                    modifier = Modifier
                        .size(40.dp)
                        .softClickable(enabled = !loading) {
                            refreshSpin += 1
                            onRefresh()
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        ConceptFluidRefreshIcon(
                            tint = accentCyan,
                            isRefreshing = loading,
                            spinTrigger = refreshSpin,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = cardBg,
                    modifier = Modifier
                        .size(40.dp)
                        .softClickable(onClick = requestDismiss)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // 容量进度条 + 播放全部快捷栏
        if (summary.maxBytes > 0L || summary.songs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            if (summary.maxBytes > 0L) {
                val usedFraction = (summary.usedBytes.toFloat() / summary.maxBytes.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { usedFraction },
                    color = accentCyan,
                    trackColor = accentCyan.copy(alpha = 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
            if (summary.songs.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "已加载 ${summary.songs.size} / ${summary.totalCount} 首",
                        color = textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = accentCyan.copy(alpha = 0.15f),
                        modifier = Modifier.softCardClickable(shape = RoundedCornerShape(12.dp)) {
                            onPlaySong(summary.songs.first(), summary.songs)
                            requestDismiss()
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = accentCyan, modifier = Modifier.size(16.dp))
                            Text("播放全部", color = accentCyan, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading && summary.songs.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accentCyan, strokeWidth = 2.5.dp)
                    }
                }
                errorMessage != null && summary.songs.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudOff,
                            contentDescription = null,
                            tint = textSecondary.copy(alpha = 0.55f),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(errorMessage, color = textSecondary, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                refreshSpin += 1
                                onRefresh()
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ConceptPrimary)
                        ) {
                            Text("重新加载", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                summary.songs.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudQueue,
                            contentDescription = null,
                            tint = textSecondary.copy(alpha = 0.5f),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("个人音乐云盘暂无曲目", color = textSecondary, fontSize = 13.5.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("在酷狗客户端上传的云盘音频会实时同步至此", color = textSecondary.copy(alpha = 0.72f), fontSize = 11.5.sp)
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 14.dp)
                    ) {
                        itemsIndexed(
                            items = summary.songs,
                            key = { idx, item -> "cloud_${item.kvId}_${item.hash}_$idx" }
                        ) { _, item ->
                            val thumbModel = rememberOptimizedAsyncImageModel(item.coverUrl, sizePx = 128)
                            val isFlac = item.extName.equals("flac", ignoreCase = true) || item.bitrate >= 700
                            val isHiRes = isFlac && item.bitrate >= 1500
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = cardBg,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .softCardClickable(shape = RoundedCornerShape(16.dp)) {
                                        onPlaySong(item, summary.songs)
                                        requestDismiss()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(accentCyan.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (thumbModel != null) {
                                            AsyncImage(
                                                model = thumbModel,
                                                contentDescription = item.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(Icons.Rounded.AudioFile, contentDescription = null, tint = accentCyan, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Text(
                                                text = item.title,
                                                color = textPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (isHiRes) {
                                                BadgeTag(text = "Hi-Res", color = accentGold)
                                            } else if (isFlac) {
                                                BadgeTag(text = "SQ", color = accentCyan)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        val meta = listOfNotNull(
                                            item.artist.takeIf { it.isNotBlank() },
                                            item.extName.uppercase(Locale.ROOT).takeIf { it.isNotBlank() },
                                            item.bitrate.takeIf { it > 0 }?.let { "${it}kbps" },
                                            item.fileSize.takeIf { it > 0L }?.let { formatCloudBytesReadable(it) }
                                        ).joinToString(" · ")
                                        Text(
                                            text = meta,
                                            color = textSecondary,
                                            fontSize = 11.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { pendingDelete = item },
                                        modifier = Modifier.size(42.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "删除云盘曲目",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (loadingMore) {
                            item(key = "cloud_loading_more") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = accentCyan, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除云盘曲目？", fontWeight = FontWeight.Bold) },
            text = { Text("确定从个人音乐云盘永久删除《${target.title}》吗？") },
            confirmButton = {
                TextButton(onClick = {
                    val toDel = target
                    pendingDelete = null
                    onDeleteSong(toDel)
                }) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 听歌偏好与内容黑名单自适应抽屉：
 * - 修复加载瞬间闪现空态；
 * - 黑名单分「屏蔽歌曲 / 屏蔽歌手」双子 Tab，展示准确数量与错误重试态；
 * - 与发现页 FM 卡片设置图标、设置页「推荐偏好与内容屏蔽」共用此组件。
 */
@Composable
fun ListeningPreferenceAndBlacklistSheet(
    preference: UserListeningPreference?,
    blacklistedSongs: List<BlacklistEntry>,
    blacklistedSingers: List<BlacklistEntry>,
    loading: Boolean,
    errorMessage: String? = null,
    onRefresh: () -> Unit,
    onSelectMode: (Int) -> Unit,
    onRemoveBlacklist: (BlacklistEntry) -> Unit,
    onDismissStart: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val cardBg = conceptElevatedSurfaceColor()
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant
    val activeMode = preference?.mode ?: 0
    var selectedBlacklistTab by remember { mutableStateOf("song") }
    var refreshSpin by remember { mutableIntStateOf(0) }

    val modes = remember {
        listOf(
            Triple(0, "默认平衡", "兼顾红心喜好与新歌发现"),
            Triple(1, "偏好熟悉", "更多推送收藏与常听曲风"),
            Triple(2, "更多尝鲜", "探索小众与跨风格新歌")
        )
    }

    val activeBlacklist = if (selectedBlacklistTab == "singer") blacklistedSingers else blacklistedSongs

    ConceptAdaptiveBottomSheetContainer(
        onDismiss = onDismiss,
        onDismissStart = onDismissStart,
        zIndex = 92f
    ) { _ ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sheetVerticalDragGesture(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("推荐偏好与内容屏蔽", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = textPrimary)
                Text("调整私人 FM / 日推算法倾向并管理屏蔽的歌曲与歌手", fontSize = 11.5.sp, color = textSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = CircleShape,
                    color = cardBg,
                    modifier = Modifier
                        .size(40.dp)
                        .softClickable(enabled = !loading) {
                            refreshSpin += 1
                            onRefresh()
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        ConceptFluidRefreshIcon(
                            tint = ConceptMintAccent,
                            isRefreshing = loading,
                            spinTrigger = refreshSpin,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = cardBg,
                    modifier = Modifier
                        .size(40.dp)
                        .softClickable(onClick = requestDismiss)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("推荐策略模式", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = textSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            modes.forEach { (modeId, label, desc) ->
                val selected = activeMode == modeId
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) ConceptPrimary.copy(alpha = 0.18f) else cardBg,
                    modifier = Modifier
                        .weight(1f)
                        .softCardClickable(shape = RoundedCornerShape(16.dp)) {
                            if (!selected) onSelectMode(modeId)
                        }
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                        Text(
                            text = label,
                            color = if (selected) ConceptPrimary else textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = desc,
                            color = textSecondary,
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp,
                            maxLines = 2
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        ConceptSlidingCategoryTabs(
            tabs = listOf(
                ConceptCategoryTabItem("song", "屏蔽歌曲", blacklistedSongs.size.toString(), ConceptCoralAccent),
                ConceptCategoryTabItem("singer", "屏蔽歌手", blacklistedSingers.size.toString(), ConceptPrimary)
            ),
            selectedKey = selectedBlacklistTab,
            onSelectTab = { selectedBlacklistTab = it },
            itemSpacing = 22.dp,
            fontSize = 14.sp,
            indicatorWidth = 20.dp
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading && activeBlacklist.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ConceptMintAccent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                    }
                }
                errorMessage != null && blacklistedSongs.isEmpty() && blacklistedSingers.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(errorMessage, color = textSecondary, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                refreshSpin += 1
                                onRefresh()
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("重试", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                activeBlacklist.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (selectedBlacklistTab == "singer") {
                                "暂未屏蔽任何歌手（可在歌曲「更多操作」中添加屏蔽歌手）"
                            } else {
                                "暂未屏蔽任何歌曲（可在 FM 或歌曲操作菜单中选择屏蔽）"
                            },
                            color = textSecondary,
                            fontSize = 12.5.sp,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        itemsIndexed(
                            items = activeBlacklist,
                            key = { idx, item -> "blk_${item.label}_${item.key}_$idx" }
                        ) { _, entry ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = cardBg,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(
                                                (if (entry.label == "singer") ConceptPrimary else ConceptCoralAccent).copy(alpha = 0.14f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (entry.label == "singer") Icons.Rounded.PersonOff else Icons.Rounded.MusicOff,
                                            contentDescription = null,
                                            tint = if (entry.label == "singer") ConceptPrimary else ConceptCoralAccent,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.name,
                                            color = textPrimary,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (entry.label == "singer") "已屏蔽该歌手推荐" else "已屏蔽该歌曲推荐",
                                            color = textSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                    TextButton(
                                        onClick = { onRemoveBlacklist(entry) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("移出", color = ConceptPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
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

/**
 * 调整云端歌单顺序抽屉：
 * - 修复 P0：保存失败后自动通过回调复位 `saving = false`，不再永久锁死；
 * - 升级支持长按拖拽排序（与 PlayQueueBottomSheet 同源手感）+ 40dp 上移/下移按钮 + TalkBack 无障碍 CustomActions；
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
                val isDragged = reorderState.dragStartIndex == idx
                val transY = reorderState.translationYForIndex(idx)
                Surface(
                    shape = RoundedCornerShape(15.dp),
                    color = if (isDragged) ConceptPrimary.copy(alpha = 0.18f) else cardBg,
                    shadowElevation = if (isDragged) 8.dp else 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragged) 10f else 0f)
                        .graphicsLayer {
                            translationY = transY
                            scaleX = if (isDragged) 1.02f else 1f
                            scaleY = if (isDragged) 1.02f else 1f
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
