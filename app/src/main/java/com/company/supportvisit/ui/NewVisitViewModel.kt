package com.company.supportvisit.ui

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.company.supportvisit.R
import com.company.supportvisit.app
import com.company.supportvisit.data.DraftEntity
import com.company.supportvisit.graph.MicrosoftAuthManager
import com.company.supportvisit.repository.NewVisitInput
import com.company.supportvisit.sync.SyncScheduler
import com.company.supportvisit.utils.DateUtils
import com.company.supportvisit.utils.ValidationUtils
import com.company.supportvisit.utils.VisitValidation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val isSubmitting: Boolean = false,
    val isSubmitted: Boolean = false,
    @StringRes val message: Int? = null
)

/**
 * Owns submitting and draft auto-save, so neither restarts nor is lost when the screen rotates.
 */
class NewVisitViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.app
    private val repository = app.visitRepository

    private val _state = MutableStateFlow(NewVisitUiState())
    val state: StateFlow<NewVisitUiState> = _state.asStateFlow()

    private var form = VisitForm()

    /** Path of the auto-saved draft signature, if the technician has signed. */
    var signaturePath: String? = null
        private set

    private var saveJob: Job? = null
    private var draftDirty = false
    private var draftLoaded = false

    /** Loads the saved draft once per screen; returns null if there is none. */
    suspend fun loadDraft(): DraftEntity? {
        if (draftLoaded) return null
        draftLoaded = true
        val draft = repository.getDraft() ?: return null
        form = VisitForm(draft.clientName, draft.clientEmail, draft.startTime, draft.endTime, draft.workDescription)
        signaturePath = draft.signaturePath
        if (draft.visitDate.isNotBlank()) _state.update { it.copy(visitDate = draft.visitDate) }
        return draft
    }

    fun onFormChanged(newForm: VisitForm) {
        if (newForm == form) return
        form = newForm
        scheduleDraftSave()
    }

    fun onSignatureChanged(signature: Bitmap?) {
        if (_state.value.isSubmitting || _state.value.isSubmitted) return
        viewModelScope.launch {
            signaturePath = repository.saveDraftSignature(signature)
            scheduleDraftSave()
        }
    }

    fun discardDraft() {
        saveJob?.cancel()
        draftDirty = false
        form = VisitForm()
        signaturePath = null
        _state.update { it.copy(visitDate = DateUtils.todayIso()) }
        app.appScope.launch { repository.clearDraft() }
    }

    fun submit(currentForm: VisitForm, signature: Bitmap?) {
        val current = _state.value
        if (current.isSubmitting || current.isSubmitted) return

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
                        clientName = currentForm.clientName,
                        clientEmail = currentForm.clientEmail,
                        visitDate = current.visitDate,
                        startTime = currentForm.startTime,
                        endTime = currentForm.endTime,
                        workDescription = currentForm.workDescription
                    ),
                    signature = signature,
                    technicianEmail = MicrosoftAuthManager.accountEmail.value
                )
                SyncScheduler.enqueue(getApplication())
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
        if (_state.value.isSubmitting || _state.value.isSubmitted) return
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

    private fun currentDraft() = DraftEntity(
        clientName = form.clientName,
        clientEmail = form.clientEmail,
        visitDate = _state.value.visitDate,
        startTime = form.startTime,
        endTime = form.endTime,
        workDescription = form.workDescription,
        signaturePath = signaturePath
    )

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
    }
}
