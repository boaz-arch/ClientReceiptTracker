package com.company.supportvisit.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.company.supportvisit.R
import com.company.supportvisit.graph.MicrosoftAuthManager
import com.company.supportvisit.graph.SignInCancelledException
import com.company.supportvisit.graph.isConsentMissing
import org.json.JSONObject

object SignInHelper {

    private const val TAG = "SignInHelper"

    /**
     * Gets a valid Microsoft token, showing the sign-in screen only if needed.
     * Shows a friendly message and returns false if the user cancels or sign-in fails.
     */
    suspend fun ensureSignedIn(activity: Activity): Boolean =
        try {
            MicrosoftAuthManager.connect(activity)
            true
        } catch (e: SignInCancelledException) {
            Toast.makeText(activity, R.string.sign_in_cancelled, Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            Log.w(TAG, "Microsoft sign-in failed", e)
            if (e.isConsentMissing()) showConsentNeeded(activity)
            else Toast.makeText(activity, R.string.sign_in_failed, Toast.LENGTH_LONG).show()
            false
        }

    /**
     * A new company's Microsoft 365 admin has to approve the app once for everyone there.
     * The user can send them the approval link straight from here.
     */
    private fun showConsentNeeded(activity: Activity) {
        val link = adminConsentLink(activity)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.admin_consent_title)
            .setMessage(R.string.admin_consent_missing)
            .setNegativeButton(R.string.close, null)
        if (link != null) {
            dialog.setPositiveButton(R.string.admin_consent_share) { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.admin_consent_share_subject))
                    putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.admin_consent_share_text, link))
                }
                activity.startActivity(Intent.createChooser(share, activity.getString(R.string.admin_consent_share)))
            }
        }
        dialog.show()
    }

    /** Microsoft's admin-consent page for this app (the client id comes from msal_config.json). */
    fun adminConsentLink(context: Context): String? = runCatching {
        val json = context.resources.openRawResource(R.raw.msal_config).bufferedReader().use { it.readText() }
        val clientId = JSONObject(json).optString("client_id").takeIf { it.isNotBlank() } ?: return null
        "https://login.microsoftonline.com/organizations/adminconsent?client_id=$clientId"
    }.getOrNull()
}
