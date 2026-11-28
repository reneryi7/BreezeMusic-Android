package com.concept.music.ui.car

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.concept.music.data.model.FmChannel
import com.concept.music.data.model.Playlist
import com.concept.music.data.model.RankItem
import com.concept.music.data.model.Song
import com.concept.music.ui.theme.*
import com.concept.music.ui.viewmodel.HomeViewModel
import com.concept.music.ui.viewmodel.LibraryViewModel
import com.concept.music.ui.viewmodel.MusicPlayerViewModel
import com.concept.music.ui.viewmodel.PlayMode
import com.concept.music.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.launch

private enum class CarLibrarySubView(val title: String) {
    ROOT("我的歌单与曲库"),
    LIKED("我喜欢"),
    CLOUD_DISK("个人音乐云盘"),
    LOCAL_USB("本地 / U 盘无损"),
    RECENT("最近播放"),
    PLAYLIST_TRACKS("歌单曲目")
}

/**
 * 车机独立包右侧高通透毛玻璃抽屉：
 * 整合 6 大纯粹听歌水源（猜你喜欢 / 每日推荐 / 我的歌单含云盘与U盘 / 随心电台 / 巅峰榜单 / 大字语音搜索）与当前待播队列；
 * - 零实时双通道 GPU 模糊（采用多层渐变通透深海玻璃 + 细光边），在 8155 与老旧车机均稳跑 60fps；
 * - 列表所有封面按 `renderCaps.listThumbDecodeSizePx` 严格像素降采样；
 * - 歌曲行严格践行 C2 确认规范：点击即播并自动收起抽屉，行尾仅留一枚轻触「下一首播放」图标，无复杂弹窗菜单。
 */
