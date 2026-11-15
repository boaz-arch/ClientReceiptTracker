package com.company.supportvisit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.company.supportvisit.data.SyncStatus
import com.company.supportvisit.data.VisitEntity
import com.company.supportvisit.data.partsTotal
import com.company.supportvisit.databinding.ActivityVisitDetailsBinding
import com.company.supportvisit.graph.MicrosoftAuthManager
import com.company.supportvisit.remote.ServerApi
import com.company.supportvisit.settings.ServerSettings
import com.company.supportvisit.sync.SyncScheduler
import com.company.supportvisit.ui.SignInHelper
import com.company.supportvisit.ui.applySystemBarInsets
import com.company.supportvisit.ui.labelRes
import com.company.supportvisit.ui.messageRes
import com.company.supportvisit.utils.DateUtils
import com.company.supportvisit.utils.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VisitDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVisitDetailsBinding

    private val repository by lazy { app.visitRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityVisitDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

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
            tvType.text = getString(visit.type.labelRes())
            tvTechnician.text = visit.technicianEmail.orEmpty()
            lblTechnician.visibility = if (visit.technicianEmail.isNullOrBlank()) View.GONE else View.VISIBLE
            tvTechnician.visibility = lblTechnician.visibility
            tvEmail.text = visit.clientEmail
            tvDescription.text = visit.workDescription

            val hasParts = visit.parts.isNotEmpty()
            lblParts.visibility = if (hasParts) View.VISIBLE else View.GONE
            tvParts.visibility = lblParts.visibility
            tvParts.text = visit.parts.joinToString("\n") { part ->
                getString(R.string.part_line, part.description, Money.quantity(part.quantity), Money.format(part.total))
            } + if (hasParts) "\n" + getString(R.string.parts_total, Money.format(visit.parts.partsTotal())) else ""

            val photoLinks = visit.photos.mapNotNull { it.remoteUrl }
            lblPhotos.visibility = if (visit.photos.isEmpty()) View.GONE else View.VISIBLE
            photosContainer.removeAllViews()
            if (photoLinks.isEmpty() && visit.photos.isNotEmpty()) {
                lblPhotos.text = getString(R.string.photos_waiting_upload, visit.photos.size)
            } else {
                lblPhotos.setText(R.string.photos_header)
                photoLinks.forEachIndexed { index, url ->
                    val button = layoutInflater.inflate(R.layout.item_link_button, photosContainer, false) as Button
                    button.text = getString(R.string.open_photo, index + 1)
                    button.setOnClickListener { openLink(url) }
                    photosContainer.addView(button)
                }
            }

            val error = visit.syncError?.takeIf { visit.syncStatus == SyncStatus.FAILED }
            tvStatus.text = if (error != null) {
                getString(R.string.status_with_error, getString(visit.syncStatus.labelRes), getString(error.messageRes))
            } else {
                getString(visit.syncStatus.labelRes)
            }

            btnRetry.visibility = if (visit.syncStatus == SyncStatus.FAILED && visit.ownedLocally) View.VISIBLE else View.GONE
            btnRetry.setOnClickListener { retry(visit.id) }

            btnOpenPdf.isEnabled = !visit.pdfLink.isNullOrBlank()
            btnOpenPdf.setOnClickListener { openLink(visit.pdfLink) }
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

    /**
     * Server files need a sign-in, so they are opened through a temporary link the server
     * creates; OneDrive links open as they are.
     */
    private fun openLink(link: String?) {
        if (link.isNullOrBlank()) {
            Toast.makeText(this, R.string.pdf_not_available, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val target = try {
                val serverUrl = ServerSettings.serverUrl(this@VisitDetailsActivity)
                if (serverUrl != null && link.startsWith("$serverUrl/")) {
                    val api = ServerApi(serverUrl, MicrosoftAuthManager.getApiToken(this@VisitDetailsActivity))
                    withContext(Dispatchers.IO) { api.temporaryLink(link) }
                } else {
                    link
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not get a link to the file", e)
                Toast.makeText(this@VisitDetailsActivity, R.string.open_file_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, target.toUri()))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@VisitDetailsActivity, R.string.no_app_to_open, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val TAG = "VisitDetailsActivity"
        private const val EXTRA_VISIT_ID = "visit_id"

        fun intent(context: Context, visitId: String) =
            Intent(context, VisitDetailsActivity::class.java).putExtra(EXTRA_VISIT_ID, visitId)
    }
}
