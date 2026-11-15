package com.company.supportvisit.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("supportvisit")

fun main(args: Array<String>) {
    // Maintenance commands used by deploy/pm2/backup.sh.
    when (args.firstOrNull()) {
        "backup" -> {
            val dataDir = File(System.getenv("DATA_DIR") ?: "/data")
            Store.backup(File(dataDir, "supportvisit.db"), File(args.getOrNull(1) ?: error("usage: backup <file>")))
            return
        }
        "check" -> {
            val ok = Store.check(File(args.getOrNull(1) ?: error("usage: check <file>")))
            println(if (ok) "ok" else "FAILED")
            if (!ok) kotlin.system.exitProcess(1)
            return
        }
    }

    val config = Config.fromEnv()
    // Only reachable through the reverse proxy unless HOST says otherwise (Docker sets 0.0.0.0).
    val host = System.getenv("HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
    embeddedServer(Netty, port = config.port, host = host) {
        module(config, EntraTokenVerifier(config)::verify)
    }.start(wait = true)
}

/** The whole API. [verify] turns a bearer token into a [Caller] (swapped out in tests). */
fun Application.module(config: Config, verify: (String) -> Caller) {
    val store = Store(File(config.dataDir, "supportvisit.db"))
    val files = FileStore(config.dataDir, config.maxUploadBytes)
    val signer = LinkSigner(config.linkSecret)

    install(CallLogging) {
        // Paths only: never log tokens or personal data.
        format { call -> "${call.request.local.method.value} ${call.request.local.uri.substringBefore('?')} -> ${call.response.status()?.value}" }
    }
    install(StatusPages) {
        exception<AuthException> { call, e ->
            call.respondJson(HttpStatusCode.Unauthorized, JSONObject().put("error", "unauthorized").put("message", e.message))
        }
        exception<IllegalArgumentException> { call, e ->
            call.respondJson(HttpStatusCode.BadRequest, JSONObject().put("error", "bad_request").put("message", e.message))
        }
        exception<JSONException> { call, _ ->
            call.respondJson(HttpStatusCode.BadRequest, JSONObject().put("error", "bad_json"))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error", e)
            call.respondJson(HttpStatusCode.InternalServerError, JSONObject().put("error", "server_error"))
        }
    }

    fun ApplicationCall.caller(): Caller {
        val header = request.headers[HttpHeaders.Authorization].orEmpty()
        if (!header.startsWith("Bearer ", ignoreCase = true)) throw AuthException("Missing bearer token")
        return verify(header.substring(7).trim())
    }

    fun fileUrl(visitId: String, name: String) = "${config.publicBaseUrl}/api/v1/visits/$visitId/files/$name"

    routing {
        get("/health") { call.respondText("ok") }

        route("/api/v1") {
            get("/me") {
                val c = call.caller()
                call.respondJson(
                    HttpStatusCode.OK,
                    JSONObject().put("tenantId", c.tenantId).put("email", c.email).put("name", c.name)
                )
            }

            for (kind in Store.Kind.entries) {
                val path = "/${kind.table}"

                // Everything changed after ?since= (server time), oldest first, in pages.
                get(path) {
                    val c = call.caller()
                    val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                    val records = store.changesSince(kind, c.tenantId, since)
                    val items = JSONArray()
                    records.forEach { items.put(JSONObject(it.data.toString()).put("serverUpdatedAt", it.serverUpdatedAt)) }
                    call.respondJson(
                        HttpStatusCode.OK,
                        JSONObject()
                            .put("items", items)
                            .put("hasMore", records.size == 500)
                            .put("cursor", records.lastOrNull()?.serverUpdatedAt ?: since)
                    )
                }

                put("$path/{id}") {
                    val c = call.caller()
                    val id = call.parameters["id"].orEmpty()
                    require(FileStore.isSafeId(id)) { "Invalid id" }
                    val body = JSONObject(call.receiveText())
                    require(body.optString("id") == id) { "Body id does not match URL" }
                    body.remove("serverUpdatedAt")
                    val stamp = store.upsert(kind, c.tenantId, id, body, c.email.ifBlank { c.userId })
                    call.respondJson(HttpStatusCode.OK, JSONObject().put("id", id).put("serverUpdatedAt", stamp))
                }
            }

            put("/visits/{id}/files/{name}") {
                val c = call.caller()
                val id = call.parameters["id"].orEmpty()
                val name = call.parameters["name"].orEmpty()
                files.save(c.tenantId, id, name, call.receiveChannel().toInputStream())
                call.respondJson(HttpStatusCode.OK, JSONObject().put("url", fileUrl(id, name)))
            }

            get("/visits/{id}/files/{name}") {
                val c = call.caller()
                val file = files.file(c.tenantId, call.parameters["id"].orEmpty(), call.parameters["name"].orEmpty())
                call.respondStoredFile(file)
            }

            // A temporary public link (default 15 minutes) for opening a file outside the app.
            post("/visits/{id}/files/{name}/link") {
                val c = call.caller()
                val id = call.parameters["id"].orEmpty()
                val name = call.parameters["name"].orEmpty()
                val file = files.file(c.tenantId, id, name)
                if (!file.exists()) {
                    call.respondJson(HttpStatusCode.NotFound, JSONObject().put("error", "not_found"))
                    return@post
                }
                val expires = System.currentTimeMillis() / 1000 + 15 * 60
                val path = "${c.tenantId}/$id/$name"
                val url = "${config.publicBaseUrl}/files/$path?exp=$expires&sig=${signer.sign(path, expires)}"
                call.respondJson(HttpStatusCode.OK, JSONObject().put("url", url).put("expiresAt", expires))
            }
        }

        get("/files/{tenant}/{id}/{name}") {
            val tenant = call.parameters["tenant"].orEmpty()
            val id = call.parameters["id"].orEmpty()
            val name = call.parameters["name"].orEmpty()
            val exp = call.request.queryParameters["exp"]?.toLongOrNull() ?: 0L
            val sig = call.request.queryParameters["sig"].orEmpty()
            if (!signer.verify("$tenant/$id/$name", exp, sig)) {
                call.respondText("Link expired or invalid", status = HttpStatusCode.Forbidden)
                return@get
            }
            call.respondStoredFile(files.file(tenant, id, name))
        }
    }
}

private suspend fun ApplicationCall.respondStoredFile(file: File) {
    if (!file.exists()) {
        respondJson(HttpStatusCode.NotFound, JSONObject().put("error", "not_found"))
        return
    }
    response.header(HttpHeaders.ContentDisposition, "inline; filename=\"${file.name}\"")
    response.header(HttpHeaders.CacheControl, "private, no-store")
    response.header(HttpHeaders.ContentType, FileStore.contentTypeFor(file.name))
    respondFile(file)
}

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: JSONObject) {
    respondText(body.toString(), ContentType.Application.Json, status)
}
