package com.company.supportvisit.billing

import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.data.PartItem
import com.company.supportvisit.data.VisitEntity
import java.time.LocalDate
import java.time.YearMonth

/** One visit in a monthly report. */
data class ReportLine(
    val visit: VisitEntity,
    val bill: VisitBill
)

/** Everything billed to one client in one month. Pure data, unit tested. */
data class MonthlyReport(
    val client: ClientEntity,
    val month: YearMonth,
    val lines: List<ReportLine>,
    val vatPercent: Double,
    val currency: String
) {
    val billableMinutes: Int = lines.sumOf { it.bill.billableMinutes }
    val laborAmount: Double = Billing.round2(lines.sumOf { it.bill.laborAmount })
    val partsAmount: Double = Billing.round2(lines.sumOf { it.bill.partsAmount })
    val subtotal: Double = Billing.round2(laborAmount + partsAmount)
    val vat: Double = Billing.vat(subtotal, vatPercent)
    val total: Double = Billing.round2(subtotal + vat)

    /** All parts used in the month, combined by description and unit price. */
    val parts: List<PartItem> = lines.flatMap { it.visit.parts }
        .groupBy { it.description.trim().lowercase() to it.unitPrice }
        .map { (_, items) -> items.first().copy(quantity = items.sumOf { it.quantity }) }

    val retainer: RetainerUsage? =
        client.monthlyHoursAllowance?.takeIf { it > 0 }?.let { RetainerUsage(billableMinutes / 60.0, it) }

    val isEmpty: Boolean get() = lines.isEmpty()

    companion object {
        /** Builds the report from any list of visits; only this client's visits in [month] count. */
        fun build(
            client: ClientEntity,
            month: YearMonth,
            visits: List<VisitEntity>,
            vatPercent: Double,
            currency: String
        ): MonthlyReport {
            val lines = visits
                .filter { it.clientId == client.id && runCatching { YearMonth.from(LocalDate.parse(it.visitDate)) }.getOrNull() == month }
                .sortedWith(compareBy({ it.visitDate }, { it.startTime }))
                .map { ReportLine(it, it.bill()) }
            return MonthlyReport(client, month, lines, vatPercent, currency)
        }
    }
}
