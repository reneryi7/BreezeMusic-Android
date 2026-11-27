package com.concept.music.ui.library

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.concept.music.data.model.AudioQuality
import com.concept.music.data.model.CloudDiskSummary
import com.concept.music.data.model.CloudSongItem
import com.concept.music.data.model.Song
import com.concept.music.ui.components.AddToPlaylistDialog
import com.concept.music.ui.components.BadgeTag
import com.concept.music.ui.components.CreatePlaylistDialog
import com.concept.music.ui.components.LibraryShortcutGlyph
import com.concept.music.ui.components.RefinedShortcutBadge
import com.concept.music.ui.components.rememberOptimizedAsyncImageModel
import com.concept.music.ui.theme.ConceptCoralAccent
import com.concept.music.ui.theme.ConceptFluidRefreshIcon
import com.concept.music.ui.theme.ConceptPrimary
import com.concept.music.ui.theme.ConceptStageBackground
import com.concept.music.ui.theme.LocalConceptAdaptiveInfo
import com.concept.music.ui.theme.LocalIsDarkTheme
import com.concept.music.ui.theme.VipGold
import com.concept.music.ui.theme.conceptCyanAccentColor
import com.concept.music.ui.theme.conceptElevatedSurfaceColor
import com.concept.music.ui.theme.conceptFloatingCardColor
import com.concept.music.ui.theme.softCardClickable
import com.concept.music.ui.theme.softClickable
import com.concept.music.ui.viewmodel.LibraryViewModel
import java.util.Locale

private enum class CloudSortOption(val label: String) {
    LATEST("最新上传"),
    FILE_SIZE("文件大小")
}

private fun formatCloudSpaceText(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format(Locale.US, "%.2f GB", gb)
    else String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
}

