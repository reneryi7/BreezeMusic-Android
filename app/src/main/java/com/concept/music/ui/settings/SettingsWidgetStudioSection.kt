package com.concept.music.ui.settings

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.concept.music.ui.theme.ConceptCyanAccent
import com.concept.music.ui.theme.ConceptPrimary
import com.concept.music.ui.theme.ConceptVioletAccent
import com.concept.music.ui.theme.LocalIsDarkTheme
import com.concept.music.ui.theme.softCardClickable
import com.concept.music.ui.theme.softClickable
import com.concept.music.ui.viewmodel.MusicPlayerViewModel
import com.concept.music.ui.viewmodel.PlayMode
import com.concept.music.widget.BreezeWidgetArtPipeline
import com.concept.music.widget.BreezeWidgetConfig
import com.concept.music.widget.BreezeWidgetControlIcon
import com.concept.music.widget.BreezeWidgetManager
import com.concept.music.widget.BreezeWidgetPalette
import com.concept.music.widget.BreezeWidgetPinAdapter
import com.concept.music.widget.BreezeWidgetPlaybackSnapshot
import com.concept.music.widget.BreezeWidgetPrefs
import com.concept.music.widget.BreezeWidgetRenderer
import com.concept.music.widget.BreezeWidgetSizeClass
import com.concept.music.widget.BreezeWidgetStyle
import com.concept.music.widget.BreezeWidgetThemeMode
import com.concept.music.widget.OemWidgetPinGuidance
import com.concept.music.widget.WidgetHostRom
import com.concept.music.widget.WidgetLoadedArtwork
import com.concept.music.widget.WidgetPinAttemptResult
import com.concept.music.widget.WidgetPinAttemptStatus
import com.concept.music.widget.WidgetPinStrategy
import com.concept.music.widget.formatWidgetDuration
import com.concept.music.widget.resolveBreezeWidgetPalette
import com.concept.music.widget.resolveWidgetBackgroundSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 设置页「外观与播放样式 → 桌面小组件工坊」：
 * - 实时复用 BreezeWidgetRenderer 离屏引擎呈现与手机/平板桌面 1:1 像素同源预览；
 * - 支持切换黑胶封套 / 流光封面 / 水彩叠层三风格、四尺寸形态、明暗壁纸模拟与透明度调节；
 * - 任意配置改动实时同步到桌面全部微风小组件，并支持一键固定到桌面。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsWidgetStudioSection(
    viewModel: MusicPlayerViewModel
) {
    val context = LocalContext.current
    val isAppDark = LocalIsDarkTheme.current
    LaunchedEffect(context) {
        BreezeWidgetPrefs.init(context)
    }

    val config by BreezeWidgetPrefs.config.collectAsState()
    val playerState by viewModel.uiState.collectAsState()

    var selectedSize by remember { mutableStateOf(BreezeWidgetSizeClass.CARD_4X2) }
    var darkWallpaperPreview by remember { mutableStateOf(isAppDark) }
    var activeWidgetCount by remember { mutableIntStateOf(BreezeWidgetManager.countActiveWidgets(context)) }
    val detectedRom = remember { BreezeWidgetPinAdapter.detectCurrentRom() }
    var pinGuidanceDialog by remember { mutableStateOf<OemWidgetPinGuidance?>(null) }
    var lastPinAttemptResult by remember { mutableStateOf<WidgetPinAttemptResult?>(null) }
    var pendingPinBaselineCount by remember { mutableIntStateOf(-1) }
    var pendingPinTargetSize by remember { mutableStateOf<BreezeWidgetSizeClass?>(null) }
    var pinWatchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val studioScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 监听小组件真实落地广播（ColorOS / AOSP / OneUI 确认添加后立即刷新计数与反馈）
    LaunchedEffect(Unit) {
        BreezeWidgetManager.widgetPinnedEvents.collect { pinnedSize ->
            activeWidgetCount = BreezeWidgetManager.countActiveWidgets(context)
            pendingPinBaselineCount = -1
            pendingPinTargetSize = null
            pinGuidanceDialog = null
            Toast.makeText(
                context,
                "已成功将「${pinnedSize.gridSpec} ${pinnedSize.label}」添加到桌面",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // 返回前台时同步桌面已放置数量（兼容鸿蒙/澎湃OS 不触发 successCallback 的情况）
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val nowCount = BreezeWidgetManager.countActiveWidgets(context)
                activeWidgetCount = nowCount
                if (pendingPinBaselineCount >= 0 && nowCount > pendingPinBaselineCount) {
                    val addedSize = pendingPinTargetSize ?: selectedSize
                    pendingPinBaselineCount = -1
                    pendingPinTargetSize = null
                    pinGuidanceDialog = null
                    Toast.makeText(
                        context,
                        "已成功添加「${addedSize.gridSpec} ${addedSize.label}」到桌面",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 构造实时预览快照：优先展示当前播放曲目与真实歌词，空闲态展示优雅的微风演示样例
    val previewSnapshot = remember(
        playerState.currentSong?.id,
        playerState.isPlaying,
        playerState.currentLyricIndex,
        playerState.playMode,
        playerState.currentIndex,
        playerState.playlistQueue.size
    ) {
        val current = playerState.currentSong
        if (current != null) {
            val queue = playerState.playlistQueue
            val idx = playerState.currentIndex
            val next1 = if (queue.size > 1 && idx in queue.indices) queue[(idx + 1) % queue.size] else null
            val next2 = if (queue.size > 2 && idx in queue.indices) queue[(idx + 2) % queue.size] else null
            val lines = playerState.currentLyric.lines
            val lIdx = playerState.currentLyricIndex
            val prevL = lines.getOrNull(lIdx - 1)?.text?.trim().orEmpty()
            val curL = lines.getOrNull(lIdx)?.text?.trim().orEmpty().ifBlank { "耳畔微风起 · 帧享真无损" }
            val nextL = lines.getOrNull(lIdx + 1)?.text?.trim().orEmpty()
            BreezeWidgetPlaybackSnapshot.fromSong(
                song = current,
                nextSong1 = next1,
                nextSong2 = next2,
                isPlaying = playerState.isPlaying,
                isFavorite = true,
                playMode = playerState.playMode,
                prevLyric = prevL,
                currentLyric = curL,
                nextLyric = nextL,
                positionMs = playerState.currentPositionMs.coerceAtLeast(if (playerState.durationMs > 0L) playerState.durationMs / 3 else 68_000L),
                durationMs = playerState.durationMs.coerceAtLeast(210_000L)
            )
        } else {
            BreezeWidgetPlaybackSnapshot(
                songId = "demo_breeze",
                title = "Foggy Moon",
                artist = "She Her Her Hers",
                album = "Still Dreaming",
                qualityBadge = "Hi-Res",
                isPlaying = true,
                isFavorite = true,
                playMode = PlayMode.REPEAT_ALL,
                prevLyric = "海面微光轻轻拍打夜色海岸",
                currentLyric = "在微风里听见无损原声的层次",
                nextLyric = "每一帧流光都与旋律同频呼吸",
                positionMs = 94_000L,
                durationMs = 246_000L
            )
        }
    }

    var loadedArtwork by remember {
        mutableStateOf(WidgetLoadedArtwork(null, null, null, null))
    }
    LaunchedEffect(
        previewSnapshot.songId,
        previewSnapshot.coverUrl,
        previewSnapshot.nextCoverUrl1,
        previewSnapshot.nextCoverUrl2,
        config.style
    ) {
        loadedArtwork = BreezeWidgetArtPipeline.loadArtwork(
            context = context,
            snapshot = previewSnapshot,
            needStackedNextCovers = config.style == BreezeWidgetStyle.WATERCOLOR_STACK
        )
    }

    val palette = remember(config, isAppDark, loadedArtwork.extractedPalette) {
        resolveBreezeWidgetPalette(
            config = config,
            isAppDark = isAppDark,
            coverSeedColor = loadedArtwork.extractedPalette?.dominantAccent,
            coverSecondaryColor = loadedArtwork.extractedPalette?.secondaryAccent,
            coverTertiaryColor = loadedArtwork.extractedPalette?.tertiaryAccent
        )
    }

    fun applyConfig(transform: (BreezeWidgetConfig) -> BreezeWidgetConfig) {
        BreezeWidgetPrefs.update(context, transform)
        BreezeWidgetManager.onConfigChanged(context)
    }

    fun triggerAdaptedPin(
        targetSize: BreezeWidgetSizeClass,
        initialStrategy: WidgetPinStrategy = WidgetPinStrategy.STANDARD_CLEAN
    ) {
        pinWatchJob?.cancel()
        val result = BreezeWidgetManager.requestPinWidgetAdapted(
            context = context,
            sizeClass = targetSize,
            strategy = initialStrategy
        )
        lastPinAttemptResult = result
        activeWidgetCount = BreezeWidgetManager.countActiveWidgets(context)
        when (result.status) {
            WidgetPinAttemptStatus.BLOCKED_BY_PERMISSION,
            WidgetPinAttemptStatus.LAUNCHER_UNSUPPORTED -> {
                pendingPinBaselineCount = -1
                pendingPinTargetSize = null
                pinGuidanceDialog = BreezeWidgetPinAdapter.buildOemPinGuidance(result.rom, targetSize)
            }
            WidgetPinAttemptStatus.REQUESTED_MAY_PIN_SILENTLY,
            WidgetPinAttemptStatus.REQUESTED_AWAIT_CONFIRM -> {
                pendingPinBaselineCount = result.beforeWidgetCount
                pendingPinTargetSize = targetSize
                pinWatchJob = studioScope.launch {
                    val canAutoEscalateMinimal =
                        initialStrategy == WidgetPinStrategy.STANDARD_CLEAN &&
                            result.strategy == WidgetPinStrategy.STANDARD_CLEAN &&
                            result.status == WidgetPinAttemptStatus.REQUESTED_AWAIT_CONFIRM &&
                            result.rom != WidgetHostRom.ORIGIN_OS

                    val firstWaitMs = when {
                        result.rom == WidgetHostRom.ORIGIN_OS -> 750L
                        canAutoEscalateMinimal -> 1050L
                        else -> 1600L
                    }
                    delay(firstWaitMs)
                    val countAfterFirst = BreezeWidgetManager.countActiveWidgets(context)
                    activeWidgetCount = countAfterFirst
                    if (pendingPinBaselineCount < 0) return@launch
                    if (countAfterFirst > pendingPinBaselineCount) {
                        pendingPinBaselineCount = -1
                        pendingPinTargetSize = null
                        Toast.makeText(
                            context,
                            "已成功添加「${targetSize.gridSpec} ${targetSize.label}」到桌面",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }
                    if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        // 页面已失焦（系统确认弹窗已弹出），等待用户操作返回 ON_RESUME 或广播确认
                        return@launch
                    }

                    // 若标准零载荷通道未弹框（仍停留前台），自动静默切换极简直通通道 (null, null) 再试一次
                    if (canAutoEscalateMinimal) {
                        val minimalRes = BreezeWidgetManager.requestPinWidgetAdapted(
                            context = context,
                            sizeClass = targetSize,
                            strategy = WidgetPinStrategy.MINIMAL_NO_CALLBACK
                        )
                        lastPinAttemptResult = minimalRes
                        if (minimalRes.requestAccepted) {
                            delay(1050L)
                            val countAfterSecond = BreezeWidgetManager.countActiveWidgets(context)
                            activeWidgetCount = countAfterSecond
                            if (pendingPinBaselineCount < 0) return@launch
                            if (countAfterSecond > pendingPinBaselineCount) {
                                pendingPinBaselineCount = -1
                                pendingPinTargetSize = null
                                Toast.makeText(
                                    context,
                                    "已成功添加「${targetSize.gridSpec} ${targetSize.label}」到桌面",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@launch
                            }
                            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                return@launch
                            }
                        }
                    }

                    // 两次通道均未弹框且桌面未新增 -> 系统桌面策略拦截或桌面布局已锁定，给出精准排查与手动引导
                    pendingPinBaselineCount = -1
                    pendingPinTargetSize = null
                    pinGuidanceDialog = BreezeWidgetPinAdapter.buildOemPinGuidance(result.rom, targetSize)
                }
            }
        }
    }

    SettingsSectionCard(
        title = "桌面小组件工坊",
        icon = Icons.Rounded.Widgets
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (activeWidgetCount > 0) {
                    "桌面已放置 $activeWidgetCount 个微风小组件 · 修改实时生效"
                } else {
                    "实时定制样式并一键固定至桌面"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp,
                modifier = Modifier.weight(1f)
            )
            // 模拟深浅桌面壁纸切换胶囊
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                modifier = Modifier.softClickable(
                    pressScale = 0.90f,
                    onClick = { darkWallpaperPreview = !darkWallpaperPreview }
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (darkWallpaperPreview) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
                        contentDescription = "切换预览壁纸明暗",
                        tint = ConceptVioletAccent,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = if (darkWallpaperPreview) "暗壁纸" else "亮壁纸",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 四尺寸切换 Tab
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BreezeWidgetSizeClass.entries.forEach { sizeClass ->
                ConceptBorderlessChoicePill(
                    selected = selectedSize == sizeClass,
                    label = "${sizeClass.gridSpec} ${sizeClass.label}",
                    accentColor = ConceptPrimary,
                    onClick = { selectedSize = sizeClass }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 实时高保真模拟桌面预览舞台
        WidgetLivePreviewStage(
            sizeClass = selectedSize,
            config = config,
            snapshot = previewSnapshot,
            palette = palette,
            artwork = loadedArtwork,
            darkWallpaper = darkWallpaperPreview
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 视觉风格三选一
        Text(
            text = "视觉风格",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = config.style.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.5.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BreezeWidgetStyle.entries.forEach { style ->
                ConceptBorderlessChoicePill(
                    selected = config.style == style,
                    label = style.label,
                    accentColor = ConceptVioletAccent,
                    onClick = { applyConfig { it.copy(style = style) } }
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )

        // 卡片主题策略三选一
        Text(
            text = "小组件深浅主题",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BreezeWidgetThemeMode.entries.forEach { mode ->
                ConceptBorderlessChoicePill(
                    selected = config.themeMode == mode,
                    label = mode.label,
                    accentColor = ConceptPrimary,
                    onClick = { applyConfig { it.copy(themeMode = mode) } }
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )

        // 背景不透明度调节
        val alphaPercent = (config.sanitizedBackgroundAlpha * 100f).roundToInt()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "卡片背景不透明度",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = "调低可透出桌面壁纸，呈现轻盈磨砂通透质感",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp
                )
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ConceptPrimary.copy(alpha = 0.14f)
            ) {
                Text(
                    text = "${alphaPercent}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = ConceptPrimary,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }
        Slider(
            value = config.sanitizedBackgroundAlpha,
            onValueChange = { next ->
                BreezeWidgetPrefs.update(context) { it.copy(backgroundAlpha = next) }
            },
            onValueChangeFinished = {
                BreezeWidgetManager.onConfigChanged(context)
            },
            valueRange = BreezeWidgetConfig.MIN_BG_ALPHA..BreezeWidgetConfig.MAX_BG_ALPHA,
            colors = SliderDefaults.colors(
                thumbColor = ConceptPrimary,
                activeTrackColor = ConceptPrimary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )

        // 显示元素独立开关
        SettingToggleRow(
            title = "封面智能动态取色",
            subtitle = "复用 24-bin 取色与 WCAG 色阶，随专辑封面自动换肤",
            checked = config.dynamicCoverColor,
            onCheckedChange = { checked -> applyConfig { it.copy(dynamicCoverColor = checked) } },
            thumbColor = ConceptVioletAccent
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingToggleRow(
            title = "显示实时歌词",
            subtitle = "4×2 与 4×1 展示当前句，4×4 展示杂志级双行主词与上下句",
            checked = config.showLyric,
            onCheckedChange = { checked -> applyConfig { it.copy(showLyric = checked) } },
            thumbColor = ConceptCyanAccent
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingToggleRow(
            title = "显示音质徽标",
            subtitle = "在曲名旁呈现 Hi-Res / SQ / FLAC 精致胶囊标识",
            checked = config.showQualityBadge,
            onCheckedChange = { checked -> applyConfig { it.copy(showQualityBadge = checked) } },
            thumbColor = ConceptPrimary
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingToggleRow(
            title = "显示播放进度条",
            subtitle = "低功耗节流刷新（熄屏零唤醒），4×1 呈现迷你环形进度",
            checked = config.showProgressBar,
            onCheckedChange = { checked -> applyConfig { it.copy(showProgressBar = checked) } },
            thumbColor = ConceptPrimary
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 一键添加到桌面按钮（全厂商适配：前台 Activity + 零载荷直通 + 极简通道自动兜底 + 精准引导）
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = ConceptPrimary,
            contentColor = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .softCardClickable(
                    shape = RoundedCornerShape(16.dp),
                    pressScale = 0.97f,
                    onClick = { triggerAdaptedPin(selectedSize) }
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.AddToHomeScreen,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "添加 ${selectedSize.gridSpec} ${selectedSize.label} 到桌面",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .softClickable(
                    pressScale = 0.96f,
                    onClick = {
                        pinGuidanceDialog = BreezeWidgetPinAdapter.buildOemPinGuidance(detectedRom, selectedSize)
                    }
                )
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "本机已适配 ${detectedRom.displayName} · 无法自动添加？查看权限与手动引导",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    pinGuidanceDialog?.let { guidance ->
        OemWidgetPinGuidanceDialog(
            guidance = guidance,
            lastAttempt = lastPinAttemptResult,
            onOpenPermissionSettings = {
                val opened = BreezeWidgetPinAdapter.openOemWidgetPermissionSettings(context, guidance.rom)
                if (!opened) {
                    Toast.makeText(context, "请手动前往系统设置 → 应用管理 → 微风音乐 → 权限管理检查桌面快捷方式", Toast.LENGTH_LONG).show()
                }
            },
            onRetryPin = {
                pinGuidanceDialog = null
                triggerAdaptedPin(
                    targetSize = selectedSize,
                    initialStrategy = WidgetPinStrategy.MINIMAL_NO_CALLBACK
                )
            },
            onGoHomeDesktop = {
                pinGuidanceDialog = null
                BreezeWidgetPinAdapter.openSystemHomeLauncher(context)
            },
            onDismiss = { pinGuidanceDialog = null }
        )
    }
}

@Composable
private fun OemWidgetPinGuidanceDialog(
    guidance: OemWidgetPinGuidance,
    lastAttempt: WidgetPinAttemptResult?,
    onOpenPermissionSettings: () -> Unit,
    onRetryPin: () -> Unit,
    onGoHomeDesktop: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = guidance.title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = guidance.subtitle,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ConceptPrimary.copy(alpha = 0.10f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = ConceptPrimary,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(16.dp)
                        )
                        Text(
                            text = guidance.permissionTip,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "手动添加步骤",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    guidance.manualSteps.forEachIndexed { index, step ->
                        Text(
                            text = "${index + 1}. $step",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                lastAttempt?.let { attempt ->
                    val pkgLabel = attempt.launcherPackage.ifBlank { "未知桌面" }
                    val supportText = if (attempt.isSupported) "支持" else "不支持"
                    val acceptText = if (attempt.requestAccepted) "已受理但未弹框" else "被桌面拒绝"
                    Text(
                        text = "本机状态：$pkgLabel · 系统接口$supportText · $acceptText（${attempt.strategy.label}）",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                if (guidance.supportsDirectPermissionJump) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ConceptPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .softCardClickable(
                                shape = RoundedCornerShape(12.dp),
                                onClick = onOpenPermissionSettings
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Security,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "检查「创建桌面快捷方式」权限",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        modifier = Modifier
                            .weight(1f)
                            .softCardClickable(
                                shape = RoundedCornerShape(12.dp),
                                onClick = onRetryPin
                            )
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "极简模式重试",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        modifier = Modifier
                            .weight(1f)
                            .softCardClickable(
                                shape = RoundedCornerShape(12.dp),
                                onClick = onGoHomeDesktop
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 9.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Home,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "前往桌面",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        }
    )
}

private data class RenderedPreviewBitmaps(
    val bg: Bitmap,
    val stage: Bitmap,
    val playBtn: Bitmap,
    val prevBtn: Bitmap,
    val nextBtn: Bitmap,
    val favBtn: Bitmap,
    val modeBtn: Bitmap,
    val progress: Bitmap?,
    val badge: Bitmap?,
    val lyricAccentBar: Bitmap?
)

@Composable
private fun WidgetLivePreviewStage(
    sizeClass: BreezeWidgetSizeClass,
    config: BreezeWidgetConfig,
    snapshot: BreezeWidgetPlaybackSnapshot,
    palette: BreezeWidgetPalette,
    artwork: WidgetLoadedArtwork,
    darkWallpaper: Boolean
) {
    val rendered by produceState<RenderedPreviewBitmaps?>(
        initialValue = null,
        sizeClass, config, snapshot, palette, artwork.primaryCover, artwork.nextCover1, artwork.nextCover2
    ) {
        value = withContext(Dispatchers.Default) {
            val bgSpec = resolveWidgetBackgroundSpec(sizeClass)
            val bg = BreezeWidgetRenderer.renderCardBackground(
                widthPx = bgSpec.widthPx,
                heightPx = bgSpec.heightPx,
                cornerRadiusPx = bgSpec.cornerRadiusPx,
                style = config.style,
                palette = palette,
                coverBitmap = artwork.primaryCover,
                backgroundAlpha = config.sanitizedBackgroundAlpha,
                isStage4x4 = sizeClass == BreezeWidgetSizeClass.STAGE_4X4
            )
            val stage = BreezeWidgetRenderer.renderArtworkStage(
                sizeClass = sizeClass,
                style = config.style,
                primaryCover = artwork.primaryCover,
                nextCover1 = artwork.nextCover1,
                nextCover2 = artwork.nextCover2,
                palette = palette,
                isPlaying = snapshot.isPlaying
            )
            val playSizePx = when (sizeClass) {
                BreezeWidgetSizeClass.STAGE_4X4 -> 128
                BreezeWidgetSizeClass.CARD_4X2 -> 114
                BreezeWidgetSizeClass.SQUARE_2X2 -> 112
                BreezeWidgetSizeClass.CAPSULE_4X1 -> 104
            }
            val playBtn = BreezeWidgetRenderer.renderPlayButton(
                sizePx = playSizePx,
                isPlaying = snapshot.isPlaying,
                palette = palette,
                withProgressRing = (sizeClass == BreezeWidgetSizeClass.CAPSULE_4X1 || sizeClass == BreezeWidgetSizeClass.SQUARE_2X2) && config.showProgressBar,
                progressFraction = snapshot.progressFraction
            )
            val isCapsule = sizeClass == BreezeWidgetSizeClass.CAPSULE_4X1
            val auxSizePx = if (sizeClass == BreezeWidgetSizeClass.STAGE_4X4) 98 else 86
            val prevBtn = BreezeWidgetRenderer.renderControlButton(
                icon = BreezeWidgetControlIcon.PREV,
                sizePx = auxSizePx,
                palette = palette,
                withSubtleCircleBg = true,
                softBackgroundOnly = isCapsule
            )
            val nextBtn = BreezeWidgetRenderer.renderControlButton(
                icon = BreezeWidgetControlIcon.NEXT,
                sizePx = auxSizePx,
                palette = palette,
                withSubtleCircleBg = true,
                softBackgroundOnly = isCapsule
            )
            val favIcon = if (snapshot.isFavorite) BreezeWidgetControlIcon.FAVORITE_ON else BreezeWidgetControlIcon.FAVORITE_OFF
            val favBtn = BreezeWidgetRenderer.renderControlButton(favIcon, 92, palette, withSubtleCircleBg = true)
            val modeIcon = when (snapshot.playMode) {
                PlayMode.REPEAT_ALL -> BreezeWidgetControlIcon.PLAY_MODE_LIST
                PlayMode.REPEAT_ONE -> BreezeWidgetControlIcon.PLAY_MODE_SINGLE
                PlayMode.SHUFFLE -> BreezeWidgetControlIcon.PLAY_MODE_SHUFFLE
            }
            val modeBtn = BreezeWidgetRenderer.renderControlButton(modeIcon, 92, palette, withSubtleCircleBg = true)
            val progress = if (config.showProgressBar) {
                val progH = if (sizeClass == BreezeWidgetSizeClass.STAGE_4X4) 20 else 18
                BreezeWidgetRenderer.renderProgressBar(380, progH, snapshot.progressFraction, palette)
            } else null
            val badge = if (config.showQualityBadge && snapshot.qualityBadge.isNotBlank()) {
                val badgeH = if (sizeClass == BreezeWidgetSizeClass.STAGE_4X4) 34 else 32
                BreezeWidgetRenderer.renderQualityBadge(snapshot.qualityBadge, palette, badgeH)
            } else null
            val lyricBar = if (sizeClass == BreezeWidgetSizeClass.STAGE_4X4 && config.showLyric) {
                BreezeWidgetRenderer.renderLyricAccentBar(palette)
            } else null
            RenderedPreviewBitmaps(bg, stage, playBtn, prevBtn, nextBtn, favBtn, modeBtn, progress, badge, lyricBar)
        }
    }

    val wallpaperBrush = if (darkWallpaper) {
        Brush.linearGradient(
            listOf(Color(0xFF0A1426), Color(0xFF1A2542), Color(0xFF16132E), Color(0xFF0F2138))
        )
    } else {
        Brush.linearGradient(
            listOf(Color(0xFFD5E9FF), Color(0xFFE7DEFF), Color(0xFFD9F5F2), Color(0xFFFFE7DD))
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(wallpaperBrush)
            .padding(horizontal = 14.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        val bmps = rendered
        if (bmps == null) {
            Box(modifier = Modifier.height(130.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
        } else {
            when (sizeClass) {
                BreezeWidgetSizeClass.CAPSULE_4X1 -> PreviewCapsuleCard(bmps, config, snapshot, palette)
                BreezeWidgetSizeClass.SQUARE_2X2 -> PreviewSquareCard(bmps, snapshot, palette)
                BreezeWidgetSizeClass.CARD_4X2 -> PreviewClassicCard(bmps, config, snapshot, palette)
                BreezeWidgetSizeClass.STAGE_4X4 -> PreviewStageCard(bmps, config, snapshot, palette)
            }
        }
    }
}

@Composable
private fun PreviewCapsuleCard(
    bmps: RenderedPreviewBitmaps,
    config: BreezeWidgetConfig,
    snapshot: BreezeWidgetPlaybackSnapshot,
    palette: BreezeWidgetPalette
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
    ) {
        Image(
            bitmap = bmps.bg.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                bitmap = bmps.stage.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = snapshot.title,
                        color = palette.primaryText,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    bmps.badge?.let { badgeBmp ->
                        Spacer(modifier = Modifier.width(5.dp))
                        Image(
                            bitmap = badgeBmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.height(15.dp)
                        )
                    }
                }
                val subText = if (config.showLyric && snapshot.currentLyric.isNotBlank()) snapshot.currentLyric else snapshot.artist
                val subColor = if (config.showLyric && snapshot.currentLyric.isNotBlank()) palette.lyricActiveText else palette.secondaryText
                Text(
                    text = subText,
                    color = subColor,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Image(bitmap = bmps.prevBtn.asImageBitmap(), contentDescription = null, modifier = Modifier.size(30.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Image(bitmap = bmps.playBtn.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Image(bitmap = bmps.nextBtn.asImageBitmap(), contentDescription = null, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun PreviewSquareCard(
    bmps: RenderedPreviewBitmaps,
    snapshot: BreezeWidgetPlaybackSnapshot,
    palette: BreezeWidgetPalette
) {
    Box(modifier = Modifier.size(168.dp)) {
        Image(
            bitmap = bmps.bg.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Image(
                bitmap = bmps.stage.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = snapshot.title,
                        color = palette.primaryText,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = snapshot.artist,
                        color = palette.secondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Image(bitmap = bmps.playBtn.asImageBitmap(), contentDescription = null, modifier = Modifier.size(42.dp))
            }
        }
    }
}

@Composable
private fun PreviewClassicCard(
    bmps: RenderedPreviewBitmaps,
    config: BreezeWidgetConfig,
    snapshot: BreezeWidgetPlaybackSnapshot,
    palette: BreezeWidgetPalette
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(148.dp)
    ) {
        Image(
            bitmap = bmps.bg.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 13.dp, vertical = 11.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    bitmap = bmps.stage.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 112.dp, height = 88.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = snapshot.title,
                            color = palette.primaryText,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        bmps.badge?.let { badgeBmp ->
                            Spacer(modifier = Modifier.width(5.dp))
                            Image(
                                bitmap = badgeBmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.height(15.dp)
                            )
                        }
                    }
                    Text(
                        text = snapshot.artist,
                        color = palette.secondaryText,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (config.showLyric && snapshot.currentLyric.isNotBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = snapshot.currentLyric,
                            color = palette.lyricActiveText,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(7.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(bitmap = bmps.favBtn.asImageBitmap(), contentDescription = null, modifier = Modifier.size(33.dp))
                        Spacer(modifier = Modifier.weight(1f))
                        Image(bitmap = bmps.prevBtn.asImageBitmap(), contentDescription = null, modifier = Modifier.size(33.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Image(bitmap = bmps.playBtn.asImageBitmap(), contentDescription = null, modifier = Modifier.size(43.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Image(bitmap = bmps.nextBtn.asImageBitmap(), contentDescription = null, modifier = Modifier.size(33.dp))
                    }
                }
            }
            bmps.progress?.let { progBmp ->
                Spacer(modifier = Modifier.height(5.dp))
                Image(
                    bitmap = progBmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                )
            }
        }
    }
}

@Composable
private fun PreviewStageCard(
    bmps: RenderedPreviewBitmaps,
    config: BreezeWidgetConfig,
    snapshot: BreezeWidgetPlaybackSnapshot,
    palette: BreezeWidgetPalette
) {
    Box(
        modifier = Modifier
            .widthIn(max = 296.dp)
            .fillMaxWidth()
            .height(302.dp)
    ) {
        Image(
            bitmap = bmps.bg.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 15.dp, vertical = 14.dp)
        ) {
            // 上排杂志级非对称 Hero：左侧大片黑胶/光晕/叠层舞台 + 右侧大字曲目与元信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    bitmap = bmps.stage.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 122.dp, height = 96.dp)
                )
                Spacer(modifier = Modifier.width(11.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = snapshot.title,
                        color = palette.primaryText,
                        fontSize = 16.5.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = snapshot.artist,
                        color = palette.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val albumText = snapshot.album.trim()
                    val showAlbum = albumText.isNotBlank() && albumText != snapshot.title
                    if (bmps.badge != null || showAlbum) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            bmps.badge?.let { badgeBmp ->
                                Image(
                                    bitmap = badgeBmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.height(17.dp)
                                )
                                if (showAlbum) Spacer(modifier = Modifier.width(6.dp))
                            }
                            if (showAlbum) {
                                Text(
                                    text = albumText,
                                    color = palette.lyricDimText,
                                    fontSize = 10.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // 中央留白大字沉浸歌词舞台（左对齐 + 流光竖条焦点引导 + 同源平滑上推滚动过渡）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (config.showLyric) {
                    val lyricFrame = Triple(snapshot.prevLyric, snapshot.currentLyric, snapshot.nextLyric)
                    AnimatedContent(
                        targetState = lyricFrame,
                        transitionSpec = {
                            (slideInVertically(animationSpec = tween(340)) { (it * 0.42f).toInt() } +
                                fadeIn(animationSpec = tween(300))) togetherWith
                                (slideOutVertically(animationSpec = tween(260)) { -(it * 0.42f).toInt() } +
                                    fadeOut(animationSpec = tween(240)))
                        },
                        label = "widget_4x4_lyric_roll"
                    ) { (prevL, curL, nextL) ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (prevL.isNotBlank()) {
                                Text(
                                    text = prevL,
                                    color = palette.lyricDimText,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 11.dp)
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                bmps.lyricAccentBar?.let { barBmp ->
                                    Image(
                                        bitmap = barBmp.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.FillBounds,
                                        modifier = Modifier
                                            .width(3.5.dp)
                                            .height(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(7.5.dp))
                                }
                                Text(
                                    text = curL,
                                    color = palette.lyricActiveText,
                                    fontSize = 14.5.sp,
                                    lineHeight = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (nextL.isNotBlank()) {
                                Text(
                                    text = nextL,
                                    color = palette.lyricDimText,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 11.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 进度时间轴
            bmps.progress?.let { progBmp ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatWidgetDuration(snapshot.positionMs),
                        color = palette.lyricDimText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontFeatureSettings = "tnum"
                        )
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Image(
                        bitmap = progBmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .weight(1f)
                            .height(9.dp)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = formatWidgetDuration(snapshot.durationMs),
                        color = palette.lyricDimText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontFeatureSettings = "tnum"
                        )
                    )
                }
                Spacer(modifier = Modifier.height(9.dp))
            }

            // 底部饱满立体五键播控台
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(bitmap = bmps.modeBtn.asImageBitmap(), contentDescription = null, modifier = Modifier.size(34.dp))
                Spacer(modifier = Modifier.weight(1f))
                Image(bitmap = bmps.prevBtn.asImageBitmap(), contentDescription = null, modifier = Modifier.size(38.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Image(bitmap = bmps.playBtn.asImageBitmap(), contentDescription = null, modifier = Modifier.size(50.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Image(bitmap = bmps.nextBtn.asImageBitmap(), contentDescription = null, modifier = Modifier.size(38.dp))
                Spacer(modifier = Modifier.weight(1f))
                Image(bitmap = bmps.favBtn.asImageBitmap(), contentDescription = null, modifier = Modifier.size(34.dp))
            }
        }
    }
}
