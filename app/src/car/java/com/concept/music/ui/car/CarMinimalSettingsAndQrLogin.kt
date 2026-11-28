package com.concept.music.ui.car

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.concept.music.BuildConfig
import com.concept.music.data.kugou.AuthState
import com.concept.music.data.model.AudioQuality
import com.concept.music.service.AudioCacheLimit
import com.concept.music.service.AudioCacheManager
import com.concept.music.service.CarSessionManager
import com.concept.music.ui.account.decodeQr
import com.concept.music.ui.settings.UpdateCheckResult
import com.concept.music.ui.settings.checkGitHubLatestRelease
import com.concept.music.ui.settings.openExternalUrl
import com.concept.music.ui.theme.*
import com.concept.music.ui.viewmodel.LibraryViewModel
import com.concept.music.ui.viewmodel.LoginTabs
import com.concept.music.ui.viewmodel.MusicPlayerViewModel
import com.concept.music.ui.viewmodel.PlayerBackgroundStyle
import com.concept.music.util.SecurityGuards
import kotlinx.coroutines.launch

/**
 * 车机独立包极简设置半屏卡：
 * 严格遵循最小可用确认清单：
 * 1. 当前账号 + 静默 VIP 有效期微标与一键补领 + 大屏双扫码入口 / 退出换号（无多账号列表、无远程踢设备）；
 * 2. 默认播放音质 + 移动网络省流开关（E7）；
 * 3. 日夜外观（跟随车灯 / 常驻暗黑）+ 座舱动态背景 + 车机性能画质档位（E4 / E5）；
 * 4. 启动/点火自动续播（E12）+ 仪表盘歌词推送（D9）+ 极简缓存（E6）+ 低调版本检查更新（E11）。
 */
