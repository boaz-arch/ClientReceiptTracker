package com.company.supportvisit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.company.supportvisit.data.SyncStatus
import com.company.supportvisit.data.VisitEntity
import com.company.supportvisit.databinding.ActivityVisitDetailsBinding
import com.company.supportvisit.sync.SyncScheduler
import com.company.supportvisit.ui.SignInHelper
import com.company.supportvisit.ui.labelRes
import com.company.supportvisit.ui.messageRes
import com.company.supportvisit.utils.DateUtils
import kotlinx.coroutines.launch

class VisitDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVisitDetailsBinding

    private val repository by lazy { app.visitRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityVisitDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val visitId = intent.getStringExtra(EXTRA_VISIT_ID)
        if (visitId == null) {
            finish()
            return
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeVisit(visitId).collect { visit ->
                    if (visit == null) {
                        Toast.makeText(this@VisitDetailsActivity, R.string.visit_not_found, Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        bind(visit)
                    }
                }
            }
        }
    }

    private fun bind(visit: VisitEntity) {
        with(binding) {
            tvClient.text = visit.clientName
            tvDate.text = DateUtils.formatForDisplay(visit.visitDate)
            tvTime.text = getString(R.string.time_range, visit.startTime, visit.endTime)
            tvEmail.text = visit.clientEmail
            tvDescription.text = visit.workDescription

            val error = visit.syncError?.takeIf { visit.syncStatus == SyncStatus.FAILED }
            tvStatus.text = if (error != null) {
                getString(R.string.status_with_error, getString(visit.syncStatus.labelRes), getString(error.messageRes))
            } else {
                getString(visit.syncStatus.labelRes)
            }

            btnRetry.visibility = if (visit.syncStatus == SyncStatus.FAILED) View.VISIBLE else View.GONE
            btnRetry.setOnClickListener { retry(visit.id) }

            btnOpenPdf.isEnabled = !visit.pdfLink.isNullOrBlank()
            btnOpenPdf.setOnClickListener { openPdf(visit.pdfLink) }
        }
    }

    private fun retry(id: String) {
        lifecycleScope.launch {
            if (SignInHelper.ensureSignedIn(this@VisitDetailsActivity)) {
                repository.retry(id)
                SyncScheduler.enqueue(this@VisitDetailsActivity)
                Toast.makeText(this@VisitDetailsActivity, R.string.retry_queued, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openPdf(link: String?) {
        if (link.isNullOrBlank()) {
            Toast.makeText(this, R.string.pdf_not_available, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val EXTRA_VISIT_ID = "visit_id"

        fun intent(context: Context, visitId: String) =
            Intent(context, VisitDetailsActivity::class.java).putExtra(EXTRA_VISIT_ID, visitId)
    }
}
