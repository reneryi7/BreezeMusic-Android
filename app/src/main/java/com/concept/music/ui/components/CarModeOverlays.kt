package com.concept.music.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.concept.music.BuildConfig
import com.concept.music.service.*
import com.concept.music.ui.theme.*
import kotlin.math.roundToInt

/**
 * 首次连接疑似车载蓝牙时的单次底部高定询问浮层（仅在 `phone` 构建变体挂载）：
 * 一次点击永久记住（加入我的车机白名单自动挂载声学 / 标记普通蓝牙耳机永不再扰）。
 */
@Composable
fun IsolatedCarBluetoothPromptHost(
    visible: Boolean = true,
    onDecisionApplied: () -> Unit = {}
) {
    if (BuildConfig.IS_TABLET_FLAVOR || !visible) return
    val context = LocalContext.current
    val carState by CarSessionManager.state.collectAsState()
    val candidate = carState.pendingPromptCandidate

    AnimatedVisibility(
        visible = candidate != null && carState.featureEnabled && !carState.isCarModeActive,
        enter = fadeIn(tween(240)) + slideInVertically(tween(280)) { it / 2 },
        exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 2 }
    ) {
        val activeCandidate = candidate ?: return@AnimatedVisibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 86.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xF2101827),
                contentColor = Color.White,
                shadowElevation = 14.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    ConceptCyanAccent.copy(alpha = 0.14f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ConceptCyanAccent.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DirectionsCar,
                                contentDescription = null,
                                tint = ConceptCyanAccent,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "检测到车载蓝牙「${activeCandidate.displayName}」",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.5.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "是否开启主驾皇帝位声场、导航平滑闪避与中控蓝牙歌词？下车自动还原。",
                                fontSize = 11.5.sp,
                                lineHeight = 15.5.sp,
                                color = Color.White.copy(alpha = 0.74f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.08f),
                            modifier = Modifier
                                .weight(1f)
                                .softCardClickable(
                                    shape = RoundedCornerShape(12.dp),
                                    onClick = {
                                        CarSessionManager.dismissPendingPromptAsNonCar(context)
                                        onDecisionApplied()
                                    }
                                )
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "普通耳机 (不再提示)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(alpha = 0.80f)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ConceptCyanAccent.copy(alpha = 0.24f),
                            modifier = Modifier
                                .weight(1f)
                                .softCardClickable(
                                    shape = RoundedCornerShape(12.dp),
                                    onClick = {
                                        CarSessionManager.confirmPendingPromptAsCar(context)
                                        onDecisionApplied()
                                        Toast.makeText(
                                            context,
                                            "已开启车载模式 · 断开车机后自动还原日常音效",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = ConceptCyanAccent,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "设为我的车机",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = ConceptCyanAccent
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
 * 播放页顶部「车载模式」胶囊点击弹出的座舱声学快捷控制面板：
 * 大字号、高对比度、无边框大触控热区，支持一秒切换四款座舱声场与蓝牙呼吸歌词。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarModeQuickControlSheet(
    onDismiss: () -> Unit,
    onSyncLyricOffset: () -> Unit = {}
) {
    val context = LocalContext.current
    val carState by CarSessionManager.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0E1522),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 6.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.24f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(ConceptCyanAccent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DirectionsCar,
                            contentDescription = null,
                            tint = ConceptCyanAccent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "车载座舱声学中心",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 17.sp,
                            color = Color.White
                        )
                        Text(
                            text = "${carState.activeConnectionSource.label} · ${carState.activeCarDeviceName.ifBlank { "日常音效已快照保护" }}",
                            fontSize = 12.sp,
                            color = ConceptCyanAccent
                        )
                    }
                }
            }

            Text(
                text = "64-bit 座舱声场预设",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.78f)
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CarCabinAcousticMode.entries.chunked(2).forEach { rowModes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowModes.forEach { mode ->
                            val selected = carState.cabinAcousticMode == mode
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (selected) {
                                    ConceptCyanAccent.copy(alpha = 0.22f)
                                } else {
                                    Color.White.copy(alpha = 0.06f)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .softCardClickable(
                                        shape = RoundedCornerShape(16.dp),
                                        onClick = { CarSessionManager.setCabinAcousticMode(mode) }
                                    )
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (selected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(ConceptCyanAccent)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                        }
                                        Text(
                                            text = mode.label,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 14.sp,
                                            color = if (selected) ConceptCyanAccent else Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = mode.shortBadge,
                                        fontSize = 11.2.sp,
                                        color = Color.White.copy(alpha = 0.62f),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.White.copy(alpha = 0.055f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                text = "中控与仪表盘蓝牙呼吸歌词",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                color = Color.White
                            )
                            Text(
                                text = "前奏过滤制作名单 · 间奏音符呼吸 · 手机通知栏零闪烁",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.62f)
                            )
                        }
                        Switch(
                            checked = carState.carBluetoothLyricEnabled,
                            onCheckedChange = { CarSessionManager.setCarBluetoothLyricEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ConceptCyanAccent
                            )
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val pct = (carState.navDuckingTargetGain * 100f).roundToInt()
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                text = "智能导航语音平滑闪避 ($pct%)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                color = Color.White
                            )
                            Text(
                                text = "350ms 防呼吸保持 + 1.85kHz 人声频段避让",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.62f)
                            )
                        }
                        Switch(
                            checked = carState.smartNavDuckingEnabled,
                            onCheckedChange = { CarSessionManager.setSmartNavDuckingEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ConceptMintAccent
                            )
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ConceptCoralAccent.copy(alpha = 0.16f),
                modifier = Modifier
                    .fillMaxWidth()
                    .softCardClickable(
                        shape = RoundedCornerShape(14.dp),
                        onClick = {
                            CarSessionManager.disconnectCurrentCarSessionTemporarily(context)
                            onSyncLyricOffset()
                            onDismiss()
                            Toast.makeText(context, "已临时退出车载模式并还原手机日常音效", Toast.LENGTH_SHORT).show()
                        }
                    )
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PowerSettingsNew,
                        contentDescription = null,
                        tint = ConceptCoralAccent,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "退出当前车载状态并还原日常音效",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        color = ConceptCoralAccent
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
