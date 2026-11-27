package com.concept.music.ui.library

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.concept.music.data.model.Playlist
import com.concept.music.data.model.Song
import com.concept.music.data.repository.PlaylistCoverOverrideRepository
import com.concept.music.service.AppPermissionManager
import com.concept.music.service.AppPermissionType
import com.concept.music.ui.components.AddToPlaylistDialog
import com.concept.music.ui.components.ArtworkDownloadCategory
import com.concept.music.ui.components.ArtworkSaveResult
import com.concept.music.ui.components.BatchSongDownloadDialog
import com.concept.music.ui.components.ConceptAdaptiveBottomSheetContainer
import com.concept.music.ui.components.ConceptListDragReorderState
import com.concept.music.ui.components.CoverSquareCropDialog
import com.concept.music.ui.components.CreatePlaylistDialog
import com.concept.music.ui.components.FloatingScrollToTopButton
import com.concept.music.ui.components.HdArtworkPreviewDialog
import com.concept.music.ui.components.HdArtworkSaver
import com.concept.music.ui.components.SongItemRow
import com.concept.music.ui.components.rememberProgressiveSongList
import com.concept.music.ui.components.smoothFastScrollToItem
import com.concept.music.ui.theme.ConceptCoralAccent
import com.concept.music.ui.theme.ConceptCyanAccent
import com.concept.music.ui.theme.ConceptFluidRefreshIcon
import com.concept.music.ui.theme.ConceptMintAccent
import com.concept.music.ui.theme.ConceptMotion
import com.concept.music.ui.theme.ConceptPrimary
import com.concept.music.ui.theme.ConceptPrimaryDark
import com.concept.music.ui.theme.ConceptStageBackground
import com.concept.music.ui.theme.LocalConceptAdaptiveInfo
import com.concept.music.ui.theme.conceptCyanAccentColor
import com.concept.music.ui.theme.conceptElevatedSurfaceColor
import com.concept.music.ui.theme.conceptFloatingCardColor
import com.concept.music.ui.theme.conceptSheetContainerColor
import com.concept.music.ui.theme.rememberCategorySwitchGraphicModifier
import com.concept.music.ui.theme.softCardClickable
import com.concept.music.ui.theme.softClickable
import com.concept.music.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/**
 * 歌单内排序模式（6 项竖向弹窗展示，无门槛直接标配；「我喜欢」与自建歌单云端自定义排序保存后直接作为「默认排序」呈现）
 */
private fun Song.selectionKey(): String = "${id}_${hash}"

enum class PlaylistSortMode(val label: String) {
    DEFAULT("默认排序"),
    ADDED_NEWEST("从新到旧"),
    ADDED_OLDEST("从旧到新"),
    TITLE_AZ("歌名 A-Z"),
    ARTIST_AZ("歌手名"),
    DURATION_DESC("时长最长")
}

private val LEADING_SORT_PUNCTUATION_REGEX = Regex("""^[\s《》〈〉「」『』【】()（）\[\]"'“”‘’·\-_:：]+""")

private fun normalizeSortText(raw: String): String {
    val trimmed = raw.trim()
    val stripped = LEADING_SORT_PUNCTUATION_REGEX.replace(trimmed, "").trim()
    return stripped.ifBlank { trimmed }
}

internal fun sortPlaylistSongs(songs: List<Song>, sortMode: PlaylistSortMode): List<Song> {
    if (songs.size <= 1) return songs
    return when (sortMode) {
        PlaylistSortMode.DEFAULT -> songs
        PlaylistSortMode.ADDED_NEWEST -> {
            val hasTimeOrFileId = songs.any { it.addTimeSec > 0L || it.fileId > 0L }
            if (!hasTimeOrFileId) {
                songs
            } else {
                songs.withIndex()
                    .sortedWith(
                        compareByDescending<IndexedValue<Song>> { it.value.addTimeSec }
                            .thenByDescending { it.value.fileId }
                            .thenBy { it.index }
                    )
                    .map { it.value }
            }
        }
        PlaylistSortMode.ADDED_OLDEST -> {
            val hasTimeOrFileId = songs.any { it.addTimeSec > 0L || it.fileId > 0L }
            if (!hasTimeOrFileId) {
                songs.asReversed()
            } else {
                songs.withIndex()
                    .sortedWith(
                        compareBy<IndexedValue<Song>> {
                            if (it.value.addTimeSec > 0L) it.value.addTimeSec else Long.MAX_VALUE
                        }
                            .thenBy {
                                if (it.value.fileId > 0L) it.value.fileId else Long.MAX_VALUE
                            }
                            .thenByDescending { it.index }
                    )
                    .map { it.value }
            }
        }
        PlaylistSortMode.TITLE_AZ -> {
            val collator = Collator.getInstance(Locale.CHINA)
            songs.sortedWith { a, b ->
                val cmp = collator.compare(normalizeSortText(a.title), normalizeSortText(b.title))
                if (cmp != 0) cmp else collator.compare(normalizeSortText(a.artist), normalizeSortText(b.artist))
            }
        }
        PlaylistSortMode.ARTIST_AZ -> {
            val collator = Collator.getInstance(Locale.CHINA)
            songs.sortedWith { a, b ->
                val cmp = collator.compare(normalizeSortText(a.artist), normalizeSortText(b.artist))
                if (cmp != 0) cmp else collator.compare(normalizeSortText(a.title), normalizeSortText(b.title))
            }
        }
        PlaylistSortMode.DURATION_DESC -> songs.sortedByDescending { it.durationMs }
    }
}

