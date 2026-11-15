package com.company.supportvisit.utils

import com.company.supportvisit.data.SyncStatus
import com.company.supportvisit.data.VisitEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvExporterTest {

    @Test
    fun `escape doubles embedded quotes and wraps the field`() {
        assertEquals("\"He said \"\"hi\"\"\"", CsvExporter.escape("He said \"hi\""))
    }

    @Test
    fun `escape keeps commas and newlines inside the quoted field`() {
        assertEquals("\"a,b\nc\"", CsvExporter.escape("a,b\nc"))
    }

    @Test
    fun `escape turns null into an empty field`() {
        assertEquals("\"\"", CsvExporter.escape(null))
    }

    @Test
    fun `toCsv writes a header and one row per visit`() {
        val visit = VisitEntity(
            id = "1",
            clientName = "Acme \"Ltd\"",
            clientEmail = "it@acme.test",
            visitDate = "2026-11-15",
            startTime = "09:00",
            endTime = "10:30",
            workDescription = "Replaced router, configured Wi-Fi",
            pdfLink = null,
            syncStatus = SyncStatus.EMAILED
        )

        val lines = CsvExporter.toCsv(listOf(visit)).split("\r\n")

        assertEquals(
            "\"Client\",\"Date\",\"Start Time\",\"End Time\",\"Email\",\"Description\",\"PDF Link\",\"Sync Status\"",
            lines[0]
        )
        assertEquals(
            "\"Acme \"\"Ltd\"\"\",\"2026-11-15\",\"09:00\",\"10:30\",\"it@acme.test\"," +
                "\"Replaced router, configured Wi-Fi\",\"\",\"EMAILED\"",
            lines[1]
        )
    }
}
