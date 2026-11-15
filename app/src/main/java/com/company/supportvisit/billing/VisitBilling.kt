package com.company.supportvisit.billing

import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.data.VisitEntity
import java.time.LocalDate
import java.time.YearMonth

/** The bill for a saved visit (uses the hourly rate captured when it was recorded). */
fun VisitEntity.bill(): VisitBill =
    Billing.bill(startTime, endTime, breakMinutes, billableMinutesOverride, hourlyRate, parts)

/** First and last ISO date of a month, for range queries. */
fun YearMonth.isoRange(): Pair<String, String> = atDay(1).toString() to atEndOfMonth().toString()

object RetainerCalculator {

    /** Hours of [visits] that count against [client]'s package in [month]; null if no package. */
    fun usage(client: ClientEntity, visits: List<VisitEntity>, month: YearMonth): RetainerUsage? {
        val allowance = client.monthlyHoursAllowance?.takeIf { it > 0 } ?: return null
        val minutes = visits
            .filter { it.clientId == client.id && it.inMonth(month) }
            .sumOf { it.bill().billableMinutes }
        return RetainerUsage(minutes / 60.0, allowance)
    }

    private fun VisitEntity.inMonth(month: YearMonth): Boolean =
        runCatching { YearMonth.from(LocalDate.parse(visitDate)) == month }.getOrDefault(false)
}