@Composable
internal fun CarSourceGlassDrawer(
    drawerType: CarDrawerType,
    layoutSpec: CarCockpitLayoutSpec,
    renderCaps: CarRenderCapabilities,
    accentPrimary: Color,
    accentSecondary: Color,
    playerViewModel: MusicPlayerViewModel,
    homeViewModel: HomeViewModel,
    libraryViewModel: LibraryViewModel,
    searchViewModel: SearchViewModel,
    onSwitchDrawer: (CarDrawerType) -> Unit,
    onPlaySongAndClose: (Song, List<Song>, Boolean) -> Unit,
    onPlayNextSong: (Song) -> Unit,
    onDismiss: () -> Unit
) {
    val playerState by playerViewModel.uiState.collectAsState()
    val drawerWidthFraction = layoutSpec.drawerWidthFraction

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88040812))
            .softCardClickable(shape = RoundedCornerShape(0.dp), clip = false, pressScale = 1f, onClick = onDismiss),
        contentAlignment = Alignment.CenterEnd
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp),
            color = Color(0xF20A1222),
            contentColor = Color.White,
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(drawerWidthFraction)
                .widthIn(max = 680.dp)
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            accentSecondary.copy(alpha = 0.16f),
                            Color.White.copy(alpha = 0.08f)
                        )
                    ),
                    shape = RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp)
                )
                .softCardClickable(shape = RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp), clip = false, pressScale = 1f, onClick = {})
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 18.dp)
            ) {
                // 抽屉顶栏 + 水源快切胶囊
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(accentSecondary.copy(alpha = 0.20f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = drawerType.icon,
                                contentDescription = null,
                                tint = accentSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = drawerType.label,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.10f),
                        modifier = Modifier
                            .size(46.dp)
                            .softCardClickable(shape = CircleShape, onClick = onDismiss)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Close, contentDescription = "收起抽屉", tint = Color.White)
                        }
                    }
                }

                // 窄分屏下抽屉顶部提供水源快速切换条
                if (!layoutSpec.isDualPaneLandscape) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            CarDrawerType.PERSONAL_FM to "FM",
                            CarDrawerType.DAILY_RECOMMEND to "日推",
                            CarDrawerType.MY_LIBRARY to "歌单",
                            CarDrawerType.RADIO to "电台",
                            CarDrawerType.CHARTS to "榜单",
                            CarDrawerType.SEARCH to "搜索"
                        ).forEach { (type, shortName) ->
                            CarChoicePill(
                                label = shortName,
                                selected = drawerType == type,
                                accent = accentSecondary,
                                modifier = Modifier.weight(1f),
                                onClick = { onSwitchDrawer(type) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                AnimatedContent(
                    targetState = drawerType,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    modifier = Modifier.weight(1f),
                    label = "carDrawerContent"
                ) { activeType ->
                    when (activeType) {
                        CarDrawerType.PERSONAL_FM -> CarPersonalFmDrawerContent(
                            homeViewModel = homeViewModel,
                            currentSongId = playerState.currentSong?.id,
                            thumbSizePx = renderCaps.listThumbDecodeSizePx,
                            accentPrimary = accentPrimary,
                            accentSecondary = accentSecondary,
                            onPlay = { s, q -> onPlaySongAndClose(s, q, true) },
                            onPlayNext = onPlayNextSong
                        )

                        CarDrawerType.DAILY_RECOMMEND -> CarDailyRecommendDrawerContent(
                            homeViewModel = homeViewModel,
                            currentSongId = playerState.currentSong?.id,
                            thumbSizePx = renderCaps.listThumbDecodeSizePx,
                            accentSecondary = accentSecondary,
                            onPlay = { s, q -> onPlaySongAndClose(s, q, false) },
                            onPlayNext = onPlayNextSong
                        )

                        CarDrawerType.MY_LIBRARY -> CarMyLibraryDrawerContent(
                            libraryViewModel = libraryViewModel,
                            currentSongId = playerState.currentSong?.id,
                            thumbSizePx = renderCaps.listThumbDecodeSizePx,
                            accentSecondary = accentSecondary,
                            onPlay = { s, q -> onPlaySongAndClose(s, q, false) },
                            onPlayNext = onPlayNextSong
                        )

                        CarDrawerType.RADIO -> CarRadioDrawerContent(
                            homeViewModel = homeViewModel,
                            thumbSizePx = renderCaps.listThumbDecodeSizePx,
                            accentSecondary = accentSecondary,
                            onPlayStation = { songs ->
                                songs.firstOrNull()?.let { onPlaySongAndClose(it, songs, false) }
                            }
                        )

                        CarDrawerType.CHARTS -> CarChartsDrawerContent(
                            homeViewModel = homeViewModel,
                            currentSongId = playerState.currentSong?.id,
                            thumbSizePx = renderCaps.listThumbDecodeSizePx,
                            accentSecondary = accentSecondary,
                            onPlay = { s, q -> onPlaySongAndClose(s, q, false) },
                            onPlayNext = onPlayNextSong
                        )

                        CarDrawerType.SEARCH -> CarVoiceSearchDrawerContent(
                            searchViewModel = searchViewModel,
                            currentSongId = playerState.currentSong?.id,
                            thumbSizePx = renderCaps.listThumbDecodeSizePx,
                            accentSecondary = accentSecondary,
                            onPlay = { s, q -> onPlaySongAndClose(s, q, false) },
                            onPlayNext = onPlayNextSong
                        )

                        CarDrawerType.PLAY_QUEUE -> CarPlayQueueDrawerContent(
                            queue = playerState.playlistQueue,
                            currentIndex = playerState.currentIndex,
                            playMode = playerState.playMode,
                            thumbSizePx = renderCaps.listThumbDecodeSizePx,
                            accentSecondary = accentSecondary,
                            onCyclePlayMode = { playerViewModel.cyclePlayMode() },
                            onSelectSong = { song ->
                                playerViewModel.playSongInCurrentQueue(song)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CarPersonalFmDrawerContent(
    homeViewModel: HomeViewModel,
    currentSongId: String?,
    thumbSizePx: Int,
    accentPrimary: Color,
    accentSecondary: Color,
    onPlay: (Song, List<Song>) -> Unit,
    onPlayNext: (Song) -> Unit
) {
    val fmSongs by homeViewModel.personalFmSongs.collectAsState()
    val poolId by homeViewModel.fmSongPoolId.collectAsState()
    val refreshing by homeViewModel.personalFmRefreshing.collectAsState()

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 平衡 / 熟悉 / 尝鲜 三模式一键切换 + 换一批
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(0 to "平衡推荐", 1 to "偏好熟悉", 2 to "更多尝鲜").forEach { (code, label) ->
                CarChoicePill(
                    label = label,
                    selected = poolId == code,
                    accent = accentSecondary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        homeViewModel.switchPersonalFmPool(code) { songs ->
                            songs.firstOrNull()?.let { onPlay(it, songs) }
                        }
                    }
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = accentPrimary.copy(alpha = 0.22f),
                modifier = Modifier.softCardClickable(
                    enabled = !refreshing,
                    shape = RoundedCornerShape(14.dp),
                    onClick = { homeViewModel.refreshPersonalFm() }
                )
            ) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                    Text(
                        text = if (refreshing) "刷新中" else "换一批",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = accentPrimary
                    )
                }
            }
        }

        if (fmSongs.isEmpty()) {
            CarDrawerEmptyState(
                text = if (refreshing) "正在为你调配私人 FM..." else "暂无推荐曲目，点击「换一批」获取",
                actionLabel = "立即获取",
                onAction = { homeViewModel.refreshPersonalFm() }
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 18.dp)
            ) {
                itemsIndexed(fmSongs, key = { idx, s -> "fm_${idx}_${s.id}" }) { idx, song ->
                    CarGlassSongRow(
                        index = idx + 1,
                        song = song,
                        isPlaying = song.id == currentSongId,
                        thumbSizePx = thumbSizePx,
                        accentColor = accentSecondary,
                        onClick = { onPlay(song, fmSongs) },
                        onPlayNext = { onPlayNext(song) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CarDailyRecommendDrawerContent(
    homeViewModel: HomeViewModel,
    currentSongId: String?,
    thumbSizePx: Int,
    accentSecondary: Color,
    onPlay: (Song, List<Song>) -> Unit,
    onPlayNext: (Song) -> Unit
) {
    val dailySongs by homeViewModel.dailyRecommendSongs.collectAsState()
    val refreshing by homeViewModel.dailyRefreshing.collectAsState()

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = accentSecondary.copy(alpha = 0.24f),
                modifier = Modifier.softCardClickable(
                    enabled = dailySongs.isNotEmpty(),
                    shape = RoundedCornerShape(16.dp),
                    onClick = { dailySongs.firstOrNull()?.let { onPlay(it, dailySongs) } }
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = accentSecondary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("一键播放今日推荐", fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold, color = accentSecondary)
                }
            }
            CarChoicePill(
                label = if (refreshing) "刷新中..." else "刷新日推",
                selected = false,
                accent = Color.White,
                onClick = { homeViewModel.refreshDailyRecommend() }
            )
        }

        if (dailySongs.isEmpty()) {
            CarDrawerEmptyState(
                text = if (refreshing) "正在加载今日推荐..." else "请扫码登录酷狗账号或点击刷新获取今日推荐",
                actionLabel = "刷新日推",
                onAction = { homeViewModel.refreshDailyRecommend() }
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 18.dp)
            ) {
                itemsIndexed(dailySongs, key = { idx, s -> "daily_${idx}_${s.id}" }) { idx, song ->
                    CarGlassSongRow(
                        index = idx + 1,
                        song = song,
                        isPlaying = song.id == currentSongId,
                        thumbSizePx = thumbSizePx,
                        accentColor = accentSecondary,
                        onClick = { onPlay(song, dailySongs) },
                        onPlayNext = { onPlayNext(song) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CarMyLibraryDrawerContent(
    libraryViewModel: LibraryViewModel,
    currentSongId: String?,
    thumbSizePx: Int,
    accentSecondary: Color,
    onPlay: (Song, List<Song>) -> Unit,
    onPlayNext: (Song) -> Unit
) {
    val context = LocalContext.current
    val playlists by libraryViewModel.playlists.collectAsState()
    val likedSongs by libraryViewModel.likedSongs.collectAsState()
    val sectionSongs by libraryViewModel.librarySongs.collectAsState()
    val libraryLoading by libraryViewModel.libraryLoading.collectAsState()
    val cloudSummary by libraryViewModel.cloudDiskSummary.collectAsState()
    val cloudLoading by libraryViewModel.cloudDiskLoading.collectAsState()
    val localTracks by libraryViewModel.localTracks.collectAsState()
    val localScanProgress by libraryViewModel.localScanProgress.collectAsState()

    var subView by remember { mutableStateOf(CarLibrarySubView.ROOT) }
    var selectedPlaylistTitle by remember { mutableStateOf("") }

    BackHandler(enabled = subView != CarLibrarySubView.ROOT) {
        subView = CarLibrarySubView.ROOT
    }

    // 只读 U 盘 / 本地目录选择器（SAF DocumentTree）
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            libraryViewModel.importLocalMusicFolder(treeUri) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(subView) {
        when (subView) {
            CarLibrarySubView.LIKED -> libraryViewModel.openLibrarySection("我喜欢")
            CarLibrarySubView.RECENT -> libraryViewModel.openLibrarySection("最近")
            CarLibrarySubView.CLOUD_DISK -> libraryViewModel.loadCloudDisk(force = false)
            else -> Unit
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (subView != CarLibrarySubView.ROOT) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.10f),
                    modifier = Modifier.softCardClickable(
                        shape = RoundedCornerShape(14.dp),
                        onClick = { subView = CarLibrarySubView.ROOT }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (subView == CarLibrarySubView.PLAYLIST_TRACKS) selectedPlaylistTitle else subView.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (subView == CarLibrarySubView.LOCAL_USB) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CarChoicePill(
                            label = "选 U 盘 / 目录",
                            selected = true,
                            accent = ConceptMintAccent,
                            onClick = { folderPicker.launch(null) }
                        )
                        CarChoicePill(
                            label = if (localScanProgress.isScanning) "扫描中" else "刷新",
                            selected = false,
                            accent = accentSecondary,
                            onClick = {
                                libraryViewModel.rescanAllLocalMusic { _, msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }

        when (subView) {
            CarLibrarySubView.ROOT -> {
                // 顶部 2×2 四大直达光源大卡片（我喜欢 / 音乐云盘 / 本地 U 盘 / 最近播放）
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CarLibraryQuickTile(
                            title = "我喜欢",
                            subtitle = if (likedSongs.isNotEmpty()) "${likedSongs.size} 首红心" else "红心收藏",
                            icon = Icons.Rounded.Favorite,
                            accent = ConceptCoralAccent,
                            modifier = Modifier.weight(1f),
                            onClick = { subView = CarLibrarySubView.LIKED }
                        )
                        CarLibraryQuickTile(
                            title = "音乐云盘",
                            subtitle = if (cloudSummary.songs.isNotEmpty()) "${cloudSummary.songs.size} 首云端" else "只读畅听",
                            icon = Icons.Rounded.CloudQueue,
                            accent = ConceptCyanAccent,
                            modifier = Modifier.weight(1f),
                            onClick = { subView = CarLibrarySubView.CLOUD_DISK }
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CarLibraryQuickTile(
                            title = "本地 / U 盘",
                            subtitle = if (localTracks.isNotEmpty()) "${localTracks.size} 首无损本地" else "插 U 盘离线播",
                            icon = Icons.Rounded.Usb,
                            accent = ConceptMintAccent,
                            modifier = Modifier.weight(1f),
                            onClick = { subView = CarLibrarySubView.LOCAL_USB }
                        )
                        CarLibraryQuickTile(
                            title = "最近播放",
                            subtitle = "快速找回刚听过的歌",
                            icon = Icons.Rounded.History,
                            accent = ConceptPrimary,
                            modifier = Modifier.weight(1f),
                            onClick = { subView = CarLibrarySubView.RECENT }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "我的歌单（只读消费 · 管理同步自手机端）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.62f)
                )

                val visiblePlaylists = remember(playlists) { playlists.filterNot { it.isLiked } }
                if (visiblePlaylists.isEmpty()) {
                    CarDrawerEmptyState(
                        text = "扫码登录酷狗账号后自动同步自建与收藏歌单",
                        actionLabel = "刷新歌单",
                        onAction = { libraryViewModel.refreshPlaylistsOnly() }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 18.dp)
                    ) {
                        items(visiblePlaylists, key = { it.id }) { pl ->
                            CarGlassPlaylistRow(
                                playlist = pl,
                                thumbSizePx = thumbSizePx,
                                onClick = {
                                    selectedPlaylistTitle = pl.title.ifBlank { "未命名歌单" }
                                    libraryViewModel.openLibrarySection("歌单:${pl.id}")
                                    subView = CarLibrarySubView.PLAYLIST_TRACKS
                                }
                            )
                        }
                    }
                }
            }

            CarLibrarySubView.CLOUD_DISK -> {
                val cloudSongs = remember(cloudSummary.songs) { cloudSummary.songs.map { it.toSong() } }
                if (cloudLoading && cloudSongs.isEmpty()) {
                    CarDrawerLoadingState("正在同步个人音乐云盘...")
                } else if (cloudSongs.isEmpty()) {
                    CarDrawerEmptyState("个人音乐云盘暂无曲目", "刷新云盘") { libraryViewModel.loadCloudDisk(force = true) }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 18.dp)
                    ) {
                        itemsIndexed(cloudSongs, key = { idx, s -> "cloud_${idx}_${s.id}" }) { idx, song ->
                            CarGlassSongRow(
                                index = idx + 1,
                                song = song,
                                isPlaying = song.id == currentSongId,
                                thumbSizePx = thumbSizePx,
                                accentColor = accentSecondary,
                                onClick = { onPlay(song, cloudSongs) },
                                onPlayNext = { onPlayNext(song) }
                            )
                        }
                    }
                }
            }

            CarLibrarySubView.LOCAL_USB -> {
                val localSongs = remember(localTracks) { localTracks.map { it.toSong() } }
                if (localSongs.isEmpty()) {
                    CarDrawerEmptyState(
                        text = if (localScanProgress.isScanning) {
                            "正在扫描：${localScanProgress.message.ifBlank { "识别无损音源..." }}"
                        } else {
                            "尚未导入本地或 U 盘音乐目录，支持 FLAC / WAV / MP3 / DSD 离线无损畅播"
                        },
                        actionLabel = "选择 U 盘 / 音乐目录",
                        onAction = { folderPicker.launch(null) }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 18.dp)
                    ) {
                        itemsIndexed(localSongs, key = { idx, s -> "usb_${idx}_${s.id}" }) { idx, song ->
                            CarGlassSongRow(
                                index = idx + 1,
                                song = song,
                                isPlaying = song.id == currentSongId,
                                thumbSizePx = thumbSizePx,
                                accentColor = ConceptMintAccent,
                                onClick = { onPlay(song, localSongs) },
                                onPlayNext = { onPlayNext(song) }
                            )
                        }
                    }
                }
            }

            CarLibrarySubView.LIKED,
            CarLibrarySubView.RECENT,
            CarLibrarySubView.PLAYLIST_TRACKS -> {
                val list = if (subView == CarLibrarySubView.LIKED && sectionSongs.isEmpty()) likedSongs else sectionSongs
                if (libraryLoading && list.isEmpty()) {
                    CarDrawerLoadingState("正在加载歌单曲目...")
                } else if (list.isEmpty()) {
                    CarDrawerEmptyState("当前列表为空", "返回") { subView = CarLibrarySubView.ROOT }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 18.dp)
                    ) {
                        itemsIndexed(list, key = { idx, s -> "lib_${idx}_${s.id}" }) { idx, song ->
                            CarGlassSongRow(
                                index = idx + 1,
                                song = song,
                                isPlaying = song.id == currentSongId,
                                thumbSizePx = thumbSizePx,
                                accentColor = accentSecondary,
                                onClick = { onPlay(song, list) },
                                onPlayNext = { onPlayNext(song) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CarRadioDrawerContent(
    homeViewModel: HomeViewModel,
    thumbSizePx: Int,
    accentSecondary: Color,
    onPlayStation: (List<Song>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val channels by homeViewModel.fmChannels.collectAsState()
    var loadingChannelId by remember { mutableStateOf<String?>(null) }

    if (channels.isEmpty()) {
        CarDrawerEmptyState("正在加载随心听场景电台...", "刷新") { homeViewModel.refresh() }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            items(channels, key = { it.id }) { channel: FmChannel ->
                val isLoading = loadingChannelId == channel.id
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.075f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
                        .softCardClickable(
                            enabled = loadingChannelId == null,
                            shape = RoundedCornerShape(20.dp),
                            onClick = {
                                loadingChannelId = channel.id
                                scope.launch {
                                    val songs = runCatching {
                                        homeViewModel.getFmChannelSongs(channel.id, size = 24, forceNextBatch = true)
                                    }.getOrDefault(homeViewModel.getCachedFmChannelSongs(channel.id))
                                    loadingChannelId = null
                                    if (songs.isNotEmpty()) {
                                        onPlayStation(songs)
                                    } else {
                                        Toast.makeText(context, "电台暂时无曲目，请稍后再试", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CarDownsampledCoverThumb(
                            url = channel.coverUrl,
                            sizePx = thumbSizePx,
                            modifier = Modifier
                                .size(74.dp)
                                .clip(RoundedCornerShape(16.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = channel.name,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isLoading) "正在调频起播..." else channel.desc.ifBlank { channel.category },
                                fontSize = 12.sp,
                                color = if (isLoading) accentSecondary else Color.White.copy(alpha = 0.62f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CarChartsDrawerContent(
    homeViewModel: HomeViewModel,
    currentSongId: String?,
    thumbSizePx: Int,
    accentSecondary: Color,
    onPlay: (Song, List<Song>) -> Unit,
    onPlayNext: (Song) -> Unit
) {
    val scope = rememberCoroutineScope()
    val ranks by homeViewModel.ranks.collectAsState()
    var selectedRank by remember { mutableStateOf<RankItem?>(null) }
    var rankSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loadingRank by remember { mutableStateOf(false) }

    BackHandler(enabled = selectedRank != null) {
        selectedRank = null
    }

    val activeRank = selectedRank
    if (activeRank != null) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.10f),
                    modifier = Modifier.softCardClickable(
                        shape = RoundedCornerShape(14.dp),
                        onClick = { selectedRank = null }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(activeRank.rankName, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    }
                }
                if (rankSongs.isNotEmpty()) {
                    CarChoicePill(
                        label = "播放榜单",
                        selected = true,
                        accent = accentSecondary,
                        onClick = { rankSongs.firstOrNull()?.let { onPlay(it, rankSongs) } }
                    )
                }
            }

            if (loadingRank && rankSongs.isEmpty()) {
                CarDrawerLoadingState("正在拉取「${activeRank.rankName}」...")
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 18.dp)
                ) {
                    itemsIndexed(rankSongs, key = { idx, s -> "rank_${activeRank.rankId}_${idx}_${s.id}" }) { idx, song ->
                        CarGlassSongRow(
                            index = idx + 1,
                            song = song,
                            isPlaying = song.id == currentSongId,
                            thumbSizePx = thumbSizePx,
                            accentColor = accentSecondary,
                            onClick = { onPlay(song, rankSongs) },
                            onPlayNext = { onPlayNext(song) }
                        )
                    }
                }
            }
        }
    } else if (ranks.isEmpty()) {
        CarDrawerEmptyState("正在加载巅峰排行榜...", "刷新") { homeViewModel.refresh() }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            items(ranks.take(16), key = { it.rankId }) { rank ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.075f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
                        .softCardClickable(
                            shape = RoundedCornerShape(20.dp),
                            onClick = {
                                selectedRank = rank
                                rankSongs = homeViewModel.getCachedRankSongs(rank.rankId)
                                loadingRank = true
                                scope.launch {
                                    val loaded = runCatching { homeViewModel.getRankSongs(rank.rankId) }.getOrDefault(rankSongs)
                                    if (loaded.isNotEmpty()) rankSongs = loaded
                                    loadingRank = false
                                }
                            }
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CarDownsampledCoverThumb(
                            url = rank.coverUrl,
                            sizePx = thumbSizePx,
                            modifier = Modifier
                                .size(74.dp)
                                .clip(RoundedCornerShape(16.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = rank.rankName,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = rank.updateFrequency.ifBlank { rank.category },
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.62f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CarVoiceSearchDrawerContent(
    searchViewModel: SearchViewModel,
    currentSongId: String?,
    thumbSizePx: Int,
    accentSecondary: Color,
    onPlay: (Song, List<Song>) -> Unit,
    onPlayNext: (Song) -> Unit
) {
    val context = LocalContext.current
    val query by searchViewModel.query.collectAsState()
    val hasSearched by searchViewModel.hasSearched.collectAsState()
    val loading by searchViewModel.loading.collectAsState()
    val complexResult by searchViewModel.complexResult.collectAsState()
    val songResults by searchViewModel.songResults.collectAsState()
    val hotKeywords by searchViewModel.hotKeywords.collectAsState()
    val history by searchViewModel.searchHistory.collectAsState()

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotBlank()) {
                searchViewModel.search(spoken)
            }
        }
    }

    val flatResults = remember(complexResult.songs, songResults) {
        if (complexResult.songs.isNotEmpty()) complexResult.songs else songResults
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 大字搜索框 + 语音麦快捷按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { searchViewModel.onQueryChange(it) },
                placeholder = { Text("说歌名、歌手或点击右侧语音点歌", fontSize = 14.5.sp, color = Color.White.copy(alpha = 0.48f)) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.SemiBold),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { searchViewModel.search(query) }),
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { searchViewModel.onQueryChange("") }) {
                            Icon(Icons.Rounded.Clear, contentDescription = "清空", tint = Color.White.copy(alpha = 0.72f))
                        }
                    }
                },
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentSecondary,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                    focusedContainerColor = Color.White.copy(alpha = 0.06f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                    cursorColor = accentSecondary
                ),
                modifier = Modifier.weight(1f)
            )

            // 语音点歌大麦按钮
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = accentSecondary.copy(alpha = 0.24f),
                modifier = Modifier
                    .size(56.dp)
                    .border(1.dp, accentSecondary.copy(alpha = 0.60f), RoundedCornerShape(18.dp))
                    .softCardClickable(
                        shape = RoundedCornerShape(18.dp),
                        onClick = {
                            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出想听的歌名或歌手")
                            }
                            runCatching { voiceLauncher.launch(speechIntent) }.onFailure {
                                Toast.makeText(context, "当前车机未安装系统语音识别组件，可直接输入或呼唤车机语音助手", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Mic, contentDescription = "语音搜索", tint = accentSecondary, modifier = Modifier.size(26.dp))
                }
            }
        }

        if (!hasSearched || query.isBlank()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (history.isNotEmpty()) {
                    item {
                        Text("最近搜过", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.64f))
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            history.take(10).forEach { kw ->
                                CarChoicePill(label = kw, selected = false, accent = accentSecondary) {
                                    searchViewModel.search(kw)
                                }
                            }
                        }
                    }
                }
                if (hotKeywords.isNotEmpty()) {
                    item {
                        Text("车载热搜", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.64f))
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            hotKeywords.take(14).forEach { kw ->
                                CarChoicePill(label = kw, selected = false, accent = ConceptMintAccent) {
                                    searchViewModel.search(kw)
                                }
                            }
                        }
                    }
                }
            }
        } else if (loading && flatResults.isEmpty()) {
            CarDrawerLoadingState("正在搜索「$query」...")
        } else if (flatResults.isEmpty()) {
            CarDrawerEmptyState("未找到与「$query」匹配的歌曲", "重新搜索") { searchViewModel.search(query) }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 18.dp)
            ) {
                itemsIndexed(flatResults, key = { idx, s -> "search_${idx}_${s.id}" }) { idx, song ->
                    CarGlassSongRow(
                        index = idx + 1,
                        song = song,
                        isPlaying = song.id == currentSongId,
                        thumbSizePx = thumbSizePx,
                        accentColor = accentSecondary,
                        onClick = { onPlay(song, flatResults) },
                        onPlayNext = { onPlayNext(song) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CarPlayQueueDrawerContent(
    queue: List<Song>,
    currentIndex: Int,
    playMode: PlayMode,
    thumbSizePx: Int,
    accentSecondary: Color,
    onCyclePlayMode: () -> Unit,
    onSelectSong: (Song) -> Unit
) {
    val modeLabel = when (playMode) {
        PlayMode.REPEAT_ALL -> "列表循环"
        PlayMode.SHUFFLE -> "随机播放"
        PlayMode.REPEAT_ONE -> "单曲循环"
    }
    val modeIcon = when (playMode) {
        PlayMode.REPEAT_ALL -> Icons.Rounded.Repeat
        PlayMode.SHUFFLE -> Icons.Rounded.Shuffle
        PlayMode.REPEAT_ONE -> Icons.Rounded.RepeatOne
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "当前待播 · 共 ${queue.size} 首",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.72f)
            )
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = accentSecondary.copy(alpha = 0.20f),
                modifier = Modifier.softCardClickable(shape = RoundedCornerShape(14.dp), onClick = onCyclePlayMode)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(modeIcon, contentDescription = null, tint = accentSecondary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(modeLabel, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, color = accentSecondary)
                }
            }
        }

        if (queue.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("当前待播队列为空", fontSize = 16.sp, color = Color.White.copy(alpha = 0.56f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 18.dp)
            ) {
                itemsIndexed(queue, key = { idx, s -> "q_${idx}_${s.id}" }) { idx, song ->
                    CarGlassSongRow(
                        index = idx + 1,
                        song = song,
                        isPlaying = idx == currentIndex,
                        thumbSizePx = thumbSizePx,
                        accentColor = accentSecondary,
                        onClick = { onSelectSong(song) },
                        onPlayNext = null
                    )
                }
            }
        }
    }
}

/**
 * 车机大行高毛玻璃单曲条目（>= 70dp 易于行车点选）：
 * 严格遵循 C2 裁剪规范：点击即播，右侧仅可选展示一枚轻触「下一首播放」图标，无复杂更多菜单。
 */
@Composable
private fun CarGlassSongRow(
    index: Int,
    song: Song,
    isPlaying: Boolean,
    thumbSizePx: Int,
    accentColor: Color,
    onClick: () -> Unit,
    onPlayNext: (() -> Unit)?
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (isPlaying) accentColor.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.055f),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .border(
                width = if (isPlaying) 1.2.dp else 0.8.dp,
                color = if (isPlaying) accentColor.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.07f),
                shape = RoundedCornerShape(18.dp)
            )
            .softCardClickable(shape = RoundedCornerShape(18.dp), onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = index.toString().padStart(2, '0'),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isPlaying) accentColor else Color.White.copy(alpha = 0.45f),
                modifier = Modifier.width(28.dp)
            )

            CarDownsampledCoverThumb(
                url = song.coverUrl,
                sizePx = thumbSizePx,
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = song.title.ifBlank { "未知歌曲" },
                        fontSize = 15.5.sp,
                        fontWeight = if (isPlaying) FontWeight.ExtraBold else FontWeight.Bold,
                        color = if (isPlaying) accentColor else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (song.isHiRes || song.isLossless) {
                        Spacer(modifier = Modifier.width(6.dp))
                        val badgeColor = if (song.isHiRes) VipGold else ConceptMintAccent
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = badgeColor.copy(alpha = 0.20f)
                        ) {
                            Text(
                                text = if (song.isHiRes) "Hi-Res" else "SQ",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = song.artist.ifBlank { "未知歌手" },
                    fontSize = 12.5.sp,
                    color = Color.White.copy(alpha = 0.64f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // C2：仅保留单枚轻触「下一首播放」图标
            if (onPlayNext != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.08f),
                    modifier = Modifier
                        .size(44.dp)
                        .softCardClickable(shape = CircleShape, onClick = onPlayNext)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                            contentDescription = "下一首播放",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CarGlassPlaylistRow(
    playlist: Playlist,
    thumbSizePx: Int,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.06f),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 74.dp)
            .softCardClickable(shape = RoundedCornerShape(18.dp), onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CarDownsampledCoverThumb(
                url = playlist.coverUrl,
                sizePx = thumbSizePx,
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(14.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.title.ifBlank { "未命名歌单" },
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = if (playlist.trackCount > 0) "${playlist.trackCount} 首" else if (playlist.isCreatedByMe) "自建歌单" else "收藏歌单",
                    fontSize = 12.5.sp,
                    color = Color.White.copy(alpha = 0.62f)
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
private fun CarLibraryQuickTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = accent.copy(alpha = 0.14f),
        modifier = modifier
            .height(78.dp)
            .border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(18.dp))
            .softCardClickable(shape = RoundedCornerShape(18.dp), onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(accent.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
                Text(subtitle, fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.66f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CarDownsampledCoverThumb(
    url: String,
    sizePx: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(
        modifier = modifier.background(Color(0xFF152033)),
        contentAlignment = Alignment.Center
    ) {
        if (url.isNotBlank()) {
            val req = remember(url, sizePx) {
                ImageRequest.Builder(context)
                    .data(url)
                    .size(sizePx, sizePx)
                    .allowHardware(false)
                    .build()
            }
            AsyncImage(
                model = req,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = ConceptCyanAccent.copy(alpha = 0.65f))
        }
    }
}

@Composable
private fun CarDrawerLoadingState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = ConceptCyanAccent, strokeWidth = 3.dp)
            Text(message, fontSize = 14.5.sp, color = Color.White.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun CarDrawerEmptyState(
    text: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(text, fontSize = 15.sp, color = Color.White.copy(alpha = 0.70f))
            CarChoicePill(label = actionLabel, selected = true, accent = ConceptCyanAccent, onClick = onAction)
        }
    }
}
