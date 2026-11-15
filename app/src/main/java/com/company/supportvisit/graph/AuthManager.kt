package com.company.supportvisit.graph

import android.content.Context

/**
 * Optional local login, only used when AppConfig.LOGIN_ENABLED is true.
 * Scheduled for removal in Phase 5 (Microsoft sign-in becomes the only login).
 */
object AuthManager {

    private const val PREFS = "auth_prefs"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val DEFAULT_CREDENTIAL = "admin"

    private var loggedIn = false

    fun initialize(context: Context) {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_USERNAME)) {
            prefs.edit()
                .putString(KEY_USERNAME, DEFAULT_CREDENTIAL)
                .putString(KEY_PASSWORD, DEFAULT_CREDENTIAL)
                .apply()
        }
    }

    fun login(context: Context, username: String, password: String): Boolean {
        val prefs = prefs(context)
        loggedIn = username == prefs.getString(KEY_USERNAME, DEFAULT_CREDENTIAL) &&
            password == prefs.getString(KEY_PASSWORD, DEFAULT_CREDENTIAL)
        return loggedIn
    }

    fun logout() {
        loggedIn = false
    }

    fun isLoggedIn(): Boolean = loggedIn

    fun changeLoginInfo(context: Context, username: String, password: String) {
        prefs(context).edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
