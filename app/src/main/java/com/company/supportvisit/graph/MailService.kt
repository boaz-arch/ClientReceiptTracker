package com.company.supportvisit.graph

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

object MailService {

    /**
     * sendMail requests are limited to 4 MB, and base64 adds a third. Larger PDFs are not
     * attached; the email links to the uploaded copy instead.
     */
    const val MAX_ATTACHMENT_BYTES = 2_900_000L

    fun canAttach(file: File): Boolean = file.length() <= MAX_ATTACHMENT_BYTES

    /** Builds the Graph sendMail payload with JSONObject so every value is escaped correctly. */
    fun buildSendMailJson(
        subject: String,
        body: String,
        recipients: List<String>,
        attachmentName: String?,
        attachmentBase64: String?
    ): JSONObject {
        val toRecipients = JSONArray()
        recipients.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { address ->
            toRecipients.put(JSONObject().put("emailAddress", JSONObject().put("address", address)))
        }

        val message = JSONObject()
            .put("subject", subject)
            .put("body", JSONObject().put("contentType", "Text").put("content", body))
            .put("toRecipients", toRecipients)

        if (attachmentName != null && attachmentBase64 != null) {
            val attachment = JSONObject()
                .put("@odata.type", "#microsoft.graph.fileAttachment")
                .put("name", attachmentName)
                .put("contentType", "application/pdf")
                .put("contentBytes", attachmentBase64)
            message.put("attachments", JSONArray().put(attachment))
        }

        return JSONObject()
            .put("message", message)
            .put("saveToSentItems", true)
    }

    /** One file to attach. */
    data class Attachment(val file: File, val contentType: String)

    /** Sends a message with several attachments (total must stay under the 4 MB request limit). */
    fun sendMailWithAttachments(
        token: String,
        subject: String,
        body: String,
        recipients: List<String>,
        attachments: List<Attachment>
    ) {
        val json = buildSendMailJson(subject, body, recipients, null, null)
        val list = JSONArray()
        attachments.forEach { a ->
            list.put(
                JSONObject()
                    .put("@odata.type", "#microsoft.graph.fileAttachment")
                    .put("name", a.file.name)
                    .put("contentType", a.contentType)
                    .put("contentBytes", Base64.getEncoder().encodeToString(a.file.readBytes()))
            )
        }
        json.getJSONObject("message").put("attachments", list)
        val request = Request.Builder()
            .url("${GraphHttp.BASE_URL}/me/sendMail")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        GraphHttp.execute(request, token)
    }

    fun sendMail(
        token: String,
        subject: String,
        body: String,
        recipients: List<String>,
        pdfFile: File?
    ) {
        val json = buildSendMailJson(
            subject = subject,
            body = body,
            recipients = recipients,
            attachmentName = pdfFile?.name,
            attachmentBase64 = pdfFile?.let { Base64.getEncoder().encodeToString(it.readBytes()) }
        )

        val request = Request.Builder()
            .url("${GraphHttp.BASE_URL}/me/sendMail")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        GraphHttp.execute(request, token)
    }
}
