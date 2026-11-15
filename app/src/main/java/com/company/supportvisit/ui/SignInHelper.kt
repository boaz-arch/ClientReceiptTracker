package com.company.supportvisit.ui

import android.app.Activity
import android.util.Log
import android.widget.Toast
import com.company.supportvisit.R
import com.company.supportvisit.graph.MicrosoftAuthManager
import com.company.supportvisit.graph.SignInCancelledException

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
            Toast.makeText(activity, R.string.sign_in_failed, Toast.LENGTH_LONG).show()
            false
        }
}
