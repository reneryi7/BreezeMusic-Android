package com.concept.music.ui.library

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.concept.music.data.kugou.AuthState
import com.concept.music.data.model.LocalCloudDefaultTab
import com.concept.music.data.model.LocalMusicQueryEngine
import com.concept.music.data.model.LocalPrimaryTab
import com.concept.music.data.model.LocalQualityFilter
import com.concept.music.data.model.LocalSortOption
import com.concept.music.data.model.Song
import com.concept.music.service.AppPermissionManager
import com.concept.music.service.AppPermissionType
import com.concept.music.ui.theme.*
import com.concept.music.ui.viewmodel.LibraryViewModel

/**
 * 独立「本地/云盘」双页中心：
 * - 顶栏无框分段胶囊在「本地音乐」与「音乐云盘」间丝滑切换；
 * - 默认优先展示用户所选的默认页面（初始默认为「本地音乐」），并在右上角更多菜单与设置页两处同步可调；
 * - 未登录酷狗账号也可直接进入本地音乐离线扫描、筛选、多选建单与播放；
 * - 集成 SAF 音乐文件夹选择、独立歌词目录导入、重新扫描与系统音频权限引导。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAndCloudMusicScreen(
    viewModel: LibraryViewModel,
    currentPlayingSongId: String? = null,
    isPlaying: Boolean = false,
    onBack: () -> Unit,
    onLogin: () -> Unit = {},
    onPlaySong: (Song, List<Song>) -> Unit,
    onAddNextPlay: (Song) -> Unit = {},
    onOpenSongActions: ((Song) -> Unit)? = null,
    onLocalLyricsUpdated: () -> Unit = {}
) {
    val context = LocalContext.current
    val isDark = LocalIsDarkTheme.current
    val cyanAccent = conceptCyanAccentColor()
    val cardBg = conceptFloatingCardColor()

    val account by viewModel.accountState.collectAsState()
    val isLoggedIn = account.auth == AuthState.READY
    val playlists by viewModel.playlists.collectAsState()

    val defaultTab by viewModel.localCloudDefaultTab.collectAsState()
    val onlineLyricFallback by viewModel.localOnlineLyricFallback.collectAsState()
    val localTracks by viewModel.localTracks.collectAsState()
    val localFolders by viewModel.localFolders.collectAsState()
    val localLyricFolders by viewModel.localLyricFolders.collectAsState()
    val localSummary by viewModel.localSummary.collectAsState()
    val localScanProgress by viewModel.localScanProgress.collectAsState()
    val cloudSummary by viewModel.cloudDiskSummary.collectAsState()
    val cloudLoading by viewModel.cloudDiskLoading.collectAsState()

    // 进入页面时严格按用户自选的默认页打开（初始默认为 LOCAL 本地音乐）
    var activeTabName by rememberSaveable { mutableStateOf(defaultTab.name) }
    val activeTab = remember(activeTabName) {
        runCatching { LocalCloudDefaultTab.valueOf(activeTabName) }.getOrDefault(LocalCloudDefaultTab.LOCAL)
    }

    var searchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showMoreMenu by remember { mutableStateOf(false) }
    var cloudSelectionMode by remember { mutableStateOf(false) }
    var cloudSelectedKvIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // 本地音乐筛选、钻取与多选状态
    var localPrimaryTab by remember { mutableStateOf(LocalPrimaryTab.ALL_SONGS) }
    var localQualityFilter by remember { mutableStateOf(LocalQualityFilter.ALL) }
    var localSortOption by remember { mutableStateOf(LocalSortOption.DEFAULT) }
    var localDrillDown by remember { mutableStateOf<LocalDrillDownTarget?>(null) }
    var localSelectionMode by remember { mutableStateOf(false) }
    var localSelectedTrackIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var hasPromptedRuntimeAudioPerm by remember { mutableStateOf(false) }
    var pendingTreeActionAfterPerm by remember { mutableStateOf<String?>(null) } // "music" or "lyric"

    // 计算当前本地歌曲列表可见的曲目 ID 集合（供顶栏全选/取消全选同步读取）
    val visibleLocalTrackIds = remember(
        localTracks,
        localDrillDown,
        localPrimaryTab,
        localQualityFilter,
        localSortOption,
        searchQuery
    ) {
        val showSongList = localDrillDown != null || localPrimaryTab == LocalPrimaryTab.ALL_SONGS
        if (!showSongList) {
            emptySet()
        } else {
            val basePool = when (val d = localDrillDown) {
                is LocalDrillDownTarget.Folder -> localTracks.filter { it.folderId == d.folderId }
                is LocalDrillDownTarget.Artist -> localTracks.filter { t ->
                    val list = t.artists.ifEmpty { LocalMusicQueryEngine.splitArtistNames(t.artist) }
                    list.any { LocalMusicQueryEngine.normalizeArtistKey(it) == d.normalizedKey }
                }
                is LocalDrillDownTarget.Album -> localTracks.filter { t ->
                    LocalMusicQueryEngine.normalizeAlbumKey(t.album) == d.normalizedKey
                }
                null -> localTracks
            }
            LocalMusicQueryEngine.filterAndSortTracks(
                tracks = basePool,
                folderId = null,
                qualityFilter = localQualityFilter,
                sortOption = localSortOption,
                searchQuery = searchQuery
            ).map { it.id }.toSet()
        }
    }

    LaunchedEffect(visibleLocalTrackIds, localSelectionMode) {
        if (localSelectionMode) {
            if (visibleLocalTrackIds.isEmpty()) {
                localSelectionMode = false
                localSelectedTrackIds = emptySet()
            } else {
                val pruned = localSelectedTrackIds.intersect(visibleLocalTrackIds)
                if (pruned.size != localSelectedTrackIds.size) {
                    localSelectedTrackIds = pruned
                }
            }
        }
    }

    val musicFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            activeTabName = LocalCloudDefaultTab.LOCAL.name
            viewModel.importLocalMusicFolder(treeUri) { ok, msg ->
                Toast.makeText(context, msg, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            }
        }
    }

    val lyricFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.importLocalLyricFolder(treeUri) { ok, msg ->
                Toast.makeText(context, msg, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                if (ok) onLocalLyricsUpdated()
            }
        }
    }

    val audioPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 无论系统媒体权限是否授予，均继续打开 SAF 文件夹选择器（SAF 具备系统级精确目录读写授权）
        when (pendingTreeActionAfterPerm) {
            "music" -> runCatching { musicFolderPickerLauncher.launch(null) }
            "lyric" -> runCatching { lyricFolderPickerLauncher.launch(null) }
        }
        pendingTreeActionAfterPerm = null
    }

    val launchFolderPickerWithPermissionCheck: (String) -> Unit = { kind ->
        if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.LOCAL_AUDIO_IMPORT)) {
            Toast.makeText(
                context,
                "已在「设置 - 权限管理」中关闭本地音乐目录读取权限，请先开启",
                Toast.LENGTH_LONG
            ).show()
        } else if (!hasPromptedRuntimeAudioPerm && !AppPermissionManager.hasLocalAudioReadPermission(context)) {
            hasPromptedRuntimeAudioPerm = true
            pendingTreeActionAfterPerm = kind
            audioPermLauncher.launch(AppPermissionManager.requiredLocalAudioRuntimePermissions())
        } else {
            runCatching {
                if (kind == "music") musicFolderPickerLauncher.launch(null)
                else lyricFolderPickerLauncher.launch(null)
            }.onFailure {
                Toast.makeText(context, "当前设备未找到系统文件夹选择器", Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler {
        when {
            activeTab == LocalCloudDefaultTab.LOCAL && localSelectionMode -> {
                localSelectionMode = false
                localSelectedTrackIds = emptySet()
            }
            activeTab == LocalCloudDefaultTab.CLOUD && cloudSelectionMode -> {
                cloudSelectionMode = false
                cloudSelectedKvIds = emptySet()
            }
            activeTab == LocalCloudDefaultTab.LOCAL && localDrillDown != null -> {
                localDrillDown = null
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

    val isLocalSelecting = activeTab == LocalCloudDefaultTab.LOCAL && localSelectionMode
    val isCloudSelecting = activeTab == LocalCloudDefaultTab.CLOUD && cloudSelectionMode
    val isAnySelecting = isLocalSelecting || isCloudSelecting

    ConceptStageBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                when {
                                    isLocalSelecting -> {
                                        localSelectionMode = false
                                        localSelectedTrackIds = emptySet()
                                    }
                                    isCloudSelecting -> {
                                        cloudSelectionMode = false
                                        cloudSelectedKvIds = emptySet()
                                    }
                                    activeTab == LocalCloudDefaultTab.LOCAL && localDrillDown != null -> {
                                        localDrillDown = null
                                    }
                                    else -> onBack()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isAnySelecting) {
                                    Icons.Rounded.Close
                                } else {
                                    Icons.AutoMirrored.Rounded.ArrowBack
                                },
                                contentDescription = if (isAnySelecting) "退出多选" else "返回"
                            )
                        }
                    },
                    title = {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                isLocalSelecting -> {
                                    Text(
                                        text = "已选 ${localSelectedTrackIds.size} 首本地歌曲",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                isCloudSelecting -> {
                                    Text(
                                        text = "已选 ${cloudSelectedKvIds.size} 首云盘曲目",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                else -> {
                                    LocalCloudSegmentedPillSwitcher(
                                        activeTab = activeTab,
                                        isDark = isDark,
                                        accentColor = cyanAccent,
                                        onSelect = { next ->
                                            if (next != activeTab) {
                                                localSelectionMode = false
                                                localSelectedTrackIds = emptySet()
                                                cloudSelectionMode = false
                                                cloudSelectedKvIds = emptySet()
                                                activeTabName = next.name
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        if (isLocalSelecting) {
                            val allSelected = visibleLocalTrackIds.isNotEmpty() &&
                                localSelectedTrackIds.size == visibleLocalTrackIds.size
                            IconButton(
                                onClick = {
                                    localSelectedTrackIds = if (allSelected) emptySet() else visibleLocalTrackIds
                                }
                            ) {
                                Icon(
                                    imageVector = if (allSelected) Icons.Rounded.RemoveDone else Icons.Rounded.DoneAll,
                                    contentDescription = if (allSelected) "取消全选" else "全选",
                                    tint = cyanAccent
                                )
                            }
                        } else if (isCloudSelecting) {
                            val allIds = cloudSummary.songs.mapNotNull { it.kvId.takeIf { id -> id > 0L } }.toSet()
                            val allSelected = allIds.isNotEmpty() && cloudSelectedKvIds.size == allIds.size
                            IconButton(
                                onClick = {
                                    cloudSelectedKvIds = if (allSelected) emptySet() else allIds
                                }
                            ) {
                                Icon(
                                    imageVector = if (allSelected) Icons.Rounded.RemoveDone else Icons.Rounded.DoneAll,
                                    contentDescription = if (allSelected) "取消全选" else "全选",
                                    tint = cyanAccent
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    searchExpanded = !searchExpanded
                                    if (!searchExpanded) searchQuery = ""
                                }
                            ) {
                                Icon(
                                    imageVector = if (searchExpanded) Icons.Rounded.SearchOff else Icons.Rounded.Search,
                                    contentDescription = "搜索",
                                    tint = if (searchExpanded) cyanAccent else MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Box {
                                IconButton(onClick = { showMoreMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreVert,
                                        contentDescription = "更多选项",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false },
                                    modifier = Modifier.background(conceptSheetContainerColor(), RoundedCornerShape(18.dp))
                                ) {
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, tint = cyanAccent, modifier = Modifier.size(19.dp)) },
                                        text = { Text("选择音乐文件夹导入", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold) },
                                        onClick = {
                                            showMoreMenu = false
                                            launchFolderPickerWithPermissionCheck("music")
                                        }
                                    )
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Rounded.Lyrics, contentDescription = null, tint = ConceptPrimary, modifier = Modifier.size(19.dp)) },
                                        text = { Text("导入独立歌词目录 (.lrc/.krc)", fontSize = 13.5.sp) },
                                        onClick = {
                                            showMoreMenu = false
                                            launchFolderPickerWithPermissionCheck("lyric")
                                        }
                                    )
                                    if (localFolders.isNotEmpty()) {
                                        DropdownMenuItem(
                                            leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(19.dp)) },
                                            text = { Text("重新扫描全部本地目录", fontSize = 13.5.sp) },
                                            onClick = {
                                                showMoreMenu = false
                                                viewModel.rescanAllLocalMusic { _, msg ->
                                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                    }

                                    if (activeTab == LocalCloudDefaultTab.CLOUD && isLoggedIn) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                                        if (cloudSummary.songs.isNotEmpty()) {
                                            DropdownMenuItem(
                                                leadingIcon = { Icon(Icons.Rounded.Checklist, contentDescription = null, modifier = Modifier.size(19.dp)) },
                                                text = { Text("批量管理云盘曲目", fontSize = 13.5.sp) },
                                                onClick = {
                                                    showMoreMenu = false
                                                    cloudSelectionMode = true
                                                    cloudSelectedKvIds = emptySet()
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            leadingIcon = { Icon(Icons.Rounded.CloudSync, contentDescription = null, tint = cyanAccent, modifier = Modifier.size(19.dp)) },
                                            text = { Text(if (cloudLoading) "云盘同步中..." else "刷新音乐云盘", fontSize = 13.5.sp) },
                                            enabled = !cloudLoading,
                                            onClick = {
                                                showMoreMenu = false
                                                viewModel.loadCloudDisk(force = true)
                                            }
                                        )
                                    }

                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                                    val nextDefaultTab = if (defaultTab == LocalCloudDefaultTab.LOCAL) {
                                        LocalCloudDefaultTab.CLOUD
                                    } else {
                                        LocalCloudDefaultTab.LOCAL
                                    }
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null, tint = ConceptCoralAccent, modifier = Modifier.size(19.dp)) },
                                        text = {
                                            Column {
                                                Text("默认显示页：${defaultTab.label}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                Text(
                                                    text = "点击切换为默认打开「${nextDefaultTab.label}」",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            viewModel.setLocalCloudDefaultTab(nextDefaultTab)
                                            Toast.makeText(context, "已将默认显示页设为「${nextDefaultTab.label}」", Toast.LENGTH_SHORT).show()
                                        }
                                    )

                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (onlineLyricFallback) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (onlineLyricFallback) ConceptMintAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        },
                                        text = {
                                            Column {
                                                Text("缺歌词时自动联网匹配", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                                Text(
                                                    text = if (onlineLyricFallback) "已开启（优先本地/内嵌歌词）" else "已关闭（仅使用本地歌词）",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            val next = !onlineLyricFallback
                                            viewModel.setLocalOnlineLyricFallback(next)
                                            Toast.makeText(
                                                context,
                                                if (next) "已开启本地缺歌词联网兜底匹配" else "已关闭联网歌词兜底，仅读本地歌词",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // 内联无框搜索栏（本地/云盘共用，丝滑展开收起）
                AnimatedVisibility(
                    visible = searchExpanded && !isAnySelecting,
                    enter = expandVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) + fadeIn(),
                    exit = shrinkVertically(animationSpec = tween(180)) + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            placeholder = {
                                Text(
                                    text = if (activeTab == LocalCloudDefaultTab.LOCAL) {
                                        "搜索本地歌名、歌手、专辑、文件名或目录..."
                                    } else {
                                        "搜索云盘歌名、歌手、专辑或格式..."
                                    },
                                    fontSize = 13.sp
                                )
                            },
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
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .widthIn(max = 848.dp)
                                .fillMaxWidth()
                        )
                    }
                }

                // 主内容双页丝滑同平面切换
                AnimatedContent(
                    targetState = activeTab,
                    transitionSpec = {
                        val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                        conceptCategoryContentTransition(direction)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    label = "localVsCloudPageTransition"
                ) { tab ->
                    when (tab) {
                        LocalCloudDefaultTab.LOCAL -> {
                            LocalMusicTabContent(
                                tracks = localTracks,
                                folders = localFolders,
                                lyricFolders = localLyricFolders,
                                playlists = playlists,
                                summary = localSummary,
                                scanProgress = localScanProgress,
                                searchQuery = searchQuery,
                                primaryTab = localPrimaryTab,
                                onSelectPrimaryTab = { next ->
                                    localPrimaryTab = next
                                    localDrillDown = null
                                    localSelectionMode = false
                                    localSelectedTrackIds = emptySet()
                                },
                                qualityFilter = localQualityFilter,
                                onSelectQualityFilter = { localQualityFilter = it },
                                sortOption = localSortOption,
                                onSelectSortOption = { localSortOption = it },
                                drillDown = localDrillDown,
                                onChangeDrillDown = { next ->
                                    localDrillDown = next
                                    localSelectionMode = false
                                    localSelectedTrackIds = emptySet()
                                },
                                selectionMode = localSelectionMode,
                                selectedTrackIds = localSelectedTrackIds,
                                onSelectionModeChange = { enabled ->
                                    localSelectionMode = enabled
                                    if (!enabled) localSelectedTrackIds = emptySet()
                                },
                                onSelectedTrackIdsChange = { localSelectedTrackIds = it },
                                currentPlayingSongId = currentPlayingSongId,
                                isPlaying = isPlaying,
                                onPickMusicFolder = { launchFolderPickerWithPermissionCheck("music") },
                                onPickLyricFolder = { launchFolderPickerWithPermissionCheck("lyric") },
                                onRescanFolder = { folder ->
                                    viewModel.rescanLocalMusicFolder(folder.id) { _, msg ->
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onRemoveFolder = { folder ->
                                    viewModel.removeLocalMusicFolder(folder.id) { msg ->
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onRemoveLyricFolder = { lf ->
                                    viewModel.removeLocalLyricFolder(lf.id) { msg ->
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        onLocalLyricsUpdated()
                                    }
                                },
                                onRemoveTracksBatch = { ids ->
                                    viewModel.removeLocalTracksBatch(ids) { msg ->
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onAddSongsToPlaylist = { targetPl, songsToAdd ->
                                    viewModel.addSongsToPlaylistBatch(targetPl, songsToAdd) { _, msg ->
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onCreateLocalPlaylistWithSongs = { name, songsToAdd, onDone ->
                                    viewModel.createLocalOnlyPlaylist(
                                        name = name,
                                        initialSongs = songsToAdd,
                                        onResult = { ok, msg ->
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            onDone(ok)
                                        }
                                    )
                                },
                                onPlaySong = onPlaySong,
                                onAddNextPlay = onAddNextPlay,
                                onOpenSongActions = onOpenSongActions
                            )
                        }

                        LocalCloudDefaultTab.CLOUD -> {
                            CloudDiskTabContent(
                                viewModel = viewModel,
                                isLoggedIn = isLoggedIn,
                                searchQuery = searchQuery,
                                selectionMode = cloudSelectionMode,
                                selectedKvIds = cloudSelectedKvIds,
                                onSelectionModeChange = { cloudSelectionMode = it },
                                onSelectedKvIdsChange = { cloudSelectedKvIds = it },
                                currentPlayingSongId = currentPlayingSongId,
                                isPlaying = isPlaying,
                                onLogin = onLogin,
                                onPlaySong = onPlaySong,
                                onAddNextPlay = onAddNextPlay,
                                onOpenSongActions = onOpenSongActions
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 顶栏无框分段胶囊切换器 `[ 本地音乐 | 音乐云盘 ]`：
 * 内置弹性滑块，纯色阶对比反馈，点击丝滑无闪烁切换双页。
 */
