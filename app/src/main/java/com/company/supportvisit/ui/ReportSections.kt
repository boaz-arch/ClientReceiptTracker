package com.company.supportvisit.ui

import android.content.Context
import com.company.supportvisit.settings.FormSettings
import com.company.supportvisit.settings.FormSettings.Section

/** Snapshot of which optional report sections are switched on. */
data class ReportSections(
    val visitType: Boolean,
    val photos: Boolean,
    val parts: Boolean,
    val technicianSignature: Boolean
) {
    companion object {
        fun from(context: Context) = ReportSections(
            visitType = FormSettings.isEnabled(context, Section.VISIT_TYPE),
            photos = FormSettings.isEnabled(context, Section.PHOTOS),
            parts = FormSettings.isEnabled(context, Section.PARTS),
            technicianSignature = FormSettings.isEnabled(context, Section.TECHNICIAN_SIGNATURE)
        )
    }
}
