package com.yeemusic.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yeemusic.android.BuildConfig
import com.yeemusic.android.api.normalizeApiUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private fun String?.orDefault(default: String): String = this ?: default

/**
 * DataStore 1.1 exposes only the generic `Preferences.get(Key<T>)`, and the
 * compiler resolves its `T` to String for a BooleanPreferencesKey unless the
 * expected type is spelled out here. Without this the boolean reads below do not
 * compile at all.
 */
private fun <T> androidx.datastore.preferences.core.Preferences.getT(key: androidx.datastore.preferences.core.Preferences.Key<T>): T? =
    get(key)


/**
 * Runtime configuration, persisted with DataStore.
 *
 * The API base url here overrides the build-time default — the desktop app's
 * "Settings → Network → API service" behaviour, changeable without rebuilding.
 */
class SettingsStore(context: Context) {

    // Without the corruptionHandler above, a settings file left half-written (the
    // process died between read and write, or the disk filled up) makes every
    // subsequent launch throw CorruptionException before MainActivity ever composes
    // anything. The failure is permanent — clearing app data is the only escape —
    // so it has to be recovered here rather than treated as fatal.
    private val dataStore: DataStore<Preferences> = context.settingsDataStore

    // -- network -----------------------------------------------------------

    val apiBaseUrl: Flow<String> = dataStore.data.map { it[KEY_API_BASE_URL].orDefault(BuildConfig.DEFAULT_API_BASE_URL) }

    suspend fun getApiBaseUrl(): String = dataStore.data.first()[KEY_API_BASE_URL].orDefault(BuildConfig.DEFAULT_API_BASE_URL)

    suspend fun setApiBaseUrl(url: String) {
        dataStore.edit { it[KEY_API_BASE_URL] = normalizeApiUrl(url) }
    }

    // NB: there is deliberately no "allow cleartext" preference here. Android
    // reads NetworkSecurityConfig once at process start and never lets app code
    // change it, so a runtime toggle would be a switch that does nothing. The
    // policy is generated at build time from -PapiBaseUrl / -PcleartextHosts
    // (see app/build.gradle.kts) and surfaced read-only in Settings.

    // -- playback ----------------------------------------------------------

    /**
     * true = the platform [android.media.MediaPlayer], false = ExoPlayer.
     *
     * Default is the platform player because ExoPlayer 1.5.1 has a failure class
     * that is impossible to diagnose or work around from the app: a RuntimeException
     * inside ExoPlayerImplInternal.handleMessage that is neither
     * IllegalStateException nor IllegalArgumentException surfaces as
     * ERROR_CODE_UNSPECIFIED (1000) with the message "Unexpected runtime error",
     * and the same track then dies every time. The platform player has no such
     * internal loop, and the CDN serves plain MP3.
     */
    val useSystemPlayer: Flow<Boolean> = dataStore.data.map {
        it.getT(KEY_ENGINE) ?: true
    }

    suspend fun getUseSystemPlayer(): Boolean =
        dataStore.data.first().getT(KEY_ENGINE) ?: true

    suspend fun setUseSystemPlayer(enabled: Boolean) {
        dataStore.edit { it[KEY_ENGINE] = enabled }
    }

    val quality: Flow<String> = dataStore.data.map { it[KEY_QUALITY].orDefault(DEFAULT_QUALITY) }

    suspend fun getQuality(): String = dataStore.data.first()[KEY_QUALITY].orDefault(DEFAULT_QUALITY)

    suspend fun setQuality(quality: String) {
        dataStore.edit { it[KEY_QUALITY] = quality }
    }

    val defaultVolume: Flow<Int> = dataStore.data.map { it[KEY_VOLUME] ?: 100 }

    suspend fun setVolume(percent: Int) {
        dataStore.edit { it[KEY_VOLUME] = percent.coerceIn(0, 100) }
    }

    /** Download cache size limit in MB. 0 = use a device free-space heuristic. */
    val cacheLimitMb: Flow<Int> = dataStore.data.map { it[KEY_CACHE_LIMIT_MB] ?: 0 }

    suspend fun setCacheLimitMb(mib: Int) {
        dataStore.edit { it[KEY_CACHE_LIMIT_MB] = mib.coerceAtLeast(0) }
    }

    // -- appearance --------------------------------------------------------

