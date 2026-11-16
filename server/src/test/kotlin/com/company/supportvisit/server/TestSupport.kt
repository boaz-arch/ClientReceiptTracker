package com.company.supportvisit.server

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.json.JSONObject
import java.io.File
import kotlin.test.assertEquals

/** Collects outgoing email instead of sending it. */
class FakeMailer : Mailer {
    val sent = mutableListOf<Mail>()

    override fun send(mail: Mail) {
        sent += mail
    }

    fun lastCodeFor(email: String): String =
        Regex("\\b(\\d{6})\\b").find(sent.last { email in it.to }.text)!!.groupValues[1]
}

fun testConfig(dataDir: File, mailDailyLimit: Int = 200) = Config(
    port = 0,
    dataDir = dataDir,
    publicBaseUrl = "https://example.test/sv",
    linkSecret = "x".repeat(40),
    purgeIntervalMs = 3_600_000,
    mailDailyLimit = mailDailyLimit
)

/** Signs people in through the real email-code flow, once per email per test. */
class People(private val client: HttpClient, private val mailer: FakeMailer) {
    private val tokens = HashMap<String, String>()

    suspend fun token(email: String): String = tokens[email] ?: run {
        val start = client.post("/api/v1/auth/start") { setBody(JSONObject().put("email", email).toString()) }
        assertEquals(HttpStatusCode.OK, start.status, start.bodyAsText())
        val verify = client.post("/api/v1/auth/verify") {
            setBody(JSONObject().put("email", email).put("code", mailer.lastCodeFor(email)).toString())
        }
        assertEquals(HttpStatusCode.OK, verify.status, verify.bodyAsText())
        JSONObject(verify.bodyAsText()).getString("token").also { tokens[email] = it }
    }

    suspend fun me(email: String): JSONObject = JSONObject(client.get("/api/v1/me") { bearerAuth(token(email)) }.bodyAsText())

    /** [admin] starts a company; returns its id. */
    suspend fun company(admin: String, name: String = "Acme IT"): String {
        val r = client.post("/api/v1/companies") { bearerAuth(token(admin)); setBody(JSONObject().put("name", name).toString()) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return JSONObject(r.bodyAsText()).getString("tenantId")
    }

    /** [admin] invites [email], who then signs in and joins. Returns the new member's user id. */
    suspend fun join(admin: String, email: String, role: String = "TECHNICIAN"): String {
        val r = client.post("/api/v1/members/invites") {
            bearerAuth(token(admin)); setBody(JSONObject().put("email", email).put("role", role).toString())
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return me(email).getString("userId")
    }
}
