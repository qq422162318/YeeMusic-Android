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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yeemusic.android.api.Playlist
import com.yeemusic.android.api.User
import com.yeemusic.android.api.UserSubCountResp
import com.yeemusic.android.ui.AppActions
import com.yeemusic.android.ui.components.CoverImage
import com.yeemusic.android.ui.components.compactCount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    actions: AppActions,
    onPlaylist: (Long) -> Unit,
    onSettings: () -> Unit,
    onLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val loggedIn by actions.settings.loggedIn.collectAsState(initial = false)

    var user by remember { mutableStateOf<User?>(null) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var subCount by remember { mutableStateOf<UserSubCountResp?>(null) }
    var likedCount by remember { mutableLongStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var createSheet by remember { mutableStateOf(false) }
    var editSheet by remember { mutableStateOf(false) }

    fun load() {
        loading = true
        error = null
        scope.launch {
            val uid = actions.settings.userId.first()
            if (uid <= 0L) {
                user = null
                playlists = emptyList()
                subCount = null
                likedCount = 0L
                loading = false
                return@launch
            }
            user = runCatching { actions.musicApi.userDetail(uid) }.getOrNull()
            playlists = runCatching { actions.musicApi.userPlaylists(uid) }.getOrDefault(emptyList())
            subCount = runCatching { actions.musicApi.userSubCount(uid) }.getOrNull()
            likedCount = runCatching { actions.musicApi.likedSongIds(uid).size.toLong() }.getOrDefault(0L)
            actions.refreshLiked()
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账号", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        when {
            loading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            user == null -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = colors.outline,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(error ?: "未登录", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        onClick = onLogin,
                        shape = RoundedCornerShape(20.dp),
                        color = colors.primary,
                    ) {
                        Text(
                            "去登录",
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp),
                            color = colors.onPrimary,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }

            else -> {
                val u = user ?: return@Scaffold
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surfaceContainer,
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CoverImage(u.avatarUrl, size = 60, shape = CircleShape, label = u.nickname)
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            u.nickname + if (u.isVip) " · VIP" else "",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            "Lv.${u.level} · ${u.account.userName.ifBlank { "未绑定手机" }}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                        if (u.signature.isNotBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(u.signature, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    IconButton(onClick = { editSheet = true }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "编辑资料")
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    StatBox("${u.createPlaylistCount}", "创建歌单", Modifier.weight(1f))
                                    StatBox("${u.followCount}", "关注", Modifier.weight(1f))
                                    StatBox("${u.djCount}", "电台", Modifier.weight(1f))
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    StatBox(compactCount(likedCount), "喜欢", Modifier.weight(1f))
                                    StatBox(
                                        (subCount?.playlistCount ?: 0).toString(),
                                        "订阅歌单",
                                        Modifier.weight(1f),
                                    )
                                    StatBox(
                                        (subCount?.profileCount ?: 0).toString(),
                                        "粉丝",
                                        Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(
                                "我创建的歌单 (${playlists.size})",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { createSheet = true }) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("新建")
                            }
                        }
                    }

                    if (playlists.isEmpty()) {
                        item {
                            Text(
                                "还没有创建歌单",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                            )
                        }
                    } else {
                        items(playlists, key = { "pl-${it.id}" }) { pl ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = colors.surfaceContainer,
                                onClick = { onPlaylist(pl.id) },
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                                    CoverImage(pl.cover, size = 52, shape = RoundedCornerShape(8.dp), label = pl.nameDisplay)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(pl.nameDisplay, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                        Text(
                                            "${pl.trackCount}首 · ${compactCount(pl.playCount)}次播放",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(10.dp)) }
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surfaceContainer,
                            onClick = onSettings,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            ) {
                                Text("设置", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        }
                    }
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surfaceContainer,
                            onClick = {
                                scope.launch {
                                    actions.musicApi.logout()
                                    actions.settings.clearAuth()
                                    actions.refreshLiked()
                                    onLogin()
                                }
                            },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            ) {
                                Text(
                                    "退出登录",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.error,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (createSheet) {
        CreatePlaylistSheet(
            onDismiss = { createSheet = false },
            onCreate = { name ->
                createSheet = false
                scope.launch {
                    runCatching { actions.musicApi.createPlaylist(name) }
                        .onSuccess { load() }
                }
            },
        )
    }
    if (editSheet) {
        EditProfileSheet(
            initialName = user?.nickname ?: "",
            initialSignature = user?.signature ?: "",
            onDismiss = { editSheet = false },
            onSave = { name, signature ->
                editSheet = false
                scope.launch {
                    runCatching { actions.musicApi.updateUser(name, signature) }
                    load()
                }
            },
        )
    }
}

@Composable
private fun StatBox(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatePlaylistSheet(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("新建歌单", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("歌单名称") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            Spacer(Modifier.height(18.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(12.dp))
                TextButton(
                    onClick = {
                        if (name.isBlank() || busy) return@TextButton
                        onCreate(name.trim())
                    },
                    enabled = !busy,
                ) {
                    Text(if (busy) "创建中…" else "创建")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProfileSheet(
    initialName: String,
    initialSignature: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initialName) }
    var signature by remember { mutableStateOf(initialSignature) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("编辑资料", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(20) },
                label = { Text("昵称") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = signature,
                onValueChange = { signature = it.take(100) },
                label = { Text("个性签名") },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { onSave(name.trim(), signature.trim()) }) { Text("保存") }
            }
        }
    }
}
