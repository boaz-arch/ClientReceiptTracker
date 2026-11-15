package com.company.supportvisit.settings

import android.content.Context
import androidx.core.content.edit
import com.company.supportvisit.BuildConfig

/**
 * Where the Support Visit server is, and how far this device has synced from it.
 * The address is built into the app (SUPPORT_SERVER_URL at build time); users never see it.
 */
object ServerSettings {

    private const val PREFS = "server"
    private const val KEY_VISITS_CURSOR = "visits_cursor"
    private const val KEY_CLIENTS_CURSOR = "clients_cursor"

    /** Server base URL (no trailing slash), or null when the build has none. */
    fun serverUrl(@Suppress("UNUSED_PARAMETER") context: Context): String? =
        BuildConfig.DEFAULT_SERVER_URL.trim().trimEnd('/').takeIf { it.isNotBlank() }

    fun visitsCursor(context: Context): Long = prefs(context).getLong(KEY_VISITS_CURSOR, 0L)

    fun setVisitsCursor(context: Context, value: Long) = prefs(context).edit { putLong(KEY_VISITS_CURSOR, value) }

    fun clientsCursor(context: Context): Long = prefs(context).getLong(KEY_CLIENTS_CURSOR, 0L)

    fun setClientsCursor(context: Context, value: Long) = prefs(context).edit { putLong(KEY_CLIENTS_CURSOR, value) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
