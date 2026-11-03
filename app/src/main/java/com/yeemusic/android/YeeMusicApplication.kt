package com.yeemusic.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.yeemusic.android.api.ApiClient
import com.yeemusic.android.api.MusicApi
import com.yeemusic.android.log.AppLogger
import com.yeemusic.android.player.CloudDrive
import com.yeemusic.android.player.DownloadManager
import com.yeemusic.android.player.PlayerManager
import com.yeemusic.android.settings.SettingsStore
import com.yeemusic.android.ui.AppActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the process-wide singletons. The notification channel is created here so
 * it exists before the first [androidx.media3.session.MediaSessionService]
 * is started.
 */
class YeeMusicApplication : Application() {

    /**
     * Process-wide UI scope; outlives any single Activity. The handler turns an
     * uncaught coroutine failure into a log line instead of a dead process.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + AppLogger.handler)

    val settings: SettingsStore by lazy { SettingsStore(this) }

    val apiClient: ApiClient by lazy { ApiClient { settings.getCookie() } }

    val musicApi: MusicApi by lazy { MusicApi(apiClient, settings) }

    val downloads: DownloadManager by lazy { DownloadManager(this, musicApi, settings) }

    val cloud: CloudDrive by lazy { CloudDrive(this, musicApi) }

    val player: PlayerManager by lazy { PlayerManager(this, settings, musicApi, downloads) }

    /** One shared instance so every screen and the Activity see the same state. */
    val actions: AppActions by lazy { AppActions(appScope) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // First thing: the log sink, so anything that goes wrong after this is
        // recorded in Android/data/<package>/files/logs/app.log.
        AppLogger.init(this)
        AppLogger.i("App", "application created, log file ${AppLogger.path ?: "<unavailable>"}")

        createNotificationChannel()

        // Pick up the user's API endpoint and keep following it.
        appScope.launch {
            settings.apiBaseUrl.collect { apiClient.baseUrl = it }
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            "default_channel_id",
            "后台播放",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "播放音乐时保持前台服务运行"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        lateinit var instance: YeeMusicApplication
            private set

        val context: Context get() = instance
        val appScope: CoroutineScope get() = instance.appScope
        val settings: SettingsStore get() = instance.settings
        val apiClient: ApiClient get() = instance.apiClient
        val musicApi: MusicApi get() = instance.musicApi
        val downloads: DownloadManager get() = instance.downloads
        val cloud: CloudDrive get() = instance.cloud
        val player: PlayerManager get() = instance.player
        val actions: AppActions get() = instance.actions
    }
}
