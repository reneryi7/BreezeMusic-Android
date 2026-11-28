package com.concept.music.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.concept.music.data.model.Song
import com.concept.music.ui.theme.AbyssalBlueGlowPrimary
import com.concept.music.ui.theme.AbyssalBlueGlowSecondary
import com.concept.music.ui.theme.ConceptCardDark
import com.concept.music.ui.theme.ConceptCoralAccent
import com.concept.music.ui.theme.ConceptCyanAccent
import com.concept.music.ui.theme.ConceptElevatedCardDark
import com.concept.music.ui.theme.ConceptElevatedCardLight
import com.concept.music.ui.theme.ConceptPrimary
import com.concept.music.ui.theme.ConceptPrimaryLight
import com.concept.music.ui.theme.ConceptRoseAccent
import com.concept.music.ui.theme.ConceptVioletAccent
import com.concept.music.ui.theme.TextPrimaryDark
import com.concept.music.ui.theme.TextPrimaryLight
import com.concept.music.ui.theme.TextSecondaryDark
import com.concept.music.ui.theme.TextSecondaryLight
import com.concept.music.ui.theme.VipGold
import com.concept.music.ui.theme.WatercolorBlueWash
import com.concept.music.ui.theme.WatercolorCyanWash
import com.concept.music.ui.theme.WatercolorLavenderAccent
import com.concept.music.ui.theme.WatercolorPeachAccent
import com.concept.music.ui.theme.readableOnDarkSurface
import com.concept.music.ui.theme.toCoverColorRoles
import com.concept.music.ui.theme.toTonalPalette
import com.concept.music.ui.viewmodel.PlayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 微风音乐桌面小组件三大标志性视觉风格（100% 源自 App 核心界面设计 DNA）
 */
enum class BreezeWidgetStyle(
    val label: String,
    val shortLabel: String,
    val subtitle: String
) {
    VINYL_SLEEVE(
        label = "黑胶封套",
        shortLabel = "黑胶封套",
        subtitle = "猜你喜欢同源 · 方形大圆角封套与半露黑胶唱盘"
    ),
    AURORA_COVER(
        label = "流光封面",
        shortLabel = "流光封面",
        subtitle = "全屏播放页同源 · 三色柔光底与旋转菱形光晕框"
    ),
    WATERCOLOR_STACK(
        label = "水彩叠层",
        shortLabel = "水彩叠层",
        subtitle = "每日推荐同源 · 队列错位叠卡与柔光水彩洗色"
    );

    companion object {
        fun fromPersistedName(raw: String?): BreezeWidgetStyle {
            if (raw.isNullOrBlank()) return VINYL_SLEEVE
            return runCatching { valueOf(raw.trim()) }.getOrDefault(VINYL_SLEEVE)
        }
    }
}

/**
 * 桌面小组件明暗主题策略
 */
enum class BreezeWidgetThemeMode(
    val label: String,
    val subtitle: String
) {
    FOLLOW_APP("跟随应用", "随微风主题在幽邃微蓝与水彩浸润间自动切换"),
    DARK_ABYSS("幽邃微蓝", "深海暗夜基底，透出克制微蓝光感"),
    LIGHT_WATERCOLOR("水彩浸润", "纯白圆润悬浮块配淡彩水彩柔晕");

    companion object {
        fun fromPersistedName(raw: String?): BreezeWidgetThemeMode {
            if (raw.isNullOrBlank()) return FOLLOW_APP
            return runCatching { valueOf(raw.trim()) }.getOrDefault(FOLLOW_APP)
        }
    }
}

/**
 * 四大桌面小组件尺寸形态
 */
enum class BreezeWidgetSizeClass(
    val label: String,
    val gridSpec: String,
    val subtitle: String,
    val previewAspectRatio: Float
) {
    CARD_4X2(
        label = "经典黑胶卡",
        gridSpec = "4×2",
        subtitle = "封面舞台 · 歌名歌词 · 红心与完整播控",
        previewAspectRatio = 2.08f
    ),
    CAPSULE_4X1(
        label = "灵动胶囊条",
        gridSpec = "4×1",
        subtitle = "迷你播放条同源 · 单行轻盈不占桌面",
        previewAspectRatio = 4.35f
    ),
    SQUARE_2X2(
        label = "精致方卡",
        gridSpec = "2×2",
        subtitle = "方形大舞台 + 饱满主色大播放按钮",
        previewAspectRatio = 1.0f
    ),
    STAGE_4X4(
        label = "沉浸歌词台",
        gridSpec = "4×4",
        subtitle = "大屏与平板专属 · 三行居中歌词 + 完整五键控台",
        previewAspectRatio = 0.98f
    )
}

