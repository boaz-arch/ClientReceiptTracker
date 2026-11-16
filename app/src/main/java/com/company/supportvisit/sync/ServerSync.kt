package com.company.supportvisit.sync

import android.content.Context
import com.company.supportvisit.app
import com.company.supportvisit.remote.RecordMapper
import com.company.supportvisit.remote.ServerApi
import com.company.supportvisit.settings.ServerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Two-way sync of visit records and clients with the Support Visit server.
 * Push first (local changes win while they're pending), then pull everything changed since
 * the last cursor. Safe to call from the worker and from pull-to-refresh at the same time.
 */
object ServerSync {

    private val lock = Mutex()

    suspend fun pushAndPull(context: Context, api: ServerApi) = lock.withLock {
        withContext(Dispatchers.IO) {
            val visits = context.app.visitRepository
            val clients = context.app.clientRepository

            clients.getRemoteDirty().forEach { client ->
                api.putClient(RecordMapper.clientToJson(client))
                clients.markRemoteSynced(client)
            }
            visits.getRemoteDirtyVisits().forEach { visit ->
                api.putVisit(RecordMapper.visitToJson(visit))
                visits.markRemoteSynced(visit)
            }

            // Tasks: push changes, then pull (the server returns only tasks this member may see).
            val tasks = context.app.taskRepository
            tasks.getRemoteDirty().forEach { task ->
                api.putTask(RecordMapper.taskToJson(task))
                tasks.markRemoteSynced(task)
            }
            var taskCursor = ServerSettings.tasksCursor(context)
            do {
                val page = api.taskChanges(taskCursor)
                tasks.mergeRemote(page.items.filterNot { it.optBoolean("deleted") }.mapNotNull(RecordMapper::jsonToTask))
                taskCursor = page.cursor
                ServerSettings.setTasksCursor(context, taskCursor)
            } while (page.hasMore)

            var clientCursor = ServerSettings.clientsCursor(context)
            do {
                val page = api.clientChanges(clientCursor)
                clients.mergeRemote(page.items.filterNot { it.optBoolean("deleted") }.mapNotNull(RecordMapper::jsonToClient))
                clientCursor = page.cursor
                ServerSettings.setClientsCursor(context, clientCursor)
            } while (page.hasMore)

            var visitCursor = ServerSettings.visitsCursor(context)
            do {
                val page = api.visitChanges(visitCursor)
                // Deleted on the server (account deletion): remove the local copy too.
                page.items.filter { it.optBoolean("deleted") }.forEach { visits.deleteLocal(it.getString("id")) }
                visits.mergeRemote(page.items.filterNot { it.optBoolean("deleted") }.mapNotNull(RecordMapper::jsonToVisit))
                visitCursor = page.cursor
                ServerSettings.setVisitsCursor(context, visitCursor)
            } while (page.hasMore)
        }
    }
}
