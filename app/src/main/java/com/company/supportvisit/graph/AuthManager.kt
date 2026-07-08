package com.company.supportvisit.graph

import android.app.Activity

object AuthManager {

    private var accessToken = ""

    fun getToken(): String {

        return accessToken
    }

    fun signIn(
        activity: Activity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {

        accessToken = ""

        onSuccess()
    }
}