private data class SortableTrackEntry(
    val stableKey: String,
    val song: Song
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    viewModel: LibraryViewModel,
    playlistId: String,
    onBack: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onAddNextPlay: ((Song) -> Unit)? = null,
    currentSong: Song? = null,
    onSongMoreClick: ((Song, (() -> Unit)?) -> Unit)? = null,
    onSharePlaylist: ((Playlist) -> Unit)? = null,
    onOpenPlaylistComments: ((Playlist) -> Unit)? = null,
    onOpenDownloadSettings: (() -> Unit)? = null,
    onSubSheetOpenChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val adaptiveInfo = LocalConceptAdaptiveInfo.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val playlists by viewModel.playlists.collectAsState()
    val customCoverOverrides by viewModel.customCoverOverrides.collectAsState()
    val permissionState by AppPermissionManager.state.collectAsState()
    val songs by viewModel.librarySongs.collectAsState()
    val loading by viewModel.libraryLoading.collectAsState()
    val error by viewModel.libraryError.collectAsState()

    val isHistory = playlistId == "history"
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(PlaylistSortMode.DEFAULT) }
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedSongIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showBatchRemoveDialog by remember { mutableStateOf(false) }
    var showBatchDownloadDialog by remember { mutableStateOf(false) }
    var isBatchRemoving by remember { mutableStateOf(false) }
    var showPlaylistCoverPreview by remember { mutableStateOf(false) }
    var pendingCropCoverUri by remember { mutableStateOf<Uri?>(null) }
    var isUpdatingPlaylistCover by remember { mutableStateOf(false) }
    var showEditPlaylistDialog by remember { mutableStateOf(false) }
    var showManageSheet by remember { mutableStateOf(false) }
    var isSubSheetDismissing by remember { mutableStateOf(false) }

    val fallbackCoverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingCropCoverUri = uri
        }
    }
    val photoCoverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pendingCropCoverUri = uri
        }
    }
    val launchCoverPicker: () -> Unit = {
        runCatching {
            photoCoverPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }.onFailure {
            runCatching { fallbackCoverPickerLauncher.launch("image/*") }
                .onFailure {
                    Toast.makeText(context, "无法打开系统相册，请确认系统图库可用", Toast.LENGTH_SHORT).show()
                }
        }
    }

    // 页内直接拖拽排序状态（点击外置「排序」后直接在歌单页内将列表序号变为可拖动手柄，并出现取消/完成按钮）
    var isInlineReorderMode by remember(playlistId) { mutableStateOf(false) }
    var initialReorderEntries by remember(playlistId) { mutableStateOf<List<SortableTrackEntry>>(emptyList()) }
    var workingReorderEntries by remember(playlistId) { mutableStateOf<List<SortableTrackEntry>>(emptyList()) }
    var savingInlineReorder by remember { mutableStateOf(false) }

    val hasInlineOrderChanges = remember(workingReorderEntries, initialReorderEntries) {
        workingReorderEntries.map { it.stableKey } != initialReorderEntries.map { it.stableKey }
    }

    fun moveInlineEntry(fromIdx: Int, toIdx: Int) {
        if (fromIdx == toIdx || fromIdx !in workingReorderEntries.indices || toIdx !in workingReorderEntries.indices) return
        val next = workingReorderEntries.toMutableList()
        val moved = next.removeAt(fromIdx)
        next.add(toIdx, moved)
        workingReorderEntries = next
    }

    fun enterInlineReorderMode() {
        if (songs.size < 2) {
            Toast.makeText(context, "至少包含 2 首歌曲才能调整顺序", Toast.LENGTH_SHORT).show()
            return
        }
        isMultiSelectMode = false
        selectedSongIds = emptySet()
        searchQuery = ""
        sortMode = PlaylistSortMode.DEFAULT
        val snapshot = songs.mapIndexed { idx, s -> SortableTrackEntry("${idx}_${s.id}_${s.hash}", s) }
        initialReorderEntries = snapshot
        workingReorderEntries = snapshot
        isInlineReorderMode = true
    }

    fun cancelInlineReorderMode() {
        if (savingInlineReorder) return
        workingReorderEntries = initialReorderEntries
        isInlineReorderMode = false
    }

    BackHandler(enabled = isInlineReorderMode) {
        cancelInlineReorderMode()
    }
    BackHandler(enabled = isMultiSelectMode && !isInlineReorderMode) {
        isMultiSelectMode = false
        selectedSongIds = emptySet()
    }

    // 排序模式下已移除中间横条，双栏右侧列表头为 0 项，单栏列表头仅含 pl_header (1 项)
    val headerCountInList = if (adaptiveInfo.useTwoPaneDetailLayout) 0 else 1
    val latestHeaderCount by rememberUpdatedState(headerCountInList)
    val inlineReorderState = remember(listState, coroutineScope) {
        ConceptListDragReorderState(
            listState = listState,
            scope = coroutineScope,
            defaultRowHeightPx = with(density) { 64.dp.toPx() },
            itemCountProvider = { workingReorderEntries.size },
            onCommitMove = { from, to -> moveInlineEntry(from, to) },
            headerItemCountProvider = { latestHeaderCount },
            onTargetIndexChanged = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        )
    }

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
        sortPlaylistSongs(filtered, sortMode)
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

    val isLikedPlaylist = remember(currentPlaylist, playlistId) {
        playlistId == "like" || currentPlaylist?.isLiked == true
    }

    val isCustomCreated = remember(currentPlaylist, playlistId, isHistory) {
        currentPlaylist != null && currentPlaylist.isCreatedByMe && !currentPlaylist.isLiked && playlistId != "like" && !isHistory
    }

    val canCustomSortTracks = remember(isCustomCreated, isLikedPlaylist, isHistory) {
        (isCustomCreated || isLikedPlaylist) && !isHistory
    }

    val canEditCover = remember(isCustomCreated, isLikedPlaylist, isHistory) {
        (isCustomCreated || isLikedPlaylist) && !isHistory
    }

    val canBatchDownload = permissionState.musicDownloadEnabled && !isHistory

    val isExternalPlaylist = remember(currentPlaylist, playlistId, isHistory) {
        currentPlaylist != null && !currentPlaylist.isCreatedByMe && playlistId != "like" && !isHistory
    }

    val effectivePlaylistCoverUrl = remember(currentPlaylist, playlistId, songs, customCoverOverrides) {
        viewModel.resolveEffectivePlaylistCover(
            playlist = currentPlaylist,
            fallbackPlaylistId = playlistId,
            fallbackSongCover = songs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty(),
            overrides = customCoverOverrides
        )
    }

    val commitInlineReorder: () -> Unit = {
        val targetPl = currentPlaylist
        if (targetPl == null || savingInlineReorder) {
            // ignore
        } else if (!hasInlineOrderChanges) {
            sortMode = PlaylistSortMode.DEFAULT
            isInlineReorderMode = false
            Toast.makeText(context, "顺序未变动，已保持默认顺序", Toast.LENGTH_SHORT).show()
        } else {
            savingInlineReorder = true
            val reorderedSongs = workingReorderEntries.map { it.song }
            viewModel.reorderPlaylistSongs(targetPl, reorderedSongs) { ok, msg ->
                savingInlineReorder = false
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                if (ok) {
                    sortMode = PlaylistSortMode.DEFAULT
                    isInlineReorderMode = false
                }
            }
        }
    }

    var showDeletePlaylistDialog by remember { mutableStateOf(false) }
    var showUncollectPlaylistDialog by remember { mutableStateOf(false) }
    var showSharePlaylistSheet by remember { mutableStateOf(false) }
    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }
    var songToAddToPlaylist by remember { mutableStateOf<Song?>(null) }
    var showCreateDialogFromAdd by remember { mutableStateOf(false) }

    val hasAnySubSheet = showManageSheet || showSharePlaylistSheet
    LaunchedEffect(hasAnySubSheet, isSubSheetDismissing) {
        if (!hasAnySubSheet) isSubSheetDismissing = false
        onSubSheetOpenChanged(hasAnySubSheet && !isSubSheetDismissing)
    }
    DisposableEffect(Unit) {
        onDispose { onSubSheetOpenChanged(false) }
    }

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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                when {
                                    isInlineReorderMode -> cancelInlineReorderMode()
                                    isMultiSelectMode -> {
                                        isMultiSelectMode = false
                                        selectedSongIds = emptySet()
                                    }
                                    else -> onBack()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isMultiSelectMode) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = when {
                                    isInlineReorderMode -> "退出排序"
                                    isMultiSelectMode -> "退出多选"
                                    else -> "返回"
                                }
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    isInlineReorderMode -> "自定义默认排序"
                                    isMultiSelectMode -> "已选 ${selectedSongIds.size} 首"
                                    else -> currentPlaylist?.title ?: "歌单详情"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isInlineReorderMode) {
                                Text(
                                    text = if (hasInlineOrderChanges) "顺序已修改 · 点击完成同步云端" else "按住左侧手柄上下拖动 · 共 ${workingReorderEntries.size} 首",
                                    fontSize = 11.sp,
                                    color = if (hasInlineOrderChanges) ConceptPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else if (isMultiSelectMode) {
                                Text(
                                    text = "共 ${filteredAndSortedSongs.size} 首 · 点击歌曲行即可勾选",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (isInlineReorderMode) {
                            TextButton(
                                enabled = !savingInlineReorder,
                                onClick = { cancelInlineReorderMode() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "取消",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.5.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Button(
                                enabled = !savingInlineReorder,
                                onClick = commitInlineReorder,
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ConceptPrimary,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .height(34.dp)
                            ) {
                                if (savingInlineReorder) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("同步中", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Text("完成", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        } else if (isMultiSelectMode) {
                            val allSelected = filteredAndSortedSongs.isNotEmpty() && selectedSongIds.size == filteredAndSortedSongs.size
                            IconButton(
                                onClick = {
                                    selectedSongIds = if (allSelected) {
                                        emptySet()
                                    } else {
                                        filteredAndSortedSongs.map { it.selectionKey() }.toSet()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (allSelected) Icons.Rounded.RemoveDone else Icons.Rounded.DoneAll,
                                    contentDescription = if (allSelected) "取消全选" else "全选",
                                    tint = ConceptPrimary
                                )
                            }
                        } else {
                            if (songs.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        isMultiSelectMode = true
                                        selectedSongIds = emptySet()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Checklist,
                                        contentDescription = "批量多选",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (isHistory && songs.isNotEmpty()) {
                                IconButton(onClick = viewModel::clearHistory) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "清空历史",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                                    )
                                }
                            }

                            if (!isHistory && playlistId != "like" && currentPlaylist != null && onOpenPlaylistComments != null) {
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

                            if (isHistory) {
                                var plRefreshSpin by remember { mutableIntStateOf(0) }
                                IconButton(
                                    onClick = {
                                        plRefreshSpin += 1
                                        viewModel.refreshCurrentPlaylistOnly("history")
                                    }
                                ) {
                                    ConceptFluidRefreshIcon(
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        isRefreshing = loading,
                                        spinTrigger = plRefreshSpin,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            } else if (currentPlaylist != null) {
                                IconButton(
                                    onClick = {
                                        isSubSheetDismissing = false
                                        showManageSheet = true
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreVert,
                                        contentDescription = "更多管理",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(21.dp)
                                    )
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

                if (adaptiveInfo.useTwoPaneDetailLayout) {
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
                            isCustomCreated = isCustomCreated,
                            canEditCover = canEditCover,
                            isInlineReorderMode = isInlineReorderMode,
                            onPlayAll = {
                                if (filteredAndSortedSongs.isNotEmpty()) {
                                    onSongClick(filteredAndSortedSongs.first(), filteredAndSortedSongs)
                                }
                            },
                            onCollectClick = onCollectToggle,
                            onEditMetaClick = { showEditPlaylistDialog = true },
                            onCoverClick = {
                                if (effectivePlaylistCoverUrl.isNotBlank() || canEditCover) {
                                    showPlaylistCoverPreview = true
                                }
                            }
                        )

                        LazyColumn(
                            state = listState,
                            userScrollEnabled = !(isInlineReorderMode && inlineReorderState.isDragging),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentPadding = PaddingValues(bottom = if (isMultiSelectMode) 172.dp else 128.dp)
                        ) {
                            if (!isInlineReorderMode) {
                                item(key = "pl_toolbar", contentType = "pl_toolbar") {
                                    PlaylistToolbarSection(
                                        searchQuery = searchQuery,
                                        onSearchQueryChange = { searchQuery = it },
                                        sortMode = sortMode,
                                        onSortModeChange = { sortMode = it },
                                        totalSongCount = songs.size,
                                        filteredSongCount = filteredAndSortedSongs.size,
                                        canCustomSortTracks = canCustomSortTracks,
                                        onEnterCustomSort = { enterInlineReorderMode() }
                                    )
                                }
                            }

                            if (isInlineReorderMode) {
                                itemsIndexed(
                                    items = workingReorderEntries,
                                    key = { _, entry -> entry.stableKey },
                                    contentType = { _, _ -> "reorder_song_row" }
                                ) { idx, entry ->
                                    InlineDraggableSongRow(
                                        idx = idx,
                                        totalCount = workingReorderEntries.size,
                                        entry = entry,
                                        saving = savingInlineReorder,
                                        reorderState = inlineReorderState,
                                        onResolveLiveIndex = { key -> workingReorderEntries.indexOfFirst { it.stableKey == key } },
                                        onMoveItem = { from, to -> moveInlineEntry(from, to) },
                                        onHapticStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                                    )
                                }
                            } else {
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
                                                    viewModel.removeSongFromPlaylist(currentPlaylist.resolveEffectiveListId(), song) { _, msg ->
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
                                        val isSelected = selectedSongIds.contains(songKey)
                                        val toggleSongSelection = {
                                            selectedSongIds = if (isSelected) selectedSongIds - songKey else selectedSongIds + songKey
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 4.dp)
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(if (isSelected) ConceptPrimary.copy(alpha = 0.11f) else Color.Transparent)
                                                .then(sortSwitchAnimMod),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            IconButton(
                                                onClick = toggleSongSelection,
                                                modifier = Modifier.size(40.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                                    contentDescription = if (isSelected) "取消选中" else "选中",
                                                    tint = if (isSelected) ConceptPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                            Box(modifier = Modifier.weight(1f)) {
                                                SongItemRow(
                                                    song = song,
                                                    orderIndex = null,
                                                    isPlaying = isCurrentTrack,
                                                    onClick = toggleSongSelection,
                                                    onMoreClick = null
                                                )
                                            }
                                        }
                                    } else {
                                        val songKey = song.selectionKey()
                                        Box(modifier = sortSwitchAnimMod) {
                                            SongItemRow(
                                                song = song,
                                                orderIndex = index + 1,
                                                isPlaying = isCurrentTrack,
                                                onClick = {
                                                    onSongClick(song, latestFilteredAndSortedSongs)
                                                },
                                                onLongClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    isMultiSelectMode = true
                                                    selectedSongIds = setOf(songKey)
                                                },
                                                onMoreClick = handleMore
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // 手机竖屏或平板竖屏/分屏单栏模式
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        LazyColumn(
                            state = listState,
                            userScrollEnabled = !(isInlineReorderMode && inlineReorderState.isDragging),
                            modifier = Modifier
                                .then(
                                    if (adaptiveInfo.singleColumnMaxWidth != Dp.Unspecified) {
                                        Modifier.widthIn(max = adaptiveInfo.singleColumnMaxWidth)
                                    } else Modifier
                                )
                                .fillMaxSize(),
                            contentPadding = PaddingValues(bottom = if (isMultiSelectMode) 176.dp else 128.dp)
                        ) {
                            // 1. 歌单头部 Header（已将自定义排序统一收纳至右侧排序小窗，头部仅保留清爽的核心操作）
                            item(key = "pl_header", contentType = "pl_header") {
                                PlaylistHeaderCard(
                                    playlist = currentPlaylist,
                                    effectiveCoverUrl = effectivePlaylistCoverUrl,
                                    songCount = songs.size,
                                    isExternal = isExternalPlaylist,
                                    isCustomCreated = isCustomCreated,
                                    canEditCover = canEditCover,
                                    isInlineReorderMode = isInlineReorderMode,
                                    onPlayAll = {
                                        if (filteredAndSortedSongs.isNotEmpty()) {
                                            onSongClick(filteredAndSortedSongs.first(), filteredAndSortedSongs)
                                        }
                                    },
                                    onCollectClick = onCollectToggle,
                                    onEditMetaClick = { showEditPlaylistDialog = true },
                                    onCoverClick = {
                                        if (effectivePlaylistCoverUrl.isNotBlank() || canEditCover) {
                                            showPlaylistCoverPreview = true
                                        }
                                    }
                                )
                            }

                            // 2. 歌单内搜索 + 右侧无框排序小窗入口（排序模式下直接进入可拖动列表，不再显示冗余横条）
                            if (!isInlineReorderMode) {
                                item(key = "pl_toolbar", contentType = "pl_toolbar") {
                                    PlaylistToolbarSection(
                                        searchQuery = searchQuery,
                                        onSearchQueryChange = { searchQuery = it },
                                        sortMode = sortMode,
                                        onSortModeChange = { sortMode = it },
                                        totalSongCount = songs.size,
                                        filteredSongCount = filteredAndSortedSongs.size,
                                        canCustomSortTracks = canCustomSortTracks,
                                        onEnterCustomSort = { enterInlineReorderMode() }
                                    )
                                }
                            }

                            if (isInlineReorderMode) {
                                itemsIndexed(
                                    items = workingReorderEntries,
                                    key = { _, entry -> entry.stableKey },
                                    contentType = { _, _ -> "reorder_song_row" }
                                ) { idx, entry ->
                                    InlineDraggableSongRow(
                                        idx = idx,
                                        totalCount = workingReorderEntries.size,
                                        entry = entry,
                                        saving = savingInlineReorder,
                                        reorderState = inlineReorderState,
                                        onResolveLiveIndex = { key -> workingReorderEntries.indexOfFirst { it.stableKey == key } },
                                        onMoveItem = { from, to -> moveInlineEntry(from, to) },
                                        onHapticStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                                    )
                                }
                            } else {
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
                                                    viewModel.removeSongFromPlaylist(currentPlaylist.resolveEffectiveListId(), song) { _, msg ->
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
                                        val isSelected = selectedSongIds.contains(songKey)
                                        val toggleSongSelection = {
                                            selectedSongIds = if (isSelected) selectedSongIds - songKey else selectedSongIds + songKey
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 4.dp)
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(if (isSelected) ConceptPrimary.copy(alpha = 0.11f) else Color.Transparent)
                                                .then(sortSwitchAnimMod),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            IconButton(
                                                onClick = toggleSongSelection,
                                                modifier = Modifier.size(40.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                                    contentDescription = if (isSelected) "取消选中" else "选中",
                                                    tint = if (isSelected) ConceptPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                            Box(modifier = Modifier.weight(1f)) {
                                                SongItemRow(
                                                    song = song,
                                                    orderIndex = null,
                                                    isPlaying = isCurrentTrack,
                                                    onClick = toggleSongSelection,
                                                    onMoreClick = null
                                                )
                                            }
                                        }
                                    } else {
                                        val songKey = song.selectionKey()
                                        Box(modifier = sortSwitchAnimMod) {
                                            SongItemRow(
                                                song = song,
                                                orderIndex = index + 1,
                                                isPlaying = isCurrentTrack,
                                                onClick = {
                                                    onSongClick(song, latestFilteredAndSortedSongs)
                                                },
                                                onLongClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    isMultiSelectMode = true
                                                    selectedSongIds = setOf(songKey)
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
            }

            // 右下角悬浮按钮组（排序与多选态自动让位隐藏）
            val showScrollToTop by remember(filteredAndSortedSongs.size, isInlineReorderMode, isMultiSelectMode) {
                derivedStateOf {
                    !isInlineReorderMode && !isMultiSelectMode && (
                        listState.firstVisibleItemIndex > 2 ||
                            (listState.firstVisibleItemIndex == 2 && listState.firstVisibleItemScrollOffset > 100)
                        )
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
                    visible = !isInlineReorderMode && !isMultiSelectMode && playingSongIndex >= 0,
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

            if (showPlaylistCoverPreview && (effectivePlaylistCoverUrl.isNotBlank() || canEditCover)) {
                val previewAccent = if (isLikedPlaylist) ConceptCoralAccent else ConceptPrimary
                val previewGradient = if (isLikedPlaylist) {
                    listOf(ConceptCoralAccent, Color(0xFFC2410C))
                } else {
                    listOf(ConceptPrimary, ConceptPrimaryDark)
                }
                HdArtworkPreviewDialog(
                    artworkUrl = effectivePlaylistCoverUrl,
                    title = currentPlaylist?.title ?: if (isLikedPlaylist) "我喜欢的音乐" else "精选歌单",
                    subtitle = currentPlaylist?.creatorName.orEmpty(),
                    category = ArtworkDownloadCategory.PLAYLIST_COVER,
                    iconOnlySaveButton = false,
                    accentColor = previewAccent,
                    canChangeCover = canEditCover,
                    isUpdatingCover = isUpdatingPlaylistCover,
                    onChangeCoverClick = launchCoverPicker,
                    placeholderIcon = if (isLikedPlaylist) Icons.Rounded.Favorite else Icons.Rounded.MusicNote,
                    placeholderGradientColors = previewGradient,
                    onDismiss = { if (!isUpdatingPlaylistCover) showPlaylistCoverPreview = false }
                )
            }

            if (pendingCropCoverUri != null) {
                val targetUri = pendingCropCoverUri!!
                val cropAccent = if (isLikedPlaylist) ConceptCoralAccent else ConceptPrimary
                CoverSquareCropDialog(
                    imageUri = targetUri,
                    title = if (isLikedPlaylist) "裁剪「我喜欢」封面" else "裁剪歌单封面",
                    accentColor = cropAccent,
                    onDismiss = { pendingCropCoverUri = null },
                    onConfirmCroppedJpeg = { croppedJpeg ->
                        pendingCropCoverUri = null
                        isUpdatingPlaylistCover = true
                        viewModel.updatePlaylistCover(
                            playlist = currentPlaylist,
                            fallbackPlaylistId = playlistId,
                            croppedJpegBytes = croppedJpeg
                        ) { _, msg ->
                            isUpdatingPlaylistCover = false
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }

            if (showSharePlaylistSheet && currentPlaylist != null) {
                com.concept.music.ui.components.ShareMusicBottomSheet(
                    playlist = currentPlaylist.copy(
                        coverUrl = effectivePlaylistCoverUrl,
                        trackCount = if (currentPlaylist.trackCount > 0) currentPlaylist.trackCount else songs.size
                    ),
                    onDismiss = {
                        showSharePlaylistSheet = false
                        isSubSheetDismissing = false
                    }
                )
            }

            if (showManageSheet && currentPlaylist != null) {
                PlaylistManageBottomSheet(
                    playlist = currentPlaylist,
                    isCustomCreated = isCustomCreated,
                    canShare = !isHistory,
                    isRefreshing = loading,
                    onShare = {
                        showManageSheet = false
                        isSubSheetDismissing = false
                        val shareTarget = currentPlaylist.copy(
                            coverUrl = effectivePlaylistCoverUrl,
                            trackCount = if (currentPlaylist.trackCount > 0) currentPlaylist.trackCount else songs.size
                        )
                        if (onSharePlaylist != null) {
                            onSharePlaylist(shareTarget)
                        } else {
                            showSharePlaylistSheet = true
                        }
                    },
                    onRefresh = {
                        showManageSheet = false
                        isSubSheetDismissing = false
                        viewModel.refreshCurrentPlaylistOnly(playlistId)
                    },
                    onDeletePlaylist = {
                        showManageSheet = false
                        isSubSheetDismissing = false
                        showDeletePlaylistDialog = true
                    },
                    onDismissStart = { isSubSheetDismissing = true },
                    onDismiss = {
                        showManageSheet = false
                        isSubSheetDismissing = false
                    }
                )
            }

            // 底部常驻悬浮多选操作条（随滚动常驻可见，自动避让迷你播放栏，一步完成播放/下一首/加歌单/移出）
            AnimatedVisibility(
                visible = isMultiSelectMode && !isInlineReorderMode,
                enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 90.dp)
            ) {
                val chosenSongs = remember(filteredAndSortedSongs, selectedSongIds) {
                    filteredAndSortedSongs.filter { selectedSongIds.contains(it.selectionKey()) }
                }
                val hasSelection = chosenSongs.isNotEmpty()
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = conceptSheetContainerColor().copy(alpha = 0.96f),
                    shadowElevation = 14.dp,
                    modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MultiSelectDockAction(
                            icon = Icons.Rounded.PlayArrow,
                            label = "播放",
                            tint = ConceptPrimary,
                            enabled = hasSelection,
                            onClick = {
                                if (chosenSongs.isNotEmpty()) {
                                    onSongClick(chosenSongs.first(), chosenSongs)
                                    isMultiSelectMode = false
                                    selectedSongIds = emptySet()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (onAddNextPlay != null) {
                            MultiSelectDockAction(
                                icon = Icons.Rounded.QueuePlayNext,
                                label = "下一首",
                                tint = ConceptMintAccent,
                                enabled = hasSelection,
                                onClick = {
                                    if (chosenSongs.isNotEmpty()) {
                                        chosenSongs.asReversed().forEach { onAddNextPlay(it) }
                                        Toast.makeText(context, "已将 ${chosenSongs.size} 首加入下一首播放", Toast.LENGTH_SHORT).show()
                                        isMultiSelectMode = false
                                        selectedSongIds = emptySet()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        MultiSelectDockAction(
                            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                            label = "加歌单",
                            tint = MaterialTheme.colorScheme.onSurface,
                            enabled = hasSelection,
                            onClick = { showBatchAddToPlaylistDialog = true },
                            modifier = Modifier.weight(1f)
                        )
                        if (canBatchDownload) {
                            MultiSelectDockAction(
                                icon = Icons.Rounded.Download,
                                label = "下载",
                                tint = ConceptMintAccent,
                                enabled = hasSelection,
                                onClick = { showBatchDownloadDialog = true },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (isCustomCreated && currentPlaylist != null) {
                            MultiSelectDockAction(
                                icon = Icons.Rounded.DeleteOutline,
                                label = if (isBatchRemoving) "移出中" else "移出",
                                tint = MaterialTheme.colorScheme.error,
                                enabled = hasSelection && !isBatchRemoving,
                                onClick = { showBatchRemoveDialog = true },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            if (showBatchDownloadDialog && canBatchDownload) {
                val chosenSongs = remember(filteredAndSortedSongs, selectedSongIds) {
                    filteredAndSortedSongs.filter { selectedSongIds.contains(it.selectionKey()) }
                }
                if (chosenSongs.isNotEmpty()) {
                    BatchSongDownloadDialog(
                        songs = chosenSongs,
                        sourceTitle = currentPlaylist?.title ?: if (isLikedPlaylist) "我喜欢的音乐" else "当前歌单",
                        onDismiss = { showBatchDownloadDialog = false },
                        onOpenDownloadSettings = onOpenDownloadSettings,
                        onEnqueued = {
                            isMultiSelectMode = false
                            selectedSongIds = emptySet()
                        }
                    )
                } else {
                    showBatchDownloadDialog = false
                }
            }
        }
    }

    // 批量移出自建歌单二次确认
    if (showBatchRemoveDialog && isCustomCreated && currentPlaylist != null) {
        val chosenSongs = filteredAndSortedSongs.filter { selectedSongIds.contains(it.selectionKey()) }
        AlertDialog(
            onDismissRequest = { if (!isBatchRemoving) showBatchRemoveDialog = false },
            title = { Text("批量移出歌曲") },
            text = { Text("确定要从歌单「${currentPlaylist.title}」中移出已选的 ${chosenSongs.size} 首歌曲吗？") },
            confirmButton = {
                TextButton(
                    enabled = !isBatchRemoving && chosenSongs.isNotEmpty(),
                    onClick = {
                        isBatchRemoving = true
                        viewModel.removeSongsFromPlaylistBatch(currentPlaylist.resolveEffectiveListId(), chosenSongs) { ok, msg ->
                            isBatchRemoving = false
                            showBatchRemoveDialog = false
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            if (ok) {
                                isMultiSelectMode = false
                                selectedSongIds = emptySet()
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isBatchRemoving) "移出中..." else "确认移出")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isBatchRemoving,
                    onClick = { showBatchRemoveDialog = false }
                ) {
                    Text("取消")
                }
            }
        )
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
                    viewModel.addSongsToPlaylistBatch(targetPl.resolveEffectiveListId(), chosenSongs, targetPl.title) { _, msg ->
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
                                viewModel.removeSongFromPlaylist(currentPlaylist.resolveEffectiveListId(), s) { _, msg ->
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
                viewModel.addSongToPlaylist(targetPl.resolveEffectiveListId(), s, targetPl.title) { _, msg ->
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
                                    viewModel.addSongToPlaylist(createdPl.resolveEffectiveListId(), pendingSingle, createdPl.title) { _, addMsg ->
                                        Toast.makeText(context, addMsg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                                pendingBatch.isNotEmpty() -> {
                                    isMultiSelectMode = false
                                    selectedSongIds = emptySet()
                                    viewModel.addSongsToPlaylistBatch(createdPl.resolveEffectiveListId(), pendingBatch, createdPl.title) { _, addMsg ->
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
}

@Composable
private fun MultiSelectDockAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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

@Composable
private fun InlineDraggableSongRow(
    idx: Int,
    totalCount: Int,
    entry: SortableTrackEntry,
    saving: Boolean,
    reorderState: ConceptListDragReorderState,
    onResolveLiveIndex: (String) -> Int,
    onMoveItem: (Int, Int) -> Unit,
    onHapticStart: () -> Unit
) {
    val song = entry.song
    val isActiveReorder = reorderState.isDragging
    val isDragged = isActiveReorder && reorderState.dragStartIndex == idx
    val targetNeighborShiftPx = reorderState.targetNeighborShiftPxForIndex(idx)
    val displayOrderIdx = reorderState.displayOrderIndexFor(idx)

    // 对标 PlayQueueBottomSheet：每行独立 Animatable 弹簧平滑让位，告别生硬阶跃
    val neighborOffsetAnim = remember(entry.stableKey) { Animatable(0f) }
    LaunchedEffect(isActiveReorder, targetNeighborShiftPx) {
        if (!isActiveReorder) {
            neighborOffsetAnim.snapTo(0f)
        } else {
            neighborOffsetAnim.animateTo(
                targetValue = targetNeighborShiftPx,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    val dragScale by animateFloatAsState(
        targetValue = if (isDragged && !reorderState.isSettlingDrop) 1.028f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "playlistInlineDragScale"
    )
    val dragElevation by animateDpAsState(
        targetValue = if (isDragged && !reorderState.isSettlingDrop) 14.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "playlistInlineDragElevation"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragged) 12f else 0f)
            .graphicsLayer {
                translationY = when {
                    !isActiveReorder -> 0f
                    isDragged -> reorderState.activeDragTranslationY()
                    else -> neighborOffsetAnim.value
                }
                scaleX = dragScale
                scaleY = dragScale
                shadowElevation = dragElevation.toPx()
                shape = RoundedCornerShape(16.dp)
                clip = false
            }
            .semantics {
                contentDescription = "${song.title}，${song.artist}，第 ${displayOrderIdx + 1} 首，共 $totalCount 首"
                customActions = buildList {
                    if (idx > 0) {
                        add(CustomAccessibilityAction("置顶") { onMoveItem(idx, 0); true })
                        add(CustomAccessibilityAction("上移一位") { onMoveItem(idx, idx - 1); true })
                    }
                    if (idx < totalCount - 1) {
                        add(CustomAccessibilityAction("下移一位") { onMoveItem(idx, idx + 1); true })
                    }
                }
            }
    ) {
        SongItemRow(
            song = song,
            onClick = {},
            orderIndex = displayOrderIdx + 1,
            isReorderMode = true,
            isDraggingRow = isDragged,
            dragHandleModifier = Modifier.pointerInput(entry.stableKey, totalCount, saving) {
                if (saving) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        val liveIdx = onResolveLiveIndex(entry.stableKey)
                        if (liveIdx >= 0) {
                            onHapticStart()
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
            },
            trailingActionContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "#${(displayOrderIdx + 1).toString().padStart(2, '0')}",
                        color = if (isDragged) ConceptPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        fontSize = 11.5.sp,
                        fontWeight = if (isDragged) FontWeight.ExtraBold else FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                    if (idx > 0 && !isActiveReorder) {
                        IconButton(
                            enabled = !saving,
                            onClick = { onMoveItem(idx, 0) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VerticalAlignTop,
                                contentDescription = "移至顶部",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun PlaylistToolbarSection(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    sortMode: PlaylistSortMode,
    onSortModeChange: (PlaylistSortMode) -> Unit,
    totalSongCount: Int,
    filteredSongCount: Int,
    canCustomSortTracks: Boolean,
    onEnterCustomSort: () -> Unit
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = when {
                    searchQuery.isNotBlank() -> "找到 $filteredSongCount / $totalSongCount 首"
                    totalSongCount > 0 -> "共 $totalSongCount 首曲目"
                    else -> ""
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            PlaylistSortPopupButton(
                sortMode = sortMode,
                onSortModeChange = onSortModeChange,
                canCustomSortTracks = canCustomSortTracks,
                onEnterCustomSort = onEnterCustomSort
            )
        }
    }
}

private fun PlaylistSortMode.menuIcon() = when (this) {
    PlaylistSortMode.DEFAULT -> Icons.AutoMirrored.Rounded.Sort
    PlaylistSortMode.ADDED_NEWEST -> Icons.Rounded.Schedule
    PlaylistSortMode.ADDED_OLDEST -> Icons.Rounded.History
    PlaylistSortMode.TITLE_AZ -> Icons.Rounded.SortByAlpha
    PlaylistSortMode.ARTIST_AZ -> Icons.Rounded.Person
    PlaylistSortMode.DURATION_DESC -> Icons.Rounded.Timer
}

/**
 * 右侧无框纯色排序按钮 + 丝滑灵动锚点小窗：
 * - 无外框纯色质感按钮，默认显示「默认排序」，切换后以轻盈垂直淡入动画更新当前标签；
 * - 点击以 `ConceptMotion.floatingPopupSpring()` 从按钮右上角锚点弹性绽放，竖向呈现全部 6 种排序；
 * - 弹窗内配备流体滑动高亮胶囊与弹性勾选动效，点击后先完成约 220ms 选中过渡动画再柔顺收拢，绝不瞬间切换；
 * - 针对「我喜欢」与自建歌单，在底部以极细分隔线统一收纳「自定义默认排序」入口。
 */
@Composable
private fun PlaylistSortPopupButton(
    sortMode: PlaylistSortMode,
    onSortModeChange: (PlaylistSortMode) -> Unit,
    canCustomSortTracks: Boolean,
    onEnterCustomSort: () -> Unit
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val cyanAccent = conceptCyanAccentColor()

    var showPopup by remember { mutableStateOf(false) }
    var isClosing by remember { mutableStateOf(false) }
    var isSelectingTransition by remember { mutableStateOf(false) }
    var displaySelectedMode by remember { mutableStateOf(sortMode) }
    val popupProgress = remember { Animatable(0f) }

    LaunchedEffect(sortMode, showPopup) {
        if (!showPopup) {
            displaySelectedMode = sortMode
        }
    }

    val closePopupAnimated: ((() -> Unit)?) -> Unit = remember(scope) {
        { afterClosed ->
            if (showPopup && !isClosing) {
                isClosing = true
                scope.launch {
                    popupProgress.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = 175,
                            easing = ConceptMotion.GentleSpatialExitEasing
                        )
                    )
                    showPopup = false
                    isClosing = false
                    isSelectingTransition = false
                    afterClosed?.invoke()
                }
            }
        }
    }

    LaunchedEffect(showPopup) {
        if (showPopup) {
            isClosing = false
            isSelectingTransition = false
            displaySelectedMode = sortMode
            popupProgress.snapTo(0f)
            popupProgress.animateTo(
                targetValue = 1f,
                animationSpec = ConceptMotion.floatingPopupSpring()
            )
        }
    }

    val isExpanded = showPopup && !isClosing
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = ConceptMotion.expressiveSpring(),
        label = "playlistSortArrowRotate"
    )
    val isAccentActive = sortMode != PlaylistSortMode.DEFAULT || isExpanded
    val buttonBgColor by animateColorAsState(
        targetValue = if (isAccentActive) ConceptPrimary.copy(alpha = 0.14f) else conceptFloatingCardColor(),
        animationSpec = tween(220),
        label = "playlistSortBtnBg"
    )
    val buttonContentColor by animateColorAsState(
        targetValue = if (isAccentActive) ConceptPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(220),
        label = "playlistSortBtnFg"
    )

    Box(contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = buttonBgColor,
            shadowElevation = 0.dp,
            modifier = Modifier
                .height(32.dp)
                .softCardClickable(shape = RoundedCornerShape(12.dp)) {
                    if (showPopup) {
                        closePopupAnimated(null)
                    } else {
                        showPopup = true
                    }
                }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Sort,
                    contentDescription = "切换排序方式",
                    tint = buttonContentColor,
                    modifier = Modifier.size(14.5.dp)
                )
                AnimatedContent(
                    targetState = sortMode.label,
                    transitionSpec = {
                        (fadeIn(tween(180)) + slideInVertically(tween(200)) { it / 3 }) togetherWith
                            (fadeOut(tween(130)) + slideOutVertically(tween(160)) { -it / 3 })
                    },
                    label = "playlistSortBtnLabel"
                ) { label ->
                    Text(
                        text = label,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = buttonContentColor,
                        maxLines = 1
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = buttonContentColor,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = arrowRotation }
                )
            }
        }

        if (showPopup) {
            val popupOffsetYPx = with(density) { 38.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, popupOffsetYPx),
                onDismissRequest = { closePopupAnimated(null) },
                properties = PopupProperties(focusable = true)
            ) {
                val modes = PlaylistSortMode.entries
                val rowHeight = 40.dp
                val selectedIdx = modes.indexOf(displaySelectedMode).coerceAtLeast(0)
                val highlightOffsetY by animateDpAsState(
                    targetValue = rowHeight * selectedIdx,
                    animationSpec = ConceptMotion.expressiveSpring(),
                    label = "playlistSortHighlightSlide"
                )

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = conceptSheetContainerColor().copy(alpha = 0.98f),
                    shadowElevation = 18.dp,
                    modifier = Modifier
                        .width(206.dp)
                        .graphicsLayer {
                            val rawP = popupProgress.value
                            val clampedP = rawP.coerceIn(0f, 1f)
                            val s = (0.84f + 0.16f * rawP).coerceIn(0.76f, 1.04f)
                            scaleX = s
                            scaleY = s
                            translationY = (1f - rawP) * -10.dp.toPx()
                            alpha = (clampedP * 1.12f).coerceIn(0f, 1f)
                            transformOrigin = TransformOrigin(0.90f, 0f)
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            // 底层流体滑动高亮胶囊：选中切换时弹簧顺滑滑至目标行
                            Box(
                                modifier = Modifier
                                    .offset(y = highlightOffsetY)
                                    .fillMaxWidth()
                                    .height(rowHeight)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ConceptPrimary.copy(alpha = 0.14f))
                            )

                            Column(modifier = Modifier.fillMaxWidth()) {
                                modes.forEach { mode ->
                                    val isSelected = mode == displaySelectedMode
                                    val itemColor by animateColorAsState(
                                        targetValue = if (isSelected) ConceptPrimary else MaterialTheme.colorScheme.onSurface,
                                        animationSpec = tween(180),
                                        label = "sortOptionColor_${mode.name}"
                                    )
                                    val iconTint by animateColorAsState(
                                        targetValue = if (isSelected) ConceptPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        animationSpec = tween(180),
                                        label = "sortOptionIcon_${mode.name}"
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(rowHeight)
                                            .clip(RoundedCornerShape(12.dp))
                                            .softClickable(
                                                enabled = !isClosing && !isSelectingTransition,
                                                pressScale = 0.97f
                                            ) {
                                                if (isSelectingTransition || isClosing) return@softClickable
                                                isSelectingTransition = true
                                                val changed = mode != displaySelectedMode
                                                if (changed) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    displaySelectedMode = mode
                                                    onSortModeChange(mode)
                                                }
                                                scope.launch {
                                                    delay(if (changed) 225L else 85L)
                                                    closePopupAnimated(null)
                                                }
                                            }
                                            .padding(horizontal = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = mode.menuIcon(),
                                            contentDescription = null,
                                            tint = iconTint,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = mode.label,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                            color = itemColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        AnimatedVisibility(
                                            visible = isSelected,
                                            enter = scaleIn(
                                                initialScale = 0.45f,
                                                animationSpec = ConceptMotion.expressiveSpring()
                                            ) + fadeIn(tween(150)),
                                            exit = scaleOut(
                                                targetScale = 0.55f,
                                                animationSpec = tween(110)
                                            ) + fadeOut(tween(100))
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = "当前已选",
                                                tint = ConceptPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (canCustomSortTracks) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(cyanAccent.copy(alpha = 0.10f))
                                    .softClickable(
                                        enabled = !isClosing && !isSelectingTransition,
                                        pressScale = 0.97f
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        closePopupAnimated { onEnterCustomSort() }
                                    }
                                    .padding(horizontal = 11.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SwapVert,
                                    contentDescription = "自定义默认排序",
                                    tint = cyanAccent,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(9.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "自定义默认排序",
                                        fontSize = 12.8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = cyanAccent,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "拖拽调整 · 同步云端",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                        maxLines = 1
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

@Composable
private fun PlaylistSideHeroPane(
    playlist: Playlist?,
    effectiveCoverUrl: String,
    songCount: Int,
    isExternal: Boolean,
    isCustomCreated: Boolean = false,
    canEditCover: Boolean = false,
    isInlineReorderMode: Boolean = false,
    onPlayAll: () -> Unit,
    onCollectClick: () -> Unit,
    onEditMetaClick: () -> Unit = {},
    onCoverClick: () -> Unit
) {
    val context = LocalContext.current
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
    val localCoverFile = remember(effectiveCoverUrl) {
        PlaylistCoverOverrideRepository.resolveManagedCoverFile(context, effectiveCoverUrl)
            ?.takeIf { it.exists() && it.length() > 0L }
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
                        enabled = effectiveCoverUrl.isNotBlank() || canEditCover,
                        onClick = onCoverClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (localCoverFile != null || effectiveCoverUrl.isNotBlank()) {
                    AsyncImage(
                        model = localCoverFile ?: effectiveCoverUrl,
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
                        enabled = songCount > 0 && !isInlineReorderMode,
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

            if (isCustomCreated) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = ConceptPrimary.copy(alpha = 0.12f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .softCardClickable(
                            shape = RoundedCornerShape(16.dp),
                            enabled = !isInlineReorderMode,
                            onClick = onEditMetaClick
                        )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "编辑歌单信息",
                            tint = ConceptPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "编辑信息",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ConceptPrimary
                        )
                    }
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
    isCustomCreated: Boolean = false,
    canEditCover: Boolean = false,
    isInlineReorderMode: Boolean = false,
    onPlayAll: () -> Unit,
    onCollectClick: () -> Unit = {},
    onEditMetaClick: () -> Unit = {},
    onCoverClick: () -> Unit = {}
) {
    val context = LocalContext.current
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
    val localCoverFile = remember(effectiveCoverUrl) {
        PlaylistCoverOverrideRepository.resolveManagedCoverFile(context, effectiveCoverUrl)
            ?.takeIf { it.exists() && it.length() > 0L }
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
                        enabled = effectiveCoverUrl.isNotBlank() || canEditCover,
                        onClick = onCoverClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (localCoverFile != null || effectiveCoverUrl.isNotBlank()) {
                    AsyncImage(
                        model = localCoverFile ?: effectiveCoverUrl,
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
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .softClickable(enabled = songCount > 0 && !isInlineReorderMode, onClick = onPlayAll)
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
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = "播放全部",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (isCustomCreated) {
                        Surface(
                            shape = RoundedCornerShape(15.dp),
                            color = ConceptPrimary.copy(alpha = 0.12f),
                            modifier = Modifier.softCardClickable(
                                shape = RoundedCornerShape(15.dp),
                                enabled = !isInlineReorderMode,
                                onClick = onEditMetaClick
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = "编辑歌单信息",
                                    tint = ConceptPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "编辑",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ConceptPrimary
                                )
                            }
                        }
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
private fun PlaylistManageBottomSheet(
    playlist: Playlist,
    isCustomCreated: Boolean,
    canShare: Boolean,
    isRefreshing: Boolean,
    onShare: () -> Unit,
    onRefresh: () -> Unit,
    onDeletePlaylist: () -> Unit,
    onDismissStart: () -> Unit,
    onDismiss: () -> Unit
) {
    val cardBg = conceptElevatedSurfaceColor()
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant

    ConceptAdaptiveBottomSheetContainer(
        onDismiss = onDismiss,
        onDismissStart = onDismissStart,
        zIndex = 90f,
        portraitMinHeight = 160.dp,
        portraitMaxHeight = 460.dp,
        wrapContentHeight = true
    ) { _ ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sheetVerticalDragGesture(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("歌单管理", fontSize = 17.5.sp, fontWeight = FontWeight.ExtraBold, color = textPrimary)
                Text(playlist.title, fontSize = 12.sp, color = textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(
                shape = CircleShape,
                color = cardBg,
                modifier = Modifier
                    .size(38.dp)
                    .softClickable(onClick = requestDismiss)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = textSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (canShare) {
                PlaylistManageActionItem(
                    icon = Icons.Rounded.Share,
                    iconTint = ConceptMintAccent,
                    title = "分享歌单",
                    subtitle = "生成精美的分享卡片或复制链接",
                    cardBg = cardBg,
                    onClick = onShare
                )
            }
            PlaylistManageActionItem(
                icon = Icons.Rounded.Refresh,
                iconTint = textSecondary,
                title = if (isRefreshing) "正在刷新歌单..." else "刷新歌单曲目",
                subtitle = "从云端重新同步最新歌曲列表",
                cardBg = cardBg,
                onClick = onRefresh
            )
            if (isCustomCreated) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
                PlaylistManageActionItem(
                    icon = Icons.Rounded.DeleteOutline,
                    iconTint = MaterialTheme.colorScheme.error,
                    title = "删除歌单",
                    subtitle = "永久删除此自建歌单，云端不可恢复",
                    titleColor = MaterialTheme.colorScheme.error,
                    cardBg = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                    onClick = onDeletePlaylist
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PlaylistManageActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    cardBg: Color,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        modifier = Modifier
            .fillMaxWidth()
            .softCardClickable(shape = RoundedCornerShape(16.dp), onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(19.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = titleColor, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.5.sp)
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
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
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    singleLine = true,
                    enabled = !saving,
                    label = { Text("歌单名称") },
                    supportingText = { Text("${name.length}/40") },
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
                    supportingText = { Text("${intro.length}/300") },
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
                Text(if (saving) "保存中..." else "保存同步", color = ConceptPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") }
        }
    )
}
