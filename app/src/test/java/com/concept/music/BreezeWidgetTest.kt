package com.concept.music

import androidx.compose.ui.graphics.Color
import com.concept.music.data.model.Song
import com.concept.music.service.ConceptMusicWidgetUpdater
import com.concept.music.ui.components.extractCoverMeshColorsFromPixels
import com.concept.music.ui.theme.wcagContrastRatio
import com.concept.music.widget.BreezeWidgetActions
import com.concept.music.widget.BreezeWidgetConfig
import com.concept.music.widget.BreezeWidgetPinAdapter
import com.concept.music.widget.BreezeWidgetPlaybackSnapshot
import com.concept.music.widget.BreezeWidgetSizeClass
import com.concept.music.widget.BreezeWidgetStyle
import com.concept.music.widget.BreezeWidgetThemeMode
import com.concept.music.widget.WidgetHostRom
import com.concept.music.widget.formatWidgetDuration
import com.concept.music.widget.resolveBreezeWidgetPalette
import com.concept.music.widget.resolveQualityBadgeColorPair
import com.concept.music.widget.resolveWidgetBackgroundSpec
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

    @Test
    fun `resolveWidgetBackgroundSpec keeps restrained corner radii and preserves host aspect ratio`() {
        for (size in BreezeWidgetSizeClass.entries) {
            val spec = resolveWidgetBackgroundSpec(size)
            assertTrue("画布宽应合理 size=$size", spec.widthPx in 80..520)
            assertTrue("画布高应合理 size=$size", spec.heightPx in 56..520)
            val minEdge = minOf(spec.widthPx, spec.heightPx).toFloat()
            val ratio = spec.cornerRadiusPx / minEdge
            if (size == BreezeWidgetSizeClass.CAPSULE_4X1) {
                assertEquals(16f, spec.cornerRadiusDp, 0.01f)
                assertTrue("4x1 圆角不得回退为夸张半圆胶囊 ratio=$ratio", ratio <= 0.281f)
            } else {
                assertTrue("卡片圆角应克制在 18dp..20dp 之间 dp=${spec.cornerRadiusDp}", spec.cornerRadiusDp in 18f..20f)
                assertTrue("卡片圆角占比应克制 size=$size ratio=$ratio", ratio <= 0.161f)
            }
        }

        // 传入桌面网格实际宽高时应按真实宽高比生成画布，避免 fitXY 拉伸圆角畸变
        val wide4x2 = resolveWidgetBackgroundSpec(BreezeWidgetSizeClass.CARD_4X2, hostWidthDp = 360, hostHeightDp = 150)
        val aspect = wide4x2.widthPx.toFloat() / wide4x2.heightPx.toFloat()
        assertEquals(2.4f, aspect, 0.08f)

        assertEquals("00:00", formatWidgetDuration(-100L))
        assertEquals("00:00", formatWidgetDuration(0L))
        assertEquals("01:34", formatWidgetDuration(94_000L))
        assertEquals("04:06", formatWidgetDuration(246_000L))
    }

    @Test
    fun `4x4 editorial stage layout and preview drawables match redesigned structure and restrained corners`() {
        val root = resolveProjectRoot()
        val stageXml = File(root, "app/src/main/res/layout/widget_breeze_stage.xml").readText(Charsets.UTF_8)
        assertTrue(stageXml.contains("widget_song_album"))
        assertTrue(stageXml.contains("widget_lyric_flipper"))
        assertTrue(stageXml.contains("widget_lyric_roll_in"))
        assertTrue(stageXml.contains("widget_lyric_roll_out"))
        assertTrue(stageXml.contains("widget_lyric_accent_bar_0"))
        assertTrue(stageXml.contains("widget_lyric_accent_bar_1"))
        assertTrue(stageXml.contains("widget_lyric_line_0"))
        assertTrue(stageXml.contains("widget_lyric_line_1"))
        assertTrue(stageXml.contains("widget_progress_row"))
        assertTrue(stageXml.contains("widget_time_current"))
        assertTrue(stageXml.contains("widget_time_total"))
        assertTrue(stageXml.contains("""android:maxLines="2""""))

        assertTrue(File(root, "app/src/main/res/anim/widget_lyric_roll_in.xml").exists())
        assertTrue(File(root, "app/src/main/res/anim/widget_lyric_roll_out.xml").exists())

        val cardBgXml = File(root, "app/src/main/res/drawable/bg_widget_concept.xml").readText(Charsets.UTF_8)
        val capsuleBgXml = File(root, "app/src/main/res/drawable/bg_widget_capsule_preview.xml").readText(Charsets.UTF_8)
        assertTrue("4x2/2x2/4x4 预览圆角应收敛至 18dp", cardBgXml.contains("""android:radius="18dp""""))
        assertTrue("4x1 预览圆角应收敛至 16dp", capsuleBgXml.contains("""android:radius="16dp""""))

        // 验证四规格均同时声明静态 previewImage WebP 与零卡顿 previewLayout（兼容 ColorOS/MIUI/OriginOS 插件页）
        val providerChecks = listOf(
            Triple("app/src/main/res/xml/concept_music_widget_info.xml", "preview_widget_4x2", "widget_preview_4x2"),
            Triple("app/src/main/res/xml/breeze_widget_compact_info.xml", "preview_widget_4x1", "widget_preview_4x1"),
            Triple("app/src/main/res/xml/breeze_widget_square_info.xml", "preview_widget_2x2", "widget_preview_2x2"),
            Triple("app/src/main/res/xml/breeze_widget_stage_info.xml", "preview_widget_4x4", "widget_preview_4x4")
        )
        providerChecks.forEach { (infoPath, previewImg, previewLayout) ->
            val xml = File(root, infoPath).readText(Charsets.UTF_8)
            assertTrue("$infoPath 必须声明 android:previewImage", xml.contains("""android:previewImage="@drawable/$previewImg""""))
            assertTrue("$infoPath 必须声明轻量 android:previewLayout", xml.contains("""android:previewLayout="@layout/$previewLayout""""))
            assertTrue("缺少静态预览 WebP 资源 $previewImg", File(root, "app/src/main/res/drawable-nodpi/$previewImg.webp").exists())
            assertTrue("缺少极简预览布局 $previewLayout", File(root, "app/src/main/res/layout/$previewLayout.xml").exists())
        }
    }

    @Test
    fun `OEM widget pin adapter detects major ROMs and widget deep link preserves singleTask back stack`() {
        assertEquals(WidgetHostRom.COLOR_OS, BreezeWidgetPinAdapter.detectWidgetHostRom("OPPO", "OPPO", oplusProp = "V15.0"))
        assertEquals(WidgetHostRom.COLOR_OS, BreezeWidgetPinAdapter.detectWidgetHostRom("OnePlus", "OnePlus"))
        assertEquals(WidgetHostRom.COLOR_OS, BreezeWidgetPinAdapter.detectWidgetHostRom("realme", "realme"))
        assertEquals(WidgetHostRom.HYPER_OS_MIUI, BreezeWidgetPinAdapter.detectWidgetHostRom("Xiaomi", "Redmi", miuiProp = "V816"))
        assertEquals(WidgetHostRom.ORIGIN_OS, BreezeWidgetPinAdapter.detectWidgetHostRom("vivo", "iQOO", vivoProp = "5.0"))
        assertEquals(WidgetHostRom.HARMONY_EMUI, BreezeWidgetPinAdapter.detectWidgetHostRom("HUAWEI", "HUAWEI", harmonyProp = "4.2.0"))
        assertEquals(WidgetHostRom.MAGIC_OS, BreezeWidgetPinAdapter.detectWidgetHostRom("HONOR", "HONOR", magicProp = "8.0"))
        assertEquals(WidgetHostRom.ONE_UI, BreezeWidgetPinAdapter.detectWidgetHostRom("samsung", "samsung"))
        assertEquals(WidgetHostRom.FLYME, BreezeWidgetPinAdapter.detectWidgetHostRom("meizu", "meizu", flymeProp = "Flyme 11"))
        assertEquals(WidgetHostRom.AOSP_OTHER, BreezeWidgetPinAdapter.detectWidgetHostRom("Google", "google"))

        for (rom in WidgetHostRom.entries) {
            val guidance = BreezeWidgetPinAdapter.buildOemPinGuidance(rom, BreezeWidgetSizeClass.STAGE_4X4)
            assertTrue(guidance.title.isNotBlank())
            assertTrue(guidance.manualSteps.size >= 3)
        }

        val colorOsGuidance = BreezeWidgetPinAdapter.buildOemPinGuidance(WidgetHostRom.COLOR_OS, BreezeWidgetSizeClass.CARD_4X2)
        assertTrue("ColorOS 权限说明应匹配真实选项「使用时允许」", colorOsGuidance.permissionTip.contains("使用时允许"))
        assertFalse("ColorOS 权限说明不应误导用户寻找不存在的「始终允许」", colorOsGuidance.permissionTip.contains("始终允许"))
        assertTrue("ColorOS 指引应提示检查「锁定桌面布局」", colorOsGuidance.permissionTip.contains("锁定桌面布局"))

        val root = resolveProjectRoot()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        assertTrue("MainActivity 应声明 singleTask 防止后台点组件返回直退桌面", manifest.contains("""android:launchMode="singleTask""""))
        assertTrue("Manifest 应声明 INSTALL_SHORTCUT 权限以便国产桌面展示并放行桌面快捷方式开关", manifest.contains("com.android.launcher.permission.INSTALL_SHORTCUT"))

        val pinAdapterCode = File(root, "app/src/main/java/com/concept/music/widget/BreezeWidgetPinAdapter.kt").readText(Charsets.UTF_8)
        assertTrue("回调 PendingIntent 应使用 FLAG_IMMUTABLE 兼容 Android 14+ / ColorOS", pinAdapterCode.contains("PendingIntent.FLAG_IMMUTABLE"))
        assertFalse("回调 PendingIntent 不应再使用 FLAG_MUTABLE", pinAdapterCode.contains("PendingIntent.FLAG_MUTABLE"))

        val widgetManagerCode = File(root, "app/src/main/java/com/concept/music/widget/BreezeWidgetManager.kt").readText(Charsets.UTF_8)
        assertTrue("一键添加默认应传 previewRemoteViews = null 避免 ColorOS 桌面因 Binder 载荷过载丢弃弹窗", widgetManagerCode.contains("previewRemoteViews = null"))
        assertFalse("一键添加不应向 EXTRA_APPWIDGET_PREVIEW 传入含多张大 Bitmap 的动态预览", widgetManagerCode.contains("buildPinPreview("))

        val builderCode = File(root, "app/src/main/java/com/concept/music/widget/BreezeWidgetRemoteViewsBuilder.kt").readText(Charsets.UTF_8)
        assertFalse("singleTask 下点击小组件跳转不应附带 Intent.FLAG_ACTIVITY_CLEAR_TOP", builderCode.contains("Intent.FLAG_ACTIVITY_CLEAR_TOP"))

        val mainActivityCode = File(root, "app/src/main/java/com/concept/music/MainActivity.kt").readText(Charsets.UTF_8)
        assertTrue("MainActivity 应在根级提升播放页 BackHandler 并托管内部返回拦截", mainActivityCode.contains("useHoistedBackHandler = true"))
    }
}