/**
 * 桌面小组件全局外观与显示配置
 */
@Immutable
data class BreezeWidgetConfig(
    val style: BreezeWidgetStyle = BreezeWidgetStyle.VINYL_SLEEVE,
    val themeMode: BreezeWidgetThemeMode = BreezeWidgetThemeMode.FOLLOW_APP,
    val dynamicCoverColor: Boolean = true,
    val backgroundAlpha: Float = 0.94f,
    val showLyric: Boolean = true,
    val showQualityBadge: Boolean = true,
    val showProgressBar: Boolean = true
) {
    val sanitizedBackgroundAlpha: Float
        get() = backgroundAlpha.coerceIn(MIN_BG_ALPHA, MAX_BG_ALPHA)

    companion object {
        const val MIN_BG_ALPHA = 0.35f
        const val MAX_BG_ALPHA = 1.0f
    }
}

/**
 * 桌面小组件渲染所需的实时播放快照
 */
@Immutable
data class BreezeWidgetPlaybackSnapshot(
    val songId: String = "",
    val title: String = DEFAULT_IDLE_TITLE,
    val artist: String = DEFAULT_IDLE_SUBTITLE,
    val album: String = "",
    val coverUrl: String = "",
    val nextCoverUrl1: String = "",
    val nextCoverUrl2: String = "",
    val qualityBadge: String = "",
    val isPlaying: Boolean = false,
    val isFavorite: Boolean = false,
    val playMode: PlayMode = PlayMode.REPEAT_ALL,
    val prevLyric: String = "",
    val currentLyric: String = "",
    val nextLyric: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isLocalSong: Boolean = false,
    val localUri: String = ""
) {
    val hasActiveSong: Boolean
        get() = songId.isNotBlank() ||
            (title.isNotBlank() && title != DEFAULT_IDLE_TITLE)

    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    companion object {
        const val DEFAULT_IDLE_TITLE = "微风音乐"
        const val DEFAULT_IDLE_SUBTITLE = "耳畔微风起 · 轻点开启无损听歌"
        const val DEFAULT_IDLE_LYRIC = "轻触封面进入全屏沉浸播放"

        fun fromSong(
            song: Song?,
            nextSong1: Song? = null,
            nextSong2: Song? = null,
            isPlaying: Boolean = false,
            isFavorite: Boolean = false,
            playMode: PlayMode = PlayMode.REPEAT_ALL,
            prevLyric: String = "",
            currentLyric: String = "",
            nextLyric: String = "",
            positionMs: Long = 0L,
            durationMs: Long = song?.durationMs ?: 0L
        ): BreezeWidgetPlaybackSnapshot {
            if (song == null) {
                return BreezeWidgetPlaybackSnapshot(
                    isPlaying = false,
                    playMode = playMode
                )
            }
            val badge = resolveSongQualityBadge(song)
            return BreezeWidgetPlaybackSnapshot(
                songId = song.id,
                title = song.title.ifBlank { DEFAULT_IDLE_TITLE },
                artist = song.artist.ifBlank { "未知歌手" },
                album = song.album,
                coverUrl = song.coverUrl,
                nextCoverUrl1 = nextSong1?.coverUrl.orEmpty(),
                nextCoverUrl2 = nextSong2?.coverUrl.orEmpty(),
                qualityBadge = badge,
                isPlaying = isPlaying,
                isFavorite = isFavorite,
                playMode = playMode,
                prevLyric = prevLyric,
                currentLyric = currentLyric,
                nextLyric = nextLyric,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                isLocalSong = song.isLocalSong,
                localUri = song.localUri
            )
        }

        fun resolveSongQualityBadge(song: Song): String = when {
            song.isLocalSong -> song.localQualityBadgeLabel
            song.isHiRes || song.actualPlayQuality?.shortBadge == "Hi-Res" -> "Hi-Res"
            song.isLossless || song.actualPlayQuality?.shortBadge == "SQ" -> "SQ"
            song.quality == "320" || song.actualPlayQuality?.shortBadge == "320K" -> "HQ"
            else -> ""
        }
    }
}

/**
 * 经 WCAG 对比度与 CIE L* 色阶对齐后的桌面小组件完整渲染色盘
 */
