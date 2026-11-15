package com.company.supportvisit.billing

import com.company.supportvisit.data.PartItem
import com.company.supportvisit.data.partsTotal
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.roundToLong

/** Billing numbers for one visit. All amounts are in the company currency, before VAT. */
data class VisitBill(
    val durationMinutes: Int,
    val breakMinutes: Int,
    val billableMinutes: Int,
    val hourlyRate: Double?,
    val laborAmount: Double,
    val partsAmount: Double
) {
    val total: Double get() = Billing.round2(laborAmount + partsAmount)
    val billableHours: Double get() = billableMinutes / 60.0
}

/** How much of a client's monthly support package has been used. */
enum class RetainerLevel { OK, WARNING, OVER }

data class RetainerUsage(val usedHours: Double, val allowanceHours: Double) {
    val fraction: Double get() = if (allowanceHours <= 0) 0.0 else usedHours / allowanceHours
    val level: RetainerLevel
        get() = when {
            fraction >= 1.0 -> RetainerLevel.OVER
            fraction >= Billing.RETAINER_WARNING_FRACTION -> RetainerLevel.WARNING
            else -> RetainerLevel.OK
        }
}

/** Pure billing calculations (no Android), unit tested. */
object Billing {

    const val RETAINER_WARNING_FRACTION = 0.8

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    /** Minutes between two HH:mm times; 0 if either is invalid or end isn't after start. */
    fun durationMinutes(start: String, end: String): Int = try {
        val s = LocalTime.parse(start, TIME)
        val e = LocalTime.parse(end, TIME)
        if (e.isAfter(s)) (e.toSecondOfDay() - s.toSecondOfDay()) / 60 else 0
    } catch (e: DateTimeParseException) {
        0
    }

    /** Billable time: the override if set, otherwise duration minus break (never negative). */
    fun billableMinutes(durationMinutes: Int, breakMinutes: Int, overrideMinutes: Int?): Int =
        overrideMinutes?.coerceAtLeast(0) ?: (durationMinutes - breakMinutes.coerceAtLeast(0)).coerceAtLeast(0)

    fun bill(
        startTime: String,
        endTime: String,
        breakMinutes: Int,
        overrideMinutes: Int?,
        hourlyRate: Double?,
        parts: List<PartItem>
    ): VisitBill {
        val duration = durationMinutes(startTime, endTime)
        val billable = billableMinutes(duration, breakMinutes, overrideMinutes)
        val labor = if (hourlyRate == null) 0.0 else round2(billable / 60.0 * hourlyRate)
        return VisitBill(duration, breakMinutes.coerceAtLeast(0), billable, hourlyRate, labor, round2(parts.partsTotal()))
    }

    fun vat(amount: Double, vatRatePercent: Double): Double = round2(amount * vatRatePercent / 100.0)

    /** Rounds money to agorot/cents, half up. */
    fun round2(value: Double): Double = (value * 100).roundToLong() / 100.0

    /** "1:30" style hours:minutes for reports. */
    fun formatMinutes(minutes: Int): String = "%d:%02d".format(minutes / 60, minutes % 60)
}
