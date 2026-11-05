package com.yeemusic.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yeemusic.android.api.Album
import com.yeemusic.android.api.Artist
import com.yeemusic.android.api.Playlist
import com.yeemusic.android.api.Song
import com.yeemusic.android.player.RepeatMode
import com.yeemusic.android.ui.AppActions
import com.yeemusic.android.ui.components.CoverImage
import com.yeemusic.android.ui.components.EmptyView
import com.yeemusic.android.ui.components.ErrorView
import com.yeemusic.android.ui.components.RowAction
import com.yeemusic.android.ui.components.SongRow
import com.yeemusic.android.ui.components.compactCount
import com.yeemusic.android.ui.components.currentSongId
import com.yeemusic.android.ui.components.dotted
import kotlinx.coroutines.launch

/**
 * Shared list for playlist / artist / album detail pages: a header, a
 * play-all row, then the songs with like + download actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongListView(
    title: String,
    subtitle: String?,
    cover: String?,
    songs: List<Song>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    actions: AppActions,
    onBack: () -> Unit,
    onLoadMore: (() -> Unit)? = null,
    headerExtra: (@Composable () -> Unit)? = null,
) {
    val currentId = actions.currentSongId()
    val likedIds by actions.likedIds.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        when {
            loading -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }

            error != null -> ErrorView(error, onRetry, modifier = Modifier.padding(padding))

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CoverImage(cover, size = 84, shape = RoundedCornerShape(8.dp), label = title)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleLarge)
                                if (subtitle != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (headerExtra != null) {
                            Spacer(Modifier.height(10.dp))
                            headerExtra()
                        }
                    }
                }

                if (songs.isEmpty()) {
                    item { EmptyView("暂无歌曲") }
                } else {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "播放全部 · ${songs.size}首",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { actions.playFirst(songs) }) { Text("播放") }
                            TextButton(onClick = {
                                if (actions.player.state.value.repeat != RepeatMode.SHUFFLE) {
                                    actions.player.toggleShuffle()
                                }
                                if (songs.isNotEmpty()) actions.playQueue(songs, songs.first().id)
                            }) { Text("打乱") }
                        }
                    }

                    itemsIndexed(songs, key = { index, song -> "song-${song.id}-$index" }) { _, song ->
                        SongRow(
                            song = song,
                            isCurrent = currentId == song.id,
                            onClick = { actions.playSong(song, songs) },
                            modifier = Modifier.padding(vertical = 2.dp),
                            onLongClick = { actions.addToQueue(song, playNow = false) },
                            actions = {
                                RowAction(
                                    { actions.toggleLike(song.id) },
                                    if (likedIds.contains(song.id)) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    "喜欢",
                                    if (likedIds.contains(song.id)) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                RowAction(
                                    { actions.player.insertAt(song, actions.player.state.value.current + 1) },
                                    Icons.AutoMirrored.Filled.QueueMusic,
                                    "下一首",
                                )
                                RowAction({ actions.download(song.id) }, Icons.Filled.Download, "下载")
                            },
                        )
                    }

                    if (onLoadMore != null) {
                        item {
                            TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                                Text("加载更多")
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(playlistId: Long, actions: AppActions, onBack: () -> Unit) {
    var playlist by remember { mutableStateOf<Playlist?>(null) }
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        loading = true
        error = null
        scope.launch {
            try {
                playlist = actions.musicApi.playlistDetail(playlistId)
                songs = actions.musicApi.playlistTracksAll(playlistId)
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(playlistId) { load() }

    val pl = playlist
    SongListView(
        title = pl?.nameDisplay ?: "歌单",
        subtitle = pl?.let { dotted(it.creatorName, "${compactCount(it.playCount)}次播放") },
        cover = pl?.cover,
        songs = songs.orEmpty(),
        loading = loading,
        error = error,
        onRetry = { load() },
        actions = actions,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(artistId: Long, actions: AppActions, onBack: () -> Unit) {
    var artist by remember { mutableStateOf<Artist?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun load(reset: Boolean) {
        if (reset) {
            offset = 0
            songs = emptyList()
            loading = true
        }
        scope.launch {
            try {
                if (reset) {
                    val detail = actions.musicApi.artistDetail(artistId)
                    artist = detail.resolvedArtist
                }
                songs += actions.musicApi.artistSongs(artistId, offset, 20)
                offset += 20
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(artistId) { load(true) }

    val a = artist
    SongListView(
        title = a?.name ?: "歌手",
        subtitle = a?.let {
            listOfNotNull(
                it.aliasText,
                a.tag,
                if (a.albumSize > 0) "专辑 ${a.albumSize}" else null,
                if (a.songCount > 0) "歌曲 ${a.songCount}" else null,
            )
                .filter { s -> s.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { null }
        },
        cover = a?.cover,
        songs = songs,
        loading = loading,
        error = error,
        onRetry = { load(true) },
        actions = actions,
        onBack = onBack,
        onLoadMore = { load(false) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(albumId: Long, actions: AppActions, onBack: () -> Unit) {
    var album by remember { mutableStateOf<Album?>(null) }
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        loading = true
        error = null
        scope.launch {
            try {
                val detail = actions.musicApi.albumDetail(albumId)
                album = detail.album
                songs = detail.songs
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(albumId) { load() }

    val al = album
    SongListView(
        title = al?.name ?: "专辑",
        subtitle = al?.let { "${al.artistTextJoined} · ${al.totalNum}首" },
        cover = al?.cover,
        songs = songs.orEmpty(),
        loading = loading,
        error = error,
        onRetry = { load() },
        actions = actions,
        onBack = onBack,
    )
}
