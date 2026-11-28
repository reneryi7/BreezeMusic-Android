package com.concept.music

import androidx.compose.ui.graphics.Color
import com.concept.music.data.model.Song
import com.concept.music.service.ConceptMusicWidgetUpdater
import com.concept.music.ui.components.extractCoverMeshColorsFromPixels
import com.concept.music.ui.theme.wcagContrastRatio
import com.concept.music.widget.BreezeWidgetActions
import com.concept.music.widget.BreezeWidgetConfig
import com.concept.music.widget.BreezeWidgetPlaybackSnapshot
import com.concept.music.widget.BreezeWidgetStyle
import com.concept.music.widget.BreezeWidgetThemeMode
import com.concept.music.widget.resolveBreezeWidgetPalette
import com.concept.music.widget.resolveQualityBadgeColorPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BreezeWidgetTest {

    private fun resolveProjectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            if (File(dir, "app/src/main/AndroidManifest.xml").exists()) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".")
    }

    @Test
    fun `resolveBreezeWidgetPalette guarantees WCAG AA text contrast across all styles themes and cover seeds`() {
        val sampleSeeds = listOf(
            null,
            Color(0xFF0A1F44), // 极深海蓝封面
            Color(0xFFFFE066), // 极亮柠黄封面
            Color(0xFFE63946), // 高饱和洋红封面
            Color(0xFF2EC4B6), // 薄荷青绿封面
            Color(0xFF7B2CBF), // 霓虹紫罗兰封面
            Color(0xFF9AA0A6)  // 低饱和灰阶封面
        )

        for (style in BreezeWidgetStyle.entries) {
            for (themeMode in BreezeWidgetThemeMode.entries) {
                for (isAppDark in listOf(true, false)) {
                    for (dynamicCover in listOf(true, false)) {
                        for (seed in sampleSeeds) {
                            val config = BreezeWidgetConfig(
                                style = style,
                                themeMode = themeMode,
                                dynamicCoverColor = dynamicCover
                            )
                            val palette = resolveBreezeWidgetPalette(
                                config = config,
                                isAppDark = isAppDark,
                                coverSeedColor = seed,
                                coverSecondaryColor = Color(0xFF00C4A7),
                                coverTertiaryColor = Color(0xFF6C5CE7)
                            )
                            val titleContrast = palette.primaryText.wcagContrastRatio(palette.cardBase)
                            val lyricContrast = palette.lyricActiveText.wcagContrastRatio(palette.cardBase)
                            assertTrue(
                                "主标题对比度不足 style=$style mode=$themeMode dark=$isAppDark seed=$seed ratio=$titleContrast",
                                titleContrast >= 4.5f
                            )
                            assertTrue(
                                "高亮歌词对比度不足 style=$style mode=$themeMode dark=$isAppDark seed=$seed ratio=$lyricContrast",
                                lyricContrast >= 3.8f
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `extractCoverMeshColorsFromPixels handles vivid gradient and monochrome pixels safely`() {
        val w = 24
        val h = 24
        val coralPixels = IntArray(w * h) { idx ->
            val x = idx % w
            if (x < w / 2) 0xFFFF5E5B.toInt() else 0xFF00C4A7.toInt()
        }
        val vividPalette = extractCoverMeshColorsFromPixels(coralPixels, w, h)
        assertNotNull(vividPalette)
        assertTrue(vividPalette!!.dominantAccent.alpha == 1f)
        assertTrue(vividPalette.secondaryAccent.alpha == 1f)
        assertTrue(vividPalette.tertiaryAccent.alpha == 1f)

        val grayPixels = IntArray(w * h) { 0xFF808080.toInt() }
        val grayPalette = extractCoverMeshColorsFromPixels(grayPixels, w, h)
        assertNotNull(grayPalette)
    }

    @Test
    fun `BreezeWidgetConfig sanitizes alpha bounds and parses invalid enum names safely`() {
        assertEquals(BreezeWidgetConfig.MIN_BG_ALPHA, BreezeWidgetConfig(backgroundAlpha = 0.05f).sanitizedBackgroundAlpha, 1e-4f)
        assertEquals(BreezeWidgetConfig.MAX_BG_ALPHA, BreezeWidgetConfig(backgroundAlpha = 2.5f).sanitizedBackgroundAlpha, 1e-4f)
        assertEquals(BreezeWidgetStyle.VINYL_SLEEVE, BreezeWidgetStyle.fromPersistedName("UNKNOWN_STYLE"))
        assertEquals(BreezeWidgetThemeMode.FOLLOW_APP, BreezeWidgetThemeMode.fromPersistedName(null))
    }

    @Test
    fun `BreezeWidgetPlaybackSnapshot maps song quality badge and idle state cleanly`() {
        val idle = BreezeWidgetPlaybackSnapshot.fromSong(null)
        assertFalse(idle.hasActiveSong)
        assertEquals(BreezeWidgetPlaybackSnapshot.DEFAULT_IDLE_TITLE, idle.title)

        val hiResSong = Song(
            id = "s_hires",
            title = "Foggy Moon",
            artist = "She Her Her Hers",
            coverUrl = "https://imgessl.kugou.com/stdmusic/400/test.jpg",
            durationMs = 240_000L,
            isHiRes = true
        )
        val snap = BreezeWidgetPlaybackSnapshot.fromSong(
            song = hiResSong,
            nextSong1 = hiResSong.copy(id = "s_next1", coverUrl = "https://imgessl.kugou.com/1.jpg"),
            nextSong2 = hiResSong.copy(id = "s_next2", coverUrl = "https://imgessl.kugou.com/2.jpg"),
            isPlaying = true,
            isFavorite = true,
            positionMs = 120_000L
        )
        assertTrue(snap.hasActiveSong)
        assertEquals("Hi-Res", snap.qualityBadge)
        assertEquals(0.5f, snap.progressFraction, 1e-4f)
        assertEquals("https://imgessl.kugou.com/1.jpg", snap.nextCoverUrl1)
        assertEquals("https://imgessl.kugou.com/2.jpg", snap.nextCoverUrl2)

        val darkPalette = resolveBreezeWidgetPalette(BreezeWidgetConfig(), isAppDark = true)
        val (_, hiResFg) = resolveQualityBadgeColorPair("Hi-Res", darkPalette)
        assertTrue(hiResFg.red > 0.7f)
    }

    @Test
    fun `Manifest registers all four widget providers securely and legacy branding is removed`() {
        val root = resolveProjectRoot()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        assertTrue("必须保留 ConceptMusicWidgetProvider 保障旧版本小组件原地兼容升级", manifest.contains("""android:name=".service.ConceptMusicWidgetProvider""""))
        assertTrue(manifest.contains("""android:name=".widget.BreezeCompactWidgetProvider""""))
        assertTrue(manifest.contains("""android:name=".widget.BreezeSquareWidgetProvider""""))
        assertTrue(manifest.contains("""android:name=".widget.BreezeStageWidgetProvider""""))

        // 自定义小组件播控 Action 严禁以隐式 Intent Filter 暴露在 Manifest 中
        assertFalse(manifest.contains(BreezeWidgetActions.ACTION_TOGGLE_PLAY))
        assertFalse(manifest.contains(BreezeWidgetActions.ACTION_TOGGLE_FAVORITE))
        assertFalse(manifest.contains(BreezeWidgetActions.ACTION_CYCLE_PLAY_MODE))

        // 验证旧版硬编码品牌文案与 SVIP 黄色徽标已彻底清除
        val layout4x2 = File(root, "app/src/main/res/layout/widget_concept_music.xml").readText(Charsets.UTF_8)
        assertFalse(layout4x2.contains("ConceptMusic 酷狗概念版"))
        assertFalse(layout4x2.contains("SVIP 已守护"))
        assertFalse(layout4x2.contains("android:drawable/ic_media_play"))

        // 验证四个新旧小组件常量门面 100% 向后兼容
        assertEquals(BreezeWidgetActions.ACTION_TOGGLE_PLAY, ConceptMusicWidgetUpdater.ACTION_WIDGET_TOGGLE_PLAY)
        assertEquals(BreezeWidgetActions.ACTION_NEXT, ConceptMusicWidgetUpdater.ACTION_WIDGET_NEXT)
        assertEquals(BreezeWidgetActions.ACTION_PREV, ConceptMusicWidgetUpdater.ACTION_WIDGET_PREV)
        assertEquals(BreezeWidgetActions.ACTION_TOGGLE_FAVORITE, ConceptMusicWidgetUpdater.ACTION_WIDGET_TOGGLE_FAVORITE)
        assertEquals(BreezeWidgetActions.ACTION_CYCLE_PLAY_MODE, ConceptMusicWidgetUpdater.ACTION_WIDGET_CYCLE_PLAY_MODE)
    }
}
