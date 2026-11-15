package com.company.supportvisit

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.company.supportvisit.account.AccountDeletion
import com.company.supportvisit.billing.RetainerLevel
import com.company.supportvisit.dashboard.DashboardData
import com.company.supportvisit.dashboard.DashboardViewModel
import com.company.supportvisit.dashboard.DateRange
import com.company.supportvisit.dashboard.LabeledValue
import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.data.VisitType
import com.company.supportvisit.databinding.ActivityMainBinding
import com.company.supportvisit.databinding.ItemRetainerRowBinding
import com.company.supportvisit.graph.AuthManager
import com.company.supportvisit.graph.MicrosoftAuthManager
import com.company.supportvisit.ui.DonutChartView
import com.company.supportvisit.ui.applySystemBarInsets
import com.company.supportvisit.ui.labelRes
import com.company.supportvisit.utils.AppConfig
import com.company.supportvisit.utils.DateUtils
import com.company.supportvisit.utils.Money
import com.google.android.material.datepicker.MaterialDatePicker
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.views.cartesian.CartesianChartView
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Home screen: the dashboard, with New Visit as the main action. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val viewModel: DashboardViewModel by viewModels()

    private var deletionPromptShown = false

    private val clientsProducer = CartesianChartModelProducer()
    private val weeksProducer = CartesianChartModelProducer()
    private val techniciansProducer = CartesianChartModelProducer()

    private var clientLabels: List<String> = emptyList()
    private var weekLabels: List<String> = emptyList()
    private var technicianLabels: List<String> = emptyList()

    private var filterClients: List<ClientEntity> = emptyList()
    private var filterTechnicians: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (AppConfig.LOGIN_ENABLED && !AuthManager.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        binding.btnNewVisit.setOnClickListener { startActivity(Intent(this, NewVisitActivity::class.java)) }
        binding.btnPastVisits.setOnClickListener { startActivity(PastVisitsActivity.intent(this)) }
        binding.btnMonthlyReports.setOnClickListener { startActivity(Intent(this, MonthlyReportActivity::class.java)) }
        binding.btnClients.setOnClickListener { startActivity(Intent(this, ClientsActivity::class.java)) }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        setupCharts()
        setupFilters()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    if (MicrosoftAuthManager.refreshAccount() != null) checkPendingDeletion()
                    MicrosoftAuthManager.accountEmail.collect { email ->
                        binding.tvUser.text = if (email.isNullOrBlank()) {
                            getString(R.string.signed_in_guest)
                        } else {
                            getString(R.string.signed_in_as, email.substringBefore("@"))
                        }
                    }
                }
                launch { viewModel.data.collect { it?.let(::render) } }
                launch { viewModel.clients.collect(::updateClientFilter) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    // region Charts

    private fun setupCharts() {
        binding.chartClients.useLabels { clientLabels }
        binding.chartWeeks.useLabels { weekLabels }
        binding.chartTechnicians.useLabels { technicianLabels }
        binding.chartClients.modelProducer = clientsProducer
        binding.chartWeeks.modelProducer = weeksProducer
        colorLegendDots()
        binding.chartTechnicians.modelProducer = techniciansProducer
    }

    /** Colours the two "●" in the weekly legend like the lines they stand for. */
    private fun colorLegendDots() {
        val text = android.text.SpannableString(getString(R.string.chart_weekly_legend))
        val dots = Regex("●").findAll(text).map { it.range.first }.toList()
        listOf(R.color.md_primary, R.color.md_secondary).zip(dots).forEach { (color, at) ->
            text.setSpan(
                android.text.style.ForegroundColorSpan(getColor(color)), at, at + 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.tvWeeksLegend.text = text
    }

    /** Shows category names under the columns instead of numbers. */
    private fun CartesianChartView.useLabels(labels: () -> List<String>) {
        val current = chart ?: return
        val axis = current.bottomAxis as? HorizontalAxis ?: return
        chart = current.copy(
            bottomAxis = axis.copy(
                valueFormatter = CartesianValueFormatter { _, x, _ -> labels().getOrElse(x.toInt()) { "" } }
            )
        )
    }

    private fun render(d: DashboardData) {
        binding.tvKpiVisits.text = d.visitCount.toString()
        binding.tvKpiHours.text = Money.hours(d.hours)
        binding.cardKpiAmount.isVisible = d.amount != null
        d.amount?.let { binding.tvKpiAmount.text = Money.format(this, it) }
        binding.tvKpiUnsent.text = d.unsentCount.toString()
        binding.tvKpiUnsent.setTextColor(getColor(if (d.unsentCount > 0) R.color.status_pending else R.color.status_done))

        renderColumns(binding.chartClients, binding.tvClientsEmpty, clientsProducer, d.hoursPerClient) { clientLabels = it }
        renderWeeks(d)

        // Hours per technician: useful once there's a team. TODO(Phase 5): managers only.
        binding.cardTechnicians.isVisible = d.hoursPerTechnician.size > 1
        if (binding.cardTechnicians.isVisible) {
            renderColumns(binding.chartTechnicians, null, techniciansProducer, d.hoursPerTechnician) { technicianLabels = it }
        }

        renderRetainers(d)
        renderTypes(d.visitsByType)
        updateTechnicianFilter(d.technicians)
    }

    private fun renderColumns(
        chart: CartesianChartView,
        empty: TextView?,
        producer: CartesianChartModelProducer,
        values: List<LabeledValue>,
        setLabels: (List<String>) -> Unit
    ) {
        chart.isVisible = values.isNotEmpty()
        empty?.isVisible = values.isEmpty()
        if (values.isEmpty()) return
        setLabels(values.map { it.label.take(MAX_LABEL) })
        lifecycleScope.launch { producer.runTransaction { columnSeries { series(values.map { it.value }) } } }
    }

    private fun renderWeeks(d: DashboardData) {
        val formatter = DateTimeFormatter.ofPattern("d/M")
        weekLabels = d.weeks.map { it.weekStart.format(formatter) }
        lifecycleScope.launch {
            weeksProducer.runTransaction {
                lineSeries {
                    series(d.weeks.map { it.visits })
                    series(d.weeks.map { it.hours })
                }
            }
        }
    }

    private fun renderRetainers(d: DashboardData) {
        binding.cardRetainers.isVisible = d.retainers.isNotEmpty()
        binding.retainerContainer.removeAllViews()
        d.retainers.forEach { row ->
            val item = ItemRetainerRowBinding.inflate(LayoutInflater.from(this), binding.retainerContainer, true)
            item.tvName.text = row.client.name
            item.tvUsage.text = getString(R.string.retainer_short, Money.hours(row.usage.usedHours), Money.hours(row.usage.allowanceHours))
            item.progress.max = 100
            item.progress.progress = (row.usage.fraction * 100).toInt().coerceIn(0, 100)
            val color = getColor(
                when (row.usage.level) {
                    RetainerLevel.OK -> R.color.md_primary
                    RetainerLevel.WARNING -> R.color.status_pending
                    RetainerLevel.OVER -> R.color.status_failed
                }
            )
            item.progress.setIndicatorColor(color)
        }
    }

    private fun renderTypes(counts: Map<VisitType, Int>) {
        val colors = listOf(R.color.md_primary, R.color.md_secondary, R.color.md_tertiary, R.color.status_pending, R.color.md_outline)
        val entries = VisitType.entries.mapIndexedNotNull { i, type ->
            counts[type]?.takeIf { it > 0 }?.let { Triple(type, it, getColor(colors[i % colors.size])) }
        }
        binding.tvTypesEmpty.isVisible = entries.isEmpty()
        binding.donutTypes.isVisible = entries.isNotEmpty()
        binding.donutTypes.setSlices(entries.map { DonutChartView.Slice(it.second.toFloat(), it.third) })
        binding.typeLegend.removeAllViews()
        val total = entries.sumOf { it.second }
        entries.forEach { (type, count, color) ->
            val tv = TextView(this).apply {
                text = getString(R.string.type_legend_line, getString(type.labelRes()), count, count * 100 / total)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.legend_dot, 0, 0, 0)
                compoundDrawablesRelative[0]?.mutate()?.setTint(color)
                compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
                setPadding(0, 4, 0, 4)
            }
            binding.typeLegend.addView(tv)
        }
    }

    // endregion

    // region Filters

    private fun setupFilters() {
        binding.chipRange.setOnCheckedStateChangeListener { _, ids ->
            when (ids.firstOrNull()) {
                R.id.chipThisMonth -> viewModel.setRange(DateRange.ThisMonth)
                R.id.chipLastMonth -> viewModel.setRange(DateRange.LastMonth)
                R.id.chipQuarter -> viewModel.setRange(DateRange.ThisQuarter)
            }
        }
        // Custom range: pick dates every time the chip is tapped.
        binding.chipCustom.setOnClickListener { pickCustomRange() }

        binding.actFilterClient.setOnItemClickListener { _, _, i, _ ->
            viewModel.setClient(if (i == 0) null else filterClients[i - 1].id)
        }
        binding.actFilterTechnician.setOnItemClickListener { _, _, i, _ ->
            viewModel.setTechnician(if (i == 0) null else filterTechnicians[i - 1])
        }
        binding.actFilterClient.setText(getString(R.string.filter_all), false)
        binding.actFilterTechnician.setText(getString(R.string.filter_all), false)
    }

    private fun pickCustomRange() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.range_custom)
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val from = selection.first?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
            val to = selection.second?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
            if (from != null && to != null) {
                viewModel.setRange(DateRange.Custom(from, to))
                val f = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
                binding.chipCustom.text = getString(R.string.range_custom_value, from.format(f), to.format(f))
            }
        }
        picker.show(supportFragmentManager, "range")
    }

    private fun updateClientFilter(clients: List<ClientEntity>) {
        filterClients = clients
        val labels = listOf(getString(R.string.filter_all)) + clients.map { it.name }
        binding.actFilterClient.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
    }

    private fun updateTechnicianFilter(technicians: List<String>) {
        if (technicians == filterTechnicians) return
        filterTechnicians = technicians
        // Only worth showing once more than one technician has visits. TODO(Phase 5): managers only.
        binding.tilFilterTechnician.isVisible = technicians.size > 1
        val labels = listOf(getString(R.string.filter_all)) + technicians.map { it.substringBefore('@') }
        binding.actFilterTechnician.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
    }

    // endregion

    /** If this account is scheduled for deletion, offer to restore it (within the 14 days). */
    private suspend fun checkPendingDeletion() {
        if (deletionPromptShown) return
        val status = try {
            AccountDeletion.status(this)
        } catch (e: Exception) {
            null // offline or not set up: ask next time
        } ?: return
        if (!status.pending) return
        deletionPromptShown = true
        val date = status.purgeAt?.let {
            DateUtils.formatForDisplay(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString())
        }.orEmpty()
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_account_title)
            .setMessage(getString(R.string.restore_account_message, date))
            .setCancelable(false)
            .setPositiveButton(R.string.restore_account) { _, _ ->
                lifecycleScope.launch {
                    try {
                        AccountDeletion.restore(this@MainActivity)
                        Toast.makeText(this@MainActivity, R.string.restore_account_done, Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, R.string.action_failed_offline, Toast.LENGTH_LONG).show()
                        deletionPromptShown = false
                    }
                }
            }
            .setNegativeButton(R.string.restore_account_keep_deleting) { _, _ ->
                lifecycleScope.launch {
                    AccountDeletion.wipeLocalData(this@MainActivity)
                    runCatching { MicrosoftAuthManager.signOut() }
                }
            }
            .show()
    }

    companion object {
        private const val MAX_LABEL = 12
    }
}
