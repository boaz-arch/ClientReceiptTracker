package com.company.supportvisit.team

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

enum class Role {
    ADMIN, MANAGER, TECHNICIAN;

    /** Managers and admins see everyone's visits, the team dashboard and all tasks. */
    val canSeeEverything: Boolean get() = this != TECHNICIAN

    /** Admins manage the company profile, settings and members. */
    val canManageCompany: Boolean get() = this == ADMIN

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: TECHNICIAN
    }
}

/** The company profile set by an admin (branding and email template). */
data class CompanyProfile(
    val name: String = "",
    val address: String = "",
    val companyNumber: String = "",
    val phone: String = "",
    val email: String = "",
    val website: String = "",
    /** Extra recipients copied on every visit report. */
    val emailRecipients: List<String> = emptyList(),
    val emailSubjectTemplate: String = "",
    val emailBodyTemplate: String = "",
    val hasLogo: Boolean = false,
    val settings: JSONObject? = null
) {
    val isConfigured: Boolean get() = name.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("address", address).put("companyNumber", companyNumber)
        .put("phone", phone).put("email", email).put("website", website)
        .put("emailRecipients", org.json.JSONArray(emailRecipients))
        .put("emailSubjectTemplate", emailSubjectTemplate).put("emailBodyTemplate", emailBodyTemplate)

    companion object {
        fun from(json: JSONObject?): CompanyProfile {
            if (json == null) return CompanyProfile()
            val recipients = json.optJSONArray("emailRecipients")
                ?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
            return CompanyProfile(
                name = json.optString("name"),
                address = json.optString("address"),
                companyNumber = json.optString("companyNumber"),
                phone = json.optString("phone"),
                email = json.optString("email"),
                website = json.optString("website"),
                emailRecipients = recipients,
                emailSubjectTemplate = json.optString("emailSubjectTemplate"),
                emailBodyTemplate = json.optString("emailBodyTemplate"),
                hasLogo = json.optBoolean("hasLogo"),
                settings = json.optJSONObject("settings")
            )
        }
    }
}

/**
 * What this phone knows about the signed-in member and their company, cached so the app works
 * offline. Refreshed from the server by [TeamRepository].
 */
object TeamSession {

    private const val PREFS = "team"
    private const val KEY_ROLE = "role"
    private const val KEY_PLAN = "plan"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_MEMBERS = "member_count"
    private const val KEY_COMPANY = "company"

    private val _role = MutableStateFlow(Role.TECHNICIAN)
    val role: StateFlow<Role> = _role.asStateFlow()

    private val _company = MutableStateFlow(CompanyProfile())
    val company: StateFlow<CompanyProfile> = _company.asStateFlow()

    fun load(context: Context) {
        val p = prefs(context)
        _role.value = Role.from(p.getString(KEY_ROLE, null))
        _company.value = CompanyProfile.from(p.getString(KEY_COMPANY, null)?.let { runCatching { JSONObject(it) }.getOrNull() })
    }

    fun role(context: Context): Role = Role.from(prefs(context).getString(KEY_ROLE, null))

    fun userId(context: Context): String = prefs(context).getString(KEY_USER_ID, "").orEmpty()

    fun memberCount(context: Context): Int = prefs(context).getInt(KEY_MEMBERS, 1)

    /** Plan granted on the server ("PRO" or "FREE"); Play subscriptions are checked by PlanManager. */
    fun serverPlan(context: Context): String = prefs(context).getString(KEY_PLAN, "FREE").orEmpty()

    fun company(context: Context): CompanyProfile =
        CompanyProfile.from(prefs(context).getString(KEY_COMPANY, null)?.let { runCatching { JSONObject(it) }.getOrNull() })

    /** Logo cached on this phone (null if the company has none). */
    fun logoFile(context: Context): File? =
        File(File(context.filesDir, "company"), "logo").takeIf { it.exists() && company(context).hasLogo }

    internal fun logoTarget(context: Context) = File(File(context.filesDir, "company"), "logo")

    internal fun saveMe(context: Context, userId: String, role: String, plan: String, memberCount: Int) {
        prefs(context).edit {
            putString(KEY_USER_ID, userId); putString(KEY_ROLE, role); putString(KEY_PLAN, plan); putInt(KEY_MEMBERS, memberCount)
        }
        _role.value = Role.from(role)
    }

    internal fun saveCompany(context: Context, json: JSONObject) {
        prefs(context).edit { putString(KEY_COMPANY, json.toString()) }
        _company.value = CompanyProfile.from(json)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
