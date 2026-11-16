package com.company.supportvisit

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.company.supportvisit.account.AccountDeletion
import com.company.supportvisit.dashboard.DashboardViewModel
import com.company.supportvisit.databinding.ActivityMainBinding
import com.company.supportvisit.graph.AuthManager
import com.company.supportvisit.graph.MicrosoftAuthManager
import com.company.supportvisit.ui.applySystemBarInsets
import com.company.supportvisit.utils.AppConfig
import com.company.supportvisit.utils.DateUtils
import com.company.supportvisit.utils.Money
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Home: greeting, the main actions (new visit, visit reports, clients) and this month at a glance.
 * Charts and filters live in [InsightsActivity] so this screen stays calm.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Default filter = this month, everyone: exactly the "This month" card. */
    private val viewModel: DashboardViewModel by viewModels()

    private var deletionPromptShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (AppConfig.LOGIN_ENABLED && !AuthManager.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        binding.tvToday.text = LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))

        binding.btnNewVisit.setOnClickListener { startActivity(Intent(this, NewVisitActivity::class.java)) }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnPastVisits.setOnClickListener { startActivity(PastVisitsActivity.intent(this)) }
        binding.tvUnsent.setOnClickListener { startActivity(PastVisitsActivity.intent(this)) }
        binding.btnClients.setOnClickListener { startActivity(Intent(this, ClientsActivity::class.java)) }
        binding.btnMonthlyReports.setOnClickListener { startActivity(Intent(this, MonthlyReportActivity::class.java)) }
        val openInsights = { startActivity(Intent(this, InsightsActivity::class.java)) }
        binding.btnInsights.setOnClickListener { openInsights() }
        binding.cardMonth.setOnClickListener { openInsights() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    if (MicrosoftAuthManager.refreshAccount() != null) checkPendingDeletion()
                    MicrosoftAuthManager.accountEmail.collect { email ->
                        binding.tvUser.text = if (email.isNullOrBlank()) {
                            getString(R.string.greeting_guest)
                        } else {
                            getString(R.string.greeting_name, email.substringBefore("@"))
                        }
                    }
                }
                launch {
                    viewModel.data.collect { d ->
                        if (d == null) return@collect
                        binding.tvKpiVisits.text = d.visitCount.toString()
                        binding.tvKpiHours.text = Money.hours(d.hours)
                        binding.colKpiAmount.isVisible = d.amount != null
                        d.amount?.let { binding.tvKpiAmount.text = Money.format(this@MainActivity, it) }
                        binding.tvUnsent.isVisible = d.unsentCount > 0
                        binding.tvUnsent.text = resources.getQuantityString(R.plurals.unsent_banner, d.unsentCount, d.unsentCount)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }


    /** If this account is scheduled for deletion, offer to restore it (within the 14 days). */
    private suspend fun checkPendingDeletion() {
        if (deletionPromptShown) return
        val status = try {
            AccountDeletion.status(this)
        } catch (e: Exception) {
            null // offline or not set up: ask next time
        } ?: return
        if (!status.pending) return
        deletionPromptShown = true
        val date = status.purgeAt?.let {
            DateUtils.formatForDisplay(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString())
        }.orEmpty()
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_account_title)
            .setMessage(getString(R.string.restore_account_message, date))
            .setCancelable(false)
            .setPositiveButton(R.string.restore_account) { _, _ ->
                lifecycleScope.launch {
                    try {
                        AccountDeletion.restore(this@MainActivity)
                        Toast.makeText(this@MainActivity, R.string.restore_account_done, Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, R.string.action_failed_offline, Toast.LENGTH_LONG).show()
                        deletionPromptShown = false
                    }
                }
            }
            .setNegativeButton(R.string.restore_account_keep_deleting) { _, _ ->
                lifecycleScope.launch {
                    AccountDeletion.wipeLocalData(this@MainActivity)
                    runCatching { MicrosoftAuthManager.signOut() }
                }
            }
            .show()
    }

}
