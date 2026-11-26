package com.concept.music.data.recommendation

import com.concept.music.data.kugou.AuthState
import com.concept.music.data.kugou.sendPersonalFmFeedback
import com.concept.music.data.kugou.suspendRunCatching
import com.concept.music.data.model.Song
import com.concept.music.data.vip.VipManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 端云真实反馈桥接器：
 * 将播放结算（完播 / 中途切歌 / FM 秒切 / 单曲循环）与红心收藏行为同步写入本地长期画像，
 * 并在属于「猜你喜欢」队列时向酷狗云端 `/v2/personal_recommend` 回传真实的 `playtime` / `is_overplay` / `click_red` / `cancel_red`。
 */
object PersonalFmFeedbackBridge {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onTrackSettled(
        song: Song,
        playedMs: Long,
        durationMs: Long,
        isFromPersonalFm: Boolean,
        isNaturalComplete: Boolean,
        isRepeat: Boolean,
        isManualSkip: Boolean
    ) {
        if (song.title.isBlank() && song.hash.isBlank()) return
        val userId = runCatching { VipManager.accounts.state.value.currentId }.getOrNull()
        val isLoggedIn = runCatching { VipManager.accounts.state.value.auth == AuthState.READY }.getOrDefault(false)
        val pref = VipManager.recommendationPrefs.currentPreference(userId)
        val meta = VipManager.songTagCache.resolveFast(song)
        val isReducedProbe = isFromPersonalFm &&
            pref.mode == RecommendationMode.DISCOVERY &&
            meta.languageCode.isNotBlank() &&
            pref.weightForCode(meta.languageCode) in 1..49

        VipManager.tasteProfile.recordListenSignal(
            userId = userId,
            song = song,
            meta = meta,
            playedMs = playedMs,
            durationMs = durationMs,
            isFromPersonalFm = isFromPersonalFm,
            isNaturalComplete = isNaturalComplete,
            isRepeat = isRepeat,
            isManualSkip = isManualSkip,
            isReducedLanguageProbe = isReducedProbe
        )

        if (!isFromPersonalFm || !isLoggedIn || playedMs < 1_000L) return
        val effectiveDur = durationMs.takeIf { it > 0L } ?: song.durationMs.takeIf { it > 0L } ?: 180_000L
        val overplay = isNaturalComplete || (playedMs.toDouble() / effectiveDur.toDouble() >= 0.80)
        val playSec = (playedMs / 1000L).toInt().coerceIn(1, 3600)
        val poolCode = pref.mode.code

        ioScope.launch {
            suspendRunCatching {
                VipManager.music.sendPersonalFmFeedback(
                    action = "play",
                    song = song,
                    songPoolId = poolCode,
                    playTimeSec = playSec,
                    isOverplay = overplay
                )
            }
        }
    }

    fun onFavoriteToggled(
        song: Song,
        isLikedNow: Boolean,
        isFromPersonalFm: Boolean
    ) {
        val userId = runCatching { VipManager.accounts.state.value.currentId }.getOrNull()
        val isLoggedIn = runCatching { VipManager.accounts.state.value.auth == AuthState.READY }.getOrDefault(false)
        val pref = VipManager.recommendationPrefs.currentPreference(userId)
        val meta = VipManager.songTagCache.resolveFast(song)

        VipManager.tasteProfile.recordFavoriteSignal(
            userId = userId,
            song = song,
            meta = meta,
            isLiked = isLikedNow,
            isFromPersonalFm = isFromPersonalFm
        )

        if (!isLoggedIn || !isFromPersonalFm) return
        val poolCode = pref.mode.code
        ioScope.launch {
            suspendRunCatching {
                VipManager.music.sendPersonalFmFeedback(
                    action = if (isLikedNow) "click_red" else "cancel_red",
                    song = song,
                    songPoolId = poolCode
                )
            }
        }
    }
}
