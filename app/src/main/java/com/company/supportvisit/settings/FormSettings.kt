package com.company.supportvisit.settings

import android.content.Context
import androidx.core.content.edit

/** Options that change what the New Visit form asks for. */
object FormSettings {

    private const val PREFS = "app_settings"
    private const val KEY_TECHNICIAN_SIGNATURE = "technician_signature_enabled"

    /** Whether the form shows the (optional) technician signature pad. Off by default. */
    fun technicianSignatureEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TECHNICIAN_SIGNATURE, false)

    fun setTechnicianSignatureEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_TECHNICIAN_SIGNATURE, enabled) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
