package com.concept.music.ui.home

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.concept.music.data.kugou.AuthState
import com.concept.music.data.model.DailyHistoryEntry
import com.concept.music.data.model.FmChannel
import com.concept.music.data.model.RankItem
import com.concept.music.data.model.Song
import androidx.compose.ui.graphics.FilterQuality
import com.concept.music.ui.components.BadgeTag
import com.concept.music.ui.components.rememberOptimizedAsyncImageModel
import com.concept.music.ui.viewmodel.PersonalFmDislikeUndoToken
import com.concept.music.ui.components.FloatingScrollToTopButton
import com.concept.music.ui.components.rememberProgressiveSongList
import com.concept.music.ui.components.smoothFastScrollToItem
import com.concept.music.ui.components.SongItemRow
import com.concept.music.ui.theme.ConceptAnimatedOverlaySheet
import com.concept.music.ui.theme.ConceptMotion
import com.concept.music.ui.theme.ConceptAmberAccent
import com.concept.music.ui.theme.ConceptCoralAccent
import com.concept.music.ui.theme.ConceptCyanAccent
import com.concept.music.ui.theme.ConceptMintAccent
import com.concept.music.ui.theme.ConceptPrimary
import com.concept.music.ui.theme.ConceptPrimaryDark
import com.concept.music.ui.theme.ConceptStageBackground
import com.concept.music.ui.theme.ConceptVioletAccent
import com.concept.music.ui.theme.LocalIsDarkTheme
import com.concept.music.ui.theme.VipGold
import com.concept.music.ui.theme.conceptElevatedSurfaceColor
import com.concept.music.ui.theme.conceptFloatingCardColor
import com.concept.music.ui.theme.conceptSheetContainerColor
import com.concept.music.ui.theme.softCardClickable
import com.concept.music.ui.theme.ConceptCategoryTabItem
import com.concept.music.ui.theme.ConceptFluidRefreshIcon
import com.concept.music.ui.theme.conceptCategoryContentTransition
import com.concept.music.ui.theme.horizontalCategorySwipe
import com.concept.music.ui.theme.ConceptSlidingCategoryTabs
import com.concept.music.ui.theme.softClickable
import com.concept.music.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal val DISCOVER_CHINA_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

internal data class NewSongPeriodTab(
    val key: String,
    val label: String,
    val badge: String
)

