package com.company.supportvisit.team

import android.content.Context
import android.util.Log
import com.company.supportvisit.auth.AuthSession
import com.company.supportvisit.remote.ServerApi
import com.company.supportvisit.settings.BillingSettings
import com.company.supportvisit.settings.FormSettings
import com.company.supportvisit.settings.ServerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Loads the member's role and plan and the company profile from the server, and keeps the
 * company-wide settings (report sections, billing) the same on every phone of the company.
 */
object TeamRepository {

    private const val TAG = "TeamRepository"
    private const val KEY_PUSHED = "pushed_settings"

    /** Refreshes role, plan, company profile and logo. Silent on failure (cached values stay). */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val api = api(context) ?: return@withContext false
        try {
            // What this phone set up while working alone (before it ever reached the server).
            val wasSolo = !TeamSession.isTeamMember(context)
            val localProfile = TeamSession.company(context)
            val me = api.me()
            TeamSession.saveMe(context, me.userId, me.role, me.plan, me.memberCount)
            var company = api.company()
            // The first admin brings their company profile and logo along instead of starting over.
            if (wasSolo && localProfile.isConfigured && !CompanyProfile.from(company).isConfigured &&
                TeamSession.role(context).canManageCompany
            ) {
                val logo = TeamSession.logoTarget(context)
                if (localProfile.hasLogo && logo.exists()) api.uploadLogo(logo)
                company = api.putCompany(localProfile.toJson().put("hasLogo", localProfile.hasLogo && logo.exists())
                    .put("settings", currentSettings(context)))
            }
            TeamSession.saveCompany(context, company)
            val profile = CompanyProfile.from(company)
            // Small file: refreshed each time so a new logo shows up everywhere.
            if (profile.hasLogo) api.downloadLogo(TeamSession.logoTarget(context))
            // Admins' phones are the source of these settings; everyone else follows them.
            if (!TeamSession.role(context).canManageCompany) profile.settings?.let { applySettings(context, it) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Team refresh failed")
            false
        }
    }

    /**
     * Admin: saves the company profile and, if one was picked, the new logo (already resized PNG).
     * Always kept on this phone; also sent to the server when the phone is part of a team.
     * [removeLogo] clears the logo on this phone (the server keeps its copy until replaced).
     */
    suspend fun saveCompany(context: Context, profile: CompanyProfile, newLogo: File?, removeLogo: Boolean = false) =
        withContext(Dispatchers.IO) {
            val target = TeamSession.logoTarget(context)
            if (newLogo != null) {
                target.parentFile?.mkdirs()
                newLogo.copyTo(target, overwrite = true)
            } else if (removeLogo) {
                target.delete()
            }
            val hasLogo = target.exists()
            val json = profile.toJson().put("hasLogo", hasLogo).put("settings", currentSettings(context))
            val api = if (TeamSession.isTeamMember(context)) api(context) else null
            if (api == null) {
                TeamSession.saveCompany(context, json)
                return@withContext
            }
            if (newLogo != null) api.uploadLogo(target)
            TeamSession.saveCompany(context, api.putCompany(json))
        }

    /** Admin: pushes this phone's report and billing settings to the whole company. */
    suspend fun pushSettings(context: Context) = withContext(Dispatchers.IO) {
        if (!TeamSession.role(context).canManageCompany || !TeamSession.isTeamMember(context)) return@withContext
        val settings = currentSettings(context)
        val prefs = context.getSharedPreferences("team", Context.MODE_PRIVATE)
        if (prefs.getString(KEY_PUSHED, null) == settings.toString()) return@withContext
        val api = api(context) ?: return@withContext
        try {
            TeamSession.saveCompany(context, api.putCompany(JSONObject().put("settings", settings)))
            prefs.edit().putString(KEY_PUSHED, settings.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Pushing settings failed")
        }
    }

    /** The company-wide settings an admin controls. */
    fun currentSettings(context: Context): JSONObject {
        val sections = JSONObject()
        FormSettings.Section.entries.forEach { sections.put(it.name, FormSettings.isEnabled(context, it)) }
        return JSONObject()
            .put("sections", sections)
            .put("currency", BillingSettings.currency(context))
            .put("vatPercent", BillingSettings.vatPercent(context))
            .put("defaultRate", BillingSettings.defaultHourlyRate(context) ?: JSONObject.NULL)
            .put("showPrices", BillingSettings.showPricesOnPdf(context))
            .put("monthlyBilling", BillingSettings.monthlyBillingEnabled(context))
    }

    fun applySettings(context: Context, settings: JSONObject) {
        settings.optJSONObject("sections")?.let { sections ->
            FormSettings.Section.entries.forEach { s -> if (sections.has(s.name)) FormSettings.setEnabled(context, s, sections.getBoolean(s.name)) }
        }
        settings.optString("currency").takeIf { it.isNotBlank() }?.let { BillingSettings.setCurrency(context, it) }
        if (settings.has("vatPercent")) BillingSettings.setVatPercent(context, settings.getDouble("vatPercent"))
        BillingSettings.setDefaultHourlyRate(context, if (settings.isNull("defaultRate")) null else settings.optDouble("defaultRate"))
        if (settings.has("showPrices")) BillingSettings.setShowPricesOnPdf(context, settings.getBoolean("showPrices"))
        if (settings.has("monthlyBilling")) BillingSettings.setMonthlyBillingEnabled(context, settings.getBoolean("monthlyBilling"))
    }

    suspend fun api(context: Context): ServerApi? {
        val url = ServerSettings.serverUrl(context) ?: return null
        if (!AuthSession.isSignedIn) return null
        return ServerApi(url, AuthSession.getApiToken())
    }
}
