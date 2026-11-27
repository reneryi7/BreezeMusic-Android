package com.concept.music

import com.concept.music.data.kugou.AccountProfile
import com.concept.music.data.kugou.AccountRepository
import com.concept.music.data.kugou.ApiResponse
import com.concept.music.data.kugou.AuthRepository
import com.concept.music.data.kugou.DeviceIdentity
import com.concept.music.data.kugou.KugouSession
import com.concept.music.data.kugou.KugouTransport
import com.concept.music.data.kugou.MemoryStore
import com.concept.music.data.kugou.MusicApiRepository
import com.concept.music.data.kugou.SavedAccount
import com.concept.music.data.model.AudioQuality
import com.concept.music.data.model.Playlist
import com.concept.music.data.model.Song
import com.concept.music.data.repository.LocalPlaylistRepository
import com.concept.music.service.DownloadTaskItem
import com.concept.music.service.DownloadTaskStatus
import com.concept.music.service.MusicDownloadManager
import com.google.gson.JsonObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 纯本地歌单持久化、云端自建歌单本地曲目挂载零云同步隔离，以及下载队列快捷图标状态机测试。
 */
class LocalPlaylistIsolationTest {

    private fun sampleLocalSong(index: Int, title: String = "本地无损$index") = Song(
        id = "local_track_$index",
        title = title,
        artist = "本地歌手$index",
        album = "本地专辑$index",
        coverUrl = "file:///cache/local_cover_$index.jpg",
        durationMs = 210_000L + index * 1000L,
        localUri = "content://local/audio/$index",
        localFormat = "FLAC",
        sampleRateHz = 96_000,
        bitDepth = 24,
        isHiRes = true,
        isLossless = true
    )

    private fun sampleOnlineSong(index: Int, title: String = "云端单曲$index") = Song(
        id = "online_$index",
        title = title,
        artist = "云端歌手$index",
        album = "云端专辑$index",
        coverUrl = "https://img.example.com/$index.jpg",
        durationMs = 195_000L,
        hash = "%032x".format(index)
    )

    private fun sampleCloudPlaylist(listId: Long = 123456L, title: String = "云端自建歌单", trackCount: Int = 2) = Playlist(
        id = "collection_3_10086_$listId",
        title = title,
        coverUrl = "https://img.example.com/pl.jpg",
        trackCount = trackCount,
        isCreatedByMe = true,
        isFavorite = false,
        isLiked = false,
        listId = listId,
        globalCollectionId = "collection_3_10086_$listId",
        isLocalOnly = false
    )

    private fun createLoggedInRepoWithCapture(): Triple<MusicApiRepository, MutableList<String>, MutableList<JsonObject?>> {
        val calledPaths = mutableListOf<String>()
        val capturedBodies = mutableListOf<JsonObject?>()
        val store = MemoryStore()
        val session = KugouSession(userId = "10086", token = "mock_token_local_iso", t1 = "mock_t1")
        val profile = AccountProfile(userId = "10086", nickname = "隔离测试号")
        store.saveAccounts(listOf(SavedAccount(userId = session.userId, nickname = profile.nickname)))
        store.saveSession(session)
        store.setActive(session.userId)

        val transport = object : KugouTransport {
            override suspend fun call(
                path: String,
                session: KugouSession?,
                query: Map<String, String>,
                body: JsonObject?,
                host: String,
                method: String,
                web: Boolean,
                headers: Map<String, String>,
                device: DeviceIdentity?
            ): ApiResponse {
                calledPaths.add(path)
                capturedBodies.add(body)
                return ApiResponse(JsonObject().apply {
                    addProperty("status", 1)
                    addProperty("error_code", 0)
                    addProperty("err_code", 0)
                })
            }

            override suspend fun callAdvanced(
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
                calledPaths.add(path)
                capturedBodies.add(body)
                return ApiResponse(JsonObject().apply {
                    addProperty("status", 1)
                    addProperty("error_code", 0)
                    addProperty("err_code", 0)
                })
            }

            override suspend fun requestBinaryBytes(
                path: String,
                session: KugouSession?,
                query: Map<String, String>,
                rawBodyBytes: ByteArray?,
                host: String,
                method: String,
                headers: Map<String, String>,
                sign: Boolean,
                includeDefaultParams: Boolean,
                device: DeviceIdentity?
            ): ByteArray {
                calledPaths.add(path)
                return """{"status":1,"errcode":0}""".toByteArray(Charsets.UTF_8)
            }
        }
        val auth = AuthRepository(transport, store)
        val accounts = AccountRepository(store, auth, transport)
        return Triple(MusicApiRepository(transport, accounts, store::device), calledPaths, capturedBodies)
    }

