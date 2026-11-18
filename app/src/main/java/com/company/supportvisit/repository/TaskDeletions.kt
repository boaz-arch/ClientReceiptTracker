package com.company.supportvisit.repository

import android.content.Context
import androidx.core.content.edit

/**
 * Tasks deleted on this phone that the server hasn't heard about yet. The task leaves the phone
 * at once; the next sync tells the server, and until then the server's copy isn't pulled back.
 */
object TaskDeletions {

    private const val PREFS = "task_deletions"
    private const val KEY_IDS = "pending_ids"

    fun request(context: Context, id: String) =
        prefs(context).edit { putStringSet(KEY_IDS, pending(context) + id) }

    fun pending(context: Context): Set<String> = prefs(context).getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()

    fun done(context: Context, id: String) =
        prefs(context).edit { putStringSet(KEY_IDS, pending(context) - id) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
