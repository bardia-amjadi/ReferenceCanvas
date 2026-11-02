package app.referencecanvas.core.util

import android.content.Context
import android.os.Build
import app.referencecanvas.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A small on-device event log for troubleshooting. It records what happened (event codes, counts, exception types
 * and stack frames), never what the user stores: no file names, titles, tags, paths, URLs or file contents.
 * It stays on the device until the user chooses to share it.
 */
@Singleton
class DiagnosticsLog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {
    private val file = File(context.filesDir, "diagnostics.log")
    private val lock = Any()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** [message] must be non-sensitive: codes and numbers only. */
    fun record(category: String, message: String) {
        val line = "${stamp.format(Date(clock.now()))} [$category] $message\n"
        synchronized(lock) {
            try {
                if (file.length() > MAX_BYTES) trim()
                file.appendText(line)
            } catch (_: IOException) {
                // Diagnostics must never break the feature being diagnosed.
            }
        }
    }

    /** Records uncaught exceptions as type + top stack frames (no message text, which could contain user data). */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val frames = error.stackTrace.take(8).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            record("crash", "${error.javaClass.name} in ${thread.name}: $frames")
            previous?.uncaughtException(thread, error)
        }
    }

    /** A shareable text file: environment summary plus the log. Returns null if it can't be written. */
    suspend fun export(): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "capture").apply { mkdirs() }
            val out = File(dir, "diagnostics-${clock.now()}.txt")
            val body = synchronized(lock) { if (file.exists()) file.readText() else "" }
            out.writeText(
                buildString {
                    appendLine("Reference Canvas ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("No library content is included in this log.")
                    appendLine()
                    append(body.ifEmpty { "(no events recorded)\n" })
                },
            )
            out
        } catch (_: IOException) {
            null
        }
    }

    private fun trim() {
        val lines = file.readLines()
        file.writeText(lines.takeLast(lines.size / 2).joinToString("\n", postfix = "\n"))
    }

    private companion object {
        const val MAX_BYTES = 64 * 1024L
    }
}
