package com.concept.music.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.concept.music.data.model.Playlist
import com.concept.music.util.SecurityGuards
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

/**
 * 歌单与「我喜欢」本机自定义封面覆盖仓库（按账号隔离，严格限定于应用私有 custom_playlist_covers 目录）：
 * - 当酷狗云端对「我喜欢」封面存在服务端限制、审核延迟或网络波动时，提供零延迟即时生效与本机持久化兜底；
 * - 每次更换写入带时间戳的新文件名以自然刷新 Coil 缓存，并自动清理旧覆盖文件；
 * - 读取时严格校验 canonicalPath 位于受管目录内部，彻底防范路径穿越。
 */
class PlaylistCoverOverrideRepository(context: Context?) {

    private val appContext: Context? = context?.applicationContext
    private val prefs: SharedPreferences? = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _overrides = MutableStateFlow<Map<String, String>>(emptyMap())
    val overrides = _overrides.asStateFlow()

    init {
        reloadAllFromPrefs()
    }

    @Synchronized
    private fun reloadAllFromPrefs() {
        val sp = prefs ?: return
        val map = LinkedHashMap<String, String>()
        sp.all.forEach { (k, v) ->
            val path = v as? String
            if (!path.isNullOrBlank()) {
                val file = resolveManagedCoverFile(appContext, path)
                if (file != null && file.exists() && file.length() > 0L) {
                    map[k] = toStandardFileUriString(file)
                }
            }
        }
        _overrides.value = map
    }

    fun resolveOverrideCoverUri(
        userId: String?,
        playlist: Playlist?,
        fallbackPlaylistId: String = "",
        snapshot: Map<String, String> = _overrides.value
    ): String? {
        val candidateKeys = buildStorageKeys(userId, playlist, fallbackPlaylistId)
        for (key in candidateKeys) {
            val raw = snapshot[key]?.takeIf { it.isNotBlank() } ?: continue
            val ctx = appContext
            if (ctx == null) return raw
            val file = resolveManagedCoverFile(ctx, raw)
            if (file != null && file.exists() && file.length() > 0L) {
                return toStandardFileUriString(file)
            }
        }
        return null
    }

    @Synchronized
    fun saveOverrideCover(
        userId: String?,
        playlist: Playlist?,
        fallbackPlaylistId: String = "",
        jpegBytes: ByteArray
    ): String? {
        val ctx = appContext ?: return null
        if (jpegBytes.isEmpty() || jpegBytes.size > SecurityGuards.MAX_COVER_IMAGE_BYTES) return null
        val dir = File(ctx.filesDir, COVER_DIR_NAME).apply { mkdirs() }
        val keys = buildStorageKeys(userId, playlist, fallbackPlaylistId)
        if (keys.isEmpty()) return null
        val primaryKey = keys.first()
        val safeToken = SecurityGuards.sanitizeStagingToken(primaryKey, fallback = "playlist")
        val targetFile = SecurityGuards.resolveSafeChildFile(dir, "cover_${safeToken}_${System.currentTimeMillis()}.jpg")

        return runCatching {
            targetFile.writeBytes(jpegBytes)
            val newUri = toStandardFileUriString(targetFile)
            val prevFilesToDelete = LinkedHashSet<String>()
            val updated = LinkedHashMap(_overrides.value)
            val editor = prefs?.edit()
            for (k in keys) {
                updated[k]?.takeIf { it != newUri }?.let { prevFilesToDelete.add(it) }
                updated[k] = newUri
                editor?.putString(k, newUri)
            }
            editor?.apply()
            _overrides.value = updated

            // 仅清理不再被任何键引用的旧本机封面文件
            val stillReferenced = updated.values.toHashSet()
            prevFilesToDelete.forEach { oldPath ->
                if (oldPath !in stillReferenced) {
                    runCatching { resolveManagedCoverFile(ctx, oldPath)?.delete() }
                }
            }
            newUri
        }.getOrNull()
    }

