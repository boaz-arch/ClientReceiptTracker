package com.company.supportvisit.graph

import android.app.Activity
import android.content.Context
import com.company.supportvisit.BuildConfig
import com.company.supportvisit.R
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SignInCancelledException : Exception("Sign-in cancelled")

/**
 * True when Microsoft refused because an admin hasn't consented to the app's permissions
 * (AADSTS65001 consent required, AADSTS90094 admin approval required).
 */
fun Throwable.isConsentMissing(): Boolean {
    var e: Throwable? = this
    while (e != null) {
        val text = (e.message.orEmpty() + " " + ((e as? MsalException)?.errorCode).orEmpty())
        if (listOf("AADSTS65001", "AADSTS90094", "AADSTS90095", "consent_required").any { text.contains(it) }) return true
        e = e.cause
    }
    return false
}

/**
 * Single-account MSAL wrapper. The client application is created once for the whole app
 * (from [init] in the Application class); every Graph call asks [getAccessToken] for a token,
 * which MSAL serves from its cache or refreshes silently.
 */
object MicrosoftAuthManager {

    /** Microsoft Graph scopes: profile, sending the report email, OneDrive fallback upload. */
    val SCOPES = listOf("User.Read", "Mail.Send", "Files.ReadWrite")

    /** The Support Visit server's own API scope (a separate token from Graph). */
    val API_SCOPES: List<String> = listOfNotNull(BuildConfig.API_SCOPE.takeIf { it.isNotBlank() })

    private val app = CompletableDeferred<ISingleAccountPublicClientApplication>()

    private val _accountEmail = MutableStateFlow<String?>(null)

    /** Email of the signed-in account, or null when signed out. */
    val accountEmail: StateFlow<String?> = _accountEmail.asStateFlow()

    fun init(context: Context) {
        PublicClientApplication.createSingleAccountPublicClientApplication(
            context.applicationContext,
            R.raw.msal_config,
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(application: ISingleAccountPublicClientApplication) {
                    app.complete(application)
                }

                override fun onError(exception: MsalException) {
                    app.completeExceptionally(exception)
                }
            }
        )
    }

    /** Reloads the signed-in account from MSAL's cache. */
    suspend fun refreshAccount(): String? {
        val email = try {
            currentAccount()?.username
        } catch (e: Exception) {
            null
        }
        _accountEmail.value = email
        return email
    }

    /**
     * Returns a valid access token. Tries the cache / silent refresh first. If the user has to
     * interact (expired session, new consent, MFA) and an [activity] is given, it falls back to
     * interactive sign-in; otherwise it throws [AuthRequiredException].
     */
    suspend fun getAccessToken(activity: Activity? = null, scopes: List<String> = SCOPES): String {
        val application = awaitApp()
        val account = currentAccount()
        if (account == null) {
            if (activity == null) throw AuthRequiredException("No Microsoft account signed in")
            return signIn(activity, scopes).accessToken
        }

        return try {
            withContext(Dispatchers.IO) {
                val params = AcquireTokenSilentParameters.Builder()
                    .forAccount(account)
                    .fromAuthority(account.authority)
                    .withScopes(scopes)
                    .build()
                application.acquireTokenSilent(params).accessToken
            }
        } catch (e: MsalUiRequiredException) {
            if (activity == null) throw AuthRequiredException("Interactive sign-in required", e)
            acquireTokenInteractive(activity, application, account, scopes).accessToken
        }
    }

    /** Token for the Support Visit server. Throws [AuthRequiredException] if not signed in. */
    suspend fun getApiToken(activity: Activity? = null): String {
        if (API_SCOPES.isEmpty()) throw AuthRequiredException("API scope not configured")
        return getAccessToken(activity, API_SCOPES)
    }

    /**
     * Signs in (or checks the existing session) and gets consent for both Graph and the
     * server API, so background sync can then run without showing any screen.
     */
    suspend fun connect(activity: Activity): String {
        getAccessToken(activity)
        if (API_SCOPES.isNotEmpty()) getAccessToken(activity, API_SCOPES)
        return refreshAccount().orEmpty()
    }

    suspend fun signOut() {
        val application = awaitApp()
        withContext(Dispatchers.IO) { application.signOut() }
        _accountEmail.value = null
    }

    private suspend fun awaitApp(): ISingleAccountPublicClientApplication =
        try {
            app.await()
        } catch (e: MsalException) {
            throw AuthRequiredException("MSAL could not be initialised", e)
        }

    private suspend fun currentAccount(): IAccount? {
        val application = awaitApp()
        return withContext(Dispatchers.IO) { application.currentAccount?.currentAccount }
    }

    private suspend fun signIn(activity: Activity, scopes: List<String>): IAuthenticationResult {
        val application = awaitApp()
        val result = suspendCancellableCoroutine { cont ->
            val params = SignInParameters.builder()
                .withActivity(activity)
                .withScopes(scopes)
                .withCallback(continuationCallback(cont))
                .build()
            application.signIn(params)
        }
        _accountEmail.value = result.account.username
        return result
    }

    private suspend fun acquireTokenInteractive(
        activity: Activity,
        application: ISingleAccountPublicClientApplication,
        account: IAccount,
        scopes: List<String>
    ): IAuthenticationResult = suspendCancellableCoroutine { cont ->
        val params = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .forAccount(account)
            .withScopes(scopes)
            .withCallback(continuationCallback(cont))
            .build()
        application.acquireToken(params)
    }

    private fun continuationCallback(cont: CancellableContinuation<IAuthenticationResult>) =
        object : AuthenticationCallback {
            override fun onSuccess(result: IAuthenticationResult) {
                if (cont.isActive) cont.resume(result)
            }

            override fun onError(exception: MsalException) {
                if (cont.isActive) cont.resumeWithException(exception)
            }

            override fun onCancel() {
                if (cont.isActive) cont.resumeWithException(SignInCancelledException())
            }
        }
}
