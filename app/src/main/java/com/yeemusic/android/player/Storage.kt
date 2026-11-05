package com.yeemusic.android.player

import android.content.Context
import com.yeemusic.android.api.MusicApi
import com.yeemusic.android.api.SongUrlData
import com.yeemusic.android.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

/** Prefer Android/data/<package>/cache without requesting storage permissions. */
internal fun Context.musicCacheDirectory(): File {
    val external = runCatching {
        externalCacheDir?.resolve("music_cache")?.takeIf { dir ->
            (dir.isDirectory || dir.mkdirs()) && dir.canWrite()
        }
    }.getOrNull()
    return external ?: cacheDir.resolve("music_cache").apply { mkdirs() }
}

/**
 * Downloads songs to app-private storage. No storage permission is needed for
 * that, which is why downloads live under filesDir rather than Downloads/.
 *
 * Files are named `<songId>.<ext>` so they are trivially locatable when the
 * user plays the song again.
 */
class DownloadManager(
    private val context: Context,
    private val api: MusicApi,
    private val settings: SettingsStore,
) {

    private val dir: File = context.filesDir.resolve("downloads").apply { mkdirs() }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class DownloadedTrack(
        val songId: Long,
        val file: File,
        val ext: String,
    ) {
        val sizeBytes: Long get() = file.length()
        val playableUri: String get() = file.toURI().toString()
    }

    /** All downloaded tracks still present on disk, newest first. */
    suspend fun list(): List<DownloadedTrack> = withContext(Dispatchers.IO) {
        dir.listFiles()
            ?.filter { it.isFile && it.extension.isNotBlank() && !it.name.endsWith(".tmp") }
            ?.mapNotNull { file ->
                val id = file.nameWithoutExtension.toLongOrNull() ?: return@mapNotNull null
                DownloadedTrack(id, file, file.extension)
            }
            ?.sortedByDescending { it.file.lastModified() }
            ?: emptyList()
    }

    fun hasFile(songId: Long): Boolean =
        dir.listFiles()?.any { it.isFile && it.nameWithoutExtension == songId.toString() && it.length() > 0 } == true

    fun findFile(songId: Long): File? =
        dir.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension == songId.toString() && it.length() > 0 }

    /**
     * Downloads [songId] to disk. Fails for VIP/lost songs whose URL the
     * backend will not issue.
     */
    suspend fun download(songId: Long, level: String? = null): DownloadedTrack =
        withContext(Dispatchers.IO) {
            val quality = level ?: settings.getQuality()
            // Same escalation the player uses: unlocked request first, then the
            // 解锁 mirror, and only then a lower bitrate. A 试听 fragment is
            // never what should land on disk.
            val data: SongUrlData? = api.songUrl(songId, quality, unblock = true)
                ?.takeUnless { it.freeTrial }
                ?: api.unblockedUrl(songId, quality)
                ?: api.songUrl(songId, "exhigh", unblock = true)?.takeUnless { it.freeTrial }
                ?: api.songUrl(songId, "standard")

            val url = data?.url
                ?: throw IOException("无法获取下载地址（可能是 VIP 或版权受限歌曲）")

            val ext = data.type?.takeIf { it.isNotBlank() } ?: "mp3"
            val target = File(dir, "$songId.$ext")
            if (target.exists() && target.length() > 0) {
                return@withContext DownloadedTrack(songId, target, ext)
            }

            val tmp = File(dir, "$songId.$ext.tmp")
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "YeeMusic-Android/1.0")
                    .get()
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("下载失败：HTTP ${response.code}")
                    }
                    val body: ResponseBody = response.body ?: throw IOException("下载失败：空响应")
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var n = input.read(buf)
                            while (n != -1) {
                                output.write(buf, 0, n)
                                n = input.read(buf)
                            }
                        }
                    }
                }

                if (tmp.length() == 0L) throw IOException("下载结果为空文件")
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                DownloadedTrack(songId, target, ext)
            } catch (e: Exception) {
                tmp.delete()
                throw IOException("下载失败：${e.message}", e)
            }
        }

    fun delete(track: DownloadedTrack): Boolean = track.file.delete()

    /** Size of downloaded songs in bytes. */
    suspend fun downloadSize(): Long = withContext(Dispatchers.IO) {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Size of the streaming cache in bytes. */
    suspend fun streamCacheSize(directory: File): Long = withContext(Dispatchers.IO) {
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun clearDownloads() {
        withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
    }

    /**
     * Enforce the user's download limit. Oldest files go first; 0 disables the
     * limit.
     */
    suspend fun trimToLimit() {
        withContext(Dispatchers.IO) {
            val limitMb = settings.cacheLimitMb.first()
            if (limitMb <= 0) return@withContext
            val limitBytes = limitMb * 1024L * 1024L
            val files = dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".tmp") }
                ?.sortedBy { it.lastModified() }
                ?: return@withContext
            var total = files.sumOf { it.length() }
            for (file in files) {
                if (total <= limitBytes) break
                total -= file.length()
                file.delete()
            }
        }
    }
}
