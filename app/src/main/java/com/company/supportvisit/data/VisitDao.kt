package com.company.supportvisit.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VisitDao {

    @Query("SELECT * FROM visits ORDER BY visitDate DESC, startTime DESC, createdAt DESC")
    fun observeAll(): Flow<List<VisitEntity>>

    @Query("SELECT * FROM visits ORDER BY visitDate DESC, startTime DESC, createdAt DESC")
    suspend fun getAll(): List<VisitEntity>

    @Query("SELECT * FROM visits WHERE id = :id")
    fun observeById(id: String): Flow<VisitEntity?>

    @Query("SELECT * FROM visits WHERE id = :id")
    suspend fun getById(id: String): VisitEntity?

    /** Visits this device still has to upload and email. */
    @Query(
        "SELECT * FROM visits WHERE ownedLocally = 1 AND syncStatus IN ('PENDING', 'PDF_UPLOADED') " +
            "ORDER BY createdAt"
    )
    suspend fun getUnsynced(): List<VisitEntity>

    @Query("SELECT * FROM visits WHERE syncStatus = 'FAILED'")
    suspend fun getFailed(): List<VisitEntity>

    /** Visits whose latest state still has to be sent to the server. */
    @Query("SELECT * FROM visits WHERE remoteDirty = 1 AND ownedLocally = 1")
    suspend fun getRemoteDirty(): List<VisitEntity>

    /** Visits in an ISO date range (inclusive), optionally for one client. */
    @Query(
        "SELECT * FROM visits WHERE visitDate BETWEEN :from AND :to " +
            "AND (:clientId IS NULL OR clientId = :clientId) ORDER BY visitDate, startTime"
    )
    suspend fun getInRange(from: String, to: String, clientId: String?): List<VisitEntity>

    @Query(
        "SELECT * FROM visits WHERE visitDate BETWEEN :from AND :to ORDER BY visitDate, startTime"
    )
    fun observeInRange(from: String, to: String): Flow<List<VisitEntity>>

    @Query("SELECT COUNT(*) FROM visits WHERE ownedLocally = 1 AND syncStatus != 'EMAILED'")
    fun observeUnsentCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(visit: VisitEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringExisting(visits: List<VisitEntity>)

    @Upsert
    suspend fun upsert(visit: VisitEntity)

    @Update
    suspend fun update(visit: VisitEntity)

    @Query("DELETE FROM visits WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Clears the dirty flag after an upload, unless the visit changed meanwhile. */
    @Query("UPDATE visits SET remoteDirty = 0 WHERE id = :id AND updatedAt = :pushedUpdatedAt")
    suspend fun markRemoteSynced(id: String, pushedUpdatedAt: Long)
}

@Dao
interface DraftDao {

    @Query("SELECT * FROM draft WHERE id = ${DraftEntity.SINGLE_DRAFT_ID}")
    suspend fun get(): DraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(draft: DraftEntity)

    @Query("DELETE FROM draft")
    suspend fun clear()
}

@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks ORDER BY done, CASE WHEN dueDate IS NULL THEN 1 ELSE 0 END, dueDate, createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE remoteDirty = 1")
    suspend fun getRemoteDirty(): List<TaskEntity>

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE tasks SET remoteDirty = 0 WHERE id = :id AND updatedAt = :pushedUpdatedAt")
    suspend fun markRemoteSynced(id: String, pushedUpdatedAt: Long)
}

@Dao
interface ClientDao {

    @Query("SELECT * FROM clients ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ClientEntity>>

    @Query("SELECT * FROM clients ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<ClientEntity>

    @Query("SELECT * FROM clients WHERE id = :id")
    suspend fun getById(id: String): ClientEntity?

    @Query("SELECT * FROM clients WHERE remoteDirty = 1")
    suspend fun getRemoteDirty(): List<ClientEntity>

    @Upsert
    suspend fun upsert(client: ClientEntity)

    @Query("UPDATE clients SET remoteDirty = 0 WHERE id = :id AND updatedAt = :pushedUpdatedAt")
    suspend fun markRemoteSynced(id: String, pushedUpdatedAt: Long)
}