@Immutable
data class BreezeWidgetPalette(
    val isDarkSurface: Boolean,
    val cardBase: Color,
    val cardGradientEnd: Color,
    val glowPrimary: Color,
    val glowSecondary: Color,
    val glowTertiary: Color,
    val hairlineHighlight: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val lyricActiveText: Color,
    val lyricDimText: Color,
    val accentPrimary: Color,
    val accentSecondary: Color,
    val playButtonStart: Color,
    val playButtonEnd: Color,
    val playButtonIcon: Color,
    val controlIconTint: Color,
    val controlButtonBg: Color,
    val badgeBg: Color,
    val badgeText: Color,
    val favoriteActive: Color,
    val progressTrack: Color,
    val progressActiveStart: Color,
    val progressActiveEnd: Color
) {
    val cardBaseArgb: Int get() = cardBase.toArgb()
    val primaryTextArgb: Int get() = primaryText.toArgb()
    val secondaryTextArgb: Int get() = secondaryText.toArgb()
    val lyricActiveTextArgb: Int get() = lyricActiveText.toArgb()
    val lyricDimTextArgb: Int get() = lyricDimText.toArgb()
    val badgeTextArgb: Int get() = badgeText.toArgb()
}

/**
 * 结合当前主题、风格与 24-bin 封面取色生成全套 WCAG AA 可读色盘。
 * 纯函数实现，零 Android UI 依赖，可供渲染引擎、设置预览与 JVM 单元测试共用。
 */
