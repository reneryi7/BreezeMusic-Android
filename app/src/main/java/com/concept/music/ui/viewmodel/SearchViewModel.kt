package com.concept.music.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.concept.music.data.kugou.SearchSongVersionGroup
import com.concept.music.data.kugou.fetchSameSingerSongVersionsForSearch
import com.concept.music.data.kugou.groupSearchSongsWithInlineVersions
import com.concept.music.data.kugou.suspendRunCatching
import com.concept.music.data.model.AlbumItem
import com.concept.music.data.model.ArtistItem
import com.concept.music.data.model.ComplexSearchResult
import com.concept.music.data.model.PlaylistItem
import com.concept.music.data.model.SearchTab
import com.concept.music.data.model.Song
import com.concept.music.data.repository.SearchHistoryRepository
import com.concept.music.data.vip.VipManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class SearchViewModel(
    private val historyRepo: SearchHistoryRepository = VipManager.searchHistoryRepo
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _selectedTab = MutableStateFlow(SearchTab.COMPLEX)
    val selectedTab = _selectedTab.asStateFlow()

    private val _hotKeywords = MutableStateFlow<List<String>>(emptyList())
    val hotKeywords = _hotKeywords.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(historyRepo.get())
    val searchHistory = _searchHistory.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions = _suggestions.asStateFlow()

    private val _hasSearched = MutableStateFlow(false)
    val hasSearched = _hasSearched.asStateFlow()

    private val _lastSearchedKeyword = MutableStateFlow("")
    val lastSearchedKeyword = _lastSearchedKeyword.asStateFlow()

    // 搜索结果
    private val _complexResult = MutableStateFlow(ComplexSearchResult())
    val complexResult = _complexResult.asStateFlow()

    private val _songResults = MutableStateFlow<List<Song>>(emptyList())
    val songResults = _songResults.asStateFlow()

    private val _albumResults = MutableStateFlow<List<AlbumItem>>(emptyList())
    val albumResults = _albumResults.asStateFlow()

    private val _playlistResults = MutableStateFlow<List<PlaylistItem>>(emptyList())
    val playlistResults = _playlistResults.asStateFlow()

    private val _artistResults = MutableStateFlow<List<ArtistItem>>(emptyList())
    val artistResults = _artistResults.asStateFlow()

    // 搜索页专属：同歌手、同歌曲、非 Live、无翻唱（可含伴奏）的行内可展开多版本映射
    private val _complexSongGroups = MutableStateFlow<List<SearchSongVersionGroup>>(emptyList())
    val complexSongGroups = _complexSongGroups.asStateFlow()

    private val _songTabGroups = MutableStateFlow<List<SearchSongVersionGroup>>(emptyList())
    val songTabGroups = _songTabGroups.asStateFlow()

    private val _expandedVersionSongKeys = MutableStateFlow<Set<String>>(emptySet())
    val expandedVersionSongKeys = _expandedVersionSongKeys.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore = _loadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore = _hasMore.asStateFlow()

    private var currentPage = 1
    private val pageSize = 40
    private var lastComplexKeyword = ""
    private var suggestJob: Job? = null
    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var versionEnrichJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            delay(400L)
            if (_hotKeywords.value.isEmpty()) {
                runCatching { VipManager.music.searchHot() }.getOrNull()?.let {
                    if (it.isNotEmpty()) _hotKeywords.value = it
                }
            }
        }
    }

    fun songGroupKey(song: Song): String =
        song.hash.trim().ifBlank { song.id.trim() }.lowercase()

    fun toggleSongVersionsExpanded(songKey: String) {
        if (songKey.isBlank()) return
        val cur = _expandedVersionSongKeys.value
        _expandedVersionSongKeys.value = if (songKey in cur) cur - songKey else cur + songKey
    }

    private fun updateGroupedSongsAndEnrichVersions(
        rawSongs: List<Song>,
        targetTab: SearchTab,
        expectedKeyword: String
    ) {
        val grouped = VipManager.music.groupSearchSongsWithInlineVersions(rawSongs).map { it.toGroup() }
        when (targetTab) {
            SearchTab.COMPLEX -> _complexSongGroups.value = grouped
            else -> _songTabGroups.value = grouped
        }

        // 后台轻量探测前 6 首命中曲目的同歌手其他录音室版本/官方伴奏版（严格排除 Live 与他人翻唱）
        versionEnrichJob?.cancel()
        if (grouped.isEmpty()) return
        versionEnrichJob = viewModelScope.launch(Dispatchers.IO) {
            val candidates = grouped.take(6)
            val gate = Semaphore(3)
            val enrichedPairs = coroutineScope {
                candidates.map { grp ->
                    async {
                        gate.withPermit {
                            val key = songGroupKey(grp.primary)
                            val versions = suspendRunCatching {
                                VipManager.music.fetchSameSingerSongVersionsForSearch(
                                    song = grp.primary,
                                    existingVersions = grp.versions,
                                    pageSize = 20
                                )
                            }.getOrDefault(grp.versions)
                            key to versions
                        }
                    }
                }.awaitAll()
            }.toMap()

            if (_query.value.trim() != expectedKeyword) return@launch
            fun mergeInto(list: List<SearchSongVersionGroup>): List<SearchSongVersionGroup> =
                list.map { g ->
                    val k = songGroupKey(g.primary)
                    val extra = enrichedPairs[k]
                    if (extra != null && extra.size > g.versions.size) g.copy(versions = extra) else g
                }

            when (targetTab) {
                SearchTab.COMPLEX -> {
                    _complexSongGroups.value = mergeInto(_complexSongGroups.value)
                    if (_songTabGroups.value.isNotEmpty()) {
                        _songTabGroups.value = mergeInto(_songTabGroups.value)
                    }
                }
                else -> _songTabGroups.value = mergeInto(_songTabGroups.value)
            }
        }
    }

    fun loadHotKeywords() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val hot = VipManager.music.searchHot()
                if (hot.isNotEmpty()) {
                    _hotKeywords.value = hot
                }
            } catch (_: Exception) {
            }
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
        suggestJob?.cancel()
        if (newQuery.isBlank()) {
            _suggestions.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch(Dispatchers.IO) {
            delay(180)
            try {
                _suggestions.value = VipManager.music.searchSuggest(newQuery)
            } catch (_: Exception) {
                _suggestions.value = emptyList()
            }
        }
    }

    private val tabCurrentPages = mutableMapOf<SearchTab, Int>()
    private val tabHasMore = mutableMapOf<SearchTab, Boolean>()

    private fun songDedupKey(song: Song): String =
        song.hash.trim().ifBlank { song.id.trim() }.lowercase()

    fun selectTab(tab: SearchTab) {
        if (_selectedTab.value == tab) return
        _selectedTab.value = tab
        val currentKw = _query.value.trim()
        if (currentKw.isNotBlank()) {
            val hasWarmCache = lastComplexKeyword == currentKw && when (tab) {
                SearchTab.COMPLEX -> _complexResult.value.songs.isNotEmpty() ||
                    _complexResult.value.artists.isNotEmpty() ||
                    _complexResult.value.albums.isNotEmpty() ||
                    _complexResult.value.playlists.isNotEmpty()
                SearchTab.SONG -> _songResults.value.isNotEmpty()
                SearchTab.ARTIST -> _artistResults.value.isNotEmpty()
                SearchTab.ALBUM -> _albumResults.value.isNotEmpty()
                SearchTab.PLAYLIST -> _playlistResults.value.isNotEmpty()
            }
            if (hasWarmCache) {
                currentPage = tabCurrentPages[tab] ?: 1
                _hasMore.value = tabHasMore[tab] ?: (tab != SearchTab.COMPLEX)
                when (tab) {
                    SearchTab.SONG -> if (_songResults.value.size < pageSize && currentPage == 1) performSearchForCurrentTab(currentKw, silent = true)
                    SearchTab.ARTIST -> if (_artistResults.value.size < 10 && currentPage == 1) performSearchForCurrentTab(currentKw, silent = true)
                    SearchTab.ALBUM -> if (_albumResults.value.size < 12 && currentPage == 1) performSearchForCurrentTab(currentKw, silent = true)
                    SearchTab.PLAYLIST -> if (_playlistResults.value.size < 12 && currentPage == 1) performSearchForCurrentTab(currentKw, silent = true)
                    SearchTab.COMPLEX -> {}
                }
            } else {
                performSearchForCurrentTab(currentKw, silent = false)
            }
        }
    }

    fun resetTabToDefault() {
        _selectedTab.value = SearchTab.COMPLEX
        if (_hotKeywords.value.isEmpty()) {
            loadHotKeywords()
        }
    }

    fun search(targetQuery: String = _query.value) {
        val trimmed = targetQuery.trim()
        if (trimmed.isBlank()) return
        suggestJob?.cancel()
        versionEnrichJob?.cancel()
        _suggestions.value = emptyList()
        _query.value = trimmed
        _lastSearchedKeyword.value = trimmed
        _selectedTab.value = SearchTab.COMPLEX
        tabCurrentPages.clear()
        tabHasMore.clear()
        _expandedVersionSongKeys.value = emptySet()
        _hasSearched.value = true

        val currentHistory = _searchHistory.value.toMutableList()
        currentHistory.remove(trimmed)
        currentHistory.add(0, trimmed)
        val updated = currentHistory.toList()
        _searchHistory.value = updated
        historyRepo.save(updated)

        performSearchForCurrentTab(trimmed, silent = false)
    }

    private fun mergeEnrichedArtists(existing: List<ArtistItem>, incoming: List<ArtistItem>): List<ArtistItem> {
        if (existing.isEmpty()) return incoming
        if (incoming.isEmpty()) return existing
        val existingById = existing.associateBy { it.id }
        val merged = incoming.map { item ->
            val prev = existingById[item.id]
            if (prev != null) {
                item.copy(
                    avatarUrl = item.avatarUrl.ifBlank { prev.avatarUrl },
                    songCount = if (item.songCount > 0) item.songCount else prev.songCount,
                    albumCount = if (item.albumCount > 0) item.albumCount else prev.albumCount,
                    intro = item.intro.ifBlank { prev.intro }
                )
            } else item
        }.toMutableList()
        val incomingIds = incoming.map { it.id }.toHashSet()
        existing.forEach { prev ->
            if (prev.id.isNotBlank() && prev.id !in incomingIds) {
                merged.add(0, prev)
            }
        }
        return merged
    }

    private fun performSearchForCurrentTab(keyword: String, silent: Boolean = false) {
        searchJob?.cancel()
        loadMoreJob?.cancel()
        currentPage = 1

        searchJob = viewModelScope.launch {
            if (!silent) _loading.value = true
            try {
                if (com.concept.music.data.share.KugouShareLinkParser.containsKugouShareSignal(keyword)) {
                    val sharedTarget = runCatching { VipManager.music.resolveSharedMusicText(keyword) }.getOrNull()
                    if (sharedTarget != null) {
                        when (sharedTarget) {
                            is com.concept.music.data.share.ResolvedSharedMusicTarget.SharedSong -> {
                                val songs = listOf(sharedTarget.song)
                                lastComplexKeyword = keyword
                                _complexResult.value = ComplexSearchResult(songs = songs)
                                _songResults.value = songs
                                _complexSongGroups.value = songs.map { SearchSongVersionGroup(it) }
                                _songTabGroups.value = songs.map { SearchSongVersionGroup(it) }
                                _albumResults.value = emptyList()
                                _playlistResults.value = emptyList()
                                _artistResults.value = emptyList()
                                _hasMore.value = false
                                return@launch
                            }
                            is com.concept.music.data.share.ResolvedSharedMusicTarget.SharedPlaylist -> {
                                val pl = sharedTarget.playlist
                                val plItem = PlaylistItem(
                                    id = sharedTarget.playlistId,
                                    name = pl?.title ?: "酷狗分享歌单",
                                    coverUrl = pl?.coverUrl.orEmpty(),
                                    playCount = 0L,
                                    trackCount = pl?.trackCount?.takeIf { it > 0 } ?: sharedTarget.fallbackSongs.size,
                                    creatorName = pl?.creatorName.orEmpty(),
                                    intro = pl?.description.orEmpty()
                                )
                                val plList = listOf(plItem)
                                lastComplexKeyword = keyword
                                _complexResult.value = ComplexSearchResult(
                                    songs = sharedTarget.fallbackSongs,
                                    playlists = plList
                                )
                                _songResults.value = sharedTarget.fallbackSongs
                                _complexSongGroups.value = sharedTarget.fallbackSongs.map { SearchSongVersionGroup(it) }
                                _songTabGroups.value = sharedTarget.fallbackSongs.map { SearchSongVersionGroup(it) }
                                _playlistResults.value = plList
                                _albumResults.value = emptyList()
                                _artistResults.value = emptyList()
                                _hasMore.value = false
                                return@launch
                            }
                        }
                    }
                }
                when (_selectedTab.value) {
                    SearchTab.COMPLEX -> {
                        val res = VipManager.music.searchComplex(keyword)
                        lastComplexKeyword = keyword
                        _complexResult.value = res
                        _songResults.value = res.songs
                        _songTabGroups.value = VipManager.music.groupSearchSongsWithInlineVersions(res.songs).map { it.toGroup() }
                        updateGroupedSongsAndEnrichVersions(res.songs, SearchTab.COMPLEX, keyword)
                        _albumResults.value = res.albums
                        _playlistResults.value = res.playlists
                        _artistResults.value = res.artists
                        _hasMore.value = false
                    }
                    SearchTab.SONG -> {
                        val res = VipManager.music.searchSongs(keyword, page = 1, pageSize = pageSize)
                        _songResults.value = res.songs
                        updateGroupedSongsAndEnrichVersions(res.songs, SearchTab.SONG, keyword)
                        _hasMore.value = res.hasMore || res.songs.size >= pageSize
                    }
                    SearchTab.ARTIST -> {
                        val res = VipManager.music.searchArtists(keyword, page = 1, pageSize = pageSize, allowSongFallback = true)
                        val seedArtists = buildList {
                            addAll(_complexResult.value.artists)
                            _complexResult.value.primaryArtist?.let { add(it) }
                            addAll(_artistResults.value)
                        }.distinctBy { it.id }
                        _artistResults.value = mergeEnrichedArtists(seedArtists, res)
                        _hasMore.value = res.size >= pageSize
                    }
                    SearchTab.ALBUM -> {
                        val res = VipManager.music.searchAlbums(keyword, page = 1, pageSize = pageSize)
                        _albumResults.value = res
                        _hasMore.value = res.size >= pageSize
                    }
                    SearchTab.PLAYLIST -> {
                        val res = VipManager.music.searchPlaylists(keyword, page = 1, pageSize = pageSize)
                        _playlistResults.value = res
                        _hasMore.value = res.size >= pageSize
                    }
                }
            } catch (_: Exception) {
            } finally {
                _loading.value = false
            }
        }
    }

    fun loadMore() {
        val currentQuery = _query.value.trim()
        if (currentQuery.isBlank() || _loading.value || _loadingMore.value || !_hasMore.value) return
        if (_selectedTab.value == SearchTab.COMPLEX) return

        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            _loadingMore.value = true
            val nextPage = currentPage + 1
            try {
                when (_selectedTab.value) {
                    SearchTab.SONG -> {
                        val res = VipManager.music.searchSongs(currentQuery, page = nextPage, pageSize = pageSize)
                        if (res.songs.isNotEmpty()) {
                            val existingKeys = _songResults.value.map(::songDedupKey).toSet()
                            val newItems = res.songs.filter { songDedupKey(it) !in existingKeys }
                            currentPage = nextPage
                            tabCurrentPages[SearchTab.SONG] = nextPage
                            val merged = _songResults.value + newItems
                            _songResults.value = merged
                            val prevVersionsByKey = _songTabGroups.value.associate { songGroupKey(it.primary) to it.versions }
                            _songTabGroups.value = VipManager.music.groupSearchSongsWithInlineVersions(merged).map { bucket ->
                                val g = bucket.toGroup()
                                val prevV = prevVersionsByKey[songGroupKey(g.primary)].orEmpty()
                                if (prevV.size > g.versions.size) g.copy(versions = prevV) else g
                            }
                            val more = newItems.isNotEmpty() && (res.hasMore || res.songs.size >= pageSize)
                            tabHasMore[SearchTab.SONG] = more
                            _hasMore.value = more
                        } else {
                            tabHasMore[SearchTab.SONG] = false
                            _hasMore.value = false
                        }
                    }
                    SearchTab.ARTIST -> {
                        val res = VipManager.music.searchArtists(currentQuery, page = nextPage, pageSize = pageSize)
                        if (res.isNotEmpty()) {
                            val existingIds = _artistResults.value.map { it.id }.toSet()
                            val newItems = res.filter { it.id !in existingIds }
                            currentPage = nextPage
                            tabCurrentPages[SearchTab.ARTIST] = nextPage
                            _artistResults.value = _artistResults.value + newItems
                            val more = newItems.isNotEmpty() && res.size >= pageSize
                            tabHasMore[SearchTab.ARTIST] = more
                            _hasMore.value = more
                        } else {
                            tabHasMore[SearchTab.ARTIST] = false
                            _hasMore.value = false
                        }
                    }
                    SearchTab.ALBUM -> {
                        val res = VipManager.music.searchAlbums(currentQuery, page = nextPage, pageSize = pageSize)
                        if (res.isNotEmpty()) {
                            val existingIds = _albumResults.value.map { it.id }.toSet()
                            val newItems = res.filter { it.id !in existingIds }
                            currentPage = nextPage
                            tabCurrentPages[SearchTab.ALBUM] = nextPage
                            _albumResults.value = _albumResults.value + newItems
                            val more = newItems.isNotEmpty() && res.size >= pageSize
                            tabHasMore[SearchTab.ALBUM] = more
                            _hasMore.value = more
                        } else {
                            tabHasMore[SearchTab.ALBUM] = false
                            _hasMore.value = false
                        }
                    }
                    SearchTab.PLAYLIST -> {
                        val res = VipManager.music.searchPlaylists(currentQuery, page = nextPage, pageSize = pageSize)
                        if (res.isNotEmpty()) {
                            val existingIds = _playlistResults.value.map { it.id }.toSet()
                            val newItems = res.filter { it.id !in existingIds }
                            currentPage = nextPage
                            tabCurrentPages[SearchTab.PLAYLIST] = nextPage
                            _playlistResults.value = _playlistResults.value + newItems
                            val more = newItems.isNotEmpty() && res.size >= pageSize
                            tabHasMore[SearchTab.PLAYLIST] = more
                            _hasMore.value = more
                        } else {
                            tabHasMore[SearchTab.PLAYLIST] = false
                            _hasMore.value = false
                        }
                    }
                    SearchTab.COMPLEX -> {
                        _hasMore.value = false
                    }
                }
            } catch (_: Exception) {
            } finally {
                _loadingMore.value = false
            }
        }
    }

    fun dismissSuggestions() {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
    }

    fun clearQuery() {
        suggestJob?.cancel()
        searchJob?.cancel()
        loadMoreJob?.cancel()
        versionEnrichJob?.cancel()
        _query.value = ""
        _lastSearchedKeyword.value = ""
        lastComplexKeyword = ""
        _suggestions.value = emptyList()
        _hasSearched.value = false
        _hasMore.value = false
        currentPage = 1
        _selectedTab.value = SearchTab.COMPLEX
        _complexResult.value = ComplexSearchResult()
        _songResults.value = emptyList()
        _complexSongGroups.value = emptyList()
        _songTabGroups.value = emptyList()
        _expandedVersionSongKeys.value = emptySet()
        _albumResults.value = emptyList()
        _playlistResults.value = emptyList()
        _artistResults.value = emptyList()
    }

    fun removeHistoryItem(keyword: String) {
        val current = _searchHistory.value.toMutableList()
        current.remove(keyword)
        _searchHistory.value = current
        historyRepo.save(current)
    }

    fun clearHistory() {
        _searchHistory.value = emptyList()
        historyRepo.clear()
    }
}