// 对齐 MakcRe/KuGouMusicApi module/top_song.js: 21608华语 / 21609欧美 / 21610日韩 / 21611粤语（移除“全部”Tag，保留四大精准语种分类）
internal val NEW_SONG_PERIOD_TABS = listOf(
    NewSongPeriodTab("CHINESE", "华语", "华语"),
    NewSongPeriodTab("WESTERN", "欧美", "欧美"),
    NewSongPeriodTab("JPKR", "日韩", "日韩"),
    NewSongPeriodTab("CANTON", "粤语", "粤语")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onSongClick: (Song, List<Song>) -> Unit,
    onSearchClick: () -> Unit = {},
    onOpenDailyRecommend: () -> Unit = {},
    onOpenNewSongExpress: () -> Unit = {},
    onOpenRank: (Int) -> Unit = {},
    onOpenRadio: (String) -> Unit = {},
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    onTogglePlayPause: () -> Unit = {},
    onSongMoreClick: (Song) -> Unit = {},
    isOverlayOpen: Boolean = false,
    onSubSheetOpenChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dailySongs by viewModel.dailyRecommendSongs.collectAsState()
    val personalFmSongs by viewModel.personalFmSongs.collectAsState()
    val personalFmIndex by viewModel.personalFmIndex.collectAsState()
    val personalFmRefreshing by viewModel.personalFmRefreshing.collectAsState()
    val fmSongPoolId by viewModel.fmSongPoolId.collectAsState()
    val selectedNewSongPeriod by viewModel.selectedNewSongPeriod.collectAsState()
    val currentNewSongs by viewModel.currentNewSongs.collectAsState()
    val newSongsLoading by viewModel.newSongsLoading.collectAsState()
    val fmChannels by viewModel.fmChannels.collectAsState()
    val ranks by viewModel.ranks.collectAsState()
    val loading by viewModel.loading.collectAsState()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isDark = LocalIsDarkTheme.current

    val pullRefreshState = rememberPullToRefreshState()
    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.refresh(
                isBackgroundRevalidate = false,
                onFinished = { pullRefreshState.endRefresh() }
            )
        }
    }
    LaunchedEffect(loading) {
        if (!loading && pullRefreshState.isRefreshing) {
            pullRefreshState.endRefresh()
        }
    }

    // 私人随心听 FM 漫游候选列表抽屉
    var showPersonalFmSheet by remember { mutableStateOf(false) }

    // 单个场景电台快捷漫游抽屉（与「电台专区」独立区分）
    var quickFmStation by remember { mutableStateOf<FmChannel?>(null) }
    var quickFmSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var quickFmLoading by remember { mutableStateOf(false) }
    var quickFmLoadingMore by remember { mutableStateOf(false) }

    // 发现页内直接打开的全量榜单分类宫格选择器
    var showRankGridSheet by remember { mutableStateOf(false) }
    var selectedRankCategory by remember { mutableStateOf("全部") }

    // 猜你喜欢专属负反馈防误触撤销快照（4.5 秒后自动消失）
    var recentDislikeUndoToken by remember { mutableStateOf<PersonalFmDislikeUndoToken?>(null) }
    var recentDislikeFeedbackMsg by remember { mutableStateOf("") }
    var recentDislikedWasPlayingFm by remember { mutableStateOf(false) }
    LaunchedEffect(recentDislikeUndoToken) {
        if (recentDislikeUndoToken != null) {
            kotlinx.coroutines.delay(4_500L)
            recentDislikeUndoToken = null
        }
    }

    // 标记抽屉是否已进入退出动画阶段，使悬浮播放条与底栏在抽屉开始收起瞬间即同步丝滑浮现
    var isHomeSubSheetDismissing by remember { mutableStateOf(false) }

    // 当打开歌曲评论页、全屏播放页或二级页面时，立即关闭首页内的子抽屉
    LaunchedEffect(isOverlayOpen) {
        if (isOverlayOpen) {
            showPersonalFmSheet = false
            quickFmStation = null
            showRankGridSheet = false
            isHomeSubSheetDismissing = false
        }
    }

    LaunchedEffect(showPersonalFmSheet, quickFmStation, showRankGridSheet) {
        if (showPersonalFmSheet || quickFmStation != null || showRankGridSheet) {
            isHomeSubSheetDismissing = false
        }
    }

    val anyHomeSubSheetOpen = (showPersonalFmSheet || quickFmStation != null || showRankGridSheet) && !isOverlayOpen && !isHomeSubSheetDismissing
    LaunchedEffect(anyHomeSubSheetOpen) {
        onSubSheetOpenChanged(anyHomeSubSheetOpen)
    }
    DisposableEffect(Unit) {
        onDispose { onSubSheetOpenChanged(false) }
    }

    // 严格隔离随心听与每日推荐数据源，绝不在随心听未加载完成时盗用每日推荐第一首歌曲的封面
    val fmPool = personalFmSongs
    LaunchedEffect(currentSong?.id, currentSong?.hash, fmPool) {
        if (currentSong != null && fmPool.isNotEmpty()) {
            val matchedIdx = fmPool.indexOfFirst { s ->
                (s.id.isNotBlank() && s.id == currentSong.id) ||
                    (s.hash.isNotBlank() && s.hash.equals(currentSong.hash, ignoreCase = true))
            }
            if (matchedIdx >= 0 && matchedIdx != personalFmIndex) {
                viewModel.setPersonalFmIndex(matchedIdx)
            }
        }
    }

    fun openFmStationQuickSheet(channel: FmChannel) {
        quickFmStation = channel
        val cached = viewModel.getCachedFmChannelSongs(channel.id)
        if (cached.isNotEmpty()) {
            quickFmSongs = cached
            quickFmLoading = false
        } else {
            quickFmLoading = true
        }
        coroutineScope.launch {
            val loaded = viewModel.getFmChannelSongs(channel.id, forceNextBatch = false)
            if (loaded.isNotEmpty()) {
                quickFmSongs = loaded
            }
            quickFmLoading = false
        }
    }

    val coverPool = remember(dailySongs, personalFmSongs, currentNewSongs, ranks) {
        (dailySongs.mapNotNull { it.coverUrl.takeIf { u -> u.isNotBlank() } } +
            currentNewSongs.mapNotNull { it.coverUrl.takeIf { u -> u.isNotBlank() } } +
            personalFmSongs.mapNotNull { it.coverUrl.takeIf { u -> u.isNotBlank() } } +
            ranks.mapNotNull { it.coverUrl.takeIf { u -> u.isNotBlank() } }).distinct()
    }

    val rankCategories = remember(ranks) {
        listOf("全部") + ranks.map { it.category.ifBlank { "热门" } }.distinct()
    }
    ConceptStageBackground(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(pullRefreshState.nestedScrollConnection)
    ) {
        val homeAdaptive = com.concept.music.ui.theme.LocalConceptAdaptiveInfo.current
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
        // 仅在平板版安装包且当前发现页容器自身宽度 >= 680dp（如平板竖屏独享整屏宽度）时才将日推与私人FM左右并排；
        // 在平板横屏「发现+我的」双栏平铺（DUAL_PANE_HOME）下，发现页占左半屏，保持上下排布更舒展美观。
        val isWideDiscoverPane = homeAdaptive.isLargeScreenDevice && maxWidth >= 680.dp
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 920.dp)
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 148.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (loading && dailySongs.isEmpty()) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(CircleShape),
                        color = ConceptPrimary,
                        trackColor = Color.Transparent
                    )
                }
            }

            // 1. 顶部标题与右上角长圆形搜索按钮 + 全时段精细化问候文案
            item(key = "discover_header", contentType = "discover_header") {
                DiscoverTopHeader(onSearchClick = onSearchClick)
            }

            if (isWideDiscoverPane) {
                // 平板竖屏/分屏或超宽发现页：每日推荐与私人随心听 FM 并排展示
                item(key = "discover_wide_hero_row", contentType = "discover_wide_hero_row") {
                    val safeIdx = if (fmPool.isEmpty()) 0 else personalFmIndex.coerceIn(0, fmPool.lastIndex)
                    val activeFmSong = fmPool.getOrNull(safeIdx)
                    val nextFmSong = if (fmPool.size > 1) fmPool.getOrNull((safeIdx + 1) % fmPool.size) else null
                    val canSkipPrev = safeIdx > 0
                    val isCurrentInFm = currentSong != null && activeFmSong != null && (
                        (currentSong.id.isNotBlank() && currentSong.id == activeFmSong.id) ||
                            (currentSong.hash.isNotBlank() && currentSong.hash.equals(activeFmSong.hash, ignoreCase = true))
                        )
                    val isFmPlayingNow = isCurrentInFm && isPlaying

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(modifier = Modifier.weight(0.46f)) {
                            DailyRecommendBanner(
                                songs = dailySongs,
                                onCardClick = onOpenDailyRecommend,
                                onPlayAll = {
                                    if (dailySongs.isNotEmpty()) {
                                        onSongClick(dailySongs.first(), dailySongs)
                                    } else {
                                        Toast.makeText(context, "每日推荐正在加载中", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                        Box(modifier = Modifier.weight(0.54f)) {
                            PersonalFmPlayerCard(
                                activeSong = activeFmSong,
                                nextSong = nextFmSong,
                                isPlaying = isFmPlayingNow,
                                isRefreshing = personalFmRefreshing,
                                fmSongPoolId = fmSongPoolId,
                                canSkipPrev = canSkipPrev,
                                onOpenList = { showPersonalFmSheet = true },
                                onTogglePlayPause = {
                                    if (isCurrentInFm) {
                                        onTogglePlayPause()
                                    } else if (activeFmSong != null) {
                                        onSongClick(activeFmSong, fmPool)
                                    } else {
                                        viewModel.refreshPersonalFm { loaded ->
                                            if (loaded.isNotEmpty()) {
                                                onSongClick(loaded.first(), loaded)
                                            }
                                        }
                                    }
                                },
                                onSkipPrev = {
                                    viewModel.skipToPreviousPersonalFm { prevSong, list ->
                                        onSongClick(prevSong, list)
                                    }
                                },
                                onSkipNext = {
                                    viewModel.skipToNextPersonalFm { nextSong, list ->
                                        onSongClick(nextSong, list)
                                    }
                                },
                                onDislikeSong = {
                                    if (activeFmSong != null) {
                                        val shouldSwitchPlayer = isCurrentInFm
                                        recentDislikedWasPlayingFm = shouldSwitchPlayer
                                        recentDislikeUndoToken = viewModel.dislikeCurrentPersonalFmSong(
                                            song = activeFmSong,
                                            onNextPlay = { nextSong, list ->
                                                if (shouldSwitchPlayer) onSongClick(nextSong, list)
                                            },
                                            onFeedbackMessage = { msg -> recentDislikeFeedbackMsg = msg }
                                        )
                                    }
                                },
                                onDislikeArtist = {
                                    if (activeFmSong != null) {
                                        val shouldSwitchPlayer = isCurrentInFm
                                        recentDislikedWasPlayingFm = shouldSwitchPlayer
                                        recentDislikeUndoToken = viewModel.dislikeCurrentPersonalFmArtist(
                                            song = activeFmSong,
                                            onNextPlay = { nextSong, list ->
                                                if (shouldSwitchPlayer) onSongClick(nextSong, list)
                                            },
                                            onFeedbackMessage = { msg -> recentDislikeFeedbackMsg = msg }
                                        )
                                    }
                                },
                                onSelectPool = { targetPool ->
                                    val poolName = when (targetPool) {
                                        1 -> "熟悉 · 相似风格未听新曲"
                                        2 -> "尝鲜 · 跨风味异质探索"
                                        else -> "平衡 · 智能口味漫游"
                                    }
                                    viewModel.switchPersonalFmPool(targetPool) { loaded ->
                                        Toast.makeText(context, "已切换至「$poolName」", Toast.LENGTH_SHORT).show()
                                        if (loaded.isNotEmpty() && isFmPlayingNow) {
                                            onSongClick(loaded.first(), loaded)
                                        }
                                    }
                                },
                                onRefreshNewBatch = {
                                    viewModel.refreshPersonalFm { loaded ->
                                        if (loaded.isNotEmpty()) {
                                            onSongClick(loaded.first(), loaded)
                                            Toast.makeText(context, "已按当前偏好焕新 ${loaded.size} 首推荐", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            } else {
                // 2. 每日推荐大横幅
                item(key = "discover_daily_banner", contentType = "discover_daily_banner") {
                    DailyRecommendBanner(
                        songs = dailySongs,
                        onCardClick = onOpenDailyRecommend,
                        onPlayAll = {
                            if (dailySongs.isNotEmpty()) {
                                onSongClick(dailySongs.first(), dailySongs)
                            } else {
                                Toast.makeText(context, "每日推荐正在加载中", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                // 3. 私人随心听 FM（增大卡片空间 + 人性化负反馈与模式直切）
                item(key = "discover_personal_fm", contentType = "discover_personal_fm") {
                    val safeIdx = if (fmPool.isEmpty()) 0 else personalFmIndex.coerceIn(0, fmPool.lastIndex)
                    val activeFmSong = fmPool.getOrNull(safeIdx)
                    val nextFmSong = if (fmPool.size > 1) fmPool.getOrNull((safeIdx + 1) % fmPool.size) else null
                    val canSkipPrev = safeIdx > 0
                    val isCurrentInFm = currentSong != null && activeFmSong != null && (
                        (currentSong.id.isNotBlank() && currentSong.id == activeFmSong.id) ||
                            (currentSong.hash.isNotBlank() && currentSong.hash.equals(activeFmSong.hash, ignoreCase = true))
                        )
                    val isFmPlayingNow = isCurrentInFm && isPlaying

                    PersonalFmPlayerCard(
                        activeSong = activeFmSong,
                        nextSong = nextFmSong,
                        isPlaying = isFmPlayingNow,
                        isRefreshing = personalFmRefreshing,
                        fmSongPoolId = fmSongPoolId,
                        canSkipPrev = canSkipPrev,
                        onOpenList = { showPersonalFmSheet = true },
                        onTogglePlayPause = {
                            if (isCurrentInFm) {
                                onTogglePlayPause()
                            } else if (activeFmSong != null) {
                                onSongClick(activeFmSong, fmPool)
                            } else {
                                viewModel.refreshPersonalFm { loaded ->
                                    if (loaded.isNotEmpty()) {
                                        onSongClick(loaded.first(), loaded)
                                    }
                                }
                            }
                        },
                        onSkipPrev = {
                            viewModel.skipToPreviousPersonalFm { prevSong, list ->
                                onSongClick(prevSong, list)
                            }
                        },
                        onSkipNext = {
                            viewModel.skipToNextPersonalFm { nextSong, list ->
                                onSongClick(nextSong, list)
                            }
                        },
                        onDislikeSong = {
                            if (activeFmSong != null) {
                                val shouldSwitchPlayer = isCurrentInFm
                                recentDislikedWasPlayingFm = shouldSwitchPlayer
                                recentDislikeUndoToken = viewModel.dislikeCurrentPersonalFmSong(
                                    song = activeFmSong,
                                    onNextPlay = { nextSong, list ->
                                        if (shouldSwitchPlayer) onSongClick(nextSong, list)
                                    },
                                    onFeedbackMessage = { msg -> recentDislikeFeedbackMsg = msg }
                                )
                            }
                        },
                        onDislikeArtist = {
                            if (activeFmSong != null) {
                                val shouldSwitchPlayer = isCurrentInFm
                                recentDislikedWasPlayingFm = shouldSwitchPlayer
                                recentDislikeUndoToken = viewModel.dislikeCurrentPersonalFmArtist(
                                    song = activeFmSong,
                                    onNextPlay = { nextSong, list ->
                                        if (shouldSwitchPlayer) onSongClick(nextSong, list)
                                    },
                                    onFeedbackMessage = { msg -> recentDislikeFeedbackMsg = msg }
                                )
                            }
                        },
                        onSelectPool = { targetPool ->
                            val poolName = when (targetPool) {
                                1 -> "熟悉 · 相似风格未听新曲"
                                2 -> "尝鲜 · 跨风味异质探索"
                                else -> "平衡 · 智能口味漫游"
                            }
                            viewModel.switchPersonalFmPool(targetPool) { loaded ->
                                Toast.makeText(context, "已切换至「$poolName」", Toast.LENGTH_SHORT).show()
                                if (loaded.isNotEmpty() && isFmPlayingNow) {
                                    onSongClick(loaded.first(), loaded)
                                }
                            }
                        },
                        onRefreshNewBatch = {
                            viewModel.refreshPersonalFm { loaded ->
                                if (loaded.isNotEmpty()) {
                                    onSongClick(loaded.first(), loaded)
                                    Toast.makeText(context, "已按当前偏好焕新 ${loaded.size} 首推荐", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            }

            // 4. 新歌速递（对接 module/top_song.js: /musicadservice/container/v1/newsong_publish，采用横向三行分栏流卡片设计）
            item(key = "discover_new_songs", contentType = "discover_new_songs") {
                NewSongExpressSectionCard(
                    selectedPeriod = selectedNewSongPeriod,
                    songs = currentNewSongs,
                    loading = newSongsLoading,
                    currentSong = currentSong,
                    onSelectPeriod = { periodKey ->
                        viewModel.selectNewSongPeriod(periodKey, forceRefresh = false)
                    },
                    onRefreshPeriod = {
                        viewModel.selectNewSongPeriod(selectedNewSongPeriod, forceRefresh = true)
                        Toast.makeText(context, "正在实时同步官方最新发行单曲...", Toast.LENGTH_SHORT).show()
                    },
                    onOpenFullScreen = onOpenNewSongExpress,
                    onPlayAll = {
                        if (currentNewSongs.isNotEmpty()) {
                            onSongClick(currentNewSongs.first(), currentNewSongs)
                        }
                    },
                    onPlaySong = { song ->
                        onSongClick(song, currentNewSongs)
                    },
                    onSongMoreClick = onSongMoreClick
                )
            }

            // 5. 巅峰排行榜（开放式排版：无胶囊按钮堆叠，采用底部多彩指示条与单层微渐变榜单卡）
            if (ranks.isNotEmpty()) {
                item(key = "discover_ranks", contentType = "discover_ranks") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "巅峰排行榜",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 18.sp
                                )
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .softClickable { showRankGridSheet = true }
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "全部榜单",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = "榜单宫格",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (rankCategories.size > 2) {
                            val rankTabItems = remember(rankCategories) {
                                rankCategories.map { cat ->
                                    val catAccent = when (cat) {
                                        "官方热榜" -> ConceptCoralAccent
                                        "特色曲风" -> ConceptVioletAccent
                                        "全球权威" -> ConceptCyanAccent
                                        else -> ConceptPrimary
                                    }
                                    ConceptCategoryTabItem(
                                        key = cat,
                                        label = cat,
                                        accentColor = catAccent
                                    )
                                }
                            }
                            ConceptSlidingCategoryTabs(
                                tabs = rankTabItems,
                                selectedKey = selectedRankCategory,
                                onSelectTab = { selectedRankCategory = it },
                                itemSpacing = 22.dp,
                                fontSize = 13.5.sp,
                                indicatorWidth = 18.dp,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                modifier = Modifier.padding(bottom = 15.dp)
                            )
                        }

                        AnimatedContent(
                            targetState = selectedRankCategory,
                            transitionSpec = {
                                val initialIdx = rankCategories.indexOf(initialState).coerceAtLeast(0)
                                val targetIdx = rankCategories.indexOf(targetState).coerceAtLeast(0)
                                val direction = if (targetIdx >= initialIdx) 1 else -1
                                (slideInHorizontally(animationSpec = tween(260)) { fullWidth -> direction * (fullWidth / 7) } +
                                    fadeIn(animationSpec = tween(240))) togetherWith
                                    (slideOutHorizontally(animationSpec = tween(200)) { fullWidth -> -direction * (fullWidth / 9) } +
                                        fadeOut(animationSpec = tween(160)))
                            },
                            label = "rankCategoryTransition"
                        ) { catKey ->
                            val currentRanksForCat = remember(ranks, catKey) {
                                if (catKey == "全部") ranks else ranks.filter { it.category == catKey }
                            }
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                itemsIndexed(currentRanksForCat, key = { idx, r -> "rank_card_${r.rankId}_$idx" }, contentType = { _, _ -> "rank_card" }) { _, rank ->
                                    RankCardView(
                                        rank = rank,
                                        onCardClick = { onOpenRank(rank.rankId) },
                                        onPlayRank = {
                                            val cached = viewModel.getCachedRankSongs(rank.rankId)
                                            if (cached.isNotEmpty()) {
                                                onSongClick(cached.first(), cached)
                                            } else {
                                                coroutineScope.launch {
                                                    val firstPage = viewModel.getRankSongs(rank.rankId)
                                                    if (firstPage.isNotEmpty()) {
                                                        onSongClick(firstPage.first(), firstPage)
                                                    } else {
                                                        Toast.makeText(context, "榜单歌曲加载失败", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 6. 精选场景电台（画廊式无外框封面卡片 + 极简文字链入口）
            if (fmChannels.isNotEmpty()) {
                item(key = "discover_fm_channels", contentType = "discover_fm_channels") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "精选场景电台",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 18.sp
                                )
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .softClickable { onOpenRadio("all") }
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "电台广场",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = "电台专区",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (homeAdaptive.isLargeScreenDevice) {
                            // 平板端专属优化：电台预览改为两行横向网格滚动显示，并增大封面尺寸
                            val fmColumns = remember(fmChannels) { fmChannels.chunked(2) }
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                itemsIndexed(
                                    items = fmColumns,
                                    key = { colIdx, col -> "fm_col_${col.first().id}_$colIdx" },
                                    contentType = { _, _ -> "fm_channel_col" }
                                ) { colIdx, colItems ->
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(18.dp)
                                    ) {
                                        colItems.forEachIndexed { rowIdx, channel ->
                                            val flatIdx = colIdx * 2 + rowIdx
                                            val fallbackCover = coverPool.getOrNull((flatIdx + 2) % coverPool.size.coerceAtLeast(1)).orEmpty()
                                            FmChannelCardView(
                                                channel = channel,
                                                fallbackCoverUrl = fallbackCover,
                                                index = flatIdx,
                                                onCardClick = { openFmStationQuickSheet(channel) },
                                                onPlayFmChannel = {
                                                    coroutineScope.launch {
                                                        Toast.makeText(context, "正在调频至【${channel.name}】精选 20 首曲目...", Toast.LENGTH_SHORT).show()
                                                        val songs = viewModel.getFmChannelSongs(channel.id, forceNextBatch = true)
                                                        if (songs.isNotEmpty()) {
                                                            onSongClick(songs.first(), songs)
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                itemsIndexed(fmChannels, key = { idx, it -> "fm_ch_${it.id}_$idx" }, contentType = { _, _ -> "fm_channel_card" }) { idx, channel ->
                                    val fallbackCover = coverPool.getOrNull((idx + 2) % coverPool.size.coerceAtLeast(1)).orEmpty()
                                    FmChannelCardView(
                                        channel = channel,
                                        fallbackCoverUrl = fallbackCover,
                                        index = idx,
                                        onCardClick = { openFmStationQuickSheet(channel) },
                                        onPlayFmChannel = {
                                            coroutineScope.launch {
                                                Toast.makeText(context, "正在调频至【${channel.name}】精选 20 首曲目...", Toast.LENGTH_SHORT).show()
                                                val songs = viewModel.getFmChannelSongs(channel.id, forceNextBatch = true)
                                                if (songs.isNotEmpty()) {
                                                    onSongClick(songs.first(), songs)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (pullRefreshState.progress > 0.05f || pullRefreshState.isRefreshing) {
            PullToRefreshContainer(
                state = pullRefreshState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding(),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = ConceptPrimary
            )
        }

        // 猜你喜欢专属负反馈防误触「撤销」轻提示浮条
        val undoToken = recentDislikeUndoToken
        if (undoToken != null && !isOverlayOpen) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (isDark) Color(0xFF1D293D) else Color(0xFF1B2433),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 128.dp)
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = recentDislikeFeedbackMsg.ifBlank {
                            if (undoToken.isArtistDislike) "已减少推荐歌手「${undoToken.targetArtistName}」"
                            else "已减少推荐《${undoToken.dislikedSong.title}》"
                        },
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val tokenToRestore = undoToken
                            val resumeFm = recentDislikedWasPlayingFm
                            recentDislikeUndoToken = null
                            viewModel.undoPersonalFmDislike(tokenToRestore) { restored, list ->
                                if (resumeFm) onSongClick(restored, list)
                            }
                            Toast.makeText(context, "已撤销偏好反馈并恢复推荐", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("撤销", color = ConceptCyanAccent, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
        }
    }

    if (showRankGridSheet && !isOverlayOpen) {
        RankGridPickerSheet(
            ranks = ranks,
            selectedRankId = null,
            onSelectRank = { chosenId ->
                isHomeSubSheetDismissing = true
                showRankGridSheet = false
                onOpenRank(chosenId)
            },
            onDismissStart = { isHomeSubSheetDismissing = true },
            onDismiss = { showRankGridSheet = false }
        )
    }

    if (showPersonalFmSheet && !isOverlayOpen) {
        val pfmAdaptive = com.concept.music.ui.theme.LocalConceptAdaptiveInfo.current
        val useCenteredFmPanel = pfmAdaptive.isLargeScreenDevice && pfmAdaptive.isLandscapeWindow
        val fmPalette = rememberPersonalFmModePalette(fmSongPoolId, isDark)
        val mintAccent = fmPalette.accent
        val sheetBgColor = fmPalette.sheetBg
        val headerBannerBrush = Brush.linearGradient(listOf(fmPalette.gradStart, fmPalette.gradMid, fmPalette.sheetBg))
        var pfmSheetSpin by remember { mutableIntStateOf(0) }
        val pfmShape = if (useCenteredFmPanel) RoundedCornerShape(26.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

        // 采用应用主窗口内 ConceptAnimatedOverlaySheet (zIndex = 80f)，彻底解决 ModalBottomSheet 独立系统 DialogWindow 遮挡上层 SongActionBottomSheet (zIndex = 88f) 的层级问题
        ConceptAnimatedOverlaySheet(
            onDismiss = { showPersonalFmSheet = false },
            onDismissStart = { isHomeSubSheetDismissing = true },
            isLandscape = useCenteredFmPanel,
            zIndex = 80f
        ) {
            Surface(
                shape = pfmShape,
                color = sheetBgColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 24.dp,
                modifier = (if (useCenteredFmPanel) {
                    Modifier
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .wrapContentHeight()
                } else {
                    Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .wrapContentHeight()
                }).clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .navigationBarsPadding()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sheetVerticalDragGesture()
                        .padding(top = 8.dp, bottom = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
                    ) {}
                }
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Transparent,
                    shadowElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(headerBannerBrush)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val coverUrl = fmPool.getOrNull(personalFmIndex)?.coverUrl ?: fmPool.firstOrNull()?.coverUrl.orEmpty()
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Brush.linearGradient(listOf(ConceptMintAccent, ConceptPrimaryDark))),
                            contentAlignment = Alignment.Center
                        ) {
                            if (coverUrl.isNotBlank()) {
                                AsyncImage(
                                    model = coverUrl,
                                    contentDescription = "猜你喜欢·随心听",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "猜你喜欢",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 16.5.sp
                                    ),
                                    maxLines = 1
                                )
                                Text(
                                    text = " · ${fmPalette.modeLabel}模式",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = mintAccent,
                                        fontSize = 11.5.sp
                                    ),
                                    modifier = Modifier.padding(bottom = 1.5.dp),
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = fmPalette.hintText,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.5.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        FilledTonalIconButton(
                            onClick = {
                                pfmSheetSpin += 1
                                viewModel.refreshPersonalFm { loaded ->
                                    Toast.makeText(context, "已焕新 ${loaded.size} 首随心听曲目", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !personalFmRefreshing,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = mintAccent.copy(alpha = 0.16f),
                                contentColor = mintAccent
                            ),
                            modifier = Modifier.size(36.dp)
                        ) {
                            ConceptFluidRefreshIcon(
                                tint = mintAccent,
                                isRefreshing = personalFmRefreshing,
                                spinTrigger = pfmSheetSpin,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = {
                                if (fmPool.isNotEmpty()) {
                                    onSongClick(fmPool.first(), fmPool)
                                    requestDismiss()
                                }
                            },
                            enabled = fmPool.isNotEmpty(),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = mintAccent,
                                contentColor = if (isDark) Color(0xFF06201B) else Color.White
                            )
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("全部播放", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (personalFmRefreshing) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        color = mintAccent,
                        trackColor = mintAccent.copy(alpha = 0.16f)
                    )
                }

                if (fmPool.isEmpty() && !personalFmRefreshing) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("暂无曲目数据，请点击右上角刷新", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    val pfmListState = rememberLazyListState()
                    val displayedPfmSongs by rememberProgressiveSongList(
                        sourceSongs = fmPool,
                        listState = pfmListState,
                        resetKey = "pfm_sheet",
                        initialBatchSize = 12,
                        microBatchSize = 8
                    )
                    val latestFmPool by rememberUpdatedState(fmPool)
                    val curId = currentSong?.id
                    val curHash = currentSong?.hash.orEmpty()
                    LazyColumn(
                        state = pfmListState,
                        modifier = Modifier.heightIn(max = 460.dp)
                    ) {
                        itemsIndexed(
                            items = displayedPfmSongs,
                            key = { idx, s -> "pfm_${s.id}_${s.hash}_$idx" },
                            contentType = { _, _ -> "song_row" }
                        ) { index, song ->
                            SongItemRow(
                                song = song,
                                orderIndex = index + 1,
                                isPlaying = curId != null && (curId == song.id || (curHash.isNotEmpty() && curHash.equals(song.hash, ignoreCase = true))),
                                onClick = {
                                    viewModel.setPersonalFmIndex(index)
                                    onSongClick(song, latestFmPool)
                                    requestDismiss()
                                },
                                onMoreClick = { onSongMoreClick(song) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
            }
        }
    }

    if (quickFmStation != null && !isOverlayOpen) {
        val station = quickFmStation!!
        val qfmAdaptive = com.concept.music.ui.theme.LocalConceptAdaptiveInfo.current
        val useCenteredQfmPanel = qfmAdaptive.isLargeScreenDevice && qfmAdaptive.isLandscapeWindow
        val qfmShape = if (useCenteredQfmPanel) RoundedCornerShape(26.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ConceptAnimatedOverlaySheet(
            onDismiss = { quickFmStation = null },
            onDismissStart = { isHomeSubSheetDismissing = true },
            isLandscape = useCenteredQfmPanel,
            zIndex = 80f
        ) {
            Surface(
                shape = qfmShape,
                color = conceptSheetContainerColor(),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 24.dp,
                modifier = (if (useCenteredQfmPanel) {
                    Modifier
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .wrapContentHeight()
                } else {
                    Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .wrapContentHeight()
                }).clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .navigationBarsPadding()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sheetVerticalDragGesture()
                        .padding(top = 6.dp, bottom = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
                    ) {}
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val coverUrl = station.coverUrl.ifBlank { quickFmSongs.firstOrNull()?.coverUrl.orEmpty() }
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Brush.linearGradient(listOf(ConceptPrimaryDark, Color(0xFF162844)))),
                        contentAlignment = Alignment.Center
                    ) {
                        if (coverUrl.isNotBlank()) {
                            AsyncImage(
                                model = coverUrl,
                                contentDescription = station.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Radio,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = ConceptPrimary.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = "FM · ${station.category.ifBlank { "精选电台" }}",
                                    color = ConceptPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }

                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = station.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = station.description,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(
                        onClick = {
                            val targetId = station.id
                            isHomeSubSheetDismissing = true
                            quickFmStation = null
                            onOpenRadio(targetId)
                        }
                    ) {
                        Text("进入调频台 >", color = ConceptPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (quickFmSongs.isNotEmpty()) {
                                onSongClick(quickFmSongs.first(), quickFmSongs)
                                requestDismiss()
                            }
                        },
                        enabled = quickFmSongs.isNotEmpty() && !quickFmLoading,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ConceptPrimary, contentColor = Color.White),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("播放全部", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }

                    var qfmRefreshSpin by remember { mutableIntStateOf(0) }
                    FilledTonalButton(
                        onClick = {
                            qfmRefreshSpin += 1
                            quickFmLoading = true
                            coroutineScope.launch {
                                val nextBatch = viewModel.getFmChannelSongs(station.id, forceNextBatch = true)
                                if (nextBatch.isNotEmpty()) {
                                    quickFmSongs = nextBatch
                                    Toast.makeText(context, "已为【${station.name}】换一批 ${nextBatch.size} 首新歌", Toast.LENGTH_SHORT).show()
                                }
                                quickFmLoading = false
                            }
                        },
                        enabled = !quickFmLoading,
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        ConceptFluidRefreshIcon(
                            tint = ConceptPrimary,
                            isRefreshing = quickFmLoading,
                            spinTrigger = qfmRefreshSpin,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("换一批", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    FilledTonalButton(
                        onClick = {
                            if (!quickFmLoadingMore) {
                                quickFmLoadingMore = true
                                coroutineScope.launch {
                                    val more = viewModel.getFmChannelSongs(station.id, forceNextBatch = true)
                                    val merged = (quickFmSongs + more).distinctBy { it.hash.ifBlank { it.id } }
                                    val addedCount = (merged.size - quickFmSongs.size).coerceAtLeast(0)
                                    quickFmSongs = merged
                                    quickFmLoadingMore = false
                                    Toast.makeText(context, "已继续加载 $addedCount 首符合「${station.name}」的歌曲", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !quickFmLoadingMore && !quickFmLoading,
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(if (quickFmLoadingMore) "加载中" else "+20首", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (quickFmLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        color = ConceptPrimary
                    )
                } else {
                    val qfmListState = rememberLazyListState()
                    val displayedQfmSongs by rememberProgressiveSongList(
                        sourceSongs = quickFmSongs,
                        listState = qfmListState,
                        resetKey = station.id,
                        initialBatchSize = 12,
                        microBatchSize = 8
                    )
                    val latestQuickFmSongs by rememberUpdatedState(quickFmSongs)
                    val curId = currentSong?.id
                    val curHash = currentSong?.hash.orEmpty()
                    LazyColumn(
                        state = qfmListState,
                        modifier = Modifier.heightIn(max = 420.dp)
                    ) {
                        itemsIndexed(
                            items = displayedQfmSongs,
                            key = { idx, s -> "qfm_${s.id}_${s.hash}_$idx" },
                            contentType = { _, _ -> "song_row" }
                        ) { index, song ->
                            SongItemRow(
                                song = song,
                                orderIndex = index + 1,
                                isPlaying = curId != null && (curId == song.id || (curHash.isNotEmpty() && curHash.equals(song.hash, ignoreCase = true))),
                                onClick = {
                                    onSongClick(song, latestQuickFmSongs)
                                    requestDismiss()
                                },
                                onMoreClick = { onSongMoreClick(song) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
            }
            }
        }
    }
}

/**
 * 发现页顶部标题栏（去除时段胶囊背景，采用多彩呼吸圆点 + 开放式副标题）
 */
@Composable
private fun DiscoverTopHeader(
    onSearchClick: () -> Unit
) {
    val timeGreeting = remember {
        val now = runCatching { LocalTime.now(DISCOVER_CHINA_ZONE) }.getOrElse { LocalTime.now() }
        val minutes = now.hour * 60 + now.minute
        when (minutes) {
            in (5 * 60) until (8 * 60) -> Triple("早晨", "晨光微熹 · 用第一缕旋律唤醒耳朵", ConceptMintAccent)
            in (8 * 60) until (11 * 60 + 30) -> Triple("上午", "清风伴读 · 轻快节拍开启元气上午", ConceptCyanAccent)
            in (11 * 60 + 30) until (14 * 60) -> Triple("午间", "午间小憩 · 卸下忙碌享片刻松弛", ConceptAmberAccent)
            in (14 * 60) until (17 * 60) -> Triple("下午", "午后漫游 · 一杯咖啡与恰到好处的律动", ConceptCoralAccent)
            in (17 * 60) until (19 * 60) -> Triple("傍晚", "暮色微醺 · 晚风与落日余晖皆入歌", ConceptCoralAccent)
            in (19 * 60) until (22 * 60 + 30) -> Triple("晚上", "夜幕微蓝 · 卸下喧嚣沉浸私人旋律", ConceptPrimary)
            else -> Triple("深夜", "深夜静谧 · 耳机里的温柔陪你入眠", ConceptVioletAccent)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "发现",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    letterSpacing = 0.5.sp
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(timeGreeting.third)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = timeGreeting.second,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 0.2.sp,
                        fontSize = 12.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Surface(
            shape = CircleShape,
            color = conceptFloatingCardColor(),
            shadowElevation = 0.dp,
            modifier = Modifier
                .height(36.dp)
                .softCardClickable(shape = CircleShape, onClick = onSearchClick)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "搜索",
                    tint = ConceptPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "搜索",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private data class PersonalFmModePalette(
    val modeLabel: String,
    val hintText: String,
    val accent: Color,
    val onAccent: Color,
    val gradStart: Color,
    val gradMid: Color,
    val gradEnd: Color,
    val sheetBg: Color
)

@Composable
private fun rememberPersonalFmModePalette(fmSongPoolId: Int, isDark: Boolean): PersonalFmModePalette {
    val targetAccent = when (fmSongPoolId) {
        1 -> if (isDark) Color(0xFFF5B041) else Color(0xFFC97A00) // 熟悉：暖琥珀流金
        2 -> if (isDark) Color(0xFF9B7BFF) else Color(0xFF6848E8) // 尝鲜：极光鸢尾紫蓝
        else -> if (isDark) Color(0xFF26DEBF) else ConceptMintAccent // 平衡：保持原始薄荷绿渐变不变
    }
    val targetGradStart = when (fmSongPoolId) {
        1 -> if (isDark) Color(0xFF2B1F12) else Color(0xFFFFF2DE)
        2 -> if (isDark) Color(0xFF1E1838) else Color(0xFFEFE9FF)
        else -> if (isDark) Color(0xFF102A2C) else Color(0xFFE0FBF4)
    }
    val targetGradMid = when (fmSongPoolId) {
        1 -> if (isDark) Color(0xFF211B24) else Color(0xFFFFF7EC)
        2 -> if (isDark) Color(0xFF161E38) else Color(0xFFEAF2FF)
        else -> if (isDark) Color(0xFF122234) else Color(0xFFECF6FF)
    }
    val targetGradEnd = when (fmSongPoolId) {
        1 -> if (isDark) Color(0xFF141826) else Color(0xFFF7F2FF)
        2 -> if (isDark) Color(0xFF141828) else Color(0xFFF4F8FF)
        else -> if (isDark) Color(0xFF141B2B) else Color(0xFFF5F4FF)
    }
    val targetSheetBg = when (fmSongPoolId) {
        1 -> if (isDark) Color(0xFF1B1612) else Color(0xFFFFFBF4)
        2 -> if (isDark) Color(0xFF151326) else Color(0xFFF7F5FF)
        else -> if (isDark) Color(0xFF0E1D22) else Color(0xFFF2FCF9)
    }
    val animSpec = tween<Color>(durationMillis = 320, easing = FastOutSlowInEasing)
    val accent by animateColorAsState(targetAccent, animSpec, label = "fmAccent")
    val gradStart by animateColorAsState(targetGradStart, animSpec, label = "fmGradStart")
    val gradMid by animateColorAsState(targetGradMid, animSpec, label = "fmGradMid")
    val gradEnd by animateColorAsState(targetGradEnd, animSpec, label = "fmGradEnd")
    val sheetBg by animateColorAsState(targetSheetBg, animSpec, label = "fmSheetBg")

    val onAccent = when {
        !isDark -> Color.White
        fmSongPoolId == 1 -> Color(0xFF261704)
        fmSongPoolId == 2 -> Color(0xFF120A2A)
        else -> Color(0xFF06201D)
    }
    val (label, hint) = when (fmSongPoolId) {
        1 -> "熟悉" to "相似风味 · 回味红心与偏爱风格"
        2 -> "尝鲜" to "跨界探索 · 邂逅全新流派惊喜"
        else -> "平衡" to "智能平衡 · 融合红心与近期听感"
    }
    return PersonalFmModePalette(
        modeLabel = label,
        hintText = hint,
        accent = accent,
        onAccent = onAccent,
        gradStart = gradStart,
        gradMid = gradMid,
        gradEnd = gradEnd,
        sheetBg = sheetBg
    )
}

/**
 * 猜你喜欢 · 私人随心听画境卡片：
 * - 点击卡片任意空白区即可呼出候选队列（已移除右下角多余队列按钮）；
 * - 顶栏精简为单个循环模式按钮（平衡[原绿渐变] → 熟悉[暖琥珀] → 尝鲜[极光紫蓝]）+ 换一批刷新，舒展通透无紧凑感。
 */
@Composable
private fun PersonalFmPlayerCard(
    activeSong: Song?,
    nextSong: Song?,
    isPlaying: Boolean,
    isRefreshing: Boolean,
    fmSongPoolId: Int = 0,
    canSkipPrev: Boolean = false,
    onOpenList: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipPrev: () -> Unit = {},
    onSkipNext: () -> Unit,
    onDislikeSong: () -> Unit = {},
    onDislikeArtist: () -> Unit = {},
    onSelectPool: (Int) -> Unit = {},
    onRefreshNewBatch: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    var fmCardSpin by remember { mutableIntStateOf(0) }
    var showInlineDislikeChoices by remember(activeSong?.id, activeSong?.hash) { mutableStateOf(false) }

    val palette = rememberPersonalFmModePalette(fmSongPoolId, isDark)
    val modeAccent = palette.accent
    val playerCardBrush = Brush.linearGradient(listOf(palette.gradStart, palette.gradMid, palette.gradEnd))
    val modeIcon = when (fmSongPoolId) {
        1 -> Icons.Rounded.Favorite
        2 -> Icons.Rounded.Explore
        else -> Icons.Rounded.AutoAwesome
    }
    val primaryArtistName = remember(activeSong?.artist) {
        activeSong?.artist?.split("、", "/", ",", "&")?.firstOrNull()?.trim().orEmpty()
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = conceptFloatingCardColor(),
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .softCardClickable(shape = RoundedCornerShape(28.dp), onClick = onOpenList)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(playerCardBrush)
                .padding(horizontal = 22.dp, vertical = 20.dp)
        ) {
            // 1. 顶栏：左侧舒展标题与说明 + 右侧单按钮循环模式切换与刷新
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "猜你喜欢",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 18.5.sp,
                                letterSpacing = 0.3.sp
                            )
                        )
                        Text(
                            text = " · 私人电台",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = modeAccent,
                                fontSize = 12.sp
                            ),
                            modifier = Modifier.padding(bottom = 1.5.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = palette.hintText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.84f),
                            fontSize = 11.5.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 单按钮循环切换推荐模式：平衡(0) -> 熟悉(1) -> 尝鲜(2) -> 平衡(0)
                    Surface(
                        shape = CircleShape,
                        color = modeAccent.copy(alpha = if (isDark) 0.18f else 0.13f),
                        modifier = Modifier.softClickable(
                            pressScale = 0.93f,
                            onClick = { onSelectPool((fmSongPoolId + 1) % 3) }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = modeIcon,
                                contentDescription = "切换推荐模式",
                                tint = modeAccent,
                                modifier = Modifier.size(14.dp)
                            )
                            AnimatedContent(
                                targetState = palette.modeLabel,
                                transitionSpec = {
                                    fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                                },
                                label = "fmModeBtnLabel"
                            ) { labelText ->
                                Text(
                                    text = labelText,
                                    color = modeAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.Sync,
                                contentDescription = null,
                                tint = modeAccent.copy(alpha = 0.78f),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(modeAccent.copy(alpha = if (isDark) 0.14f else 0.11f))
                            .softClickable(
                                enabled = !isRefreshing,
                                pressScale = 0.88f,
                                onClick = {
                                    fmCardSpin += 1
                                    onRefreshNewBatch()
                                }
                            )
                    ) {
                        ConceptFluidRefreshIcon(
                            tint = modeAccent,
                            isRefreshing = isRefreshing,
                            spinTrigger = fmCardSpin,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. 中部主舞台：大尺寸唱片封面 + 舒展曲目主信息与下一首预告
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .shadow(10.dp, RoundedCornerShape(22.dp))
                        .clip(RoundedCornerShape(22.dp))
                        .background(Brush.linearGradient(listOf(modeAccent, ConceptPrimaryDark))),
                    contentAlignment = Alignment.Center
                ) {
                    Crossfade(
                        targetState = activeSong?.coverUrl.orEmpty(),
                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        label = "personalFmCoverCrossfade"
                    ) { coverUrl ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (coverUrl.isNotBlank()) {
                                AsyncImage(
                                    model = coverUrl,
                                    contentDescription = activeSong?.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Radio,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(18.dp))

                Column(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = activeSong?.title ?: if (isRefreshing) "正在为你调频私人曲库..." else "轻触开启私人随心漫游",
                        transitionSpec = {
                            fadeIn(tween(220, easing = FastOutSlowInEasing))
                                .togetherWith(fadeOut(tween(150, easing = FastOutSlowInEasing)))
                        },
                        label = "personalFmTitleTransition"
                    ) { displayTitle ->
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 18.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(7.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (activeSong?.isHiRes == true) {
                            BadgeTag(text = "Hi-Res", color = VipGold)
                            Spacer(modifier = Modifier.width(6.dp))
                        } else if (activeSong?.isLossless == true) {
                            BadgeTag(text = "SQ", color = ConceptPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        if (activeSong?.isVip == true) {
                            BadgeTag(text = "VIP", color = VipGold)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = activeSong?.let {
                                val alb = it.album.takeIf { a -> a.isNotBlank() && a != "单曲" && a != "未知专辑" }
                                if (alb != null) "${it.artist} · $alb" else it.artist
                            } ?: "根据你的听歌口味实时调频",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = modeAccent.copy(alpha = if (isDark) 0.14f else 0.10f)
                    ) {
                        Text(
                            text = if (nextSong != null) {
                                "下一首 · ${nextSong.title}"
                            } else {
                                "轻触卡片查看候选队列"
                            },
                            color = modeAccent,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 3. 底部简洁播控区：左侧「不喜欢」反馈 + 右侧舒展「上一首 / 播放暂停 / 下一首」（无冗余队列按钮）
            AnimatedContent(
                targetState = showInlineDislikeChoices && activeSong != null,
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                },
                label = "fmDislikeBarSwitch"
            ) { expandedDislike ->
                if (expandedDislike && activeSong != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = ConceptCoralAccent.copy(alpha = 0.14f),
                            modifier = Modifier
                                .weight(1f)
                                .softCardClickable(
                                    shape = RoundedCornerShape(16.dp),
                                    onClick = {
                                        showInlineDislikeChoices = false
                                        onDislikeSong()
                                    }
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.HeartBroken,
                                    contentDescription = null,
                                    tint = ConceptCoralAccent,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "不听这首歌",
                                    color = ConceptCoralAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1
                                )
                            }
                        }

                        if (primaryArtistName.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.12f else 0.07f),
                                modifier = Modifier
                                    .weight(1.15f)
                                    .softCardClickable(
                                        shape = RoundedCornerShape(16.dp),
                                        onClick = {
                                            showInlineDislikeChoices = false
                                            onDislikeArtist()
                                        }
                                    )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PersonOff,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "少推：$primaryArtistName",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                .softClickable { showInlineDislikeChoices = false }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "收起",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.10f else 0.06f),
                            modifier = Modifier.softCardClickable(
                                shape = CircleShape,
                                enabled = activeSong != null,
                                onClick = { showInlineDislikeChoices = true }
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.HeartBroken,
                                    contentDescription = "不喜欢当前推荐",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "不喜欢",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // 右侧：上一首 / 主播放暂停圆钮 / 下一首（舒展排布，点击卡片即弹队列）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            IconButton(
                                onClick = onSkipPrev,
                                enabled = canSkipPrev,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipPrevious,
                                    contentDescription = "上一首",
                                    tint = if (canSkipPrev) modeAccent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            FilledIconButton(
                                onClick = onTogglePlayPause,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = modeAccent,
                                    contentColor = palette.onAccent
                                ),
                                modifier = Modifier
                                    .size(48.dp)
                                    .shadow(8.dp, CircleShape)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isPlaying) "暂停随心听" else "播放随心听",
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            IconButton(
                                onClick = onSkipNext,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipNext,
                                    contentDescription = "下一首",
                                    tint = modeAccent,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 新歌速递发现页模块（彻底去盒子化 · 开放式杂志排版）：
 * - 消除此前「日期胶囊 + 刷新圆按钮 + 86首胶囊 + 分类分段框 + 全播胶囊 + 三行歌曲大黑框」共 6 处圆角盒子的密集感
 * - 标题栏采用「新歌速递  / 10.12 - 10.18」纯排版层次 + 右侧无框文字链「全部 86 首 >」
 * - 语种栏采用开放式文字 Tab + 底部 3dp 鸢尾紫指示条
 * - 三行横向新歌流直接悬浮于画布，不包裹外层深色卡片框，视野开阔通透
 */
@Composable
private fun NewSongExpressSectionCard(
    selectedPeriod: String,
    songs: List<Song>,
    loading: Boolean,
    currentSong: Song?,
    onSelectPeriod: (String) -> Unit,
    onRefreshPeriod: () -> Unit,
    onOpenFullScreen: () -> Unit,
    onPlayAll: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onSongMoreClick: (Song) -> Unit = {}
) {
    val isDark = LocalIsDarkTheme.current
    val violetAccent = if (isDark) Color(0xFF9688FF) else ConceptVioletAccent
    val windowRangeStr = remember {
        val end = runCatching { LocalDate.now(DISCOVER_CHINA_ZONE) }.getOrElse { LocalDate.now() }
        val start = end.minusDays(6)
        String.format("%02d.%02d - %02d.%02d", start.monthValue, start.dayOfMonth, end.monthValue, end.dayOfMonth)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 1. 开放式标题栏（无任何胶囊框包裹）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "新歌速递",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "/ $windowRangeStr",
                    color = violetAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 1.5.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                var nsSectionSpin by remember { mutableIntStateOf(0) }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .softClickable(
                            enabled = !loading,
                            onClick = {
                                nsSectionSpin += 1
                                onRefreshPeriod()
                            }
                        )
                ) {
                    ConceptFluidRefreshIcon(
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        isRefreshing = loading,
                        spinTrigger = nsSectionSpin,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .softClickable(onClick = onOpenFullScreen)
                        .padding(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "查看全部",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // 2. 开放式极简语种栏（单色左右滑动横条动画）+ 右侧无框全播文字动作
        val periodTabItems = remember(violetAccent) {
            NEW_SONG_PERIOD_TABS.map { tab ->
                ConceptCategoryTabItem(
                    key = tab.key,
                    label = tab.label,
                    accentColor = violetAccent
                )
            }
        }
        ConceptSlidingCategoryTabs(
            tabs = periodTabItems,
            selectedKey = selectedPeriod,
            onSelectTab = onSelectPeriod,
            itemSpacing = 22.dp,
            fontSize = 13.5.sp,
            indicatorWidth = 18.dp,
            trailingContent = {
                if (songs.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .softClickable(onClick = onPlayAll)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = violetAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "全播",
                            color = violetAccent,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        )

        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            modifier = Modifier.padding(bottom = 14.dp)
        )

        // 3. 无外框通透横向三行新歌流（加入平滑分类切换过渡动画）
        AnimatedContent(
            targetState = selectedPeriod,
            transitionSpec = {
                val initialIdx = NEW_SONG_PERIOD_TABS.indexOfFirst { it.key == initialState }.coerceAtLeast(0)
                val targetIdx = NEW_SONG_PERIOD_TABS.indexOfFirst { it.key == targetState }.coerceAtLeast(0)
                val direction = if (targetIdx >= initialIdx) 1 else -1
                conceptCategoryContentTransition(direction)
            },
            label = "newSongPeriodTransition"
        ) { _ ->
            if (loading && songs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = violetAccent, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                }
            } else if (songs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("正在同步近期发行新歌...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            } else {
                val columns = remember(songs) { songs.take(18).chunked(3) }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                itemsIndexed(columns, key = { colIdx, group -> "ns_col_${colIdx}_${group.firstOrNull()?.id}" }, contentType = { _, _ -> "new_song_column" }) { _, group ->
                    Column(
                        modifier = Modifier.width(292.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        group.forEachIndexed { rowIdx, song ->
                            val isThisPlaying = currentSong != null && (
                                currentSong.id == song.id ||
                                    (currentSong.hash.isNotBlank() && currentSong.hash.equals(song.hash, ignoreCase = true))
                                )
                            val shortDate = song.publishDate.takeIf { it.length >= 10 }?.substring(5) ?: song.publishDate

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isThisPlaying) violetAccent.copy(alpha = 0.10f)
                                        else Color.Transparent
                                    )
                                    .softClickable { onPlaySong(song) }
                                    .padding(vertical = 7.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(13.dp))
                                        .background(Brush.linearGradient(listOf(ConceptVioletAccent, ConceptPrimaryDark))),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val nsThumbModel = rememberOptimizedAsyncImageModel(rawUrl = song.coverUrl, sizePx = 144, targetCdnSize = "150")
                                    if (nsThumbModel != null) {
                                        AsyncImage(
                                            model = nsThumbModel,
                                            contentDescription = song.title,
                                            contentScale = ContentScale.Crop,
                                            filterQuality = FilterQuality.Low,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    if (isThisPlaying) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.36f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.GraphicEq,
                                                contentDescription = "正在播放",
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    } else if (shortDate.isNotBlank()) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .clip(RoundedCornerShape(topEnd = 7.dp))
                                                .background(Color.Black.copy(alpha = 0.62f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = shortDate,
                                                color = Color.White,
                                                fontSize = 8.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = if (isThisPlaying) violetAccent else MaterialTheme.colorScheme.onSurface,
                                            fontSize = 14.5.sp
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (song.isHiRes) {
                                            BadgeTag(text = "Hi-Res", color = VipGold)
                                            Spacer(modifier = Modifier.width(4.dp))
                                        } else if (song.isLossless) {
                                            BadgeTag(text = "SQ", color = ConceptPrimary)
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        if (song.isVip) {
                                            BadgeTag(text = "VIP", color = VipGold)
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = song.artist,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 12.sp
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                // 右侧改为独立可交互的「更多操作」按钮（点击行即播放，点击右侧按钮弹出歌曲操作面板）
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .softClickable { onSongMoreClick(song) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreVert,
                                        contentDescription = "更多操作",
                                        tint = if (isThisPlaying) violetAccent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            if (rowIdx < group.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 66.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
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

/**
 * 精选场景电台画廊式无外框卡片（去除外层圆角卡片盒，封面直接悬浮于通透画布，文字自然排布于封面下方）
 */
@Composable
private fun FmChannelCardView(
    channel: FmChannel,
    fallbackCoverUrl: String,
    index: Int,
    onCardClick: () -> Unit,
    onPlayFmChannel: () -> Unit
) {
    val effectiveCover = channel.coverUrl.takeIf { it.isNotBlank() && !it.contains("20210519") } ?: fallbackCoverUrl
    val freqText = remember(channel.id, index) {
        val base = 88.6 + ((channel.id.hashCode().and(0x7fffffff) % 180) * 0.1)
        String.format("FM %.1f", base)
    }
    val categoryAccent = remember(channel.category, index) {
        when {
            channel.category.contains("年代") || channel.category.contains("语种") -> ConceptCoralAccent
            channel.category.contains("曲风") || channel.category.contains("流派") -> ConceptVioletAccent
            channel.category.contains("心情") || channel.category.contains("场景") -> ConceptMintAccent
            else -> ConceptCyanAccent
        }
    }
    val fallbackBrush = remember(index) {
        when (index % 4) {
            0 -> Brush.linearGradient(listOf(Color(0xFF163A5F), Color(0xFF0E2138)))
            1 -> Brush.linearGradient(listOf(Color(0xFF1A3E4C), Color(0xFF0E242E)))
            2 -> Brush.linearGradient(listOf(Color(0xFF282B54), Color(0xFF141730)))
            else -> Brush.linearGradient(listOf(Color(0xFF33264D), Color(0xFF181228)))
        }
    }

    val fmCardAdaptive = com.concept.music.ui.theme.LocalConceptAdaptiveInfo.current
    val coverSize = if (fmCardAdaptive.isLargeScreenDevice) 168.dp else 148.dp

    Column(
        modifier = Modifier
            .width(coverSize)
            .softCardClickable(clip = false, onClick = onCardClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(coverSize)
                .clip(RoundedCornerShape(if (fmCardAdaptive.isLargeScreenDevice) 24.dp else 22.dp))
                .background(fallbackBrush)
        ) {
            val fmCoverModel = rememberOptimizedAsyncImageModel(rawUrl = effectiveCover, sizePx = 240, targetCdnSize = "240")
            if (fmCoverModel != null) {
                AsyncImage(
                    model = fmCoverModel,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Radio,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier
                        .size(46.dp)
                        .align(Alignment.Center)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.28f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.52f)
                            )
                        )
                    )
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(categoryAccent)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = freqText,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            FilledIconButton(
                onClick = onPlayFmChannel,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = categoryAccent),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .size(34.dp)
                    .shadow(4.dp, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "播放电台",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = channel.name,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = channel.description.ifBlank { "随心漫游精选旋律" },
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

/**
 * 每日推荐独立详情页（开放式无框大视野头部，严格刷新边界）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyRecommendScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    currentSong: Song? = null,
    onSongMoreClick: (Song) -> Unit = {}
) {
    val todayDailySongs by viewModel.dailyRecommendSongs.collectAsState()
    val dailyHistoryEntries by viewModel.dailyHistoryEntries.collectAsState()
    val selectedDailyHistory by viewModel.selectedDailyHistory.collectAsState()
    val dailyHistorySongs by viewModel.dailyHistorySongs.collectAsState()
    val dailyHistoryLoading by viewModel.dailyHistoryLoading.collectAsState()
    val dailyRefreshing by viewModel.dailyRefreshing.collectAsState()
    val loading by viewModel.loading.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadDailyHistoryList()
    }
    DisposableEffect(Unit) {
        onDispose {
            if (viewModel.selectedDailyHistory.value != null) {
                viewModel.selectDailyHistory(null)
            }
        }
    }

    val dailySongs = if (selectedDailyHistory != null) dailyHistorySongs else todayDailySongs
    val isBusy = if (selectedDailyHistory != null) dailyHistoryLoading else (dailyRefreshing || loading)
    val today = remember { runCatching { LocalDate.now(DISCOVER_CHINA_ZONE) }.getOrElse { LocalDate.now() } }
    val activeDateParts = remember(selectedDailyHistory, today) {
        val histDate = selectedDailyHistory?.dateLabel.orEmpty()
        val parts = histDate.split("-", "/", ".")
        if (parts.size >= 3) {
            parts[1].padStart(2, '0') to parts[2].padStart(2, '0')
        } else {
            String.format("%02d", today.monthValue) to String.format("%02d", today.dayOfMonth)
        }
    }
    val monthStr = activeDateParts.first
    val dayStr = activeDateParts.second
    var showHistoryMenu by remember { mutableStateOf(false) }

    ConceptStageBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (selectedDailyHistory != null) "历史日推" else "每日推荐",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    actions = {
                        if (dailyHistoryEntries.isNotEmpty()) {
                            Box {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = ConceptCoralAccent.copy(alpha = 0.14f),
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        .softCardClickable(shape = RoundedCornerShape(16.dp)) {
                                            showHistoryMenu = true
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.History,
                                            contentDescription = "历史每日推荐",
                                            tint = ConceptCoralAccent,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = selectedDailyHistory?.displayTitle ?: "今日",
                                            color = ConceptCoralAccent,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Icon(
                                            imageVector = Icons.Rounded.ArrowDropDown,
                                            contentDescription = null,
                                            tint = ConceptCoralAccent,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = showHistoryMenu,
                                    onDismissRequest = { showHistoryMenu = false },
                                    modifier = Modifier.background(conceptSheetContainerColor(), RoundedCornerShape(16.dp))
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "今日推荐",
                                                fontWeight = if (selectedDailyHistory == null) FontWeight.ExtraBold else FontWeight.Normal,
                                                color = if (selectedDailyHistory == null) ConceptCoralAccent else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            showHistoryMenu = false
                                            viewModel.selectDailyHistory(null)
                                        }
                                    )
                                    dailyHistoryEntries.forEach { entry ->
                                        val isChosen = selectedDailyHistory?.historyName == entry.historyName
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = entry.displayTitle,
                                                    fontWeight = if (isChosen) FontWeight.ExtraBold else FontWeight.Normal,
                                                    color = if (isChosen) ConceptCoralAccent else MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            onClick = {
                                                showHistoryMenu = false
                                                viewModel.selectDailyHistory(entry)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        var dailyRefreshSpin by remember { mutableIntStateOf(0) }
                        IconButton(
                            onClick = {
                                dailyRefreshSpin += 1
                                val curHist = selectedDailyHistory
                                if (curHist != null) {
                                    viewModel.selectDailyHistory(curHist)
                                } else {
                                    viewModel.refreshDailyRecommend()
                                }
                            },
                            enabled = !isBusy
                        ) {
                            ConceptFluidRefreshIcon(
                                tint = ConceptCoralAccent,
                                isRefreshing = isBusy,
                                spinTrigger = dailyRefreshSpin,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            val dailyListState = rememberLazyListState()
            val dailyScope = rememberCoroutineScope()
            val displayedDailySongs by rememberProgressiveSongList(
                sourceSongs = dailySongs,
                listState = dailyListState,
                resetKey = selectedDailyHistory?.historyName ?: "daily_recommend",
                initialBatchSize = 40,
                microBatchSize = 15
            )
            val latestDailySongs by rememberUpdatedState(dailySongs)
            val curDailyPlayingId = currentSong?.id
            val curDailyPlayingHash = currentSong?.hash.orEmpty()
            val playingDailySongIndex = remember(dailySongs, curDailyPlayingId, curDailyPlayingHash) {
                if (curDailyPlayingId == null && curDailyPlayingHash.isEmpty()) -1
                else dailySongs.indexOfFirst { s ->
                    (curDailyPlayingId != null && s.id == curDailyPlayingId) ||
                        (curDailyPlayingHash.isNotEmpty() && s.hash.equals(curDailyPlayingHash, ignoreCase = true))
                }
            }
            val showDailyScrollTop by remember(dailySongs.size) {
                derivedStateOf {
                    dailyListState.firstVisibleItemIndex > 2 ||
                        (dailyListState.firstVisibleItemIndex == 2 && dailyListState.firstVisibleItemScrollOffset > 100)
                }
            }
            val dailyAdaptive = com.concept.music.ui.theme.LocalConceptAdaptiveInfo.current
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.TopCenter
            ) {
            if (dailyAdaptive.useTwoPaneDetailLayout) {
                // 平板横屏双栏布局：左栏固定日推三叠封面与日期信息卡 + 右栏独立滚动双列推荐歌曲
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = conceptFloatingCardColor(),
                        modifier = Modifier
                            .width(300.dp)
                            .fillMaxHeight()
                            .padding(bottom = 96.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            StaggeredThreeCovers(
                                songs = dailySongs.take(3),
                                primarySize = 118.dp,
                                secondarySize = 100.dp,
                                tertiarySize = 86.dp
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = dayStr,
                                    color = ConceptCoralAccent,
                                    fontSize = 42.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = " / $monthStr",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = selectedDailyHistory?.let { "历史推荐回顾 · ${it.displayTitle}" }
                                    ?: "循着你的听歌轨迹 · 每日 0:00 更新",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = {
                                    if (dailySongs.isNotEmpty()) onSongClick(dailySongs.first(), dailySongs)
                                },
                                enabled = dailySongs.isNotEmpty(),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ConceptCoralAccent, contentColor = Color.White),
                                modifier = Modifier.fillMaxWidth().height(46.dp)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(19.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("播放全部", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                            }
                        }
                    }

                    LazyColumn(
                        state = dailyListState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(bottom = 100.dp)
                    ) {
                        if (isBusy && dailySongs.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 72.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = ConceptCoralAccent, strokeWidth = 2.5.dp)
                                }
                            }
                        } else if (dailySongs.isEmpty() && !isBusy) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
                                    Text("暂未获取到推荐歌曲，请点击右上角刷新", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            val dailyPairs = displayedDailySongs.mapIndexed { idx, s -> idx to s }.chunked(2)
                            itemsIndexed(
                                items = dailyPairs,
                                key = { pIdx, pair -> "daily_tp_pair_${pair.first().second.id}_$pIdx" },
                                contentType = { _, _ -> "song_pair_row" }
                            ) { _, pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    pair.forEach { (index, song) ->
                                        Box(modifier = Modifier.weight(1f)) {
                                            SongItemRow(
                                                song = song,
                                                orderIndex = index + 1,
                                                isPlaying = curDailyPlayingId != null && (curDailyPlayingId == song.id || (curDailyPlayingHash.isNotEmpty() && curDailyPlayingHash.equals(song.hash, ignoreCase = true))),
                                                onClick = { onSongClick(song, latestDailySongs) },
                                                onMoreClick = { onSongMoreClick(song) }
                                            )
                                        }
                                    }
                                    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            } else {
            LazyColumn(
                state = dailyListState,
                modifier = Modifier
                    .widthIn(max = 960.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                // 开放式日推头部（去除外层圆角卡片盒）
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StaggeredThreeCovers(
                            songs = dailySongs.take(3),
                            primarySize = 86.dp,
                            secondarySize = 74.dp,
                            tertiarySize = 64.dp
                        )

                        Spacer(modifier = Modifier.width(18.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = dayStr,
                                    color = ConceptCoralAccent,
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = " / $monthStr",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 5.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = selectedDailyHistory?.let { "历史推荐回顾 · ${it.displayTitle}" }
                                    ?: "循着你的听歌轨迹 · 每日 0:00 更新",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .softClickable(enabled = dailySongs.isNotEmpty()) {
                                        if (dailySongs.isNotEmpty()) onSongClick(dailySongs.first(), dailySongs)
                                    }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(ConceptCoralAccent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "播放全部",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (isBusy) {
                                    Spacer(modifier = Modifier.width(10.dp))
                                    CircularProgressIndicator(
                                        color = ConceptCoralAccent,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                    )
                }

                if (dailySongs.isEmpty() && !isBusy) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 64.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂未获取到推荐歌曲，请点击右上角刷新", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (dailyAdaptive.useTwoColumnSongOrCardGrid) {
                    val dailyPairs = displayedDailySongs.mapIndexed { idx, s -> idx to s }.chunked(2)
                    itemsIndexed(
                        items = dailyPairs,
                        key = { pIdx, pair -> "daily_pair_${pair.first().second.id}_$pIdx" },
                        contentType = { _, _ -> "song_pair_row" }
                    ) { _, pair ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            pair.forEach { (index, song) ->
                                Box(modifier = Modifier.weight(1f)) {
                                    SongItemRow(
                                        song = song,
                                        orderIndex = index + 1,
                                        isPlaying = curDailyPlayingId != null && (curDailyPlayingId == song.id || (curDailyPlayingHash.isNotEmpty() && curDailyPlayingHash.equals(song.hash, ignoreCase = true))),
                                        onClick = { onSongClick(song, latestDailySongs) },
                                        onMoreClick = { onSongMoreClick(song) }
                                    )
                                }
                            }
                            if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                } else {
                    itemsIndexed(
                        items = displayedDailySongs,
                        key = { index, song -> "daily_page_${song.id}_$index" },
                        contentType = { _, _ -> "song_row" }
                    ) { index, song ->
                        Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                            SongItemRow(
                                song = song,
                                orderIndex = index + 1,
                                isPlaying = curDailyPlayingId != null && (curDailyPlayingId == song.id || (curDailyPlayingHash.isNotEmpty() && curDailyPlayingHash.equals(song.hash, ignoreCase = true))),
                                onClick = { onSongClick(song, latestDailySongs) },
                                onMoreClick = { onSongMoreClick(song) }
                            )
                        }
                    }
                }
            }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 18.dp, bottom = 104.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FloatingScrollToTopButton(
                    visible = showDailyScrollTop,
                    accentColor = ConceptCoralAccent,
                    onClick = {
                        dailyScope.launch {
                            dailyListState.smoothFastScrollToItem(0)
                        }
                    }
                )

                AnimatedVisibility(
                    visible = playingDailySongIndex >= 0,
                    enter = fadeIn() + scaleIn(initialScale = 0.85f),
                    exit = fadeOut() + scaleOut(targetScale = 0.85f)
                ) {
                    FilledIconButton(
                        onClick = {
                            if (playingDailySongIndex >= 0) {
                                dailyScope.launch {
                                    val targetLazyIndex = when {
                                        dailyAdaptive.useTwoPaneDetailLayout ->
                                            (1 + playingDailySongIndex / 2 - 1).coerceAtLeast(0)
                                        dailyAdaptive.useTwoColumnSongOrCardGrid ->
                                            (2 + playingDailySongIndex / 2 - 1).coerceAtLeast(0)
                                        else ->
                                            (2 + playingDailySongIndex - 2).coerceAtLeast(0)
                                    }
                                    dailyListState.smoothFastScrollToItem(targetLazyIndex)
                                }
                            }
                        },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = ConceptCoralAccent,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .size(42.dp)
                            .shadow(8.dp, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MyLocation,
                            contentDescription = "定位当前播放歌曲",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
            }
        }
    }
}

/**
 * 每日推荐主页横幅卡片（极简概念版设计 + 暖珊瑚日落渐变点缀）
 */
@Composable
private fun DailyRecommendBanner(
    songs: List<Song>,
    onCardClick: () -> Unit,
    onPlayAll: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val today = remember { runCatching { LocalDate.now(DISCOVER_CHINA_ZONE) }.getOrElse { LocalDate.now() } }
    val dateText = remember(today) { String.format("%02d/%02d", today.monthValue, today.dayOfMonth) }

    val bgBrush = if (isDark) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF281A28),
                Color(0xFF1B1C30),
                Color(0xFF141C2E)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFFFEAE4),
                Color(0xFFFFF4F0),
                Color(0xFFF3F8FF)
            )
        )
    }

    val titleColor = if (isDark) Color.White else Color(0xFF141C2B)
    val subColor = if (isDark) Color.White.copy(alpha = 0.72f) else Color(0xFF52637A)

    // 与「我的页」头像名片卡一致的 3D 边缘光晕层次效果（无边框线，通过 clip = false 完整保留 Surface 柔和外阴影）
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = conceptFloatingCardColor(),
        shadowElevation = if (isDark) 0.dp else 5.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(146.dp)
            .softCardClickable(
                shape = RoundedCornerShape(28.dp),
                clip = false,
                onClick = onCardClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(bgBrush)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StaggeredThreeCovers(songs = songs.take(3))

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = ConceptCoralAccent,
                        fontSize = 13.sp,
                        letterSpacing = 1.2.sp
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "每日推荐",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = titleColor,
                        fontSize = 20.5.sp,
                        letterSpacing = 0.8.sp
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(5.dp))

                Text(
                    text = "懂你的今日专属旋律",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = subColor,
                        fontSize = 11.5.sp
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            FilledIconButton(
                onClick = onPlayAll,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = ConceptCoralAccent
                ),
                modifier = Modifier
                    .size(48.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = CircleShape,
                        ambientColor = ConceptCoralAccent,
                        spotColor = ConceptCoralAccent
                    )
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "播放每日推荐",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

/**
 * 前三首歌曲封面错位层叠组件
 */
@Composable
private fun StaggeredThreeCovers(
    songs: List<Song>,
    primarySize: Dp = 96.dp,
    secondarySize: Dp = 84.dp,
    tertiarySize: Dp = 72.dp
) {
    val cover1 = songs.getOrNull(0)?.coverUrl.orEmpty()
    val cover2 = songs.getOrNull(1)?.coverUrl?.takeIf { it.isNotBlank() } ?: cover1
    val cover3 = songs.getOrNull(2)?.coverUrl?.takeIf { it.isNotBlank() } ?: cover2
    val cover1Model = rememberOptimizedAsyncImageModel(rawUrl = cover1, sizePx = 240, targetCdnSize = "240")
    val cover2Model = rememberOptimizedAsyncImageModel(rawUrl = cover2, sizePx = 200, targetCdnSize = "240")
    val cover3Model = rememberOptimizedAsyncImageModel(rawUrl = cover3, sizePx = 160, targetCdnSize = "150")

    Box(
        modifier = Modifier
            .width(primarySize + 40.dp)
            .height(primarySize + 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(tertiarySize)
                .offset(x = 42.dp, y = 5.dp)
                .graphicsLayer {
                    rotationZ = 14f
                    alpha = 0.72f
                }
                .shadow(5.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF3A5A8C), Color(0xFF1F3252))))
        ) {
            if (cover3Model != null) {
                AsyncImage(
                    model = cover3Model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Box(
            modifier = Modifier
                .size(secondarySize)
                .offset(x = 22.dp, y = 2.dp)
                .graphicsLayer {
                    rotationZ = 7f
                    alpha = 0.88f
                }
                .shadow(6.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF284B7E), Color(0xFF182A49))))
        ) {
            if (cover2Model != null) {
                AsyncImage(
                    model = cover2Model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Box(
            modifier = Modifier
                .size(primarySize)
                .graphicsLayer {
                    rotationZ = -3f
                }
                .shadow(10.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        listOf(ConceptCoralAccent, ConceptPrimaryDark)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (cover1Model != null) {
                AsyncImage(
                    model = cover1Model,
                    contentDescription = "今日推荐封面",
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

/**
 * 巅峰排行榜卡片（去内嵌盒子化：消除内部嵌套的灰底预览盒，采用单层多彩微渐变 + 极细分割线）
 */
@Composable
private fun RankCardView(
    rank: RankItem,
    onCardClick: () -> Unit,
    onPlayRank: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val accent = remember(rank) { rankAccentColor(rank) }
    val cardBrush = if (isDark) {
        Brush.linearGradient(
            listOf(
                accent.copy(alpha = 0.16f),
                Color(0xFF141C2B),
                Color(0xFF141C2B)
            )
        )
    } else {
        Brush.linearGradient(
            listOf(
                accent.copy(alpha = 0.10f),
                Color(0xFFFFFFFF),
                Color(0xFFFFFFFF)
            )
        )
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = conceptFloatingCardColor(),
        shadowElevation = 0.dp,
        modifier = Modifier
            .width(268.dp)
            .softCardClickable(shape = RoundedCornerShape(24.dp), onClick = onCardClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBrush)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(accent, ConceptPrimaryDark)))
                ) {
                    val rankCoverModel = rememberOptimizedAsyncImageModel(rawUrl = rank.coverUrl, sizePx = 160, targetCdnSize = "150")
                    if (rankCoverModel != null) {
                        AsyncImage(
                            model = rankCoverModel,
                            contentDescription = rank.rankName,
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.Low,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Leaderboard,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.Center)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rank.rankName,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.5.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "${rank.category.ifBlank { "热榜" }} · ${rank.updateFrequency.ifBlank { "每日更新" }}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = accent,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onPlayRank,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayCircleFilled,
                        contentDescription = "播放榜单",
                        tint = accent,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            if (rank.topSongsPreview.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rank.topSongsPreview.take(3).forEachIndexed { idx, line ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${idx + 1}",
                                color = if (idx == 0) VipGold else accent,
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                modifier = Modifier.width(18.dp)
                            )
                            Text(
                                text = line.replace(Regex("^\\d+\\.\\s*"), ""),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
