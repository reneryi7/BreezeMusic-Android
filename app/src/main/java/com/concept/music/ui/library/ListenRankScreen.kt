package com.concept.music.ui.library

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.concept.music.data.kugou.aggregateArtistListenRank
import com.concept.music.data.kugou.sortListenRankItems
import com.concept.music.data.model.ArtistListenRankItem
import com.concept.music.data.model.ListenRankItem
import com.concept.music.data.model.Song
import com.concept.music.ui.components.BadgeTag
import com.concept.music.ui.components.LibraryShortcutGlyph
import com.concept.music.ui.components.RefinedShortcutBadge
import com.concept.music.ui.components.rememberOptimizedAsyncImageModel
import com.concept.music.ui.theme.ConceptCategoryTabItem
import com.concept.music.ui.theme.ConceptPrimary
import com.concept.music.ui.theme.ConceptSlidingCategoryTabs
import com.concept.music.ui.theme.ConceptStageBackground
import com.concept.music.ui.theme.LocalConceptAdaptiveInfo
import com.concept.music.ui.theme.LocalIsDarkTheme
import com.concept.music.ui.theme.VipGold
import com.concept.music.ui.theme.conceptCategoryContentTransition
import com.concept.music.ui.theme.conceptElevatedSurfaceColor
import com.concept.music.ui.theme.conceptFloatingCardColor
import com.concept.music.ui.theme.conceptGoldAccentColor
import com.concept.music.ui.theme.conceptSheetContainerColor
import com.concept.music.ui.theme.horizontalCategorySwipe
import com.concept.music.ui.theme.softCardClickable
import com.concept.music.ui.theme.softClickable
import com.concept.music.ui.viewmodel.LibraryViewModel
import java.util.Locale

private enum class ListenRankScope(val key: String, val apiType: Int, val label: String, val periodDesc: String) {
    WEEK("week", 0, "近一周", "近 7 天"),
    MONTH("month", 2, "近一月", "近 30 天"),
    ALL("all", 1, "总排行", "全部累计")
}

private enum class ListenRankDimension(val label: String) {
    SONGS("常听歌曲"),
    ARTISTS("最常听歌手")
}

