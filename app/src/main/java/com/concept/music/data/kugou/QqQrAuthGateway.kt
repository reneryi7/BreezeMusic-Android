package com.concept.music.data.kugou

import com.concept.music.service.AppPermissionManager
import com.concept.music.service.AppPermissionType
import com.concept.music.util.SecurityGuards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface QqQrAuthGateway {
    suspend fun createQqQr(): QqQrSession
    suspend fun pollQqQr(session: QqQrSession): QqQrPollResult
}

/**
 * 酷狗概念版 QQ 扫码登录独立 OAuth 安全网关（纯 Kotlin 端侧直连，零后端、零硬编码 AppSecret）。
 * 对齐 MakcRe/KuGouMusicApi module/login_qq_qr_create.js & login_qq_qr_check.js：
 * 1. 使用独立 OkHttpClient，不放宽酷狗主网关 (NetworkClientProvider) 的 *.kugou.com 凭据隔离边界；
 * 2. 严格白名单限制仅可访问受信任的 QQ OAuth HTTPS 域名，禁用自动跳转并逐跳防 SSRF；
 * 3. 会话 Cookie 仅在单次扫码内存态流转，绝不持久化，绝不附带酷狗内部 Header（dfid/mid/kg-*）与 Token。
 */
class DirectQqQrAuthGateway(
    private val client: OkHttpClient = defaultQqOAuthClient,
    private val clock: Clock = Clock.systemUTC(),
    private val mAuthorizeBaseUrl: String = DEFAULT_M_AUTHORIZE_BASE_URL,
    private val ptLoginBaseUrl: String = DEFAULT_PT_LOGIN_BASE_URL,
    private val allowLoopbackForTest: Boolean = false
) : QqQrAuthGateway {

    companion object {
        const val QQ_LITE_APP_ID = "101706348"
        const val KUGOU_APK_SIG_MD5 = "fe4a24d80fcf253a00676a808f62c2c6"
        const val PT_LOGIN_APP_ID = "716027609"
        const val PT_LOGIN_DAID = "381"
        const val DEFAULT_M_AUTHORIZE_BASE_URL = "https://openmobile.qq.com"
        const val DEFAULT_PT_LOGIN_BASE_URL = "https://xui.ptlogin2.qq.com"
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val MAX_QR_IMAGE_BYTES = 1024 * 1024
        private const val MAX_REDIRECT_HOPS = 10
        private const val QR_TTL_SECONDS = 180L

        private val ALLOWED_QQ_OAUTH_PARENT_DOMAINS = setOf(
            "openmobile.qq.com",
            "ptlogin2.qq.com",
            "graph.qq.com",
            "connect.qq.com"
        )

        private val SRC_URL_REGEX = Regex("""src\s*=\s*"([^"]+)"""")
        private val HEX_ESCAPE_REGEX = Regex("""\\x([0-9A-Fa-f]{2})""")
        private val PTUI_CB_REGEX = Regex("""ptuiCB\((.*)\)""", setOf(RegexOption.DOT_MATCHES_ALL))
        private val SINGLE_QUOTED_ARG_REGEX = Regex("""'((?:\\.|[^'\\])*)'""")
        private val URL_OPENID_REGEX = Regex("""(?:[?&#]|^)openid=([^&#\s]+)""", RegexOption.IGNORE_CASE)
        private val URL_ACCESS_TOKEN_REGEX = Regex("""(?:[?&#]|^)access_token=([^&#\s]+)""", RegexOption.IGNORE_CASE)
        private val BODY_OPENID_REGEX = Regex("""openid["']?\s*[:=]\s*["']?([A-Za-z0-9_%\-]+)""", RegexOption.IGNORE_CASE)
        private val BODY_ACCESS_TOKEN_REGEX = Regex("""access_token["']?\s*[:=]\s*["']?([A-Za-z0-9_%\-]+)""", RegexOption.IGNORE_CASE)
        private val CUSTOM_SCHEME_REGEX = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://""")

        private val secureRandom = SecureRandom()

        internal val defaultQqOAuthClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .dns(NetworkClientProvider.safeTransportDns)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectionPool(NetworkClientProvider.sharedConnectionPool)
                .dispatcher(NetworkClientProvider.sharedDispatcher)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        /**
         * 校验 QQ OAuth 目标链接是否严格位于受信任的腾讯 OAuth HTTPS 域名白名单内（防 SSRF / 凭据跨域外泄）。
         */
        fun isAllowedQqOAuthUrl(rawUrl: String, allowLoopbackForTest: Boolean = false): Boolean {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty() || trimmed.length > 8192) return false
            if (trimmed.any { it.code < 0x20 }) return false
            val parsed = trimmed.toHttpUrlOrNull() ?: return false
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
            val host = parsed.host.trimEnd('.').lowercase(Locale.ROOT)
            if (allowLoopbackForTest && NetworkClientProvider.isLocalLoopbackOrJvmMockHost(host)) {
                return parsed.scheme == "http" || parsed.scheme == "https"
            }
            if (parsed.scheme != "https") return false
            if (SecurityGuards.isDisallowedNonPublicHost(host)) return false
            return ALLOWED_QQ_OAUTH_PARENT_DOMAINS.any { parent ->
                SecurityGuards.matchesDomainOrSubdomain(host, parent)
            }
        }

        internal fun normalizeQqHopUrl(rawUrl: String, allowLoopbackForTest: Boolean = false): String {
            val trimmed = rawUrl.trim()
            val withScheme = when {
                trimmed.startsWith("//") -> "https:$trimmed"
                trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                CUSTOM_SCHEME_REGEX.containsMatchIn(trimmed) -> trimmed
                else -> "https://$trimmed"
            }
            if (!allowLoopbackForTest && withScheme.startsWith("http://", ignoreCase = true)) {
                val parsed = withScheme.toHttpUrlOrNull()
                val host = parsed?.host?.trimEnd('.')?.lowercase(Locale.ROOT).orEmpty()
                if (ALLOWED_QQ_OAUTH_PARENT_DOMAINS.any { SecurityGuards.matchesDomainOrSubdomain(host, it) }) {
                    return "https://" + withScheme.substring(7)
                }
            }
            return withScheme
        }

        internal fun encodeUriComponent(value: String): String {
            return URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~")
        }

        internal fun parsePtuiCallback(body: String): List<String>? {
            val match = PTUI_CB_REGEX.find(body) ?: return null
            val inner = match.groupValues[1]
            val parts = SINGLE_QUOTED_ARG_REGEX.findAll(inner).map { arg ->
                arg.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
            }.toList()
            return parts.takeIf { it.size >= 5 }
        }

        internal fun extractCredentialFromUrl(rawUrl: String): QqOAuthCredential? {
            if (rawUrl.isBlank()) return null
            val openId = URL_OPENID_REGEX.find(rawUrl)?.groupValues?.getOrNull(1)?.let(::safeUrlDecode)?.takeIf { it.isNotBlank() }
            val accessToken = URL_ACCESS_TOKEN_REGEX.find(rawUrl)?.groupValues?.getOrNull(1)?.let(::safeUrlDecode)?.takeIf { it.isNotBlank() }
            return if (openId != null && accessToken != null) QqOAuthCredential(openId, accessToken) else null
        }

        internal fun extractCredentialFromText(text: String): QqOAuthCredential? {
            if (text.isBlank()) return null
            val openId = BODY_OPENID_REGEX.find(text)?.groupValues?.getOrNull(1)?.let(::safeUrlDecode)?.takeIf { it.isNotBlank() }
            val accessToken = BODY_ACCESS_TOKEN_REGEX.find(text)?.groupValues?.getOrNull(1)?.let(::safeUrlDecode)?.takeIf { it.isNotBlank() }
            return if (openId != null && accessToken != null) QqOAuthCredential(openId, accessToken) else null
        }

        private fun safeUrlDecode(raw: String): String =
            runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).trim()

        private fun decodeEscapedJsUrl(raw: String): String {
            val hexDecoded = HEX_ESCAPE_REGEX.replace(raw) { m ->
                m.groupValues[1].toInt(16).toChar().toString()
            }
            return hexDecoded.replace("&amp;", "&").trim()
        }

        private fun mergeCookies(target: MutableMap<String, String>, setCookieHeaders: List<String>) {
            for (header in setCookieHeaders) {
                val pair = header.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val key = pair.substring(0, eq).trim()
                    val value = pair.substring(eq + 1).trim()
                    if (key.isNotEmpty()) {
                        if (value.isNotEmpty()) {
                            target[key] = value
                        } else if (!target.containsKey(key)) {
                            target[key] = ""
                        }
                    }
                }
            }
        }

        private fun formatCookieHeader(cookies: Map<String, String>): String =
            cookies.entries.filter { it.key.isNotBlank() && it.value.isNotBlank() }
                .joinToString("; ") { "${it.key}=${it.value}" }

        private fun nextRandomDecimal(): String = "%.16f".format(Locale.US, secureRandom.nextDouble())
    }

    private data class RawHttpResponse(
        val code: Int,
        val bytes: ByteArray,
        val setCookies: List<String>,
        val location: String?,
        val requestUrl: String
    ) {
        fun utf8(): String = if (bytes.isEmpty()) "" else String(bytes, Charsets.UTF_8)
    }

    override suspend fun createQqQr(): QqQrSession = withContext(Dispatchers.IO) {
        ensureNetworkPermission()
        val timeSec = clock.instant().epochSecond
        val sign = KugouSigner.md5("${KUGOU_APK_SIG_MD5}_$timeSec")

        val authorizeUrl = mAuthorizeBaseUrl.toHttpUrl().newBuilder()
            .addPathSegments("oauth2.0/m_authorize")
            .addQueryParameter("cancel_display", "1")
            .addQueryParameter("sdkp", "a")
            .addQueryParameter("display", "mobile")
            .addQueryParameter("format", "json")
            .addQueryParameter("sign", sign)
            .addQueryParameter("sdkv", "3.5.11.lite")
            .addQueryParameter("response_type", "token")
            .addQueryParameter("status_os", "11")
            .addQueryParameter("client_id", QQ_LITE_APP_ID)
            .addQueryParameter("switch", "1")
            .addQueryParameter("status_version", "30")
            .addQueryParameter("show_download_ui", "true")
            .addQueryParameter("pf", "openmobile_android")
            .addQueryParameter("scope", "all")
            .addQueryParameter("compat_v", "1")
            .addQueryParameter("status_machine", "MEIZU+18+Pro")
            .addQueryParameter("style", "qr")
            .addQueryParameter("time", timeSec.toString())
            .addQueryParameter("redirect_uri", "auth://tauth.qq.com/")
            .build()
            .toString()

        val authResp = executeGet(
            url = authorizeUrl,
            headers = mapOf(
                "User-Agent" to MOBILE_USER_AGENT,
                "Referer" to "$DEFAULT_PT_LOGIN_BASE_URL/"
            ),
            maxBytes = SecurityGuards.MAX_SHARE_HTML_BYTES
        )
        val authHtml = authResp.utf8()
        val srcRaw = SRC_URL_REGEX.find(authHtml)?.groupValues?.getOrNull(1)
            ?: throw IOException("QQ 登录初始化异常，未找到 xlogin 地址")
        val xloginUrl = normalizeQqHopUrl(decodeEscapedJsUrl(srcRaw), allowLoopbackForTest)
        if (!isAllowedQqOAuthUrl(xloginUrl, allowLoopbackForTest)) {
            throw SecurityException("已拦截不受信任的 QQ xlogin 地址: $xloginUrl")
        }
        val xloginQuery = xloginUrl.substringAfter('?', "")
        if (xloginQuery.isBlank()) {
            throw IOException("QQ 登录初始化异常，xlogin 参数缺失")
        }

        val cookies = LinkedHashMap<String, String>()
        mergeCookies(cookies, authResp.setCookies)

        val xloginResp = executeGet(
            url = xloginUrl,
            headers = buildMap {
                put("User-Agent", MOBILE_USER_AGENT)
                put("Referer", "$DEFAULT_PT_LOGIN_BASE_URL/")
                val c = formatCookieHeader(cookies)
                if (c.isNotEmpty()) put("Cookie", c)
            },
            maxBytes = SecurityGuards.MAX_SHARE_HTML_BYTES
        )
        mergeCookies(cookies, xloginResp.setCookies)
        val ptLoginSig = cookies["pt_login_sig"].orEmpty()
        if (ptLoginSig.isBlank()) {
            throw IOException("QQ 登录初始化失败，未获取到 pt_login_sig")
        }

        val qrShowUrl = ptLoginBaseUrl.toHttpUrl().newBuilder()
            .addPathSegments("ssl/ptqrshow")
            .addQueryParameter("s", "8")
            .addQueryParameter("e", "0")
            .addQueryParameter("appid", PT_LOGIN_APP_ID)
            .addQueryParameter("type", "0")
            .addQueryParameter("t", nextRandomDecimal())
            .addQueryParameter("daid", PT_LOGIN_DAID)
            .addQueryParameter("pt_3rd_aid", QQ_LITE_APP_ID)
            .build()
            .toString()

        val qrResp = executeGet(
            url = qrShowUrl,
            headers = buildMap {
                put("User-Agent", MOBILE_USER_AGENT)
                put("Referer", xloginUrl)
                val c = formatCookieHeader(cookies)
                if (c.isNotEmpty()) put("Cookie", c)
            },
            maxBytes = MAX_QR_IMAGE_BYTES
        )
        mergeCookies(cookies, qrResp.setCookies)
        val qrsig = cookies["qrsig"].orEmpty()
        if (qrsig.isBlank()) {
            throw IOException("QQ 二维码生成失败，未获取到 qrsig")
        }
        if (qrResp.bytes.isEmpty() || !SecurityGuards.isSafeImagePayload(qrResp.bytes, maxBytes = MAX_QR_IMAGE_BYTES, maxDimensionPx = 2048)) {
            throw IOException("QQ 二维码图片数据异常")
        }

        val ptQrToken = KugouSigner.hash33(qrsig)
        val imageBase64 = Base64.getEncoder().encodeToString(qrResp.bytes)
        QqQrSession(
            imageUrl = "data:image/png;base64,$imageBase64",
            expiresAt = clock.millis() + QR_TTL_SECONDS * 1000L,
            qrsig = qrsig,
            ptQrToken = ptQrToken,
            ptLoginSig = ptLoginSig,
            ptOpenLoginData = xloginQuery,
            xloginUrl = xloginUrl,
            cookies = LinkedHashMap(cookies)
        )
    }

    override suspend fun pollQqQr(session: QqQrSession): QqQrPollResult = withContext(Dispatchers.IO) {
        ensureNetworkPermission()
        val cookies = LinkedHashMap(session.cookies)
        if (session.ptLoginSig.isNotBlank() && cookies["pt_login_sig"].isNullOrBlank()) {
            cookies["pt_login_sig"] = session.ptLoginSig
        }
        if (session.qrsig.isNotBlank() && cookies["qrsig"].isNullOrBlank()) {
            cookies["qrsig"] = session.qrsig
        }

        val pollUrl = ptLoginBaseUrl.toHttpUrl().newBuilder()
            .addPathSegments("ssl/ptqrlogin")
            .addEncodedQueryParameter("u1", encodeUriComponent("http://connect.qq.com"))
            .addQueryParameter("from_ui", "1")
            .addQueryParameter("type", "1")
            .addQueryParameter("ptlang", "2052")
            .addQueryParameter("ptqrtoken", session.ptQrToken.toString())
            .addQueryParameter("daid", PT_LOGIN_DAID)
            .addQueryParameter("aid", PT_LOGIN_APP_ID)
            .addQueryParameter("pt_3rd_aid", QQ_LITE_APP_ID)
            .addEncodedQueryParameter("pt_openlogin_data", encodeUriComponent(session.ptOpenLoginData))
            .addQueryParameter("device", "2")
            .addQueryParameter("ptopt", "1")
            .addQueryParameter("pt_uistyle", "35")
            .addQueryParameter("jsver", "v1.36.0")
            .addEncodedQueryParameter("login_sig", encodeUriComponent(session.ptLoginSig))
            .addQueryParameter("r", nextRandomDecimal())
            .build()
            .toString()

        val pollResp = executeGet(
            url = pollUrl,
            headers = buildMap {
                put("User-Agent", MOBILE_USER_AGENT)
                put("Referer", session.xloginUrl.ifBlank { "$DEFAULT_PT_LOGIN_BASE_URL/" })
                val c = formatCookieHeader(cookies)
                if (c.isNotEmpty()) put("Cookie", c)
            },
            maxBytes = SecurityGuards.MAX_SHARE_HTML_BYTES
        )
        mergeCookies(cookies, pollResp.setCookies)

        val parts = parsePtuiCallback(pollResp.utf8())
            ?: throw IOException("QQ 扫码状态响应解析失败")
        val code = parts[0].trim()
        val redirectUrl = parts[2].trim()
        val msg = parts[4].trim()

        when (code) {
            "66" -> QqQrPollResult.Waiting(LinkedHashMap(cookies))
            "67" -> QqQrPollResult.Scanned(
                updatedCookies = LinkedHashMap(cookies),
                message = msg.ifBlank { "已扫码，请在手机 QQ 上确认登录" }
            )
            "65" -> QqQrPollResult.Expired(msg.ifBlank { "二维码已失效，请重新生成" })
            "68" -> QqQrPollResult.Refused(msg.ifBlank { "已取消 QQ 登录，可重新生成二维码" })
            "0" -> {
                val credential = resolveOAuthCredential(
                    initialUrl = redirectUrl,
                    initialCookies = cookies,
                    referer = session.xloginUrl.ifBlank { "$DEFAULT_PT_LOGIN_BASE_URL/" }
                ) ?: throw IOException("QQ 授权成功，但未获取到 openid 或 access_token")
                QqQrPollResult.Authorized(credential)
            }
            else -> QqQrPollResult.Failed(code, msg.ifBlank { "QQ 扫码状态异常（$code）" })
        }
    }

    private suspend fun resolveOAuthCredential(
        initialUrl: String,
        initialCookies: Map<String, String>,
        referer: String
    ): QqOAuthCredential? {
        extractCredentialFromUrl(initialUrl)?.let { return it }
        if (initialUrl.isBlank()) return null

        val hopCookies = LinkedHashMap(initialCookies)
        var currentUrl = normalizeQqHopUrl(initialUrl, allowLoopbackForTest)
        var currentReferer = referer

        for (hop in 0 until MAX_REDIRECT_HOPS) {
            extractCredentialFromUrl(currentUrl)?.let { return it }

            if (CUSTOM_SCHEME_REGEX.containsMatchIn(currentUrl) &&
                !currentUrl.startsWith("http://", ignoreCase = true) &&
                !currentUrl.startsWith("https://", ignoreCase = true)
            ) {
                return extractCredentialFromUrl(currentUrl)
            }

            if (!isAllowedQqOAuthUrl(currentUrl, allowLoopbackForTest)) {
                throw SecurityException("已拦截不受信任的 QQ OAuth 重定向目标: $currentUrl")
            }

            val resp = executeGet(
                url = currentUrl,
                headers = buildMap {
                    put("User-Agent", MOBILE_USER_AGENT)
                    put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    put("Accept-Language", "zh-CN,zh;q=0.9")
                    put("Referer", currentReferer)
                    put("Sec-Fetch-Dest", "document")
                    put("Sec-Fetch-Mode", "navigate")
                    put("Sec-Fetch-Site", "cross-site")
                    val c = formatCookieHeader(hopCookies)
                    if (c.isNotEmpty()) put("Cookie", c)
                },
                maxBytes = SecurityGuards.MAX_SHARE_HTML_BYTES,
                allowRedirectStatus = true
            )
            mergeCookies(hopCookies, resp.setCookies)

            val loc = resp.location?.trim().orEmpty()
            if (loc.isEmpty()) {
                return extractCredentialFromText(resp.utf8())
            }

            extractCredentialFromUrl(loc)?.let { return it }

            if (CUSTOM_SCHEME_REGEX.containsMatchIn(loc) &&
                !loc.startsWith("http://", ignoreCase = true) &&
                !loc.startsWith("https://", ignoreCase = true)
            ) {
                return extractCredentialFromUrl(loc)
            }

            val resolvedNext = resp.requestUrl.toHttpUrlOrNull()?.resolve(loc)?.toString()
                ?: throw SecurityException("QQ OAuth 重定向地址无效: $loc")
            val normalizedNext = normalizeQqHopUrl(resolvedNext, allowLoopbackForTest)
            extractCredentialFromUrl(normalizedNext)?.let { return it }
            if (!isAllowedQqOAuthUrl(normalizedNext, allowLoopbackForTest)) {
                throw SecurityException("已拦截不受信任的 QQ OAuth 重定向目标: $normalizedNext")
            }
            currentReferer = currentUrl
            currentUrl = normalizedNext
        }
        return null
    }

    private fun ensureNetworkPermission() {
        if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.NETWORK_ACCESS)) {
            throw IOException("已关闭「网络访问与网络状态检测」权限，无法加载在线数据（可在设置-权限管理中重新开启）")
        }
    }

    private suspend fun executeGet(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
        allowRedirectStatus: Boolean = false
    ): RawHttpResponse {
        if (!isAllowedQqOAuthUrl(url, allowLoopbackForTest)) {
            throw SecurityException("已拦截不受信任的 QQ OAuth 请求地址: $url")
        }
        val reqBuilder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        val request = reqBuilder.build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IOException("QQ 登录网络连接失败，请检查网络后重试", e))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        try {
                            val ok = if (allowRedirectStatus) {
                                resp.code in 200..399
                            } else {
                                resp.isSuccessful
                            }
                            if (!ok) {
                                throw IOException("QQ 登录服务响应异常（HTTP ${resp.code}）")
                            }
                            val bytes = SecurityGuards.readBoundedBytes(resp.body, maxBytes)
                            val result = RawHttpResponse(
                                code = resp.code,
                                bytes = bytes,
                                setCookies = resp.headers("Set-Cookie"),
                                location = resp.header("Location"),
                                requestUrl = resp.request.url.toString()
                            )
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) {
                            val err = e as? IOException ?: IOException("QQ 登录响应读取失败", e)
                            if (continuation.isActive) continuation.resumeWithException(err)
                        }
                    }
                }
            })
        }
    }
}
