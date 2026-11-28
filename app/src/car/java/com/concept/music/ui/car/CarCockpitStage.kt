package com.concept.music.ui.car

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.concept.music.data.model.LyricData
import com.concept.music.data.model.LyricLine
import com.concept.music.data.model.Song
import com.concept.music.service.CarCabinAcousticMode
import com.concept.music.ui.components.CometTailProgressBar
import com.concept.music.ui.components.MasterVinylTurntableView
import com.concept.music.ui.components.StaticRoundedSquareCoverView
import com.concept.music.ui.components.formatDuration
import com.concept.music.ui.theme.*
import com.concept.music.ui.viewmodel.CoverStyle
import com.concept.music.ui.viewmodel.PlayerUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

internal enum class CarDrawerType(val label: String, val icon: ImageVector) {
    PERSONAL_FM("猜你喜欢", Icons.Rounded.AutoAwesome),
    DAILY_RECOMMEND("每日推荐", Icons.Rounded.Today),
    MY_LIBRARY("我的歌单", Icons.Rounded.LibraryMusic),
    RADIO("随心电台", Icons.Rounded.Radio),
    CHARTS("巅峰榜单", Icons.Rounded.Leaderboard),
    SEARCH("语音搜索", Icons.Rounded.Search),
    PLAY_QUEUE("待播队列", Icons.AutoMirrored.Rounded.QueueMusic)
}

/**
 * 车机独立包「流光玻璃座舱」主驾驶舞台：
 * 自动适配 4 种车机窗口形态（超宽带鱼屏 / 标准 16:9 横屏 / 比亚迪旋转竖屏 / 1/3 高德导航分屏卡），
 * 并在夜间高速场景支持一键切入纯黑防眩光护眼态。
 */
