package com.concept.music.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.concept.music.service.*
import com.concept.music.ui.theme.*
import kotlin.math.roundToInt

/**
 * 手机端「车载互联与座舱声学」专属二级设置面板：
 * 集成连接状态总控、64-bit 四模式座舱声学矩阵、智能导航平滑闪避、蓝牙高定呼吸歌词与车机白名单管理。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsCarInterconnectSection(
    onSyncLyricOffset: () -> Unit = {}
) {
    val context = LocalContext.current
    val carState by CarSessionManager.state.collectAsState()
    val permissionState by AppPermissionManager.state.collectAsState()

    LaunchedEffect(Unit) {
        CarSessionManager.init(context)
        if (permissionState.bluetoothCarEnabled && AppPermissionManager.hasBluetoothConnectSystemPermission(context)) {
            CarSessionManager.refreshPairedBluetoothDevices(context)
        }
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted || AppPermissionManager.hasBluetoothConnectSystemPermission(context)) {
            CarSessionManager.reevaluateActiveConnections(context)
            CarSessionManager.refreshPairedBluetoothDevices(context)
            Toast.makeText(context, "已同步已配对蓝牙设备列表", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "未授予附近蓝牙权限，仍可通过当前已连接音频路由名称识别车机", Toast.LENGTH_SHORT).show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. 车载互联状态总览与自动感知总控
        SettingsSectionCard(
            title = "车载互联状态与总控",
            icon = Icons.Rounded.DirectionsCar
        ) {
            val statusBrush = if (carState.isCarModeActive) {
                Brush.horizontalGradient(
                    listOf(
                        ConceptCyanAccent.copy(alpha = 0.20f),
                        ConceptPrimary.copy(alpha = 0.16f)
                    )
                )
            } else {
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)
                    )
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(statusBrush)
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (carState.isCarModeActive) ConceptCyanAccent
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (carState.isCarModeActive) {
                                    "${carState.activeConnectionSource.label} · ${carState.activeCarDeviceName.ifBlank { "座舱运行中" }}"
                                } else {
                                    "当前处于手机日常模式 · 已认证车机 ${carState.confirmedCarCount} 台"
                                },
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.5.sp,
                                color = if (carState.isCarModeActive) ConceptCyanAccent else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Text(
                        text = if (carState.isCarModeActive) {
                            "已挂载「${carState.cabinAcousticMode.label}」64-bit 声学滤波与日常快照保护，断开车机后自动 100% 无感还原手机音效。"
                        } else {
                            "支持 Android Auto、华为 HiCar、小米 CarWith、ICCOA Carlink、百度 CarLife 及车载蓝牙自动感知。"
                        },
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (carState.isCarModeActive) {
                            ConceptTonalActionButton(
                                text = if (carState.manualPreviewActive) "关闭手动车载预览" else "退出车载并还原日常音效",
                                icon = Icons.Rounded.PowerSettingsNew,
                                accentColor = ConceptCoralAccent,
                                onClick = {
                                    CarSessionManager.disconnectCurrentCarSessionTemporarily(context)
                                    onSyncLyricOffset()
                                    Toast.makeText(context, "已还原手机日常音效与音频焦点", Toast.LENGTH_SHORT).show()
                                }
                            )
                        } else {
                            ConceptTonalActionButton(
                                text = "开启车载声学预览",
                                icon = Icons.Rounded.PlayCircleOutline,
                                accentColor = ConceptCyanAccent,
                                onClick = {
                                    if (!carState.featureEnabled) {
                                        CarSessionManager.setFeatureEnabled(context, true)
                                    }
                                    CarSessionManager.setManualPreviewActive(context, true)
                                    onSyncLyricOffset()
                                    Toast.makeText(context, "已进入车载座舱预览（随时可一键还原）", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            SettingToggleRow(
                title = "启用车载互联与座舱自动感知",
                subtitle = "连接已认证车机蓝牙或车联投屏时自动挂载座舱声学、导航闪避与中控歌词",
                checked = carState.featureEnabled,
                thumbColor = ConceptCyanAccent,
                onCheckedChange = {
                    CarSessionManager.setFeatureEnabled(context, it)
                    onSyncLyricOffset()
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )

            SettingToggleRow(
                title = "连接已认证车机后自动续播",
                subtitle = "默认关闭以防上车突兀出声；开启后仅在点火连上白名单车机蓝牙时自动恢复上次队列",
                checked = carState.autoPlayOnCarConnect,
                thumbColor = ConceptMintAccent,
                onCheckedChange = { CarSessionManager.setAutoPlayOnCarConnect(it) }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )

            SettingToggleRow(
                title = "方向盘物理按键零延迟直通",
                subtitle = "车载激活期间自动跳过有线耳机单键 350ms 连击等待窗，方向盘切歌/暂停 0ms 即按即响",
                checked = carState.steeringWheelZeroLatencyEnabled,
                thumbColor = ConceptPrimary,
                onCheckedChange = { CarSessionManager.setSteeringWheelZeroLatencyEnabled(it) }
            )
        }

        // 2. 64-bit 车载座舱声学矩阵
        SettingsSectionCard(
            title = "64-bit 车载座舱音效矩阵",
            icon = Icons.Rounded.GraphicEq
        ) {
            Text(
                text = "针对汽车左舵偏置听音位、车门中低频驻波与高速胎噪掩蔽定制，仅在车载激活期间无损叠加，不污染手机耳机参数：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CarCabinAcousticMode.entries.forEach { mode ->
                    val selected = carState.cabinAcousticMode == mode
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) {
                            ConceptMintAccent.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .softCardClickable(
                                shape = RoundedCornerShape(16.dp),
                                onClick = { CarSessionManager.setCabinAcousticMode(mode) }
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = mode.label,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 14.sp,
                                        color = if (selected) ConceptMintAccent else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (mode == CarCabinAcousticMode.DRIVER_SWEET_SPOT) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = ConceptCyanAccent.copy(alpha = 0.18f)
                                        ) {
                                            Text(
                                                text = "左舵主驾推荐",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ConceptCyanAccent,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = mode.subtitle,
                                    fontSize = 11.5.sp,
                                    lineHeight = 15.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            RadioButton(
                                selected = selected,
                                onClick = { CarSessionManager.setCabinAcousticMode(mode) },
                                colors = RadioButtonDefaults.colors(selectedColor = ConceptMintAccent)
                            )
                        }
                    }
                }
            }
        }

        // 3. 智能导航语音平滑闪避
        SettingsSectionCard(
            title = "智能导航语音平滑闪避",
            icon = Icons.Rounded.Navigation
        ) {
            SettingToggleRow(
                title = "导航播报平滑压低同播",
                subtitle = "高德/百度地图语音播报时不打断音乐，采用 180ms 渐隐 + 350ms 连播保持 + 650ms 渐显包络消除音量抽搐",
                checked = carState.smartNavDuckingEnabled,
                thumbColor = ConceptCoralAccent,
                onCheckedChange = { CarSessionManager.setSmartNavDuckingEnabled(it) }
            )

            AnimatedVisibility(
                visible = carState.smartNavDuckingEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )

                    val pct = (carState.navDuckingTargetGain * 100f).roundToInt()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "导航播报时背景音乐保留比例",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "$pct%",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp,
                            color = ConceptCoralAccent
                        )
                    }

                    Slider(
                        value = carState.navDuckingTargetGain,
                        onValueChange = { CarSessionManager.setNavDuckingTargetGain(it) },
                        valueRange = 0.15f..0.50f,
                        colors = SliderDefaults.colors(
                            thumbColor = ConceptCoralAccent,
                            activeTrackColor = ConceptCoralAccent
                        )
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("轻柔 20%" to 0.20f, "均衡 28%" to 0.28f, "饱满 40%" to 0.40f).forEach { (label, v) ->
                            ConceptBorderlessChoicePill(
                                selected = kotlin.math.abs(carState.navDuckingTargetGain - v) < 0.02f,
                                label = label,
                                accentColor = ConceptCoralAccent,
                                onClick = { CarSessionManager.setNavDuckingTargetGain(v) }
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )

                    SettingToggleRow(
                        title = "1.85kHz 导航人声频段动态避让",
                        subtitle = "闪避期间自动对音乐 1.85kHz 人声核心频段做 -3.5dB 窄带微凹避让，无需大幅压低音乐也能清晰听清路口提示",
                        checked = carState.navSpectralDuckingEnabled,
                        thumbColor = ConceptCoralAccent,
                        onCheckedChange = { CarSessionManager.setNavSpectralDuckingEnabled(it) }
                    )
                }
            }
        }

        // 4. 车载蓝牙高定呼吸歌词与车机白名单
        SettingsSectionCard(
            title = "车载蓝牙歌词与车机管理",
            icon = Icons.Rounded.BluetoothAudio
        ) {
            SettingToggleRow(
                title = "原厂中控/仪表盘蓝牙呼吸歌词",
                subtitle = "通过 AVRCP 实时推送纯净唱词，前奏智能过滤作词作曲流水账，且手机本机通知栏与锁屏保持原歌名零闪烁",
                checked = carState.carBluetoothLyricEnabled,
                thumbColor = ConceptCyanAccent,
                onCheckedChange = { CarSessionManager.setCarBluetoothLyricEnabled(it) }
            )

            AnimatedVisibility(
                visible = carState.carBluetoothLyricEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "超过 5 秒长间奏中控显示样式",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CarInterludeStyle.entries.forEach { style ->
                            ConceptBorderlessChoicePill(
                                selected = carState.carInterludeStyle == style,
                                label = style.label,
                                accentColor = ConceptCyanAccent,
                                onClick = { CarSessionManager.setCarInterludeStyle(style) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    val offsetSign = if (carState.carLyricOffsetMs > 0) "+" else ""
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "车载蓝牙音频延迟校准",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "${offsetSign}${carState.carLyricOffsetMs} ms",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp,
                            color = ConceptCyanAccent
                        )
                    }
                    Slider(
                        value = carState.carLyricOffsetMs.toFloat(),
                        onValueChange = {
                            val snapped = ((it / 50f).roundToInt() * 50L).coerceIn(-1000L, 600L)
                            CarSessionManager.setCarLyricOffsetMs(snapped)
                            onSyncLyricOffset()
                        },
                        valueRange = -1000f..600f,
                        colors = SliderDefaults.colors(
                            thumbColor = ConceptCyanAccent,
                            activeTrackColor = ConceptCyanAccent
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConceptBorderlessChoicePill(
                            selected = false,
                            label = "提前 50ms",
                            accentColor = ConceptCyanAccent,
                            onClick = {
                                CarSessionManager.setCarLyricOffsetMs(carState.carLyricOffsetMs - 50L)
                                onSyncLyricOffset()
                            }
                        )
                        ConceptBorderlessChoicePill(
                            selected = carState.carLyricOffsetMs == -250L,
                            label = "默认 -250ms",
                            accentColor = ConceptCyanAccent,
                            onClick = {
                                CarSessionManager.setCarLyricOffsetMs(-250L)
                                onSyncLyricOffset()
                            }
                        )
                        ConceptBorderlessChoicePill(
                            selected = false,
                            label = "延后 50ms",
                            accentColor = ConceptCyanAccent,
                            onClick = {
                                CarSessionManager.setCarLyricOffsetMs(carState.carLyricOffsetMs + 50L)
                                onSyncLyricOffset()
                            }
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "我的车机蓝牙白名单",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold)
                    )
                    Text(
                        text = "绑定为「我的车机」后每次点火连上即自动激活车载模式，普通蓝牙耳机绝不误触发",
                        fontSize = 11.2.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ConceptTonalActionButton(
                    text = "扫描配对蓝牙",
                    icon = Icons.AutoMirrored.Rounded.BluetoothSearching,
                    accentColor = ConceptCyanAccent,
                    onClick = {
                        if (!permissionState.bluetoothCarEnabled) {
                            Toast.makeText(context, "请先在「设置 -> 权限管理」中开启车载蓝牙识别权限", Toast.LENGTH_SHORT).show()
                            return@ConceptTonalActionButton
                        }
                        val reqPerms = AppPermissionManager.requiredBluetoothConnectRuntimePermissions()
                        if (reqPerms.isNotEmpty() && !AppPermissionManager.hasBluetoothConnectSystemPermission(context)) {
                            btPermissionLauncher.launch(reqPerms)
                        } else {
                            CarSessionManager.reevaluateActiveConnections(context)
                            CarSessionManager.refreshPairedBluetoothDevices(context)
                            Toast.makeText(context, "已刷新已连接与已配对蓝牙列表", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            val mergedCandidates = remember(
                carState.currentBluetoothSinks,
                carState.pairedBluetoothDevices,
                carState.knownDevices
            ) {
                val map = LinkedHashMap<String, Triple<String, String, Boolean>>()
                carState.currentBluetoothSinks.forEach { s ->
                    map[s.deviceKey] = Triple(s.displayName, s.macAddress, true)
                }
                carState.knownDevices.forEach { rec ->
                    if (!map.containsKey(rec.deviceKey)) {
                        map[rec.deviceKey] = Triple(rec.displayName, rec.macAddress, false)
                    }
                }
                carState.pairedBluetoothDevices.forEach { p ->
                    if (!map.containsKey(p.deviceKey)) {
                        map[p.deviceKey] = Triple(p.displayName, p.macAddress, false)
                    }
                }
                map.entries.toList()
            }

            if (mergedCandidates.isEmpty()) {
                Text(
                    text = "暂未检测到蓝牙音频外设。你可连接车机蓝牙后自动识别，或点击上方「扫描配对蓝牙」提前勾选爱车蓝牙。",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    mergedCandidates.forEach { (devKey, meta) ->
                        val (displayName, macAddr, isConnectedNow) = meta
                        val existingRecord = carState.knownDevices.firstOrNull {
                            it.deviceKey == devKey ||
                                (macAddr.isNotBlank() && it.macAddress.equals(macAddr, ignoreCase = true))
                        }
                        val isConfirmedCar = existingRecord?.classification == CarDeviceClassification.CONFIRMED_CAR
                        val isIgnored = existingRecord?.classification == CarDeviceClassification.IGNORED_NON_CAR

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isConfirmedCar) {
                                ConceptCyanAccent.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (isConfirmedCar) Icons.Rounded.DirectionsCar else Icons.Rounded.Bluetooth,
                                            contentDescription = null,
                                            tint = if (isConfirmedCar) ConceptCyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = displayName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.2.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    val subTag = buildString {
                                        if (isConnectedNow) append("当前已连接 · ")
                                        when {
                                            isConfirmedCar -> append("已认证我的车机")
                                            isIgnored -> append("已标记普通耳机/音箱")
                                            else -> append("未分类蓝牙设备")
                                        }
                                    }
                                    Text(
                                        text = subTag,
                                        fontSize = 11.sp,
                                        color = if (isConfirmedCar) ConceptCyanAccent else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (isConfirmedCar) {
                                        ConceptBorderlessChoicePill(
                                            selected = true,
                                            label = "取消车机",
                                            accentColor = ConceptCoralAccent,
                                            onClick = {
                                                CarSessionManager.removeKnownDevice(context, existingRecord?.deviceKey ?: devKey)
                                                onSyncLyricOffset()
                                            }
                                        )
                                    } else {
                                        ConceptBorderlessChoicePill(
                                            selected = false,
                                            label = "设为车机",
                                            accentColor = ConceptCyanAccent,
                                            onClick = {
                                                CarSessionManager.setDeviceClassification(
                                                    context = context,
                                                    deviceKey = devKey,
                                                    displayName = displayName,
                                                    macAddress = macAddr,
                                                    classification = CarDeviceClassification.CONFIRMED_CAR
                                                )
                                                onSyncLyricOffset()
                                                Toast.makeText(context, "已将「$displayName」加入我的车机白名单", Toast.LENGTH_SHORT).show()
                                            }
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
}
