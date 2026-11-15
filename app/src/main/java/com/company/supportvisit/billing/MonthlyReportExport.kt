package com.company.supportvisit.billing

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.company.supportvisit.R
import com.company.supportvisit.ui.labelRes
import com.company.supportvisit.utils.CsvExporter
import com.company.supportvisit.utils.DateUtils
import com.company.supportvisit.utils.Money
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Monthly report as CSV (opens in Excel) and as a PDF summary for the client. */
object MonthlyReportExport {

    fun monthLabel(report: MonthlyReport, locale: Locale = Locale.getDefault()): String =
        report.month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale) + " " + report.month.year

    fun fileBaseName(report: MonthlyReport): String {
        val client = report.client.name.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40)
        return "Report_${client}_${report.month.format(DateTimeFormatter.ofPattern("yyyy-MM"))}"
    }

    /** CSV with one row per visit, then totals. Plain numbers (no currency signs) so Excel can sum them. */
    fun toCsv(report: MonthlyReport): String = buildString {
        fun row(vararg cells: Any?) = append(cells.joinToString(",") { CsvExporter.escape(it?.toString()) }).append("\r\n")
        row("Date", "Start", "End", "Type", "Technician", "Description", "Break (min)", "Billable (min)",
            "Billable (h)", "Rate", "Labor", "Parts", "Total")
        report.lines.forEach { (v, b) ->
            row(v.visitDate, v.startTime, v.endTime, v.type.key, v.technicianEmail, v.workDescription, b.breakMinutes,
                b.billableMinutes, "%.2f".format(Locale.US, b.billableHours), b.hourlyRate?.let { "%.2f".format(Locale.US, it) },
                "%.2f".format(Locale.US, b.laborAmount), "%.2f".format(Locale.US, b.partsAmount), "%.2f".format(Locale.US, b.total))
        }
        append("\r\n")
        row("Client", report.client.name)
        row("Month", report.month.toString())
        row("Currency", report.currency)
        row("Billable hours", "%.2f".format(Locale.US, report.billableMinutes / 60.0))
        report.client.monthlyHoursAllowance?.let { row("Monthly hours included", "%.2f".format(Locale.US, it)) }
        row("Labor", "%.2f".format(Locale.US, report.laborAmount))
        row("Parts", "%.2f".format(Locale.US, report.partsAmount))
        row("Subtotal", "%.2f".format(Locale.US, report.subtotal))
        row("VAT ${report.vatPercent}%", "%.2f".format(Locale.US, report.vat))
        row("Total", "%.2f".format(Locale.US, report.total))
    }

    fun writeCsv(report: MonthlyReport, dir: File): File {
        dir.mkdirs()
        return File(dir, fileBaseName(report) + ".csv").also { it.writeText(0xFEFF.toChar() + toCsv(report), Charsets.UTF_8) }
    }

    // region PDF

    private const val W = 595
    private const val H = 842
    private const val M = 40f
    private const val CONTENT = W - 2 * M

    fun writePdf(context: Context, report: MonthlyReport, dir: File, showPrices: Boolean = true): File {
        dir.mkdirs()
        val file = File(dir, fileBaseName(report) + ".pdf")
        val doc = PdfDocument()
        val title = paint(18f, bold = true)
        val bold = paint(11f, bold = true)
        val body = paint(10f)
        val small = paint(8.5f).apply { color = Color.DKGRAY }

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun finish() {
            page?.let { p ->
                val footer = context.getString(R.string.pdf_footer, context.getString(R.string.company_footer), pageNo)
                draw(p, footer, small, M, H - M - 12f, CONTENT, Layout.Alignment.ALIGN_CENTER)
                doc.finishPage(p)
            }
        }

        fun newPage() {
            finish()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M
        }

        fun ensure(height: Float) {
            if (page == null || y + height > H - M - 30f) newPage()
        }

        fun text(s: String, p: TextPaint, after: Float = 4f, width: Float = CONTENT, x: Float = M) {
            val h = layout(s, p, width).height.toFloat()
            ensure(h)
            draw(page!!, s, p, x, y, width)
            y += h + after
        }

        newPage()
        text(context.getString(R.string.monthly_report_title), title, 6f)
        text(context.getString(R.string.monthly_report_subtitle, report.client.name, monthLabel(report)), bold, 14f)

        // Visit table: date | type | description | time | amount
        val cols = if (showPrices) floatArrayOf(70f, 70f, 245f, 50f, 80f) else floatArrayOf(70f, 80f, 315f, 50f)
        val heads = buildList {
            add(context.getString(R.string.label_date)); add(context.getString(R.string.label_visit_type))
            add(context.getString(R.string.label_work_description)); add(context.getString(R.string.report_col_hours))
            if (showPrices) add(context.getString(R.string.report_col_amount))
        }

        fun row(cells: List<String>, p: TextPaint) {
            val heights = cells.mapIndexed { i, c -> layout(c, p, cols[i] - 6f).height }
            val h = (heights.maxOrNull() ?: 0).toFloat() + 6f
            ensure(h)
            var x = M
            cells.forEachIndexed { i, c ->
                draw(page!!, c, p, x, y, cols[i] - 6f)
                x += cols[i]
            }
            y += h
            page!!.canvas.drawLine(M, y - 2f, M + CONTENT, y - 2f, Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f })
        }

        row(heads, bold)
        if (report.isEmpty) text(context.getString(R.string.monthly_report_no_visits), body, 8f)
        report.lines.forEach { (v, b) ->
            row(
                buildList {
                    add(DateUtils.formatForDisplay(v.visitDate))
                    add(context.getString(v.type.labelRes()))
                    add(v.workDescription.ifBlank { "-" })
                    add(Billing.formatMinutes(b.billableMinutes))
                    if (showPrices) add(Money.format(b.total, report.currency))
                },
                body
            )
        }
        y += 10f

        if (report.parts.isNotEmpty()) {
            text(context.getString(R.string.label_parts), bold, 4f)
            report.parts.forEach { part ->
                val line = if (showPrices) {
                    context.getString(R.string.pdf_part_line, part.description, Money.quantity(part.quantity),
                        Money.format(part.unitPrice, report.currency), Money.format(part.total, report.currency))
                } else {
                    context.getString(R.string.pdf_part_line_no_price, part.description, Money.quantity(part.quantity))
                }
                text(line, body, 2f)
            }
            y += 8f
        }

        text(context.getString(R.string.report_total_hours, Billing.formatMinutes(report.billableMinutes)), bold, 2f)
        report.retainer?.let { r ->
            text(context.getString(R.string.retainer_usage, Money.hours(r.usedHours), Money.hours(r.allowanceHours)), body, 2f)
        }
        if (showPrices) {
            text(context.getString(R.string.pdf_labor, Money.format(report.laborAmount, report.currency)), body, 2f)
            text(context.getString(R.string.pdf_parts_amount, Money.format(report.partsAmount, report.currency)), body, 2f)
            text(context.getString(R.string.pdf_subtotal, Money.format(report.subtotal, report.currency)), body, 2f)
            text(context.getString(R.string.pdf_vat, Money.quantity(report.vatPercent), Money.format(report.vat, report.currency)), body, 2f)
            text(context.getString(R.string.pdf_total_with_vat, Money.format(report.total, report.currency)), bold, 2f)
        }

        finish()
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun paint(size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = Color.BLACK
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun layout(text: String, paint: TextPaint, width: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(align)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .build()

    private fun draw(
        page: PdfDocument.Page,
        text: String,
        paint: TextPaint,
        x: Float,
        y: Float,
        width: Float,
        align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
    ) {
        val l = layout(text, paint, width, align)
        page.canvas.save()
        page.canvas.translate(x, y)
        l.draw(page.canvas)
        page.canvas.restore()
    }

    // endregion
}
