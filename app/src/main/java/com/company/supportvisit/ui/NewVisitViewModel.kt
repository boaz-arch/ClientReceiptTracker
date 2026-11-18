package com.company.supportvisit.ui

import android.app.Application
import com.company.supportvisit.telemetry.Telemetry
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.company.supportvisit.R
import com.company.supportvisit.app
import com.company.supportvisit.billing.RetainerCalculator
import com.company.supportvisit.billing.RetainerUsage
import com.company.supportvisit.billing.isoRange
import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.data.DraftEntity
import com.company.supportvisit.data.VisitEntity
import com.company.supportvisit.data.VisitPhoto
import com.company.supportvisit.utils.PdfReportGenerator
import com.company.supportvisit.data.PartItem
import com.company.supportvisit.data.VisitType
import com.company.supportvisit.auth.AuthSession
import com.company.supportvisit.repository.NewVisitInput
import com.company.supportvisit.settings.BillingSettings
import com.company.supportvisit.sync.SyncScheduler
import com.company.supportvisit.utils.DateUtils
import com.company.supportvisit.utils.PhotoCompressor
import com.company.supportvisit.utils.ValidationUtils
import com.company.supportvisit.utils.VisitValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.YearMonth

/** The text fields of the New Visit form. */
data class VisitForm(
    val clientName: String = "",
    val clientEmail: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val workDescription: String = ""
)

data class NewVisitUiState(
    val visitDate: String = DateUtils.todayIso(),
    val clientId: String? = null,
    val visitType: VisitType = VisitType.OTHER,
    val photoPaths: List<String> = emptyList(),
    val parts: List<PartItem> = emptyList(),
    /** The picked client (for hourly rate and support package), if any. */
    val client: ClientEntity? = null,
    /** This month's support package usage for the picked client, if it has a package. */
    val retainer: RetainerUsage? = null,
    val isAddingPhotos: Boolean = false,
    val isSubmitting: Boolean = false,
    val isSubmitted: Boolean = false,
    @StringRes val message: Int? = null
)

/**
 * Owns the New Visit form state, draft auto-save and submitting, so none of it restarts or is
 * lost when the screen rotates.
 */
class NewVisitViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.app
    private val repository = app.visitRepository

    private val _state = MutableStateFlow(NewVisitUiState())
    val state: StateFlow<NewVisitUiState> = _state.asStateFlow()

    /** Clients for the searchable picker. */
    val clients: StateFlow<List<ClientEntity>> = app.clientRepository.observeClients()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var form = VisitForm()

    /** Path of the auto-saved client signature, if the client has signed. */
    var signaturePath: String? = null
        private set

    /** Path of the auto-saved technician signature (optional). */
    var technicianSignaturePath: String? = null
        private set

    private var saveJob: Job? = null
    private var draftDirty = false
    private var draftLoaded = false

    private val locked get() = _state.value.isSubmitting || _state.value.isSubmitted

    /** Loads the saved draft once per screen; returns null if there is none. */
    suspend fun loadDraft(): DraftEntity? {
        if (draftLoaded) return null
        draftLoaded = true
        val draft = repository.getDraft() ?: return null
        form = VisitForm(draft.clientName, draft.clientEmail, draft.startTime, draft.endTime, draft.workDescription)
        signaturePath = draft.signaturePath
        technicianSignaturePath = draft.technicianSignaturePath
        _state.update {
            it.copy(
                visitDate = draft.visitDate.ifBlank { it.visitDate },
                clientId = draft.clientId,
                visitType = VisitType.fromKey(draft.visitType),
                photoPaths = draft.photoPaths.filter { path -> File(path).exists() },
                parts = draft.parts
            )
        }
        draft.clientId?.let { id -> app.clientRepository.getClient(id)?.let { loadClientInfo(it) } }
        return draft
    }

    /** Remembers the picked client and works out this month's package usage for it. */
    private suspend fun loadClientInfo(client: ClientEntity) {
        val month = YearMonth.now()
        val (from, to) = month.isoRange()
        val visits = repository.getVisitsInRange(from, to, client.id)
        _state.update { it.copy(client = client, retainer = RetainerCalculator.usage(client, visits, month)) }
    }

    fun onFormChanged(newForm: VisitForm) {
        if (newForm == form) return
        // Typing a different name than the picked client's means it's no longer that client.
        val clientId = _state.value.clientId
        if (clientId != null && newForm.clientName != form.clientName) {
            val picked = clients.value.firstOrNull { it.id == clientId }
            if (picked == null || picked.name != newForm.clientName) {
                _state.update { it.copy(clientId = null, client = null, retainer = null) }
            }
        }
        form = newForm
        scheduleDraftSave()
    }

    fun selectClient(client: ClientEntity) {
        _state.update { it.copy(clientId = client.id) }
        viewModelScope.launch { loadClientInfo(client) }
        form = form.copy(clientName = client.name, clientEmail = client.email.ifBlank { form.clientEmail })
        scheduleDraftSave()
    }

    fun setVisitType(type: VisitType) {
        if (type == _state.value.visitType) return
        _state.update { it.copy(visitType = type) }
        scheduleDraftSave()
    }

    fun addPhotos(uris: List<Uri>) {
        if (locked || uris.isEmpty()) return
        val room = MAX_PHOTOS - _state.value.photoPaths.size
        if (room <= 0) {
            _state.update { it.copy(message = R.string.photo_limit_reached) }
            return
        }
        _state.update { it.copy(isAddingPhotos = true) }
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.take(room).mapNotNull { uri ->
                    try {
                        PhotoCompressor.compress(getApplication(), uri, repository.draftPhotoDir)?.absolutePath
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not read a photo", e)
                        null
                    }
                }
            }
            _state.update {
                it.copy(
                    photoPaths = it.photoPaths + added,
                    isAddingPhotos = false,
                    message = when {
                        added.size < uris.take(room).size -> R.string.photo_read_failed
                        uris.size > room -> R.string.photo_limit_reached
                        else -> null
                    }
                )
            }
            scheduleDraftSave()
        }
    }

    fun removePhoto(path: String) {
        if (locked) return
        _state.update { it.copy(photoPaths = it.photoPaths - path) }
        viewModelScope.launch(Dispatchers.IO) { File(path).delete() }
        scheduleDraftSave()
    }

    fun addPart(part: PartItem) {
        if (locked) return
        _state.update { it.copy(parts = it.parts + part) }
        scheduleDraftSave()
    }

    fun removePart(index: Int) {
        if (locked) return
        _state.update { it.copy(parts = it.parts.filterIndexed { i, _ -> i != index }) }
        scheduleDraftSave()
    }

    fun onSignatureChanged(signature: Bitmap?, technician: Boolean = false) {
        if (locked) return
        viewModelScope.launch {
            val path = repository.saveDraftSignature(signature, technician)
            if (technician) technicianSignaturePath = path else signaturePath = path
            scheduleDraftSave()
        }
    }

    /** Whether anything has been filled in (today's date alone doesn't count). */
    fun hasContent(): Boolean = !currentDraft().isEmpty()

    /**
     * Starts this form from an earlier visit: same client, email, visit type, description and
     * parts, with today's date and no times, photos or signatures. Replaces the saved draft.
     */
    suspend fun copyFrom(visitId: String): VisitEntity? {
        val visit = repository.getVisit(visitId) ?: return null
        saveJob?.cancel()
        draftLoaded = true
        form = VisitForm(visit.clientName, visit.clientEmail, "", "", visit.workDescription)
        signaturePath = null
        technicianSignaturePath = null
        _state.value = NewVisitUiState(
            clientId = visit.clientId,
            visitType = VisitType.fromKey(visit.visitType),
            parts = visit.parts
        )
        visit.clientId?.let { id -> app.clientRepository.getClient(id)?.let { loadClientInfo(it) } }
        draftDirty = true
        saveDraftNow()
        return visit
    }

    /** Builds the report PDF from what's on screen now, without saving or sending anything. */
    suspend fun buildPreview(
        currentForm: VisitForm,
        signature: Bitmap?,
        technicianSignature: Bitmap?,
        sections: ReportSections
    ): File = withContext(Dispatchers.IO) {
        val s = _state.value
        val context = getApplication<Application>()
        val visit = VisitEntity(
            id = "preview",
            clientId = s.clientId,
            clientName = currentForm.clientName.trim(),
            clientEmail = currentForm.clientEmail.trim(),
            visitDate = s.visitDate,
            startTime = currentForm.startTime,
            endTime = currentForm.endTime,
            visitType = (if (sections.visitType) s.visitType else VisitType.OTHER).key,
            workDescription = currentForm.workDescription.trim(),
            parts = if (sections.parts) s.parts else emptyList(),
            photos = if (sections.photos) s.photoPaths.map { VisitPhoto(localPath = it) } else emptyList(),
            technicianEmail = AuthSession.accountEmail.value,
            hourlyRate = s.client?.hourlyRate ?: BillingSettings.defaultHourlyRate(context)
        )
        val dir = File(context.cacheDir, "preview").apply { deleteRecursively(); mkdirs() }
        PdfReportGenerator.createReport(context, visit, signature, dir, technicianSignature)
    }

    fun discardDraft() {
        saveJob?.cancel()
        draftDirty = false
        form = VisitForm()
        signaturePath = null
        technicianSignaturePath = null
        _state.update { NewVisitUiState() }
        app.appScope.launch { repository.clearDraft() }
    }

    fun submit(
        currentForm: VisitForm,
        signature: Bitmap?,
        technicianSignature: Bitmap?,
        sections: ReportSections
    ) {
        val current = _state.value
        if (locked || current.isAddingPhotos) return

        val validation = ValidationUtils.validateVisit(
            client = currentForm.clientName,
            email = currentForm.clientEmail,
            startTime = currentForm.startTime,
            endTime = currentForm.endTime,
            hasSignature = signature != null
        )
        if (validation != VisitValidation.VALID || signature == null) {
            _state.update { it.copy(message = validation.messageRes()) }
            return
        }

        saveJob?.cancel()
        draftDirty = false
        _state.update { it.copy(isSubmitting = true) }

        viewModelScope.launch {
            try {
                repository.createVisit(
                    input = NewVisitInput(
                        clientId = current.clientId,
                        clientName = currentForm.clientName,
                        clientEmail = currentForm.clientEmail,
                        visitDate = current.visitDate,
                        startTime = currentForm.startTime,
                        endTime = currentForm.endTime,
                        visitType = if (sections.visitType) current.visitType else VisitType.OTHER,
                        workDescription = currentForm.workDescription,
                        // Switched-off sections are left out even if an older draft has data in them.
                        parts = if (sections.parts) current.parts else emptyList(),
                        photoPaths = if (sections.photos) current.photoPaths else emptyList(),
                        // Billed from the start and end time at the client's rate, or the default rate.
                        hourlyRate = current.client?.hourlyRate
                            ?: BillingSettings.defaultHourlyRate(getApplication())
                    ),
                    signature = signature,
                    technicianSignature = technicianSignature,
                    technicianEmail = AuthSession.accountEmail.value
                )
                SyncScheduler.enqueue(getApplication())
                Telemetry.track(getApplication(), Telemetry.VISIT_SUBMITTED)
                _state.update { it.copy(isSubmitting = false, isSubmitted = true, message = R.string.visit_saved) }
            } catch (e: Exception) {
                Log.w(TAG, "Saving visit failed", e)
                _state.update { it.copy(isSubmitting = false, message = R.string.error_save_failed) }
            }
        }
    }

    fun messageShown() {
        _state.update { it.copy(message = null) }
    }

    private fun scheduleDraftSave() {
        if (locked) return
        draftDirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(DRAFT_SAVE_DELAY_MS)
            saveDraftNow()
        }
    }

    private suspend fun saveDraftNow() {
        if (!draftDirty) return
        draftDirty = false
        repository.saveDraft(currentDraft())
    }

    private fun currentDraft(): DraftEntity {
        val s = _state.value
        return DraftEntity(
            clientName = form.clientName,
            clientEmail = form.clientEmail,
            visitDate = s.visitDate,
            startTime = form.startTime,
            endTime = form.endTime,
            workDescription = form.workDescription,
            signaturePath = signaturePath,
            clientId = s.clientId,
            visitType = s.visitType.key,
            technicianSignaturePath = technicianSignaturePath,
            photoPaths = s.photoPaths,
            parts = s.parts
        )
    }

    override fun onCleared() {
        // The screen is closing: write any pending change now instead of losing it.
        if (draftDirty && !_state.value.isSubmitted) {
            val draft = currentDraft()
            app.appScope.launch { repository.saveDraft(draft) }
        }
    }

    private fun VisitValidation.messageRes(): Int = when (this) {
        VisitValidation.MISSING_FIELDS -> R.string.error_missing_fields
        VisitValidation.INVALID_EMAIL -> R.string.error_invalid_email
        VisitValidation.END_BEFORE_START -> R.string.error_end_before_start
        VisitValidation.MISSING_SIGNATURE, VisitValidation.VALID -> R.string.error_missing_signature
    }

    companion object {
        private const val TAG = "NewVisitViewModel"
        private const val DRAFT_SAVE_DELAY_MS = 500L
        const val MAX_PHOTOS = 10
    }
}
