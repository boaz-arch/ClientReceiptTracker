package com.company.supportvisit.graph

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

object MailService {

    /** Builds the Graph sendMail payload with JSONObject so every value is escaped correctly. */
    fun buildSendMailJson(
        subject: String,
        body: String,
        recipients: List<String>,
        attachmentName: String,
        attachmentBase64: String
    ): JSONObject {
        val toRecipients = JSONArray()
        recipients.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { address ->
            toRecipients.put(JSONObject().put("emailAddress", JSONObject().put("address", address)))
        }

        val attachment = JSONObject()
            .put("@odata.type", "#microsoft.graph.fileAttachment")
            .put("name", attachmentName)
            .put("contentType", "application/pdf")
            .put("contentBytes", attachmentBase64)

        val message = JSONObject()
            .put("subject", subject)
            .put("body", JSONObject().put("contentType", "Text").put("content", body))
            .put("toRecipients", toRecipients)
            .put("attachments", JSONArray().put(attachment))

        return JSONObject()
            .put("message", message)
            .put("saveToSentItems", true)
    }

    fun sendMail(
        token: String,
        subject: String,
        body: String,
        recipients: List<String>,
        pdfFile: File
    ) {
        val json = buildSendMailJson(
            subject = subject,
            body = body,
            recipients = recipients,
            attachmentName = pdfFile.name,
            attachmentBase64 = Base64.getEncoder().encodeToString(pdfFile.readBytes())
        )

        val request = Request.Builder()
            .url("${GraphHttp.BASE_URL}/me/sendMail")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        GraphHttp.execute(request, token)
    }
}
