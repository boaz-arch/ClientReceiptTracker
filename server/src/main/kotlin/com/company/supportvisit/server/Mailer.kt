package com.company.supportvisit.server

import jakarta.activation.DataHandler
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.util.Properties

data class MailAttachment(val name: String, val contentType: String, val bytes: ByteArray)

data class Mail(
    val to: List<String>,
    val subject: String,
    val text: String,
    /** Display name on the From line (the address is always the server's own). */
    val fromName: String? = null,
    val replyTo: String? = null,
    val attachments: List<MailAttachment> = emptyList()
)

class MailNotConfiguredException : Exception("Email is not set up on this server")

fun interface Mailer {
    fun send(mail: Mail)
}

/** Refuses to send: used when SMTP isn't configured. */
object NoMailer : Mailer {
    override fun send(mail: Mail) = throw MailNotConfiguredException()
}

/** Sends through the SMTP account in [SmtpConfig]. */
class SmtpMailer(private val smtp: SmtpConfig) : Mailer {

    private val session: Session = Session.getInstance(Properties().apply {
        put("mail.smtp.host", smtp.host)
        put("mail.smtp.port", smtp.port.toString())
        put("mail.smtp.auth", (smtp.username.isNotEmpty()).toString())
        if (smtp.ssl) put("mail.smtp.ssl.enable", "true") else put("mail.smtp.starttls.enable", "true")
        put("mail.smtp.starttls.required", (!smtp.ssl).toString())
        put("mail.smtp.ssl.checkserveridentity", "true")
        put("mail.smtp.connectiontimeout", "15000")
        put("mail.smtp.timeout", "30000")
        put("mail.smtp.writetimeout", "30000")
    })

    override fun send(mail: Mail) {
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(smtp.from, mail.fromName?.take(80), "UTF-8"))
            setRecipients(Message.RecipientType.TO, mail.to.map { InternetAddress(it, true) }.toTypedArray())
            mail.replyTo?.let { replyTo = arrayOf(InternetAddress(it, true)) }
            setSubject(mail.subject, "UTF-8")
            if (mail.attachments.isEmpty()) {
                setText(mail.text, "UTF-8")
            } else {
                val parts = MimeMultipart()
                parts.addBodyPart(MimeBodyPart().apply { setText(mail.text, "UTF-8") })
                mail.attachments.forEach { a ->
                    parts.addBodyPart(MimeBodyPart().apply {
                        dataHandler = DataHandler(ByteArrayDataSource(a.bytes, a.contentType))
                        fileName = a.name
                    })
                }
                setContent(parts)
            }
        }
        if (smtp.username.isNotEmpty()) Transport.send(message, smtp.username, smtp.password)
        else Transport.send(message)
    }
}