private fun formatListenDurationLabel(totalMs: Long): String {
    if (totalMs <= 0L) return "0 分钟"
    val totalMinutes = (totalMs / 60_000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val mins = totalMinutes % 60L
    return when {
        hours >= 1L && mins > 0L -> "${hours}小时${mins}分"
        hours >= 1L -> "${hours}小时"
        else -> "${mins}分钟"
    }
}

/**
 * 微风本机「听歌排行」全新无边框音乐数据画报页：
 * - 数据完全源于微风本机真实播放记录（有效播放满 15s 自动记次），未登录亦可直接浏览；
 * - 顶部沉浸式成就画报头 + 精致 Top 3 错落领奖台 + No.4 及以后通透开放式排行列表，深浅双主题与手机/平板全面适配。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenRankScreen(
    viewModel: LibraryViewModel,
    currentPlayingSongId: String? = null,
    isPlaying: Boolean = false,
    onBack: () -> Unit,
    onPlaySong: (Song, List<Song>) -> Unit,
    onOpenArtist: (artistId: String, artistName: String, fallbackSongs: List<Song>) -> Unit,
    onOpenSongMore: (Song) -> Unit
) {
    val context = LocalContext.current
    val isDark = LocalIsDarkTheme.current
    val adaptive = LocalConceptAdaptiveInfo.current
    val goldAccent = conceptGoldAccentColor()
    val elevatedBg = conceptElevatedSurfaceColor()
    val primaryText = MaterialTheme.colorScheme.onSurface
    val subText = MaterialTheme.colorScheme.onSurfaceVariant

    val weekList by viewModel.listenRankWeek.collectAsState()
    val monthList by viewModel.listenRankMonth.collectAsState()
    val allList by viewModel.listenRankAll.collectAsState()
    val weekPlayedMs by viewModel.listenRankWeekPlayedMs.collectAsState()
    val monthPlayedMs by viewModel.listenRankMonthPlayedMs.collectAsState()
    val allPlayedMs by viewModel.listenRankAllPlayedMs.collectAsState()
    val artistMetaCache by viewModel.rankArtistMetaCache.collectAsState()

    var selectedScope by remember { mutableStateOf(ListenRankScope.ALL) }
    var dimension by remember { mutableStateOf(ListenRankDimension.SONGS) }
    var ascending by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadListenRank(type = 0, force = true)
    }

    val rawScopeItems = when (selectedScope) {
        ListenRankScope.WEEK -> weekList
        ListenRankScope.MONTH -> monthList
        ListenRankScope.ALL -> allList
    }
    val activePlayedMs = when (selectedScope) {
        ListenRankScope.WEEK -> weekPlayedMs
        ListenRankScope.MONTH -> monthPlayedMs
        ListenRankScope.ALL -> allPlayedMs
    }

    val sortedSongs = remember(rawScopeItems, ascending) {
        sortListenRankItems(rawScopeItems, ascending = ascending)
    }
    val sortedArtists = remember(rawScopeItems, ascending, artistMetaCache) {
        aggregateArtistListenRank(rawScopeItems, ascending = ascending).map { item ->
            val norm = item.artistName.trim().lowercase(Locale.ROOT).replace(Regex("[.\\s·]+"), "")
            val meta = artistMetaCache[norm]
            if (meta != null) {
                item.copy(
                    artistId = item.artistId.ifBlank { meta.first },
                    avatarUrl = meta.second.ifBlank { item.avatarUrl }
                )
            } else item
        }
    }

    val totalPlayCount = remember(rawScopeItems) { rawScopeItems.sumOf { it.playCount } }
    val maxSongPlays = remember(rawScopeItems) { rawScopeItems.maxOfOrNull { it.playCount }?.coerceAtLeast(1) ?: 1 }
    val maxArtistPlays = remember(sortedArtists) { sortedArtists.maxOfOrNull { it.totalPlayCount }?.coerceAtLeast(1) ?: 1 }
    val songQueue = remember(sortedSongs) { sortedSongs.map { it.song } }

    val scopeTabs = remember(goldAccent) {
        ListenRankScope.entries.map { scope ->
            ConceptCategoryTabItem(
                key = scope.key,
                label = scope.label,
                accentColor = goldAccent
            )
        }
    }

    ConceptStageBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "听歌排行",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 19.sp
                                )
                            )
                            Text(
                                text = "微风本机听歌记录",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = subText,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (rawScopeItems.isNotEmpty()) {
                            IconButton(onClick = { ascending = !ascending }) {
                                Icon(
                                    imageVector = if (ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                                    contentDescription = if (ascending) "当前升序，点击切换降序" else "当前降序，点击切换升序",
                                    tint = goldAccent
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = "更多选项",
                                    tint = primaryText
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                                modifier = Modifier.background(conceptSheetContainerColor(), RoundedCornerShape(16.dp))
                            ) {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (ascending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                                            contentDescription = null,
                                            tint = goldAccent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    text = { Text(if (ascending) "切换为次数降序" else "切换为次数升序", fontSize = 13.5.sp) },
                                    onClick = {
                                        showOverflowMenu = false
                                        ascending = !ascending
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    text = {
                                        Text(
                                            text = "清空本机听歌统计",
                                            fontSize = 13.5.sp,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        showClearConfirm = true
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { innerPadding ->
            val horizontalPad = if (adaptive.isLargeScreenDevice) adaptive.horizontalContentPadding else 16.dp

            if (adaptive.useTwoPaneDetailLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = horizontalPad, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // 平板左栏：周期切换 + 沉浸数据画报头
                    Column(
                        modifier = Modifier
                            .width(330.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 112.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        ConceptSlidingCategoryTabs(
                            tabs = scopeTabs,
                            selectedKey = selectedScope.key,
                            onSelectTab = { key ->
                                ListenRankScope.entries.find { it.key == key }?.let { selectedScope = it }
                            },
                            itemSpacing = 20.dp,
                            fontSize = 15.sp,
                            indicatorWidth = 22.dp,
                            fillWidth = false
                        )
                        ListenRankHeroCard(
                            scope = selectedScope,
                            dimension = dimension,
                            onDimensionChange = { dimension = it },
                            totalPlayCount = totalPlayCount,
                            trackCount = rawScopeItems.size,
                            artistCount = sortedArtists.size,
                            totalPlayedMs = activePlayedMs,
                            goldAccent = goldAccent,
                            isDark = isDark,
                            canPlay = songQueue.isNotEmpty(),
                            onPlayAll = {
                                if (songQueue.isNotEmpty()) onPlaySong(songQueue.first(), songQueue)
                            }
                        )
                    }

                    // 平板右栏：领奖台与开放式列表
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        ListenRankContentStage(
                            selectedScope = selectedScope,
                            onScopeChange = { selectedScope = it },
                            dimension = dimension,
                            ascending = ascending,
                            sortedSongs = sortedSongs,
                            sortedArtists = sortedArtists,
                            songQueue = songQueue,
                            maxSongPlays = maxSongPlays,
                            maxArtistPlays = maxArtistPlays,
                            currentPlayingSongId = currentPlayingSongId,
                            isPlaying = isPlaying,
                            goldAccent = goldAccent,
                            elevatedBg = elevatedBg,
                            isDark = isDark,
                            showHeroInsideList = false,
                            heroContent = {},
                            onPlaySong = onPlaySong,
                            onOpenArtist = onOpenArtist,
                            onOpenSongMore = onOpenSongMore
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .widthIn(max = 880.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 单层清爽周期切换导航
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = horizontalPad, vertical = 2.dp)
                    ) {
                        ConceptSlidingCategoryTabs(
                            tabs = scopeTabs,
                            selectedKey = selectedScope.key,
                            onSelectTab = { key ->
                                ListenRankScope.entries.find { it.key == key }?.let { selectedScope = it }
                            },
                            itemSpacing = 24.dp,
                            fontSize = 15.sp,
                            indicatorWidth = 22.dp,
                            fillWidth = false
                        )
                    }

                    ListenRankContentStage(
                        selectedScope = selectedScope,
                        onScopeChange = { selectedScope = it },
                        dimension = dimension,
                        ascending = ascending,
                        sortedSongs = sortedSongs,
                        sortedArtists = sortedArtists,
                        songQueue = songQueue,
                        maxSongPlays = maxSongPlays,
                        maxArtistPlays = maxArtistPlays,
                        currentPlayingSongId = currentPlayingSongId,
                        isPlaying = isPlaying,
                        goldAccent = goldAccent,
                        elevatedBg = elevatedBg,
                        isDark = isDark,
                        showHeroInsideList = true,
                        heroContent = {
                            ListenRankHeroCard(
                                scope = selectedScope,
                                dimension = dimension,
                                onDimensionChange = { dimension = it },
                                totalPlayCount = totalPlayCount,
                                trackCount = rawScopeItems.size,
                                artistCount = sortedArtists.size,
                                totalPlayedMs = activePlayedMs,
                                goldAccent = goldAccent,
                                isDark = isDark,
                                canPlay = songQueue.isNotEmpty(),
                                onPlayAll = {
                                    if (songQueue.isNotEmpty()) onPlaySong(songQueue.first(), songQueue)
                                },
                                modifier = Modifier.padding(horizontal = horizontalPad, vertical = 8.dp)
                            )
                        },
                        horizontalPadding = horizontalPad,
                        onPlaySong = onPlaySong,
                        onOpenArtist = onOpenArtist,
                        onOpenSongMore = onOpenSongMore
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空微风本机听歌排行？", fontWeight = FontWeight.ExtraBold) },
            text = { Text("将清除当前账号在微风本机累计的周榜、月榜与总榜收听频次统计，清除后不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearLocalListenStats {
                            Toast.makeText(context, "已清空本机听歌排行记录", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认清空", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun ListenRankHeroCard(
    scope: ListenRankScope,
    dimension: ListenRankDimension,
    onDimensionChange: (ListenRankDimension) -> Unit,
    totalPlayCount: Int,
    trackCount: Int,
    artistCount: Int,
    totalPlayedMs: Long,
    goldAccent: Color,
    isDark: Boolean,
    canPlay: Boolean,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val heroBrush = if (isDark) {
        Brush.linearGradient(
            colors = listOf(Color(0xFF2B2114), Color(0xFF1D1B28), Color(0xFF141826))
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color(0xFFFFF3DD), Color(0xFFFFF8ED), Color(0xFFF6F7FF))
        )
    }
    val onAccent = if (isDark) Color(0xFF241604) else Color.White

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = Color.Transparent,
        shadowElevation = 0.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(heroBrush)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${scope.periodDesc}微风收听统计",
                        color = goldAccent,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "$totalPlayCount",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            lineHeight = 34.sp
                        )
                        Text(
                            text = "次播放",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Text(
                        text = if (trackCount > 0) {
                            "上榜 $trackCount 首歌 · $artistCount 位歌手 · 累计 ${formatListenDurationLabel(totalPlayedMs)}"
                        } else {
                            "在微风中听满 15 秒将自动累计本机排行"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(12.dp))

                RefinedShortcutBadge(
                    glyph = LibraryShortcutGlyph.LISTEN_RANK,
                    accentColor = goldAccent,
                    size = 46.dp
                )
            }

            // 维度切换 + 播放榜单整合为单排清爽控件
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.10f else 0.06f)
                ) {
                    Row(
                        modifier = Modifier.padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        ListenRankDimension.entries.forEach { dim ->
                            val selected = dimension == dim
                            Surface(
                                shape = CircleShape,
                                color = if (selected) goldAccent else Color.Transparent,
                                modifier = Modifier.softClickable(pressScale = 0.95f) {
                                    onDimensionChange(dim)
                                }
                            ) {
                                Text(
                                    text = dim.label,
                                    color = if (selected) onAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                if (canPlay) {
                    Surface(
                        shape = CircleShape,
                        color = goldAccent.copy(alpha = if (isDark) 0.20f else 0.16f),
                        modifier = Modifier.softCardClickable(shape = CircleShape, onClick = onPlayAll)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "播放榜单",
                                tint = goldAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "播放全部",
                                color = goldAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListenRankContentStage(
    selectedScope: ListenRankScope,
    onScopeChange: (ListenRankScope) -> Unit,
    dimension: ListenRankDimension,
    ascending: Boolean,
    sortedSongs: List<ListenRankItem>,
    sortedArtists: List<ArtistListenRankItem>,
    songQueue: List<Song>,
    maxSongPlays: Int,
    maxArtistPlays: Int,
    currentPlayingSongId: String?,
    isPlaying: Boolean,
    goldAccent: Color,
    elevatedBg: Color,
    isDark: Boolean,
    showHeroInsideList: Boolean,
    heroContent: @Composable () -> Unit,
    horizontalPadding: Dp = 0.dp,
    onPlaySong: (Song, List<Song>) -> Unit,
    onOpenArtist: (String, String, List<Song>) -> Unit,
    onOpenSongMore: (Song) -> Unit
) {
    val scopes = ListenRankScope.entries
    val currentScopeIdx = scopes.indexOf(selectedScope).coerceAtLeast(0)

    AnimatedContent(
        targetState = Pair(selectedScope, dimension),
        transitionSpec = {
            val fromIdx = scopes.indexOf(initialState.first)
            val toIdx = scopes.indexOf(targetState.first)
            val dir = when {
                toIdx != fromIdx -> if (toIdx > fromIdx) 1 else -1
                targetState.second.ordinal >= initialState.second.ordinal -> 1
                else -> -1
            }
            conceptCategoryContentTransition(dir)
        },
        label = "listenRankContentTransition",
        modifier = Modifier
            .fillMaxSize()
            .horizontalCategorySwipe(
                currentIndex = currentScopeIdx,
                itemCount = scopes.size,
                onSelectIndex = { idx -> scopes.getOrNull(idx)?.let(onScopeChange) }
            )
    ) { (_, activeDim) ->
        val listState = rememberLazyListState()
        val isEmpty = if (activeDim == ListenRankDimension.SONGS) sortedSongs.isEmpty() else sortedArtists.isEmpty()
        val showSongPodium = activeDim == ListenRankDimension.SONGS && !ascending && sortedSongs.size >= 3
        val showArtistPodium = activeDim == ListenRankDimension.ARTISTS && !ascending && sortedArtists.size >= 3

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 128.dp)
        ) {
            if (showHeroInsideList) {
                item(key = "rank_hero_item", contentType = "rank_hero") {
                    heroContent()
                }
            }

            if (isEmpty) {
                item(key = "rank_empty_item", contentType = "rank_empty") {
                    ListenRankEmptyPlaceholder(
                        scope = selectedScope,
                        goldAccent = goldAccent,
                        modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 28.dp)
                    )
                }
            } else if (activeDim == ListenRankDimension.SONGS) {
                if (showSongPodium) {
                    item(key = "rank_song_podium", contentType = "rank_podium") {
                        SongTopThreePodium(
                            topThree = sortedSongs.take(3),
                            goldAccent = goldAccent,
                            isDark = isDark,
                            onSongClick = { song -> onPlaySong(song, songQueue) },
                            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 10.dp)
                        )
                    }
                }
                val listSongs = if (showSongPodium) sortedSongs.drop(3) else sortedSongs
                itemsIndexed(
                    items = listSongs,
                    key = { idx, item -> "rank_song_${selectedScope.key}_${item.song.hash.ifBlank { item.song.id }}_$idx" },
                    contentType = { _, _ -> "rank_song_row" }
                ) { idx, item ->
                    val isCurrent = currentPlayingSongId != null &&
                        (item.song.id == currentPlayingSongId ||
                            (item.song.hash.isNotBlank() && currentPlayingSongId.contains(item.song.hash.take(8), ignoreCase = true)))
                    OpenListenRankSongRow(
                        item = item,
                        maxPlays = maxSongPlays,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && isPlaying,
                        goldAccent = goldAccent,
                        elevatedBg = elevatedBg,
                        onClick = { onPlaySong(item.song, songQueue) },
                        onMoreClick = { onOpenSongMore(item.song) },
                        modifier = Modifier.padding(horizontal = horizontalPadding)
                    )
                    if (idx < listSongs.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = horizontalPadding + 60.dp, end = horizontalPadding + 12.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                        )
                    }
                }
            } else {
                if (showArtistPodium) {
                    item(key = "rank_artist_podium", contentType = "rank_artist_podium") {
                        ArtistTopThreePodium(
                            topThree = sortedArtists.take(3),
                            goldAccent = goldAccent,
                            isDark = isDark,
                            onArtistClick = { artist ->
                                onOpenArtist(artist.artistId, artist.artistName, artist.songs)
                            },
                            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 10.dp)
                        )
                    }
                }
                val listArtists = if (showArtistPodium) sortedArtists.drop(3) else sortedArtists
                itemsIndexed(
                    items = listArtists,
                    key = { idx, item -> "rank_artist_${selectedScope.key}_${item.artistName}_$idx" },
                    contentType = { _, _ -> "rank_artist_row" }
                ) { idx, artist ->
                    OpenListenRankArtistRow(
                        artist = artist,
                        maxPlays = maxArtistPlays,
                        goldAccent = goldAccent,
                        elevatedBg = elevatedBg,
                        onClick = { onOpenArtist(artist.artistId, artist.artistName, artist.songs) },
                        modifier = Modifier.padding(horizontal = horizontalPadding)
                    )
                    if (idx < listArtists.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = horizontalPadding + 60.dp, end = horizontalPadding + 12.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Top 3 歌曲错落领奖台（No.2 左 · No.1 中高 · No.3 右）
 */
@Composable
private fun SongTopThreePodium(
    topThree: List<ListenRankItem>,
    goldAccent: Color,
    isDark: Boolean,
    onSongClick: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    if (topThree.size < 3) return
    val first = topThree[0]
    val second = topThree[1]
    val third = topThree[2]
    val silverColor = if (isDark) Color(0xFFC8D4E6) else Color(0xFF7B8CA3)
    val bronzeColor = if (isDark) Color(0xFFE09E6D) else Color(0xFFB86B35)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        PodiumSongColumn(
            rankNo = 2,
            item = second,
            medalColor = silverColor,
            coverSize = 64.dp,
            topOffset = 14.dp,
            isDark = isDark,
            onClick = { onSongClick(second.song) },
            modifier = Modifier.weight(1f)
        )
        PodiumSongColumn(
            rankNo = 1,
            item = first,
            medalColor = goldAccent,
            coverSize = 80.dp,
            topOffset = 0.dp,
            isDark = isDark,
            onClick = { onSongClick(first.song) },
            modifier = Modifier.weight(1.16f)
        )
        PodiumSongColumn(
            rankNo = 3,
            item = third,
            medalColor = bronzeColor,
            coverSize = 64.dp,
            topOffset = 18.dp,
            isDark = isDark,
            onClick = { onSongClick(third.song) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PodiumSongColumn(
    rankNo: Int,
    item: ListenRankItem,
    medalColor: Color,
    coverSize: Dp,
    topOffset: Dp,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coverModel = rememberOptimizedAsyncImageModel(item.song.coverUrl, sizePx = 220, targetCdnSize = "240")
    val bgBrush = Brush.verticalGradient(
        colors = listOf(
            medalColor.copy(alpha = if (isDark) 0.16f else 0.14f),
            medalColor.copy(alpha = if (isDark) 0.04f else 0.03f)
        )
    )
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent,
        modifier = modifier
            .padding(top = topOffset)
            .softCardClickable(shape = RoundedCornerShape(22.dp), onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgBrush)
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "NO.$rankNo",
                color = medalColor,
                fontSize = if (rankNo == 1) 12.sp else 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .size(coverSize)
                    .shadow(if (rankNo == 1) 10.dp else 4.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(medalColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                if (coverModel != null) {
                    AsyncImage(
                        model = coverModel,
                        contentDescription = item.song.title,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Low,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = medalColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = item.song.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = if (rankNo == 1) 13.5.sp else 12.5.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.song.artist,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = CircleShape,
                color = medalColor.copy(alpha = if (isDark) 0.22f else 0.16f)
            ) {
                Text(
                    text = "${item.playCount} 次",
                    color = medalColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.5.dp)
                )
            }
        }
    }
}

/**
 * Top 3 歌手错落领奖台
 */
@Composable
private fun ArtistTopThreePodium(
    topThree: List<ArtistListenRankItem>,
    goldAccent: Color,
    isDark: Boolean,
    onArtistClick: (ArtistListenRankItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (topThree.size < 3) return
    val silverColor = if (isDark) Color(0xFFC8D4E6) else Color(0xFF7B8CA3)
    val bronzeColor = if (isDark) Color(0xFFE09E6D) else Color(0xFFB86B35)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        PodiumArtistColumn(
            rankNo = 2,
            artist = topThree[1],
            medalColor = silverColor,
            avatarSize = 62.dp,
            topOffset = 14.dp,
            isDark = isDark,
            onClick = { onArtistClick(topThree[1]) },
            modifier = Modifier.weight(1f)
        )
        PodiumArtistColumn(
            rankNo = 1,
            artist = topThree[0],
            medalColor = goldAccent,
            avatarSize = 78.dp,
            topOffset = 0.dp,
            isDark = isDark,
            onClick = { onArtistClick(topThree[0]) },
            modifier = Modifier.weight(1.16f)
        )
        PodiumArtistColumn(
            rankNo = 3,
            artist = topThree[2],
            medalColor = bronzeColor,
            avatarSize = 62.dp,
            topOffset = 18.dp,
            isDark = isDark,
            onClick = { onArtistClick(topThree[2]) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PodiumArtistColumn(
    rankNo: Int,
    artist: ArtistListenRankItem,
    medalColor: Color,
    avatarSize: Dp,
    topOffset: Dp,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val avatarModel = rememberOptimizedAsyncImageModel(artist.avatarUrl, sizePx = 220, targetCdnSize = "240")
    val bgBrush = Brush.verticalGradient(
        colors = listOf(
            medalColor.copy(alpha = if (isDark) 0.16f else 0.14f),
            medalColor.copy(alpha = if (isDark) 0.04f else 0.03f)
        )
    )
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent,
        modifier = modifier
            .padding(top = topOffset)
            .softCardClickable(shape = RoundedCornerShape(22.dp), onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgBrush)
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "NO.$rankNo",
                color = medalColor,
                fontSize = if (rankNo == 1) 12.sp else 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .shadow(if (rankNo == 1) 10.dp else 4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(medalColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                if (avatarModel != null) {
                    AsyncImage(
                        model = avatarModel,
                        contentDescription = artist.artistName,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Low,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = artist.artistName.take(1).uppercase(Locale.ROOT),
                        color = medalColor,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = artist.artistName,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = if (rankNo == 1) 13.5.sp else 12.5.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${artist.trackCount} 首上榜",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = CircleShape,
                color = medalColor.copy(alpha = if (isDark) 0.22f else 0.16f)
            ) {
                Text(
                    text = "${artist.totalPlayCount} 次",
                    color = medalColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.5.dp)
                )
            }
        }
    }
}

/**
 * 开放式无框歌曲排行行（去除厚重黑盒与突兀底部横线，信息呼吸通透）
 */
@Composable
private fun OpenListenRankSongRow(
    item: ListenRankItem,
    maxPlays: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    goldAccent: Color,
    elevatedBg: Color,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val song = item.song
    val coverModel = rememberOptimizedAsyncImageModel(song.coverUrl, sizePx = 144, targetCdnSize = "150")
    val ratio = (item.playCount.toFloat() / maxPlays.coerceAtLeast(1).toFloat()).coerceIn(0.10f, 1f)
    val rankColor = when (item.rank) {
        1 -> goldAccent
        2 -> ConceptPrimary
        3 -> Color(0xFFE09E6D)
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isCurrent) ConceptPrimary.copy(alpha = 0.10f) else Color.Transparent)
            .softClickable(pressScale = 0.985f, onClick = onClick)
            .padding(start = 6.dp, end = 2.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.rank.toString().padStart(2, '0'),
            color = if (isCurrent) ConceptPrimary else rankColor,
            fontSize = 13.5.sp,
            fontWeight = if (item.rank <= 3 || isCurrent) FontWeight.Black else FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(30.dp)
        )

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(elevatedBg),
            contentAlignment = Alignment.Center
        ) {
            if (coverModel != null) {
                AsyncImage(
                    model = coverModel,
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = goldAccent.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = song.title,
                    color = if (isCurrent) ConceptPrimary else MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.5.sp,
                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (song.isHiRes) {
                    BadgeTag(text = "Hi-Res", color = VipGold)
                } else if (song.isLossless) {
                    BadgeTag(text = "SQ", color = ConceptPrimary)
                }
            }
            Text(
                text = if (song.album.isNotBlank() && song.album != "单曲" && song.album != "未知专辑") {
                    "${song.artist} · ${song.album}"
                } else song.artist,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "${item.playCount} 次",
                color = if (isCurrent) ConceptPrimary else goldAccent,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(ratio)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(listOf(goldAccent.copy(alpha = 0.55f), goldAccent))
                        )
                )
            }
        }

        IconButton(onClick = onMoreClick, modifier = Modifier.size(34.dp)) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "更多操作",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 开放式无框歌手排行行
 */
@Composable
private fun OpenListenRankArtistRow(
    artist: ArtistListenRankItem,
    maxPlays: Int,
    goldAccent: Color,
    elevatedBg: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val avatarModel = rememberOptimizedAsyncImageModel(artist.avatarUrl, sizePx = 144, targetCdnSize = "150")
    val ratio = (artist.totalPlayCount.toFloat() / maxPlays.coerceAtLeast(1).toFloat()).coerceIn(0.10f, 1f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .softClickable(pressScale = 0.985f, onClick = onClick)
            .padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = artist.rank.toString().padStart(2, '0'),
            color = if (artist.rank <= 3) goldAccent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            fontSize = 13.5.sp,
            fontWeight = if (artist.rank <= 3) FontWeight.Black else FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(30.dp)
        )

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(elevatedBg),
            contentAlignment = Alignment.Center
        ) {
            if (avatarModel != null) {
                AsyncImage(
                    model = avatarModel,
                    contentDescription = artist.artistName,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = artist.artistName.take(1).uppercase(Locale.ROOT),
                    color = goldAccent,
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = artist.artistName,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append("${artist.trackCount} 首上榜")
                    if (artist.topSongTitle.isNotBlank()) {
                        append(" · 常听《${artist.topSongTitle}》")
                    }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "${artist.totalPlayCount} 次",
                color = goldAccent,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(ratio)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(listOf(goldAccent.copy(alpha = 0.55f), goldAccent))
                        )
                )
            }
        }

        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun ListenRankEmptyPlaceholder(
    scope: ListenRankScope,
    goldAccent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = conceptFloatingCardColor(),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RefinedShortcutBadge(
                glyph = LibraryShortcutGlyph.LISTEN_RANK,
                accentColor = goldAccent,
                size = 56.dp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${scope.label}暂无微风听歌记录",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "在微风音乐播放任意歌曲满 15 秒，即可自动统计到本机周榜、月榜与总排行",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp
            )
        }
    }
}
