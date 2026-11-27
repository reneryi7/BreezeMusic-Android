package com.concept.music

import com.concept.music.data.model.*
import com.concept.music.data.repository.LocalMusicRepository
import com.concept.music.service.AppPermissionManager
import com.concept.music.service.AppPermissionState
import com.concept.music.service.AppPermissionType
import com.concept.music.service.LocalAudioMetadataParser
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

class LocalMusicAndMarqueeRegressionTest {

    // =========================================================================
    // 1. 二进制音频流解析测试（FLAC / WAV / MP3 ID3v2 / M4A ALAC & AAC）
    // =========================================================================

    @Test
    fun testFlacHiResAndStandardLosslessBinaryParsing() {
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
        val hiResFlac = buildMinimalFlacBytes(
            sampleRate = 96_000,
            channels = 2,
            bitDepth = 24,
            totalSamples = 96_000L * 240L,
            comments = listOf(
                "TITLE=晴天 (母带版)",
                "ARTIST=周杰伦",
                "ARTIST=费玉清",
                "ALBUM=叶惠美",
                "DATE=2003",
                "LYRICS=[00:01.00]故事的小黄花\n[00:05.00]从出生那年就飘着"
            ),
            pictureBytes = fakeJpeg
        )

        val parsedHiRes = LocalAudioMetadataParser.parseFromStream(
            ByteArrayInputStream(hiResFlac),
            fileName = "01. qingtian.flac",
            fileSizeBytes = 75L * 1024L * 1024L
        )
        assertEquals("FLAC", parsedHiRes.format)
        assertEquals(96_000, parsedHiRes.sampleRateHz)
        assertEquals(24, parsedHiRes.bitDepth)
        assertEquals(240_000L, parsedHiRes.durationMs)
        assertEquals(LocalAudioQualityTier.HI_RES, parsedHiRes.qualityTier)
        assertEquals("晴天 (母带版)", parsedHiRes.title)
        assertEquals(listOf("周杰伦", "费玉清"), parsedHiRes.artists)
        assertEquals("叶惠美", parsedHiRes.album)
        assertEquals("2003", parsedHiRes.year)
        assertTrue(parsedHiRes.embeddedLyric.contains("故事的小黄花"))
        assertNotNull(parsedHiRes.coverBytes)
        assertArrayEquals(fakeJpeg, parsedHiRes.coverBytes)

        val cdFlac = buildMinimalFlacBytes(
            sampleRate = 44_100,
            channels = 2,
            bitDepth = 16,
            totalSamples = 44_100L * 180L,
            comments = listOf("TITLE=七里香", "ARTIST=周杰伦", "ALBUM=七里香")
        )
        val parsedCd = LocalAudioMetadataParser.parseFromStream(
            ByteArrayInputStream(cdFlac),
            fileName = "qilixiang.flac",
            fileSizeBytes = 28L * 1024L * 1024L
        )
        assertEquals("FLAC", parsedCd.format)
        assertEquals(44_100, parsedCd.sampleRateHz)
        assertEquals(16, parsedCd.bitDepth)
        assertEquals(180_000L, parsedCd.durationMs)
        assertEquals(LocalAudioQualityTier.LOSSLESS, parsedCd.qualityTier)
    }

    @Test
    fun testWavBinaryParsingWithListInfoAndEmbeddedId3() {
        val fakeCover = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val id3 = buildMinimalId3v23(
            title = "Hotel California",
            artist = "Eagles",
            album = "Hotel California",
            lyric = "[00:10.00]On a dark desert highway",
            coverBytes = fakeCover,
            useGb18030ForArtist = false
        )
        val wav24Bit = buildMinimalWavBytes(
            sampleRate = 48_000,
            channels = 2,
            bitDepth = 24,
            durationSec = 390,
            id3Chunk = id3
        )
        val parsed = LocalAudioMetadataParser.parseFromStream(
            ByteArrayInputStream(wav24Bit),
            fileName = "hotel.wav",
            fileSizeBytes = wav24Bit.size.toLong()
        )
        assertEquals("WAV", parsed.format)
        assertEquals(48_000, parsed.sampleRateHz)
        assertEquals(24, parsed.bitDepth)
        assertEquals(390_000L, parsed.durationMs)
        assertEquals(LocalAudioQualityTier.HI_RES, parsed.qualityTier)
        assertEquals("Hotel California", parsed.title)
        assertEquals("Eagles", parsed.artist)
        assertTrue(parsed.embeddedLyric.contains("dark desert highway"))
        assertArrayEquals(fakeCover, parsed.coverBytes)

        val wav16Bit = buildMinimalWavBytes(
            sampleRate = 44_100,
            channels = 2,
            bitDepth = 16,
            durationSec = 200,
            infoTitle = "夜曲",
            infoArtist = "周杰伦"
        )
        val parsed16 = LocalAudioMetadataParser.parseFromStream(
            ByteArrayInputStream(wav16Bit),
            fileName = "yequ.wav",
            fileSizeBytes = wav16Bit.size.toLong()
        )
        assertEquals("WAV", parsed16.format)
        assertEquals(44_100, parsed16.sampleRateHz)
        assertEquals(16, parsed16.bitDepth)
        assertEquals(LocalAudioQualityTier.LOSSLESS, parsed16.qualityTier)
        assertEquals("夜曲", parsed16.title)
        assertEquals("周杰伦", parsed16.artist)
    }

