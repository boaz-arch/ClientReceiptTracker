package com.company.supportvisit.graph

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

object OneDriveService {

    fun uploadFile(
        file: File
    ): String {

        val token =
            AuthManager.getToken()

        val body =
            file.readBytes().toRequestBody(
                "application/octet-stream"
                    .toMediaType()
            )

        val request =
            Request.Builder()
                .url(
                    "https://graph.microsoft.com/v1.0/me/drive/root:/SupportVisits/Signatures/${file.name}:/content"
                )
                .put(body)
                .addHeader(
                    "Authorization",
                    "Bearer $token"
                )
                .build()

        val response =
            GraphClient.client
                .newCall(request)
                .execute()

        val responseBody =
            response.body?.string()
                ?: ""

        if (!response.isSuccessful) {

            throw Exception(
                "Upload Failed"
            )
        }

        val json =
            JSONObject(responseBody)

        return json.optString(
            "webUrl"
        )
    }
}