package com.concept.music.data.kugou

import com.concept.music.data.model.Song
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset

class KugouProtocolTest {
    @Test fun md5MidAndSignatureMatchUpstreamFixture() {
        assertEquals("104602365767877882724869659726014956567", KugouSigner.mid("fixture-guid"))
        val params = mapOf("mid" to "1234", "appid" to "3116", "clienttime" to "1770000000", "token" to "fixture-token", "userid" to "123")
        assertEquals("c2f83c3eeb8c6d72bfcdeedb0511c87e", KugouSigner.signature(params, "{\"mobile\":\"13800000000\",\"code\":\"123456\"}"))
        assertEquals("358d8457b5451893c970670a977256c4", KugouSigner.paramsKey(1770000000123L))
    }

    @Test fun fixedAesAndRawRsaAreDeterministic() {
        assertEquals("8270cd5eba410362918595d0cc6188dd", KugouSigner.aes("|1770000000123", "5e4ef500e9597fe004bd09a46d8add98", "04bd09a46d8add98"))
        assertEquals("73143dd1ae3d044b1eb18cb4178c7ead1c10607262e8afe75d954c2b4a5eb365b1913e1ab60fc516ad3154114c6eefe9b47a7b6895fb0f67ce7422b62f75255a574be399ac6cc2f39e5fb9a543c55523be17e52f4aecee6f8a497568d65f61559a204967195ab22dca1c6d27d85e2f7b0ae9262bb51808e2ce0d9190e5d5c998", KugouSigner.rsa("{\"clienttime_ms\":1770000000123,\"key\":\"0123456789abcdef\"}"))
        val pkcs1Hex = KugouSigner.rsaPkcs1("{\"aes\":\"abcdef\",\"uid\":123,\"token\":\"t\"}")
        assertEquals(256, pkcs1Hex.length)
        assertEquals(pkcs1Hex.lowercase(), pkcs1Hex)
    }