fun resolveBreezeWidgetPalette(
    config: BreezeWidgetConfig,
    isAppDark: Boolean,
    coverSeedColor: Color? = null,
    coverSecondaryColor: Color? = null,
    coverTertiaryColor: Color? = null
): BreezeWidgetPalette {
    val resolvedThemeDark = when (config.themeMode) {
        BreezeWidgetThemeMode.FOLLOW_APP -> isAppDark
        BreezeWidgetThemeMode.DARK_ABYSS -> true
        BreezeWidgetThemeMode.LIGHT_WATERCOLOR -> false
    }
    // 「流光封面」风格在深邃封面柔光舞台下恒保证深色前景高对比（同时允许浅色主题下采用清透高明度柔雾底）
    val isDarkSurface = when (config.style) {
        BreezeWidgetStyle.AURORA_COVER -> resolvedThemeDark || config.themeMode != BreezeWidgetThemeMode.LIGHT_WATERCOLOR
        else -> resolvedThemeDark
    }

    val useCoverSeed = config.dynamicCoverColor && coverSeedColor != null
    val effectiveSeed = if (useCoverSeed) {
        coverSeedColor!!
    } else if (config.style == BreezeWidgetStyle.WATERCOLOR_STACK && !isDarkSurface) {
        ConceptCoralAccent
    } else {
        ConceptPrimary
    }
    val effectiveSecondary = if (useCoverSeed && coverSecondaryColor != null) {
        coverSecondaryColor
    } else {
        ConceptCyanAccent
    }
    val effectiveTertiary = if (useCoverSeed && coverTertiaryColor != null) {
        coverTertiaryColor
    } else {
        ConceptVioletAccent
    }

    val baseSurface = if (isDarkSurface) ConceptCardDark else Color(0xFFF7FAFF)
    val roles = effectiveSeed.toCoverColorRoles(isDark = isDarkSurface, baseSurface = baseSurface)
    val secondaryPalette = effectiveSecondary.toTonalPalette()

    return if (isDarkSurface) {
        val cardBase = when (config.style) {
            BreezeWidgetStyle.AURORA_COVER -> Color(0xFF0E1524)
            BreezeWidgetStyle.WATERCOLOR_STACK -> lerp(ConceptCardDark, roles.palette.tone20, if (useCoverSeed) 0.28f else 0.14f)
            BreezeWidgetStyle.VINYL_SLEEVE -> ConceptCardDark
        }
        val cardEnd = when (config.style) {
            BreezeWidgetStyle.AURORA_COVER -> Color(0xFF131D30)
            BreezeWidgetStyle.WATERCOLOR_STACK -> lerp(ConceptElevatedCardDark, secondaryPalette.tone20, if (useCoverSeed) 0.22f else 0.10f)
            BreezeWidgetStyle.VINYL_SLEEVE -> Color(0xFF101829)
        }
        val playStart = if (useCoverSeed) roles.palette.tone70 else ConceptPrimary
        val playEnd = if (useCoverSeed) {
            secondaryPalette.tone70.readableOnDarkSurface(0.38f)
        } else {
            ConceptCyanAccent
        }
        val playIcon = if (playStart.toTonalPalette().tone(20) == Color.Black) Color(0xFF071321) else roles.onPrimary
        val badgeTextColor = if (useCoverSeed) roles.palette.tone90 else Color(0xFF9CD0FF)

        BreezeWidgetPalette(
            isDarkSurface = true,
            cardBase = cardBase,
            cardGradientEnd = cardEnd,
            glowPrimary = if (useCoverSeed) roles.palette.tone50 else AbyssalBlueGlowPrimary,
            glowSecondary = if (useCoverSeed) secondaryPalette.tone40 else AbyssalBlueGlowSecondary,
            glowTertiary = effectiveTertiary,
            hairlineHighlight = Color.White.copy(alpha = 0.11f),
            primaryText = TextPrimaryDark,
            secondaryText = Color(0xFFB7C5D9),
            lyricActiveText = if (useCoverSeed) roles.stageLyricPrimary else Color(0xFF8CC8FF),
            lyricDimText = TextSecondaryDark.copy(alpha = 0.80f),
            accentPrimary = if (useCoverSeed) roles.primary else ConceptPrimary,
            accentSecondary = if (useCoverSeed) secondaryPalette.tone80 else ConceptCyanAccent,
            playButtonStart = playStart,
            playButtonEnd = playEnd,
            playButtonIcon = if (useCoverSeed) playIcon else Color.White,
            controlIconTint = Color(0xFFE7EFFA),
            controlButtonBg = Color.White.copy(alpha = 0.11f),
            badgeBg = (if (useCoverSeed) roles.palette.tone30 else Color(0xFF19355E)).copy(alpha = 0.72f),
            badgeText = badgeTextColor,
            favoriteActive = Color(0xFFFF6B8B),
            progressTrack = Color.White.copy(alpha = 0.18f),
            progressActiveStart = if (useCoverSeed) roles.primary else ConceptPrimary,
            progressActiveEnd = if (useCoverSeed) secondaryPalette.tone80 else ConceptCyanAccent
        )
    } else {
        val cardBase = when (config.style) {
            BreezeWidgetStyle.WATERCOLOR_STACK -> lerp(Color(0xFFFFFFFF), roles.palette.tone95, if (useCoverSeed) 0.55f else 0.42f)
            BreezeWidgetStyle.AURORA_COVER -> lerp(Color(0xFFF6FAFF), roles.palette.tone95, 0.48f)
            BreezeWidgetStyle.VINYL_SLEEVE -> Color(0xFFFBFCFF)
        }
        val cardEnd = when (config.style) {
            BreezeWidgetStyle.WATERCOLOR_STACK -> lerp(Color(0xFFF4F8FF), secondaryPalette.tone95, if (useCoverSeed) 0.40f else 0.25f)
            BreezeWidgetStyle.AURORA_COVER -> lerp(Color(0xFFEDF5FF), secondaryPalette.tone95, 0.36f)
            BreezeWidgetStyle.VINYL_SLEEVE -> Color(0xFFF0F6FF)
        }
        val playStart = if (useCoverSeed) {
            roles.primary
        } else if (config.style == BreezeWidgetStyle.WATERCOLOR_STACK) {
            ConceptCoralAccent
        } else {
            ConceptPrimaryLight
        }
        val playEnd = if (useCoverSeed) {
            secondaryPalette.tone50
        } else if (config.style == BreezeWidgetStyle.WATERCOLOR_STACK) {
            Color(0xFFFF8868)
        } else {
            ConceptCyanAccent
        }
        val badgeBgColor = roles.primaryContainer.copy(alpha = 0.90f).compositeOver(cardBase)

        BreezeWidgetPalette(
            isDarkSurface = false,
            cardBase = cardBase,
            cardGradientEnd = cardEnd,
            glowPrimary = if (useCoverSeed) roles.palette.tone80 else WatercolorBlueWash,
            glowSecondary = if (useCoverSeed) secondaryPalette.tone80 else WatercolorLavenderAccent,
            glowTertiary = if (config.style == BreezeWidgetStyle.WATERCOLOR_STACK) WatercolorPeachAccent else WatercolorCyanWash,
            hairlineHighlight = Color.White.copy(alpha = 0.82f),
            primaryText = TextPrimaryLight,
            secondaryText = TextSecondaryLight,
            lyricActiveText = if (useCoverSeed) roles.primary else ConceptPrimaryLight,
            lyricDimText = TextSecondaryLight.copy(alpha = 0.72f),
            accentPrimary = if (useCoverSeed) roles.primary else ConceptPrimaryLight,
            accentSecondary = if (useCoverSeed) secondaryPalette.tone40 else Color(0xFF00796B),
            playButtonStart = playStart,
            playButtonEnd = playEnd,
            playButtonIcon = Color.White,
            controlIconTint = Color(0xFF1F324D),
            controlButtonBg = if (useCoverSeed) roles.palette.tone90.copy(alpha = 0.80f) else ConceptElevatedCardLight,
            badgeBg = badgeBgColor,
            badgeText = if (useCoverSeed) roles.onPrimaryContainer else Color(0xFF0F4C97),
            favoriteActive = ConceptRoseAccent,
            progressTrack = Color(0xFF1A365D).copy(alpha = 0.13f),
            progressActiveStart = playStart,
            progressActiveEnd = playEnd
        )
    }
}

