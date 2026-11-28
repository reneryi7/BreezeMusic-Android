package com.concept.music.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.concept.music.ui.components.CoverExtractedPalette
import com.concept.music.ui.components.coverPaletteMemoryCache
import com.concept.music.ui.components.extractCoverMeshColorsFromBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

internal data class WidgetLoadedArtwork(
    val primaryCover: Bitmap?,
    val nextCover1: Bitmap?,
    val nextCover2: Bitmap?,
    val extractedPalette: CoverExtractedPalette?
)

/**
 * 桌面小组件封面加载与 24-bin 色彩提取管线：
 * - 在线封面复用 ConceptMusicApp 安全 Coil ImageLoader（公网 DNS 防重绑定 + 剥离 Gainmap + 硬件位图禁用）；
 * - 本地音乐封面自动解码内嵌封面并精准降采样，杜绝 OOM；
 * - 轻量 LRU 缓存避免歌词换行、红心点击与进度刷新时重复解码或重复取色。
 */
internal object BreezeWidgetArtPipeline {
    private const val PRIMARY_COVER_PX = 240
    private const val STACK_COVER_PX = 144

    private val bitmapCache = object : LruCache<String, Bitmap>(8) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val extractedPaletteMap = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, CoverExtractedPalette>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CoverExtractedPalette>?
            ): Boolean = size > 16
        }
    )

    /**
     * 0ms 纯内存缓存窥探（供点击添加小组件与弹窗预览零阻塞首帧直出，彻底消除 ColorOS 插件页卡顿）
     */
    fun peekCachedArtwork(
        snapshot: BreezeWidgetPlaybackSnapshot,
        needStackedNextCovers: Boolean
    ): Pair<WidgetLoadedArtwork, Boolean> {
        val primaryKey = buildPrimaryArtKey(snapshot)
        val primaryCached = if (primaryKey.isNotBlank()) {
            bitmapCache.get(primaryKey)?.takeIf { !it.isRecycled }
        } else null
        val paletteCached = when {
            primaryKey.isBlank() -> null
            extractedPaletteMap[primaryKey] != null -> extractedPaletteMap[primaryKey]
            snapshot.coverUrl.isNotBlank() -> coverPaletteMemoryCache[snapshot.coverUrl]
            else -> null
        }

        val next1Key = if (needStackedNextCovers && snapshot.nextCoverUrl1.isNotBlank()) "stack:${snapshot.nextCoverUrl1}" else ""
        val next2Key = if (needStackedNextCovers && snapshot.nextCoverUrl2.isNotBlank()) "stack:${snapshot.nextCoverUrl2}" else ""
        val next1Cached = if (next1Key.isNotBlank()) bitmapCache.get(next1Key)?.takeIf { !it.isRecycled } else null
        val next2Cached = if (next2Key.isNotBlank()) bitmapCache.get(next2Key)?.takeIf { !it.isRecycled } else null

        val primaryMissing = primaryKey.isNotBlank() && primaryCached == null
        val next1Missing = next1Key.isNotBlank() && next1Cached == null
        val next2Missing = next2Key.isNotBlank() && next2Cached == null
        val fullyReady = !primaryMissing && !next1Missing && !next2Missing

        return WidgetLoadedArtwork(
            primaryCover = primaryCached,
            nextCover1 = next1Cached,
            nextCover2 = next2Cached,
            extractedPalette = paletteCached
        ) to fullyReady
    }

    suspend fun loadArtwork(
        context: Context,
        snapshot: BreezeWidgetPlaybackSnapshot,
        needStackedNextCovers: Boolean
    ): WidgetLoadedArtwork = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val primaryKey = buildPrimaryArtKey(snapshot)
        val primaryCover = loadSingleCover(
            context = appCtx,
            cacheKey = primaryKey,
            coverUrl = snapshot.coverUrl,
            localUri = if (snapshot.isLocalSong) snapshot.localUri else "",
            targetPx = PRIMARY_COVER_PX
        )

        val palette = when {
            primaryKey.isBlank() -> null
            extractedPaletteMap[primaryKey] != null -> extractedPaletteMap[primaryKey]
            snapshot.coverUrl.isNotBlank() && coverPaletteMemoryCache[snapshot.coverUrl] != null -> {
                coverPaletteMemoryCache[snapshot.coverUrl]?.also { extractedPaletteMap[primaryKey] = it }
            }
            primaryCover != null && !primaryCover.isRecycled -> {
                runCatching { extractCoverMeshColorsFromBitmap(primaryCover) }.getOrNull()?.also {
                    extractedPaletteMap[primaryKey] = it
                    if (snapshot.coverUrl.isNotBlank()) {
                        coverPaletteMemoryCache[snapshot.coverUrl] = it
                    }
                }
            }
            else -> null
        }

        if (!needStackedNextCovers) {
            return@withContext WidgetLoadedArtwork(
                primaryCover = primaryCover,
                nextCover1 = null,
                nextCover2 = null,
                extractedPalette = palette
            )
        }

        coroutineScope {
            val next1Deferred = async {
                if (snapshot.nextCoverUrl1.isNotBlank()) {
                    loadSingleCover(
                        context = appCtx,
                        cacheKey = "stack:${snapshot.nextCoverUrl1}",
                        coverUrl = snapshot.nextCoverUrl1,
                        localUri = "",
                        targetPx = STACK_COVER_PX
                    )
                } else null
            }
            val next2Deferred = async {
                if (snapshot.nextCoverUrl2.isNotBlank()) {
                    loadSingleCover(
                        context = appCtx,
                        cacheKey = "stack:${snapshot.nextCoverUrl2}",
                        coverUrl = snapshot.nextCoverUrl2,
                        localUri = "",
                        targetPx = STACK_COVER_PX
                    )
                } else null
            }
            WidgetLoadedArtwork(
                primaryCover = primaryCover,
                nextCover1 = next1Deferred.await(),
                nextCover2 = next2Deferred.await(),
                extractedPalette = palette
            )
        }
    }

    private fun buildPrimaryArtKey(snapshot: BreezeWidgetPlaybackSnapshot): String {
        if (snapshot.coverUrl.isNotBlank()) return "url:${snapshot.coverUrl}"
        if (snapshot.isLocalSong && snapshot.localUri.isNotBlank()) return "local:${snapshot.localUri}"
        return ""
    }

    private suspend fun loadSingleCover(
        context: Context,
        cacheKey: String,
        coverUrl: String,
        localUri: String,
        targetPx: Int
    ): Bitmap? {
        if (cacheKey.isBlank()) return null
        bitmapCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return cached
            bitmapCache.remove(cacheKey)
        }

        val loaded = when {
            coverUrl.isNotBlank() -> loadViaCoil(context, coverUrl, targetPx)
            localUri.isNotBlank() -> loadLocalEmbeddedCover(context, localUri, targetPx)
            else -> null
        } ?: return null

        val safeCopy = normalizeWidgetBitmap(loaded, targetPx) ?: return null
        bitmapCache.put(cacheKey, safeCopy)
        return safeCopy
    }

    private suspend fun loadViaCoil(
        context: Context,
        coverUrl: String,
        targetPx: Int
    ): Bitmap? {
        return runCatching {
            val request = ImageRequest.Builder(context)
                .data(coverUrl)
                .size(targetPx, targetPx)
                .precision(Precision.INEXACT)
                .allowHardware(false)
                .bitmapConfig(Bitmap.Config.ARGB_8888)
                .build()
            val result = context.imageLoader.execute(request)
            ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
        }.getOrNull()
    }

    private fun loadLocalEmbeddedCover(
        context: Context,
        localUriStr: String,
        targetPx: Int
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(localUriStr)
            if (uri.scheme.equals("content", ignoreCase = true) || uri.scheme.equals("file", ignoreCase = true)) {
                retriever.setDataSource(context, uri)
            } else {
                retriever.setDataSource(localUriStr)
            }
            val artBytes = retriever.embeddedPicture ?: return null
            if (artBytes.isEmpty() || artBytes.size > 10 * 1024 * 1024) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, bounds)
            val srcMax = max(bounds.outWidth, bounds.outHeight)
            if (srcMax <= 0) return null
            var inSampleSize = 1
            while (srcMax / (inSampleSize * 2) >= targetPx) {
                inSampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, decodeOpts)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun normalizeWidgetBitmap(source: Bitmap, maxEdge: Int): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        val w = source.width
        val h = source.height
        val scale = if (max(w, h) > maxEdge) maxEdge.toFloat() / max(w, h).toFloat() else 1f
        val dstW = (w * scale).roundToInt().coerceAtLeast(1)
        val dstH = (h * scale).roundToInt().coerceAtLeast(1)
        val scaled = if (dstW != w || dstH != h) {
            Bitmap.createScaledBitmap(source, dstW, dstH, true)
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: source
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && scaled.hasGainmap()) {
            runCatching { scaled.gainmap = null }
        }
        return scaled
    }
}
