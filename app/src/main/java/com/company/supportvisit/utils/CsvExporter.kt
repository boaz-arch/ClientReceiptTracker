package com.company.supportvisit.utils

import android.content.Context
import com.company.supportvisit.model.Visit
import java.io.File

object CsvExporter {

    fun exportVisits(
        context: Context,
        visits: List<Visit>
    ): File {

        val file =
            File(
                context.filesDir,
                "SupportVisits.csv"
            )

        val csv =
            StringBuilder()

        csv.append(
            "Client,Date,Start Time,End Time,Email,Description,PDF Link\n"
        )

        visits.forEach { visit ->

            csv.append(
                "\"${visit.clientName}\","
            )

            csv.append(
                "\"${visit.visitDate}\","
            )

            csv.append(
                "\"${visit.startTime}\","
            )

            csv.append(
                "\"${visit.endTime}\","
            )

            csv.append(
                "\"${visit.clientEmail}\","
            )

            csv.append(
                "\"${visit.workDescription}\","
            )

            csv.append(
                "\"${visit.pdfLink}\"\n"
            )
        }

        file.writeText(
            csv.toString()
        )

        return file
    }
}