@Composable
private fun LocalCloudSegmentedPillSwitcher(
    activeTab: LocalCloudDefaultTab,
    isDark: Boolean,
    accentColor: Color,
    onSelect: (LocalCloudDefaultTab) -> Unit
) {
    val pillWidth = 196.dp
    val pillHeight = 36.dp
    val segmentWidth = 94.dp
    val indicatorOffset by animateDpAsState(
        targetValue = if (activeTab == LocalCloudDefaultTab.LOCAL) 4.dp else 98.dp,
        animationSpec = ConceptMotion.expressiveSpring(),
        label = "localCloudSwitcherOffset"
    )
    val trackBg = if (isDark) Color(0xFF141E30).copy(alpha = 0.88f) else Color(0xFFE7EFFA).copy(alpha = 0.92f)
    val thumbBg = if (isDark) accentColor.copy(alpha = 0.24f) else Color.White

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = trackBg,
        modifier = Modifier
            .width(pillWidth)
            .height(pillHeight)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 滑动高亮胶囊底托
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = thumbBg,
                shadowElevation = if (isDark) 0.dp else 2.dp,
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .align(Alignment.CenterStart)
                    .width(segmentWidth)
                    .height(28.dp)
            ) {}

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LocalCloudDefaultTab.entries.forEach { tab ->
                    val selected = tab == activeTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(tab) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.label,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            color = if (selected) {
                                if (isDark) accentColor else ConceptPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
