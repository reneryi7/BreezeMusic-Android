package com.concept.music.data.kugou

import com.concept.music.util.SecurityGuards
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Clock
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 全局共享的 OkHttp 客户端连接池与调度器提供者（避免多实例连接池分裂与 SSL 握手重复开销）
 */
object NetworkClientProvider {
    val sharedConnectionPool = ConnectionPool(12, 5, TimeUnit.MINUTES)
    val sharedDispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 16
    }

    private val SENSITIVE_QUERY_KEYS = setOf(
        "token",
        "clienttoken",
        "vip_token",
        "viptoken",
        "p3",
        "secu_params",
        "mobile",
        "code",
        "p",
        "pk",
        "params",
        "qrcode",
        "cookie"
    )

    private val SENSITIVE_POST_PATH_KEYWORDS = listOf(
        "/login",
        "/send_mobile_code",
        "/r_register_dev",
        "/get_my_info",
        "/upgrade_vip_reward",
        "/receive_vip_listen_song",
        "/v2/post",
        "/v2/gen",
        "/v2/qrydid"
    )

    private val KUGOU_INTERNAL_HEADERS_TO_STRIP_CROSS_DOMAIN = listOf(
        "dfid",
        "mid",
        "kg-rf",
        "kg-rc",
        "kg-thash",
        "x-router"
    )

    private val IS_ANDROID_DEVICE_RUNTIME: Boolean by lazy {
        System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)
    }

    /**
     * 仅放行真实本地回环地址（以及非 Android 纯 JVM 单测环境下无 '.' 且无 ':' 的本机 MockWebServer 主机名），
     * 严禁将包含 ':' 的任何公网 IPv6 地址（如 `[2606:4700:4700::1111]`）或真机局域网单标签主机误判为回环！
     */
    internal fun isLocalLoopbackOrJvmMockHost(rawHost: String): Boolean {
        val host = rawHost.trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase(Locale.ROOT)
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
        if (host.contains(':')) return false
        return !IS_ANDROID_DEVICE_RUNTIME && !host.contains('.')
    }

    /**
     * 兼容单测本机 MockWebServer 回环主机、同时阻断非回环主机发生 DNS 重绑定至私网/回环 IP 的安全 DNS 解析器。
     */
    internal val safeTransportDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            if (isLocalLoopbackOrJvmMockHost(hostname)) {
                return Dns.SYSTEM.lookup(hostname)
            }
            return SecurityGuards.publicOnlyDns.lookup(hostname)
        }
    }

    internal fun enforceCredentialTransportSafety(req: Request) {
        val url = req.url
        val host = url.host.lowercase(Locale.ROOT)
        val isLoopback = isLocalLoopbackOrJvmMockHost(host)
        val hasSensitiveQuery = url.queryParameterNames.any { name ->
            name.lowercase(Locale.ROOT) in SENSITIVE_QUERY_KEYS &&
                url.queryParameterValues(name).any { !it.isNullOrBlank() }
        }
        val hasSensitiveHeader = !req.header("Authorization").isNullOrBlank() ||
            !req.header("Cookie").isNullOrBlank()
        val encodedPathLower = url.encodedPath.lowercase(Locale.ROOT)
        val hasSensitivePostBody = req.body != null &&
            SENSITIVE_POST_PATH_KEYWORDS.any { encodedPathLower.contains(it) }
        val carriesAuthToken = hasSensitiveQuery || hasSensitiveHeader || hasSensitivePostBody
        if (!isLoopback && carriesAuthToken) {
            if (!url.isHttps) {
                throw IOException("已拦截携带登录凭证的明文 HTTP 请求: $host")
            }
            val isTrustedAuthHost = SecurityGuards.matchesDomainOrSubdomain(host, "kugou.com")
            if (!isTrustedAuthHost) {
                throw IOException("已拦截携带登录凭证向外部非授信域名的跨域请求: $host")
            }
        }
    }

    private fun stripInternalHeadersIfCrossDomain(req: Request): Request {
        val host = req.url.host.lowercase(Locale.ROOT)
        if (isLocalLoopbackOrJvmMockHost(host) || SecurityGuards.matchesDomainOrSubdomain(host, "kugou.com")) {
            return req
        }
        val hasAnyInternalHeader = KUGOU_INTERNAL_HEADERS_TO_STRIP_CROSS_DOMAIN.any { req.header(it) != null }
        if (!hasAnyInternalHeader) return req
        val builder = req.newBuilder()
        for (h in KUGOU_INTERNAL_HEADERS_TO_STRIP_CROSS_DOMAIN) {
            builder.removeHeader(h)
        }
        return builder.build()
    }

    val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(safeTransportDns)
            .followSslRedirects(false)
            .connectionPool(sharedConnectionPool)
            .dispatcher(sharedDispatcher)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val req = chain.request()
                enforceCredentialTransportSafety(req)
                chain.proceed(stripInternalHeadersIfCrossDomain(req))
            }
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                enforceCredentialTransportSafety(req)
                chain.proceed(stripInternalHeadersIfCrossDomain(req))
            }
            .build()
    }
}

