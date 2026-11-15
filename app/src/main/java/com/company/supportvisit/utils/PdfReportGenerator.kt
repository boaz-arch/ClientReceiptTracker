package com.company.supportvisit.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.company.supportvisit.R
import com.company.supportvisit.data.VisitEntity
import com.company.supportvisit.data.partsTotal
import com.company.supportvisit.settings.FormSettings
import com.company.supportvisit.settings.BillingSettings
import com.company.supportvisit.billing.Billing
import com.company.supportvisit.billing.bill
import com.company.supportvisit.ui.labelRes
import java.io.File

object PdfReportGenerator {

    // A4 at 72 dpi.
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val FOOTER_HEIGHT = 40f
    private const val CONTENT_WIDTH = (PAGE_WIDTH - 2 * MARGIN).toInt()

    private const val SIGNATURE_MAX_WIDTH = 300f
    private const val SIGNATURE_MAX_HEIGHT = 120f

    /** File name contains the visit id so re-uploads overwrite instead of duplicating. */
    fun fileNameFor(visit: VisitEntity): String {
        val client = visit.clientName.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40)
        return "Visit_${visit.visitDate}_${client}_${visit.id.take(8)}.pdf"
    }

    /** Photos in the PDF appendix are scaled to this long side; full-size copies are uploaded separately. */
    private const val PDF_PHOTO_MAX_SIDE = 480

    fun createReport(
        context: Context,
        visit: VisitEntity,
        signature: Bitmap,
        outputDir: File,
        technicianSignature: Bitmap? = null
    ): File {
        val titlePaint = textPaint(20f, bold = true)
        val labelPaint = textPaint(12f, bold = true)
        val bodyPaint = textPaint(12f)
        val footerPaint = textPaint(9f).apply { color = Color.DKGRAY }

        val writer = PageWriter(context, footerPaint)

        writer.paragraph(context.getString(R.string.pdf_title), titlePaint, spacingAfter = 18f)

        fun field(labelRes: Int, value: String) {
            writer.paragraph(context.getString(labelRes), labelPaint, spacingAfter = 2f)
            writer.paragraph(value.ifBlank { "-" }, bodyPaint, spacingAfter = 10f)
        }

        field(R.string.label_client, visit.clientName)
        field(R.string.label_date, DateUtils.formatForDisplay(visit.visitDate))
        field(R.string.label_time, context.getString(R.string.time_range, visit.startTime, visit.endTime))
        if (FormSettings.isEnabled(context, FormSettings.Section.VISIT_TYPE)) {
            field(R.string.label_visit_type, context.getString(visit.type.labelRes()))
        }
        field(R.string.label_client_email, visit.clientEmail)
        visit.technicianEmail?.let { field(R.string.label_technician, it) }

        writer.space(6f)
        field(R.string.label_work_description, visit.workDescription)

        if (visit.parts.isNotEmpty()) {
            writer.space(4f)
            writer.paragraph(context.getString(R.string.label_parts), labelPaint, spacingAfter = 4f)
            val partPrices = BillingSettings.showPricesOnPdf(context)
            visit.parts.forEach { part ->
                if (!partPrices) {
                    writer.paragraph(
                        context.getString(R.string.pdf_part_line_no_price, part.description, Money.quantity(part.quantity)),
                        bodyPaint,
                        spacingAfter = 2f
                    )
                    return@forEach
                }
                writer.paragraph(
                    context.getString(
                        R.string.pdf_part_line,
                        part.description,
                        Money.quantity(part.quantity),
                        Money.format(context, part.unitPrice),
                        Money.format(context, part.total)
                    ),
                    bodyPaint,
                    spacingAfter = 2f
                )
            }
            if (partPrices) {
                writer.paragraph(
                    context.getString(R.string.parts_total, Money.format(context, visit.parts.partsTotal())),
                    labelPaint,
                    spacingAfter = 10f
                )
            } else {
                writer.space(8f)
            }
        }

        if (FormSettings.isEnabled(context, FormSettings.Section.BILLING)) {
            val bill = visit.bill()
            val showPrices = BillingSettings.showPricesOnPdf(context) && bill.hourlyRate != null
            writer.space(4f)
            writer.paragraph(context.getString(R.string.billing_section), labelPaint, spacingAfter = 4f)
            val lines = mutableListOf(
                context.getString(R.string.pdf_billable_time, Billing.formatMinutes(bill.billableMinutes))
            )
            if (bill.breakMinutes > 0) lines += context.getString(R.string.pdf_break, bill.breakMinutes)
            if (showPrices) {
                val vatPercent = BillingSettings.vatPercent(context)
                val vat = Billing.vat(bill.total, vatPercent)
                lines += context.getString(R.string.pdf_rate, Money.format(context, bill.hourlyRate!!))
                lines += context.getString(R.string.pdf_labor, Money.format(context, bill.laborAmount))
                if (bill.partsAmount > 0) lines += context.getString(R.string.pdf_parts_amount, Money.format(context, bill.partsAmount))
                lines += context.getString(R.string.pdf_subtotal, Money.format(context, bill.total))
                lines += context.getString(R.string.pdf_vat, Money.quantity(vatPercent), Money.format(context, vat))
                lines += context.getString(R.string.pdf_total_with_vat, Money.format(context, Billing.round2(bill.total + vat)))
            }
            lines.forEach { writer.paragraph(it, bodyPaint, spacingAfter = 2f) }
            writer.space(8f)
        }

        writer.space(10f)
        writer.paragraph(context.getString(R.string.label_client_signature), labelPaint, spacingAfter = 6f)
        writer.bitmap(signature, SIGNATURE_MAX_WIDTH, SIGNATURE_MAX_HEIGHT)

        if (technicianSignature != null) {
            writer.space(10f)
            writer.paragraph(context.getString(R.string.label_technician_signature), labelPaint, spacingAfter = 6f)
            writer.bitmap(technicianSignature, SIGNATURE_MAX_WIDTH, SIGNATURE_MAX_HEIGHT)
        }

        val photoPaths = visit.photos.mapNotNull { it.localPath }.filter { File(it).exists() }
        if (photoPaths.isNotEmpty()) {
            writer.pageBreak()
            writer.paragraph(context.getString(R.string.pdf_photos_title), titlePaint, spacingAfter = 12f)
            photoPaths.forEachIndexed { index, path ->
                val photo = PhotoCompressor.loadScaled(path, PDF_PHOTO_MAX_SIDE) ?: return@forEachIndexed
                writer.paragraph(context.getString(R.string.pdf_photo_caption, index + 1), labelPaint, spacingAfter = 4f)
                writer.bitmap(photo, CONTENT_WIDTH.toFloat(), (PAGE_HEIGHT - 2 * MARGIN - FOOTER_HEIGHT) / 2 - 30f)
                writer.space(12f)
                photo.recycle()
            }
        }

        outputDir.mkdirs()
        val file = File(outputDir, fileNameFor(visit))
        writer.finish(file)
        return file
    }

    private fun textPaint(size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = Color.BLACK
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** Lays content out top to bottom, starting a new page whenever the next piece doesn't fit. */
    private class PageWriter(private val context: Context, private val footerPaint: TextPaint) {

        private val document = PdfDocument()
        private var page: PdfDocument.Page? = null
        private var pageNumber = 0
        private var y = MARGIN

        private val bottom get() = PAGE_HEIGHT - MARGIN - FOOTER_HEIGHT

        private val canvas: Canvas
            get() = (page ?: newPage()).canvas

        private fun newPage(): PdfDocument.Page {
            page?.let { finishPage(it) }
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            return document.startPage(info).also {
                page = it
                y = MARGIN
            }
        }

        private fun finishPage(p: PdfDocument.Page) {
            val footer = context.getString(R.string.pdf_footer, context.getString(R.string.company_footer), pageNumber)
            val layout = layout(footer, footerPaint, Layout.Alignment.ALIGN_CENTER)
            p.canvas.save()
            p.canvas.translate(MARGIN, PAGE_HEIGHT - MARGIN - layout.height)
            layout.draw(p.canvas)
            p.canvas.restore()
            document.finishPage(p)
        }

        fun space(height: Float) {
            y += height
        }

        fun pageBreak() {
            newPage()
        }

        /** Draws wrapped text; long text continues on as many pages as needed. */
        fun paragraph(text: String, paint: TextPaint, spacingAfter: Float = 0f) {
            val layout = layout(text, paint, Layout.Alignment.ALIGN_NORMAL)
            var line = 0
            while (line < layout.lineCount) {
                if (page == null || y + lineHeight(layout, line) > bottom) newPage()

                // Take as many lines as fit in the space left on this page.
                val startTop = layout.getLineTop(line)
                var end = line
                while (end < layout.lineCount && y + (layout.getLineBottom(end) - startTop) <= bottom) end++
                if (end == line) end = line + 1 // a single line taller than a page: draw it anyway

                val endBottom = layout.getLineBottom(end - 1)
                canvas.save()
                canvas.translate(MARGIN, y - startTop)
                canvas.clipRect(0f, startTop.toFloat(), CONTENT_WIDTH.toFloat(), endBottom.toFloat())
                layout.draw(canvas)
                canvas.restore()

                y += endBottom - startTop
                line = end
            }
            y += spacingAfter
        }

        fun bitmap(bitmap: Bitmap, maxWidth: Float, maxHeight: Float) {
            val scale = minOf(maxWidth / bitmap.width, maxHeight / bitmap.height, 1f)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            if (page == null || y + h > bottom) newPage()
            val start = if (isRtl()) PAGE_WIDTH - MARGIN - w else MARGIN
            canvas.drawBitmap(bitmap, null, RectF(start, y, start + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            y += h
        }

        fun finish(file: File) {
            page?.let { finishPage(it) } ?: run { newPage(); finishPage(page!!) }
            file.outputStream().use { document.writeTo(it) }
            document.close()
        }

        private fun lineHeight(layout: StaticLayout, line: Int) =
            layout.getLineBottom(line) - layout.getLineTop(line)

        private fun isRtl() =
            context.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL

        private fun layout(text: String, paint: TextPaint, alignment: Layout.Alignment): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, CONTENT_WIDTH)
                .setAlignment(alignment)
                // Each paragraph takes its direction from its first strong character,
                // so Hebrew and English text both render correctly.
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setLineSpacing(2f, 1f)
                .build()
    }
}
