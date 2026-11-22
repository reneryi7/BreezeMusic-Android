package com.concept.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.concept.music.data.kugou.fetchRelatedSongVersions
import com.concept.music.data.kugou.suspendRunCatching
import com.concept.music.data.model.Song
import com.concept.music.data.vip.VipManager
import com.concept.music.ui.theme.ConceptPrimary
import com.concept.music.ui.theme.conceptCyanAccentColor
import com.concept.music.ui.theme.conceptElevatedSurfaceColor
import com.concept.music.ui.theme.conceptGoldAccentColor
import com.concept.music.ui.theme.rememberCoverColorRoles
import com.concept.music.ui.theme.softCardClickable
import com.concept.music.ui.theme.softClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private fun formatVersionDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSec = durationMs / 1000L
    return String.format(Locale.getDefault(), "%d:%02d", totalSec / 60L, totalSec % 60L)
}

/**
 * 歌曲「更多版本」自适应切换抽屉（Live / 翻唱 / DJ / 伴奏 / 混音等）：
 * - 顶部提供「当前版本基准卡」（含音质徽标与时长），方便横向对比音质与版本长短；
 * - 列表项完整呈现 Hi-Res / SQ / HQ / VIP 徽标与歌曲时长，缩略图接入 rememberOptimizedAsyncImageModel；
 * - 选中某一版本播放时仅在原播放队列替换/切播而不清空用户原有歌单队列，并完整走完收起动效；
 * - 错误态提供一键「重试」，全交互按钮满足 >= 40dp 触控热区。
 */
