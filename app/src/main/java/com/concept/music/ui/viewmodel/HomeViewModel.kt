package com.concept.music.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.concept.music.data.kugou.AuthState
import com.concept.music.data.kugou.LocalBlacklistFilter
import com.concept.music.data.kugou.editContentBlacklist
import com.concept.music.data.kugou.fetchContentBlacklist
import com.concept.music.data.kugou.fetchDailyRecommendHistoryList
import com.concept.music.data.kugou.fetchDailyRecommendHistorySongs
import com.concept.music.data.kugou.fetchUserListeningPreference
import com.concept.music.data.kugou.sendPersonalFmFeedback
import com.concept.music.data.kugou.suspendRunCatching
import com.concept.music.data.kugou.updateUserListeningPreference
import com.concept.music.data.model.BlacklistEntry
import com.concept.music.data.model.DailyHistoryEntry
import com.concept.music.data.model.FmChannel
import com.concept.music.data.model.RankItem
import com.concept.music.data.model.Song
import com.concept.music.data.vip.VipManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 猜你喜欢可撤销负反馈动作快照（支持单曲不感兴趣与少推该歌手两种粒度）
 */
data class PersonalFmDislikeUndoToken(
    val dislikedSong: Song,
    val isArtistDislike: Boolean,
    val targetArtistName: String = "",
    val targetArtistKey: String = "",
    val previousQueue: List<Song>,
    val previousIndex: Int
)

class HomeViewModel : ViewModel() {
    // 每日推荐（独立刷新边界，不受猜你喜欢屏蔽列表影响）
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val dailyRecommendSongs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _dailyRefreshing = MutableStateFlow(false)
    val dailyRefreshing: StateFlow<Boolean> = _dailyRefreshing.asStateFlow()

    // 历史每日推荐回溯（过去 6 天 + 今日 = 最近完整 7 天一周）
    private val _dailyHistoryEntries = MutableStateFlow<List<DailyHistoryEntry>>(emptyList())
    val dailyHistoryEntries: StateFlow<List<DailyHistoryEntry>> = _dailyHistoryEntries.asStateFlow()

    private val _selectedDailyHistory = MutableStateFlow<DailyHistoryEntry?>(null)
    val selectedDailyHistory: StateFlow<DailyHistoryEntry?> = _selectedDailyHistory.asStateFlow()

    private val _dailyHistorySongs = MutableStateFlow<List<Song>>(emptyList())
    val dailyHistorySongs: StateFlow<List<Song>> = _dailyHistorySongs.asStateFlow()

    private val _dailyHistoryLoading = MutableStateFlow(false)
    val dailyHistoryLoading: StateFlow<Boolean> = _dailyHistoryLoading.asStateFlow()

    // 猜你喜欢（官方私人 FM：独立刷新与专属偏好调校闭环）
    private val _personalFmSongs = MutableStateFlow<List<Song>>(emptyList())
    val personalFmSongs: StateFlow<List<Song>> = _personalFmSongs.asStateFlow()

    private val _personalFmIndex = MutableStateFlow(0)
    val personalFmIndex: StateFlow<Int> = _personalFmIndex.asStateFlow()

    private val _personalFmRefreshing = MutableStateFlow(false)
    val personalFmRefreshing: StateFlow<Boolean> = _personalFmRefreshing.asStateFlow()

    // 推荐模式：0=默认平衡, 1=偏好熟悉（相似风格非重复新曲）, 2=更多尝鲜（跨风格陌生探索）
    private val _fmSongPoolId = MutableStateFlow(0)
    val fmSongPoolId: StateFlow<Int> = _fmSongPoolId.asStateFlow()

    private var dailyHistoryJob: Job? = null
    private var fmPoolJob: Job? = null
    private var fmPreferenceSyncJob: Job? = null

    // 新歌速递（独立刷新边界）
    private val _selectedNewSongPeriod = MutableStateFlow("CHINESE")
    val selectedNewSongPeriod: StateFlow<String> = _selectedNewSongPeriod.asStateFlow()

    private val _currentNewSongs = MutableStateFlow<List<Song>>(emptyList())
    val currentNewSongs: StateFlow<List<Song>> = _currentNewSongs.asStateFlow()

    private val _newSongsLoading = MutableStateFlow(false)
    val newSongsLoading: StateFlow<Boolean> = _newSongsLoading.asStateFlow()

