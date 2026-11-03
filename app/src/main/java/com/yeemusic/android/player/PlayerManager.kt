package com.yeemusic.android.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.core.content.ContextCompat
import com.yeemusic.android.log.AppLogger
import java.io.IOException
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.yeemusic.android.MainActivity
import com.yeemusic.android.api.LyricData
import com.yeemusic.android.api.MusicApi
import com.yeemusic.android.api.Song
import com.yeemusic.android.api.SongUrlData
import com.yeemusic.android.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

enum class RepeatMode(val label: String) {
    SEQUENCE("顺序播放"),
    LIST_LOOP("列表循环"),
    SINGLE_LOOP("单曲循环"),
    SHUFFLE("随机播放"),
}

/** Immutable view of the player, published to the UI as a [StateFlow]. */
data class PlayerSnapshot(
    val queue: List<Song> = emptyList(),
    val current: Int = -1,
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 100,
    val repeat: RepeatMode = RepeatMode.SEQUENCE,
    val error: String? = null,
    val lyric: ParsedLyric = ParsedLyric(),
) {
    val hasMedia: Boolean get() = song != null
    val title: String get() = song?.name ?: "未在播放"
    val artistText: String get() = song?.artistsText ?: ""
    val coverUrl: String? get() = song?.coverUrl
}

/** What a song should actually play, independent of which engine plays it. */
private data class MediaTarget(
    val url: String,
    val isLocal: Boolean,
    val useSystemPlayer: Boolean,
    val stream: CachedStream? = null,
)

/**
 * Adapts [MediaPlayer] to the media3 [Player] interface.
 *
 * [androidx.media3.session.MediaSession] does not expose its own playback state —
 * it reads the state, metadata and commands out of whatever [Player] it is
 * handed. The session is also the object the lock screen and the notification are
 * built from, so playing through the platform player without this bridge would
 * leave both showing an idle player.
 *
 * Every method the platform player cannot express is a no-op. They exist so the
 * class compiles, not because anything calls them.
 */
