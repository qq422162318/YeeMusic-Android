package com.yeemusic.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yeemusic.android.api.Song
import com.yeemusic.android.player.DownloadManager.DownloadedTrack
import com.yeemusic.android.ui.AppActions
import com.yeemusic.android.ui.components.CoverImage
import com.yeemusic.android.ui.components.EmptyView
import com.yeemusic.android.ui.components.ErrorView
import com.yeemusic.android.ui.components.currentSongId
import com.yeemusic.android.ui.components.formatBytes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(actions: AppActions, onBack: () -> Unit) {
    var tracks by remember { mutableStateOf<List<DownloadedTrack>?>(null) }
    var songs by remember { mutableStateOf<Map<Long, Song>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var used by remember { mutableLongStateOf(0L) }
    var streamCache by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val currentId = actions.currentSongId()

    fun load() {
        loading = true
        error = null
        scope.launch {
            try {
                val list = actions.downloads.list()
                tracks = list
                used = list.sumOf { it.sizeBytes }
                streamCache = runCatching { actions.downloads.streamCacheSize(actions.player.streamCacheDirectory) }.getOrDefault(0L)
                songs = if (list.isEmpty()) emptyMap()
                else runCatching { actions.musicApi.songDetail(list.map { it.songId }) }
                    .getOrDefault(emptyList()).associateBy { it.id }
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下载", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        when {
            loading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            error != null -> ErrorView(error!!, onRetry = { load() }, modifier = Modifier.padding(padding))

            else -> {
                val list = tracks.orEmpty()
                val playableSongs = list.map { track ->
                    (songs[track.songId] ?: Song(id = track.songId, name = "本地文件 ${track.songId}"))
                        .copy(path = track.file.absolutePath)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Storage,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("离线歌曲 · ${list.size} 首", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "占用 ${formatBytes(used)} · 在线缓存 ${formatBytes(streamCache)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row {
                                    TextButton(onClick = { load() }) { Text("刷新") }
                                    TextButton(onClick = {
                                        scope.launch {
                                            actions.downloads.clearDownloads()
                                            load()
                                        }
                                    }) { Text("清空下载") }
                                    TextButton(onClick = {
                                        scope.launch {
                                            actions.clearStreamCache()
                                            actions.downloads.trimToLimit()
                                            load()
                                        }
                                    }) { Text("清理缓存") }
                                }
                            }
                        }
                    }

                    if (list.isEmpty()) {
                        item { EmptyView("还没有下载的歌曲，长按歌曲选择「下载」") }
                    } else {
                        items(list, key = { it.songId.toString() }) { track ->
                            val song = songs[track.songId]
                            val isCurrent = currentId == track.songId
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                onClick = {
                                    val playable = playableSongs.first { it.id == track.songId }
                                    actions.playSong(playable, playableSongs)
                                },
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                                    CoverImage(song?.coverUrl, size = 44, label = song?.name ?: track.songId.toString())
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            song?.name ?: "本地文件 ${track.songId}",
                                            maxLines = 1,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            song?.artistsText ?: track.ext.uppercase(),
                                            maxLines = 1,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            formatBytes(track.sizeBytes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                    IconButton(onClick = {
                                        scope.launch {
                                            actions.downloads.delete(track)
                                            load()
                                        }
                                    }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}
