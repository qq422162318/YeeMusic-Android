package com.yeemusic.android.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.yeemusic.android.api.SongUrlData
import com.yeemusic.android.log.AppLogger
import java.security.MessageDigest

internal data class CachedStream(
    val url: String,
    val key: String,
    val length: Long = C.LENGTH_UNSET.toLong(),
    val offline: Boolean = false,
)

/** Both engines share keys and bytes, independently of short-lived CDN URLs. */
internal class PlaybackCache(
    private val cache: Cache?,
    private val upstream: DataSource.Factory,
) {
    private val onlineFactory: DataSource.Factory = cache?.let {
        CacheDataSource.Factory()
            .setCache(it)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    } ?: upstream

    private val offlineFactory: DataSource.Factory? = cache?.let {
        CacheDataSource.Factory().setCache(it).setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
    }

    fun sourceFactory(offline: Boolean): DataSource.Factory =
        if (offline) checkNotNull(offlineFactory) else onlineFactory

    fun remember(songId: Long, quality: String, data: SongUrlData): CachedStream {
        val url = requireNotNull(data.url)
        val key = cacheKey(songId, quality, data)
        runCatching {
            cache?.applyContentMetadataMutations(
                key,
                ContentMetadataMutations()
                    .set(URL, url)
                    .set(FULL_TRACK, if (data.freeTrial) 0L else 1L)
                    .set(EXPECTED_SIZE, data.size)
                    .remove(ContentMetadata.KEY_REDIRECTED_URI),
            )
        }.onFailure { AppLogger.w(PlayerManager.TAG, "cache metadata write failed", it) }
        return CachedStream(url, key)
    }

    /**
     * Only a full, gap-free copy is eligible for offline playback.
     *
     * The size must come from the cache span itself (`getCachedLength`), not from
     * [ContentMetadata.getContentLength]: that reads Media3's internal `exo_len`,
     * which is only populated for ranged requests. A span's length alone cannot
     * tell a partial copy from a complete one, so it is compared against the size
     * the API reported for this rendition when it was cached.
     */
    fun findComplete(songId: Long, quality: String): CachedStream? = runCatching {
        val disk = cache ?: return@runCatching null
        val prefix = keyPrefix(songId, quality)
        disk.keys.asSequence().filter { it.startsWith(prefix) }.mapNotNull { key ->
            val metadata = disk.getContentMetadata(key)
            val url = metadata.get(URL, "").orEmpty()
            if (metadata.get(FULL_TRACK, 0L) != 1L || url.isBlank()) return@mapNotNull null
            val expected = metadata.get(EXPECTED_SIZE, 0L)
            if (expected <= 0L) return@mapNotNull null
            // A hole span answers with a negative length, and a partial copy holds
            // fewer bytes than the rendition should — both rejected here. The
            // tolerance band absorbs a CDN Content-Length that differs from the
            // API's reported size by a few bytes.
            val length = disk.getCachedLength(key, 0L, Long.MAX_VALUE)
            if (length <= 0L || kotlin.math.abs(length - expected) > expected / 100L) return@mapNotNull null
            if (!disk.isCached(key, 0L, length)) return@mapNotNull null
            CachedStream(url, key, length, offline = true)
        }.firstOrNull()
    }.onFailure { AppLogger.w(PlayerManager.TAG, "cache lookup failed", it) }.getOrNull()

    companion object {
        private const val URL = "custom_yeemusic_url"
        private const val FULL_TRACK = "custom_yeemusic_full_track"
        private const val EXPECTED_SIZE = "custom_yeemusic_expected_size"

        // v3 adds the expected-size metadata that offline detection compares against;
        // v2 keys hold bytes the old player never fed through the cache.
        private fun keyPrefix(songId: Long, quality: String) = "yeemusic-v3:$songId:$quality:"

        // Different renditions and trial fragments must never share byte offsets.
        internal fun cacheKey(songId: Long, quality: String, data: SongUrlData): String {
            val rendition = listOf(data.level, data.type, data.br, data.size, data.md5, data.freeTrial)
                .joinToString("|")
            val hash = MessageDigest.getInstance("SHA-256").digest(rendition.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            return keyPrefix(songId, quality) + hash
        }
    }
}