/**
 * 独立「音乐云盘」全屏页面：
 * - 告别旧版局促抽屉弹窗，提供流体容量仪表卡、云盘内搜索、无损筛选、多维排序、批量多选与真实酷狗云端同步删除。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CloudDiskScreen(
    viewModel: LibraryViewModel,
    currentPlayingSongId: String? = null,
    isPlaying: Boolean = false,
    onBack: () -> Unit,
    onPlaySong: (Song, List<Song>) -> Unit,
    onAddNextPlay: (Song) -> Unit = {},
    onOpenSongActions: ((Song) -> Unit)? = null
) {
    val context = LocalContext.current
    val isDark = LocalIsDarkTheme.current
    val adaptive = LocalConceptAdaptiveInfo.current
    val summary by viewModel.cloudDiskSummary.collectAsState()
    val loading by viewModel.cloudDiskLoading.collectAsState()
    val loadingMore by viewModel.cloudDiskLoadingMore.collectAsState()
    val deleting by viewModel.cloudDiskDeleting.collectAsState()
    val errorMessage by viewModel.cloudDiskError.collectAsState()
    val playlists by viewModel.playlists.collectAsState()

    val cyanAccent = conceptCyanAccentColor()
    val cardBg = conceptFloatingCardColor()
    val elevatedBg = conceptElevatedSurfaceColor()
    val primaryText = MaterialTheme.colorScheme.onSurface
    val subText = MaterialTheme.colorScheme.onSurfaceVariant

    var searchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(CloudSortOption.LATEST) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedKvIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingDeleteItems by remember { mutableStateOf<List<CloudSongItem>?>(null) }
    var batchAddToPlaylistSongs by remember { mutableStateOf<List<Song>?>(null) }
    var showCreatePlaylistForBatch by remember { mutableStateOf(false) }
    var refreshSpin by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()
    val pullRefreshState = rememberPullToRefreshState()

    LaunchedEffect(Unit) {
        viewModel.loadCloudDisk(force = false)
    }

    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.loadCloudDisk(force = true)
        }
    }
    LaunchedEffect(loading) {
        if (!loading && pullRefreshState.isRefreshing) {
            pullRefreshState.endRefresh()
        }
    }

    BackHandler {
        when {
            selectionMode -> {
                selectionMode = false
                selectedKvIds = emptySet()
            }
            searchExpanded && searchQuery.isNotBlank() -> {
                searchQuery = ""
            }
            searchExpanded -> {
                searchExpanded = false
            }
            else -> onBack()
        }
    }

    val displayedItems = remember(summary.songs, searchQuery, sortOption) {
        val kw = searchQuery.trim().lowercase(Locale.ROOT)
        val filtered = if (kw.isEmpty()) {
            summary.songs
        } else {
            summary.songs.filter { item ->
                item.title.lowercase(Locale.ROOT).contains(kw) ||
                    item.artist.lowercase(Locale.ROOT).contains(kw) ||
                    item.album.lowercase(Locale.ROOT).contains(kw) ||
                    item.extName.lowercase(Locale.ROOT).contains(kw)
            }
        }
        when (sortOption) {
            CloudSortOption.LATEST -> filtered
            CloudSortOption.FILE_SIZE -> filtered.sortedByDescending { it.fileSize }
        }
    }

    // 预构建并缓存完整 Song 列表，彻底消除列表滚动/点击时反复映射 toSong() 引发的掉帧
    val displayedSongs = remember(displayedItems) {
        displayedItems.map { it.toSong() }
    }

    ConceptStageBackground(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pullRefreshState.nestedScrollConnection)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = if (selectionMode) "已选 ${selectedKvIds.size} 首云盘曲目" else "音乐云盘",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 19.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!selectionMode && summary.songs.isNotEmpty()) {
                                val countDisplay = maxOf(summary.totalCount, summary.songs.size)
                                Text(
                                    text = if (loadingMore || summary.hasMore) {
                                        "正在同步全量云盘 · 已加载 ${summary.songs.size} 首"
                                    } else {
                                        "酷狗官方私有云实时同步 · 共 $countDisplay 首"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = subText,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (selectionMode) {
                                    selectionMode = false
                                    selectedKvIds = emptySet()
                                } else {
                                    onBack()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (selectionMode) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    actions = {
                        if (selectionMode) {
                            val allIds = displayedItems.mapNotNull { it.kvId.takeIf { id -> id > 0L } }.toSet()
                            val allSelected = allIds.isNotEmpty() && selectedKvIds.size == allIds.size
                            IconButton(
                                onClick = {
                                    selectedKvIds = if (allSelected) emptySet() else allIds
                                }
                            ) {
                                Icon(
                                    imageVector = if (allSelected) Icons.Rounded.RemoveDone else Icons.Rounded.DoneAll,
                                    contentDescription = if (allSelected) "取消全选" else "全选",
                                    tint = cyanAccent
                                )
                            }
                        } else {
                            IconButton(onClick = {
                                searchExpanded = !searchExpanded
                                if (!searchExpanded) searchQuery = ""
                            }) {
                                Icon(
                                    imageVector = if (searchExpanded) Icons.Rounded.SearchOff else Icons.Rounded.Search,
                                    contentDescription = "搜索云盘",
                                    tint = if (searchExpanded) cyanAccent else primaryText
                                )
                            }
                            if (summary.songs.isNotEmpty()) {
                                IconButton(onClick = {
                                    selectionMode = true
                                    selectedKvIds = emptySet()
                                }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Checklist,
                                        contentDescription = "批量管理",
                                        tint = primaryText
                                    )
                                }
                            }
                            IconButton(
                                enabled = !loading,
                                onClick = {
                                    refreshSpin += 1
                                    viewModel.loadCloudDisk(force = true)
                                }
                            ) {
                                ConceptFluidRefreshIcon(
                                    tint = cyanAccent,
                                    isRefreshing = loading,
                                    spinTrigger = refreshSpin,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = selectionMode,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    val selectedItems = remember(summary.songs, selectedKvIds) {
                        summary.songs.filter { it.kvId in selectedKvIds }
                    }
                    Surface(
                        color = if (isDark) Color(0xFF131B2A).copy(alpha = 0.97f) else Color.White.copy(alpha = 0.97f),
                        shadowElevation = 12.dp,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                enabled = selectedItems.isNotEmpty(),
                                onClick = {
                                    selectedItems.reversed().forEach { onAddNextPlay(it.toSong()) }
                                    Toast.makeText(context, "已将 ${selectedItems.size} 首云盘歌曲加入下一首播放", Toast.LENGTH_SHORT).show()
                                    selectionMode = false
                                    selectedKvIds = emptySet()
                                },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("下一首播", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }
                            FilledTonalButton(
                                enabled = selectedItems.isNotEmpty(),
                                onClick = {
                                    batchAddToPlaylistSongs = selectedItems.map { it.toSong() }
                                },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("加入歌单", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                enabled = selectedItems.isNotEmpty() && !deleting,
                                onClick = { pendingDeleteItems = selectedItems },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = if (deleting) "同步删除中..." else "云端删除 (${selectedItems.size})",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                val horizontalPad = if (adaptive.isLargeScreenDevice) adaptive.horizontalContentPadding else 16.dp
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .widthIn(max = 880.dp)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = horizontalPad,
                        end = horizontalPad,
                        top = 6.dp,
                        bottom = if (selectionMode) 110.dp else 124.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. 搜索框平滑展开区
                    item(key = "cloud_search_bar") {
                        AnimatedVisibility(
                            visible = searchExpanded,
                            enter = expandVertically(animationSpec = tween(240, easing = FastOutSlowInEasing)) + fadeIn(),
                            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut()
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                placeholder = { Text("搜索云盘歌名、歌手、专辑或格式 (如 flac)...", fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = cyanAccent) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Rounded.Clear, contentDescription = "清空")
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(18.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = cardBg,
                                    unfocusedContainerColor = cardBg,
                                    focusedBorderColor = cyanAccent.copy(alpha = 0.55f),
                                    unfocusedBorderColor = Color.Transparent
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp)
                            )
                        }
                    }

                    // 2. 顶部雅致云端容量仪表与操作概览卡
                    item(key = "cloud_hero_overview") {
                        CloudStorageOverviewHeroCard(
                            summary = summary,
                            displayedCount = displayedItems.size,
                            cyanAccent = cyanAccent,
                            isDark = isDark,
                            onPlayAll = {
                                if (displayedSongs.isNotEmpty()) {
                                    onPlaySong(displayedSongs.first(), displayedSongs)
                                }
                            },
                            onShufflePlay = {
                                if (displayedSongs.isNotEmpty()) {
                                    val shuffled = displayedSongs.shuffled()
                                    onPlaySong(shuffled.first(), shuffled)
                                }
                            }
                        )
                    }

                    // 3. 精简排序栏（仅保留右侧「最新上传 / 文件大小」一键切换，去除冗余音质分类按钮）
                    if (summary.songs.isNotEmpty()) {
                        item(key = "cloud_sort_bar", contentType = "cloud_sort_bar") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (searchQuery.isNotBlank()) {
                                        "匹配到 ${displayedItems.size} 首曲目"
                                    } else if (loadingMore || summary.hasMore) {
                                        "同步中 · 已加载 ${summary.songs.size} 首"
                                    } else {
                                        "云盘曲目"
                                    },
                                    color = subText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 4.dp)
                                )

                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = elevatedBg,
                                    modifier = Modifier.softCardClickable(shape = RoundedCornerShape(14.dp)) {
                                        sortOption = if (sortOption == CloudSortOption.LATEST) {
                                            CloudSortOption.FILE_SIZE
                                        } else {
                                            CloudSortOption.LATEST
                                        }
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.SwapVert,
                                            contentDescription = "切换排序方式",
                                            tint = ConceptPrimary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = sortOption.label,
                                            color = primaryText,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 4. 内容列表态
                    when {
                        loading && summary.songs.isEmpty() -> {
                            item(key = "cloud_initial_loading") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 72.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = cyanAccent, strokeWidth = 2.6.dp)
                                }
                            }
                        }
                        errorMessage != null && summary.songs.isEmpty() -> {
                            item(key = "cloud_error_state") {
                                Surface(
                                    shape = RoundedCornerShape(24.dp),
                                    color = cardBg,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 44.dp, horizontal = 24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CloudOff,
                                            contentDescription = null,
                                            tint = subText.copy(alpha = 0.6f),
                                            modifier = Modifier.size(42.dp)
                                        )
                                        Text(errorMessage ?: "云盘加载失败", color = subText, fontSize = 13.5.sp)
                                        Button(
                                            onClick = { viewModel.loadCloudDisk(force = true) },
                                            shape = RoundedCornerShape(14.dp)
                                        ) {
                                            Text("重新连接云盘", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                        displayedItems.isEmpty() -> {
                            item(key = "cloud_empty_state") {
                                Surface(
                                    shape = RoundedCornerShape(24.dp),
                                    color = cardBg,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 52.dp, horizontal = 24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        RefinedShortcutBadge(
                                            glyph = LibraryShortcutGlyph.CLOUD_DISK,
                                            accentColor = cyanAccent,
                                            size = 54.dp
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = if (summary.songs.isEmpty()) "个人音乐云盘暂无曲目" else "没有符合筛选条件的云盘歌曲",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = primaryText
                                        )
                                        Text(
                                            text = if (summary.songs.isEmpty()) {
                                                "您在酷狗各端上传的私有无损与珍藏音频会实时在此同步呈现"
                                            } else {
                                                "请尝试更换或清空搜索关键词"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = subText
                                        )
                                    }
                                }
                            }
                        }
                        else -> {
                            itemsIndexed(
                                items = displayedItems,
                                key = { idx, item -> "cloud_row_${item.kvId}_${item.hash}_$idx" },
                                contentType = { _, _ -> "cloud_song_row" }
                            ) { index, item ->
                                val songModel = displayedSongs.getOrElse(index) { item.toSong() }
                                val isCurrentPlaying = currentPlayingSongId != null &&
                                    (songModel.id == currentPlayingSongId ||
                                        (songModel.hash.isNotBlank() && currentPlayingSongId.contains(songModel.hash.take(8), ignoreCase = true)))
                                val isSelected = item.kvId in selectedKvIds

                                CloudSongRowCard(
                                    index = index + 1,
                                    item = item,
                                    isCurrent = isCurrentPlaying,
                                    isPlaying = isCurrentPlaying && isPlaying,
                                    selectionMode = selectionMode,
                                    selected = isSelected,
                                    cyanAccent = cyanAccent,
                                    cardBg = cardBg,
                                    onClick = {
                                        if (selectionMode) {
                                            if (item.kvId > 0L) {
                                                selectedKvIds = if (isSelected) selectedKvIds - item.kvId else selectedKvIds + item.kvId
                                            }
                                        } else {
                                            onPlaySong(songModel, displayedSongs)
                                        }
                                    },
                                    onLongPress = {
                                        if (!selectionMode && item.kvId > 0L) {
                                            selectionMode = true
                                            selectedKvIds = setOf(item.kvId)
                                        }
                                    },
                                    onPlayNext = {
                                        onAddNextPlay(songModel)
                                        Toast.makeText(context, "已加入下一首播放：《${item.title}》", Toast.LENGTH_SHORT).show()
                                    },
                                    onAddToPlaylist = {
                                        batchAddToPlaylistSongs = listOf(songModel)
                                    },
                                    onMoreActions = onOpenSongActions?.let { handler -> { handler(songModel) } },
                                    onDelete = {
                                        pendingDeleteItems = listOf(item)
                                    }
                                )
                            }

                            if (loadingMore) {
                                item(key = "cloud_page_loading_more") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(color = cyanAccent, strokeWidth = 2.2.dp, modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                if (pullRefreshState.progress > 0.05f || pullRefreshState.isRefreshing) {
                    PullToRefreshContainer(
                        state = pullRefreshState,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = cyanAccent
                    )
                }
            }
        }
    }

    pendingDeleteItems?.let { targets ->
        val titleText = if (targets.size == 1) {
            "从云盘永久删除？"
        } else {
            "批量删除 ${targets.size} 首云盘歌曲？"
        }
        val bodyText = if (targets.size == 1) {
            "确认将《${targets.first().title}》从酷狗官方云盘中实时永久移除吗？删除后不可恢复。"
        } else {
            "确认将所选的 ${targets.size} 首云盘曲目从酷狗官方云盘永久移除吗？删除操作将实时同步至云端。"
        }
        AlertDialog(
            onDismissRequest = { if (!deleting) pendingDeleteItems = null },
            title = { Text(titleText, fontWeight = FontWeight.ExtraBold) },
            text = { Text(bodyText) },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        val toDelete = targets
                        pendingDeleteItems = null
                        viewModel.deleteCloudSongsBatch(toDelete) { ok, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            if (ok && selectionMode) {
                                selectionMode = false
                                selectedKvIds = emptySet()
                            }
                        }
                    }
                ) {
                    Text("永久删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { pendingDeleteItems = null }) {
                    Text("取消")
                }
            }
        )
    }

    batchAddToPlaylistSongs?.let { songsToAdd ->
        val leadSong = songsToAdd.firstOrNull()
        val createdLists = remember(playlists) {
            playlists.filter { it.isCreatedByMe && !it.isLiked && !it.isDefaultCollect }
        }
        if (leadSong != null) {
            AddToPlaylistDialog(
                song = leadSong,
                playlists = createdLists,
                onDismiss = { batchAddToPlaylistSongs = null },
                onSelectPlaylist = { pl ->
                    val targetListId = pl.resolveEffectiveListId()
                    batchAddToPlaylistSongs = null
                    if (targetListId > 0L) {
                        viewModel.addSongsToPlaylistBatch(targetListId, songsToAdd, pl.title) { _, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                        if (selectionMode) {
                            selectionMode = false
                            selectedKvIds = emptySet()
                        }
                    }
                },
                onCreateNewPlaylist = {
                    showCreatePlaylistForBatch = true
                }
            )
        }
    }

    if (showCreatePlaylistForBatch) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistForBatch = false },
            onConfirmWithCallback = { name, onDone ->
                viewModel.createPlaylist(name) { ok, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    onDone(ok)
                    if (ok) showCreatePlaylistForBatch = false
                }
            }
        )
    }
}

/**
 * 可嵌入「本地/云盘」双页中心的音乐云盘内容组件（复用全套容量仪表卡、列表、排序与云端删除交互，支持未登录优雅引导）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun CloudDiskTabContent(
    viewModel: LibraryViewModel,
    isLoggedIn: Boolean,
    searchQuery: String,
    selectionMode: Boolean,
    selectedKvIds: Set<Long>,
    onSelectionModeChange: (Boolean) -> Unit,
    onSelectedKvIdsChange: (Set<Long>) -> Unit,
    currentPlayingSongId: String? = null,
    isPlaying: Boolean = false,
    onLogin: () -> Unit = {},
    onPlaySong: (Song, List<Song>) -> Unit,
    onAddNextPlay: (Song) -> Unit = {},
    onOpenSongActions: ((Song) -> Unit)? = null
) {
    val context = LocalContext.current
    val isDark = LocalIsDarkTheme.current
    val adaptive = LocalConceptAdaptiveInfo.current
    val summary by viewModel.cloudDiskSummary.collectAsState()
    val loading by viewModel.cloudDiskLoading.collectAsState()
    val loadingMore by viewModel.cloudDiskLoadingMore.collectAsState()
    val deleting by viewModel.cloudDiskDeleting.collectAsState()
    val errorMessage by viewModel.cloudDiskError.collectAsState()
    val playlists by viewModel.playlists.collectAsState()

    val cyanAccent = conceptCyanAccentColor()
    val cardBg = conceptFloatingCardColor()
    val elevatedBg = conceptElevatedSurfaceColor()
    val primaryText = MaterialTheme.colorScheme.onSurface
    val subText = MaterialTheme.colorScheme.onSurfaceVariant

    var sortOption by remember { mutableStateOf(CloudSortOption.LATEST) }
    var pendingDeleteItems by remember { mutableStateOf<List<CloudSongItem>?>(null) }
    var batchAddToPlaylistSongs by remember { mutableStateOf<List<Song>?>(null) }
    var showCreatePlaylistForBatch by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val pullRefreshState = rememberPullToRefreshState()

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            viewModel.loadCloudDisk(force = false)
        }
    }

    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            if (isLoggedIn) {
                viewModel.loadCloudDisk(force = true)
            } else {
                pullRefreshState.endRefresh()
            }
        }
    }
    LaunchedEffect(loading) {
        if (!loading && pullRefreshState.isRefreshing) {
            pullRefreshState.endRefresh()
        }
    }

    if (!isLoggedIn) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = cardBg,
                shadowElevation = 0.dp,
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 26.dp, vertical = 38.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RefinedShortcutBadge(
                        glyph = LibraryShortcutGlyph.CLOUD_DISK,
                        accentColor = cyanAccent,
                        size = 64.dp
                    )
                    Text(
                        text = "登录开启音乐云盘",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 19.sp
                        ),
                        color = primaryText
                    )
                    Text(
                        text = "登录酷狗账号后即可实时同步您在各端上传的私有无损曲库；无需登录也可切换至「本地音乐」离线导入畅听",
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                        color = subText
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = onLogin,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = cyanAccent, contentColor = Color(0xFF06161B)),
                        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Rounded.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("登录酷狗账号", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    }
                }
            }
        }
        return
    }

    val displayedItems = remember(summary.songs, searchQuery, sortOption) {
        val kw = searchQuery.trim().lowercase(Locale.ROOT)
        val filtered = if (kw.isEmpty()) {
            summary.songs
        } else {
            summary.songs.filter { item ->
                item.title.lowercase(Locale.ROOT).contains(kw) ||
                    item.artist.lowercase(Locale.ROOT).contains(kw) ||
                    item.album.lowercase(Locale.ROOT).contains(kw) ||
                    item.extName.lowercase(Locale.ROOT).contains(kw)
            }
        }
        when (sortOption) {
            CloudSortOption.LATEST -> filtered
            CloudSortOption.FILE_SIZE -> filtered.sortedByDescending { it.fileSize }
        }
    }

    val displayedSongs = remember(displayedItems) {
        displayedItems.map { it.toSong() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pullRefreshState.nestedScrollConnection),
        contentAlignment = Alignment.TopCenter
    ) {
        val horizontalPad = if (adaptive.isLargeScreenDevice) adaptive.horizontalContentPadding else 16.dp
        LazyColumn(
            state = listState,
            modifier = Modifier
                .widthIn(max = 880.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPad,
                end = horizontalPad,
                top = 6.dp,
                bottom = if (selectionMode) 128.dp else 132.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "cloud_tab_hero") {
                CloudStorageOverviewHeroCard(
                    summary = summary,
                    displayedCount = displayedItems.size,
                    cyanAccent = cyanAccent,
                    isDark = isDark,
                    onPlayAll = {
                        if (displayedSongs.isNotEmpty()) {
                            onPlaySong(displayedSongs.first(), displayedSongs)
                        }
                    },
                    onShufflePlay = {
                        if (displayedSongs.isNotEmpty()) {
                            val shuffled = displayedSongs.shuffled()
                            onPlaySong(shuffled.first(), shuffled)
                        }
                    }
                )
            }

            if (summary.songs.isNotEmpty()) {
                item(key = "cloud_tab_sort_bar") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                "匹配到 ${displayedItems.size} 首云盘曲目"
                            } else if (loadingMore || summary.hasMore) {
                                "同步中 · 已加载 ${summary.songs.size} 首"
                            } else {
                                "云盘曲目 · 共 ${maxOf(summary.totalCount, summary.songs.size)} 首"
                            },
                            color = subText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 4.dp)
                        )

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = elevatedBg,
                            modifier = Modifier.softCardClickable(shape = RoundedCornerShape(14.dp)) {
                                sortOption = if (sortOption == CloudSortOption.LATEST) {
                                    CloudSortOption.FILE_SIZE
                                } else {
                                    CloudSortOption.LATEST
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SwapVert,
                                    contentDescription = "切换排序方式",
                                    tint = ConceptPrimary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = sortOption.label,
                                    color = primaryText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            when {
                loading && summary.songs.isEmpty() -> {
                    item(key = "cloud_tab_loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 72.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = cyanAccent, strokeWidth = 2.6.dp)
                        }
                    }
                }
                errorMessage != null && summary.songs.isEmpty() -> {
                    item(key = "cloud_tab_error") {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = cardBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 44.dp, horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudOff,
                                    contentDescription = null,
                                    tint = subText.copy(alpha = 0.6f),
                                    modifier = Modifier.size(42.dp)
                                )
                                Text(errorMessage ?: "云盘加载失败", color = subText, fontSize = 13.5.sp)
                                Button(
                                    onClick = { viewModel.loadCloudDisk(force = true) },
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("重新连接云盘", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                displayedItems.isEmpty() -> {
                    item(key = "cloud_tab_empty") {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = cardBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 52.dp, horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RefinedShortcutBadge(
                                    glyph = LibraryShortcutGlyph.CLOUD_DISK,
                                    accentColor = cyanAccent,
                                    size = 54.dp
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = if (summary.songs.isEmpty()) "个人音乐云盘暂无曲目" else "没有符合筛选条件的云盘歌曲",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = primaryText
                                )
                                Text(
                                    text = if (summary.songs.isEmpty()) {
                                        "您在酷狗各端上传的私有无损与珍藏音频会实时在此同步呈现"
                                    } else {
                                        "请尝试更换或清空搜索关键词"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = subText
                                )
                            }
                        }
                    }
                }
                else -> {
                    itemsIndexed(
                        items = displayedItems,
                        key = { idx, item -> "cloud_tab_row_${item.kvId}_${item.hash}_$idx" },
                        contentType = { _, _ -> "cloud_song_row" }
                    ) { index, item ->
                        val songModel = displayedSongs.getOrElse(index) { item.toSong() }
                        val isCurrentPlaying = currentPlayingSongId != null &&
                            (songModel.id == currentPlayingSongId ||
                                (songModel.hash.isNotBlank() && currentPlayingSongId.contains(songModel.hash.take(8), ignoreCase = true)))
                        val isSelected = item.kvId in selectedKvIds

                        CloudSongRowCard(
                            index = index + 1,
                            item = item,
                            isCurrent = isCurrentPlaying,
                            isPlaying = isCurrentPlaying && isPlaying,
                            selectionMode = selectionMode,
                            selected = isSelected,
                            cyanAccent = cyanAccent,
                            cardBg = cardBg,
                            onClick = {
                                if (selectionMode) {
                                    if (item.kvId > 0L) {
                                        onSelectedKvIdsChange(if (isSelected) selectedKvIds - item.kvId else selectedKvIds + item.kvId)
                                    }
                                } else {
                                    onPlaySong(songModel, displayedSongs)
                                }
                            },
                            onLongPress = {
                                if (!selectionMode && item.kvId > 0L) {
                                    onSelectionModeChange(true)
                                    onSelectedKvIdsChange(setOf(item.kvId))
                                }
                            },
                            onPlayNext = {
                                onAddNextPlay(songModel)
                                Toast.makeText(context, "已加入下一首播放：《${item.title}》", Toast.LENGTH_SHORT).show()
                            },
                            onAddToPlaylist = {
                                batchAddToPlaylistSongs = listOf(songModel)
                            },
                            onMoreActions = onOpenSongActions?.let { handler -> { handler(songModel) } },
                            onDelete = {
                                pendingDeleteItems = listOf(item)
                            }
                        )
                    }

                    if (loadingMore) {
                        item(key = "cloud_tab_loading_more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = cyanAccent, strokeWidth = 2.2.dp, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }

        if (pullRefreshState.progress > 0.05f || pullRefreshState.isRefreshing) {
            PullToRefreshContainer(
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = cyanAccent
            )
        }

        // 批量操作底部浮动栏
        AnimatedVisibility(
            visible = selectionMode,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val selectedItems = remember(summary.songs, selectedKvIds) {
                summary.songs.filter { it.kvId in selectedKvIds }
            }
            Surface(
                color = if (isDark) Color(0xFF131B2A).copy(alpha = 0.97f) else Color.White.copy(alpha = 0.97f),
                shadowElevation = 12.dp,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        enabled = selectedItems.isNotEmpty(),
                        onClick = {
                            selectedItems.reversed().forEach { onAddNextPlay(it.toSong()) }
                            Toast.makeText(context, "已将 ${selectedItems.size} 首云盘歌曲加入下一首播放", Toast.LENGTH_SHORT).show()
                            onSelectionModeChange(false)
                            onSelectedKvIdsChange(emptySet())
                        },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("下一首播", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        enabled = selectedItems.isNotEmpty(),
                        onClick = { batchAddToPlaylistSongs = selectedItems.map { it.toSong() } },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("加入歌单", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        enabled = selectedItems.isNotEmpty() && !deleting,
                        onClick = { pendingDeleteItems = selectedItems },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (deleting) "同步删除中..." else "云端删除 (${selectedItems.size})",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    pendingDeleteItems?.let { targets ->
        AlertDialog(
            onDismissRequest = { if (!deleting) pendingDeleteItems = null },
            title = { Text(if (targets.size == 1) "从云盘永久删除？" else "批量删除 ${targets.size} 首云盘歌曲？", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text(
                    if (targets.size == 1) "确认将《${targets.first().title}》从酷狗官方云盘中实时永久移除吗？删除后不可恢复。"
                    else "确认将所选的 ${targets.size} 首云盘曲目从酷狗官方云盘永久移除吗？删除操作将实时同步至云端。"
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        val toDelete = targets
                        pendingDeleteItems = null
                        viewModel.deleteCloudSongsBatch(toDelete) { ok, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            if (ok && selectionMode) {
                                onSelectionModeChange(false)
                                onSelectedKvIdsChange(emptySet())
                            }
                        }
                    }
                ) {
                    Text("永久删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { pendingDeleteItems = null }) {
                    Text("取消")
                }
            }
        )
    }

    batchAddToPlaylistSongs?.let { songsToAdd ->
        val leadSong = songsToAdd.firstOrNull()
        val createdLists = remember(playlists) {
            playlists.filter { it.isCreatedByMe && !it.isLiked && !it.isDefaultCollect }
        }
        if (leadSong != null) {
            AddToPlaylistDialog(
                song = leadSong,
                playlists = createdLists,
                onDismiss = { batchAddToPlaylistSongs = null },
                onSelectPlaylist = { pl ->
                    batchAddToPlaylistSongs = null
                    viewModel.addSongsToPlaylistBatch(pl, songsToAdd) { _, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                    if (selectionMode) {
                        onSelectionModeChange(false)
                        onSelectedKvIdsChange(emptySet())
                    }
                },
                onCreateNewPlaylist = { showCreatePlaylistForBatch = true }
            )
        }
    }

    if (showCreatePlaylistForBatch) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistForBatch = false },
            onConfirmWithCallback = { name, onDone ->
                viewModel.createPlaylist(name) { ok, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    onDone(ok)
                    if (ok) showCreatePlaylistForBatch = false
                }
            }
        )
    }
}

@Composable
private fun CloudStorageOverviewHeroCard(
    summary: CloudDiskSummary,
    displayedCount: Int,
    cyanAccent: Color,
    isDark: Boolean,
    onPlayAll: () -> Unit,
    onShufflePlay: () -> Unit
) {
    val rawProgress = if (summary.maxBytes > 0L) {
        (summary.usedBytes.toFloat() / summary.maxBytes.toFloat()).coerceIn(0f, 1f)
    } else if (summary.songs.isNotEmpty()) {
        0.18f
    } else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "cloudStorageProgress"
    )

    val losslessCount = remember(summary.songs) {
        summary.songs.count {
            it.effectiveQuality == AudioQuality.HI_RES || it.effectiveQuality == AudioQuality.LOSSLESS
        }
    }
    val localSumBytes = remember(summary.songs) { summary.songs.sumOf { it.fileSize } }
    val effectiveUsedBytes = if (summary.usedBytes > 0L) summary.usedBytes else localSumBytes

    val heroBrush = if (isDark) {
        Brush.linearGradient(
            colors = listOf(Color(0xFF122B31), Color(0xFF132033), Color(0xFF141A29))
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color(0xFFE0F8F5), Color(0xFFEDF6FF), Color(0xFFF7FAFF))
        )
    }

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(heroBrush)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // 雅致环形云容量指示器
                    Box(modifier = Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokePx = 4.2.dp.toPx()
                            val inset = strokePx / 2f
                            val arcSize = Size(size.width - strokePx, size.height - strokePx)
                            drawArc(
                                color = cyanAccent.copy(alpha = 0.16f),
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = Offset(inset, inset),
                                size = arcSize,
                                style = Stroke(width = strokePx, cap = StrokeCap.Round)
                            )
                            drawArc(
                                brush = Brush.sweepGradient(
                                    colors = listOf(cyanAccent, ConceptPrimary, cyanAccent)
                                ),
                                startAngle = -90f,
                                sweepAngle = 360f * animatedProgress.coerceAtLeast(0.04f),
                                useCenter = false,
                                topLeft = Offset(inset, inset),
                                size = arcSize,
                                style = Stroke(width = strokePx, cap = StrokeCap.Round)
                            )
                        }
                        RefinedShortcutBadge(
                            glyph = LibraryShortcutGlyph.CLOUD_DISK,
                            accentColor = cyanAccent,
                            size = 36.dp
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${maxOf(summary.totalCount, summary.songs.size)}",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 23.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            Text(
                                text = "首云端珍藏",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.padding(bottom = 3.dp)
                            )
                        }
                        val spaceDesc = if (summary.maxBytes > 0L) {
                            "存储占用 ${formatCloudSpaceText(effectiveUsedBytes)} / ${formatCloudSpaceText(summary.maxBytes)}"
                        } else if (effectiveUsedBytes > 0L) {
                            "已加载曲目体积 ${formatCloudSpaceText(effectiveUsedBytes)}"
                        } else {
                            "私有音频多端无损同步"
                        }
                        Text(
                            text = if (losslessCount > 0) "$spaceDesc · 无损 $losslessCount 首" else spaceDesc,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.5.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (displayedCount > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(15.dp),
                        color = cyanAccent,
                        modifier = Modifier
                            .weight(1f)
                            .softCardClickable(shape = RoundedCornerShape(15.dp), onClick = onPlayAll)
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = if (isDark) Color(0xFF062024) else Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = "播放全部",
                                color = if (isDark) Color(0xFF062024) else Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(15.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.09f else 0.06f),
                        modifier = Modifier
                            .weight(1f)
                            .softCardClickable(shape = RoundedCornerShape(15.dp), onClick = onShufflePlay)
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shuffle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "随机漫游播放",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CloudSongRowCard(
    index: Int,
    item: CloudSongItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    cyanAccent: Color,
    cardBg: Color,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onMoreActions: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coverModel = rememberOptimizedAsyncImageModel(item.coverUrl, sizePx = 144, targetCdnSize = "150")
    val primaryText = MaterialTheme.colorScheme.onSurface
    val subText = MaterialTheme.colorScheme.onSurfaceVariant
    var menuExpanded by remember { mutableStateOf(false) }

    val badgeColor = when (item.effectiveQuality) {
        AudioQuality.HI_RES -> VipGold
        AudioQuality.LOSSLESS -> cyanAccent
        AudioQuality.HIGH -> ConceptPrimary
        AudioQuality.STANDARD -> subText
    }

    val metaParts = remember(item) {
        buildList {
            if (item.artist.isNotBlank()) add(item.artist)
            if (item.album.isNotBlank() && item.album != "我的音乐云盘") add("《${item.album}》")
            val codecBr = item.formattedCodecAndBitrate
            if (codecBr.isNotBlank()) add(codecBr)
            if (item.formattedSize.isNotBlank()) add(item.formattedSize)
        }.joinToString(" · ")
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = when {
            selected -> cyanAccent.copy(alpha = 0.14f)
            isCurrent -> ConceptPrimary.copy(alpha = 0.12f)
            else -> cardBg
        },
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (selected) "已选中" else "未选中",
                    tint = if (selected) cyanAccent else subText.copy(alpha = 0.55f),
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(22.dp)
                )
            } else {
                Box(
                    modifier = Modifier.width(26.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (isCurrent) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.Pause,
                            contentDescription = null,
                            tint = ConceptPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text(
                            text = "$index",
                            color = subText.copy(alpha = 0.72f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 封面缩略图
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(cyanAccent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                if (coverModel != null) {
                    AsyncImage(
                        model = coverModel,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Low,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Album,
                        contentDescription = null,
                        tint = cyanAccent.copy(alpha = 0.8f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = item.title,
                        color = if (isCurrent) ConceptPrimary else primaryText,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    BadgeTag(
                        text = item.qualityBadgeLabel,
                        color = badgeColor
                    )
                }

                Text(
                    text = metaParts,
                    color = subText,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (item.addTime.isNotBlank()) {
                    Text(
                        text = "上传于 ${item.addTime}",
                        color = subText.copy(alpha = 0.65f),
                        fontSize = 10.5.sp,
                        maxLines = 1
                    )
                }
            }

            if (!selectionMode) {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "云盘歌曲操作",
                            tint = subText,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("下一首播放") },
                            leadingIcon = { Icon(Icons.Rounded.QueuePlayNext, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onPlayNext()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("添加到歌单") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onAddToPlaylist()
                            }
                        )
                        if (onMoreActions != null) {
                            DropdownMenuItem(
                                text = { Text("更多歌曲详情") },
                                leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onMoreActions()
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("从云盘永久删除", color = ConceptCoralAccent, fontWeight = FontWeight.Bold) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = ConceptCoralAccent) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}
