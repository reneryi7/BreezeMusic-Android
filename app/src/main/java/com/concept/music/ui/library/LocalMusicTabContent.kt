package com.concept.music.ui.library

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.concept.music.data.model.*
import com.concept.music.ui.components.AddToPlaylistDialog
import com.concept.music.ui.components.BadgeTag
import com.concept.music.ui.components.CreatePlaylistDialog
import com.concept.music.ui.components.rememberOptimizedAsyncImageModel
import com.concept.music.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

internal sealed class LocalDrillDownTarget {
    abstract val stableKey: String

    data class Folder(val folderId: String, val folderName: String) : LocalDrillDownTarget() {
        override val stableKey: String get() = "folder_$folderId"
    }

    data class Artist(val normalizedKey: String, val artistName: String) : LocalDrillDownTarget() {
        override val stableKey: String get() = "artist_$normalizedKey"
    }

    data class Album(val normalizedKey: String, val albumName: String) : LocalDrillDownTarget() {
        override val stableKey: String get() = "album_$normalizedKey"
    }
}

private enum class ActiveSecondaryPopup {
    NONE,
    QUALITY,
    SORT
}

/**
 * 本地音乐核心展示页面：
 * - 空态：简洁优雅的无框文件夹导入引导舞台，明确支持格式与离线隐私承诺；
 * - 已导入态：无框 Hero 统计概览 + 一级四维流体滑动 Tab（全部歌曲 / 文件夹 / 歌手 / 专辑，虚化滑移动效仅作用于下方列表）
 *   + 二级钻取平滑推入/弹出过渡 + 无框沉浸式歌曲列表 + 仅歌曲列表可见的多选管理与零云同步加歌单/建单 + 紧凑无框音质/排序浮窗。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalMusicTabContent(
    tracks: List<LocalAudioTrack>,
    folders: List<LocalMusicFolder>,
    lyricFolders: List<LocalLyricFolder>,
    playlists: List<Playlist>,
    summary: LocalLibrarySummary,
    scanProgress: LocalScanProgress,
    searchQuery: String,
    primaryTab: LocalPrimaryTab,
    onSelectPrimaryTab: (LocalPrimaryTab) -> Unit,
    qualityFilter: LocalQualityFilter,
    onSelectQualityFilter: (LocalQualityFilter) -> Unit,
    sortOption: LocalSortOption,
    onSelectSortOption: (LocalSortOption) -> Unit,
    drillDown: LocalDrillDownTarget?,
    onChangeDrillDown: (LocalDrillDownTarget?) -> Unit,
    selectionMode: Boolean,
    selectedTrackIds: Set<String>,
    onSelectionModeChange: (Boolean) -> Unit,
    onSelectedTrackIdsChange: (Set<String>) -> Unit,
    currentPlayingSongId: String? = null,
    isPlaying: Boolean = false,
    onPickMusicFolder: () -> Unit,
    onPickLyricFolder: () -> Unit,
    onRescanFolder: (LocalMusicFolder) -> Unit,
    onRemoveFolder: (LocalMusicFolder) -> Unit,
    onRemoveLyricFolder: (LocalLyricFolder) -> Unit,
    onRemoveTracksBatch: (Collection<String>) -> Unit,
    onAddSongsToPlaylist: (Playlist, List<Song>) -> Unit,
    onCreateLocalPlaylistWithSongs: (String, List<Song>, (Boolean) -> Unit) -> Unit,
    onPlaySong: (Song, List<Song>) -> Unit,
    onAddNextPlay: (Song) -> Unit,
    onOpenSongActions: ((Song) -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDark = LocalIsDarkTheme.current
    val adaptive = LocalConceptAdaptiveInfo.current
    val cyanAccent = conceptCyanAccentColor()
    val goldAccent = conceptGoldAccentColor()
    val cardBg = conceptFloatingCardColor()
    val elevatedBg = conceptElevatedSurfaceColor()
    val subText = MaterialTheme.colorScheme.onSurfaceVariant

    var pendingRemoveFolder by remember { mutableStateOf<LocalMusicFolder?>(null) }
    var activePopup by remember { mutableStateOf(ActiveSecondaryPopup.NONE) }
    var showBatchAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showCreateLocalPlaylistDialog by remember { mutableStateOf(false) }
    var showBatchRemoveTracksDialog by remember { mutableStateOf(false) }

    // 若已导入的文件夹被移除且正好处于该文件夹钻取态，自动回退至顶层
    LaunchedEffect(folders, drillDown) {
        if (drillDown is LocalDrillDownTarget.Folder && folders.none { it.id == drillDown.folderId }) {
            onChangeDrillDown(null)
        }
    }

    // 切换钻取页或进入多选时收起浮窗
    LaunchedEffect(drillDown, selectionMode) {
        activePopup = ActiveSecondaryPopup.NONE
    }

    // 当尚未导入任何文件夹且无歌曲（也未处于扫描态）时，展示极简无框引导舞台
    if (tracks.isEmpty() && folders.isEmpty() && !scanProgress.isScanning) {
        LocalMusicEmptyStage(
            isDark = isDark,
            cyanAccent = cyanAccent,
            goldAccent = goldAccent,
            lyricFolderCount = lyricFolders.size,
            onPickMusicFolder = onPickMusicFolder,
            onPickLyricFolder = onPickLyricFolder
        )
        return
    }

    val allArtistGroupsBase = remember(tracks) {
        LocalMusicQueryEngine.groupByArtists(tracks)
    }
    val allAlbumGroupsBase = remember(tracks) {
        LocalMusicQueryEngine.groupByAlbums(tracks)
    }

    val displayedArtists = remember(tracks, qualityFilter, sortOption, searchQuery) {
        LocalMusicQueryEngine.groupByArtists(
            tracks = tracks,
            qualityFilter = qualityFilter,
            sortOption = sortOption,
            searchQuery = searchQuery
        )
    }

    val displayedAlbums = remember(tracks, qualityFilter, sortOption, searchQuery) {
        LocalMusicQueryEngine.groupByAlbums(
            tracks = tracks,
            qualityFilter = qualityFilter,
            sortOption = sortOption,
            searchQuery = searchQuery
        )
    }

    val displayedFolders = remember(folders, tracks, qualityFilter, sortOption, searchQuery) {
        val kw = searchQuery.trim().lowercase(Locale.ROOT)
        folders.map { f ->
            val matching = tracks.filter { it.folderId == f.id && LocalMusicQueryEngine.matchesQualityFilter(it, qualityFilter) }
            f to matching
        }.filter { (f, matching) ->
            val matchKeyword = kw.isBlank() ||
                f.displayName.lowercase(Locale.ROOT).contains(kw) ||
                f.relativePathLabel.lowercase(Locale.ROOT).contains(kw) ||
                matching.any { it.title.lowercase(Locale.ROOT).contains(kw) || it.artist.lowercase(Locale.ROOT).contains(kw) }
            matchKeyword && (qualityFilter == LocalQualityFilter.ALL || matching.isNotEmpty())
        }.sortedWith { a, b ->
            when (sortOption) {
                LocalSortOption.TITLE, LocalSortOption.ARTIST -> a.first.displayName.compareTo(b.first.displayName, ignoreCase = true)
                LocalSortOption.FILE_SIZE -> b.second.sumOf { it.fileSizeBytes }.compareTo(a.second.sumOf { it.fileSizeBytes })
                LocalSortOption.DURATION -> b.second.sumOf { it.durationMs }.compareTo(a.second.sumOf { it.durationMs })
                LocalSortOption.DEFAULT -> a.first.addedAtMs.compareTo(b.first.addedAtMs)
            }
        }
    }

    // 计算当前视图上下文的底层曲目池（供多选操作条与实时筛选共享）
    val activeContextBaseTracks = remember(tracks, drillDown) {
        resolveTracksForDrillDown(tracks, drillDown)
    }
    val activeDisplayedTracks = remember(activeContextBaseTracks, qualityFilter, sortOption, searchQuery) {
        LocalMusicQueryEngine.filterAndSortTracks(
            tracks = activeContextBaseTracks,
            folderId = null,
            qualityFilter = qualityFilter,
            sortOption = sortOption,
            searchQuery = searchQuery
        )
    }

    val rootListState = rememberLazyListState()
    val horizontalPad = if (adaptive.isLargeScreenDevice) adaptive.horizontalContentPadding else 16.dp

    // 仅作用于下方列表内容的 120fps GPU 横移 + 方向性运动虚化，绝不波及顶部 Hero 卡、一级 Tag 栏与工具栏
    val tabSwitchAnimMod = rememberCategorySwitchGraphicModifier(
        selectedIndex = primaryTab.ordinal,
        applyMotionBlur = true
    )

    val latestPrimaryTab by rememberUpdatedState(primaryTab)
    val latestDrillDown by rememberUpdatedState(drillDown)
    val latestSelectionMode by rememberUpdatedState(selectionMode)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var totalDx = 0f
                var triggered = false
                detectHorizontalDragGestures(
                    onDragStart = {
                        totalDx = 0f
                        triggered = false
                    },
                    onDragEnd = {
                        totalDx = 0f
                        triggered = false
                    },
                    onDragCancel = {
                        totalDx = 0f
                        triggered = false
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        if (triggered || latestDrillDown != null || latestSelectionMode) return@detectHorizontalDragGestures
                        totalDx += dragAmount
                        val curIdx = latestPrimaryTab.ordinal
                        val count = LocalPrimaryTab.entries.size
                        if (totalDx <= -130f && curIdx < count - 1) {
                            triggered = true
                            change.consume()
                            activePopup = ActiveSecondaryPopup.NONE
                            onSelectPrimaryTab(LocalPrimaryTab.entries[curIdx + 1])
                        } else if (totalDx >= 130f && curIdx > 0) {
                            triggered = true
                            change.consume()
                            activePopup = ActiveSecondaryPopup.NONE
                            onSelectPrimaryTab(LocalPrimaryTab.entries[curIdx - 1])
                        }
                    }
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        // 顶层与二级钻取页面之间具备丝滑弹性推入 / 弹出过渡，并完整保留顶层列表滚动位置
        AnimatedContent(
            targetState = drillDown,
            contentKey = { target -> target?.stableKey ?: "local_root_page" },
            transitionSpec = {
                val enteringDrillDown = initialState == null && targetState != null
                val exitingDrillDown = initialState != null && targetState == null
                when {
                    enteringDrillDown -> ContentTransform(
                        targetContentEnter = slideInHorizontally(
                            animationSpec = ConceptMotion.spatialPageSpring()
                        ) { fullWidth -> (fullWidth * 0.28f).toInt() } +
                            fadeIn(animationSpec = tween(220, easing = ConceptMotion.EmphasizedDecelerate)),
                        initialContentExit = slideOutHorizontally(
                            animationSpec = ConceptMotion.spatialPageSpring()
                        ) { fullWidth -> -(fullWidth * 0.12f).toInt() } +
                            fadeOut(animationSpec = tween(180, easing = ConceptMotion.EmphasizedAccelerate)),
                        sizeTransform = SizeTransform(clip = false) { _, _ -> snap() }
                    )
                    exitingDrillDown -> ContentTransform(
                        targetContentEnter = slideInHorizontally(
                            animationSpec = ConceptMotion.spatialPageSpring()
                        ) { fullWidth -> -(fullWidth * 0.12f).toInt() } +
                            fadeIn(animationSpec = tween(220, easing = ConceptMotion.EmphasizedDecelerate)),
                        initialContentExit = slideOutHorizontally(
                            animationSpec = ConceptMotion.spatialPageSpring()
                        ) { fullWidth -> (fullWidth * 0.28f).toInt() } +
                            fadeOut(animationSpec = tween(180, easing = ConceptMotion.EmphasizedAccelerate)),
                        sizeTransform = SizeTransform(clip = false) { _, _ -> snap() }
                    )
                    else -> ContentTransform(
                        targetContentEnter = fadeIn(animationSpec = tween(200)),
                        initialContentExit = fadeOut(animationSpec = tween(160)),
                        sizeTransform = SizeTransform(clip = false) { _, _ -> snap() }
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            label = "localDrillDownPageTransition"
        ) { activeDrillDown ->
            val pageBaseTracks = remember(tracks, activeDrillDown) {
                resolveTracksForDrillDown(tracks, activeDrillDown)
            }
            val pageDisplayedTracks = remember(pageBaseTracks, qualityFilter, sortOption, searchQuery) {
                LocalMusicQueryEngine.filterAndSortTracks(
                    tracks = pageBaseTracks,
                    folderId = null,
                    qualityFilter = qualityFilter,
                    sortOption = sortOption,
                    searchQuery = searchQuery
                )
            }
            val pageDisplayedSongs = remember(pageDisplayedTracks) {
                pageDisplayedTracks.map { it.toSong() }
            }
            val pageQualityCounts = remember(pageBaseTracks) {
                LocalQualityFilter.entries.associateWith { filter ->
                    LocalMusicQueryEngine.countByQualityFilter(pageBaseTracks, filter)
                }
            }

            val showTrackList = activeDrillDown != null || primaryTab == LocalPrimaryTab.ALL_SONGS
            val canShowMultiSelectBtn = showTrackList && pageDisplayedTracks.isNotEmpty() && !selectionMode
            val listState = if (activeDrillDown == null) rootListState else rememberLazyListState()

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .widthIn(max = 880.dp)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = horizontalPad,
                        end = horizontalPad,
                        top = 6.dp,
                        bottom = if (selectionMode) 176.dp else 132.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(if (showTrackList) 2.dp else 10.dp)
                ) {
                    // 1. 顶部概览卡（顶层为 Hero 统计卡 + 一级 Tab 栏；二级页为清爽钻取头部）
                    if (activeDrillDown == null) {
                        item(key = "local_hero_card") {
                            Column {
                                LocalLibraryHeroCard(
                                    summary = summary,
                                    sampleCovers = remember(tracks) {
                                        tracks.mapNotNull { it.coverModelUrl.takeIf { u -> u.isNotBlank() } }.distinct().take(4)
                                    },
                                    scanProgress = scanProgress,
                                    isDark = isDark,
                                    cyanAccent = cyanAccent,
                                    goldAccent = goldAccent,
                                    onPlayAll = {
                                        if (pageDisplayedSongs.isNotEmpty()) {
                                            onPlaySong(pageDisplayedSongs.first(), pageDisplayedSongs)
                                        } else if (tracks.isNotEmpty()) {
                                            val all = tracks.map { it.toSong() }
                                            onPlaySong(all.first(), all)
                                        }
                                    },
                                    onShufflePlay = {
                                        val pool = if (pageDisplayedSongs.isNotEmpty()) pageDisplayedSongs else tracks.map { it.toSong() }
                                        if (pool.isNotEmpty()) {
                                            val shuffled = pool.shuffled()
                                            onPlaySong(shuffled.first(), shuffled)
                                        }
                                    },
                                    onPickMusicFolder = onPickMusicFolder
                                )
                                if (showTrackList) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                }
                            }
                        }

                        // 2. 一级筛选：流体滑动 Tab 栏（全部歌曲 / 文件夹 / 歌手 / 专辑）
                        item(key = "local_primary_tabs") {
                            val primaryTabs = listOf(
                                ConceptCategoryTabItem(LocalPrimaryTab.ALL_SONGS.key, "全部歌曲", summary.totalCount.toString(), ConceptPrimary),
                                ConceptCategoryTabItem(LocalPrimaryTab.FOLDERS.key, "文件夹", folders.size.toString(), cyanAccent),
                                ConceptCategoryTabItem(LocalPrimaryTab.ARTISTS.key, "歌手", allArtistGroupsBase.size.toString(), ConceptCoralAccent),
                                ConceptCategoryTabItem(LocalPrimaryTab.ALBUMS.key, "专辑", allAlbumGroupsBase.size.toString(), goldAccent)
                            )
                            Column(modifier = Modifier.fillMaxWidth()) {
                                ConceptSlidingCategoryTabs(
                                    tabs = primaryTabs,
                                    selectedKey = primaryTab.key,
                                    onSelectTab = { key ->
                                        val target = LocalPrimaryTab.entries.firstOrNull { it.key == key } ?: LocalPrimaryTab.ALL_SONGS
                                        activePopup = ActiveSecondaryPopup.NONE
                                        onSelectPrimaryTab(target)
                                    },
                                    itemSpacing = 20.dp,
                                    fontSize = 15.sp,
                                    indicatorWidth = 22.dp,
                                    scrollable = true
                                )
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                )
                                if (showTrackList) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    } else {
                        item(key = "local_drilldown_header") {
                            val (categoryLabel, titleLabel, badgeColor) = when (activeDrillDown) {
                                is LocalDrillDownTarget.Folder -> Triple("文件夹", activeDrillDown.folderName, cyanAccent)
                                is LocalDrillDownTarget.Artist -> Triple("歌手", activeDrillDown.artistName, ConceptCoralAccent)
                                is LocalDrillDownTarget.Album -> Triple("专辑", activeDrillDown.albumName, goldAccent)
                            }
                            Column {
                                LocalDrillDownHeaderCard(
                                    categoryLabel = categoryLabel,
                                    title = titleLabel,
                                    trackCount = pageDisplayedTracks.size,
                                    hiResCount = pageDisplayedTracks.count { it.isHiRes },
                                    losslessCount = pageDisplayedTracks.count { !it.isHiRes && it.isLossless },
                                    accentColor = badgeColor,
                                    cardBg = cardBg,
                                    onBack = {
                                        activePopup = ActiveSecondaryPopup.NONE
                                        onChangeDrillDown(null)
                                    },
                                    onPlayAll = {
                                        if (pageDisplayedSongs.isNotEmpty()) {
                                            onPlaySong(pageDisplayedSongs.first(), pageDisplayedSongs)
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }

                    // 3. 二级筛选工具条：左侧结果摘要 + 右侧多选（仅歌曲列表非空时显示）/ 音质 / 默认排序
                    item(key = "local_secondary_filter_bar") {
                        val summaryLeftText = when {
                            activeDrillDown != null -> "共 ${pageDisplayedTracks.size} 首曲目"
                            primaryTab == LocalPrimaryTab.ALL_SONGS -> "${pageDisplayedTracks.size} 首歌曲"
                            primaryTab == LocalPrimaryTab.FOLDERS -> "${displayedFolders.size} 个音乐目录"
                            primaryTab == LocalPrimaryTab.ARTISTS -> "${displayedArtists.size} 位歌手"
                            else -> "${displayedAlbums.size} 张专辑"
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = summaryLeftText,
                                color = subText,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 4.dp, end = 6.dp)
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 仅当歌曲列表有歌曲展示时呈现「多选」无框色阶按钮
                                if (canShowMultiSelectBtn) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = elevatedBg,
                                        modifier = Modifier.softCardClickable(shape = RoundedCornerShape(14.dp)) {
                                            activePopup = ActiveSecondaryPopup.NONE
                                            onSelectionModeChange(true)
                                            onSelectedTrackIdsChange(emptySet())
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Checklist,
                                                contentDescription = "多选歌曲",
                                                tint = cyanAccent,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Text(
                                                text = "多选",
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }

                                // 独立「音质」无框按钮 + 紧凑弹性小浮窗
                                Box {
                                    LocalFilterAnchorButton(
                                        icon = Icons.Rounded.GraphicEq,
                                        label = if (qualityFilter == LocalQualityFilter.ALL) "音质" else qualityFilter.label,
                                        active = qualityFilter != LocalQualityFilter.ALL,
                                        expanded = activePopup == ActiveSecondaryPopup.QUALITY,
                                        accentColor = if (qualityFilter == LocalQualityFilter.HI_RES) goldAccent else cyanAccent,
                                        containerBg = elevatedBg,
                                        onClick = {
                                            activePopup = if (activePopup == ActiveSecondaryPopup.QUALITY) {
                                                ActiveSecondaryPopup.NONE
                                            } else {
                                                ActiveSecondaryPopup.QUALITY
                                            }
                                        }
                                    )
                                    if (activePopup == ActiveSecondaryPopup.QUALITY) {
                                        LocalFilterFloatingPopup(
                                            title = "音质筛选",
                                            popupWidth = 174.dp,
                                            onDismiss = { activePopup = ActiveSecondaryPopup.NONE },
                                            items = LocalQualityFilter.entries.map { qf ->
                                                FilterPopupOption(
                                                    key = qf.key,
                                                    label = qf.label,
                                                    subtitle = qf.shortHint,
                                                    count = pageQualityCounts[qf] ?: 0,
                                                    selected = qf == qualityFilter,
                                                    accentColor = when (qf) {
                                                        LocalQualityFilter.HI_RES -> goldAccent
                                                        LocalQualityFilter.FLAC_WAV -> cyanAccent
                                                        LocalQualityFilter.MP3_AAC -> ConceptPrimary
                                                        LocalQualityFilter.ALL -> ConceptPrimary
                                                    }
                                                )
                                            },
                                            onSelect = { key ->
                                                val picked = LocalQualityFilter.entries.firstOrNull { it.key == key }
                                                    ?: LocalQualityFilter.ALL
                                                onSelectQualityFilter(picked)
                                                activePopup = ActiveSecondaryPopup.NONE
                                            }
                                        )
                                    }
                                }

                                // 独立「排序」无框按钮（默认态直接展示「默认」）+ 超紧凑弹性小浮窗
                                Box {
                                    LocalFilterAnchorButton(
                                        icon = Icons.Rounded.SwapVert,
                                        label = sortOption.label,
                                        active = sortOption != LocalSortOption.DEFAULT,
                                        expanded = activePopup == ActiveSecondaryPopup.SORT,
                                        accentColor = ConceptPrimary,
                                        containerBg = elevatedBg,
                                        onClick = {
                                            activePopup = if (activePopup == ActiveSecondaryPopup.SORT) {
                                                ActiveSecondaryPopup.NONE
                                            } else {
                                                ActiveSecondaryPopup.SORT
                                            }
                                        }
                                    )
                                    if (activePopup == ActiveSecondaryPopup.SORT) {
                                        LocalFilterFloatingPopup(
                                            title = "排序方式",
                                            popupWidth = 118.dp,
                                            onDismiss = { activePopup = ActiveSecondaryPopup.NONE },
                                            items = LocalSortOption.entries.map { so ->
                                                FilterPopupOption(
                                                    key = so.key,
                                                    label = so.label,
                                                    subtitle = so.shortHint,
                                                    count = null,
                                                    selected = so == sortOption,
                                                    accentColor = ConceptPrimary
                                                )
                                            },
                                            onSelect = { key ->
                                                val picked = LocalSortOption.entries.firstOrNull { it.key == key }
                                                    ?: LocalSortOption.DEFAULT
                                                onSelectSortOption(picked)
                                                activePopup = ActiveSecondaryPopup.NONE
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        if (showTrackList) {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }

                    // 4. 列表主体（顶层切换一级 Tab 时，方向性虚化与滑移动画仅挂载在下方列表条目上）
                    val itemAnimModifier = if (activeDrillDown == null) tabSwitchAnimMod else Modifier
                    if (showTrackList) {
                        if (pageDisplayedTracks.isEmpty()) {
                            item(key = "local_tracks_empty_filter") {
                                Box(modifier = itemAnimModifier) {
                                    LocalFilterEmptyCard(
                                        hasAnyFilter = qualityFilter != LocalQualityFilter.ALL || searchQuery.isNotBlank(),
                                        cardBg = cardBg,
                                        onResetFilter = {
                                            onSelectQualityFilter(LocalQualityFilter.ALL)
                                        },
                                        onPickMusicFolder = onPickMusicFolder
                                    )
                                }
                            }
                        } else {
                            itemsIndexed(
                                items = pageDisplayedTracks,
                                key = { _, track -> "local_track_${track.id}" },
                                contentType = { _, _ -> "local_track_row" }
                            ) { index, track ->
                                val songModel = pageDisplayedSongs.getOrElse(index) { track.toSong() }
                                val isCurrent = currentPlayingSongId != null &&
                                    (currentPlayingSongId == songModel.id || currentPlayingSongId == track.id)
                                val isSelected = track.id in selectedTrackIds
                                val toggleTrackSelection = {
                                    val next = if (isSelected) selectedTrackIds - track.id else selectedTrackIds + track.id
                                    onSelectedTrackIdsChange(next)
                                }
                                LocalTrackRowCard(
                                    index = index + 1,
                                    track = track,
                                    sortOption = sortOption,
                                    isCurrent = isCurrent,
                                    isPlaying = isCurrent && isPlaying,
                                    selectionMode = selectionMode,
                                    isSelected = isSelected,
                                    cyanAccent = cyanAccent,
                                    goldAccent = goldAccent,
                                    onClick = {
                                        if (selectionMode) {
                                            toggleTrackSelection()
                                        } else {
                                            onPlaySong(songModel, pageDisplayedSongs)
                                        }
                                    },
                                    onLongClick = if (!selectionMode) {
                                        {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onSelectionModeChange(true)
                                            onSelectedTrackIdsChange(setOf(track.id))
                                        }
                                    } else null,
                                    onMoreActions = if (!selectionMode) {
                                        onOpenSongActions?.let { handler -> { handler(songModel) } }
                                    } else null,
                                    modifier = itemAnimModifier
                                )
                            }
                        }
                    } else {
                        when (primaryTab) {
                            LocalPrimaryTab.FOLDERS -> {
                                if (displayedFolders.isEmpty()) {
                                    item(key = "local_folders_empty") {
                                        Box(modifier = itemAnimModifier) {
                                            LocalFilterEmptyCard(
                                                hasAnyFilter = qualityFilter != LocalQualityFilter.ALL || searchQuery.isNotBlank(),
                                                cardBg = cardBg,
                                                onResetFilter = { onSelectQualityFilter(LocalQualityFilter.ALL) },
                                                onPickMusicFolder = onPickMusicFolder
                                            )
                                        }
                                    }
                                } else {
                                    items(items = displayedFolders, key = { "folder_card_${it.first.id}" }) { (folder, matchingTracks) ->
                                        Box(modifier = itemAnimModifier) {
                                            LocalFolderGroupCard(
                                                folder = folder,
                                                matchedCount = matchingTracks.size,
                                                isFiltered = qualityFilter != LocalQualityFilter.ALL,
                                                cyanAccent = cyanAccent,
                                                goldAccent = goldAccent,
                                                cardBg = cardBg,
                                                onClick = {
                                                    onChangeDrillDown(LocalDrillDownTarget.Folder(folder.id, folder.displayName))
                                                },
                                                onRescan = { onRescanFolder(folder) },
                                                onRemove = { pendingRemoveFolder = folder }
                                            )
                                        }
                                    }
                                }

                                // 独立歌词文件夹管理卡片区（放在「文件夹」Tab 下，清晰实用且不打扰歌单浏览）
                                item(key = "local_lyric_folders_card") {
                                    Box(modifier = itemAnimModifier) {
                                        LocalLyricFoldersManagementCard(
                                            lyricFolders = lyricFolders,
                                            cardBg = cardBg,
                                            elevatedBg = elevatedBg,
                                            cyanAccent = cyanAccent,
                                            onAddLyricFolder = onPickLyricFolder,
                                            onRemoveLyricFolder = onRemoveLyricFolder
                                        )
                                    }
                                }
                            }

                            LocalPrimaryTab.ARTISTS -> {
                                if (displayedArtists.isEmpty()) {
                                    item(key = "local_artists_empty") {
                                        Box(modifier = itemAnimModifier) {
                                            LocalFilterEmptyCard(
                                                hasAnyFilter = qualityFilter != LocalQualityFilter.ALL || searchQuery.isNotBlank(),
                                                cardBg = cardBg,
                                                onResetFilter = { onSelectQualityFilter(LocalQualityFilter.ALL) },
                                                onPickMusicFolder = onPickMusicFolder
                                            )
                                        }
                                    }
                                } else {
                                    items(items = displayedArtists, key = { "artist_group_${it.normalizedKey}" }) { group ->
                                        Box(modifier = itemAnimModifier) {
                                            LocalArtistGroupCard(
                                                group = group,
                                                cyanAccent = cyanAccent,
                                                goldAccent = goldAccent,
                                                cardBg = cardBg,
                                                onClick = {
                                                    onChangeDrillDown(LocalDrillDownTarget.Artist(group.normalizedKey, group.name))
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            LocalPrimaryTab.ALBUMS -> {
                                if (displayedAlbums.isEmpty()) {
                                    item(key = "local_albums_empty") {
                                        Box(modifier = itemAnimModifier) {
                                            LocalFilterEmptyCard(
                                                hasAnyFilter = qualityFilter != LocalQualityFilter.ALL || searchQuery.isNotBlank(),
                                                cardBg = cardBg,
                                                onResetFilter = { onSelectQualityFilter(LocalQualityFilter.ALL) },
                                                onPickMusicFolder = onPickMusicFolder
                                            )
                                        }
                                    }
                                } else {
                                    items(items = displayedAlbums, key = { "album_group_${it.normalizedKey}" }) { group ->
                                        Box(modifier = itemAnimModifier) {
                                            LocalAlbumGroupCard(
                                                group = group,
                                                cyanAccent = cyanAccent,
                                                goldAccent = goldAccent,
                                                cardBg = cardBg,
                                                onClick = {
                                                    onChangeDrillDown(LocalDrillDownTarget.Album(group.normalizedKey, group.name))
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            LocalPrimaryTab.ALL_SONGS -> Unit
                        }
                    }
                }
            }
        }

        // 底部无框悬浮多选操作条（自动避让底部迷你播放栏）
        AnimatedVisibility(
            visible = selectionMode,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 90.dp)
        ) {
            val chosenTracks = remember(activeDisplayedTracks, selectedTrackIds) {
                activeDisplayedTracks.filter { it.id in selectedTrackIds }
            }
            val chosenSongs = remember(chosenTracks) {
                chosenTracks.map { it.toSong() }
            }
            val hasSelection = chosenSongs.isNotEmpty()
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = conceptSheetContainerColor().copy(alpha = 0.96f),
                shadowElevation = 14.dp,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LocalMultiSelectDockAction(
                        icon = Icons.Rounded.PlayArrow,
                        label = "播放",
                        tint = ConceptPrimary,
                        enabled = hasSelection,
                        onClick = {
                            if (chosenSongs.isNotEmpty()) {
                                onPlaySong(chosenSongs.first(), chosenSongs)
                                onSelectionModeChange(false)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    LocalMultiSelectDockAction(
                        icon = Icons.Rounded.QueuePlayNext,
                        label = "下一首",
                        tint = ConceptMintAccent,
                        enabled = hasSelection,
                        onClick = {
                            if (chosenSongs.isNotEmpty()) {
                                chosenSongs.asReversed().forEach { onAddNextPlay(it) }
                                Toast.makeText(context, "已将 ${chosenSongs.size} 首本地歌曲加入下一首播放", Toast.LENGTH_SHORT).show()
                                onSelectionModeChange(false)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    LocalMultiSelectDockAction(
                        icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        label = "加歌单",
                        tint = cyanAccent,
                        enabled = hasSelection,
                        onClick = { showBatchAddToPlaylistDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                    LocalMultiSelectDockAction(
                        icon = Icons.Rounded.DeleteOutline,
                        label = "移出曲库",
                        tint = MaterialTheme.colorScheme.error,
                        enabled = hasSelection,
                        onClick = { showBatchRemoveTracksDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    // 批量添加到现有自建歌单或新建纯本地歌单
    if (showBatchAddToPlaylistDialog) {
        val chosenSongs = remember(activeDisplayedTracks, selectedTrackIds) {
            activeDisplayedTracks.filter { it.id in selectedTrackIds }.map { it.toSong() }
        }
        val lead = chosenSongs.firstOrNull()
        if (lead != null) {
            AddToPlaylistDialog(
                song = lead,
                playlists = playlists,
                batchCount = chosenSongs.size,
                headerSubtitleOverride = "本地歌曲仅本机保存，不会同步至云端",
                createPlaylistHint = "创建纯本地歌单（不同步歌单名至云端）",
                onSelectPlaylist = { targetPl ->
                    showBatchAddToPlaylistDialog = false
                    onAddSongsToPlaylist(targetPl, chosenSongs)
                    onSelectionModeChange(false)
                },
                onCreateNewPlaylist = {
                    showBatchAddToPlaylistDialog = false
                    showCreateLocalPlaylistDialog = true
                },
                onDismiss = { showBatchAddToPlaylistDialog = false }
            )
        } else {
            showBatchAddToPlaylistDialog = false
        }
    }

    if (showCreateLocalPlaylistDialog) {
        val pendingSongs = remember(activeDisplayedTracks, selectedTrackIds) {
            activeDisplayedTracks.filter { it.id in selectedTrackIds }.map { it.toSong() }
        }
        CreatePlaylistDialog(
            title = "新建本地歌单",
            subtitle = "纯本地音乐歌单仅保存在本机，不会将歌单名或曲目同步至云端",
            onDismiss = { showCreateLocalPlaylistDialog = false },
            onConfirmWithCallback = { name, onComplete ->
                onCreateLocalPlaylistWithSongs(name, pendingSongs) { ok ->
                    onComplete(ok)
                    if (ok) {
                        showCreateLocalPlaylistDialog = false
                        onSelectionModeChange(false)
                    }
                }
            }
        )
    }

    if (showBatchRemoveTracksDialog) {
        val idsToRemove = selectedTrackIds.toList()
        AlertDialog(
            onDismissRequest = { showBatchRemoveTracksDialog = false },
            title = { Text("移出本地曲库列表？", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text("将选中的 ${idsToRemove.size} 首歌曲从微风本地曲库列表中移除。此操作不会删除您手机中的原始音频文件。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatchRemoveTracksDialog = false
                        onRemoveTracksBatch(idsToRemove)
                        onSelectionModeChange(false)
                    }
                ) {
                    Text("确认移除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchRemoveTracksDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    pendingRemoveFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingRemoveFolder = null },
            title = { Text("移除本地音乐目录？", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text("将「${folder.displayName}」及其下的 ${folder.trackCount} 首歌曲从微风本地曲库中移除。此操作不会删除您手机中的原始音频文件。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = folder
                        pendingRemoveFolder = null
                        onRemoveFolder(target)
                    }
                ) {
                    Text("确认移除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveFolder = null }) {
                    Text("取消")
                }
            }
        )
    }
}

private fun resolveTracksForDrillDown(
    tracks: List<LocalAudioTrack>,
    drillDown: LocalDrillDownTarget?
): List<LocalAudioTrack> = when (drillDown) {
    is LocalDrillDownTarget.Folder -> tracks.filter { it.folderId == drillDown.folderId }
    is LocalDrillDownTarget.Artist -> tracks.filter { t ->
        val list = t.artists.ifEmpty { LocalMusicQueryEngine.splitArtistNames(t.artist) }
        list.any { LocalMusicQueryEngine.normalizeArtistKey(it) == drillDown.normalizedKey }
    }
    is LocalDrillDownTarget.Album -> tracks.filter { t ->
        LocalMusicQueryEngine.normalizeAlbumKey(t.album) == drillDown.normalizedKey
    }
    null -> tracks
}

@Composable
private fun LocalMultiSelectDockAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val effectiveTint = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .softClickable(enabled = enabled, pressScale = 0.94f, onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = effectiveTint,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            color = effectiveTint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

// =============================================================================
// 1. 初始空态极简无框高质感引导舞台
// =============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalMusicEmptyStage(
    isDark: Boolean,
    cyanAccent: Color,
    goldAccent: Color,
    lyricFolderCount: Int,
    onPickMusicFolder: () -> Unit,
    onPickLyricFolder: () -> Unit
) {
    val primaryText = MaterialTheme.colorScheme.onSurface
    val subText = MaterialTheme.colorScheme.onSurfaceVariant
    val badgeBrush = Brush.linearGradient(
        colors = if (isDark) {
            listOf(ConceptPrimary.copy(alpha = 0.28f), cyanAccent.copy(alpha = 0.22f))
        } else {
            listOf(ConceptPrimary.copy(alpha = 0.16f), cyanAccent.copy(alpha = 0.16f))
        }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 极简无框光晕音符文件夹徽标
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(86.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(badgeBrush),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LibraryMusic,
                        contentDescription = null,
                        tint = if (isDark) Color(0xFF8CD9FF) else ConceptPrimary,
                        modifier = Modifier.size(40.dp)
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = cyanAccent,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-2).dp, y = (-2).dp)
                        .size(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.CreateNewFolder,
                            contentDescription = null,
                            tint = Color(0xFF06161B),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "导入你的本地音乐",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 21.sp
                ),
                color = primaryText,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "选择手机中的音乐文件夹即可开始扫描\n自动识别 Hi-Res 与无损规格、内嵌封面及独立歌词",
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 21.sp,
                    fontSize = 13.5.sp
                ),
                color = subText,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 主按钮：选择音乐文件夹
            Button(
                onClick = onPickMusicFolder,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ConceptPrimary,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .height(50.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "选择音乐文件夹",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 次按钮：导入独立歌词目录
            TextButton(
                onClick = onPickLyricFolder,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lyrics,
                    contentDescription = null,
                    tint = cyanAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (lyricFolderCount > 0) "已关联 $lyricFolderCount 个歌词目录 · 继续导入" else "导入独立歌词目录 (.lrc / .krc)",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cyanAccent
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(
                    "Hi-Res" to goldAccent,
                    "FLAC" to cyanAccent,
                    "WAV" to cyanAccent,
                    "ALAC" to cyanAccent,
                    "MP3" to ConceptPrimary,
                    "AAC" to ConceptPrimary,
                    "OGG" to subText
                ).forEach { (fmt, tint) ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = tint.copy(alpha = if (isDark) 0.13f else 0.10f)
                    ) {
                        Text(
                            text = fmt,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = tint,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.VerifiedUser,
                    contentDescription = null,
                    tint = subText.copy(alpha = 0.65f),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "纯端侧离线扫描 · 仅访问您授权的目录",
                    fontSize = 11.5.sp,
                    color = subText.copy(alpha = 0.72f)
                )
            }
        }
    }
}

// =============================================================================
// 2. 已导入态无框 Hero 统计概览卡（严谨展示 Hi-Res & 无损统计）
// =============================================================================

@Composable
private fun LocalLibraryHeroCard(
    summary: LocalLibrarySummary,
    sampleCovers: List<String>,
    scanProgress: LocalScanProgress,
    isDark: Boolean,
    cyanAccent: Color,
    goldAccent: Color,
    onPlayAll: () -> Unit,
    onShufflePlay: () -> Unit,
    onPickMusicFolder: () -> Unit
) {
    val heroBrush = if (isDark) {
        Brush.linearGradient(listOf(Color(0xFF132238), Color(0xFF121A2C), Color(0xFF101726)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFE7F3FF), Color(0xFFF1F7FF), Color(0xFFF6FAFF)))
    }
    val primaryText = MaterialTheme.colorScheme.onSurface
    val subText = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(heroBrush)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LocalCoverCollageBadge(
                    covers = sampleCovers,
                    isDark = isDark,
                    cyanAccent = cyanAccent
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${summary.totalCount}",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 24.sp
                            ),
                            color = primaryText
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "首本地歌曲",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            ),
                            color = primaryText.copy(alpha = 0.86f),
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = goldAccent.copy(alpha = if (isDark) 0.18f else 0.14f)
                        ) {
                            Text(
                                text = "${summary.hiResCount} 首 Hi-Res",
                                color = goldAccent,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = cyanAccent.copy(alpha = if (isDark) 0.18f else 0.14f)
                        ) {
                            Text(
                                text = "${summary.losslessCount} 首无损",
                                color = cyanAccent,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
                            )
                        }
                    }

                    val metaLine = buildString {
                        append("${summary.folderCount} 个音乐目录 · ${LocalMusicQueryEngine.formatBytes(summary.totalBytes)}")
                        if (summary.lyricFolderCount > 0) {
                            append(" · ${summary.lyricFolderCount} 个歌词目录")
                        }
                    }
                    Text(
                        text = metaLine,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = subText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            AnimatedVisibility(
                visible = scanProgress.isScanning,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        color = cyanAccent,
                        trackColor = cyanAccent.copy(alpha = 0.18f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                    )
                    Text(
                        text = scanProgress.message.ifBlank { "正在扫描本地音频文件…" },
                        fontSize = 11.5.sp,
                        color = cyanAccent,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    enabled = summary.totalCount > 0,
                    onClick = onPlayAll,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ConceptCoralAccent,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
                    modifier = Modifier.height(38.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("播放全部", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                }

                FilledTonalButton(
                    enabled = summary.totalCount > 0,
                    onClick = onShufflePlay,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 9.dp),
                    modifier = Modifier.height(38.dp)
                ) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("随机", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.weight(1f))

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cyanAccent.copy(alpha = if (isDark) 0.16f else 0.13f),
                    modifier = Modifier
                        .height(38.dp)
                        .softCardClickable(shape = RoundedCornerShape(14.dp), onClick = onPickMusicFolder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CreateNewFolder,
                            contentDescription = "添加目录",
                            tint = cyanAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "添加目录",
                            color = cyanAccent,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalCoverCollageBadge(
    covers: List<String>,
    isDark: Boolean,
    cyanAccent: Color
) {
    val size = 68.dp
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    if (isDark) listOf(Color(0xFF1D3250), Color(0xFF15253A))
                    else listOf(Color(0xFFD6EAFF), Color(0xFFC5F0EC))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            covers.size >= 4 -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f)) {
                        AsyncImage(
                            model = rememberOptimizedAsyncImageModel(covers[0], sizePx = 128),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.Medium,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        AsyncImage(
                            model = rememberOptimizedAsyncImageModel(covers[1], sizePx = 128),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.Medium,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                    Row(modifier = Modifier.weight(1f)) {
                        AsyncImage(
                            model = rememberOptimizedAsyncImageModel(covers[2], sizePx = 128),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.Medium,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        AsyncImage(
                            model = rememberOptimizedAsyncImageModel(covers[3], sizePx = 128),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.Medium,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            }
            covers.isNotEmpty() -> {
                AsyncImage(
                    model = rememberOptimizedAsyncImageModel(covers.first(), sizePx = 220),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Rounded.LibraryMusic,
                    contentDescription = null,
                    tint = cyanAccent,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}

// =============================================================================
// 3. 钻取子视图头部
// =============================================================================

@Composable
private fun LocalDrillDownHeaderCard(
    categoryLabel: String,
    title: String,
    trackCount: Int,
    hiResCount: Int,
    losslessCount: Int,
    accentColor: Color,
    cardBg: Color,
    onBack: () -> Unit,
    onPlayAll: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cardBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回上一级",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BadgeTag(text = categoryLabel, color = accentColor)
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "$trackCount 首歌曲 · $hiResCount 首 Hi-Res · $losslessCount 首无损",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalIconButton(
                enabled = trackCount > 0,
                onClick = onPlayAll,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = ConceptCoralAccent.copy(alpha = 0.18f),
                    contentColor = ConceptCoralAccent
                ),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "播放全部", modifier = Modifier.size(20.dp))
            }
        }
    }
}

// =============================================================================
// 4. 二级无框独立筛选按钮 & 紧凑丝滑弹性小浮窗
// =============================================================================

@Composable
private fun LocalFilterAnchorButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    expanded: Boolean,
    accentColor: Color,
    containerBg: Color,
    onClick: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = ConceptMotion.expressiveSpring(),
        label = "filterChevronRotation"
    )
    val bgColor = if (active || expanded) {
        accentColor.copy(alpha = if (isDark) 0.22f else 0.14f)
    } else {
        containerBg
    }
    val fgColor = if (active || expanded) accentColor else MaterialTheme.colorScheme.onSurface

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        modifier = Modifier.softCardClickable(shape = RoundedCornerShape(14.dp), onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active || expanded) accentColor else ConceptPrimary,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = label,
                color = fgColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = fgColor.copy(alpha = 0.78f),
                modifier = Modifier
                    .size(15.dp)
                    .graphicsLayer { rotationZ = chevronRotation }
            )
        }
    }
}

private data class FilterPopupOption(
    val key: String,
    val label: String,
    val subtitle: String,
    val count: Int?,
    val selected: Boolean,
    val accentColor: Color
)

/**
 * 紧凑无框丝滑灵动弹性绽放小浮窗：
 * - 锚定在对应按钮正下方弹出，按内容类型精确控制宽度消除右侧冗余留白；
 * - 无边框设计，使用 `ConceptMotion.floatingPopupSpring()` 驱动 GPU `graphicsLayer` 绽放缩放与渐显。
 */
