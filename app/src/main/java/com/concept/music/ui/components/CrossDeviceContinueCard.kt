package com.concept.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.concept.music.data.model.ContinuePlaySnapshot
import com.concept.music.data.model.Song
import com.concept.music.ui.theme.ConceptCyanAccent
import com.concept.music.ui.theme.ConceptFluidRefreshIcon
import com.concept.music.ui.theme.ConceptPrimary
import com.concept.music.ui.theme.conceptCyanAccentColor
import com.concept.music.ui.theme.conceptElevatedSurfaceColor
import com.concept.music.ui.theme.conceptFloatingCardColor
import com.concept.music.ui.theme.conceptGoldAccentColor
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
 * - 修复长设备名挤压「等 N 台」溢出；
 * - 扩充关闭按钮触控热区至 38dp 并与主按钮拉开 8dp 间距防误触；
 * - 封面缩略图接入 rememberOptimizedAsyncImageModel。
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
    val coverModel = rememberOptimizedAsyncImageModel(primarySong.coverUrl, sizePx = 128)

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
                    .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ConceptPrimary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverModel != null) {
                        AsyncImage(
                            model = coverModel,
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
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (snapshot.deviceGroups.size > 1) {
                            Text(
                                text = "等 ${snapshot.deviceGroups.size} 台",
                                color = textSecondary,
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                softWrap = false
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
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
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

                Spacer(modifier = Modifier.width(6.dp))

                Box(
                    modifier = Modifier
                        .size(38.dp)
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
 * - 统一使用 ConceptAdaptiveBottomSheetContainer 支持平板横竖屏居中卡与 onDismissStart 底栏联动；
 * - 补齐 ConceptFluidRefreshIcon、音质徽标、当前播放高亮、单曲「下一首」与网络错态重试按钮。
 */
@Composable
fun CrossDeviceContinueSheet(
    snapshot: ContinuePlaySnapshot,
    loading: Boolean,
    errorMessage: String? = null,
    currentLocalSong: Song?,
    onRefresh: () -> Unit,
    onPlaySong: (Song, List<Song>) -> Unit,
    onAddToNext: ((Song) -> Unit)? = null,
    onSyncCurrentLocalSong: () -> Unit,
    onDismissStart: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val cardBg = conceptElevatedSurfaceColor()
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant
    val accentCyan = conceptCyanAccentColor()
    val accentGold = conceptGoldAccentColor()

    var selectedSessionIdx by remember(snapshot.deviceGroups) { mutableIntStateOf(0) }
    var refreshSpin by remember { mutableIntStateOf(0) }
    val safeIdx = selectedSessionIdx.coerceIn(0, (snapshot.deviceGroups.size - 1).coerceAtLeast(0))
    val activeSession = snapshot.deviceGroups.getOrNull(safeIdx)
    val activeSongs = activeSession?.songs ?: snapshot.queue

    val curId = currentLocalSong?.id
    val curHash = currentLocalSong?.hash.orEmpty()

    ConceptAdaptiveBottomSheetContainer(
        onDismiss = onDismiss,
        onDismissStart = onDismissStart,
        zIndex = 92f
    ) { _ ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sheetVerticalDragGesture(),
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
                        .size(40.dp)
                        .softClickable(enabled = !loading) {
                            refreshSpin += 1
                            onRefresh()
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        ConceptFluidRefreshIcon(
                            tint = ConceptPrimary,
                            isRefreshing = loading,
                            spinTrigger = refreshSpin,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = cardBg,
                    modifier = Modifier
                        .size(40.dp)
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
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
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
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
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
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
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
            when {
                loading && activeSongs.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ConceptPrimary, strokeWidth = 2.5.dp)
                    }
                }
                errorMessage != null && activeSongs.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WifiOff,
                            contentDescription = null,
                            tint = textSecondary.copy(alpha = 0.55f),
                            modifier = Modifier.size(38.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(errorMessage, color = textSecondary, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                refreshSpin += 1
                                onRefresh()
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("重新拉取", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                activeSongs.isEmpty() -> {
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
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        itemsIndexed(
                            items = activeSongs,
                            key = { idx, item -> "handoff_${idx}_${item.hash.ifBlank { item.id }}" }
                        ) { idx, item ->
                            val isPlayingNow = curId != null && (
                                curId == item.id || (curHash.isNotEmpty() && curHash.equals(item.hash, ignoreCase = true))
                                )
                            val itemCoverModel = rememberOptimizedAsyncImageModel(item.coverUrl, sizePx = 120)
                            Surface(
                                shape = RoundedCornerShape(15.dp),
                                color = if (isPlayingNow) ConceptPrimary.copy(alpha = 0.14f) else cardBg,
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
                                        .padding(start = 11.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${idx + 1}",
                                        color = if (isPlayingNow || idx == 0) ConceptPrimary else textSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.width(22.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(ConceptPrimary.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (itemCoverModel != null) {
                                            AsyncImage(
                                                model = itemCoverModel,
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
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Text(
                                                text = item.title,
                                                color = if (isPlayingNow) ConceptPrimary else textPrimary,
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (item.isHiRes) {
                                                BadgeTag(text = "Hi-Res", color = accentGold)
                                            } else if (item.isLossless) {
                                                BadgeTag(text = "SQ", color = accentCyan)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
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
                                    if (onAddToNext != null) {
                                        IconButton(
                                            onClick = { onAddToNext(item) },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.AddToQueue,
                                                contentDescription = "下一首播放",
                                                tint = textSecondary,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = if (isPlayingNow) Icons.Rounded.GraphicEq else Icons.Rounded.PlayCircleOutline,
                                            contentDescription = "播放",
                                            tint = ConceptPrimary.copy(alpha = 0.85f),
                                            modifier = Modifier.padding(end = 6.dp).size(22.dp)
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
