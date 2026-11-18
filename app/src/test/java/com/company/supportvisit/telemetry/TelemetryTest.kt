package com.company.supportvisit.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryTest {

    @Test
    fun `crash reports never contain exception messages`() {
        val error = IllegalStateException(
            "Could not send report to dana@client.example",
            java.io.IOException("Client: Acme Ltd, phone 050-1234567")
        )

        val stack = Telemetry.scrubbedStack(error)

        assertFalse(stack.contains("dana@client.example"))
        assertFalse(stack.contains("Acme"))
        assertFalse(stack.contains("050-1234567"))
        assertTrue(stack.startsWith("java.lang.IllegalStateException\n"))
        assertTrue(stack.contains("Caused by: java.io.IOException\n"))
        assertTrue("keeps code lines", stack.contains("    at com.company.supportvisit.telemetry.TelemetryTest"))
    }

    @Test
    fun `cause loops do not hang`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        val stack = Telemetry.scrubbedStack(a)

        assertEquals(2, Regex("java.lang.RuntimeException").findAll(stack).count())
    }

    @Test
    fun `report has the fields the server stores`() {
        val report = Telemetry.buildReport(RuntimeException("secret"), "1.2.0 (10200)", "15 (API 35)", "Google Pixel 9")

        assertEquals("1.2.0 (10200)", report.getString("appVersion"))
        assertEquals("15 (API 35)", report.getString("android"))
        assertEquals("Google Pixel 9", report.getString("device"))
        assertFalse(report.getString("stack").contains("secret"))
        assertTrue(report.getString("stack").length <= 20_000)
    }
}
