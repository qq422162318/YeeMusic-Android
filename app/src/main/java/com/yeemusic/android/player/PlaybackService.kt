package com.yeemusic.android.player

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.yeemusic.android.MainActivity
import com.yeemusic.android.R
import com.yeemusic.android.YeeMusicApplication
import com.yeemusic.android.log.AppLogger

/**
 * Foreground service that keeps playback alive when the app is backgrounded.
 *
 * It deliberately owns nothing — the player and the session both live in
 * [PlayerManager], so the lock screen, the notification and the in-app UI all
 * describe the same object.
 *
 * Enter the foreground before waiting for Media3's asynchronous controller and
 * notification updates. Otherwise Android can deliver
 * ForegroundServiceDidNotStartInTimeException to the main thread while music is
 * already playing. Media3 replaces the startup notification using the same ID.
 */
class PlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        enterForeground()
        addSession(YeeMusicApplication.player.mediaSession)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A paused service may have left the foreground without being destroyed.
        // A new startForegroundService() request still needs a prompt promotion.
        if (!isPlaybackOngoing) enterForeground()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun enterForeground() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(
            this,
            DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_notification_music)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.playback_service_starting))
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(
            this,
            DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )
        AppLogger.i(PlayerManager.TAG, "playback service entered foreground")
    }

    override fun onDestroy() {
        runCatching {
            val session = YeeMusicApplication.player.mediaSession
            // removeSession() throws if the session was never added, which happens
            // when the system stops the service before onCreate finished.
            if (isSessionAdded(session)) removeSession(session)
        }
        super.onDestroy()
    }

    /**
     * Hand out the one session the app already owns. The notification and the
     * media keys both describe this object, so nothing has to be kept in sync.
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        YeeMusicApplication.player.mediaSession
}