@Composable
internal fun CarCockpitStage(
    layoutSpec: CarCockpitLayoutSpec,
    renderCaps: CarRenderCapabilities,
    playerState: PlayerUiState,
    smoothPositionProvider: () -> Long,
    isFavorite: Boolean,
    cabinMode: CarCabinAcousticMode,
    accentPrimary: Color,
    accentSecondary: Color,
    isDrawerOpen: Boolean,
    activeDrawer: CarDrawerType?,
    isNightEyeCareMode: Boolean,
    onToggleNightEyeCare: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekToLyricLine: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onDislikeFmSong: () -> Unit,
    onToggleCoverStyle: () -> Unit,
    onToggleTranslation: () -> Unit,
    onTogglePhonetic: () -> Unit,
    onOpenQualityPicker: () -> Unit,
    onOpenCabinAcoustic: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: (CarDrawerType) -> Unit,
    onQuickStartFm: () -> Unit,
    onOpenQrLogin: () -> Unit
) {
    if (isNightEyeCareMode) {
        CarNightEyeCareStage(
            song = playerState.currentSong,
            isPlaying = playerState.isPlaying,
            lyricData = playerState.currentLyric,
            currentLyricIndex = playerState.currentLyricIndex,
            onExitNightEyeCare = onToggleNightEyeCare,
            onTogglePlay = onTogglePlay,
            onPrevious = onPrevious,
            onNext = onNext
        )
        return
    }

    val currentSong = playerState.currentSong
    if (currentSong == null) {
        CarWelcomeEmptyStage(
            accentPrimary = accentPrimary,
            accentSecondary = accentSecondary,
            onQuickStartFm = onQuickStartFm,
            onOpenDrawer = onOpenDrawer,
            onOpenQrLogin = onOpenQrLogin,
            onOpenSettings = onOpenSettings
        )
        return
    }

    val isStarted = rememberIsLifecycleStarted()
    var vinylRotationDeg by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playerState.isPlaying, playerState.coverStyle, isStarted, renderCaps.enableAnimatedBackground) {
        if (playerState.isPlaying &&
            playerState.coverStyle == CoverStyle.VINYL &&
            isStarted &&
            renderCaps.enableAnimatedBackground
        ) {
            var lastNanos = 0L
            val minIntervalNanos = if (renderCaps.backgroundMaxFps > 0) (1_000_000_000L / renderCaps.backgroundMaxFps) else 33_333_333L
            var lastTick = 0L
            while (isActive) {
                withFrameNanos { nanos ->
                    if (lastTick > 0L && (nanos - lastTick) < minIntervalNanos) return@withFrameNanos
                    lastTick = nanos
                    val dt = if (lastNanos == 0L) 0.033f else ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0.004f, 0.1f)
                    lastNanos = nanos
                    vinylRotationDeg = (vinylRotationDeg + dt * 20f) % 360f
                }
            }
        }
    }

    when (layoutSpec.mode) {
        CarCockpitLayoutMode.ULTRA_WIDE_COCKPIT,
        CarCockpitLayoutMode.STANDARD_LANDSCAPE_COCKPIT -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = layoutSpec.horizontalPadding, vertical = layoutSpec.verticalPadding),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .then(
                            if (layoutSpec.maxStageWidth != Dp.Unspecified) {
                                Modifier.widthIn(max = layoutSpec.maxStageWidth)
                            } else {
                                Modifier
                            }
                        )
                        .fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左区 45%：主驾黄金盲操区（支持左右滑手势切歌）
                    CarDriverLeftZone(
                        song = currentSong,
                        playerState = playerState,
                        layoutSpec = layoutSpec,
                        renderCaps = renderCaps,
                        smoothPositionProvider = smoothPositionProvider,
                        vinylRotationProvider = { vinylRotationDeg },
                        isFavorite = isFavorite,
                        cabinMode = cabinMode,
                        accentPrimary = accentPrimary,
                        accentSecondary = accentSecondary,
                        onTogglePlay = onTogglePlay,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onSeekTo = onSeekTo,
                        onToggleFavorite = onToggleFavorite,
                        onDislikeFmSong = onDislikeFmSong,
                        onToggleCoverStyle = onToggleCoverStyle,
                        onOpenQualityPicker = onOpenQualityPicker,
                        onOpenCabinAcoustic = onOpenCabinAcoustic,
                        onToggleNightEyeCare = onToggleNightEyeCare,
                        onOpenSettings = onOpenSettings,
                        onOpenQueue = { onOpenDrawer(CarDrawerType.PLAY_QUEUE) },
                        modifier = Modifier
                            .weight(layoutSpec.leftPaneWeight)
                            .fillMaxHeight()
                            .padding(end = 22.dp)
                    )

                    // 右区 55%：光学景深巨幕 KRC 逐字提词器 + 右边缘悬浮音源胶囊条
                    val lyricRecedeScale by animateFloatAsState(
                        targetValue = if (isDrawerOpen) 0.94f else 1f,
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                        label = "carLyricRecedeScale"
                    )
                    val lyricRecedeAlpha by animateFloatAsState(
                        targetValue = if (isDrawerOpen) 0.20f else 1f,
                        animationSpec = tween(260),
                        label = "carLyricRecedeAlpha"
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f - layoutSpec.leftPaneWeight)
                            .fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .graphicsLayer {
                                    scaleX = lyricRecedeScale
                                    scaleY = lyricRecedeScale
                                    alpha = lyricRecedeAlpha
                                }
                        ) {
                            CarOpticalDepthLyricStage(
                                lyricData = playerState.currentLyric,
                                currentLyricIndex = playerState.currentLyricIndex,
                                isPlaying = playerState.isPlaying,
                                showTranslation = playerState.showTranslation,
                                showPhonetic = playerState.showPhonetic,
                                smoothPositionProvider = smoothPositionProvider,
                                lyricOffsetMs = playerState.effectiveLyricOffsetMs,
                                activeFontSp = layoutSpec.lyricActiveFontSp,
                                inactiveFontSp = layoutSpec.lyricInactiveFontSp,
                                enableDepthBlur = renderCaps.enableLyricDepthBlur,
                                accentPrimary = accentPrimary,
                                accentSecondary = accentSecondary,
                                onSeekToLyricLine = onSeekToLyricLine,
                                onToggleTranslation = onToggleTranslation,
                                onTogglePhonetic = onTogglePhonetic
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // 右边缘悬浮半透明音源胶囊条（不用时极简隐形，点开即呼出毛玻璃抽屉）
                        CarRightSourceEdgeDock(
                            activeDrawer = activeDrawer,
                            accentColor = accentSecondary,
                            onSelectSource = onOpenDrawer
                        )
                    }
                }
            }
        }

        CarCockpitLayoutMode.PORTRAIT_COCKPIT -> {
            // 比亚迪 DiLink 旋转竖屏大舞台：上区封面盲操 + 下区巨幕歌词 + 底边缘悬浮音源胶囊条
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = layoutSpec.horizontalPadding, vertical = layoutSpec.verticalPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CarDriverLeftZone(
                    song = currentSong,
                    playerState = playerState,
                    layoutSpec = layoutSpec,
                    renderCaps = renderCaps,
                    smoothPositionProvider = smoothPositionProvider,
                    vinylRotationProvider = { vinylRotationDeg },
                    isFavorite = isFavorite,
                    cabinMode = cabinMode,
                    accentPrimary = accentPrimary,
                    accentSecondary = accentSecondary,
                    onTogglePlay = onTogglePlay,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSeekTo = onSeekTo,
                    onToggleFavorite = onToggleFavorite,
                    onDislikeFmSong = onDislikeFmSong,
                    onToggleCoverStyle = onToggleCoverStyle,
                    onOpenQualityPicker = onOpenQualityPicker,
                    onOpenCabinAcoustic = onOpenCabinAcoustic,
                    onToggleNightEyeCare = onToggleNightEyeCare,
                    onOpenSettings = onOpenSettings,
                    onOpenQueue = { onOpenDrawer(CarDrawerType.PLAY_QUEUE) },
                    modifier = Modifier
                        .widthIn(max = layoutSpec.maxStageWidth)
                        .fillMaxWidth()
                        .weight(0.54f)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .widthIn(max = layoutSpec.maxStageWidth)
                        .fillMaxWidth()
                        .weight(0.38f)
                ) {
                    CarOpticalDepthLyricStage(
                        lyricData = playerState.currentLyric,
                        currentLyricIndex = playerState.currentLyricIndex,
                        isPlaying = playerState.isPlaying,
                        showTranslation = playerState.showTranslation,
                        showPhonetic = playerState.showPhonetic,
                        smoothPositionProvider = smoothPositionProvider,
                        lyricOffsetMs = playerState.effectiveLyricOffsetMs,
                        activeFontSp = layoutSpec.lyricActiveFontSp,
                        inactiveFontSp = layoutSpec.lyricInactiveFontSp,
                        enableDepthBlur = renderCaps.enableLyricDepthBlur,
                        accentPrimary = accentPrimary,
                        accentSecondary = accentSecondary,
                        onSeekToLyricLine = onSeekToLyricLine,
                        onToggleTranslation = onToggleTranslation,
                        onTogglePhonetic = onTogglePhonetic
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                CarBottomCompactSourceStrip(
                    activeDrawer = activeDrawer,
                    accentColor = accentSecondary,
                    onSelectSource = onOpenDrawer
                )
            }
        }

        CarCockpitLayoutMode.COMPACT_SPLIT_CARD -> {
            // 1/3 或 1/2 导航分屏极简竖向悬浮卡片：大封面 + 两行超大歌词 + 三颗盲操大控键
            CarCompactSplitCardStage(
                song = currentSong,
                playerState = playerState,
                layoutSpec = layoutSpec,
                renderCaps = renderCaps,
                isFavorite = isFavorite,
                cabinMode = cabinMode,
                accentPrimary = accentPrimary,
                accentSecondary = accentSecondary,
                onTogglePlay = onTogglePlay,
                onPrevious = onPrevious,
                onNext = onNext,
                onToggleFavorite = onToggleFavorite,
                onOpenCabinAcoustic = onOpenCabinAcoustic,
                onToggleNightEyeCare = onToggleNightEyeCare,
                onOpenDrawer = onOpenDrawer,
                onOpenSettings = onOpenSettings
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CarDriverLeftZone(
    song: Song,
    playerState: PlayerUiState,
    layoutSpec: CarCockpitLayoutSpec,
    renderCaps: CarRenderCapabilities,
    smoothPositionProvider: () -> Long,
    vinylRotationProvider: () -> Float,
    isFavorite: Boolean,
    cabinMode: CarCabinAcousticMode,
    accentPrimary: Color,
    accentSecondary: Color,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onDislikeFmSong: () -> Unit,
    onToggleCoverStyle: () -> Unit,
    onOpenQualityPicker: () -> Unit,
    onOpenCabinAcoustic: () -> Unit,
    onToggleNightEyeCare: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val swipeOffsetX = remember { Animatable(0f) }
    var swipeHintText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .pointerInput(song.id) {
                var totalDx = 0f
                var triggered = false
                detectHorizontalDragGestures(
                    onDragStart = {
                        totalDx = 0f
                        triggered = false
                    },
                    onDragEnd = {
                        scope.launch {
                            swipeOffsetX.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium))
                        }
                        swipeHintText = null
                    },
                    onDragCancel = {
                        scope.launch { swipeOffsetX.animateTo(0f) }
                        swipeHintText = null
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        if (triggered) return@detectHorizontalDragGestures
                        totalDx += dragAmount
                        val visualDx = (totalDx * 0.28f).coerceIn(-68f, 68f)
                        scope.launch { swipeOffsetX.snapTo(visualDx) }
                        swipeHintText = when {
                            totalDx <= -85f -> "松手下一首 ›"
                            totalDx >= 85f -> "‹ 松手上一首"
                            else -> null
                        }
                        if (abs(totalDx) >= 150f) {
                            triggered = true
                            if (totalDx < 0f) onNext() else onPrevious()
                            scope.launch {
                                swipeOffsetX.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium))
                            }
                            swipeHintText = null
                        }
                    }
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 顶部精简状态微章栏（音质 / 64-bit 座舱声场 / 夜间护眼 / 极简设置）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 音质徽章（点击直接切换）
                val isHiRes = playerState.currentQualityLabel.contains("Hi-Res", ignoreCase = true)
                val qColor = if (isHiRes) VipGold else accentSecondary
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = qColor.copy(alpha = 0.20f),
                    modifier = Modifier
                        .border(1.dp, qColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .softCardClickable(shape = RoundedCornerShape(12.dp), onClick = onOpenQualityPicker)
                ) {
                    Text(
                        text = playerState.currentQualityLabel.ifBlank { "高品 320K" },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = qColor,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                    )
                }

                // 64-bit 座舱声场胶囊
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.10f),
                    modifier = Modifier
                        .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                        .softCardClickable(shape = RoundedCornerShape(12.dp), onClick = onOpenCabinAcoustic)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = ConceptCyanAccent,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = cabinMode.shortBadge,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 一键夜间纯黑防眩光护眼态
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.10f),
                    modifier = Modifier
                        .size(42.dp)
                        .softCardClickable(shape = CircleShape, onClick = onToggleNightEyeCare)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.DarkMode,
                            contentDescription = "夜间护眼",
                            tint = Color.White.copy(alpha = 0.88f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                // 极简设置入口
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.10f),
                    modifier = Modifier
                        .size(42.dp)
                        .softCardClickable(shape = CircleShape, onClick = onOpenSettings)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "座舱设置",
                            tint = Color.White.copy(alpha = 0.88f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // 中部主角大封面 / 黑胶唱盘（轻触切换样式，左滑/右滑视觉反馈）
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .graphicsLayer { translationX = swipeOffsetX.value },
            contentAlignment = Alignment.Center
        ) {
            val coverSide = minOf(maxWidth * 0.78f, maxHeight * 0.92f).coerceIn(136.dp, 286.dp)
            Box(
                modifier = Modifier.softCardClickable(
                    shape = RoundedCornerShape(28.dp),
                    clip = false,
                    pressScale = 0.97f,
                    onClick = onToggleCoverStyle
                ),
                contentAlignment = Alignment.Center
            ) {
                if (playerState.coverStyle == CoverStyle.VINYL && renderCaps.enableAnimatedBackground) {
                    val vinylScale = (coverSide.value / 280f).coerceIn(0.55f, 1.10f)
                    MasterVinylTurntableView(
                        coverUrl = song.coverUrl,
                        isPlaying = playerState.isPlaying,
                        accentColor = accentPrimary,
                        rotationProvider = vinylRotationProvider,
                        scale = vinylScale
                    )
                } else {
                    StaticRoundedSquareCoverView(
                        coverUrl = song.coverUrl,
                        isPlaying = playerState.isPlaying && renderCaps.enableAnimatedBackground,
                        accentColor = accentPrimary,
                        secondaryAccentColor = accentSecondary,
                        coverSize = coverSide,
                        cornerRadius = 28.dp
                    )
                }
            }

            // 左右滑动切歌轻提示胶囊
            this@Column.AnimatedVisibility(
                visible = swipeHintText != null,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xD80A1220)
                ) {
                    Text(
                        text = swipeHintText.orEmpty(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = ConceptCyanAccent,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // 歌曲大字标题与歌手（独立双行无限跑马灯）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = song.title.ifBlank { "未知歌曲" },
                fontSize = 25.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = song.artist.ifBlank { "未知歌手" },
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 叶节点隔离的加粗流光进度条（不触发父组件重组）
        CarIsolatedProgressBar(
            durationMs = playerState.durationMs,
            isPlaying = playerState.isPlaying,
            smoothPositionProvider = smoothPositionProvider,
            accentPrimary = accentPrimary,
            accentSecondary = accentSecondary,
            onSeekTo = onSeekTo
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 超大盲操控播坞（64dp - 82dp 车规级触控热区）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // 私人 FM 队列下提供一键「不喜欢」负反馈（B2）；否则展示红心收藏（A2）
            CarBlindControlButton(
                icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDesc = "喜欢",
                sizeDp = (layoutSpec.secondaryControlButtonDp - 6.dp).coerceAtLeast(52.dp),
                tint = if (isFavorite) ConceptCoralAccent else Color.White.copy(alpha = 0.88f),
                bgColor = if (isFavorite) ConceptCoralAccent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f),
                onClick = onToggleFavorite
            )

            if (playerState.isPersonalFmQueue) {
                CarBlindControlButton(
                    icon = Icons.Rounded.ThumbDownOffAlt,
                    contentDesc = "不喜欢并切歌",
                    sizeDp = (layoutSpec.secondaryControlButtonDp - 8.dp).coerceAtLeast(50.dp),
                    tint = Color.White.copy(alpha = 0.82f),
                    bgColor = Color.White.copy(alpha = 0.09f),
                    onClick = onDislikeFmSong
                )
            }

            CarBlindControlButton(
                icon = Icons.Rounded.SkipPrevious,
                contentDesc = "上一首",
                sizeDp = layoutSpec.secondaryControlButtonDp,
                tint = Color.White,
                bgColor = Color.White.copy(alpha = 0.12f),
                iconSizeDp = 34.dp,
                onClick = onPrevious
            )

            // 核心超大播放/暂停球
            Surface(
                shape = CircleShape,
                color = accentPrimary,
                shadowElevation = 10.dp,
                modifier = Modifier
                    .size(layoutSpec.primaryPlayButtonDp)
                    .border(1.5.dp, Color.White.copy(alpha = 0.32f), CircleShape)
                    .softCardClickable(shape = CircleShape, pressScale = 0.92f, onClick = onTogglePlay)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(accentPrimary, accentSecondary)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playerState.isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }

            CarBlindControlButton(
                icon = Icons.Rounded.SkipNext,
                contentDesc = "下一首",
                sizeDp = layoutSpec.secondaryControlButtonDp,
                tint = Color.White,
                bgColor = Color.White.copy(alpha = 0.12f),
                iconSizeDp = 34.dp,
                onClick = onNext
            )

            CarBlindControlButton(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                contentDesc = "待播队列",
                sizeDp = (layoutSpec.secondaryControlButtonDp - 6.dp).coerceAtLeast(52.dp),
                tint = Color.White.copy(alpha = 0.88f),
                bgColor = Color.White.copy(alpha = 0.10f),
                onClick = onOpenQueue
            )
        }
    }
}

