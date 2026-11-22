package com.concept.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.concept.music.data.model.ContinuePlaySnapshot
import com.concept.music.data.model.Song
import com.concept.music.ui.theme.ConceptAnimatedOverlaySheet
import com.concept.music.ui.theme.ConceptCyanAccent
import com.concept.music.ui.theme.ConceptPrimary
import com.concept.music.ui.theme.conceptElevatedSurfaceColor
import com.concept.music.ui.theme.conceptFloatingCardColor
import com.concept.music.ui.theme.conceptSheetContainerColor
import com.concept.music.ui.theme.softCardClickable
import com.concept.music.ui.theme.softClickable

internal fun resolveDeviceIcon(deviceLabel: String): ImageVector {
    val lower = deviceLabel.lowercase()
    return when {
        lower.contains("电脑") || lower.contains("pc") || lower.contains("windows") || lower.contains("mac") || lower.contains("桌面") -> Icons.Rounded.Computer
        lower.contains("平板") || lower.contains("pad") || lower.contains("tablet") -> Icons.Rounded.TabletAndroid
        lower.contains("车载") || lower.contains("car") -> Icons.Rounded.DirectionsCar
        lower.contains("电视") || lower.contains("tv") -> Icons.Rounded.Tv
        lower.contains("音箱") || lower.contains("speaker") -> Icons.Rounded.Speaker
        lower.contains("网页") || lower.contains("web") -> Icons.Rounded.Language
        else -> Icons.Rounded.Devices
    }
}

/**
 * 发现页轻量「跨设备继续播放」接力横幅：
 * - 仅在云端存在其他设备（或远端最近在听）且与当前本机正在播放歌曲不同时浮现；
 * - 卡片高度克制，单行展示来源设备标签 + 封面 + 歌名/歌手 + 「继续听」主胶囊 + 关闭按钮；
 * - 点击横幅主体可展开完整跨设备队列与「同步本机到云端」接力抽屉。
 */
