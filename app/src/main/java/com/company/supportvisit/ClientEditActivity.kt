package com.company.supportvisit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.databinding.ActivityClientEditBinding
import com.company.supportvisit.sync.SyncScheduler
import com.company.supportvisit.ui.applySystemBarInsets
import com.company.supportvisit.utils.ValidationUtils
import kotlinx.coroutines.launch

/** Add a new client, or edit one when started with a client id. */
class ClientEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClientEditBinding

    private var existing: ClientEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityClientEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        val clientId = intent.getStringExtra(EXTRA_CLIENT_ID)
        if (clientId != null) {
            binding.tvTitle.setText(R.string.edit_client)
            lifecycleScope.launch {
                existing = app.clientRepository.getClient(clientId)
                // Only fill the form on first open; after rotation the fields restore themselves.
                if (savedInstanceState == null) existing?.let(::fill)
            }
        }

        binding.btnSave.setOnClickListener { save() }
    }

    private fun fill(c: ClientEntity) {
        binding.etName.setText(c.name)
        binding.etContactPerson.setText(c.contactPerson)
        binding.etEmail.setText(c.email)
        binding.etPhone.setText(c.phone)
        binding.etAddress.setText(c.address)
        binding.etHourlyRate.setText(c.hourlyRate?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() })
        binding.etNotes.setText(c.notes)
    }

    private fun save() {
        val name = binding.etName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val rateText = binding.etHourlyRate.text.toString().trim()
        val rate = rateText.toDoubleOrNull()

        when {
            name.isEmpty() -> return toast(R.string.error_client_name_required)
            email.isNotEmpty() && !ValidationUtils.isValidEmail(email) -> return toast(R.string.error_invalid_email)
            rateText.isNotEmpty() && (rate == null || rate < 0) -> return toast(R.string.error_invalid_rate)
        }

        val client = (existing ?: ClientEntity(id = "", name = name)).copy(
            name = name,
            contactPerson = binding.etContactPerson.text.toString().trim(),
            email = email,
            phone = binding.etPhone.text.toString().trim(),
            address = binding.etAddress.text.toString().trim(),
            hourlyRate = rate,
            notes = binding.etNotes.text.toString().trim()
        )

        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            app.clientRepository.save(client)
            SyncScheduler.enqueue(this@ClientEditActivity)
            toast(R.string.client_saved)
            finish()
        }
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val EXTRA_CLIENT_ID = "client_id"

        fun intent(context: Context, clientId: String?) =
            Intent(context, ClientEditActivity::class.java).apply { clientId?.let { putExtra(EXTRA_CLIENT_ID, it) } }
    }
}
