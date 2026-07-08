package com.company.supportvisit.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateUtils {

    fun currentDate(): String {

        return SimpleDateFormat(
            "dd/MM/yyyy",
            Locale.getDefault()
        ).format(Date())
    }

    fun currentTime(): String {

        return SimpleDateFormat(
            "HH:mm",
            Locale.getDefault()
        ).format(Date())
    }
}