@Composable
private fun CarIsolatedProgressBar(
    durationMs: Long,
    isPlaying: Boolean,
    smoothPositionProvider: () -> Long,
    accentPrimary: Color,
    accentSecondary: Color,
    onSeekTo: (Long) -> Unit
) {
    val isStarted = rememberIsLifecycleStarted()
    var displayPosMs by remember { mutableLongStateOf(smoothPositionProvider()) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying, isStarted, durationMs) {
        displayPosMs = smoothPositionProvider()
        if (isPlaying && isStarted) {
            while (isActive) {
                if (!isDragging) {
                    displayPosMs = smoothPositionProvider()
                }
                delay(250L)
            }
        }
    }

    val safeDur = durationMs.coerceAtLeast(1L)
    val fraction = if (isDragging) dragFraction else (displayPosMs.toFloat() / safeDur.toFloat()).coerceIn(0f, 1f)
    val shownMs = if (isDragging) (dragFraction * safeDur).toLong() else displayPosMs

    CometTailProgressBar(
        fraction = fraction,
        isPlaying = isPlaying && isStarted,
        isInteracting = isDragging,
        currentTimeText = formatDuration(shownMs),
        totalTimeText = formatDuration(durationMs),
        climaxStartFrac = 0f,
        climaxEndFrac = 0f,
        highlightClimaxRange = false,
        accentPrimary = accentPrimary,
        accentSecondary = accentSecondary,
        compact = false,
        onDragStart = { f ->
            isDragging = true
            dragFraction = f.coerceIn(0f, 1f)
        },
        onDrag = { f ->
            dragFraction = f.coerceIn(0f, 1f)
        },
        onDragEnd = {
            val targetMs = (dragFraction * durationMs).toLong().coerceIn(0L, durationMs.coerceAtLeast(0L))
            isDragging = false
            displayPosMs = targetMs
            onSeekTo(targetMs)
        }
    )
}