    @Test fun vipParsesTypesAndDateRepresentationsIncludingDvipQvip() {
        val root = JsonObject().apply { add("busi_vip", com.google.gson.JsonArray().apply {
            add(JsonObject().apply { addProperty("product_type", "dvip"); addProperty("is_vip", 1); addProperty("vip_end_time", "2026-12-01 10:00:00") })
            add(JsonObject().apply { addProperty("product_type", "svip"); addProperty("is_vip", 0); addProperty("vip_end_time", "0") })
        }) }
        val snapshot = VipSnapshot.parse(root, java.time.Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), java.time.ZoneOffset.UTC))
        assertTrue(snapshot.active("tvip")); assertFalse(snapshot.active("svip"))
        assertEquals(Instant.parse("2026-12-01T02:00:00Z"), snapshot.benefits.first().expiresAt)
    }

    @Test fun responseDoesNotTreatMissingStatusAsSuccess() {
        val response = ApiResponse(JsonObject().apply { add("data", JsonObject()) })
        assertFalse(response.success)
        try { response.requireSuccess(); fail("missing status must fail") } catch (expected: ApiFailure) { assertNull(expected.code) }
    }

    @Test fun registerDeviceSavesDfidWhenCurrentDfidIsPlaceholderOrUnregistered() = kotlinx.coroutines.test.runTest {
        var savedDfid = "2localfallbackdfid000000"
        var registeredFlag = false
        val store = object : SessionStore by MemoryStore() {
            override fun device() = DeviceIdentity("fixture-guid", "1234", "dev", dfid = savedDfid)
            override fun isDfidRegistered() = registeredFlag
            override fun saveDfid(dfid: String) {
                savedDfid = dfid
                registeredFlag = true
            }
        }
        val transport = object : KugouTransport {
            override suspend fun call(
                path: String, session: KugouSession?, query: Map<String, String>, body: JsonObject?,
                host: String, method: String, web: Boolean, headers: Map<String, String>, device: DeviceIdentity?
            ): ApiResponse = ApiResponse(JsonObject())

            override suspend fun requestRawBytes(
                path: String, session: KugouSession?, query: Map<String, String>, rawBody: String?,
                host: String, method: String, headers: Map<String, String>, sign: Boolean,
                includeDefaultParams: Boolean, device: DeviceIdentity?
            ): ByteArray {
                assertEquals("/risk/v2/r_register_dev", path)
                assertEquals("https://userservice.kugou.com", host)
                return "{\"status\":1,\"data\":{\"dfid\":\"dfid_concept_2026\"}}".toByteArray(Charsets.UTF_8)
            }
        }
        val auth = AuthRepository(transport, store)
        val dfid = auth.registerDevice()
        assertEquals("dfid_concept_2026", dfid)
        assertEquals("dfid_concept_2026", savedDfid)
        assertTrue(store.isDfidRegistered())
    }

    @Test fun csccReporterMaintainsConsistentSongStateOnRapidSwitch() {
        val songA = Song(id = "song_a", title = "Song A", artist = "Artist A")
        val songB = Song(id = "song_b", title = "Song B", artist = "Artist B")
        KugouCsccReporter.currentPlayingSongId = songB.id
        KugouCsccReporter.songStartTimeMs = 123456L
        // Ending previous songA after songB already started must NOT clear songB's state
        KugouCsccReporter.onSongEnd(songA, playedDurationMs = 5000L)
        assertEquals("song_b", KugouCsccReporter.currentPlayingSongId)
        assertEquals(123456L, KugouCsccReporter.songStartTimeMs)
    }

    @Test fun resolvePlaybackUsesPrivUrlEvenWhenVipTokenIsNull() = kotlinx.coroutines.test.runTest {
        val store = MemoryStore()
        var privUrlCalled = false
        val transport = object : KugouTransport {
            override suspend fun call(
                path: String, session: KugouSession?, query: Map<String, String>, body: JsonObject?,
                host: String, method: String, web: Boolean, headers: Map<String, String>, device: DeviceIdentity?
            ): ApiResponse = ApiResponse(JsonObject())

            override suspend fun callAdvanced(
                path: String, session: KugouSession?, query: Map<String, String>, body: JsonObject?,
                host: String, method: String, web: Boolean, headers: Map<String, String>, device: DeviceIdentity?,
                sign: Boolean, includeDefaultParams: Boolean
            ): ApiResponse {
                if (path == "/v6/priv_url") {
                    privUrlCalled = true
                    assertEquals(6, body?.get("vip")?.asInt)
                    assertEquals("a", body?.getAsJsonObject("tracker_param")?.get("viptoken")?.asString)
                    val mockPriv = "{\"status\":1,\"priv_status\":1,\"file_size\":28000000,\"bitrate\":920,\"extname\":\"flac\",\"url\":[\"http://vip.kugou.com/lossless.flac\"]}"
                    return ApiResponse(com.google.gson.JsonParser.parseString(mockPriv).asJsonObject)
                }
                return ApiResponse(JsonObject())
            }
        }
        val auth = AuthRepository(transport, store)
        val accounts = AccountRepository(store, auth, transport)
        val repo = MusicApiRepository(transport, accounts)
        val song = com.concept.music.data.model.Song(
            id = "s1",
            hash = "11111111111111111111111111111111",
            hashFlac = "22222222222222222222222222222222",
            title = "概念版无损测试",
            artist = "歌手",
            durationMs = 240_000L,
            isLossless = true
        )
        val res = repo.resolvePlayback(song, com.concept.music.data.model.AudioQuality.LOSSLESS)
        assertTrue(privUrlCalled)
        assertEquals("http://vip.kugou.com/lossless.flac", res.url)
        assertEquals(com.concept.music.data.model.AudioQuality.LOSSLESS, res.actualQuality)
    }

    @Test fun standardRsaKeyAndStandardSignaturesWorkWithoutBase64Exception() {
        val encrypted = KugouSigner.rsa("{\"clienttime_ms\":1770000000123,\"key\":\"0123456789abcdef\"}", useStandardKey = true)
        assertEquals(256, encrypted.length)
        assertEquals(encrypted.lowercase(), encrypted)

        val stdParamsKey = KugouSigner.paramsKeyStandard(1770000000123L)
        assertEquals(32, stdParamsKey.length)

        val stdSig = KugouSigner.signatureStandard(mapOf("appid" to "1005", "clientver" to "20489"), "{\"test\":1}")
        assertEquals(32, stdSig.length)
    }

    @Test fun userGradeInfoSelectsMaxDurationAcrossChannelsForAccurateOfficialTime() = kotlinx.coroutines.test.runTest {
        val store = MemoryStore()
        val transport = object : KugouTransport {
            override suspend fun call(
                path: String, session: KugouSession?, query: Map<String, String>, body: JsonObject?,
                host: String, method: String, web: Boolean, headers: Map<String, String>, device: DeviceIdentity?
            ): ApiResponse {
                if (path == "/v3/get_my_info") {
                    // Simulate Lite endpoint returning lagging 220788 minutes
                    val json = """{"status":1,"data":{"user_y_grade":11,"dura_With_M":220788,"r_listen":17435}}"""
                    return ApiResponse(com.google.gson.JsonParser.parseString(json).asJsonObject)
                }
                return ApiResponse(JsonObject())
            }

            override suspend fun callAdvanced(
                path: String, session: KugouSession?, query: Map<String, String>, body: JsonObject?,
                host: String, method: String, web: Boolean, headers: Map<String, String>, device: DeviceIdentity?,
                sign: Boolean, includeDefaultParams: Boolean
            ): ApiResponse {
                if (path == "/v4/get_grade_info") {
                    // Simulate Standard /v4/get_grade_info returning up-to-date 221062 minutes (~3684.4h)
                    val json = """{"status":1,"data":{"grade":11,"duration":221062,"next_grade_duration":225000}}"""
                    return ApiResponse(com.google.gson.JsonParser.parseString(json).asJsonObject)
                }
                return ApiResponse(JsonObject())
            }
        }
        val auth = AuthRepository(transport, store)
        val accounts = AccountRepository(store, auth, transport)
        val repo = MusicApiRepository(transport, accounts)
        val gradeInfo = repo.userGradeInfo()
        assertNotNull(gradeInfo)
        assertEquals(11, gradeInfo.level)
        assertEquals(221062L, gradeInfo.listenDurationMinutes)
        assertEquals("3684 小时 22 分", gradeInfo.displayListenTimeText)
    }

    @Test fun userGradeInfoAlignsWithStandardClientLocalDiffSecWhenCloudDsecIs220788Minutes() = kotlinx.coroutines.test.runTest {
        val store = MemoryStore().apply {
            rows = listOf(SavedAccount("874555157"))
            activeKey = "874555157"
            sessions["874555157"] = KugouSession("874555157", "lite-token")
        }
        var capturedV4AppId: String? = null
        val transport = object : KugouTransport {
            override suspend fun call(
                path: String, session: KugouSession?, query: Map<String, String>, body: JsonObject?,
                host: String, method: String, web: Boolean, headers: Map<String, String>, device: DeviceIdentity?
            ): ApiResponse = ApiResponse(JsonObject())

            override suspend fun callAdvanced(
                path: String, session: KugouSession?, query: Map<String, String>, body: JsonObject?,
                host: String, method: String, web: Boolean, headers: Map<String, String>, device: DeviceIdentity?,
                sign: Boolean, includeDefaultParams: Boolean
            ): ApiResponse {
                if (path == "/v4/get_grade_info") {
                    capturedV4AppId = query["appid"]
                    val json = """{"status":1,"error_code":0,"data":{"p_grade":11,"p_current_point":298810,"p_next_grade_point":300000,"d_sec":13248244,"duration":220804}}"""
                    return ApiResponse(com.google.gson.JsonParser.parseString(json).asJsonObject)
                }
                return ApiResponse(JsonObject())
            }
        }
        val auth = AuthRepository(transport, store)
        val accounts = AccountRepository(store, auth, transport)
        val repo = MusicApiRepository(transport, accounts)
        val gradeInfo = repo.userGradeInfo()
        assertEquals("3116", capturedV4AppId)
        assertEquals(11, gradeInfo.level)
        assertEquals(13248244L, repo.lastServerRawDsec)
        assertEquals(13264624L, gradeInfo.totalListenSeconds)
        assertEquals(221077L, gradeInfo.listenDurationMinutes)
        assertEquals("3684 小时 37 分", gradeInfo.displayListenTimeText)
    }

    @Test fun rankSongsAllLoadsUpTo500SongsAndStreamsProgressPerPage() = kotlinx.coroutines.test.runTest {
        val store = MemoryStore()
        val transport = object : KugouTransport {
            override suspend fun call(
                path: String, session: KugouSession?, query: Map<String, String>, body: JsonObject?,
                host: String, method: String, web: Boolean, headers: Map<String, String>, device: DeviceIdentity?
            ): ApiResponse = ApiResponse(JsonObject())

            override suspend fun callAdvanced(
                path: String, session: KugouSession?, query: Map<String, String>, body: JsonObject?,
                host: String, method: String, web: Boolean, headers: Map<String, String>, device: DeviceIdentity?,
                sign: Boolean, includeDefaultParams: Boolean
            ): ApiResponse {
                if (path == "/api/v3/rank/song") {
                    val page = query["page"]?.toIntOrNull() ?: 1
                    val pageSize = query["pagesize"]?.toIntOrNull() ?: 100
                    val startIdx = (page - 1) * pageSize + 1
                    val listArr = com.google.gson.JsonArray()
                    for (i in startIdx until (startIdx + pageSize)) {
                        listArr.add(JsonObject().apply {
                            addProperty("audio_id", i)
                            addProperty("filename", "Singer $i - TopSong $i")
                            addProperty("hash", "hash_${i}_000000000000000000000000")
                            addProperty("duration", 200)
                        })
                    }
                    val resp = JsonObject().apply {
                        addProperty("status", 1)
                        add("data", JsonObject().apply {
                            addProperty("total", 500)
                            add("info", listArr)
                        })
                    }
                    return ApiResponse(resp)
                }
                return ApiResponse(JsonObject())
            }
        }
        val auth = AuthRepository(transport, store)
        val accounts = AccountRepository(store, auth, transport)
        val repo = MusicApiRepository(transport, accounts)
        val progressSizes = mutableListOf<Int>()
        val allSongs = repo.rankSongsAll(8888, maxSongs = 500) { partial ->
            progressSizes += partial.size
        }
        assertEquals(500, allSongs.size)
        assertEquals(listOf(100, 200, 300, 400, 500), progressSizes)
    }

    @Test fun webSignatureUsesUpstreamWebSaltAndTransportAvoidsTwoLevelSubdomainSslError() {
        val webParams = mapOf("appid" to "1001", "plat" to "4", "srcappid" to "2919", "type" to "1")
        val expectedRaw = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwtappid=1001plat=4srcappid=2919type=1NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
        assertEquals(KugouSigner.md5(expectedRaw), KugouSigner.signature(webParams, "", web = true))

        assertEquals(
            "http://login.user.kugou.com",
            DirectKugouTransport.enforceHttpsForSensitiveHost("https://login.user.kugou.com", null, emptyMap())
        )
        val endpoints = DirectKugouTransport.buildCandidateEndpoints("https://login.user.kugou.com", emptyMap())
        assertTrue(endpoints.any { it.first == "http://login.user.kugou.com" })
        assertTrue(endpoints.any { it.first == "https://gateway.kugou.com" && it.second["x-router"] == "login.user.kugou.com" })
    }

    @Test fun guestModePlaybackResolvesViaTrackerWithUserIdZeroAndCorrectRouter() = kotlinx.coroutines.test.runTest {
        val store = MemoryStore().apply { activeKey = null }
        var capturedTrackerRouter: String? = null
        var capturedUserId: String? = null
        val transport = object : KugouTransport {
            override suspend fun call(
                path: String, session: KugouSession?, query: Map<String, String>, body: JsonObject?,
                host: String, method: String, web: Boolean, headers: Map<String, String>, device: DeviceIdentity?
            ): ApiResponse = ApiResponse(JsonObject())

            override suspend fun callAdvanced(
                path: String, session: KugouSession?, query: Map<String, String>, body: JsonObject?,
                host: String, method: String, web: Boolean, headers: Map<String, String>, device: DeviceIdentity?,
                sign: Boolean, includeDefaultParams: Boolean
            ): ApiResponse {
                if (path == "/v5/url") {
                    capturedTrackerRouter = headers["x-router"]
                    capturedUserId = query["userid"]
                    val mockV5 = "{\"status\":1,\"fileSize\":4200000,\"bitRate\":128,\"extName\":\"mp3\",\"url\":[\"https://fs.android.kugou.com/guest_track.mp3\"]}"
                    return ApiResponse(com.google.gson.JsonParser.parseString(mockV5).asJsonObject)
                }
                return ApiResponse(JsonObject())
            }
        }
        val auth = AuthRepository(transport, store)
        val accounts = AccountRepository(store, auth, transport)
        accounts.select(null)
        val repo = MusicApiRepository(transport, accounts)
        val song = com.concept.music.data.model.Song(
            id = "guest_s1",
            hash = "abcdef1234567890abcdef1234567890",
            title = "颠 (OFF-KILTER)",
            artist = "吴莫愁",
            durationMs = 210_000L
        )
        val res = repo.resolvePlayback(song, com.concept.music.data.model.AudioQuality.STANDARD)
        assertEquals("tracker.kugou.com", capturedTrackerRouter)
        assertEquals("0", capturedUserId)
        assertEquals("https://fs.android.kugou.com/guest_track.mp3", res.url)
    }

    @Test fun qqHash33MatchesUpstreamReference() {
        assertEquals(0, KugouSigner.hash33(""))
        assertEquals(108966, KugouSigner.hash33("abc"))
        // 含高位溢出字符的长 qrsig 必须严格落在非负 31 位整数区间内并与逐位乘 33 同余
        val sample = "mK9xPq2L7vN4wR8sT1uY5zA3bC6dE0fG-hI_jJ"
        val expected = sample.fold(0) { acc, ch -> acc + (acc shl 5) + ch.code } and 0x7FFFFFFF
        val actual = KugouSigner.hash33(sample)
        assertEquals(expected, actual)
        assertTrue(actual >= 0)
    }

    @Test fun qqQrFlowCreatesPollsFollowsRedirectsAndExchangesKugouSession() = kotlinx.coroutines.test.runTest {
        fun minimalValidPng(width: Int = 240, height: Int = 240): ByteArray {
            val buf = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.BIG_ENDIAN)
            buf.put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            buf.putInt(13)
            buf.put("IHDR".toByteArray(Charsets.US_ASCII))
            buf.putInt(width)
            buf.putInt(height)
            buf.put(byteArrayOf(8, 6, 0, 0, 0))
            return buf.array()
        }

        okhttp3.mockwebserver.MockWebServer().use { qqServer ->
            val baseUrl = "http://127.0.0.1:${qqServer.port}"
            val xloginEscaped = "$baseUrl/cgi-bin/xlogin?appid=716027609\\x26h5sig=test_h5_sig\\x26daid=381"
            // 1. m_authorize 返回包含 xlogin src 的 HTML
            qqServer.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setBody("""<script>var src = "$xloginEscaped";</script>""")
            )
            // 2. xlogin 返回 pt_login_sig Cookie
            qqServer.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .addHeader("Set-Cookie", "pt_login_sig=sig_login_fixture_123; Path=/; Secure")
                    .setBody("ok")
            )
            // 3. ptqrshow 返回 PNG 与 qrsig Cookie
            qqServer.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "image/png")
                    .addHeader("Set-Cookie", "qrsig=qrsig_fixture_token_999; Path=/; Secure")
                    .setBody(okio.Buffer().write(minimalValidPng()))
            )
            // 4. ptqrlogin 首次轮询：66 等待扫码
            qqServer.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setBody("ptuiCB('66','0','','0','二维码未失效。','')")
            )
            // 5. ptqrlogin 第二次轮询：67 已扫码待确认
            qqServer.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setBody("ptuiCB('67','0','','0','二维码认证中。','')")
            )
            // 6. ptqrlogin 第三次轮询：0 成功，返回 check_sig 跳转链接
            qqServer.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setBody("ptuiCB('0','0','$baseUrl/check_sig?ptsigx=abc','0','登录成功！','微风测试')")
            )
            // 7. check_sig 第一跳：下发会话 Cookie 并 302 跳转到 login_jump
            qqServer.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(302)
                    .addHeader("Set-Cookie", "p_skey=pskey_hop1_ok; Path=/")
                    .addHeader("Location", "$baseUrl/oauth2.0/login_jump")
            )
            // 8. login_jump 第二跳：302 跳转至 auth://tauth.qq.com/#access_token=...&openid=...
            qqServer.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "auth://tauth.qq.com/#access_token=QQ_ACCESS_TOKEN_XYZ&expires_in=7776000&openid=QQ_OPENID_123456")
            )

            val fixedClock = Clock.fixed(Instant.parse("2026-11-28T00:00:00Z"), ZoneOffset.UTC)
            val qqGateway = DirectQqQrAuthGateway(
                clock = fixedClock,
                mAuthorizeBaseUrl = baseUrl,
                ptLoginBaseUrl = baseUrl,
                allowLoopbackForTest = true
            )

            var capturedPath: String? = null
            var capturedRouter: String? = null
            var capturedBody: JsonObject? = null
            val kugouTransport = object : KugouTransport {
                override suspend fun call(
                    path: String, session: KugouSession?, query: Map<String, String>, body: JsonObject?,
                    host: String, method: String, web: Boolean, headers: Map<String, String>, device: DeviceIdentity?
                ): ApiResponse {
                    capturedPath = path
                    capturedRouter = headers["x-router"]
                    capturedBody = body
                    assertEquals("POST", method)
                    assertEquals("https://gateway.kugou.com", host)
                    assertEquals(1, body?.number("partnerid"))
                    assertEquals(DirectQqQrAuthGateway.QQ_LITE_APP_ID, body?.text("third_appid"))
                    assertEquals("QQ_OPENID_123456", body?.text("openid"))
                    assertFalse(body?.text("params").isNullOrBlank())
                    assertFalse(body?.text("pk").isNullOrBlank())
                    return ApiResponse(
                        com.google.gson.JsonParser.parseString(
                            """{"status":1,"error_code":0,"data":{"userid":"99887766","token":"kugou_qq_token_ok","t1":"kugou_t1_qq","vip_token":"kugou_vip_tok","nickname":"QQ微风用户","pic":"https://imge.kugou.com/avatar.jpg"}}"""
                        ).asJsonObject
                    )
                }
            }

            val store = MemoryStore()
            val auth = AuthRepository(kugouTransport, store, clock = fixedClock, qqQrGateway = qqGateway)

            // 创建 QQ 二维码并验证参数
            val qrSession = auth.createQqQr()
            assertTrue(qrSession.imageUrl.startsWith("data:image/png;base64,"))
            assertEquals("qrsig_fixture_token_999", qrSession.qrsig)
            assertEquals(KugouSigner.hash33("qrsig_fixture_token_999"), qrSession.ptQrToken)
            assertEquals("sig_login_fixture_123", qrSession.ptLoginSig)

            // 轮询 66 -> 67 -> 0
            val waitRes = auth.pollQqQr(qrSession)
            assertTrue(waitRes is QqQrPollResult.Waiting)
            val scannedRes = auth.pollQqQr(qrSession)
            assertTrue(scannedRes is QqQrPollResult.Scanned)
            val authRes = auth.pollQqQr(qrSession)
            assertTrue(authRes is QqQrPollResult.Authorized)
            val cred = (authRes as QqQrPollResult.Authorized).credential
            assertEquals("QQ_OPENID_123456", cred.openId)
            assertEquals("QQ_ACCESS_TOKEN_XYZ", cred.accessToken)

            // 验证多跳重定向中第 2 跳携带了第 1 跳下发的 p_skey Cookie
            assertEquals(8, qqServer.requestCount)
            val recordedRequests = buildList {
                repeat(8) {
                    add(requireNotNull(qqServer.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS)))
                }
            }
            val loginJumpReq = recordedRequests.last()
            assertTrue(
                "check_sig 下发的 Cookie 必须传递至后续重定向跳",
                loginJumpReq.getHeader("Cookie").orEmpty().contains("p_skey=pskey_hop1_ok")
            )

            // 使用 QQ 凭证换取酷狗概念版会话
            val loginSuccess = auth.loginByQq(cred.openId, cred.accessToken)
            assertEquals("/v6/login_by_openplat", capturedPath)
            assertEquals("login.user.kugou.com", capturedRouter)
            assertNotNull(capturedBody)
            assertEquals("99887766", loginSuccess.session.userId)
            assertEquals("kugou_qq_token_ok", loginSuccess.session.token)
            assertEquals("kugou_t1_qq", loginSuccess.session.t1)
            assertEquals("kugou_vip_tok", loginSuccess.session.vipToken)
            assertEquals("QQ微风用户", loginSuccess.profile.nickname)
        }
    }

    @Test fun qqQrPollHandlesExpiredAndRefusedStates() = kotlinx.coroutines.test.runTest {
        okhttp3.mockwebserver.MockWebServer().use { qqServer ->
            val baseUrl = "http://127.0.0.1:${qqServer.port}"
            qqServer.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setBody("ptuiCB('65','0','','0','二维码已失效。','')")
            )
            qqServer.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setBody("ptuiCB('68','0','','0','本次登录已被拒绝','')")
            )

            val gateway = DirectQqQrAuthGateway(
                mAuthorizeBaseUrl = baseUrl,
                ptLoginBaseUrl = baseUrl,
                allowLoopbackForTest = true
            )
            val dummySession = QqQrSession(
                imageUrl = "data:image/png;base64,AA==",
                expiresAt = System.currentTimeMillis() + 60_000L,
                qrsig = "sig",
                ptQrToken = KugouSigner.hash33("sig"),
                ptLoginSig = "login_sig",
                ptOpenLoginData = "appid=716027609",
                xloginUrl = "$baseUrl/cgi-bin/xlogin?appid=716027609",
                cookies = mapOf("qrsig" to "sig", "pt_login_sig" to "login_sig")
            )

            val expired = gateway.pollQqQr(dummySession)
            assertTrue(expired is QqQrPollResult.Expired)
            val refused = gateway.pollQqQr(dummySession)
            assertTrue(refused is QqQrPollResult.Refused)
            assertEquals("本次登录已被拒绝", (refused as QqQrPollResult.Refused).message)
        }
    }
}
