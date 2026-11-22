package com.concept.music.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.concept.music.data.kugou.fetchRelatedSongVersions
import com.concept.music.data.model.Song
import com.concept.music.data.vip.VipManager
import com.concept.music.ui.theme.ConceptAnimatedOverlaySheet
import com.concept.music.ui.theme.ConceptPrimary
import com.concept.music.ui.theme.conceptElevatedSurfaceColor
import com.concept.music.ui.theme.conceptSheetContainerColor
import com.concept.music.ui.theme.rememberCoverColorRoles
import com.concept.music.ui.theme.softCardClickable
import com.concept.music.ui.theme.softClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 歌曲「更多版本」轻量切换抽屉（Live / 翻唱 / DJ / 伴奏 / 混音等）：
 * 采用全应用统一的 ConceptAnimatedOverlaySheet 无框色阶容器风格，支持全部/热门/最新切换。
 */
@Composable
fun SongVersionsBottomSheet(
    song: Song,
    accentColor: Color = ConceptPrimary,
    onPlayVersion: (Song, List<Song>) -> Unit,
    onAddToNext: (Song) -> Unit,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val sheetBg = conceptSheetContainerColor()
    val cardBg = conceptElevatedSurfaceColor()
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant
    val roles = rememberCoverColorRoles(accentColor)

    val sortOptions = remember {
        listOf("all" to "全部版本", "hot" to "热门版本", "new" to "最新发布")
    }
    var selectedSort by remember(song.id) { mutableStateOf("all") }
    var versions by remember(song.id) { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember(song.id) { mutableStateOf(true) }
    var errorMsg by remember(song.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(song.id, song.hash, selectedSort) {
        loading = true
        errorMsg = null
        val list = withContext(Dispatchers.IO) {
            runCatching {
                VipManager.music.fetchRelatedSongVersions(song = song, sort = selectedSort, page = 1, pageSize = 30)
            }.getOrElse {
                errorMsg = it.message ?: "获取关联版本失败"
                emptyList()
            }
        }
        versions = list
        loading = false
    }

    val panelShape = if (isLandscape) {
        RoundedCornerShape(26.dp)
    } else {
        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    }

    ConceptAnimatedOverlaySheet(
        onDismiss = onDismiss,
        isLandscape = isLandscape,
        zIndex = 92f
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
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                            .widthIn(max = 580.dp)
                            .fillMaxWidth()
                            .fillMaxHeight(0.86f)
                    } else {
                        Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .heightIn(min = 320.dp, max = 560.dp)
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
                                text = "更多版本",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = textPrimary
                            )
                            Text(
                                text = "${song.title} · ${song.artist}",
                                fontSize = 12.sp,
                                color = textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = cardBg,
                            modifier = Modifier
                                .size(32.dp)
                                .softClickable(onClick = requestDismiss)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "关闭",
                                    tint = textSecondary,
                                    modifier = Modifier.size(17.dp)
                                )
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
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
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
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.LibraryMusic,
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
                                        val isSameCurrent = item.hash.equals(song.hash, ignoreCase = true) && song.hash.isNotBlank()
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = if (isSameCurrent) roles.primaryContainer.copy(alpha = 0.45f) else cardBg,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .softCardClickable(shape = RoundedCornerShape(16.dp)) {
                                                    onPlayVersion(item, versions)
                                                    requestDismiss()
                                                }
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
                                                        .clip(RoundedCornerShape(11.dp))
                                                        .background(roles.secondaryContainer),
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
                                                            tint = roles.onSecondaryContainer,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Text(
                                                            text = item.title,
                                                            color = if (isSameCurrent) roles.primary else textPrimary,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f, fill = false)
                                                        )
                                                        val badgeText = item.versionTag.ifBlank { if (isSameCurrent) "当前" else "" }
                                                        if (badgeText.isNotBlank()) {
                                                            Surface(
                                                                shape = RoundedCornerShape(6.dp),
                                                                color = roles.secondaryContainer
                                                            ) {
                                                                Text(
                                                                    text = badgeText,
                                                                    color = roles.onSecondaryContainer,
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = buildString {
                                                            append(item.artist.ifBlank { "未知歌手" })
                                                            if (item.album.isNotBlank()) append(" · ${item.album}")
                                                        },
                                                        color = textSecondary,
                                                        fontSize = 11.5.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                IconButton(
                                                    onClick = { onAddToNext(item) },
                                                    modifier = Modifier.size(34.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.AddToQueue,
                                                        contentDescription = "下一首播放",
                                                        tint = textSecondary,
                                                        modifier = Modifier.size(19.dp)
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
    }
}
