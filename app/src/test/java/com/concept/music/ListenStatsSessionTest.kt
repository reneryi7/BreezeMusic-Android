package com.concept.music

import androidx.media3.common.Player
import com.concept.music.data.kugou.KugouSigner
import com.concept.music.data.model.Song
import com.concept.music.data.repository.LocalListenEvent
import com.concept.music.data.repository.LocalListenStatsRepository
import com.concept.music.data.repository.LocalListenStatsStore
import com.concept.music.data.repository.LocalListenTrackRecord
import com.concept.music.service.ListenSessionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 微风本机听歌统计 v2 全场景单元测试：
 * 覆盖 Last.fm/ListenBrainz 50%/4min 黄金门槛、短曲判定、防拉进度条作弊、暂停恢复、
 * 切音质/伴奏/错误重试会话保活、单曲循环与显式重播、倍速播放、高潮速刷过滤、
 * 账号切换隔离、播中清空防复活、以及 v1.4.1 -> v2 CSCC 重复事件智能去重迁移。
 */
class ListenStatsSessionTest {

    private fun song(
        id: String,
        title: String,
        artist: String = "周杰伦",
        durationMs: Long = 240_000L,
        hash: String = id.padEnd(32, '0').take(32)
    ): Song = Song(
        id = id,
        hash = hash,
        title = title,
        artist = artist,
        album = "测试专辑",
        durationMs = durationMs
    )

    private class Harness(initialUser: String? = "u1") {
        val repo = LocalListenStatsRepository(context = null)
        var userId: String? = initialUser
        var elapsedMs: Long = 10_000L
        var wallMs: Long = 1_800_000_000_000L

        val tracker = ListenSessionTracker(
            statsRepoProvider = { repo },
            userIdProvider = { userId },
            elapsedRealtimeProvider = { elapsedMs },
            wallClockProvider = { wallMs }
        )

        fun advance(ms: Long) {
            elapsedMs += ms
            wallMs += ms
        }
    }

    @Test
    fun testSingleFullPlayAcrossMultiple60sCheckpointsCountsExactlyOnceWithFullDuration() {
        val h = Harness()
        val s = song("s1", "晴天", durationMs = 270_000L) // 4m30s -> 50% 门槛 = 135s
        h.tracker.prepareMediaItemLoad(s, preserveSession = false)
        h.tracker.onMediaItemTransition(s.id, s, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 270_000L, 1f, isPlaying = false)
        h.tracker.onPlayingStateChanged(true, s.id, s, 270_000L, 1f)

        // 模拟原 v1.4.1 每 15s / 60s 触发点：15s, 60s, 75s, 120s 均未满 135s -> 0 次
        listOf(15_000L, 45_000L, 15_000L, 45_000L).forEach { step ->
            h.advance(step)
            h.tracker.onPeriodicTick(s.id, 270_000L, 1f)
            assertEquals("未满 50% 门槛 (135s) 前不得计次", 0, h.repo.getTotalPlayCount("u1"))
        }

        // 跨过 135s 门槛瞬间立即 +1（实时可见）
        h.advance(15_000L) // total = 135s
        h.tracker.onPeriodicTick(s.id, 270_000L, 1f)
        val midSnap = h.repo.queryRank("u1", LocalListenStatsRepository.PERIOD_ALL, nowMs = h.wallMs)
        assertEquals("刚跨过 50% 门槛应立刻记为 1 次", 1, midSnap.totalPlayCount)
        assertEquals(135_000L, midSnap.songs.single().playedMs)

        // 继续播到 180s, 195s, 240s, 255s, 270s 自然播完 -> 绝不重复计次，且精准回补至 270s
        listOf(45_000L, 15_000L, 45_000L, 15_000L, 15_000L).forEach { step ->
            h.advance(step)
            h.tracker.onPeriodicTick(s.id, 270_000L, 1f)
        }
        h.tracker.onPlaybackEnded()

        val finalSnap = h.repo.queryRank("u1", LocalListenStatsRepository.PERIOD_ALL, nowMs = h.wallMs)
        assertEquals("完整播完一遍 4 分半歌曲必须严格计为 1 次（非 4~5 次）", 1, finalSnap.totalPlayCount)
        assertEquals("累计收听时长应精准回补完整 270 秒", 270_000L, finalSnap.songs.single().playedMs)
    }

