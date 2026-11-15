package com.company.supportvisit.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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

    @Query("SELECT * FROM visits WHERE syncStatus IN ('PENDING', 'PDF_UPLOADED') ORDER BY createdAt")
    suspend fun getUnsynced(): List<VisitEntity>

    @Query("SELECT * FROM visits WHERE syncStatus = 'FAILED'")
    suspend fun getFailed(): List<VisitEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(visit: VisitEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringExisting(visits: List<VisitEntity>)

    @Update
    suspend fun update(visit: VisitEntity)
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
