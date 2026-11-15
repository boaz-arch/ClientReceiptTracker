package com.company.supportvisit.repository

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.room.withTransaction
import com.company.supportvisit.data.AppDatabase
import com.company.supportvisit.data.DraftEntity
import com.company.supportvisit.data.LegacyVisitsMigrator
import com.company.supportvisit.data.SyncError
import com.company.supportvisit.data.SyncStatus
import com.company.supportvisit.data.VisitEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** The form values for a new visit, as entered by the technician. */
data class NewVisitInput(
    val clientName: String,
    val clientEmail: String,
    val visitDate: String,
    val startTime: String,
    val endTime: String,
    val workDescription: String
)

/**
 * Single source of truth for visits, backed by Room. Replaces the old in-memory VisitCache,
 * the visits.json VisitStorage and GraphRepository.
 */
class VisitRepository(
    private val context: Context,
    private val db: AppDatabase
) {

    private val visitDao = db.visitDao()
    private val draftDao = db.draftDao()

    private val migrationLock = Mutex()

    @Volatile
    private var migrationChecked = false

    private val signatureDir get() = File(context.filesDir, "signatures").apply { mkdirs() }
    private val draftSignatureFile get() = File(File(context.filesDir, "drafts").apply { mkdirs() }, "signature.png")

    // region Visits

    fun observeVisits(): Flow<List<VisitEntity>> = visitDao.observeAll()

    fun observeVisit(id: String): Flow<VisitEntity?> = visitDao.observeById(id)

    suspend fun getVisit(id: String): VisitEntity? = visitDao.getById(id)

    suspend fun getAllVisits(): List<VisitEntity> = visitDao.getAll()

    suspend fun getUnsyncedVisits(): List<VisitEntity> = visitDao.getUnsynced()

    /**
     * Saves a new visit locally (status PENDING) and clears the draft in one transaction.
     * Uploading and emailing happen later in the sync worker.
     */
    suspend fun createVisit(input: NewVisitInput, signature: Bitmap, technicianEmail: String?): VisitEntity {
        val id = UUID.randomUUID().toString()
        val signatureFile = File(signatureDir, "$id.png")
        withContext(Dispatchers.IO) { savePng(signature, signatureFile) }

        val visit = VisitEntity(
            id = id,
            clientName = input.clientName.trim(),
            clientEmail = input.clientEmail.trim(),
            visitDate = input.visitDate,
            startTime = input.startTime,
            endTime = input.endTime,
            workDescription = input.workDescription.trim(),
            technicianEmail = technicianEmail,
            signaturePath = signatureFile.absolutePath,
            syncStatus = SyncStatus.PENDING
        )

        try {
            db.withTransaction {
                visitDao.insert(visit)
                draftDao.clear()
            }
        } catch (e: Exception) {
            signatureFile.delete()
            throw e
        }
        withContext(Dispatchers.IO) { draftSignatureFile.delete() }
        return visit
    }

    suspend fun update(visit: VisitEntity) {
        visitDao.update(visit.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun markFailed(visit: VisitEntity, error: SyncError) {
        update(
            visit.copy(
                syncStatus = SyncStatus.FAILED,
                failedAtStatus = visit.syncStatus.takeIf { it != SyncStatus.FAILED } ?: visit.failedAtStatus,
                syncError = error
            )
        )
    }

    /** Called once a visit is uploaded and emailed: local PDF and signature are no longer needed. */
    suspend fun markEmailed(visit: VisitEntity) {
        withContext(Dispatchers.IO) {
            visit.pdfPath?.let { File(it).delete() }
            visit.signaturePath?.let { File(it).delete() }
        }
        update(
            visit.copy(
                syncStatus = SyncStatus.EMAILED,
                pdfPath = null,
                signaturePath = null,
                failedAtStatus = null,
                syncError = null
            )
        )
    }

    /** Puts a FAILED visit back in the queue, resuming from the step that failed. */
    suspend fun retry(id: String) {
        val visit = visitDao.getById(id) ?: return
        if (visit.syncStatus == SyncStatus.FAILED) resetFailed(visit)
    }

    suspend fun retryAllFailed() {
        visitDao.getFailed().forEach { resetFailed(it) }
    }

    private suspend fun resetFailed(visit: VisitEntity) {
        val resumeFrom = visit.failedAtStatus
            ?: if (visit.pdfLink != null) SyncStatus.PDF_UPLOADED else SyncStatus.PENDING
        update(visit.copy(syncStatus = resumeFrom, failedAtStatus = null, syncError = null))
    }

    // endregion

    // region Draft

    suspend fun getDraft(): DraftEntity? = draftDao.get()?.takeUnless { it.isEmpty() }

    suspend fun saveDraft(draft: DraftEntity) {
        if (draft.isEmpty()) clearDraft() else draftDao.save(draft.copy(updatedAt = System.currentTimeMillis()))
    }

    /** Saves (or with null, deletes) the draft signature image and returns its path. */
    suspend fun saveDraftSignature(signature: Bitmap?): String? = withContext(Dispatchers.IO) {
        val file = draftSignatureFile
        if (signature == null) {
            file.delete()
            null
        } else {
            savePng(signature, file)
            file.absolutePath
        }
    }

    suspend fun clearDraft() {
        draftDao.clear()
        withContext(Dispatchers.IO) { draftSignatureFile.delete() }
    }

    // endregion

    /**
     * One-time import of the old visits.json. Runs at most once per process; the file is
     * renamed afterwards so it is never imported twice but stays available as a backup.
     */
    suspend fun migrateLegacyIfNeeded() {
        if (migrationChecked) return
        migrationLock.withLock {
            if (migrationChecked) return
            withContext(Dispatchers.IO) {
                val legacy = File(context.filesDir, LegacyVisitsMigrator.LEGACY_FILE_NAME)
                if (legacy.exists()) {
                    try {
                        val visits = LegacyVisitsMigrator.parse(legacy.readText())
                        visitDao.insertAllIgnoringExisting(visits)
                        legacy.renameTo(File(context.filesDir, "${legacy.name}.migrated"))
                    } catch (e: Exception) {
                        // Keep the file so nothing is lost; try again on next launch.
                        Log.w(TAG, "Legacy visits migration failed", e)
                        return@withContext
                    }
                }
            }
            migrationChecked = true
        }
    }

    private fun savePng(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        private const val TAG = "VisitRepository"
    }
}