    @Synchronized
    fun clearOverrideCover(
        userId: String?,
        playlist: Playlist?,
        fallbackPlaylistId: String = ""
    ) {
        val ctx = appContext
        val keys = buildStorageKeys(userId, playlist, fallbackPlaylistId)
        if (keys.isEmpty()) return
        val removedUris = LinkedHashSet<String>()
        val updated = LinkedHashMap(_overrides.value)
        val editor = prefs?.edit()
        for (k in keys) {
            updated.remove(k)?.let { removedUris.add(it) }
            editor?.remove(k)
        }
        editor?.apply()
        _overrides.value = updated

        if (ctx != null) {
            val stillReferenced = updated.values.toHashSet()
            removedUris.forEach { oldPath ->
                if (oldPath !in stillReferenced) {
                    runCatching { resolveManagedCoverFile(ctx, oldPath)?.delete() }
                }
            }
        }
    }

    internal fun buildStorageKeys(
        userId: String?,
        playlist: Playlist?,
        fallbackPlaylistId: String = ""
    ): List<String> {
        val uid = userId?.trim()?.takeIf { it.isNotEmpty() } ?: "guest"
        val result = LinkedHashSet<String>()
        val isLiked = playlist?.isLiked == true || fallbackPlaylistId == "like"
        if (isLiked) {
            result.add("${uid}_like")
        }
        val effectiveListId = playlist?.resolveEffectiveListId() ?: 0L
        if (effectiveListId > 0L) {
            result.add("${uid}_lid_$effectiveListId")
        }
        playlist?.candidateIdentityKeys()?.forEach { key ->
            result.add("${uid}_id_${key.lowercase(Locale.ROOT)}")
        }
        val cleanFallback = fallbackPlaylistId.trim().lowercase(Locale.ROOT)
        if (cleanFallback.isNotBlank() && cleanFallback != "like" && cleanFallback != "history") {
            result.add("${uid}_id_$cleanFallback")
        }
        return result.toList()
    }

    companion object {
        private const val PREFS_NAME = "concept_playlist_cover_overrides"
        internal const val COVER_DIR_NAME = "custom_playlist_covers"

        internal fun toStandardFileUriString(file: File): String {
            val rawUri = file.toURI().toString()
            return if (rawUri.startsWith("file:/") && !rawUri.startsWith("file:///")) {
                "file:///" + rawUri.removePrefix("file:/").trimStart('/')
            } else {
                rawUri
            }
        }

        /**
         * 校验并解析受本仓库托管的私有自定义封面文件路径，严格限制位于 `filesDir/custom_playlist_covers/` 内部，
         * 拒绝任意路径穿越或指向外部敏感文件的 URI。
         */
        fun resolveManagedCoverFile(context: Context?, rawPathOrUri: String?): File? {
            val ctx = context?.applicationContext ?: return null
            return resolveManagedCoverFileInRoot(File(ctx.filesDir, COVER_DIR_NAME), rawPathOrUri)
        }

        internal fun resolveManagedCoverFileInRoot(managedRootDir: File, rawPathOrUri: String?): File? {
            val trimmed = rawPathOrUri?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                return null
            }
            val candidateFile = runCatching {
                if (trimmed.startsWith("file:", ignoreCase = true)) {
                    File(java.net.URI(trimmed))
                } else {
                    File(trimmed)
                }
            }.getOrNull() ?: return null

            val rootCanonical = runCatching { managedRootDir.canonicalFile }.getOrElse { managedRootDir.absoluteFile }
            val candidateCanonical = runCatching { candidateFile.canonicalFile }.getOrElse { candidateFile.absoluteFile }
            val rootPrefix = rootCanonical.path.trimEnd(File.separatorChar) + File.separator
            if (!candidateCanonical.path.startsWith(rootPrefix)) {
                return null
            }
            return candidateCanonical
        }
    }
}
