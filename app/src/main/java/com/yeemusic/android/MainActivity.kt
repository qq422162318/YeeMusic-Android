package com.yeemusic.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yeemusic.android.log.AppLogger
import com.yeemusic.android.ui.AppActions
import com.yeemusic.android.ui.NetState
import com.yeemusic.android.ui.NetStatus
import com.yeemusic.android.ui.components.MiniPlayerBar
import com.yeemusic.android.ui.components.QueueSheet
import com.yeemusic.android.ui.nav.Routes
import com.yeemusic.android.ui.nav.Tab
import com.yeemusic.android.ui.screens.AlbumScreen
import com.yeemusic.android.ui.screens.ArtistScreen
import com.yeemusic.android.ui.screens.CloudScreen
import com.yeemusic.android.ui.screens.DownloadsScreen
import com.yeemusic.android.ui.screens.FullPlayerScreen
import com.yeemusic.android.ui.screens.HomeScreen
import com.yeemusic.android.ui.screens.LibraryScreen
import com.yeemusic.android.ui.screens.LocalMusicScreen
import com.yeemusic.android.ui.screens.LoginScreen
import com.yeemusic.android.ui.screens.PlaylistScreen
import com.yeemusic.android.ui.screens.ProfileScreen
import com.yeemusic.android.ui.screens.SearchScreen
import com.yeemusic.android.ui.screens.SettingsScreen
import com.yeemusic.android.ui.theme.YeeMusicTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Single-activity shell: bottom navigation + mini player, with the full player
 * drawn as an overlay so playback survives navigation.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TEMP DIAGNOSTIC: distinguishes "fresh process" from "activity recreated
        // in a live process" — the two look identical from the launcher.
        AppLogger.i("activity", "onCreate savedState=${savedInstanceState != null}")
        requestNotificationPermission()
        setContent {
            // The one shared instance: the reconnect state must survive every
            // recomposition, and screens need the same player object as the app.
            val actions = YeeMusicApplication.actions
            val theme by YeeMusicApplication.settings.theme.collectAsState(initial = "system")
            YeeMusicTheme(theme) {
                YeeMusicShell(actions)
            }
        }
    }

    /**
     * Re-probe whenever the app comes back to the foreground. This is what
     * recovers a node that died while we were backgrounded: no request in the
     * app has to fail first before the user sees the reconnect page.
     */
    override fun onResume() {
        super.onResume()
        // TEMP DIAGNOSTIC (notification theme-flip investigation): records the
        // launch path and uiMode so the log can show whether a notification tap
        // is recreating the activity or flipping a configuration. Remove once
        // the FlymeOS launcher-theme bug is understood.
        val cm = resources.configuration
        val night = (cm.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        AppLogger.i(
            "activity",
            "resumed uiMode=0x${Integer.toHexString(cm.uiMode)} night=$night " +
                "taskId=$taskId isTaskRoot=$isTaskRoot intentAction=${intent?.action}",
        )
        YeeMusicApplication.actions.probeConnection()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        AppLogger.i("activity", "newIntent action=${intent.action} data=${intent.data}")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        AppLogger.i("activity", "windowFocus=$hasFocus")
    }

    override fun onPause() {
        super.onPause()
        // Playback keeps going: the foreground service owns it from here on.
        AppLogger.i("activity", "paused while playing=${YeeMusicApplication.player.state.value.isPlaying}")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
        }
    }

    private companion object {
        const val NOTIFICATION_PERMISSION = 1001
    }
}

