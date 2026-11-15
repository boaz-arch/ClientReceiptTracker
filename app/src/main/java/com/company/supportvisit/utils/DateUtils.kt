package com.company.supportvisit.utils

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

object DateUtils {

    private val LEGACY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** Today as ISO yyyy-MM-dd, the format stored in the database. */
    fun todayIso(): String = LocalDate.now().toString()

    /** Formats an ISO date for display in the user's locale; returns the input if unparsable. */
    fun formatForDisplay(isoDate: String, locale: Locale = Locale.getDefault()): String =
        try {
            LocalDate.parse(isoDate)
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        } catch (e: DateTimeParseException) {
            isoDate
        }

    /** Converts the old dd/MM/yyyy format to ISO. Already-ISO or unparsable input is returned as is. */
    fun legacyToIso(date: String): String =
        try {
            LocalDate.parse(date.trim(), LEGACY_FORMAT).toString()
        } catch (e: DateTimeParseException) {
            date
        }
}
