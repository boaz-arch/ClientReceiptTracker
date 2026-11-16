package com.company.supportvisit.auth

import android.content.Context
import com.company.supportvisit.remote.ServerApi
import com.company.supportvisit.settings.SecretStore
import com.company.supportvisit.settings.ServerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

/** Not signed in (or the session ended): the user has to sign in again. */
class AuthRequiredException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Email sign-in with the Support Visit server: the server emails a one-time code, which is
 * traded for a long-lived session token. The token is kept encrypted with the Android Keystore.
 */
object AuthSession {

    private const val KEY_TOKEN = "session_token"
    private const val KEY_EMAIL = "session_email"

    private lateinit var appContext: Context

    private val _accountEmail = MutableStateFlow<String?>(null)

    /** Email of the signed-in account, or null when signed out. */
    val accountEmail: StateFlow<String?> = _accountEmail.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        _accountEmail.value = if (token() != null) SecretStore.get(appContext, KEY_EMAIL) else null
    }

    val isSignedIn: Boolean get() = token() != null

    /** The signed-in email, or null. */
    fun refreshAccount(): String? = accountEmail.value

    /** Session token for the server. Throws [AuthRequiredException] when signed out. */
    fun getApiToken(): String = token() ?: throw AuthRequiredException("Not signed in")

    /** Step 1: the server emails a 6-digit code to [email]. */
    suspend fun sendCode(email: String) = withContext(Dispatchers.IO) {
        api().startSignIn(email.trim(), Locale.getDefault().language)
    }

    /** Step 2: trades the code for a session. Returns the email signed in with. */
    suspend fun verifyCode(email: String, code: String): String = withContext(Dispatchers.IO) {
        val (token, signedIn) = api().verifySignIn(email.trim(), code.trim())
        SecretStore.put(appContext, KEY_TOKEN, token)
        SecretStore.put(appContext, KEY_EMAIL, signedIn)
        _accountEmail.value = signedIn
        signedIn
    }

    /** Ends the session on the server (best effort) and on this phone. */
    suspend fun signOut() {
        val token = token()
        if (token != null) runCatching { withContext(Dispatchers.IO) { api(token).signOut() } }
        clear()
    }

    /** The server no longer accepts the session (expired, revoked, removed from the company). */
    fun clear() {
        SecretStore.put(appContext, KEY_TOKEN, null)
        _accountEmail.value = null
    }

    private fun token(): String? = if (::appContext.isInitialized) SecretStore.get(appContext, KEY_TOKEN) else null

    private fun api(token: String = ""): ServerApi {
        val url = ServerSettings.serverUrl(appContext) ?: throw IllegalStateException("No server configured")
        return ServerApi(url, token)
    }
}
