package com.company.supportvisit.dashboard

import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.data.SyncStatus
import com.company.supportvisit.data.VisitEntity
import com.company.supportvisit.data.VisitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class DashboardStatsTest {

    private val today = LocalDate.of(2026, 11, 16) // a Monday
    private val acme = ClientEntity(id = "a", name = "Acme", hourlyRate = 200.0, monthlyHoursAllowance = 10.0)
    private val beta = ClientEntity(id = "b", name = "Beta")

    private fun visit(
        id: String, date: String, start: String, end: String, client: ClientEntity = acme,
        tech: String = "dana@co.test", type: VisitType = VisitType.REPAIR, status: SyncStatus = SyncStatus.EMAILED
    ) = VisitEntity(
        id = id, clientName = client.name, clientEmail = "", visitDate = date, startTime = start, endTime = end,
        workDescription = "", clientId = client.id, technicianEmail = tech, visitType = type.key, syncStatus = status
    )

    private val visits = listOf(
        visit("1", "2026-11-02", "09:00", "11:00"),
        visit("2", "2026-11-10", "09:00", "10:00", client = beta, tech = "omer@co.test", type = VisitType.REMOTE),
        visit("3", "2026-11-16", "13:00", "16:00", status = SyncStatus.PENDING),
        visit("4", "2026-10-20", "09:00", "17:00"),            // last month
        visit("5", "2026-08-03", "09:00", "10:00")             // outside the 12 weeks
    )

    private fun compute(filter: DashboardFilter = DashboardFilter(), billingOn: Boolean = true) =
        DashboardStats.compute(visits, listOf(acme, beta), filter, today, billingOn, defaultRate = 100.0, firstDayOfWeek = DayOfWeek.SUNDAY)

    @Test
    fun `KPIs for this month`() {
        val d = compute()
        assertEquals(3, d.visitCount)
        assertEquals(6.0, d.hours, 0.0)
        // Acme 5 h × 200 + Beta 1 h × default 100
        assertEquals(1100.0, d.amount!!, 0.0)
        assertEquals(1, d.unsentCount)
    }

    @Test
    fun `amount is hidden when billing is off`() {
        assertNull(compute(billingOn = false).amount)
    }

    @Test
    fun `date ranges`() {
        assertEquals(1, compute(DashboardFilter(range = DateRange.LastMonth)).visitCount)
        assertEquals(4, compute(DashboardFilter(range = DateRange.ThisQuarter)).visitCount)
        val custom = DateRange.Custom(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 1))
        assertEquals(LocalDate.of(2026, 11, 1) to LocalDate.of(2026, 11, 16), custom.bounds(today))
        assertEquals(3, compute(DashboardFilter(range = custom)).visitCount)
    }

    @Test
    fun `client and technician filters`() {
        assertEquals(1, compute(DashboardFilter(clientId = "b")).visitCount)
        assertEquals(2, compute(DashboardFilter(technician = "DANA@co.test")).visitCount)
    }

    @Test
    fun `hours per client and technician, biggest first`() {
        val d = compute()
        assertEquals(listOf("Acme" to 5.0, "Beta" to 1.0), d.hoursPerClient.map { it.label to it.value })
        assertEquals(listOf("dana" to 5.0, "omer" to 1.0), d.hoursPerTechnician.map { it.label to it.value })
    }

    @Test
    fun `twelve weeks ending this week`() {
        val d = compute()
        assertEquals(12, d.weeks.size)
        assertEquals(LocalDate.of(2026, 11, 15), d.weeks.last().weekStart) // Sunday
        assertEquals(1, d.weeks.last().visits)
        assertEquals(3.0, d.weeks.last().hours, 0.0)
        assertEquals(0, d.weeks.sumOf { w -> if (w.weekStart < LocalDate.of(2026, 8, 30)) w.visits else 0 })
    }

    @Test
    fun `visits by type, retainers and technician list`() {
        val d = compute()
        assertEquals(mapOf(VisitType.REPAIR to 2, VisitType.REMOTE to 1), d.visitsByType)
        assertEquals(1, d.retainers.size)
        assertEquals(5.0, d.retainers.single().usage.usedHours, 0.0)
        assertEquals(listOf("dana@co.test", "omer@co.test"), d.technicians)
    }
}