/**
 * 音质徽标强调色解析（Hi-Res 鎏金、SQ/FLAC 主题蓝）
 */
fun resolveQualityBadgeColorPair(
    badgeText: String,
    palette: BreezeWidgetPalette
): Pair<Color, Color> {
    val upper = badgeText.uppercase()
    return if (upper.contains("HI-RES") || upper.contains("DSD")) {
        if (palette.isDarkSurface) {
            Color(0x38E5A93B) to VipGold
        } else {
            Color(0xFFFFF3D6) to Color(0xFF9E6E17)
        }
    } else {
        palette.badgeBg to palette.badgeText
    }
}

/**
 * 桌面小组件全局配置持久化仓储
 */
object BreezeWidgetPrefs {
    private const val PREFS_NAME = "breeze_widget_config_v2"
    private const val KEY_STYLE = "widget_style"
    private const val KEY_THEME_MODE = "widget_theme_mode"
    private const val KEY_DYNAMIC_COVER = "widget_dynamic_cover"
    private const val KEY_BG_ALPHA = "widget_bg_alpha"
    private const val KEY_SHOW_LYRIC = "widget_show_lyric"
    private const val KEY_SHOW_QUALITY = "widget_show_quality"
    private const val KEY_SHOW_PROGRESS = "widget_show_progress"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _config = MutableStateFlow(BreezeWidgetConfig())
    val config: StateFlow<BreezeWidgetConfig> = _config.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = sp
            _config.value = BreezeWidgetConfig(
                style = BreezeWidgetStyle.fromPersistedName(sp.getString(KEY_STYLE, null)),
                themeMode = BreezeWidgetThemeMode.fromPersistedName(sp.getString(KEY_THEME_MODE, null)),
                dynamicCoverColor = sp.getBoolean(KEY_DYNAMIC_COVER, true),
                backgroundAlpha = sp.getFloat(KEY_BG_ALPHA, 0.94f)
                    .coerceIn(BreezeWidgetConfig.MIN_BG_ALPHA, BreezeWidgetConfig.MAX_BG_ALPHA),
                showLyric = sp.getBoolean(KEY_SHOW_LYRIC, true),
                showQualityBadge = sp.getBoolean(KEY_SHOW_QUALITY, true),
                showProgressBar = sp.getBoolean(KEY_SHOW_PROGRESS, true)
            )
        }
    }

    fun update(context: Context, transform: (BreezeWidgetConfig) -> BreezeWidgetConfig): BreezeWidgetConfig {
        init(context)
        val rawNext = transform(_config.value)
        val sanitized = rawNext.copy(
            backgroundAlpha = rawNext.backgroundAlpha.coerceIn(
                BreezeWidgetConfig.MIN_BG_ALPHA,
                BreezeWidgetConfig.MAX_BG_ALPHA
            )
        )
        _config.value = sanitized
        runCatching {
            prefs?.edit()
                ?.putString(KEY_STYLE, sanitized.style.name)
                ?.putString(KEY_THEME_MODE, sanitized.themeMode.name)
                ?.putBoolean(KEY_DYNAMIC_COVER, sanitized.dynamicCoverColor)
                ?.putFloat(KEY_BG_ALPHA, sanitized.backgroundAlpha)
                ?.putBoolean(KEY_SHOW_LYRIC, sanitized.showLyric)
                ?.putBoolean(KEY_SHOW_QUALITY, sanitized.showQualityBadge)
                ?.putBoolean(KEY_SHOW_PROGRESS, sanitized.showProgressBar)
                ?.apply()
        }
        return sanitized
    }
}
