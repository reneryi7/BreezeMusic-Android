package com.concept.music.data.kugou

import com.concept.music.data.model.Playlist
import com.concept.music.data.model.Song
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PlaylistManagementTest {

    @Test
    fun playlistAesEncryptionRoundTrip() {
        val originalText = """{"listid":123456,"total_ver":0,"type":0,"userid":88888,"token":"test_token"}"""
        val (key, base64Cipher) = KugouSigner.playlistAesEncrypt(originalText)

        assertNotNull(key)
        assertEquals(6, key.length)
        assertTrue(base64Cipher.isNotBlank())

        val decrypted = KugouSigner.playlistAesDecrypt(key, base64Cipher)
        assertEquals(originalText, decrypted)

        val rawCipherBytes = Base64.getDecoder().decode(base64Cipher)
        val decryptedFromBytes = KugouSigner.playlistAesDecrypt(rawCipherBytes, key)
        assertEquals(originalText, decryptedFromBytes)
    }

    @Test
    fun createPlaylistSendsCorrectPayload() = runTest {
        var calledPath = ""
        var calledBody: JsonObject? = null
        var calledQuery: Map<String, String>? = null

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
                calledPath = path
                calledBody = body
                calledQuery = query
                return ApiResponse(JsonParser.parseString("""{"status":1,"data":{"listid":9988}}""").asJsonObject)
            }
        }

        val store = MemoryStore()
        val auth = AuthRepository(transport, store)
        val accounts = AccountRepository(store, auth, transport)
        val repo = MusicApiRepository(transport, accounts)

        // 注入登录上下文
        store.saveSession(KugouSession("123", "mock-token"))

        val success = repo.createPlaylist("我的新歌单")
        assertTrue(success)
        assertEquals("/cloudlist.service/v5/add_list", calledPath)
        assertNotNull(calledBody)
        assertEquals("我的新歌单", calledBody?.get("name")?.asString)
        assertEquals(0, calledBody?.get("type")?.asInt)
        assertEquals(1, calledBody?.get("source")?.asInt)
        assertEquals("123", calledQuery?.get("userid"))
    }

    @Test
    fun collectPlaylistParsesGidAndSendsType1() = runTest {
        var calledPath = ""
        var calledBody: JsonObject? = null

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
                calledPath = path
                calledBody = body
                return ApiResponse(JsonParser.parseString("""{"status":1,"data":{}}""").asJsonObject)
            }
        }

        val store = MemoryStore()
        val auth = AuthRepository(transport, store)
        val accounts = AccountRepository(store, auth, transport)
        val repo = MusicApiRepository(transport, accounts)
        store.saveSession(KugouSession("123", "mock-token"))

        val targetPlaylist = Playlist(
            id = "collection_1_666888_999000",
            title = "周杰伦精选",
            globalCollectionId = "collection_1_666888_999000",
            isCreatedByMe = false,
            isFavorite = false
        )

        val success = repo.collectPlaylist(targetPlaylist)
        assertTrue(success)
        assertEquals("/cloudlist.service/v5/add_list", calledPath)
        assertEquals(1, calledBody?.get("type")?.asInt)
        assertEquals(666888L, calledBody?.get("list_create_userid")?.asLong)
        assertEquals(999000L, calledBody?.get("list_create_listid")?.asLong)
        assertEquals("collection_1_666888_999000", calledBody?.get("list_create_gid")?.asString)
    }

    @Test
    fun deleteOrUncollectPlaylistRejectsErrorResponseAndDecryptsEncryptedSuccess() = runTest {
        var capturedP = ""
        var mockRawBytes: ByteArray = """{"status":0,"err_code":30001}""".toByteArray(Charsets.UTF_8)

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
            ): ApiResponse = ApiResponse(JsonObject())

            override suspend fun requestRawBytes(
                path: String,
                session: KugouSession?,
                query: Map<String, String>,
                rawBody: String?,
                host: String,
                method: String,
                headers: Map<String, String>,
                sign: Boolean,
                includeDefaultParams: Boolean,
                device: DeviceIdentity?
            ): ByteArray {
                assertEquals("/v2/delete_list", path)
                capturedP = query["p"].orEmpty()
                return mockRawBytes
            }
        }

        val store = MemoryStore()
        val auth = AuthRepository(transport, store)
        val accounts = AccountRepository(store, auth, transport)
        val repo = MusicApiRepository(transport, accounts)
        store.saveSession(KugouSession("123", "mock-token"))

        val targetPlaylist = Playlist(
            id = "556677",
            listId = 556677L,
            title = "测试待删歌单",
            isCreatedByMe = true
        )

        // 1. 服务端返回错误 JSON (status=0, err_code=30001) 时绝不能假成功返回 true
        val failedResult = repo.deleteOrUncollectPlaylist(targetPlaylist)
        assertFalse("delete_list 返回 status=0 时必须返回 false", failedResult)
        assertEquals(256, capturedP.length)
        assertEquals(capturedP.lowercase(), capturedP)

        // 2. 服务端返回 status=1, err_code=0 时返回 true
        mockRawBytes = """{"status":1,"err_code":0}""".toByteArray(Charsets.UTF_8)
        val okResult = repo.deleteOrUncollectPlaylist(targetPlaylist)
        assertTrue("delete_list 返回 status=1 且 err_code=0 时必须返回 true", okResult)
    }

    @Test
    fun addAndRemoveSongFromPlaylistAndBatchAdd() = runTest {
        var addSongBody: JsonObject? = null
        var deleteSongBody: JsonObject? = null
        var addCallCount = 0

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
                if (path.contains("add_song")) {
                    addCallCount++
                    addSongBody = body
                } else if (path.contains("delete_songs")) {
                    deleteSongBody = body
                }
                return ApiResponse(JsonParser.parseString("""{"status":1,"data":{}}""").asJsonObject)
            }
        }

        val store = MemoryStore()
        val auth = AuthRepository(transport, store)
        val accounts = AccountRepository(store, auth, transport)
        val repo = MusicApiRepository(transport, accounts)
        store.saveSession(KugouSession("123", "mock-token"))

        val song = Song(
            id = "song_1",
            hash = "E7B82245C4B40B5DB3884D7759086111",
            title = "晴天",
            artist = "周杰伦",
            durationMs = 269000,
            albumAudioId = 12345678L,
            fileId = 998877L
        )

        // 1. 添加单曲到歌单（对齐 KuGou 云歌单标准「歌手 - 歌名」格式）
        val addSuccess = repo.addSongToPlaylist(1001L, song)
        assertTrue(addSuccess)
        assertNotNull(addSongBody)
        assertEquals(1001L, addSongBody?.get("listid")?.asLong)
        val songItemInAdd = addSongBody?.getAsJsonArray("data")?.get(0)?.asJsonObject
        assertEquals("周杰伦 - 晴天", songItemInAdd?.get("name")?.asString)
        assertEquals(song.hash, songItemInAdd?.get("hash")?.asString)
        assertEquals(12345678L, songItemInAdd?.get("mixsongid")?.asLong)

        // 2. 批量添加多首歌曲到歌单（单次网络请求提交全部歌曲）
        addCallCount = 0
        val song2 = song.copy(id = "song_2", hash = "F8C93356D5C51C6EC4995E8860197222", title = "七里香")
        val batchAdded = repo.addSongsToPlaylistBatch(1001L, listOf(song, song2))
        assertEquals(2, batchAdded)
        assertEquals("批量添加应只发起 1 次请求", 1, addCallCount)
        assertEquals(2, addSongBody?.getAsJsonArray("data")?.size())

        // 3. 从歌单移除单曲
        val removeSuccess = repo.removeSongFromPlaylist(1001L, song)
        assertTrue(removeSuccess)
        assertNotNull(deleteSongBody)
        assertEquals(1001L, deleteSongBody?.get("listid")?.asLong)
        val songItemInDel = deleteSongBody?.getAsJsonArray("data")?.get(0)?.asJsonObject
        assertEquals(998877L, songItemInDel?.get("fileid")?.asLong)
    }

    @Test
    fun songTitleStripsKnownAudioExtensionsWithoutCorruptingValidDots() {
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
            ): ApiResponse = ApiResponse(JsonObject())
        }
        val store = MemoryStore()
        val auth = AuthRepository(transport, store)
        val repo = MusicApiRepository(transport, AccountRepository(store, auth, transport))

        val mp3Obj = JsonParser.parseString(
            """{"fileid":101,"hash":"11111111111111111111111111111111","name":"Linkin Park - Numb.mp3","addtime":1700000100,"sort":0}"""
        ).asJsonObject
        val parsedMp3 = repo.parseSong(mp3Obj)
        assertEquals("Numb", parsedMp3.title)
        assertEquals("Linkin Park", parsedMp3.artist)
        assertEquals(1700000100L, parsedMp3.addTimeSec)
        assertEquals(0L, parsedMp3.cloudSortOrder)

        val flacObj = JsonParser.parseString(
            """{"fileid":102,"hash":"22222222222222222222222222222222","filename":"万妖王 - 白月初，你到底行不行.FLAC"}"""
        ).asJsonObject
        val parsedFlac = repo.parseSong(flacObj)
        assertEquals("白月初，你到底行不行", parsedFlac.title)
        assertEquals("万妖王", parsedFlac.artist)

        val validDotObj = JsonParser.parseString(
            """{"fileid":103,"hash":"33333333333333333333333333333333","songname":"Mr. Brightside","singername":"The Killers"}"""
        ).asJsonObject
        assertEquals("Mr. Brightside", repo.parseSong(validDotObj).title)

        val cachedLegacy = Song(
            id = "legacy_1",
            hash = "44444444444444444444444444444444",
            title = "In The End.mp3",
            artist = "Linkin Park"
        ).sanitizeFromJson()
        assertEquals("In The End", cachedLegacy.title)
        assertEquals("3.14", com.concept.music.data.model.stripKnownAudioFileExtension("3.14"))
    }

    @Test
    fun cloudPlaylistDefaultSortMatchesOfficialOrderAndSixSortModesWork() = runTest {
        val mockResponseJson = """
            {
              "status": 1,
              "data": {
                "list_ver": 42,
                "count": 4,
                "info": [
                  {"fileid": 1001, "hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "name": "歌手A - 最早添加.mp3", "addtime": 1700000100, "sort": 0, "timelen": 210},
                  {"fileid": 1002, "hash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "name": "歌手B - 较早添加.mp3", "addtime": 1700000200, "sort": 0, "timelen": 280},
                  {"fileid": 1003, "hash": "cccccccccccccccccccccccccccccccc", "name": "歌手C - 最新添加.mp3", "addtime": 1700000500, "sort": 0, "timelen": 190},
                  {"fileid": 1004, "hash": "dddddddddddddddddddddddddddddddd", "name": "歌手D - 居中时间.flac", "addtime": 1700000300, "sort": 0, "timelen": 320}
                ]
              }
            }
        """.trimIndent()

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
                return if (path == "/v4/get_list_all_file_v3") {
                    ApiResponse(JsonParser.parseString(mockResponseJson).asJsonObject)
                } else {
                    ApiResponse(JsonObject())
                }
            }
        }
        val store = MemoryStore()
        val auth = AuthRepository(transport, store)
        val accounts = AccountRepository(store, auth, transport)
        val repo = MusicApiRepository(transport, accounts)
        store.saveSession(KugouSession("123", "mock-token"))

        // 1. 未自定义排序时（全 sort=0），官方默认顺序为按 addtime 降序（最新添加在前）且歌名不含后缀
        val (ver, officialTracks) = repo.fetchCloudPlaylistTracksWithVer(listId = 2L, type = 0)
        assertEquals(42L, ver)
        assertEquals(listOf("最新添加", "居中时间", "较早添加", "最早添加"), officialTracks.map { it.title })

        // 2. 已自定义排序（sort=1,2）且之后新增两首（sort=0）：新加曲目置顶按新到旧，随后按 sort 升序
        val mixedCustom = listOf(
            Song(id = "1", hash = "h1", title = "自定义第二", artist = "B", fileId = 10, addTimeSec = 1000L, cloudSortOrder = 2L),
            Song(id = "2", hash = "h2", title = "自定义第一", artist = "A", fileId = 11, addTimeSec = 2000L, cloudSortOrder = 1L),
            Song(id = "3", hash = "h3", title = "新加较早", artist = "C", fileId = 12, addTimeSec = 3000L, cloudSortOrder = 0L),
            Song(id = "4", hash = "h4", title = "新加最新", artist = "D", fileId = 13, addTimeSec = 4000L, cloudSortOrder = 0L)
        )
        val orderedMixed = sortCloudPlaylistTracksToOfficialOrder(mixedCustom)
        assertEquals(listOf("新加最新", "新加较早", "自定义第一", "自定义第二"), orderedMixed.map { it.title })

        // 3. 校验全部 6 种 PlaylistSortMode
        val newest = com.concept.music.ui.library.sortPlaylistSongs(officialTracks, com.concept.music.ui.library.PlaylistSortMode.ADDED_NEWEST)
        val oldest = com.concept.music.ui.library.sortPlaylistSongs(officialTracks, com.concept.music.ui.library.PlaylistSortMode.ADDED_OLDEST)
        val longest = com.concept.music.ui.library.sortPlaylistSongs(officialTracks, com.concept.music.ui.library.PlaylistSortMode.DURATION_DESC)
        assertEquals(listOf("最新添加", "居中时间", "较早添加", "最早添加"), newest.map { it.title })
        assertEquals(listOf("最早添加", "较早添加", "居中时间", "最新添加"), oldest.map { it.title })
        assertEquals("居中时间", longest.first().title)
    }

    @Test
    fun uploadPlaylistCustomCoverAndUpdatePlaylistPicProtocol() = runTest {
        val mockServer = okhttp3.mockwebserver.MockWebServer()
        mockServer.enqueue(
            okhttp3.mockwebserver.MockResponse()
                .setResponseCode(200)
                .setBody("""{"IsSuccess":true,"FileName":"202611270088.jpg"}""")
        )
        mockServer.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        try {
            val capturedPayloads = mutableListOf<JsonObject>()
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
                ): ApiResponse = ApiResponse(JsonObject())

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
                    assertEquals("/v1/modify_list", path)
                    assertFalse("加密请求应包含 RSA 参数 p", query["p"].isNullOrBlank())
                    assertNotNull("加密请求体不应为空", rawBodyBytes)
                    assertTrue("加密请求体字节长度应大于 0", (rawBodyBytes?.size ?: 0) > 0)
                    capturedPayloads.add(JsonObject().apply {
                        addProperty("path", path)
                        addProperty("bodySize", rawBodyBytes?.size ?: 0)
                    })
                    return """{"status":1,"err_code":0}""".toByteArray(Charsets.UTF_8)
                }
            }

            val store = MemoryStore()
            val auth = AuthRepository(transport, store)
            val accounts = AccountRepository(store, auth, transport)
            val repo = MusicApiRepository(transport, accounts)
            store.saveSession(KugouSession("123", "mock-token"))

            // 构造最小合法 JPEG 头（SOI + SOF0 16x16 + EOI）
            val validJpeg = byteArrayOf(
                0xFF.toByte(), 0xD8.toByte(),
                0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08, 0x00, 0x10, 0x00, 0x10, 0x01, 0x01, 0x11, 0x00,
                0xFF.toByte(), 0xD9.toByte()
            )
            val uploadUrl = "http://127.0.0.1:${mockServer.port}/imageupload/stream.php"
            val likedPl = Playlist(id = "like", title = "我喜欢", listId = 2L, isCreatedByMe = true, isLiked = true)

            val (ok, fileName) = repo.updatePlaylistCoverOnCloud(
                playlist = likedPl,
                imageBytes = validJpeg,
                uploadEndpointOverride = uploadUrl
            )
            assertEquals("图床应返回 FileName", "202611270088.jpg", fileName)
            assertTrue("封面上传并同步云端应返回 true (payloads=${capturedPayloads.size})", ok)
            assertEquals(1, capturedPayloads.size)

            val recorded = mockServer.takeRequest()
            assertEquals("custom", recorded.requestUrl?.queryParameter("type"))
            assertEquals(".jpg", recorded.requestUrl?.queryParameter("extendName"))
            assertEquals("1", recorded.requestUrl?.queryParameter("jsonResponse"))
            assertNull("图床上传请求严禁携带用户 token", recorded.requestUrl?.queryParameter("token"))
            assertNull("图床上传请求严禁携带 Cookie", recorded.getHeader("Cookie"))
            assertTrue(imageUrl("custom/202611270088.jpg").startsWith("https://imge.kugou.com/custom/400/"))
            assertTrue(imageUrl("stdmusic/202611270088.jpg").startsWith("https://imge.kugou.com/stdmusic/400/"))
        } finally {
            mockServer.shutdown()
        }
    }

    @Test
    fun coverOverridePathGuardAndBatchDownloadDedupeAndPermissionGate() {
        val tempRoot = java.io.File(System.getProperty("java.io.tmpdir"), "breeze_cover_guard_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val safeFile = java.io.File(tempRoot, "cover_like_1.jpg")
            assertNotNull(
                com.concept.music.data.repository.PlaylistCoverOverrideRepository.resolveManagedCoverFileInRoot(
                    tempRoot,
                    safeFile.toURI().toString()
                )
            )
            val escaped = java.io.File(tempRoot, "../outside_secret.jpg")
            assertNull(
                "必须拒绝越出受管目录的路径穿越封面路径",
                com.concept.music.data.repository.PlaylistCoverOverrideRepository.resolveManagedCoverFileInRoot(
                    tempRoot,
                    escaped.absolutePath
                )
            )
            assertNull(
                "网络 URL 不应被误判为本地覆盖文件",
                com.concept.music.data.repository.PlaylistCoverOverrideRepository.resolveManagedCoverFileInRoot(
                    tempRoot,
                    "https://imge.kugou.com/custom/400/test.jpg"
                )
            )
        } finally {
            tempRoot.deleteRecursively()
        }

        // 验证下载权限默认关闭，以及批量下载去重逻辑
        com.concept.music.service.AppPermissionManager.resetForTest()
        assertFalse(
            "音乐下载权限必须默认关闭",
            com.concept.music.service.AppPermissionManager.isPermissionEnabled(
                com.concept.music.service.AppPermissionType.MUSIC_DOWNLOAD
            )
        )
        val s1 = Song(id = "1", hash = "AAAA", title = "歌1", artist = "歌手1")
        val s1Dup = Song(id = "1", hash = "aaaa", title = "歌1", artist = "歌手1")
        val s2Active = Song(id = "2", hash = "BBBB", title = "歌2", artist = "歌手2")
        val s3Done = Song(id = "3", hash = "CCCC", title = "歌3", artist = "歌手3")
        val existingTasks = listOf(
            com.concept.music.service.DownloadTaskItem(
                taskId = "t_active",
                song = s2Active,
                requestedQuality = com.concept.music.data.model.AudioQuality.HIGH,
                status = com.concept.music.service.DownloadTaskStatus.DOWNLOADING
            ),
            com.concept.music.service.DownloadTaskItem(
                taskId = "t_done",
                song = s3Done,
                requestedQuality = com.concept.music.data.model.AudioQuality.HIGH,
                status = com.concept.music.service.DownloadTaskStatus.COMPLETED
            )
        )
        val filtered = com.concept.music.service.MusicDownloadManager.filterPendingBatchDownloadSongs(
            requestedSongs = listOf(s1, s1Dup, s2Active, s3Done),
            existingTasks = existingTasks
        )
        assertEquals(listOf("1", "3"), filtered.map { it.id })
    }

    @Test
    fun normalizeStdMusicPicPathAndCustomCoverDetection() {
        // 标准歌曲封面 URL 与相对路径均归一化为 stdmusic/<FileName>
        assertEquals(
            "stdmusic/20250217144212257577.jpg",
            normalizeKugouStdMusicPicPath("https://imge.kugou.com/stdmusic/400/20250217/20250217144212257577.jpg")
        )
        assertEquals(
            "stdmusic/20210111184454391203.jpg",
            normalizeKugouStdMusicPicPath("http://imge.kugou.com/stdmusic/{size}/20210111/20210111184454391203.jpg?t=1")
        )
        assertEquals(
            "stdmusic/20210111184454391203.jpg",
            normalizeKugouStdMusicPicPath("stdmusic/20210111/20210111184454391203.jpg")
        )
        assertEquals(
            "stdmusic/20210111184454391203.jpg",
            normalizeKugouStdMusicPicPath("stdmusic/20210111184454391203.jpg")
        )

        // 拒绝本地路径、歌手头像、自定义图床及非酷狗域名
        assertNull(normalizeKugouStdMusicPicPath(""))
        assertNull(normalizeKugouStdMusicPicPath("file:///cache/local_cover_1.jpg"))
        assertNull(normalizeKugouStdMusicPicPath("content://media/external/audio/albumart/12"))
        assertNull(normalizeKugouStdMusicPicPath("https://imge.kugou.com/custom/400/202611270088.jpg"))
        assertNull(normalizeKugouStdMusicPicPath("https://imge.kugou.com/singerimg/400/20250101/artist.jpg"))
        assertNull(normalizeKugouStdMusicPicPath("https://example.com/stdmusic/400/fake.jpg"))

        // 自定义封面识别：仅 custom / 本机 custom_playlist_covers / hasCustomCover 为 true，空白与 stdmusic 均为 false
        assertFalse(Playlist(id = "1", title = "新建无封面", coverUrl = "").isCustomCoverApplied)
        assertFalse(
            Playlist(
                id = "2",
                title = "歌曲封面同步歌单",
                coverUrl = "https://imge.kugou.com/stdmusic/400/20250217/20250217144212257577.jpg"
            ).isCustomCoverApplied
        )
        assertFalse(
            Playlist(
                id = "3",
                title = "本地歌单自动封面",
                coverUrl = "file:///data/user/0/com.concept.music/cache/local_music_covers/t1.jpg",
                isLocalOnly = true
            ).isCustomCoverApplied
        )
        assertTrue(
            Playlist(
                id = "4",
                title = "云端自定义封面",
                coverUrl = "https://imge.kugou.com/custom/400/202611270088.jpg"
            ).isCustomCoverApplied
        )
        assertTrue(
            Playlist(
                id = "5",
                title = "本机覆盖自定义封面",
                coverUrl = "file:///data/user/0/com.concept.music/files/custom_playlist_covers/cover_1.jpg"
            ).isCustomCoverApplied
        )
    }

    @Test
    fun syncPlaylistSongCoverOnCloudUsesModifyListPicWithTotalVerFallbackAndRespectsLocalGuard() = runTest {
        val capturedPaths = mutableListOf<String>()
        val capturedRouters = mutableListOf<String>()
        var modifyPicAttempts = 0

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
                capturedPaths.add(path)
                return when (path) {
                    "/v7/get_all_list" -> ApiResponse(
                        JsonParser.parseString(
                            """
                            {
                              "status": 1,
                              "data": {
                                "total_ver": 888,
                                "info": [
                                  {"listid": 1001, "name": "无封面歌单", "pic": "", "count": 1, "type": 0},
                                  {"listid": 1002, "name": "歌曲封面歌单", "pic": "stdmusic/20250101000001.jpg", "count": 2, "type": 0},
                                  {"listid": 1003, "name": "自定义封面歌单", "pic": "custom/202611270088.jpg", "count": 3, "type": 0}
                                ]
                              }
                            }
                            """.trimIndent()
                        ).asJsonObject
                    )
                    "/cloudlist.service/v5/add_list", "/cloudlist.service/v6/add_song" ->
                        ApiResponse(JsonParser.parseString("""{"status":1,"data":{"listid":1001}}""").asJsonObject)
                    else -> ApiResponse(JsonObject())
                }
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
                capturedPaths.add(path)
                capturedRouters.add(headers["x-router"].orEmpty())
                if (path == "/v1/modify_list_pic") {
                    modifyPicAttempts++
                    assertFalse("加密请求应包含 RSA 参数 p", query["p"].isNullOrBlank())
                    assertTrue("加密请求体不应为空", (rawBodyBytes?.size ?: 0) > 0)
                    // 模拟首次 total_ver 过期失败，第二次 total_ver=0 兜底成功
                    return if (modifyPicAttempts == 1) {
                        """{"status":0,"err_code":1002}""".toByteArray(Charsets.UTF_8)
                    } else {
                        """{"status":1,"err_code":0}""".toByteArray(Charsets.UTF_8)
                    }
                }
                return """{"status":1,"err_code":0}""".toByteArray(Charsets.UTF_8)
            }
        }

        val store = MemoryStore()
        val auth = AuthRepository(transport, store)
        val accounts = AccountRepository(store, auth, transport)
        val repo = MusicApiRepository(transport, accounts)
        store.saveSession(KugouSession("123", "mock-token"))

        // 1. 验证 userPlaylists 准确识别自定义封面与非自定义封面
        val playlists = repo.userPlaylists()
        val emptyCoverPl = playlists.first { it.listId == 1001L }
        val stdCoverPl = playlists.first { it.listId == 1002L }
        val customCoverPl = playlists.first { it.listId == 1003L }
        assertFalse(emptyCoverPl.isCustomCoverApplied)
        assertFalse(stdCoverPl.isCustomCoverApplied)
        assertTrue(customCoverPl.isCustomCoverApplied)

        // 2. 验证同步歌曲封面到云端：走 /v1/modify_list_pic + cloudlist 路由 + 自动重试兜底
        val synced = repo.syncPlaylistSongCoverOnCloud(
            playlist = emptyCoverPl,
            songCoverUrl = "https://imge.kugou.com/stdmusic/400/20250217/20250217144212257577.jpg"
        )
        assertTrue("云端歌单歌曲封面同步应在兜底重试后成功", synced)
        assertEquals(2, modifyPicAttempts)
        assertTrue(capturedRouters.all { it == "cloudlist.service.kugou.com" })

        // 3. 验证纯本地歌单与本地封面绝不触发任何云端请求
        val beforeCalls = capturedPaths.size
        val localPl = Playlist(id = "local_pl_99", title = "本地歌单", isLocalOnly = true, listId = 1001L)
        assertFalse(
            repo.syncPlaylistSongCoverOnCloud(
                playlist = localPl,
                songCoverUrl = "https://imge.kugou.com/stdmusic/400/20250217/20250217144212257577.jpg"
            )
        )
        assertFalse(
            repo.syncPlaylistSongCoverOnCloud(
                playlist = emptyCoverPl,
                songCoverUrl = "file:///cache/local_track_cover.jpg"
            )
        )
        assertEquals("纯本地歌单或本地图片路径严禁触发云端请求", beforeCalls, capturedPaths.size)

        // 4. 验证导入歌单时自动将歌曲封面同步至云端
        val songWithCover = Song(
            id = "s_imp_1",
            hash = "E7B82245C4B40B5DB3884D7759086111",
            title = "晴天",
            artist = "周杰伦",
            coverUrl = "https://imge.kugou.com/stdmusic/400/20250217/20250217144212257577.jpg"
        )
        val imported = repo.importPlaylistSongsDirect("无封面歌单", listOf(songWithCover))
        assertEquals(1, imported)
        assertTrue("导入歌单后应自动触发 /v1/modify_list_pic 同步封面", modifyPicAttempts >= 3)
    }
}
