package com.company.supportvisit.ui

import android.app.Activity
import com.company.supportvisit.SignInActivity
import com.company.supportvisit.auth.AuthSession

object SignInHelper {

    /**
     * True when signed in. Otherwise opens the sign-in screen and returns false: the user
     * repeats the action once signed in.
     */
    fun ensureSignedIn(activity: Activity): Boolean {
        if (AuthSession.isSignedIn) return true
        activity.startActivity(SignInActivity.intent(activity))
        return false
    }
}