    @Test
    fun testLongTrackCapsThresholdAtFourMinutes() {
        val h = Harness()
        val symphony = song("s_long", "交响乐长曲", durationMs = 720_000L) // 12 分钟 -> 门槛封顶 4 分钟 (240s)
        h.tracker.prepareMediaItemLoad(symphony, preserveSession = false)
        h.tracker.onMediaItemTransition(symphony.id, symphony, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 720_000L, 1f, isPlaying = true)

        h.advance(239_000L)
        h.tracker.onPeriodicTick(symphony.id, 720_000L, 1f)
        assertEquals(0, h.repo.getTotalPlayCount("u1"))

        h.advance(1_000L) // 满 240s
        h.tracker.onPeriodicTick(symphony.id, 720_000L, 1f)
        assertEquals("超过 8 分钟长曲满 4 分钟应即刻计 1 次", 1, h.repo.getTotalPlayCount("u1"))

        // 继续听到 500s 后切歌
        h.advance(260_000L)
        val next = song("s_next", "下一首", durationMs = 180_000L)
        h.tracker.prepareMediaItemLoad(next, preserveSession = false)
        h.tracker.onMediaItemTransition(next.id, next, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 180_000L, 1f, isPlaying = true)

        val snap = h.repo.queryRank("u1", LocalListenStatsRepository.PERIOD_ALL, nowMs = h.wallMs)
        assertEquals(1, snap.totalPlayCount)
        assertEquals(500_000L, snap.songs.single().playedMs)
    }

    @Test
    fun testShortTracksUnder30SecondsThresholdCompletionAndScrubGuard() {
        val h = Harness()

        // 1) 20 秒短曲：80% (16s) 即时计次，播完 20s 回补全长
        val s20 = song("short_20", "前奏曲", durationMs = 20_000L)
        h.tracker.prepareMediaItemLoad(s20, preserveSession = false)
        h.tracker.onMediaItemTransition(s20.id, s20, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 20_000L, 1f, isPlaying = true)
        h.advance(15_000L)
        h.tracker.onPeriodicTick(s20.id, 20_000L, 1f)
        assertEquals(0, h.repo.getTotalPlayCount("u1"))
        h.advance(1_000L) // 16s = 80%
        h.tracker.onPeriodicTick(s20.id, 20_000L, 1f)
        assertEquals(1, h.repo.getTotalPlayCount("u1"))
        h.advance(4_000L)
        h.tracker.onPlaybackEnded()
        assertEquals(20_000L, h.repo.queryRank("u1", LocalListenStatsRepository.PERIOD_ALL, nowMs = h.wallMs).songs.first().playedMs)

        // 2) 12 秒短曲：播 9s (75%) 自然播完 -> 短曲自然播完容差计 1 次
        val s12 = song("short_12", "间奏短曲", durationMs = 12_000L)
        h.tracker.prepareMediaItemLoad(s12, preserveSession = false)
        h.tracker.onMediaItemTransition(s12.id, s12, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 12_000L, 1f, isPlaying = true)
        h.advance(9_000L)
        h.tracker.onPlaybackEnded()
        assertEquals(2, h.repo.getTotalPlayCount("u1"))

        // 3) 20 秒短曲直接快进到尾部只出声 1.5s 就播完 -> 不计次
        val sScrub = song("short_scrub", "快进短曲", durationMs = 20_000L)
        h.tracker.prepareMediaItemLoad(sScrub, preserveSession = false)
        h.tracker.onMediaItemTransition(sScrub.id, sScrub, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 20_000L, 1f, isPlaying = true)
        h.advance(1_500L)
        h.tracker.onPlaybackEnded()
        assertEquals("短曲快进到尾仅播 1.5s 不应计次", 2, h.repo.getTotalPlayCount("u1"))
    }