/**
 * 巨幕级光学景深 KRC 逐字提词器：
 * - 高配 API 31+ 启用 GPU RenderEffect 渐进景深模糊，低配/老车机（API < 31 / ECO）平滑降级为透明渐变景深；
 * - 仅当歌曲含外文译文或音译时，在右上角浮现极简「译」「音」微胶囊（A8）。
 */
@Composable
private fun CarOpticalDepthLyricStage(
    lyricData: LyricData,
    currentLyricIndex: Int,
    isPlaying: Boolean,
    showTranslation: Boolean,
    showPhonetic: Boolean,
    smoothPositionProvider: () -> Long,
    lyricOffsetMs: Long,
    activeFontSp: Float,
    inactiveFontSp: Float,
    enableDepthBlur: Boolean,
    accentPrimary: Color,
    accentSecondary: Color,
    onSeekToLyricLine: (Int) -> Unit,
    onToggleTranslation: () -> Unit,
    onTogglePhonetic: () -> Unit
) {
    val listState = rememberLazyListState()
    val lines = lyricData.lines

    LaunchedEffect(currentLyricIndex, lines.size) {
        if (currentLyricIndex in lines.indices) {
            runCatching {
                listState.animateScrollToItem(
                    index = (currentLyricIndex - 1).coerceAtLeast(0),
                    scrollOffset = -40
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (lines.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "纯音乐或暂无歌词 · 静享座舱声场",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.56f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 84.dp, bottom = 120.dp, start = 8.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                itemsIndexed(
                    items = lines,
                    key = { idx, line -> "${idx}_${line.startTimeMs}" }
                ) { idx, line ->
                    val isActiveLine = idx == currentLyricIndex
                    val distance = if (currentLyricIndex >= 0) abs(idx - currentLyricIndex) else 1
                    val lineAlpha = when {
                        isActiveLine -> 1f
                        distance == 1 -> 0.54f
                        distance == 2 -> 0.30f
                        else -> 0.16f
                    }
                    val blurDp = if (enableDepthBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isActiveLine) {
                        when (distance) {
                            1 -> 1.6.dp
                            2 -> 3.4.dp
                            else -> 5.2.dp
                        }
                    } else {
                        0.dp
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .then(if (blurDp > 0.dp) Modifier.blur(blurDp) else Modifier)
                            .softCardClickable(
                                shape = RoundedCornerShape(14.dp),
                                pressScale = 0.985f,
                                onClick = { onSeekToLyricLine(idx) }
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        if (isActiveLine) {
                            CarActiveKaraokeLineText(
                                line = line,
                                isPlaying = isPlaying,
                                smoothPositionProvider = smoothPositionProvider,
                                lyricOffsetMs = lyricOffsetMs,
                                fontSizeSp = activeFontSp,
                                accentPrimary = accentPrimary,
                                accentSecondary = accentSecondary
                            )
                        } else {
                            Text(
                                text = line.text,
                                fontSize = inactiveFontSp.sp,
                                lineHeight = (inactiveFontSp * 1.35f).sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = lineAlpha)
                            )
                        }

                        if (showPhonetic && !line.phonetic.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = line.phonetic,
                                fontSize = (if (isActiveLine) activeFontSp * 0.56f else inactiveFontSp * 0.70f).sp,
                                fontWeight = FontWeight.Medium,
                                color = accentSecondary.copy(alpha = if (isActiveLine) 0.85f else lineAlpha * 0.8f)
                            )
                        }
                        if (showTranslation && !line.translation.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = line.translation,
                                fontSize = (if (isActiveLine) activeFontSp * 0.62f else inactiveFontSp * 0.74f).sp,
                                lineHeight = (if (isActiveLine) activeFontSp * 0.85f else inactiveFontSp * 0.95f).sp,
                                fontWeight = if (isActiveLine) FontWeight.SemiBold else FontWeight.Normal,
                                color = Color.White.copy(alpha = if (isActiveLine) 0.84f else lineAlpha * 0.85f)
                            )
                        }
                    }
                }
            }
        }

        // A8：仅当当前歌曲存在外文译文或音译时，在歌词区右上角浮现极简微胶囊
        if (lyricData.hasTranslation || lyricData.hasPhonetic) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (lyricData.hasTranslation) {
                    CarTinyLyricTogglePill(
                        label = "译",
                        active = showTranslation,
                        accent = accentSecondary,
                        onClick = onToggleTranslation
                    )
                }
                if (lyricData.hasPhonetic) {
                    CarTinyLyricTogglePill(
                        label = "音",
                        active = showPhonetic,
                        accent = accentPrimary,
                        onClick = onTogglePhonetic
                    )
                }
            }
        }
    }
}

