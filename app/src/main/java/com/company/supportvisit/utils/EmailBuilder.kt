package com.company.supportvisit.utils

import com.company.supportvisit.model.VisitRequest

object EmailBuilder {

    fun buildHtml(
        visit: VisitRequest,
        signatureUrl: String
    ): String {

        return """
        <html>
        <body>

        <h2>Support Visit Report</h2>

        <table border="1" cellpadding="8">

            <tr>
                <td>Visit ID</td>
                <td>${visit.visitId}</td>
            </tr>

            <tr>
                <td>Client</td>
                <td>${visit.clientName}</td>
            </tr>

            <tr>
                <td>Date</td>
                <td>${visit.visitDate}</td>
            </tr>

            <tr>
                <td>Start Time</td>
                <td>${visit.startTime}</td>
            </tr>

            <tr>
                <td>End Time</td>
                <td>${visit.endTime}</td>
            </tr>

            <tr>
                <td>Description</td>
                <td>${visit.workDescription}</td>
            </tr>

        </table>

        <br/>

        $signatureUrl
            View Signature
        </a>

        </body>
        </html>
        """.trimIndent()
    }
}