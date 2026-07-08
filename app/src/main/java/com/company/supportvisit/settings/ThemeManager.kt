package com.company.supportvisit.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {

    fun setLightMode() {

        AppCompatDelegate.setDefaultNightMode(
            AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    fun setDarkMode() {

        AppCompatDelegate.setDefaultNightMode(
            AppCompatDelegate.MODE_NIGHT_YES
        )
    }

    fun setSystemMode() {

        AppCompatDelegate.setDefaultNightMode(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )
    }
}