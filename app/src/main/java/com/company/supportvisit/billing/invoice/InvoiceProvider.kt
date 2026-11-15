package com.company.supportvisit.billing.invoice

import android.content.Context
import com.company.supportvisit.R
import com.company.supportvisit.billing.MonthlyReport
import com.company.supportvisit.settings.BillingSettings
import com.company.supportvisit.settings.InvoiceProviderId
import com.company.supportvisit.settings.SecretStore

/** A document created in the accounting system. */
data class InvoiceResult(
    val documentId: String,
    val number: String?,
    /** Link to view the document, if the provider returns one. */
    val url: String?
)

class InvoiceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Creates a draft invoice from a monthly report in an accounting system.
 * Implementations must never send the invoice to the client by themselves: a person reviews
 * and issues it in the accounting system.
 */
interface InvoiceProvider {
    val id: InvoiceProviderId

    /** Checks the credentials work. */
    suspend fun testConnection()

    suspend fun createDraftInvoice(report: MonthlyReport): InvoiceResult

    companion object {
        fun texts(context: Context): InvoiceTexts {
            val locale = context.resources.configuration.locales[0]
            return InvoiceTexts(
                serviceLine = context.getString(R.string.invoice_service_line),
                serviceLineWithHours = context.getString(R.string.invoice_service_line_hours),
                description = context.getString(R.string.invoice_description),
                remarks = context.getString(R.string.invoice_remarks),
                lang = if (locale.language in setOf("iw", "he")) "he" else "en",
                locale = locale
            )
        }

        /** The provider chosen in Settings > Billing, or null if none is set up. */
        fun configured(context: Context): InvoiceProvider? = when (BillingSettings.invoiceProvider(context)) {
            InvoiceProviderId.NONE -> null
            InvoiceProviderId.GREEN_INVOICE -> {
                val keyId = SecretStore.get(context, SecretStore.GREEN_INVOICE_KEY_ID)
                val secret = SecretStore.get(context, SecretStore.GREEN_INVOICE_SECRET)
                if (keyId.isNullOrBlank() || secret.isNullOrBlank()) null
                else GreenInvoiceProvider(keyId, secret, BillingSettings.greenInvoiceSandbox(context), texts(context))
            }
            InvoiceProviderId.ICOUNT -> ICountProvider()
            InvoiceProviderId.RIVHIT -> RivhitProvider()
        }
    }
}

/** iCount: not implemented yet. TODO: implement with the iCount API (api.icount.co.il). */
class ICountProvider : InvoiceProvider {
    override val id = InvoiceProviderId.ICOUNT
    override suspend fun testConnection() = throw InvoiceException("iCount is not supported yet")
    override suspend fun createDraftInvoice(report: MonthlyReport) = throw InvoiceException("iCount is not supported yet")
}

/** Rivhit: not implemented yet. TODO: implement with the Rivhit Online API. */
class RivhitProvider : InvoiceProvider {
    override val id = InvoiceProviderId.RIVHIT
    override suspend fun testConnection() = throw InvoiceException("Rivhit is not supported yet")
    override suspend fun createDraftInvoice(report: MonthlyReport) = throw InvoiceException("Rivhit is not supported yet")
}
