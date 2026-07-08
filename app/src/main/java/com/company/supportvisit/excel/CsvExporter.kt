package com.company.supportvisit.excel

import android.content.Context
import com.company.supportvisit.repository.VisitCache
import java.io.File

object CsvExporter {

    fun export(
        context: Context
    ) {

        val file =
            File(
                context.filesDir,
                "SupportVisits.csv"
            )

        val builder =
            StringBuilder()

        builder.append(
            "VisitID,Client,Date,Start,End,Email,Description\n"
        )

        VisitCache.visits.forEach {

            builder.append(
                "${it.visitId},"
            )

            builder.append(
                "${it.clientName},"
            )

            builder.append(
                "${it.visitDate},"
            )

            builder.append(
                "${it.startTime},"
            )

            builder.append(
                "${it.endTime},"
            )

            builder.append(
                "${it.clientEmail},"
            )

            builder.append(
                "\"${it.workDescription}\"\n"
            )
        }

        file.writeText(
            builder.toString()
        )
    }
}