package com.company.supportvisit

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.company.supportvisit.adapter.VisitAdapter
import com.company.supportvisit.databinding.ActivityPastVisitsBinding
import com.company.supportvisit.ui.PastVisitsViewModel
import com.company.supportvisit.ui.SignInHelper
import kotlinx.coroutines.launch

class PastVisitsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPastVisitsBinding

    private val viewModel: PastVisitsViewModel by viewModels()

    private val adapter = VisitAdapter(
        onClick = { visit ->
            startActivity(VisitDetailsActivity.intent(this, visit.id))
        },
        onRetry = { visit -> retry(visit.id) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPastVisitsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerVisits.layoutManager = LinearLayoutManager(this)
        binding.recyclerVisits.adapter = adapter

        binding.etSearch.doAfterTextChanged { viewModel.setQuery(it?.toString().orEmpty()) }

        binding.btnRefresh.setOnClickListener {
            viewModel.syncNow()
            Toast.makeText(this, R.string.sync_started, Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.visits.collect { visits ->
                    if (visits == null) return@collect
                    adapter.submitList(visits)
                    binding.tvEmpty.visibility = if (visits.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    /** Makes sure the Microsoft session is valid (interactive if needed), then re-queues the visit. */
    private fun retry(id: String) {
        lifecycleScope.launch {
            if (SignInHelper.ensureSignedIn(this@PastVisitsActivity)) {
                viewModel.retry(id)
                Toast.makeText(this@PastVisitsActivity, R.string.retry_queued, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        fun intent(activity: AppCompatActivity) = Intent(activity, PastVisitsActivity::class.java)
    }
}
