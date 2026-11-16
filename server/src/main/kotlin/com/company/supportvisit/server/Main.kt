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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        "grant-pro", "revoke-pro", "grants", "crashes", "invite-admin" -> {
            val team = TeamStore(File(File(System.getenv("DATA_DIR") ?: "/data"), "supportvisit.db"))
            when (args[0]) {
                "grant-pro" -> team.grantPro(args.getOrNull(1)?.lowercase() ?: error("usage: grant-pro <tenantId> [note]"), args.drop(2).joinToString(" "))
                "revoke-pro" -> team.revokePro(args.getOrNull(1)?.lowercase() ?: error("usage: revoke-pro <tenantId>"))
                "grants" -> team.grants().forEach { (tenant, note) -> println("$tenant  $note") }
                "crashes" -> println(team.recentCrashes().toString(2))
                "invite-admin" -> team.inviteAdmin(args.getOrNull(1) ?: error("usage: invite-admin <email>"))
            }
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
    val team = TeamStore(File(config.dataDir, "supportvisit.db"), config.adminEmails)

    // Purge accounts whose grace period has ended: at start-up, then every hour.
    launch {
        while (isActive) {
            try {
                AccountPurge.run(store, files)
            } catch (e: Exception) {
                log.error("Account purge failed", e)
            }
            delay(config.purgeIntervalMs)
        }
    }

    install(CallLogging) {
        // Paths only: never log tokens or personal data.
        format { call -> "${call.request.local.method.value} ${call.request.local.uri.substringBefore('?')} -> ${call.response.status()?.value}" }
    }
    install(StatusPages) {
        exception<AuthException> { call, e ->
            call.respondJson(HttpStatusCode.Unauthorized, JSONObject().put("error", "unauthorized").put("message", e.message))
        }
        exception<ForbiddenException> { call, e ->
            call.respondJson(HttpStatusCode.Forbidden, JSONObject().put("error", e.message))
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

    /** The caller as a company member (registered on first use; the first one becomes admin). */
    fun ApplicationCall.member(): Pair<Caller, Member> {
        val c = caller()
        return c to team.touch(c)
    }

    fun planOf(tenantId: String): String =
        if (team.hasProGrant(tenantId) || tenantId in config.proTenants) "PRO" else "FREE"

    fun fileUrl(visitId: String, name: String) = "${config.publicBaseUrl}/api/v1/visits/$visitId/files/$name"

    routing {
        get("/health") { call.respondText("ok") }

        route("/api/v1") {
            get("/me") {
                val (c, m) = call.member()
                call.respondJson(
                    HttpStatusCode.OK,
                    JSONObject().put("tenantId", c.tenantId).put("userId", c.userId).put("email", c.email)
                        .put("name", c.name).put("role", m.role.name)
                        .put("plan", planOf(c.tenantId)).put("memberCount", team.memberCount(c.tenantId))
                )
            }

            // Team: managers and admins see everyone; only admins change roles.
            get("/members") {
                val (c, m) = call.member()
                val list = if (m.role.canSeeEverything) team.members(c.tenantId) else listOf(m)
                call.respondJson(HttpStatusCode.OK, JSONObject().put("items", JSONArray(list.map { it.toJson() })))
            }
            put("/members/{userId}/role") {
                val (c, m) = call.member()
                if (!m.role.canManageCompany) return@put call.forbidden("admins_only")
                val role = runCatching { Role.valueOf(JSONObject(call.receiveText()).getString("role")) }
                    .getOrElse { throw IllegalArgumentException("Unknown role") }
                if (!team.setRole(c.tenantId, call.parameters["userId"].orEmpty(), role)) return@put call.notFound()
                call.respondJson(HttpStatusCode.OK, JSONObject().put("role", role.name))
            }
            get("/members/removed") {
                val (c, m) = call.member()
                if (!m.role.canManageCompany) return@get call.forbidden("admins_only")
                call.respondJson(HttpStatusCode.OK, JSONObject().put("items", JSONArray(team.removedMembers(c.tenantId).map { it.toJson() })))
            }
            post("/members/{userId}/restore") {
                val (c, m) = call.member()
                if (!m.role.canManageCompany) return@post call.forbidden("admins_only")
                if (!team.restoreMember(c.tenantId, call.parameters["userId"].orEmpty())) return@post call.notFound()
                call.respondJson(HttpStatusCode.OK, JSONObject().put("restored", true))
            }
            delete("/members/{userId}") {
                val (c, m) = call.member()
                if (!m.role.canManageCompany) return@delete call.forbidden("admins_only")
                val target = call.parameters["userId"].orEmpty()
                require(target != c.userId) { "You can't remove yourself" }
                team.removeMember(c.tenantId, target)
                call.respondJson(HttpStatusCode.OK, JSONObject().put("removed", true))
            }

            // Company profile: branding and email template, shared with every member.
            get("/company") {
                val (c, _) = call.member()
                call.respondJson(HttpStatusCode.OK, team.company(c.tenantId))
            }
            put("/company") {
                val (c, m) = call.member()
                if (!m.role.canManageCompany) return@put call.forbidden("admins_only")
                call.respondJson(HttpStatusCode.OK, team.updateCompany(c.tenantId, JSONObject(call.receiveText())))
            }
            put("/company/logo") {
                val (c, m) = call.member()
                if (!m.role.canManageCompany) return@put call.forbidden("admins_only")
                files.saveCompanyLogo(c.tenantId, call.receiveChannel().toInputStream())
                team.updateCompany(c.tenantId, JSONObject().put("hasLogo", true))
                call.respondJson(HttpStatusCode.OK, JSONObject().put("hasLogo", true))
            }
            get("/company/logo") {
                val (c, _) = call.member()
                call.respondStoredFile(files.companyLogo(c.tenantId))
            }

            // Crash reports and anonymous usage counters (the app has an opt-out).
            post("/crashes") {
                val (c, _) = call.member()
                team.addCrash(c.tenantId, JSONObject(call.receiveText()))
                call.respondJson(HttpStatusCode.OK, JSONObject().put("ok", true))
            }
            post("/events") {
                val (c, _) = call.member()
                val body = JSONObject(call.receiveText())
                val names = body.optJSONArray("events")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                team.addEvents(c.tenantId, java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(), names.take(500))
                call.respondJson(HttpStatusCode.OK, JSONObject().put("ok", true))
            }

            // Account deletion: scheduled now, purged after the grace period, cancellable until then.
            route("/me/deletion") {
                get {
                    val c = call.caller()
                    val r = store.deletionRequest(c.tenantId, c.userId)
                    call.respondJson(
                        HttpStatusCode.OK,
                        JSONObject().put("pending", r != null)
                            .put("purgeAt", r?.purgeAt ?: JSONObject.NULL)
                            .put("keepShared", r?.keepShared ?: false)
                    )
                }
                post {
                    val c = call.caller()
                    val body = call.receiveText().let { if (it.isBlank()) JSONObject() else JSONObject(it) }
                    val now = System.currentTimeMillis()
                    val request = Store.DeletionRequest(
                        tenantId = c.tenantId,
                        userId = c.userId,
                        email = c.email,
                        keepShared = body.optBoolean("keepShared", false),
                        requestedAt = now,
                        purgeAt = now + config.deletionGraceMs
                    )
                    store.requestDeletion(request)
                    log.info("Account deletion scheduled (keepShared={})", request.keepShared)
                    call.respondJson(HttpStatusCode.OK, JSONObject().put("pending", true).put("purgeAt", request.purgeAt))
                }
                delete {
                    val c = call.caller()
                    val cancelled = store.cancelDeletion(c.tenantId, c.userId)
                    call.respondJson(HttpStatusCode.OK, JSONObject().put("cancelled", cancelled))
                }
            }

            for (kind in Store.Kind.entries) {
                val path = "/${kind.table}"

                // Everything changed after ?since= (server time), oldest first, in pages.
                get(path) {
                    val (c, m) = call.member()
                    val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                    val records = store.changesSince(kind, c.tenantId, since, onlyFor = if (m.role.canSeeEverything) null else c.userId)
                    val items = JSONArray()
                    records.forEach { r ->
                        val item = JSONObject(r.data.toString()).put("serverUpdatedAt", r.serverUpdatedAt)
                        item.remove("deleted")
                        if (r.isServerTombstone) item.put("deleted", true)
                        items.put(item)
                    }
                    call.respondJson(
                        HttpStatusCode.OK,
                        JSONObject()
                            .put("items", items)
                            .put("hasMore", records.size == 500)
                            .put("cursor", records.lastOrNull()?.serverUpdatedAt ?: since)
                    )
                }

                put("$path/{id}") {
                    val (c, m) = call.member()
                    val id = call.parameters["id"].orEmpty()
                    require(FileStore.isSafeId(id)) { "Invalid id" }
                    val body = JSONObject(call.receiveText())
                    require(body.optString("id") == id) { "Body id does not match URL" }
                    body.remove("serverUpdatedAt")
                    body.remove("deleted")
                    if (kind == Store.Kind.VISITS) {
                        // Fail closed: only the creator (immutable Microsoft user id) may overwrite a
                        // visit. Rows without a recorded creator are read-only.
                        val owner = store.ownershipOf(kind, c.tenantId, id)
                        val allowed = owner == null || owner.createdBy == c.userId
                        if (!allowed) {
                            call.respondJson(HttpStatusCode.Forbidden, JSONObject().put("error", "not_your_visit"))
                            return@put
                        }
                    }
                    if (kind == Store.Kind.TASKS) {
                        // Tasks: the creator, the assignee, or a manager/admin may change them.
                        val owner = store.ownershipOf(kind, c.tenantId, id)
                        val allowed = owner == null || m.role.canSeeEverything || owner.createdBy == c.userId ||
                            store.taskAssignee(c.tenantId, id) == c.userId
                        if (!allowed) return@put call.forbidden("not_your_task")
                    }
                    val stamp = store.upsert(kind, c.tenantId, id, body, c.email.ifBlank { c.userId }, createdBy = c.userId)
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

        // App updates for installs outside Google Play: latest.json + the signed APK (public).
        get("/updates/{name}") {
            val name = call.parameters["name"].orEmpty()
            require(FileStore.isSafeName(name)) { "Invalid name" }
            val file = File(File(config.dataDir, "updates"), name)
            if (!file.exists()) return@get call.notFound()
            if (name.endsWith(".apk")) {
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$name\"")
                call.response.header(HttpHeaders.ContentType, "application/vnd.android.package-archive")
                call.respondFile(file)
            } else {
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.respondText(file.readText(), ContentType.Application.Json)
            }
        }

        // Privacy policy and terms, linked from the app (plain text wrapped in HTML).
        get("/legal/{doc}") {
            val doc = call.parameters["doc"].orEmpty()
            require(doc in setOf("privacy", "terms")) { "Unknown document" }
            val file = File(File(config.dataDir, "legal"), "$doc.md")
            if (!file.exists()) return@get call.notFound()
            val escaped = file.readText().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            call.respondText(
                "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
                    "<title>$doc</title></head><body><pre style=\"white-space:pre-wrap;font-family:sans-serif;padding:12px\">" +
                    escaped + "</pre></body></html>",
                ContentType.Text.Html
            )
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

private suspend fun ApplicationCall.forbidden(code: String) =
    respondJson(HttpStatusCode.Forbidden, JSONObject().put("error", code))

private suspend fun ApplicationCall.notFound() =
    respondJson(HttpStatusCode.NotFound, JSONObject().put("error", "not_found"))

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: JSONObject) {
    respondText(body.toString(), ContentType.Application.Json, status)
}
