package com.concept.music.ui.library

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.concept.music.data.model.ListenRankItem
import com.concept.music.data.model.Playlist
import com.concept.music.data.model.Song
import com.concept.music.service.AppPermissionManager
import com.concept.music.service.AppPermissionType
import com.concept.music.ui.components.AddToPlaylistDialog
import com.concept.music.ui.components.ArtworkDownloadCategory
import com.concept.music.ui.components.ArtworkSaveResult
import com.concept.music.ui.components.CreatePlaylistDialog
import com.concept.music.ui.components.FloatingScrollToTopButton
import com.concept.music.ui.components.HdArtworkPreviewDialog
import com.concept.music.ui.components.HdArtworkSaver
import com.concept.music.ui.components.SongItemRow
import com.concept.music.ui.components.rememberProgressiveSongList
import com.concept.music.ui.components.smoothFastScrollToItem
import com.concept.music.ui.theme.ConceptAnimatedOverlaySheet
import com.concept.music.ui.theme.ConceptCategoryTabItem
import com.concept.music.ui.theme.ConceptCoralAccent
import com.concept.music.ui.theme.ConceptCyanAccent
import com.concept.music.ui.theme.ConceptFluidRefreshIcon
import com.concept.music.ui.theme.ConceptMintAccent
import com.concept.music.ui.theme.ConceptPrimary
import com.concept.music.ui.theme.ConceptPrimaryDark
import com.concept.music.ui.theme.ConceptSlidingCategoryTabs
import com.concept.music.ui.theme.ConceptStageBackground
import com.concept.music.ui.theme.LocalConceptAdaptiveInfo
import com.concept.music.ui.theme.VipGold
import com.concept.music.ui.theme.conceptElevatedSurfaceColor
import com.concept.music.ui.theme.conceptFloatingCardColor
import com.concept.music.ui.theme.conceptSheetContainerColor
import com.concept.music.ui.theme.rememberCategorySwitchGraphicModifier
import com.concept.music.ui.theme.softCardClickable
import com.concept.music.ui.theme.softClickable
import com.concept.music.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 歌单内排序模式（无门槛直接标配，不设 200 首门槛限制）
 */
private fun Song.selectionKey(): String = "${id}_${hash}"

enum class PlaylistSortMode(val label: String) {
    DEFAULT("默认顺序"),
    TITLE_AZ("歌名 A-Z"),
    ARTIST_AZ("歌手名"),
    DURATION_DESC("时长最长")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    viewModel: LibraryViewModel,
    playlistId: String,
    onBack: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    currentSong: Song? = null,
    onSongMoreClick: ((Song, (() -> Unit)?) -> Unit)? = null,
    onSharePlaylist: ((Playlist) -> Unit)? = null,
    onOpenPlaylistComments: ((Playlist) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val adaptiveInfo = LocalConceptAdaptiveInfo.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val playlists by viewModel.playlists.collectAsState()
    val songs by viewModel.librarySongs.collectAsState()
    val loading by viewModel.libraryLoading.collectAsState()
    val error by viewModel.libraryError.collectAsState()
    val listenRankWeek by viewModel.listenRankWeek.collectAsState()
    val listenRankAll by viewModel.listenRankAll.collectAsState()
    val listenRankLoading by viewModel.listenRankLoading.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(PlaylistSortMode.DEFAULT) }
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedSongIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showPlaylistCoverPreview by remember { mutableStateOf(false) }
    var showEditPlaylistDialog by remember { mutableStateOf(false) }
    var showCloudTrackSortSheet by remember { mutableStateOf(false) }
    var historySubTab by remember(playlistId) { mutableIntStateOf(0) } // 0: 最近播放, 1: 听歌排行
    var listenRankType by remember { mutableIntStateOf(0) } // 0: 近一周, 1: 全部时间

    val filteredAndSortedSongs = remember(songs, searchQuery, sortMode) {
        val q = searchQuery.trim()
        val filtered = if (q.isBlank()) {
            songs
        } else {
            songs.filter { s ->
                s.title.contains(q, ignoreCase = true) ||
                    s.artist.contains(q, ignoreCase = true) ||
                    s.album.contains(q, ignoreCase = true)
            }
        }
        when (sortMode) {
            PlaylistSortMode.DEFAULT -> filtered
            PlaylistSortMode.TITLE_AZ -> filtered.sortedBy { it.title.lowercase() }
            PlaylistSortMode.ARTIST_AZ -> filtered.sortedBy { it.artist.lowercase() }
            PlaylistSortMode.DURATION_DESC -> filtered.sortedByDescending { it.durationMs }
        }
    }

    val playingSongIndex = remember(filteredAndSortedSongs, currentSong) {
        if (currentSong == null) -1
        else filteredAndSortedSongs.indexOfFirst { s -> viewModel.isSameSong(s, currentSong) }
    }

    var lastKnownPlaylist by remember(playlistId) { mutableStateOf<Playlist?>(null) }
    val currentPlaylist = remember(playlists, playlistId, songs, lastKnownPlaylist) {
        val matched = when (playlistId) {
            "like" -> playlists.firstOrNull { it.isLiked } ?: Playlist("like", "我喜欢的音乐", isLiked = true)
            "history" -> Playlist("history", "最近播放")
            else -> {
                val directMatch = playlists.firstOrNull { it.matchesIdentifier(playlistId) }
                val fallbackMatch = if (directMatch == null && lastKnownPlaylist != null) {
                    playlists.firstOrNull { it.isSamePlaylistAs(lastKnownPlaylist) }
                } else null
                val chosen = directMatch ?: fallbackMatch
                if (chosen != null && lastKnownPlaylist != null) {
                    chosen.copy(
                        coverUrl = chosen.coverUrl.ifBlank { lastKnownPlaylist!!.coverUrl },
                        description = chosen.description.ifBlank { lastKnownPlaylist!!.description },
                        creatorName = chosen.creatorName.ifBlank { lastKnownPlaylist!!.creatorName },
                        trackCount = if (chosen.trackCount > 0) chosen.trackCount else lastKnownPlaylist!!.trackCount
                    )
                } else {
                    chosen ?: lastKnownPlaylist?.copy(isFavorite = false)
                }
            }
        }
        if (matched != null && matched.coverUrl.isBlank() && songs.isNotEmpty()) {
            matched.copy(coverUrl = songs.firstOrNull()?.coverUrl.orEmpty())
        } else {
            matched
        }
    }
    LaunchedEffect(currentPlaylist) {
        if (currentPlaylist != null) {
            lastKnownPlaylist = currentPlaylist
        }
    }

