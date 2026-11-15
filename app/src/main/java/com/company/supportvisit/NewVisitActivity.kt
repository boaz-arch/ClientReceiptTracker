package com.company.supportvisit

import com.company.supportvisit.ui.applySystemBarInsets
import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.company.supportvisit.databinding.ActivityNewVisitBinding
import com.company.supportvisit.ui.NewVisitViewModel
import com.company.supportvisit.ui.VisitForm
import com.company.supportvisit.utils.DateUtils
import com.github.gcacace.signaturepad.views.SignaturePad
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class NewVisitActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewVisitBinding

    private val viewModel: NewVisitViewModel by viewModels()

    /** True while we fill the form programmatically, so it isn't treated as user edits. */
    private var restoring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityNewVisitBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        setupTimePickers()
        setupAutoSave()

        binding.btnClearSignature.setOnClickListener { binding.signaturePad.clear() }
        binding.btnSubmit.setOnClickListener {
            viewModel.submit(currentForm(), signatureOrNull())
        }

        restoreDraft(isFreshStart = savedInstanceState == null)

        observeState()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.tvDate.text = DateUtils.formatForDisplay(state.visitDate)

                    val busy = state.isSubmitting || state.isSubmitted
                    binding.btnSubmit.isEnabled = !busy
                    binding.progressBar.visibility = if (state.isSubmitting) View.VISIBLE else View.GONE

                    state.message?.let {
                        Toast.makeText(this@NewVisitActivity, it, Toast.LENGTH_LONG).show()
                        viewModel.messageShown()
                    }
                    if (state.isSubmitted) finish()
                }
            }
        }
    }

    /**
     * Text fields restore themselves after rotation or process death. The signature pad does not
     * (its saved state is too large for Android, so it's disabled in the layout), so the signature
     * always comes back from the auto-saved draft file.
     */
    private fun restoreDraft(isFreshStart: Boolean) {
        lifecycleScope.launch {
            val draft = viewModel.loadDraft()
            if (isFreshStart && draft != null) {
                restoring = true
                binding.etClientName.setText(draft.clientName)
                binding.etClientEmail.setText(draft.clientEmail)
                binding.etStartTime.setText(draft.startTime)
                binding.etEndTime.setText(draft.endTime)
                binding.etDescription.setText(draft.workDescription)
                restoring = false

                Snackbar.make(binding.root, R.string.draft_restored, DRAFT_SNACKBAR_MS)
                    .setAction(R.string.discard_draft) { discardDraft() }
                    .show()
            }
            if (binding.signaturePad.isEmpty) loadSignature(viewModel.signaturePath)
        }
    }

    private fun discardDraft() {
        viewModel.discardDraft()
        restoring = true
        listOf(
            binding.etClientName, binding.etClientEmail, binding.etStartTime,
            binding.etEndTime, binding.etDescription
        ).forEach { it.text = null }
        binding.signaturePad.clear()
        restoring = false
    }

    private fun loadSignature(path: String?) {
        if (path == null) return
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) } ?: return@launch
            restoring = true
            binding.signaturePad.signatureBitmap = bitmap
            restoring = false
        }
    }

    private fun setupAutoSave() {
        listOf(
            binding.etClientName, binding.etClientEmail, binding.etStartTime,
            binding.etEndTime, binding.etDescription
        ).forEach { field ->
            field.doAfterTextChanged { if (!restoring) viewModel.onFormChanged(currentForm()) }
        }

        binding.signaturePad.setOnSignedListener(object : SignaturePad.OnSignedListener {
            override fun onStartSigning() = Unit

            override fun onSigned() {
                if (!restoring) viewModel.onSignatureChanged(binding.signaturePad.signatureBitmap)
            }

            override fun onClear() {
                if (!restoring) viewModel.onSignatureChanged(null)
            }
        })
    }

    private fun setupTimePickers() {
        binding.etStartTime.setOnClickListener {
            showTimePicker(binding.etStartTime.text.toString()) { binding.etStartTime.setText(it) }
        }
        binding.etEndTime.setOnClickListener {
            showTimePicker(binding.etEndTime.text.toString()) { binding.etEndTime.setText(it) }
        }
    }

    private fun showTimePicker(current: String, onTimeSelected: (String) -> Unit) {
        val parts = current.split(":").mapNotNull { it.toIntOrNull() }
        val hour = parts.getOrNull(0) ?: DEFAULT_HOUR
        val minute = parts.getOrNull(1) ?: 0
        TimePickerDialog(
            this,
            { _, h, m -> onTimeSelected(String.format(Locale.US, "%02d:%02d", h, m)) },
            hour,
            minute,
            true
        ).show()
    }

    private fun currentForm() = VisitForm(
        clientName = binding.etClientName.text.toString(),
        clientEmail = binding.etClientEmail.text.toString(),
        startTime = binding.etStartTime.text.toString(),
        endTime = binding.etEndTime.text.toString(),
        workDescription = binding.etDescription.text.toString()
    )

    private fun signatureOrNull() =
        if (binding.signaturePad.isEmpty) null else binding.signaturePad.signatureBitmap

    companion object {
        private const val DEFAULT_HOUR = 9

        /** Long enough for a technician to notice the Discard option. */
        private const val DRAFT_SNACKBAR_MS = 10_000
    }
}
