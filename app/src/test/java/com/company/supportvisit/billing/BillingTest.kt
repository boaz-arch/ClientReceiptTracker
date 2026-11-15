package com.company.supportvisit.billing

import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.data.PartItem
import com.company.supportvisit.data.VisitEntity
import com.company.supportvisit.billing.invoice.GreenInvoiceProvider
import com.company.supportvisit.billing.invoice.InvoiceTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.util.Locale

class BillingTest {

    @Test
    fun `duration is end minus start, zero when invalid`() {
        assertEquals(105, Billing.durationMinutes("10:15", "12:00"))
        assertEquals(0, Billing.durationMinutes("12:00", "10:00"))
        assertEquals(0, Billing.durationMinutes("", "10:00"))
    }

    @Test
    fun `billable time subtracts the break and honours the override`() {
        assertEquals(90, Billing.billableMinutes(120, 30, null))
        assertEquals(0, Billing.billableMinutes(20, 30, null))
        assertEquals(45, Billing.billableMinutes(120, 30, 45))
        assertEquals(0, Billing.billableMinutes(120, 0, -10))
    }

    @Test
    fun `bill is billable hours times rate plus parts`() {
        val bill = Billing.bill("09:00", "11:00", 30, null, 250.0, listOf(PartItem("Cable", 2.0, 15.5)))
        assertEquals(90, bill.billableMinutes)
        assertEquals(375.0, bill.laborAmount, 0.0)
        assertEquals(31.0, bill.partsAmount, 0.0)
        assertEquals(406.0, bill.total, 0.0)
    }

    @Test
    fun `no rate means no labour amount`() {
        val bill = Billing.bill("09:00", "10:00", 0, null, null, emptyList())
        assertEquals(0.0, bill.laborAmount, 0.0)
        assertEquals(60, bill.billableMinutes)
    }

    @Test
    fun `money rounds to cents and VAT uses the given rate`() {
        assertEquals(10.01, Billing.round2(10.005), 0.0)
        assertEquals(73.08, Billing.vat(406.0, 18.0), 0.0)
        assertEquals("1:05", Billing.formatMinutes(65))
    }

    @Test
    fun `retainer warns at 80 percent and flags over 100 percent`() {
        assertEquals(RetainerLevel.OK, RetainerUsage(7.9, 10.0).level)
        assertEquals(RetainerLevel.WARNING, RetainerUsage(8.0, 10.0).level)
        assertEquals(RetainerLevel.OVER, RetainerUsage(10.0, 10.0).level)
    }

    private val client = ClientEntity(id = "c1", name = "Acme", email = "it@acme.test", hourlyRate = 200.0, monthlyHoursAllowance = 10.0)

    private fun visit(id: String, date: String, start: String, end: String, clientId: String = "c1", rate: Double? = 200.0,
                      parts: List<PartItem> = emptyList()) = VisitEntity(
        id = id, clientName = "Acme", clientEmail = "it@acme.test", visitDate = date, startTime = start, endTime = end,
        workDescription = "Work $id", clientId = clientId, hourlyRate = rate, parts = parts
    )

    @Test
    fun `retainer usage counts only this client's visits in the month`() {
        val visits = listOf(
            visit("1", "2026-11-03", "09:00", "11:00"),
            visit("2", "2026-11-20", "09:00", "15:00"),
            visit("3", "2026-10-31", "09:00", "17:00"),            // other month
            visit("4", "2026-11-05", "09:00", "17:00", clientId = "c2") // other client
        )
        val usage = RetainerCalculator.usage(client, visits, YearMonth.of(2026, 11))!!
        assertEquals(8.0, usage.usedHours, 0.0)
        assertEquals(RetainerLevel.WARNING, usage.level)
        assertNull(RetainerCalculator.usage(client.copy(monthlyHoursAllowance = null), visits, YearMonth.of(2026, 11)))
    }

    @Test
    fun `monthly report totals, combined parts and VAT`() {
        val visits = listOf(
            visit("1", "2026-11-03", "09:00", "11:00", parts = listOf(PartItem("Cable", 2.0, 10.0))),
            visit("2", "2026-11-20", "09:00", "10:30", parts = listOf(PartItem("cable", 1.0, 10.0), PartItem("Switch", 1.0, 100.0))),
            visit("3", "2026-12-01", "09:00", "10:00")
        )
        val report = MonthlyReport.build(client, YearMonth.of(2026, 11), visits, 18.0, "ILS")

        assertEquals(2, report.lines.size)
        assertEquals(210, report.billableMinutes)
        assertEquals(700.0, report.laborAmount, 0.0)
        assertEquals(130.0, report.partsAmount, 0.0)
        assertEquals(830.0, report.subtotal, 0.0)
        assertEquals(149.4, report.vat, 0.0)
        assertEquals(979.4, report.total, 0.0)
        assertEquals("cable quantities are combined", 3.0, report.parts.first { it.unitPrice == 10.0 }.quantity, 0.0)
        assertEquals(3.5, report.retainer!!.usedHours, 0.0)
    }

    @Test
    fun `green invoice document has a labour line and one line per part`() {
        val visits = listOf(visit("1", "2026-11-03", "09:00", "11:00", parts = listOf(PartItem("Switch", 1.0, 100.0))))
        val report = MonthlyReport.build(client, YearMonth.of(2026, 11), visits, 18.0, "ILS")
        val texts = InvoiceTexts("Support - %1\$s", "Support - %1\$s (%2\$s h)", "Report %1\$s", "%1\$s h, %2\$d visits", "en", Locale.US)

        val doc = GreenInvoiceProvider.buildDocument(report, texts)

        assertEquals(300, doc.getInt("type"))
        assertEquals(false, doc.getBoolean("attachment"))
        assertEquals("Acme", doc.getJSONObject("client").getString("name"))
        assertTrue(doc.getJSONObject("client").getBoolean("add"))
        val income = doc.getJSONArray("income")
        assertEquals(2, income.length())
        assertEquals("Support - November 2026", income.getJSONObject(0).getString("description"))
        assertEquals(2.0, income.getJSONObject(0).getDouble("quantity"), 0.0)
        assertEquals(200.0, income.getJSONObject(0).getDouble("price"), 0.0)
        assertEquals("Switch", income.getJSONObject(1).getString("description"))
    }

    @Test
    fun `monthly CSV has plain numbers that Excel can sum`() {
        val visits = listOf(visit("1", "2026-11-03", "09:00", "10:30"))
        val csv = MonthlyReportExport.toCsv(MonthlyReport.build(client, YearMonth.of(2026, 11), visits, 18.0, "ILS"))
        val row = csv.lines()[1]
        assertTrue(row, row.startsWith("\"2026-11-03\",\"09:00\",\"10:30\""))
        assertTrue(row, row.endsWith("\"300.00\",\"0.00\",\"300.00\""))
        assertTrue(csv.contains("\"Total\",\"354.00\""))
    }
}