    val isCustomCreated = remember(currentPlaylist, playlistId) {
        currentPlaylist != null && currentPlaylist.isCreatedByMe && !currentPlaylist.isLiked && playlistId != "like" && playlistId != "history"
    }

    val isExternalPlaylist = remember(currentPlaylist, playlistId) {
        currentPlaylist != null && !currentPlaylist.isCreatedByMe && playlistId != "like" && playlistId != "history"
    }

    val effectivePlaylistCoverUrl = remember(currentPlaylist?.coverUrl, songs) {
        currentPlaylist?.coverUrl?.takeIf { it.isNotBlank() }
            ?: songs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty()
    }

    var showDeletePlaylistDialog by remember { mutableStateOf(false) }
    var showUncollectPlaylistDialog by remember { mutableStateOf(false) }
    var showSharePlaylistSheet by remember { mutableStateOf(false) }
    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }
    var songToAddToPlaylist by remember { mutableStateOf<Song?>(null) }
    var showCreateDialogFromAdd by remember { mutableStateOf(false) }

    LaunchedEffect(playlistId) {
        val section = when (playlistId) {
            "like" -> "我喜欢"
            "history" -> "最近"
            else -> "歌单:$playlistId"
        }
        viewModel.openLibrarySection(section)
    }

    ConceptStageBackground(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                    ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                        Text(
                            text = currentPlaylist?.title ?: "歌单详情",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        if (songs.isNotEmpty() && !(playlistId == "history" && historySubTab == 1)) {
                            TextButton(
                                onClick = {
                                    isMultiSelectMode = !isMultiSelectMode
                                    if (!isMultiSelectMode) selectedSongIds = emptySet()
                                }
                            ) {
                                Text(
                                    text = if (isMultiSelectMode) "完成 (${selectedSongIds.size})" else "多选",
                                    color = ConceptPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        if (playlistId == "history" && historySubTab == 0 && songs.isNotEmpty()) {
                            IconButton(onClick = viewModel::clearHistory) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "清空历史",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                                )
                            }
                        }

                        if (isCustomCreated && currentPlaylist != null) {
                            IconButton(onClick = { showEditPlaylistDialog = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = "编辑歌单信息",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            if (songs.size >= 2) {
                                IconButton(onClick = { showCloudTrackSortSheet = true }) {
                                    Icon(
                                        imageVector = Icons.Rounded.SwapVert,
                                        contentDescription = "调整曲目云端排序",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            IconButton(onClick = { showDeletePlaylistDialog = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "删除歌单",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                )
                            }
                        }

                        if (playlistId != "history" && playlistId != "like" && currentPlaylist != null && onOpenPlaylistComments != null) {
                            IconButton(
                                onClick = {
                                    val targetPl = currentPlaylist.copy(
                                        coverUrl = effectivePlaylistCoverUrl,
                                        trackCount = if (currentPlaylist.trackCount > 0) currentPlaylist.trackCount else songs.size
                                    )
                                    onOpenPlaylistComments(targetPl)
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Comment,
                                    contentDescription = "歌单评论",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }

                        if (playlistId != "history" && currentPlaylist != null) {
                            IconButton(
                                onClick = {
                                    val shareTarget = currentPlaylist.copy(
                                        coverUrl = effectivePlaylistCoverUrl,
                                        trackCount = if (currentPlaylist.trackCount > 0) currentPlaylist.trackCount else songs.size
                                    )
                                    if (onSharePlaylist != null) {
                                        onSharePlaylist(shareTarget)
                                    } else {
                                        showSharePlaylistSheet = true
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = "分享歌单",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        var plRefreshSpin by remember { mutableIntStateOf(0) }
                        IconButton(
                            onClick = {
                                plRefreshSpin += 1
                                if (playlistId == "history" && historySubTab == 1) {
                                    viewModel.loadListenRank(listenRankType, force = true)
                                } else {
                                    viewModel.refreshCurrentPlaylistOnly(playlistId)
                                }
                            }
                        ) {
                            ConceptFluidRefreshIcon(
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                isRefreshing = if (playlistId == "history" && historySubTab == 1) listenRankLoading else loading,
                                spinTrigger = plRefreshSpin,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    if (playlistId == "history") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                0 to "最近播放",
                                1 to "听歌排行"
                            ).forEach { (idx, label) ->
                                val selected = historySubTab == idx
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (selected) ConceptPrimary.copy(alpha = 0.16f) else conceptElevatedSurfaceColor(),
                                    modifier = Modifier.softCardClickable(shape = RoundedCornerShape(14.dp)) {
                                        historySubTab = idx
                                        if (idx == 1) {
                                            isMultiSelectMode = false
                                            selectedSongIds = emptySet()
                                            viewModel.loadListenRank(listenRankType, force = false)
                                        }
                                    }
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selected) ConceptPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                    }
                }
            ) { innerPadding ->
                val displayedPlaylistSongs by rememberProgressiveSongList(
                    sourceSongs = filteredAndSortedSongs,
                    listState = listState,
                    resetKey = "${playlistId}_${sortMode.name}_$searchQuery",
                    initialBatchSize = 20,
                    microBatchSize = 15
                )
                val latestFilteredAndSortedSongs by rememberUpdatedState(filteredAndSortedSongs)
                val curPlaylistPlayingId = currentSong?.id
                val curPlaylistPlayingHash = currentSong?.hash.orEmpty()
                val sortSwitchAnimMod = rememberCategorySwitchGraphicModifier(sortMode.ordinal)

                val onCollectToggle: () -> Unit = {
                    val targetPl = currentPlaylist
                    if (targetPl != null) {
                        if (targetPl.isFavorite) {
                            viewModel.deletePlaylist(targetPl) { _, msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            viewModel.collectPlaylist(targetPl) { _, msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                if (playlistId == "history" && historySubTab == 1) {
                    LaunchedEffect(listenRankType) {
                        viewModel.loadListenRank(listenRankType, force = false)
                    }
                    ListenRankSectionBody(
                        rankType = listenRankType,
                        onSelectRankType = { nextType ->
                            listenRankType = nextType
                            viewModel.loadListenRank(nextType, force = false)
                        },
                        items = if (listenRankType == 0) listenRankWeek else listenRankAll,
                        loading = listenRankLoading,
                        currentSong = currentSong,
                        onSongClick = onSongClick,
                        onSongMoreClick = { s ->
                            if (onSongMoreClick != null) onSongMoreClick(s, null)
                            else selectedSongForMenu = s
                        },
                        maxWidth = adaptiveInfo.singleColumnMaxWidth,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                } else if (adaptiveInfo.useTwoPaneDetailLayout) {
                    // 平板横屏 / 宽屏双栏布局：左栏固定歌单信息卡片 + 右栏独立滚动曲目列表
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        PlaylistSideHeroPane(
                            playlist = currentPlaylist,
                            effectiveCoverUrl = effectivePlaylistCoverUrl,
                            songCount = songs.size,
                            isExternal = isExternalPlaylist,
                            onPlayAll = {
                                if (filteredAndSortedSongs.isNotEmpty()) {
                                    onSongClick(filteredAndSortedSongs.first(), filteredAndSortedSongs)
                                }
                            },
                            onCollectClick = onCollectToggle,
                            onCoverClick = {
                                if (effectivePlaylistCoverUrl.isNotBlank()) {
                                    showPlaylistCoverPreview = true
                                }
                            }
                        )

                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentPadding = PaddingValues(bottom = 128.dp)
                        ) {
                            item(key = "pl_toolbar", contentType = "pl_toolbar") {
                                PlaylistToolbarSection(
                                    searchQuery = searchQuery,
                                    onSearchQueryChange = { searchQuery = it },
                                    sortMode = sortMode,
                                    onSortModeChange = { sortMode = it },
                                    isMultiSelectMode = isMultiSelectMode,
                                    selectedSongIds = selectedSongIds,
                                    filteredAndSortedSongs = filteredAndSortedSongs,
                                    onToggleSelectAll = {
                                        selectedSongIds = if (selectedSongIds.size == filteredAndSortedSongs.size) {
                                            emptySet()
                                        } else {
                                            filteredAndSortedSongs.map { it.selectionKey() }.toSet()
                                        }
                                    },
                                    onPlaySelected = {
                                        val chosen = filteredAndSortedSongs.filter { selectedSongIds.contains(it.selectionKey()) }
                                        if (chosen.isNotEmpty()) {
                                            onSongClick(chosen.first(), chosen)
                                            isMultiSelectMode = false
                                            selectedSongIds = emptySet()
                                        }
                                    },
                                    onBatchAddSelected = { showBatchAddToPlaylistDialog = true }
                                )
                            }

                            if (loading && songs.isEmpty()) {
                                item {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        color = ConceptPrimary
                                    )
                                }
                            }

                            if (error != null) {
                                item {
                                    Text(
                                        text = error ?: "加载失败",
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }

                            if (!loading && error == null && filteredAndSortedSongs.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(sortSwitchAnimMod)
                                            .padding(vertical = 48.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (searchQuery.isNotBlank()) "未找到匹配「$searchQuery」的歌曲" else "歌单内暂无歌曲",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            itemsIndexed(
                                items = displayedPlaylistSongs,
                                key = { idx, s -> "${s.id}_${s.hash}_$idx" },
                                contentType = { _, _ -> "song_row" }
                            ) { index, song ->
                                val isCurrentTrack = curPlaylistPlayingId != null && (
                                    curPlaylistPlayingId == song.id ||
                                        (curPlaylistPlayingHash.isNotEmpty() && curPlaylistPlayingHash.equals(song.hash, ignoreCase = true))
                                    )
                                val handleMore: () -> Unit = {
                                    if (onSongMoreClick != null) {
                                        val removeAction: (() -> Unit)? = if (isCustomCreated && currentPlaylist != null) {
                                            {
                                                viewModel.removeSongFromPlaylist(currentPlaylist.listId, song) { _, msg ->
                                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } else null
                                        onSongMoreClick(song, removeAction)
                                    } else {
                                        selectedSongForMenu = song
                                    }
                                }

                                if (isMultiSelectMode) {
                                    val songKey = song.selectionKey()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(sortSwitchAnimMod),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = selectedSongIds.contains(songKey),
                                            onCheckedChange = { checked ->
                                                selectedSongIds = if (checked) {
                                                    selectedSongIds + songKey
                                                } else {
                                                    selectedSongIds - songKey
                                                }
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = ConceptPrimary),
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                        Box(modifier = Modifier.weight(1f)) {
                                            SongItemRow(
                                                song = song,
                                                orderIndex = index + 1,
                                                isPlaying = isCurrentTrack,
                                                onClick = {
                                                    selectedSongIds = if (selectedSongIds.contains(songKey)) {
                                                        selectedSongIds - songKey
                                                    } else {
                                                        selectedSongIds + songKey
                                                    }
                                                },
                                                onMoreClick = handleMore
                                            )
                                        }
                                    }
                                } else {
                                    Box(modifier = sortSwitchAnimMod) {
                                        SongItemRow(
                                            song = song,
                                            orderIndex = index + 1,
                                            isPlaying = isCurrentTrack,
                                            onClick = {
                                                onSongClick(song, latestFilteredAndSortedSongs)
                                            },
                                            onMoreClick = handleMore
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // 手机竖屏或平板竖屏/分屏单栏模式（平板竖屏下居中受控最大宽度）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .then(
                                    if (adaptiveInfo.singleColumnMaxWidth != Dp.Unspecified) {
                                        Modifier.widthIn(max = adaptiveInfo.singleColumnMaxWidth)
                                    } else Modifier
                                )
                                .fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 128.dp)
                        ) {
                            // 1. 歌单头部 Header（点击封面弹出高清预览与保存）
                            item(key = "pl_header", contentType = "pl_header") {
                                PlaylistHeaderCard(
                                    playlist = currentPlaylist,
                                    effectiveCoverUrl = effectivePlaylistCoverUrl,
                                    songCount = songs.size,
                                    isExternal = isExternalPlaylist,
                                    onPlayAll = {
                                        if (filteredAndSortedSongs.isNotEmpty()) {
                                            onSongClick(filteredAndSortedSongs.first(), filteredAndSortedSongs)
                                        }
                                    },
                                    onCollectClick = onCollectToggle,
                                    onCoverClick = {
                                        if (effectivePlaylistCoverUrl.isNotBlank()) {
                                            showPlaylistCoverPreview = true
                                        }
                                    }
                                )
                            }

                            // 2. 歌单内搜索 + 排序切换 + 批量多选栏
                            item(key = "pl_toolbar", contentType = "pl_toolbar") {
                                PlaylistToolbarSection(
                                    searchQuery = searchQuery,
                                    onSearchQueryChange = { searchQuery = it },
                                    sortMode = sortMode,
                                    onSortModeChange = { sortMode = it },
                                    isMultiSelectMode = isMultiSelectMode,
                                    selectedSongIds = selectedSongIds,
                                    filteredAndSortedSongs = filteredAndSortedSongs,
                                    onToggleSelectAll = {
                                        selectedSongIds = if (selectedSongIds.size == filteredAndSortedSongs.size) {
                                            emptySet()
                                        } else {
                                            filteredAndSortedSongs.map { it.selectionKey() }.toSet()
                                        }
                                    },
                                    onPlaySelected = {
                                        val chosen = filteredAndSortedSongs.filter { selectedSongIds.contains(it.selectionKey()) }
                                        if (chosen.isNotEmpty()) {
                                            onSongClick(chosen.first(), chosen)
                                            isMultiSelectMode = false
                                            selectedSongIds = emptySet()
                                        }
                                    },
                                    onBatchAddSelected = { showBatchAddToPlaylistDialog = true }
                                )
                            }

                            // 3. 状态提示
                            if (loading && songs.isEmpty()) {
                                item {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        color = ConceptPrimary
                                    )
                                }
                            }

                            if (error != null) {
                                item {
                                    Text(
                                        text = error ?: "加载失败",
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }

                            if (!loading && error == null && filteredAndSortedSongs.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(sortSwitchAnimMod)
                                            .padding(vertical = 40.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (searchQuery.isNotBlank()) "未找到匹配「$searchQuery」的歌曲" else "歌单内暂无歌曲",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // 4. 歌曲列表
                            itemsIndexed(
                                items = displayedPlaylistSongs,
                                key = { idx, s -> "${s.id}_${s.hash}_$idx" },
                                contentType = { _, _ -> "song_row" }
                            ) { index, song ->
                                val isCurrentTrack = curPlaylistPlayingId != null && (
                                    curPlaylistPlayingId == song.id ||
                                        (curPlaylistPlayingHash.isNotEmpty() && curPlaylistPlayingHash.equals(song.hash, ignoreCase = true))
                                    )
                                val handleMore: () -> Unit = {
                                    if (onSongMoreClick != null) {
                                        val removeAction: (() -> Unit)? = if (isCustomCreated && currentPlaylist != null) {
                                            {
                                                viewModel.removeSongFromPlaylist(currentPlaylist.listId, song) { _, msg ->
                                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } else null
                                        onSongMoreClick(song, removeAction)
                                    } else {
                                        selectedSongForMenu = song
                                    }
                                }

                                if (isMultiSelectMode) {
                                    val songKey = song.selectionKey()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(sortSwitchAnimMod),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = selectedSongIds.contains(songKey),
                                            onCheckedChange = { checked ->
                                                selectedSongIds = if (checked) {
                                                    selectedSongIds + songKey
                                                } else {
                                                    selectedSongIds - songKey
                                                }
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = ConceptPrimary),
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                        Box(modifier = Modifier.weight(1f)) {
                                            SongItemRow(
                                                song = song,
                                                orderIndex = index + 1,
                                                isPlaying = isCurrentTrack,
                                                onClick = {
                                                    selectedSongIds = if (selectedSongIds.contains(songKey)) {
                                                        selectedSongIds - songKey
                                                    } else {
                                                        selectedSongIds + songKey
                                                    }
                                                },
                                                onMoreClick = handleMore
                                            )
                                        }
                                    }
                                } else {
                                    Box(modifier = sortSwitchAnimMod) {
                                        SongItemRow(
                                            song = song,
                                            orderIndex = index + 1,
                                            isPlaying = isCurrentTrack,
                                            onClick = {
                                                onSongClick(song, latestFilteredAndSortedSongs)
                                            },
                                            onMoreClick = handleMore
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 右下角悬浮按钮组
            val showScrollToTop by remember(filteredAndSortedSongs.size) {
                derivedStateOf {
                    listState.firstVisibleItemIndex > 2 ||
                        (listState.firstVisibleItemIndex == 2 && listState.firstVisibleItemScrollOffset > 100)
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
                    visible = showScrollToTop,
                    onClick = {
                        coroutineScope.launch {
                            listState.smoothFastScrollToItem(0)
                        }
                    }
                )

                AnimatedVisibility(
                    visible = playingSongIndex >= 0,
                    enter = fadeIn() + scaleIn(initialScale = 0.85f),
                    exit = fadeOut() + scaleOut(targetScale = 0.85f)
                ) {
                    FilledIconButton(
                        onClick = {
                            if (playingSongIndex >= 0) {
                                coroutineScope.launch {
                                    val headerOffset = if (adaptiveInfo.useTwoPaneDetailLayout) 1 else 2
                                    val targetLazyIndex = (headerOffset + playingSongIndex - 2).coerceAtLeast(0)
                                    listState.smoothFastScrollToItem(targetLazyIndex)
                                }
                            }
                        },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = ConceptPrimary,
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

            if (showPlaylistCoverPreview && effectivePlaylistCoverUrl.isNotBlank()) {
                HdArtworkPreviewDialog(
                    artworkUrl = effectivePlaylistCoverUrl,
                    title = currentPlaylist?.title ?: "精选歌单",
                    subtitle = currentPlaylist?.creatorName.orEmpty(),
                    category = ArtworkDownloadCategory.PLAYLIST_COVER,
                    iconOnlySaveButton = false,
                    accentColor = ConceptPrimary,
                    onDismiss = { showPlaylistCoverPreview = false }
                )
            }

            if (showSharePlaylistSheet && currentPlaylist != null) {
                com.concept.music.ui.components.ShareMusicBottomSheet(
                    playlist = currentPlaylist.copy(
                        coverUrl = effectivePlaylistCoverUrl,
                        trackCount = if (currentPlaylist.trackCount > 0) currentPlaylist.trackCount else songs.size
                    ),
                    onDismiss = { showSharePlaylistSheet = false }
                )
            }
        }
    }

    // 批量添加到歌单弹窗
    if (showBatchAddToPlaylistDialog) {
        val chosenSongs = filteredAndSortedSongs.filter { selectedSongIds.contains(it.selectionKey()) }
        val lead = chosenSongs.firstOrNull()
        if (lead != null) {
            AddToPlaylistDialog(
                song = lead,
                playlists = playlists,
                onSelectPlaylist = { targetPl ->
                    showBatchAddToPlaylistDialog = false
                    viewModel.addSongsToPlaylistBatch(targetPl.listId, chosenSongs, targetPl.title) { _, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                    isMultiSelectMode = false
                    selectedSongIds = emptySet()
                },
                onCreateNewPlaylist = {
                    showBatchAddToPlaylistDialog = false
                    showCreateDialogFromAdd = true
                },
                onDismiss = { showBatchAddToPlaylistDialog = false }
            )
        }
    }

    // 删除自建歌单二次确认
    if (showDeletePlaylistDialog && currentPlaylist != null) {
        AlertDialog(
            onDismissRequest = { showDeletePlaylistDialog = false },
            title = { Text("删除歌单") },
            text = { Text("确定要删除歌单「${currentPlaylist.title}」吗？删除后云端数据不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeletePlaylistDialog = false
                        viewModel.deletePlaylist(currentPlaylist) { success, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            if (success) {
                                onBack()
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePlaylistDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 取消收藏歌单二次确认
    if (showUncollectPlaylistDialog && currentPlaylist != null) {
        AlertDialog(
            onDismissRequest = { showUncollectPlaylistDialog = false },
            title = { Text("取消收藏") },
            text = { Text("确定要取消收藏歌单「${currentPlaylist.title}」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUncollectPlaylistDialog = false
                        viewModel.deletePlaylist(currentPlaylist) { _, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("取消收藏")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUncollectPlaylistDialog = false }) {
                    Text("保留")
                }
            }
        )
    }

    // 单曲更多操作备用 BottomSheet
    if (selectedSongForMenu != null) {
        val targetSong = selectedSongForMenu!!
        ModalBottomSheet(
            onDismissRequest = { selectedSongForMenu = null },
            containerColor = conceptSheetContainerColor(),
            windowInsets = WindowInsets(0, 0, 0, 0),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (targetSong.coverUrl.isNotEmpty()) {
                            AsyncImage(
                                model = targetSong.coverUrl,
                                contentDescription = targetSong.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = targetSong.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = targetSong.artist,
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .softClickable {
                            val s = targetSong
                            selectedSongForMenu = null
                            val appCtx = context.applicationContext
                            if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.STORAGE_EXPORT)) {
                                Toast.makeText(
                                    appCtx,
                                    "已在权限管理中关闭「存储读写与相册导出」权限，请先在设置-权限管理中开启",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                coroutineScope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        HdArtworkSaver.saveArtworkToGallery(
                                            context = appCtx,
                                            rawUrl = s.coverUrl,
                                            category = ArtworkDownloadCategory.SONG_COVER,
                                            title = s.title,
                                            subtitle = s.artist,
                                            songForFallback = s
                                        )
                                    }
                                    val msg = when (res) {
                                        is ArtworkSaveResult.Success -> res.message
                                        is ArtworkSaveResult.PermissionDisabled -> res.message
                                        is ArtworkSaveResult.Failure -> res.message
                                    }
                                    Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = null,
                        tint = ConceptCyanAccent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "保存歌曲封面",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .softClickable {
                            val s = targetSong
                            selectedSongForMenu = null
                            songToAddToPlaylist = s
                        }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "添加到其他歌单",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                if (isCustomCreated && currentPlaylist != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .softClickable {
                                val s = targetSong
                                selectedSongForMenu = null
                                viewModel.removeSongFromPlaylist(currentPlaylist.listId, s) { _, msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "从歌单中移除",
                            style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.error)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // 添加到歌单 Dialog
    if (songToAddToPlaylist != null) {
        val s = songToAddToPlaylist!!
        AddToPlaylistDialog(
            song = s,
            playlists = playlists,
            onSelectPlaylist = { targetPl ->
                songToAddToPlaylist = null
                viewModel.addSongToPlaylist(targetPl.listId, s, targetPl.title) { _, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onCreateNewPlaylist = {
                showCreateDialogFromAdd = true
            },
            onDismiss = { songToAddToPlaylist = null }
        )
    }

    if (showCreateDialogFromAdd) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialogFromAdd = false },
            onConfirmWithCallback = { name, onComplete ->
                val pendingSingle = songToAddToPlaylist
                val pendingBatch = if (isMultiSelectMode) songs.filter { it.selectionKey() in selectedSongIds } else emptyList()
                viewModel.createPlaylist(
                    name = name,
                    onCreatedPlaylist = { createdPl ->
                        if (createdPl != null) {
                            when {
                                pendingSingle != null -> {
                                    songToAddToPlaylist = null
                                    viewModel.addSongToPlaylist(createdPl.listId, pendingSingle, createdPl.title) { _, addMsg ->
                                        Toast.makeText(context, addMsg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                                pendingBatch.isNotEmpty() -> {
                                    isMultiSelectMode = false
                                    selectedSongIds = emptySet()
                                    viewModel.addSongsToPlaylistBatch(createdPl.listId, pendingBatch, createdPl.title) { _, addMsg ->
                                        Toast.makeText(context, addMsg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    },
                    onResult = { success, msg ->
                        if (!success || (pendingSingle == null && pendingBatch.isEmpty())) {
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                        onComplete(success)
                    }
                )
            }
        )
    }

    if (showEditPlaylistDialog && currentPlaylist != null) {
        EditPlaylistMetaDialog(
            playlist = currentPlaylist,
            onDismiss = { showEditPlaylistDialog = false },
            onSave = { newName, newIntro, onDone ->
                viewModel.updatePlaylistMeta(currentPlaylist, newName, newIntro) { ok, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    onDone(ok)
                    if (ok) showEditPlaylistDialog = false
                }
            }
        )
    }

    if (showCloudTrackSortSheet && currentPlaylist != null && songs.size >= 2) {
        PlaylistSongsCloudSortSheet(
            playlistTitle = currentPlaylist.title,
            songs = songs,
            onSaveOrder = { reordered, onDone ->
                viewModel.reorderPlaylistSongs(currentPlaylist, reordered) { ok, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    onDone(ok)
                    if (ok) {
                        sortMode = PlaylistSortMode.DEFAULT
                        showCloudTrackSortSheet = false
                    }
                }
            },
            onDismiss = { showCloudTrackSortSheet = false }
        )
    }
}

@Composable
private fun PlaylistToolbarSection(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    sortMode: PlaylistSortMode,
    onSortModeChange: (PlaylistSortMode) -> Unit,
    isMultiSelectMode: Boolean,
    selectedSongIds: Set<String>,
    filteredAndSortedSongs: List<Song>,
    onToggleSelectAll: () -> Unit,
    onPlaySelected: () -> Unit,
    onBatchAddSelected: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            singleLine = true,
            placeholder = {
                Text(
                    text = "搜索歌单内歌曲 / 歌手 / 专辑...",
                    fontSize = 13.sp
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "搜索",
                    tint = ConceptPrimary,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "清除",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ConceptPrimary,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = conceptFloatingCardColor(),
                unfocusedContainerColor = conceptFloatingCardColor()
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        )

        val sortTabItems = remember {
            PlaylistSortMode.entries.map { mode ->
                ConceptCategoryTabItem(
                    key = mode.name,
                    label = mode.label,
                    accentColor = ConceptPrimary
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Sort,
                    contentDescription = "排序",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "排序",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ConceptSlidingCategoryTabs(
                tabs = sortTabItems,
                selectedKey = sortMode.name,
                onSelectTab = { key ->
                    PlaylistSortMode.entries.find { it.name == key }?.let { onSortModeChange(it) }
                },
                itemSpacing = 16.dp,
                fontSize = 12.5.sp,
                indicatorWidth = 16.dp,
                fillWidth = false
            )
        }

        AnimatedVisibility(visible = isMultiSelectMode) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ConceptPrimary.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = onToggleSelectAll,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (selectedSongIds.size == filteredAndSortedSongs.size) "取消全选" else "全选 (${filteredAndSortedSongs.size})",
                                color = ConceptPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton(
                            onClick = onPlaySelected,
                            enabled = selectedSongIds.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("播放选中", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        FilledTonalButton(
                            onClick = onBatchAddSelected,
                            enabled = selectedSongIds.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("批量加歌单", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistSideHeroPane(
    playlist: Playlist?,
    effectiveCoverUrl: String,
    songCount: Int,
    isExternal: Boolean,
    onPlayAll: () -> Unit,
    onCollectClick: () -> Unit,
    onCoverClick: () -> Unit
) {
    val isLikedPlaylist = playlist?.id == "like" || playlist?.isLiked == true
    val isHistoryPlaylist = playlist?.id == "history"
    val accentColor = when {
        isLikedPlaylist -> ConceptCoralAccent
        isHistoryPlaylist -> ConceptMintAccent
        else -> ConceptPrimary
    }
    val gradientColors = when {
        isLikedPlaylist -> listOf(ConceptCoralAccent, Color(0xFFC2410C))
        isHistoryPlaylist -> listOf(ConceptMintAccent, ConceptPrimaryDark)
        else -> listOf(ConceptPrimary, ConceptPrimaryDark)
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = conceptFloatingCardColor(),
        modifier = Modifier
            .width(320.dp)
            .fillMaxHeight()
            .padding(bottom = 96.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(172.dp)
                    .shadow(14.dp, RoundedCornerShape(26.dp))
                    .clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(colors = gradientColors))
                    .softCardClickable(
                        shape = RoundedCornerShape(26.dp),
                        enabled = effectiveCoverUrl.isNotBlank(),
                        onClick = onCoverClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (effectiveCoverUrl.isNotBlank()) {
                    AsyncImage(
                        model = effectiveCoverUrl,
                        contentDescription = playlist?.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val icon = when {
                        isLikedPlaylist -> Icons.Rounded.Favorite
                        isHistoryPlaylist -> Icons.Rounded.History
                        else -> Icons.Rounded.MusicNote
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = playlist?.title ?: "精选歌单",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (!playlist?.creatorName.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = playlist!!.creatorName,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            if (songCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "共 $songCount 首曲目",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = accentColor,
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = accentColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .softCardClickable(
                        shape = RoundedCornerShape(22.dp),
                        enabled = songCount > 0,
                        onClick = onPlayAll
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "播放全部",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }
            }

            if (isExternal && playlist != null) {
                Spacer(modifier = Modifier.height(10.dp))
                val isFavorite = playlist.isFavorite
                val favScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isFavorite) 1.02f else 1f,
                    animationSpec = com.concept.music.ui.theme.ConceptMotion.expressiveSpring(),
                    label = "sideHeroCollectScale"
                )
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (isFavorite) accentColor.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .graphicsLayer {
                            scaleX = favScale
                            scaleY = favScale
                        }
                        .softCardClickable(
                            shape = RoundedCornerShape(18.dp),
                            onClick = onCollectClick
                        )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkAdd,
                            contentDescription = if (isFavorite) "已收藏（再次点击取消收藏）" else "收藏歌单",
                            tint = if (isFavorite) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isFavorite) "已收藏" else "收藏",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isFavorite) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!playlist?.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = playlist!!.description.trim(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f),
                        lineHeight = 20.sp
                    )
                )
            }
        }
    }
}

@Composable
private fun PlaylistHeaderCard(
    playlist: Playlist?,
    effectiveCoverUrl: String = playlist?.coverUrl.orEmpty(),
    songCount: Int,
    isExternal: Boolean = false,
    onPlayAll: () -> Unit,
    onCollectClick: () -> Unit = {},
    onCoverClick: () -> Unit = {}
) {
    var isDescExpanded by remember { mutableStateOf(false) }
    val isLikedPlaylist = playlist?.id == "like" || playlist?.isLiked == true
    val isHistoryPlaylist = playlist?.id == "history"
    val accentColor = when {
        isLikedPlaylist -> ConceptCoralAccent
        isHistoryPlaylist -> ConceptMintAccent
        else -> ConceptPrimary
    }
    val gradientColors = when {
        isLikedPlaylist -> listOf(ConceptCoralAccent, Color(0xFFC2410C))
        isHistoryPlaylist -> listOf(ConceptMintAccent, ConceptPrimaryDark)
        else -> listOf(ConceptPrimary, ConceptPrimaryDark)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .shadow(8.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(colors = gradientColors))
                    .softCardClickable(
                        shape = RoundedCornerShape(20.dp),
                        enabled = effectiveCoverUrl.isNotBlank(),
                        onClick = onCoverClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (effectiveCoverUrl.isNotBlank()) {
                    AsyncImage(
                        model = effectiveCoverUrl,
                        contentDescription = playlist?.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val icon = when {
                        isLikedPlaylist -> Icons.Rounded.Favorite
                        isHistoryPlaylist -> Icons.Rounded.History
                        else -> Icons.Rounded.MusicNote
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(18.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist?.title ?: "精选歌单",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!playlist?.creatorName.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = playlist!!.creatorName,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.5.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .softClickable(enabled = songCount > 0, onClick = onPlayAll)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(accentColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "播放全部",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (isExternal && playlist != null) {
                        val isFavorite = playlist.isFavorite
                        val favScale by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (isFavorite) 1.04f else 1f,
                            animationSpec = com.concept.music.ui.theme.ConceptMotion.expressiveSpring(),
                            label = "headerCollectScale"
                        )
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = if (isFavorite) {
                                accentColor.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            },
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = favScale
                                    scaleY = favScale
                                }
                                .softCardClickable(
                                    shape = RoundedCornerShape(18.dp),
                                    onClick = onCollectClick
                                )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkAdd,
                                    contentDescription = if (isFavorite) "已收藏（再次点击取消收藏）" else "收藏歌单",
                                    tint = if (isFavorite) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.5.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (isFavorite) "已收藏" else "收藏",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isFavorite) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!playlist?.description.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .softClickable { isDescExpanded = !isDescExpanded }
            ) {
                Text(
                    text = playlist!!.description.trim(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f),
                        lineHeight = 19.sp
                    ),
                    maxLines = if (isDescExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (playlist.description.length > 45) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = if (isDescExpanded) "收起简介 ▲" else "展开完整歌单简介 ▼",
                        color = accentColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        )
    }
}

@Composable
private fun ListenRankSectionBody(
    rankType: Int,
    onSelectRankType: (Int) -> Unit,
    items: List<ListenRankItem>,
    loading: Boolean,
    currentSong: Song?,
    onSongClick: (Song, List<Song>) -> Unit,
    onSongMoreClick: (Song) -> Unit,
    maxWidth: Dp,
    modifier: Modifier = Modifier
) {
    val rankSongs = remember(items) { items.map { it.song } }
    val curId = currentSong?.id
    val curHash = currentSong?.hash.orEmpty()
    val cardBg = conceptElevatedSurfaceColor()

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .then(if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier)
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "近一周", 1 to "累计总榜").forEach { (type, label) ->
                        val selected = rankType == type
                        Surface(
                            shape = RoundedCornerShape(11.dp),
                            color = if (selected) ConceptCyanAccent.copy(alpha = 0.16f) else cardBg,
                            modifier = Modifier.softCardClickable(shape = RoundedCornerShape(11.dp)) {
                                onSelectRankType(type)
                            }
                        ) {
                            Text(
                                text = label,
                                color = if (selected) ConceptCyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
                if (rankSongs.isNotEmpty()) {
                    TextButton(
                        onClick = { onSongClick(rankSongs.first(), rankSongs) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = ConceptPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("播放全部", color = ConceptPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (loading && items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ConceptPrimary, strokeWidth = 2.5.dp)
                }
            } else if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (rankType == 0) "近一周暂无云端听歌排行数据" else "暂无累计听歌排行数据（请确认已登录酷狗账号）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.5.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(
                        items = items,
                        key = { idx, item -> "rank_${rankType}_${item.rank}_${item.song.hash.ifBlank { item.song.id }}_$idx" }
                    ) { _, item ->
                        val s = item.song
                        val isPlaying = curId != null && (
                            curId == s.id || (curHash.isNotEmpty() && curHash.equals(s.hash, ignoreCase = true))
                            )
                        val rankColor = when (item.rank) {
                            1 -> VipGold
                            2 -> ConceptCyanAccent
                            3 -> ConceptCoralAccent
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .softClickable { onSongClick(s, rankSongs) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${item.rank}",
                                color = rankColor,
                                fontSize = if (item.rank <= 3) 16.sp else 13.5.sp,
                                fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(30.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                SongItemRow(
                                    song = s,
                                    isPlaying = isPlaying,
                                    onClick = { onSongClick(s, rankSongs) },
                                    onMoreClick = { onSongMoreClick(s) }
                                )
                            }
                            if (item.playCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = ConceptPrimary.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = "${item.playCount} 次",
                                        color = ConceptPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(110.dp)) }
                }
            }
        }
    }
}

@Composable
private fun EditPlaylistMetaDialog(
    playlist: Playlist,
    onDismiss: () -> Unit,
    onSave: (String, String, (Boolean) -> Unit) -> Unit
) {
    var name by remember(playlist) { mutableStateOf(playlist.title) }
    var intro by remember(playlist) { mutableStateOf(playlist.description) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("编辑歌单信息", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    singleLine = true,
                    enabled = !saving,
                    label = { Text("歌单名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = intro,
                    onValueChange = { if (it.length <= 300) intro = it },
                    minLines = 3,
                    maxLines = 6,
                    enabled = !saving,
                    label = { Text("歌单简介") },
                    placeholder = { Text("写一句关于这个歌单的心情或说明...") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && name.trim().isNotEmpty(),
                onClick = {
                    saving = true
                    onSave(name.trim(), intro.trim()) { _ -> saving = false }
                }
            ) {
                Text(if (saving) "保存中..." else "保存修改", color = ConceptPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun PlaylistSongsCloudSortSheet(
    playlistTitle: String,
    songs: List<Song>,
    onSaveOrder: (List<Song>, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val sheetBg = conceptSheetContainerColor()
    val cardBg = conceptElevatedSurfaceColor()
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant
    var workingSongs by remember(songs) { mutableStateOf(songs) }
    var saving by remember { mutableStateOf(false) }

    ConceptAnimatedOverlaySheet(
        onDismiss = onDismiss,
        isLandscape = isLandscape,
        zIndex = 88f
    ) {
        Surface(
            shape = if (isLandscape) RoundedCornerShape(24.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = sheetBg,
            contentColor = textPrimary,
            shadowElevation = 24.dp,
            modifier = Modifier
                .then(
                    if (isLandscape) {
                        Modifier
                            .padding(horizontal = 24.dp, vertical = 14.dp)
                            .widthIn(max = 580.dp)
                            .fillMaxWidth()
                            .fillMaxHeight(0.86f)
                    } else {
                        Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .heightIn(min = 360.dp, max = 600.dp)
                    }
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("调整歌单曲目顺序", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = textPrimary)
                        Text(playlistTitle, fontSize = 11.5.sp, color = textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = !saving && workingSongs.isNotEmpty(),
                            onClick = {
                                saving = true
                                onSaveOrder(workingSongs) { _ -> saving = false }
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(if (saving) "同步中..." else "同步云端", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = requestDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = textSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    itemsIndexed(
                        items = workingSongs,
                        key = { idx, s -> "sort_track_${s.id}_${s.hash}_$idx" }
                    ) { idx, song ->
                        Surface(
                            shape = RoundedCornerShape(13.dp),
                            color = cardBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${idx + 1}",
                                    color = ConceptPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(26.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        color = textPrimary,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = song.artist,
                                        color = textSecondary,
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    enabled = idx > 0,
                                    onClick = {
                                        val next = workingSongs.toMutableList()
                                        val item = next.removeAt(idx)
                                        next.add(0, item)
                                        workingSongs = next
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(Icons.Rounded.VerticalAlignTop, contentDescription = "置顶", modifier = Modifier.size(17.dp))
                                }
                                IconButton(
                                    enabled = idx > 0,
                                    onClick = {
                                        val next = workingSongs.toMutableList()
                                        val item = next.removeAt(idx)
                                        next.add(idx - 1, item)
                                        workingSongs = next
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "上移", modifier = Modifier.size(19.dp))
                                }
                                IconButton(
                                    enabled = idx < workingSongs.lastIndex,
                                    onClick = {
                                        val next = workingSongs.toMutableList()
                                        val item = next.removeAt(idx)
                                        next.add(idx + 1, item)
                                        workingSongs = next
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "下移", modifier = Modifier.size(19.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
