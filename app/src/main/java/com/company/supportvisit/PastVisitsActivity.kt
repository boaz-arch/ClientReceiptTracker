package com.company.supportvisit

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.company.supportvisit.adapter.VisitAdapter
import com.company.supportvisit.databinding.ActivityPastVisitsBinding
import com.company.supportvisit.repository.GraphRepository
import com.company.supportvisit.repository.VisitCache
import com.company.supportvisit.utils.LoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PastVisitsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPastVisitsBinding

    private lateinit var adapter: VisitAdapter

    private lateinit var loading: LoadingDialog

    private val allVisits =
        VisitCache.visits

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        binding =
            ActivityPastVisitsBinding.inflate(
                layoutInflater
            )

        setContentView(binding.root)

        loading =
            LoadingDialog(this)

        adapter =
            VisitAdapter(emptyList()) { visit ->

                val intent =
                    Intent(
                        this,
                        VisitDetailsActivity::class.java
                    )

                intent.putExtra(
                    "visitId",
                    visit.visitId
                )

                intent.putExtra(
                    "client",
                    visit.clientName
                )

                intent.putExtra(
                    "date",
                    visit.visitDate
                )

                intent.putExtra(
                    "startTime",
                    visit.startTime
                )

                intent.putExtra(
                    "endTime",
                    visit.endTime
                )

                intent.putExtra(
                    "email",
                    visit.clientEmail
                )

                intent.putExtra(
                    "description",
                    visit.workDescription
                )

                intent.putExtra(
                    "signatureUrl",
                    visit.signatureUrl
                )

                startActivity(intent)
            }

        binding.recyclerVisits.layoutManager =
            LinearLayoutManager(this)

        binding.recyclerVisits.adapter =
            adapter

        binding.btnRefresh.setOnClickListener {

            loadVisits()
        }

        binding.etSearch.addTextChangedListener {

            val search =
                it.toString()

            val filtered =
                allVisits.filter { visit ->

                    visit.clientName.contains(
                        search,
                        true
                    ) ||

                            visit.visitId.contains(
                                search,
                                true
                            ) ||

                            visit.workDescription.contains(
                                search,
                                true
                            ) ||

                            visit.visitDate.contains(
                                search,
                                true
                            )
                }

            adapter.updateData(
                filtered
            )
        }

        loadVisits()
    }

    private fun loadVisits() {

        loading.show(
            "Loading Visits..."
        )

        lifecycleScope.launch {

            try {

                val visits =
                    withContext(
                        Dispatchers.IO
                    ) {

                        GraphRepository.loadVisits()
                    }

                allVisits.clear()

                allVisits.addAll(
                    visits
                )

                VisitCache.clear()

                VisitCache.visits.addAll(
                    visits
                )

                adapter.updateData(
                    allVisits
                )

            } finally {

                loading.dismiss()
            }
        }
    }
}