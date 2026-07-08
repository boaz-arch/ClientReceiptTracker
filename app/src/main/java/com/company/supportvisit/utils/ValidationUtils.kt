package com.company.supportvisit.utils

import android.util.Patterns

object ValidationUtils {

    fun isValidVisit(

        client: String,

        email: String,

        startTime: String,

        endTime: String

    ): Boolean {

        if (client.isBlank()) {
            return false
        }

        if (email.isBlank()) {
            return false
        }

        if (
            !Patterns.EMAIL_ADDRESS
                .matcher(email)
                .matches()
        ) {
            return false
        }

        if (startTime.isBlank()) {
            return false
        }

        if (endTime.isBlank()) {
            return false
        }

        return true
    }
}