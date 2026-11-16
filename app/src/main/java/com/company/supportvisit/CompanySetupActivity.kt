package com.company.supportvisit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.company.supportvisit.databinding.ActivityCompanySetupBinding
import com.company.supportvisit.team.CompanyProfile
import com.company.supportvisit.team.TeamRepository
import com.company.supportvisit.team.TeamSession
import com.company.supportvisit.ui.applySystemBarInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Admin: the company's name, details and logo (shown on every PDF report), plus the report
 * email: extra recipients and a subject/body template.
 */
class CompanySetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompanySetupBinding

    /** A newly picked logo, already resized (null = unchanged). */
    private var newLogo: File? = null
    private var removeLogo = false

    private val pickLogo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) lifecycleScope.launch { loadLogo(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompanySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        if (!TeamSession.role(this).canManageCompany) {
            finish()
            return
        }

        fill(TeamSession.company(this))
        showLogo(TeamSession.logoFile(this))

        binding.btnPickLogo.setOnClickListener {
            pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnRemoveLogo.setOnClickListener {
            newLogo = null
            removeLogo = true
            showLogo(null)
        }
        binding.btnSave.setOnClickListener { save() }
    }

    private fun fill(p: CompanyProfile) {
        binding.etName.setText(p.name)
        binding.etCompanyNumber.setText(p.companyNumber)
        binding.etAddress.setText(p.address)
        binding.etPhone.setText(p.phone)
        binding.etEmail.setText(p.email)
        binding.etWebsite.setText(p.website)
        binding.etRecipients.setText(p.emailRecipients.joinToString(", "))
        binding.etSubject.setText(p.emailSubjectTemplate.ifBlank { getString(R.string.default_email_subject_template) })
        binding.etBody.setText(p.emailBodyTemplate.ifBlank { getString(R.string.default_email_body_template) })
    }

    private fun showLogo(file: File?) {
        if (file != null && file.exists()) {
            // Decode a fresh copy (ImageView caches by URI).
            binding.ivLogo.setImageBitmap(BitmapFactory.decodeFile(file.path))
            binding.tvLogoState.setText(R.string.company_logo_set)
        } else {
            binding.ivLogo.setImageDrawable(null)
            binding.tvLogoState.setText(R.string.company_logo_none)
        }
        binding.btnRemoveLogo.isVisible = file != null && file.exists()
    }

    /** Resizes the picked image to at most [LOGO_MAX_PX] and stores it as a PNG (well under the 2 MB limit). */
    private suspend fun loadLogo(uri: Uri) {
        val file = withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= LOGO_MAX_PX || bounds.outHeight / (sample * 2) >= LOGO_MAX_PX) sample *= 2
                val decoded = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: return@withContext null
                val scale = minOf(1f, LOGO_MAX_PX.toFloat() / maxOf(decoded.width, decoded.height))
                val bitmap = if (scale < 1f) {
                    Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
                } else decoded
                File(cacheDir, "logo_pick.png").also { out ->
                    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the picked logo")
                null
            }
        }
        if (file == null) {
            Toast.makeText(this, R.string.company_logo_failed, Toast.LENGTH_LONG).show()
            return
        }
        newLogo = file
        removeLogo = false
        showLogo(file)
    }

    private fun save() {
        val name = binding.etName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etName.error = getString(R.string.company_name_required)
            return
        }
        val recipients = binding.etRecipients.text.toString()
            .split(',', ';', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val bad = recipients.firstOrNull { !Patterns.EMAIL_ADDRESS.matcher(it).matches() }
        if (bad != null) {
            binding.etRecipients.error = getString(R.string.error_invalid_email_named, bad)
            return
        }
        val email = binding.etEmail.text.toString().trim()
        if (email.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.error = getString(R.string.error_invalid_email)
            return
        }
        val profile = CompanyProfile(
            name = name,
            address = binding.etAddress.text.toString().trim(),
            companyNumber = binding.etCompanyNumber.text.toString().trim(),
            phone = binding.etPhone.text.toString().trim(),
            email = email,
            website = binding.etWebsite.text.toString().trim(),
            emailRecipients = recipients,
            emailSubjectTemplate = binding.etSubject.text.toString().trim(),
            emailBodyTemplate = binding.etBody.text.toString().trim()
        )
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            try {
                TeamRepository.saveCompany(this@CompanySetupActivity, profile, newLogo, removeLogo)
                Toast.makeText(this@CompanySetupActivity, R.string.company_saved, Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Log.w(TAG, "Saving the company profile failed")
                Toast.makeText(this@CompanySetupActivity, R.string.action_failed_offline, Toast.LENGTH_LONG).show()
                binding.btnSave.isEnabled = true
            }
        }
    }

    companion object {
        private const val TAG = "CompanySetup"
        private const val LOGO_MAX_PX = 512
    }
}
