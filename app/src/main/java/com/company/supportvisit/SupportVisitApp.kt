package com.company.supportvisit

import android.app.Application
import android.content.Context
import com.company.supportvisit.data.AppDatabase
import com.company.supportvisit.graph.MicrosoftAuthManager
import com.company.supportvisit.repository.VisitRepository
import com.company.supportvisit.settings.ThemeManager
import com.company.supportvisit.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SupportVisitApp : Application() {

    /** Lives as long as the process; for work that must not die with a screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.build(this) }

    val visitRepository: VisitRepository by lazy { VisitRepository(this, database) }

    override fun onCreate() {
        super.onCreate()

        ThemeManager.applySaved(this)

        MicrosoftAuthManager.init(this)

        appScope.launch {
            visitRepository.migrateLegacyIfNeeded()
            MicrosoftAuthManager.refreshAccount()
        }

        // Picks up anything left unsent from a previous session (runs once online).
        SyncScheduler.enqueue(this)
    }
}

val Context.app: SupportVisitApp get() = applicationContext as SupportVisitApp
