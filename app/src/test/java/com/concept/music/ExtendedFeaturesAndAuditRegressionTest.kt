package com.concept.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import com.concept.music.data.kugou.*
import com.concept.music.data.model.*
import com.concept.music.service.AmbientSoundManager
import com.concept.music.service.AppPermissionManager
import com.concept.music.service.AppPermissionType
import com.concept.music.service.AudioCacheManager
import com.concept.music.service.AudioEffectManager
import com.concept.music.service.ConceptMasteringAudioProcessor
import com.concept.music.service.EffectPreset
import com.concept.music.service.FocusPodManager
import com.concept.music.service.PeqFilterType
import com.concept.music.service.PlaybackService
import com.concept.music.service.ReverbMode
import com.concept.music.service.SystemLyricBridge
import com.concept.music.ui.components.resolveLandscapeLyricAlignmentLayout
import com.concept.music.ui.viewmodel.LyricAlignment
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExtendedFeaturesAndAuditRegressionTest {

    private fun loggedInRepo(
        handler: (path: String, query: Map<String, String>, body: JsonObject?, host: String, method: String) -> ApiResponse = { _, _, _, _, _ ->
            ApiResponse(JsonObject())
        },
        binaryHandler: (path: String, query: Map<String, String>, rawBodyBytes: ByteArray, headers: Map<String, String>) -> ByteArray = { _, _, _, _ ->
            """{"status":1,"errcode":0}""".toByteArray(Charsets.UTF_8)
        }
    ): Pair<MusicApiRepository, MutableList<String>> {
        val calledPaths = mutableListOf<String>()
        val store = MemoryStore()
        val session = KugouSession(userId = "10086", token = "mock_token_xyz_123", t1 = "mock_t1")
        val profile = AccountProfile(userId = "10086", nickname = "微风测试号")
        store.saveAccounts(listOf(SavedAccount(userId = session.userId, nickname = profile.nickname)))
        store.saveSession(session)
        store.setActive(session.userId)

        val transport = object : KugouTransport {
            override suspend fun call(
                path: String,
                session: KugouSession?,
                query: Map<String, String>,
                body: JsonObject?,
                host: String,
                method: String,
                web: Boolean,
                headers: Map<String, String>,
                device: DeviceIdentity?
            ): ApiResponse {
                calledPaths.add(path)
                return handler(path, query, body, host, method)
            }

            override suspend fun callAdvanced(
                path: String,
                session: KugouSession?,
                query: Map<String, String>,
                body: JsonObject?,
                host: String,
                method: String,
                web: Boolean,
                headers: Map<String, String>,
                device: DeviceIdentity?,
                sign: Boolean,
                includeDefaultParams: Boolean
            ): ApiResponse {
                calledPaths.add(path)
                return handler(path, query, body, host, method)
            }

            override suspend fun requestBinaryBytes(
                path: String,
                session: KugouSession?,
                query: Map<String, String>,
                rawBodyBytes: ByteArray?,
                host: String,
                method: String,
                headers: Map<String, String>,
                sign: Boolean,
                includeDefaultParams: Boolean,
                device: DeviceIdentity?
            ): ByteArray {
                calledPaths.add(path)
                return binaryHandler(path, query, rawBodyBytes ?: ByteArray(0), headers)
            }
        }
        val auth = AuthRepository(transport, store)
        val accounts = AccountRepository(store, auth, transport)
        return MusicApiRepository(transport, accounts, store::device) to calledPaths
    }

    // ==================== F1 核心 BUG：横屏播放页歌词对齐解析 ====================

    @Test
    fun testLandscapeLyricAlignmentLayoutPhoneAndTablet() {
        val phoneLeft = resolveLandscapeLyricAlignmentLayout(LyricAlignment.LEFT, isTabletLandscape = false)
        assertEquals(Alignment.Start, phoneLeft.columnAlignment)
        assertEquals(TextAlign.Start, phoneLeft.textAlign)
        assertEquals(Arrangement.Start, phoneLeft.flowArrangement)
        assertEquals(0f, phoneLeft.transformOriginX, 0.001f)
        assertTrue(phoneLeft.listEndPaddingDp > phoneLeft.listStartPaddingDp)

        val phoneCenter = resolveLandscapeLyricAlignmentLayout(LyricAlignment.CENTER, isTabletLandscape = false)
        assertEquals(Alignment.CenterHorizontally, phoneCenter.columnAlignment)
        assertEquals(TextAlign.Center, phoneCenter.textAlign)
        assertEquals(Arrangement.Center, phoneCenter.flowArrangement)
        assertEquals(0.5f, phoneCenter.transformOriginX, 0.001f)
        assertEquals(phoneCenter.listStartPaddingDp, phoneCenter.listEndPaddingDp, 0.001f)

        val phoneRight = resolveLandscapeLyricAlignmentLayout(LyricAlignment.RIGHT, isTabletLandscape = false)
        assertEquals(Alignment.End, phoneRight.columnAlignment)
        assertEquals(TextAlign.End, phoneRight.textAlign)
        assertEquals(Arrangement.End, phoneRight.flowArrangement)
        assertEquals(1f, phoneRight.transformOriginX, 0.001f)
        assertTrue("居右时右侧应为快捷栏留白避免遮挡", phoneRight.listEndPaddingDp >= 40f)

        val tabletCenter = resolveLandscapeLyricAlignmentLayout(LyricAlignment.CENTER, isTabletLandscape = true)
        assertEquals(Alignment.CenterHorizontally, tabletCenter.columnAlignment)
        assertEquals(tabletCenter.listStartPaddingDp, tabletCenter.listEndPaddingDp, 0.001f)

        val tabletRight = resolveLandscapeLyricAlignmentLayout(LyricAlignment.RIGHT, isTabletLandscape = true)
        assertEquals(Alignment.End, tabletRight.columnAlignment)
        assertTrue(tabletRight.listEndPaddingDp >= 48f)
    }

    // ==================== F4 / F16：DSP 解耦、PRESET 记忆、独立 PEQ 重置与 BAND_PASS ====================

    @Test
    fun testAudioEffectManagerTogglePreservesCustomAndResetPeqOnlyPreservesSoundstage() {
        val manager = AudioEffectManager
        try {
            manager.setPreset(EffectPreset.OFF)
            manager.setBassBoostStrength(640)
            manager.setVirtualizerStrength(520)
            manager.setReverbMode(ReverbMode.MEDIUM_HALL)
            manager.setTenBandLevel(2, 500)
            manager.setHighPassCutoffHz(45)
            manager.setLowPassCutoffHz(16000)
            manager.setPreampGainDb(3.0f)

            assertEquals(EffectPreset.CUSTOM, manager.state.value.currentPreset)
            assertTrue(manager.state.value.isEnabled)

            // 关闭总开关再重新开启，应无损恢复 CUSTOM 与自定义参数（不再重置为 VIPER_3D）
            manager.setEnabled(false)
            assertFalse(manager.state.value.isEnabled)
            manager.setEnabled(true)
            assertTrue(manager.state.value.isEnabled)
            assertEquals(EffectPreset.CUSTOM, manager.state.value.currentPreset)
            assertEquals(500, manager.state.value.tenBandLevels[2])
            assertEquals(640, manager.state.value.bassBoostStrength)

            // 仅重置 PEQ，声场参数（重低音 / 环绕 / 混响）必须完整保留
            manager.resetPeqOnly()
            assertEquals(0, manager.state.value.tenBandLevels[2])
            assertEquals(0, manager.state.value.highPassCutoffHz)
            assertEquals(20000, manager.state.value.lowPassCutoffHz)
            assertEquals(0f, manager.state.value.preampGainDb, 0.01f)
            assertEquals(640, manager.state.value.bassBoostStrength)
            assertEquals(520, manager.state.value.virtualizerStrength)
            assertEquals(ReverbMode.MEDIUM_HALL, manager.state.value.reverbMode)

            // BAND_PASS 滤波器在中心频点应呈现正向聚焦增益，LPF < 19500 应产生高频衰减
            manager.setTenBandFilterType(5, PeqFilterType.BAND_PASS)
            manager.setTenBandLevel(5, 500)
            val magAt1k = manager.evaluatePeqMagnitudeDbAtFrequency(1000.0, manager.state.value, 48000.0)
            assertTrue("BAND_PASS +5dB 在 1kHz 应呈现正向增益: $magAt1k", magAt1k > 2.0f)

            manager.resetPeqOnly()
            manager.setLowPassCutoffHz(12000)
            val magAt18k = manager.evaluatePeqMagnitudeDbAtFrequency(18000.0, manager.state.value, 48000.0)
            assertTrue("LPF 12kHz 在 18kHz 处应明显衰减: $magAt18k", magAt18k < -4.0f)
        } finally {
            manager.resetCurrentPresetToDefault()
            manager.setEnabled(false)
            manager.setEqualLoudnessCompensation(false)
            manager.setVolumeNormalizationEnabled(false)
        }
    }

    @Test
    fun testStandaloneEqualLoudnessAndVolumeNormWorkWhenViperDisabled() {
        val manager = AudioEffectManager
        try {
            manager.setPreset(EffectPreset.OFF)
            manager.setEqualLoudnessCompensation(false)
            manager.setVolumeNormalizationEnabled(false)
            assertFalse(manager.state.value.isEnabled)

            // 开启夜间等响度补偿后，即便蝰蛇总开关 OFF，DSP 也应对极低频正弦波产生非直通增益提升
            manager.setEqualLoudnessCompensation(true)
            assertFalse("等响度补偿不应强制打开蝰蛇总开关", manager.state.value.isEnabled)
            assertTrue(manager.state.value.equalLoudnessCompensation)

            val processor = ConceptMasteringAudioProcessor()
            processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
            processor.flush()

            val frames = 1024
            val input = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            var inputRmsSq = 0.0
            for (i in 0 until frames) {
                val s = (kotlin.math.sin(2.0 * kotlin.math.PI * 55.0 * i / 44100.0) * 6000.0).toInt().toShort()
                input.putShort(s)
                input.putShort(s)
                inputRmsSq += s.toDouble() * s.toDouble()
            }
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            var outputRmsSq = 0.0
            for (i in 0 until frames) {
                val l = output.short.toDouble()
                output.short
                outputRmsSq += l * l
            }
            assertTrue(
                "蝰蛇关闭但开启等响度补偿时，55Hz 低频输出能量应高于输入直通能量",
                outputRmsSq > inputRmsSq * 1.15
            )
        } finally {
            manager.setEqualLoudnessCompensation(false)
            manager.setVolumeNormalizationEnabled(false)
            manager.setEnabled(false)
        }
    }

    // ==================== F14 / F17：歌词偏移同步 & 权限默认恢复 ====================

    @Test
    fun testSystemLyricBridgeOffsetSyncAndRestoreDefaultCorePermissions() {
        try {
            SystemLyricBridge.lyricOffsetMsSnapshot = -350L
            SystemLyricBridge.syncProgressFromService(positionMs = 1000L, isPlaying = true)
            assertEquals(650L, SystemLyricBridge.currentPositionMsSnapshot)
        } finally {
            SystemLyricBridge.lyricOffsetMsSnapshot = 0L
        }

        // 关闭几个核心权限并开启下载权限后，验证 restoreDefaultCorePermissions 恢复 8 项核心权限且不强开下载
        AppPermissionManager.setPermissionEnabled(null, AppPermissionType.NOTIFICATION_MEDIA, false)
        AppPermissionManager.setPermissionEnabled(null, AppPermissionType.AUDIO_HARDWARE, false)
        AppPermissionManager.setPermissionEnabled(null, AppPermissionType.MUSIC_DOWNLOAD, false)
        AppPermissionManager.restoreDefaultCorePermissions(null)

        val st = AppPermissionManager.state.value
        assertTrue(st.notificationMediaEnabled)
        assertTrue(st.audioHardwareEnabled)
        assertTrue(st.backgroundGuardianEnabled)
        assertFalse("恢复默认核心权限绝不可自动打开音乐下载开关", st.musicDownloadEnabled)
    }

    // ==================== KugouSigner 新签名与加密协议 ====================

    @Test
    fun testKugouSignerCloudKeyRsaNoPadAndPlaylistAesRoundTrip() {
        val cloudKey = KugouSigner.signCloudKey("ABCDEF1234567890", 20026)
        assertEquals(32, cloudKey.length)
        assertTrue(cloudKey.all { it in '0'..'9' || it in 'a'..'f' })

        val tokenHex = KugouSigner.rsaNoPadLiteToken("sample_session_token_987654")
        assertTrue("h5 前缀 + 无填充 RSA-1024 hex 输出应合法", tokenHex.startsWith("h5") && tokenHex.length == 258)

        val rawJson = """{"listid":123,"name":"微风歌单","intro":"轻盈纯净"}"""
        val (aesKey, cipherBytes) = KugouSigner.playlistAesEncryptBytes(rawJson)
        assertEquals(6, aesKey.length)
        assertTrue(cipherBytes.isNotEmpty())
        val decrypted = KugouSigner.playlistAesDecrypt(cipherBytes, aesKey)
        assertEquals(rawJson, decrypted)
    }

    // ==================== Tier 1 (#1-#6) 扩展协议与新版交互边界单元测试 ====================

    @Test
    fun testTier1SameSingerSearchVersionsPlaylistEditSortLyricOriginalPriorityDailyWeekFollowAndComments() = runTest {
        val (repo, paths) = loggedInRepo(
            handler = { path, query, _, _, _ ->
                when (path) {
                    "/v3/album_audio/related" -> ApiResponse(
                        JsonParser.parseString(
                            """{
                                "status":1,
                                "data":{
                                    "lists":[
                                        {"hash":"aaaa1111aaaa1111aaaa1111aaaa1111","songname":"晴天 (Live)","author_name":"周杰伦","album_audio_id":55501,"duration":269},
                                        {"hash":"aaaa2222aaaa2222aaaa2222aaaa2222","songname":"晴天 (翻唱)","author_name":"路人歌手","album_audio_id":55502,"duration":269},
                                        {"hash":"aaaa3333aaaa3333aaaa3333aaaa3333","songname":"晴天 (伴奏)","author_name":"周杰伦","album_audio_id":55503,"duration":269},
                                        {"hash":"aaaa4444aaaa4444aaaa4444aaaa4444","songname":"晴天 (重制版)","author_name":"周杰伦","album_name":"叶惠美纪念盘","album_audio_id":55504,"duration":269}
                                    ]
                                }
                            }""".trimIndent()
                        ).asJsonObject
                    )
                    "/v4/get_list_all_file_v3" -> ApiResponse(
                        JsonParser.parseString(
                            """{
                                "status":1,
                                "data":{
                                    "list_ver":18,
                                    "count":2,
                                    "info":[
                                        {"fileid":9101,"hash":"h1h1h1h1h1h1h1h1h1h1h1h1h1h1h1h1","filename":"A - 曲1"},
                                        {"fileid":9102,"hash":"h2h2h2h2h2h2h2h2h2h2h2h2h2h2h2h2","filename":"B - 曲2"}
                                    ]
                                }
                            }""".trimIndent()
                        ).asJsonObject
                    )
                    "/v1/search/lyric" -> ApiResponse(
                        JsonParser.parseString(
                            """{
                                "status":1,
                                "data":{
                                    "total":3,
                                    "lists":[
                                        {"FileHash":"11112222111122221111222211112222","SongName":"稻香 (翻唱版)","SingerName":"翻唱达人","lyrics":"<em>还记得你说家是唯一的城堡</em>","Duration":223},
                                        {"FileHash":"33334444333344443333444433334444","SongName":"稻香 (Live)","SingerName":"周杰伦","lyrics":"<em>还记得你说家是唯一的城堡</em>","Duration":225},
                                        {"FileHash":"bbbb2222bbbb2222bbbb2222bbbb2222","SongName":"稻香","SingerName":"周杰伦","AlbumName":"魔杰座","is_original":1,"lyrics":"<em>还记得你说家是唯一的城堡</em>","Duration":223}
                                    ]
                                }
                            }""".trimIndent()
                        ).asJsonObject
                    )
                    "/everyday/api/v1/get_history" -> {
                        if (query["mode"] == "list") {
                            ApiResponse(
                                JsonParser.parseString(
                                    """{"status":1,"data":{"lists":[{"history_name":"RT_336d5ebc5436534e61d16e63ddfca327_20261123","date":"20261123","title":"昨天"}]}}"""
                                ).asJsonObject
                            )
                        } else {
                            // 严格校验 mode=song 传入服务端真实 history_name 与 8 位紧凑日期 yyyyMMdd
                            if (query["history_name"] == "RT_336d5ebc5436534e61d16e63ddfca327_20261123" && query["date"] == "20261123") {
                                ApiResponse(
                                    JsonParser.parseString(
                                        """{"status":1,"data":{"song_list":[{"hash":"cccc3333cccc3333cccc3333cccc3333","songname":"七里香","author_name":"周杰伦","time_length":299}]}}"""
                                    ).asJsonObject
                                )
                            } else {
                                ApiResponse(JsonParser.parseString("""{"status":1,"data":{"song_list":[]}}""").asJsonObject)
                            }
                        }
                    }
                    "/m.comment.service/v1/cmtlist" -> ApiResponse(
                        JsonParser.parseString(
                            """{
                                "status":1,
                                "count":18,
                                "data":{
                                    "childrenid":"776655",
                                    "childrenname":"微风歌单",
                                    "list":[{"id":"cmt_99","user_id":"10086","user_name":"微风测试号","content":"歌单太棒了","special_child_id":"776655","addtime":"2026-11-22 10:00:00"}]
                                }
                            }""".trimIndent()
                        ).asJsonObject
                    )
                    "/index.php" -> ApiResponse(
                        JsonParser.parseString("""{"status":1,"err_code":0,"data":{"id":"cmt_100"}}""").asJsonObject
                    )
                    else -> ApiResponse(JsonParser.parseString("""{"status":1,"data":{}}""").asJsonObject)
                }
            }
        )

        // #1 搜索专属同歌手同曲多版本（严格排除 Live 与他人翻唱，保留伴奏与录音室其他版本）
        val primarySong = Song(id = "s1", hash = "h0", title = "晴天", artist = "周杰伦", album = "叶惠美", albumAudioId = 12345L)
        val versions = repo.fetchSameSingerSongVersionsForSearch(primarySong)
        assertEquals("应仅保留同歌手伴奏版与重制版，剔除 Live 与他人翻唱", 2, versions.size)
        assertTrue(versions.none { it.title.contains("Live", ignoreCase = true) || it.artist == "路人歌手" })
        assertEquals(setOf("伴奏", "重制版"), versions.map { it.versionTag }.toSet())

        // 验证搜索结果行内分组：伴奏排在前面时也能自动提拔人声原版做主行，Live 与翻唱保持独立不混入
        val rawSearchSongs = listOf(
            Song(id = "v_acc", hash = "h_acc", title = "泡沫 (伴奏)", artist = "G.E.M.邓紫棋", album = "Xposed"),
            Song(id = "v_orig", hash = "h_orig", title = "泡沫", artist = "邓紫棋", album = "Xposed"),
            Song(id = "v_live", hash = "h_live", title = "泡沫 (Live)", artist = "G.E.M.邓紫棋", album = "演唱会"),
            Song(id = "v_cov", hash = "h_cov", title = "泡沫", artist = "其他翻唱歌手", album = "翻唱集")
        )
        val grouped = repo.groupSearchSongsWithInlineVersions(rawSearchSongs).map { it.toGroup() }
        assertEquals(3, grouped.size)
        assertEquals("泡沫", grouped[0].primary.title)
        assertEquals(1, grouped[0].versions.size)
        assertEquals("泡沫 (伴奏)", grouped[0].versions.first().title)
        assertTrue(grouped[1].versions.isEmpty())
        assertTrue(grouped[2].versions.isEmpty())

        // #2 自建歌单 GID 解析 + 信息编辑 + 无 fileId 时自动回查 /v4/get_list_all_file_v3 完成云端排序
        val gidOnlyPl = Playlist(id = "collection_3_10086_8899_0", title = "原歌单", listId = 0L, isCreatedByMe = true)
        assertEquals(8899L, gidOnlyPl.resolveEffectiveListId())
        val testPl2 = Playlist(id = "15", title = "歌单2", listId = 15L, isCreatedByMe = true)
        val trackWithoutFid1 = Song(id = "t1", hash = "h1h1h1h1h1h1h1h1h1h1h1h1h1h1h1h1", title = "曲1", artist = "A", fileId = 0L)
        val trackWithoutFid2 = Song(id = "t2", hash = "h2h2h2h2h2h2h2h2h2h2h2h2h2h2h2h2", title = "曲2", artist = "B", fileId = 0L)
        assertTrue(repo.updatePlaylistInfo(playlist = gidOnlyPl, newName = "微风精选", newIntro = "夜间纯音乐"))
        assertTrue(repo.sortPlaylistTracks(playlist = gidOnlyPl, orderedSongs = listOf(trackWithoutFid2, trackWithoutFid1)))
        assertTrue(repo.sortUserPlaylists(orderedPlaylists = listOf(testPl2, gidOnlyPl), isCreatedByMe = true))
        assertTrue(paths.contains("/v1/modify_list"))
        assertTrue(paths.contains("/v4/get_list_all_file_v3"))
        assertTrue(paths.contains("/v1/modify_song_sort"))
        assertTrue(paths.contains("/v1/modify_list_sort"))

        // #3 歌词搜歌（支持小写 lyrics 字段正文提取 + 原唱优先重排超越翻唱与 Live + 已移除独立歌词结果 Tab）
        val lyricRes = repo.searchByLyric("家是唯一的城堡")
        assertEquals(3, lyricRes.songs.size)
        assertEquals("原唱录音室版必须优先排在第一位", "稻香", lyricRes.songs.first().title)
        assertEquals("周杰伦", lyricRes.songs.first().artist)
        assertEquals("还记得你说家是唯一的城堡", lyricRes.songs.first().matchedLyricSnippet)
        assertFalse("独立的歌词结果页 Tab 应已彻底移除", SearchTab.entries.any { it.name == "LYRIC" })

        // #4 历史每日推荐（仅保留服务端真实批次，绝不伪造不存在的日期；严格传真实 RT_xxx + 8 位 yyyyMMdd）
        val histEntries = repo.fetchDailyRecommendHistoryList()
        assertEquals("历史日推不得伪造假日期，应仅返回服务端真实下发的 1 条批次", 1, histEntries.size)
        assertEquals("RT_336d5ebc5436534e61d16e63ddfca327_20261123", histEntries.first().historyName)
        assertEquals("20261123", histEntries.first().compactDate)
        val histSongs = repo.fetchDailyRecommendHistorySongs(histEntries.first().historyName, histEntries.first().compactDate)
        assertEquals(1, histSongs.size)
        assertEquals("七里香", histSongs.first().title)

        // #6 歌单/专辑评论与真实 special_child_id 删除评论
        val (plCount, plComments) = repo.fetchResourceComments(CommentTargetType.PLAYLIST, "collection_3_100_1_0")
        assertEquals(18, plCount)
        assertEquals("歌单太棒了", plComments.first().content)
        val posted = repo.postResourceComment(CommentTargetType.ALBUM, "9001", "夜曲新发", "循环播放一整晚")
        assertEquals("循环播放一整晚", posted.content)
        assertTrue(repo.deleteComment(CommentTargetType.PLAYLIST, "collection_3_100_1_0", "cmt_99"))
    }

    // ==================== Tier 2 (#7, #8, #9, #11) 云盘、三榜排行+歌手榜、猜你喜欢边界与全平台设备管理 ====================

    @Test
    fun testTier2CloudDiskListenRankArtistLeaderboardFmPreferenceAndWindowsDeviceDetection() = runTest {
        var capturedKickQuery: Map<String, String> = emptyMap()
        var capturedBlacklistHeadersTid = ""
        val (repo, paths) = loggedInRepo(
            handler = { path, query, body, _, _ ->
                when (path) {
                    "/bsstrackercdngz/v2/query_musicclound_url" -> ApiResponse(
                        JsonParser.parseString(
                            """{"status":1,"data":{"url":["https://fs.kgimg.com/cloud_track.flac"],"bitRate":960,"extName":"flac","fileSize":10485760}}"""
                        ).asJsonObject
                    )
                    "/v2/get_list" -> {
                        val listType = body?.get("list_type")?.asInt ?: 0
                        val count = when (listType) {
                            2 -> 88
                            1 -> 260
                            else -> 42
                        }
                        ApiResponse(
                            JsonParser.parseString(
                                """{
                                    "status":1,
                                    "data":{
                                        "lists":[
                                            {"hash":"ffff6666ffff6666ffff6666ffff6666","songname":"红豆","author_name":"王菲","pc":$count},
                                            {"hash":"eeee7777eeee7777eeee7777eeee7777","songname":"流年","author_name":"王菲","pc":18},
                                            {"hash":"dddd8888dddd8888dddd8888dddd8888","songname":"晴天","author_name":"周杰伦","pc":35}
                                        ]
                                    }
                                }""".trimIndent()
                            ).asJsonObject
                        )
                    }
                    "/v2/personal_recommend" -> ApiResponse(
                        JsonParser.parseString(
                            """{"status":1,"data":{"song_list":[{"hash":"99998888999988889999888899998888","songname":"新FM推荐","author_name":"林俊杰"}]}}"""
                        ).asJsonObject
                    )
                    "/userpreferservice/v1/get_user_conf" -> ApiResponse(
                        JsonParser.parseString("""{"status":1,"data":{"mode":2,"lang":"国语"}}""").asJsonObject
                    )
                    "/userpreferservice/v1/update_user_conf" -> ApiResponse(JsonParser.parseString("""{"status":1}""").asJsonObject)
                    "/v2/get_list_items" -> ApiResponse(
                        JsonParser.parseString(
                            """{"status":1,"data":{"items":[{"song_k":"abc123","song_v":"{\"n\":\"屏蔽曲目\",\"m\":\"999\",\"t\":\"1700000000\"}"}]}}"""
                        ).asJsonObject
                    )
                    "/v1/edit_list_items" -> {
                        capturedBlacklistHeadersTid = "474"
                        ApiResponse(JsonParser.parseString("""{"status":1}""").asJsonObject)
                    }
                    "/v2/get_dev" -> {
                        // 模拟 3116 概念版通道仅返回手机端，而 1005 标准通道返回当前已登录的 Windows 电脑客户端
                        if (query["appid"] == "1005") {
                            ApiResponse(
                                JsonParser.parseString(
                                    """{
                                        "status":1,
                                        "data":{
                                            "list":[
                                                {"mid":"pc_mid_999","dev_name":"Windows 11 Desktop","plat":"0","appid":"1005","clientver":"20489","t":"1763800000"}
                                            ]
                                        }
                                    }""".trimIndent()
                                ).asJsonObject
                            )
                        } else {
                            ApiResponse(
                                JsonParser.parseString(
                                    """{
                                        "status":1,
                                        "data":{
                                            "list":[
                                                {"mid":"android_self_mid","dev_name":"Pixel 9 Pro","plat":"1","appid":"3116","clientver":"11080","t":"1763790000"}
                                            ]
                                        }
                                    }""".trimIndent()
                                ).asJsonObject
                            )
                        }
                    }
                    "/loginservice/v1/dev_logout" -> {
                        capturedKickQuery = query
                        ApiResponse(JsonParser.parseString("""{"status":1,"error_code":0}""").asJsonObject)
                    }
                    else -> ApiResponse(JsonParser.parseString("""{"status":1,"data":{}}""").asJsonObject)
                }
            },
            binaryHandler = { path, _, _, _ ->
                if (path == "/v1/del_files") {
                    """{"status":1,"errcode":0}""".toByteArray(Charsets.UTF_8)
                } else {
                    """{
                        "status":1,
                        "data":{
                            "total":36,
                            "used_size":10485760,
                            "max_size":1073741824,
                            "list":[
                                {"kv_id":7788,"hash":"eeee5555eeee5555eeee5555eeee5555","name":"陈奕迅 - 富士山下.flac","size":10485760,"timelen":259000,"bitrate":960,"extname":"flac"}
                            ]
                        }
                    }""".trimIndent().toByteArray(Charsets.UTF_8)
                }
            }
        )

        // #7 个人音乐云盘（总数、音质、文件大小解析与实时云端删除）
        val cloud = repo.fetchUserCloudDisk()
        assertEquals(36, cloud.totalCount)
        assertEquals(1, cloud.songs.size)
        val cloudSongItem = cloud.songs.first()
        assertEquals(7788L, cloudSongItem.kvId)
        assertEquals("SQ", cloudSongItem.qualityBadgeLabel)
        assertEquals("10.0 MB", cloudSongItem.formattedSize)
        val cloudSong = cloudSongItem.toSong()
        assertTrue(cloudSong.isCloudSong)
        val resolvedCloud = repo.resolveUserCloudPlayback(cloudSong)
        assertEquals("https://fs.kgimg.com/cloud_track.flac", resolvedCloud.url)
        assertTrue(repo.deleteUserCloudSongs(listOf(cloudSongItem)))

        // #8 独立听歌排行：周榜(0) / 月榜(2) / 总榜(1) + 播放次数升降序 + 最常听歌手排行
        val weekRank = repo.fetchUserListenRank(type = 0)
        val monthRank = repo.fetchUserListenRank(type = 2)
        val allRank = repo.fetchUserListenRank(type = 1)
        assertEquals(42, weekRank.first().playCount)
        assertEquals(88, monthRank.first().playCount)
        assertEquals(260, allRank.first().playCount)

        val ascSorted = sortListenRankItems(weekRank, ascending = true)
        val descSorted = sortListenRankItems(weekRank, ascending = false)
        assertEquals(listOf(18, 35, 42), ascSorted.map { it.playCount })
        assertEquals(listOf(42, 35, 18), descSorted.map { it.playCount })

        val artistLeaderboard = aggregateArtistListenRank(weekRank, ascending = false)
        assertEquals("王菲", artistLeaderboard.first().artistName)
        assertEquals(60, artistLeaderboard.first().totalPlayCount)
        assertEquals(2, artistLeaderboard.first().trackCount)
        assertEquals("周杰伦", artistLeaderboard[1].artistName)
        assertEquals(35, artistLeaderboard[1].totalPlayCount)

        // #9 猜你喜欢负反馈（garbage）+ 听歌偏好 + 专属黑名单（KG-TID=474）
        val nextFmSongs = repo.sendPersonalFmFeedback(
            action = "garbage",
            song = Song(id = "f1", hash = "h_fm", title = "FM", artist = "歌手"),
            songPoolId = 1
        )
        assertEquals(1, nextFmSongs.size)
        assertEquals("新FM推荐", nextFmSongs.first().title)
        val pref = repo.fetchUserListeningPreference()
        assertNotNull(pref)
        assertEquals(2, pref?.mode)
        assertEquals("更多尝鲜", pref?.modeLabel)
        assertTrue(repo.updateUserListeningPreference(mode = 1, inactiveSingerList = "测试歌手"))
        val black = repo.fetchContentBlacklist("song")
        assertEquals(1, black.size)
        assertEquals("屏蔽曲目", black.first().name)
        assertTrue(repo.editContentBlacklist("song", "abc123", "屏蔽曲目", "999", isDelete = true))
        assertEquals("474", capturedBlacklistHeadersTid)

        // #11 全平台在线登录设备管理（同时检出 3116 手机端与 1005 Windows 桌面端并支持踢下线）
        val devices = repo.fetchLoginDevices()
        assertEquals(2, devices.size)
        val winDev = devices.firstOrNull { it.deviceType == "windows" }
        assertNotNull("应成功检出 1005 标准通道上的 Windows 在线设备", winDev)
        assertEquals("Windows 11 Desktop", winDev?.deviceName)
        assertTrue(repo.kickLoginDevice(winDev!!))
        assertEquals("pc_mid_999", capturedKickQuery["t_mid"])
        assertEquals("1005", capturedKickQuery["appid"])
        assertTrue(capturedKickQuery["signature"].orEmpty().isNotBlank())
        assertTrue(paths.contains("/loginservice/v1/dev_logout"))
    }

    // ==================== 深度审计二轮回归测试（并发 / DSP / 协议边界） ====================

    @Test
    fun testSleepTimerTrackEndStopAckPreventsForegroundDoubleConsumption() {
        try {
            PlaybackService.cancelServiceSleepTimer()
            PlaybackService.startServiceSleepTimer(minutes = 0, stopAfterCurrentSong = true)
            assertTrue(PlaybackService.sleepTimerState.value.pendingStopAtTrackEnd)

            // 模拟 PlaybackService 监听器先一步消费并打标 ViewModel ack
            assertTrue(PlaybackService.consumePendingTrackEndStop(markViewModelAck = true))
            // 二次消费必须返回 false
            assertFalse(PlaybackService.consumePendingTrackEndStop(markViewModelAck = false))
            // ViewModel 收到 STATE_ENDED / 切歌事件时通过 consumeViewModelTrackEndStopAck 识别到已处理，且仅消费一次
            assertTrue(PlaybackService.consumeViewModelTrackEndStopAck())
            assertFalse(PlaybackService.consumeViewModelTrackEndStopAck())

            // 用户手动切歌时 clearViewModelTrackEndStopAck 应清理残留 ack
            PlaybackService.startServiceSleepTimer(minutes = 0, stopAfterCurrentSong = true)
            assertTrue(PlaybackService.consumePendingTrackEndStop(markViewModelAck = true))
            PlaybackService.clearViewModelTrackEndStopAck()
            assertFalse(PlaybackService.consumeViewModelTrackEndStopAck())
        } finally {
            PlaybackService.cancelServiceSleepTimer()
        }
    }

    @Test
    fun testAudioEffectNamedPresetTweaksPreservedAndBandPassZeroLeakWhenViperOff() {
        val manager = AudioEffectManager
        try {
            // 1. 命名预设下微调声场参数后关闭再开启总开关，微调参数必须完整保留
            manager.setPreset(EffectPreset.VIPER_3D)
            manager.setBassBoostStrength(780)
            manager.setVirtualizerStrength(610)
            manager.setPreampGainDb(2.5f)
            assertEquals(EffectPreset.VIPER_3D, manager.state.value.currentPreset)

            manager.setEnabled(false)
            assertFalse(manager.state.value.isEnabled)
            manager.setEnabled(true)
            assertTrue(manager.state.value.isEnabled)
            assertEquals(EffectPreset.VIPER_3D, manager.state.value.currentPreset)
            assertEquals(780, manager.state.value.bassBoostStrength)
            assertEquals(610, manager.state.value.virtualizerStrength)
            assertEquals(2.5f, manager.state.value.preampGainDb, 0.01f)

            // 2. 配置 BAND_PASS +5dB 后关闭蝰蛇总开关但开启等响度补偿，1kHz 中心频点不应泄漏 +3.0dB 染色
            manager.setTenBandFilterType(5, PeqFilterType.BAND_PASS)
            manager.setTenBandLevel(5, 500)
            manager.setEnabled(false)
            manager.setEqualLoudnessCompensation(true)
            manager.setVolumeNormalizationEnabled(false)

            val processor = ConceptMasteringAudioProcessor()
            processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
            processor.flush()

            val frames = 2048
            val input = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            var inputMidRmsSq = 0.0
            for (i in 0 until frames) {
                val s = (kotlin.math.sin(2.0 * kotlin.math.PI * 1000.0 * i / 44100.0) * 8000.0).toInt().toShort()
                input.putShort(s)
                input.putShort(s)
                if (i >= 512) inputMidRmsSq += s.toDouble() * s.toDouble()
            }
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            var outputMidRmsSq = 0.0
            for (i in 0 until frames) {
                val l = output.short.toDouble()
                output.short
                if (i >= 512) outputMidRmsSq += l * l
            }
            val ratio = outputMidRmsSq / inputMidRmsSq
            assertTrue(
                "蝰蛇关闭但开等响度补偿时 1kHz 不应受到 BAND_PASS +3dB 泄漏（预期比值接近 1.0，实际=$ratio）",
                ratio in 0.78..1.25
            )
        } finally {
            manager.resetCurrentPresetToDefault()
            manager.setEnabled(false)
            manager.setEqualLoudnessCompensation(false)
            manager.setVolumeNormalizationEnabled(false)
        }
    }

    @Test
    fun testStandaloneVolumeNormalizationBoostsQuietSignalWhenViperDisabled() {
        val manager = AudioEffectManager
        try {
            manager.setPreset(EffectPreset.OFF)
            manager.setEqualLoudnessCompensation(false)
            manager.setVolumeNormalizationEnabled(true)
            assertFalse(manager.state.value.isEnabled)

            val processor = ConceptMasteringAudioProcessor()
            processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
            processor.flush()

            val frames = 4096
            val input = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            var inputTailRmsSq = 0.0
            for (i in 0 until frames) {
                val s = (kotlin.math.sin(2.0 * kotlin.math.PI * 1000.0 * i / 44100.0) * 2000.0).toInt().toShort()
                input.putShort(s)
                input.putShort(s)
                if (i >= 2048) inputTailRmsSq += s.toDouble() * s.toDouble()
            }
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            var outputTailRmsSq = 0.0
            for (i in 0 until frames) {
                val l = output.short.toDouble()
                output.short
                if (i >= 2048) outputTailRmsSq += l * l
            }
            assertTrue(
                "独立开启音量均衡且蝰蛇关闭时，低电平音频输出能量应获得自动增益提升",
                outputTailRmsSq > inputTailRmsSq * 1.25
            )
        } finally {
            manager.setVolumeNormalizationEnabled(false)
            manager.setEqualLoudnessCompensation(false)
            manager.setEnabled(false)
        }
    }

    @Test
    fun testFocusPodMidSessionAutoMixDisableStillReleasesAmbientTakeover() {
        try {
            AmbientSoundManager.setMasterEnabled(false)
            AmbientSoundManager.setSyncWithMusic(true)
            FocusPodManager.setAutoMixAmbient(true)

            FocusPodManager.startFocusPod()
            assertFalse("FocusPod 接管白噪音后应临时解除与音乐联动", AmbientSoundManager.state.value.syncWithMusic)
            assertTrue("FocusPod 启动后应开启白噪音", AmbientSoundManager.state.value.masterEnabled)

            // 运行中途关闭 autoMixAmbient，结束时仍必须恢复原有 syncWithMusic 并释放接管标志
            FocusPodManager.setAutoMixAmbient(false)
            FocusPodManager.stopFocusPod()
            assertTrue("即使中途关闭 autoMixAmbient，停止 FocusPod 也应恢复原 syncWithMusic=true", AmbientSoundManager.state.value.syncWithMusic)
            assertFalse(AmbientSoundManager.state.value.masterEnabled)
        } finally {
            FocusPodManager.stopFocusPod()
            FocusPodManager.setAutoMixAmbient(true)
            AmbientSoundManager.setMasterEnabled(false)
            AmbientSoundManager.setSyncWithMusic(true)
        }
    }

    @Test
    fun testStrictWriteResponsesRejectErrorStatusAndNonZeroErrorCode() = runTest {
        val (repoStatusZero, _) = loggedInRepo(
            handler = { _, _, _, _, _ ->
                ApiResponse(JsonParser.parseString("""{"status":0,"error_code":0,"error_msg":"failed"}""").asJsonObject)
            },
            binaryHandler = { _, _, _, _ ->
                """{"status":0,"errcode":0}""".toByteArray(Charsets.UTF_8)
            }
        )
        val (repoBusinessErrCode, _) = loggedInRepo(
            handler = { _, _, _, _, _ ->
                ApiResponse(JsonParser.parseString("""{"status":1,"error_code":40001,"error_msg":"business rejected"}""").asJsonObject)
            },
            binaryHandler = { _, _, _, _ ->
                """{"status":1,"errcode":40001}""".toByteArray(Charsets.UTF_8)
            }
        )

        val pl1 = Playlist(id = "12", title = "A", listId = 12L, isCreatedByMe = true)
        val pl2 = Playlist(id = "13", title = "B", listId = 13L, isCreatedByMe = true)
        val s1 = Song(id = "t1", hash = "h1", title = "1", artist = "A", fileId = 101L)
        val s2 = Song(id = "t2", hash = "h2", title = "2", artist = "B", fileId = 102L)
        val dev = LoginDeviceItem(mid = "m1", deviceName = "PC", platform = "0", appId = "1005", clientVer = "11000")

        for (r in listOf(repoStatusZero, repoBusinessErrCode)) {
            assertFalse(r.updatePlaylistInfo(pl1, "新标题", "新简介"))
            assertFalse(r.sortPlaylistTracks(pl1, listOf(s2, s1)))
            assertFalse(r.sortUserPlaylists(listOf(pl2, pl1), isCreatedByMe = true))
            assertFalse(r.updateUserListeningPreference(mode = 1))
            assertFalse(r.editContentBlacklist("song", "abc123", "测试曲", isDelete = false))
            assertFalse(r.deleteComment(CommentTargetType.SONG, "12345", "cmt_1"))
            assertFalse(r.kickLoginDevice(dev))
        }

        // 同时验证 20010 登录过期错误码会被 auth { ... } 包装识别并自动尝试刷新登录态
        val (repoExpired, expiredPaths) = loggedInRepo(
            handler = { _, _, _, _, _ ->
                ApiResponse(JsonParser.parseString("""{"status":1,"error_code":20010,"error_msg":"token expired"}""").asJsonObject)
            },
            binaryHandler = { _, _, _, _ ->
                """{"status":1,"errcode":20010}""".toByteArray(Charsets.UTF_8)
            }
        )
        val authRes = runCatching { repoExpired.updateUserListeningPreference(mode = 1) }
        assertTrue("20010 过期态应由 auth 拦截并抛出 ApiFailure 或返回 false", authRes.isFailure || authRes.getOrNull() == false)
        assertTrue("20010 过期态应自动触发刷新登录接口", expiredPaths.any { it.contains("login_by_token") })
    }

    @Test
    fun testPersonalFmFeedbackUsesTenDigitSecondsAndLowercaseHash() = runTest {
        var capturedBody: JsonObject? = null
        val (repo, _) = loggedInRepo(
            handler = { path, _, body, _, _ ->
                if (path == "/v2/personal_recommend") {
                    capturedBody = body
                    ApiResponse(JsonParser.parseString("""{"status":1,"data":{"song_list":[]}}""").asJsonObject)
                } else {
                    ApiResponse(JsonParser.parseString("""{"status":1,"data":{}}""").asJsonObject)
                }
            }
        )
        repo.sendPersonalFmFeedback(
            action = "garbage",
            song = Song(id = "f1", hash = "AABBCCDDEEFF00112233445566778899", title = "测试", artist = "歌手", audioId = 777L, albumAudioId = 888L),
            songPoolId = 2
        )
        val body = assertNotNull(capturedBody).let { capturedBody!! }
        val clientTime = body.get("clienttime").asLong
        assertTrue("clienttime 应为 10 位秒级时间戳，实际=$clientTime", clientTime in 1_000_000_000L..9_999_999_999L)
        assertEquals("garbage", body.get("action").asString)
        assertEquals("small", body.get("mode").asString)
        assertEquals("aabbccddeeff00112233445566778899", body.get("hash").asString)
        assertEquals(777L, body.get("songid").asLong)
        assertEquals(2, body.get("song_pool_id").asInt)
    }

    @Test
    fun testBoundaryIsolationAndIndependentPagesSourceArchitecture() {
        val projectRoot = java.io.File(".").canonicalFile.let { dir ->
            if (java.io.File(dir, "app/src/main").exists()) dir else dir.parentFile
        }
        val songActionCode = java.io.File(projectRoot, "app/src/main/java/com/concept/music/ui/components/SongActionBottomSheet.kt").readText(Charsets.UTF_8)
        assertFalse("全局歌曲更多菜单绝不能包含屏蔽歌曲或屏蔽歌手入口", songActionCode.contains("屏蔽此曲") || songActionCode.contains("屏蔽歌手"))
        assertFalse("全局歌曲更多菜单不再包含更多版本入口", songActionCode.contains("更多版本"))

        val npDialogsCode = java.io.File(projectRoot, "app/src/main/java/com/concept/music/ui/components/NowPlayingDialogs.kt").readText(Charsets.UTF_8)
        assertFalse("全屏播放页更多面板绝不能包含屏蔽歌曲或更多版本入口", npDialogsCode.contains("屏蔽此曲") || npDialogsCode.contains("更多版本"))

        val plDetailCode = java.io.File(projectRoot, "app/src/main/java/com/concept/music/ui/library/PlaylistDetailScreen.kt").readText(Charsets.UTF_8)
        assertFalse("最近播放/歌单详情页已彻底剥离听歌排行", plDetailCode.contains("ListenRankSectionBody"))
        assertTrue("自建歌单详情页支持页内拖拽排序并展示取消与完成按钮", plDetailCode.contains("isInlineReorderMode") && plDetailCode.contains("InlineDraggableSongRow"))
        assertFalse("自建歌单页内排序已删除中间重复横条", plDetailCode.contains("InlineReorderToolbarBanner"))
        assertFalse("歌单详情顶栏已全面使用 Checklist 图标而非文字多选", plDetailCode.contains("\"多选\""))

        val homeCode = java.io.File(projectRoot, "app/src/main/java/com/concept/music/ui/home/HomeScreen.kt").readText(Charsets.UTF_8)
        assertFalse("每日推荐已删除重复横条入口", homeCode.contains("DailyFullWeekHistoryStrip"))
        assertFalse("全应用不再在播放全部按钮内拼接数量", homeCode.contains("播放全部 ("))

        val cloudCode = java.io.File(projectRoot, "app/src/main/java/com/concept/music/ui/library/CloudDiskScreen.kt").readText(Charsets.UTF_8)
        assertFalse("云盘页已移除音质分类筛选按钮", cloudCode.contains("CloudQualityFilter"))
        assertFalse("云盘页播放全部按钮不显示数量", cloudCode.contains("播放全部 ("))

        val libCode = java.io.File(projectRoot, "app/src/main/java/com/concept/music/ui/library/LibraryScreen.kt").readText(Charsets.UTF_8)
        assertFalse("我的页已彻底删除歌手官方动态功能", libCode.contains("followedArtistNewReleases") || libCode.contains("官方动态"))

        assertFalse("跨设备继续播放组件已按计划暂缓移除", java.io.File(projectRoot, "app/src/main/java/com/concept/music/ui/components/CrossDeviceContinueCard.kt").exists())
        assertTrue("独立音乐云盘页面应就绪", java.io.File(projectRoot, "app/src/main/java/com/concept/music/ui/library/CloudDiskScreen.kt").exists())
        assertTrue("独立听歌排行页面应就绪", java.io.File(projectRoot, "app/src/main/java/com/concept/music/ui/library/ListenRankScreen.kt").exists())
    }

    @Test
    fun testCloudDiskUnlimitedPaginationBitrateEnumCalibrationAndSwappedTitleCorrection() = runTest {
        // 1. 校验酷狗云盘 bitrate=1..10 品质枚举校准（彻底杜绝 FLAC 4kbps / MP3 3kbps 及 320k 误标标准）
        val (extFlac, kbpsFlac, qFlac) = parseCloudDiskBitrateAndQuality(
            brRaw = 4,
            extRaw = "flac",
            fileSize = 25_000_000L, // 25MB / 200s ≈ 1000kbps
            durationMs = 200_000L
        )
        assertEquals("flac", extFlac)
        assertTrue("FLAC 估算码率应落在合理无损范围而非 4kbps，实际=$kbpsFlac", kbpsFlac in 800..1200)
        assertEquals(AudioQuality.LOSSLESS, qFlac)

        val (extMp3Hq, kbpsMp3Hq, qMp3Hq) = parseCloudDiskBitrateAndQuality(
            brRaw = 3,
            extRaw = "mp3",
            fileSize = 7_600_000L,
            durationMs = 190_000L
        )
        assertEquals("mp3", extMp3Hq)
        assertEquals("bitrate=3 为 320k HQ 品质枚举，绝不能输出 3kbps", 320, kbpsMp3Hq)
        assertEquals("bitrate=3 必须判定为 HQ 而非标准", AudioQuality.HIGH, qMp3Hq)

        // 2. 模拟 3 页共 215 首云盘曲目（即使服务端未提供 total 字段，fetchAllUserCloudDisk 也应自动翻页加载完全部 215 首，不受 50 首上限限制）
        var requestedPages = 0
        val (repo, _) = loggedInRepo(
            binaryHandler = { _, _, _, _ ->
                requestedPages++
                val startIdx = (requestedPages - 1) * 100
                val pageCount = when (requestedPages) {
                    1 -> 100
                    2 -> 100
                    3 -> 15
                    else -> 0
                }
                val itemsJson = (0 until pageCount).joinToString(",") { offset ->
                    val num = startIdx + offset + 1
                    val hash = num.toString().padStart(32, 'a')
                    """{"kv_id":$num,"hash":"$hash","name":"Artist$num - Track$num.flac","ext":"flac","bitrate":4,"size":24000000,"timelen":200}"""
                }
                """{"status":1,"data":{"used_size":5160000000,"max_size":64424509440,"list":[$itemsJson]}}""".toByteArray(Charsets.UTF_8)
            }
        )
        val progressiveCounts = mutableListOf<Int>()
        val full = repo.fetchAllUserCloudDisk(pageSize = 100, enrichMetadata = false) { snap ->
            progressiveCounts.add(snap.songs.size)
        }
        assertEquals("必须自动连续拉取全部 215 首云盘曲目", 215, full.songs.size)
        assertEquals(215, full.totalCount)
        assertFalse(full.hasMore)
        assertEquals("流式回调应逐页推送 100 -> 200 -> 215", listOf(100, 200, 215), progressiveCounts)
        assertTrue("首首码率均应大于 450kbps 而非 4kbps", full.songs.first().bitrate >= 450)
        assertFalse(full.songs.first().formattedCodecAndBitrate.contains("4kbps"))
    }

    @Test
    fun testLocalListenStatsRepositoryWindowAggregationDedupAndPersistence() {
        val tempDir = java.nio.file.Files.createTempDirectory("breeze_listen_stats_test").toFile()
        try {
            val repo = com.concept.music.data.repository.LocalListenStatsRepository(context = null, customStoreDir = tempDir)
            val now = 1_800_000_000_000L
            val dayMs = 86_400_000L

            val songA = Song(id = "sA", hash = "11112222333344445555666677778888", title = "晴天", artist = "周杰伦", album = "叶惠美")
            val songAFlac = songA.copy(id = "sA_dup", hashFlac = "11112222333344445555666677778888", isLossless = true)
            val songB = Song(id = "sB", hash = "aaaa2222333344445555666677779999", title = "泡沫", artist = "邓紫棋", album = "Xposed")
            val songC = Song(id = "sC", hash = "bbbb2222333344445555666677770000", title = "夜曲", artist = "周杰伦", album = "十一月的萧邦")

            // 近一周：晴天 3 次（含跨音质合并）、泡沫 1 次
            repo.recordPlay("u1", songA, playedMs = 180_000L, nowMs = now - 1L * dayMs)
            repo.recordPlay("u1", songAFlac, playedMs = 200_000L, nowMs = now - 2L * dayMs)
            repo.recordPlay("u1", songA, playedMs = 190_000L, nowMs = now - 3L * dayMs)
            repo.recordPlay("u1", songB, playedMs = 210_000L, nowMs = now - 4L * dayMs)
            // 15 天前（仅在月榜和总榜）：夜曲 2 次
            repo.recordPlay("u1", songC, playedMs = 220_000L, nowMs = now - 15L * dayMs)
            repo.recordPlay("u1", songC, playedMs = 220_000L, nowMs = now - 16L * dayMs)

            val week = repo.queryRank("u1", com.concept.music.data.repository.LocalListenStatsRepository.PERIOD_WEEK, nowMs = now)
            assertEquals(2, week.songs.size)
            assertEquals("晴天", week.songs[0].song.title)
            assertEquals("同曲不同音质应合并计为 3 次", 3, week.songs[0].playCount)
            assertEquals(4, week.totalPlayCount)

            val month = repo.queryRank("u1", com.concept.music.data.repository.LocalListenStatsRepository.PERIOD_MONTH, nowMs = now)
            assertEquals(3, month.songs.size)
            assertEquals(6, month.totalPlayCount)
            assertEquals("周杰伦", month.artists.first().artistName)
            assertEquals("周杰伦合计晴天3次+夜曲2次=5次", 5, month.artists.first().totalPlayCount)
            assertEquals(2, month.artists.first().trackCount)

            val all = repo.queryRank("u1", com.concept.music.data.repository.LocalListenStatsRepository.PERIOD_ALL, nowMs = now)
            assertEquals(6, all.totalPlayCount)
            assertEquals(6, repo.getTotalPlayCount("u1"))

            // 游客态独立隔离
            assertEquals(0, repo.getTotalPlayCount(null))

            // 清空验证
            repo.clear("u1")
            assertEquals(0, repo.getTotalPlayCount("u1"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLyricBodySnippetStripsCreditsHeaderAndFusesMultiEngineOriginalSongs() = runTest {
        // 1. 仅命中歌头/词曲制作人行时，绝不把「词：xxx 曲：xxx」当唱词展示
        val creditsOnlyLyric = """
            勋章 - <em>鹿晗</em>
            词：朱婧汐 Jing
            曲：Jamie Scot/Nicolas Squires
            编曲：Nicolas Squires
            是谁说伟大才值得被歌颂
        """.trimIndent()
        val (headerOnlySnippet, isHeaderBodyHit) = extractMatchedLyricBodySnippet(
            rawLyric = creditsOnlyLyric,
            keyword = "鹿晗",
            songTitle = "勋章",
            artist = "鹿晗"
        )
        assertFalse("只命中歌手/制作人头时不应视作唱词正文命中", isHeaderBodyHit)
        assertEquals("", headerOnlySnippet)

        // 2. 命中真实唱词正文时，跳过词曲制作人行并精准提取命中句
        val realBodyLyric = """
            勋章 - 鹿晗
            词：朱婧汐 Jing
            曲：Jamie Scot
            而我受过的伤都是我的<em>勋章</em>
            武器就是我紧握的梦想
        """.trimIndent()
        val (bodySnippet, isRealHit) = extractMatchedLyricBodySnippet(
            rawLyric = realBodyLyric,
            keyword = "受过的伤都是我的勋章",
            songTitle = "勋章",
            artist = "鹿晗"
        )
        assertTrue(isRealHit)
        assertTrue("提取结果应包含真实唱词", bodySnippet.contains("受过的伤都是我的勋章"))
        assertFalse("提取结果不得包含词曲制作人头", bodySnippet.contains("朱婧汐"))

        // 3. 歌词句识别器与批量歌单移出校验
        assertTrue(isLikelyLyricQuery("而我已经分不清你是友情还是错过的爱情"))
        assertFalse(isLikelyLyricQuery("鹿晗"))
        assertFalse(isLikelyLyricQuery("周杰伦 歌单"))

        var capturedBatchDeleteCount = 0
        val (repo, paths) = loggedInRepo(
            handler = { path, _, body, _, _ ->
                if (path == "/cloudlist.service/v4/delete_songs") {
                    capturedBatchDeleteCount = body?.getAsJsonArray("data")?.size() ?: 0
                    ApiResponse(JsonParser.parseString("""{"status":1,"err_code":0}""").asJsonObject)
                } else {
                    ApiResponse(JsonParser.parseString("""{"status":1,"data":{}}""").asJsonObject)
                }
            }
        )
        val ok = repo.removeSongsFromPlaylistBatch(
            playlistId = 123L,
            songs = listOf(
                Song(id = "s1", hash = "aaaa1111aaaa1111aaaa1111aaaa1111", title = "曲1", artist = "A", fileId = 501L),
                Song(id = "s2", hash = "bbbb2222bbbb2222bbbb2222bbbb2222", title = "曲2", artist = "B", fileId = 502L)
            )
        )
        assertTrue(ok)
        assertEquals("批量移出应单次提交 2 首歌曲的 fileid", 2, capturedBatchDeleteCount)
        assertTrue(paths.contains("/cloudlist.service/v4/delete_songs"))
    }

    @Test
    fun testEncryptedJsonLeftBraceCollisionCloudTitleDotPreservationAndLocalBlacklistFilter() = runTest {
        // 1. 构造首字节恰为 '{' (0x7B) 的原始 AES 密文，验证 decodeEncryptedJsonResponse 不误判为明文 JSON
        var keyWithLeftBrace = ""
        var cipherWithLeftBrace = ByteArray(0)
        for (attempt in 0 until 2000) {
            val payload = """{"status":1,"attempt":$attempt}"""
            val (k, bytes) = KugouSigner.playlistAesEncryptBytes(payload)
            if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) {
                keyWithLeftBrace = k
                cipherWithLeftBrace = bytes
                break
            }
        }
        assertTrue("应在合理尝试次数内生成首字节为 '{' 的密文", cipherWithLeftBrace.isNotEmpty())
        val decoded = decodeEncryptedJsonResponse(cipherWithLeftBrace, keyWithLeftBrace)
        assertNotNull(decoded)
        assertEquals(1, decoded?.get("status")?.asInt)

        // 2. 云盘歌名仅剥离已知音频后缀，完整保留 A.I.N.Y. / Mr.Lonely 中的英文句点
        val (repo, _) = loggedInRepo(
            binaryHandler = { _, _, _, _ ->
                """{
                    "status":1,
                    "data":{
                        "total":2,
                        "used_size":2048,
                        "max_size":1048576,
                        "list":[
                            {"kv_id":101,"hash":"aaaa0000aaaa0000aaaa0000aaaa0000","name":"G.E.M.邓紫棋 - A.I.N.Y..flac","size":1024,"timelen":220000},
                            {"kv_id":102,"hash":"bbbb0000bbbb0000bbbb0000bbbb0000","name":"Mr.Lonely.mp3","size":1024,"timelen":200000}
                        ]
                    }
                }""".trimIndent().toByteArray(Charsets.UTF_8)
            }
        )
        val cloud = repo.fetchUserCloudDisk()
        assertEquals(2, cloud.songs.size)
        assertEquals("A.I.N.Y.", cloud.songs[0].title)
        assertEquals("Mr.Lonely", cloud.songs[1].title)

        // 3. LocalBlacklistFilter 按多档位 Hash 与歌手 ID/名实时过滤
        try {
            LocalBlacklistFilter.clear()
            val target = Song(
                id = "s1",
                hash = "abcd1234",
                hashFlac = "EFGH5678",
                mixSongId = "99001",
                title = "夜曲",
                artist = "周杰伦",
                artistId = "3520"
            )
            val sameSinger = Song(id = "s2", hash = "zzzz9999", title = "晴天", artist = "周杰伦", artistId = "3520")
            val innocent = Song(id = "s3", hash = "yyyy8888", title = "红豆", artist = "王菲", artistId = "4000")

            LocalBlacklistFilter.addSong(target)
            assertTrue(LocalBlacklistFilter.isBlocked(target.copy(hash = "efgh5678")))
            assertFalse(LocalBlacklistFilter.isBlocked(sameSinger))

            LocalBlacklistFilter.addArtist(artistId = "3520", artistName = "周杰伦")
            assertEquals(listOf(innocent), LocalBlacklistFilter.filterSongs(listOf(target, sameSinger, innocent)))

            LocalBlacklistFilter.removeArtistEntry(BlacklistEntry(key = "3520", label = "singer", name = "周杰伦"))
            assertFalse(LocalBlacklistFilter.isBlocked(sameSinger))
        } finally {
            LocalBlacklistFilter.clear()
        }
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun testDynamicLruCacheEvictorEvictsMultipleSpansInSinglePassAndHandlesLockedSpan() {
        val mb = 1024L * 1024L
        val evictor = AudioCacheManager.DynamicLruCacheEvictor(64L * mb)
        val removedKeys = mutableListOf<String>()
        var failRemovalForKey: String? = null

        lateinit var fakeCache: Cache
        fakeCache = java.lang.reflect.Proxy.newProxyInstance(
            Cache::class.java.classLoader,
            arrayOf(Cache::class.java)
        ) { _, method, args ->
            if (method.name == "removeSpan" && args != null && args.isNotEmpty()) {
                val span = args[0] as CacheSpan
                removedKeys.add(span.key)
                if (span.key != failRemovalForKey) {
                    evictor.onSpanRemoved(fakeCache, span)
                }
                null
            } else {
                when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        } as Cache

        // 插入 3 个 20MB 分片（合计 60MB <= 64MB），均不应被淘汰
        evictor.onSpanAdded(fakeCache, CacheSpan("k1", 0L, 20L * mb, 100L, null))
        evictor.onSpanAdded(fakeCache, CacheSpan("k2", 0L, 20L * mb, 200L, null))
        evictor.onSpanAdded(fakeCache, CacheSpan("k3", 0L, 20L * mb, 300L, null))
        assertTrue(removedKeys.isEmpty())

        // 新写入一个 40MB 大分片（总计 100MB > 64MB），单次 evictCache 必须连续淘汰 k1、k2 两个分片（剩余 k3+k4 = 60MB <= 64MB）
        evictor.onSpanAdded(fakeCache, CacheSpan("k4", 0L, 40L * mb, 400L, null))
        assertEquals(
            "超出上限需释放 36MB 时，单次淘汰必须连续移除最旧的 k1 和 k2（不能删 1 个就提前终止）",
            listOf("k1", "k2"),
            removedKeys
        )

        // 模拟 k3 被锁导致 cache.removeSpan 未触发 onSpanRemoved：应主动推进并安全退出不死循环
        removedKeys.clear()
        failRemovalForKey = "k3"
        evictor.onSpanAdded(fakeCache, CacheSpan("k5", 0L, 30L * mb, 500L, null))
        assertTrue("锁定分片 k3 至少尝试移除一次且不陷入死循环", removedKeys.contains("k3"))
    }

    @Test
    fun testAuthPassesRefreshedTokenAndSingleMicroservice20010NeverLogsOutAccount() = runTest {
        // 1. 场景 A：业务接口首次返回 20010 -> /v5/login_by_token 刷新返回新 token_v2 ->
        //    MusicRepository.auth 重试时必须携带新 token_v2 并成功，严禁回传旧 token 导致二次 20010 掉登
        val seenTokensOnBlacklist = mutableListOf<String>()
        var refreshCallsA = 0
        val storeA = MemoryStore().apply {
            rows = listOf(SavedAccount("123", "测试用户", needsLogin = false))
            activeKey = "123"
            sessions["123"] = KugouSession("123", "tok_old", "t1_a")
        }
        val transportA = object : KugouTransport {
            override suspend fun call(
                path: String,
                session: KugouSession?,
                query: Map<String, String>,
                body: JsonObject?,
                host: String,
                method: String,
                web: Boolean,
                headers: Map<String, String>,
                device: DeviceIdentity?
            ): ApiResponse {
                return when {
                    path == "/v5/login_by_token" -> {
                        refreshCallsA++
                        ApiResponse(
                            JsonParser.parseString(
                                """{"status":1,"data":{"userid":"123","token":"tok_v2","t1":"t1_b"}}"""
                            ).asJsonObject
                        )
                    }
                    path == "/v2/get_list_items" -> {
                        val tok = session?.token.orEmpty()
                        seenTokensOnBlacklist.add(tok)
                        if (tok == "tok_old") {
                            ApiResponse(JsonParser.parseString("""{"status":0,"error_code":20010,"error_msg":"expired"}""").asJsonObject)
                        } else {
                            ApiResponse(JsonParser.parseString("""{"status":1,"data":{"items":[]}}""").asJsonObject)
                        }
                    }
                    else -> ApiResponse(JsonParser.parseString("""{"status":1,"data":{}}""").asJsonObject)
                }
            }
        }
        val authA = AuthRepository(transportA, storeA)
        val accountsA = AccountRepository(storeA, authA, transportA)
        val repoA = MusicApiRepository(transportA, accountsA)
        repoA.fetchContentBlacklist("song")
        assertEquals("应先用旧 token 请求，再携带刷新后的 tok_v2 重试", listOf("tok_old", "tok_v2"), seenTokensOnBlacklist)
        assertEquals(1, refreshCallsA)
        assertNotEquals("刷新成功后绝不能被注销为 NEEDS_LOGIN", AuthState.NEEDS_LOGIN, accountsA.state.value.auth)
        assertFalse(accountsA.state.value.current?.needsLogin == true)

        // 2. 场景 B：/v5/login_by_token 刷新成功证明账号登录态有效，但某一边缘微服务仍持续返回 20010 ->
        //    绝不误杀全局登录态，且 15 秒内重复 20010 不连环轰炸 /v5/login_by_token
        var refreshCallsB = 0
        val storeB = MemoryStore().apply {
            rows = listOf(SavedAccount("123", "测试用户", needsLogin = false))
            activeKey = "123"
            sessions["123"] = KugouSession("123", "tok_same", "t1_a")
        }
        val transportB = object : KugouTransport {
            override suspend fun call(
                path: String,
                session: KugouSession?,
                query: Map<String, String>,
                body: JsonObject?,
                host: String,
                method: String,
                web: Boolean,
                headers: Map<String, String>,
                device: DeviceIdentity?
            ): ApiResponse {
                return when (path) {
                    "/v5/login_by_token" -> {
                        refreshCallsB++
                        ApiResponse(
                            JsonParser.parseString(
                                """{"status":1,"data":{"userid":"123","token":"tok_same","t1":"t1_a"}}"""
                            ).asJsonObject
                        )
                    }
                    "/v2/get_list_items" ->
                        ApiResponse(JsonParser.parseString("""{"status":0,"error_code":20010,"error_msg":"param error"}""").asJsonObject)
                    else -> ApiResponse(JsonParser.parseString("""{"status":1,"data":{}}""").asJsonObject)
                }
            }
        }
        val authB = AuthRepository(transportB, storeB)
        val accountsB = AccountRepository(storeB, authB, transportB)
        accountsB.accept(LoginResult.Success(KugouSession("123", "tok_same", "t1_a"), AccountProfile("123", "测试用户")))
        assertEquals("新登录成功 accept() 应直接进入 READY 状态而非 RESTORING", AuthState.READY, accountsB.state.value.auth)
        val repoB = MusicApiRepository(transportB, accountsB)
        repeat(3) {
            runCatching { repoB.fetchContentBlacklist("song") }
        }
        assertEquals("边缘微服务持续报 20010 时账号必须保持 READY，严禁误杀掉登", AuthState.READY, accountsB.state.value.auth)
        assertFalse("边缘微服务 20010 不得将 needsLogin 写入 true", accountsB.state.value.current?.needsLogin == true)
        assertEquals("刚登录 15 秒内不应因边缘微服务 20010 连环轰炸 /v5/login_by_token", 0, refreshCallsB)

        // 3. 场景 C：仅当权威接口 /v5/login_by_token 自身明确返回 20010 时，才认定会话真正过期并置为 NEEDS_LOGIN
        val storeC = MemoryStore().apply {
            rows = listOf(SavedAccount("123", "测试用户", needsLogin = false))
            activeKey = "123"
            sessions["123"] = KugouSession("123", "tok_dead", "t1_a")
        }
        val transportC = object : KugouTransport {
            override suspend fun call(
                path: String,
                session: KugouSession?,
                query: Map<String, String>,
                body: JsonObject?,
                host: String,
                method: String,
                web: Boolean,
                headers: Map<String, String>,
                device: DeviceIdentity?
            ): ApiResponse {
                return ApiResponse(JsonParser.parseString("""{"status":0,"error_code":20010,"error_msg":"token revoked"}""").asJsonObject)
            }
        }
        val authC = AuthRepository(transportC, storeC)
        val accountsC = AccountRepository(storeC, authC, transportC)
        val repoC = MusicApiRepository(transportC, accountsC)
        runCatching { repoC.fetchContentBlacklist("song") }
        assertEquals("当权威 /v5/login_by_token 自身报 20010 时应正确进入 NEEDS_LOGIN", AuthState.NEEDS_LOGIN, accountsC.state.value.auth)
        assertTrue(accountsC.state.value.current?.needsLogin == true)
    }
}
