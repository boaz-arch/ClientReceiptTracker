package com.company.supportvisit.team

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.company.supportvisit.R
import com.company.supportvisit.billing.Billing
import com.company.supportvisit.billing.bill
import com.company.supportvisit.data.VisitEntity
import com.company.supportvisit.utils.DateUtils

/** The company's name, details and logo on reports, and its email template. */
object Branding {

    fun companyName(context: Context): String = TeamSession.company(context).name.trim()

    /** "Address · Phone · Email · Company no." — only the parts that are filled in. */
    fun detailsLine(context: Context): String {
        val p = TeamSession.company(context)
        return listOfNotNull(
            p.address.replace('\n', ' ').takeIf { it.isNotBlank() },
            p.phone.takeIf { it.isNotBlank() },
            p.email.takeIf { it.isNotBlank() },
            p.website.takeIf { it.isNotBlank() },
            p.companyNumber.takeIf { it.isNotBlank() }?.let { context.getString(R.string.pdf_company_number, it) }
        ).joinToString("  ·  ")
    }

    fun logo(context: Context): Bitmap? = TeamSession.logoFile(context)?.let { BitmapFactory.decodeFile(it.path) }

    /** PDF page footer: "Company · Page 3", or just "Page 3" before the company is set up. */
    fun pageFooter(context: Context, page: Int): String {
        val name = companyName(context)
        return if (name.isEmpty()) context.getString(R.string.pdf_page, page)
        else context.getString(R.string.pdf_footer, name, page)
    }

    /** Extra addresses the admin wants on every visit email (office, manager...). */
    fun extraRecipients(context: Context): List<String> = TeamSession.company(context).emailRecipients

    fun emailSubject(context: Context, visit: VisitEntity): String {
        val template = TeamSession.company(context).emailSubjectTemplate
        if (template.isBlank()) return context.getString(R.string.email_subject, visit.clientName)
        return fill(template, values(context, visit)).ifBlank { context.getString(R.string.email_subject, visit.clientName) }
    }

    fun emailBody(context: Context, visit: VisitEntity, attached: Boolean): String {
        val template = TeamSession.company(context).emailBodyTemplate
        val body = if (template.isBlank()) {
            context.getString(
                R.string.email_body,
                visit.clientName,
                DateUtils.formatForDisplay(visit.visitDate),
                visit.startTime,
                visit.endTime,
                visit.workDescription,
                ""
            ).trimEnd()
        } else {
            fill(template, values(context, visit)).trimEnd()
        }
        return if (attached) body else body + "\n\n" + context.getString(R.string.email_report_too_large)
    }

    /** Placeholders an admin can use in the subject and body. */
    fun values(context: Context, visit: VisitEntity): Map<String, String> = mapOf(
        "client" to visit.clientName,
        "date" to DateUtils.formatForDisplay(visit.visitDate),
        "time" to context.getString(R.string.time_range, visit.startTime, visit.endTime),
        "hours" to Billing.formatMinutes(visit.bill().billableMinutes),
        "technician" to (visit.technicianEmail?.substringBefore('@') ?: ""),
        "description" to visit.workDescription,
        "company" to companyName(context)
    )

    /** Replaces {name} placeholders; unknown ones are left as typed so a typo is visible. */
    fun fill(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { m -> values[m.groupValues[1].lowercase()] ?: m.value }

    private val PLACEHOLDER = Regex("\\{([A-Za-z]+)\\}")
}
