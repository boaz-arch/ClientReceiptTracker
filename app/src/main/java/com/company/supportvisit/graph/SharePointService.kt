package com.company.supportvisit.graph

import com.company.supportvisit.model.VisitRequest
import com.company.supportvisit.utils.Constants

object SharePointService {

    fun createVisit(
        visit: VisitRequest,
        signatureUrl: String
    ) {

        val token = AuthManager.getToken()

        val json = """
        {
          "fields": {
        
            "Title":"${visit.clientName}",
        
            "VisitID":"${visit.visitId}",
        
            "VisitDate":"${visit.visitDate}",
        
            "StartTime":"${visit.startTime}",
        
            "EndTime":"${visit.endTime}",
        
            "WorkDescription":"${visit.workDescription}",
        
            "ClientEmail":"${visit.clientEmail}",
        
            "SignatureUrl":"$signatureUrl"
          }
        }
        """.trimIndent()

        val url =
            "https://graph.microsoft.com/v1.0/sites/" +
                    "${Constants.SITE_ID}/lists/" +
                    "${Constants.LIST_ID}/items"

        GraphClient.post(
            url,
            token,
            json
        )
    }

    fun getVisits(): String? {

        val token =
            AuthManager.getToken()

        val url =
            "https://graph.microsoft.com/v1.0/sites/" +
                    "${Constants.SITE_ID}/lists/" +
                    "${Constants.LIST_ID}/items?expand=fields"

        return GraphClient.get(
            url,
            token
        )
    }
}