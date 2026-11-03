package com.yeemusic.android.ui.screens

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yeemusic.android.api.Artist
import com.yeemusic.android.api.Song
import com.yeemusic.android.api.SongAlbum
import com.yeemusic.android.ui.AppActions
import com.yeemusic.android.ui.components.EmptyView
import com.yeemusic.android.ui.components.SongRow
import com.yeemusic.android.ui.components.currentSongId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ids for local files never collide with real NetEase song ids. */
private const val LOCAL_ID_BASE = 1_000_000_000_000L

private val AUDIO_MIME_TYPES = arrayOf("audio/*", "video/*")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalMusicScreen(actions: AppActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val currentId = actions.currentSongId()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            try {
                val granted = uris.mapNotNull { uri ->
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }.getOrNull()?.toString()
                }
                val merged = actions.settings.localMusicUris.first() + granted
                actions.settings.setLocalMusicUris(merged)
                songs = scanLocal(context, merged)
            } catch (e: Exception) {
                error = e.message ?: "读取失败"
            } finally {
                loading = false
            }
        }
    }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            try {
                songs = scanLocal(context, actions.settings.localMusicUris.first())
            } catch (e: Exception) {
                error = e.message ?: "读取失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本地音乐", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = { TextButton(onClick = { refresh() }) { Text("刷新") } },
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

            songs.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    EmptyView(error ?: "还没有添加本地音乐", modifier = Modifier.weight(1f))
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = { picker.launch(AUDIO_MIME_TYPES) },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                Icons.Filled.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("选择音乐文件夹", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    TextButton(onClick = { picker.launch(AUDIO_MIME_TYPES) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("继续添加")
                    }
                }
                items(songs, key = { it.idStr }) { song ->
                    SongRow(
                        song = song,
                        isCurrent = currentId == song.id,
                        onClick = { actions.playSong(song, songs) },
                        onLongClick = { actions.addToQueue(song) },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** Resolve title / artist / duration for SAF uris through the resolver. */
private suspend fun scanLocal(context: Context, uris: Set<String>): List<Song> =
    withContext(Dispatchers.IO) {
        uris.mapIndexedNotNull { index, raw ->
            val uri = Uri.parse(raw)
            var title = ""
            var artist = ""
            var album = ""
            var durationMs = 0L

            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.TITLE,
                        MediaStore.MediaColumns.ARTIST,
                        MediaStore.MediaColumns.ALBUM,
                        MediaStore.MediaColumns.DURATION,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use
                    title = stringOf(cursor, MediaStore.MediaColumns.DISPLAY_NAME)
                        ?: stringOf(cursor, MediaStore.MediaColumns.TITLE)
                        ?: ""
                    artist = stringOf(cursor, MediaStore.MediaColumns.ARTIST) ?: ""
                    album = stringOf(cursor, MediaStore.MediaColumns.ALBUM) ?: ""
                    val millis = longOf(cursor, MediaStore.MediaColumns.DURATION)
                    if (millis != null && millis > 0L) durationMs = millis
                }
            }

            if (title.isBlank()) {
                title = uri.lastPathSegment?.substringBeforeLast('.') ?: "本地音乐 ${index + 1}"
            }

            Song(
                id = LOCAL_ID_BASE + index.toLong(),
                name = title,
                ar = listOf(Artist(id = 0, name = artist.ifBlank { "本地音乐" })),
                al = SongAlbum(id = 0, name = album),
                duration = durationMs,
                uri = raw,
                path = raw,
            )
        }
    }

private fun stringOf(cursor: Cursor, column: String): String? {
    val index = cursor.getColumnIndex(column)
    if (index < 0) return null
    return runCatching { cursor.getString(index) }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun longOf(cursor: Cursor, column: String): Long? {
    val index = cursor.getColumnIndex(column)
    if (index < 0) return null
    return runCatching { cursor.getLong(index) }.getOrNull()
}
