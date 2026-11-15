package com.company.supportvisit.utils

import com.company.supportvisit.data.VisitEntity
import java.io.File

object CsvExporter {

    private val UTF8_BOM = 0xFEFF.toChar().toString()

    private val HEADER = listOf(
        "Client", "Date", "Start Time", "End Time", "Email", "Description", "PDF Link", "Sync Status"
    )

    /** RFC 4180: wrap every field in quotes and double any embedded quotes. */
    fun escape(value: String?): String = "\"" + (value ?: "").replace("\"", "\"\"") + "\""

    fun toCsv(visits: List<VisitEntity>): String = buildString {
        append(HEADER.joinToString(",") { escape(it) }).append("\r\n")
        visits.forEach { v ->
            append(
                listOf(
                    v.clientName, v.visitDate, v.startTime, v.endTime, v.clientEmail,
                    v.workDescription, v.pdfLink, v.syncStatus.name
                ).joinToString(",") { escape(it) }
            ).append("\r\n")
        }
    }

    fun writeTo(file: File, visits: List<VisitEntity>): File {
        file.parentFile?.mkdirs()
        // UTF-8 BOM so Excel opens Hebrew text correctly.
        file.writeText(UTF8_BOM + toCsv(visits), Charsets.UTF_8)
        return file
    }
}
