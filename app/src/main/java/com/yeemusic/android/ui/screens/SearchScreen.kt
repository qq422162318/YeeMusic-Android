package com.yeemusic.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yeemusic.android.api.Song
import com.yeemusic.android.ui.AppActions
import com.yeemusic.android.ui.components.CoverImage
import com.yeemusic.android.ui.components.EmptyView
import com.yeemusic.android.ui.components.ErrorView
import com.yeemusic.android.ui.components.SongRow
import com.yeemusic.android.ui.components.compactCount
import com.yeemusic.android.ui.components.currentSongId
import com.yeemusic.android.ui.components.dotted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private data class TypeTab(val label: String, val type: Int)

private val typeTabs = listOf(
    TypeTab("单曲", 1),
    TypeTab("歌单", 1000),
    TypeTab("歌手", 100),
    TypeTab("专辑", 10),
)

private sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Ok(val songs: List<Song>, val playlists: List<com.yeemusic.android.api.Playlist>,
                  val artists: List<com.yeemusic.android.api.Artist>, val albums: List<com.yeemusic.android.api.Album>,
                  val total: Int, val offset: Int) : SearchState
    data class Error(val message: String) : SearchState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    actions: AppActions,
    onPlaylist: (Long) -> Unit,
    onArtist: (Long) -> Unit,
    onAlbum: (Long) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var defaults by remember { mutableStateOf<List<String>?>(null) }
    var state by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    var offset by rememberSaveable { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val currentId = actions.currentSongId()

    LaunchedEffect(Unit) {
        if (defaults == null) {
            runCatching { actions.musicApi.searchDefault() }
                .onSuccess { defaults = listOf(it.showKeyword, it.realkeyword).filter { k -> k.isNotBlank() } }
        }
        actions.refreshLiked()
    }

    fun runSearch(reset: Boolean) {
        val keywords = query.trim()
        if (keywords.isEmpty()) return
        searchJob?.cancel()
        if (reset) offset = 0
        val requestOffset = offset
        val requestType = typeTabs[selectedTab].type
        state = SearchState.Loading
        searchJob = scope.launch {
            try {
                val result = actions.musicApi.search(keywords, requestType, 30, requestOffset)
                state = SearchState.Ok(
                    songs = result.songs,
                    playlists = result.playlists,
                    artists = result.artists,
                    albums = result.albums,
                    total = result.songCount.coerceAtLeast(result.playlistCount),
                    offset = requestOffset,
                )
                offset = requestOffset + 30
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = SearchState.Error(e.message ?: "搜索失败")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { SearchField(query, { query = it }, focused, { focused = it }, onSearch = { runSearch(true) }) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { searchJob?.cancel(); query = ""; state = SearchState.Idle }) {
                            Icon(Icons.Filled.Close, contentDescription = "清除")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                typeTabs.forEachIndexed { i, tab ->
                    Surface(
                        onClick = {
                            selectedTab = i
                            offset = 0
                            runSearch(true)
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = if (i == selectedTab) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                        contentColor = if (i == selectedTab) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(tab.label, modifier = Modifier.padding(vertical = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            when (val s = state) {
                is SearchState.Idle -> {
                    val chips = defaults.orEmpty()
                    if (chips.isNotEmpty()) {
                        Text("热门搜索", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                        LazyColumn {
                            items(chips) { chip ->
                                Text(
                                    chip,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { query = chip; runSearch(true) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    } else {
                        EmptyView("搜索歌曲、歌单、歌手、专辑")
                    }
                }

                is SearchState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }

                is SearchState.Error -> ErrorView(s.message, onRetry = { runSearch(false) })

                is SearchState.Ok -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    when (typeTabs[selectedTab].type) {
                        1 -> items(s.songs, key = { "s-${it.id}" }) { song ->
                            SongRow(
                                song = song,
                                isCurrent = currentId == song.id,
                                onClick = { actions.playSong(song, s.songs) },
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }

                        1000 -> items(s.playlists, key = { "p-${it.id}" }) { pl ->
                            MetaRow(pl.cover, pl.nameDisplay,
                                dotted(pl.creatorName, "${pl.trackCount}首", "${compactCount(pl.playCount)}次播放"),
                                { onPlaylist(pl.id) })
                        }

                        100 -> items(s.artists, key = { "a-${it.id}" }) { artist ->
                            MetaRow(artist.cover, artist.name, artist.aliasText ?: "歌手",
                                { onArtist(artist.id) })
                        }

                        10 -> items(s.albums, key = { "al-${it.id}" }) { album ->
                            MetaRow(album.cover, album.name,
                                "${album.artistTextJoined} · ${album.totalNum}首",
                                { onAlbum(album.id) })
                        }

                        else -> item { EmptyView("未知类型") }
                    }

                    item {
                        TextButton(onClick = { runSearch(false) }, modifier = Modifier.fillMaxWidth()) {
                            Text("加载更多")
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MetaRow(cover: String?, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 20.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            CoverImage(cover, size = 56, shape = RoundedCornerShape(8.dp), label = title)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    focused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onSearch: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChanged(it.isFocused) }.padding(end = 8.dp),
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        placeholder = { Text("搜索歌曲、歌单、歌手") },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}
