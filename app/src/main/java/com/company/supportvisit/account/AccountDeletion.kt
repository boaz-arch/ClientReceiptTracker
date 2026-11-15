package com.company.supportvisit.account

import android.content.Context
import androidx.work.WorkManager
import com.company.supportvisit.app
import com.company.supportvisit.graph.MicrosoftAuthManager
import com.company.supportvisit.remote.ServerApi
import com.company.supportvisit.settings.ServerSettings
import com.company.supportvisit.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** A scheduled account deletion on the server. */
data class DeletionStatus(val pending: Boolean, val purgeAt: Long?)

class UnsentVisitsException : Exception("Visits not sent yet")

/**
 * Delete account: the server keeps everything for 14 days (restorable by signing in again),
 * then wipes the user's visit reports, PDFs and photos everywhere (other phones remove their
 * copies through the sync). With keepShared, the reports stay in the company's records.
 * This phone's data is wiped immediately.
 */
object AccountDeletion {

    private const val PREFS = "account_deletion"
    private const val KEY_PURGE_AT = "scheduled_purge_at"

    /** Schedules deletion on the server, then wipes this phone and signs out. */
    suspend fun request(context: Context, keepShared: Boolean): Long? {
        // Unsent visits would be lost: they exist only on this phone.
        if (context.app.visitRepository.observeUnsentCount().first() > 0) throw UnsentVisitsException()

        var purgeAt: Long? = null
        ServerSettings.serverUrl(context)?.let { url ->
            val api = ServerApi(url, MicrosoftAuthManager.getApiToken())
            purgeAt = withContext(Dispatchers.IO) { api.requestDeletion(keepShared) }
        }
        wipeLocalData(context)
        runCatching { MicrosoftAuthManager.signOut() }
        // Remembered (outside the wiped data) only to tell the user the date on the next start.
        purgeAt?.let { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_PURGE_AT, it).apply() }
        return purgeAt
    }

    suspend fun status(context: Context): DeletionStatus? {
        val url = ServerSettings.serverUrl(context) ?: return null
        val api = ServerApi(url, MicrosoftAuthManager.getApiToken())
        return withContext(Dispatchers.IO) { api.deletionStatus() }
    }

    /** Cancels the deletion and downloads the account's data again. */
    suspend fun restore(context: Context) {
        val url = ServerSettings.serverUrl(context) ?: return
        val api = ServerApi(url, MicrosoftAuthManager.getApiToken())
        withContext(Dispatchers.IO) { api.cancelDeletion() }
        ServerSettings.setVisitsCursor(context, 0)
        ServerSettings.setClientsCursor(context, 0)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        SyncScheduler.enqueue(context)
    }

    /** Removes everything the app stored on this phone (database, files, settings, keys). */
    suspend fun wipeLocalData(context: Context) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelAllWork()
        context.app.database.clearAllTables()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles()
            ?.filter { !it.name.startsWith("com.microsoft.identity") && !it.name.startsWith("WorkManager") }
            ?.forEach { prefsFile ->
                context.getSharedPreferences(prefsFile.nameWithoutExtension, Context.MODE_PRIVATE).edit().clear().commit()
            }
    }
}
