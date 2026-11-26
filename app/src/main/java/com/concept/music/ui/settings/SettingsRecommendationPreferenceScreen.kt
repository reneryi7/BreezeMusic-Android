package com.concept.music.ui.settings

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
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
import com.concept.music.data.kugou.AuthState
import com.concept.music.data.model.BlacklistEntry
import com.concept.music.data.recommendation.PreferenceIntensity
import com.concept.music.data.recommendation.RecommendationMode
import com.concept.music.data.recommendation.SongLangTag
import com.concept.music.data.recommendation.StylePrefTag
import com.concept.music.data.recommendation.TasteProfileSummary
import com.concept.music.data.vip.VipManager
import com.concept.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置中心「推荐偏好」统一详情内容（无边框设计，同时适配手机二级页与平板 Master-Detail 右侧详情栏）：
 * 1. 推荐模式切换（平衡 / 熟悉 / 尝鲜，与发现页猜你喜欢卡片实时双向联动）；
 * 2. 官方语种与内容强度（双向同步 song_lang 10 项权重，含官方规则防呆）；
 * 3. 偏好曲风标签（双向同步 style 15 项流派）；
 * 4. 微风长期听歌画像概览与「重置本地隐式权重」；
 * 5. 屏蔽名单管理（同步云端黑名单 KG-TID=474，支持歌曲/歌手一键恢复推荐）。
 */
