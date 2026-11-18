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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

private val log = LoggerFactory.getLogger("supportvisit")

fun main(args: Array<String>) {
    // Maintenance commands used by deploy/pm2/backup.sh and the server operator.
    when (args.firstOrNull()) {
        "backup" -> {
            val dataDir = File(System.getenv("DATA_DIR") ?: "/data")
            Store.backup(File(dataDir, "supportvisit.db"), File(args.getOrNull(1) ?: error("usage: backup <file>")))
            return
        }
        "grant-pro", "revoke-pro", "grants", "crashes", "companies" -> {
            val team = TeamStore(File(File(System.getenv("DATA_DIR") ?: "/data"), "supportvisit.db"))
            when (args[0]) {
                "grant-pro" -> team.grantPro(args.getOrNull(1)?.lowercase() ?: error("usage: grant-pro <companyId> [note]"), args.drop(2).joinToString(" "))
                "revoke-pro" -> team.revokePro(args.getOrNull(1)?.lowercase() ?: error("usage: revoke-pro <companyId>"))
                "grants" -> team.grants().forEach { (tenant, note) -> println("$tenant  $note") }
                "crashes" -> println(team.recentCrashes().toString(2))
                "companies" -> team.companies().forEach { (id, name, members) -> println("$id  $members member(s)  $name") }
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
    if (config.smtp == null) log.warn("SMTP is not configured: nobody can sign in until SMTP_HOST and SMTP_FROM are set")
    // Only reachable through the reverse proxy unless HOST says otherwise (Docker sets 0.0.0.0).
    val host = System.getenv("HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
    embeddedServer(Netty, port = config.port, host = host) {
        module(config, config.smtp?.let(::SmtpMailer) ?: NoMailer)
    }.start(wait = true)
}

/** The whole API. [mailer] sends sign-in codes, invitations and reports (a fake in tests). */
fun Application.module(config: Config, mailer: Mailer) {
    val dbFile = File(config.dataDir, "supportvisit.db")
    val store = Store(dbFile)
    val files = FileStore(config.dataDir, config.maxUploadBytes)
    val signer = LinkSigner(config.linkSecret)
    val team = TeamStore(dbFile)
    val accounts = AccountStore(dbFile, config.linkSecret, reviewLogin = config.reviewLogin)
    if (config.reviewLogin != null) log.warn("App-store review sign-in is ON for {}", config.reviewLogin.email)
    // Per client address: sign-in emails and code checks (on top of the per-email limits).
    val startLimiter = WindowLimiter(max = 20, windowMs = 3_600_000)
    val verifyLimiter = WindowLimiter(max = 40, windowMs = 3_600_000)
    // Whole server: warns early, and refuses only far above normal use, so a flood can't
    // easily lock everyone out (per-email and per-address limits do the real work).
    val globalStartWarn = WindowLimiter(max = 500, windowMs = 3_600_000)
    val globalStartLimiter = WindowLimiter(max = 5000, windowMs = 3_600_000)

    // Carry out account deletions whose grace period has ended: at start-up, then every hour.
    launch {
        while (isActive) {
            try {
                AccountPurge.run(store, files, team, accounts)
            } catch (e: Exception) {
                log.error("Account purge failed", e)
            }
            delay(config.purgeIntervalMs)
        }
    }

    install(CallLogging) {
        // Paths only: never log tokens, codes or personal data.
        format { call -> "${call.request.local.method.value} ${call.request.local.uri.substringBefore('?')} -> ${call.response.status()?.value}" }
    }
    install(StatusPages) {
        exception<AuthException> { call, e ->
            call.respondJson(HttpStatusCode.Unauthorized, JSONObject().put("error", e.message ?: "unauthorized"))
        }
        exception<NoCompanyException> { call, e ->
            call.respondJson(HttpStatusCode.Conflict, JSONObject().put("error", "no_company").put("invites", JSONArray(e.invites)))
        }
        exception<ForbiddenException> { call, e ->
            call.respondJson(HttpStatusCode.Forbidden, JSONObject().put("error", e.message))
        }
        exception<RateLimitedException> { call, e ->
            call.response.header(HttpHeaders.RetryAfter, e.retryAfterSeconds.toString())
            call.respondJson(HttpStatusCode.TooManyRequests, JSONObject().put("error", "rate_limited").put("retryAfter", e.retryAfterSeconds))
        }
        exception<MailNotConfiguredException> { call, _ ->
            call.respondJson(HttpStatusCode.ServiceUnavailable, JSONObject().put("error", "email_not_configured"))
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

    fun ApplicationCall.bearer(): String {
        val header = request.headers[HttpHeaders.Authorization].orEmpty()
        if (!header.startsWith("Bearer ", ignoreCase = true)) throw AuthException("missing_token")
        return header.substring(7).trim()
    }

    /** The signed-in person (no company needed). */
    fun ApplicationCall.user(): UserAccount = accounts.authenticate(bearer())

    /** The signed-in person as a member of their company. */
    fun ApplicationCall.member(): Pair<Caller, Member> {
        val u = user()
        val m = team.resolve(u)
        return Caller(m.tenantId, u.userId, u.email, u.name) to m
    }

    fun ApplicationCall.caller(): Caller = member().first

    fun planOf(tenantId: String): String =
        if (team.hasProGrant(tenantId) || tenantId in config.proTenants) "PRO" else "FREE"

    /**
     * The client address. The server listens only on 127.0.0.1 behind Apache, which appends the
     * real address as the LAST X-Forwarded-For entry (earlier entries can be forged by the client).
     */
    fun ApplicationCall.clientKey(): String =
        request.headers["X-Forwarded-For"]?.substringAfterLast(',')?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.local.remoteAddress

    /**
     * Whether a company may send one more report email today: a daily limit per company (lower
     * for new companies without Pro, so a fresh sign-up can't use the address for spam) and a
     * daily cap for the whole server.
     */
    fun takeMailQuota(tenantId: String): Boolean {
        val day = LocalDate.now(ZoneOffset.UTC).toString()
        if (team.mailSentToday(day) >= config.mailGlobalDailyLimit) {
            log.warn("Server-wide daily email cap reached")
            return false
        }
        val isNew = System.currentTimeMillis() - team.companyCreatedAt(tenantId) < NEW_COMPANY_MS
        val limit = if (isNew && planOf(tenantId) != "PRO") minOf(config.mailDailyLimit, config.newCompanyMailDailyLimit) else config.mailDailyLimit
        return team.takeMailQuota(tenantId, day, limit)
    }

    /** "Acme IT (via Support Visit)": the company's name, but clearly sent through the service. */
    fun senderName(company: JSONObject): String {
        val name = company.optString("name").replace(Regex("[\\r\\n\"<>]"), " ").trim().take(80)
        return if (name.isEmpty()) config.appName else "$name (via ${config.appName})"
    }

    fun fileUrl(visitId: String, name: String) = "${config.publicBaseUrl}/api/v1/visits/$visitId/files/$name"

    suspend fun send(mail: Mail) = withContext(Dispatchers.IO) { mailer.send(mail) }

    routing {
        get("/health") { call.respondText("ok") }

        route("/api/v1") {

            // Sign-in: email → 6-digit code → session token.
            post("/auth/start") {
                startLimiter.check(call.clientKey())
                globalStartLimiter.check("all")
                runCatching { globalStartWarn.check("all") }.onFailure { log.warn("Unusually many sign-in emails this hour") }
                val body = JSONObject(call.receiveText())
                val email = AccountStore.normalizeEmail(body.optString("email"))
                // The review account signs in with its fixed code; nothing is emailed.
                if (accounts.isReviewAccount(email)) return@post call.respondJson(HttpStatusCode.OK, JSONObject().put("sent", true))
                val code = accounts.startLogin(email)
                val (subject, text) = Emails.loginCode(config.appName, code, body.optString("lang"))
                send(Mail(listOf(email), subject, text, fromName = config.appName))
                call.respondJson(HttpStatusCode.OK, JSONObject().put("sent", true))
            }
            post("/auth/verify") {
                verifyLimiter.check(call.clientKey())
                val body = JSONObject(call.receiveText())
                val (token, user) = accounts.verifyLogin(body.optString("email"), body.optString("code"),
                    existingUserId = team::legacyUserIdForEmail)
                log.info("Signed in")
                call.respondJson(HttpStatusCode.OK, JSONObject().put("token", token).put("userId", user.userId).put("email", user.email))
            }
            post("/auth/logout") {
                accounts.logout(call.bearer())
                call.respondJson(HttpStatusCode.OK, JSONObject().put("ok", true))
            }

            get("/me") {
                val (c, m) = call.member()
                call.respondJson(
                    HttpStatusCode.OK,
                    JSONObject().put("tenantId", c.tenantId).put("userId", c.userId).put("email", c.email)
                        .put("name", c.name).put("role", m.role.name)
                        .put("plan", planOf(c.tenantId)).put("memberCount", team.memberCount(c.tenantId))
                )
            }
            put("/me/name") {
                val u = call.user()
                val name = JSONObject(call.receiveText()).optString("name").trim()
                require(name.isNotEmpty()) { "Name is empty" }
                accounts.setName(u.userId, name)
                call.respondJson(HttpStatusCode.OK, JSONObject().put("name", name.take(80)))
            }

            // Joining is the invited person's choice (the 409 from /me lists their invitations).
            post("/me/invites/{tenantId}/accept") {
                val u = call.user()
                val m = team.acceptInvite(u, call.parameters["tenantId"].orEmpty())
                log.info("Invitation accepted")
                call.respondJson(HttpStatusCode.OK, JSONObject().put("tenantId", m.tenantId).put("role", m.role.name))
            }

            // A signed-in person who isn't in a company yet starts one and becomes its admin.
            post("/companies") {
                val u = call.user()
                val name = JSONObject(call.receiveText()).optString("name").trim()
                require(name.isNotEmpty()) { "Company name is empty" }
                val m = team.createCompany(u, name)
                log.info("Company created")
                call.respondJson(HttpStatusCode.OK, JSONObject().put("tenantId", m.tenantId).put("role", m.role.name))
            }

            // Team: managers and admins see everyone; only admins change roles and invite.
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
                if (!team.removeMember(c.tenantId, target)) return@delete call.notFound()
                // Only ever someone who was in this admin's company.
                accounts.revokeAll(target)
                call.respondJson(HttpStatusCode.OK, JSONObject().put("removed", true))
            }

            get("/members/invites") {
                val (c, m) = call.member()
                if (!m.role.canManageCompany) return@get call.forbidden("admins_only")
                call.respondJson(HttpStatusCode.OK, JSONObject().put("items", JSONArray(team.invites(c.tenantId))))
            }
            post("/members/invites") {
                val (c, m) = call.member()
                if (!m.role.canManageCompany) return@post call.forbidden("admins_only")
                val body = JSONObject(call.receiveText())
                val email = AccountStore.normalizeEmail(body.optString("email"))
                val role = runCatching { Role.valueOf(body.optString("role", "TECHNICIAN")) }.getOrDefault(Role.TECHNICIAN)
                if (team.isActiveMemberAnywhere(email)) {
                    return@post call.respondJson(HttpStatusCode.Conflict, JSONObject().put("error", "already_in_a_company"))
                }
                team.createInvite(c.tenantId, email, role, c.userId)
                // The invitation stands even if the email can't be sent; the admin can tell them.
                val emailed = runCatching {
                    val company = team.company(c.tenantId).optString("name").ifBlank { config.appName }
                    val (subject, text) = Emails.invitation(config.appName, company, c.name.ifBlank { c.email }, email, body.optString("lang"))
                    send(Mail(listOf(email), subject, text, fromName = company, replyTo = c.email))
                }.onFailure { log.warn("Invitation email failed: {}", it.javaClass.simpleName) }.isSuccess
                call.respondJson(HttpStatusCode.OK, JSONObject().put("email", email).put("role", role.name).put("emailed", emailed))
            }
            delete("/members/invites/{email}") {
                val (c, m) = call.member()
                if (!m.role.canManageCompany) return@delete call.forbidden("admins_only")
                team.deleteInvite(c.tenantId, call.parameters["email"].orEmpty())
                call.respondJson(HttpStatusCode.OK, JSONObject().put("deleted", true))
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
                team.addEvents(c.tenantId, LocalDate.now(ZoneOffset.UTC).toString(), names.take(500))
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
                        // Fail closed: only the creator (immutable user id) may overwrite a visit.
                        // Rows without a recorded creator are read-only.
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
                require(FileStore.isSafeId(id)) { "Invalid id" }
                // Same rule as the visit itself: only its creator may replace its files.
                val owner = store.ownershipOf(Store.Kind.VISITS, c.tenantId, id)
                if (owner != null && owner.createdBy != c.userId) return@put call.forbidden("not_your_visit")
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

            /*
             * Emails a visit's signed report (already uploaded as report.pdf) from the server's
             * address, shown with the company's name; replies go to the company or the technician.
             */
            post("/visits/{id}/email") {
                val (c, m) = call.member()
                val id = call.parameters["id"].orEmpty()
                require(FileStore.isSafeId(id)) { "Invalid id" }
                val owner = store.ownershipOf(Store.Kind.VISITS, c.tenantId, id) ?: return@post call.notFound()
                if (owner.createdBy != c.userId && !m.role.canSeeEverything) return@post call.forbidden("not_your_visit")
                val body = JSONObject(call.receiveText())
                val to = body.optJSONArray("to")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    .map { AccountStore.normalizeEmail(it) }.distinct()
                require(to.isNotEmpty() && to.size <= MAX_REPORT_RECIPIENTS) { "1 to $MAX_REPORT_RECIPIENTS recipients" }
                val subject = body.optString("subject").replace('\n', ' ').trim().take(200)
                val text = body.optString("body").take(20_000)
                require(subject.isNotEmpty()) { "Subject is empty" }
                val pdf = files.file(c.tenantId, id, "report.pdf")
                if (!pdf.exists() || !isPdf(pdf.inputStream().use { it.readNBytes(5) })) {
                    return@post call.respondJson(HttpStatusCode.BadRequest, JSONObject().put("error", "report_missing"))
                }
                if (!takeMailQuota(c.tenantId)) {
                    return@post call.respondJson(HttpStatusCode.TooManyRequests, JSONObject().put("error", "daily_email_limit"))
                }
                val company = team.company(c.tenantId)
                val fromName = senderName(company)
                val replyTo = company.optString("email").takeIf { runCatching { AccountStore.normalizeEmail(it) }.isSuccess } ?: c.email
                val attachName = body.optString("fileName").takeIf { FileStore.isSafeName(it) && it.lowercase().endsWith(".pdf") } ?: "report.pdf"
                val attach = pdf.length() <= MAX_ATTACHMENT_BYTES
                val finalText = if (attach) text else {
                    // Too big to attach: a 30-day signed link instead.
                    val expires = System.currentTimeMillis() / 1000 + 30L * 24 * 60 * 60
                    val path = "${c.tenantId}/$id/report.pdf"
                    text + "\n\n" + "${config.publicBaseUrl}/files/$path?exp=$expires&sig=${signer.sign(path, expires)}"
                }
                send(
                    Mail(
                        to = to, subject = subject, text = finalText, fromName = fromName, replyTo = replyTo,
                        attachments = if (attach) listOf(MailAttachment(attachName, "application/pdf", pdf.readBytes())) else emptyList()
                    )
                )
                log.info("Report emailed to {} recipient(s)", to.size)
                call.respondJson(HttpStatusCode.OK, JSONObject().put("sent", true).put("attached", attach))
            }

            // Monthly report for a client: the app sends the PDF and CSV it made (base64, max 10 MB).
            post("/reports/email") {
                val (c, _) = call.member()
                val body = JSONObject(call.receiveText())
                val to = body.optJSONArray("to")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    .map { AccountStore.normalizeEmail(it) }.distinct()
                require(to.isNotEmpty() && to.size <= MAX_REPORT_RECIPIENTS) { "1 to $MAX_REPORT_RECIPIENTS recipients" }
                val subject = body.optString("subject").replace('\n', ' ').trim().take(200)
                require(subject.isNotEmpty()) { "Subject is empty" }
                val list = body.optJSONArray("attachments") ?: JSONArray()
                require(list.length() in 1..3) { "1 to 3 attachments" }
                var total = 0L
                val attachments = (0 until list.length()).map { i ->
                    val a = list.getJSONObject(i)
                    val type = a.optString("contentType")
                    require(type in setOf("application/pdf", "text/csv")) { "Only PDF and CSV" }
                    // The extension always matches the checked type, whatever name was sent.
                    val base = a.optString("name").substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').take(80)
                    val name = base.ifEmpty { "report" } + if (type == "application/pdf") ".pdf" else ".csv"
                    val bytes = java.util.Base64.getDecoder().decode(a.optString("data"))
                    total += bytes.size
                    require(total <= MAX_ATTACHMENT_BYTES) { "Attachments too large" }
                    // Must really be what it says: a PDF, or plain text for the CSV.
                    if (type == "application/pdf") require(isPdf(bytes)) { "Not a PDF" }
                    else require(isPlainText(bytes)) { "Not a CSV file" }
                    MailAttachment(name, type, bytes)
                }
                if (!takeMailQuota(c.tenantId)) {
                    return@post call.respondJson(HttpStatusCode.TooManyRequests, JSONObject().put("error", "daily_email_limit"))
                }
                val company = team.company(c.tenantId)
                val replyTo = company.optString("email").takeIf { runCatching { AccountStore.normalizeEmail(it) }.isSuccess } ?: c.email
                send(Mail(to, subject, body.optString("body").take(20_000), senderName(company), replyTo, attachments))
                call.respondJson(HttpStatusCode.OK, JSONObject().put("sent", true))
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

private const val MAX_REPORT_RECIPIENTS = 10
private const val MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024
/** Companies younger than this (and without Pro) get the lower email limit. */
private const val NEW_COMPANY_MS = 7L * 24 * 60 * 60 * 1000

private fun isPdf(bytes: ByteArray) = bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-"

/** Valid UTF-8 (with or without BOM) and no control characters other than tab and line breaks. */
private fun isPlainText(bytes: ByteArray): Boolean {
    val decoder = Charsets.UTF_8.newDecoder()
    val text = runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull() ?: return false
    return text.none { it < ' ' && it != '\t' && it != '\n' && it != '\r' }
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
