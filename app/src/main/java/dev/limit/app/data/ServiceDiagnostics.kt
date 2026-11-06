package dev.limit.app.data

import android.content.Context
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import java.io.File
import java.time.Instant
import org.json.JSONArray

/** Small, private, best-effort failure history. Never records exception messages or app usage. */
object ServiceDiagnostics {
    internal data class ExitSummary(val time: Long, val reason: Int, val status: Int)

    /** Own-process history only. No trace streams, descriptions, other apps or error messages. */
    fun report(context: Context): String = report(context) {
        if (Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 3)
                .map { ExitSummary(it.timestamp, it.reason, it.status) }
        } else emptyList()
    }

    internal fun report(context: Context, history: () -> List<ExitSummary>): String = buildString {
        append("LIMIT diagnostics\nAndroid API: ").append(Build.VERSION.SDK_INT).append('\n')
        append("Report time: ").append(Instant.now()).append('\n')
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty().replace(safeToken, "_").take(40)
        append("Installed app version: ").append(version).append('\n')
        append("Local errors:\n").append(read(context).ifBlank { "No local error recorded." })
        append("\nAndroid process exit history (may predate this app update):\n")
        if (Build.VERSION.SDK_INT < 30) append("Requires Android 11 or newer.\n")
        else try {
            val exits = history().sortedByDescending { it.time }.take(3)
            if (exits.isEmpty()) append("No exit reason supplied by Android.\n")
            exits.forEach {
                val reason = when (it.reason) {
                    ApplicationExitInfo.REASON_ANR -> "ANR"
                    ApplicationExitInfo.REASON_CRASH -> "CRASH"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                    ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                    ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                    else -> "OTHER_OR_UNKNOWN"
                }
                append(Instant.ofEpochMilli(it.time)).append(" reason=").append(reason)
                append(" code=").append(it.reason).append(" status=").append(it.status).append('\n')
            }
        } catch (_: Exception) { append("Android exit history unavailable.\n") }
        append("An absent record does not rule out termination. No automatic upload.\n")
    }

    internal const val FILE_NAME = "service-diagnostics.json"
    private const val MAX_BYTES = 16 * 1024
    private const val MAX_RECORDS = 3
    private val safeToken = Regex("[^A-Za-z0-9_.$:-]")
    private val allowedFrames = listOf("dev.limit.app.", "android.", "androidx.", "java.", "kotlin.", "kotlinx.")

    @Synchronized
    fun record(context: Context, phase: String, error: Throwable) {
        try {
            val appContext = context.applicationContext
            val version = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
                .orEmpty().replace(safeToken, "_").take(40)
            val entry = buildString {
                append("Time: ").append(Instant.now()).append('\n')
                append("Phase: ").append(phase.replace(safeToken, "_").take(64)).append('\n')
                append("Android API: ").append(Build.VERSION.SDK_INT).append('\n')
                append("Android release: ").append(Build.VERSION.RELEASE.replace(safeToken, "_").take(40)).append('\n')
                append("App version: ").append(version).append('\n')
                // Throwable.toString(), message and filenames can contain private information.
                append("Exception: ").append(error.javaClass.name.replace(safeToken, "_").take(160)).append('\n')
                error.stackTrace.asSequence().filter { frame ->
                    allowedFrames.any { frame.className.startsWith(it) }
                }.take(16).forEach { frame ->
                    append("at ").append(frame.className.replace(safeToken, "_").take(120))
                    append('.').append(frame.methodName.replace(safeToken, "_").take(64))
                    append(':').append(frame.lineNumber).append('\n')
                }
            }
            val file = File(appContext.filesDir, FILE_NAME)
            val previous = try { load(file) } catch (_: Exception) { emptyList() }
            val entries = previous.takeLast(MAX_RECORDS - 1) + entry
            var retained = entries
            var encoded = encode(retained)
            while (encoded.toByteArray(Charsets.UTF_8).size > MAX_BYTES && retained.size > 1) {
                retained = retained.drop(1)
                encoded = encode(retained)
            }
            val temporary = File(appContext.filesDir, "$FILE_NAME.tmp")
            temporary.writeText(encoded, Charsets.UTF_8)
            if (!temporary.renameTo(file)) temporary.delete()
        } catch (_: Exception) {
            // Diagnostics must never prevent recovery from the original service failure.
        }
    }

    @Synchronized
    fun read(context: Context): String = try {
        load(File(context.applicationContext.filesDir, FILE_NAME)).joinToString("\n---\n")
    } catch (_: Exception) {
        ""
    }

    private fun load(file: File): List<String> {
        if (!file.exists() || file.length() > MAX_BYTES) return emptyList()
        val entries = JSONArray(file.readText(Charsets.UTF_8))
        return (maxOf(0, entries.length() - MAX_RECORDS) until entries.length()).map { entries.getString(it) }
    }

    private fun encode(entries: List<String>): String = JSONArray().apply { entries.forEach { put(it) } }.toString()
}
