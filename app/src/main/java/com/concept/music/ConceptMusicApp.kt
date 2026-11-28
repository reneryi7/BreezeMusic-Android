package com.concept.music

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.concept.music.data.kugou.NetworkClientProvider
import com.concept.music.data.vip.VipManager
import com.concept.music.service.AmbientSoundManager
import com.concept.music.service.FocusPodManager
import com.concept.music.service.MusicDownloadManager
import com.concept.music.service.SystemLyricBridge
import com.concept.music.service.VipGuardianManager
import com.concept.music.ui.theme.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class ConceptMusicApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.init(this)
        com.concept.music.service.AppPermissionManager.init(this)
        VipManager.initialize(this)
        val appCtx = applicationContext
        // 将音效、桌面歌词与下载等组件的磁盘配置读取全部下沉至后台 IO 线程池，缩短冷启动图标页停留时间
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { com.concept.music.service.AudioEffectManager.init(appCtx) }
            runCatching { SystemLyricBridge.init(appCtx) }
            runCatching { MusicDownloadManager.init(appCtx) }
            runCatching { AmbientSoundManager.init(appCtx) }
            runCatching { FocusPodManager.init(appCtx) }
            runCatching { VipGuardianManager.init(appCtx) }
            runCatching { com.concept.music.widget.BreezeWidgetPrefs.init(appCtx) }
        }
    }

    override fun newImageLoader(): ImageLoader {
        val imageDispatcher = Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 10
        }

        // 复用全局共享连接池，减少 TLS 握手与 Socket 创建开销；
        // 对酷狗静态图片 CDN (如 webimg.bssdl.kugou.com 等无多级通配符证书的子域) 提供 HTTPS -> HTTP 降级容错
        val okHttpClient = NetworkClientProvider.baseClient.newBuilder()
            .dns(com.concept.music.util.SecurityGuards.publicOnlyDns)
            .dispatcher(imageDispatcher)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                if (!com.concept.music.service.AppPermissionManager.isPermissionEnabled(
                        com.concept.music.service.AppPermissionType.NETWORK_ACCESS
                    )
                ) {
                    throw java.io.IOException("已关闭「网络访问与网络状态检测」权限，离线隔离模式已拦截在线图片加载")
                }
                val initialUrl = chain.request().url.toString()
                if (!com.concept.music.util.SecurityGuards.isSafeExternalMediaUrl(initialUrl)) {
                    throw java.io.IOException("已拦截指向非公网或不安全地址的图片请求")
                }
                val headers = mapOf(
                    "Referer" to "https://www.kugou.com/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
                try {
                    com.concept.music.util.SecurityGuards.executeSafeExternalMediaGet(
                        baseClient = NetworkClientProvider.baseClient,
                        initialUrl = initialUrl,
                        headers = headers
                    )
                } catch (sslEx: SSLException) {
                    val url = chain.request().url
                    val host = url.host.lowercase()
                    val isPublicImageCdn = (host.endsWith(".kugou.com") || host.endsWith(".kgimg.com")) &&
                        !host.endsWith(".user.kugou.com") &&
                        host !in setOf(
                            "gateway.kugou.com",
                            "gatewayretry.kugou.com",
                            "login.user.kugou.com",
                            "login-user.kugou.com",
                            "userservice.kugou.com",
                            "vip.kugou.com",
                            "tracker.kugou.com"
                        )
                    if (url.isHttps && isPublicImageCdn) {
                        val fallbackUrl = url.newBuilder().scheme("http").build().toString()
                        com.concept.music.util.SecurityGuards.executeSafeExternalMediaGet(
                            baseClient = NetworkClientProvider.baseClient,
                            initialUrl = fallbackUrl,
                            headers = headers
                        )
                    } else {
                        throw sslEx
                    }
                }
            }
            .addNetworkInterceptor { chain ->
                val hopUrl = chain.request().url.toString()
                if (!com.concept.music.util.SecurityGuards.isSafeExternalMediaUrl(hopUrl)) {
                    throw java.io.IOException("已拦截指向非公网或不安全地址的图片重定向跳转")
                }
                chain.proceed(chain.request())
            }
            .build()

        // 必须保持 allowHardware(false) + ARGB_8888 并剥离 Gainmap：
        // 酷狗封面图含有非标 AGTM Gainmap 元数据，若使用 HARDWARE 位图在 Android 14+ 旋转/缩放绘制会触发 libhwui 原生闪退
        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .allowHardware(false)
            .bitmapConfig(android.graphics.Bitmap.Config.ARGB_8888)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(coil.intercept.Interceptor { chain ->
                    val result = chain.proceed(chain.request)
                    if (Build.VERSION.SDK_INT >= 34 && result is coil.request.SuccessResult) {
                        val bmp = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        if (bmp != null && bmp.hasGainmap()) {
                            runCatching { bmp.gainmap = null }
                        }
                    }
                    result
                })
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.05)
                    .build()
            }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
    }
}
