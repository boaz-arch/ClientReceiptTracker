package com.company.supportvisit.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthTest {

    private val dataDir: File = Files.createTempDirectory("svauth").toFile()
    private val config = testConfig(dataDir)
    private val mailer = FakeMailer()

    private fun body(email: String, code: String? = null) =
        JSONObject().put("email", email).apply { code?.let { put("code", it) } }.toString()

    @Test
    fun `a code sent by email signs in once, and sign-out ends the session`() = testApplication {
        application { module(config, mailer) }
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/start") { setBody(body(" Dana@Example.COM ")) }.status)
        val mail = mailer.sent.single()
        assertEquals(listOf("dana@example.com"), mail.to)
        val code = mailer.lastCodeFor("dana@example.com")

        assertEquals(HttpStatusCode.Unauthorized,
            client.post("/api/v1/auth/verify") { setBody(body("dana@example.com", if (code == "111111") "222222" else "111111")) }.status)
        val ok = client.post("/api/v1/auth/verify") { setBody(body("dana@example.com", code)) }
        assertEquals(HttpStatusCode.OK, ok.status)
        val token = JSONObject(ok.bodyAsText()).getString("token")

        // The code is used up.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/verify") { setBody(body("dana@example.com", code)) }.status)

        assertEquals(HttpStatusCode.Conflict, client.get("/api/v1/me") { bearerAuth(token) }.status, "signed in, no company yet")
        client.post("/api/v1/auth/logout") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(token) }.status)
    }

    @Test
    fun `wrong guesses burn the code and resends are rate limited`() = testApplication {
        application { module(config, mailer) }
        client.post("/api/v1/auth/start") { setBody(body("omer@example.com")) }
        val code = mailer.lastCodeFor("omer@example.com")
        val wrong = if (code == "123456") "654321" else "123456"
        repeat(AccountStore.MAX_CODE_ATTEMPTS) { client.post("/api/v1/auth/verify") { setBody(body("omer@example.com", wrong)) } }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/verify") { setBody(body("omer@example.com", code)) }.status,
            "even the right code fails after too many guesses")

        assertEquals(HttpStatusCode.TooManyRequests, client.post("/api/v1/auth/start") { setBody(body("omer@example.com")) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/auth/start") { setBody(body("not-an-email")) }.status)
    }

    @Test
    fun `without SMTP nobody can be sent a code`() = testApplication {
        application { module(config, NoMailer) }
        val r = client.post("/api/v1/auth/start") { setBody(body("a@example.com")) }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertEquals("email_not_configured", JSONObject(r.bodyAsText()).getString("error"))
    }

    @Test
    fun `session tokens are not stored in the database`() = testApplication {
        application { module(config, mailer) }
        val people = People(client, mailer)
        val token = people.token("a@example.com")
        // The database and its write-ahead log hold only HMACs of the token.
        val dump = dataDir.listFiles()!!.filter { it.name.startsWith("supportvisit.db") }
            .joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertTrue(dump.isNotEmpty())
        assertFalse(dump.contains(token))
    }

    @Test
    fun `people from before email sign-in keep their company and their data`() = testApplication {
        // A member stored when sign-in was through Microsoft (user id = Microsoft object id).
        File(dataDir, "supportvisit.db").also { db ->
            TeamStore(db) // creates the tables
            DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
                c.createStatement().execute(
                    "INSERT INTO members (tenant_id, user_id, email, name, role, joined_at, last_seen_at, status) " +
                        "VALUES ('legacy-tenant', 'ms-oid-1', 'boaz@example.com', 'Boaz', 'ADMIN', 1, 1, 'ACTIVE')"
                )
            }
        }
        application { module(config, mailer) }
        val me = People(client, mailer).me("boaz@example.com")
        assertEquals("legacy-tenant", me.getString("tenantId"))
        assertEquals("ms-oid-1", me.getString("userId"))
        assertEquals("ADMIN", me.getString("role"))
        assertTrue(me.getInt("memberCount") >= 1)
    }
}
