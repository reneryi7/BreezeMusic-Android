package com.concept.music.data.recommendation

import com.concept.music.data.kugou.LocalBlacklistFilter
import com.concept.music.data.model.BlacklistEntry
import com.concept.music.data.model.Song
import com.concept.music.data.model.UserListeningPreference
import com.concept.music.data.repository.LocalListenStatsRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class RecommendationEngineTest {

    @Before
    fun setUp() {
        LocalBlacklistFilter.clear()
    }

    @After
    fun tearDown() {
        LocalBlacklistFilter.clear()
    }

    @Test
    fun officialSongLangValidationEnforcesAllLanguageFloorAndDjCoverRule() {
        // 1. 6 项语种全为 0 必须被拦截
        val allZeroLang = SongLangTag.languageTags.associate { it.code to 0 }
        val resAllZero = StructuredListeningPreference.validateSongLangWeights(allZeroLang)
        assertFalse("语种不可全部屏蔽", resAllZero.valid)

        // 2. 至少保留 1 项语种 > 0 应通过
        val oneLeft = allZeroLang.toMutableMap().apply { put(SongLangTag.MANDARIN.code, 50) }
        assertTrue(StructuredListeningPreference.validateSongLangWeights(oneLeft).valid)

        // 3. 加大 DJ (100) 且屏蔽翻唱 (0) 必须被拦截
        val djConflict = mapOf(
            SongLangTag.MANDARIN.code to 100,
            SongLangTag.DJ.code to 100,
            SongLangTag.COVER.code to 0
        )
        val resConflict = StructuredListeningPreference.validateSongLangWeights(djConflict)
        assertFalse("加大 DJ 时不可同时屏蔽翻唱", resConflict.valid)

        // 4. 加大 DJ (100) 且翻唱为减少 (25) 允许通过
        val djAllowed = djConflict.toMutableMap().apply { put(SongLangTag.COVER.code, 25) }
        assertTrue(StructuredListeningPreference.validateSongLangWeights(djAllowed).valid)
    }

    @Test
    fun structuredPreferenceRoundTripsOfficialJsonAndAppendsInactiveSingersWithoutOverwriting() {
        val raw = UserListeningPreference(
            mode = 2,
            songLangRaw = """{"L1":100,"L4":0,"N2":25,"X99":75}""",
            styleRaw = """{"1":100,"10":100,"3":50}""",
            inactiveSingerList = "歌手甲,歌手乙"
        )
        val structured = StructuredListeningPreference.fromRaw(raw)
        assertEquals(RecommendationMode.DISCOVERY, structured.mode)
        assertEquals(100, structured.weightFor(SongLangTag.MANDARIN))
        assertEquals(0, structured.weightFor(SongLangTag.KOREAN))
        assertEquals(25, structured.weightFor(SongLangTag.LIVE))
        assertEquals(50, structured.weightFor(SongLangTag.ENGLISH))
        assertEquals(setOf("1", "10"), structured.enabledStyleIds)
        assertEquals(listOf("歌手甲", "歌手乙"), structured.inactiveSingers)

        // 未知云端扩展 key 无损保留
        val songLangJson = structured.toSongLangJson()
        assertTrue(songLangJson.contains("\"X99\":75"))
        assertTrue(songLangJson.contains("\"L4\":0"))

        // 追加新屏蔽歌手不会覆盖旧列表
        val repo = RecommendationPreferenceRepository(context = null)
        repo.activateUser("u1", isLoggedIn = false)
        kotlinx.coroutines.runBlocking {
            repo.addBlockedArtist("u1", "id_a", "歌手甲", null, null, isLoggedIn = false)
            repo.addBlockedArtist("u1", "id_b", "歌手乙", null, null, isLoggedIn = false)
            val prefAfterAdd = repo.currentPreference("u1")
            assertEquals(listOf("歌手甲", "歌手乙"), prefAfterAdd.inactiveSingers)

            // 移除一个后仅剩另一个
            repo.removeBlacklistEntry(
                userId = "u1",
                entry = BlacklistEntry(key = "id_a", name = "歌手甲", label = "singer"),
                musicRepo = null,
                isLoggedIn = false
            )
            assertEquals(listOf("歌手乙"), repo.currentPreference("u1").inactiveSingers)
        }
    }

    @Test
    fun hardBlockedLanguageAndContentNeverLeakIntoAnyModeIncludingDiscovery() {
        val prefRepo = RecommendationPreferenceRepository(null)
        val tasteRepo = PersonalTasteProfileRepository(null)
        val statsRepo = LocalListenStatsRepository(null)
        val tagCache = SongTagMetadataCache(null)
        val engine = PersonalRecommendationEngine()

        // 屏蔽韩语(L4=0)、现场(N2=0)、DJ(S9=0)
        val pref = StructuredListeningPreference(
            mode = RecommendationMode.DISCOVERY,
            songLangWeights = mapOf(
                SongLangTag.MANDARIN.code to 100,
                SongLangTag.KOREAN.code to 0,
                SongLangTag.LIVE.code to 0,
                SongLangTag.DJ.code to 0
            )
        )
        val snapshot = tasteRepo.buildScoringSnapshot(
            userId = "u_hard",
            listenStats = statsRepo,
            likedSongs = emptyList(),
            recentHistory = emptyList(),
            tagCache = tagCache,
            preference = pref
        )

        val candidates = mutableListOf<Pair<Song, PersonalRecommendationEngine.CandidateSource>>()
        val metaMap = HashMap<String, SongTagMeta>()

        // 注入 10 首屏蔽曲目（韩语 / Live / DJ）
        for (i in 1..4) {
            val s = Song(id = "kr_$i", hash = String.format("%032x", 100 + i), title = "韩语歌_$i", artist = "韩团_$i")
            candidates.add(s to PersonalRecommendationEngine.CandidateSource.YOUTH_DISCOVERY)
            metaMap[s.id] = SongTagMeta(languageCode = "L4", isInferredLanguage = false)
        }
        for (i in 1..3) {
            val s = Song(id = "live_$i", hash = String.format("%032x", 200 + i), title = "演唱会现场 (Live)_$i", artist = "现场歌手_$i")
            candidates.add(s to PersonalRecommendationEngine.CandidateSource.PERSONAL_FM)
            metaMap[s.id] = SongTagMeta(languageCode = "L1", isInferredLanguage = false, contentFlags = setOf("N2"))
        }
        for (i in 1..3) {
            val s = Song(id = "dj_$i", hash = String.format("%032x", 300 + i), title = "夜店串烧 (DJ版)_$i", artist = "DJ手_$i")
            candidates.add(s to PersonalRecommendationEngine.CandidateSource.YOUTH_TAG_BLIND_BOX)
            metaMap[s.id] = SongTagMeta(languageCode = "L1", isInferredLanguage = false, contentFlags = setOf("S9"))
        }
        // 注入 24 首正常国语曲目
        for (i in 1..24) {
            val s = Song(id = "cn_$i", hash = String.format("%032x", 400 + i), title = "正常好歌_$i", artist = "独立歌手_$i")
            candidates.add(s to PersonalRecommendationEngine.CandidateSource.YOUTH_DISCOVERY)
            metaMap[s.id] = SongTagMeta(languageCode = "L1", isInferredLanguage = false)
        }

        for (mode in RecommendationMode.entries) {
            val picked = engine.rankAndSelectCandidates(
                candidates = candidates,
                metaResolver = { s -> metaMap[s.id] ?: SongTagMeta(languageCode = "L1") },
                mode = mode,
                size = 20,
                preference = pref.copy(mode = mode),
                preferenceRepo = prefRepo,
                snapshot = snapshot,
                random = Random(42)
            )
            assertEquals("模式 ${mode.label} 应返回完整 20 首", 20, picked.size)
            assertTrue(
                "模式 ${mode.label} 绝不能泄露任何屏蔽韩语/Live/DJ曲目",
                picked.none { it.id.startsWith("kr_") || it.id.startsWith("live_") || it.id.startsWith("dj_") }
            )
        }
    }

    @Test
    fun discoveryModeLimitsReducedLanguageProbeAndConvergesAfterQuickSkips() {
        val prefRepo = RecommendationPreferenceRepository(null)
        val tasteRepo = PersonalTasteProfileRepository(null)
        val statsRepo = LocalListenStatsRepository(null)
        val tagCache = SongTagMetadataCache(null)
        val engine = PersonalRecommendationEngine()

        // 英语手动降低为 25（1..49 软降权），国语默认 50
        val pref = StructuredListeningPreference(
            mode = RecommendationMode.DISCOVERY,
            songLangWeights = mapOf(
                SongLangTag.MANDARIN.code to 50,
                SongLangTag.ENGLISH.code to 25
            )
        )

        val candidates = mutableListOf<Pair<Song, PersonalRecommendationEngine.CandidateSource>>()
        val metaMap = HashMap<String, SongTagMeta>()
        for (i in 1..22) {
            val s = Song(id = "cn_$i", hash = String.format("%032x", 1000 + i), title = "华语探索_$i", artist = "陌生歌手_$i")
            candidates.add(s to PersonalRecommendationEngine.CandidateSource.YOUTH_DISCOVERY)
            metaMap[s.id] = SongTagMeta(languageCode = "L1", isInferredLanguage = false)
        }
        for (i in 1..8) {
            val s = Song(id = "en_$i", hash = String.format("%032x", 2000 + i), title = "English Track $i", artist = "Indie Western $i")
            candidates.add(s to PersonalRecommendationEngine.CandidateSource.YOUTH_TAG_BLIND_BOX)
            metaMap[s.id] = SongTagMeta(languageCode = "L2", isInferredLanguage = false)
        }

        // 1. 初始状态（0 次拒绝）：平衡/熟悉模式 0 首降权探针；尝鲜模式最多 2 首（<= 15%）
        val initSnap = tasteRepo.buildScoringSnapshot("u_probe", statsRepo, emptyList(), emptyList(), tagCache, pref)
        val balancedPicked = engine.rankAndSelectCandidates(
            candidates, { metaMap[it.id]!! }, RecommendationMode.BALANCED, 20, pref.copy(mode = RecommendationMode.BALANCED), prefRepo, initSnap, random = Random(7)
        )
        assertEquals("平衡模式不应主动推出手动降权语种", 0, balancedPicked.count { it.id.startsWith("en_") })

        val discoveryPicked = engine.rankAndSelectCandidates(
            candidates, { metaMap[it.id]!! }, RecommendationMode.DISCOVERY, 20, pref, prefRepo, initSnap, random = Random(7)
        )
        val probeCount = discoveryPicked.count { it.id.startsWith("en_") }
        assertTrue("尝鲜模式初始应允许 1~2 首受控跨语种探针，实际=$probeCount", probeCount in 1..2)
        // 验证尝鲜模式单批无重复歌手
        assertEquals(discoveryPicked.size, discoveryPicked.map { it.artist }.distinct().size)

        // 2. 连续 3 次在猜你喜欢中秒切（<15s）英语探针 -> 探针自动收敛至 0
        for (k in 1..3) {
            val skippedSong = Song(id = "skip_en_$k", hash = String.format("%032x", 3000 + k), title = "Skip $k", artist = "Skip Artist $k", durationMs = 210_000L)
            tasteRepo.recordListenSignal(
                userId = "u_probe",
                song = skippedSong,
                meta = SongTagMeta(languageCode = "L2", isInferredLanguage = false),
                playedMs = 6_000L,
                durationMs = 210_000L,
                isFromPersonalFm = true,
                isNaturalComplete = false,
                isRepeat = false,
                isManualSkip = true,
                isReducedLanguageProbe = true
            )
        }
        val convergedSnap = tasteRepo.buildScoringSnapshot("u_probe", statsRepo, emptyList(), emptyList(), tagCache, pref)
        assertTrue("画像摘要应展示英语探针已自动收紧", convergedSnap.summary.tightenedProbeLanguages.contains("英语"))
        val convergedDiscovery = engine.rankAndSelectCandidates(
            candidates, { metaMap[it.id]!! }, RecommendationMode.DISCOVERY, 20, pref, prefRepo, convergedSnap, random = Random(7)
        )
        assertEquals("连续 3 次秒切后，该降权语种探针应自动收敛为 0", 0, convergedDiscovery.count { it.id.startsWith("en_") })

        // 3. 重置本地隐式权重后，探针收敛计数清零
        tasteRepo.resetImplicitWeights("u_probe")
        val afterResetSnap = tasteRepo.buildScoringSnapshot("u_probe", statsRepo, emptyList(), emptyList(), tagCache, pref)
        assertTrue(afterResetSnap.summary.tightenedProbeLanguages.isEmpty())
        assertEquals(0, afterResetSnap.summary.quickSkipCount)
    }

    @Test
    fun familiarBalancedDiscoveryRespectFamiliarRediscoveryGradientAndNonFmSkipsDoNotPenalize() {
        val prefRepo = RecommendationPreferenceRepository(null)
        val tasteRepo = PersonalTasteProfileRepository(null)
        val statsRepo = LocalListenStatsRepository(null)
        val tagCache = SongTagMetadataCache(null)
        val engine = PersonalRecommendationEngine()

        val nowMs = 1_800_000_000_000L
        val oldMs = nowMs - 20L * PersonalTasteProfileRepository.DAY_MS

        // 构造 10 首 20 天前反复听过的久违红心熟歌
        val likedSongs = (1..10).map { i ->
            val s = Song(id = "fam_$i", hash = String.format("%032x", 5000 + i), title = "久违老歌_$i", artist = "老歌手_$i", durationMs = 200_000L)
            statsRepo.recordPlay("u_grad", s, playedMs = 190_000L, nowMs = oldMs)
            statsRepo.recordPlay("u_grad", s, playedMs = 190_000L, nowMs = oldMs + 3600_000L)
            s
        }

        // 在非 FM（例如排行榜试听）连续秒切某歌手 5 次：不应产生 FM 秒切惩罚
        for (i in 1..5) {
            tasteRepo.recordListenSignal(
                userId = "u_grad",
                song = Song(id = "chart_$i", hash = String.format("%032x", 6000 + i), title = "榜单试听_$i", artist = "无辜歌手", durationMs = 200_000L),
                meta = SongTagMeta(languageCode = "L1"),
                playedMs = 4_000L,
                durationMs = 200_000L,
                isFromPersonalFm = false,
                isNaturalComplete = false,
                isRepeat = false,
                isManualSkip = true,
                nowMs = nowMs
            )
        }

        val pref = StructuredListeningPreference()
        val snap = tasteRepo.buildScoringSnapshot("u_grad", statsRepo, likedSongs, emptyList(), tagCache, pref, nowMs = nowMs)
        assertEquals("非 FM 队列秒切绝不能记入 FM 秒切计数", 0, snap.summary.quickSkipCount)
        assertFalse("非 FM 队列秒切绝不能惩罚该歌手", snap.artistPenalty.containsKey("无辜歌手"))

        val candidates = mutableListOf<Pair<Song, PersonalRecommendationEngine.CandidateSource>>()
        likedSongs.forEach { candidates.add(it to PersonalRecommendationEngine.CandidateSource.FAMILIAR_REDISCOVERY) }
        for (i in 1..25) {
            val s = Song(id = "new_$i", hash = String.format("%032x", 7000 + i), title = "新发现_$i", artist = "新歌手_$i")
            candidates.add(s to PersonalRecommendationEngine.CandidateSource.AI_CF_SEED)
        }

        val famOut = engine.rankAndSelectCandidates(candidates, { SongTagMeta(languageCode = "L1") }, RecommendationMode.FAMILIAR, 20, pref, prefRepo, snap, random = Random(1))
        val balOut = engine.rankAndSelectCandidates(candidates, { SongTagMeta(languageCode = "L1") }, RecommendationMode.BALANCED, 20, pref, prefRepo, snap, random = Random(1))
        val disOut = engine.rankAndSelectCandidates(candidates, { SongTagMeta(languageCode = "L1") }, RecommendationMode.DISCOVERY, 20, pref, prefRepo, snap, random = Random(1))

        val famCount = famOut.count { it.id.startsWith("fam_") }
        val balCount = balOut.count { it.id.startsWith("fam_") }
        val disCount = disOut.count { it.id.startsWith("fam_") }

        assertTrue("熟悉模式应含 30%~40%（6~8 首）久违熟歌，实际=$famCount", famCount in 6..8)
        assertTrue("平衡模式应含约 15%~20%（3~4 首）久违熟歌，实际=$balCount", balCount in 3..4)
        assertEquals("尝鲜模式应 0% 久违熟歌", 0, disCount)
    }

    @Test
    fun timeDecayFormulaAndCardRenovationSourceInvariantsHold() {
        val w0 = PersonalTasteProfileRepository.computeTimeDecayWeight(0L)
        val w45 = PersonalTasteProfileRepository.computeTimeDecayWeight(45L * PersonalTasteProfileRepository.DAY_MS)
        val w365 = PersonalTasteProfileRepository.computeTimeDecayWeight(365L * PersonalTasteProfileRepository.DAY_MS)
        assertEquals(1.0, w0, 1e-4)
        assertEquals(0.625, w45, 1e-4)
        assertTrue("长期底色下限应维持在 0.25 以上，实际=$w365", w365 >= 0.25 && w365 < 0.28)

        // 探针衰减校验
        assertEquals(1.0, PersonalTasteProfileRepository.computeProbeMultiplier(0), 1e-6)
        assertEquals(0.45, PersonalTasteProfileRepository.computeProbeMultiplier(1), 1e-6)
        assertEquals(0.2025, PersonalTasteProfileRepository.computeProbeMultiplier(2), 1e-6)

        // 源码架构校验：卡片翻新与设置偏好页严格无边框且移除 Sync 循环小图标
        val projectRoot = java.io.File(".").canonicalFile.let { dir ->
            if (java.io.File(dir, "app/src/main").exists()) dir else dir.parentFile
        }
        val homeCode = java.io.File(projectRoot, "app/src/main/java/com/concept/music/ui/home/HomeScreen.kt").readText(Charsets.UTF_8)
        assertTrue("HomeScreen 应包含迁移自 Windows 的黑胶封套组件 FmSleeveVinylStage", homeCode.contains("private fun FmSleeveVinylStage"))
        assertTrue("HomeScreen 应包含样式 C 极简三模式微点切换器", homeCode.contains("private fun PersonalFmModeMinimalPill"))
        assertFalse("猜你喜欢三模式切换按钮不得再包含 Sync 循环图标", homeCode.contains("Icons.Rounded.Sync"))

        val prefScreenCode = java.io.File(projectRoot, "app/src/main/java/com/concept/music/ui/settings/SettingsRecommendationPreferenceScreen.kt").readText(Charsets.UTF_8)
        assertFalse("推荐偏好页严禁使用带边框的 FilterChip", prefScreenCode.contains("FilterChip("))
        assertFalse("推荐偏好页严禁使用 OutlinedButton", prefScreenCode.contains("OutlinedButton("))
        assertFalse("推荐偏好页严禁使用 .border(", prefScreenCode.contains(".border("))
    }

    @Test
    fun dailyRecommendHardBlockFilterDropsBlockedTracksAndPreservesAllowed() = kotlinx.coroutines.runBlocking {
        val prefRepo = RecommendationPreferenceRepository(null)
        val tagCache = SongTagMetadataCache(null)
        val engine = PersonalRecommendationEngine()

        // 屏蔽 DJ (S9=0)、现场 (N2=0)，英语仅降权 (25，不属于硬屏蔽)
        prefRepo.activateUser("u_daily", isLoggedIn = false)
        prefRepo.updateSongLangWeight("u_daily", SongLangTag.DJ, 0, musicRepo = null, isLoggedIn = false)
        prefRepo.updateSongLangWeight("u_daily", SongLangTag.LIVE, 0, musicRepo = null, isLoggedIn = false)
        prefRepo.updateSongLangWeight("u_daily", SongLangTag.ENGLISH, 25, musicRepo = null, isLoggedIn = false)
        prefRepo.addBlockedArtist("u_daily", "artist_blk", "拉黑歌手", musicRepo = null, sampleSong = null, isLoggedIn = false)

        val goodMandarin = Song(id = "d_cn", hash = String.format("%032x", 9001), title = "晴天", artist = "周杰伦")
        val reducedEnglish = Song(id = "d_en", hash = String.format("%032x", 9002), title = "Yellow", artist = "Coldplay")
        val blockedDj = Song(id = "d_dj", hash = String.format("%032x", 9003), title = "夜店狂欢 (DJ版)", artist = "某DJ")
        val blockedLive = Song(id = "d_live", hash = String.format("%032x", 9004), title = "演唱会 (Live)", artist = "某歌手")
        val blockedArtistSong = Song(id = "d_blk", hash = String.format("%032x", 9005), title = "普通歌名", artist = "拉黑歌手")

        val filtered = engine.filterDailyRecommendHardBlocked(
            songs = listOf(goodMandarin, reducedEnglish, blockedDj, blockedLive, blockedArtistSong),
            userId = "u_daily",
            musicRepo = null,
            preferenceRepo = prefRepo,
            tagCache = tagCache
        )

        assertEquals(listOf("d_cn", "d_en"), filtered.map { it.id })

        // 极端全被屏蔽时保留非空兜底，防止今日日推空白崩溃
        val fallback = engine.filterDailyRecommendHardBlocked(
            songs = listOf(blockedDj, blockedLive),
            userId = "u_daily",
            musicRepo = null,
            preferenceRepo = prefRepo,
            tagCache = tagCache
        )
        assertEquals(2, fallback.size)
    }
}
