package com.company.supportvisit.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.company.supportvisit.R
import com.company.supportvisit.app
import com.company.supportvisit.settings.BillingSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.app

    private val _filter = MutableStateFlow(DashboardFilter())
    val filter: StateFlow<DashboardFilter> = _filter.asStateFlow()

    /** Recomputed whenever visits, clients or the filter change (and on resume, for settings). */
    private val refresh = MutableStateFlow(0)

    val clients = app.clientRepository.observeClients()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val data: StateFlow<DashboardData?> =
        combine(app.visitRepository.observeVisits(), app.clientRepository.observeClients(), _filter, refresh) { visits, clients, filter, _ ->
            DashboardStats.compute(
                visits = visits,
                clients = clients,
                filter = filter,
                today = LocalDate.now(),
                billingOn = BillingSettings.anyBillingEnabled(application),
                defaultRate = BillingSettings.defaultHourlyRate(application),
                firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
                unknownClientLabel = application.getString(R.string.unknown)
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setRange(range: DateRange) = _filter.update { it.copy(range = range) }

    fun setClient(clientId: String?) = _filter.update { it.copy(clientId = clientId) }

    fun setTechnician(email: String?) = _filter.update { it.copy(technician = email) }

    /** Settings (billing, rates) may have changed while away. */
    fun refresh() = refresh.update { it + 1 }
}