    private val newSongCache = ConcurrentHashMap<String, List<Song>>()
    private val rankSongsCache = ConcurrentHashMap<Int, List<Song>>()
    private val fmChannelSongsCache = ConcurrentHashMap<String, List<Song>>()
    private val refreshedRanksInSession = ConcurrentHashMap.newKeySet<Int>()
    private val refreshedFmChannelsInSession = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private val ALL_NEW_SONG_PERIOD_KEYS = listOf("CHINESE", "WESTERN", "JPKR", "CANTON")
    }

    // 实时场景电台列表 (FM Channels)
    private val _fmChannels = MutableStateFlow<List<FmChannel>>(emptyList())
    val fmChannels: StateFlow<List<FmChannel>> = _fmChannels.asStateFlow()

    // 巅峰排行榜列表
    private val _ranks = MutableStateFlow<List<RankItem>>(emptyList())
    val ranks: StateFlow<List<RankItem>> = _ranks.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val accountState = VipManager.accounts.state
    val vipStatus = VipManager.vip.state.map { it.snapshot }

    private var request: Job? = null
    private var silentPreloadJob: Job? = null
    private var loadedUser: String? = "__UNINITIALIZED__"
    private var hasInitialLoaded: Boolean = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val initialUserId = accountState.value.currentId
            hydrateFromLocalCache(initialUserId)
            loadedUser = initialUserId
            hasInitialLoaded = true
            refresh(isBackgroundRevalidate = hasAnyContentLoaded(), allowWhileRestoring = true)

            accountState.collect { state ->
                if (state.auth != AuthState.RESTORING) {
                    if (state.currentId != loadedUser || !hasInitialLoaded) {
                        loadedUser = state.currentId
                        hasInitialLoaded = true
                        dailyHistoryJob?.cancel()
                        fmPoolJob?.cancel()
                        fmPreferenceSyncJob?.cancel()
                        LocalBlacklistFilter.clear()
                        _selectedDailyHistory.value = null
                        _dailyHistoryEntries.value = emptyList()
                        _dailyHistorySongs.value = emptyList()
                        _dailyHistoryLoading.value = false
                        _fmSongPoolId.value = 0
                        hydrateFromLocalCache(state.currentId)
                        if (state.auth == AuthState.READY) {
                            syncPersonalFmPreferenceAndBlacklistSilently(state.currentId)
                            loadDailyHistoryList(force = true)
                        }
                        refresh(isBackgroundRevalidate = hasAnyContentLoaded(), allowWhileRestoring = false)
                    } else if (state.auth == AuthState.READY && _dailyHistoryEntries.value.isEmpty()) {
                        syncPersonalFmPreferenceAndBlacklistSilently(state.currentId)
                        loadDailyHistoryList(force = false)
                    }
                }
            }
        }
    }

    private fun syncPersonalFmPreferenceAndBlacklistSilently(expectedUserId: String?) {
        fmPreferenceSyncJob?.cancel()
        fmPreferenceSyncJob = viewModelScope.launch(Dispatchers.IO) {
            // 错峰让出首屏与核心登录态同步（profile / vip / userPlaylists），避免登录瞬间高并发请求拥堵
            delay(650L)
            if (accountState.value.currentId != expectedUserId || accountState.value.auth != AuthState.READY) {
                return@launch
            }
            supervisorScope {
                val prefJob = launch {
                    val pref = suspendRunCatching { VipManager.music.fetchUserListeningPreference() }.getOrNull()
                    if (pref != null && accountState.value.currentId == expectedUserId) {
                        _fmSongPoolId.value = pref.mode.coerceIn(0, 2)
                    }
                }
                val blackSongJob = launch {
                    val bSongs = suspendRunCatching { VipManager.music.fetchContentBlacklist("song") }.getOrDefault(emptyList())
                    val bSingers = suspendRunCatching { VipManager.music.fetchContentBlacklist("singer") }.getOrDefault(emptyList())
                    if (accountState.value.currentId == expectedUserId && (bSongs.isNotEmpty() || bSingers.isNotEmpty())) {
                        LocalBlacklistFilter.syncFromEntries(bSongs, bSingers)
                        applyPersonalFmBlacklistFilterOnly()
                    }
                }
                prefJob.join()
                blackSongJob.join()
            }
        }
    }

    private fun hasAnyContentLoaded(): Boolean {
        return _songs.value.isNotEmpty() ||
            _personalFmSongs.value.isNotEmpty() ||
            _currentNewSongs.value.isNotEmpty() ||
            _ranks.value.isNotEmpty()
    }

    private fun hydrateFromLocalCache(userId: String?) {
        val cache = VipManager.discoverCache
        val cachedDaily = cache.getDailyRecommend(userId).ifEmpty { cache.getDailyRecommend(null) }
        if (cachedDaily.isNotEmpty()) {
            _songs.value = cachedDaily
        }

        val cachedFm = cache.getPersonalFm(userId).ifEmpty { cache.getPersonalFm(null) }
        if (cachedFm.isNotEmpty()) {
            _personalFmSongs.value = cachedFm
            // 将本地缓存过的猜你喜欢曲目注入已推去重池，防止重启后在熟悉模式下重复推送刚推过的歌
            VipManager.music.seedSeenPersonalFmHashes(cachedFm.mapNotNull { it.hash.takeIf { h -> h.isNotBlank() } })
        }

        for (periodKey in ALL_NEW_SONG_PERIOD_KEYS) {
            val cachedPeriod = cache.getNewSongs(periodKey)
            if (cachedPeriod.isNotEmpty() && newSongCache[periodKey].isNullOrEmpty()) {
                newSongCache[periodKey] = cachedPeriod
            }
        }
        val activePeriod = _selectedNewSongPeriod.value
        if (_currentNewSongs.value.isEmpty() && !newSongCache[activePeriod].isNullOrEmpty()) {
            _currentNewSongs.value = newSongCache[activePeriod].orEmpty()
        }

        val cachedRanks = cache.getRanks()
        if (_ranks.value.isEmpty() && cachedRanks.isNotEmpty()) {
            _ranks.value = cachedRanks
        }
        for (rank in _ranks.value) {
            val cachedSongs = cache.getRankSongs(rank.rankId)
            if (cachedSongs.isNotEmpty() && rankSongsCache[rank.rankId].isNullOrEmpty()) {
                rankSongsCache[rank.rankId] = cachedSongs
            }
        }

        val cachedChannels = cache.getFmChannels()
        if (_fmChannels.value.isEmpty() && cachedChannels.isNotEmpty()) {
            _fmChannels.value = cachedChannels
        }
        for (ch in _fmChannels.value) {
            val cachedChSongs = cache.getFmChannelSongs(ch.id)
            if (cachedChSongs.isNotEmpty() && fmChannelSongsCache[ch.id].isNullOrEmpty()) {
                fmChannelSongsCache[ch.id] = cachedChSongs
            }
        }
    }

    fun refresh(
        isBackgroundRevalidate: Boolean = false,
        allowWhileRestoring: Boolean = false,
        onFinished: (() -> Unit)? = null
    ) {
        request?.cancel()
        val authState = accountState.value.auth
        if (authState == AuthState.RESTORING && !allowWhileRestoring) {
            onFinished?.invoke()
            return
        }

        val currentUserId = accountState.value.currentId
        request = viewModelScope.launch(Dispatchers.IO) {
            if (!isBackgroundRevalidate) {
                _loading.value = true
            } else {
                delay(60L)
            }
            _error.value = null
            try {
                val dailyJob = launch {
                    val daily = runCatching { VipManager.music.dailyRecommend() }.getOrDefault(emptyList())
                    if (accountState.value.currentId == currentUserId && daily.isNotEmpty()) {
                        if (_songs.value != daily) {
                            _songs.value = daily
                        }
                        VipManager.discoverCache.saveDailyRecommend(currentUserId, daily)
                        val todayCompact = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).let { d ->
                            String.format(java.util.Locale.US, "%04d%02d%02d", d.year, d.monthValue, d.dayOfMonth)
                        }
                        VipManager.discoverCache.saveDailyRecommendHistorySnapshot(currentUserId, todayCompact, daily)
                    }
                }
                val fmJob = launch {
                    if (isBackgroundRevalidate) delay(30L)
                    val activePool = _fmSongPoolId.value
                    val fm = runCatching {
                        VipManager.music.personalFm(size = 20, poolId = activePool)
                    }.getOrDefault(emptyList())
                    if (accountState.value.currentId == currentUserId && fm.isNotEmpty()) {
                        val filtered = LocalBlacklistFilter.filterSongs(fm).ifEmpty { fm }
                        val wasEmpty = _personalFmSongs.value.isEmpty()
                        if (wasEmpty || !isBackgroundRevalidate) {
                            _personalFmSongs.value = filtered
                            _personalFmIndex.value = 0
                        }
                        VipManager.discoverCache.savePersonalFm(currentUserId, filtered)
                    }
                }
                val newSongJob = launch {
                    if (isBackgroundRevalidate) delay(60L)
                    val period = _selectedNewSongPeriod.value
                    if (_currentNewSongs.value.isEmpty()) {
                        _newSongsLoading.value = true
                    }
                    val list = runCatching { VipManager.music.newSongExpress(periodKey = period) }.getOrDefault(emptyList())
                    if (accountState.value.currentId == currentUserId && list.isNotEmpty()) {
                        newSongCache[period] = list
                        if (_currentNewSongs.value != list) {
                            _currentNewSongs.value = list
                        }
                        VipManager.discoverCache.saveNewSongs(period, list)
                    }
                    _newSongsLoading.value = false
                }
                val radioJob = launch {
                    if (isBackgroundRevalidate) delay(90L)
                    val radios = runCatching { VipManager.music.fmChannels() }.getOrDefault(emptyList())
                    if (accountState.value.currentId == currentUserId && radios.isNotEmpty()) {
                        if (_fmChannels.value.map { it.id } != radios.map { it.id }) {
                            _fmChannels.value = radios
                        }
                        VipManager.discoverCache.saveFmChannels(radios)
                    }
                }
                val rankJob = launch {
                    if (isBackgroundRevalidate) delay(120L)
                    val rankList = runCatching { VipManager.music.rankList() }.getOrDefault(emptyList())
                    if (accountState.value.currentId == currentUserId && rankList.isNotEmpty()) {
                        if (_ranks.value.map { it.rankId } != rankList.map { it.rankId }) {
                            _ranks.value = rankList
                        }
                        VipManager.discoverCache.saveRanks(rankList)
                    }
                }
                if (accountState.value.auth == AuthState.READY && _dailyHistoryEntries.value.isEmpty()) {
                    loadDailyHistoryList(force = false)
                }
                dailyJob.join()
                fmJob.join()
                newSongJob.join()
                radioJob.join()
                rankJob.join()
                scheduleStaggeredSilentPreload(currentUserId)
            } catch (e: Exception) {
                if (accountState.value.currentId == currentUserId && !hasAnyContentLoaded()) {
                    _error.value = e.message ?: "发现页内容加载失败"
                }
            } finally {
                _loading.value = false
                if (onFinished != null) {
                    withContext(Dispatchers.Main) {
                        onFinished.invoke()
                    }
                }
            }
        }
    }

    fun refreshDailyRecommend(onLoaded: (List<Song>) -> Unit = {}) {
        if (_dailyRefreshing.value) return
        val currentUserId = accountState.value.currentId
        viewModelScope.launch(Dispatchers.IO) {
            _dailyRefreshing.value = true
            val daily = runCatching { VipManager.music.dailyRecommend() }.getOrDefault(emptyList())
            if (daily.isNotEmpty()) {
                _songs.value = daily
                VipManager.discoverCache.saveDailyRecommend(currentUserId, daily)
                val todayCompact = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).let { d ->
                    String.format(java.util.Locale.US, "%04d%02d%02d", d.year, d.monthValue, d.dayOfMonth)
                }
                VipManager.discoverCache.saveDailyRecommendHistorySnapshot(currentUserId, todayCompact, daily)
            }
            _dailyRefreshing.value = false
            withContext(Dispatchers.Main) {
                onLoaded(_songs.value)
            }
        }
    }

    /**
     * 刷新「猜你喜欢」曲池：严格按当前选定的模式（平衡 / 熟悉 / 尝鲜）召回新曲并应用专属黑名单过滤
     */
    fun refreshPersonalFm(onLoaded: (List<Song>) -> Unit = {}) {
        if (_personalFmRefreshing.value) return
        val currentUserId = accountState.value.currentId
        val activePool = _fmSongPoolId.value
        fmPoolJob?.cancel()
        fmPoolJob = viewModelScope.launch(Dispatchers.IO) {
            _personalFmRefreshing.value = true
            try {
                val rawList = suspendRunCatching {
                    VipManager.music.personalFm(size = 20, poolId = activePool)
                }.getOrDefault(emptyList()).ifEmpty {
                    suspendRunCatching {
                        VipManager.music.sendPersonalFmFeedback(
                            action = "change_song_pool",
                            song = null,
                            songPoolId = activePool
                        )
                    }.getOrDefault(emptyList())
                }
                val filtered = LocalBlacklistFilter.filterSongs(rawList).ifEmpty { rawList }
                if (accountState.value.currentId == currentUserId && filtered.isNotEmpty()) {
                    _personalFmSongs.value = filtered
                    _personalFmIndex.value = 0
                    VipManager.discoverCache.savePersonalFm(currentUserId, filtered)
                }
            } finally {
                _personalFmRefreshing.value = false
            }
            withContext(Dispatchers.Main) {
                onLoaded(_personalFmSongs.value)
            }
        }
    }

    fun setPersonalFmIndex(index: Int) {
        val list = _personalFmSongs.value
        if (list.isNotEmpty()) {
            _personalFmIndex.value = index.coerceIn(0, list.lastIndex)
        }
    }

    fun skipToPreviousPersonalFm(onPlay: (Song, List<Song>) -> Unit) {
        val list = _personalFmSongs.value
        if (list.isEmpty()) return
        val prevIdx = (_personalFmIndex.value - 1).coerceAtLeast(0)
        _personalFmIndex.value = prevIdx
        onPlay(list[prevIdx], list)
    }

    fun skipToNextPersonalFm(onPlay: (Song, List<Song>) -> Unit) {
        val list = _personalFmSongs.value
        if (list.isEmpty()) {
            refreshPersonalFm { loaded ->
                if (loaded.isNotEmpty()) {
                    _personalFmIndex.value = 0
                    onPlay(loaded.first(), loaded)
                }
            }
            return
        }
        val nextIdx = _personalFmIndex.value + 1
        if (nextIdx < list.size) {
            _personalFmIndex.value = nextIdx
            onPlay(list[nextIdx], list)
        } else {
            refreshPersonalFm { loaded ->
                if (loaded.isNotEmpty()) {
                    _personalFmIndex.value = 0
                    onPlay(loaded.first(), loaded)
                }
            }
        }
    }

    fun selectNewSongPeriod(periodKey: String, forceRefresh: Boolean = false) {
        _selectedNewSongPeriod.value = periodKey
        val cached = newSongCache[periodKey] ?: VipManager.discoverCache.getNewSongs(periodKey).also {
            if (it.isNotEmpty()) newSongCache[periodKey] = it
        }
        if (!cached.isNullOrEmpty()) {
            _currentNewSongs.value = cached
            if (!forceRefresh) return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (_currentNewSongs.value.isEmpty() || forceRefresh) {
                _newSongsLoading.value = true
            }
            val list = runCatching { VipManager.music.newSongExpress(periodKey = periodKey) }.getOrDefault(emptyList())
            if (list.isNotEmpty()) {
                newSongCache[periodKey] = list
                VipManager.discoverCache.saveNewSongs(periodKey, list)
                if (_selectedNewSongPeriod.value == periodKey) {
                    _currentNewSongs.value = list
                }
            }
            _newSongsLoading.value = false
        }
    }

    private fun scheduleStaggeredSilentPreload(expectedUserId: String?) {
        silentPreloadJob?.cancel()
        silentPreloadJob = viewModelScope.launch(Dispatchers.IO) {
            delay(480L)
            if (accountState.value.currentId != expectedUserId) return@launch

            val initialPeriod = _selectedNewSongPeriod.value
            for (periodKey in ALL_NEW_SONG_PERIOD_KEYS) {
                if (accountState.value.currentId != expectedUserId) return@launch
                if (periodKey == initialPeriod && !newSongCache[periodKey].isNullOrEmpty()) continue
                val songs = runCatching { VipManager.music.newSongExpress(periodKey = periodKey) }.getOrDefault(emptyList())
                if (songs.isNotEmpty() && accountState.value.currentId == expectedUserId) {
                    newSongCache[periodKey] = songs
                    VipManager.discoverCache.saveNewSongs(periodKey, songs)
                    if (_selectedNewSongPeriod.value == periodKey && _currentNewSongs.value != songs) {
                        _currentNewSongs.value = songs
                    }
                }
                delay(180L)
            }

            val currentRanks = _ranks.value
            for (rank in currentRanks) {
                if (accountState.value.currentId != expectedUserId) return@launch
                val rankSongs = runCatching {
                    VipManager.music.rankSongs(rank.rankId, page = 1, pageSize = 100)
                }.getOrDefault(emptyList())
                if (rankSongs.isNotEmpty() && accountState.value.currentId == expectedUserId) {
                    rankSongsCache[rank.rankId] = rankSongs
                    refreshedRanksInSession.add(rank.rankId)
                    VipManager.discoverCache.saveRankSongs(rank.rankId, rankSongs)
                    val previewLines = rankSongs.take(3).mapIndexed { i, s -> "${i + 1}. ${s.title} - ${s.artist}" }
                    val liveCover = rankSongs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty()
                    val updatedList = _ranks.value.map { item ->
                        if (item.rankId == rank.rankId) {
                            item.copy(
                                topSongsPreview = if (previewLines.isNotEmpty()) previewLines else item.topSongsPreview,
                                coverUrl = if (liveCover.isNotBlank() && (item.coverUrl.isBlank() || item.coverUrl.contains("20210729"))) liveCover else item.coverUrl
                            )
                        } else item
                    }
                    if (_ranks.value != updatedList) {
                        _ranks.value = updatedList
                        VipManager.discoverCache.saveRanks(updatedList)
                    }
                }
                delay(180L)
            }

            val currentChannels = _fmChannels.value
            for (channel in currentChannels) {
                if (accountState.value.currentId != expectedUserId) return@launch
                val chSongs = runCatching {
                    VipManager.music.fmSongs(channel.id, size = 20, forceNextBatch = true)
                }.getOrDefault(emptyList())
                if (chSongs.isNotEmpty() && accountState.value.currentId == expectedUserId) {
                    fmChannelSongsCache[channel.id] = chSongs
                    refreshedFmChannelsInSession.add(channel.id)
                    VipManager.discoverCache.saveFmChannelSongs(channel.id, chSongs)
                    val liveCover = chSongs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty()
                    if (liveCover.isNotBlank() && (channel.coverUrl.isBlank() || channel.coverUrl.contains("20210519"))) {
                        val updatedChannels = _fmChannels.value.map { item ->
                            if (item.id == channel.id) item.copy(coverUrl = liveCover) else item
                        }
                        if (_fmChannels.value != updatedChannels) {
                            _fmChannels.value = updatedChannels
                            VipManager.discoverCache.saveFmChannels(updatedChannels)
                        }
                    }
                }
                delay(180L)
            }
        }
    }

    fun getCachedFmChannelSongs(channelId: String): List<Song> {
        return fmChannelSongsCache[channelId]?.takeIf { it.isNotEmpty() }
            ?: VipManager.discoverCache.getFmChannelSongs(channelId)
    }

    suspend fun getFmChannelSongs(channelId: String, size: Int = 20, forceNextBatch: Boolean = true): List<Song> = withContext(Dispatchers.IO) {
        if (!forceNextBatch && refreshedFmChannelsInSession.contains(channelId)) {
            val cached = getCachedFmChannelSongs(channelId)
            if (cached.isNotEmpty()) return@withContext cached
        }
        val fresh = VipManager.music.fmSongs(channelId, size = size, forceNextBatch = true)
        if (fresh.isNotEmpty()) {
            fmChannelSongsCache[channelId] = fresh
            refreshedFmChannelsInSession.add(channelId)
            VipManager.discoverCache.saveFmChannelSongs(channelId, fresh)
        }
        fresh
    }

    suspend fun getSceneRecommendSongs(sceneId: String, size: Int = 20): List<Song> = withContext(Dispatchers.IO) {
        VipManager.music.sceneRecommendSongs(sceneId, size = size)
    }

    fun getCachedRankSongs(rankId: Int): List<Song> {
        return rankSongsCache[rankId]?.takeIf { it.isNotEmpty() }
            ?: VipManager.discoverCache.getRankSongs(rankId)
    }

    suspend fun getRankSongs(rankId: Int): List<Song> = withContext(Dispatchers.IO) {
        val list = VipManager.music.rankSongs(rankId, page = 1, pageSize = 100)
        if (list.isNotEmpty()) {
            rankSongsCache[rankId] = list
            refreshedRanksInSession.add(rankId)
            VipManager.discoverCache.saveRankSongs(rankId, list)
        }
        list
    }

    suspend fun getRankSongsAll(
        rankId: Int,
        maxSongs: Int = 500,
        onProgress: ((List<Song>) -> Unit)? = null
    ): List<Song> = withContext(Dispatchers.IO) {
        val list = VipManager.music.rankSongsAll(
            rankId = rankId,
            maxSongs = maxSongs,
            pageSize = 100,
            onProgress = onProgress
        )
        if (list.isNotEmpty()) {
            rankSongsCache[rankId] = list
            refreshedRanksInSession.add(rankId)
            VipManager.discoverCache.saveRankSongs(rankId, list)
        }
        list
    }

    /**
     * 严格边界：黑名单过滤器仅作用于「猜你喜欢」列表，绝不修改每日推荐或历史日推
     */
    fun applyPersonalFmBlacklistFilterOnly() {
        val uid = accountState.value.currentId
        val curFm = _personalFmSongs.value
        val nextFm = LocalBlacklistFilter.filterSongs(curFm)
        if (nextFm.size != curFm.size) {
            _personalFmSongs.value = nextFm
            _personalFmIndex.value = _personalFmIndex.value.coerceIn(0, (nextFm.size - 1).coerceAtLeast(0))
            VipManager.discoverCache.savePersonalFm(uid, nextFm)
        }
    }

    /**
     * 加载真实历史每日推荐日期列表（仅返回服务端真实批次与本机已存档的过去日推，绝不伪造无数据日期）
     */
    fun loadDailyHistoryList(force: Boolean = false) {
        if (!force && _dailyHistoryEntries.value.isNotEmpty()) return
        if (accountState.value.auth != AuthState.READY) return
        val uid = accountState.value.currentId
        viewModelScope.launch(Dispatchers.IO) {
            val entries = suspendRunCatching { VipManager.music.fetchDailyRecommendHistoryList() }.getOrDefault(emptyList())
            if (accountState.value.currentId == uid) {
                _dailyHistoryEntries.value = entries.take(7)
            }
        }
    }

    fun selectDailyHistory(entry: DailyHistoryEntry?) {
        dailyHistoryJob?.cancel()
        _selectedDailyHistory.value = entry
        if (entry == null) {
            _dailyHistoryLoading.value = false
            return
        }
        val uid = accountState.value.currentId
        _dailyHistorySongs.value = emptyList()
        _dailyHistoryLoading.value = true
        dailyHistoryJob = viewModelScope.launch(Dispatchers.IO) {
            val list = suspendRunCatching {
                VipManager.music.fetchDailyRecommendHistorySongs(
                    historyName = entry.historyName,
                    date = entry.compactDate.ifBlank { entry.dateLabel }
                )
            }.getOrDefault(emptyList())
            if (accountState.value.currentId == uid && _selectedDailyHistory.value?.historyName == entry.historyName) {
                _dailyHistorySongs.value = list
                _dailyHistoryLoading.value = false
            }
        }
    }

    /**
     * 猜你喜欢负反馈 — 「不听这首歌」：
     * 1. 立即同步剔除本地队列并毫秒级切歌；
     * 2. 异步向酷狗私人 FM 引擎上报 action="garbage" 并写入猜你喜欢专属云端黑名单（KG-TID=474）；
     * 3. 若剩余待播曲目不足 4 首，后台自动按当前模式静默续池。
     */
    fun dislikeCurrentPersonalFmSong(
        song: Song,
        onNextPlay: (Song, List<Song>) -> Unit = { _, _ -> },
        onFeedbackMessage: (String) -> Unit = {}
    ): PersonalFmDislikeUndoToken {
        val uid = accountState.value.currentId
        val isLoggedIn = accountState.value.auth == AuthState.READY
        val prevList = _personalFmSongs.value
        val prevIndex = _personalFmIndex.value
        val remaining = prevList.filterNot {
            it.id == song.id || (song.hash.isNotBlank() && it.hash.equals(song.hash, ignoreCase = true))
        }
        LocalBlacklistFilter.addSong(song)
        _personalFmSongs.value = remaining
        val nextIdx = prevIndex.coerceIn(0, (remaining.size - 1).coerceAtLeast(0))
        _personalFmIndex.value = nextIdx
        VipManager.discoverCache.savePersonalFm(uid, remaining)

        val immediateNext = remaining.getOrNull(nextIdx) ?: remaining.firstOrNull()
        if (immediateNext != null) {
            onNextPlay(immediateNext, remaining)
        }
        onFeedbackMessage(
            if (isLoggedIn) "已减少《${song.title}》及同类推荐" else "已跳过《${song.title}》并减少本机推送"
        )

        viewModelScope.launch(Dispatchers.IO) {
            val poolId = _fmSongPoolId.value
            val feedbackSongs = suspendRunCatching {
                VipManager.music.sendPersonalFmFeedback(
                    action = "garbage",
                    song = song,
                    songPoolId = poolId
                )
            }.getOrDefault(emptyList())

            if (isLoggedIn) {
                val songKey = song.hash.ifBlank { song.mixSongId }.trim().lowercase()
                if (songKey.isNotBlank()) {
                    suspendRunCatching {
                        VipManager.music.editContentBlacklist(
                            label = "song",
                            key = songKey,
                            name = "${song.artist} - ${song.title}",
                            mixSongId = song.mixSongId,
                            isDelete = false,
                            moduleId = 474
                        )
                    }
                }
            }

            if (accountState.value.currentId == uid && _personalFmSongs.value.size < 4) {
                val replenished = LocalBlacklistFilter.filterSongs(
                    feedbackSongs.ifEmpty {
                        suspendRunCatching { VipManager.music.personalFm(size = 20, poolId = poolId) }.getOrDefault(emptyList())
                    }
                )
                if (replenished.isNotEmpty() && accountState.value.currentId == uid) {
                    val cur = _personalFmSongs.value
                    val curHashes = cur.map { (it.hash.ifBlank { it.id }).lowercase() }.toHashSet()
                    val merged = cur + replenished.filter { (it.hash.ifBlank { it.id }).lowercase() !in curHashes }
                    _personalFmSongs.value = merged
                    VipManager.discoverCache.savePersonalFm(uid, merged)
                    if (cur.isEmpty()) {
                        val first = merged.first()
                        _personalFmIndex.value = 0
                        withContext(Dispatchers.Main) {
                            onNextPlay(first, merged)
                        }
                    }
                }
            }
        }
        return PersonalFmDislikeUndoToken(
            dislikedSong = song,
            isArtistDislike = false,
            previousQueue = prevList,
            previousIndex = prevIndex
        )
    }

    /**
     * 猜你喜欢负反馈 — 「少推该歌手」：
     * 1. 立即清空当前猜你喜欢队列中该歌手的所有曲目并切歌；
     * 2. 上报私人 FM garbage 负反馈 + 云端黑名单 singer (KG-TID=474) + 用户偏好 inactive_singerlist；
     * 3. 后台立即补拉不含该歌手的新推荐池。
     */
    fun dislikeCurrentPersonalFmArtist(
        song: Song,
        onNextPlay: (Song, List<Song>) -> Unit = { _, _ -> },
        onFeedbackMessage: (String) -> Unit = {}
    ): PersonalFmDislikeUndoToken {
        val uid = accountState.value.currentId
        val isLoggedIn = accountState.value.auth == AuthState.READY
        val primaryArtist = song.artist.split("、", "/", ",", "&").firstOrNull()?.trim().orEmpty().ifBlank { song.artist.trim() }
        val artistKey = song.artistId.trim().takeIf { it.isNotBlank() && it != "0" } ?: primaryArtist
        val prevList = _personalFmSongs.value
        val prevIndex = _personalFmIndex.value

        LocalBlacklistFilter.addArtist(artistId = artistKey, artistName = primaryArtist)
        val remaining = LocalBlacklistFilter.filterSongs(prevList)
        _personalFmSongs.value = remaining
        val nextIdx = prevIndex.coerceIn(0, (remaining.size - 1).coerceAtLeast(0))
        _personalFmIndex.value = nextIdx
        VipManager.discoverCache.savePersonalFm(uid, remaining)

        val immediateNext = remaining.getOrNull(nextIdx) ?: remaining.firstOrNull()
        if (immediateNext != null) {
            onNextPlay(immediateNext, remaining)
        }
        onFeedbackMessage(
            if (primaryArtist.isNotBlank()) "已减少推荐歌手「$primaryArtist」的作品" else "已减少该歌手推荐"
        )

        viewModelScope.launch(Dispatchers.IO) {
            val poolId = _fmSongPoolId.value
            val feedbackSongs = suspendRunCatching {
                VipManager.music.sendPersonalFmFeedback(
                    action = "garbage",
                    song = song,
                    songPoolId = poolId
                )
            }.getOrDefault(emptyList())

            if (isLoggedIn && artistKey.isNotBlank()) {
                suspendRunCatching {
                    VipManager.music.editContentBlacklist(
                        label = "singer",
                        key = artistKey,
                        name = primaryArtist.ifBlank { artistKey },
                        isDelete = false,
                        moduleId = 474
                    )
                }
                suspendRunCatching {
                    VipManager.music.updateUserListeningPreference(inactiveSingerList = primaryArtist.ifBlank { artistKey })
                }
            }

            if (accountState.value.currentId == uid && _personalFmSongs.value.size < 5) {
                val freshBatch = LocalBlacklistFilter.filterSongs(
                    suspendRunCatching { VipManager.music.personalFm(size = 20, poolId = poolId) }
                        .getOrDefault(feedbackSongs)
                )
                if (freshBatch.isNotEmpty() && accountState.value.currentId == uid) {
                    val cur = _personalFmSongs.value
                    val curHashes = cur.map { (it.hash.ifBlank { it.id }).lowercase() }.toHashSet()
                    val merged = cur + freshBatch.filter { (it.hash.ifBlank { it.id }).lowercase() !in curHashes }
                    _personalFmSongs.value = merged
                    VipManager.discoverCache.savePersonalFm(uid, merged)
                    if (cur.isEmpty()) {
                        val first = merged.first()
                        _personalFmIndex.value = 0
                        withContext(Dispatchers.Main) {
                            onNextPlay(first, merged)
                        }
                    }
                }
            }
        }
        return PersonalFmDislikeUndoToken(
            dislikedSong = song,
            isArtistDislike = true,
            targetArtistName = primaryArtist,
            targetArtistKey = artistKey,
            previousQueue = prevList,
            previousIndex = prevIndex
        )
    }

    /**
     * 撤销刚才在猜你喜欢中触发的「不听这首 / 少推该歌手」操作
     */
    fun undoPersonalFmDislike(
        token: PersonalFmDislikeUndoToken,
        onRestored: (Song, List<Song>) -> Unit = { _, _ -> }
    ) {
        val uid = accountState.value.currentId
        val song = token.dislikedSong
        if (token.isArtistDislike) {
            LocalBlacklistFilter.removeArtistEntry(
                BlacklistEntry(
                    key = token.targetArtistKey,
                    name = token.targetArtistName,
                    label = "singer"
                )
            )
        } else {
            val songKey = song.hash.ifBlank { song.mixSongId }.trim().lowercase()
            LocalBlacklistFilter.removeSongEntry(
                BlacklistEntry(
                    key = songKey,
                    name = "${song.artist} - ${song.title}",
                    label = "song",
                    mixSongId = song.mixSongId
                )
            )
        }
        val restoredList = token.previousQueue.ifEmpty {
            (listOf(song) + _personalFmSongs.value).distinctBy { (it.hash.ifBlank { it.id }).lowercase() }
        }
        val restoredIndex = token.previousIndex.coerceIn(0, (restoredList.size - 1).coerceAtLeast(0))
        _personalFmSongs.value = restoredList
        _personalFmIndex.value = restoredIndex
        VipManager.discoverCache.savePersonalFm(uid, restoredList)
        onRestored(song, restoredList)

        if (accountState.value.auth == AuthState.READY) {
            viewModelScope.launch(Dispatchers.IO) {
                if (token.isArtistDislike && token.targetArtistKey.isNotBlank()) {
                    suspendRunCatching {
                        VipManager.music.editContentBlacklist(
                            label = "singer",
                            key = token.targetArtistKey,
                            name = token.targetArtistName.ifBlank { token.targetArtistKey },
                            isDelete = true,
                            moduleId = 474
                        )
                    }
                } else {
                    val songKey = song.hash.ifBlank { song.mixSongId }.trim().lowercase()
                    if (songKey.isNotBlank()) {
                        suspendRunCatching {
                            VipManager.music.editContentBlacklist(
                                label = "song",
                                key = songKey,
                                name = "${song.artist} - ${song.title}",
                                mixSongId = song.mixSongId,
                                isDelete = true,
                                moduleId = 474
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 切换猜你喜欢推荐模式（0=默认平衡 / 1=偏好熟悉 / 2=更多尝鲜）：
     * 同步更新酷狗云端用户偏好与私人 FM 曲池，并立即按新算法拉取整批新推荐歌曲。
     */
    fun switchPersonalFmPool(
        poolId: Int,
        onLoaded: (List<Song>) -> Unit = {}
    ) {
        val coerced = poolId.coerceIn(0, 2)
        if (_fmSongPoolId.value == coerced && _personalFmSongs.value.isNotEmpty() && !_personalFmRefreshing.value) {
            onLoaded(_personalFmSongs.value)
            return
        }
        val uid = accountState.value.currentId
        val isLoggedIn = accountState.value.auth == AuthState.READY
        _fmSongPoolId.value = coerced
        fmPoolJob?.cancel()
        fmPoolJob = viewModelScope.launch(Dispatchers.IO) {
            _personalFmRefreshing.value = true
            try {
                if (isLoggedIn) {
                    launch {
                        suspendRunCatching { VipManager.music.updateUserListeningPreference(mode = coerced) }
                        suspendRunCatching {
                            VipManager.music.sendPersonalFmFeedback(
                                action = "change_song_pool",
                                song = null,
                                songPoolId = coerced
                            )
                        }
                    }
                }
                val raw = suspendRunCatching {
                    VipManager.music.personalFm(size = 20, poolId = coerced)
                }.getOrDefault(emptyList())
                val filtered = LocalBlacklistFilter.filterSongs(raw).ifEmpty { raw }
                if (accountState.value.currentId == uid && filtered.isNotEmpty()) {
                    _personalFmSongs.value = filtered
                    _personalFmIndex.value = 0
                    VipManager.discoverCache.savePersonalFm(uid, filtered)
                }
            } finally {
                if (_fmSongPoolId.value == coerced) {
                    _personalFmRefreshing.value = false
                }
            }
            withContext(Dispatchers.Main) {
                onLoaded(_personalFmSongs.value)
            }
        }
    }
}