    @Test
    fun testScrubToEndDoesNotCountAndRewindWithinSameSessionCountsAtMostOnce() {
        val h = Harness()
        val s1 = song("s_scrub", "快进测试", durationMs = 240_000L)
        h.tracker.prepareMediaItemLoad(s1, preserveSession = false)
        h.tracker.onMediaItemTransition(s1.id, s1, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 240_000L, 1f, isPlaying = true)
        // 播 10 秒后快进到 235 秒，再播 5 秒自然播完 -> 真实有效播放仅 15s < 120s -> 0 次
        h.advance(10_000L)
        h.tracker.onMediaItemTransition(s1.id, s1, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, 240_000L, 1f, isPlaying = true)
        h.advance(5_000L)
        h.tracker.onPlaybackEnded()
        assertEquals("拉进度条跳过歌曲不得计入播放次数", 0, h.repo.getTotalPlayCount("u1"))

        // 同一会话内已达标后反复拖回开头重听：仍严格最多只计 1 次
        val s2 = song("s_rewind", "单会话反复重听", durationMs = 200_000L) // 门槛 100s
        h.tracker.prepareMediaItemLoad(s2, preserveSession = false)
        h.tracker.onMediaItemTransition(s2.id, s2, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 200_000L, 1f, isPlaying = true)
        h.advance(120_000L)
        h.tracker.onPeriodicTick(s2.id, 200_000L, 1f)
        assertEquals(1, h.repo.getTotalPlayCount("u1"))

        // 拉回开头再听 150s -> 同一会话内绝不再 +1，仅累计时长
        h.tracker.onMediaItemTransition(s2.id, s2, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, 200_000L, 1f, isPlaying = true)
        h.advance(150_000L)
        h.tracker.onPeriodicTick(s2.id, 200_000L, 1f)
        h.tracker.onPlaybackEnded()

        val snap = h.repo.queryRank("u1", LocalListenStatsRepository.PERIOD_ALL, nowMs = h.wallMs)
        assertEquals("严格一播一会话：未切歌前反复拉进度条重听最多只计 1 次", 1, snap.totalPlayCount)
        assertEquals(270_000L, snap.songs.single().playedMs)
    }

    @Test
    fun testPauseAndResumeNeverDoubleCountsAndExcludesPausedDuration() {
        val h = Harness()
        val s = song("s_pause", "夜曲", durationMs = 200_000L) // 门槛 100s
        h.tracker.prepareMediaItemLoad(s, preserveSession = false)
        h.tracker.onMediaItemTransition(s.id, s, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 200_000L, 1f, isPlaying = true)

        // 播 60s 后暂停
        h.advance(60_000L)
        h.tracker.onPlayingStateChanged(false, s.id, s, 200_000L, 1f)
        assertEquals(-1L, h.tracker.computeNextTickDelayMs())
        assertEquals(0, h.repo.getTotalPlayCount("u1"))

        // 暂停 10 分钟（不得计入播放时长）
        h.advance(600_000L)
        assertEquals(-1L, h.tracker.onPeriodicTick(s.id, 200_000L, 1f))
        assertEquals(0, h.repo.getTotalPlayCount("u1"))

        // 恢复继续播 40s（累计 100s 达标）
        h.tracker.onPlayingStateChanged(true, s.id, s, 200_000L, 1f)
        h.advance(40_000L)
        h.tracker.onPeriodicTick(s.id, 200_000L, 1f)
        assertEquals(1, h.repo.getTotalPlayCount("u1"))

        // 播到 140s 再暂停 5 分钟，随后恢复播完最后 60s
        h.advance(40_000L)
        h.tracker.onPlayingStateChanged(false, s.id, s, 200_000L, 1f)
        h.advance(300_000L)
        h.tracker.onPlayingStateChanged(true, s.id, s, 200_000L, 1f)
        h.advance(60_000L)
        h.tracker.onPlaybackEnded()

        val snap = h.repo.queryRank("u1", LocalListenStatsRepository.PERIOD_ALL, nowMs = h.wallMs)
        assertEquals("多次暂停恢复同一首歌绝不得重复计次", 1, snap.totalPlayCount)
        assertEquals("暂停时长必须被严格剔除，累计真实播放 200s", 200_000L, snap.songs.single().playedMs)
    }

