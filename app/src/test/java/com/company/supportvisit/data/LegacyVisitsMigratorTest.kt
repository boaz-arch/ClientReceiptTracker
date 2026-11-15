package com.company.supportvisit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyVisitsMigratorTest {

    private val legacyJson = """
        [
          {"clientName":"Acme","visitDate":"28/07/2026","startTime":"09:00","endTime":"10:00",
           "workDescription":"Printer \"fix\"","clientEmail":"a@acme.test","pdfLink":"https://x/1.pdf"},
          {"clientName":"Beta","visitDate":"29/07/2026","startTime":"11:00","endTime":"12:30",
           "workDescription":"","clientEmail":"b@beta.test","pdfLink":""},
          "not an object"
        ]
    """.trimIndent()

    @Test
    fun `parses every legacy visit as already emailed with ISO dates`() {
        val visits = LegacyVisitsMigrator.parse(legacyJson, now = 1_000L)

        assertEquals(2, visits.size)
        with(visits[0]) {
            assertEquals("Acme", clientName)
            assertEquals("2026-07-28", visitDate)
            assertEquals("Printer \"fix\"", workDescription)
            assertEquals("https://x/1.pdf", pdfLink)
            assertEquals(SyncStatus.EMAILED, syncStatus)
        }
        assertNull("blank links become null", visits[1].pdfLink)
        assertTrue("each visit gets its own id", visits[0].id != visits[1].id)
    }

    @Test
    fun `blank file gives no visits`() {
        assertTrue(LegacyVisitsMigrator.parse("  ").isEmpty())
    }
}
