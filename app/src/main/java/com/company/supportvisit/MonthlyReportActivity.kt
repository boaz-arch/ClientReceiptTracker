package com.company.supportvisit

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.company.supportvisit.billing.Billing
import com.company.supportvisit.billing.MonthlyReport
import com.company.supportvisit.billing.MonthlyReportExport
import com.company.supportvisit.billing.RetainerLevel
import com.company.supportvisit.billing.invoice.InvoiceProvider
import com.company.supportvisit.billing.isoRange
import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.databinding.ActivityMonthlyReportBinding
import com.company.supportvisit.graph.MailService
import com.company.supportvisit.graph.MicrosoftAuthManager
import com.company.supportvisit.settings.BillingSettings
import com.company.supportvisit.settings.FormSettings
import com.company.supportvisit.ui.SignInHelper
import com.company.supportvisit.ui.applySystemBarInsets
import com.company.supportvisit.utils.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.YearMonth

/** Pick a client and a month; export the summary as PDF/CSV, email it, or create a draft invoice. */
class MonthlyReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMonthlyReportBinding

    private var clients: List<ClientEntity> = emptyList()
    private var client: ClientEntity? = null
    private var month: YearMonth = YearMonth.now()
    private var report: MonthlyReport? = null

    private val exportDir by lazy { File(filesDir, "exports") }

    /** Billing is optional; without it reports show hours and parts but no amounts. */
    private val billingOn by lazy { FormSettings.isEnabled(this, FormSettings.Section.BILLING) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonthlyReportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        // Nothing to export until a client's report is loaded.
        listOf(binding.btnSharePdf, binding.btnShareCsv, binding.btnEmailClient, binding.btnCreateInvoice)
            .forEach { it.isEnabled = false }

        savedInstanceState?.getString(STATE_MONTH)?.let { month = YearMonth.parse(it) }
        val savedClientId = savedInstanceState?.getString(STATE_CLIENT)

        binding.btnPrevMonth.setOnClickListener { month = month.minusMonths(1); refresh() }
        binding.btnNextMonth.setOnClickListener { month = month.plusMonths(1); refresh() }
        binding.actClient.setOnItemClickListener { _, _, position, _ -> client = clients[position]; refresh() }

        binding.btnSharePdf.setOnClickListener { withReport { share(pdf(it), "application/pdf") } }
        binding.btnShareCsv.setOnClickListener { withReport { share(MonthlyReportExport.writeCsv(it, exportDir), "text/csv") } }
        binding.btnEmailClient.setOnClickListener { withReport(::confirmEmail) }
        binding.btnCreateInvoice.setOnClickListener { withReport(::createInvoice) }

        lifecycleScope.launch {
            clients = app.clientRepository.getClients()
            binding.actClient.setAdapter(
                ArrayAdapter(this@MonthlyReportActivity, android.R.layout.simple_dropdown_item_1line, clients.map { it.name })
            )
            client = clients.firstOrNull { it.id == savedClientId } ?: clients.firstOrNull()
            client?.let { binding.actClient.setText(it.name, false) }
            if (clients.isEmpty()) binding.tvSummary.setText(R.string.monthly_report_no_clients)
            refresh()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MONTH, month.toString())
        client?.let { outState.putString(STATE_CLIENT, it.id) }
    }

    private fun refresh() {
        binding.tvMonth.text = MonthlyReportExport.monthLabel(
            MonthlyReport(ClientEntity("", ""), month, emptyList(), 0.0, "ILS")
        )
        val c = client ?: return
        lifecycleScope.launch {
            val (from, to) = month.isoRange()
            val visits = app.visitRepository.getVisitsInRange(from, to, c.id)
            val r = MonthlyReport.build(c, month, visits, BillingSettings.vatPercent(this@MonthlyReportActivity),
                BillingSettings.currency(this@MonthlyReportActivity))
            report = r
            render(r)
        }
    }

    private fun render(r: MonthlyReport) {
        val ctx = this
        binding.tvSummary.text = if (r.isEmpty) {
            getString(R.string.monthly_report_no_visits)
        } else {
            val money = if (!billingOn) emptyList() else listOf(
                getString(R.string.pdf_labor, Money.format(ctx, r.laborAmount)),
                getString(R.string.pdf_parts_amount, Money.format(ctx, r.partsAmount)),
                getString(R.string.pdf_subtotal, Money.format(ctx, r.subtotal)),
                getString(R.string.pdf_vat, Money.quantity(r.vatPercent), Money.format(ctx, r.vat)),
                getString(R.string.pdf_total_with_vat, Money.format(ctx, r.total))
            )
            (listOf(
                getString(R.string.report_visits_count, r.lines.size),
                getString(R.string.report_total_hours, Billing.formatMinutes(r.billableMinutes))
            ) + money).joinToString("\n")
        }
        val usage = r.retainer
        binding.tvRetainer.visibility = if (usage == null) View.GONE else View.VISIBLE
        if (usage != null) {
            binding.tvRetainer.text = getString(
                when (usage.level) {
                    RetainerLevel.OK -> R.string.retainer_usage
                    RetainerLevel.WARNING -> R.string.retainer_usage_warning
                    RetainerLevel.OVER -> R.string.retainer_usage_over
                },
                Money.hours(usage.usedHours), Money.hours(usage.allowanceHours)
            )
            binding.tvRetainer.setTextColor(
                getColor(
                    when (usage.level) {
                        RetainerLevel.OK -> R.color.text_secondary
                        RetainerLevel.WARNING -> R.color.status_pending
                        RetainerLevel.OVER -> R.color.status_failed
                    }
                )
            )
        }
        val hasData = !r.isEmpty
        listOf(binding.btnSharePdf, binding.btnShareCsv, binding.btnEmailClient, binding.btnCreateInvoice)
            .forEach { it.isEnabled = hasData }
        binding.btnCreateInvoice.visibility = if (billingOn) View.VISIBLE else View.GONE
    }

    private fun withReport(action: (MonthlyReport) -> Unit) {
        val r = report ?: return
        if (r.isEmpty) {
            Toast.makeText(this, R.string.monthly_report_no_visits, Toast.LENGTH_LONG).show()
            return
        }
        action(r)
    }

    private fun pdf(r: MonthlyReport): File =
        MonthlyReportExport.writePdf(this, r, exportDir, billingOn && BillingSettings.showPricesOnPdf(this))

    private fun share(file: File, type: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, file.name))
    }

    private fun confirmEmail(r: MonthlyReport) {
        val to = r.client.email
        if (to.isBlank()) {
            Toast.makeText(this, R.string.monthly_report_client_no_email, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.email_report_to_client)
            .setMessage(getString(R.string.monthly_report_email_confirm, to))
            .setPositiveButton(R.string.send) { _, _ -> sendEmail(r) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun sendEmail(r: MonthlyReport) = busy {
        if (!SignInHelper.ensureSignedIn(this)) return@busy
        val token = MicrosoftAuthManager.getAccessToken(this)
        val monthLabel = MonthlyReportExport.monthLabel(r)
        withContext(Dispatchers.IO) {
            val pdf = pdf(r)
            val csv = MonthlyReportExport.writeCsv(r, exportDir)
            MailService.sendMailWithAttachments(
                token = token,
                subject = getString(R.string.monthly_report_email_subject, r.client.name, monthLabel),
                body = getString(R.string.monthly_report_email_body, r.client.name, monthLabel),
                recipients = listOfNotNull(r.client.email, MicrosoftAuthManager.accountEmail.value),
                attachments = listOf(
                    MailService.Attachment(pdf, "application/pdf"),
                    MailService.Attachment(csv, "text/csv")
                )
            )
        }
        Toast.makeText(this, R.string.monthly_report_email_sent, Toast.LENGTH_LONG).show()
    }

    private fun createInvoice(r: MonthlyReport) {
        val provider = InvoiceProvider.configured(this)
        if (provider == null) {
            Toast.makeText(this, R.string.invoice_not_configured, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, BillingSettingsActivity::class.java))
            return
        }
        busy {
            val result = provider.createDraftInvoice(r)
            AlertDialog.Builder(this)
                .setTitle(R.string.invoice_created_title)
                .setMessage(getString(R.string.invoice_created_message, result.number ?: result.documentId))
                .setPositiveButton(R.string.ok, null)
                .apply {
                    result.url?.let { url ->
                        setNeutralButton(R.string.open) { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                    }
                }
                .show()
        }
    }

    /** Runs [block] with a spinner and the buttons disabled; shows a friendly error on failure. */
    private fun busy(block: suspend () -> Unit) {
        binding.progress.visibility = View.VISIBLE
        val buttons = listOf(binding.btnSharePdf, binding.btnShareCsv, binding.btnEmailClient, binding.btnCreateInvoice)
        buttons.forEach { it.isEnabled = false }
        lifecycleScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "Monthly report action failed", e)
                val message = when (e) {
                    is com.company.supportvisit.billing.invoice.InvoiceException -> R.string.invoice_failed
                    is java.io.IOException -> R.string.action_failed_offline
                    else -> R.string.action_failed
                }
                Toast.makeText(this@MonthlyReportActivity, message, Toast.LENGTH_LONG).show()
            } finally {
                binding.progress.visibility = View.GONE
                report?.let(::render)
            }
        }
    }

    companion object {
        private const val TAG = "MonthlyReportActivity"
        private const val STATE_MONTH = "month"
        private const val STATE_CLIENT = "client"
    }
}
