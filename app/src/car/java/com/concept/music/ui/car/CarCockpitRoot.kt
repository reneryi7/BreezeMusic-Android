package com.concept.music.ui.car

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.concept.music.MainActivity
import com.concept.music.data.model.AudioQuality
import com.concept.music.service.CarMediaLibraryHelper
import com.concept.music.service.CarSessionManager
import com.concept.music.service.ConceptMusicWidgetUpdater
import com.concept.music.ui.account.VerificationDialog
import com.concept.music.ui.components.NowPlayingSelectableBackground
import com.concept.music.ui.components.extractCoverMeshColors
import com.concept.music.ui.theme.*
import com.concept.music.ui.viewmodel.CoverStyle
import com.concept.music.ui.viewmodel.HomeViewModel
import com.concept.music.ui.viewmodel.LibraryViewModel
import com.concept.music.ui.viewmodel.MusicPlayerViewModel
import com.concept.music.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.launch

/**
 * 车机独立包（`com.concept.music.car`）根宿主容器：
 * - 物理隔离在 `app/src/car/` 源码集，确保 `carRelease` 在 R8 压缩期自动摇树剔除全部手机/平板不可达页面；
 * - 负责串联车机 5 大性能治理策略（三档硬件算力降帧、前台常亮后台释放、封面主色轻量取色、语音点歌与桌面小组件双向闭环）。
 */