@Composable
fun CrossDeviceContinueBanner(
    snapshot: ContinuePlaySnapshot,
    onContinueNow: (Song, List<Song>) -> Unit,
    onOpenSheet: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primarySong = snapshot.primarySong ?: return
    val deviceLabel = snapshot.primaryDeviceLabel.ifBlank { "云端设备" }
    val primarySession = snapshot.deviceGroups.firstOrNull()
    val queueSongs = primarySession?.songs?.takeIf { it.isNotEmpty() } ?: snapshot.queue
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant
    val cardBg = conceptFloatingCardColor()

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cardBg,
        shadowElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .softCardClickable(shape = RoundedCornerShape(20.dp), onClick = onOpenSheet)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            ConceptPrimary.copy(alpha = 0.14f),
                            ConceptCyanAccent.copy(alpha = 0.06f),
                            Color.Transparent
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ConceptPrimary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (primarySong.coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = primarySong.coverUrl,
                            contentDescription = primarySong.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = ConceptPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = resolveDeviceIcon(deviceLabel),
                            contentDescription = null,
                            tint = ConceptPrimary,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "继续播放 · $deviceLabel",
                            color = ConceptPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (snapshot.deviceGroups.size > 1) {
                            Text(
                                text = "等 ${snapshot.deviceGroups.size} 台",
                                color = textSecondary,
                                fontSize = 10.5.sp,
                                maxLines = 1
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${primarySong.title} - ${primarySong.artist.ifBlank { "未知歌手" }}",
                        color = textPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ConceptPrimary,
                    modifier = Modifier.softCardClickable(shape = RoundedCornerShape(14.dp)) {
                        onContinueNow(primarySong, queueSongs)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "继续听",
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "继续听",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .softClickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "暂不提示",
                        tint = textSecondary.copy(alpha = 0.72f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * 跨设备「继续播放」接力面板：
 * - 按设备分组展示所有云端同步的最近播放队列，一键接力播放整列或指定单曲；
 * - 支持手动刷新云端状态，及将本机当前播放歌曲主动同步给其他设备（如桌面端/平板）。
 */
@Composable
fun CrossDeviceContinueSheet(
    snapshot: ContinuePlaySnapshot,
    loading: Boolean,
    currentLocalSong: Song?,
    onRefresh: () -> Unit,
    onPlaySong: (Song, List<Song>) -> Unit,
    onSyncCurrentLocalSong: () -> Unit,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val sheetBg = conceptSheetContainerColor()
    val cardBg = conceptElevatedSurfaceColor()
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant

    var selectedSessionIdx by remember(snapshot.deviceGroups) { mutableIntStateOf(0) }
    val safeIdx = selectedSessionIdx.coerceIn(0, (snapshot.deviceGroups.size - 1).coerceAtLeast(0))
    val activeSession = snapshot.deviceGroups.getOrNull(safeIdx)
    val activeSongs = activeSession?.songs ?: snapshot.queue

    val panelShape = if (isLandscape) {
        RoundedCornerShape(26.dp)
    } else {
        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    }

    ConceptAnimatedOverlaySheet(
        onDismiss = onDismiss,
        isLandscape = isLandscape,
        zIndex = 90f
    ) {
        Surface(
            shape = panelShape,
            color = sheetBg,
            contentColor = textPrimary,
            shadowElevation = 24.dp,
            modifier = Modifier
                .then(
                    if (isLandscape) {
                        Modifier
                            .padding(horizontal = 24.dp, vertical = 14.dp)
                            .widthIn(max = 600.dp)
                            .fillMaxWidth()
                            .fillMaxHeight(0.88f)
                    } else {
                        Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .heightIn(min = 340.dp, max = 600.dp)
                    }
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            CompositionLocalProvider(LocalContentColor provides textPrimary) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                        .navigationBarsPadding()
                ) {
                    if (!isLandscape) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .sheetVerticalDragGesture()
                                .padding(top = 4.dp, bottom = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(textSecondary.copy(alpha = 0.28f))
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "跨设备继续播放",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = textPrimary
                            )
                            Text(
                                text = "同步同账号手机、平板、电脑等设备的云端播放进度",
                                fontSize = 11.5.sp,
                                color = textSecondary
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = CircleShape,
                                color = cardBg,
                                modifier = Modifier
                                    .size(34.dp)
                                    .softClickable(onClick = onRefresh)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (loading) {
                                        CircularProgressIndicator(
                                            color = ConceptPrimary,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = "刷新云端队列",
                                            tint = textPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            Surface(
                                shape = CircleShape,
                                color = cardBg,
                                modifier = Modifier
                                    .size(34.dp)
                                    .softClickable(onClick = requestDismiss)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "关闭",
                                        tint = textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 将本机当前播放同步至云端卡片（双向接力）
                    if (currentLocalSong != null && !currentLocalSong.isCloudSong) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = ConceptPrimary.copy(alpha = 0.11f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudUpload,
                                    contentDescription = null,
                                    tint = ConceptPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "本机正在听：${currentLocalSong.title}",
                                        color = textPrimary,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "同步后可在电脑或其他设备一键续播",
                                        color = textSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(11.dp),
                                    color = ConceptPrimary,
                                    modifier = Modifier.softCardClickable(shape = RoundedCornerShape(11.dp)) {
                                        onSyncCurrentLocalSong()
                                    }
                                ) {
                                    Text(
                                        text = "同步云端",
                                        color = Color.White,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 设备分组胶囊栏
                    if (snapshot.deviceGroups.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            snapshot.deviceGroups.forEachIndexed { idx, session ->
                                val selected = idx == safeIdx
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) ConceptPrimary.copy(alpha = 0.20f) else cardBg,
                                    modifier = Modifier.softCardClickable(shape = RoundedCornerShape(12.dp)) {
                                        selectedSessionIdx = idx
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = resolveDeviceIcon(session.sourceLabel),
                                            contentDescription = null,
                                            tint = if (selected) ConceptPrimary else textSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "${session.sourceLabel} (${session.songs.size})",
                                            color = if (selected) ConceptPrimary else textPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (activeSongs.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "最近播放队列 · 共 ${activeSongs.size} 首",
                                color = textSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Surface(
                                shape = RoundedCornerShape(11.dp),
                                color = ConceptPrimary.copy(alpha = 0.16f),
                                modifier = Modifier.softCardClickable(shape = RoundedCornerShape(11.dp)) {
                                    onPlaySong(activeSongs.first(), activeSongs)
                                    requestDismiss()
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = ConceptPrimary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "接力播放全部",
                                        color = ConceptPrimary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (loading && activeSongs.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = ConceptPrimary, strokeWidth = 2.5.dp)
                            }
                        } else if (activeSongs.isEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DevicesOther,
                                    contentDescription = null,
                                    tint = textSecondary.copy(alpha = 0.5f),
                                    modifier = Modifier.size(38.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "暂无云端跨设备播放记录",
                                    color = textSecondary,
                                    fontSize = 13.5.sp
                                )
                                Text(
                                    text = "登录同一酷狗账号并在任意设备听歌后将自动同步",
                                    color = textSecondary.copy(alpha = 0.72f),
                                    fontSize = 11.5.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                                contentPadding = PaddingValues(bottom = 12.dp)
                            ) {
                                itemsIndexed(
                                    items = activeSongs,
                                    key = { idx, item -> "handoff_${idx}_${item.hash.ifBlank { item.id }}" }
                                ) { idx, item ->
                                    Surface(
                                        shape = RoundedCornerShape(15.dp),
                                        color = cardBg,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .softCardClickable(shape = RoundedCornerShape(15.dp)) {
                                                onPlaySong(item, activeSongs)
                                                requestDismiss()
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 11.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${idx + 1}",
                                                color = if (idx == 0) ConceptPrimary else textSecondary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                modifier = Modifier.width(22.dp)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(ConceptPrimary.copy(alpha = 0.14f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (item.coverUrl.isNotBlank()) {
                                                    AsyncImage(
                                                        model = item.coverUrl,
                                                        contentDescription = item.title,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Rounded.MusicNote,
                                                        contentDescription = null,
                                                        tint = ConceptPrimary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.title,
                                                    color = textPrimary,
                                                    fontSize = 13.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = buildString {
                                                        append(item.artist.ifBlank { "未知歌手" })
                                                        if (item.sourceDeviceLabel.isNotBlank()) {
                                                            append(" · ${item.sourceDeviceLabel}")
                                                        }
                                                    },
                                                    color = textSecondary,
                                                    fontSize = 11.5.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Icon(
                                                imageVector = Icons.Rounded.PlayCircleOutline,
                                                contentDescription = "播放",
                                                tint = ConceptPrimary.copy(alpha = 0.85f),
                                                modifier = Modifier.size(22.dp)
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
}
