package com.company.supportvisit.graph

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object GraphClient {

    val client =
        OkHttpClient()

    fun get(
        url: String,
        token: String
    ): String? {

        val request = Request.Builder()
            .url(url)
            .addHeader(
                "Authorization",
                "Bearer $token"
            )
            .build()

        return client
            .newCall(request)
            .execute()
            .body
            ?.string()
    }

    fun post(
        url: String,
        token: String,
        json: String
    ): String? {

        val body = json
            .toRequestBody(
                "application/json"
                    .toMediaType()
            )

        val request =
            Request.Builder()
                .url(url)
                .addHeader(
                    "Authorization",
                    "Bearer $token"
                )
                .post(body)
                .build()

        return client
            .newCall(request)
            .execute()
            .body
            ?.string()
    }
}