@Composable
fun CarCockpitRoot(
    musicPlayerViewModel: MusicPlayerViewModel = viewModel(),
    homeViewModel: HomeViewModel = viewModel(),
    libraryViewModel: LibraryViewModel = viewModel(),
    searchViewModel: SearchViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isLifecycleStarted = rememberIsLifecycleStarted()
    val layoutSpec = rememberCarCockpitLayoutSpec()

    val playerState by musicPlayerViewModel.uiState.collectAsState()
    val renderCaps by CarPerformanceManager.capabilities.collectAsState()
    val carState by CarSessionManager.state.collectAsState()
    val likedSongs by libraryViewModel.likedSongs.collectAsState()
    val pendingVerification by libraryViewModel.pendingVerification.collectAsState()

    var activeDrawer by remember { mutableStateOf<CarDrawerType?>(null) }
    var showCabinAcoustic by remember { mutableStateOf(false) }
    var showMinimalSettings by remember { mutableStateOf(false) }
    var showQrLogin by remember { mutableStateOf(false) }
    var showQualityPicker by remember { mutableStateOf(false) }
    var isNightEyeCareMode by remember { mutableStateOf(false) }

    // 确保常驻车机服务与性能配置完成水合
    LaunchedEffect(Unit) {
        CarPerformanceManager.init(context)
        CarSessionManager.init(context)
    }

    // 1. 仅在前后台生命周期切换时同步前后台状态（切勿绑定 playerState.isPlaying，防止每次暂停/播放触发多余账号网络刷新）
    DisposableEffect(isLifecycleStarted) {
        musicPlayerViewModel.onAppForegroundChanged(isLifecycleStarted)
        libraryViewModel.onForeground(isLifecycleStarted)
        onDispose {}
    }

    // 2. 车机窗口常亮锁（仅在前台播放期间防休眠，暂停或切去导航立刻释放）
    DisposableEffect(isLifecycleStarted, playerState.isPlaying) {
        val window = (context as? Activity)?.window
        if (isLifecycleStarted && playerState.isPlaying) {
            runCatching { window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        } else {
            runCatching { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        onDispose {
            runCatching { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    // 3. 响应 4×2 车机桌面小组件主卡片点击：自动收起抽屉/浮层直达主驾驶播控舞台
    val openNowPlayingToken by MainActivity.openNowPlayingRequestFlow.collectAsState()
    LaunchedEffect(openNowPlayingToken) {
        if (openNowPlayingToken != 0L) {
            MainActivity.openNowPlayingRequestFlow.value = 0L
            activeDrawer = null
            showCabinAcoustic = false
            showMinimalSettings = false
            showQualityPicker = false
            showQrLogin = false
        }
    }

    // 绑定私人 FM 队列余量 <= 3 首无限续杯回调
    DisposableEffect(musicPlayerViewModel, homeViewModel) {
        musicPlayerViewModel.onPersonalFmQueueNeedsRefill = { currentQueue ->
            homeViewModel.refillMorePersonalFmSongs(currentQueue) { moreSongs ->
                musicPlayerViewModel.appendPersonalFmQueueSongs(moreSongs)
            }
        }
        onDispose {
            musicPlayerViewModel.onPersonalFmQueueNeedsRefill = null
        }
    }

    // 绑定 4×2 车机桌面流光玻璃小组件红心与播控指令
    DisposableEffect(musicPlayerViewModel, libraryViewModel) {
        ConceptMusicWidgetUpdater.onToggleFavorite = {
            val activeSong = musicPlayerViewModel.uiState.value.currentSong
            if (activeSong != null) {
                libraryViewModel.toggleFavorite(activeSong) { ok, liked, msg ->
                    if (ok) {
                        ConceptMusicWidgetUpdater.updateState(context, isFavorite = liked)
                    } else if (msg.isNotBlank()) {
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        ConceptMusicWidgetUpdater.onCyclePlayMode = {
            val mode = musicPlayerViewModel.cyclePlayMode()
            ConceptMusicWidgetUpdater.updateState(context, playMode = mode)
        }
        onDispose {
            ConceptMusicWidgetUpdater.onToggleFavorite = null
            ConceptMusicWidgetUpdater.onCyclePlayMode = null
        }
    }

    // 响应车机原生语音助理下发的 MEDIA_PLAY_FROM_SEARCH 意图
    val incomingVoiceSearch by MainActivity.incomingVoiceSearchFlow.collectAsState()
    LaunchedEffect(incomingVoiceSearch) {
        val req = incomingVoiceSearch ?: return@LaunchedEffect
        MainActivity.incomingVoiceSearchFlow.value = null
        val (songs, isFm) = CarMediaLibraryHelper.resolveVoicePlayQueue(context, req.first, req.second)
        val first = songs.firstOrNull()
        if (first != null) {
            musicPlayerViewModel.playSong(first, songs, isPersonalFm = isFm)
            activeDrawer = null
            val tip = if (isFm) "已为你开启猜你喜欢私人 FM" else "正在播放：${first.title} · ${first.artist}"
            Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "未找到相关曲目，已为你打开语音搜索", Toast.LENGTH_SHORT).show()
            activeDrawer = CarDrawerType.SEARCH
        }
    }

    // 错误轻提示
    val playerError = playerState.error
    LaunchedEffect(playerError) {
        if (!playerError.isNullOrBlank()) {
            Toast.makeText(context, playerError, Toast.LENGTH_SHORT).show()
        }
    }

    // 动态封面主色提取（A6：用于进度条、主播控键、流光背景与光晕联动）
    var meshPrimary by remember { mutableStateOf(Color(0xFF15386B)) }
    var meshSecondary by remember { mutableStateOf(Color(0xFF0A4F6E)) }
    var meshTertiary by remember { mutableStateOf(Color(0xFF1D1D42)) }
    var accentPrimary by remember { mutableStateOf(ConceptPrimary) }
    var accentSecondary by remember { mutableStateOf(ConceptCyanAccent) }

    val activeCoverUrl = playerState.currentSong?.coverUrl.orEmpty()
    LaunchedEffect(activeCoverUrl, playerState.dynamicCoverAccentEnabled) {
        if (activeCoverUrl.isNotBlank() && playerState.dynamicCoverAccentEnabled) {
            val extracted = extractCoverMeshColors(context, activeCoverUrl)
            if (extracted != null) {
                meshPrimary = extracted.meshPrimary
                meshSecondary = extracted.meshSecondary
                meshTertiary = extracted.meshTertiary
                accentPrimary = extracted.dominantAccent.readableOnDarkSurface(0.45f)
                accentSecondary = extracted.secondaryAccent.readableOnDarkSurface(0.50f)
            }
        } else {
            meshPrimary = Color(0xFF15386B)
            meshSecondary = Color(0xFF0A4F6E)
            meshTertiary = Color(0xFF1D1D42)
            accentPrimary = ConceptPrimary
            accentSecondary = ConceptCyanAccent
        }
    }

    val isCurrentFavorite = remember(playerState.currentSong?.id, likedSongs) {
        libraryViewModel.isSongLiked(playerState.currentSong, likedSongs)
    }

    // 返回键逐层优雅关闭浮层
    BackHandler(
        enabled = showQrLogin || showQualityPicker || showMinimalSettings || showCabinAcoustic || activeDrawer != null || isNightEyeCareMode
    ) {
        when {
            showQrLogin -> showQrLogin = false
            showQualityPicker -> showQualityPicker = false
            showMinimalSettings -> showMinimalSettings = false
            showCabinAcoustic -> showCabinAcoustic = false
            activeDrawer != null -> activeDrawer = null
            isNightEyeCareMode -> isNightEyeCareMode = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF060A12))
    ) {
        // 底层全屏连续动量流光背景（按 CarPerformanceProfile 自动节流 FPS / 省电静态化，夜间护眼时完全跳过）
        if (!isNightEyeCareMode) {
            NowPlayingSelectableBackground(
                coverUrl = activeCoverUrl,
                backgroundStyle = playerState.playerBackgroundStyle,
                isPlaying = playerState.isPlaying,
                showLyricView = true,
                dimAlpha = if (isConceptDarkTheme()) 0.72f else 0.56f,
                meshColorPrimary = meshPrimary,
                meshColorSecondary = meshSecondary,
                meshColorTertiary = meshTertiary,
                accentPrimary = accentPrimary,
                accentSecondary = accentSecondary,
                maxFps = renderCaps.backgroundMaxFps,
                enableAnimatedEffect = renderCaps.enableAnimatedBackground
            )
        }

        // 主驾驶舞台（自动适配带鱼屏 / 16:9 横屏 / DiLink 旋转竖屏 / 1/3 导航分屏）
        CarCockpitStage(
            layoutSpec = layoutSpec,
            renderCaps = renderCaps,
            playerState = playerState,
            smoothPositionProvider = { musicPlayerViewModel.currentSmoothPositionMs() },
            isFavorite = isCurrentFavorite,
            cabinMode = carState.cabinAcousticMode,
            accentPrimary = accentPrimary,
            accentSecondary = accentSecondary,
            isDrawerOpen = activeDrawer != null,
            activeDrawer = activeDrawer,
            isNightEyeCareMode = isNightEyeCareMode,
            onToggleNightEyeCare = { isNightEyeCareMode = !isNightEyeCareMode },
            onTogglePlay = { musicPlayerViewModel.togglePlayPause() },
            onPrevious = { musicPlayerViewModel.skipToPrevious() },
            onNext = { musicPlayerViewModel.skipToNext() },
            onSeekTo = { musicPlayerViewModel.seekTo(it) },
            onSeekToLyricLine = { musicPlayerViewModel.seekToLyricLine(it) },
            onToggleFavorite = {
                val song = playerState.currentSong ?: return@CarCockpitStage
                libraryViewModel.toggleFavorite(song) { ok, liked, msg ->
                    if (ok) {
                        ConceptMusicWidgetUpdater.updateState(context, isFavorite = liked)
                        Toast.makeText(context, if (liked) "已加入我喜欢" else "已移出我喜欢", Toast.LENGTH_SHORT).show()
                    } else if (msg.isNotBlank()) {
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDislikeFmSong = {
                val song = playerState.currentSong ?: return@CarCockpitStage
                homeViewModel.dislikeCurrentPersonalFmSong(
                    song = song,
                    onNextPlay = { nextSong, updatedQueue ->
                        musicPlayerViewModel.playSong(nextSong, updatedQueue, isPersonalFm = true)
                    },
                    onFeedbackMessage = { msg ->
                        if (msg.isNotBlank()) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                )
            },
            onToggleCoverStyle = {
                val nextStyle = if (playerState.coverStyle == CoverStyle.VINYL) CoverStyle.SQUARE else CoverStyle.VINYL
                musicPlayerViewModel.setCoverStyle(nextStyle)
            },
            onToggleTranslation = { musicPlayerViewModel.toggleTranslation() },
            onTogglePhonetic = { musicPlayerViewModel.togglePhonetic() },
            onOpenQualityPicker = { showQualityPicker = true },
            onOpenCabinAcoustic = { showCabinAcoustic = true },
            onOpenSettings = { showMinimalSettings = true },
            onOpenDrawer = { target ->
                activeDrawer = if (activeDrawer == target) null else target
            },
            onQuickStartFm = {
                val cachedFm = homeViewModel.personalFmSongs.value
                if (cachedFm.isNotEmpty()) {
                    musicPlayerViewModel.playSong(cachedFm.first(), cachedFm, isPersonalFm = true)
                } else {
                    homeViewModel.refreshPersonalFm { loaded ->
                        val first = loaded.firstOrNull()
                        if (first != null) {
                            musicPlayerViewModel.playSong(first, loaded, isPersonalFm = true)
                        } else {
                            scope.launch {
                                Toast.makeText(context, "暂未获取到推荐曲目，请检查车机网络", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            },
            onOpenQrLogin = { showQrLogin = true }
        )

        // 右侧悬浮毛玻璃音源 / 待播队列抽屉
        AnimatedVisibility(
            visible = activeDrawer != null,
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut() + slideOutHorizontally { it / 3 }
        ) {
            val currentDrawer = activeDrawer ?: return@AnimatedVisibility
            CarSourceGlassDrawer(
                drawerType = currentDrawer,
                layoutSpec = layoutSpec,
                renderCaps = renderCaps,
                accentPrimary = accentPrimary,
                accentSecondary = accentSecondary,
                playerViewModel = musicPlayerViewModel,
                homeViewModel = homeViewModel,
                libraryViewModel = libraryViewModel,
                searchViewModel = searchViewModel,
                onSwitchDrawer = { activeDrawer = it },
                onPlaySongAndClose = { song, queue, isFm ->
                    musicPlayerViewModel.playSong(song, queue, isPersonalFm = isFm)
                    activeDrawer = null
                },
                onPlayNextSong = { song ->
                    musicPlayerViewModel.playNext(song)
                    Toast.makeText(context, "已加入下一首播放：${song.title}", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { activeDrawer = null }
            )
        }

        // 64-bit 俯视车厢皇帝位声学卡片
        AnimatedVisibility(visible = showCabinAcoustic, enter = fadeIn(), exit = fadeOut()) {
            CarCabinAcousticOverlayCard(
                renderCaps = renderCaps,
                accentColor = accentPrimary,
                onDismiss = { showCabinAcoustic = false }
            )
        }

        // 极简座舱设置卡
        AnimatedVisibility(visible = showMinimalSettings, enter = fadeIn(), exit = fadeOut()) {
            CarMinimalSettingsOverlay(
                playerViewModel = musicPlayerViewModel,
                libraryViewModel = libraryViewModel,
                onOpenQrLogin = {
                    showMinimalSettings = false
                    showQrLogin = true
                },
                onOpenCabinAcoustic = { showCabinAcoustic = true },
                onDismiss = { showMinimalSettings = false }
            )
        }

        // 快捷音质切换弹窗（点击封面旁音质微章直达）
        AnimatedVisibility(visible = showQualityPicker, enter = fadeIn(), exit = fadeOut()) {
            CarQuickQualityOverlay(
                currentQuality = playerState.preferredQuality,
                onSelectQuality = { q ->
                    musicPlayerViewModel.setPreferredQuality(q)
                    showQualityPicker = false
                },
                onDismiss = { showQualityPicker = false }
            )
        }

        // 大屏双扫码登录浮层（酷狗扫码 + QQ 扫码）
        AnimatedVisibility(visible = showQrLogin, enter = fadeIn(), exit = fadeOut()) {
            CarQrLoginOverlay(
                libraryViewModel = libraryViewModel,
                onDismiss = { showQrLogin = false }
            )
        }

        // 安全风控验证弹窗（如登录过程触发酷狗安全滑块）
        pendingVerification?.let { req ->
            VerificationDialog(
                request = req,
                onAnswer = { ans -> libraryViewModel.verificationAnswer(req.id, ans) }
            )
        }
    }
}

@Composable
private fun CarQuickQualityOverlay(
    currentQuality: AudioQuality,
    onSelectQuality: (AudioQuality) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xA6040812))
            .softCardClickable(shape = RoundedCornerShape(0.dp), clip = false, pressScale = 1f, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = Color(0xF60B1322),
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth(0.84f)
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(26.dp))
                .softCardClickable(shape = RoundedCornerShape(26.dp), clip = false, pressScale = 1f, onClick = {})
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "选择座舱播放音质",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                listOf(
                    Triple(AudioQuality.HI_RES, "Hi-Res 母带级高解析", "最高 24-bit / 96kHz 录音室无损"),
                    Triple(AudioQuality.LOSSLESS, "SQ 无损 FLAC", "CD 级无损保真，车载发烧推荐"),
                    Triple(AudioQuality.HIGH, "HQ 高品 320K", "流畅省流兼顾清晰细节"),
                    Triple(AudioQuality.STANDARD, "标准 128K", "弱网路段极速起播")
                ).forEach { (q, title, desc) ->
                    val selected = currentQuality == q
                    val accent = if (q == AudioQuality.HI_RES) VipGold else ConceptCyanAccent
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = if (selected) 1.3.dp else 0.8.dp,
                                color = if (selected) accent.copy(alpha = 0.70f) else Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .softCardClickable(shape = RoundedCornerShape(16.dp), onClick = { onSelectQuality(q) })
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(title, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold, color = if (selected) accent else Color.White)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(desc, fontSize = 12.sp, color = Color.White.copy(alpha = 0.64f))
                        }
                    }
                }
            }
        }
    }
}
