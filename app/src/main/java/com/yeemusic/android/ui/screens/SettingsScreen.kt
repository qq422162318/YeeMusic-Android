package com.yeemusic.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yeemusic.android.api.normalizeApiUrl
import com.yeemusic.android.BuildConfig
import com.yeemusic.android.log.AppLogger
import com.yeemusic.android.ui.AppActions
import com.yeemusic.android.ui.components.formatBytes
import kotlinx.coroutines.launch

private data class Choice(val id: String, val label: String)

private val ENGINE_CHOICES = listOf(
    Choice("system", "系统播放器"),
    Choice("exoplayer", "ExoPlayer"),
)

private val QUALITY_CHOICES = listOf(
    Choice("standard", "标准 128kbps"),
    Choice("higher", "较高 192kbps"),
    Choice("exhigh", "极高 320kbps"),
    Choice("lossless", "无损 FLAC"),
    Choice("hires", "Hi-Res"),
)

private val THEME_CHOICES = listOf(
    Choice("system", "跟随系统"),
    Choice("light", "浅色"),
    Choice("dark", "深色"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(actions: AppActions, onBack: () -> Unit, onLogin: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings = actions.settings

    val apiUrl by settings.apiBaseUrl.collectAsState(initial = "")
    val engineIsSystem by settings.useSystemPlayer.collectAsState(initial = true)
    val quality by settings.quality.collectAsState(initial = "exhigh")
    val theme by settings.theme.collectAsState(initial = "system")
    val lyricScale by settings.lyricFontScale.collectAsState(initial = 100)
    val cacheLimit by settings.cacheLimitMb.collectAsState(initial = 0)
    val loggedIn by settings.loggedIn.collectAsState(initial = false)

    var urlDraft by remember { mutableStateOf("") }
    var cacheDraft by remember { mutableStateOf("0") }
    var qualityMenu by remember { mutableStateOf(false) }
    var engineMenu by remember { mutableStateOf(false) }
    var themeMenu by remember { mutableStateOf(false) }
    var downloadBytes by remember { mutableLongStateOf(0L) }
    var streamCacheBytes by remember { mutableLongStateOf(0L) }
    var resetConfirm by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(apiUrl) { urlDraft = apiUrl }
    LaunchedEffect(cacheLimit) { cacheDraft = cacheLimit.toString() }
    LaunchedEffect(Unit) { readStorageUsage(actions) { _, d, s -> downloadBytes = d; streamCacheBytes = s } }

    fun refreshUsage() {
        scope.launch {
            downloadBytes = runCatching { actions.downloads.downloadSize() }.getOrDefault(0L)
            streamCacheBytes = runCatching { actions.downloads.streamCacheSize(actions.player.streamCacheDirectory) }.getOrDefault(0L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { GroupTitle("账号") }
            item {
                SettingsRow(
                    icon = Icons.Filled.Person,
                    label = if (loggedIn) "账号设置" else "登录",
                    value = if (loggedIn) "已登录" else "未登录",
                    onClick = if (loggedIn) onBack else onLogin,
                )
            }

            item { GroupTitle("网络") }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("API 服务地址", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = urlDraft,
                            onValueChange = { urlDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("https://…") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            TextButton(onClick = {
                                scope.launch { runCatching { settings.setApiBaseUrl(urlDraft) } }
                            }) { Text("保存") }
                            TextButton(onClick = {
                                scope.launch { settings.setApiBaseUrl(BuildConfig.DEFAULT_API_BASE_URL) }
                            }) { Text("恢复默认") }
                        }
                        if (testResult != null) {
                            Text(
                                testResult!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Settings,
                    label = "测试连接",
                    value = if (testing) "测试中…" else null,
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            val url = urlDraft.ifBlank { apiUrl }
                            if (url.isNotBlank() && url != apiUrl) {
                                runCatching { settings.setApiBaseUrl(url) }
                            }
                            testResult = runCatching { actions.musicApi.searchDefault() }.fold(
                                onSuccess = { "连接成功：${it.realkeyword.ifBlank { it.showKeyword }}" },
                                onFailure = { "连接失败：${it.message}" },
                            )
                            testing = false
                        }
                    },
                )
            }
            item {
                // The log file lives in the app's own external dir. logcat is
                // unreadable on this device, so the file is the only console;
                // tapping copies its full path for a file manager.
                val context = LocalContext.current
                SettingsRow(
                    icon = Icons.Filled.Info,
                    label = "运行日志",
                    value = "logs/app.log",
                    onClick = {
                        val path = AppLogger.path ?: return@SettingsRow
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                            ?.setPrimaryClip(ClipData.newPlainText("log", path))
                        Toast.makeText(context, "日志路径已复制", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            item {
                // Read-only: the cleartext policy is fixed at build time, so this
                // reports what is actually possible instead of offering a switch
                // that could never take effect.
                val node = normalizeApiUrl(apiUrl)
                val nodeHost = node
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .substringBefore('/')
                    .substringBefore(':')
                val permitted = BuildConfig.CLEARTEXT_HOSTS
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val status = when {
                    node.isBlank() -> "未配置地址"
                    node.startsWith("https://") -> "HTTPS 节点，不受明文策略限制"
                    nodeHost in permitted -> "已放行明文访问：$nodeHost"
                    else -> "该节点是明文 HTTP 但编译时未放行，系统会直接拒绝连接"
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("明文 HTTP", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Android 不允许运行时修改网络策略，明文放行名单在编译时写入" +
                                "（-PcleartextHosts=a.example.com,1.2.3.4）。" +
                                if (permitted.isEmpty()) {
                                    "当前未放行任何远端节点。"
                                } else {
                                    "当前已放行：${permitted.joinToString(", ")}"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { GroupTitle("播放") }
            item {
                val label = QUALITY_CHOICES.firstOrNull { it.id == quality }?.label ?: quality
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { qualityMenu = !qualityMenu }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("音质", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(label, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                        QUALITY_CHOICES.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choice.label) },
                                onClick = {
                                    qualityMenu = false
                                    scope.launch { settings.setQuality(choice.id) }
                                },
                            )
                        }
                    }
                }
            }
            item {
                // ExoPlayer 1.5.1 has a failure that cannot be diagnosed or worked
                // around from the app: a RuntimeException inside
                // ExoPlayerImplInternal.handleMessage that is neither
                // IllegalStateException nor IllegalArgumentException surfaces as
                // ERROR_CODE_UNSPECIFIED with the message "Unexpected runtime
                // error", and the same track then dies every time. The platform
                // player has no such internal loop, so it is the default here.
                val current = if (engineIsSystem) "system" else "exoplayer"
                val label = ENGINE_CHOICES.firstOrNull { it.id == current }?.label ?: "系统播放器"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { engineMenu = !engineMenu }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("播放引擎", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(label, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    DropdownMenu(expanded = engineMenu, onDismissRequest = { engineMenu = false }) {
                        ENGINE_CHOICES.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choice.label) },
                                onClick = {
                                    engineMenu = false
                                    scope.launch { settings.setUseSystemPlayer(choice.id == "system") }
                                },
                            )
                        }
                    }
                }
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("歌词字号", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text("${lyricScale}%")
                        }
                        Slider(
                            value = lyricScale / 100f,
                            valueRange = 0.6f..2f,
                            steps = 13,
                            onValueChange = { scope.launch { settings.setLyricFontScale((it * 100).toInt()) } },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            item { GroupTitle("外观") }
            item {
                val label = THEME_CHOICES.firstOrNull { it.id == theme }?.label ?: theme
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { themeMenu = !themeMenu }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("主题", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(label, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                        THEME_CHOICES.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choice.label) },
                                onClick = {
                                    themeMenu = false
                                    scope.launch { settings.setTheme(choice.id) }
                                },
                            )
                        }
                    }
                }
            }

            item { GroupTitle("存储") }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("离线下载 · ${formatBytes(downloadBytes)}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "在线播放缓存 · ${formatBytes(streamCacheBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Text(
                            "缓存目录：${actions.player.streamCacheDirectory.absolutePath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("缓存上限（MB，0 表示自动）", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = cacheDraft,
                                onValueChange = { cacheDraft = it.filter { c -> c.isDigit() } },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                            )
                            TextButton(onClick = {
                                scope.launch { settings.setCacheLimitMb(cacheDraft.toIntOrNull() ?: 0) }
                            }) { Text("保存") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            TextButton(onClick = {
                                scope.launch {
                                    actions.downloads.clearDownloads()
                                    refreshUsage()
                                }
                            }) { Text("清空下载") }
                            TextButton(onClick = {
                                scope.launch {
                                    actions.clearStreamCache()
                                    refreshUsage()
                                }
                            }) { Text("清理播放缓存") }
                            TextButton(onClick = {
                                scope.launch {
                                    actions.downloads.trimToLimit()
                                    refreshUsage()
                                }
                            }) { Text("按上限裁剪") }
                        }
                    }
                }
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Settings,
                    label = "清空最近播放记录",
                    onClick = { scope.launch { settings.clearRecentHistory() } },
                )
            }

            item { GroupTitle("关于") }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("版本", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("默认 API 节点", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
                        Text(
                            BuildConfig.DEFAULT_API_BASE_URL,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "YeeMusic Android 是一个第三方网易云音乐客户端，由 AGPL-3.0 的 YeeMusic 桌面版移植而来。音乐数据版权属于网易云音乐及相关权利人，本应用仅供个人学习与交流，请勿用于商业用途。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Settings,
                    label = "重置全部设置",
                    onClick = { resetConfirm = true },
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (resetConfirm) {
        AlertDialog(
            onDismissRequest = { resetConfirm = false },
            title = { Text("重置全部设置") },
            text = { Text("将清除登录状态、API 地址和播放偏好，不会影响已下载的文件。") },
            confirmButton = {
                TextButton(onClick = {
                    resetConfirm = false
                    scope.launch {
                        settings.resetAll()
                        actions.refreshLiked()
                    }
                }) { Text("重置", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { resetConfirm = false }) { Text("取消") } },
        )
    }
}

/** One-shot: read both sizes without needing a coroutine scope. */
private suspend fun readStorageUsage(
    actions: AppActions,
    apply: suspend (AppActions, Long, Long) -> Unit,
) {
    val d = runCatching { actions.downloads.downloadSize() }.getOrDefault(0L)
    val s = runCatching { actions.downloads.streamCacheSize(actions.player.streamCacheDirectory) }.getOrDefault(0L)
    apply(actions, d, s)
}

@Composable
private fun GroupTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    value: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = onClick,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (value != null) {
                Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = tint)
        }
    }
}
