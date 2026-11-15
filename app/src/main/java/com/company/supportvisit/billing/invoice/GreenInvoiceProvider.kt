package com.company.supportvisit.billing.invoice

import com.company.supportvisit.billing.Billing
import com.company.supportvisit.billing.MonthlyReport
import com.company.supportvisit.billing.MonthlyReportExport
import com.company.supportvisit.graph.GraphException
import com.company.supportvisit.graph.GraphHttp
import com.company.supportvisit.settings.InvoiceProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Green Invoice (Morning) integration. Creates a "חשבון עסקה" (proforma, type 300) for the
 * month: it is not a tax document, so it works as a draft that the accountant reviews and turns
 * into a tax invoice in Green Invoice. Nothing is emailed to the client from here.
 *
 * NOTE: verify against Green Invoice's current API docs and the sandbox before production use.
 * The sign-in endpoint is tried in two forms because public sources disagree.
 */
/** Invoice wording, from string resources (format strings take the month label / hours). */
data class InvoiceTexts(
    /** e.g. "Support and maintenance - %1$s" */
    val serviceLine: String,
    /** e.g. "Support and maintenance - %1$s (%2$s hours)" */
    val serviceLineWithHours: String,
    /** e.g. "Monthly report %1$s" */
    val description: String,
    /** e.g. "%1$s hours, %2$d visits" */
    val remarks: String,
    /** Green Invoice document language: "he" or "en". */
    val lang: String,
    val locale: Locale
)

class GreenInvoiceProvider(
    private val apiKeyId: String,
    private val apiSecret: String,
    sandbox: Boolean,
    private val texts: InvoiceTexts
) : InvoiceProvider {

    override val id = InvoiceProviderId.GREEN_INVOICE

    private val base = if (sandbox) SANDBOX_URL else PRODUCTION_URL
    private val json = "application/json".toMediaType()

    override suspend fun testConnection() {
        token()
    }

    override suspend fun createDraftInvoice(report: MonthlyReport): InvoiceResult = withContext(Dispatchers.IO) {
        if (report.isEmpty) throw InvoiceException("No billable visits in this month")
        val response = post("$base/documents", buildDocument(report, texts), token())
        InvoiceResult(
            documentId = response.optString("id"),
            number = response.optString("number").ifBlank { null },
            url = response.optJSONObject("url")?.optString("he")?.ifBlank { null }
                ?: response.optString("url").takeIf { it.startsWith("http") }
        )
    }

    private suspend fun token(): String = withContext(Dispatchers.IO) {
        val credentials = JSONObject().put("id", apiKeyId).put("secret", apiSecret)
        try {
            post("$base/account/token", credentials, null).optString("token").ifBlank { null }
        } catch (e: GraphException) {
            if (e.code != 404) throw InvoiceException("Green Invoice rejected the API key", e)
            null
        } ?: try {
            // Newer OAuth client-credentials endpoint.
            val body = JSONObject().put("client_id", apiKeyId).put("client_secret", apiSecret)
                .put("grant_type", "client_credentials")
            post(OAUTH_URL, body, null).optString("access_token").ifBlank { null }
        } catch (e: GraphException) {
            throw InvoiceException("Green Invoice rejected the API key", e)
        } ?: throw InvoiceException("Green Invoice did not return a token")
    }

    private fun post(url: String, body: JSONObject, token: String?): JSONObject {
        val request = Request.Builder().url(url).post(body.toString().toRequestBody(json)).build()
        val text = if (token != null) {
            GraphHttp.execute(request, token)
        } else {
            GraphHttp.client.newCall(request).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw GraphException(r.code, t.take(300))
                t
            }
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    companion object {
        const val PRODUCTION_URL = "https://api.greeninvoice.co.il/api/v1"
        const val SANDBOX_URL = "https://sandbox.d.greeninvoice.co.il/api/v1"
        const val OAUTH_URL = "https://api.morning.co/idp/v1/oauth/token"

        /** Proforma / חשבון עסקה. */
        const val DOCUMENT_TYPE_PROFORMA = 300

        /** Document body: one income line for labour (hours × rate) and one per part. Pure, unit tested. */
        fun buildDocument(report: MonthlyReport, texts: InvoiceTexts): JSONObject {
            val monthLabel = MonthlyReportExport.monthLabel(report, texts.locale)
            val income = JSONArray()

            val hours = Billing.round2(report.billableMinutes / 60.0)
            val rates = report.lines.mapNotNull { it.bill.hourlyRate }.distinct()
            if (report.laborAmount > 0) {
                if (rates.size == 1) {
                    income.put(line(texts.serviceLine.format(monthLabel), hours, rates.first(), report.currency))
                } else {
                    // Different rates within the month: one line with the total.
                    income.put(line(texts.serviceLineWithHours.format(monthLabel, Billing.formatMinutes(report.billableMinutes)), 1.0, report.laborAmount, report.currency))
                }
            }
            report.parts.filter { it.total > 0 }.forEach { part ->
                income.put(line(part.description, part.quantity, part.unitPrice, report.currency))
            }

            val client = JSONObject()
                .put("name", report.client.name)
                .put("add", true)
            if (report.client.email.isNotBlank()) client.put("emails", JSONArray().put(report.client.email))
            if (report.client.phone.isNotBlank()) client.put("phone", report.client.phone)
            if (report.client.address.isNotBlank()) client.put("address", report.client.address)

            return JSONObject()
                .put("type", DOCUMENT_TYPE_PROFORMA)
                .put("description", texts.description.format(monthLabel))
                .put("lang", texts.lang)
                .put("currency", report.currency)
                .put("vatType", 0)
                .put("client", client)
                .put("income", income)
                .put("remarks", texts.remarks.format(Billing.formatMinutes(report.billableMinutes), report.lines.size))
                // Never email the client from the app; the accountant issues the real invoice.
                .put("attachment", false)
        }

        private fun line(description: String, quantity: Double, price: Double, currency: String) = JSONObject()
            .put("description", description)
            .put("quantity", quantity)
            .put("price", Billing.round2(price))
            .put("currency", currency)
            .put("vatType", 0)
    }
}
