package com.concept.music.ui.account

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.concept.music.data.kugou.*
import com.concept.music.util.SecurityGuards
import com.google.gson.JsonParser

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VerificationDialog(request: VerificationRequest, onAnswer: (VerificationAnswer?) -> Unit) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val callback by rememberUpdatedState(onAnswer)
    var error by remember(request.id) { mutableStateOf<String?>(null) }
    val webView = remember(request.id) {
        WebView(context).apply {
            filterTouchesWhenObscured = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.setGeolocationEnabled(false)
            @Suppress("DEPRECATION")
            settings.saveFormData = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
            webViewClient = object : WebViewClient() {
                private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    403,
                    "Blocked",
                    mapOf("Cache-Control" to "no-store"),
                    java.io.ByteArrayInputStream(ByteArray(0))
                )

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url ?: return blockedResponse()
                    val scheme = url.scheme?.lowercase().orEmpty()
                    val host = url.host?.lowercase().orEmpty()
                    if (scheme != "https" || host.isBlank()) {
                        return blockedResponse()
                    }
                    if (host == "appassets.androidplatform.net") {
                        val path = url.path.orEmpty()
                        val allowedAssetPaths = setOf(
                            "/assets/verification/index.html",
                            "/assets/verification/verifycode.js",
                            "/assets/verification/verifycode_bg_ios.wasm"
                        )
                        if (path !in allowedAssetPaths) {
                            return blockedResponse()
                        }
                        val assetResp = loader.shouldInterceptRequest(url) ?: return blockedResponse()
                        val mergedHeaders = LinkedHashMap<String, String>().apply {
                            assetResp.responseHeaders?.let { putAll(it) }
                            put("X-Content-Type-Options", "nosniff")
                            put("Referrer-Policy", "strict-origin-when-cross-origin")
                            put("Cross-Origin-Resource-Policy", "same-origin")
                            put("Cache-Control", "no-store")
                        }
                        assetResp.responseHeaders = mergedHeaders
                        return assetResp
                    }
                    if (!com.concept.music.service.AppPermissionManager.isPermissionEnabled(
                            com.concept.music.service.AppPermissionType.NETWORK_ACCESS
                        )
                    ) {
                        return blockedResponse()
                    }
                    val isTrustedCaptchaHost = com.concept.music.util.SecurityGuards.matchesDomainOrSubdomain(host, "qcloud.com") ||
                        com.concept.music.util.SecurityGuards.matchesDomainOrSubdomain(host, "qq.com") ||
                        com.concept.music.util.SecurityGuards.matchesDomainOrSubdomain(host, "gtimg.com")
                    return if (isTrustedCaptchaHost) null else blockedResponse()
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url ?: return true
                    val scheme = url.scheme?.lowercase().orEmpty()
                    val host = url.host?.lowercase().orEmpty()
                    if (scheme != "https") return true
                    if (request.isForMainFrame) {
                        return !(host == "appassets.androidplatform.net" && url.path == "/assets/verification/index.html")
                    }
                    return !(host == "appassets.androidplatform.net" ||
                        com.concept.music.util.SecurityGuards.matchesDomainOrSubdomain(host, "qcloud.com") ||
                        com.concept.music.util.SecurityGuards.matchesDomainOrSubdomain(host, "qq.com") ||
                        com.concept.music.util.SecurityGuards.matchesDomainOrSubdomain(host, "gtimg.com"))
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                    if (request.isForMainFrame) error = "验证页面加载失败，请取消后重试"
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, failure: android.net.http.SslError) {
                    handler.cancel()
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    error = "安全验证渲染进程已重置，请取消后重试"
                    runCatching {
                        (view?.parent as? ViewGroup)?.removeView(view)
                        view?.destroy()
                    }
                    return true
                }
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(this, "VerificationBridge", setOf("https://appassets.androidplatform.net")) { _, message, sourceOrigin, mainFrame, _ ->
                    if (mainFrame && sourceOrigin.toString() == "https://appassets.androidplatform.net") {
                        val data = message.data.orEmpty()
                        if (data.length <= 16_384) runCatching {
                            val obj = JsonParser.parseString(data).asJsonObject
                            if (obj.has("error")) error = "验证码组件加载或验证失败，请取消后重试"
                            else {
                                val code = obj.get("code")?.asString.orEmpty()
                                val sid = obj.get("sid")?.asString.orEmpty()
                                val edt = obj.get("edt")?.asString.orEmpty()
                                if (code.isNotBlank() && sid.isNotBlank() && edt.isNotBlank()) callback(VerificationAnswer(code, sid, edt))
                            }
                        }.onFailure { error = "验证结果无效，请重试" }
                    }
                }
                val safeAppId = request.txAppId.trim()
                if (request.type == 23 && !safeAppId.matches(Regex("^\\d{6,16}$"))) {
                    error = "验证码应用标识无效，请取消后重试"
                } else {
                    val encodedType = Uri.encode(request.type.toString())
                    val encodedAppId = Uri.encode(safeAppId)
                    loadUrl("https://appassets.androidplatform.net/assets/verification/index.html?type=$encodedType&appid=$encodedAppId")
                }
            } else error = "请更新 Android System WebView 后再进行安全验证"
        }
    }
    DisposableEffect(webView, hostView) {
        val activityWindow = (context as? android.app.Activity)?.window
        SecurityGuards.acquireSensitiveWindowProtection(activityWindow, hostView)
        onDispose {
            SecurityGuards.releaseSensitiveWindowProtection(activityWindow, hostView)
            runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.removeWebMessageListener(webView, "VerificationBridge")
                }
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.stopLoading()
                webView.clearHistory()
                webView.clearCache(true)
                webView.destroy()
            }
        }
    }
    Dialog(
        onDismissRequest = { callback(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
            SecurityGuards.acquireSensitiveWindowProtection(dialogWindow, dialogView)
            onDispose {
                SecurityGuards.releaseSensitiveWindowProtection(dialogWindow, dialogView)
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .imePadding(),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("酷狗安全验证", style = MaterialTheme.typography.titleLarge)
                Text(request.message, Modifier.padding(vertical = 8.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                AndroidView(
                    factory = { webView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 320.dp, max = 460.dp)
                )
                TextButton(onClick = { callback(null) }) { Text("取消验证") }
            }
        }
    }
}
