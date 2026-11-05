package com.yeemusic.android.player

import android.media.MediaDataSource
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** MediaPlayer's random-access reader backed by the same cache as ExoPlayer. */
internal class CacheMediaDataSource(
    private val stream: CachedStream,
    private val factory: DataSource.Factory,
) : MediaDataSource() {
    private var source: DataSource? = null
    private var position = 0L
    private var length = stream.length
    private var closed = false

    @Synchronized
    override fun getSize(): Long {
        checkOpen()
        if (length == C.LENGTH_UNSET.toLong()) openAt(0L)
        return length
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        checkOpen()
        if (position < 0 || offset < 0 || size < 0 || offset > buffer.size - size) {
            throw IOException("Invalid media read range")
        }
        if (size == 0) return 0
        if (length >= 0 && position >= length) return -1
        if (source == null || this.position != position) openAt(position)
        val count = if (length >= 0) minOf(size.toLong(), length - position).toInt() else size
        val read = checkNotNull(source).read(buffer, offset, count)
        if (read > 0) this.position += read
        return read
    }

    private fun openAt(position: Long) {
        closeSource()
        val next = factory.createDataSource()
        try {
            val spec = DataSpec.Builder()
                .setUri(stream.url)
                .setKey(stream.key)
                .setPosition(position)
                .apply { if (length > position) setLength(length - position) }
                .build()
            val remaining = next.open(spec)
            if (remaining != C.LENGTH_UNSET.toLong()) length = position + remaining
            source = next
            this.position = position
        } catch (e: Exception) {
            runCatching { next.close() }
            throw IOException("Cannot open cached audio at $position", e)
        }
    }

    private fun checkOpen() {
        if (closed) throw IOException("Media data source is closed")
    }

    private fun closeSource() {
        val previous = source
        source = null
        previous?.close()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        closeSource()
    }
}
