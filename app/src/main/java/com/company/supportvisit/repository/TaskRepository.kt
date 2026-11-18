package com.company.supportvisit.repository

import com.company.supportvisit.data.TaskDao
import com.company.supportvisit.data.TaskEntity
import com.company.supportvisit.data.TaskTime
import com.company.supportvisit.data.TimeEntry
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class TaskRepository(private val dao: TaskDao) {

    fun observeTasks(): Flow<List<TaskEntity>> = dao.observeAll()

    suspend fun getTask(id: String): TaskEntity? = dao.getById(id)

    /** Saves a new or edited task and marks it for upload. */
    suspend fun save(task: TaskEntity): TaskEntity {
        val saved = task.copy(
            id = task.id.ifBlank { UUID.randomUUID().toString() },
            title = task.title.trim(),
            notes = task.notes.trim(),
            remoteDirty = true,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(saved)
        return saved
    }

    /** Someone's running timer on any task (one at a time per person), with its task. */
    suspend fun runningTimer(userId: String?): Pair<TaskEntity, TimeEntry>? =
        dao.getAll().firstNotNullOfOrNull { t -> TaskTime.running(t.timeEntries, userId)?.let { t to it } }

    /**
     * Starts [userId]'s timer on this task. A timer they left running on another task is
     * finished first; returns that task, if any, so the screen can say so.
     */
    suspend fun startTimer(taskId: String, userId: String?, userName: String?, now: Long = System.currentTimeMillis()): TaskEntity? {
        val stoppedElsewhere = runningTimer(userId)?.first?.takeIf { it.id != taskId }
        stoppedElsewhere?.let { finishTimer(it.id, userId, now) }
        val task = dao.getById(taskId) ?: return stoppedElsewhere
        if (TaskTime.running(task.timeEntries, userId) == null) {
            save(task.copy(timeEntries = task.timeEntries + TimeEntry(userId = userId, userName = userName, start = now)))
        }
        return stoppedElsewhere
    }

    suspend fun finishTimer(taskId: String, userId: String?, now: Long = System.currentTimeMillis()) {
        val task = dao.getById(taskId) ?: return
        val entries = task.timeEntries.map { if (it.isRunning && it.userId == userId) it.copy(end = maxOf(now, it.start)) else it }
        if (entries != task.timeEntries) save(task.copy(timeEntries = entries))
    }

    suspend fun addTimeEntry(taskId: String, entry: TimeEntry) {
        val task = dao.getById(taskId) ?: return
        save(task.copy(timeEntries = (task.timeEntries + entry).sortedBy { it.start }))
    }

    /** Removes one logged time: your own, or anyone's if you manage the company. */
    suspend fun deleteTimeEntry(taskId: String, entryId: String, userId: String?, canManage: Boolean) {
        val task = dao.getById(taskId) ?: return
        val entry = task.timeEntries.firstOrNull { it.id == entryId } ?: return
        if (entry.userId != userId && !canManage) return
        save(task.copy(timeEntries = task.timeEntries - entry))
    }

    suspend fun setDone(task: TaskEntity, done: Boolean) =
        save(task.copy(done = done, doneAt = if (done) System.currentTimeMillis() else null))

    suspend fun getRemoteDirty(): List<TaskEntity> = dao.getRemoteDirty()

    suspend fun markRemoteSynced(task: TaskEntity) = dao.markRemoteSynced(task.id, task.updatedAt)

    /** The server copy wins unless this phone has unsent changes to the task. */
    /** Stores the server's versions (local unsent edits win); returns the tasks new to this phone. */
    suspend fun mergeRemote(remote: List<TaskEntity>): List<TaskEntity> {
        val added = mutableListOf<TaskEntity>()
        remote.forEach { r ->
            val local = dao.getById(r.id)
            if (local == null) added += r
            if (local == null || !local.remoteDirty) dao.upsert(r)
        }
        return added
    }

    suspend fun deleteLocal(id: String) = dao.deleteById(id)

    /** After a full pull: drops synced tasks the server no longer shows this member. */
    suspend fun pruneNotVisible(visibleIds: Set<String>) {
        dao.getAll().filter { !it.remoteDirty && it.id !in visibleIds }.forEach { dao.deleteById(it.id) }
    }
}
