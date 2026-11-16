package com.company.supportvisit.sync

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.company.supportvisit.R
import com.company.supportvisit.app
import com.company.supportvisit.data.SyncError
import com.company.supportvisit.data.SyncStatus
import com.company.supportvisit.data.VisitEntity
import com.company.supportvisit.auth.AuthRequiredException
import com.company.supportvisit.auth.AuthSession
import com.company.supportvisit.graph.GraphException
import com.company.supportvisit.remote.RecordMapper
import com.company.supportvisit.remote.ServerApi
import com.company.supportvisit.repository.VisitRepository
import com.company.supportvisit.settings.ServerSettings
import com.company.supportvisit.team.Branding
import com.company.supportvisit.utils.DateUtils
import com.company.supportvisit.utils.PdfReportGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 1. For every visit of this device not yet EMAILED: upload photos and PDF to the Support
 *    Visit server, then have the server email the report. Each step is saved before the next,
 *    so a retry never repeats a finished step.
 * 2. Push changed visits and clients to the server and pull everyone else's changes.
 */
class VisitSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val repository: VisitRepository = context.app.visitRepository

    private var serverApi: ServerApi? = null

    private val serverUrl: String? = ServerSettings.serverUrl(context)

    override suspend fun doWork(): Result {
        repository.migrateLegacyIfNeeded()

        val retryable = mutableListOf<Pair<VisitEntity, SyncError>>()
        for (visit in repository.getUnsyncedVisits()) {
            val outcome = try {
                sendVisit(visit)
                null
            } catch (e: Exception) {
                Log.w(TAG, "Sending a visit failed", e)
                classify(e)
            }
            if (outcome != null) {
                val (error, canRetry) = outcome
                val latest = repository.getVisit(visit.id) ?: visit
                if (canRetry) retryable += latest to error else repository.markFailed(latest, error)
            }
        }

        val serverOk = syncWithServer()

        if (retryable.isEmpty()) return if (serverOk) Result.success() else retryOrSucceed()
        if (runAttemptCount + 1 < MAX_ATTEMPTS) return Result.retry()
        retryable.forEach { (visit, error) -> repository.markFailed(repository.getVisit(visit.id) ?: visit, error) }
        return Result.failure()
    }

    /** Returns false if the server sync should be retried later (e.g. server offline). */
    private suspend fun syncWithServer(): Boolean {
        if (serverUrl == null) return true
        return try {
            ServerSync.pushAndPull(applicationContext, server())
            true
        } catch (e: AuthRequiredException) {
            Log.w(TAG, "Server sync needs sign-in", e)
            true // nothing to retry until the user signs in again
        } catch (e: Exception) {
            Log.w(TAG, "Server sync failed", e)
            !ServerApi.isRetryable(e)
        }
    }

    private fun retryOrSucceed(): Result =
        if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.success()

    private fun server(): ServerApi {
        if (serverUrl == null) throw IllegalStateException("No server configured")
        return serverApi ?: ServerApi(serverUrl, AuthSession.getApiToken()).also { serverApi = it }
    }

    private suspend fun sendVisit(start: VisitEntity) {
        var visit = start

        if (visit.syncStatus == SyncStatus.PENDING) {
            val pdf = ensurePdf(visit)
            visit = visit.copy(pdfPath = pdf.absolutePath)
            visit = uploadToServer(visit, pdf)
            visit = visit.copy(syncStatus = SyncStatus.PDF_UPLOADED)
            repository.update(visit)
        }

        if (visit.syncStatus == SyncStatus.PDF_UPLOADED) {
            val pdf = ensurePdf(visit)
            visit = visit.copy(pdfPath = pdf.absolutePath)
            val api = server()
            val recipients = (listOfNotNull(visit.clientEmail, visit.technicianEmail) + Branding.extraRecipients(applicationContext))
                .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
            withContext(Dispatchers.IO) {
                // The server only emails visits it knows, so make sure this one is there.
                api.putVisit(RecordMapper.visitToJson(visit))
                api.emailVisit(
                    visitId = visit.id,
                    to = recipients,
                    subject = Branding.emailSubject(applicationContext, visit),
                    // The server attaches the PDF, or adds a link when it is too big.
                    body = Branding.emailBody(applicationContext, visit, attached = true),
                    fileName = pdf.name
                )
            }
            repository.markEmailed(visit)
        }
    }

    /** Uploads photos (skipping ones already uploaded) and the PDF, saving after each file. */
    private suspend fun uploadToServer(start: VisitEntity, pdf: File): VisitEntity {
        var visit = start
        val api = server()
        visit.photos.forEachIndexed { index, photo ->
            if (photo.remoteUrl != null || photo.localPath == null) return@forEachIndexed
            val url = withContext(Dispatchers.IO) {
                api.uploadVisitFile(visit.id, "photo-${index + 1}.jpg", File(photo.localPath), "image/jpeg")
            }
            visit = visit.copy(photos = visit.photos.toMutableList().also { it[index] = photo.copy(remoteUrl = url) })
            repository.update(visit)
        }
        val link = withContext(Dispatchers.IO) { api.uploadVisitFile(visit.id, "report.pdf", pdf, "application/pdf") }
        return visit.copy(pdfLink = link)
    }

    /** Uses the local PDF if it still exists, otherwise regenerates it from the saved data. */
    private suspend fun ensurePdf(visit: VisitEntity): File = withContext(Dispatchers.IO) {
        visit.pdfPath?.let(::File)?.takeIf { it.exists() }?.let { return@withContext it }

        val signaturePath = visit.signaturePath
            ?: throw IllegalStateException("Visit has no signature to build the PDF from")
        val signature = BitmapFactory.decodeFile(signaturePath)
            ?: throw IllegalStateException("Signature image could not be read")
        val technicianSignature = visit.technicianSignaturePath?.let { BitmapFactory.decodeFile(it) }
        PdfReportGenerator.createReport(
            applicationContext, visit, signature, File(applicationContext.filesDir, "pdfs"), technicianSignature
        )
    }

    /** Maps an exception to a user-facing error and whether it's worth retrying. */
    private fun classify(e: Exception): Pair<SyncError, Boolean> = when (e) {
        is AuthRequiredException -> SyncError.SIGN_IN_REQUIRED to false
        is GraphException -> when {
            // Session ended, or not in a company (yet / any more): sign in again.
            e.code == 401 -> {
                AuthSession.clear()
                SyncError.SIGN_IN_REQUIRED to false
            }
            e.code == 403 || e.code == 409 -> SyncError.SIGN_IN_REQUIRED to false
            e.isRetryable -> SyncError.SERVER to true
            else -> SyncError.SERVER to false
        }
        is IOException -> SyncError.NETWORK to true
        else -> SyncError.UNKNOWN to false
    }

    companion object {
        private const val TAG = "VisitSyncWorker"
        private const val MAX_ATTEMPTS = 6
    }
}