    @Test
    fun testQualitySwitchAccompanyToggleAndErrorRetryPreserveSession() {
        val h = Harness()
        val s320 = song("s_q", "稻香", durationMs = 240_000L, hash = "11111111111111111111111111111111")
        val sFlac = s320.copy(hashFlac = "22222222222222222222222222222222", isLossless = true)

        // 场景 A：已达标后中途切无损音质 -> 绝不重复计次
        h.tracker.prepareMediaItemLoad(s320, preserveSession = false)
        h.tracker.onMediaItemTransition(s320.id, s320, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 240_000L, 1f, isPlaying = true)
        h.advance(130_000L)
        h.tracker.onPeriodicTick(s320.id, 240_000L, 1f)
        assertEquals(1, h.repo.getTotalPlayCount("u1"))

        h.tracker.prepareMediaItemLoad(sFlac, preserveSession = true)
        h.tracker.onMediaItemTransition(sFlac.id, sFlac, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 240_000L, 1f, isPlaying = true)
        h.advance(110_000L)
        h.tracker.onPlaybackEnded()

        val snapA = h.repo.queryRank("u1", LocalListenStatsRepository.PERIOD_ALL, nowMs = h.wallMs)
        assertEquals("中途切换音质后播完应仍为 1 次", 1, snapA.totalPlayCount)
        assertEquals(240_000L, snapA.songs.single().playedMs)

        // 场景 B：未达标时（70s）切换 KTV 伴奏/解码重试 -> 保留前置 70s，再播 50s 即达 120s 门槛
        val s2 = song("s_acc", "告白气球", durationMs = 240_000L)
        h.tracker.prepareMediaItemLoad(s2, preserveSession = false)
        h.tracker.onMediaItemTransition(s2.id, s2, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 240_000L, 1f, isPlaying = true)
        h.advance(70_000L)
        h.tracker.prepareMediaItemLoad(s2, preserveSession = true)
        h.tracker.onMediaItemTransition(s2.id, s2, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 240_000L, 1f, isPlaying = true)
        h.advance(50_000L)
        h.tracker.onPeriodicTick(s2.id, 240_000L, 1f)
        assertEquals("切伴奏保活前 70s + 后 50s = 120s 应正常达标计次", 2, h.repo.getTotalPlayCount("u1"))
    }

    @Test
    fun testRepeatOneAndExplicitUserReplayEachCountProperly() {
        val h = Harness()
        val s = song("s_loop", "七里香", durationMs = 200_000L) // 门槛 100s

        // 单曲循环：第 1 遍 200s、第 2 遍 200s、第 3 遍仅 30s 后切歌 -> 共计 2 次，400s
        h.tracker.prepareMediaItemLoad(s, preserveSession = false)
        h.tracker.onMediaItemTransition(s.id, s, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 200_000L, 1f, isPlaying = true)
        h.advance(200_000L)
        h.tracker.onMediaItemTransition(s.id, s, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT, 200_000L, 1f, isPlaying = true)
        h.advance(200_000L)
        h.tracker.onMediaItemTransition(s.id, s, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT, 200_000L, 1f, isPlaying = true)
        h.advance(30_000L)
        h.tracker.onMediaItemTransition(null, null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 0L, 1f, isPlaying = false)

        val loopSnap = h.repo.queryRank("u1", LocalListenStatsRepository.PERIOD_ALL, nowMs = h.wallMs)
        assertEquals(2, loopSnap.totalPlayCount)
        assertEquals(400_000L, loopSnap.songs.single().playedMs)

        // 用户在歌单列表再次显式点播同一首歌 -> 开启第 3 轮会话，播 150s -> 共计 3 次，550s
        h.tracker.prepareMediaItemLoad(s, preserveSession = false)
        h.tracker.onMediaItemTransition(s.id, s, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 200_000L, 1f, isPlaying = true)
        h.advance(150_000L)
        h.tracker.onPlaybackEnded()

        val afterReplaySnap = h.repo.queryRank("u1", LocalListenStatsRepository.PERIOD_ALL, nowMs = h.wallMs)
        assertEquals(3, afterReplaySnap.totalPlayCount)
        assertEquals(550_000L, afterReplaySnap.songs.single().playedMs)
    }

