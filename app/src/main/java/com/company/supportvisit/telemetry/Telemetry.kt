package com.company.supportvisit.telemetry

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.company.supportvisit.BuildConfig
import com.company.supportvisit.auth.AuthSession
import com.company.supportvisit.remote.ServerApi
import com.company.supportvisit.settings.ServerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Crash reports and anonymous usage counters, sent to the Support Visit server.
 *
 * Nothing personal is collected: crash reports hold the app version, Android version, phone
 * model and the stack trace with exception *types and code lines only* (exception messages are
 * dropped because they can contain client data). Usage events are bare names such as
 * "visit_submitted" that the server only counts per day. The user can turn both off in Settings.
 */
object Telemetry {

    private const val TAG = "Telemetry"
    private const val PREFS = "telemetry"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_EVENTS = "pending_events"
    private const val MAX_PENDING_EVENTS = 500
    private const val CRASH_DIR = "crash_reports"
    private const val MAX_CRASH_FILES = 10

    /** Event names (lowercase and underscores only; the server rejects anything else). */
    const val VISIT_SUBMITTED = "visit_submitted"
    const val MONTHLY_REPORT_GENERATED = "monthly_report_generated"
    const val INVOICE_CREATED = "invoice_created"
    const val SYNC_FAILED = "sync_failed"

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_ENABLED, enabled)
            if (!enabled) remove(KEY_EVENTS)
        }
        if (!enabled) crashDir(context).listFiles()?.forEach { it.delete() }
    }

    /** Saves a report for any crash; it is sent on the next start. Call once from Application.onCreate. */
    fun installCrashHandler(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { if (isEnabled(appContext)) saveCrash(appContext, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Counts one event (kept on the phone until [flush]). */
    fun track(context: Context, name: String) {
        if (!isEnabled(context) || !name.matches(EVENT_NAME)) return
        synchronized(this) {
            val pending = pendingEvents(context)
            if (pending.length() >= MAX_PENDING_EVENTS) return
            pending.put(name)
            prefs(context).edit { putString(KEY_EVENTS, pending.toString()) }
        }
    }

    /** Sends saved crash reports and event counts. Safe to call often; does nothing offline or signed out. */
    suspend fun flush(context: Context) = withContext(Dispatchers.IO) {
        if (!isEnabled(context) || !AuthSession.isSignedIn) return@withContext
        val url = ServerSettings.serverUrl(context) ?: return@withContext
        try {
            val api = ServerApi(url, AuthSession.getApiToken())

            crashDir(context).listFiles()?.sortedBy { it.name }?.forEach { file ->
                val report = runCatching { JSONObject(file.readText()) }.getOrNull()
                if (report != null) api.sendCrash(report)
                file.delete()
            }

            val names = synchronized(this) {
                val pending = pendingEvents(context)
                prefs(context).edit { remove(KEY_EVENTS) }
                (0 until pending.length()).map { pending.optString(it) }
            }
            if (names.isNotEmpty()) {
                try {
                    api.sendEvents(names)
                } catch (e: Exception) {
                    names.forEach { track(context, it) } // keep them for next time
                    throw e
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sending telemetry failed: ${e.javaClass.simpleName}")
        }
    }

    /** The crash report sent to the server: no exception messages, only types and code lines. */
    internal fun buildReport(error: Throwable, appVersion: String, android: String, device: String): JSONObject =
        JSONObject()
            .put("appVersion", appVersion)
            .put("android", android)
            .put("device", device)
            .put("stack", scrubbedStack(error))

    internal fun scrubbedStack(error: Throwable): String {
        val out = StringBuilder()
        var current: Throwable? = error
        val seen = HashSet<Throwable>()
        var depth = 0
        while (current != null && seen.add(current) && depth < 10) {
            if (depth > 0) out.append("Caused by: ")
            out.append(current.javaClass.name).append('\n')
            current.stackTrace.take(40).forEach { out.append("    at ").append(it.toString()).append('\n') }
            current = current.cause
            depth++
        }
        return out.toString().take(20_000)
    }

    private fun saveCrash(context: Context, error: Throwable) {
        val dir = crashDir(context).apply { mkdirs() }
        if ((dir.listFiles()?.size ?: 0) >= MAX_CRASH_FILES) return
        val report = buildReport(
            error,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
        File(dir, "crash_${System.currentTimeMillis()}.json").writeText(report.toString())
    }

    private fun pendingEvents(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString(KEY_EVENTS, "[]")) }.getOrDefault(JSONArray())

    private fun crashDir(context: Context) = File(context.filesDir, CRASH_DIR)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val EVENT_NAME = Regex("^[a-z_]{1,40}$")
}
