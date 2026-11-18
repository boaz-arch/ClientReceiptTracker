package com.company.supportvisit

import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.company.supportvisit.adapter.PhotoAdapter
import com.company.supportvisit.billing.Billing
import com.company.supportvisit.billing.RetainerLevel
import com.company.supportvisit.settings.BillingSettings
import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.data.PartItem
import com.company.supportvisit.data.VisitType
import com.company.supportvisit.data.partsTotal
import com.company.supportvisit.databinding.ActivityNewVisitBinding
import com.company.supportvisit.databinding.DialogPartBinding
import com.company.supportvisit.databinding.ItemPartBinding
import com.company.supportvisit.ui.ReportSections
import com.company.supportvisit.ui.NewVisitUiState
import com.company.supportvisit.ui.NewVisitViewModel
import com.company.supportvisit.ui.VisitForm
import com.company.supportvisit.ui.applySystemBarInsets
import com.company.supportvisit.ui.setUpBackBar
import com.company.supportvisit.ui.labelRes
import com.company.supportvisit.utils.DateUtils
import com.company.supportvisit.utils.Money
import com.github.gcacace.signaturepad.views.SignaturePad
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class NewVisitActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewVisitBinding

    private val viewModel: NewVisitViewModel by viewModels()

    /** True while we fill the form programmatically, so it isn't treated as user edits. */
    private var restoring = false

    private val photoAdapter = PhotoAdapter(onRemove = { viewModel.removePhoto(it) })

    /** Where the camera writes the full-size photo before it's compressed. */
    private val cameraFile by lazy { File(File(filesDir, "camera").apply { mkdirs() }, "capture.jpg") }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) viewModel.addPhotos(listOf(Uri.fromFile(cameraFile)))
    }

    private val pickPhotos = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(NewVisitViewModel.MAX_PHOTOS)
    ) { uris -> viewModel.addPhotos(uris) }

    private val visitTypes = VisitType.entries

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityNewVisitBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        setUpBackBar()

        setupTimePickers()
        setupClientPicker()
        setupVisitType()
        setupPhotos()
        setupAutoSave()

        binding.btnAddPart.setOnClickListener { showAddPartDialog() }
        binding.btnClearSignature.setOnClickListener { binding.signaturePad.clear() }
        binding.btnClearTechnicianSignature.setOnClickListener { binding.technicianSignaturePad.clear() }
        applyReportSections()

        binding.btnSubmit.setOnClickListener {
            viewModel.submit(
                currentForm(),
                binding.signaturePad.bitmapOrNull(),
                binding.technicianSignaturePad.bitmapOrNull().takeIf { sections.technicianSignature },
                sections
            )
        }

        restoreDraft(isFreshStart = savedInstanceState == null)

        observeState()
    }

    /** Which optional report sections are on (Settings > Customize report). */
    private val sections by lazy { ReportSections.from(this) }

    /** Hides the sections that are switched off. Photos stay visible if a draft already has some. */
    private fun applyReportSections() {
        fun show(on: Boolean, vararg views: View) = views.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
        show(sections.visitType, binding.tilVisitType)
        show(sections.photos, binding.tvPhotosHeader, binding.photoButtonsRow)
        show(sections.parts, binding.tvPartsHeader, binding.partsContainer, binding.partsActionsRow)
        show(sections.billing, binding.tvBillingHeader, binding.tvBillingSummary)
        show(
            sections.technicianSignature,
            binding.tvTechnicianSignatureHeader, binding.technicianSignatureFrame, binding.btnClearTechnicianSignature
        )
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.clients.collect { clients ->
                        binding.etClientName.setAdapter(
                            ArrayAdapter(
                                this@NewVisitActivity,
                                android.R.layout.simple_dropdown_item_1line,
                                clients.map(::ClientOption)
                            )
                        )
                    }
                }
            }
        }
    }

    private fun render(state: NewVisitUiState) {
        binding.tvDate.text = DateUtils.formatForDisplay(state.visitDate)

        val typeLabel = getString(state.visitType.labelRes())
        if (binding.actVisitType.text.toString() != typeLabel) binding.actVisitType.setText(typeLabel, false)

        photoAdapter.submitList(state.photoPaths)
        binding.rvPhotos.visibility = if (state.photoPaths.isEmpty() || !sections.photos) View.GONE else View.VISIBLE
        binding.tvPhotosHeader.text =
            getString(R.string.photos_header_count, state.photoPaths.size, NewVisitViewModel.MAX_PHOTOS)
        binding.photoProgress.visibility = if (state.isAddingPhotos) View.VISIBLE else View.GONE
        val canAddPhotos = state.photoPaths.size < NewVisitViewModel.MAX_PHOTOS && !state.isAddingPhotos
        binding.btnTakePhoto.isEnabled = canAddPhotos
        binding.btnPickPhotos.isEnabled = canAddPhotos

        renderParts(state.parts)
        renderBilling(state)
        renderRetainer(state)

        val busy = state.isSubmitting || state.isSubmitted
        binding.btnSubmit.isEnabled = !busy && !state.isAddingPhotos
        binding.progressBar.visibility = if (state.isSubmitting) View.VISIBLE else View.GONE

        state.message?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            viewModel.messageShown()
        }
        if (state.isSubmitted) finish()
    }

    /** Live "time × hourly rate (+ parts)" line from the start and end time, updated as the form changes. */
    private fun renderBilling(state: NewVisitUiState = viewModel.state.value) {
        if (!sections.billing) return
        val form = currentForm()
        val rate = state.client?.hourlyRate ?: BillingSettings.defaultHourlyRate(this)
        val bill = Billing.bill(
            form.startTime, form.endTime, 0, null, rate, if (sections.parts) state.parts else emptyList()
        )
        binding.tvBillingSummary.text = when {
            bill.durationMinutes == 0 -> getString(R.string.billing_enter_times)
            bill.hourlyRate == null -> getString(R.string.billing_summary_no_rate, Billing.formatMinutes(bill.billableMinutes))
            else -> getString(
                R.string.billing_summary,
                Billing.formatMinutes(bill.billableMinutes),
                Money.format(this, bill.hourlyRate),
                Money.format(this, bill.total)
            )
        }
    }

    /** "Support package: 7.5 of 10 h used this month", coloured at 80% and 100%. */
    private fun renderRetainer(state: NewVisitUiState) {
        val usage = state.retainer
        binding.tvRetainer.visibility = if (usage == null) View.GONE else View.VISIBLE
        if (usage == null) return
        binding.tvRetainer.text = getString(
            when (usage.level) {
                RetainerLevel.OK -> R.string.retainer_usage
                RetainerLevel.WARNING -> R.string.retainer_usage_warning
                RetainerLevel.OVER -> R.string.retainer_usage_over
            },
            Money.hours(usage.usedHours),
            Money.hours(usage.allowanceHours)
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

    private fun renderParts(parts: List<PartItem>) {
        binding.partsContainer.removeAllViews()
        parts.forEachIndexed { index, part ->
            val row = ItemPartBinding.inflate(LayoutInflater.from(this), binding.partsContainer, true)
            row.tvPartDescription.text = part.description
            row.tvPartDetail.text = getString(
                R.string.part_detail, Money.quantity(part.quantity), Money.format(this, part.unitPrice), Money.format(this, part.total)
            )
            row.btnRemovePart.setOnClickListener { viewModel.removePart(index) }
        }
        binding.tvPartsTotal.text =
            if (parts.isEmpty()) "" else getString(R.string.parts_total, Money.format(this, parts.partsTotal()))
    }

    private fun showAddPartDialog() {
        val dialogBinding = DialogPartBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.add_part)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val description = dialogBinding.etPartDescription.text.toString().trim()
                val quantity = dialogBinding.etPartQuantity.text.toString().toDoubleOrNull()
                val price = dialogBinding.etPartUnitPrice.text.toString().ifBlank { "0" }.toDoubleOrNull()
                if (description.isEmpty() || quantity == null || quantity <= 0 || price == null || price < 0) {
                    Toast.makeText(this, R.string.error_part_invalid, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                viewModel.addPart(PartItem(description, quantity, price))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * Text fields restore themselves after rotation or process death. The signature pads do not
     * (their saved state is too large for Android, so it's disabled in the layout), so signatures
     * always come back from the auto-saved draft files.
     */
    private fun restoreDraft(isFreshStart: Boolean) {
        lifecycleScope.launch {
            val draft = viewModel.loadDraft()
            if (isFreshStart && draft != null) {
                restoring = true
                binding.etClientName.setText(draft.clientName, false)
                binding.etClientEmail.setText(draft.clientEmail)
                binding.etStartTime.setText(draft.startTime)
                binding.etEndTime.setText(draft.endTime)
                binding.etDescription.setText(draft.workDescription)
                restoring = false

                Snackbar.make(binding.root, R.string.draft_restored, DRAFT_SNACKBAR_MS)
                    .setAction(R.string.discard_draft) { discardDraft() }
                    .show()
            }
            if (binding.signaturePad.isEmpty) loadSignature(binding.signaturePad, viewModel.signaturePath)
            if (binding.technicianSignaturePad.isEmpty) {
                loadSignature(binding.technicianSignaturePad, viewModel.technicianSignaturePath)
            }
        }
    }

    private fun discardDraft() {
        viewModel.discardDraft()
        restoring = true
        binding.etClientName.setText("", false)
        listOf(
            binding.etClientEmail, binding.etStartTime, binding.etEndTime, binding.etDescription
        ).forEach { it.text = null }
        binding.signaturePad.clear()
        binding.technicianSignaturePad.clear()
        restoring = false
    }

    private fun loadSignature(pad: SignaturePad, path: String?) {
        if (path == null) return
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) } ?: return@launch
            restoring = true
            pad.signatureBitmap = bitmap
            restoring = false
        }
    }

    private fun setupClientPicker() {
        binding.etClientName.setOnItemClickListener { parent, _, position, _ ->
            val option = parent.getItemAtPosition(position) as ClientOption
            viewModel.selectClient(option.client)
            restoring = true
            binding.etClientName.setText(option.client.name, false)
            if (option.client.email.isNotBlank()) binding.etClientEmail.setText(option.client.email)
            restoring = false
        }
    }

    private fun setupVisitType() {
        val labels = visitTypes.map { getString(it.labelRes()) }
        binding.actVisitType.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
        binding.actVisitType.setOnItemClickListener { _, _, position, _ -> viewModel.setVisitType(visitTypes[position]) }
    }

    private fun setupPhotos() {
        binding.rvPhotos.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvPhotos.adapter = photoAdapter

        binding.btnTakePhoto.setOnClickListener {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", cameraFile)
            try {
                takePicture.launch(uri)
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_LONG).show()
            }
        }
        binding.btnPickPhotos.setOnClickListener {
            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun setupAutoSave() {
        listOf(
            binding.etClientName, binding.etClientEmail, binding.etStartTime,
            binding.etEndTime, binding.etDescription
        ).forEach { field ->
            field.doAfterTextChanged {
                if (!restoring) viewModel.onFormChanged(currentForm())
                renderBilling()
            }
        }

        fun SignaturePad.autoSave(technician: Boolean) = setOnSignedListener(object : SignaturePad.OnSignedListener {
            override fun onStartSigning() = Unit

            override fun onSigned() {
                if (!restoring) viewModel.onSignatureChanged(signatureBitmap, technician)
            }

            override fun onClear() {
                if (!restoring) viewModel.onSignatureChanged(null, technician)
            }
        })
        binding.signaturePad.autoSave(technician = false)
        binding.technicianSignaturePad.autoSave(technician = true)
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

    private fun SignaturePad.bitmapOrNull() = if (isEmpty) null else signatureBitmap

    /** Shown in the client dropdown; ArrayAdapter filters on toString(). */
    private class ClientOption(val client: ClientEntity) {
        override fun toString() = client.name
    }

    companion object {
        private const val DEFAULT_HOUR = 9

        /** Long enough for a technician to notice the Discard option. */
        private const val DRAFT_SNACKBAR_MS = 10_000
    }
}
