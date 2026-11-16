package com.company.supportvisit.security

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import com.company.supportvisit.R

/**
 * Optional app lock: when on, the app asks for the fingerprint, face or phone screen lock
 * after it has been in the background for [TIMEOUT_MS]. Off by default.
 */
object AppLock : Application.ActivityLifecycleCallbacks {

    private const val PREFS = "security"
    private const val KEY_ENABLED = "app_lock"
    private const val TIMEOUT_MS = 60_000L
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private var started = 0
    private var backgroundSince = 0L
    /** Locked at cold start too, so a killed-and-reopened app still asks. */
    private var locked = true
    private var prompting = false

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
        if (enabled) locked = false // they just proved it's them
    }

    /** False when the phone has no fingerprint/face and no screen lock set up. */
    fun isAvailable(context: Context) =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Asks once to turn the lock on (confirms the user can unlock before enabling). */
    fun confirm(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        prompt(activity, onSuccess = { onResult(true) }, onFail = { onResult(false) })
    }

    override fun onActivityStarted(activity: Activity) {
        if (started == 0 && backgroundSince > 0 && SystemClock.elapsedRealtime() - backgroundSince > TIMEOUT_MS) locked = true
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) backgroundSince = SystemClock.elapsedRealtime()
    }

    override fun onActivityResumed(activity: Activity) {
        if (!isEnabled(activity)) {
            locked = false
            return
        }
        if (!locked || prompting || activity !is FragmentActivity) return
        if (!isAvailable(activity)) {
            // The screen lock was removed from the phone: nothing to check against.
            locked = false
            return
        }
        // Hide the content until unlocked.
        val content = activity.findViewById<View>(android.R.id.content)
        content?.visibility = View.INVISIBLE
        prompt(
            activity,
            onSuccess = {
                locked = false
                content?.visibility = View.VISIBLE
            },
            onFail = { activity.finishAffinity() }
        )
    }

    private fun prompt(activity: FragmentActivity, onSuccess: () -> Unit, onFail: () -> Unit) {
        prompting = true
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
                onFail()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.app_lock_prompt_title))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
