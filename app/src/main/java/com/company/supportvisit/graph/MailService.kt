package com.company.supportvisit.graph

import com.company.supportvisit.model.VisitRequest
import com.company.supportvisit.utils.EmailBuilder
import org.json.JSONObject

object MailService {

    fun sendVisitMail(

        visit: VisitRequest,

        signatureUrl: String

    ) {

        val token =
            AuthManager.getToken()

        val html =
            EmailBuilder.buildHtml(

                visit,

                signatureUrl
            )

        val json = """
        {
          "message": {

            "subject":
            "Support Visit ${visit.visitId}",

            "body": {
              "contentType": "HTML",
              "content": ${JSONObject.quote(html)}
            },

            "toRecipients": [
              {
                "emailAddress": {
                  "address":
                  "${visit.clientEmail}"
                }
              }
            ],

            "ccRecipients": [
              {
                "emailAddress": {
                  "address":
                  "YOUR_EMAIL@COMPANY.COM"
                }
              }
            ]
          }
        }
        """.trimIndent()

        GraphClient.post(

            "https://graph.microsoft.com/v1.0/me/sendMail",

            token,

            json
        )
    }
}
