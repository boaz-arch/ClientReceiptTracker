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
import com.company.supportvisit.graph.AuthRequiredException
import com.company.supportvisit.graph.GraphException
import com.company.supportvisit.graph.MailService
import com.company.supportvisit.graph.MicrosoftAuthManager
import com.company.supportvisit.graph.OneDriveService
import com.company.supportvisit.repository.VisitRepository
import com.company.supportvisit.utils.DateUtils
import com.company.supportvisit.utils.PdfReportGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Uploads the PDF and sends the email for every visit that is not yet EMAILED.
 * Each step is saved before the next one starts, so a retry never repeats a finished step.
 */
class VisitSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val repository: VisitRepository = context.app.visitRepository

    override suspend fun doWork(): Result {
        repository.migrateLegacyIfNeeded()

        val visits = repository.getUnsyncedVisits()
        if (visits.isEmpty()) return Result.success()

        val token = try {
            MicrosoftAuthManager.getAccessToken()
        } catch (e: AuthRequiredException) {
            // Can't show a sign-in screen from the background; the user retries after signing in.
            visits.forEach { repository.markFailed(it, SyncError.SIGN_IN_REQUIRED) }
            return Result.failure()
        } catch (e: IOException) {
            return retryOrFail(visits, SyncError.NETWORK)
        }

        val retryable = mutableListOf<Pair<VisitEntity, SyncError>>()
        for (visit in visits) {
            val outcome = try {
                syncVisit(visit, token)
                null
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed for a visit", e)
                classify(e)
            }
            if (outcome != null) {
                val (error, canRetry) = outcome
                val latest = repository.getVisit(visit.id) ?: visit
                if (canRetry) retryable += latest to error else repository.markFailed(latest, error)
            }
        }

        if (retryable.isEmpty()) return Result.success()
        return retryOrFail(retryable.map { it.first }, retryable.first().second)
    }

    private suspend fun retryOrFail(visits: List<VisitEntity>, error: SyncError): Result {
        if (runAttemptCount + 1 < MAX_ATTEMPTS) return Result.retry()
        visits.forEach { repository.markFailed(repository.getVisit(it.id) ?: it, error) }
        return Result.failure()
    }

    private suspend fun syncVisit(start: VisitEntity, token: String) {
        var visit = start

        if (visit.syncStatus == SyncStatus.PENDING) {
            val pdf = ensurePdf(visit)
            visit = visit.copy(pdfPath = pdf.absolutePath)
            val link = withContext(Dispatchers.IO) { OneDriveService.uploadFile(token, pdf) }
            visit = visit.copy(pdfLink = link, syncStatus = SyncStatus.PDF_UPLOADED)
            repository.update(visit)
        }

        if (visit.syncStatus == SyncStatus.PDF_UPLOADED) {
            val pdf = ensurePdf(visit)
            visit = visit.copy(pdfPath = pdf.absolutePath)
            withContext(Dispatchers.IO) {
                MailService.sendMail(
                    token = token,
                    subject = applicationContext.getString(R.string.email_subject, visit.clientName),
                    body = emailBody(visit),
                    recipients = listOfNotNull(visit.clientEmail, visit.technicianEmail),
                    pdfFile = pdf
                )
            }
            repository.markEmailed(visit)
        }
    }

    /** Uses the local PDF if it still exists, otherwise regenerates it from the saved data. */
    private suspend fun ensurePdf(visit: VisitEntity): File = withContext(Dispatchers.IO) {
        visit.pdfPath?.let(::File)?.takeIf { it.exists() }?.let { return@withContext it }

        val signaturePath = visit.signaturePath
            ?: throw IllegalStateException("Visit has no signature to build the PDF from")
        val signature = BitmapFactory.decodeFile(signaturePath)
            ?: throw IllegalStateException("Signature image could not be read")
        PdfReportGenerator.createReport(
            applicationContext, visit, signature, File(applicationContext.filesDir, "pdfs")
        )
    }

    private fun emailBody(visit: VisitEntity): String =
        applicationContext.getString(
            R.string.email_body,
            visit.clientName,
            DateUtils.formatForDisplay(visit.visitDate),
            visit.startTime,
            visit.endTime,
            visit.workDescription,
            visit.pdfLink.orEmpty()
        )

    /** Maps an exception to a user-facing error and whether it's worth retrying. */
    private fun classify(e: Exception): Pair<SyncError, Boolean> = when (e) {
        is AuthRequiredException -> SyncError.SIGN_IN_REQUIRED to false
        is GraphException -> when {
            e.code == 401 -> SyncError.SIGN_IN_REQUIRED to false
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
