package com.company.supportvisit.team

import android.content.Context
import android.util.Log
import com.company.supportvisit.graph.MicrosoftAuthManager
import com.company.supportvisit.remote.ServerApi
import com.company.supportvisit.settings.BillingSettings
import com.company.supportvisit.settings.FormSettings
import com.company.supportvisit.settings.ServerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Loads the member's role and plan and the company profile from the server, and keeps the
 * company-wide settings (report sections, billing) the same on every phone of the company.
 */
object TeamRepository {

    private const val TAG = "TeamRepository"

    /** Refreshes role, plan, company profile and logo. Silent on failure (cached values stay). */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val api = api(context) ?: return@withContext false
        try {
            val me = api.me()
            TeamSession.saveMe(context, me.userId, me.role, me.plan, me.memberCount)
            val company = api.company()
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

    /** Admin: saves the company profile (and the logo, if a new one was picked). */
    suspend fun saveCompany(context: Context, profile: CompanyProfile, newLogo: java.io.File?) = withContext(Dispatchers.IO) {
        val api = api(context) ?: throw IllegalStateException("No server")
        if (newLogo != null) api.uploadLogo(newLogo)
        val saved = api.putCompany(profile.toJson().put("settings", currentSettings(context)))
        TeamSession.saveCompany(context, saved)
        if (newLogo != null) api.downloadLogo(TeamSession.logoTarget(context))
    }

    /** Admin: pushes this phone's report and billing settings to the whole company. */
    suspend fun pushSettings(context: Context) = withContext(Dispatchers.IO) {
        if (!TeamSession.role(context).canManageCompany) return@withContext
        val api = api(context) ?: return@withContext
        try {
            TeamSession.saveCompany(context, api.putCompany(JSONObject().put("settings", currentSettings(context))))
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
        return ServerApi(url, MicrosoftAuthManager.getApiToken())
    }
}
