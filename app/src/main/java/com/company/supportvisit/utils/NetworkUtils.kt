package com.company.supportvisit.utils

import android.content.Context
import android.net.ConnectivityManager

object NetworkUtils {

    fun isOnline(
        context: Context
    ): Boolean {

        val manager =
            context.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        val network =
            manager.activeNetwork

        return network != null
    }
}