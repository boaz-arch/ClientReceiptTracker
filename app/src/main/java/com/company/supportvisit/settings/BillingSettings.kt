package com.company.supportvisit.settings

import android.content.Context
import androidx.core.content.edit

/** Which accounting system creates invoices from monthly reports. */
enum class InvoiceProviderId { NONE, GREEN_INVOICE, ICOUNT, RIVHIT }

/** Company-wide billing options (Phase 5 moves them to the server so the whole team shares them). */
object BillingSettings {

    const val DEFAULT_CURRENCY = "ILS"
    const val DEFAULT_VAT_PERCENT = 18.0
    val CURRENCIES = listOf("ILS", "USD", "EUR", "GBP")

    private const val PREFS = "billing"
    private const val KEY_CURRENCY = "currency"
    private const val KEY_VAT = "vat_percent"
    private const val KEY_SHOW_PRICES = "show_prices_on_pdf"
    private const val KEY_PROVIDER = "invoice_provider"
    private const val KEY_GREEN_SANDBOX = "green_invoice_sandbox"

    fun currency(context: Context): String = prefs(context).getString(KEY_CURRENCY, DEFAULT_CURRENCY) ?: DEFAULT_CURRENCY

    fun setCurrency(context: Context, code: String) = prefs(context).edit { putString(KEY_CURRENCY, code) }

    fun vatPercent(context: Context): Double =
        prefs(context).getFloat(KEY_VAT, DEFAULT_VAT_PERCENT.toFloat()).toDouble()

    fun setVatPercent(context: Context, value: Double) = prefs(context).edit { putFloat(KEY_VAT, value.toFloat()) }

    /** Whether rates and amounts appear on the PDF the client receives. */
    fun showPricesOnPdf(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_PRICES, true)

    fun setShowPricesOnPdf(context: Context, value: Boolean) = prefs(context).edit { putBoolean(KEY_SHOW_PRICES, value) }

    fun invoiceProvider(context: Context): InvoiceProviderId =
        runCatching { InvoiceProviderId.valueOf(prefs(context).getString(KEY_PROVIDER, null)!!) }
            .getOrDefault(InvoiceProviderId.NONE)

    fun setInvoiceProvider(context: Context, id: InvoiceProviderId) = prefs(context).edit { putString(KEY_PROVIDER, id.name) }

    /** Use Green Invoice's sandbox (test) environment instead of the real one. */
    fun greenInvoiceSandbox(context: Context): Boolean = prefs(context).getBoolean(KEY_GREEN_SANDBOX, true)

    fun setGreenInvoiceSandbox(context: Context, value: Boolean) = prefs(context).edit { putBoolean(KEY_GREEN_SANDBOX, value) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