@Composable
private fun CarActiveKaraokeLineText(
    line: LyricLine,
    isPlaying: Boolean,
    smoothPositionProvider: () -> Long,
    lyricOffsetMs: Long,
    fontSizeSp: Float,
    accentPrimary: Color,
    accentSecondary: Color
) {
    val isStarted = rememberIsLifecycleStarted()
    val words = line.words
    if (words.isEmpty()) {
        Text(
            text = line.text,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp * 1.32f).sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White
        )
        return
    }

    var currentAdjustedMs by remember(line.startTimeMs) {
        mutableLongStateOf(smoothPositionProvider() + lyricOffsetMs)
    }

    LaunchedEffect(line.startTimeMs, isPlaying, isStarted, lyricOffsetMs) {
        currentAdjustedMs = smoothPositionProvider() + lyricOffsetMs
        if (isPlaying && isStarted) {
            while (isActive) {
                currentAdjustedMs = smoothPositionProvider() + lyricOffsetMs
                delay(55L)
            }
        }
    }

    val highlightColor = remember(accentPrimary, accentSecondary) {
        accentSecondary.readableOnDarkSurface(0.58f)
    }

    val annotated = remember(words, currentAdjustedMs, highlightColor) {
        buildAnnotatedString {
            for (word in words) {
                val endMs = word.startTimeMs + word.durationMs.coerceAtLeast(1L)
                val sung = currentAdjustedMs >= endMs
                val singing = currentAdjustedMs in word.startTimeMs until endMs
                val color = when {
                    sung -> Color.White
                    singing -> highlightColor
                    else -> Color.White.copy(alpha = 0.44f)
                }
                withStyle(SpanStyle(color = color)) {
                    append(word.text)
                }
            }
        }
    }

    Text(
        text = annotated,
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * 1.32f).sp,
        fontWeight = FontWeight.ExtraBold
    )
}

