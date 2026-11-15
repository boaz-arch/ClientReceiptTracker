package com.company.supportvisit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.company.supportvisit.app
import com.company.supportvisit.data.VisitEntity
import com.company.supportvisit.sync.SyncScheduler
import com.company.supportvisit.utils.DateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PastVisitsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = application.app.visitRepository

    private val query = MutableStateFlow("")

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

    fun syncNow() {
        SyncScheduler.enqueue(getApplication())
    }

    private fun filter(visits: List<VisitEntity>, q: String): List<VisitEntity> {
        if (q.isEmpty()) return visits
        return visits.filter { v ->
            v.clientName.contains(q, ignoreCase = true) ||
                v.workDescription.contains(q, ignoreCase = true) ||
                v.clientEmail.contains(q, ignoreCase = true) ||
                v.visitDate.contains(q) ||
                DateUtils.formatForDisplay(v.visitDate).contains(q, ignoreCase = true)
        }
    }
}