@Composable
fun SongVersionsBottomSheet(
    song: Song,
    accentColor: Color = ConceptPrimary,
    onPlayVersion: (Song, List<Song>) -> Unit,
    onAddToNext: (Song) -> Unit,
    onDismissStart: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val cardBg = conceptElevatedSurfaceColor()
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant
    val roles = rememberCoverColorRoles(accentColor)
    val accentCyan = conceptCyanAccentColor()
    val accentGold = conceptGoldAccentColor()

    val sortOptions = remember {
        listOf("all" to "全部版本", "hot" to "热门版本", "new" to "最新发布")
    }
    var selectedSort by remember(song.id) { mutableStateOf("all") }
    var retryTick by remember(song.id) { mutableIntStateOf(0) }
    var versions by remember(song.id) { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember(song.id) { mutableStateOf(true) }
    var errorMsg by remember(song.id) { mutableStateOf<String?>(null) }
    var pendingAfterDismiss by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(song.id, song.hash, selectedSort, retryTick) {
        loading = true
        errorMsg = null
        val list = withContext(Dispatchers.IO) {
            suspendRunCatching {
                VipManager.music.fetchRelatedSongVersions(song = song, sort = selectedSort, page = 1, pageSize = 30)
            }.getOrElse {
                errorMsg = it.message ?: "获取关联版本失败，请检查网络"
                emptyList()
            }
        }
        versions = list
        loading = false
    }

    ConceptAdaptiveBottomSheetContainer(
        onDismiss = {
            onDismiss()
            pendingAfterDismiss?.invoke()
            pendingAfterDismiss = null
        },
        onDismissStart = onDismissStart,
        zIndex = 92f
    ) { _ ->
        val dismissWithAction: (() -> Unit) -> Unit = { action ->
            pendingAfterDismiss = action
            requestDismiss()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sheetVerticalDragGesture(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "更多版本",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = textPrimary
                )
                Text(
                    text = "切换版本将保留当前歌单播放队列",
                    fontSize = 11.5.sp,
                    color = textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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

        Spacer(modifier = Modifier.height(10.dp))

        // 当前基准版本对比卡
        Surface(
            shape = RoundedCornerShape(15.dp),
            color = roles.primaryContainer.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth()
        ) {
            val baseCover = rememberOptimizedAsyncImageModel(song.coverUrl, sizePx = 112)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(roles.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (baseCover != null) {
                        AsyncImage(
                            model = baseCover,
                            contentDescription = song.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = roles.primary, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Surface(shape = RoundedCornerShape(6.dp), color = roles.primary) {
                            Text(
                                text = "当前版本",
                                color = roles.onPrimary,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                        Text(
                            text = song.title,
                            color = roles.onPrimaryContainer,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (song.isHiRes) {
                            BadgeTag(text = "Hi-Res", color = accentGold)
                        } else if (song.isLossless) {
                            BadgeTag(text = "SQ", color = accentCyan)
                        } else if (song.hash320.isNotBlank()) {
                            BadgeTag(text = "HQ", color = ConceptPrimary)
                        }
                        val durStr = formatVersionDuration(song.durationMs)
                        Text(
                            text = buildString {
                                append(song.artist.ifBlank { "未知歌手" })
                                if (durStr.isNotBlank()) append(" · $durStr")
                            },
                            color = roles.onPrimaryContainerSubtle,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sortOptions.forEach { (key, label) ->
                val selected = selectedSort == key
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) roles.primaryContainer else cardBg,
                    modifier = Modifier.softCardClickable(shape = RoundedCornerShape(12.dp)) {
                        selectedSort = key
                    }
                ) {
                    Text(
                        text = label,
                        color = if (selected) roles.onPrimaryContainer else textSecondary,
                        fontSize = 12.5.sp,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = roles.primary,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                versions.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (errorMsg != null) Icons.Rounded.WifiOff else Icons.Rounded.LibraryMusic,
                            contentDescription = null,
                            tint = textSecondary.copy(alpha = 0.55f),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMsg ?: "暂未找到该曲目的其他版本",
                            color = textSecondary,
                            fontSize = 13.sp
                        )
                        if (errorMsg != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { retryTick += 1 },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = roles.primary, contentColor = roles.onPrimary)
                            ) {
                                Text("重新加载", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        itemsIndexed(
                            items = versions,
                            key = { idx, item -> "ver_${idx}_${item.hash.ifBlank { item.id }}" }
                        ) { _, item ->
                            val itemCover = rememberOptimizedAsyncImageModel(item.coverUrl, sizePx = 128)
                            val durStr = formatVersionDuration(item.durationMs)
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = cardBg,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .softCardClickable(shape = RoundedCornerShape(16.dp)) {
                                        dismissWithAction {
                                            onPlayVersion(item, versions)
                                        }
                                    }
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
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(roles.secondaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (itemCover != null) {
                                            AsyncImage(
                                                model = itemCover,
                                                contentDescription = item.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.MusicNote,
                                                contentDescription = null,
                                                tint = roles.onSecondaryContainer,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Text(
                                                text = item.title,
                                                color = textPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (item.versionTag.isNotBlank()) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = roles.secondaryContainer
                                                ) {
                                                    Text(
                                                        text = item.versionTag,
                                                        color = roles.onSecondaryContainer,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            if (item.isHiRes) {
                                                BadgeTag(text = "Hi-Res", color = accentGold)
                                            } else if (item.isLossless) {
                                                BadgeTag(text = "SQ", color = accentCyan)
                                            } else if (item.hash320.isNotBlank()) {
                                                BadgeTag(text = "HQ", color = ConceptPrimary)
                                            }
                                            if (item.isVip) {
                                                BadgeTag(text = "VIP", color = accentGold)
                                            }
                                            Text(
                                                text = buildString {
                                                    append(item.artist.ifBlank { "未知歌手" })
                                                    if (durStr.isNotBlank()) append(" · $durStr")
                                                    if (item.album.isNotBlank()) append(" · ${item.album}")
                                                },
                                                color = textSecondary,
                                                fontSize = 11.5.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { onAddToNext(item) },
                                        modifier = Modifier.size(42.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.AddToQueue,
                                            contentDescription = "下一首播放",
                                            tint = textSecondary,
                                            modifier = Modifier.size(20.dp)
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
