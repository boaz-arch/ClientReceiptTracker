package com.company.supportvisit.remote

import com.company.supportvisit.graph.GraphException
import com.company.supportvisit.graph.GraphHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

/** One page of changes from the server. */
data class ChangesPage(val items: List<JSONObject>, val cursor: Long, val hasMore: Boolean)

/** Info about the signed-in caller, as the server sees it. */
data class ServerIdentity(val tenantId: String, val email: String)

/**
 * Client for the Support Visit server (see /server). [token] is an access token for the
 * server's own API scope, not a Graph token. Errors use GraphException (HTTP status + body).
 */
class ServerApi(baseUrl: String, private val token: String) {

    private val base = baseUrl.trimEnd('/')
    private val jsonType = "application/json".toMediaType()

    fun me(): ServerIdentity {
        val json = getJson("$base/api/v1/me")
        return ServerIdentity(json.optString("tenantId"), json.optString("email"))
    }

    /** Pending account deletion for the signed-in user, if any. */
    fun deletionStatus(): com.company.supportvisit.account.DeletionStatus {
        val json = getJson("$base/api/v1/me/deletion")
        return com.company.supportvisit.account.DeletionStatus(
            pending = json.optBoolean("pending"),
            purgeAt = if (json.isNull("purgeAt")) null else json.optLong("purgeAt")
        )
    }

    /** Schedules the signed-in user's account for deletion; returns when it will be purged. */
    fun requestDeletion(keepShared: Boolean): Long {
        val request = Request.Builder()
            .url("$base/api/v1/me/deletion")
            .post(JSONObject().put("keepShared", keepShared).toString().toRequestBody(jsonType))
            .build()
        return JSONObject(GraphHttp.execute(request, token)).optLong("purgeAt")
    }

    fun cancelDeletion() {
        GraphHttp.execute(Request.Builder().url("$base/api/v1/me/deletion").delete().build(), token)
    }

    fun visitChanges(since: Long) = changes("visits", since)

    fun clientChanges(since: Long) = changes("clients", since)

    fun putVisit(json: JSONObject) = put("visits", json)

    fun putClient(json: JSONObject) = put("clients", json)

    /** Uploads a file for a visit (overwrites) and returns its server URL. */
    fun uploadVisitFile(visitId: String, name: String, file: File, contentType: String): String {
        val request = Request.Builder()
            .url(fileUrl(visitId, name))
            .put(file.asRequestBody(contentType.toMediaType()))
            .build()
        return JSONObject(GraphHttp.execute(request, token)).getString("url")
    }

    /** Turns a server file URL into a temporary link that opens without signing in. */
    fun temporaryLink(fileUrl: String): String {
        val request = Request.Builder().url("$fileUrl/link").post(ByteArray(0).toRequestBody(null)).build()
        return JSONObject(GraphHttp.execute(request, token)).getString("url")
    }

    fun isServerUrl(url: String?): Boolean = url != null && url.startsWith("$base/")

    private fun fileUrl(visitId: String, name: String) =
        "$base/api/v1/visits".toHttpUrl().newBuilder().addPathSegment(visitId).addPathSegment("files")
            .addPathSegment(name).build()

    private fun changes(kind: String, since: Long): ChangesPage {
        val json = getJson("$base/api/v1/$kind?since=$since")
        val items = json.optJSONArray("items")
        return ChangesPage(
            items = (0 until (items?.length() ?: 0)).mapNotNull { items!!.optJSONObject(it) },
            cursor = json.optLong("cursor", since),
            hasMore = json.optBoolean("hasMore", false)
        )
    }

    private fun put(kind: String, json: JSONObject): Long {
        val id = json.getString("id")
        val url = "$base/api/v1/$kind".toHttpUrl().newBuilder().addPathSegment(id).build()
        val request = Request.Builder().url(url).put(json.toString().toRequestBody(jsonType)).build()
        return JSONObject(GraphHttp.execute(request, token)).optLong("serverUpdatedAt")
    }

    private fun getJson(url: String): JSONObject = GraphHttp.getJson(url, token)

    companion object {
        /** Server unreachable or temporarily failing: worth retrying later. */
        fun isRetryable(e: Throwable) = e is java.io.IOException && (e !is GraphException || e.isRetryable)
    }
}
