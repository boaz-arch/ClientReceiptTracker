package com.company.supportvisit

import com.company.supportvisit.ui.applySystemBarInsets
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.company.supportvisit.account.AccountDeletion
import com.company.supportvisit.account.UnsentVisitsException
import com.company.supportvisit.databinding.ActivitySettingsBinding
import com.company.supportvisit.databinding.DialogDeleteAccountBinding
import com.company.supportvisit.utils.DateUtils
import androidx.appcompat.app.AlertDialog
import com.company.supportvisit.graph.AuthManager
import com.company.supportvisit.graph.MicrosoftAuthManager
import com.company.supportvisit.settings.ThemeManager
import com.company.supportvisit.sync.SyncScheduler
import com.company.supportvisit.ui.SignInHelper
import com.company.supportvisit.utils.AppConfig
import com.company.supportvisit.utils.CsvExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        val modes = mapOf(
            R.id.btnLightMode to AppCompatDelegate.MODE_NIGHT_NO,
            R.id.btnDarkMode to AppCompatDelegate.MODE_NIGHT_YES,
            R.id.btnSystemMode to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )
        val current = ThemeManager.savedMode(this)
        modes.entries.firstOrNull { it.value == current }?.let { binding.themeToggle.check(it.key) }
        binding.themeToggle.addOnButtonCheckedListener { _, id, checked ->
            val mode = modes[id]
            if (checked && mode != null && mode != ThemeManager.savedMode(this)) applyThemeMode(mode)
        }

        binding.btnBillingSettings.setOnClickListener {
            startActivity(Intent(this, BillingSettingsActivity::class.java))
        }
        binding.btnCustomizeReport.setOnClickListener {
            startActivity(Intent(this, ReportSettingsActivity::class.java))
        }

        binding.btnExportCsv.setOnClickListener { exportCsv() }

        binding.btnConnectMicrosoft.setOnClickListener { connectMicrosoft() }
        binding.btnDeleteAccount.setOnClickListener { confirmDeleteAccount() }
        binding.btnDisconnectMicrosoft.setOnClickListener { disconnectMicrosoft() }

        // Local login buttons only make sense when the local login screen is enabled.
        val localLoginVisibility = if (AppConfig.LOGIN_ENABLED) View.VISIBLE else View.GONE
        binding.btnChangeLogin.visibility = localLoginVisibility
        binding.btnLogout.visibility = localLoginVisibility

        binding.btnChangeLogin.setOnClickListener {
            startActivity(Intent(this, ChangeLoginActivity::class.java))
        }
        binding.btnLogout.setOnClickListener {
            AuthManager.logout()
            startActivity(Intent(this, LoginActivity::class.java))
            finishAffinity()
        }
    }

    private fun applyThemeMode(mode: Int) {
        ThemeManager.setMode(this, mode)
    }

    private fun exportCsv() {
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val visits = app.visitRepository.getAllVisits()
                    CsvExporter.writeTo(File(File(filesDir, "exports"), "SupportVisits.csv"), visits)
                }
                val uri = FileProvider.getUriForFile(this@SettingsActivity, "$packageName.provider", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, getString(R.string.share_csv)))
            } catch (e: Exception) {
                Log.w(TAG, "CSV export failed", e)
                Toast.makeText(this@SettingsActivity, R.string.export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Delete account: wipe everything by default; optionally keep reports in the company records. */
    private fun confirmDeleteAccount() {
        val dialogBinding = DialogDeleteAccountBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_account)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_account_confirm) { _, _ ->
                deleteAccount(keepShared = dialogBinding.cbKeepShared.isChecked)
            }
            .show()
    }

    private fun deleteAccount(keepShared: Boolean) {
        binding.btnDeleteAccount.isEnabled = false
        lifecycleScope.launch {
            try {
                if (!SignInHelper.ensureSignedIn(this@SettingsActivity)) return@launch
                val purgeAt = AccountDeletion.request(this@SettingsActivity, keepShared)
                val message = if (purgeAt != null) {
                    getString(R.string.delete_account_scheduled, DateUtils.formatForDisplay(
                        java.time.Instant.ofEpochMilli(purgeAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
                    ))
                } else {
                    getString(R.string.delete_account_done_local)
                }
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                startActivity(Intent(this@SettingsActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            } catch (e: UnsentVisitsException) {
                Toast.makeText(this@SettingsActivity, R.string.delete_account_unsent, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.w(TAG, "Account deletion failed", e)
                Toast.makeText(this@SettingsActivity, R.string.delete_account_failed, Toast.LENGTH_LONG).show()
            } finally {
                binding.btnDeleteAccount.isEnabled = true
            }
        }
    }

    private fun connectMicrosoft() {
        lifecycleScope.launch {
            if (!SignInHelper.ensureSignedIn(this@SettingsActivity)) return@launch
            val email = MicrosoftAuthManager.accountEmail.value.orEmpty()
            Toast.makeText(
                this@SettingsActivity, getString(R.string.connected_as, email), Toast.LENGTH_LONG
            ).show()
            // Visits that failed for lack of a session can go now.
            app.visitRepository.retryAllFailed()
            SyncScheduler.enqueue(this@SettingsActivity)
        }
    }

    private fun disconnectMicrosoft() {
        lifecycleScope.launch {
            try {
                MicrosoftAuthManager.signOut()
                Toast.makeText(this@SettingsActivity, R.string.microsoft_disconnected, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.w(TAG, "Sign-out failed", e)
                Toast.makeText(this@SettingsActivity, R.string.sign_out_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val TAG = "SettingsActivity"
    }
}
