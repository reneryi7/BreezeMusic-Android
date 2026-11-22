package com.concept.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.concept.music.data.kugou.*
import com.concept.music.data.model.*
import com.concept.music.service.AppPermissionManager
import com.concept.music.service.AppPermissionType
import com.concept.music.service.AudioEffectManager
import com.concept.music.service.ConceptMasteringAudioProcessor
import com.concept.music.service.EffectPreset
import com.concept.music.service.PeqFilterType
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

    // ==================== Tier 1 (#1-#6) 扩展协议单元测试 ====================

    @Test
    fun testTier1ApisRelatedVersionsPlaylistEditLyricSearchDailyHistoryFollowNewSongsAndComments() = runTest {
        val (repo, paths) = loggedInRepo(
            handler = { path, query, _, _, _ ->
                when (path) {
                    "/v3/album_audio/related" -> ApiResponse(
                        JsonParser.parseString(
                            """{
                                "status":1,
                                "data":{
                                    "lists":[
                                        {"hash":"aaaa1111aaaa1111aaaa1111aaaa1111","songname":"晴天 (Live)","author_name":"周杰伦","album_audio_id":55501,"duration":269}
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
                                    "total":1,
                                    "lists":[
                                        {"FileHash":"bbbb2222bbbb2222bbbb2222bbbb2222","SongName":"稻香","SingerName":"周杰伦","Lyric":"<em>还记得你说家是唯一的城堡</em>","Duration":223}
                                    ]
                                }
                            }""".trimIndent()
                        ).asJsonObject
                    )
                    "/everyday/api/v1/get_history" -> {
                        if (query["mode"] == "list") {
                            ApiResponse(
                                JsonParser.parseString(
                                    """{"status":1,"data":{"lists":[{"history_name":"2026-11-21","date":"2026-11-21","title":"11月21日"}]}}"""
                                ).asJsonObject
                            )
                        } else {
                            ApiResponse(
                                JsonParser.parseString(
                                    """{"status":1,"data":{"song_list":[{"hash":"cccc3333cccc3333cccc3333cccc3333","songname":"七里香","author_name":"周杰伦","time_length":299}]}}"""
                                ).asJsonObject
                            )
                        }
                    }
                    "/feed/v1/follow/newsong_album_list" -> ApiResponse(
                        JsonParser.parseString(
                            """{
                                "status":1,
                                "data":{
                                    "list":[
                                        {"album_id":"9001","album_name":"夜曲新发","singer_name":"周杰伦","singer_id":"3520","publish_date":"2026-11-20","hash":"dddd4444dddd4444dddd4444dddd4444","songname":"夜曲"}
                                    ]
                                }
                            }""".trimIndent()
                        ).asJsonObject
                    )
                    "/m.comment.service/v1/cmtlist" -> ApiResponse(
                        JsonParser.parseString(
                            """{
                                "status":1,
                                "count":18,
                                "data":{
                                    "list":[{"id":"cmt_99","user_id":"10086","user_name":"微风测试号","content":"歌单太棒了","addtime":"2026-11-22 10:00:00"}]
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

        // #1 更多版本
        val versions = repo.fetchRelatedSongVersions(Song(id = "s1", hash = "h0", title = "晴天", artist = "周杰伦", albumAudioId = 12345L))
        assertEquals(1, versions.size)
        assertEquals("Live", versions.first().versionTag)

        // #2 歌单编辑与云端排序
        val testPl = Playlist(id = "12", title = "原歌单", listId = 12L, isCreatedByMe = true)
        val testPl2 = Playlist(id = "15", title = "歌单2", listId = 15L, isCreatedByMe = true)
        val track1 = Song(id = "t1", hash = "h1", title = "曲1", artist = "A", fileId = 101L)
        val track2 = Song(id = "t2", hash = "h2", title = "曲2", artist = "B", fileId = 102L)
        assertTrue(repo.updatePlaylistInfo(playlist = testPl, newName = "微风精选", newIntro = "夜间纯音乐"))
        assertTrue(repo.sortPlaylistTracks(playlist = testPl, orderedSongs = listOf(track2, track1)))
        assertTrue(repo.sortUserPlaylists(orderedPlaylists = listOf(testPl2, testPl), isCreatedByMe = true))
        assertTrue(paths.contains("/v1/modify_list"))
        assertTrue(paths.contains("/v1/modify_song_sort"))
        assertTrue(paths.contains("/v1/modify_list_sort"))

        // #3 歌词搜歌
        val lyricRes = repo.searchByLyric("家是唯一的城堡")
        assertEquals(1, lyricRes.songs.size)
        assertEquals("还记得你说家是唯一的城堡", lyricRes.songs.first().matchedLyricSnippet)
        assertTrue(SearchTab.entries.contains(SearchTab.LYRIC))

        // #4 历史每日推荐
        val histEntries = repo.fetchDailyRecommendHistoryList()
        assertEquals(1, histEntries.size)
        assertEquals("2026-11-21", histEntries.first().historyName)
        val histSongs = repo.fetchDailyRecommendHistorySongs(histEntries.first().historyName, histEntries.first().dateLabel)
        assertEquals(1, histSongs.size)
        assertEquals("七里香", histSongs.first().title)

        // #5 已关注歌手新歌动态
        val newReleases = repo.fetchFollowedArtistNewSongs()
        assertEquals(1, newReleases.size)
        assertEquals("夜曲新发", newReleases.first().title)
        assertNotNull(newReleases.first().song)

        // #6 歌单/专辑评论与删除评论
        val (plCount, plComments) = repo.fetchResourceComments(CommentTargetType.PLAYLIST, "collection_3_100_1_0")
        assertEquals(18, plCount)
        assertEquals("歌单太棒了", plComments.first().content)
        val posted = repo.postResourceComment(CommentTargetType.ALBUM, "9001", "夜曲新发", "循环播放一整晚")
        assertEquals("循环播放一整晚", posted.content)
        assertTrue(repo.deleteComment(CommentTargetType.SONG, "12345", "cmt_99"))
    }

    // ==================== Tier 2 (#7, #8, #9, #11⭐) 扩展协议单元测试 ====================

    @Test
    fun testTier2CloudDiskListenRankFmPreferenceBlacklistAndCrossDeviceContinuePlay() = runTest {
        var capturedKickQuery: Map<String, String> = emptyMap()
        val (repo, paths) = loggedInRepo(
            handler = { path, query, _, _, _ ->
                when (path) {
                    "/bsstrackercdngz/v2/query_musicclound_url" -> ApiResponse(
                        JsonParser.parseString(
                            """{"status":1,"data":{"url":["https://fs.kgimg.com/cloud_track.flac"],"bitRate":960,"extName":"flac","fileSize":10485760}}"""
                        ).asJsonObject
                    )
                    "/v2/get_list" -> ApiResponse(
                        JsonParser.parseString(
                            """{
                                "status":1,
                                "data":{
                                    "lists":[
                                        {"hash":"ffff6666ffff6666ffff6666ffff6666","songname":"红豆","author_name":"王菲","pc":42}
                                    ]
                                }
                            }""".trimIndent()
                        ).asJsonObject
                    )
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
                    "/v1/edit_list_items" -> ApiResponse(JsonParser.parseString("""{"status":1}""").asJsonObject)
                    "/playque/devque/v1/get_latest_songs" -> ApiResponse(
                        JsonParser.parseString(
                            """{
                                "status":1,
                                "data":{
                                    "pc":{
                                        "device_name":"Windows 桌面端",
                                        "update_time":1763800000,
                                        "songs":[
                                            {"hash":"1111aaaa1111aaaa1111aaaa1111aaaa","songname":"花海","author_name":"周杰伦","ot":1763800000},
                                            {"hash":"2222bbbb2222bbbb2222bbbb2222bbbb","songname":"兰亭序","author_name":"周杰伦","ot":1763799000}
                                        ]
                                    },
                                    "mobile":{
                                        "device_name":"Android 手机",
                                        "update_time":1763700000,
                                        "songs":[
                                            {"hash":"3333cccc3333cccc3333cccc3333cccc","songname":"青花瓷","author_name":"周杰伦","ot":1763700000}
                                        ]
                                    }
                                }
                            }""".trimIndent()
                        ).asJsonObject
                    )
                    "/v2/get_dev" -> ApiResponse(
                        JsonParser.parseString(
                            """{
                                "status":1,
                                "data":{
                                    "list":[
                                        {"mid":"pc_mid_999","dev_name":"ThinkPad Windows","plat":"0","appid":"1005","clientver":"11000","t":"1763800000"}
                                    ]
                                }
                            }""".trimIndent()
                        ).asJsonObject
                    )
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
                            "total":1,
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

        // #7 个人音乐云盘
        val cloud = repo.fetchUserCloudDisk()
        assertEquals(1, cloud.songs.size)
        val cloudSongItem = cloud.songs.first()
        assertEquals(7788L, cloudSongItem.kvId)
        val cloudSong = cloudSongItem.toSong()
        assertTrue(cloudSong.isCloudSong)
        val resolvedCloud = repo.resolveUserCloudPlayback(cloudSong)
        assertEquals("https://fs.kgimg.com/cloud_track.flac", resolvedCloud.url)
        assertTrue(repo.deleteUserCloudSongs(listOf(cloudSongItem)))

        // #8 听歌排行
        val rank = repo.fetchUserListenRank(type = 0)
        assertEquals(1, rank.size)
        assertEquals(42, rank.first().playCount)
        assertEquals("红豆", rank.first().song.title)

        // #9 私人 FM 负反馈 + 听歌偏好 + 黑名单
        val nextFmSongs = repo.sendPersonalFmFeedback(
            action = "trash",
            song = Song(id = "f1", hash = "h_fm", title = "FM", artist = "歌手"),
            songPoolId = 1
        )
        assertEquals(1, nextFmSongs.size)
        assertEquals("新FM推荐", nextFmSongs.first().title)
        val pref = repo.fetchUserListeningPreference()
        assertNotNull(pref)
        assertEquals(2, pref?.mode)
        assertEquals("更多尝鲜", pref?.modeLabel)
        assertTrue(repo.updateUserListeningPreference(mode = 1))
        val black = repo.fetchContentBlacklist("song")
        assertEquals(1, black.size)
        assertEquals("屏蔽曲目", black.first().name)
        assertTrue(repo.editContentBlacklist("song", "abc123", "屏蔽曲目", "999", isDelete = true))

        // #11 ⭐ 跨设备继续播放 & 登录设备管理
        val snapshot = repo.fetchCrossDeviceContinuePlay()
        assertTrue(snapshot.hasPlayableContent)
        assertEquals("Windows 桌面端", snapshot.primaryDeviceLabel)
        assertEquals("pc", snapshot.primarySourceType)
        assertEquals("花海", snapshot.primarySong?.title)
        assertEquals(2, snapshot.deviceGroups.size)

        // 验证智能推荐过滤：当前未播放或播放不同歌曲时触发提示，正在播放同一首时不重复打扰
        assertTrue(snapshot.shouldSuggestOverCurrentSong(currentSong = null, isCurrentlyPlaying = false))
        assertFalse(
            snapshot.shouldSuggestOverCurrentSong(
                currentSong = Song(id = "x", hash = "1111aaaa1111aaaa1111aaaa1111aaaa", title = "花海", artist = "周杰伦"),
                isCurrentlyPlaying = true
            )
        )
        assertTrue(
            snapshot.shouldSuggestOverCurrentSong(
                currentSong = Song(id = "y", hash = "9999xxxx", title = "晴天", artist = "周杰伦"),
                isCurrentlyPlaying = true
            )
        )

        // 登录设备管理 & 踢出设备
        val devices = repo.fetchLoginDevices()
        assertEquals(1, devices.size)
        assertEquals("ThinkPad Windows", devices.first().deviceName)
        assertTrue(repo.kickLoginDevice(devices.first()))
        assertEquals("pc_mid_999", capturedKickQuery["t_mid"])
        assertTrue(capturedKickQuery["signature"].orEmpty().isNotBlank())
        assertTrue(paths.contains("/loginservice/v1/dev_logout"))
    }
}
