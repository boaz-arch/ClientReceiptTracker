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
data class ServerIdentity(
    val tenantId: String,
    val email: String,
    val userId: String = "",
    val role: String = "TECHNICIAN",
    val plan: String = "FREE",
    val memberCount: Int = 1
)

/**
 * Client for the Support Visit server (see /server). [token] is an access token for the
 * server's own API scope, not a Graph token. Errors use GraphException (HTTP status + body).
 */
class ServerApi(baseUrl: String, private val token: String) {

    private val base = baseUrl.trimEnd('/')
    private val jsonType = "application/json".toMediaType()

    fun me(): ServerIdentity {
        val json = getJson("$base/api/v1/me")
        return ServerIdentity(
            tenantId = json.optString("tenantId"),
            email = json.optString("email"),
            userId = json.optString("userId"),
            role = json.optString("role", "TECHNICIAN"),
            plan = json.optString("plan", "FREE"),
            memberCount = json.optInt("memberCount", 1)
        )
    }

    // region Team and company

    fun members(): List<JSONObject> = items(getJson("$base/api/v1/members"))

    fun removedMembers(): List<JSONObject> = items(getJson("$base/api/v1/members/removed"))

    fun setRole(userId: String, role: String) {
        send("PUT", "$base/api/v1/members/${seg(userId)}/role", JSONObject().put("role", role))
    }

    fun removeMember(userId: String) = send("DELETE", "$base/api/v1/members/${seg(userId)}", null)

    fun restoreMember(userId: String) = send("POST", "$base/api/v1/members/${seg(userId)}/restore", JSONObject())

    fun company(): JSONObject = getJson("$base/api/v1/company")

    fun putCompany(changes: JSONObject): JSONObject = JSONObject(send("PUT", "$base/api/v1/company", changes))

    fun uploadLogo(file: File) {
        val request = Request.Builder().url("$base/api/v1/company/logo")
            .put(file.asRequestBody("image/png".toMediaType())).build()
        GraphHttp.execute(request, token)
    }

    /** Downloads the company logo into [target]; false if there is none. */
    fun downloadLogo(target: File): Boolean {
        val request = Request.Builder().url("$base/api/v1/company/logo")
            .header("Authorization", "Bearer $token").get().build()
        return GraphHttp.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return@use false
            target.parentFile?.mkdirs()
            r.body?.byteStream()?.use { input -> target.outputStream().use { input.copyTo(it) } }
            true
        }
    }

    fun sendCrash(report: JSONObject) = send("POST", "$base/api/v1/crashes", report)

    fun sendEvents(names: List<String>) = send("POST", "$base/api/v1/events", JSONObject().put("events", org.json.JSONArray(names)))

    fun taskChanges(since: Long) = changes("tasks", since)

    fun putTask(json: JSONObject) = put("tasks", json)

    private fun items(json: JSONObject): List<JSONObject> {
        val a = json.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun seg(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun send(method: String, url: String, body: JSONObject?): String {
        val requestBody = body?.toString()?.toRequestBody(jsonType)
        val request = Request.Builder().url(url).method(method, requestBody ?: if (method == "DELETE") null else ByteArray(0).toRequestBody(null)).build()
        return GraphHttp.execute(request, token)
    }

    // endregion

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
