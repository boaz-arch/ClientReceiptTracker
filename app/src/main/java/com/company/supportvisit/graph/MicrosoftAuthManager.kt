package com.company.supportvisit.graph

import android.app.Activity
import android.content.Context
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
 * Single-account MSAL wrapper. The client application is created once for the whole app
 * (from [init] in the Application class); every Graph call asks [getAccessToken] for a token,
 * which MSAL serves from its cache or refreshes silently.
 */
object MicrosoftAuthManager {

    val SCOPES = listOf("User.Read", "Mail.Send", "Files.ReadWrite")

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
    suspend fun getAccessToken(activity: Activity? = null): String {
        val application = awaitApp()
        val account = currentAccount()
        if (account == null) {
            if (activity == null) throw AuthRequiredException("No Microsoft account signed in")
            return signIn(activity).accessToken
        }

        return try {
            withContext(Dispatchers.IO) {
                val params = AcquireTokenSilentParameters.Builder()
                    .forAccount(account)
                    .fromAuthority(account.authority)
                    .withScopes(SCOPES)
                    .build()
                application.acquireTokenSilent(params).accessToken
            }
        } catch (e: MsalUiRequiredException) {
            if (activity == null) throw AuthRequiredException("Interactive sign-in required", e)
            acquireTokenInteractive(activity, application, account).accessToken
        }
    }

    /** Signs in, or if an account is already signed in, makes sure it still has a valid token. */
    suspend fun connect(activity: Activity): String {
        getAccessToken(activity)
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

    private suspend fun signIn(activity: Activity): IAuthenticationResult {
        val application = awaitApp()
        val result = suspendCancellableCoroutine { cont ->
            val params = SignInParameters.builder()
                .withActivity(activity)
                .withScopes(SCOPES)
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
        account: IAccount
    ): IAuthenticationResult = suspendCancellableCoroutine { cont ->
        val params = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .forAccount(account)
            .withScopes(SCOPES)
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
