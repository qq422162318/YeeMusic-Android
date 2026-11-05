package com.yeemusic.android.ui

import com.yeemusic.android.BuildConfig
import com.yeemusic.android.YeeMusicApplication
import com.yeemusic.android.api.ApiClient
import com.yeemusic.android.api.MusicApi
import com.yeemusic.android.api.Song
import com.yeemusic.android.log.AppLogger
import com.yeemusic.android.player.CloudDrive
import com.yeemusic.android.player.DownloadManager
import com.yeemusic.android.player.PlayerManager
import com.yeemusic.android.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the connectivity probe knows about the configured node. */
enum class NetStatus { UNKNOWN, OK, FAILING }

data class NetState(
    val status: NetStatus = NetStatus.UNKNOWN,
    val message: String = "",
)

/**
 * Shared operations screens need. Kept separate from [PlayerManager] because it
 * mixes player, API and preference concerns and would otherwise have to be
 * threaded through every composable.
 */
class AppActions(private val scope: CoroutineScope) {

    val player: PlayerManager = YeeMusicApplication.player

    /** Exposed for cookie handling; screens should not talk HTTP directly. */
    val apiClient: ApiClient = YeeMusicApplication.apiClient
    val musicApi: MusicApi = YeeMusicApplication.musicApi
    val settings: SettingsStore = YeeMusicApplication.settings
    val downloads: DownloadManager = YeeMusicApplication.downloads
    val cloud: CloudDrive = YeeMusicApplication.cloud

    private val _likedIds = MutableStateFlow<Set<Long>>(emptySet())
    val likedIds: StateFlow<Set<Long>> = _likedIds.asStateFlow()

    private val _refreshTick = MutableStateFlow(0L)
    val refreshTick: StateFlow<Long> = _refreshTick.asStateFlow()

    private val _net = MutableStateFlow(NetState())
    val net: StateFlow<NetState> = _net.asStateFlow()

    private var probeJob: Job? = null

    /**
     * Pings the configured node with a cheap, login-free call.
     *
     * While this is FAILING the shell shows the reconnect page, so the user sees
     * one clear "the server is unreachable, tap here" instead of every screen
     * failing on its own. Re-probed from [MainActivity.onResume], which is what
     * picks up a connection that dropped while the app was backgrounded.
     */
    fun probeConnection() {
        probeJob?.cancel()
        _net.update { it.copy(status = NetStatus.UNKNOWN) }
        probeJob = scope.launch {
            // The endpoint is followed out of DataStore asynchronously, so read
            // the stored value instead of trusting whatever baseUrl holds yet.
            runCatching { settings.apiBaseUrl.first() }.getOrNull()
                ?.let { apiClient.baseUrl = it }
            val url = apiClient.baseUrl
            runCatching { musicApi.searchDefault() }
                .fold(
                    onSuccess = {
                        AppLogger.i("network", "node reachable: $url")
                        _net.update { NetState(NetStatus.OK) }
                    },
                    onFailure = { e ->
                        AppLogger.w("network", "node unreachable $url: ${e.message}")
                        _net.update { NetState(NetStatus.FAILING, e.message ?: "连接失败") }
                    },
                )
        }
    }

    /** Back to the factory endpoint, then probe again. */
    fun restoreDefaultNode() {
        scope.launch {
            runCatching { settings.setApiBaseUrl(BuildConfig.DEFAULT_API_BASE_URL) }
                .onFailure { AppLogger.w("network", "restore default node failed: ${it.message}") }
            apiClient.baseUrl = BuildConfig.DEFAULT_API_BASE_URL
            probeConnection()
        }
    }

    fun playSong(song: Song, queue: List<Song>) = player.play(song, queue)

    fun playQueue(queue: List<Song>, startId: Long) {
        if (queue.isEmpty()) return
        val index = queue.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        player.play(queue[index], queue)
    }

    fun playFirst(queue: List<Song>) {
        if (queue.isEmpty()) return
        player.play(queue.first(), queue)
    }

    fun addToQueue(song: Song, playNow: Boolean = false) = player.addToQueue(song, playNow)

    fun isLiked(id: Long): Boolean = _likedIds.value.contains(id)

    fun refreshLiked() {
        scope.launch {
            val uid = settings.userId.first()
            if (uid <= 0L) {
                _likedIds.update { emptySet() }
                return@launch
            }
            runCatching { musicApi.likedSongIds(uid).toSet() }
                .onSuccess { _likedIds.update { it } }
        }
    }

    /** Returns the new liked state. */
    fun toggleLike(id: Long): Boolean {
        val target = !isLiked(id)
        _likedIds.update { if (target) it + id else it - id }
        scope.launch {
            runCatching { musicApi.toggleLike(id, target) }
                .onFailure { _likedIds.update { if (target) it - id else it + id } }
        }
        return target
    }

    fun download(id: Long) {
        scope.launch { runCatching { downloads.download(id) } }
    }

    /** Goes through the cache handle; deleting the files directly corrupts it. */
    fun clearStreamCache() = player.clearStreamCache()

    fun refreshDownloads() = _refreshTick.update { it + 1 }
}
