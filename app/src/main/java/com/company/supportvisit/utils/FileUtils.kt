package com.company.supportvisit.utils

import android.content.Context
import java.io.File

object FileUtils {

    fun getExcelFile(
        context: Context
    ): File {

        return File(
            context.filesDir,
            "SupportVisits.xlsx"
        )
    }

    fun getCsvFile(
        context: Context
    ): File {

        return File(
            context.filesDir,
            "SupportVisits.csv"
        )
    }
}