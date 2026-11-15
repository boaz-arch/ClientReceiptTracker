package com.company.supportvisit.utils

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Currency formatting. ILS for now; Phase 3 makes the currency a setting. */
object Money {

    private const val DEFAULT_CURRENCY = "ILS"

    fun format(amount: Double, locale: Locale = Locale.getDefault(), currencyCode: String = DEFAULT_CURRENCY): String =
        NumberFormat.getCurrencyInstance(locale).apply {
            currency = Currency.getInstance(currencyCode)
        }.format(amount)

    /** Quantities without needless decimals: 2 -> "2", 1.5 -> "1.5". */
    fun quantity(value: Double, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(value)
}
