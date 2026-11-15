package com.company.supportvisit.utils

import android.content.Context
import com.company.supportvisit.settings.BillingSettings
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Currency formatting in the company currency (Settings > Billing). */
object Money {

    fun format(context: Context, amount: Double): String = format(amount, BillingSettings.currency(context))

    fun format(amount: Double, currencyCode: String = BillingSettings.DEFAULT_CURRENCY, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getCurrencyInstance(locale).apply {
            currency = Currency.getInstance(currencyCode)
        }.format(amount)

    /** Quantities without needless decimals: 2 -> "2", 1.5 -> "1.5". */
    fun quantity(value: Double, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(value)

    /** Hours with up to 2 decimals: 7.5 -> "7.5". */
    fun hours(value: Double, locale: Locale = Locale.getDefault()): String = quantity(value, locale)
}
