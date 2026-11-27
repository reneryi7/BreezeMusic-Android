package com.concept.music.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class Playlist(
    val id: String,
    val title: String,
    val coverUrl: String = "",
    val trackCount: Int = 0,
    val playCount: String = "",
    val description: String = "",
    val creatorName: String = "",
    val isCreatedByMe: Boolean = true,
    val isFavorite: Boolean = false,
    val isLiked: Boolean = false,
    val listId: Long = 0L,
    val globalCollectionId: String = "",
    val totalVersion: Long = 0L,
    val listCreateUserid: String = "",
    val listCreateListid: String = "",
    val listCreateGid: String = "",
    val specialId: String = "",
    val isLocalOnly: Boolean = false,
    val hasCustomCover: Boolean = false
) {
    val songCount: Int get() = trackCount

    /** 是否为纯本机自建歌单（绝不同步歌单名与曲目至云端） */
    val isPureLocalPlaylist: Boolean
        get() = isLocalOnly || id.startsWith("local_pl_")

    /** 是否为酷狗系统遗留的「默认收藏」歌单（is_def = 1） */
    val isDefaultCollect: Boolean
        get() = title.trim() == "默认收藏"

    /**
     * 当前歌单是否已显式设置自定义封面（云端 custom 图床或本机受管自定义覆盖）；
     * 空白封面与歌曲自动同步封面 (`stdmusic/...`) 均返回 false，允许添加新歌时自动同步最新歌曲封面。
     */
    val isCustomCoverApplied: Boolean
        get() = hasCustomCover || isCustomPlaylistCoverUrl(coverUrl)

    /**
     * 解析酷狗云端歌单的数字 listid（支持从 listId、listCreateListid 及 collection_3_{uid}_{listid}_0 中提取）
     */
    fun resolveEffectiveListId(): Long {
        if (isPureLocalPlaylist) return 0L
        if (listId > 0L) return listId
        listCreateListid.trim().toLongOrNull()?.takeIf { it > 0L }?.let { return it }
        for (raw in listOf(globalCollectionId, listCreateGid, id, specialId)) {
            val clean = raw.trim()
            if (clean.startsWith("collection_", ignoreCase = true)) {
                val parts = clean.split("_")
                if (parts.size >= 4) {
                    parts[3].trim().toLongOrNull()?.takeIf { it > 0L }?.let { return it }
                }
            }
        }
        return id.trim().toLongOrNull()?.takeIf { it > 0L } ?: 0L
    }

    /**
     * 提取歌单所有可能匹配的身份标识集合（兼容 specialId、global_collection_id、list_create_gid、list_create_listid 以及 GID 内嵌的原始 listId）
     */
    fun candidateIdentityKeys(): Set<String> {
        val result = LinkedHashSet<String>()
        fun addKey(raw: String?) {
            val clean = raw?.trim().orEmpty()
            if (clean.isEmpty() || clean == "0") return
            result.add(clean.lowercase())
            if (clean.startsWith("collection_", ignoreCase = true)) {
                val parts = clean.split("_")
                // collection_3_{createUserid}_{createListid}_{...}
                if (parts.size >= 4) {
                    val embeddedListId = parts[3].trim()
                    if (embeddedListId.isNotEmpty() && embeddedListId != "0" && embeddedListId.all { it.isDigit() }) {
                        result.add(embeddedListId)
                    }
                }
            }
        }
        addKey(id)
        addKey(specialId)
        addKey(globalCollectionId)
        addKey(listCreateGid)
        addKey(listCreateListid)
        if (listId > 0L) {
            result.add(listId.toString())
        }
        return result
    }

    fun matchesIdentifier(rawId: String?): Boolean {
        val clean = rawId?.trim().orEmpty()
        if (clean.isEmpty() || clean == "0") return false
        val targetKeys = LinkedHashSet<String>()
        targetKeys.add(clean.lowercase())
        if (clean.startsWith("collection_", ignoreCase = true)) {
            val parts = clean.split("_")
            if (parts.size >= 4) {
                val embedded = parts[3].trim()
                if (embedded.isNotEmpty() && embedded != "0" && embedded.all { it.isDigit() }) {
                    targetKeys.add(embedded)
                }
            }
        }
        val myKeys = candidateIdentityKeys()
        return targetKeys.any { it in myKeys }
    }

    fun isSamePlaylistAs(other: Playlist?): Boolean {
        if (other == null) return false
        if (this.listId > 0L && other.listId > 0L && this.listId == other.listId) return true
        val myKeys = candidateIdentityKeys()
        val otherKeys = other.candidateIdentityKeys()
        if (myKeys.any { it in otherKeys }) return true
        val t1 = title.trim()
        val t2 = other.title.trim()
        val isGenericTitle = t1.isEmpty() || t1 == "精选歌单" || t1 == "未命名歌单" || t1 == "我喜欢" || t1 == "我喜欢的音乐"
        if (!isGenericTitle && !this.isCreatedByMe && !other.isCreatedByMe && t1.equals(t2, ignoreCase = true)) {
            return true
        }
        return false
    }

    @Suppress("USELESS_ELVIS")
    fun sanitizeFromJson(): Playlist {
        val safeCoverUrl = (coverUrl as String?) ?: ""
        return Playlist(
            id = (id as String?) ?: "",
            title = (title as String?) ?: "",
            coverUrl = safeCoverUrl,
            trackCount = trackCount,
            playCount = (playCount as String?) ?: "",
            description = (description as String?) ?: "",
            creatorName = (creatorName as String?) ?: "",
            isCreatedByMe = isCreatedByMe,
            isFavorite = isFavorite,
            isLiked = isLiked,
            listId = listId,
            globalCollectionId = (globalCollectionId as String?) ?: "",
            totalVersion = totalVersion,
            listCreateUserid = (listCreateUserid as String?) ?: "",
            listCreateListid = (listCreateListid as String?) ?: "",
            listCreateGid = (listCreateGid as String?) ?: "",
            specialId = (specialId as String?) ?: "",
            isLocalOnly = isLocalOnly || ((id as String?)?.startsWith("local_pl_") == true),
            hasCustomCover = hasCustomCover || isCustomPlaylistCoverUrl(safeCoverUrl)
        )
    }

    companion object {
        /**
         * 判断封面 URL / 云端相对路径是否属于「用户自定义歌单封面」：
         * - 命中酷狗自定义图床路径 (`custom/...` / `.../custom/...` / `.../userpic/...` / `.../ugc/...`)
         *   或本机自定义覆盖目录 (`/custom_playlist_covers/`) 返回 true；
         * - 空白串、歌曲封面 (`stdmusic/...`)、本地音频内嵌封面等均返回 false。
         */
        fun isCustomPlaylistCoverUrl(rawUrl: String?): Boolean {
            val clean = rawUrl?.trim().orEmpty()
            if (clean.isEmpty()) return false
            val lower = clean.lowercase()
            if (lower.contains("/custom_playlist_covers/")) return true
            if (lower.startsWith("custom/") || lower.contains("/custom/")) return true
            if (lower.startsWith("userpic/") || lower.contains("/userpic/")) return true
            if (lower.startsWith("ugc/") || lower.contains("/ugc/")) return true
            return false
        }
    }
}
