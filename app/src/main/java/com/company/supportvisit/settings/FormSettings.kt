package com.company.supportvisit.settings

import android.content.Context
import androidx.core.content.edit

/** Which optional sections the visit report (New Visit form and PDF) includes. */
object FormSettings {

    /** An optional report section and whether it is on when nobody has changed it. */
    enum class Section(val key: String, val defaultOn: Boolean) {
        VISIT_TYPE("report_visit_type", true),
        PHOTOS("report_photos", true),
        PARTS("report_parts", true),
        TECHNICIAN_SIGNATURE("technician_signature_enabled", false)
    }

    private const val PREFS = "app_settings"

    fun isEnabled(context: Context, section: Section): Boolean =
        prefs(context).getBoolean(section.key, section.defaultOn)

    fun setEnabled(context: Context, section: Section, enabled: Boolean) {
        prefs(context).edit { putBoolean(section.key, enabled) }
    }

    fun technicianSignatureEnabled(context: Context) = isEnabled(context, Section.TECHNICIAN_SIGNATURE)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
