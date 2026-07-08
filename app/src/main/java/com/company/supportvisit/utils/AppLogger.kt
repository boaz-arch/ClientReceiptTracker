package com.company.supportvisit.utils

import android.util.Log

object AppLogger {

    private const val TAG =
        "SupportVisit"

    fun info(
        message: String
    ) {

        Log.i(
            TAG,
            message
        )
    }

    fun error(
        message: String
    ) {

        Log.e(
            TAG,
            message
        )
    }
}