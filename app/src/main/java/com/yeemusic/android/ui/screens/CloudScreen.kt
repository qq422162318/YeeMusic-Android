package com.yeemusic.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yeemusic.android.api.UserCloudData
import com.yeemusic.android.player.CloudDrive
import com.yeemusic.android.player.CloudDrive.UploadState
import com.yeemusic.android.ui.AppActions
import com.yeemusic.android.ui.components.EmptyView
import com.yeemusic.android.ui.components.ErrorView
import com.yeemusic.android.ui.components.SongRow
import com.yeemusic.android.ui.components.currentSongId
import com.yeemusic.android.ui.components.formatBytes
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Narrowed to audio: the picker is the only way in, so this is the whole filter. */
private val AUDIO_MIME_TYPES = arrayOf("audio/*")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudScreen(actions: AppActions, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val currentId = actions.currentSongId()

    var data by remember { mutableStateOf<UserCloudData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Keyed by the source uri so a row keeps its own progress as the list reloads.
    val uploads = remember { mutableStateMapOf<String, UploadState>() }
    val jobs = remember { mutableStateMapOf<String, Job>() }

    fun load() {
        loading = true
        error = null
        scope.launch {
            try {
                data = actions.musicApi.cloudAll()
            } catch (e: Exception) {
                // An expired cookie is the common cause here, and it has a fix
                // the user can act on, so it gets its own message.
                error = when {
                    e.message?.contains("登录") == true -> "登录已过期，请重新登录后再查看云盘"
                    else -> e.message ?: "加载失败"
                }
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { uri ->
            // Take persistable permission first: an upload of a 40 MB file
            // outlives the picker callback, and without this the read dies with
            // the result's grant.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            scope.launch {
                val upload = actions.cloud.describe(uri) ?: return@launch
                uploads[upload.uri.toString()] = UploadState.Hashing(upload)
                val job = scope.launch {
                    actions.cloud.upload(upload).collect { state ->
                        uploads[upload.uri.toString()] = state
                    }
                }
                jobs[upload.uri.toString()] = job
                job.invokeOnCompletion {
                    // Refresh so the new track shows up. A failed upload leaves
                    // the list alone rather than appearing to have succeeded, and
                    // a cancelled row may already have been removed by the time
                    // this runs — hence the null check.
                    if (uploads[upload.uri.toString()] is UploadState.Done) load()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("网盘", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { picker.launch(AUDIO_MIME_TYPES) }) {
                        Icon(Icons.Filled.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("上传")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val list = data?.songs.orEmpty()
            val usage = data

            // -- usage header -------------------------------------------
            Surface(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "云盘 · ${usage?.count ?: list.size} 首",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                if (list.isEmpty()) return@TextButton
                                actions.playFirst(list)
                            },
                            enabled = list.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("播放全部")
                        }
                    }
                    if (usage != null && usage.totalBytes > 0) {
                        Spacer(Modifier.height(6.dp))
                        // A quota that has not been read yet would show a full
                        // bar, which is worse than showing nothing.
                        val fraction = usage.usedBytes.toFloat() / usage.totalBytes
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "已用 ${formatBytes(usage.usedBytes)} / ${formatBytes(usage.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            // -- in-flight uploads --------------------------------------
            val pending = uploads.values.filter { it !is UploadState.Done && it !is UploadState.Cancelled }
            pending.forEach { state ->
                val upload = when (state) {
                    is UploadState.Hashing -> state.upload
                    is UploadState.Uploading -> state.upload
                    is UploadState.Failed -> state.upload
                    else -> return@forEach
                }
                UploadRow(
                    state = state,
                    onCancel = {
                        jobs.remove(upload.uri.toString())?.cancel()
                        uploads.remove(upload.uri.toString())
                    },
                )
            }

            // -- the drive ----------------------------------------------
            when {
                loading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                error != null -> ErrorView(error!!, onRetry = { load() })

                list.isEmpty() -> EmptyView("云盘还没有歌曲，点右上角「上传」")

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(list, key = { "cloud-${it.id}" }) { song ->
                        SongRow(
                            song = song,
                            isCurrent = currentId == song.id,
                            onClick = { actions.playSong(song, list) },
                            onLongClick = { actions.addToQueue(song) },
                            actions = {
                                IconButton(onClick = {
                                    scope.launch {
                                        runCatching { actions.musicApi.cloudDelete(listOf(song.id)) }
                                            .onSuccess { load() }
                                            .onFailure {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    it.message ?: "删除失败",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                    }
                                }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/** One row of the transient upload queue. */
@Composable
private fun UploadRow(state: UploadState, onCancel: () -> Unit) {
    val upload = when (state) {
        is UploadState.Hashing -> state.upload
        is UploadState.Uploading -> state.upload
        is UploadState.Failed -> state.upload
        else -> return
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
            Icon(
                Icons.Filled.CloudUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    upload.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (state) {
                        is UploadState.Hashing -> "正在校验文件…"
                        is UploadState.Uploading -> {
                            val pct = if (upload.sizeBytes > 0) {
                                (state.uploadedBytes * 100 / upload.sizeBytes).coerceIn(0, 100)
                            } else 0
                            "$pct% · ${formatBytes(state.uploadedBytes)}"
                        }
                        is UploadState.Failed -> state.message
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state is UploadState.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state is UploadState.Uploading && upload.sizeBytes > 0) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = {
                            (state.uploadedBytes.toFloat() / upload.sizeBytes).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "取消上传")
            }
        }
    }
}