@Composable
internal fun CarMinimalSettingsOverlay(
    playerViewModel: MusicPlayerViewModel,
    libraryViewModel: LibraryViewModel,
    onOpenQrLogin: () -> Unit,
    onOpenCabinAcoustic: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerState by playerViewModel.uiState.collectAsState()
    val accountState by libraryViewModel.accountState.collectAsState()
    val vipState by libraryViewModel.vipState.collectAsState()
    val themeMode by ThemeManager.themeMode.collectAsState()
    val perfMode by CarPerformanceManager.mode.collectAsState()
    val renderCaps by CarPerformanceManager.capabilities.collectAsState()
    val carState by CarSessionManager.state.collectAsState()
    val cacheStats by AudioCacheManager.detailedStats.collectAsState()
    val cacheLimit by AudioCacheManager.cacheLimit.collectAsState()

    var updateState by remember { mutableStateOf<UpdateCheckResult>(UpdateCheckResult.Idle) }
    var clearingCache by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        AudioCacheManager.refreshCacheSize(context)
    }

    val isLoggedIn = accountState.auth == AuthState.READY && !accountState.currentId.isNullOrBlank()
    val vipSummary = remember(vipState.snapshot) {
        val activeBenefit = vipState.snapshot.benefits.firstOrNull { it.active }
        if (activeBenefit != null) {
            activeBenefit.display()
        } else if (vipState.snapshot.checked) {
            "概念版 VIP 待续期 · 点此补领"
        } else {
            "每日静默自动续期 VIP 守护中"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB8040812))
            .softCardClickable(shape = RoundedCornerShape(0.dp), clip = false, pressScale = 1f, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = Color(0xF60B1322),
            contentColor = Color.White,
            modifier = Modifier
                .widthIn(max = 780.dp)
                .fillMaxWidth(0.90f)
                .fillMaxHeight(0.88f)
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(30.dp))
                .softCardClickable(shape = RoundedCornerShape(30.dp), clip = false, pressScale = 1f, onClick = {})
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 18.dp)
            ) {
                // 顶部标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = ConceptCyanAccent,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "座舱极简设置",
                            fontSize = 20.sp,
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
                            Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. 账号与静默 VIP 守护卡
                    CarSettingsGlassBlock {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                val avatarUrl = accountState.profile?.avatarUrl.orEmpty()
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(CircleShape)
                                        .background(ConceptPrimary.copy(alpha = 0.20f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (avatarUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(avatarUrl)
                                                .size(128, 128)
                                                .allowHardware(false)
                                                .build(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.Person,
                                            contentDescription = null,
                                            tint = ConceptCyanAccent,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (isLoggedIn) {
                                                accountState.profile?.nickname?.ifBlank { "酷狗用户" } ?: "酷狗用户"
                                            } else {
                                                "未登录账号"
                                            },
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isLoggedIn) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = VipGold.copy(alpha = 0.22f),
                                                modifier = Modifier.softCardClickable(
                                                    shape = RoundedCornerShape(8.dp),
                                                    onClick = {
                                                        libraryViewModel.claimVip()
                                                        Toast.makeText(context, "正在静默补领今日概念版 VIP...", Toast.LENGTH_SHORT).show()
                                                    }
                                                )
                                            ) {
                                                Text(
                                                    text = if (vipState.snapshot.isVip) "VIP 畅听" else "补领 VIP",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = VipGold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = if (isLoggedIn) vipSummary else "手机扫码秒登，同步云端歌单与无损权益",
                                        fontSize = 12.5.sp,
                                        color = if (isLoggedIn && vipState.snapshot.isVip) VipGold.copy(alpha = 0.86f) else Color.White.copy(alpha = 0.65f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))
                            if (isLoggedIn) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = ConceptCoralAccent.copy(alpha = 0.18f),
                                    modifier = Modifier.softCardClickable(
                                        shape = RoundedCornerShape(14.dp),
                                        onClick = {
                                            accountState.currentId?.let { libraryViewModel.removeAccount(it) }
                                            onOpenQrLogin()
                                        }
                                    )
                                ) {
                                    Text(
                                        text = "退出换号",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ConceptCoralAccent,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                                    )
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = ConceptCyanAccent.copy(alpha = 0.24f),
                                    modifier = Modifier.softCardClickable(
                                        shape = RoundedCornerShape(14.dp),
                                        onClick = onOpenQrLogin
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.QrCode2,
                                            contentDescription = null,
                                            tint = ConceptCyanAccent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "大屏扫码登录",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = ConceptCyanAccent
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. 音质、座舱声场与流量省流（A12 + D1 + E7）
                    CarSettingsGlassBlock {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("默认播放音质", fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = ConceptCyanAccent.copy(alpha = 0.16f),
                                modifier = Modifier.softCardClickable(
                                    shape = RoundedCornerShape(12.dp),
                                    onClick = {
                                        onDismiss()
                                        onOpenCabinAcoustic()
                                    }
                                )
                            ) {
                                Text(
                                    text = "64-bit 声场 · ${carState.cabinAcousticMode.shortBadge} ›",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ConceptCyanAccent,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                AudioQuality.STANDARD to "标准",
                                AudioQuality.HIGH to "高品 320K",
                                AudioQuality.LOSSLESS to "无损 FLAC",
                                AudioQuality.HI_RES to "Hi-Res"
                            ).forEach { (q, label) ->
                                val selected = playerState.preferredQuality == q
                                CarChoicePill(
                                    label = label,
                                    selected = selected,
                                    accent = if (q == AudioQuality.HI_RES) VipGold else ConceptCyanAccent,
                                    modifier = Modifier.weight(1f),
                                    onClick = { playerViewModel.setPreferredQuality(q) }
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = Color.White.copy(alpha = 0.08f)
                        )
                        CarSettingSwitchRow(
                            title = "移动网络省流（4G / 5G 自动 320K）",
                            subtitle = "车联网流量环境下自动切至高品 320K 省流，连接 Wi-Fi 自动恢复无损 / Hi-Res",
                            checked = playerState.wifiOnlyLossless,
                            accent = ConceptMintAccent,
                            onCheckedChange = { playerViewModel.setWifiOnlyLossless(it) }
                        )
                    }

                    // 3. 日夜外观、座舱背景与车机性能画质档位（E4 + A5 + E5）
                    CarSettingsGlassBlock {
                        Text("日夜外观与座舱视觉", fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CarChoicePill(
                                label = "跟随车灯日夜",
                                selected = themeMode == AppThemeMode.SYSTEM,
                                accent = ConceptCyanAccent,
                                modifier = Modifier.weight(1f),
                                onClick = { ThemeManager.setThemeMode(context, AppThemeMode.SYSTEM) }
                            )
                            CarChoicePill(
                                label = "常驻暗夜防眩光",
                                selected = themeMode != AppThemeMode.SYSTEM,
                                accent = ConceptPrimary,
                                modifier = Modifier.weight(1f),
                                onClick = { ThemeManager.setThemeMode(context, AppThemeMode.DARK_ABYSS_BLUE) }
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PlayerBackgroundStyle.entries.forEach { bg ->
                                CarChoicePill(
                                    label = bg.label,
                                    selected = playerState.playerBackgroundStyle == bg,
                                    accent = ConceptCyanAccent,
                                    modifier = Modifier.weight(1f),
                                    onClick = { playerViewModel.setPlayerBackgroundStyle(bg) }
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = Color.White.copy(alpha = 0.08f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("车机性能与画质档位", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                text = "当前生效：${renderCaps.tier.label}",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = ConceptMintAccent
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CarPerformanceMode.entries.forEach { pm ->
                                CarChoicePill(
                                    label = pm.label,
                                    selected = perfMode == pm,
                                    accent = ConceptMintAccent,
                                    modifier = Modifier.weight(1f),
                                    onClick = { CarPerformanceManager.setMode(pm) }
                                )
                            }
                        }
                    }

                    // 4. 点火续播、仪表盘歌词、缓存与检查更新（E12 + D9 + E6 + E11）
                    CarSettingsGlassBlock {
                        CarSettingSwitchRow(
                            title = "启动与车辆点火自动续播",
                            subtitle = "打开车机或车辆开机后自动恢复上次熄火前的队列与毫秒级进度",
                            checked = playerState.autoPlayOnStartup,
                            accent = ConceptCyanAccent,
                            onCheckedChange = { playerViewModel.setAutoPlayOnStartup(it) }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = Color.White.copy(alpha = 0.08f)
                        )
                        CarSettingSwitchRow(
                            title = "液晶仪表盘 / 原厂桌面卡实时歌词",
                            subtitle = "通过系统 MediaSession 标题向支持车型仪表盘推送当前唱词（默认关闭保留纯歌名）",
                            checked = carState.carBluetoothLyricEnabled,
                            accent = ConceptPrimary,
                            onCheckedChange = { CarSessionManager.setCarBluetoothLyricEnabled(it) }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = Color.White.copy(alpha = 0.08f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("隧道与地库音频缓存", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(
                                    text = "已缓存 ${AudioCacheManager.formatSize(cacheStats.totalBytes)} · 自动预缓存下一首",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.62f)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = ConceptCoralAccent.copy(alpha = 0.16f),
                                modifier = Modifier.softCardClickable(
                                    enabled = !clearingCache,
                                    shape = RoundedCornerShape(12.dp),
                                    onClick = {
                                        clearingCache = true
                                        AudioCacheManager.clearCache(context) {
                                            clearingCache = false
                                            Toast.makeText(context, "已清空全部音频与图片缓存", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            ) {
                                Text(
                                    text = if (clearingCache) "清理中..." else "一键清空",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ConceptCoralAccent,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(AudioCacheLimit.GB_1, AudioCacheLimit.GB_2, AudioCacheLimit.GB_5).forEach { lim ->
                                CarChoicePill(
                                    label = "上限 ${lim.label}",
                                    selected = cacheLimit == lim,
                                    accent = ConceptCyanAccent,
                                    modifier = Modifier.weight(1f),
                                    onClick = { AudioCacheManager.setCacheLimit(context, lim) }
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = Color.White.copy(alpha = 0.08f)
                        )
                        // 底部低调版本号与检查更新
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "微风音乐车机 · v${BuildConfig.VERSION_NAME}",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(alpha = 0.78f)
                                )
                                when (val st = updateState) {
                                    is UpdateCheckResult.HasUpdate -> Text(
                                        text = "发现新版本 ${st.latestVersion}（点击右侧前往下载）",
                                        fontSize = 12.sp,
                                        color = ConceptMintAccent
                                    )
                                    is UpdateCheckResult.UpToDate -> Text(
                                        text = "已是最新正式版",
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.56f)
                                    )
                                    is UpdateCheckResult.Error -> Text(
                                        text = st.message,
                                        fontSize = 12.sp,
                                        color = ConceptCoralAccent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    else -> Unit
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.09f),
                                modifier = Modifier.softCardClickable(
                                    enabled = updateState !is UpdateCheckResult.Checking,
                                    shape = RoundedCornerShape(12.dp),
                                    onClick = {
                                        val cur = updateState
                                        if (cur is UpdateCheckResult.HasUpdate) {
                                            openExternalUrl(context, cur.downloadUrl)
                                        } else {
                                            updateState = UpdateCheckResult.Checking
                                            scope.launch {
                                                updateState = checkGitHubLatestRelease(BuildConfig.VERSION_NAME)
                                            }
                                        }
                                    }
                                )
                            ) {
                                Text(
                                    text = when (updateState) {
                                        is UpdateCheckResult.Checking -> "检查中..."
                                        is UpdateCheckResult.HasUpdate -> "下载新版"
                                        else -> "检查更新"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ConceptCyanAccent,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
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
 * 车机独立包专属双大屏扫码登录浮层（酷狗扫码 + QQ 扫码）：
 * 彻底移除手机号键盘输入与保存二维码按钮，大尺寸高对比度纯白底框扫码秒识别。
 */
@Composable
internal fun CarQrLoginOverlay(
    libraryViewModel: LibraryViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val loginState by libraryViewModel.login.collectAsState()

    DisposableEffect(context, hostView) {
        val window = (context as? Activity)?.window
        SecurityGuards.acquireSensitiveWindowProtection(window, hostView)
        libraryViewModel.openLogin()
        libraryViewModel.chooseTab(LoginTabs.KUGOU_QR)
        onDispose {
            SecurityGuards.releaseSensitiveWindowProtection(window, hostView)
            libraryViewModel.closeLogin()
        }
    }

    LaunchedEffect(loginState.completed) {
        if (loginState.completed) {
            Toast.makeText(context, "扫码登录成功，正在同步云端歌单", Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }

    val isQqTab = loginState.tab == LoginTabs.QQ_QR
    val activeQrRaw = if (isQqTab) loginState.qqQrImageUrl else loginState.qr?.imageUrl
    val activeStatus = if (isQqTab) loginState.qqQrStatus else loginState.qrStatus
    val qrBitmap = remember(activeQrRaw) { decodeQr(activeQrRaw) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC040812))
            .softCardClickable(shape = RoundedCornerShape(0.dp), clip = false, pressScale = 1f, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = Color(0xF60C1526),
            contentColor = Color.White,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.88f)
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(30.dp))
                .softCardClickable(shape = RoundedCornerShape(30.dp), clip = false, pressScale = 1f, onClick = {})
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "车机大屏扫码登录",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.10f),
                        modifier = Modifier
                            .size(44.dp)
                            .softCardClickable(shape = CircleShape, onClick = onDismiss)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
                        }
                    }
                }

                // 酷狗扫码 / QQ 扫码双大屏切换
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CarChoicePill(
                        label = "酷狗音乐扫码",
                        selected = !isQqTab,
                        accent = ConceptCyanAccent,
                        modifier = Modifier.weight(1f),
                        onClick = { libraryViewModel.chooseTab(LoginTabs.KUGOU_QR) }
                    )
                    CarChoicePill(
                        label = "手机 QQ 扫码",
                        selected = isQqTab,
                        accent = ConceptPrimary,
                        modifier = Modifier.weight(1f),
                        onClick = { libraryViewModel.chooseTab(LoginTabs.QQ_QR) }
                    )
                }

                // 高对比度纯白底框大二维码
                Box(
                    modifier = Modifier
                        .size(252.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "登录二维码",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(color = ConceptPrimary, strokeWidth = 3.dp)
                    }
                }

                Text(
                    text = activeStatus,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.88f)
                )

                loginState.message?.takeIf { it.isNotBlank() }?.let { msg ->
                    Text(
                        text = msg,
                        fontSize = 12.5.sp,
                        color = ConceptCyanAccent
                    )
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ConceptCyanAccent.copy(alpha = 0.20f),
                    modifier = Modifier.softCardClickable(
                        enabled = !loginState.busy,
                        shape = RoundedCornerShape(14.dp),
                        onClick = {
                            if (isQqTab) libraryViewModel.createQqQr() else libraryViewModel.createQr()
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = ConceptCyanAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "刷新二维码",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = ConceptCyanAccent
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CarSettingsGlassBlock(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.White.copy(alpha = 0.055f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(22.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 15.dp),
            content = content
        )
    }
}

@Composable
internal fun CarChoicePill(
    label: String,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.07f),
        modifier = modifier
            .border(
                width = if (selected) 1.2.dp else 0.8.dp,
                color = if (selected) accent.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(14.dp)
            )
            .softCardClickable(shape = RoundedCornerShape(14.dp), onClick = onClick)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = if (selected) accent else Color.White.copy(alpha = 0.80f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CarSettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = Color.White.copy(alpha = 0.62f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent
            )
        )
    }
}
