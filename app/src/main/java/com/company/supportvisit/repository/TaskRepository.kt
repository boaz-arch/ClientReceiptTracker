package com.company.supportvisit.repository

import com.company.supportvisit.data.TaskDao
import com.company.supportvisit.data.TaskEntity
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

    suspend fun setDone(task: TaskEntity, done: Boolean) =
        save(task.copy(done = done, doneAt = if (done) System.currentTimeMillis() else null))

    suspend fun getRemoteDirty(): List<TaskEntity> = dao.getRemoteDirty()

    suspend fun markRemoteSynced(task: TaskEntity) = dao.markRemoteSynced(task.id, task.updatedAt)

    /** The server copy wins unless this phone has unsent changes to the task. */
    suspend fun mergeRemote(remote: List<TaskEntity>) {
        remote.forEach { r ->
            val local = dao.getById(r.id)
            if (local == null || !local.remoteDirty) dao.upsert(r)
        }
    }

    suspend fun deleteLocal(id: String) = dao.deleteById(id)
}