@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.recommendationPreferenceDetailItems(
    prefState: com.concept.music.data.recommendation.RecommendationPreferenceUiState,
    tasteSummary: TasteProfileSummary,
    blacklistTab: Int,
    onBlacklistTabChange: (Int) -> Unit,
    blacklistExpanded: Boolean,
    onBlacklistExpandedChange: (Boolean) -> Unit,
    onRequestResetImplicitDialog: () -> Unit,
    onSyncCloudNow: () -> Unit,
    onSelectMode: (RecommendationMode) -> Unit,
    onChangeSongLangWeight: (SongLangTag, Int) -> Unit,
    onToggleStyleTag: (StylePrefTag) -> Unit,
    onRemoveBlacklist: (BlacklistEntry) -> Unit
) {
    val pref = prefState.preference

    // ==================== 1. 推荐模式切换 ====================
    item(key = "rec_pref_mode_card") {
        SettingsSectionCard(
            title = "推荐模式",
            icon = Icons.Rounded.Tune
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (prefState.isLoggedIn) "与发现页「猜你喜欢」及酷狗云端双向同步" else "当前离线本机生效，登录后自动同步云端",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp,
                    modifier = Modifier.weight(1f)
                )
                if (prefState.isLoggedIn) {
                    Spacer(modifier = Modifier.width(8.dp))
                    ConceptTonalActionButton(
                        text = if (prefState.isSyncing) "同步中..." else "同步云端",
                        icon = Icons.Rounded.CloudSync,
                        accentColor = ConceptMintAccent,
                        onClick = { if (!prefState.isSyncing) onSyncCloudNow() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RecommendationMode.entries.forEach { mode ->
                    val selected = pref.mode == mode
                    RecommendationModeOptionCard(
                        mode = mode,
                        selected = selected,
                        onClick = { onSelectMode(mode) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = pref.mode.shortDesc,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.8.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
        }
    }

    // ==================== 2. 官方语种与内容强度（song_lang） ====================
    item(key = "rec_pref_song_lang_card") {
        SettingsSectionCard(
            title = "语种与内容强度",
            icon = Icons.Rounded.Translate
        ) {
            Text(
                text = "语种偏好",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp
                )
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SongLangTag.languageTags.forEach { tag ->
                    PreferenceIntensityRow(
                        label = tag.label,
                        currentIntensity = pref.intensityFor(tag),
                        onSelectIntensity = { intensity -> onChangeSongLangWeight(tag, intensity.weight) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f))
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "版本与内容形态",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp
                )
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SongLangTag.contentTags.forEach { tag ->
                    PreferenceIntensityRow(
                        label = tag.label,
                        currentIntensity = pref.intensityFor(tag),
                        onSelectIntensity = { intensity -> onChangeSongLangWeight(tag, intensity.weight) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "屏蔽项（0）在所有模式及今日日推中均严格拦截；减少（25）的语种仅在「尝鲜」模式保留极少破圈试探，连续跳过将自动收紧。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                fontSize = 11.2.sp,
                lineHeight = 16.sp
            )
        }
    }

    // ==================== 3. 官方偏好曲风（style） ====================
    item(key = "rec_pref_style_card") {
        SettingsSectionCard(
            title = "偏好曲风标签",
            icon = Icons.Rounded.LibraryMusic
        ) {
            Text(
                text = "选择常听曲风同步至官方画像，同时增强猜你喜欢与尝鲜模式同语种跨流派精排",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp,
                lineHeight = 16.5.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StylePrefTag.entries.forEach { tag ->
                    val enabled = tag.id in pref.enabledStyleIds
                    ConceptBorderlessChoicePill(
                        selected = enabled,
                        label = tag.label,
                        accentColor = ConceptVioletAccent,
                        onClick = { onToggleStyleTag(tag) }
                    )
                }
            }
        }
    }

    // ==================== 4. 微风本地长期听歌画像 ====================
    item(key = "rec_pref_local_profile_card") {
        SettingsSectionCard(
            title = "微风长期听歌画像",
            icon = Icons.Rounded.AutoGraph
        ) {
            Text(
                text = "融合全站红心与有效收听（45天半衰期衰减），结合猜你喜欢内完播与秒切动态演进",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp,
                lineHeight = 16.5.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 语种自然分布
            if (tasteSummary.languageShares.isNotEmpty()) {
                Text(
                    text = "长期主力语种分布",
                    fontSize = 12.2.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    tasteSummary.languageShares.forEach { (langLabel, ratio) ->
                        val pct = (ratio * 100f).toInt().coerceIn(1, 100)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = langLabel,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(52.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(7.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(ratio.coerceIn(0.05f, 1f))
                                        .clip(CircleShape)
                                        .background(
                                            Brush.horizontalGradient(listOf(ConceptMintAccent, ConceptPrimary))
                                        )
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "$pct%",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = ConceptPrimary,
                                modifier = Modifier.width(34.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // 常听核心歌手 Top 5
            if (tasteSummary.topArtists.isNotEmpty()) {
                Text(
                    text = "核心偏爱歌手",
                    fontSize = 12.2.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    tasteSummary.topArtists.forEach { artist ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ConceptPrimary.copy(alpha = 0.11f)
                        ) {
                            Text(
                                text = artist,
                                color = ConceptPrimary,
                                fontSize = 11.8.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // 时段微调与尝鲜探针收敛状态
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (tasteSummary.timeSlotAdjustmentActive) ConceptMintAccent else ConceptAmberAccent)
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = tasteSummary.activeTimeSlotHint,
                            fontSize = 11.8.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (tasteSummary.tightenedProbeLanguages.isNotEmpty()) {
                        Text(
                            text = "尝鲜探针收敛：${tasteSummary.tightenedProbeLanguages.joinToString("、")}已根据你的跳过习惯自动收紧",
                            fontSize = 11.3.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "已累计正向信号 ${tasteSummary.trackedPositiveCount} 次 · 猜你喜欢秒切降权 ${tasteSummary.quickSkipCount} 次",
                        fontSize = 11.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                ConceptTonalActionButton(
                    text = "重置本地隐式权重",
                    icon = Icons.Rounded.RestartAlt,
                    accentColor = ConceptCoralAccent,
                    onClick = onRequestResetImplicitDialog
                )
            }
        }
    }

    // ==================== 5. 屏蔽名单管理（歌曲 / 歌手） ====================
    item(key = "rec_pref_blacklist_card") {
        SettingsSectionCard(
            title = "屏蔽名单管理",
            icon = Icons.Rounded.Block
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ConceptBorderlessChoicePill(
                    selected = blacklistTab == 0,
                    label = "屏蔽歌曲 (${prefState.blacklistedSongs.size})",
                    accentColor = ConceptCoralAccent,
                    onClick = {
                        onBlacklistTabChange(0)
                        onBlacklistExpandedChange(false)
                    }
                )
                ConceptBorderlessChoicePill(
                    selected = blacklistTab == 1,
                    label = "屏蔽歌手 (${prefState.blacklistedSingers.size})",
                    accentColor = ConceptCoralAccent,
                    onClick = {
                        onBlacklistTabChange(1)
                        onBlacklistExpandedChange(false)
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val activeList = if (blacklistTab == 0) prefState.blacklistedSongs else prefState.blacklistedSingers
            if (activeList.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (blacklistTab == 0) "暂无已屏蔽的推荐歌曲" else "暂无已屏蔽的推荐歌手",
                        fontSize = 12.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)
                    )
                }
            } else {
                val visibleList = if (blacklistExpanded) activeList else activeList.take(6)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    visibleList.forEach { entry ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (entry.label == "singer") Icons.Rounded.PersonOff else Icons.Rounded.MusicOff,
                                        contentDescription = null,
                                        tint = ConceptCoralAccent.copy(alpha = 0.85f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(9.dp))
                                    Text(
                                        text = entry.name.ifBlank { entry.key },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                ConceptTonalActionButton(
                                    text = "恢复推荐",
                                    accentColor = ConceptPrimary,
                                    onClick = { onRemoveBlacklist(entry) }
                                )
                            }
                        }
                    }
                }
                if (activeList.size > 6) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        ConceptTonalActionButton(
                            text = if (blacklistExpanded) "收起列表" else "展开全部 ${activeList.size} 项",
                            accentColor = ConceptPrimary,
                            onClick = { onBlacklistExpandedChange(!blacklistExpanded) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationModeOptionCard(
    mode: RecommendationMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val accent = when (mode) {
        RecommendationMode.FAMILIAR -> if (isDark) Color(0xFFF5B041) else Color(0xFFC97A00)
        RecommendationMode.DISCOVERY -> if (isDark) Color(0xFF9B7BFF) else Color(0xFF6848E8)
        RecommendationMode.BALANCED -> if (isDark) Color(0xFF26DEBF) else ConceptMintAccent
    }
    val bg by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = if (isDark) 0.20f else 0.14f)
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
        animationSpec = tween(200),
        label = "recModeCardBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(200),
        label = "recModeCardText"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bg,
        modifier = modifier.softCardClickable(
            shape = RoundedCornerShape(16.dp),
            pressScale = 0.95f,
            onClick = onClick
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(if (selected) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (selected) accent else accent.copy(alpha = 0.45f))
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = mode.label,
                color = textColor,
                fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = when (mode) {
                    RecommendationMode.BALANCED -> "均衡漫游"
                    RecommendationMode.FAMILIAR -> "重温偏爱"
                    RecommendationMode.DISCOVERY -> "跨界探索"
                },
                color = if (selected) accent.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.5.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PreferenceIntensityRow(
    label: String,
    currentIntensity: PreferenceIntensity,
    onSelectIntensity: (PreferenceIntensity) -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 13.2.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (currentIntensity == PreferenceIntensity.BLOCKED) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            modifier = Modifier.widthIn(min = 66.dp)
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.08f else 0.055f)
        ) {
            Row(
                modifier = Modifier.padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PreferenceIntensity.entries.forEach { tier ->
                    val selected = currentIntensity == tier
                    val tierAccent = when (tier) {
                        PreferenceIntensity.BLOCKED -> ConceptCoralAccent
                        PreferenceIntensity.REDUCED -> ConceptAmberAccent
                        PreferenceIntensity.DEFAULT -> ConceptPrimary
                        PreferenceIntensity.BOOSTED -> ConceptMintAccent
                    }
                    val pillBg by animateColorAsState(
                        targetValue = if (selected) tierAccent.copy(alpha = if (isDark) 0.24f else 0.18f) else Color.Transparent,
                        animationSpec = tween(160),
                        label = "tierBg_${label}_${tier.name}"
                    )
                    val pillFg by animateColorAsState(
                        targetValue = if (selected) tierAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(160),
                        label = "tierFg_${label}_${tier.name}"
                    )
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = pillBg,
                        modifier = Modifier.softClickable(
                            pressScale = 0.94f,
                            onClick = { onSelectIntensity(tier) }
                        )
                    ) {
                        Text(
                            text = tier.label,
                            color = pillFg,
                            fontSize = 11.2.sp,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 记住并响应式刷新设置页所需的「推荐偏好 + 微风长期画像」状态控制器
 */
@Composable
internal fun rememberRecommendationPreferenceController(): RecommendationPreferenceController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountState by VipManager.accounts.state.collectAsState()
    val prefState by VipManager.recommendationPrefs.uiState.collectAsState()
    val profileVersion by VipManager.tasteProfile.profileVersion.collectAsState()
    val statsVersion by VipManager.listenStats.statsVersion.collectAsState()

    val uid = accountState.currentId
    val isLoggedIn = accountState.auth == AuthState.READY

    LaunchedEffect(uid, isLoggedIn) {
        withContext(Dispatchers.IO) {
            VipManager.recommendationPrefs.activateUser(uid, isLoggedIn = isLoggedIn)
        }
    }

    val summary by produceState(
        initialValue = TasteProfileSummary(),
        keys = arrayOf(uid, profileVersion, statsVersion, prefState.preference)
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val liked = VipManager.discoverCache.getLikedSongs(uid)
                val recent = VipManager.history.get(uid)
                VipManager.tasteProfile.buildScoringSnapshot(
                    userId = uid,
                    listenStats = VipManager.listenStats,
                    likedSongs = liked,
                    recentHistory = recent,
                    tagCache = VipManager.songTagCache,
                    preference = prefState.preference
                ).summary
            }.getOrDefault(TasteProfileSummary())
        }
    }

    return remember(prefState, summary, uid, isLoggedIn) {
        RecommendationPreferenceController(
            prefState = prefState,
            tasteSummary = summary,
            onSyncCloudNow = {
                if (!isLoggedIn) {
                    Toast.makeText(context, "请先登录酷狗账号", Toast.LENGTH_SHORT).show()
                } else {
                    scope.launch(Dispatchers.IO) {
                        val (ok, _) = VipManager.recommendationPrefs.syncFromCloud(uid, VipManager.music)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                if (ok) "已同步酷狗官方偏好与屏蔽名单" else "同步失败，请稍后重试",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            },
            onSelectMode = { mode ->
                scope.launch(Dispatchers.IO) {
                    VipManager.recommendationPrefs.updateMode(
                        userId = uid,
                        mode = mode,
                        musicRepo = VipManager.music,
                        isLoggedIn = isLoggedIn
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            if (isLoggedIn) "已切换至「${mode.label}」模式并同步云端" else "已切换至「${mode.label}」模式",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onChangeSongLangWeight = { tag, weight ->
                scope.launch(Dispatchers.IO) {
                    val res = VipManager.recommendationPrefs.updateSongLangWeight(
                        userId = uid,
                        tag = tag,
                        weight = weight,
                        musicRepo = VipManager.music,
                        isLoggedIn = isLoggedIn
                    )
                    if (!res.valid) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onToggleStyleTag = { styleTag ->
                scope.launch(Dispatchers.IO) {
                    VipManager.recommendationPrefs.toggleStyleTag(
                        userId = uid,
                        styleTag = styleTag,
                        musicRepo = VipManager.music,
                        isLoggedIn = isLoggedIn
                    )
                }
            },
            onRemoveBlacklist = { entry ->
                scope.launch(Dispatchers.IO) {
                    val ok = VipManager.recommendationPrefs.removeBlacklistEntry(
                        userId = uid,
                        entry = entry,
                        musicRepo = VipManager.music,
                        isLoggedIn = isLoggedIn
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            if (ok) "已恢复推荐「${entry.name}」" else "恢复失败，请稍后重试",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onResetImplicitWeights = {
                scope.launch(Dispatchers.IO) {
                    VipManager.tasteProfile.resetImplicitWeights(uid)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "已重置微风本地隐式降权与探针收敛", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

internal data class RecommendationPreferenceController(
    val prefState: com.concept.music.data.recommendation.RecommendationPreferenceUiState,
    val tasteSummary: TasteProfileSummary,
    val onSyncCloudNow: () -> Unit,
    val onSelectMode: (RecommendationMode) -> Unit,
    val onChangeSongLangWeight: (SongLangTag, Int) -> Unit,
    val onToggleStyleTag: (StylePrefTag) -> Unit,
    val onRemoveBlacklist: (BlacklistEntry) -> Unit,
    val onResetImplicitWeights: () -> Unit
)