@Composable
private fun YeeMusicShell(actions: AppActions) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val playerUi = remember(actions.player) {
        actions.player.state.map { it.copy(positionMs = 0, durationMs = 0, volume = 0, lyric = com.yeemusic.android.player.ParsedLyric()) }
            .distinctUntilChanged()
    }
    val initialPlayerUi = remember(actions.player) { actions.player.state.value.copy(positionMs = 0, durationMs = 0, volume = 0, lyric = com.yeemusic.android.player.ParsedLyric()) }
    val snapshot by playerUi.collectAsStateWithLifecycle(initialValue = initialPlayerUi)
    var showQueue by remember { mutableStateOf(false) }

    // Top-level tabs keep the bottom bar; detail pages hide it.
    val topLevel = currentRoute in Routes.bottomNav.map { it.route }

    val net by actions.net.collectAsState()

    Scaffold(
        bottomBar = {
            if (topLevel) {
                Column {
                    MiniPlayerBar(
                        snapshot = snapshot,
                        onPlay = { actions.player.toggle() },
                        onNext = { actions.player.next() },
                        onPrev = { actions.player.prev() },
                        onOpen = { navController.navigate(Routes.FULL_PLAYER) },
                        onQueue = { showQueue = true },
                    )
                    YeeMusicNavigationBar(
                        currentRoute = currentRoute,
                        onTab = { tab ->
                            navController.navigate(tab.route) {
                                // Keep the back stack flat: otherwise every tab tap
                                // stacks another copy and system-back walks them all.
                                popUpTo(Routes.HOME) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        // NavHost keeps composing underneath so coming back to the app restores
        // the screen the user was on; the failure page only covers it.
        Box(modifier = Modifier.fillMaxSize()) {
            // Only the bottom inset is honoured here: each screen owns its top
            // bar and therefore the status-bar inset, applied exactly once.
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                enterTransition = { fadeIn(tween(160)) },
                exitTransition = { fadeOut(tween(120)) },
                popEnterTransition = { fadeIn(tween(160)) },
                popExitTransition = { fadeOut(tween(120)) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding()),
            ) {
                composable(Routes.HOME) {
                    HomeScreen(actions, onSearch = { navController.navigate(Routes.SEARCH) })
                }
                composable(Routes.SEARCH) {
                    SearchScreen(
                        actions,
                        onPlaylist = { navController.navigate(Routes.playlist(it)) },
                        onArtist = { navController.navigate(Routes.artist(it)) },
                        onAlbum = { navController.navigate(Routes.album(it)) },
                    )
                }
                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        actions,
                        onPlaylist = { navController.navigate(Routes.playlist(it)) },
                        onDownloads = { navController.navigate(Routes.DOWNLOADS) },
                        onLocalMusic = { navController.navigate(Routes.LOCAL) },
                        onCloud = { navController.navigate(Routes.CLOUD) },
                        onCreatePlaylist = { navController.navigate(Routes.PROFILE) },
                        onProfile = { navController.navigate(Routes.PROFILE) },
                        onLogin = { navController.navigate(Routes.LOGIN) },
                    )
                }
                composable(Routes.PROFILE) {
                    ProfileScreen(
                        actions,
                        onPlaylist = { navController.navigate(Routes.playlist(it)) },
                        onSettings = { navController.navigate(Routes.SETTINGS) },
                        onLogin = { navController.navigate(Routes.LOGIN) },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        actions,
                        onBack = { navController.navigateUp() },
                        onLogin = { navController.navigate(Routes.LOGIN) },
                    )
                }
                composable(Routes.LOGIN) {
                    LoginScreen(actions, onDone = { navController.navigateUp() })
                }
                composable(Routes.DOWNLOADS) {
                    DownloadsScreen(actions, onBack = { navController.navigateUp() })
                }
                composable(Routes.LOCAL) {
                    LocalMusicScreen(actions, onBack = { navController.navigateUp() })
                }
                composable(Routes.CLOUD) {
                    CloudScreen(actions, onBack = { navController.navigateUp() })
                }
                composable(Routes.FULL_PLAYER) {
                    FullPlayerScreen(
                        actions,
                        onDismiss = { navController.popBackStack() },
                        onQueue = { showQueue = true },
                    )
                }
                composable(Routes.PLAYLIST) { entry ->
                    val id = longArg(entry)
                    PlaylistScreen(id, actions, onBack = { navController.navigateUp() })
                }
                composable(Routes.ARTIST) { entry ->
                    val id = longArg(entry)
                    ArtistScreen(id, actions, onBack = { navController.navigateUp() })
                }
                composable(Routes.ALBUM) { entry ->
                    val id = longArg(entry)
                    AlbumScreen(id, actions, onBack = { navController.navigateUp() })
                }
            }

            if (net.status == NetStatus.FAILING && currentRoute !in setOf(
                    Routes.LIBRARY, Routes.DOWNLOADS, Routes.LOCAL, Routes.FULL_PLAYER, Routes.SETTINGS,
                )
            ) {
                ConnectionFailurePage(
                    state = net,
                    node = actions.apiClient.baseUrl,
                    onRetry = { actions.probeConnection() },
                    onRestore = { actions.restoreDefaultNode() },
                )
            }
        }
    }

    if (showQueue) {
        QueueSheet(
            snapshot = snapshot,
            onDismiss = { showQueue = false },
            onPick = { index ->
                val song = snapshot.queue.getOrNull(index)
                if (song != null) actions.player.play(song, snapshot.queue)
            },
            onRemove = { index -> actions.player.removeFromQueue(index) },
            onMoveUp = { index -> actions.player.moveTo(index, index - 1) },
            onRepeat = { actions.player.cycleRepeat() },
            onClear = { actions.player.clearQueue() },
        )
    }
}

private fun longArg(entry: NavBackStackEntry): Long =
    entry.arguments?.getString("id")?.toLongOrNull() ?: 0L

/**
 * Covers the content area while the configured node is unreachable, so every
 * screen gets one obvious "the server is down, tap here" instead of a dozen
 * independent error states. The mini player and tabs stay below it: playback of
 * already-resolved songs keeps working without a server.
 */
@Composable
private fun ConnectionFailurePage(
    state: NetState,
    node: String,
    onRetry: () -> Unit,
    onRestore: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp, vertical = 24.dp)
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(20.dp))
        Text("无法连接音乐服务", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        Text(
            "当前服务地址\n$node",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (state.message.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onRetry, shape = shape, modifier = Modifier.fillMaxWidth()) {
            Text("重新连接")
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onRestore, shape = shape, modifier = Modifier.fillMaxWidth()) {
            Text("恢复默认服务地址")
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "正在播放和已下载的歌曲不受影响，仍可在下方控制。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun YeeMusicNavigationBar(currentRoute: String?, onTab: (Tab) -> Unit) {
    NavigationBar(
        modifier = Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
        tonalElevation = 0.dp,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Routes.bottomNav.forEach { tab ->
            val selected = tab.route == currentRoute
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) onTab(tab)
                },
                icon = {
                    Icon(
                        navIcon(tab.label),
                        contentDescription = tab.label,
                        modifier = Modifier.size(24.dp),
                    )
                },
                label = { Text(tab.label, style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

private fun navIcon(label: String): ImageVector = when (label) {
    "首页" -> Icons.Filled.Home
    "搜索" -> Icons.Filled.Search
    "我的" -> Icons.Filled.LibraryMusic
    "账号" -> Icons.Filled.Person
    else -> Icons.Filled.Home
}
