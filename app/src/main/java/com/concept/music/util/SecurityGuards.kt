package com.concept.music.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.View
import android.view.Window
import android.view.WindowManager
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.WeakHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.abs

/**
 * 全局纵深防御安全守卫工具集（SSRF 防护、供应链更新域名钉扎、路径穿越防护、流式大小上限、图像解压炸弹防护、IPC 鉴权令牌）
 */
object SecurityGuards {

    const val MAX_API_RESPONSE_BYTES: Int = 16 * 1024 * 1024 // 16 MB
    const val MAX_SHARE_HTML_BYTES: Int = 1024 * 1024 // 1 MB
    const val MAX_UPDATE_METADATA_BYTES: Int = 2 * 1024 * 1024 // 2 MB
    const val MAX_COVER_IMAGE_BYTES: Int = 20 * 1024 * 1024 // 20 MB
    const val MAX_AUDIO_STREAM_BYTES: Long = 600L * 1024L * 1024L // 600 MB (兼容超长 Hi-Res FLAC)
    const val MAX_IMAGE_DIMENSION_PX: Int = 8192
    const val MAX_IMAGE_TOTAL_PIXELS: Long = 36_000_000L // 约 36 MP (防 OOM 解压炸弹)
    const val MAX_CLIPBOARD_TEXT_CHARS: Int = 8192

    private const val IPC_PREFS_NAME = "concept_ipc_security_prefs"
    private const val KEY_INTERNAL_IPC_TOKEN = "internal_ipc_cap_token_v1"
    private const val KEY_INTERNAL_IPC_TOKEN_ENC = "internal_ipc_cap_token_enc_v2"
    private const val IPC_KEYSTORE_ALIAS = "concept_music_ipc_token_aes256_v1"
    private const val IPC_TOKEN_ENC_PREFIX = "enc_v1:"
    const val EXTRA_INTERNAL_IPC_TOKEN = "com.concept.music.extra.IPC_CAP_TOKEN"

    private val secureRandom = SecureRandom()

    @Volatile
    private var cachedIpcToken: String? = null

    @Volatile
    private var cachedIpcSecretKey: SecretKey? = null

    private data class WindowSecurityState(
        var activeCount: Int = 0,
        var hadFlagSecureBefore: Boolean = false
    )

    private val protectedWindowStates = WeakHashMap<Window, WindowSecurityState>()
    private val protectedViewPrevFilter = WeakHashMap<View, Boolean>()
    private val protectedViewCounts = WeakHashMap<View, Int>()

    private val TRUSTED_SHARE_ROOT_DOMAINS = setOf(
        "kugou.com",
        "kgurl.cn"
    )

    private val TRUSTED_SHARE_EXACT_HOSTS = setOf(
        "a.app.qq.com"
    )

    private val TRUSTED_GITHUB_WEB_HOSTS = setOf(
        "github.com"
    )

    private val FORBIDDEN_EXPORT_PATH_PREFIXES = listOf(
        "/data",
        "/proc",
        "/sys",
        "/dev",
        "/system",
        "/vendor",
        "/product",
        "/apex",
        "/etc",
        "/sbin",
        "/root",
        "/mnt/runtime",
        "/mnt/asec",
        "/mnt/obb",
        "/mnt/user",
        "/storage/emulated/0/android/data",
        "/storage/emulated/0/android/obb",
        "/storage/emulated/0/android/sandbox",
        "/sdcard/android/data",
        "/sdcard/android/obb",
        "/sdcard/android/sandbox"
    )

