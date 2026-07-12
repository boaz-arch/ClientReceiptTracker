package com.company.supportvisit.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import com.company.supportvisit.model.VisitRequest
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.scale

object PdfReportGenerator {

    fun createReport(
        context: Context,
        visit: VisitRequest,
        signatureBitmap: Bitmap
    ): File {

        val document = PdfDocument()

        val pageInfo =
            PdfDocument.PageInfo.Builder(
                595,
                842,
                1
            ).create()

        val page =
            document.startPage(pageInfo)

        val canvas =
            page.canvas

        val paint =
            android.graphics.Paint()

        paint.textSize = 18f

        var y = 60

        canvas.drawText(
            "Support Visit Report",
            40f,
            y.toFloat(),
            paint
        )

        y += 50

        canvas.drawText(
            "Client: ${visit.clientName}",
            40f,
            y.toFloat(),
            paint
        )

        y += 30

        canvas.drawText(
            "Date: ${visit.visitDate}",
            40f,
            y.toFloat(),
            paint
        )

        y += 30

        canvas.drawText(
            "Start Time: ${visit.startTime}",
            40f,
            y.toFloat(),
            paint
        )

        y += 30

        canvas.drawText(
            "End Time: ${visit.endTime}",
            40f,
            y.toFloat(),
            paint
        )

        y += 30

        canvas.drawText(
            "Email: ${visit.clientEmail}",
            40f,
            y.toFloat(),
            paint
        )

        y += 50

        canvas.drawText(
            "Work Description:",
            40f,
            y.toFloat(),
            paint
        )

        y += 35

        paint.textSize = 14f

        canvas.drawText(
            visit.workDescription,
            40f,
            y.toFloat(),
            paint
        )

        y += 120

        paint.textSize = 18f

        canvas.drawText(
            "Authorization Signature",
            40f,
            y.toFloat(),
            paint
        )

        y += 20

        val scaledSignature =
            signatureBitmap.scale(300, 120)

        canvas.drawBitmap(
            scaledSignature,
            40f,
            y.toFloat(),
            null
        )

        y += 180

        canvas.drawText(
            "Livnelavan LTD",
            40f,
            y.toFloat(),
            paint
        )

        document.finishPage(page)

        val file =
            File(
                context.filesDir,
                "Visit_${System.currentTimeMillis()}.pdf"
            )

        FileOutputStream(file).use {

            document.writeTo(it)
        }

        document.close()

        return file
    }
}