interface KugouTransport {
    suspend fun call(path: String, session: KugouSession? = null, query: Map<String, String> = emptyMap(),
        body: JsonObject? = null, host: String = "https://gateway.kugou.com", method: String = "GET",
        web: Boolean = false, headers: Map<String, String> = emptyMap(), device: DeviceIdentity? = null): ApiResponse
    suspend fun callAdvanced(path: String, session: KugouSession? = null, query: Map<String, String> = emptyMap(),
        body: JsonObject? = null, host: String = "https://gateway.kugou.com", method: String = "GET",
        web: Boolean = false, headers: Map<String, String> = emptyMap(), device: DeviceIdentity? = null,
        sign: Boolean = true, includeDefaultParams: Boolean = true): ApiResponse =
        call(path, session, query, body, host, method, web, headers, device)
    suspend fun callRaw(path: String, session: KugouSession? = null, query: Map<String, String> = emptyMap(),
        rawBody: String? = null, host: String = "https://gateway.kugou.com", method: String = "POST",
        headers: Map<String, String> = emptyMap(), sign: Boolean = true, includeDefaultParams: Boolean = true,
        device: DeviceIdentity? = null): ByteArray = ByteArray(0)
    suspend fun requestRawBytes(path: String, session: KugouSession? = null, query: Map<String, String> = emptyMap(),
        rawBody: String? = null, host: String = "https://gateway.kugou.com", method: String = "POST",
        headers: Map<String, String> = emptyMap(), sign: Boolean = true, includeDefaultParams: Boolean = true,
        device: DeviceIdentity? = null): ByteArray =
        callRaw(path, session, query, rawBody, host, method, headers, sign, includeDefaultParams, device)
    suspend fun requestBinaryBytes(path: String, session: KugouSession? = null, query: Map<String, String> = emptyMap(),
        rawBodyBytes: ByteArray? = null, host: String = "https://gateway.kugou.com", method: String = "POST",
        headers: Map<String, String> = emptyMap(), sign: Boolean = false, includeDefaultParams: Boolean = false,
        device: DeviceIdentity? = null): ByteArray = ByteArray(0)
}

