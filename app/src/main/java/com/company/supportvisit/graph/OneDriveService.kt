package com.company.supportvisit.graph

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

object OneDriveService {

    private const val FOLDER = "SupportVisits/PDFReports"

    /**
     * Uploads (or overwrites) a file in the signed-in user's OneDrive and returns its web URL.
     * The file name contains the visit id, so retrying an upload is idempotent.
     * A simple upload supports files up to 4 MB, plenty for a visit report.
     */
    fun uploadFile(token: String, file: File): String {
        val url = GraphHttp.BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments("me/drive")
            .addPathSegment("root:")
            .addPathSegments(FOLDER)
            .addPathSegment(file.name + ":")
            .addPathSegment("content")
            .build()

        val request = Request.Builder()
            .url(url)
            .put(file.asRequestBody("application/pdf".toMediaType()))
            .build()

        return JSONObject(GraphHttp.execute(request, token)).optString("webUrl")
    }
}
