package com.company.supportvisit.dashboard

import com.company.supportvisit.billing.Billing
import com.company.supportvisit.billing.RetainerCalculator
import com.company.supportvisit.billing.RetainerUsage
import com.company.supportvisit.billing.bill
import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.data.SyncStatus
import com.company.supportvisit.data.VisitEntity
import com.company.supportvisit.data.VisitType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** Date range filter for the dashboard. */
sealed class DateRange {
    data object ThisMonth : DateRange()
    data object LastMonth : DateRange()
    data object ThisQuarter : DateRange()
    data class Custom(val from: LocalDate, val to: LocalDate) : DateRange()

    /** Inclusive start and end dates. */
    fun bounds(today: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
        ThisMonth -> YearMonth.from(today).let { it.atDay(1) to it.atEndOfMonth() }
        LastMonth -> YearMonth.from(today).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
        ThisQuarter -> {
            val firstMonth = ((today.monthValue - 1) / 3) * 3 + 1
            val start = LocalDate.of(today.year, firstMonth, 1)
            start to YearMonth.from(start.plusMonths(2)).atEndOfMonth()
        }
        is Custom -> if (from <= to) from to to else to to from
    }
}

data class DashboardFilter(
    val range: DateRange = DateRange.ThisMonth,
    val clientId: String? = null,
    /** Technician email, or null for everyone. */
    val technician: String? = null
)

data class LabeledValue(val label: String, val value: Double)

data class WeekPoint(val weekStart: LocalDate, val visits: Int, val hours: Double)

data class RetainerRow(val client: ClientEntity, val usage: RetainerUsage)

/** Everything the dashboard shows. Built by [DashboardStats.compute]; pure, unit tested. */
data class DashboardData(
    val visitCount: Int,
    val hours: Double,
    /** Billable amount (before VAT), or null when billing is off. */
    val amount: Double?,
    /** Visits on this phone not sent yet (ignores the filters). */
    val unsentCount: Int,
    val hoursPerClient: List<LabeledValue>,
    val weeks: List<WeekPoint>,
    val hoursPerTechnician: List<LabeledValue>,
    val retainers: List<RetainerRow>,
    val visitsByType: Map<VisitType, Int>,
    val technicians: List<String>
)

object DashboardStats {

    const val TOP_CLIENTS = 10
    const val WEEKS = 12

    fun compute(
        visits: List<VisitEntity>,
        clients: List<ClientEntity>,
        filter: DashboardFilter,
        today: LocalDate,
        billingOn: Boolean,
        defaultRate: Double?,
        firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
        unknownClientLabel: String = "-"
    ): DashboardData {
        val clientsById = clients.associateBy { it.id }
        val dated = visits.mapNotNull { v -> runCatching { LocalDate.parse(v.visitDate) }.getOrNull()?.let { v to it } }

        // Client and technician filters apply everywhere; the date range applies to the totals.
        val scoped = dated.filter { (v, _) ->
            (filter.clientId == null || v.clientId == filter.clientId) &&
                (filter.technician == null || v.technicianEmail.equals(filter.technician, ignoreCase = true))
        }
        val (from, to) = filter.range.bounds(today)
        val inRange = scoped.filter { (_, d) -> d in from..to }.map { it.first }

        fun minutes(v: VisitEntity) = Billing.durationMinutes(v.startTime, v.endTime)
        fun amount(v: VisitEntity): Double {
            val rate = v.hourlyRate ?: clientsById[v.clientId]?.hourlyRate ?: defaultRate
            return v.copy(hourlyRate = rate).bill().total
        }

        val hoursPerClient = inRange
            .groupBy { it.clientId?.let(clientsById::get)?.name ?: it.clientName.ifBlank { unknownClientLabel } }
            .map { (name, list) -> LabeledValue(name, list.sumOf(::minutes) / 60.0) }
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(TOP_CLIENTS)

        val thisWeek = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        val weeks = (WEEKS - 1 downTo 0).map { back ->
            val start = thisWeek.minusWeeks(back.toLong())
            val week = scoped.filter { (_, d) -> d >= start && d < start.plusWeeks(1) }.map { it.first }
            WeekPoint(start, week.size, week.sumOf(::minutes) / 60.0)
        }

        val hoursPerTechnician = inRange
            .groupBy { it.technicianEmail?.substringBefore('@')?.ifBlank { null } ?: unknownClientLabel }
            .map { (tech, list) -> LabeledValue(tech, list.sumOf(::minutes) / 60.0) }
            .sortedByDescending { it.value }

        val month = YearMonth.from(today)
        val retainers = clients
            .filter { filter.clientId == null || it.id == filter.clientId }
            .mapNotNull { c -> RetainerCalculator.usage(c, visits, month)?.let { RetainerRow(c, it) } }
            .sortedByDescending { it.usage.fraction }

        return DashboardData(
            visitCount = inRange.size,
            hours = inRange.sumOf(::minutes) / 60.0,
            amount = if (billingOn) Billing.round2(inRange.sumOf(::amount)) else null,
            unsentCount = visits.count { it.ownedLocally && it.syncStatus != SyncStatus.EMAILED },
            hoursPerClient = hoursPerClient,
            weeks = weeks,
            hoursPerTechnician = hoursPerTechnician,
            retainers = retainers,
            visitsByType = inRange.groupingBy { it.type }.eachCount(),
            technicians = visits.mapNotNull { it.technicianEmail?.lowercase() }.distinct().sorted()
        )
    }
}
