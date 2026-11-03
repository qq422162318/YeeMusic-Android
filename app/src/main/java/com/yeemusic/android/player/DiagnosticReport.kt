package com.yeemusic.android.player

import android.os.Build
import com.yeemusic.android.log.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Persists what the UI cannot fit: the full error chain and whatever was observed
 * about the stream, in a file the user can open from a file manager.
 *
 * On MIUI the shell cannot read this app's logs (SELinux per-UID log filtering),
 * so `adb logcat` is a dead end and a screen string is the only feedback channel.
 * `externalFilesDir` is app-scoped storage, needs no runtime permission, and is
 * visible in `Android/data/com.yeemusic.android/files/`, so a log file there is
 * the closest thing to a working console this setup allows.
 */
object DiagnosticReport {

    private const val TAG = "DiagnosticReport"
    private const val FILE_NAME = "playback_diag.txt"

    private val notes = ConcurrentLinkedQueue<String>()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var last: File? = null

    /** One line worth keeping between failures; also lands in the running log. */
    fun note(message: String) {
        notes.add(timeFormat.format(Date()) + "  " + message)
        AppLogger.note(message)
    }

    /**
     * Appends the latest failure to the report and returns the file written.
     * Returns null only when the filesystem is genuinely unavailable, in which
     * case the caller has to fall back to the on-screen string alone.
     */
    fun dump(context: android.content.Context, summary: String, error: Throwable): File? {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = root.resolve("diagnostics").apply { mkdirs() }
        val file = dir.resolve(FILE_NAME)
        last = file
        val header = StringBuilder()
        header.appendLine("YeeMusic playback diagnostics")
        header.appendLine("written    ${timeFormat.format(Date())}")
        header.appendLine("file       ${file.absolutePath}")
        header.appendLine("api        ${com.yeemusic.android.BuildConfig.DEFAULT_API_BASE_URL}")
        header.appendLine("cleartext  ${com.yeemusic.android.BuildConfig.CLEARTEXT_HOSTS}")
        header.appendLine(
            "device     ${Build.MANUFACTURER} ${Build.MODEL} " +
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) " +
                "build ${Build.DISPLAY}",
        )
        header.appendLine("app        v${com.yeemusic.android.BuildConfig.VERSION_NAME}")
        header.appendLine()
        header.appendLine("--- last failure ---")
        header.appendLine(summary)
        header.appendLine()
        appendChain(header, error)
        if (notes.size > 0) {
            header.appendLine("--- observations before the failure ---")
            notes.forEach { header.appendLine(it) }
            header.appendLine()
        }
        try {
            FileOutputStream(file, true).use { it.write(header.toString().toByteArray(StandardCharsets.UTF_8)) }
            return file
        } catch (e: IOException) {
            AppLogger.e(TAG, "diagnostics not writable", e)
            return null
        }
    }

    /** Class name plus message, one frame deep, so a messageless exception is still identifiable. */
    private fun appendChain(target: StringBuilder, root: Throwable) {
        var cause: Throwable? = root
        while (cause != null) {
            val depth = cause.stackTrace.size
            target.appendLine("cause    ${cause.javaClass.name}")
            target.appendLine("message  ${(cause.message ?: "<null>").take(512)}")
            target.appendLine("stack    ${cause.stackTrace.take(8).joinToString("\n          ") { it.toString().take(240) }}")
            if (depth == 0) target.appendLine("         <no stack frame: synthetic exception>")
            cause = cause.cause
        }
    }

    /** For logs the UI shows directly: how big the report has grown. */
    val lastPath: String? get() = last?.absolutePath
}