@Composable
private fun CarRightSourceEdgeDock(
    activeDrawer: CarDrawerType?,
    accentColor: Color,
    onSelectSource: (CarDrawerType) -> Unit
) {
    val sourceItems = remember {
        listOf(
            CarDrawerType.PERSONAL_FM,
            CarDrawerType.DAILY_RECOMMEND,
            CarDrawerType.MY_LIBRARY,
            CarDrawerType.RADIO,
            CarDrawerType.CHARTS,
            CarDrawerType.SEARCH
        )
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0x9E0B1324),
        modifier = Modifier
            .width(86.dp)
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            sourceItems.forEach { item ->
                val selected = activeDrawer == item
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) accentColor.copy(alpha = 0.24f) else Color.Transparent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .softCardClickable(shape = RoundedCornerShape(16.dp), onClick = { onSelectSource(item) })
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (selected) accentColor else Color.White.copy(alpha = 0.82f),
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = item.label,
                            fontSize = 11.5.sp,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            color = if (selected) accentColor else Color.White.copy(alpha = 0.78f),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CarBottomCompactSourceStrip(
    activeDrawer: CarDrawerType?,
    accentColor: Color,
    onSelectSource: (CarDrawerType) -> Unit
) {
    val sourceItems = remember {
        listOf(
            CarDrawerType.PERSONAL_FM,
            CarDrawerType.DAILY_RECOMMEND,
            CarDrawerType.MY_LIBRARY,
            CarDrawerType.RADIO,
            CarDrawerType.CHARTS,
            CarDrawerType.SEARCH
        )
    }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color(0xAE0B1324),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            sourceItems.forEach { item ->
                val selected = activeDrawer == item
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) accentColor.copy(alpha = 0.22f) else Color.Transparent)
                        .softCardClickable(shape = RoundedCornerShape(14.dp), onClick = { onSelectSource(item) })
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (selected) accentColor else Color.White.copy(alpha = 0.82f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) accentColor else Color.White.copy(alpha = 0.78f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 1/3 与 1/2 导航分屏自适应悬浮卡片：
 * 自动隐藏次要控件，保留大封面 + 双行高亮歌词 + 超大三键盲操 + 一键快捷入口。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CarCompactSplitCardStage(
    song: Song,
    playerState: PlayerUiState,
    layoutSpec: CarCockpitLayoutSpec,
    renderCaps: CarRenderCapabilities,
    isFavorite: Boolean,
    cabinMode: CarCabinAcousticMode,
    accentPrimary: Color,
    accentSecondary: Color,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenCabinAcoustic: () -> Unit,
    onToggleNightEyeCare: () -> Unit,
    onOpenDrawer: (CarDrawerType) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val curLine = playerState.currentLyric.lines.getOrNull(playerState.currentLyricIndex)?.text.orEmpty()
    val nextLine = playerState.currentLyric.lines.getOrNull(playerState.currentLyricIndex + 1)?.text.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = layoutSpec.horizontalPadding, vertical = layoutSpec.verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 顶部单行微章
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.White.copy(alpha = 0.12f),
                modifier = Modifier.softCardClickable(shape = RoundedCornerShape(10.dp), onClick = onOpenCabinAcoustic)
            ) {
                Text(
                    text = cabinMode.shortBadge,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = ConceptCyanAccent,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CarBlindControlButton(
                    icon = Icons.Rounded.DarkMode,
                    contentDesc = "护眼",
                    sizeDp = 36.dp,
                    iconSizeDp = 18.dp,
                    tint = Color.White,
                    bgColor = Color.White.copy(alpha = 0.12f),
                    onClick = onToggleNightEyeCare
                )
                CarBlindControlButton(
                    icon = Icons.Rounded.Settings,
                    contentDesc = "设置",
                    sizeDp = 36.dp,
                    iconSizeDp = 18.dp,
                    tint = Color.White,
                    bgColor = Color.White.copy(alpha = 0.12f),
                    onClick = onOpenSettings
                )
            }
        }

        // 大封面
        Box(
            modifier = Modifier
                .size(168.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xFF121A2A))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(26.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (song.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(song.coverUrl)
                        .size(renderCaps.coverDecodeSizePx, renderCaps.coverDecodeSizePx)
                        .allowHardware(false)
                        .build(),
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = accentSecondary, modifier = Modifier.size(64.dp))
            }
        }

        // 歌名 + 歌手 + 两行流光歌词
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = song.title.ifBlank { "未知歌曲" },
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
            )
            Text(
                text = song.artist.ifBlank { "未知歌手" },
                fontSize = 13.5.sp,
                color = Color.White.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(12.dp))
            AnimatedContent(
                targetState = curLine,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                label = "splitLyric"
            ) { activeText ->
                Text(
                    text = activeText.ifBlank { "♪ 微风音乐车机 ♪" },
                    fontSize = layoutSpec.lyricActiveFontSp.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = accentSecondary.readableOnDarkSurface(0.60f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (nextLine.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = nextLine,
                    fontSize = layoutSpec.lyricInactiveFontSp.sp,
                    color = Color.White.copy(alpha = 0.45f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 三大盲操键 + 红心
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CarBlindControlButton(
                icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDesc = "喜欢",
                sizeDp = 48.dp,
                iconSizeDp = 24.dp,
                tint = if (isFavorite) ConceptCoralAccent else Color.White.copy(alpha = 0.85f),
                bgColor = Color.White.copy(alpha = 0.10f),
                onClick = onToggleFavorite
            )
            CarBlindControlButton(
                icon = Icons.Rounded.SkipPrevious,
                contentDesc = "上一首",
                sizeDp = layoutSpec.secondaryControlButtonDp,
                iconSizeDp = 30.dp,
                tint = Color.White,
                bgColor = Color.White.copy(alpha = 0.12f),
                onClick = onPrevious
            )
            Surface(
                shape = CircleShape,
                color = accentPrimary,
                modifier = Modifier
                    .size(layoutSpec.primaryPlayButtonDp)
                    .softCardClickable(shape = CircleShape, onClick = onTogglePlay)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "播放/暂停",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            CarBlindControlButton(
                icon = Icons.Rounded.SkipNext,
                contentDesc = "下一首",
                sizeDp = layoutSpec.secondaryControlButtonDp,
                iconSizeDp = 30.dp,
                tint = Color.White,
                bgColor = Color.White.copy(alpha = 0.12f),
                onClick = onNext
            )
        }

        // 底部紧凑水源与队列入口条
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CarChoicePill(
                label = "水源",
                selected = false,
                accent = accentSecondary,
                modifier = Modifier.weight(1f),
                onClick = { onOpenDrawer(CarDrawerType.PERSONAL_FM) }
            )
            CarChoicePill(
                label = "歌单",
                selected = false,
                accent = accentSecondary,
                modifier = Modifier.weight(1f),
                onClick = { onOpenDrawer(CarDrawerType.MY_LIBRARY) }
            )
            CarChoicePill(
                label = "队列",
                selected = false,
                accent = accentSecondary,
                modifier = Modifier.weight(1f),
                onClick = { onOpenDrawer(CarDrawerType.PLAY_QUEUE) }
            )
        }
    }
}

/**
 * 夜间高速纯黑低眩光护眼态（防前挡风玻璃反光）：
 * 零背景流光、零 GPU 模糊，仅保留超低眩光纯黑底 + 柔和微光歌名/两行大歌词 + 极简盲操三键。
 */
@Composable
private fun CarNightEyeCareStage(
    song: Song?,
    isPlaying: Boolean,
    lyricData: LyricData,
    currentLyricIndex: Int,
    onExitNightEyeCare: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val activeLine = lyricData.lines.getOrNull(currentLyricIndex)?.text.orEmpty()
    val nextLine = lyricData.lines.getOrNull(currentLyricIndex + 1)?.text.orEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 36.dp, vertical = 24.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF141820),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .softCardClickable(shape = RoundedCornerShape(14.dp), onClick = onExitNightEyeCare)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.WbSunny, contentDescription = null, tint = Color(0xFF8FA4BF), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("退出护眼暗黑", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9EB4D0))
            }
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = song?.title?.ifBlank { "微风音乐车机" } ?: "微风音乐车机",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8FA4BF)
            )
            Text(
                text = activeLine.ifBlank { song?.artist ?: "夜间低眩光护眼运行中" },
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFC9D8EC),
                textAlign = TextAlign.Center,
                maxLines = 2
            )
            if (nextLine.isNotBlank()) {
                Text(
                    text = nextLine,
                    fontSize = 20.sp,
                    color = Color(0xFF56667A),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CarBlindControlButton(
                    icon = Icons.Rounded.SkipPrevious,
                    contentDesc = "上一首",
                    sizeDp = 66.dp,
                    iconSizeDp = 32.dp,
                    tint = Color(0xFF9FB4CE),
                    bgColor = Color(0xFF12161E),
                    onClick = onPrevious
                )
                CarBlindControlButton(
                    icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDesc = "播放/暂停",
                    sizeDp = 78.dp,
                    iconSizeDp = 38.dp,
                    tint = Color(0xFFD5E2F2),
                    bgColor = Color(0xFF192231),
                    onClick = onTogglePlay
                )
                CarBlindControlButton(
                    icon = Icons.Rounded.SkipNext,
                    contentDesc = "下一首",
                    sizeDp = 66.dp,
                    iconSizeDp = 32.dp,
                    tint = Color(0xFF9FB4CE),
                    bgColor = Color(0xFF12161E),
                    onClick = onNext
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CarWelcomeEmptyStage(
    accentPrimary: Color,
    accentSecondary: Color,
    onQuickStartFm: () -> Unit,
    onOpenDrawer: (CarDrawerType) -> Unit,
    onOpenQrLogin: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = Color(0xC80B1324),
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(32.dp))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Brush.linearGradient(listOf(accentPrimary, accentSecondary))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.DirectionsCar, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
                }
                Text(
                    text = "微风音乐车机 · 流光玻璃座舱",
                    fontSize = 23.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "64-bit 主驾皇帝位声场与智能导航闪避已就绪，一点即播无损好歌",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = accentPrimary,
                        modifier = Modifier
                            .widthIn(min = 210.dp)
                            .softCardClickable(shape = RoundedCornerShape(18.dp), onClick = onQuickStartFm)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 15.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("立即开启猜你喜欢", fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color.White.copy(alpha = 0.11f),
                        modifier = Modifier
                            .widthIn(min = 190.dp)
                            .softCardClickable(shape = RoundedCornerShape(18.dp), onClick = onOpenQrLogin)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 15.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = ConceptCyanAccent, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("大屏扫码登录", fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = ConceptCyanAccent)
                        }
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CarChoicePill("本地 / U 盘音乐", false, ConceptMintAccent, onClick = { onOpenDrawer(CarDrawerType.MY_LIBRARY) })
                    CarChoicePill("巅峰榜单", false, ConceptCyanAccent, onClick = { onOpenDrawer(CarDrawerType.CHARTS) })
                    CarChoicePill("座舱设置", false, Color.White, onClick = onOpenSettings)
                }
            }
        }
    }
}

@Composable
internal fun CarBlindControlButton(
    icon: ImageVector,
    contentDesc: String,
    sizeDp: Dp,
    tint: Color,
    bgColor: Color,
    iconSizeDp: Dp = 28.dp,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = bgColor,
        modifier = Modifier
            .size(sizeDp)
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .softCardClickable(shape = CircleShape, pressScale = 0.90f, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDesc,
                tint = tint,
                modifier = Modifier.size(iconSizeDp)
            )
        }
    }
}

@Composable
private fun CarTinyLyricTogglePill(
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (active) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.12f),
        modifier = Modifier
            .border(1.dp, if (active) accent.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
            .softCardClickable(shape = RoundedCornerShape(10.dp), onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (active) accent else Color.White.copy(alpha = 0.76f),
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
        )
    }
}
