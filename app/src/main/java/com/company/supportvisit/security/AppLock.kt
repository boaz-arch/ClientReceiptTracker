package com.company.supportvisit.security

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import java.lang.ref.WeakReference
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
        val enabled = isEnabled(activity)
        // Keep app content out of the recent-apps thumbnail while the lock is on.
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!enabled) {
            locked = false
            return
        }
        if (!locked) {
            showContent(activity)
            return
        }
        if (!isAvailable(activity)) {
            // The screen lock was removed from the phone: nothing to check against.
            locked = false
            showContent(activity)
            return
        }
        // Every screen that comes up while locked stays hidden until unlocked.
        activity.findViewById<View>(android.R.id.content)?.visibility = View.INVISIBLE
        if (activity !is FragmentActivity) {
            activity.finishAffinity()
            return
        }
        // A prompt shown on another (now hidden) screen is replaced by one on this screen.
        if (prompting && promptActivity?.get() === activity) return
        prompt(
            activity,
            onSuccess = {
                locked = false
                showContent(activity)
            },
            onFail = { activity.finishAffinity() }
        )
    }

    private fun showContent(activity: Activity) {
        activity.findViewById<View>(android.R.id.content)?.visibility = View.VISIBLE
    }

    private var promptActivity: WeakReference<Activity>? = null

    private fun prompt(activity: FragmentActivity, onSuccess: () -> Unit, onFail: () -> Unit) {
        prompting = true
        promptActivity = WeakReference(activity)
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                promptActivity = null
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
                promptActivity = null
                // Cancelled because another screen took over: that screen asks instead.
                if (errorCode == BiometricPrompt.ERROR_CANCELED && activity.isFinishing.not() && locked &&
                    !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                ) return
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
    override fun onActivityDestroyed(activity: Activity) {
        if (promptActivity?.get() === activity) {
            prompting = false
            promptActivity = null
        }
    }
}