private class MediaPlayerBridge(
    private val context: Context,
    private val volume: Float,
    private val onPrepared: (Long) -> Unit,
    private val onCompletion: () -> Unit,
    private val onError: (Int, Int) -> Boolean,
) : Player {

    @Volatile
    var volumeLevel = volume

    private var mediaPlayer = createMediaPlayer()
    private var cachedSource: CacheMediaDataSource? = null

    private fun createMediaPlayer() = MediaPlayer().apply {
        setAudioAttributes(
            PlatformAudioAttributes.Builder()
                .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        setVolume(volumeLevel, volumeLevel)
        setOnPreparedListener { onPrepared(it.duration.toLong()) }
        setOnCompletionListener { onCompletion() }
        setOnBufferingUpdateListener { _, buffered -> bufferedPercent = buffered }
        setOnErrorListener { _, what, extra ->
            runCatching { onError(what, extra) }
                .onFailure { DiagnosticReport.note("system player error callback: ${it.message}") }
            true
        }
    }

    private val listeners = mutableListOf<Player.Listener>()

    /** Set once the source has been prepared and [getMediaItemCount] is meaningful. */
    @Volatile
    var trackItem: MediaItem? = null

    @Volatile
    var prepared = false

    @Volatile
    var playing = false

    @Volatile
    var bufferedPercent = 0

    /** MediaPlayer was started and has been running long enough to know its position. */
    private fun isLive(): Boolean = prepared && playing

    private fun fire(action: (Player.Listener) -> Unit) {
        // The bridge's callbacks come off the MediaPlayer's own looper, so no
        // locking is needed against the callers of addListener.
        listeners.toList().forEach(action)
    }

    /**
     * The commands the notification and the lock screen read to decide which
     * transport buttons to draw.
     *
     * [COMMAND_SEEK_TO_PREVIOUS] and [COMMAND_SEEK_TO_NEXT] alone are what
     * media3's DefaultMediaNotificationProvider looks for: it maps them to the
     * prev/next buttons without requiring a real multi-item timeline, which the
     * bridge does not have (it loads exactly one item at a time). Omitting them
     * is why the notification drew neither button — the provider gates each one
     * on its own command, and play/pause was the only one advertised.
     *
     * media3 sends the button press as [COMMAND_SEEK_TO_NEXT] /
     * [COMMAND_SEEK_TO_PREVIOUS], and the session hands it to the app's
     * [MediaSession.Callback.onPlayerCommandRequest] — the bridge's own
     * no-op seekToNext()/seekToPrevious() are never reached, so the queue
     * advance has to be routed there.
     */
    private val playerCommands: Player.Commands
        get() = if (prepared) {
            Player.Commands.Builder()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .build()
        } else {
            Player.Commands.EMPTY
        }

    fun mediaPlayer(): MediaPlayer = mediaPlayer

    fun startPlayback() {
        try {
            mediaPlayer.start()
            playing = true
            fire { it.onIsPlayingChanged(true) }
            fire { it.onPlaybackStateChanged(Player.STATE_READY) }
        } catch (e: IllegalStateException) {
            playing = false
            DiagnosticReport.note("system player start: ${e.message}")
        }
    }

    fun pausePlayback() {
        try {
            mediaPlayer.pause()
            playing = false
            fire { it.onIsPlayingChanged(false) }
            fire { it.onPlaybackStateChanged(Player.STATE_READY) }
        } catch (e: IllegalStateException) {
            playing = false
            DiagnosticReport.note("system player pause: ${e.message}")
        }
    }

    fun setDataSource(url: String) {
        mediaPlayer.setDataSource(context, Uri.parse(url))
    }

    fun setDataSource(source: CacheMediaDataSource) {
        cachedSource = source
        mediaPlayer.setDataSource(source)
    }

    private fun closeCachedSource() {
        runCatching { cachedSource?.close() }
            .onFailure { DiagnosticReport.note("cached media source close: ${it.message}") }
        cachedSource = null
    }

    fun prepareAsync() {
        mediaPlayer.prepareAsync()
    }

    fun stopPlayback() {
        playing = false
        prepared = false
        runCatching { mediaPlayer.stop() }
            .onFailure { DiagnosticReport.note("system player stop: ${it.message}") }
    }

    fun releasePlayer() {
        playing = false
        prepared = false
        mediaPlayer.release()
        closeCachedSource()
        mediaPlayer = createMediaPlayer()
    }

    fun disposePlayer() {
        playing = false
        prepared = false
        listeners.clear()
        mediaPlayer.release()
        closeCachedSource()
    }

    // -- Player ------------------------------------------------------------

    override fun addListener(listener: Player.Listener) {
        if (!listeners.contains(listener)) listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        listeners -= listener
    }

    override fun isPlaying(): Boolean = isLive()

    /** media3 1.5 has no STATE_PLAYING: paused playback is STATE_READY with isPlaying() false. */
    override fun getPlaybackState(): Int =
        if (prepared) Player.STATE_READY else Player.STATE_BUFFERING

    override fun isLoading(): Boolean = !prepared

    override fun getDuration(): Long = if (prepared) mediaPlayer.duration.toLong() else C.TIME_UNSET

    override fun getCurrentPosition(): Long = if (prepared) mediaPlayer.currentPosition.toLong() else 0L

    /** MediaPlayer reports a buffered percentage, not a position — derive the position. */
    override fun getBufferedPosition(): Long =
        if (prepared) (getDuration().coerceAtLeast(0) * bufferedPercent / 100f).toLong() else 0L

    override fun getBufferedPercentage(): Int = bufferedPercent.coerceIn(0, 100)

    override fun getMediaItemCount(): Int = if (trackItem != null) 1 else 0

    override fun getCurrentMediaItem(): MediaItem? = trackItem

    override fun getMediaItemAt(index: Int): MediaItem =
        if (index == 0) trackItem ?: MediaItem.EMPTY else MediaItem.EMPTY

    override fun getMediaMetadata(): androidx.media3.common.MediaMetadata =
        trackItem?.mediaMetadata ?: androidx.media3.common.MediaMetadata.EMPTY

    override fun getVolume(): Float = volumeLevel

    override fun setVolume(level: Float) {
        volumeLevel = level
        mediaPlayer.setVolume(level, level)
    }

    override fun play() = startPlayback()

    override fun pause() = pausePlayback()

    override fun seekTo(positionMs: Long) {
        runCatching { mediaPlayer.seekTo(positionMs.toInt()) }
            .onFailure { DiagnosticReport.note("system player seek: ${it.message}") }
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) startPlayback() else pausePlayback()
    }

    override fun getPlayWhenReady(): Boolean = playing

    override fun getAvailableCommands(): Player.Commands = playerCommands

    override fun isCommandAvailable(command: Int): Boolean = playerCommands.contains(command)

    override fun canAdvertiseSession(): Boolean = true

    override fun getApplicationLooper(): android.os.Looper = android.os.Looper.getMainLooper()

    override fun release() {
        mediaPlayer.release()
        closeCachedSource()
        trackItem = null
        prepared = false
        playing = false
    }

    // -- No-ops: the platform player has no equivalent ---------------------

    override fun setMediaItems(items: MutableList<MediaItem>) {}
    override fun setMediaItems(items: MutableList<MediaItem>, resetPosition: Boolean) {}
    override fun setMediaItems(items: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {}
    override fun setMediaItem(item: MediaItem) {}
    override fun setMediaItem(item: MediaItem, startPositionMs: Long) {}
    override fun setMediaItem(item: MediaItem, resetPosition: Boolean) {}
    override fun addMediaItem(item: MediaItem) {}
    override fun addMediaItem(index: Int, item: MediaItem) {}
    override fun addMediaItems(items: MutableList<MediaItem>) {}
    override fun addMediaItems(index: Int, items: MutableList<MediaItem>) {}
    override fun moveMediaItem(fromIndex: Int, toIndex: Int) {}
    override fun moveMediaItems(fromIndex: Int, toIndex: Int, count: Int) {}
    override fun replaceMediaItem(index: Int, item: MediaItem) {}
    override fun replaceMediaItems(index: Int, count: Int, items: MutableList<MediaItem>) {}
    override fun removeMediaItem(index: Int) {}
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {}
    override fun clearMediaItems() {}
    override fun prepare() {}
    override fun getPlayerError(): PlaybackException? = null
    override fun getRepeatMode(): Int = Player.REPEAT_MODE_OFF
    override fun setRepeatMode(repeatMode: Int) {}
    override fun getShuffleModeEnabled(): Boolean = false
    override fun setShuffleModeEnabled(enabled: Boolean) {}
    override fun seekToDefaultPosition() {}
    override fun seekToDefaultPosition(playbackPositionAdjustmentMs: Int) {}
    override fun seekTo(windowIndex: Int, positionMs: Long) {}
    override fun seekToPrevious() {}
    override fun seekToNext() {}
    override fun seekToNextWindow() {}
    override fun seekToPreviousWindow() {}
    override fun seekToNextMediaItem() {}
    override fun seekToPreviousMediaItem() {}
    override fun next() {
        onCompletion()
    }

    override fun stop() {}
    override fun getCurrentTracks(): androidx.media3.common.Tracks = androidx.media3.common.Tracks.EMPTY
    override fun getTrackSelectionParameters(): androidx.media3.common.TrackSelectionParameters =
        androidx.media3.common.TrackSelectionParameters.DEFAULT

    override fun setTrackSelectionParameters(parameters: androidx.media3.common.TrackSelectionParameters) {}
    override fun getPlaylistMetadata(): androidx.media3.common.MediaMetadata =
        androidx.media3.common.MediaMetadata.EMPTY

    override fun setPlaylistMetadata(metadata: androidx.media3.common.MediaMetadata) {}
    override fun getCurrentManifest(): Any? = null
    override fun getCurrentTimeline(): androidx.media3.common.Timeline =
        androidx.media3.common.Timeline.EMPTY

    override fun getCurrentPeriodIndex(): Int = -1
    override fun getCurrentWindowIndex(): Int = 0
    override fun getCurrentMediaItemIndex(): Int = 0
    override fun getNextWindowIndex(): Int = 0
    override fun getNextMediaItemIndex(): Int = 0
    override fun getPreviousWindowIndex(): Int = 0
    override fun getPreviousMediaItemIndex(): Int = 0
    override fun isCurrentWindowDynamic(): Boolean = false
    override fun isCurrentMediaItemDynamic(): Boolean = false
    override fun isCurrentWindowLive(): Boolean = false
    override fun isCurrentMediaItemLive(): Boolean = false
    override fun getCurrentLiveOffset(): Long = 0L
    override fun isCurrentWindowSeekable(): Boolean = prepared
    override fun isCurrentMediaItemSeekable(): Boolean = prepared
    override fun isPlayingAd(): Boolean = false
    override fun getCurrentAdGroupIndex(): Int = -1
    override fun getCurrentAdIndexInAdGroup(): Int = -1
    override fun getContentDuration(): Long = if (prepared) mediaPlayer.duration.toLong() else C.TIME_UNSET
    override fun getContentPosition(): Long = getCurrentPosition()
    override fun getContentBufferedPosition(): Long = getBufferedPosition()
    override fun getAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()
    override fun setAudioAttributes(attributes: AudioAttributes, handleAudioFocus: Boolean) {}
    override fun getPlaybackSuppressionReason(): Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    override fun getSeekBackIncrement(): Long = C.DEFAULT_SEEK_BACK_INCREMENT_MS
    override fun seekBack() { seekTo(maxOf(0L, getCurrentPosition() - getSeekBackIncrement())) }
    override fun getSeekForwardIncrement(): Long = C.DEFAULT_SEEK_FORWARD_INCREMENT_MS
    override fun seekForward() { seekTo(getCurrentPosition() + getSeekForwardIncrement()) }
    override fun hasPreviousMediaItem(): Boolean = false
    override fun getMaxSeekToPreviousPosition(): Long = 0L
    override fun hasNext(): Boolean = false
    override fun hasNextWindow(): Boolean = false
    override fun hasNextMediaItem(): Boolean = false
    override fun getTotalBufferedDuration(): Long =
        (getBufferedPosition() - getCurrentPosition()).coerceAtLeast(0)
    override fun clearVideoSurface() {}
    // media3 annotates these parameters @Nullable, and Kotlin requires the
    // nullability to match on an override.
    override fun clearVideoSurface(surface: android.view.Surface?) {}
    override fun setVideoSurface(surface: android.view.Surface?) {}
    override fun setVideoSurfaceHolder(holder: android.view.SurfaceHolder?) {}
    override fun clearVideoSurfaceHolder(holder: android.view.SurfaceHolder?) {}
    override fun setVideoSurfaceView(view: android.view.SurfaceView?) {}
    override fun clearVideoSurfaceView(view: android.view.SurfaceView?) {}
    override fun setVideoTextureView(view: android.view.TextureView?) {}
    override fun clearVideoTextureView(view: android.view.TextureView?) {}
    override fun getVideoSize(): androidx.media3.common.VideoSize =
        androidx.media3.common.VideoSize.UNKNOWN

    override fun getSurfaceSize(): androidx.media3.common.util.Size =
        androidx.media3.common.util.Size.UNKNOWN

    override fun getCurrentCues(): androidx.media3.common.text.CueGroup =
        androidx.media3.common.text.CueGroup.EMPTY_TIME_ZERO

    override fun getDeviceInfo(): androidx.media3.common.DeviceInfo =
        androidx.media3.common.DeviceInfo.UNKNOWN
    override fun getDeviceVolume(): Int = 0
    override fun isDeviceMuted(): Boolean = false
    override fun setDeviceVolume(deviceVolume: Int) {}
    override fun setDeviceVolume(deviceVolume: Int, flags: Int) {}
    override fun increaseDeviceVolume() {}
    override fun increaseDeviceVolume(amount: Int) {}
    override fun decreaseDeviceVolume() {}
    override fun decreaseDeviceVolume(amount: Int) {}
    override fun setDeviceMuted(muted: Boolean) {}
    override fun setDeviceMuted(muted: Boolean, flags: Int) {}
    override fun setPlaybackParameters(parameters: androidx.media3.common.PlaybackParameters) {}
    override fun setPlaybackSpeed(speed: Float) {}
    override fun getPlaybackParameters(): androidx.media3.common.PlaybackParameters =
        androidx.media3.common.PlaybackParameters.DEFAULT
}

/**
 * Owns the [ExoPlayer] and the play queue. Everything the UI needs to render
 * comes from [state].
 *
 * Playable URLs from the backend are short-lived and quality-specific, so a
 * queue is a list of songs; the URL is resolved on demand and cached to disk.
 */
class PlayerManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val api: MusicApi,
    private val downloads: DownloadManager? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + AppLogger.handler)

    @Volatile
    private var resolvingFor: Song? = null

    @Volatile
    private var currentLoadJob: Job? = null

    val streamCacheDirectory = context.musicCacheDirectory()

    private val cache: SimpleCache? = openCache()

    private val upstream: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent("YeeMusic-Android/1.0")
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)

    private val playbackCache = PlaybackCache(cache, upstream)

    /**
     * [SimpleCache] can fail when its on-disk index is unreadable. Earlier cache
     * cleanup deleted the index while it was still open. Cache directories survive
     * app updates, so a damaged index can otherwise break the next launch.
     *
     * The index is disposable, so wipe it and reopen. If even a clean directory
     * fails, keep going without disk caching rather than crashing — playback
     * still works, it just re-downloads.
     */
    private fun openCache(): SimpleCache? {
        val dir = streamCacheDirectory
        AppLogger.i(TAG, "playback cache directory: ${dir.absolutePath}")
        val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES)
        try {
            return SimpleCache(dir, evictor)
        } catch (e: RuntimeException) {
            // Some Media3 versions wrap the index read failure in a runtime exception.
            AppLogger.w(TAG, "unreadable cache index, resetting ${dir.path}", e)
        } catch (e: IOException) {
            AppLogger.w(TAG, "unreadable cache index, resetting ${dir.path}", e)
        }
        dir.listFiles()?.forEach { it.delete() }
        return runCatching { SimpleCache(dir, evictor) }
            .onFailure { AppLogger.e(TAG, "disk cache unavailable, streaming without it", it) }
            .getOrNull()
    }

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    // Play/pause and seek go straight to the player, so the bridge answers them
    // itself. The skip commands are different: media3 has no timeline to move
    // through (one item is loaded at a time), so it routes them here as
    // COMMAND_SEEK_TO_NEXT / COMMAND_SEEK_TO_PREVIOUS and the queue has to
    // advance instead. Without this the notification buttons were drawn but did
    // nothing.
    private val sessionCallbacks = object : MediaSession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: android.content.Intent,
        ): Boolean = false

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int = when (playerCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            -> {
                skip(delta = 1)
                SessionResult.RESULT_SUCCESS
            }

            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> {
                prev()
                SessionResult.RESULT_SUCCESS
            }

            else -> SessionResult.RESULT_SUCCESS
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                // The one signal media3 guarantees for "this item played out".
                // onMediaItemTransition cannot serve here: exactly one item is
                // ever loaded, so the transition reason is never AUTO — the
                // player reaches STATE_ENDED without a transition event at all.
                onItemFinished()
            }
            _state.update { it.copy(isPlaying = player.playWhenReady && state == Player.STATE_READY) }
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            _state.update { it.copy(isPlaying = playing) }
            // Playback starting is the one moment the foreground service has to
            // exist: media3 1.5 never starts it on its own, so without this call
            // there is no notification and the system reclaims the process the
            // moment the app leaves the foreground — which reads as "the song
            // stops when I background the app".
            if (playing) startPlaybackService()
        }

        override fun onPlayerError(error: PlaybackException) {
            isLoading = false
            val songName = queue.getOrNull(current)?.name ?: "?"
            AppLogger.e(TAG, "playback failed for $songName", error)
            errorText = describePlaybackError(error, queue.getOrNull(current))
            _state.update { it.copy(isLoading = false, error = errorText) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // With one item loaded the reason is never AUTO, so there is nothing
            // to advance here — that is [onItemFinished] via STATE_ENDED. This
            // only keeps the UI's copy of the queue current.
            if (mediaItem == null) return
            refresh()
        }
    }

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(audioAttributes, true)
        .build()

    /**
     * What tapping the notification does.
     *
     * media3's DefaultMediaNotificationProvider calls
     * `setContentIntent(mediaSession.getSessionActivity())`; with no session
     * activity set that is null, so the notification has nothing to launch and
     * a tap does nothing. The PendingIntent has to be an *activity* one —
     * media3 checks that on API 31+.
     *
     * MainActivity is `singleTask`, so this brings the existing task forward
     * instead of stacking a second copy of the app on top of it.
     */
    private fun sessionActivity(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Wired to the foreground service in [PlaybackService]. */
    val mediaSession: MediaSession =
        MediaSession.Builder(context, player)
            .setCallback(sessionCallbacks)
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivity())
            .build()

    private fun metadataFor(song: Song): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(song.name)
            .setArtist(song.artistsText)
            .setAlbumTitle(song.al?.name)
        song.coverUrl?.takeIf { it.isNotBlank() }?.let { builder.setArtworkUri(Uri.parse(it)) }
        return builder.build()
    }

    private var queue: MutableList<Song> = mutableListOf()
    private var current: Int = -1
    private var repeat: RepeatMode = RepeatMode.SEQUENCE
    private var isLoading: Boolean = false
    private var errorText: String? = null
    private var reportPath: String? = null

    /** Last URL that actually went to ExoPlayer — worth knowing when the stream is dead. */
    @Volatile
    private var lastUrl: String? = null

    /**
     * Drives [mediaSession] when the platform player is in use.
     *
     * The MediaSession has no way to describe playback on its own; it reads the
     * state out of whichever [Player] it is handed, so playing through the
     * platform player without it would leave the notification and lock screen
     * describing an idle ExoPlayer.
     */
    private val bridge = MediaPlayerBridge(
        context,
        volume = 1f,
        onPrepared = { duration ->
            onSystemPrepared(duration)
        },
        onCompletion = {
            skip(delta = 1)
        },
        onError = { what, extra ->
            onSystemError(what, extra); true
        },
    )

    @Volatile
    private var usingSystemPlayer = false

    @Volatile
    private var systemPlaying = false

    /**
     * Once the platform player has died, stay on ExoPlayer for the rest of the
     * session. Without this a track that dies once would be retried through the
     * same dead engine on every later song and fail again.
     */
    @Volatile
    private var systemPlayerDead = false

    private var positionMs: Long = 0
    private var durationMs: Long = 0
    private var pendingSeekMs: Long? = null
    private var seekRequestedAt: Long = 0
    private var volumeSaveJob: Job? = null
    private var lyric: ParsedLyric = ParsedLyric()

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<PlayerSnapshot> = _state.asStateFlow()

    init {
        player.addListener(listener)
        // The bridge is handed to MediaSession in place of ExoPlayer when the
        // platform engine takes over, and it fires the same callbacks by hand.
        // Without this the listener only ever hears ExoPlayer, so the state the
        // UI reads goes stale the moment the bridge starts driving playback.
        bridge.addListener(listener)
        positionTicker()
        scope.launch {
            settings.defaultVolume.collect { v ->
                player.volume = v / 100f
                bridge.setVolume(v / 100f)
                _state.update { it.copy(volume = v) }
            }
        }
        // Re-choosing the platform player clears the dead-engine flag, so a
        // later switch back on is not silently ignored.
        scope.launch {
            settings.useSystemPlayer.collect { systemPlayerDead = false }
        }
    }

    /** The session's source of truth, whichever engine is actually playing. */
    private fun attachToSession(active: Boolean) {
        // MediaSession in media3 1.5.1 has no setActive/setPlaybackState of its
        // own — it reads them off the attached Player, so handing it the bridge
        // is what makes the notification and lock screen move.
        mediaSession.setPlayer(if (active) bridge else player)
    }

    // -- queue -------------------------------------------------------------

    /** Replace the queue and start playing [song]. */
    fun play(song: Song, songs: List<Song>) {
        queue = songs.toMutableList()
        val index = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        startAt(index)
    }

    /** Add to the end; optionally start playing immediately. */
    fun addToQueue(song: Song, playNow: Boolean = false) {
        val index = queue.indexOfFirst { it.id == song.id }
        val insertAt = if (index >= 0) index else queue.size
        queue.add(insertAt, song)
        if (playNow || player.mediaItemCount == 0) startAt(insertAt)
        refresh()
    }

    fun insertAt(song: Song, index: Int) {
        val clamped = index.coerceIn(0, queue.size)
        queue.add(clamped, song)
        refresh()
    }

    fun removeFromQueue(index: Int) {
        if (index !in queue.indices) return
        queue.removeAt(index)
        if (queue.isEmpty()) {
            current = -1
            lyric = ParsedLyric()
            releaseSystemPlayer()
            player.clearMediaItems()
            errorText = null
            refresh()
            stopPlaybackService()
            return
        }
        if (index < current) current -= 1
        else if (index == current) {
            current = current.coerceAtMost(queue.size - 1)
            reloadCurrent()
            return
        }
        refresh()
    }

    fun moveTo(index: Int, target: Int) {
        if (index !in queue.indices || target !in queue.indices) return
        val moved = queue.removeAt(index)
        queue.add(target, moved)
        if (index < current && target >= current) current++
        else if (index > current && target <= current) current--
        clampIndex()
        refresh()
    }

    fun clearQueue() {
        queue.clear()
        current = -1
        lyric = ParsedLyric()
        errorText = null
        releaseSystemPlayer()
        player.clearMediaItems()
        refresh()
        stopPlaybackService()
    }

    // -- background playback -----------------------------------------------

    /**
     * Keeps the session alive while the app is backgrounded.
     *
     * Media3 1.5 does not start its own [PlaybackService] — that only happens
     * when a client connects, and this app never connects one. Without this the
     * service never runs: no notification, no foreground-service protection, so
     * the OS reclaims the process shortly after the app leaves the foreground
     * and the song dies with it.
     *
     * Guarded because Android 12+ refuses a foreground-service start while the
     * app is in the background; in the case that matters (a notification action
     * resuming playback) the service is already running, so a plain start is
     * enough there.
     */
    private fun startPlaybackService() {
        if (queue.isEmpty()) return
        val intent = Intent(context, PlaybackService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            runCatching { context.startService(intent) }
                .onFailure { AppLogger.w(TAG, "playback service not started: ${it.message}") }
        }
    }

    /** Stops the service when nothing is queued, so no stale notification lingers. */
    private fun stopPlaybackService() {
        runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
            .onFailure { AppLogger.w(TAG, "playback service stop failed: ${it.message}") }
    }

    // -- transport ---------------------------------------------------------

    fun toggle() {
        if (usingSystemPlayer) {
            if (systemPlaying) bridge.pausePlayback() else bridge.startPlayback()
        } else if (!player.playWhenReady) player.play() else player.pause()
    }

    fun play() {
        if (usingSystemPlayer) bridge.startPlayback() else player.play()
    }

    fun pause() {
        if (usingSystemPlayer) bridge.pausePlayback() else player.pause()
    }

    fun next() = skip(delta = 1)

    /** Current position, whichever engine is playing. */
    private val currentTrackPosition: Long
        get() = if (usingSystemPlayer) {
            bridge.getCurrentPosition()
        } else if (player.mediaItemCount > 0) {
            player.currentPosition
        } else {
            0L
        }

    /** [player.seekTo] for whichever engine is playing. */
    private fun seekToCurrentPosition(ms: Long) {
        if (usingSystemPlayer) bridge.seekTo(ms) else player.seekTo(ms)
        // Platform seeks complete asynchronously; don't snap the thumb back to the old position.
        pendingSeekMs = ms
        seekRequestedAt = android.os.SystemClock.elapsedRealtime()
        positionMs = ms
        _state.update { it.copy(positionMs = ms) }
    }

    /**
     * Always goes back a song in the list.
     *
     * The earlier "replay from the start unless we are in the first 3 seconds"
     * rule made the first press do nothing but restart the current track, so a
     * user who wanted the previous song had to press twice — and the two presses
     * had to land within three seconds of each other or the second one restarted
     * again. That reads exactly as the button ignoring the list.
     */
    fun prev() = skip(delta = -1)

    private fun skip(delta: Int) {
        if (queue.isEmpty()) return
        current = current.coerceIn(0, queue.size - 1)
        when (repeat) {
            RepeatMode.SINGLE_LOOP -> {
                if (delta == 1) {
                    seekToCurrentPosition(0)
                    if (usingSystemPlayer) bridge.startPlayback() else player.play()
                    refresh()
                } else if (queue.size > 1) {
                    current = (current + queue.size - 1) % queue.size
                    reloadCurrent()
                }
            }

            RepeatMode.SHUFFLE -> {
                if (queue.size <= 1) return
                var index = Random.nextInt(queue.size - 1)
                if (index >= current) index++
                current = index
                reloadCurrent()
            }

            // SEQUENCE plays the list once and stops there; LIST_LOOP wraps.
            // Wrapping unconditionally made the last song hand back to the first,
            // which read as the player ignoring the order of the list.
            else -> {
                val moved = current + delta
                if (repeat == RepeatMode.LIST_LOOP) {
                    current = (moved + queue.size * 2) % queue.size
                } else {
                    if (moved < 0 || moved >= queue.size) {
                        // Off an end in SEQUENCE mode: the list is genuinely
                        // finished, so there is nothing to advance to. Restart the
                        // track we are sitting on instead of returning silently — a
                        // skip button that does nothing reads as the player ignoring
                        // the list, which is exactly what this used to do at both
                        // ends, and [prev] made worse by re-arming the restart on
                        // every press.
                        if (current in queue.indices) {
                            seekToCurrentPosition(0)
                            if (usingSystemPlayer) bridge.startPlayback() else player.play()
                            refresh()
                        }
                        return
                    }
                    current = moved
                }
                reloadCurrent()
            }
        }
    }

    /**
     * Queue advancement after the current song plays out.
     *
     * Driven from [STATE_ENDED], not from an `onMediaItemTransition` AUTO event:
     * exactly one item is ever loaded, so ExoPlayer's transition reason is never
     * AUTO — it reaches [STATE_ENDED] without emitting a transition at all. The
     * AUTO branch used to carry this and was therefore dead code, which is why a
     * finished song stopped the player instead of starting the next one.
     *
     * The guard rejects a stale signal: when the user skips while the player is
     * still finishing the track it is leaving, the state has already moved on and
     * the ENDED for the abandoned song must not advance the queue a second time.
     */
    private fun onItemFinished() {
        if (queue.isEmpty()) return
        if (currentLoadJob?.isActive == true) return
        AppLogger.i(TAG, "queue advance on STATE_ENDED: ${queue.getOrNull(current)?.name ?: "?"}")
        skip(delta = 1)
    }

    fun seekTo(ms: Long) {
        seekToCurrentPosition(ms.coerceIn(0, durationMs.coerceAtLeast(1)))
    }

    fun seekByFraction(fraction: Float) {
        if (durationMs > 0) seekToCurrentPosition((durationMs * fraction.coerceIn(0f, 1f)).toLong())
    }

    /**
     * The −15/+15 second buttons.
     *
     * Clamped to the duration as well as zero: an unclamped seek past the end of
     * the track reaches ExoPlayer, which treats it as "finish this item", so a
     * forward skip near the end used to advance the queue as a side effect.
     */
    fun seekBy(deltaMs: Long) {
        val limit = if (durationMs > 0) durationMs else Long.MAX_VALUE
        seekToCurrentPosition((currentTrackPosition + deltaMs).coerceIn(0, limit))
    }

    fun setVolume(percent: Int) {
        val v = percent.coerceIn(0, 100)
        if (_state.value.volume == v) return
        player.volume = v / 100f
        bridge.setVolume(v / 100f)
        _state.update { it.copy(volume = v) }
        volumeSaveJob?.cancel()
        volumeSaveJob = scope.launch {
            delay(250)
            settings.setVolume(v)
        }
    }

    fun cycleRepeat(): RepeatMode {
        repeat = when (repeat) {
            RepeatMode.SEQUENCE -> RepeatMode.LIST_LOOP
            RepeatMode.LIST_LOOP -> RepeatMode.SINGLE_LOOP
            RepeatMode.SINGLE_LOOP -> RepeatMode.SHUFFLE
            RepeatMode.SHUFFLE -> RepeatMode.SEQUENCE
        }
        refresh()
        return repeat
    }

    fun toggleShuffle() {
        repeat = if (repeat == RepeatMode.SHUFFLE) RepeatMode.SEQUENCE else RepeatMode.SHUFFLE
        refresh()
    }

    // -- internals ---------------------------------------------------------

    private fun startAt(index: Int) {
        if (index !in queue.indices) return
        current = index
        loadCurrent()
    }

    private fun reloadCurrent() {
        if (current !in queue.indices) return
        loadCurrent()
    }

    private fun loadCurrent() {
        val song = queue.getOrNull(current) ?: return
        pendingSeekMs = null
        resolvingFor = song
        errorText = null
        isLoading = true
        lyric = ParsedLyric()
        _state.update {
            it.copy(
                song = song,
                current = current,
                isLoading = true,
                error = null,
                positionMs = 0,
                durationMs = song.durationMs,
                lyric = ParsedLyric(),
            )
        }

        currentLoadJob?.cancel()
        currentLoadJob = scope.launch {
            val target = resolveTarget(song)

            if (target == null) {
                if (resolvingFor !== song) return@launch
                isLoading = false
                errorText = "无法播放：${song.name}（版权限制或节点失效）"
                _state.update { it.copy(isLoading = false, error = errorText) }
                return@launch
            }

            if (resolvingFor !== song) return@launch
            if (target.useSystemPlayer) {
                releaseSystemPlayer()
                playWithSystemPlayer(target, song)
                return@launch
            }

            releaseSystemPlayer()
            try {
                player.setMediaSource(buildSource(target, song))
                player.seekTo(0)
                player.prepare()
                player.playWhenReady = true
                startPlaybackService()
                isLoading = false
                errorText = null
                // One full update, not a partial copy: the bar and the full
                // player read these fields together, and a partial copy could
                // leave one of them describing the song that just finished.
                _state.update {
                    it.copy(
                        queue = queue.toList(),
                        current = current,
                        song = queue.getOrNull(current),
                        repeat = repeat,
                        isLoading = false,
                        error = null,
                        lyric = lyric,
                    )
                }
                loadLyric(song)
                scope.launch { settings.addRecentPlayed(song.id) }
            } catch (e: Exception) {
                isLoading = false
                errorText = "加载失败：${e.message}"
                _state.update { it.copy(isLoading = false, error = errorText) }
            }
        }
    }

    /**
     * ExoPlayer funnels nearly everything into "ERROR_CODE_IO_UNSPECIFIED" or
     * "ERROR_CODE_UNSPECIFIED" and parks the real reason a frame down the cause
     * chain. Worse, the "Unexpected runtime error" path attaches a cause whose
     * message is empty, so class names are the only handle left in the UI.
     * Everything that does not fit in one line goes to [DiagnosticReport].
     */
    private fun describePlaybackError(error: PlaybackException, song: Song?): String {
        var cause: Throwable? = error
        var detail = ""
        val chain = StringBuilder()
        while (cause != null) {
            val message = cause.message?.takeIf { it.isNotBlank() }
            if (message != null) detail = message.take(200)
            chain.append("← ").append(cause.javaClass.simpleName)
            if (message != null) chain.append(": ").append(message)
            cause = cause.cause
        }
        val code = error.errorCodeName ?: ""
        val summary = buildString {
            append("code=${code.ifEmpty { "<null>" }}")
            append(" | ")
            append(lastUrl ?: "<url not resolved>")
            append(" | ")
            append(chain)
        }
        reportPath = DiagnosticReport.dump(context, summary, error)?.absolutePath

        val suffix = detail.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
        val reportHint = reportPath?.let { " 日志：$it" } ?: ""
        return when {
            detail.contains("CLEARTEXT", ignoreCase = true) ->
                "播放出错：音源是明文 HTTP 地址，已被系统网络安全策略拦截。" +
                    "请在 build.gradle.kts 用 -PmediaHosts 放行该域名后重新打包。$suffix$reportHint"

            code.contains("TIMEOUT") -> "播放出错：连接音源超时$suffix$reportHint"

            code.contains("BAD_HTTP_STATUS") -> "播放出错：音源地址返回异常状态$suffix$reportHint"

            code.contains("DECOD") -> "播放出错：音频解码失败$suffix$reportHint"

            else -> "播放出错：${code.ifEmpty { "未知错误" }}$suffix$reportHint"
        }
    }

    /**
     * Pulls the first bytes through ExoPlayer's own data source before the URL is
     * handed to the player. ExoPlayer reports a failure at this stage as an
     * unclassified runtime error and never says which host refused, so fetching it
     * ourselves turns an unanswerable failure into a network error that also names
     * the status code — which is what decides "fix the allowlist" vs "fix the
     * decoder".
     */
    private fun probeStream(stream: CachedStream) {
        val url = stream.url
        val spec = DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setKey(stream.key)
            .setPosition(0L)
            .setLength(PROBE_BYTES.toLong())
            .build()
        val source = playbackCache.sourceFactory(stream.offline).createDataSource()
        try {
            source.open(spec)
            val buf = ByteArray(PROBE_BYTES)
            val read = source.read(buf, 0, buf.size)

            val head = if (read > 0) buf.copyOfRange(0, read.coerceAtMost(32)) else ByteArray(0)
            val headText = head.joinToString("") {
                val v = it.toInt() and 0xFF
                if (v in 0x20..0x7E) Character.toString(v.toChar()) else "."
            }
            val contentType = source.getResponseHeaders()["Content-Type"]?.firstOrNull().orEmpty()
            DiagnosticReport.note(
                "probe ${url.substringBefore('?').take(90)} → ${read}B, " +
                    "type=$contentType, head=$headText",
            )
            if (read <= 0) throw IOException("音源连接成功但没有返回数据")
        } finally {
            runCatching { source.close() }
        }
    }

    /**
     * Works out where a song should be played from, and whether the platform
     * engine should play it.
     *
     * Runs off the main thread because [probeStream] does real socket I/O and
     * media3's data source honours Android's main-thread network ban with a hard
     * exception — that used to kill the process the moment playback was tapped.
     * It also never throws: a failure here becomes an error string, not an
     * uncaught exception on the main dispatcher.
     */
    private suspend fun resolveTarget(song: Song): MediaTarget? {
        val target = withContext(Dispatchers.IO) {
            val useSystem = settings.getUseSystemPlayer() && !systemPlayerDead
            val localPath = when {
                // A downloaded copy wins: it is instant and works offline.
                downloads?.hasFile(song.id) == true -> downloads.findFile(song.id)?.absolutePath
                song.uri.isNotBlank() -> song.uri
                song.path.isNotBlank() -> song.path
                else -> null
            }
            if (localPath != null) {
                // Local files used to leave lastUrl pointing at the previous track,
                // so the diagnostics named a song that was never played.
                lastUrl = localPath
                return@withContext MediaTarget(localPath, isLocal = true, useSystem)
            }
            val quality = settings.getQuality()
            playbackCache.findComplete(song.id, quality)?.let { cached ->
                lastUrl = cached.url
                AppLogger.i(TAG, "complete playback cache hit: ${song.id}, $quality, ${cached.length}B")
                return@withContext MediaTarget(cached.url, isLocal = false, useSystem, cached)
            }
            val resolved = resolveUrl(song, quality) ?: return@withContext null
            if (resolved.url.isNullOrBlank()) return@withContext null
            val stream = playbackCache.remember(song.id, quality, resolved)
            lastUrl = stream.url
            AppLogger.i(TAG, "playback cache miss: ${song.id}, $quality, engine=${if (useSystem) "system" else "exo"}")
            try {
                probeStream(stream)
            } catch (e: Exception) {
                // Diagnostic only: still hand the URL to the player, since a
                // blocked or slow host can be transient.
                DiagnosticReport.note("probe ${e.javaClass.simpleName}: ${e.message}")
            }
            MediaTarget(stream.url, isLocal = false, useSystem, stream)
        }
        return if (resolvingFor !== song) null else target
    }

    /** [ProgressiveMediaSource] for an http(s) URL, cached; a plain source for a file. */
    private fun buildSource(target: MediaTarget, song: Song): MediaSource =
        if (target.isLocal) buildUriSource(Uri.parse(target.url), song)
        else buildUrlSource(checkNotNull(target.stream), song)

    /**
     * Plays [target] through the platform player.
     *
     * MediaPlayer's async methods do not block, so they can be called straight
     * from the main thread; creating the MediaPlayer there is also what makes
     * its listeners fire on the main looper.
     */
    private fun playWithSystemPlayer(target: MediaTarget, song: Song) {
        bridge.trackItem = mediaItem(target.url, song)
        bridge.releasePlayer()
        attachToSession(true)
        usingSystemPlayer = true
        try {
            if (target.isLocal) {
                bridge.setDataSource(target.url)
            } else {
                val stream = checkNotNull(target.stream)
                bridge.setDataSource(CacheMediaDataSource(stream, playbackCache.sourceFactory(stream.offline)))
            }
            bridge.prepareAsync()
        } catch (e: Exception) {
            DiagnosticReport.note("system player: ${e.javaClass.simpleName}: ${e.message}")
            releaseSystemPlayer()
            isLoading = false
            errorText = "播放出错：系统播放器无法打开音源（${e.message}）"
            _state.update { it.copy(isLoading = false, error = errorText) }
        }
    }

    /** Hands the session and the remembered position back to ExoPlayer. */
    private fun releaseSystemPlayer() {
        if (!usingSystemPlayer) return
        usingSystemPlayer = false
        systemPlaying = false
        bridge.stopPlayback()
        bridge.trackItem = null
        bridge.releasePlayer()
        attachToSession(false)
        pendingSeekMs = null
        positionMs = 0
    }

    private fun onSystemPrepared(lengthMs: Long) {
        val song = resolvingFor ?: return
        if (!usingSystemPlayer) return
        bridge.prepared = true
        // startPlayback() fires onIsPlayingChanged, which is what pulls the
        // foreground service up for the platform engine. Setting the flag by
        // hand would leave the session with no playback state at all.
        bridge.startPlayback()
        systemPlaying = true
        isLoading = false
        errorText = null
        pendingSeekMs = null
        positionMs = 0
        durationMs = lengthMs
        _state.update {
            it.copy(
                isLoading = false,
                error = null,
                isPlaying = true,
                durationMs = lengthMs,
                positionMs = 0,
            )
        }
        scope.launch(Dispatchers.IO) {
            loadLyric(song)
            settings.addRecentPlayed(song.id)
        }
    }

    private fun onSystemError(what: Int, extra: Int) {
        val message = "系统播放器错误 what=$what extra=$extra"
        val diagnostic = IllegalStateException(message)
        reportPath = DiagnosticReport.dump(context, message, diagnostic)?.absolutePath

        // The platform player gives up with an unclassifiable code on some
        // devices. Retry the same track through ExoPlayer once rather than
        // parking the player on an error the user cannot act on. `systemPlayerDead`
        // is what stops this from looping on a later track.
        if (!systemPlayerDead) {
            systemPlayerDead = true
            DiagnosticReport.note("system player died, staying on ExoPlayer from now on")
            releaseSystemPlayer()
            loadCurrent()
            return
        }

        releaseSystemPlayer()
        isLoading = false
        errorText = "播放出错：$message${reportPath?.let { " 日志：$it" } ?: ""}"
        _state.update { it.copy(isLoading = false, error = errorText) }
    }

    /**
     * Tries the requested bitrate first, then walks down [QUALITY_LADDER].
     * One request is enough to tell whether the account actually has a level.
     *
     * Passes `unblock=true`, so a VIP track the account is entitled to comes back
     * whole (as FLAC) instead of as a 30-second 试听 clip. A level that answers
     * with a trial fragment is remembered and only used if no other level yields
     * the full track — walking past it to a lower level is always the better trade.
     */
    private suspend fun resolveUrl(song: Song, wanted: String): SongUrlData? {
        val ladder = buildList {
            add(wanted)
            addAll(QUALITY_LADDER)
        }.distinct()

        var trial: SongUrlData? = null
        for (level in ladder) {
            val data = runCatching { api.songUrl(song.id, level, unblock = true) }.getOrNull()
            if (data == null || data.url.isNullOrBlank()) continue
            if (!data.freeTrial) return data
            if (trial == null) trial = data
        }
        // Nothing full-quality on offer. For a fee song the 解锁 mirror is the
        // only remaining source; for anything else the trial is still playable.
        if (song.isVipLocked || trial != null) {
            val unblocked = runCatching { api.unblockedUrl(song.id, wanted) }.getOrNull()
            if (unblocked != null && !unblocked.url.isNullOrBlank()) {
                AppLogger.d(TAG, "unlocked ${song.name} via mirror (${unblocked.type})")
                return unblocked
            }
        }
        return trial
    }

    private fun buildUrlSource(stream: CachedStream, song: Song): MediaSource =
        ProgressiveMediaSource.Factory(playbackCache.sourceFactory(stream.offline))
            .createMediaSource(
                mediaItem(stream.url, song).buildUpon().setCustomCacheKey(stream.key).build(),
            )

    /**
     * A downloaded or device-local file plays straight from disk.
     *
     * Wrapping it in a CacheDataSource whose cache was never set makes media3
     * throw a NullPointerException inside `createDataSourceInternal` the moment
     * the period is created, so the local-file path is what used to crash
     * playback. The disk cache would also only burn 256 MB on a file that is
     * already on this device.
     */
    private fun buildUriSource(uri: Uri, song: Song): MediaSource =
        ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
            .createMediaSource(mediaItem(uri.toString(), song))

    /** The song id rides in mediaId so [onMediaItemTransition] can map back. */
    private fun mediaItem(uri: String, song: Song): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(song.id.toString())
            .setMediaMetadata(metadataFor(song))
            .build()

    /** After a removal the remembered index may be out of range. */
    private fun clampIndex() {
        if (queue.isEmpty()) current = -1
        else current = current.coerceIn(0, queue.size - 1)
    }

    private suspend fun loadLyric(song: Song) {
        val data: LyricData? = runCatching { api.songLyric(song.id) }.getOrNull()
        if (resolvingFor !== song) return
        // Blocks are parsed individually: NetEase answers some songs with a
        // timing-only translation that would otherwise fill the sheet with dots.
        val parsed = withContext(Dispatchers.Default) {
            LyricParser.parseBlocks(data?.main, data?.translation, data?.romaji)
        }
        if (resolvingFor !== song) return
        lyric = parsed
        _state.update { it.copy(lyric = parsed) }
        AppLogger.d(TAG, "lyric for ${song.name}: ${parsed.lines.size} lines, unTimed=${parsed.unTimed}")
    }

    private fun positionTicker() {
        scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                if (usingSystemPlayer || player.mediaItemCount > 0) {
                    val actual = if (usingSystemPlayer) bridge.getCurrentPosition() else player.currentPosition
                    val pending = pendingSeekMs
                    val awaitingSeek = pending != null &&
                        android.os.SystemClock.elapsedRealtime() - seekRequestedAt < 1_500 &&
                        kotlin.math.abs(actual - pending) > 1_000
                    positionMs = if (awaitingSeek) pending!! else actual
                    if (!awaitingSeek) pendingSeekMs = null
                    val item = queue.getOrNull(current)
                    if (item != null && item.durationMs > 0) durationMs = item.durationMs
                    _state.update { it.copy(positionMs = positionMs, durationMs = durationMs) }
                }
                delay(if (_state.value.isPlaying) 250L else 750L)
            }
        }
    }

    private fun refresh() {
        _state.update {
            it.copy(
                queue = queue.toList(),
                current = current,
                song = queue.getOrNull(current),
                repeat = repeat,
            )
        }
    }

    /** [PlayerSnapshot.isPlaying], whichever engine is playing. */
    private val isPlayingNow: Boolean
        get() = if (usingSystemPlayer) bridge.isPlaying() else player.playWhenReady

    private fun snapshot(): PlayerSnapshot = PlayerSnapshot(
        queue = queue.toList(),
        current = current,
        song = queue.getOrNull(current),
        isPlaying = isPlayingNow,
        isLoading = isLoading,
        positionMs = 0,
        durationMs = 0,
        volume = (player.volume * 100).toInt(),
        repeat = repeat,
        lyric = lyric,
    )

    fun destroy() {
        scope.cancel()
        runCatching { mediaSession.release() }
        runCatching { bridge.disposePlayer() }
        player.release()
        runCatching { cache?.release() }
    }

    /**
     * Drops streamed chunks through the cache itself. Deleting the files under
     * [cache] directly corrupts the index, which is what turns "clean playback
     * cache" into a crash on the next open.
     */
    fun clearStreamCache() {
        scope.launch(Dispatchers.Main.immediate) {
            runCatching {
                cache?.let { c ->
                    // SimpleCache.clear() is gone since media3 1.5; removing the
                    // resources by key drops their files without touching the index.
                    c.getKeys().toList().forEach { key -> c.removeResource(key) }
                }
            }.onFailure { AppLogger.w(TAG, "stream cache clear failed", it) }
        }
    }

    companion object {
        const val TAG = "PlayerManager"
        const val SESSION_ID = "com.yeemusic.android.session"

        /** Playback cache budget; evicted least-recently-used first. */
        const val CACHE_SIZE_BYTES = 256L * 1024 * 1024

        /** Enough to read the HTTP headers and the MP3 frame sync, not a whole chunk. */
        private const val PROBE_BYTES = 4096

        /**
         * Bitrate ladder walked when a level comes back with no URL. Highest
         * first, so a request for a level the account may not have costs one
         * extra call at most instead of one per level.
         *
         * `lossless` sits between `exhigh` and `higher` so a non-VIP track
         * still plays as FLAC when the account holds that tier — but a VIP
         * track that 320 kbps already serves does not pay for the FLAC probe.
         */
        private val QUALITY_LADDER = listOf("exhigh", "lossless", "higher", "standard")
    }
}
