package com.company.supportvisit.graph

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A Graph call returned a non-2xx status. The body is kept for logs, never shown to users. */
class GraphException(val code: Int, message: String) : IOException("Graph HTTP $code: $message") {
    /** Throttling and server errors are worth retrying; other 4xx errors are not. */
    val isRetryable: Boolean get() = code == 408 || code == 429 || code >= 500
}

/** No usable Microsoft session: the user must sign in interactively. */
class AuthRequiredException(message: String, cause: Throwable? = null) : Exception(message, cause)

object GraphHttp {

    const val BASE_URL = "https://graph.microsoft.com/v1.0"

    /** One shared client for the whole app (connection pool and threads are reused). */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** Executes a request with a bearer token and returns the body, always closing the response. */
    fun execute(request: Request, token: String): String {
        val authorized = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return client.newCall(authorized).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw GraphException(response.code, body.take(500))
            body
        }
    }
}
