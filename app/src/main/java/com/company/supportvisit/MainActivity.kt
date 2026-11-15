package com.company.supportvisit

import com.company.supportvisit.ui.applySystemBarInsets
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.company.supportvisit.account.AccountDeletion
import com.company.supportvisit.utils.DateUtils
import java.time.Instant
import java.time.ZoneId
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.company.supportvisit.databinding.ActivityMainBinding
import com.company.supportvisit.graph.AuthManager
import com.company.supportvisit.graph.MicrosoftAuthManager
import com.company.supportvisit.utils.AppConfig
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var deletionPromptShown = false

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

        binding.tvUser.setText(R.string.loading_user)

        binding.btnNewVisit.setOnClickListener {
            startActivity(Intent(this, NewVisitActivity::class.java))
        }
        binding.btnPastVisits.setOnClickListener {
            startActivity(PastVisitsActivity.intent(this))
        }
        binding.btnMonthlyReports.setOnClickListener {
            startActivity(Intent(this, MonthlyReportActivity::class.java))
        }
        binding.btnClients.setOnClickListener {
            startActivity(Intent(this, ClientsActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (MicrosoftAuthManager.refreshAccount() != null) checkPendingDeletion()
                MicrosoftAuthManager.accountEmail.collect { email ->
                    binding.tvUser.text = if (email.isNullOrBlank()) {
                        getString(R.string.signed_in_guest)
                    } else {
                        getString(R.string.signed_in_as, email.substringBefore("@"))
                    }
                }
            }
        }
    }
}