    @Test
    fun testPlaybackSpeedUsesContentTimeForThresholdAndWallTimeForDuration() {
        val h = Harness()

        // 2.0x 倍速听 240s 歌曲：墙上 60s = 内容 120s (50%) -> 立刻计 1 次，播完墙上 120s
        val sFast = song("s_2x", "二倍速歌曲", durationMs = 240_000L)
        h.tracker.prepareMediaItemLoad(sFast, preserveSession = false)
        h.tracker.onMediaItemTransition(sFast.id, sFast, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 240_000L, 2.0f, isPlaying = true)
        h.advance(60_000L)
        h.tracker.onPeriodicTick(sFast.id, 240_000L, 2.0f)
        assertEquals(1, h.repo.getTotalPlayCount("u1"))
        h.advance(60_000L)
        h.tracker.onPlaybackEnded()
        assertEquals(120_000L, h.repo.queryRank("u1", LocalListenStatsRepository.PERIOD_ALL, nowMs = h.wallMs).songs.first().playedMs)

        // 0.5x 慢速听 240s 歌曲：墙上 120s 仅听完 60s 内容 (25%) 不计次；墙上 240s 听完 120s 内容 (50%) 才计次
        val sSlow = song("s_half", "半倍速歌曲", durationMs = 240_000L)
        h.tracker.prepareMediaItemLoad(sSlow, preserveSession = false)
        h.tracker.onMediaItemTransition(sSlow.id, sSlow, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 240_000L, 0.5f, isPlaying = true)
        h.advance(120_000L)
        h.tracker.onPeriodicTick(sSlow.id, 240_000L, 0.5f)
        assertEquals("0.5x 播 120s 仅听完 25% 曲长不应提前计次", 1, h.repo.getTotalPlayCount("u1"))
        h.advance(120_000L)
        h.tracker.onPeriodicTick(sSlow.id, 240_000L, 0.5f)
        assertEquals("0.5x 播满 240s (内容 50%) 应计 1 次", 2, h.repo.getTotalPlayCount("u1"))
    }

    @Test
    fun testClimax30sPreviewFilteredAndUnknownDurationDynamicallyResolves() {
        val h = Harness()

        // 高潮速刷 30s 自动切下一首 -> 不计次
        val sClimax = song("s_climax", "高潮速刷曲", durationMs = 240_000L)
        val sUnknown = song("s_unknown", "未知时长流", durationMs = 0L)
        h.tracker.prepareMediaItemLoad(sClimax, preserveSession = false)
        h.tracker.onMediaItemTransition(sClimax.id, sClimax, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 240_000L, 1f, isPlaying = true)
        h.advance(30_000L)
        h.tracker.prepareMediaItemLoad(sUnknown, preserveSession = false)
        h.tracker.onMediaItemTransition(sUnknown.id, sUnknown, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 0L, 1f, isPlaying = true)
        assertEquals("高潮速刷 30s 不足 50% 不应计入排行", 0, h.repo.getTotalPlayCount("u1"))

        // 未知时长流：播 40s（未到 60s 兜底）不计次，随后 ExoPlayer 解析出真实时长 60s（门槛 30s）-> 立刻补记 1 次
        h.advance(40_000L)
        h.tracker.onPeriodicTick(sUnknown.id, 0L, 1f)
        assertEquals(0, h.repo.getTotalPlayCount("u1"))
        h.tracker.onTrackDurationUpdated(sUnknown.id, 60_000L)
        assertEquals("解析出真实时长 60s 后已播 40s >= 30s 应即刻计次", 1, h.repo.getTotalPlayCount("u1"))
    }