    @Test
    fun testPureLocalPlaylistCrudAndDiskPersistence() {
        val tempFile = File.createTempFile("local_pl_crud_", ".json")
        tempFile.deleteOnExit()
        try {
            val repo = LocalPlaylistRepository(context = null, customStoreFile = tempFile)
            val l1 = sampleLocalSong(1)
            val l2 = sampleLocalSong(2)
            val l3 = sampleLocalSong(3)

            // 创建纯本地歌单（包含重复曲目自动去重）
            val created = repo.createLocalPlaylist("车载无损合集", listOf(l1, l2, l1), "本机无损")
            assertTrue("纯本地歌单 ID 应以 local_pl_ 开头", created.id.startsWith(LocalPlaylistRepository.LOCAL_PLAYLIST_ID_PREFIX))
            assertTrue(created.isPureLocalPlaylist)
            assertTrue(created.isLocalOnly)
            assertEquals("纯本地歌单绝不可分配云端 listId", 0L, created.resolveEffectiveListId())
            assertEquals(2, created.trackCount)
            assertEquals(l1.coverUrl, created.coverUrl)

            // 增歌去重
            val added = repo.addSongsToLocalPlaylist(created.id, listOf(l2, l3))
            assertEquals(1, added)
            assertEquals(listOf(l3.id, l1.id, l2.id), repo.getLocalPlaylistTracks(created.id).map { it.id })

            // 重命名、修改简介与排序
            assertTrue(repo.updateLocalPlaylistMeta(created.id, "Hi-Res 私藏", "仅本机保存"))
            assertTrue(repo.reorderLocalPlaylistTracks(created.id, listOf(l2, l3, l1)))

            // 重新从磁盘反序列化验证持久化完全一致
            val reloaded = LocalPlaylistRepository(context = null, customStoreFile = tempFile)
            val loadedPl = reloaded.getLocalPlaylist(created.id)
            assertNotNull(loadedPl)
            assertEquals("Hi-Res 私藏", loadedPl!!.title)
            assertEquals("仅本机保存", loadedPl.description)
            assertTrue(loadedPl.isPureLocalPlaylist)
            assertEquals(listOf(l2.id, l3.id, l1.id), reloaded.getLocalPlaylistTracks(created.id).map { it.id })

            // 移出曲目与删除歌单
            assertEquals(1, reloaded.removeSongsFromLocalPlaylist(created.id, listOf(l3)))
            assertEquals(listOf(l2.id, l1.id), reloaded.getLocalPlaylistTracks(created.id).map { it.id })
            assertTrue(reloaded.deleteLocalPlaylist(created.id))
            assertNull(reloaded.getLocalPlaylist(created.id))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testCloudPlaylistLocalAttachmentMergeOrderAndNoDoubleCount() {
        val tempFile = File.createTempFile("local_pl_attach_", ".json")
        tempFile.deleteOnExit()
        try {
            val repo = LocalPlaylistRepository(context = null, customStoreFile = tempFile)
            val uid = "10086"
            val cloudPl = sampleCloudPlaylist(listId = 998877L, title = "深夜循环", trackCount = 2)
            val c1 = sampleOnlineSong(1)
            val c2 = sampleOnlineSong(2)
            val l1 = sampleLocalSong(10)
            val l2 = sampleLocalSong(11)

            // 只能挂载本地歌曲，在线歌曲自动忽略
            val attached = repo.attachLocalSongsToCloudPlaylist(uid, cloudPl, listOf(l1, c1, l2, l1))
            assertEquals(2, attached)
            assertEquals(2, repo.getAttachedLocalTrackCount(uid, cloudPl))

            // 首次合并：本地曲目置于前排，云端曲目紧随其后
            val mergedDefault = repo.mergeCloudAndAttachedLocalTracks(uid, cloudPl, listOf(c1, c2))
            assertEquals(listOf(l1.id, l2.id, c1.id, c2.id), mergedDefault.map { it.id })

            // 保存自定义混合顺序并重新从磁盘加载后验证精确还原
            val customOrder = listOf(c1, l2, c2, l1)
            repo.saveCloudPlaylistMergedOrder(uid, cloudPl, customOrder)

            val reloaded = LocalPlaylistRepository(context = null, customStoreFile = tempFile)
            val restoredOrder = reloaded.mergeCloudAndAttachedLocalTracks(uid, cloudPl, listOf(c1, c2))
            assertEquals(listOf(c1.id, l2.id, c2.id, l1.id), restoredOrder.map { it.id })

            // 云端歌单刷新并合并纯本地歌单时：总数精准等于云端2+本地2=4，且重复刷新绝不二次叠加
            val pureLocal = reloaded.createLocalPlaylist("纯本地测试", listOf(l1))
            val mergedOnce = reloaded.mergeWithCloudPlaylists(uid, listOf(cloudPl))
            val mergedTwice = reloaded.mergeWithCloudPlaylists(uid, listOf(cloudPl))
            assertEquals(4, mergedOnce.first { it.id == cloudPl.id }.trackCount)
            assertEquals(4, mergedTwice.first { it.id == cloudPl.id }.trackCount)
            assertTrue(mergedOnce.any { it.id == pureLocal.id && it.isPureLocalPlaylist })

            // 未登录（cloudPlaylists = emptyList）时纯本地歌单仍完整保留
            val loggedOutList = reloaded.mergeWithCloudPlaylists(null, emptyList())
            assertEquals(1, loggedOutList.size)
            assertEquals(pureLocal.id, loggedOutList.first().id)

            // 移出本地曲目后计数恢复正常
            assertEquals(1, reloaded.detachLocalSongsFromCloudPlaylist(uid, cloudPl, listOf(l1)))
            assertEquals(1, reloaded.getAttachedLocalTrackCount(uid, cloudPl))
            reloaded.clearCloudPlaylistAttachments(uid, cloudPl)
            assertEquals(0, reloaded.getAttachedLocalTrackCount(uid, cloudPl))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testMusicRepositoryZeroCloudSyncGuardForLocalSongsAndPureLocalPlaylists() = runTest {
        val (repo, calledPaths, capturedBodies) = createLoggedInRepoWithCapture()
        val pureLocalPl = Playlist(
            id = "local_pl_test_1",
            title = "纯本地歌单",
            isCreatedByMe = true,
            isLocalOnly = true,
            listId = 999L
        )
        val localSong1 = sampleLocalSong(1)
        val localSong2 = sampleLocalSong(2)
        val onlineSong = sampleOnlineSong(7)

        // 1. 删除纯本地歌单：0 次云端请求
        assertFalse(repo.deleteOrUncollectPlaylist(pureLocalPl))
        assertTrue("纯本地歌单绝不可触发云端删除接口", calledPaths.isEmpty())

        // 2. 单曲添加/移出本地歌曲：0 次云端请求
        assertFalse(repo.addSongToPlaylist(12345L, localSong1))
        assertFalse(repo.removeSongFromPlaylist(12345L, localSong1))
        assertTrue("本地歌曲单曲操作绝不可触发云端接口", calledPaths.isEmpty())

        // 3. 批量添加/移出全为本地歌曲：0 次云端请求
        assertEquals(0, repo.addSongsToPlaylistBatch(12345L, listOf(localSong1, localSong2)))
        assertFalse(repo.removeSongsFromPlaylistBatch(12345L, listOf(localSong1, localSong2)))
        assertTrue("纯本地批量加/删歌绝不可触发云端接口", calledPaths.isEmpty())

        // 4. 混合批量添加（1 首本地 + 1 首云端）：仅向云端发送云端歌曲，绝不包含本地歌曲
        val addedCount = repo.addSongsToPlaylistBatch(12345L, listOf(localSong1, onlineSong))
        assertEquals(1, addedCount)
        assertEquals(1, calledPaths.size)
        val bodyJson = capturedBodies.firstOrNull()?.toString().orEmpty()
        assertTrue("请求体应包含在线歌曲 Hash", bodyJson.contains(onlineSong.hash, ignoreCase = true))
        assertFalse("请求体绝不可包含本地歌曲标题或 ID", bodyJson.contains(localSong1.title) || bodyJson.contains(localSong1.id))
    }

    @Test
    fun testDownloadShortcutBadgeStateMachine() {
        MusicDownloadManager.resetForTest()
        try {
            val s1 = sampleOnlineSong(1)
            val s2 = sampleOnlineSong(2)
            val t1 = DownloadTaskItem("task_1", s1, AudioQuality.HI_RES, status = DownloadTaskStatus.DOWNLOADING, progress = 0.4f)
            val t2 = DownloadTaskItem("task_2", s2, AudioQuality.LOSSLESS, status = DownloadTaskStatus.QUEUED, progress = 0f)

            // 进行中：activeCount=2，平均进度=0.2，不触发已完成提醒
            MusicDownloadManager.applyTasksForTest(
                tasks = listOf(t1, t2),
                trackedIds = setOf("task_1", "task_2"),
                triggerUnseenOnIdle = false
            )
            val activeState = MusicDownloadManager.shortcutBadgeState.value
            assertEquals(2, activeState.activeCount)
            assertEquals(0.2f, activeState.overallProgress, 0.001f)
            assertFalse(activeState.hasUnseenSessionCompletion)

            // 全部完成（1成功1失败）：activeCount=0，触发未查看完成态
            val done1 = t1.copy(status = DownloadTaskStatus.COMPLETED, progress = 1f)
            val done2 = t2.copy(status = DownloadTaskStatus.FAILED, progress = 0f)
            MusicDownloadManager.applyTasksForTest(
                tasks = listOf(done1, done2),
                trackedIds = setOf("task_1", "task_2"),
                triggerUnseenOnIdle = true
            )
            val finishedState = MusicDownloadManager.shortcutBadgeState.value
            assertEquals(0, finishedState.activeCount)
            assertEquals(1, finishedState.completedInSessionCount)
            assertEquals(1, finishedState.failedInSessionCount)
            assertTrue(finishedState.hasUnseenSessionCompletion)

            // 点击查看下载队列或自动收起：清理未查看标记
            MusicDownloadManager.markDownloadQueueViewed()
            val clearedState = MusicDownloadManager.shortcutBadgeState.value
            assertEquals(0, clearedState.activeCount)
            assertFalse(clearedState.hasUnseenSessionCompletion)
        } finally {
            MusicDownloadManager.resetForTest()
        }
    }
}
