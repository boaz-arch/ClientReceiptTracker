package com.company.supportvisit

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.company.supportvisit.billing.invoice.InvoiceProvider
import com.company.supportvisit.databinding.ActivityBillingSettingsBinding
import com.company.supportvisit.settings.BillingSettings
import com.company.supportvisit.settings.FormSettings
import com.company.supportvisit.settings.InvoiceProviderId
import com.company.supportvisit.settings.SecretStore
import com.company.supportvisit.ui.applySystemBarInsets
import com.company.supportvisit.ui.setUpBackBar
import kotlinx.coroutines.launch

/** Settings > Billing: currency, VAT, prices on the client PDF, invoice provider and its API key. */
class BillingSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBillingSettingsBinding

    private val providers = InvoiceProviderId.entries

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBillingSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        setUpBackBar()

        // Two independent switches, both off by default: billing on visit reports, and billing on
        // monthly reports & invoices. Rates, currency and VAT show when either is on.
        binding.switchEnableBilling.isChecked = FormSettings.isEnabled(this, FormSettings.Section.BILLING)
        binding.switchMonthlyBilling.isChecked = BillingSettings.monthlyBillingEnabled(this)
        binding.switchEnableBilling.setOnCheckedChangeListener { _, on ->
            FormSettings.setEnabled(this, FormSettings.Section.BILLING, on)
            updateVisibility()
        }
        binding.switchMonthlyBilling.setOnCheckedChangeListener { _, on ->
            BillingSettings.setMonthlyBillingEnabled(this, on)
            updateVisibility()
        }

        // Currency
        val currencies = BillingSettings.CURRENCIES
        binding.actCurrency.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, currencies))
        binding.actCurrency.setText(BillingSettings.currency(this), false)
        binding.actCurrency.setOnItemClickListener { _, _, i, _ -> BillingSettings.setCurrency(this, currencies[i]) }

        binding.etDefaultRate.setText(
            BillingSettings.defaultHourlyRate(this)?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
        )

        // VAT (saved when leaving the screen)
        binding.etVat.setText(BillingSettings.vatPercent(this).let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() })

        binding.switchShowPrices.isChecked = BillingSettings.showPricesOnPdf(this)
        binding.switchShowPrices.setOnCheckedChangeListener { _, on -> BillingSettings.setShowPricesOnPdf(this, on) }

        // Invoice provider
        val labels = providers.map { getString(it.labelRes()) }
        binding.actProvider.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
        val current = BillingSettings.invoiceProvider(this)
        binding.actProvider.setText(getString(current.labelRes()), false)
        binding.actProvider.setOnItemClickListener { _, _, i, _ ->
            BillingSettings.setInvoiceProvider(this, providers[i])
            updateVisibility()
        }

        // Green Invoice key: the secret is never shown again once saved.
        binding.etApiKeyId.setText(SecretStore.get(this, SecretStore.GREEN_INVOICE_KEY_ID).orEmpty())
        if (SecretStore.has(this, SecretStore.GREEN_INVOICE_SECRET)) binding.etApiSecret.hint = getString(R.string.api_secret_saved)
        binding.switchSandbox.isChecked = BillingSettings.greenInvoiceSandbox(this)
        binding.switchSandbox.setOnCheckedChangeListener { _, on -> BillingSettings.setGreenInvoiceSandbox(this, on) }
        binding.btnTestInvoice.setOnClickListener { saveAndTest() }

        updateVisibility()
    }

    private fun updateVisibility() {
        fun View.show(on: Boolean) { visibility = if (on) View.VISIBLE else View.GONE }
        val monthly = BillingSettings.monthlyBillingEnabled(this)
        binding.billingOptions.show(BillingSettings.anyBillingEnabled(this))
        // Invoices are made from monthly reports.
        binding.tvInvoicesHeader.show(monthly)
        binding.tilInvoiceProvider.show(monthly)
        val provider = BillingSettings.invoiceProvider(this)
        binding.greenInvoiceGroup.show(monthly && provider == InvoiceProviderId.GREEN_INVOICE)
        binding.tvProviderStub.show(monthly && (provider == InvoiceProviderId.ICOUNT || provider == InvoiceProviderId.RIVHIT))
    }

    override fun onPause() {
        super.onPause()
        val rateText = binding.etDefaultRate.text.toString().trim()
        when {
            rateText.isEmpty() -> BillingSettings.setDefaultHourlyRate(this, null)
            else -> rateText.toDoubleOrNull()?.takeIf { it >= 0 }?.let { BillingSettings.setDefaultHourlyRate(this, it) }
        }
        binding.etVat.text.toString().trim().toDoubleOrNull()
            ?.takeIf { it in 0.0..100.0 }
            ?.let { BillingSettings.setVatPercent(this, it) }
    }


    private fun saveAndTest() {
        SecretStore.put(this, SecretStore.GREEN_INVOICE_KEY_ID, binding.etApiKeyId.text.toString().trim())
        val secret = binding.etApiSecret.text.toString().trim()
        if (secret.isNotEmpty()) {
            SecretStore.put(this, SecretStore.GREEN_INVOICE_SECRET, secret)
            binding.etApiSecret.text = null
            binding.etApiSecret.hint = getString(R.string.api_secret_saved)
        }
        val provider = InvoiceProvider.configured(this)
        if (provider == null) {
            binding.tvInvoiceStatus.setText(R.string.invoice_enter_key)
            return
        }
        binding.tvInvoiceStatus.setText(R.string.testing)
        binding.btnTestInvoice.isEnabled = false
        lifecycleScope.launch {
            try {
                provider.testConnection()
                binding.tvInvoiceStatus.setText(R.string.invoice_connected)
            } catch (e: Exception) {
                Log.w(TAG, "Invoice provider test failed", e)
                binding.tvInvoiceStatus.setText(R.string.invoice_connection_failed)
            } finally {
                binding.btnTestInvoice.isEnabled = true
            }
        }
    }

    private fun InvoiceProviderId.labelRes(): Int = when (this) {
        InvoiceProviderId.NONE -> R.string.invoice_provider_none
        InvoiceProviderId.GREEN_INVOICE -> R.string.invoice_provider_green
        InvoiceProviderId.ICOUNT -> R.string.invoice_provider_icount
        InvoiceProviderId.RIVHIT -> R.string.invoice_provider_rivhit
    }

    companion object {
        private const val TAG = "BillingSettings"
    }
}
