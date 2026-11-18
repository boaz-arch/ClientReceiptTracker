package com.company.supportvisit

import com.company.supportvisit.ui.applySystemBarInsets
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.company.supportvisit.account.AccountDeletion
import com.company.supportvisit.account.UnsentVisitsException
import com.company.supportvisit.databinding.ActivitySettingsBinding
import com.company.supportvisit.databinding.DialogDeleteAccountBinding
import com.company.supportvisit.utils.DateUtils
import androidx.appcompat.app.AlertDialog
import com.company.supportvisit.auth.AuthSession
import com.company.supportvisit.security.AppLock
import com.company.supportvisit.settings.ThemeManager
import com.company.supportvisit.sync.SyncScheduler
import com.company.supportvisit.team.TeamRepository
import com.company.supportvisit.team.TeamSession
import androidx.core.view.isVisible
import com.company.supportvisit.ui.SignInHelper
import com.company.supportvisit.utils.CsvExporter
import com.company.supportvisit.plans.Feature
import com.company.supportvisit.plans.PlanManager
import com.company.supportvisit.settings.ServerSettings
import com.company.supportvisit.telemetry.Telemetry
import androidx.core.net.toUri
import kotlinx.coroutines.flow.first
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

        setUpLanguageToggle()

        binding.btnBillingSettings.setOnClickListener {
            PlanManager.runIfAllowed(this, Feature.BILLING) { startActivity(Intent(this, BillingSettingsActivity::class.java)) }
        }
        binding.btnCustomizeReport.setOnClickListener {
            startActivity(Intent(this, ReportSettingsActivity::class.java))
        }

        binding.btnCompanyProfile.setOnClickListener { startActivity(Intent(this, CompanySetupActivity::class.java)) }
        binding.btnTeamMembers.setOnClickListener { startActivity(Intent(this, TeamMembersActivity::class.java)) }

        binding.tvVersion.text = runCatching {
            getString(R.string.app_version, packageManager.getPackageInfo(packageName, 0).versionName)
        }.getOrDefault("")

        binding.switchAppLock.isChecked = AppLock.isEnabled(this)
        binding.switchAppLock.setOnCheckedChangeListener { view, checked ->
            if (checked == AppLock.isEnabled(this)) return@setOnCheckedChangeListener
            if (!AppLock.isAvailable(this)) {
                view.isChecked = false
                Toast.makeText(this, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            // Check they can unlock before turning it on (and before turning it off).
            AppLock.confirm(this) { ok ->
                if (ok) AppLock.setEnabled(this, checked) else view.isChecked = !checked
            }
        }

        binding.btnExportCsv.setOnClickListener { exportCsv() }

        binding.switchTelemetry.isChecked = Telemetry.isEnabled(this)
        binding.switchTelemetry.setOnCheckedChangeListener { _, checked -> Telemetry.setEnabled(this, checked) }
        binding.btnPrivacyPolicy.setOnClickListener { openLegal("privacy") }
        binding.btnTerms.setOnClickListener { openLegal("terms") }
        binding.btnClearLocalData.setOnClickListener { confirmClearLocalData() }

        binding.btnConnectMicrosoft.setOnClickListener { signIn() }
        binding.btnDeleteAccount.setOnClickListener { confirmDeleteAccount() }
        binding.btnDisconnectMicrosoft.setOnClickListener { signOut() }

    }

    override fun onResume() {
        super.onResume()
        applyRole()
        showAccount()
        // Coming back from Billing or Customize report: share the admin's choices with the team.
        lifecycleScope.launch { TeamRepository.pushSettings(this@SettingsActivity) }
    }

    /** Company, billing and report screens belong to admins; a phone working alone is its own admin. */
    private fun applyRole() {
        val admin = TeamSession.role(this).canManageCompany
        binding.sectionCompany.isVisible = admin
        binding.btnTeamMembers.isVisible = admin && TeamSession.isTeamMember(this)
        binding.headerReportSettings.isVisible = admin
        binding.btnBillingSettings.isVisible = admin
        binding.btnCustomizeReport.isVisible = admin
    }

    /** System / English / Hebrew. AppCompat restarts the screens in the new language. */
    private fun setUpLanguageToggle() {
        val languages = mapOf(
            R.id.btnLanguageSystem to "",
            R.id.btnLanguageEnglish to "en",
            R.id.btnLanguageHebrew to "he"
        )
        val current = AppCompatDelegate.getApplicationLocales().get(0)?.language.orEmpty()
            .let { if (it == "iw") "he" else it }
        languages.entries.firstOrNull { it.value == current }?.let { binding.languageToggle.check(it.key) }
        binding.languageToggle.addOnButtonCheckedListener { _, id, checked ->
            val tag = languages[id] ?: return@addOnButtonCheckedListener
            if (checked && tag != current) {
                AppCompatDelegate.setApplicationLocales(
                    if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
                )
            }
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

    /** Privacy policy and terms are published by the server at /legal/{privacy,terms}. */
    private fun openLegal(doc: String) {
        val url = ServerSettings.serverUrl(this)
        if (url == null) {
            Toast.makeText(this, R.string.legal_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, "$url/legal/$doc".toUri())) }
            .onFailure { Toast.makeText(this, R.string.legal_unavailable, Toast.LENGTH_LONG).show() }
    }

    /** Removes everything on this phone (not on the server) and signs out. Unsent visits would be lost, so it refuses. */
    private fun confirmClearLocalData() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_local_data)
            .setMessage(R.string.clear_local_data_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear_local_data_confirm) { _, _ -> clearLocalData() }
            .show()
    }

    private fun clearLocalData() {
        binding.btnClearLocalData.isEnabled = false
        lifecycleScope.launch {
            try {
                if (app.visitRepository.observeUnsentCount().first() > 0) {
                    Toast.makeText(this@SettingsActivity, R.string.clear_local_data_unsent, Toast.LENGTH_LONG).show()
                    return@launch
                }
                runCatching { AuthSession.signOut() }
                AccountDeletion.wipeLocalData(this@SettingsActivity)
                AuthSession.clear()
                TeamSession.clear(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, R.string.clear_local_data_done, Toast.LENGTH_LONG).show()
                startActivity(Intent(this@SettingsActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            } catch (e: Exception) {
                Log.w(TAG, "Clearing local data failed", e)
                Toast.makeText(this@SettingsActivity, R.string.clear_local_data_failed, Toast.LENGTH_LONG).show()
            } finally {
                binding.btnClearLocalData.isEnabled = true
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

    /** Signed out: sign in. Signed in: the same screen checks the company (join / create). */
    private fun signIn() {
        startActivity(SignInActivity.intent(this))
    }

    private fun showAccount() {
        val email = AuthSession.accountEmail.value
        binding.btnConnectMicrosoft.text = if (email == null) getString(R.string.sign_in_title) else getString(R.string.signed_in_as, email)
        binding.btnDisconnectMicrosoft.isVisible = email != null
    }

    private fun signOut() {
        lifecycleScope.launch {
            try {
                AuthSession.signOut()
                TeamSession.clear(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, R.string.signed_out, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.w(TAG, "Sign-out failed", e)
                Toast.makeText(this@SettingsActivity, R.string.sign_out_failed, Toast.LENGTH_LONG).show()
            }
            showAccount()
            applyRole()
        }
    }

    companion object {
        private const val TAG = "SettingsActivity"
    }
}
