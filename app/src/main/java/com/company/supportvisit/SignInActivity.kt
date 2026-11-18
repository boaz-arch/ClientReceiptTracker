package com.company.supportvisit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.company.supportvisit.auth.AuthSession
import com.company.supportvisit.databinding.ActivitySignInBinding
import com.company.supportvisit.graph.GraphException
import com.company.supportvisit.remote.ServerApi
import com.company.supportvisit.settings.ServerSettings
import com.company.supportvisit.sync.SyncScheduler
import com.company.supportvisit.team.TeamRepository
import com.company.supportvisit.ui.applySystemBarInsets
import com.company.supportvisit.ui.setUpBackBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Sign in with email: the server emails a 6-digit code. Then the person joins the company
 * that invited them, or starts their own company (and becomes its admin).
 */
class SignInActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignInBinding
    private var email = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignInBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        setUpBackBar()

        binding.btnSendCode.setOnClickListener { sendCode() }
        binding.etEmail.setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_SEND).also { if (it) sendCode() } }
        binding.btnVerify.setOnClickListener { verify() }
        binding.etCode.setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_DONE).also { if (it) verify() } }
        binding.btnResend.setOnClickListener { sendCode() }
        binding.btnOtherEmail.setOnClickListener { showStep(Step.EMAIL) }
        binding.btnCreateCompany.setOnClickListener { createCompany() }
        binding.btnCheckAgain.setOnClickListener { checkCompany() }
        binding.btnLater.setOnClickListener { finish() }

        // Already signed in but not in a company yet (e.g. opened from a "no company" error).
        if (AuthSession.isSignedIn) {
            email = AuthSession.accountEmail.value.orEmpty()
            checkCompany()
        } else {
            showStep(Step.EMAIL)
        }
    }

    private enum class Step { EMAIL, CODE, COMPANY }

    private fun showStep(step: Step) {
        binding.stepEmail.isVisible = step == Step.EMAIL
        binding.stepCode.isVisible = step == Step.CODE
        binding.stepCompany.isVisible = step == Step.COMPANY
        when (step) {
            Step.EMAIL -> {
                binding.tvTitle.setText(R.string.sign_in_title)
                binding.tvSubtitle.setText(R.string.sign_in_subtitle)
            }
            Step.CODE -> {
                binding.tvTitle.setText(R.string.sign_in_code_title)
                binding.tvSubtitle.text = getString(R.string.sign_in_code_sent, email)
                binding.etCode.text = null
                binding.etCode.requestFocus()
            }
            Step.COMPANY -> {
                binding.tvTitle.setText(R.string.sign_in_company_title)
                binding.tvSubtitle.text = getString(R.string.sign_in_signed_in_as, email)
                binding.tvJoinHint.text = getString(R.string.sign_in_join_hint, email)
            }
        }
    }

    private fun sendCode() {
        val typed = binding.etEmail.text.toString().trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(typed).matches()) {
            binding.etEmail.error = getString(R.string.error_invalid_email)
            return
        }
        email = typed
        busy {
            AuthSession.sendCode(email)
            showStep(Step.CODE)
        }
    }

    private fun verify() {
        val code = binding.etCode.text.toString().trim()
        if (code.length != 6) {
            binding.etCode.error = getString(R.string.sign_in_code_invalid)
            return
        }
        busy {
            email = AuthSession.verifyCode(email, code)
            checkMembership()
        }
    }

    private fun checkCompany() = busy { checkMembership() }

    /** In a company: done. Otherwise show invitations and the "create a company" option. */
    private suspend fun checkMembership() {
        val api = api() ?: return
        val (status, body) = withContext(Dispatchers.IO) { api.membership() }
        when (status) {
            200 -> done()
            409 -> showCompanyChoices(body)
            403 -> {
                // Removed from their company: they can still start a company of their own.
                showCompanyChoices(JSONObject())
                binding.tvJoinHint.text = getString(R.string.sign_in_removed, email)
            }
            401 -> {
                AuthSession.clear()
                showStep(Step.EMAIL)
            }
            else -> throw IOException("HTTP $status")
        }
    }

    private fun showCompanyChoices(body: JSONObject) {
        showStep(Step.COMPANY)
        binding.listInvites.removeAllViews()
        val invites = body.optJSONArray("invites")
        for (i in 0 until (invites?.length() ?: 0)) {
            val invite = invites!!.getJSONObject(i)
            val tenantId = invite.optString("tenantId")
            val name = invite.optString("companyName").ifBlank { getString(R.string.sign_in_a_company) }
            val button = layoutInflater.inflate(R.layout.item_invite_button, binding.listInvites, false) as Button
            button.text = getString(R.string.sign_in_join, name)
            button.setOnClickListener {
                busy {
                    withContext(Dispatchers.IO) { api()?.acceptInvite(tenantId) }
                    done()
                }
            }
            binding.listInvites.addView(button)
        }
    }

    private fun createCompany() {
        val name = binding.etCompanyName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etCompanyName.error = getString(R.string.company_name_required)
            return
        }
        busy {
            withContext(Dispatchers.IO) { api()?.createCompany(name) }
            done()
        }
    }

    private suspend fun done() {
        TeamRepository.refresh(this)
        app.visitRepository.retryAllFailed()
        SyncScheduler.enqueue(this)
        Toast.makeText(this, getString(R.string.connected_as, email), Toast.LENGTH_LONG).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun api(): ServerApi? {
        val url = ServerSettings.serverUrl(this)
        if (url == null) {
            Toast.makeText(this, R.string.server_not_configured, Toast.LENGTH_LONG).show()
            return null
        }
        return ServerApi(url, runCatching { AuthSession.getApiToken() }.getOrDefault(""))
    }

    private fun busy(block: suspend () -> Unit) {
        binding.progress.isVisible = true
        setButtonsEnabled(false)
        lifecycleScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "Sign-in step failed: ${e.javaClass.simpleName}")
                Toast.makeText(this@SignInActivity, messageFor(e), Toast.LENGTH_LONG).show()
            } finally {
                binding.progress.isVisible = false
                setButtonsEnabled(true)
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        listOf(binding.btnSendCode, binding.btnVerify, binding.btnResend, binding.btnCreateCompany, binding.btnCheckAgain)
            .forEach { it.isEnabled = enabled }
        for (i in 0 until binding.listInvites.childCount) binding.listInvites.getChildAt(i).isEnabled = enabled
    }

    private fun messageFor(e: Exception): Int = when {
        e is GraphException && e.code == 401 -> R.string.sign_in_code_wrong
        e is GraphException && e.code == 429 -> R.string.sign_in_wait
        e is GraphException && e.code == 503 -> R.string.sign_in_email_unavailable
        e is GraphException && e.code == 400 -> R.string.error_invalid_email
        e is IOException -> R.string.action_failed_offline
        else -> R.string.sign_in_failed
    }

    companion object {
        private const val TAG = "SignIn"

        fun intent(context: Context) = Intent(context, SignInActivity::class.java)
    }
}
