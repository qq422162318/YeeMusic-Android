package com.yeemusic.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yeemusic.android.api.Playlist
import com.yeemusic.android.api.Song
import com.yeemusic.android.api.User
import com.yeemusic.android.ui.AppActions
import com.yeemusic.android.ui.components.CoverImage
import com.yeemusic.android.ui.components.SongRow
import com.yeemusic.android.ui.components.currentSongId
import com.yeemusic.android.ui.components.dotted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    actions: AppActions,
    onPlaylist: (Long) -> Unit,
    onDownloads: () -> Unit,
    onLocalMusic: () -> Unit,
    onCloud: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onProfile: () -> Unit,
    onLogin: () -> Unit,
) {
    var user by remember { mutableStateOf<User?>(null) }
    var liked by remember { mutableStateOf<List<Song>?>(null) }
    var recent by remember { mutableStateOf<List<Song>?>(null) }
    var created by remember { mutableStateOf<List<Playlist>?>(null) }
    var subscribed by remember { mutableStateOf<List<Playlist>?>(null) }
    val scope = rememberCoroutineScope()
    val currentId = actions.currentSongId()

    fun load() {
        scope.launch {
            val uid = actions.settings.userId.first()
            if (uid <= 0L) {
                user = null
                liked = emptyList()
                recent = runCatching { actions.musicApi.recentSongs() }.getOrDefault(emptyList())
                created = emptyList()
                subscribed = emptyList()
                return@launch
            }
            user = runCatching { actions.musicApi.userDetail(uid) }.getOrNull()
            liked = runCatching { actions.musicApi.likedSongs(uid) }.getOrDefault(emptyList())
            recent = runCatching { actions.musicApi.recentSongs() }.getOrDefault(emptyList())
            created = runCatching { actions.musicApi.userPlaylists(uid) }.getOrDefault(emptyList())
            actions.refreshLiked()
        }
    }

    LaunchedEffect(Unit) {
        actions.refreshLiked()
        load()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的音乐", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // -- profile row ---------------------------------------------
            item {
                val u = user
                Surface(
                    onClick = { if (u == null) onLogin() else onProfile() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
                        CoverImage(
                            u?.avatarUrl,
                            size = 60,
                            shape = CircleShape,
                            label = u?.nickname ?: "游客",
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                u?.nickname ?: "未登录",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                u?.signature ?: "点击登录，同步收藏和歌单",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onCreatePlaylist) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("新建")
                        }
                    }
                }
            }

            // -- quick entries -------------------------------------------
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    EntryCard(Icons.Filled.Cloud, "网盘", onCloud, Modifier.weight(1f))
                    EntryCard(Icons.Filled.CloudDownload, "下载", onDownloads, Modifier.weight(1f).padding(start = 8.dp))
                    EntryCard(Icons.Filled.LibraryMusic, "本地", onLocalMusic, Modifier.weight(1f).padding(start = 8.dp))
                }
            }
            item { Spacer(Modifier.height(8.dp)) }

            // -- liked ---------------------------------------------------
            item { SectionLabel("喜欢歌曲", liked?.size ?: 0) }
            val likedSongs = liked.orEmpty()
            if (likedSongs.isNotEmpty()) {
                item {
                    Surface(
                        onClick = { actions.playFirst(likedSongs) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                            Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("${likedSongs.size} 首", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text("播放全部", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            // -- recent --------------------------------------------------
            val recentSongs = recent.orEmpty()
            if (recentSongs.isNotEmpty()) {
                item { SectionLabel("最近播放", recentSongs.size) }
                itemsIndexed(recentSongs.take(20), key = { i, song -> "librecent-${song.id}-$i" }) { _, song ->
                    SongRow(
                        song = song,
                        isCurrent = currentId == song.id,
                        onClick = { actions.playSong(song, recentSongs) },
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }

            // -- created playlists ---------------------------------------
            val mine = created.orEmpty()
            if (mine.isNotEmpty()) {
                item { SectionLabel("我创建的歌单", mine.size) }
                items(mine, key = { "mine-${it.id}" }) { pl ->
                    PlaylistRow(pl, { onPlaylist(pl.id) })
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionLabel(title: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
            Text(count.toString(), modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EntryCard(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
            CoverImage(playlist.cover, size = 52, shape = RoundedCornerShape(8.dp), label = playlist.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.nameDisplay, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    dotted(playlist.creatorName, "${playlist.trackCount}首"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
