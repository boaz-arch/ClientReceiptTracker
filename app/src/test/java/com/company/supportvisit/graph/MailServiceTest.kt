package com.company.supportvisit.graph

import com.company.supportvisit.data.VisitEntity
import com.company.supportvisit.utils.PdfReportGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MailServiceTest {

    @Test
    fun `sendMail payload escapes values and de-duplicates recipients`() {
        val json = MailService.buildSendMailJson(
            subject = "Report - \"Acme\"",
            body = "Line 1\nLine \"2\"",
            recipients = listOf("client@acme.test", " client@acme.test ", "", "tech@company.test"),
            attachmentName = "Visit.pdf",
            attachmentBase64 = "QUJD"
        )

        // Round-trips through a real JSON parser, so quotes and newlines were escaped.
        val message = org.json.JSONObject(json.toString()).getJSONObject("message")
        assertEquals("Report - \"Acme\"", message.getString("subject"))
        assertEquals("Line 1\nLine \"2\"", message.getJSONObject("body").getString("content"))

        val to = message.getJSONArray("toRecipients")
        assertEquals(2, to.length())
        assertEquals("client@acme.test", to.getJSONObject(0).getJSONObject("emailAddress").getString("address"))
        assertEquals("tech@company.test", to.getJSONObject(1).getJSONObject("emailAddress").getString("address"))

        val attachment = message.getJSONArray("attachments").getJSONObject(0)
        assertEquals("#microsoft.graph.fileAttachment", attachment.getString("@odata.type"))
        assertEquals("QUJD", attachment.getString("contentBytes"))
        assertTrue(json.getBoolean("saveToSentItems"))
    }

    @Test
    fun `pdf file name is stable per visit and safe for OneDrive`() {
        val visit = VisitEntity(
            id = "0123456789abcdef",
            clientName = "Acme / Ltd: \"HQ\"",
            clientEmail = "",
            visitDate = "2026-11-15",
            startTime = "09:00",
            endTime = "10:00",
            workDescription = ""
        )
        assertEquals("Visit_2026-11-15_Acme_Ltd_HQ_01234567.pdf", PdfReportGenerator.fileNameFor(visit))
    }
}
