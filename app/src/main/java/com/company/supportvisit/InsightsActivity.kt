package com.company.supportvisit

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.company.supportvisit.billing.RetainerLevel
import com.company.supportvisit.dashboard.DashboardData
import com.company.supportvisit.dashboard.DashboardViewModel
import com.company.supportvisit.dashboard.DateRange
import com.company.supportvisit.dashboard.LabeledValue
import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.data.VisitType
import com.company.supportvisit.databinding.ActivityInsightsBinding
import com.company.supportvisit.databinding.DialogDashboardFiltersBinding
import com.company.supportvisit.databinding.ItemRetainerRowBinding
import com.company.supportvisit.team.TeamSession
import com.company.supportvisit.ui.DonutChartView
import com.company.supportvisit.ui.applySystemBarInsets
import com.company.supportvisit.ui.setUpBackBar
import com.company.supportvisit.ui.labelRes
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Insights: charts for the chosen period, client and technician. */
class InsightsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInsightsBinding

    private val viewModel: DashboardViewModel by viewModels()

    private val clientsProducer = CartesianChartModelProducer()
    private val weeksProducer = CartesianChartModelProducer()
    private val techniciansProducer = CartesianChartModelProducer()

    private var clientLabels: List<String> = emptyList()
    private var weekLabels: List<String> = emptyList()
    private var technicianLabels: List<String> = emptyList()

    private var clients: List<ClientEntity> = emptyList()
    private var technicians: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInsightsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        setUpBackBar()

        binding.chartClients.useLabels { clientLabels }
        binding.chartWeeks.useLabels { weekLabels }
        binding.chartTechnicians.useLabels { technicianLabels }
        binding.chartClients.modelProducer = clientsProducer
        binding.chartWeeks.modelProducer = weeksProducer
        binding.chartTechnicians.modelProducer = techniciansProducer
        colorLegendDots()

        binding.chipRange.setOnCheckedStateChangeListener { _, ids ->
            when (ids.firstOrNull()) {
                R.id.chipThisMonth -> viewModel.setRange(DateRange.ThisMonth)
                R.id.chipLastMonth -> viewModel.setRange(DateRange.LastMonth)
                R.id.chipQuarter -> viewModel.setRange(DateRange.ThisQuarter)
            }
        }
        binding.chipCustom.setOnClickListener { pickCustomRange() }
        binding.btnFilters.setOnClickListener { showFilters() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.data.collect { it?.let(::render) } }
                launch { viewModel.clients.collect { clients = it; updateFilterLabel() } }
                launch { viewModel.filter.collect { updateFilterLabel() } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun render(d: DashboardData) {
        technicians = d.technicians
        binding.tvKpiVisits.text = d.visitCount.toString()
        binding.tvKpiHours.text = Money.hours(d.hours)
        binding.colKpiAmount.isVisible = d.amount != null
        d.amount?.let { binding.tvKpiAmount.text = Money.format(this, it) }

        // One calm message instead of several empty cards.
        val emptyPeriod = d.visitCount == 0
        binding.tvEmptyPeriod.isVisible = emptyPeriod

        binding.cardClients.isVisible = d.hoursPerClient.isNotEmpty()
        renderColumns(clientsProducer, d.hoursPerClient) { clientLabels = it }

        binding.cardWeeks.isVisible = d.weeks.any { it.visits > 0 }
        renderWeeks(d)

        // Comparing technicians is for managers and admins, and only once there's a team.
        binding.cardTechnicians.isVisible = TeamSession.role(this).canSeeEverything && d.hoursPerTechnician.size > 1
        if (binding.cardTechnicians.isVisible) renderColumns(techniciansProducer, d.hoursPerTechnician) { technicianLabels = it }

        renderRetainers(d)
        renderTypes(d.visitsByType)
    }

    private fun renderColumns(
        producer: CartesianChartModelProducer,
        values: List<LabeledValue>,
        setLabels: (List<String>) -> Unit
    ) {
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
            item.progress.setIndicatorColor(
                getColor(
                    when (row.usage.level) {
                        RetainerLevel.OK -> R.color.md_primary
                        RetainerLevel.WARNING -> R.color.status_pending
                        RetainerLevel.OVER -> R.color.status_failed
                    }
                )
            )
        }
    }

    private fun renderTypes(counts: Map<VisitType, Int>) {
        val colors = listOf(R.color.md_primary, R.color.md_secondary, R.color.md_tertiary, R.color.status_pending, R.color.md_outline)
        val entries = VisitType.entries.mapIndexedNotNull { i, type ->
            counts[type]?.takeIf { it > 0 }?.let { Triple(type, it, getColor(colors[i % colors.size])) }
        }
        binding.cardTypes.isVisible = entries.isNotEmpty()
        binding.donutTypes.setSlices(entries.map { DonutChartView.Slice(it.second.toFloat(), it.third) })
        binding.typeLegend.removeAllViews()
        val total = entries.sumOf { it.second }.coerceAtLeast(1)
        entries.forEach { (type, count, color) ->
            binding.typeLegend.addView(TextView(this).apply {
                text = getString(R.string.type_legend_line, getString(type.labelRes()), count, count * 100 / total)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.legend_dot, 0, 0, 0)
                compoundDrawablesRelative[0]?.mutate()?.setTint(color)
                compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
                setPadding(0, 4, 0, 4)
            })
        }
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

    /** Colours the two "●" in the weekly legend like the lines they stand for. */
    private fun colorLegendDots() {
        val text = SpannableString(getString(R.string.chart_weekly_legend))
        val dots = Regex("●").findAll(text).map { it.range.first }.toList()
        listOf(R.color.md_primary, R.color.md_secondary).zip(dots).forEach { (color, at) ->
            text.setSpan(ForegroundColorSpan(getColor(color)), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.tvWeeksLegend.text = text
    }

    // region Filters

    private fun showFilters() {
        val dialog = DialogDashboardFiltersBinding.inflate(layoutInflater)
        val clientLabels = listOf(getString(R.string.filter_all)) + clients.map { it.name }
        val techLabels = listOf(getString(R.string.filter_all)) + technicians.map { it.substringBefore('@') }
        dialog.actFilterClient.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, clientLabels))
        dialog.actFilterTechnician.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, techLabels))
        val f = viewModel.filter.value
        dialog.actFilterClient.setText(clients.firstOrNull { it.id == f.clientId }?.name ?: getString(R.string.filter_all), false)
        dialog.actFilterTechnician.setText(f.technician?.substringBefore('@') ?: getString(R.string.filter_all), false)
        // Managers and admins only, and only once more than one technician has visits.
        dialog.tilFilterTechnician.isVisible = TeamSession.role(this).canSeeEverything && technicians.size > 1

        var clientId = f.clientId
        var technician = f.technician
        dialog.actFilterClient.setOnItemClickListener { _, _, i, _ -> clientId = if (i == 0) null else clients[i - 1].id }
        dialog.actFilterTechnician.setOnItemClickListener { _, _, i, _ -> technician = if (i == 0) null else technicians[i - 1] }

        AlertDialog.Builder(this)
            .setTitle(R.string.filters)
            .setView(dialog.root)
            .setPositiveButton(R.string.apply) { _, _ ->
                viewModel.setClient(clientId)
                viewModel.setTechnician(technician)
            }
            .setNeutralButton(R.string.clear) { _, _ ->
                viewModel.setClient(null)
                viewModel.setTechnician(null)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateFilterLabel() {
        val f = viewModel.filter.value
        val client = clients.firstOrNull { it.id == f.clientId }?.name
        val parts = listOfNotNull(client, f.technician?.substringBefore('@'))
        binding.btnFilters.text = if (parts.isEmpty()) getString(R.string.filter_all_clients) else parts.joinToString(" · ")
    }

    private fun pickCustomRange() {
        val picker = MaterialDatePicker.Builder.dateRangePicker().setTitleText(R.string.range_custom).build()
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

    // endregion

    companion object {
        private const val MAX_LABEL = 12
    }
}