    @Test
    fun testClearStatsMidSongDoesNotResurrectAndAccountSwitchIsolatesCounts() {
        val h = Harness(initialUser = "u1")
        val s1 = song("s_clear", "清空测试曲", durationMs = 240_000L)
        h.tracker.prepareMediaItemLoad(s1, preserveSession = false)
        h.tracker.onMediaItemTransition(s1.id, s1, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 240_000L, 1f, isPlaying = true)
        h.advance(130_000L)
        h.tracker.onPeriodicTick(s1.id, 240_000L, 1f)
        assertEquals(1, h.repo.getTotalPlayCount("u1"))

        // 播歌中途用户清空听歌排行，随后播完触发时长回补 -> 不得复活记录
        h.repo.clear("u1")
        h.advance(110_000L)
        h.tracker.onPlaybackEnded()
        assertEquals("中途清空排行后，尾段时长回补绝不可复活已清空记录", 0, h.repo.getTotalPlayCount("u1"))

        // 账号切换隔离：u1 听满 140s 再听 30s 后切到 u2 -> u1 应保留 1 次 / 170s，u2 独立记账
        val sA = song("s_acc_a", "账号A歌曲", durationMs = 240_000L)
        val sB = song("s_acc_b", "账号B歌曲", durationMs = 240_000L)
        h.tracker.prepareMediaItemLoad(sA, preserveSession = false)
        h.tracker.onMediaItemTransition(sA.id, sA, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 240_000L, 1f, isPlaying = true)
        h.advance(140_000L)
        h.tracker.onPeriodicTick(sA.id, 240_000L, 1f)
        h.advance(30_000L)

        h.userId = "u2"
        h.tracker.onAccountChanged("u1", "u2")
        assertNull(h.tracker.currentSessionSnapshot())

        h.tracker.prepareMediaItemLoad(sB, preserveSession = false)
        h.tracker.onMediaItemTransition(sB.id, sB, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 240_000L, 1f, isPlaying = true)
        h.advance(130_000L)
        h.tracker.onPlaybackEnded()

        val u1Snap = h.repo.queryRank("u1", LocalListenStatsRepository.PERIOD_ALL, nowMs = h.wallMs)
        val u2Snap = h.repo.queryRank("u2", LocalListenStatsRepository.PERIOD_ALL, nowMs = h.wallMs)
        assertEquals(1, u1Snap.totalPlayCount)
        assertEquals(170_000L, u1Snap.songs.single().playedMs)
        assertEquals(1, u2Snap.totalPlayCount)
        assertEquals(130_000L, u2Snap.songs.single().playedMs)
    }

