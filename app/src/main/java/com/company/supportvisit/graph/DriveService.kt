package com.company.supportvisit.graph

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Uploads to the signed-in user's OneDrive. Only used while no Support Visit server is
 * configured; normally files go to the server.
 */
object DriveService {

    private const val DRIVE = "me/drive"

    /** Graph's limit for a single PUT upload. Bigger files use an upload session. */
    private const val SIMPLE_UPLOAD_LIMIT = 4L * 1024 * 1024

    /** Upload session chunks must be multiples of 320 KiB. */
    private const val CHUNK_SIZE = 320 * 1024 * 10

    /**
     * Uploads (or overwrites) [file] at [path] (folders separated by "/") and returns its web URL.
     * Paths contain the visit id, so retrying an upload is idempotent.
     */
    fun upload(token: String, path: String, file: File, contentType: String): String =
        if (file.length() <= SIMPLE_UPLOAD_LIMIT) {
            simpleUpload(token, path, file, contentType)
        } else {
            sessionUpload(token, path, file)
        }

    private fun itemUrl(path: String, action: String) =
        GraphHttp.BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments(DRIVE)
            .addPathSegment("root:")
            .apply {
                val segments = path.trim('/').split('/')
                segments.dropLast(1).forEach { addPathSegment(it) }
                addPathSegment(segments.last() + ":")
            }
            .addPathSegment(action)
            .build()

    private fun simpleUpload(token: String, path: String, file: File, contentType: String): String {
        val request = Request.Builder()
            .url(itemUrl(path, "content"))
            .put(file.asRequestBody(contentType.toMediaType()))
            .build()
        return JSONObject(GraphHttp.execute(request, token)).optString("webUrl")
    }

    private fun sessionUpload(token: String, path: String, file: File): String {
        val session = GraphHttp.postJson(
            itemUrl(path, "createUploadSession").toString(),
            token,
            JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "replace"))
        )
        val uploadUrl = session.getString("uploadUrl")
        val size = file.length()
        var offset = 0L
        var lastResponse = ""

        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(CHUNK_SIZE)
            while (offset < size) {
                val length = minOf(CHUNK_SIZE.toLong(), size - offset).toInt()
                raf.seek(offset)
                raf.readFully(buffer, 0, length)
                // The upload URL is pre-authenticated: it must NOT carry the bearer token.
                val request = Request.Builder()
                    .url(uploadUrl)
                    .header("Content-Range", "bytes $offset-${offset + length - 1}/$size")
                    .put(buffer.copyOf(length).toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                lastResponse = GraphHttp.client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw GraphException(response.code, body.take(500))
                    body
                }
                offset += length
            }
        }
        return JSONObject(lastResponse).optString("webUrl")
    }
}
