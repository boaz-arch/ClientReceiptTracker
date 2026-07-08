package com.company.supportvisit.excel

import android.content.Context
import com.company.supportvisit.repository.VisitCache
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream

object ExcelExporter {

    fun export(
        context: Context
    ) {

        val workbook =
            XSSFWorkbook()

        val sheet =
            workbook.createSheet(
                "Visits"
            )

        val header =
            sheet.createRow(0)

        header.createCell(0)
            .setCellValue("Visit ID")

        header.createCell(1)
            .setCellValue("Client")

        header.createCell(2)
            .setCellValue("Date")

        header.createCell(3)
            .setCellValue("Start")

        header.createCell(4)
            .setCellValue("End")

        header.createCell(5)
            .setCellValue("Email")

        header.createCell(6)
            .setCellValue("Description")

        VisitCache.visits.forEachIndexed {

                index,
                visit ->

            val row =
                sheet.createRow(
                    index + 1
                )

            row.createCell(0)
                .setCellValue(
                    visit.visitId
                )

            row.createCell(1)
                .setCellValue(
                    visit.clientName
                )

            row.createCell(2)
                .setCellValue(
                    visit.visitDate
                )

            row.createCell(3)
                .setCellValue(
                    visit.startTime
                )

            row.createCell(4)
                .setCellValue(
                    visit.endTime
                )

            row.createCell(5)
                .setCellValue(
                    visit.clientEmail
                )

            row.createCell(6)
                .setCellValue(
                    visit.workDescription
                )
        }

        for (i in 0..6) {

            sheet.autoSizeColumn(i)
        }

        val file =
            File(
                context.filesDir,
                "SupportVisits.xlsx"
            )

        val output =
            FileOutputStream(file)

        workbook.write(output)

        output.close()

        workbook.close()
    }
}