    @Test
    fun testV1ToV2MigrationSmartlyDeduplicatesCsccBurstsWhilePreservingLegitPlays() {
        val tempDir = Files.createTempDirectory("breeze_listen_v1_migrate").toFile()
        try {
            val baseTs = 1_800_000_000_000L
            val songA = song("sA", "单遍误记4次", durationMs = 240_000L, hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            val songB = song("sB", "循环两遍误记8次", durationMs = 210_000L, hash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
            val songC = song("sC", "短曲真实循环3次", durationMs = 60_000L, hash = "cccccccccccccccccccccccccccccccc")
            val songD = song("sD", "正常大时长记录", durationMs = 200_000L, hash = "dddddddddddddddddddddddddddddddd")

            val keyA = "h:${songA.hash}"
            val keyB = "h:${songB.hash}"
            val keyC = "h:${songC.hash}"
            val keyD = "h:${songD.hash}"

            val v1Events = mutableListOf(
                // Song A: 单遍 240s 播放被 CSCC 60s 心跳切成 4 条 15s 事件
                LocalListenEvent("a1", keyA, baseTs + 15_000L, 15_120L),
                LocalListenEvent("a2", keyA, baseTs + 75_100L, 15_080L),
                LocalListenEvent("a3", keyA, baseTs + 135_200L, 15_110L),
                LocalListenEvent("a4", keyA, baseTs + 195_300L, 15_090L),

                // Song B: 210s 单曲循环真实播了 2 遍，每遍被切成 4 条（共 8 条）
                LocalListenEvent("b1", keyB, baseTs + 1_000_000L + 15_000L, 15_100L),
                LocalListenEvent("b2", keyB, baseTs + 1_000_000L + 75_000L, 15_100L),
                LocalListenEvent("b3", keyB, baseTs + 1_000_000L + 135_000L, 15_100L),
                LocalListenEvent("b4", keyB, baseTs + 1_000_000L + 195_000L, 15_100L),
                LocalListenEvent("b5", keyB, baseTs + 1_000_000L + 225_000L, 15_100L),
                LocalListenEvent("b6", keyB, baseTs + 1_000_000L + 285_000L, 15_100L),
                LocalListenEvent("b7", keyB, baseTs + 1_000_000L + 345_000L, 15_100L),
                LocalListenEvent("b8", keyB, baseTs + 1_000_000L + 405_000L, 15_100L),

                // Song C: 60s 短曲（< 80s，不可能触发 CSCC 60s 切段），真实单曲循环 3 遍间隔 62s -> 不得误合并
                LocalListenEvent("c1", keyC, baseTs + 2_000_000L + 15_000L, 15_100L),
                LocalListenEvent("c2", keyC, baseTs + 2_000_000L + 77_000L, 15_100L),
                LocalListenEvent("c3", keyC, baseTs + 2_000_000L + 139_000L, 15_100L),

                // Song D: 正常大时长记录 -> 不得误合并
                LocalListenEvent("d1", keyD, baseTs + 3_000_000L, 180_000L),
                LocalListenEvent("d2", keyD, baseTs + 3_060_000L, 185_000L)
            )

            val v1Tracks = linkedMapOf(
                keyA to LocalListenTrackRecord(keyA, songA, totalPlayCount = 4, totalPlayedMs = 60_400L, lastPlayedAtMs = baseTs + 195_300L),
                keyB to LocalListenTrackRecord(keyB, songB, totalPlayCount = 8, totalPlayedMs = 120_800L, lastPlayedAtMs = baseTs + 1_405_000L),
                keyC to LocalListenTrackRecord(keyC, songC, totalPlayCount = 3, totalPlayedMs = 45_300L, lastPlayedAtMs = baseTs + 2_139_000L),
                keyD to LocalListenTrackRecord(keyD, songD, totalPlayCount = 2, totalPlayedMs = 365_000L, lastPlayedAtMs = baseTs + 3_060_000L)
            )

            val v1Store = LocalListenStatsStore(
                version = 1,
                tracks = v1Tracks,
                events = v1Events
            )
            File(tempDir, "stats_u_legacy.json").writeText(KugouSigner.gson.toJson(v1Store), Charsets.UTF_8)

            // 首次读取触发 v1 -> v2 自动迁移
            val repo = LocalListenStatsRepository(context = null, customStoreDir = tempDir)
            val nowQuery = baseTs + 4_000_000L
            val all = repo.queryRank("u_legacy", LocalListenStatsRepository.PERIOD_ALL, nowMs = nowQuery)
            val week = repo.queryRank("u_legacy", LocalListenStatsRepository.PERIOD_WEEK, nowMs = nowQuery)

            val byTitleAll = all.songs.associateBy { it.song.title }
            val byTitleWeek = week.songs.associateBy { it.song.title }

            assertEquals("单遍误记 4 次应归并修复为 1 次", 1, byTitleAll["单遍误记4次"]?.playCount)
            assertEquals("周榜事件同步归并为 1 次", 1, byTitleWeek["单遍误记4次"]?.playCount)
            assertTrue(
                "合并后单遍时长应校准接近完整曲长 (>= 200s)",
                (byTitleAll["单遍误记4次"]?.playedMs ?: 0L) >= 200_000L
            )

            assertEquals("单曲循环 2 遍误记 8 次应精准恢复为 2 次", 2, byTitleAll["循环两遍误记8次"]?.playCount)
            assertEquals(2, byTitleWeek["循环两遍误记8次"]?.playCount)

            assertEquals("短曲 (<80s) 真实循环 3 次必须完整保留", 3, byTitleAll["短曲真实循环3次"]?.playCount)
            assertEquals("正常非 CSCC 大时长记录必须完整保留", 2, byTitleAll["正常大时长记录"]?.playCount)

            // 验证迁移已持久化为 version = 2 且二次读取幂等
            Thread.sleep(250L)
            val persistedJson = File(tempDir, "stats_u_legacy.json").readText(Charsets.UTF_8)
            val parsedPersisted = KugouSigner.gson.fromJson(persistedJson, LocalListenStatsStore::class.java)
            assertEquals(LocalListenStatsRepository.CURRENT_SCHEMA_VERSION, parsedPersisted.version)

            val repoReloaded = LocalListenStatsRepository(context = null, customStoreDir = tempDir)
            assertEquals(all.totalPlayCount, repoReloaded.getTotalPlayCount("u_legacy"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
