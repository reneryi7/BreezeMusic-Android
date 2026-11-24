package com.concept.music.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import com.concept.music.data.kugou.SearchSongVersionGroup
import com.concept.music.data.model.*
import androidx.compose.ui.graphics.FilterQuality
import com.concept.music.ui.components.BadgeTag
import com.concept.music.ui.components.FloatingScrollToTopButton
import com.concept.music.ui.components.rememberOptimizedAsyncImageModel
import com.concept.music.ui.components.rememberProgressiveSongList
import com.concept.music.ui.components.smoothFastScrollToItem
import com.concept.music.ui.components.SongItemRow
import com.concept.music.ui.theme.ConceptAmberAccent
import com.concept.music.ui.theme.ConceptCyanAccent
import com.concept.music.ui.theme.ConceptMintAccent
import com.concept.music.ui.theme.ConceptPrimary
import com.concept.music.ui.theme.ConceptStageBackground
import com.concept.music.ui.theme.ConceptVioletAccent
import com.concept.music.ui.theme.LocalIsDarkTheme
import com.concept.music.ui.theme.conceptCyanAccentColor
import com.concept.music.ui.theme.conceptElevatedSurfaceColor
import com.concept.music.ui.theme.conceptFloatingCardColor
import com.concept.music.ui.theme.softCardClickable
import com.concept.music.ui.theme.ConceptCategoryTabItem
import com.concept.music.ui.theme.ConceptSlidingCategoryTabs
import com.concept.music.ui.theme.conceptCategoryContentTransition
import com.concept.music.ui.theme.horizontalCategorySwipe
import com.concept.music.ui.theme.softClickable
import com.concept.music.ui.viewmodel.SearchViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit = {},
    onSongClick: (Song, List<Song>) -> Unit,
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    currentSong: Song? = null,
    onSongMoreClick: (Song) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val adaptiveInfo = com.concept.music.ui.theme.LocalConceptAdaptiveInfo.current
    val cardColor = conceptFloatingCardColor()

    val query by viewModel.query.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()
    val hotKeywords by viewModel.hotKeywords.collectAsState()
    LaunchedEffect(Unit) {
        if (hotKeywords.isEmpty()) {
            viewModel.loadHotKeywords()
        }
    }
    val suggestions by viewModel.suggestions.collectAsState()
    val hasSearched by viewModel.hasSearched.collectAsState()
    val lastSearchedKeyword by viewModel.lastSearchedKeyword.collectAsState()

    val complexResult by viewModel.complexResult.collectAsState()
    val songResults by viewModel.songResults.collectAsState()
    val artistResults by viewModel.artistResults.collectAsState()
    val albumResults by viewModel.albumResults.collectAsState()
    val playlistResults by viewModel.playlistResults.collectAsState()
    val complexSongGroups by viewModel.complexSongGroups.collectAsState()
    val songTabGroups by viewModel.songTabGroups.collectAsState()
    val expandedVersionSongKeys by viewModel.expandedVersionSongKeys.collectAsState()

    val loading by viewModel.loading.collectAsState()
    val loadingMore by viewModel.loadingMore.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()

    val keyboardController = LocalSoftwareKeyboardController.current
    val songListState = rememberLazyListState()
    val artistListState = rememberLazyListState()
    val albumListState = rememberLazyListState()
    val playlistListState = rememberLazyListState()
    val activeListState = when (selectedTab) {
        SearchTab.SONG -> songListState
        SearchTab.ARTIST -> artistListState
        SearchTab.ALBUM -> albumListState
        SearchTab.PLAYLIST -> playlistListState
        SearchTab.COMPLEX -> songListState
    }
    val currentOnBack by rememberUpdatedState(onBack)
    val currentSuggestions by rememberUpdatedState(suggestions)
    val currentHasSearched by rememberUpdatedState(hasSearched)
    val currentQuery by rememberUpdatedState(query)

    BackHandler(enabled = true) {
        if (suggestions.isNotEmpty()) {
            viewModel.dismissSuggestions()
        } else if (hasSearched || query.isNotEmpty()) {
            viewModel.clearQuery()
        } else {
            viewModel.resetTabToDefault()
            onBack()
        }
    }

    var edgeDragStartX by remember { mutableFloatStateOf(-1f) }

    val shouldLoadMore by remember(activeListState) {
        derivedStateOf {
            val totalItems = activeListState.layoutInfo.totalItemsCount
            val lastVisibleIndex = activeListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleIndex >= totalItems - 4
        }
    }

    LaunchedEffect(shouldLoadMore, hasMore, loadingMore, loading, selectedTab) {
        if (shouldLoadMore && hasMore && !loadingMore && !loading && selectedTab != SearchTab.COMPLEX) {
            viewModel.loadMore()
        }
    }

    ConceptStageBackground(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .pointerInput(Unit) {
                    val edgeTriggerMaxPx = 40.dp.toPx()
                    val swipeDistanceThresholdPx = 150f
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            edgeDragStartX = if (offset.x in 0f..edgeTriggerMaxPx) offset.x else -1f
                        },
                        onDragEnd = { edgeDragStartX = -1f },
                        onDragCancel = { edgeDragStartX = -1f },
                        onHorizontalDrag = { change, dragAmount ->
                            if (edgeDragStartX >= 0f && dragAmount > 0f) {
                                if (change.position.x - edgeDragStartX > swipeDistanceThresholdPx) {
                                    // 立即复位为 -1f，杜绝下一帧二次命中导致直接退出整个搜索页
                                    edgeDragStartX = -1f
                                    if (currentSuggestions.isNotEmpty()) {
                                        viewModel.dismissSuggestions()
                                    } else if (currentHasSearched || currentQuery.isNotEmpty()) {
                                        viewModel.clearQuery()
                                    } else {
                                        viewModel.resetTabToDefault()
                                        currentOnBack()
                                    }
                                }
                            }
                        }
                    )
                }
        ) {
            // 顶部无边框搜索输入胶囊
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = {
                        Text(
                            text = "搜索歌曲、歌手、专辑、歌词或酷狗链接...",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    shape = CircleShape,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "搜索",
                            tint = ConceptPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = viewModel::clearQuery) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "清空",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ConceptPrimary,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = cardColor,
                        unfocusedContainerColor = cardColor,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            keyboardController?.hide()
                            viewModel.search()
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                )
            }

            // 开放式极简分类导航栏（综合 / 单曲 / 歌手 / 专辑 / 歌单）
            if (hasSearched && query.isNotEmpty()) {
                val searchTabItems = remember {
                    SearchTab.entries.map { tab ->
                        val tabAccent = when (tab) {
                            SearchTab.COMPLEX -> ConceptPrimary
                            SearchTab.SONG -> ConceptMintAccent
                            SearchTab.ARTIST -> ConceptPrimary
                            SearchTab.ALBUM -> ConceptAmberAccent
                            SearchTab.PLAYLIST -> ConceptVioletAccent
                        }
                        ConceptCategoryTabItem(
                            key = tab.name,
                            label = tab.label,
                            accentColor = tabAccent
                        )
                    }
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    ConceptSlidingCategoryTabs(
                        tabs = searchTabItems,
                        selectedKey = selectedTab.name,
                        onSelectTab = { name ->
                            runCatching { SearchTab.valueOf(name) }.getOrNull()?.let { viewModel.selectTab(it) }
                        },
                        itemSpacing = 18.dp,
                        fontSize = 14.5.sp,
                        indicatorWidth = 20.dp,
                        scrollable = true,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    // 1. 输入联想实时浮层
                    suggestions.isNotEmpty() && !hasSearched -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            items(items = suggestions, key = { it }, contentType = { "search_suggestion" }) { text ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .softClickable {
                                            keyboardController?.hide()
                                            viewModel.search(text)
                                        }
                                        .padding(horizontal = 20.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null,
                                        tint = ConceptPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Text(
                                        text = text,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                )
                            }
                        }
                    }

                    // 2. 搜索中状态
                    loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = ConceptPrimary,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(34.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "正在检索曲库...",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }

                    // 3. 搜索结果展示（带平滑分类切换过渡动画）
                    hasSearched && query.isNotEmpty() -> {
                        val displayKw = lastSearchedKeyword.ifBlank { query }
                        AnimatedContent(
                            targetState = selectedTab,
                            transitionSpec = {
                                val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                                conceptCategoryContentTransition(direction)
                            },
                            label = "searchTabContentTransition",
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalCategorySwipe(
                                    currentIndex = selectedTab.ordinal,
                                    itemCount = SearchTab.entries.size,
                                    onSelectIndex = { nextIdx ->
                                        SearchTab.entries.getOrNull(nextIdx)?.let { viewModel.selectTab(it) }
                                    },
                                    onSwipeRightAtStart = {
                                        viewModel.clearQuery()
                                    }
                                )
                        ) { activeTab ->
                            when (activeTab) {
                                SearchTab.COMPLEX -> {
                                    val isComplexEmpty = complexResult.songs.isEmpty() &&
                                        complexResult.artists.isEmpty() &&
                                        complexResult.albums.isEmpty() &&
                                        complexResult.playlists.isEmpty()
                                    if (isComplexEmpty) {
                                        EmptySearchState(message = "未找到“$displayKw”的相关结果")
                                    } else {
                                        ComplexSearchResultView(
                                            result = complexResult,
                                            songGroups = complexSongGroups,
                                            expandedVersionKeys = expandedVersionSongKeys,
                                            onToggleVersionsExpand = viewModel::toggleSongVersionsExpanded,
                                            keyword = displayKw,
                                            currentSong = currentSong,
                                            onSongClick = onSongClick,
                                            onSongMoreClick = onSongMoreClick,
                                            onAlbumClick = onAlbumClick,
                                            onPlaylistClick = onPlaylistClick,
                                            onArtistClick = onArtistClick,
                                            onSelectTab = { viewModel.selectTab(it) }
                                        )
                                    }
                                }
                                SearchTab.SONG -> {
                                    val effectiveGroups = remember(songTabGroups, songResults) {
                                        songTabGroups.ifEmpty { songResults.map { SearchSongVersionGroup(primary = it, versions = emptyList()) } }
                                    }
                                    if (effectiveGroups.isEmpty()) {
                                        EmptySearchState(message = "未找到“$displayKw”的相关单曲")
                                    } else {
                                        val searchScope = rememberCoroutineScope()
                                        val latestSongResults by rememberUpdatedState(songResults)
                                        val showSongSearchScrollTop by remember(effectiveGroups.size) {
                                            derivedStateOf {
                                                songListState.firstVisibleItemIndex > 2 ||
                                                    (songListState.firstVisibleItemIndex == 2 && songListState.firstVisibleItemScrollOffset > 100)
                                            }
                                        }
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            LazyColumn(
                                                state = songListState,
                                                modifier = Modifier.fillMaxSize(),
                                                contentPadding = PaddingValues(bottom = 112.dp)
                                            ) {
                                                if (adaptiveInfo.useTwoColumnSongOrCardGrid) {
                                                    val groupPairs = effectiveGroups.mapIndexed { idx, g -> idx to g }.chunked(2)
                                                    itemsIndexed(
                                                        items = groupPairs,
                                                        key = { pIdx, pair -> "search_song_pair_${pair.first().second.primary.id}_$pIdx" },
                                                        contentType = { _, _ -> "song_pair_row" }
                                                    ) { _, pair ->
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 12.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                        ) {
                                                            pair.forEach { (index, group) ->
                                                                val gKey = viewModel.songGroupKey(group.primary)
                                                                Box(modifier = Modifier.weight(1f)) {
                                                                    SearchExpandableSongGroupRow(
                                                                        group = group,
                                                                        orderIndex = index + 1,
                                                                        isExpanded = gKey in expandedVersionSongKeys,
                                                                        onToggleExpand = { viewModel.toggleSongVersionsExpanded(gKey) },
                                                                        currentSong = currentSong,
                                                                        allContextSongs = latestSongResults,
                                                                        highlightKeyword = displayKw,
                                                                        onSongClick = onSongClick,
                                                                        onSongMoreClick = onSongMoreClick
                                                                    )
                                                                }
                                                            }
                                                            if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                                                        }
                                                    }
                                                } else {
                                                    itemsIndexed(
                                                        items = effectiveGroups,
                                                        key = { idx, g -> "search_song_${g.primary.id}_${g.primary.hash}_$idx" },
                                                        contentType = { _, _ -> "song_group_row" }
                                                    ) { index, group ->
                                                        val gKey = viewModel.songGroupKey(group.primary)
                                                        SearchExpandableSongGroupRow(
                                                            group = group,
                                                            orderIndex = index + 1,
                                                            isExpanded = gKey in expandedVersionSongKeys,
                                                            onToggleExpand = { viewModel.toggleSongVersionsExpanded(gKey) },
                                                            currentSong = currentSong,
                                                            allContextSongs = latestSongResults,
                                                            highlightKeyword = displayKw,
                                                            onSongClick = onSongClick,
                                                            onSongMoreClick = onSongMoreClick
                                                        )
                                                    }
                                                }
                                                if (loadingMore) {
                                                    item {
                                                        Box(
                                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            CircularProgressIndicator(color = ConceptPrimary, modifier = Modifier.size(24.dp))
                                                        }
                                                    }
                                                }
                                            }
                                            FloatingScrollToTopButton(
                                                visible = showSongSearchScrollTop,
                                                onClick = {
                                                    searchScope.launch {
                                                        songListState.smoothFastScrollToItem(0)
                                                    }
                                                },
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .navigationBarsPadding()
                                                    .padding(end = 18.dp, bottom = 104.dp)
                                            )
                                        }
                                    }
                                }
                                SearchTab.ARTIST -> {
                                    if (artistResults.isEmpty()) {
                                        EmptySearchState(message = "未找到“$displayKw”的相关歌手")
                                    } else {
                                        LazyColumn(
                                            state = artistListState,
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                        ) {
                                            if (adaptiveInfo.useTwoColumnSongOrCardGrid) {
                                                val pairs = artistResults.chunked(2)
                                                itemsIndexed(
                                                    items = pairs,
                                                    key = { idx, pair -> "search_artist_pair_${pair.first().id}_$idx" },
                                                    contentType = { _, _ -> "artist_pair" }
                                                ) { _, pair ->
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                                    ) {
                                                        pair.forEach { artist ->
                                                            Box(modifier = Modifier.weight(1f)) {
                                                                ArtistSearchCard(artist = artist, onClick = { onArtistClick(artist.id) })
                                                            }
                                                        }
                                                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                                                    }
                                                }
                                            } else {
                                                itemsIndexed(
                                                    items = artistResults,
                                                    key = { idx, artist -> "search_artist_${artist.id}_$idx" },
                                                    contentType = { _, _ -> "artist_card" }
                                                ) { _, artist ->
                                                    ArtistSearchCard(artist = artist, onClick = { onArtistClick(artist.id) })
                                                }
                                            }
                                            if (loadingMore) {
                                                item {
                                                    Box(
                                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        CircularProgressIndicator(color = ConceptPrimary, modifier = Modifier.size(24.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                SearchTab.ALBUM -> {
                                    if (albumResults.isEmpty()) {
                                        EmptySearchState(message = "未找到“$displayKw”的相关专辑")
                                    } else {
                                        LazyColumn(
                                            state = albumListState,
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                        ) {
                                            if (adaptiveInfo.useTwoColumnSongOrCardGrid) {
                                                val pairs = albumResults.chunked(2)
                                                itemsIndexed(
                                                    items = pairs,
                                                    key = { idx, pair -> "search_album_pair_${pair.first().id}_$idx" },
                                                    contentType = { _, _ -> "album_pair" }
                                                ) { _, pair ->
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                                    ) {
                                                        pair.forEach { album ->
                                                            Box(modifier = Modifier.weight(1f)) {
                                                                AlbumCardItem(album = album, onClick = { onAlbumClick(album.id) })
                                                            }
                                                        }
                                                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                                                    }
                                                }
                                            } else {
                                                itemsIndexed(
                                                    items = albumResults,
                                                    key = { idx, album -> "search_album_${album.id}_$idx" },
                                                    contentType = { _, _ -> "album_card" }
                                                ) { _, album ->
                                                    AlbumCardItem(album = album, onClick = { onAlbumClick(album.id) })
                                                }
                                            }
                                            if (loadingMore) {
                                                item {
                                                    Box(
                                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        CircularProgressIndicator(color = ConceptAmberAccent, modifier = Modifier.size(24.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                SearchTab.PLAYLIST -> {
                                    if (playlistResults.isEmpty()) {
                                        EmptySearchState(message = "未找到“$displayKw”的相关歌单")
                                    } else {
                                        LazyColumn(
                                            state = playlistListState,
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                        ) {
                                            if (adaptiveInfo.useTwoColumnSongOrCardGrid) {
                                                val pairs = playlistResults.chunked(2)
                                                itemsIndexed(
                                                    items = pairs,
                                                    key = { idx, pair -> "search_pl_pair_${pair.first().id}_$idx" },
                                                    contentType = { _, _ -> "playlist_pair" }
                                                ) { _, pair ->
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                                    ) {
                                                        pair.forEach { playlist ->
                                                            Box(modifier = Modifier.weight(1f)) {
                                                                PlaylistSearchCard(playlist = playlist, onClick = { onPlaylistClick(playlist.id) })
                                                            }
                                                        }
                                                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                                                    }
                                                }
                                            } else {
                                                itemsIndexed(
                                                    items = playlistResults,
                                                    key = { idx, playlist -> "search_pl_${playlist.id}_$idx" },
                                                    contentType = { _, _ -> "playlist_card" }
                                                ) { _, playlist ->
                                                    PlaylistSearchCard(playlist = playlist, onClick = { onPlaylistClick(playlist.id) })
                                                }
                                            }
                                            if (loadingMore) {
                                                item {
                                                    Box(
                                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        CircularProgressIndicator(color = ConceptVioletAccent, modifier = Modifier.size(24.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. 搜索历史与热搜引导页
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            if (searchHistory.isNotEmpty()) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "搜索历史",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                        IconButton(onClick = viewModel::clearHistory) {
                                            Icon(
                                                imageVector = Icons.Rounded.DeleteOutline,
                                                contentDescription = "清空历史",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 10.dp)
                                    ) {
                                        searchHistory.forEach { hist ->
                                            Surface(
                                                color = cardColor,
                                                shape = RoundedCornerShape(16.dp),
                                                shadowElevation = 0.dp,
                                                modifier = Modifier.clip(RoundedCornerShape(16.dp))
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
                                                ) {
                                                    Text(
                                                        text = hist,
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        ),
                                                        modifier = Modifier
                                                            .softClickable {
                                                                keyboardController?.hide()
                                                                viewModel.search(hist)
                                                            }
                                                            .padding(vertical = 5.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(2.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .size(26.dp)
                                                            .softClickable { viewModel.removeHistoryItem(hist) },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.Close,
                                                            contentDescription = "删除",
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(13.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (hotKeywords.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "热门搜索",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 10.dp)
                                    ) {
                                        hotKeywords.take(15).forEachIndexed { idx, kw ->
                                            Surface(
                                                color = cardColor,
                                                shape = RoundedCornerShape(16.dp),
                                                shadowElevation = 0.dp,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .softClickable {
                                                        keyboardController?.hide()
                                                        viewModel.search(kw)
                                                    }
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                                ) {
                                                    if (idx < 3) {
                                                        Text(
                                                            text = "${idx + 1}",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                color = ConceptPrimary,
                                                                fontWeight = FontWeight.ExtraBold
                                                            ),
                                                            modifier = Modifier.padding(end = 5.dp)
                                                        )
                                                    }
                                                    Text(
                                                        text = kw,
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 智能意图驱动的综合搜索结果页：
 * - 搜索歌手（如“邓紫棋”、“G.E.M.邓紫棋”、“鹿晗”）：置顶展示歌手名片，依次展示精选单曲、热门专辑与精选歌单
 * - 搜索专辑（如“范特西”、“摩天动物园”）：置顶展示匹配专辑，依次展示专辑收录单曲、专辑歌手与相关歌单
 * - 搜索单曲（如“勋章”、“光年之外”）：置顶展示原版单曲及 Live/翻唱版本，紧随其后展示原唱歌手、同名/关联专辑与歌单（不盲目裁剪）
 * - 搜索歌单：置顶展示匹配的精选歌单，依次展示相关精选单曲与专辑
 */
@Composable
private fun ComplexSearchResultView(
    result: ComplexSearchResult,
    songGroups: List<SearchSongVersionGroup>,
    expandedVersionKeys: Set<String>,
    onToggleVersionsExpand: (String) -> Unit,
    keyword: String,
    currentSong: Song?,
    onSongClick: (Song, List<Song>) -> Unit,
    onSongMoreClick: (Song) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onSelectTab: (SearchTab) -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val adaptiveInfo = com.concept.music.ui.theme.LocalConceptAdaptiveInfo.current
    val elevatedColor = conceptElevatedSurfaceColor()
    val effectiveComplexGroups = remember(songGroups, result.songs) {
        songGroups.ifEmpty { result.songs.map { SearchSongVersionGroup(primary = it, versions = emptyList()) } }
    }

    if (adaptiveInfo.useTwoPaneDetailLayout) {
        // 平板 / 宽屏「左右分栏分类展示」综合搜索结果看板：
        // 左栏：焦点歌手名片 + 精选单曲列表；右栏：同名/热门专辑 + 精选歌单
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(0.52f)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(top = 12.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                val leadArtist = result.primaryArtist ?: result.artists.firstOrNull()
                if (leadArtist != null && leadArtist.id.isNotBlank() && leadArtist.id != "0") {
                    item(key = "wide_complex_artist") {
                        ArtistHeroCard(
                            artist = leadArtist,
                            roleBadge = if (result.intent == SearchIntent.ARTIST) "最佳匹配歌手" else "关联歌手",
                            isDark = isDark,
                            elevatedColor = elevatedColor,
                            onClick = { onArtistClick(leadArtist.id) }
                        )
                    }
                }
                if (effectiveComplexGroups.isNotEmpty()) {
                    item(key = "wide_complex_songs") {
                        SongSectionBlock(
                            title = "单曲",
                            subtitle = null,
                            groups = effectiveComplexGroups.take(10),
                            expandedVersionKeys = expandedVersionKeys,
                            onToggleVersionsExpand = onToggleVersionsExpand,
                            allSongs = result.songs,
                            currentSong = currentSong,
                            highlightKeyword = keyword,
                            onSongClick = onSongClick,
                            onSongMoreClick = onSongMoreClick,
                            onViewAll = { onSelectTab(SearchTab.SONG) }
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(0.48f)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(top = 12.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (result.albums.isNotEmpty()) {
                    item(key = "wide_complex_albums") {
                        AlbumSectionBlock(
                            title = "同名与热门专辑",
                            albums = result.albums.take(6),
                            primaryArtistName = result.primaryArtist?.name ?: result.artists.firstOrNull()?.name.orEmpty(),
                            onAlbumClick = onAlbumClick,
                            onViewAll = { onSelectTab(SearchTab.ALBUM) }
                        )
                    }
                }
                if (result.playlists.isNotEmpty()) {
                    item(key = "wide_complex_playlists") {
                        PlaylistGalleryBlock(
                            title = "相关精选歌单",
                            playlists = result.playlists.take(6),
                            elevatedColor = elevatedColor,
                            onPlaylistClick = onPlaylistClick,
                            onViewAll = { onSelectTab(SearchTab.PLAYLIST) }
                        )
                    }
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        when (result.intent) {
            SearchIntent.SONG -> {
                // 1. 单曲意图：首屏置顶该歌曲的原版及同歌手多版本折叠行
                if (effectiveComplexGroups.isNotEmpty()) {
                    item {
                        SongSectionBlock(
                            title = "单曲",
                            subtitle = null,
                            groups = effectiveComplexGroups.take(10),
                            expandedVersionKeys = expandedVersionKeys,
                            onToggleVersionsExpand = onToggleVersionsExpand,
                            allSongs = result.songs,
                            currentSong = currentSong,
                            highlightKeyword = keyword,
                            onSongClick = onSongClick,
                            onSongMoreClick = onSongMoreClick,
                            onViewAll = { onSelectTab(SearchTab.SONG) }
                        )
                    }
                }

                // 2. 原唱歌手关联卡片
                val origArtist = result.primaryArtist
                if (origArtist != null && origArtist.id.isNotBlank() && origArtist.id != "0") {
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "原唱歌手",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                ),
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                            )
                            ArtistHeroCard(
                                artist = origArtist,
                                roleBadge = "原唱",
                                isDark = isDark,
                                elevatedColor = elevatedColor,
                                onClick = { onArtistClick(origArtist.id) }
                            )
                        }
                    }
                }

                // 3. 同名与相关专辑（解决搜主打歌/同名专辑时看不到专辑的问题）
                if (result.albums.isNotEmpty()) {
                    item {
                        AlbumSectionBlock(
                            title = "同名与相关专辑",
                            albums = result.albums.take(4),
                            primaryArtistName = result.primaryArtist?.name.orEmpty(),
                            onAlbumClick = onAlbumClick,
                            onViewAll = { onSelectTab(SearchTab.ALBUM) }
                        )
                    }
                }

                // 4. 相关精选歌单
                if (result.playlists.isNotEmpty()) {
                    item {
                        PlaylistGalleryBlock(
                            title = "相关精选歌单",
                            playlists = result.playlists.take(6),
                            elevatedColor = elevatedColor,
                            onPlaylistClick = onPlaylistClick,
                            onViewAll = { onSelectTab(SearchTab.PLAYLIST) }
                        )
                    }
                }
            }

            SearchIntent.ALBUM -> {
                // 1. 专辑意图：首屏置顶展示匹配的专辑
                if (result.albums.isNotEmpty()) {
                    item {
                        AlbumSectionBlock(
                            title = "匹配的专辑",
                            albums = result.albums.take(6),
                            primaryArtistName = result.primaryArtist?.name.orEmpty(),
                            onAlbumClick = onAlbumClick,
                            onViewAll = { onSelectTab(SearchTab.ALBUM) }
                        )
                    }
                }
                // 2. 专辑歌手名片
                val albArtist = result.primaryArtist
                if (albArtist != null && albArtist.id.isNotBlank() && albArtist.id != "0") {
                    item {
                        ArtistHeroCard(
                            artist = albArtist,
                            roleBadge = "专辑歌手",
                            isDark = isDark,
                            elevatedColor = elevatedColor,
                            onClick = { onArtistClick(albArtist.id) }
                        )
                    }
                }
                // 3. 专辑关联单曲
                if (effectiveComplexGroups.isNotEmpty()) {
                    item {
                        SongSectionBlock(
                            title = "专辑收录与相关单曲",
                            subtitle = null,
                            groups = effectiveComplexGroups.take(8),
                            expandedVersionKeys = expandedVersionKeys,
                            onToggleVersionsExpand = onToggleVersionsExpand,
                            allSongs = result.songs,
                            currentSong = currentSong,
                            onSongClick = onSongClick,
                            onSongMoreClick = onSongMoreClick,
                            onViewAll = { onSelectTab(SearchTab.SONG) }
                        )
                    }
                }
                // 4. 相关歌单
                if (result.playlists.isNotEmpty()) {
                    item {
                        PlaylistGalleryBlock(
                            title = "相关精选歌单",
                            playlists = result.playlists.take(6),
                            elevatedColor = elevatedColor,
                            onPlaylistClick = onPlaylistClick,
                            onViewAll = { onSelectTab(SearchTab.PLAYLIST) }
                        )
                    }
                }
            }

            SearchIntent.PLAYLIST -> {
                // 1. 歌单意图：首屏置顶展示匹配的精选歌单
                if (result.playlists.isNotEmpty()) {
                    item {
                        PlaylistGalleryBlock(
                            title = "匹配的精选歌单",
                            playlists = result.playlists.take(8),
                            elevatedColor = elevatedColor,
                            onPlaylistClick = onPlaylistClick,
                            onViewAll = { onSelectTab(SearchTab.PLAYLIST) }
                        )
                    }
                }
                // 2. 主题相关单曲
                if (effectiveComplexGroups.isNotEmpty()) {
                    item {
                        SongSectionBlock(
                            title = "相关精选单曲",
                            subtitle = null,
                            groups = effectiveComplexGroups.take(8),
                            expandedVersionKeys = expandedVersionKeys,
                            onToggleVersionsExpand = onToggleVersionsExpand,
                            allSongs = result.songs,
                            currentSong = currentSong,
                            onSongClick = onSongClick,
                            onSongMoreClick = onSongMoreClick,
                            onViewAll = { onSelectTab(SearchTab.SONG) }
                        )
                    }
                }
                // 3. 相关专辑
                if (result.albums.isNotEmpty()) {
                    item {
                        AlbumSectionBlock(
                            title = "相关专辑",
                            albums = result.albums.take(4),
                            primaryArtistName = result.primaryArtist?.name.orEmpty(),
                            onAlbumClick = onAlbumClick,
                            onViewAll = { onSelectTab(SearchTab.ALBUM) }
                        )
                    }
                }
            }

            SearchIntent.ARTIST -> {
                // 1. 歌手意图（如“邓紫棋”、“鹿晗”）：置顶歌手数字名片
                if (result.artists.isNotEmpty()) {
                    val artist = result.artists.first()
                    item {
                        ArtistHeroCard(
                            artist = artist,
                            roleBadge = "歌手",
                            isDark = isDark,
                            elevatedColor = elevatedColor,
                            onClick = { onArtistClick(artist.id) }
                        )
                    }
                }

                // 2. 歌手精选单曲
                if (effectiveComplexGroups.isNotEmpty()) {
                    item {
                        SongSectionBlock(
                            title = "精选单曲",
                            subtitle = null,
                            groups = effectiveComplexGroups.take(6),
                            expandedVersionKeys = expandedVersionKeys,
                            onToggleVersionsExpand = onToggleVersionsExpand,
                            allSongs = result.songs,
                            currentSong = currentSong,
                            onSongClick = onSongClick,
                            onSongMoreClick = onSongMoreClick,
                            onViewAll = { onSelectTab(SearchTab.SONG) }
                        )
                    }
                }

                // 3. 热门专辑
                if (result.albums.isNotEmpty()) {
                    item {
                        AlbumSectionBlock(
                            title = "热门专辑",
                            albums = result.albums.take(4),
                            primaryArtistName = result.artists.firstOrNull()?.name.orEmpty(),
                            onAlbumClick = onAlbumClick,
                            onViewAll = { onSelectTab(SearchTab.ALBUM) }
                        )
                    }
                }

                // 4. 精选歌单
                if (result.playlists.isNotEmpty()) {
                    item {
                        PlaylistGalleryBlock(
                            title = "精选歌单",
                            playlists = result.playlists.take(6),
                            elevatedColor = elevatedColor,
                            onPlaylistClick = onPlaylistClick,
                            onViewAll = { onSelectTab(SearchTab.PLAYLIST) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistHeroCard(
    artist: ArtistItem,
    roleBadge: String,
    isDark: Boolean,
    elevatedColor: Color,
    onClick: () -> Unit
) {
    val artistBrush = if (isDark) {
        Brush.linearGradient(listOf(Color(0xFF15253C), Color(0xFF131B2B)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFE8F3FF), Color(0xFFF5F8FF)))
    }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .softCardClickable(shape = RoundedCornerShape(22.dp), onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(artistBrush)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(elevatedColor),
                contentAlignment = Alignment.Center
            ) {
                val artistThumbModel = rememberOptimizedAsyncImageModel(rawUrl = artist.avatarUrl, sizePx = 156, targetCdnSize = "150")
                if (artistThumbModel != null) {
                    AsyncImage(
                        model = artistThumbModel,
                        contentDescription = artist.name,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Low,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = artist.name,
                        tint = ConceptPrimary,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 17.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = ConceptPrimary.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = roleBadge,
                            color = ConceptPrimary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = buildString {
                        if (artist.songCount > 0) append("单曲 ${artist.songCount}")
                        if (artist.albumCount > 0) {
                            if (isNotEmpty()) append("  ·  ")
                            append("专辑 ${artist.albumCount}")
                        }
                        if (isEmpty()) append("查看代表作与专辑")
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }

            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = "进入歌手主页",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * 歌手分类 Tab 列表项
 */
@Composable
private fun ArtistSearchCard(artist: ArtistItem, onClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .softClickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(conceptElevatedSurfaceColor()),
                contentAlignment = Alignment.Center
            ) {
                val artistThumbModel = rememberOptimizedAsyncImageModel(rawUrl = artist.avatarUrl, sizePx = 156, targetCdnSize = "150")
                if (artistThumbModel != null) {
                    AsyncImage(
                        model = artistThumbModel,
                        contentDescription = artist.name,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Low,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = artist.name,
                        tint = ConceptPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                val meta = buildList {
                    if (artist.songCount > 0) add("单曲 ${artist.songCount}")
                    if (artist.albumCount > 0) add("专辑 ${artist.albumCount}")
                    if (isEmpty()) add("歌手主页")
                }.joinToString(" · ")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(ConceptPrimary)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 72.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        )
    }
}

@Composable
private fun SongSectionBlock(
    title: String,
    subtitle: String?,
    groups: List<SearchSongVersionGroup>,
    expandedVersionKeys: Set<String>,
    onToggleVersionsExpand: (String) -> Unit,
    allSongs: List<Song>,
    currentSong: Song?,
    highlightKeyword: String = "",
    onSongClick: (Song, List<Song>) -> Unit,
    onSongMoreClick: (Song) -> Unit,
    onViewAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .softClickable(onClick = onViewAll)
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = "查看全部",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        groups.forEachIndexed { index, group ->
            val gKey = group.primary.hash.trim().ifBlank { group.primary.id.trim() }.lowercase()
            SearchExpandableSongGroupRow(
                group = group,
                orderIndex = index + 1,
                isExpanded = gKey in expandedVersionKeys,
                onToggleExpand = { onToggleVersionsExpand(gKey) },
                currentSong = currentSong,
                allContextSongs = allSongs,
                highlightKeyword = highlightKeyword,
                onSongClick = onSongClick,
                onSongMoreClick = onSongMoreClick
            )
        }
    }
}

private fun inferInlineVersionBadge(song: Song): String {
    val raw = "${song.title} ${song.album}".lowercase()
    return when {
        raw.contains("伴奏") || raw.contains("inst") || raw.contains("karaoke") || raw.contains("消音") -> "伴奏"
        raw.contains("纯音乐") || raw.contains("器乐") || raw.contains("钢琴") -> "纯享"
        raw.contains("ost") || raw.contains("原声") || raw.contains("插曲") || raw.contains("片尾") || raw.contains("主题曲") -> "原声版"
        raw.contains("remaster") || raw.contains("重制") || raw.contains("修复") || raw.contains("纪念") -> "重制版"
        raw.contains("acoustic") || raw.contains("不插电") || raw.contains("木吉他") -> "不插电"
        raw.contains("粤语") -> "粤语版"
        raw.contains("国语") || raw.contains("普通话") -> "国语版"
        else -> "其他版本"
    }
}

private fun formatSearchVersionDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSec = (durationMs / 1000L).toInt()
    return String.format("%02d:%02d", totalSec / 60, totalSec % 60)
}

/**
 * 搜索结果专属行内可展开多版本组件：
 * - 仅在搜索结果中展示（同原歌手、同一首歌、无 Live、无他人翻唱，保留伴奏与官方其他录音室版）
 * - 点击按钮向下平滑展开子版本，再次点击平滑收起
 */
@Composable
private fun SearchExpandableSongGroupRow(
    group: SearchSongVersionGroup,
    orderIndex: Int,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    currentSong: Song?,
    allContextSongs: List<Song>,
    highlightKeyword: String = "",
    onSongClick: (Song, List<Song>) -> Unit,
    onSongMoreClick: (Song) -> Unit
) {
    val primary = group.primary
    val curId = currentSong?.id
    val curHash = currentSong?.hash.orEmpty()
    val isPrimaryPlaying = curId != null && (
        curId == primary.id || (curHash.isNotEmpty() && curHash.equals(primary.hash, ignoreCase = true))
        )
    val cyanAccent = conceptCyanAccentColor()
    val hasVersions = group.versions.isNotEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
        SongItemRow(
            song = primary,
            orderIndex = orderIndex,
            isPlaying = isPrimaryPlaying,
            onClick = { onSongClick(primary, allContextSongs.ifEmpty { listOf(primary) + group.versions }) },
            trailingActionContent = if (hasVersions) {
                {
                    Surface(
                        shape = CircleShape,
                        color = if (isExpanded) cyanAccent.copy(alpha = 0.20f) else ConceptPrimary.copy(alpha = 0.11f),
                        modifier = Modifier.softCardClickable(
                            shape = CircleShape,
                            onClick = onToggleExpand
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = if (isExpanded) "收起" else "${group.versions.size}个版本",
                                color = if (isExpanded) cyanAccent else ConceptPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                contentDescription = if (isExpanded) "收起更多版本" else "展开更多版本",
                                tint = if (isExpanded) cyanAccent else ConceptPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            } else null,
            onMoreClick = { onSongMoreClick(primary) }
        )

        if (primary.matchedLyricSnippet.isNotBlank()) {
            MatchedLyricQuoteStrip(
                snippet = primary.matchedLyricSnippet,
                keyword = highlightKeyword,
                accentColor = cyanAccent,
                onClick = { onSongClick(primary, allContextSongs.ifEmpty { listOf(primary) + group.versions }) }
            )
        }

        if (hasVersions) {
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(240)) + fadeIn(tween(200)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(150))
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = conceptFloatingCardColor(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 48.dp, end = 14.dp, top = 2.dp, bottom = 6.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        group.versions.forEachIndexed { vIdx, verSong ->
                            val isVerPlaying = curId != null && (
                                curId == verSong.id || (curHash.isNotEmpty() && curHash.equals(verSong.hash, ignoreCase = true))
                                )
                            val vBadge = remember(verSong.title, verSong.album) { inferInlineVersionBadge(verSong) }
                            val qBadge = remember(verSong.isHiRes, verSong.isLossless, verSong.quality) {
                                when {
                                    verSong.isHiRes -> "Hi-Res"
                                    verSong.isLossless -> "SQ"
                                    verSong.quality == "320" -> "HQ"
                                    else -> ""
                                }
                            }
                            val durStr = remember(verSong.durationMs) { formatSearchVersionDuration(verSong.durationMs) }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .softClickable {
                                        val versionPlayQueue = buildList {
                                            add(verSong)
                                            add(primary)
                                            group.versions.forEach { if (it.id != verSong.id && it.hash != verSong.hash) add(it) }
                                        }
                                        onSongClick(verSong, versionPlayQueue)
                                    }
                                    .padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(26.dp)
                                        .clip(CircleShape)
                                        .background(if (isVerPlaying) cyanAccent else ConceptPrimary.copy(alpha = 0.28f))
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        BadgeTag(text = vBadge, color = cyanAccent)
                                        if (qBadge.isNotEmpty()) {
                                            BadgeTag(text = qBadge, color = ConceptPrimary)
                                        }
                                        Text(
                                            text = verSong.title,
                                            color = if (isVerPlaying) cyanAccent else MaterialTheme.colorScheme.onSurface,
                                            fontSize = 13.5.sp,
                                            fontWeight = if (isVerPlaying) FontWeight.Bold else FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                    }
                                    val subLine = buildList {
                                        add(verSong.artist)
                                        if (verSong.album.isNotBlank() && verSong.album != "单曲") add("《${verSong.album}》")
                                        if (durStr.isNotBlank()) add(durStr)
                                    }.joinToString(" · ")
                                    Text(
                                        text = subLine,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onSongMoreClick(verSong) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreVert,
                                        contentDescription = "更多",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                            if (vIdx < group.versions.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 25.dp, end = 12.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistGalleryBlock(
    title: String,
    playlists: List<PlaylistItem>,
    elevatedColor: Color,
    onPlaylistClick: (String) -> Unit,
    onViewAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .softClickable(onClick = onViewAll)
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = "更多歌单",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(items = playlists, key = { "cpl_${it.id}" }, contentType = { "complex_playlist_card" }) { pl ->
                Column(
                    modifier = Modifier
                        .width(134.dp)
                        .softCardClickable(clip = false) { onPlaylistClick(pl.id) }
                ) {
                    Box(
                        modifier = Modifier
                            .size(134.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(elevatedColor)
                    ) {
                        AsyncImage(
                            model = pl.coverUrl,
                            contentDescription = pl.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (pl.trackCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(Color.Black.copy(alpha = 0.58f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${pl.trackCount}首",
                                    color = Color.White,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = pl.name,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumSectionBlock(
    title: String,
    albums: List<AlbumItem>,
    primaryArtistName: String,
    onAlbumClick: (String) -> Unit,
    onViewAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .softClickable(onClick = onViewAll)
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = "更多专辑",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            albums.forEach { alb ->
                val fixedAlb = if ((alb.artist.isBlank() || alb.artist == "未知歌手" || alb.artist == "群星") && primaryArtistName.isNotBlank()) {
                    alb.copy(artist = primaryArtistName)
                } else alb
                AlbumCardItem(album = fixedAlb, onClick = { onAlbumClick(alb.id) })
            }
        }
    }
}

/**
 * 专辑搜索项（无外框通透行排版 + 柔和弹性反馈）
 */
@Composable
private fun AlbumCardItem(album: AlbumItem, onClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .softClickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(conceptElevatedSurfaceColor()),
                contentAlignment = Alignment.Center
            ) {
                val albumThumbModel = rememberOptimizedAsyncImageModel(rawUrl = album.coverUrl, sizePx = 156, targetCdnSize = "150")
                AsyncImage(
                    model = albumThumbModel ?: album.coverUrl,
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                val meta = buildList {
                    if (album.artist.isNotBlank() && album.artist != "未知歌手") add(album.artist)
                    if (album.songCount > 1) add("${album.songCount} 首") else add("专辑")
                    if (album.publishDate.isNotBlank()) add(album.publishDate)
                }.joinToString(" · ")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(ConceptAmberAccent)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 74.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        )
    }
}

/**
 * 歌单搜索项（无外框通透行排版 + 柔和弹性反馈）
 */
@Composable
private fun PlaylistSearchCard(playlist: PlaylistItem, onClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .softClickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val playlistThumbModel = rememberOptimizedAsyncImageModel(rawUrl = playlist.coverUrl, sizePx = 156, targetCdnSize = "150")
            AsyncImage(
                model = playlistThumbModel ?: playlist.coverUrl,
                contentDescription = playlist.name,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(conceptElevatedSurfaceColor())
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(ConceptVioletAccent)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${playlist.creatorName} · ${playlist.trackCount} 首",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 74.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        )
    }
}

@Composable
private fun EmptySearchState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.SearchOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

/**
 * 综合/单曲搜索内命中唱词正文时的紧凑高亮引用条（仅在歌词搜歌命中真实正文时渲染）
 */
@Composable
private fun MatchedLyricQuoteStrip(
    snippet: String,
    keyword: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    if (snippet.isBlank()) return
    val highlightedSnippet = remember(snippet, keyword, accentColor) {
        buildAnnotatedString {
            val kw = keyword.trim()
            if (kw.isBlank()) {
                append(snippet)
            } else {
                val tokens = kw.split(Regex("[\\s，,。！？!?、]+")).filter { it.length >= 2 }.distinct()
                    .ifEmpty { listOf(kw) }
                val lower = snippet.lowercase()
                val matchedMask = BooleanArray(snippet.length)
                tokens.forEach { token ->
                    val lowerToken = token.lowercase()
                    var start = 0
                    while (true) {
                        val idx = lower.indexOf(lowerToken, startIndex = start)
                        if (idx < 0) break
                        for (i in idx until (idx + lowerToken.length).coerceAtMost(snippet.length)) {
                            matchedMask[i] = true
                        }
                        start = idx + lowerToken.length
                    }
                }
                var i = 0
                while (i < snippet.length) {
                    val isHit = matchedMask[i]
                    var j = i + 1
                    while (j < snippet.length && matchedMask[j] == isHit) {
                        j++
                    }
                    val seg = snippet.substring(i, j)
                    if (isHit) {
                        withStyle(SpanStyle(color = accentColor, fontWeight = FontWeight.ExtraBold)) {
                            append(seg)
                        }
                    } else {
                        append(seg)
                    }
                    i = j
                }
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = accentColor.copy(alpha = 0.08f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 52.dp, end = 16.dp, bottom = 6.dp)
            .softCardClickable(
                shape = RoundedCornerShape(10.dp),
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.FormatQuote,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = highlightedSnippet,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