    val theme: Flow<String> = dataStore.data.map { it[KEY_THEME].orDefault("system") }

    suspend fun setTheme(theme: String) {
        dataStore.edit { it[KEY_THEME] = theme }
    }

    val lyricFontScale: Flow<Int> = dataStore.data.map { it[KEY_LYRIC_FONT] ?: 100 }

    suspend fun setLyricFontScale(percent: Int) {
        dataStore.edit { it[KEY_LYRIC_FONT] = percent.coerceIn(60, 200) }
    }

    // -- auth --------------------------------------------------------------

    val cookie: Flow<String?> = dataStore.data.map { it[KEY_COOKIE] }

    suspend fun getCookie(): String? = dataStore.data.first()[KEY_COOKIE]

    val loggedIn: Flow<Boolean> = dataStore.data.map {
        !it[KEY_COOKIE].isNullOrBlank() && it[KEY_LOGIN_STATUS] != 0
    }

    val userId: Flow<Long> = dataStore.data.map { it[KEY_USER_ID] ?: 0L }

    suspend fun setAuth(cookie: String, userId: Long, status: Int) {
        dataStore.edit {
            it[KEY_COOKIE] = cookie
            it[KEY_USER_ID] = userId
            it[KEY_LOGIN_STATUS] = status
        }
    }

    suspend fun clearAuth() {
        dataStore.edit {
            it.remove(KEY_COOKIE)
            it.remove(KEY_USER_ID)
            it[KEY_LOGIN_STATUS] = 0
        }
    }

    // -- history -----------------------------------------------------------

    /** Recently played ids, most recent first. The live view uses
     *  /record/recent/song; this backs the offline fallback. */
    val recentPlayedIds: Flow<List<Long>> = dataStore.data.map {
        it[KEY_RECENT_IDS].orEmpty().mapNotNull { s -> s.toLongOrNull() }.take(200)
    }

    suspend fun recentPlayedIdsList(): List<Long> = recentPlayedIds.first()

    suspend fun addRecentPlayed(id: Long) {
        if (id == 0L) return
        dataStore.edit { p ->
            val current = p[KEY_RECENT_IDS].orEmpty().toMutableList()
            current.remove(id.toString())
            current.add(0, id.toString())
            p[KEY_RECENT_IDS] = current.take(200).toSet()
        }
    }

    suspend fun clearRecentHistory() {
        dataStore.edit { it.remove(KEY_RECENT_IDS) }
    }

    // -- local library -----------------------------------------------------

    /** SAF document URIs picked by the user. Persisted read permission is
     *  requested at pick time, so these survive reboots. */
    val localMusicUris: Flow<Set<String>> = dataStore.data.map { it[KEY_LOCAL_URIS].orEmpty() }

    suspend fun setLocalMusicUris(uris: Set<String>) {
        dataStore.edit { it[KEY_LOCAL_URIS] = uris }
    }

    // -- misc --------------------------------------------------------------

    val firstLaunch: Flow<Boolean> = dataStore.data.map { !it.contains(KEY_HAS_RUN) }

    suspend fun markFirstLaunchDone() {
        dataStore.edit { it[KEY_HAS_RUN] = true }
    }

    suspend fun resetAll() {
        dataStore.edit { it.clear() }
    }

    companion object {
        const val DEFAULT_QUALITY = "exhigh"

        private val KEY_API_BASE_URL = stringPreferencesKey("api_base_url")
        private val KEY_ENGINE = booleanPreferencesKey("use_system_player")
        private val KEY_QUALITY = stringPreferencesKey("quality")
        private val KEY_VOLUME = intPreferencesKey("volume")
        private val KEY_CACHE_LIMIT_MB = intPreferencesKey("cache_limit_mb")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_LYRIC_FONT = intPreferencesKey("lyric_font_scale")
        private val KEY_COOKIE = stringPreferencesKey("cookie")
        private val KEY_USER_ID = longPreferencesKey("user_id")
        private val KEY_LOGIN_STATUS = intPreferencesKey("login_status")
        private val KEY_RECENT_IDS = stringSetPreferencesKey("recent_played_ids")
        private val KEY_LOCAL_URIS = stringSetPreferencesKey("local_music_uris")
        private val KEY_HAS_RUN = booleanPreferencesKey("has_run")
    }
}
