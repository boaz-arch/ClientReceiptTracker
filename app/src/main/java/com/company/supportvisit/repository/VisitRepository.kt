package com.company.supportvisit.repository

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.room.withTransaction
import com.company.supportvisit.data.AppDatabase
import com.company.supportvisit.data.DraftEntity
import com.company.supportvisit.data.LegacyVisitsMigrator
import com.company.supportvisit.data.PartItem
import com.company.supportvisit.data.SyncError
import com.company.supportvisit.data.SyncStatus
import com.company.supportvisit.data.VisitEntity
import com.company.supportvisit.data.VisitPhoto
import com.company.supportvisit.data.VisitType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** The form values for a new visit, as entered by the technician. */
data class NewVisitInput(
    val clientId: String?,
    val clientName: String,
    val clientEmail: String,
    val visitDate: String,
    val startTime: String,
    val endTime: String,
    val visitType: VisitType,
    val workDescription: String,
    val parts: List<PartItem>,
    /** Compressed photos from the draft folder; moved into the visit's own folder on submit. */
    val photoPaths: List<String>,
    val breakMinutes: Int = 0,
    val billableMinutesOverride: Int? = null,
    /** The client's hourly rate at submit time. */
    val hourlyRate: Double? = null
)

/**
 * Single source of truth for visits, backed by Room (SQLite). The server is synced in the
 * background by the sync worker; screens only ever read from here.
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

    private val filesDir get() = context.filesDir
    private val draftDir get() = File(filesDir, "drafts").apply { mkdirs() }
    private val draftSignatureFile get() = File(draftDir, "signature.png")
    private val draftTechSignatureFile get() = File(draftDir, "technician_signature.png")

    /** Folder for a draft's compressed photos. */
    val draftPhotoDir: File get() = File(draftDir, "photos").apply { mkdirs() }

    private fun visitDir(id: String) = File(File(filesDir, "visits"), id).apply { mkdirs() }

    // region Visits

    fun observeVisits(): Flow<List<VisitEntity>> = visitDao.observeAll()

    fun observeVisit(id: String): Flow<VisitEntity?> = visitDao.observeById(id)

    fun observeUnsentCount(): Flow<Int> = visitDao.observeUnsentCount()

    suspend fun getVisit(id: String): VisitEntity? = visitDao.getById(id)

    suspend fun getAllVisits(): List<VisitEntity> = visitDao.getAll()

    suspend fun getUnsyncedVisits(): List<VisitEntity> = visitDao.getUnsynced()

    suspend fun getRemoteDirtyVisits(): List<VisitEntity> = visitDao.getRemoteDirty()

    suspend fun getVisitsInRange(from: String, to: String, clientId: String? = null): List<VisitEntity> =
        visitDao.getInRange(from, to, clientId)

    fun observeVisitsInRange(from: String, to: String): Flow<List<VisitEntity>> = visitDao.observeInRange(from, to)

    /**
     * Saves a new visit locally (status PENDING) and clears the draft in one transaction.
     * Uploading and emailing happen later in the sync worker.
     */
    suspend fun createVisit(
        input: NewVisitInput,
        signature: Bitmap,
        technicianSignature: Bitmap?,
        technicianEmail: String?
    ): VisitEntity {
        val id = UUID.randomUUID().toString()
        val dir = visitDir(id)

        val (signatureFile, techSignatureFile, photos) = withContext(Dispatchers.IO) {
            val sig = File(dir, "signature.png").also { savePng(signature, it) }
            val techSig = technicianSignature?.let { bmp -> File(dir, "technician_signature.png").also { savePng(bmp, it) } }
            val movedPhotos = input.photoPaths.mapIndexedNotNull { i, path ->
                val source = File(path)
                if (!source.exists()) return@mapIndexedNotNull null
                val target = File(dir, "photo-${i + 1}.jpg")
                if (!source.renameTo(target)) source.copyTo(target, overwrite = true).also { source.delete() }
                VisitPhoto(localPath = target.absolutePath)
            }
            Triple(sig, techSig, movedPhotos)
        }

        val visit = VisitEntity(
            id = id,
            clientId = input.clientId,
            clientName = input.clientName.trim(),
            clientEmail = input.clientEmail.trim(),
            visitDate = input.visitDate,
            startTime = input.startTime,
            endTime = input.endTime,
            visitType = input.visitType.key,
            workDescription = input.workDescription.trim(),
            parts = input.parts,
            photos = photos,
            technicianEmail = technicianEmail,
            breakMinutes = input.breakMinutes,
            billableMinutesOverride = input.billableMinutesOverride,
            hourlyRate = input.hourlyRate,
            signaturePath = signatureFile.absolutePath,
            technicianSignaturePath = techSignatureFile?.absolutePath,
            syncStatus = SyncStatus.PENDING
        )

        try {
            db.withTransaction {
                visitDao.insert(visit)
                draftDao.clear()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
            throw e
        }
        withContext(Dispatchers.IO) { deleteDraftFiles() }
        return visit
    }

    /** Saves a change made by this device; it will be uploaded to the server. */
    suspend fun update(visit: VisitEntity) {
        visitDao.update(visit.copy(updatedAt = System.currentTimeMillis(), remoteDirty = visit.ownedLocally))
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

    /** Called once a visit is uploaded and emailed: the local files are no longer needed. */
    suspend fun markEmailed(visit: VisitEntity) {
        withContext(Dispatchers.IO) { File(File(filesDir, "visits"), visit.id).deleteRecursively() }
        visit.pdfPath?.let { File(it).delete() }
        update(
            visit.copy(
                syncStatus = SyncStatus.EMAILED,
                pdfPath = null,
                signaturePath = null,
                technicianSignaturePath = null,
                photos = visit.photos.map { it.copy(localPath = null) },
                failedAtStatus = null,
                syncError = null
            )
        )
    }

    suspend fun markRemoteSynced(visit: VisitEntity) {
        visitDao.markRemoteSynced(visit.id, visit.updatedAt)
    }

    /**
     * Removes a visit the server deleted (its technician deleted their account). A visit this
     * phone is still sending, or has unsent changes to, is never deleted.
     */
    suspend fun deleteLocal(id: String) {
        val local = visitDao.getById(id) ?: return
        if (local.ownedLocally && (local.remoteDirty || local.syncStatus != SyncStatus.EMAILED)) return
        visitDao.deleteById(id)
        withContext(Dispatchers.IO) { File(File(filesDir, "visits"), id).deleteRecursively() }
    }

    /** Applies visits pulled from the server. */
    suspend fun mergeRemote(remote: List<VisitEntity>) {
        remote.forEach { r -> RemoteMerge.visit(visitDao.getById(r.id), r)?.let { visitDao.upsert(it) } }
    }

    /** Puts a FAILED visit back in the queue, resuming from the step that failed. */
    suspend fun retry(id: String) {
        val visit = visitDao.getById(id) ?: return
        if (visit.syncStatus == SyncStatus.FAILED) resetFailed(visit)
    }

    suspend fun retryAllFailed() {
        visitDao.getFailed().filter { it.ownedLocally }.forEach { resetFailed(it) }
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

    /** Saves (or with null, deletes) a draft signature image and returns its path. */
    suspend fun saveDraftSignature(signature: Bitmap?, technician: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            val file = if (technician) draftTechSignatureFile else draftSignatureFile
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
        withContext(Dispatchers.IO) { deleteDraftFiles() }
    }

    private fun deleteDraftFiles() {
        draftSignatureFile.delete()
        draftTechSignatureFile.delete()
        draftPhotoDir.deleteRecursively()
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
                val legacy = File(filesDir, LegacyVisitsMigrator.LEGACY_FILE_NAME)
                if (legacy.exists()) {
                    try {
                        val visits = LegacyVisitsMigrator.parse(legacy.readText())
                        visitDao.insertAllIgnoringExisting(visits)
                        legacy.renameTo(File(filesDir, "${legacy.name}.migrated"))
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
