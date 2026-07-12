package com.company.supportvisit.graph

import com.company.supportvisit.model.VisitRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Base64

object MailService {

    private val client =
        OkHttpClient()

    fun sendVisitMail(

        visit: VisitRequest,

        pdfFile: File

    ) {

        val token =
            MicrosoftAuthManager.getToken()

        if (token.isBlank()) {

            throw Exception(
                "Microsoft account not connected"
            )
        }

        val pdfBytes =
            pdfFile.readBytes()

        val pdfBase64 =
            Base64.getEncoder()
                .encodeToString(
                    pdfBytes
                )

        val bodyText =
            """
Client: ${visit.clientName}

Date: ${visit.visitDate}

Start Time: ${visit.startTime}

End Time: ${visit.endTime}

Email: ${visit.clientEmail}

Description:

${visit.workDescription}
            """.trimIndent()

        val json = """
{
  "message": {

    "subject": "Support Visit Report - ${visit.clientName}",

    "body": {
      "contentType": "Text",
      "content": ${JSONObject.quote(bodyText)}
    },

    "toRecipients": [
      {
        "emailAddress": {
          "address":
          "${visit.clientEmail}"
        }
      },
      {
        "emailAddress": {
          "address":
          "${MicrosoftAuthManager.getEmail()}"
        }
      }
    ],

    "attachments": [
      {
        "@odata.type":
        "#microsoft.graph.fileAttachment",

        "name":
        "${pdfFile.name}",

        "contentType":
        "application/pdf",

        "contentBytes":
        "$pdfBase64"
      }
    ]
  },

  "saveToSentItems": true
}
        """.trimIndent()

        val request =
            Request.Builder()

                .url(
                    "https://graph.microsoft.com/v1.0/me/sendMail"
                )

                .addHeader(
                    "Authorization",
                    "Bearer $token"
                )

                .post(
                    json.toRequestBody(
                        "application/json"
                            .toMediaType()
                    )
                )

                .build()

        client.newCall(request)
            .execute()
            .use {

                if (!it.isSuccessful) {

                    throw Exception(
                        it.body?.string()
                            ?: "Email Failed"
                    )
                }
            }
    }
}
