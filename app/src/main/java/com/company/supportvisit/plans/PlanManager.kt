package com.company.supportvisit.plans

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.company.supportvisit.BuildConfig
import com.company.supportvisit.R
import com.company.supportvisit.team.TeamSession

/** Features that a paid plan can unlock. */
enum class Feature {
    /** Charts and statistics (Insights). */
    INSIGHTS,
    /** Hourly rates, monthly client reports and invoices. */
    BILLING,
    /** More than one person in the company. */
    TEAM
}

/**
 * The single place that decides what the company's plan allows.
 *
 * Today the plan comes from the server ("PRO" when the vendor granted it with `grant-pro` or
 * PRO_TENANT_IDS). Google Play Billing subscriptions or license keys can be added here later
 * without touching the screens.
 *
 * Enforcement is OFF unless the app is built with ENFORCE_PLANS=true, so nothing is locked
 * until pricing is decided.
 */
object PlanManager {

    /** Free plan: the number of people a company can have. */
    const val FREE_MEMBER_LIMIT = 1

    fun isPro(context: Context): Boolean = TeamSession.serverPlan(context).equals("PRO", ignoreCase = true)

    fun isEnforced(): Boolean = BuildConfig.ENFORCE_PLANS

    fun allows(context: Context, feature: Feature): Boolean = !isEnforced() || isPro(context) || freeAllows(feature)

    /** Whether the company may add one more person, given how many it has now. */
    fun canAddMember(context: Context, currentMembers: Int): Boolean =
        !isEnforced() || isPro(context) || currentMembers < FREE_MEMBER_LIMIT

    /**
     * Runs [action] if the plan allows [feature]; otherwise explains that it needs Pro.
     * Returns whether the action ran.
     */
    fun runIfAllowed(context: Context, feature: Feature, action: () -> Unit): Boolean {
        if (allows(context, feature)) {
            action()
            return true
        }
        showUpgrade(context)
        return false
    }

    fun showUpgrade(context: Context) {
        AlertDialog.Builder(context)
            .setTitle(R.string.pro_feature_title)
            .setMessage(R.string.pro_feature_message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun freeAllows(feature: Feature): Boolean = false
}
