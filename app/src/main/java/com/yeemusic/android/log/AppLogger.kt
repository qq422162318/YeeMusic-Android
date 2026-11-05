package com.yeemusic.android.log

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Every log line the app produces, in one place: logcat, and a file under
 * `Android/data/com.yeemusic.android/files/logs/`.
 *
 * The file matters more than logcat here. On MIUI the shell cannot read this
 * app's logs (SELinux per-UID log filtering), so `adb logcat` is a dead end,
 * and the app-scoped external dir is writable without any runtime permission —
 * that is the closest thing this setup has to a working console.
 *
 * Lines go through a single daemon thread, so a chatty screen cannot stall the
 * UI thread. [flush] writes whatever is queued straight away, which is what the
 * uncaught-exception handler relies on before the process is gone.
 */
object AppLogger {

    private const val LOG_DIR = "logs"
    private const val FILE_NAME = "app.log"

    /** Rotation budget. Older lines are dropped rather than filling the device. */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private const val TAG = "YeeMusic"

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val queue = ConcurrentLinkedQueue<String>()

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null

    /** Where the running log lives, for showing the user. */
    val path: String? get() = logFile?.absolutePath

    /**
     * Install this into a [kotlinx.coroutines.CoroutineScope] so an uncaught
     * coroutine failure is recorded instead of taking the process down with it.
     */
    val handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        record(Log.ERROR, TAG, "unhandled coroutine exception", throwable)
    }

    /**
     * Where the running log lives, for showing the user.
     *
     * [TEMP_DIAG] points at `/sdcard/Download/yeemusic-diag.log` instead of the
     * app-scoped dir: on Android 11+ Termux cannot traverse `/sdcard/Android/data`
     * (scoped storage), so the app's own log file is unreadable from the shell
     * and the only diagnostic channel is this world-readable path. Revert to
     * `getExternalFilesDir(null)` once the FlymeOS launcher-theme bug is resolved.
     */
    private val tempDiagFile: File?
        get() = java.io.File("/sdcard/Download/yeemusic-diag.log")

    /** Wires up the file sink and the crash recorder. Idempotent. */
    fun init(context: Context) {
        if (logFile == null) {
            val temp = tempDiagFile
            val dir = if (temp != null && (temp.parentFile?.canWrite() == true)) {
                temp.parentFile!!
            } else {
                (context.getExternalFilesDir(null) ?: context.filesDir).resolve(LOG_DIR).apply { mkdirs() }
            }
            logFile = if (temp != null && dir == temp.parentFile) temp else dir.resolve(FILE_NAME)
            write(
                "=== YeeMusic log, new session ===" +
                    " app v${com.yeemusic.android.BuildConfig.VERSION_NAME}" +
                    " sdk ${Build.VERSION.SDK_INT}" +
                    " build ${Build.DISPLAY}",
            )
            write("api        ${com.yeemusic.android.BuildConfig.DEFAULT_API_BASE_URL}")
            write("cleartext  ${com.yeemusic.android.BuildConfig.CLEARTEXT_HOSTS}")
            write(
                "device     ${Build.MANUFACTURER} ${Build.MODEL} " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            )
            write("file       ${logFile?.absolutePath ?: "<unknown>"}")
        }
        ensureWorker()
        installCrashHandler()
    }

    fun d(tag: String, message: String, throwable: Throwable? = null) =
        record(Log.DEBUG, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) =
        record(Log.INFO, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        record(Log.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        record(Log.ERROR, tag, message, throwable)

    /** An untagged event line: what the player observed about a stream. */
    fun note(message: String) = record(Log.INFO, TAG, message, null)

    fun record(level: Int, tag: String, message: String, throwable: Throwable?) {
        Log.println(level, tag, message + (throwable?.message?.let { " ($it)" } ?: ""))
        val line = buildString {
            append(timeFormat.format(Date()))
            append("  ").append(levelName(level)).append(" ").append(tag).append("  ").append(message)
            throwable?.let {
                append("  (").append(it.javaClass.name).append(": ")
                append((it.message ?: "<null>").take(300)).append(")")
                it.stackTrace.take(6).forEach { frame -> append("    ").append(frame.toString().take(200)) }
            }
        }
        queue.add(line)
        ensureWorker()
    }

    /** Writes the queue on this thread. The crash handler needs it before death. */
    fun flush() {
        synchronized(this) {
            worker = null
        }
        while (true) {
            val line = queue.poll() ?: break
            val target = logFile ?: break
            writeLine(line, target)
        }
    }

    private fun ensureWorker() {
        synchronized(this) {
            if (worker?.isAlive == true) return
            worker = Thread({
                while (true) {
                    val line = queue.poll() ?: break
                    val target = logFile ?: break
                    writeLine(line, target)
                }
                synchronized(this) { worker = null }
            }, "AppLogger").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun write(line: String) {
        queue.add(line)
        ensureWorker()
    }

    private fun writeLine(line: String, target: File) {
        try {
            if (target.length() > MAX_FILE_BYTES) {
                // Rotate in place: the file keeps its name, the oldest lines go.
                target.writeBytes(ByteArray(0))
                queue.add("=== rotated, content older than ${MAX_FILE_BYTES / (1024 * 1024)} MB discarded ===")
            }
            FileOutputStream(target, true).use { out ->
                out.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
            }
        } catch (e: IOException) {
            // Storage is gone; logcat still has the line. Do not throw from here.
        }
    }

    private fun levelName(level: Int): String = when (level) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        else -> "?"
    }

    /** Records the fatal exception, then hands over to whoever was there before. */
    private fun installCrashHandler() {
        if (previousCrashHandler != null) return
        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record(Log.ERROR, TAG, "uncaught exception on ${thread.name}", throwable)
            flush()
            val previous = previousCrashHandler
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // Nothing above us: die the way Android normally would.
                throw throwable
            }
        }
    }
}
