package com.company.supportvisit

import android.app.Application
import android.content.Context
import com.company.supportvisit.data.AppDatabase
import com.company.supportvisit.graph.MicrosoftAuthManager
import com.company.supportvisit.repository.ClientRepository
import com.company.supportvisit.repository.TaskRepository
import com.company.supportvisit.team.TeamRepository
import com.company.supportvisit.team.TeamSession
import com.company.supportvisit.repository.VisitRepository
import com.company.supportvisit.security.AppLock
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

    val clientRepository: ClientRepository by lazy { ClientRepository(database.clientDao()) }

    val taskRepository: TaskRepository by lazy { TaskRepository(database.taskDao()) }

    override fun onCreate() {
        super.onCreate()

        ThemeManager.applySaved(this)
        TeamSession.load(this)
        AppLock.install(this)

        MicrosoftAuthManager.init(this)

        appScope.launch {
            visitRepository.migrateLegacyIfNeeded()
            if (MicrosoftAuthManager.refreshAccount() != null) TeamRepository.refresh(this@SupportVisitApp)
        }

        // Picks up anything left unsent from a previous session (runs once online).
        SyncScheduler.enqueue(this)
    }
}

val Context.app: SupportVisitApp get() = applicationContext as SupportVisitApp