class DirectKugouTransport(
    private val identity: () -> DeviceIdentity,
    private val client: OkHttpClient = NetworkClientProvider.baseClient,
    private val clock: Clock = Clock.systemUTC()
) : KugouTransport {

    companion object {
        // 仅允许无敏感凭证的纯公开多级音频/资源 CDN 保留明文回退，严禁包含任何登录、账号或用户信息子域
        private val CLEARTEXT_ALLOWED_HOSTS = setOf(
            "fs.android.kugou.com",
            "bssdlbig.kugou.com",
            "bssdl.kugou.com",
            "trackercdn.kugou.com",
            "tracker.kugou.com",
            "mobilecdnbj.kugou.com",
            "wwwapi.kugou.com",
            "msearchcdn.kugou.com",
            "mobilecdn.kugou.com",
            "m.kugou.com"
        )

        private val SENSITIVE_PARAM_KEYS = setOf(
            "token",
            "vip_token",
            "viptoken",
            "mobile",
            "code",
            "p3",
            "p",
            "pk",
            "params",
            "secu_params",
            "qrcode"
        )

        internal fun hasSensitiveCredentials(
            session: KugouSession?,
            query: Map<String, String>,
            body: JsonObject? = null,
            rawBody: String? = null
        ): Boolean {
            if (session != null) return true
            if (query.keys.any { it.lowercase(Locale.ROOT) in SENSITIVE_PARAM_KEYS }) return true
            if (body != null && body.keySet().any { it.lowercase(Locale.ROOT) in SENSITIVE_PARAM_KEYS }) return true
            if (!rawBody.isNullOrBlank()) return true
            return false
        }

        private fun requiresGatewayTlsRouting(hostName: String): Boolean {
            if (!hostName.endsWith(".kugou.com")) return false
            if (hostName in setOf(
                    "login.user.kugou.com",
                    "userinfo.user.kugou.com",
                    "relation.user.kugou.com",
                    "usercenter.kugou.com",
                    "update.user.kugou.com"
                )
            ) {
                return true
            }
            // 多级子域（如 fs.android.kugou.com）无二级通配符 TLS 证书，携带敏感参数时统一走 gateway.kugou.com TLS 转发
            return hostName.count { it == '.' } > 2 &&
                !hostName.endsWith(".cloud.kugou.com") &&
                !hostName.endsWith(".tx.kugou.com")
        }

        internal fun enforceHttpsForSensitiveHost(
            rawHost: String,
            session: KugouSession?,
            query: Map<String, String>,
            body: JsonObject? = null
        ): String {
            val hostName = runCatching { rawHost.toHttpUrl().host.lowercase(Locale.ROOT) }.getOrDefault("")
            if (NetworkClientProvider.isLocalLoopbackOrJvmMockHost(hostName)) return rawHost

            val hasSensitiveParam = hasSensitiveCredentials(session, query, body)

            // 二级子域 login.user.kugou.com 在酷狗服务端仅部署了 *.kugou.com 一级通配符证书：
            // - 携带任何敏感凭据/手机号时，强制升级至 https://gateway.kugou.com (配合 x-router 头加密传输)；
            // - 纯公开探测（无任何敏感参数）时保留下游单测契约兼容行为。
            if (hostName == "login.user.kugou.com") {
                return if (hasSensitiveParam) {
                    "https://gateway.kugou.com"
                } else if (rawHost.startsWith("https://", ignoreCase = true)) {
                    "http://" + rawHost.substring(8)
                } else {
                    rawHost
                }
            }

            if (requiresGatewayTlsRouting(hostName) && (hasSensitiveParam || hostName.endsWith(".user.kugou.com"))) {
                return "https://gateway.kugou.com"
            }

            if (!rawHost.startsWith("http://", ignoreCase = true)) return rawHost

            return if (hasSensitiveParam || hostName !in CLEARTEXT_ALLOWED_HOSTS) {
                "https://" + rawHost.substring(7)
            } else {
                rawHost
            }
        }

        /**
         * 针对国内移动/联通/电信/广电不同运营商网络下的 DNS 污染、二级子域 TLS 证书不匹配或网关偶发超时，
         * 构建多通道自动容灾候选端点列表（优先走全链路 TLS 网关，携带敏感凭证时彻底剔除 HTTP 明文回退）。
         */
        internal fun buildCandidateEndpoints(
            rawHost: String,
            baseHeaders: Map<String, String>,
            allowCleartextFallback: Boolean = true
        ): List<Pair<String, Map<String, String>>> {
            val hostName = runCatching { rawHost.toHttpUrl().host.lowercase(Locale.ROOT) }.getOrDefault("")
            if (hostName.isBlank() || NetworkClientProvider.isLocalLoopbackOrJvmMockHost(hostName)) {
                return listOf(rawHost to baseHeaders)
            }
            val loginRouterHeaders = LinkedHashMap(baseHeaders).apply {
                put("x-router", "login.user.kugou.com")
            }
            val qrLoginRouterHeaders = LinkedHashMap(baseHeaders).apply {
                put("x-router", "login-user.kugou.com")
            }
            return when (hostName) {
                "login.user.kugou.com" -> buildList {
                    add("https://gateway.kugou.com" to loginRouterHeaders)
                    add("https://login-user.kugou.com" to baseHeaders)
                    if (allowCleartextFallback) {
                        add("http://login.user.kugou.com" to baseHeaders)
                    }
                }
                "loginserviceretry.kugou.com" -> buildList {
                    val httpsPrimary = if (rawHost.startsWith("http://", ignoreCase = true)) {
                        "https://" + rawHost.substring(7)
                    } else rawHost
                    add(httpsPrimary to baseHeaders)
                    add("https://gateway.kugou.com" to loginRouterHeaders)
                    add("https://login-user.kugou.com" to baseHeaders)
                    if (allowCleartextFallback) {
                        add("http://login.user.kugou.com" to baseHeaders)
                    }
                }
                "login-user.kugou.com" -> listOf(
                    rawHost to baseHeaders,
                    "https://gateway.kugou.com" to qrLoginRouterHeaders
                )
                "gateway.kugou.com" -> listOf(
                    rawHost to baseHeaders,
                    "https://gatewayretry.kugou.com" to baseHeaders
                )
                "tracker.kugou.com" -> buildList {
                    val httpsTracker = if (rawHost.startsWith("http://", ignoreCase = true)) {
                        "https://" + rawHost.substring(7)
                    } else rawHost
                    add(httpsTracker to baseHeaders)
                    add("https://gateway.kugou.com" to LinkedHashMap(baseHeaders).apply { put("x-router", "tracker.kugou.com") })
                    if (allowCleartextFallback) {
                        add("http://tracker.kugou.com" to baseHeaders)
                    }
                }
                else -> listOf(rawHost to baseHeaders)
            }
        }
    }

    override suspend fun call(path: String, session: KugouSession?, query: Map<String, String>, body: JsonObject?,
        host: String, method: String, web: Boolean, headers: Map<String, String>, device: DeviceIdentity?) =
        callAdvanced(path, session, query, body, host, method, web, headers, device, sign = true, includeDefaultParams = true)

    override suspend fun callAdvanced(path: String, session: KugouSession?, query: Map<String, String>, body: JsonObject?,
        host: String, method: String, web: Boolean, headers: Map<String, String>, device: DeviceIdentity?,
        sign: Boolean, includeDefaultParams: Boolean): ApiResponse = withContext(Dispatchers.IO) {
        val allowCleartext = !hasSensitiveCredentials(session, query, body, null)
        val candidateEndpoints = buildCandidateEndpoints(host, headers, allowCleartextFallback = allowCleartext)
        var lastError: IOException? = null

        for ((index, endpoint) in candidateEndpoints.withIndex()) {
            val (candidateHost, candidateHeaders) = endpoint
            try {
                return@withContext executeSingleCall(
                    path = path,
                    session = session,
                    query = query,
                    body = body,
                    host = candidateHost,
                    method = method,
                    web = web,
                    headers = candidateHeaders,
                    device = device,
                    sign = sign,
                    includeDefaultParams = includeDefaultParams
                )
            } catch (e: IOException) {
                lastError = e
                if (index < candidateEndpoints.lastIndex) {
                    delay(150L * (index + 1))
                }
            }
        }
        throw (lastError ?: IOException("网络连接失败，请检查网络后重试"))
    }

    private suspend fun executeSingleCall(
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
        if (!com.concept.music.service.AppPermissionManager.isPermissionEnabled(com.concept.music.service.AppPermissionType.NETWORK_ACCESS)) {
            throw IOException("已关闭「网络访问与网络状态检测」权限，无法加载在线数据（可在设置-权限管理中重新开启）")
        }
        val d = device ?: identity()
        val params = linkedMapOf<String, String>()
        if (includeDefaultParams) {
            params.putAll(mapOf(
                "dfid" to d.dfid,
                "mid" to d.mid,
                "uuid" to "-",
                "appid" to "3116",
                "clientver" to "11440",
                "clienttime" to clock.instant().epochSecond.toString()
            ))
            session?.let { params["token"] = it.token; params["userid"] = it.userId }
        }
        params.putAll(query)
        val payload = body?.let(KugouSigner.gson::toJson).orEmpty()
        if (sign) params["signature"] = KugouSigner.signature(params, payload, web)
        val originalHostName = runCatching { host.toHttpUrl().host.lowercase(Locale.ROOT) }.getOrDefault("")
        val safeHost = enforceHttpsForSensitiveHost(host, session, params, body)
        val safeHostName = runCatching { safeHost.toHttpUrl().host.lowercase(Locale.ROOT) }.getOrDefault("")
        val effectiveHeaders = if (safeHostName == "gateway.kugou.com" &&
            originalHostName.isNotBlank() &&
            originalHostName != "gateway.kugou.com" &&
            headers.keys.none { it.equals("x-router", ignoreCase = true) }
        ) {
            LinkedHashMap(headers).apply { put("x-router", originalHostName) }
        } else {
            headers
        }
        val url = safeHost.toHttpUrl().newBuilder().addPathSegments(path.trimStart('/')).apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val isBodylessMethod = method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true)
        val request = Request.Builder().url(url)
            .header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi")
            .header("dfid", d.dfid).header("mid", d.mid).header("clienttime", params["clienttime"] ?: clock.instant().epochSecond.toString())
            .header("kg-rc", "1").header("kg-thash", "5d816a0").header("kg-rec", "1")
            .header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")
            .apply { effectiveHeaders.forEach { (key, value) -> header(key, value) } }
            .method(method, if (isBodylessMethod) null else payload.toRequestBody(
                (effectiveHeaders.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value ?: "application/json; charset=utf-8").toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("网络连接失败，请检查网络后重试", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val bodyString = SecurityGuards.readBoundedUtf8(it.body, SecurityGuards.MAX_API_RESPONSE_BYTES)
                            val root = JsonParser.parseString(bodyString)
                            if (!root.isJsonObject) throw IOException("酷狗返回了无法识别的响应")
                            val result = ApiResponse(root.asJsonObject, it.header("ssa-code") ?: root.asJsonObject.text("ssaCode"), it.code)
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(IOException("酷狗响应解析失败，请稍后重试", e))
                        }
                    }
                }
            })
        }
    }

    override suspend fun callRaw(path: String, session: KugouSession?, query: Map<String, String>,
        rawBody: String?, host: String, method: String, headers: Map<String, String>,
        sign: Boolean, includeDefaultParams: Boolean, device: DeviceIdentity?): ByteArray =
        requestRawBytes(path, session, query, rawBody, host, method, headers, sign, includeDefaultParams, device)

    override suspend fun requestRawBytes(path: String, session: KugouSession?, query: Map<String, String>,
        rawBody: String?, host: String, method: String, headers: Map<String, String>,
        sign: Boolean, includeDefaultParams: Boolean, device: DeviceIdentity?): ByteArray = withContext(Dispatchers.IO) {
        if (!com.concept.music.service.AppPermissionManager.isPermissionEnabled(com.concept.music.service.AppPermissionType.NETWORK_ACCESS)) {
            throw IOException("已关闭「网络访问与网络状态检测」权限，无法加载在线数据（可在设置-权限管理中重新开启）")
        }
        val d = device ?: identity()
        val params = linkedMapOf<String, String>()
        if (includeDefaultParams) {
            params.putAll(mapOf("dfid" to d.dfid, "mid" to d.mid, "uuid" to "-", "appid" to "3116",
                "clientver" to "11440", "clienttime" to clock.instant().epochSecond.toString()))
            session?.let { params["token"] = it.token; params["userid"] = it.userId }
        }
        params.putAll(query)
        val payload = rawBody.orEmpty()
        if (sign) params["signature"] = KugouSigner.signature(params, payload, false)
        val originalHostName = runCatching { host.toHttpUrl().host.lowercase(Locale.ROOT) }.getOrDefault("")
        val safeHost = enforceHttpsForSensitiveHost(host, session, params, if (payload.isNotEmpty()) JsonObject().apply { addProperty("params", "1") } else null)
        val safeHostName = runCatching { safeHost.toHttpUrl().host.lowercase(Locale.ROOT) }.getOrDefault("")
        val effectiveHeaders = if (safeHostName == "gateway.kugou.com" &&
            originalHostName.isNotBlank() &&
            originalHostName != "gateway.kugou.com" &&
            headers.keys.none { it.equals("x-router", ignoreCase = true) }
        ) {
            LinkedHashMap(headers).apply { put("x-router", originalHostName) }
        } else {
            headers
        }
        val url = safeHost.toHttpUrl().newBuilder().addPathSegments(path.trimStart('/')).apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val contentType = effectiveHeaders.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value ?: "text/plain; charset=utf-8"
        val isBodylessMethod = method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true)
        val request = Request.Builder().url(url)
            .header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi")
            .header("dfid", d.dfid).header("mid", d.mid).header("clienttime", params["clienttime"] ?: clock.instant().epochSecond.toString())
            .header("kg-rc", "1").header("kg-thash", "5d816a0").header("kg-rec", "1")
            .header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")
            .apply { effectiveHeaders.forEach { (key, value) -> header(key, value) } }
            .method(method, if (isBodylessMethod) null else payload.toRequestBody(contentType.toMediaType()))
            .build()
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("网络连接失败，请检查网络后重试", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!it.isSuccessful) {
                                throw IOException("HTTP ${it.code} for ${url.encodedPath}")
                            }
                            val bytes = SecurityGuards.readBoundedBytes(it.body, SecurityGuards.MAX_API_RESPONSE_BYTES)
                            if (continuation.isActive) continuation.resume(bytes)
                        } catch (e: Exception) {
                            val ioEx = e as? IOException ?: IOException("酷狗响应读取失败", e)
                            if (continuation.isActive) continuation.resumeWithException(ioEx)
                        }
                    }
                }
            })
        }
    }

    override suspend fun requestBinaryBytes(path: String, session: KugouSession?, query: Map<String, String>,
        rawBodyBytes: ByteArray?, host: String, method: String, headers: Map<String, String>,
        sign: Boolean, includeDefaultParams: Boolean, device: DeviceIdentity?): ByteArray = withContext(Dispatchers.IO) {
        if (!com.concept.music.service.AppPermissionManager.isPermissionEnabled(com.concept.music.service.AppPermissionType.NETWORK_ACCESS)) {
            throw IOException("已关闭「网络访问与网络状态检测」权限，无法加载在线数据（可在设置-权限管理中重新开启）")
        }
        val d = device ?: identity()
        val params = linkedMapOf<String, String>()
        if (includeDefaultParams) {
            params.putAll(mapOf("dfid" to d.dfid, "mid" to d.mid, "uuid" to "-", "appid" to "3116",
                "clientver" to "11440", "clienttime" to clock.instant().epochSecond.toString()))
            session?.let { params["token"] = it.token; params["userid"] = it.userId }
        }
        params.putAll(query)
        val bodyBytes = rawBodyBytes ?: ByteArray(0)
        if (sign) {
            val bodyForSign = if (bodyBytes.isNotEmpty()) String(bodyBytes, Charsets.ISO_8859_1) else ""
            params["signature"] = KugouSigner.signature(params, bodyForSign, false)
        }
        val safeHost = enforceHttpsForSensitiveHost(host, session, params, if (bodyBytes.isNotEmpty()) JsonObject().apply { addProperty("params", "1") } else null)
        val hasSensitiveCreds = hasSensitiveCredentials(session, params, if (bodyBytes.isNotEmpty()) JsonObject().apply { addProperty("params", "1") } else null)
        val candidates = buildCandidateEndpoints(
            rawHost = safeHost,
            baseHeaders = headers,
            allowCleartextFallback = !hasSensitiveCreds
        )
        val isBodylessMethod = method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true)
        var lastException: IOException? = null

        for ((candidateHost, candidateHeaders) in candidates) {
            val url = candidateHost.toHttpUrl().newBuilder().addPathSegments(path.trimStart('/')).apply {
                params.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
            val contentType = candidateHeaders.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value ?: "application/octet-stream"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi")
                .header("dfid", d.dfid).header("mid", d.mid).header("clienttime", params["clienttime"] ?: clock.instant().epochSecond.toString())
                .header("kg-rc", "1").header("kg-thash", "5d816a0").header("kg-rec", "1")
                .header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")
                .apply { candidateHeaders.forEach { (key, value) -> header(key, value) } }
                .method(method, if (isBodylessMethod) null else bodyBytes.toRequestBody(contentType.toMediaType()))
                .build()
            try {
                return@withContext suspendCancellableCoroutine { continuation ->
                    val call = client.newCall(request)
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(IOException("网络连接失败，请检查网络后重试", e))
                        }
                        override fun onResponse(call: Call, response: Response) {
                            response.use {
                                try {
                                    if (!it.isSuccessful) {
                                        throw IOException("HTTP ${it.code} for ${url.encodedPath}")
                                    }
                                    val bytes = SecurityGuards.readBoundedBytes(it.body, SecurityGuards.MAX_API_RESPONSE_BYTES)
                                    if (continuation.isActive) continuation.resume(bytes)
                                } catch (e: Exception) {
                                    val ioEx = e as? IOException ?: IOException("酷狗响应读取失败", e)
                                    if (continuation.isActive) continuation.resumeWithException(ioEx)
                                }
                            }
                        }
                    })
                }
            } catch (e: IOException) {
                lastException = e
            }
        }
        throw lastException ?: IOException("网络连接失败，请检查网络后重试")
    }
}
