package com.yeemusic.android.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.yeemusic.android.api.ApiException
import com.yeemusic.android.api.MusicApi
import com.yeemusic.android.log.AppLogger
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import okio.source

/**
 * Uploads local audio files into the user's NetEase cloud drive.
 *
 * The transfer is deliberately *not* proxied through the API node: the node
 * hands out a NOS object-storage URL and the bytes go there directly, which is
 * the only thing that keeps a 40 MB FLAC inside the node's request-body limit.
 * The NOS host is plain HTTP, so the cleartext policy has to permit it (see
 * `-PmediaHosts` in app/build.gradle.kts).
 *
 * Everything is a [Flow] because an upload is long-running and cancelable — a
 * suspend function that only returned at the end could neither report progress
 * nor be abandoned mid-file.
 */
class CloudDrive(
    private val context: Context,
    private val api: MusicApi,
) {

    /** One queued file. Identity is the [Uri]; [name] is display-only. */
    data class Upload(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long,
    )

    /** What the UI renders for one upload. */
    sealed interface UploadState {
        data class Hashing(val upload: Upload) : UploadState
        data class Uploading(val upload: Upload, val uploadedBytes: Long) : UploadState

        data class Done(val upload: Upload) : UploadState
        data class Failed(val upload: Upload, val message: String) : UploadState

        /** The user cancelled it; the row is dropped rather than shown as failed. */
        data class Cancelled(val upload: Upload) : UploadState
    }

    /**
     * Serialises uploads the way the desktop client does: one at a time, so a
     * multi-file pick does not split the uplink and make every file crawl.
     */
    private val sequential = Semaphore(1)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // The read timeout only bounds the gap between bytes, so it can be
        // generous without risking a stuck-socket leak.
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Reads a document's display name and size without opening the stream. */
    suspend fun describe(uri: Uri): Upload? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name: String? = null
        var size = 0L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment?.substringAfterLast('/')
        if (name.isNullOrBlank()) return@withContext null
        Upload(uri = uri, name = name!!, sizeBytes = if (size > 0) size else 0L)
    }

    /**
     * Streams [upload] to the drive.
     *
     * Progress is reported from inside the socket write, so the flow has to be a
     * [channelFlow]: `RequestBody.writeTo` runs on OkHttp's own thread and is not
     * a suspend function, so there is no `emit` available there.
     *
     * Cancelling the collector's job cancels this flow, which closes the channel
     * and drops the in-flight request — that is what the ✕ on a row does.
     */
    fun upload(upload: Upload): Flow<UploadState> = channelFlow {
        send(UploadState.Hashing(upload))
        sequential.acquire()
        try {
            val md5 = md5Of(upload)
            // The node's `Content-MD5` header wants base64, the API wants hex.
            // Both are derived once so the two can never disagree.
            val md5Hex = md5.joinToString("") { "%02x".format(it) }
            val md5Base64 = android.util.Base64.encodeToString(md5, android.util.Base64.NO_WRAP)

            val token = api.cloudUploadToken(
                md5 = md5Hex,
                fileSize = upload.sizeBytes,
                filename = upload.name,
            )

            // needUpload=false is the "秒传" fast path: the bytes are already in
            // the drive, so skipping the transfer is correct, not a bug.
            if (token.needUpload) {
                val body = nosBody(upload) { sent ->
                    // Called from OkHttp's writer thread. offer() is
                    // thread-safe and conflates rather than blocking.
                    trySend(UploadState.Uploading(upload, sent))
                }
                val request = Request.Builder()
                    .url(token.uploadUrl)
                    .header("x-nos-token", token.uploadToken)
                    .header("Content-MD5", md5Base64)
                    .post(body)
                    .build()

                withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw ApiException("上传到云存储失败：HTTP ${response.code}")
                        }
                    }
                }
            }

            api.cloudUploadComplete(
                songId = token.songId,
                resourceId = token.resourceId,
                md5 = md5Hex,
                filename = upload.name,
            )
            send(UploadState.Done(upload))
        } catch (e: ApiException) {
            send(UploadState.Failed(upload, e.message ?: "上传失败"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            send(UploadState.Cancelled(upload))
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "cloud upload failed for ${upload.name}", e)
            send(UploadState.Failed(upload, e.message ?: "上传失败"))
        } finally {
            sequential.release()
        }
    }

    /**
     * Reports bytes as they leave, throttled to roughly four updates a second.
     *
     * Progress rides a [ForwardingSink] rather than the read loop: the write
     * side is what tracks the socket, and it stays correct even when OkHttp
     * retries a request internally.
     */
    private fun nosBody(upload: Upload, onProgress: (Long) -> Unit): RequestBody =
        object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
            override fun contentLength() = upload.sizeBytes

            override fun writeTo(sink: BufferedSink) {
                var sent = 0L
                var lastReport = 0L
                val counting = object : ForwardingSink(sink) {
                    override fun write(source: Buffer, byteCount: Long) {
                        super.write(source, byteCount)
                        sent += byteCount
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 250) {
                            lastReport = now
                            onProgress(sent)
                        }
                    }
                }
                counting.buffer().use { buffered ->
                    context.contentResolver.openInputStream(upload.uri)?.use { input ->
                        input.source().use { source ->
                            buffered.writeAll(source)
                        }
                    } ?: throw IOException("无法读取所选文件")
                }
                onProgress(sent)
            }
        }

    /**
     * MD5 of the file, streamed through a 4 MB window.
     *
     * Reading the whole file into a `ByteArray` would put a 40 MB FLAC plus a
     * copy of it on the heap at once — the difference between "uploads" and
     * "OOMs on a mid-range device".
     */
    private suspend fun md5Of(upload: Upload): ByteArray = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("MD5")
        context.contentResolver.openInputStream(upload.uri)?.use { input ->
            val buf = ByteArray(4 * 1024 * 1024)
            var read = input.read(buf)
            while (read != -1) {
                digest.update(buf, 0, read)
                read = input.read(buf)
            }
        } ?: throw IOException("无法读取所选文件")
        digest.digest()
    }

    companion object {
        private const val TAG = "CloudDrive"
    }
}
