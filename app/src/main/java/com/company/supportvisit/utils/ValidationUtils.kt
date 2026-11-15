package com.company.supportvisit.utils

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

enum class VisitValidation {
    VALID,
    MISSING_FIELDS,
    INVALID_EMAIL,
    END_BEFORE_START,
    MISSING_SIGNATURE
}

object ValidationUtils {

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // Pure-Kotlin equivalent of android.util.Patterns.EMAIL_ADDRESS so it can be unit tested.
    private val EMAIL_REGEX = Regex(
        """[a-zA-Z0-9+._%\-]{1,256}@[a-zA-Z0-9][a-zA-Z0-9\-]{0,64}(\.[a-zA-Z0-9][a-zA-Z0-9\-]{0,25})+"""
    )

    fun isValidEmail(email: String): Boolean = EMAIL_REGEX.matches(email.trim())

    /** True when both times parse as HH:mm and end is strictly after start. */
    fun isEndAfterStart(start: String, end: String): Boolean =
        try {
            LocalTime.parse(end, TIME_FORMAT).isAfter(LocalTime.parse(start, TIME_FORMAT))
        } catch (e: DateTimeParseException) {
            false
        }

    fun validateVisit(
        client: String,
        email: String,
        startTime: String,
        endTime: String,
        hasSignature: Boolean
    ): VisitValidation = when {
        client.isBlank() || email.isBlank() || startTime.isBlank() || endTime.isBlank() ->
            VisitValidation.MISSING_FIELDS
        !isValidEmail(email) -> VisitValidation.INVALID_EMAIL
        !isEndAfterStart(startTime, endTime) -> VisitValidation.END_BEFORE_START
        !hasSignature -> VisitValidation.MISSING_SIGNATURE
        else -> VisitValidation.VALID
    }
}
