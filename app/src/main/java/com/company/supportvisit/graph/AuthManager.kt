package com.company.supportvisit.graph

import android.content.Context

object AuthManager {

    private const val PREFS =
        "auth_prefs"

    private const val KEY_USERNAME =
        "username"

    private const val KEY_PASSWORD =
        "password"

    private var loggedIn = false

    fun initialize(
        context: Context
    ) {

        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

        if (
            !prefs.contains(KEY_USERNAME)
        ) {

            prefs.edit()

                .putString(
                    KEY_USERNAME,
                    "admin"
                )

                .putString(
                    KEY_PASSWORD,
                    "admin"
                )

                .apply()
        }
    }

    fun login(
        context: Context,
        username: String,
        password: String
    ): Boolean {

        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

        val savedUser =
            prefs.getString(
                KEY_USERNAME,
                "admin"
            )

        val savedPass =
            prefs.getString(
                KEY_PASSWORD,
                "admin"
            )

        if (
            username == savedUser &&
            password == savedPass
        ) {

            loggedIn = true

            return true
        }

        return false
    }

    fun logout() {

        loggedIn = false
    }

    fun isLoggedIn(): Boolean {

        return loggedIn
    }

    fun getUserName(
        context: Context
    ): String {

        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

        return prefs.getString(
            KEY_USERNAME,
            "admin"
        ) ?: "admin"
    }

    fun changeLoginInfo(

        context: Context,

        username: String,

        password: String

    ) {

        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

        prefs.edit()

            .putString(
                KEY_USERNAME,
                username
            )

            .putString(
                KEY_PASSWORD,
                password
            )

            .apply()
    }

    fun getUserEmail(): String {

        return "boaz@livnelavan.com"
    }

    fun getToken(): String {

        return ""
    }
}