    @Test
    fun testMp3Id3v23Gb18030AndMpegHeaderParsing() {
        val cover = byteArrayOf(1, 2, 3, 4, 5, 6)
        val id3 = buildMinimalId3v23(
            title = "千里之外",
            artist = "周杰伦 / 费玉清",
            album = "依然范特西",
            lyric = "[00:02.50]屋檐如悬崖 风铃如沧海",
            coverBytes = cover,
            useGb18030ForArtist = true
        )
        // MPEG1 Layer3 320kbps 44100Hz 合法帧头: FF FB E0 00
        val mp3Bytes = id3 + byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0xE0.toByte(), 0x00, 0, 0, 0, 0)
        val parsed = LocalAudioMetadataParser.parseFromStream(
            ByteArrayInputStream(mp3Bytes),
            fileName = "qianli.mp3",
            fileSizeBytes = 9L * 1024L * 1024L
        )
        assertEquals("MP3", parsed.format)
        assertEquals(44_100, parsed.sampleRateHz)
        assertEquals(320, parsed.bitrateKbps)
        assertEquals(LocalAudioQualityTier.LOSSY, parsed.qualityTier)
        assertEquals("千里之外", parsed.title)
        assertEquals(listOf("周杰伦", "费玉清"), parsed.artists)
        assertTrue(parsed.embeddedLyric.contains("屋檐如悬崖"))
        assertArrayEquals(cover, parsed.coverBytes)
    }

    @Test
    fun testMp4AlacVsAacParsing() {
        val alacBytes = buildMinimalM4aBytes(
            isAlac = true,
            sampleRate = 96_000,
            bitDepth = 24,
            durationSec = 215,
            title = "稻香",
            artist = "周杰伦",
            album = "魔杰座",
            lyric = "[00:03.00]对这个世界如果你有太多的抱怨"
        )
        val parsedAlac = LocalAudioMetadataParser.parseFromStream(
            ByteArrayInputStream(alacBytes),
            fileName = "daoxiang.m4a",
            fileSizeBytes = 64L * 1024L * 1024L
        )
        assertEquals("ALAC", parsedAlac.format)
        assertEquals(96_000, parsedAlac.sampleRateHz)
        assertEquals(24, parsedAlac.bitDepth)
        assertEquals(215_000L, parsedAlac.durationMs)
        assertEquals(LocalAudioQualityTier.HI_RES, parsedAlac.qualityTier)
        assertEquals("稻香", parsedAlac.title)
        assertTrue(parsedAlac.embeddedLyric.contains("太多的抱怨"))

        val aacBytes = buildMinimalM4aBytes(
            isAlac = false,
            sampleRate = 44_100,
            bitDepth = 16,
            durationSec = 190,
            title = "Memories",
            artist = "A7i",
            album = "Single",
            lyric = ""
        )
        val parsedAac = LocalAudioMetadataParser.parseFromStream(
            ByteArrayInputStream(aacBytes),
            fileName = "memories.m4a",
            fileSizeBytes = 6L * 1024L * 1024L
        )
        assertEquals("AAC", parsedAac.format)
        assertEquals(44_100, parsedAac.sampleRateHz)
        assertEquals(LocalAudioQualityTier.LOSSY, parsedAac.qualityTier)
        assertEquals("Memories", parsedAac.title)
        assertEquals("A7i", parsedAac.artist)
    }

    // =========================================================================
    // 2. Hi-Res/无损严格口径、筛选并集、多歌手拆分、五维排序与歌词编码
    // =========================================================================

    @Test
    fun testQualityTierClassificationSummaryAndUnionFilter() {
        // 主流播放器口径验证：24bit/44.1kHz、24bit/96kHz、16bit/96kHz 均属于 Hi-Res
        assertEquals(LocalAudioQualityTier.HI_RES, LocalAudioMetadataParser.classifyQualityTier("FLAC", 44_100, 24))
        assertEquals(LocalAudioQualityTier.HI_RES, LocalAudioMetadataParser.classifyQualityTier("FLAC", 96_000, 24))
        assertEquals(LocalAudioQualityTier.HI_RES, LocalAudioMetadataParser.classifyQualityTier("WAV", 96_000, 16))
        assertEquals(LocalAudioQualityTier.LOSSLESS, LocalAudioMetadataParser.classifyQualityTier("FLAC", 44_100, 16))
        assertEquals(LocalAudioQualityTier.LOSSLESS, LocalAudioMetadataParser.classifyQualityTier("WAV", 48_000, 16))
        assertEquals(LocalAudioQualityTier.LOSSY, LocalAudioMetadataParser.classifyQualityTier("MP3", 48_000, 24))
        assertEquals(LocalAudioQualityTier.LOSSY, LocalAudioMetadataParser.classifyQualityTier("AAC", 96_000, 24))

        val t1 = sampleTrack("1", "HiResFlac", "歌手A", "FLAC", LocalAudioQualityTier.HI_RES, size = 80_000_000L, dur = 240_000L, order = 1)
        val t2 = sampleTrack("2", "LosslessFlac", "歌手B", "FLAC", LocalAudioQualityTier.LOSSLESS, size = 30_000_000L, dur = 180_000L, order = 2)
        val t3 = sampleTrack("3", "LosslessWav", "歌手C", "WAV", LocalAudioQualityTier.LOSSLESS, size = 45_000_000L, dur = 210_000L, order = 3)
        val t4 = sampleTrack("4", "HiResAlac", "歌手D", "ALAC", LocalAudioQualityTier.HI_RES, size = 65_000_000L, dur = 200_000L, order = 4)
        val t5 = sampleTrack("5", "Mp3Track", "歌手E", "MP3", LocalAudioQualityTier.LOSSY, size = 8_000_000L, dur = 300_000L, order = 5)
        val t6 = sampleTrack("6", "AacTrack", "歌手F", "AAC", LocalAudioQualityTier.LOSSY, size = 7_000_000L, dur = 150_000L, order = 6)
        val all = listOf(t1, t2, t3, t4, t5, t6)

        // 顶部统计严格互斥（2 首 Hi-Res + 2 首无损 + 2 首有损 = 6 首）
        val summary = LocalMusicQueryEngine.summarize(all, emptyList(), emptyList())
        assertEquals(6, summary.totalCount)
        assertEquals(2, summary.hiResCount)
        assertEquals(2, summary.losslessCount)
        assertEquals(2, summary.lossyCount)

        // 二级筛选并集：选 FLAC/WAV 必须包含 Hi-Res 级 FLAC (t1) 与标准无损 FLAC/WAV (t2, t3)，不包含 ALAC/MP3/AAC
        val flacWavFiltered = LocalMusicQueryEngine.filterAndSortTracks(all, qualityFilter = LocalQualityFilter.FLAC_WAV)
        assertEquals(listOf("1", "2", "3"), flacWavFiltered.map { it.id })

        val hiResFiltered = LocalMusicQueryEngine.filterAndSortTracks(all, qualityFilter = LocalQualityFilter.HI_RES)
        assertEquals(listOf("1", "4"), hiResFiltered.map { it.id })

        val mp3AacFiltered = LocalMusicQueryEngine.filterAndSortTracks(all, qualityFilter = LocalQualityFilter.MP3_AAC)
        assertEquals(listOf("5", "6"), mp3AacFiltered.map { it.id })

        // 五维排序验证
        assertEquals(listOf("1", "4", "3", "2", "5", "6"), LocalMusicQueryEngine.sortTracks(all, LocalSortOption.FILE_SIZE).map { it.id })
        assertEquals(listOf("5", "1", "3", "4", "2", "6"), LocalMusicQueryEngine.sortTracks(all, LocalSortOption.DURATION).map { it.id })
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), LocalMusicQueryEngine.sortTracks(all.reversed(), LocalSortOption.DEFAULT).map { it.id })
    }

    @Test
    fun testMultiArtistSplittingAndProtectedBandNames() {
        assertEquals(listOf("周杰伦", "费玉清"), LocalMusicQueryEngine.splitArtistNames("周杰伦 / 费玉清"))
        assertEquals(listOf("Linkin Park", "Jay-Z", "Mike Shinoda"), LocalMusicQueryEngine.splitArtistNames("Linkin Park feat. Jay-Z & Mike Shinoda"))
        assertEquals(listOf("陈奕迅", "王菲"), LocalMusicQueryEngine.splitArtistNames("陈奕迅、王菲"))
        // 乐队白名单防误拆
        assertEquals(listOf("AC/DC"), LocalMusicQueryEngine.splitArtistNames("AC/DC"))
        assertEquals(listOf("Simon & Garfunkel"), LocalMusicQueryEngine.splitArtistNames("Simon & Garfunkel"))
        assertEquals(listOf("Earth, Wind & Fire", "Pharrell Williams"), LocalMusicQueryEngine.splitArtistNames("Earth, Wind & Fire feat. Pharrell Williams"))

        // 同一歌手跨多首合唱与独唱合并为唯一项
        val duet = sampleTrack("d1", "千里之外", "周杰伦 / 费玉清", "FLAC", LocalAudioQualityTier.HI_RES)
        val solo = sampleTrack("s1", "晴天", "周杰伦", "FLAC", LocalAudioQualityTier.LOSSLESS)
        val groups = LocalMusicQueryEngine.groupByArtists(listOf(duet, solo))
        val jay = groups.firstOrNull { it.name == "周杰伦" }
        val fei = groups.firstOrNull { it.name == "费玉清" }
        assertNotNull(jay)
        assertNotNull(fei)
        assertEquals(2, jay!!.tracks.size)
        assertEquals(1, jay.hiResCount)
        assertEquals(1, jay.losslessCount)
        assertEquals(1, fei!!.tracks.size)
        assertEquals("千里之外", fei.tracks.first().title)
    }

    @Test
    fun testSmartLyricEncodingAndExternalKeyMatching() {
        val sampleLrc = "[00:01.00]风吹过耳边\n[00:05.00]微风轻轻吹"
        val gbBytes = sampleLrc.toByteArray(Charset.forName("GB18030"))
        val decodedGb = LocalMusicRepository.parseLyricBytes(gbBytes, "demo.lrc")
        assertEquals(2, decodedGb.lines.size)
        assertEquals("风吹过耳边", decodedGb.lines[0].text)
        assertEquals("微风轻轻吹", decodedGb.lines[1].text)

        val utf8BomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sampleLrc.toByteArray(Charsets.UTF_8)
        val decodedBom = LocalMusicRepository.parseLyricBytes(utf8BomBytes, "demo.lrc")
        assertEquals(2, decodedBom.lines.size)
        assertEquals("风吹过耳边", decodedBom.lines[0].text)

        val repo = LocalMusicRepository(null)
        val fakeIndex = mapOf(
            LocalMusicRepository.normalizeMatchKey("周杰伦 - 晴天.lrc") to "content://lyrics/qingtian.lrc",
            LocalMusicRepository.normalizeMatchKey("Memories.lrc") to "content://lyrics/memories.lrc"
        )
        assertEquals(
            "content://lyrics/qingtian.lrc",
            repo.findBestExternalLyricUri(fileName = "01. qingtian.flac", title = "晴天", artist = "周杰伦", indexOverride = fakeIndex)
        )
        assertEquals(
            "content://lyrics/memories.lrc",
            repo.findBestExternalLyricUri(fileName = "track08.wav", title = "Memories", artist = "A7i", indexOverride = fakeIndex)
        )
    }

    @Test
    fun testLocalPermissionDefaultAndDefaultTabPreference() {
        assertTrue(
            "本地音频导入开关默认应开启，实际访问仍需用户主动选文件夹",
            AppPermissionState().isEnabled(AppPermissionType.LOCAL_AUDIO_IMPORT)
        )
        assertEquals(LocalCloudDefaultTab.LOCAL, LocalCloudDefaultTab.fromKey(null))
        assertEquals(LocalCloudDefaultTab.CLOUD, LocalCloudDefaultTab.fromKey("cloud"))

        AppPermissionManager.resetForTest()
        AppPermissionManager.setPermissionEnabled(null, AppPermissionType.LOCAL_AUDIO_IMPORT, false)
        assertFalse(AppPermissionManager.isPermissionEnabled(AppPermissionType.LOCAL_AUDIO_IMPORT))
        AppPermissionManager.restoreDefaultCorePermissions(null)
        assertTrue(AppPermissionManager.isPermissionEnabled(AppPermissionType.LOCAL_AUDIO_IMPORT))
        assertFalse("恢复默认权限绝不可误开下载开关", AppPermissionManager.isPermissionEnabled(AppPermissionType.MUSIC_DOWNLOAD))
    }

    // =========================================================================
    // 3. 横屏退回竖屏歌名消失 BUG 源码级回归保护
    // =========================================================================

    @Test
    fun testNowPlayingPortraitTitleMarqueeRegressionGuard() {
        val candidates = listOf(
            File("src/main/java/com/concept/music/ui/components/NowPlayingSheet.kt"),
            File("app/src/main/java/com/concept/music/ui/components/NowPlayingSheet.kt")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("无法定位 NowPlayingSheet.kt 源码文件")
        val content = file.readText(Charsets.UTF_8)

        assertTrue("应包含手机端退出横屏过渡状态保护", content.contains("isExitingLandscapeTransition"))
        assertTrue("标题 AnimatedContent 应配置 SizeTransform(clip = false)", content.contains("SizeTransform(clip = false)"))
        assertTrue(
            "标题跑马灯应随屏幕方向与宽度重建避免横屏退回竖屏后偏移裁剪",
            content.contains("key(configuration.orientation, configuration.screenWidthDp, displayTitle)")
        )

        val headerIdx = content.indexOf("private fun RedesignedTopHeaderBar")
        assertTrue(headerIdx > 0)
        val headerSection = content.substring(headerIdx, (headerIdx + 3600).coerceAtMost(content.length))
        assertFalse(
            "RedesignedTopHeaderBar 的跑马灯 Text 不可再叠加 .fillMaxWidth().basicMarquee() 冲突约束",
            Regex("""\.fillMaxWidth\(\)\s*\.basicMarquee""").containsMatchIn(headerSection)
        )
    }

    // =========================================================================
    // 4. 本地音乐 UI 无框化、局部虚化、首字防裁切、二级过渡、精简浮窗与下载快捷入口回归保护
    // =========================================================================

    private fun readProjectSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/concept/music/$relativePath"),
            File("app/src/main/java/com/concept/music/$relativePath")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("无法定位源码文件: $relativePath")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun testLocalMusicUiFramelessAndAnimationRegressionGuards() {
        val localTabSrc = readProjectSource("ui/library/LocalMusicTabContent.kt")
        val localAndCloudSrc = readProjectSource("ui/library/LocalAndCloudMusicScreen.kt")
        val themeSrc = readProjectSource("ui/theme/Theme.kt")
        val playlistDetailSrc = readProjectSource("ui/library/PlaylistDetailScreen.kt")
        val librarySrc = readProjectSource("ui/library/LibraryScreen.kt")
        val mainActivitySrc = readProjectSource("MainActivity.kt")

        // 1. 严格无框设计：本地音乐与切换顶栏绝不使用 BorderStroke 或 .border(
        assertFalse("LocalMusicTabContent.kt 不可使用 BorderStroke", localTabSrc.contains("BorderStroke"))
        assertFalse("LocalMusicTabContent.kt 不可使用 .border(", localTabSrc.contains(".border("))
        assertFalse("LocalAndCloudMusicScreen.kt 不可使用 BorderStroke", localAndCloudSrc.contains("BorderStroke"))
        assertFalse("LocalAndCloudMusicScreen.kt 不可使用 .border(", localAndCloudSrc.contains(".border("))

        // 2. 删除多余的文件夹快速切换栏
        assertFalse("应彻底删除 FolderFilterTagChip", localTabSrc.contains("FolderFilterTagChip"))
        assertFalse("应彻底删除 local_folder_tags", localTabSrc.contains("local_folder_tags"))
        assertFalse("应彻底删除 localFolderTagId 状态", localAndCloudSrc.contains("localFolderTagId"))

        // 3. 一级 Tag 切换虚化仅作用在歌曲/列表项，不作用于外层容器；并支持二级页平滑过渡
        assertTrue(
            "LocalMusicTabContent.kt 应使用局部 rememberCategorySwitchGraphicModifier",
            localTabSrc.contains("rememberCategorySwitchGraphicModifier(") &&
                localTabSrc.contains("selectedIndex = primaryTab.ordinal") &&
                localTabSrc.contains("applyMotionBlur = true")
        )
        assertFalse(
            "LocalMusicTabContent.kt 外层不可再整页挂载 horizontalCategorySwipe 导致顶卡虚化",
            localTabSrc.contains(".horizontalCategorySwipe(")
        )
        assertTrue(
            "LocalMusicTabContent.kt 应使用 AnimatedContent 为二级钻取页提供过渡动画",
            localTabSrc.contains("AnimatedContent(") && localTabSrc.contains("ConceptMotion.spatialPageSpring()")
        )

        // 4. 歌曲列表项取消每行独立胶囊外框，统一无框沉浸式设计
        val rowCardIdx = localTabSrc.indexOf("private fun LocalTrackRowCard")
        assertTrue("应存在 LocalTrackRowCard 定义", rowCardIdx > 0)
        val rowCardBody = localTabSrc.substring(rowCardIdx, (rowCardIdx + 1200).coerceAtMost(localTabSrc.length))
        assertFalse(
            "LocalTrackRowCard 不可再包裹 RoundedCornerShape(18.dp) 胶囊卡片 Surface",
            rowCardBody.contains("RoundedCornerShape(18.dp)")
        )

        // 5. 本地音乐多选按钮仅在歌曲列表有曲目展示时出现，并支持零云同步创建本地歌单
        assertTrue(
            "多选按钮必须由歌曲列表可见且非空门控",
            localTabSrc.contains("showTrackList && pageDisplayedTracks.isNotEmpty() && !selectionMode")
        )
        assertTrue(
            "本地音乐页应通过 createLocalOnlyPlaylist 创建纯本地歌单",
            localAndCloudSrc.contains("createLocalOnlyPlaylist")
        )

        // 6. 首 Tab 缩放锚点与滚动左侧缓冲防止「全」字左缘被裁切
        assertTrue(
            "ConceptSlidingCategoryTabs 首项应左对齐缩放锚点并保留左侧缓冲防裁切",
            themeSrc.contains("index == 0 -> 0f") &&
                themeSrc.contains("TransformOrigin(pivotX, 0.85f)") &&
                themeSrc.contains(".padding(start = 2.dp, end = 6.dp)")
        )

        // 7. 默认排序显示「默认」且音质/排序浮窗缩窄消除右侧留白
        assertTrue("本地排序按钮默认应展示 sortOption.label（默认）", localTabSrc.contains("label = sortOption.label"))
        assertTrue("音质筛选浮窗应缩窄为 174.dp", localTabSrc.contains("popupWidth = 174.dp"))
        assertTrue("本地排序浮窗应大幅缩窄为 118.dp", localTabSrc.contains("popupWidth = 118.dp"))
        assertTrue(
            "歌单/我喜欢排序浮窗应缩窄消除右侧留白",
            playlistDetailSrc.contains("if (canCustomSortTracks) 168.dp else 148.dp")
        )

        // 8. 我的页右上角搜索左侧动态下载快捷入口及独立下载队列路由
        assertTrue("LibraryScreen 应提供 onOpenDownloadQueue 回调", librarySrc.contains("onOpenDownloadQueue: () -> Unit"))
        assertTrue("LibraryScreen 应包含 DynamicDownloadQueueShortcutButton", librarySrc.contains("private fun DynamicDownloadQueueShortcutButton"))
        assertTrue("动态下载入口动效应受生命周期约束", librarySrc.contains("rememberIsLifecycleStarted()"))
        val shortcutCallIdx = librarySrc.indexOf("DynamicDownloadQueueShortcutButton(")
        val searchIconIdx = librarySrc.indexOf("Icons.Rounded.Search")
        assertTrue("动态下载入口应位于我的页顶栏搜索图标左侧", shortcutCallIdx in 1 until searchIconIdx)
        assertTrue("MainActivity 应注册 download_queue 路由", mainActivitySrc.contains("composable(\"download_queue\")"))
    }

    // =========================================================================
    // 内存最小二进制音频构造器（用于真实字节流单元测试）
    // =========================================================================

    private fun sampleTrack(
        id: String,
        title: String,
        artist: String,
        format: String,
        tier: LocalAudioQualityTier,
        size: Long = 10_000_000L,
        dur: Long = 200_000L,
        order: Long = 1L
    ) = LocalAudioTrack(
        id = id,
        uri = "content://local/$id",
        fileName = "$title.${format.lowercase()}",
        folderId = "f1",
        folderName = "Music",
        title = title,
        artist = artist,
        artists = LocalMusicQueryEngine.splitArtistNames(artist),
        album = "TestAlbum",
        format = format,
        qualityTier = tier,
        fileSizeBytes = size,
        durationMs = dur,
        addedOrder = order
    )

    private fun buildMinimalFlacBytes(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        totalSamples: Long,
        comments: List<String>,
        pictureBytes: ByteArray? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))

        // Block 0: STREAMINFO (34 bytes)
        val streamInfo = ByteArray(34)
        streamInfo[10] = ((sampleRate ushr 12) and 0xFF).toByte()
        streamInfo[11] = ((sampleRate ushr 4) and 0xFF).toByte()
        val bpsMinus1 = (bitDepth - 1) and 0x1F
        val chMinus1 = (channels - 1) and 0x07
        streamInfo[12] = (((sampleRate and 0x0F) shl 4) or (chMinus1 shl 1) or ((bpsMinus1 ushr 4) and 0x01)).toByte()
        streamInfo[13] = (((bpsMinus1 and 0x0F) shl 4) or ((totalSamples ushr 32).toInt() and 0x0F)).toByte()
        streamInfo[14] = ((totalSamples ushr 24) and 0xFF).toByte()
        streamInfo[15] = ((totalSamples ushr 16) and 0xFF).toByte()
        streamInfo[16] = ((totalSamples ushr 8) and 0xFF).toByte()
        streamInfo[17] = (totalSamples and 0xFF).toByte()
        writeFlacBlock(out, blockType = 0, isLast = false, payload = streamInfo)

        // Block 4: VORBIS_COMMENT
        val vc = ByteArrayOutputStream()
        val vendor = "BreezeTest".toByteArray(Charsets.UTF_8)
        writeInt32LE(vc, vendor.size)
        vc.write(vendor)
        writeInt32LE(vc, comments.size)
        for (c in comments) {
            val cb = c.toByteArray(Charsets.UTF_8)
            writeInt32LE(vc, cb.size)
            vc.write(cb)
        }
        writeFlacBlock(out, blockType = 4, isLast = pictureBytes == null, payload = vc.toByteArray())

        // Block 6: PICTURE (optional)
        if (pictureBytes != null) {
            val pic = ByteArrayOutputStream()
            writeInt32BE(pic, 3) // Front cover
            val mime = "image/jpeg".toByteArray(Charsets.ISO_8859_1)
            writeInt32BE(pic, mime.size)
            pic.write(mime)
            writeInt32BE(pic, 0) // description
            writeInt32BE(pic, 100); writeInt32BE(pic, 100); writeInt32BE(pic, 24); writeInt32BE(pic, 0)
            writeInt32BE(pic, pictureBytes.size)
            pic.write(pictureBytes)
            writeFlacBlock(out, blockType = 6, isLast = true, payload = pic.toByteArray())
        }
        return out.toByteArray()
    }

    private fun writeFlacBlock(out: ByteArrayOutputStream, blockType: Int, isLast: Boolean, payload: ByteArray) {
        out.write((if (isLast) 0x80 else 0) or (blockType and 0x7F))
        out.write((payload.size ushr 16) and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload)
    }

    private fun buildMinimalWavBytes(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        durationSec: Int,
        infoTitle: String = "",
        infoArtist: String = "",
        id3Chunk: ByteArray? = null
    ): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray(Charsets.ISO_8859_1))

        val byteRate = sampleRate * channels * (bitDepth / 8)
        val blockAlign = channels * (bitDepth / 8)
        val fmt = ByteArrayOutputStream()
        writeInt16LE(fmt, 1) // PCM
        writeInt16LE(fmt, channels)
        writeInt32LE(fmt, sampleRate)
        writeInt32LE(fmt, byteRate)
        writeInt16LE(fmt, blockAlign)
        writeInt16LE(fmt, bitDepth)
        writeWavChunk(body, "fmt ", fmt.toByteArray())

        if (infoTitle.isNotBlank() || infoArtist.isNotBlank()) {
            val list = ByteArrayOutputStream()
            list.write("INFO".toByteArray(Charsets.ISO_8859_1))
            if (infoTitle.isNotBlank()) writeWavChunk(list, "INAM", infoTitle.toByteArray(Charsets.UTF_8) + byteArrayOf(0))
            if (infoArtist.isNotBlank()) writeWavChunk(list, "IART", infoArtist.toByteArray(Charsets.UTF_8) + byteArrayOf(0))
            writeWavChunk(body, "LIST", list.toByteArray())
        }

        // Fake data chunk size header (skipping real PCM bytes since parser skips data payload!)
        val declaredDataSize = byteRate * durationSec
        // Write a tiny data chunk if id3Chunk follows so stream stays aligned; else test direct byteRate math
        if (id3Chunk != null) {
            // To keep stream small while preserving durationSec = 390, scale via fmt byteRate or write exact header?
            // Let's write a small fake PCM chunk? No, byteRate*durationSec would be huge. Instead we put id3 BEFORE data!
            writeWavChunk(body, "id3 ", id3Chunk)
        }
        body.write("data".toByteArray(Charsets.ISO_8859_1))
        writeInt32LE(body, declaredDataSize)

        val bodyBytes = body.toByteArray()
        val full = ByteArrayOutputStream()
        full.write("RIFF".toByteArray(Charsets.ISO_8859_1))
        writeInt32LE(full, bodyBytes.size)
        full.write(bodyBytes)
        return full.toByteArray()
    }

    private fun writeWavChunk(out: ByteArrayOutputStream, id: String, payload: ByteArray) {
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        writeInt32LE(out, payload.size)
        out.write(payload)
        if ((payload.size and 1) == 1) out.write(0)
    }

    private fun buildMinimalId3v23(
        title: String,
        artist: String,
        album: String,
        lyric: String,
        coverBytes: ByteArray?,
        useGb18030ForArtist: Boolean
    ): ByteArray {
        val frames = ByteArrayOutputStream()
        // TIT2 UTF-16 with LE BOM
        val titlePayload = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + title.toByteArray(Charsets.UTF_16LE)
        writeId3v23Frame(frames, "TIT2", titlePayload)
        // TPE1 either GB18030 under encoding=0 (classic Chinese MP3 quirk!) or UTF-8
        val artistPayload = if (useGb18030ForArtist) {
            byteArrayOf(0) + artist.toByteArray(Charset.forName("GB18030"))
        } else {
            byteArrayOf(3) + artist.toByteArray(Charsets.UTF_8)
        }
        writeId3v23Frame(frames, "TPE1", artistPayload)
        writeId3v23Frame(frames, "TALB", byteArrayOf(3) + album.toByteArray(Charsets.UTF_8))
        if (lyric.isNotBlank()) {
            val uslt = ByteArrayOutputStream()
            uslt.write(3) // UTF-8
            uslt.write("chi".toByteArray(Charsets.ISO_8859_1))
            uslt.write(0) // empty description terminator
            uslt.write(lyric.toByteArray(Charsets.UTF_8))
            writeId3v23Frame(frames, "USLT", uslt.toByteArray())
        }
        if (coverBytes != null) {
            val apic = ByteArrayOutputStream()
            apic.write(0)
            apic.write("image/jpeg".toByteArray(Charsets.ISO_8859_1))
            apic.write(0)
            apic.write(3) // Front cover
            apic.write(0) // empty desc
            apic.write(coverBytes)
            writeId3v23Frame(frames, "APIC", apic.toByteArray())
        }

        val frameBytes = frames.toByteArray()
        val tag = ByteArrayOutputStream()
        tag.write("ID3".toByteArray(Charsets.ISO_8859_1))
        tag.write(3); tag.write(0); tag.write(0)
        // synchsafe size
        val sz = frameBytes.size
        tag.write((sz ushr 21) and 0x7F)
        tag.write((sz ushr 14) and 0x7F)
        tag.write((sz ushr 7) and 0x7F)
        tag.write(sz and 0x7F)
        tag.write(frameBytes)
        return tag.toByteArray()
    }

    private fun writeId3v23Frame(out: ByteArrayOutputStream, id: String, payload: ByteArray) {
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        writeInt32BE(out, payload.size)
        out.write(0); out.write(0)
        out.write(payload)
    }

    private fun buildMinimalM4aBytes(
        isAlac: Boolean,
        sampleRate: Int,
        bitDepth: Int,
        durationSec: Int,
        title: String,
        artist: String,
        album: String,
        lyric: String
    ): ByteArray {
        val ftyp = wrapMp4Atom("ftyp", "M4A \u0000\u0000\u0000\u0000M4A mp42".toByteArray(Charsets.ISO_8859_1))

        // mvhd (version 0): 12B prefix + 4B timescale + 4B duration + padding
        val mvhdBody = ByteArray(28)
        putInt32BE(mvhdBody, 12, 1000)
        putInt32BE(mvhdBody, 16, durationSec * 1000)
        val mvhd = wrapMp4Atom("mvhd", mvhdBody)

        // stsd
        val entryBody = ByteArrayOutputStream()
        entryBody.write(ByteArray(28)) // reserved + sampleSize + sampleRate placeholder
        val entryHeader = entryBody.toByteArray()
        // AudioSampleEntry inside sample entry starts right after 8B size+codec:
        // offset 18 (entry pos + 26) = sampleSize, offset 24 (entry pos + 32) = sampleRate high 16
        entryHeader[18] = ((bitDepth ushr 8) and 0xFF).toByte()
        entryHeader[19] = (bitDepth and 0xFF).toByte()
        val sr16 = sampleRate.coerceAtMost(65535)
        entryHeader[24] = ((sr16 ushr 8) and 0xFF).toByte()
        entryHeader[25] = (sr16 and 0xFF).toByte()

        val codecAtom = if (isAlac) {
            val magicCfg = ByteArray(28) // 4B ver/flags + 24B ALACSpecificConfig
            magicCfg[4 + 5] = bitDepth.toByte()
            putInt32BE(magicCfg, 4 + 20, sampleRate)
            val innerAlac = wrapMp4Atom("alac", magicCfg)
            wrapMp4Atom("alac", entryHeader + innerAlac)
        } else {
            wrapMp4Atom("mp4a", entryHeader)
        }
        val stsdBody = ByteArrayOutputStream().apply {
            writeInt32BE(this, 0) // version/flags
            writeInt32BE(this, 1) // entryCount
            write(codecAtom)
        }.toByteArray()
        val stsd = wrapMp4Atom("stsd", stsdBody)
        val trak = wrapMp4Atom("trak", wrapMp4Atom("mdia", wrapMp4Atom("minf", wrapMp4Atom("stbl", stsd))))

        // ilst
        val ilstBody = ByteArrayOutputStream()
        ilstBody.write(wrapMp4IlstText(0xA9, "nam", title))
        ilstBody.write(wrapMp4IlstText(0xA9, "ART", artist))
        ilstBody.write(wrapMp4IlstText(0xA9, "alb", album))
        if (lyric.isNotBlank()) ilstBody.write(wrapMp4IlstText(0xA9, "lyr", lyric))
        val meta = wrapMp4Atom("meta", ByteArray(4) + wrapMp4Atom("ilst", ilstBody.toByteArray()))
        val udta = wrapMp4Atom("udta", meta)

        val moov = wrapMp4Atom("moov", mvhd + trak + udta)
        return ftyp + moov
    }

    private fun wrapMp4Atom(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeInt32BE(out, 8 + body.size)
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        return out.toByteArray()
    }

    private fun wrapMp4IlstText(b0: Int, type3: String, value: String): ByteArray {
        val dataBody = ByteArrayOutputStream().apply {
            writeInt32BE(this, 1) // UTF-8
            writeInt32BE(this, 0)
            write(value.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        val dataAtom = wrapMp4Atom("data", dataBody)
        val out = ByteArrayOutputStream()
        writeInt32BE(out, 8 + dataAtom.size)
        out.write(b0)
        out.write(type3.toByteArray(Charsets.ISO_8859_1))
        out.write(dataAtom)
        return out.toByteArray()
    }

    private fun writeInt16LE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun writeInt32LE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    private fun writeInt32BE(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun putInt32BE(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = ((v ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (v and 0xFF).toByte()
    }
}