    private val EXTERNAL_APP_SANDBOX_PATH_REGEX = Regex(
        """^/(?:storage/[^/]+|sdcard|mnt/sdcard)/android/(?:data|obb|sandbox)(?:/|$)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 拦截 DNS 重绑定攻击（DNS Rebinding）的公共网络解析器：
     * 仅允许域名解析至公网单播 IP，拒绝解析到回环/局域网/链路本地/CGNAT/多播地址的恶意域名。
     */
    val publicOnlyDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (isDisallowedNonPublicHost(hostname)) {
                throw UnknownHostException("Blocked non-public host: $hostname")
            }
            val resolved = Dns.SYSTEM.lookup(hostname)
            val safeAddresses = resolved.filter { addr -> !isNonPublicInetAddress(addr) }
            if (safeAddresses.isEmpty()) {
                throw UnknownHostException("Blocked private/loopback DNS resolution for host: $hostname")
            }
            return safeAddresses
        }
    }

    /**
     * 判断 InetAddress 是否属于非公网地址（回环、未指定、私网 RFC1918、CGNAT RFC6598、链路本地、多播、ULA IPv6、NAT64/6to4/Teredo/IPv4-mapped 私网）
     */
    fun isNonPublicInetAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        return when (address) {
            is Inet4Address -> isNonPublicIpv4Bytes(address.address)
            is Inet6Address -> {
                val bytes = address.address
                if (bytes.size != 16) return true
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                val b2 = bytes[2].toInt() and 0xFF
                val b3 = bytes[3].toInt() and 0xFF
                val b4 = bytes[4].toInt() and 0xFF
                val b5 = bytes[5].toInt() and 0xFF

                // Unique Local Address fc00::/7
                if ((b0 and 0xFE) == 0xFC) return true

                // 2001::/16 Special-Purpose Ranges (Teredo 2001:0000::/32, Benchmarking 2001:2::/48, Documentation 2001:db8::/32)
                if (b0 == 0x20 && b1 == 0x01) {
                    if (b2 == 0x00 && b3 == 0x00) return true // Teredo 2001:0000::/32
                    if (b2 == 0x00 && b3 == 0x02 && b4 == 0x00 && b5 == 0x00) return true // Benchmarking 2001:2::/48
                    if (b2 == 0x0D && b3 == 0xB8) return true // Documentation 2001:db8::/32
                }

                // 6to4 2002::/16 embeds IPv4 in bytes[2..5]
                if (b0 == 0x20 && b1 == 0x02) {
                    return isNonPublicIpv4Bytes(bytes.copyOfRange(2, 6))
                }

                // NAT64 Well-Known Prefix 64:ff9b::/96 & Local-Use NAT64 64:ff9b:1::/48
                if (b0 == 0x00 && b1 == 0x64 && b2 == 0xFF && b3 == 0x9B) {
                    if (b4 == 0x00 && b5 == 0x01) return true // RFC 8215 Local-Use NAT64
                    if ((4..11).all { bytes[it].toInt() == 0 }) {
                        return isNonPublicIpv4Bytes(bytes.copyOfRange(12, 16))
                    }
                }

                // IPv4-mapped ::ffff:a.b.c.d, IPv4-translated ::ffff:0:a.b.c.d, or IPv4-compatible ::a.b.c.d
                val isIpv4MappedOrCompatible = (0..7).all { bytes[it].toInt() == 0 } &&
                    (
                        (bytes[8].toInt() == 0 && bytes[9].toInt() == 0 && bytes[10].toInt() == 0 && bytes[11].toInt() == 0) ||
                            (bytes[8].toInt() == 0 && bytes[9].toInt() == 0 && (bytes[10].toInt() and 0xFF) == 0xFF && (bytes[11].toInt() and 0xFF) == 0xFF) ||
                            ((bytes[8].toInt() and 0xFF) == 0xFF && (bytes[9].toInt() and 0xFF) == 0xFF && bytes[10].toInt() == 0 && bytes[11].toInt() == 0)
                        )
                if (isIpv4MappedOrCompatible) {
                    return isNonPublicIpv4Bytes(bytes.copyOfRange(12, 16))
                }
                false
            }
            else -> true
        }
    }

    private fun isNonPublicIpv4Bytes(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return true
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        val c = bytes[2].toInt() and 0xFF
        return when {
            a == 0 -> true // 0.0.0.0/8
            a == 10 -> true // 10.0.0.0/8
            a == 100 && b in 64..127 -> true // 100.64.0.0/10 CGNAT
            a == 127 -> true // 127.0.0.0/8 Loopback
            a == 169 && b == 254 -> true // 169.254.0.0/16 Link-local / Cloud Metadata
            a == 172 && b in 16..31 -> true // 172.16.0.0/12 Private
            a == 192 && b == 0 && (c == 0 || c == 2) -> true // 192.0.0.0/24, 192.0.2.0/24
            a == 192 && b == 168 -> true // 192.168.0.0/16 Private
            a == 198 && (b == 18 || b == 19) -> true // 198.18.0.0/15 Benchmarking
            a == 198 && b == 51 && c == 100 -> true // 198.51.100.0/24 TEST-NET-2
            a == 203 && b == 0 && c == 113 -> true // 203.0.113.0/24 TEST-NET-3
            a >= 224 -> true // 224.0.0.0/4 Multicast & 240.0.0.0/4 Reserved
            else -> false
        }
    }

    /**
     * 零 DNS 阻塞的主机名/IP 字面量安全性校验：拦截 localhost、内部后缀及各类私网/回环/八进制/十六进制/隧道 IPv4/IPv6 字面量。
     */
    fun isDisallowedNonPublicHost(rawHost: String): Boolean {
        val cleaned = rawHost.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .trimEnd('.')
            .lowercase(Locale.ROOT)
        if (cleaned.isBlank()) return true
        if (cleaned.contains('%')) return true // 拦截 IPv6 Zone ID (如 fe80::1%eth0) 与 URL 编码混淆
        if (cleaned == "localhost" ||
            cleaned.endsWith(".localhost") ||
            cleaned.endsWith(".local") ||
            cleaned.endsWith(".internal") ||
            cleaned.endsWith(".home.arpa") ||
            !cleaned.contains('.') && !cleaned.contains(':')
        ) {
            return true
        }
        if (cleaned.any { it.isWhitespace() || it.code < 0x20 }) return true

        // IPv4 严格无前导零点分十进制快速路径（含前导零如 0177.0.0.1 必须交由下方规范化八进制解析，严禁误当十进制 177.0.0.1 放行）
        val ipv4Parts = cleaned.split('.')
        if (ipv4Parts.size == 4 &&
            ipv4Parts.all { part ->
                part.isNotEmpty() &&
                    part.all(Char::isDigit) &&
                    (part.length == 1 || !part.startsWith('0'))
            }
        ) {
            val octets = ipv4Parts.map { it.toIntOrNull() ?: return true }
            if (octets.any { it !in 0..255 }) return true
            val byteArr = byteArrayOf(
                octets[0].toByte(),
                octets[1].toByte(),
                octets[2].toByte(),
                octets[3].toByte()
            )
            return isNonPublicIpv4Bytes(byteArr)
        }

        // 非标准十进制/八进制/十六进制 IPv4（如 2130706433、0177.0.0.1 或 0x7f000001）：借助 OkHttp 规范化检验
        if (ipv4Parts.all { part ->
                part.isNotEmpty() && (part.all(Char::isDigit) || part.startsWith("0x", ignoreCase = true))
            }
        ) {
            val canonical = "http://$cleaned/".toHttpUrlOrNull()?.host
            if (canonical != null && canonical != cleaned) {
                return isDisallowedNonPublicHost(canonical)
            }
            return true
        }

        // IPv6 字面量（包含 ':' 时 InetAddress.getByName 仅解析字面量，不触发网络 DNS 查询）
        if (cleaned.contains(':')) {
            val addr = runCatching { InetAddress.getByName(cleaned) }.getOrNull() ?: return true
            return isNonPublicInetAddress(addr)
        }

        return false
    }

    /**
     * 校验主机是否匹配受信任的根域名（精确相等或严格以 `.rootDomain` 结尾）
     */
    fun matchesDomainOrSubdomain(host: String, rootDomain: String): Boolean {
        val cleanHost = host.trim().trimEnd('.').lowercase(Locale.ROOT)
        val cleanRoot = rootDomain.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (cleanHost.isBlank() || cleanRoot.isBlank()) return false
        return cleanHost == cleanRoot || cleanHost.endsWith(".$cleanRoot")
    }

    /**
     * 校验分享链接是否属于受信任的酷狗官方分享域名（阻断 `http://127.0.0.1/?kugou.com` 或 `https://evil.com/?kugou.com` SSRF 绕过）
     */
    fun isTrustedKugouShareUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > 4096) return false
        if (trimmed.any { it.code < 0x20 }) return false
        val parsed: HttpUrl = trimmed.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "http" && parsed.scheme != "https") return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        val host = parsed.host.trimEnd('.').lowercase(Locale.ROOT)
        if (isDisallowedNonPublicHost(host)) return false
        if (host in TRUSTED_SHARE_EXACT_HOSTS) return true
        return TRUSTED_SHARE_ROOT_DOMAINS.any { root -> matchesDomainOrSubdomain(host, root) }
    }

    /**
     * 校验外部媒体/图片 URL 是否为安全的公网 HTTP(S) URL（阻断 file://、content://、内网 IP 与 URL 凭据注入）
     */
    fun isSafeExternalMediaUrl(rawUrl: String, allowLoopbackForTest: Boolean = false): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > 4096) return false
        if (trimmed.any { it.code < 0x20 }) return false
        val parsed = trimmed.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "http" && parsed.scheme != "https") return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        val host = parsed.host.trimEnd('.').lowercase(Locale.ROOT)
        if (allowLoopbackForTest && (host == "127.0.0.1" || host == "localhost")) {
            return true
        }
        return !isDisallowedNonPublicHost(host)
    }

    /**
     * 执行带 DNS 重绑定防护与逐跳连接前重定向校验的外部媒体 GET 请求：
     * 严禁依赖自动跟随重定向在请求发出后才事后校验 URL，确保遇到恶意 30x 跳转至内网/回环时零建连阻断。
     */
    @Throws(IOException::class)
    fun executeSafeExternalMediaGet(
        baseClient: OkHttpClient,
        initialUrl: String,
        headers: Map<String, String> = emptyMap(),
        maxRedirects: Int = 5,
        allowEntryLoopbackForTest: Boolean = false
    ): Response {
        val safeHopsClient = baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .apply {
                if (!allowEntryLoopbackForTest) {
                    dns(publicOnlyDns)
                }
            }
            .build()

        var currentUrl = initialUrl.trim()
        for (hop in 0..maxRedirects) {
            val allowThisHopLoopback = allowEntryLoopbackForTest && hop == 0
            if (!isSafeExternalMediaUrl(currentUrl, allowLoopbackForTest = allowThisHopLoopback)) {
                throw SecurityException("已拦截指向非公网或不安全地址的外部媒体请求: $currentUrl")
            }
            val reqBuilder = Request.Builder().url(currentUrl).get()
            for ((k, v) in headers) {
                reqBuilder.header(k, v)
            }
            val resp = safeHopsClient.newCall(reqBuilder.build()).execute()
            if (resp.code in setOf(301, 302, 303, 307, 308)) {
                val loc = resp.header("Location")?.trim().orEmpty()
                if (loc.isEmpty()) {
                    return resp
                }
                val resolvedNext = resp.request.url.resolve(loc)?.toString()
                resp.close()
                if (resolvedNext.isNullOrBlank()) {
                    throw SecurityException("外部媒体重定向目标无效: $loc")
                }
                if (!isSafeExternalMediaUrl(resolvedNext, allowLoopbackForTest = false)) {
                    throw SecurityException("已拦截指向内网或不受信任地址的外部媒体重定向: $resolvedNext")
                }
                currentUrl = resolvedNext
                continue
            }
            return resp
        }
        throw IOException("外部媒体重定向跳数超过安全上限 ($maxRedirects)")
    }

    /**
     * 校验外部浏览器跳转链接：仅允许合法的 http/https 公网地址，彻底拦截 intent://、javascript:、file://、content:// 等危险 Scheme。
     */
    fun isSafeBrowserHttpUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > 4096) return false
        if (trimmed.any { it.code < 0x20 }) return false
        val parsed = trimmed.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https" && parsed.scheme != "http") return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        return !isDisallowedNonPublicHost(parsed.host)
    }

    /**
     * 供应链安全：校验并净化在线更新返回的下载链接与跳转链接。
     * 即使第三方镜像代理（如 ghfast.top / gh-proxy.com）被劫持篡改 JSON 中的 browser_download_url 或 html_url，
     * 也强制只放行精确隶属于本仓库 `https://github.com/<owner>/<repo>/releases/...` 的官方路径，
     * 严禁直接信任无仓库路径归属的多租户 CDN 域名（如 objects.githubusercontent.com），否则自动回退钉扎至本仓库官方 Release Tag 页面。
     */
    fun sanitizeUpdateDownloadUrl(
        candidateUrl: String?,
        normalizedTag: String,
        repoOwner: String = "reneryi7",
        repoName: String = "BreezeMusic-Android"
    ): String {
        val safeTag = normalizedTag.trim()
            .replace(Regex("""[^a-zA-Z0-9._+-]"""), "")
            .ifBlank { "latest" }
        val fallbackReleaseUrl = if (safeTag == "latest") {
            "https://github.com/$repoOwner/$repoName/releases/latest"
        } else {
            "https://github.com/$repoOwner/$repoName/releases/tag/$safeTag"
        }
        val raw = candidateUrl?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > 2048 || raw.any { it.code < 0x20 } || raw.contains('\\')) {
            return fallbackReleaseUrl
        }
        val parsed = raw.toHttpUrlOrNull() ?: return fallbackReleaseUrl
        if (parsed.scheme != "https") return fallbackReleaseUrl
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return fallbackReleaseUrl
        val host = parsed.host.trimEnd('.').lowercase(Locale.ROOT)
        if (host !in TRUSTED_GITHUB_WEB_HOSTS) {
            return fallbackReleaseUrl
        }
        val normalizedPath = parsed.encodedPath.lowercase(Locale.ROOT)
        if (normalizedPath.contains("%2e") || normalizedPath.contains("%2f") || normalizedPath.contains("%5c")) {
            return fallbackReleaseUrl
        }
        if (".." in parsed.pathSegments || "." in parsed.pathSegments) {
            return fallbackReleaseUrl
        }
        val requiredReleasesPrefix = "/${repoOwner.lowercase(Locale.ROOT)}/${repoName.lowercase(Locale.ROOT)}/releases/"
        if (!normalizedPath.startsWith(requiredReleasesPrefix)) {
            return fallbackReleaseUrl
        }
        return parsed.toString()
    }

    private val TRUSTED_GITHUB_UPDATE_API_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "codeload.github.com",
        "raw.githubusercontent.com"
    )

    /**
     * 校验在线更新探测跳转目标：强制要求 HTTPS 公网地址，且当 `requireGitHubOfficialHost = true` 时严格限制在 GitHub 官方域名内。
     */
    fun isSafeUpdateProbeHopUrl(rawUrl: String, requireGitHubOfficialHost: Boolean = false): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048 || trimmed.any { it.code < 0x20 }) return false
        val parsed = trimmed.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https") return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        val host = parsed.host.trimEnd('.').lowercase(Locale.ROOT)
        if (isDisallowedNonPublicHost(host)) return false
        if (requireGitHubOfficialHost && host !in TRUSTED_GITHUB_UPDATE_API_HOSTS) return false
        return true
    }

    /**
     * 执行带逐跳 HTTPS 校验与公网 DNS 防重绑定的更新检查 GET 请求（禁止降级为 HTTP 或跳转至内网/非授信主机）。
     */
    @Throws(IOException::class)
    fun executeSafeHttpsUpdateGet(
        baseClient: OkHttpClient,
        initialUrl: String,
        headers: Map<String, String> = emptyMap(),
        requireGitHubOfficialHost: Boolean = false,
        maxRedirects: Int = 4
    ): Response {
        val noRedirectClient = baseClient.newBuilder()
            .dns(publicOnlyDns)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        var currentUrl = initialUrl.trim()
        for (hop in 0..maxRedirects) {
            if (!isSafeUpdateProbeHopUrl(currentUrl, requireGitHubOfficialHost = requireGitHubOfficialHost)) {
                throw SecurityException("已拦截不安全的更新探测地址: $currentUrl")
            }
            val reqBuilder = Request.Builder().url(currentUrl).get()
            for ((k, v) in headers) {
                reqBuilder.header(k, v)
            }
            val resp = noRedirectClient.newCall(reqBuilder.build()).execute()
            if (resp.code in setOf(301, 302, 303, 307, 308)) {
                val loc = resp.header("Location")?.trim().orEmpty()
                if (loc.isEmpty()) return resp
                val nextUrl = resp.request.url.resolve(loc)?.toString()
                resp.close()
                if (nextUrl.isNullOrBlank() ||
                    !isSafeUpdateProbeHopUrl(nextUrl, requireGitHubOfficialHost = requireGitHubOfficialHost)
                ) {
                    throw SecurityException("已拦截不安全的更新重定向目标: $loc")
                }
                currentUrl = nextUrl
                continue
            }
            return resp
        }
        throw IOException("更新探测重定向跳数超过安全上限 ($maxRedirects)")
    }

    /**
     * 编码并净化 Compose Navigation 动态路由段参数，防止包含 `/`、`?`、`#`、`%` 等特殊字符的外部 ID 引发路由注入或导航异常崩溃。
     */
    fun encodeSafeNavSegment(raw: String?, fallback: String = "0"): String {
        val cleaned = raw?.trim().orEmpty()
            .filter { it.code >= 0x20 && it != '\u007f' }
            .take(256)
        if (cleaned.isEmpty()) return fallback
        return runCatching {
            java.net.URLEncoder.encode(cleaned, "UTF-8")
                .replace("+", "%20")
                .ifBlank { fallback }
        }.getOrDefault(fallback)
    }

    /**
     * 净化临时文件名/任务 ID 片段，防止通过恶意 song.id / song.hash 实现 `../../` 缓存目录穿越。
     */
    fun sanitizeStagingToken(raw: String, fallback: String = "task"): String {
        val cleaned = raw.trim()
            .replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
            .replace("..", "_")
            .trim('.', '_')
            .take(72)
        return cleaned.ifBlank { fallback }
    }

    /**
     * 校验并规范化用户自定义音频/歌词导出目录路径：
     * 1. 剔除空字节与控制字符，将反斜杠统一为正斜杠；
     * 2. 拒绝任何包含 `..` 路径穿越段的输入；
     * 3. 拦截指向 `/data`（应用私有沙盒）、`/storage/.../Android/data|obb`（外部沙盒）、`/system`、`/proc`、`/etc` 等敏感目录的路径，违规时安全回退至默认公共目录。
     */
    fun sanitizeSafeExportDirectoryPath(
        rawPath: String?,
        defaultPath: String,
        appDataDirPath: String? = null
    ): String {
        val raw = rawPath?.trim().orEmpty()
        if (raw.isEmpty()) return defaultPath
        if (raw.any { it == '\u0000' || it.code < 0x20 }) return defaultPath

        val normalizedSlashes = raw.replace('\\', '/').replace(Regex("/+"), "/").trimEnd('/')
        if (normalizedSlashes.isEmpty() || !normalizedSlashes.startsWith('/')) {
            return defaultPath
        }

        val segments = normalizedSlashes.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.any { it == "." || it == ".." }) {
            return defaultPath
        }

        val reconstructed = "/" + segments.joinToString("/")
        val lowerPath = reconstructed.lowercase(Locale.ROOT)

        if (FORBIDDEN_EXPORT_PATH_PREFIXES.any { prefix ->
                lowerPath == prefix || lowerPath.startsWith("$prefix/")
            } || EXTERNAL_APP_SANDBOX_PATH_REGEX.containsMatchIn(lowerPath)
        ) {
            return defaultPath
        }

        if (!appDataDirPath.isNullOrBlank()) {
            val cleanAppData = appDataDirPath.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)
            if (cleanAppData.isNotEmpty() && (lowerPath == cleanAppData || lowerPath.startsWith("$cleanAppData/"))) {
                return defaultPath
            }
        }

        // 若文件系统上目标父路径已存在，进一步检查 canonicalPath 是否通过软链接指向敏感目录
        val canonical = runCatching { File(reconstructed).canonicalPath.replace('\\', '/') }.getOrNull()
        if (canonical != null && canonical.startsWith('/')) {
            val lowerCanonical = canonical.lowercase(Locale.ROOT)
            if (FORBIDDEN_EXPORT_PATH_PREFIXES.any { prefix ->
                    lowerCanonical == prefix || lowerCanonical.startsWith("$prefix/")
                } || EXTERNAL_APP_SANDBOX_PATH_REGEX.containsMatchIn(lowerCanonical)
            ) {
                return defaultPath
            }
        }

        return reconstructed
    }

    /**
     * 校验导出的目标文件严格位于目标目录内部，防止文件名或软链接逃逸。
     */
    fun resolveSafeChildFile(parentDir: File, rawFileName: String): File {
        val cleanedChars = buildString(rawFileName.length) {
            for (ch in rawFileName) {
                if (ch == '/' || ch == '\\' || ch == '\u0000' || ch.code < 0x20 ||
                    ch == ':' || ch == '*' || ch == '?' || ch == '"' || ch == '<' || ch == '>' || ch == '|'
                ) {
                    append('_')
                } else {
                    append(ch)
                }
            }
        }
        val wsTrimmed = cleanedChars.trim()
        val safeLeafName = when {
            wsTrimmed.isEmpty() -> "BreezeMusic_${System.currentTimeMillis()}"
            wsTrimmed.all { it == '.' || it.isWhitespace() } -> "_track_"
            else -> wsTrimmed.trimEnd { it == '.' || it.isWhitespace() }
                .ifBlank { "BreezeMusic_${System.currentTimeMillis()}" }
        }
        val candidate = File(parentDir, safeLeafName)
        val parentCanonical = runCatching { parentDir.canonicalFile }.getOrElse { parentDir.absoluteFile }
        val candidateCanonical = runCatching { candidate.canonicalFile }.getOrElse { candidate.absoluteFile }
        val parentPrefix = parentCanonical.path.trimEnd(File.separatorChar) + File.separator
        if (!candidateCanonical.path.startsWith(parentPrefix)) {
            throw SecurityException("Blocked path traversal outside target directory: $rawFileName")
        }
        return candidate
    }

    /**
     * 受字节上限保护的流式读取：同时防御超大 Content-Length 与无限 Chunked 响应导致的 OOM 崩溃。
     */
    @Throws(IOException::class)
    fun readBoundedBytes(
        input: InputStream,
        maxBytes: Int,
        declaredContentLength: Long = -1L
    ): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        if (declaredContentLength > maxBytes.toLong()) {
            throw IOException("Response content-length ($declaredContentLength) exceeds limit ($maxBytes)")
        }
        val initialCap = when {
            declaredContentLength in 1L..maxBytes.toLong() -> declaredContentLength.toInt().coerceAtMost(64 * 1024)
            else -> 8 * 1024
        }
        val out = ByteArrayOutputStream(initialCap)
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            if (read == 0) continue
            total += read
            if (total > maxBytes.toLong()) {
                throw IOException("Stream payload exceeded maximum allowed size ($maxBytes bytes)")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * 受字节上限保护的 OkHttp ResponseBody 字节读取。
     */
    @Throws(IOException::class)
    fun readBoundedBytes(body: ResponseBody?, maxBytes: Int): ByteArray {
        if (body == null) return ByteArray(0)
        val contentLength = body.contentLength()
        return body.byteStream().use { stream ->
            readBoundedBytes(stream, maxBytes = maxBytes, declaredContentLength = contentLength)
        }
    }

    /**
     * 受字节上限保护的 OkHttp ResponseBody UTF-8 文本读取。
     */
    @Throws(IOException::class)
    fun readBoundedUtf8(body: ResponseBody?, maxBytes: Int): String {
        val bytes = readBoundedBytes(body, maxBytes)
        return if (bytes.isEmpty()) "" else String(bytes, Charsets.UTF_8)
    }

    /**
     * 纯字节流头部解析图像宽高（支持 PNG / GIF / JPEG SOF / WebP），
     * 可在不依赖 Android BitmapFactory 的纯 JVM 环境或解码前第一时间识别超大分辨率图像炸弹（Image Bomb）。
     */
    fun peekImageDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 10) return null
        // 1. PNG: 8-byte signature + IHDR chunk (width at 16..19, height at 20..23)
        if (bytes.size >= 24 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            val w = ByteBuffer.wrap(bytes, 16, 4).order(ByteOrder.BIG_ENDIAN).int
            val h = ByteBuffer.wrap(bytes, 20, 4).order(ByteOrder.BIG_ENDIAN).int
            if (w > 0 && h > 0) return w to h
            return null
        }

        // 2. GIF87a / GIF89a: width at 6..7 (LE), height at 8..9 (LE)
        if (bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        ) {
            val w = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
            val h = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
            if (w > 0 && h > 0) return w to h
            return null
        }

        // 3. WebP: RIFF....WEBP (supports VP8X, VP8L lossless, and VP8 lossy bitstream)
        if (bytes.size >= 30 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            val chunkTag = String(bytes, 12, 4, Charsets.US_ASCII)
            when (chunkTag) {
                "VP8X" -> {
                    val w = 1 + ((bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or ((bytes[26].toInt() and 0xFF) shl 16))
                    val h = 1 + ((bytes[27].toInt() and 0xFF) or ((bytes[28].toInt() and 0xFF) shl 8) or ((bytes[29].toInt() and 0xFF) shl 16))
                    if (w > 0 && h > 0) return w to h
                }
                "VP8L" -> {
                    val b0 = bytes[21].toInt() and 0xFF
                    val b1 = bytes[22].toInt() and 0xFF
                    val b2 = bytes[23].toInt() and 0xFF
                    val b3 = bytes[24].toInt() and 0xFF
                    val w = 1 + (b0 or ((b1 and 0x3F) shl 8))
                    val h = 1 + (((b1 and 0xC0) shr 6) or (b2 shl 2) or ((b3 and 0x0F) shl 10))
                    if (w > 0 && h > 0) return w to h
                }
                "VP8 " -> {
                    val isKeyFrame = (bytes[20].toInt() and 0x01) == 0
                    val hasStartCode = (bytes[23].toInt() and 0xFF) == 0x9D &&
                        (bytes[24].toInt() and 0xFF) == 0x01 &&
                        (bytes[25].toInt() and 0xFF) == 0x2A
                    if (isKeyFrame && hasStartCode) {
                        val w = ((bytes[26].toInt() and 0xFF) or ((bytes[27].toInt() and 0xFF) shl 8)) and 0x3FFF
                        val h = ((bytes[28].toInt() and 0xFF) or ((bytes[29].toInt() and 0xFF) shl 8)) and 0x3FFF
                        if (w > 0 && h > 0) return w to h
                    }
                }
            }
        }

        // 4. BMP: "BM" + DIB header (BITMAPCOREHEADER 12B or BITMAPINFOHEADER >= 40B)
        if (bytes.size >= 26 &&
            bytes[0] == 'B'.code.toByte() &&
            bytes[1] == 'M'.code.toByte()
        ) {
            val dibSize = ByteBuffer.wrap(bytes, 14, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (dibSize == 12) {
                val w = (bytes[18].toInt() and 0xFF) or ((bytes[19].toInt() and 0xFF) shl 8)
                val h = (bytes[20].toInt() and 0xFF) or ((bytes[21].toInt() and 0xFF) shl 8)
                if (w > 0 && h > 0) return w to h
            } else if (dibSize >= 40) {
                val rawW = ByteBuffer.wrap(bytes, 18, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val rawH = ByteBuffer.wrap(bytes, 22, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val absH = abs(rawH.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (rawW > 0 && absH > 0) return rawW to absH
            }
            return null
        }

        // 5. JPEG: scan markers until SOF0..SOF15 (excluding DHT=C4, JPG=C8, DAC=CC)
        if ((bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8) {
            var pos = 2
            while (pos + 8 < bytes.size) {
                if ((bytes[pos].toInt() and 0xFF) != 0xFF) {
                    pos++
                    continue
                }
                while (pos < bytes.size && (bytes[pos].toInt() and 0xFF) == 0xFF) {
                    pos++
                }
                if (pos >= bytes.size) break
                val marker = bytes[pos].toInt() and 0xFF
                pos++
                if (marker == 0xD9 || marker == 0xDA) break
                if (marker == 0x01 || marker in 0xD0..0xD8) continue
                if (pos + 2 > bytes.size) break
                val segLen = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
                if (segLen < 2 || pos + segLen > bytes.size) break
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    if (pos + 7 <= bytes.size) {
                        val h = ((bytes[pos + 3].toInt() and 0xFF) shl 8) or (bytes[pos + 4].toInt() and 0xFF)
                        val w = ((bytes[pos + 5].toInt() and 0xFF) shl 8) or (bytes[pos + 6].toInt() and 0xFF)
                        if (w > 0 && h > 0) return w to h
                    }
                    break
                }
                pos += segLen
            }
        }
        return null
    }

    /**
     * 检查图像字节数组是否安全（非图像解压炸弹）
     */
    fun isSafeImagePayload(
        bytes: ByteArray,
        maxBytes: Int = MAX_COVER_IMAGE_BYTES,
        maxDimensionPx: Int = MAX_IMAGE_DIMENSION_PX,
        maxTotalPixels: Long = MAX_IMAGE_TOTAL_PIXELS
    ): Boolean {
        if (bytes.isEmpty() || bytes.size > maxBytes) return false
        val dims = peekImageDimensions(bytes) ?: return true
        val (w, h) = dims
        if (w <= 0 || h <= 0) return false
        if (w > maxDimensionPx || h > maxDimensionPx) return false
        val totalPixels = w.toLong() * h.toLong()
        return totalPixels <= maxTotalPixels
    }

    /**
     * 防护图像解压炸弹（Decompression Bomb）的安全位图解码器：
     * 先通过字节头与 `inJustDecodeBounds = true` 探测真实像素尺寸，超限时拒绝或按 2 的幂次降采样解码。
     */
    fun decodeBoundedBitmap(
        bytes: ByteArray,
        maxBytes: Int = MAX_COVER_IMAGE_BYTES,
        targetMaxDimensionPx: Int = 2048,
        hardMaxDimensionPx: Int = MAX_IMAGE_DIMENSION_PX,
        hardMaxTotalPixels: Long = MAX_IMAGE_TOTAL_PIXELS
    ): Bitmap? {
        if (bytes.isEmpty() || bytes.size > maxBytes) return null
        val hardMaxEdge = hardMaxDimensionPx.coerceAtLeast(1).toLong() * 2L
        val headerDims = peekImageDimensions(bytes)
        if (headerDims != null) {
            val (hw, hh) = headerDims
            if (hw <= 0 || hh <= 0 || hw.toLong() > hardMaxEdge || hh.toLong() > hardMaxEdge) {
                return null
            }
        }

        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val width = if (boundsOpts.outWidth > 0) boundsOpts.outWidth else headerDims?.first ?: 0
        val height = if (boundsOpts.outHeight > 0) boundsOpts.outHeight else headerDims?.second ?: 0
        if (width <= 0 || height <= 0) return null
        if (width.toLong() > hardMaxEdge || height.toLong() > hardMaxEdge) return null

        var sampleSize = 1
        while ((width / sampleSize) > targetMaxDimensionPx ||
            (height / sampleSize) > targetMaxDimensionPx ||
            ((width.toLong() / sampleSize) * (height.toLong() / sampleSize)) > hardMaxTotalPixels
        ) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        }.getOrNull()
    }

    private fun getOrCreateIpcKeystoreKey(forceRecreate: Boolean = false): SecretKey? {
        if (!forceRecreate && cachedIpcSecretKey != null) {
            return cachedIpcSecretKey
        }
        return runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (forceRecreate) {
                runCatching { ks.deleteEntry(IPC_KEYSTORE_ALIAS) }
                cachedIpcSecretKey = null
            }
            val existing = if (!forceRecreate) {
                runCatching { ks.getKey(IPC_KEYSTORE_ALIAS, null) as? SecretKey }.getOrNull()
            } else null
            val resolved = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        IPC_KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
                generateKey()
            }
            cachedIpcSecretKey = resolved
            resolved
        }.getOrNull()
    }

    private fun encryptIpcTokenForPrefs(token: String): String? {
        val encodeWithKey = fun(key: SecretKey): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(KEY_INTERNAL_IPC_TOKEN.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            return IPC_TOKEN_ENC_PREFIX +
                Base64.getEncoder().encodeToString(cipher.iv) + ":" +
                Base64.getEncoder().encodeToString(encrypted)
        }
        val key = getOrCreateIpcKeystoreKey(forceRecreate = false) ?: return null
        return runCatching { encodeWithKey(key) }.getOrElse {
            val rebuilt = getOrCreateIpcKeystoreKey(forceRecreate = true) ?: return null
            runCatching { encodeWithKey(rebuilt) }.getOrNull()
        }
    }

    private fun decryptIpcTokenFromPrefs(stored: String?): String? {
        val raw = stored?.trim().orEmpty()
        if (!raw.startsWith(IPC_TOKEN_ENC_PREFIX)) return null
        val payload = raw.removePrefix(IPC_TOKEN_ENC_PREFIX)
        val parts = payload.split(":")
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        val key = getOrCreateIpcKeystoreKey(forceRecreate = false) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])))
            cipher.updateAAD(KEY_INTERNAL_IPC_TOKEN.toByteArray(Charsets.UTF_8))
            val plain = String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8)
            plain.takeIf { it.length >= 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
        }.getOrNull()
    }

    /**
     * 获取或创建应用私有 256-bit 随机 IPC 能力令牌：
     * - 使用 AndroidKeyStore AES-256-GCM 硬件密钥加密持久化于本应用沙盒内（Fail-Closed：若 Keystore 不可用仅保留于进程内存，绝不明文落盘）；
     * - 自动无感迁移旧版明文存储键并立即清除旧明文条目，保证桌面小组件 PendingIntent 平滑过渡不失效；
     * - 外部第三方应用无法读取本应用私有目录，任何伪造的显式广播或 startService Intent 均因缺少合法令牌而被拦截。
     */
    fun getOrCreateInternalIpcToken(context: Context?): String {
        cachedIpcToken?.let { return it }
        synchronized(this) {
            cachedIpcToken?.let { return it }
            val prefs = runCatching {
                context?.applicationContext?.getSharedPreferences(IPC_PREFS_NAME, Context.MODE_PRIVATE)
                    ?: context?.getSharedPreferences(IPC_PREFS_NAME, Context.MODE_PRIVATE)
            }.getOrNull()

            // 1. 优先读取 AndroidKeyStore AES-256-GCM 加密持久化令牌
            val encryptedStored = runCatching { prefs?.getString(KEY_INTERNAL_IPC_TOKEN_ENC, null) }.getOrNull()
            val decryptedExisting = decryptIpcTokenFromPrefs(encryptedStored)
            if (decryptedExisting != null) {
                // 顺带确保历史明文键已彻底清理
                runCatching {
                    if (prefs?.contains(KEY_INTERNAL_IPC_TOKEN) == true) {
                        prefs.edit().remove(KEY_INTERNAL_IPC_TOKEN).apply()
                    }
                }
                cachedIpcToken = decryptedExisting
                return decryptedExisting
            }

            // 2. 平滑迁移旧版明文令牌：读出后立即改用 AES-256-GCM 加密写入并删除旧明文键
            val legacyPlain = runCatching { prefs?.getString(KEY_INTERNAL_IPC_TOKEN, null) }
                .getOrNull()
                ?.takeIf { it.length >= 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
            val resolvedToken = legacyPlain ?: run {
                val randomBytes = ByteArray(32)
                secureRandom.nextBytes(randomBytes)
                randomBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            }

            // 3. Fail-Closed 加密持久化：仅在成功由 AndroidKeyStore 加密后才写入磁盘，严禁明文回退落盘
            val encryptedPayload = encryptIpcTokenForPrefs(resolvedToken)
            runCatching {
                val editor = prefs?.edit()
                if (editor != null) {
                    if (encryptedPayload != null) {
                        editor.putString(KEY_INTERNAL_IPC_TOKEN_ENC, encryptedPayload)
                    }
                    editor.remove(KEY_INTERNAL_IPC_TOKEN)
                    editor.apply()
                }
            }
            cachedIpcToken = resolvedToken
            return resolvedToken
        }
    }

    /**
     * 敏感界面窗口纵深防护（引用计数管理，支持 LoginScreen + VerificationDialog 嵌套安全叠加）：
     * - 开启 `WindowManager.LayoutParams.FLAG_SECURE`（阻断截屏、后台录屏与系统最近任务缩略图泄露短信验证码/登录凭据）；
     * - Android 12+ (API 31+) 开启 `setHideOverlayWindows(true)` 隐藏第三方悬浮窗；
     * - 全版本（含 Android 8~11）开启 `View.filterTouchesWhenObscured = true` 阻断覆盖层点击劫持（Tapjacking）。
     */
    fun acquireSensitiveWindowProtection(window: Window?, targetView: View? = null) {
        if (window != null) {
            synchronized(protectedWindowStates) {
                val state = protectedWindowStates.getOrPut(window) { WindowSecurityState() }
                if (state.activeCount == 0) {
                    state.hadFlagSecureBefore = runCatching {
                        (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
                    }.getOrDefault(false)
                    runCatching {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching { window.setHideOverlayWindows(true) }
                    }
                }
                state.activeCount++
            }
        }
        if (targetView != null) {
            synchronized(protectedViewCounts) {
                val count = protectedViewCounts[targetView] ?: 0
                if (count == 0) {
                    protectedViewPrevFilter[targetView] = runCatching { targetView.filterTouchesWhenObscured }.getOrDefault(false)
                    runCatching { targetView.filterTouchesWhenObscured = true }
                }
                protectedViewCounts[targetView] = count + 1
            }
        }
    }

    fun releaseSensitiveWindowProtection(window: Window?, targetView: View? = null) {
        if (targetView != null) {
            synchronized(protectedViewCounts) {
                val nextCount = (protectedViewCounts[targetView] ?: 0) - 1
                if (nextCount <= 0) {
                    protectedViewCounts.remove(targetView)
                    val prev = protectedViewPrevFilter.remove(targetView) ?: false
                    runCatching { targetView.filterTouchesWhenObscured = prev }
                } else {
                    protectedViewCounts[targetView] = nextCount
                }
            }
        }
        if (window != null) {
            synchronized(protectedWindowStates) {
                val state = protectedWindowStates[window] ?: return
                state.activeCount--
                if (state.activeCount <= 0) {
                    protectedWindowStates.remove(window)
                    if (!state.hadFlagSecureBefore) {
                        runCatching { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching { window.setHideOverlayWindows(false) }
                    }
                }
            }
        }
    }

    /**
     * 为应用内部组件（如桌面小组件 PendingIntent、服务控制指令）签发防伪造令牌。
     */
    fun signInternalIntent(context: Context?, intent: Intent): Intent {
        val token = getOrCreateInternalIpcToken(context)
        intent.putExtra(EXTRA_INTERNAL_IPC_TOKEN, token)
        return intent
    }

    /**
     * 常量时间校验外部传入 Intent 是否携带合法的应用内部 IPC 令牌。
     */
    fun verifyInternalIntent(context: Context?, intent: Intent?): Boolean {
        if (intent == null) return false
        val provided = runCatching { intent.getStringExtra(EXTRA_INTERNAL_IPC_TOKEN) }.getOrNull()
        return verifyInternalIpcTokenValue(context, provided)
    }

    /**
     * 常量时间校验候选令牌字符串是否与当前应用私有 IPC 能力令牌一致。
     */
    fun verifyInternalIpcTokenValue(context: Context?, candidateToken: String?): Boolean {
        if (candidateToken.isNullOrBlank()) return false
        return verifyTokenConstantTime(expected = getOrCreateInternalIpcToken(context), provided = candidateToken)
    }

    internal fun verifyTokenConstantTime(expected: String, provided: String): Boolean {
        if (expected.isBlank() || provided.isBlank()) return false
        val expectedBytes = expected.toByteArray(Charsets.UTF_8)
        val providedBytes = provided.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expectedBytes, providedBytes)
    }
}