@Composable
private fun LocalFilterFloatingPopup(
    title: String,
    popupWidth: Dp,
    items: List<FilterPopupOption>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = ConceptMotion.floatingPopupSpring()
        )
    }

    val animateDismiss: (() -> Unit) -> Unit = { after ->
        if (!closing) {
            closing = true
            scope.launch {
                progress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 155, easing = ConceptMotion.GentleSpatialExitEasing)
                )
                after()
            }
        }
    }

    val yOffsetPx = with(density) { 40.dp.roundToPx() }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, yOffsetPx),
        onDismissRequest = { animateDismiss(onDismiss) },
        properties = PopupProperties(focusable = true)
    ) {
        val surfaceBg = conceptSheetContainerColor().copy(alpha = 0.98f)

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = surfaceBg,
            shadowElevation = 18.dp,
            modifier = Modifier
                .width(popupWidth)
                .graphicsLayer {
                    val raw = progress.value
                    val clamped = raw.coerceIn(0f, 1f)
                    val s = (0.84f + 0.16f * raw).coerceIn(0.76f, 1.05f)
                    scaleX = s
                    scaleY = s
                    alpha = (clamped * 1.15f).coerceIn(0f, 1f)
                    translationY = (1f - raw) * (-12f * density.density)
                    transformOrigin = TransformOrigin(0.86f, 0f)
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )

                items.forEach { opt ->
                    val rowBg = if (opt.selected) {
                        opt.accentColor.copy(alpha = if (isDark) 0.20f else 0.13f)
                    } else {
                        Color.Transparent
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = rowBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .softCardClickable(shape = RoundedCornerShape(12.dp)) {
                                animateDismiss { onSelect(opt.key) }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 9.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (opt.selected) opt.accentColor
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = opt.label,
                                    fontSize = 13.sp,
                                    fontWeight = if (opt.selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    color = if (opt.selected) opt.accentColor else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                if (opt.count != null) {
                                    Text(
                                        text = opt.count.toString(),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (opt.selected) opt.accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                    )
                                }
                                if (opt.selected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "已选中",
                                        tint = opt.accentColor,
                                        modifier = Modifier.size(15.dp)
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

// =============================================================================
// 5. 无框沉浸式本地曲目行 & 一级分组卡片（文件夹 / 歌词目录 / 歌手 / 专辑）
// =============================================================================

@Composable
private fun LocalTrackRowCard(
    index: Int,
    track: LocalAudioTrack,
    sortOption: LocalSortOption,
    isCurrent: Boolean,
    isPlaying: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    cyanAccent: Color,
    goldAccent: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onMoreActions: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val primaryText = MaterialTheme.colorScheme.onSurface
    val subText = MaterialTheme.colorScheme.onSurfaceVariant
    val activeHighlight = if (track.isHiRes) goldAccent else ConceptPrimary

    // 统一项目内无框沉浸式设计：常规态透明直通舞台背景，仅在多选选中或当前播放时给予轻柔圆角色阶高亮
    val highlightBg = when {
        selectionMode && isSelected -> cyanAccent.copy(alpha = if (isDark) 0.16f else 0.11f)
        isCurrent -> activeHighlight.copy(alpha = if (isDark) 0.14f else 0.10f)
        else -> Color.Transparent
    }

    val qualityBadgeText = when {
        track.isHiRes -> "Hi-Res"
        track.format.isNotBlank() -> track.format.uppercase(Locale.ROOT)
        track.isLossless -> "SQ"
        else -> "MP3"
    }
    val qualityBadgeColor = when (track.qualityTier) {
        LocalAudioQualityTier.HI_RES -> goldAccent
        LocalAudioQualityTier.LOSSLESS -> cyanAccent
        LocalAudioQualityTier.LOSSY -> ConceptPrimary
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                if (highlightBg.alpha > 0f) {
                    val r = 16.dp.toPx()
                    drawRoundRect(
                        color = highlightBg,
                        cornerRadius = CornerRadius(r, r)
                    )
                }
            }
            .softClickable(
                pressScale = 0.985f,
                pressAlpha = 0.88f,
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(start = 6.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：多选态展示无框勾选图标，常规态展示精致编号 / 播放态指示
        Box(
            modifier = Modifier.width(30.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                selectionMode -> {
                    Icon(
                        imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (isSelected) "已选中" else "未选中",
                        tint = if (isSelected) cyanAccent else subText.copy(alpha = 0.55f),
                        modifier = Modifier.size(21.dp)
                    )
                }
                isCurrent -> {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = activeHighlight,
                        modifier = Modifier.size(18.dp)
                    )
                }
                else -> {
                    Text(
                        text = String.format(Locale.US, "%02d", index),
                        fontSize = 12.5.sp,
                        fontWeight = if (index in 1..3) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (index in 1..3) ConceptPrimary else subText.copy(alpha = 0.82f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // 封面缩略图
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center
        ) {
            if (track.coverModelUrl.isNotBlank()) {
                AsyncImage(
                    model = rememberOptimizedAsyncImageModel(track.coverModelUrl, sizePx = 144),
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = qualityBadgeColor.copy(alpha = 0.75f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.5.dp)
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 15.sp
                ),
                color = if (isCurrent) activeHighlight else primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                BadgeTag(text = qualityBadgeText, color = qualityBadgeColor)
                if (track.isHiRes && track.format.isNotBlank() && track.format != "HI-RES") {
                    BadgeTag(text = track.format.uppercase(Locale.ROOT), color = cyanAccent)
                }
                if (track.hasLocalLyric) {
                    BadgeTag(text = "词", color = ConceptMintAccent)
                }

                val tailSpec = when (sortOption) {
                    LocalSortOption.FILE_SIZE -> LocalMusicQueryEngine.formatBytes(track.fileSizeBytes)
                    LocalSortOption.DURATION -> formatTrackDuration(track.durationMs)
                    else -> track.technicalSpecLabel
                }
                val subtitleText = buildString {
                    append(track.artist.ifBlank { "未知歌手" })
                    if (track.album.isNotBlank()) append(" · ${track.album}")
                    if (tailSpec.isNotBlank()) append(" · $tailSpec")
                }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.8.sp),
                    color = subText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (onMoreActions != null) {
            IconButton(
                onClick = onMoreActions,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "更多操作",
                    tint = subText,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

@Composable
private fun LocalFolderGroupCard(
    folder: LocalMusicFolder,
    matchedCount: Int,
    isFiltered: Boolean,
    cyanAccent: Color,
    goldAccent: Color,
    cardBg: Color,
    onClick: () -> Unit,
    onRescan: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cardBg,
        modifier = Modifier
            .fillMaxWidth()
            .softCardClickable(shape = RoundedCornerShape(20.dp), onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(cyanAccent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = cyanAccent,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = folder.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (folder.relativePathLabel.isNotBlank()) {
                    Text(
                        text = folder.relativePathLabel,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (isFiltered) "筛选匹配 $matchedCount 首 / 共 ${folder.trackCount} 首" else "${folder.trackCount} 首歌曲",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (folder.hiResCount > 0) {
                        BadgeTag(text = "${folder.hiResCount} Hi-Res", color = goldAccent)
                    }
                    if (folder.losslessCount > 0) {
                        BadgeTag(text = "${folder.losslessCount} 无损", color = cyanAccent)
                    }
                }
            }
            IconButton(onClick = onRescan, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "重新扫描该目录",
                    tint = cyanAccent,
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "移除目录",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

@Composable
private fun LocalLyricFoldersManagementCard(
    lyricFolders: List<LocalLyricFolder>,
    cardBg: Color,
    elevatedBg: Color,
    cyanAccent: Color,
    onAddLyricFolder: () -> Unit,
    onRemoveLyricFolder: (LocalLyricFolder) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cardBg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Lyrics, contentDescription = null, tint = cyanAccent, modifier = Modifier.size(19.dp))
                    Column {
                        Text(
                            text = "独立歌词目录",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "当歌词独立存放于其他目录时，导入后将自动匹配同曲名 .lrc / .krc",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (lyricFolders.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    lyricFolders.forEach { lf ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = elevatedBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.FolderSpecial,
                                    contentDescription = null,
                                    tint = cyanAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = lf.displayName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "已索引 ${lf.lyricFileCount} 份独立歌词",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveLyricFolder(lf) },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "移除歌词目录",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            FilledTonalButton(
                onClick = onAddLyricFolder,
                shape = RoundedCornerShape(13.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("选择独立歌词文件夹目录", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LocalArtistGroupCard(
    group: LocalArtistGroup,
    cyanAccent: Color,
    goldAccent: Color,
    cardBg: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardBg,
        modifier = Modifier
            .fillMaxWidth()
            .softCardClickable(shape = RoundedCornerShape(18.dp), onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ConceptCoralAccent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                if (group.coverModelUrl.isNotBlank()) {
                    AsyncImage(
                        model = rememberOptimizedAsyncImageModel(group.coverModelUrl, sizePx = 144),
                        contentDescription = group.name,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Medium,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = ConceptCoralAccent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${group.tracks.size} 首歌曲",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (group.hiResCount > 0) {
                        BadgeTag(text = "${group.hiResCount} Hi-Res", color = goldAccent)
                    }
                    if (group.losslessCount > 0) {
                        BadgeTag(text = "${group.losslessCount} 无损", color = cyanAccent)
                    }
                }
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun LocalAlbumGroupCard(
    group: LocalAlbumGroup,
    cyanAccent: Color,
    goldAccent: Color,
    cardBg: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardBg,
        modifier = Modifier
            .fillMaxWidth()
            .softCardClickable(shape = RoundedCornerShape(18.dp), onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(goldAccent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                if (group.coverModelUrl.isNotBlank()) {
                    AsyncImage(
                        model = rememberOptimizedAsyncImageModel(group.coverModelUrl, sizePx = 156),
                        contentDescription = group.name,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Medium,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Album,
                        contentDescription = null,
                        tint = goldAccent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${group.artistSummary} · ${group.tracks.size} 首",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (group.hiResCount > 0) {
                        BadgeTag(text = "Hi-Res", color = goldAccent)
                    } else if (group.losslessCount > 0) {
                        BadgeTag(text = "无损", color = cyanAccent)
                    }
                }
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun LocalFilterEmptyCard(
    hasAnyFilter: Boolean,
    cardBg: Color,
    onResetFilter: () -> Unit,
    onPickMusicFolder: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
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
                imageVector = if (hasAnyFilter) Icons.Rounded.FilterAltOff else Icons.Rounded.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(38.dp)
            )
            Text(
                text = if (hasAnyFilter) "当前筛选条件下没有匹配的本地歌曲" else "所选目录下未发现支持的音频文件",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            if (hasAnyFilter) {
                FilledTonalButton(onClick = onResetFilter, shape = RoundedCornerShape(14.dp)) {
                    Text("清空筛选条件", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(onClick = onPickMusicFolder, shape = RoundedCornerShape(14.dp)) {
                    Text("选择其他音乐文件夹", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun formatTrackDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSec = durationMs / 1000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
