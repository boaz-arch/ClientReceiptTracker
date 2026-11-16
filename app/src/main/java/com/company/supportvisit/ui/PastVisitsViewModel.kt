package com.company.supportvisit.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.company.supportvisit.R
import com.company.supportvisit.app
import com.company.supportvisit.data.VisitEntity
import com.company.supportvisit.auth.AuthRequiredException
import com.company.supportvisit.auth.AuthSession
import com.company.supportvisit.remote.ServerApi
import com.company.supportvisit.settings.ServerSettings
import com.company.supportvisit.sync.ServerSync
import com.company.supportvisit.sync.SyncScheduler
import com.company.supportvisit.utils.DateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

class PastVisitsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = application.app.visitRepository

    private val query = MutableStateFlow("")

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    /** Visits newest first, filtered by the search box. Null until the first load. */
    val visits: StateFlow<List<VisitEntity>?> =
        combine(repository.observeVisits(), query) { visits, q -> filter(visits, q.trim()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(text: String) {
        query.value = text
    }

    fun retry(id: String) {
        viewModelScope.launch {
            repository.retry(id)
            SyncScheduler.enqueue(getApplication())
        }
    }

    /** Pull-to-refresh: sync with the server now, and queue sending of anything unsent. */
    fun refresh() {
        if (_refreshing.value) return
        SyncScheduler.enqueue(getApplication())
        val url = ServerSettings.serverUrl(getApplication())
        if (url == null) {
            _message.value = R.string.server_not_configured
            return
        }
        _refreshing.value = true
        viewModelScope.launch {
            try {
                ServerSync.pushAndPull(getApplication(), ServerApi(url, AuthSession.getApiToken()))
            } catch (e: AuthRequiredException) {
                _message.value = R.string.sync_error_sign_in
            } catch (e: IOException) {
                Log.w(TAG, "Refresh failed", e)
                _message.value = R.string.refresh_failed_offline
            } catch (e: Exception) {
                Log.w(TAG, "Refresh failed", e)
                _message.value = R.string.refresh_failed
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }

    private fun filter(visits: List<VisitEntity>, q: String): List<VisitEntity> {
        if (q.isEmpty()) return visits
        return visits.filter { v ->
            v.clientName.contains(q, ignoreCase = true) ||
                v.workDescription.contains(q, ignoreCase = true) ||
                v.clientEmail.contains(q, ignoreCase = true) ||
                v.technicianEmail.orEmpty().contains(q, ignoreCase = true) ||
                v.visitDate.contains(q) ||
                DateUtils.formatForDisplay(v.visitDate).contains(q, ignoreCase = true)
        }
    }

    companion object {
        private const val TAG = "PastVisitsViewModel"
    }
}
