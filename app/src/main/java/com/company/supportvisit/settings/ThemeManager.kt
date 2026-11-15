package com.company.supportvisit.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Persists the light/dark/system choice and applies it (called from the Application class). */
object ThemeManager {

    private const val PREFS = "app_settings"
    private const val KEY_THEME_MODE = "theme_mode"

    fun applySaved(context: Context) {
        AppCompatDelegate.setDefaultNightMode(savedMode(context))
    }

    fun setMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_THEME_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun savedMode(context: Context): Int =
        prefs(context).getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
