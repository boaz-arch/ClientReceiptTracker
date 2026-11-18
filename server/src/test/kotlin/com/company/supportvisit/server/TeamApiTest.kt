package com.company.supportvisit.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeamApiTest {

    private val dataDir: File = Files.createTempDirectory("svteam").toFile()
    private val mailer = FakeMailer()

    @Test
    fun `whoever creates a company is its admin, invited people join with their role`() = testApplication {
        application { module(testConfig(dataDir), mailer) }
        val p = People(client, mailer)
        p.company("boss@a.test")
        assertEquals("ADMIN", p.me("boss@a.test").getString("role"))

        p.join("boss@a.test", "dana@a.test", role = "MANAGER")
        assertEquals("MANAGER", p.me("dana@a.test").getString("role"))
        assertEquals(p.me("boss@a.test").getString("tenantId"), p.me("dana@a.test").getString("tenantId"))
        assertEquals(2, p.me("boss@a.test").getInt("memberCount"))
        assertTrue(mailer.sent.any { "dana@a.test" in it.to && it.subject.contains("Acme IT") }, "invitation emailed")

        // Not invited: no company until they create one.
        assertEquals(HttpStatusCode.Conflict, client.get("/api/v1/me") { bearerAuth(p.token("stranger@x.test")) }.status)
        // Someone already in a company can't be invited into another, nor start a second one.
        p.company("other@b.test", "B Ltd")
        assertEquals(HttpStatusCode.Conflict, client.post("/api/v1/members/invites") {
            bearerAuth(p.token("other@b.test")); setBody("""{"email":"dana@a.test"}""")
        }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/companies") {
            bearerAuth(p.token("dana@a.test")); setBody("""{"name":"Second"}""")
        }.status)
        // Only admins invite.
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/members/invites") {
            bearerAuth(p.token("dana@a.test")); setBody("""{"email":"x@a.test"}""")
        }.status)
    }

    @Test
    fun `invitations wait for the person to choose, and companies can't replace each other's`() = testApplication {
        application { module(testConfig(dataDir), mailer) }
        val p = People(client, mailer)
        val a = p.company("boss@a.test", "A Ltd")
        val b = p.company("boss@b.test", "B Ltd")
        for (admin in listOf("boss@a.test", "boss@b.test")) {
            client.post("/api/v1/members/invites") { bearerAuth(p.token(admin)); setBody("""{"email":"tech@x.test"}""") }
        }
        // Not joined automatically: both invitations are offered.
        val r = client.get("/api/v1/me") { bearerAuth(p.token("tech@x.test")) }
        assertEquals(HttpStatusCode.Conflict, r.status)
        val offered = JSONObject(r.bodyAsText()).getJSONArray("invites")
        assertEquals(setOf("A Ltd", "B Ltd"), (0 until offered.length()).map { offered.getJSONObject(it).getString("companyName") }.toSet())

        // Accepting a company that didn't invite them is refused.
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/me/invites/c-nope/accept") { bearerAuth(p.token("tech@x.test")) }.status)
        p.accept("tech@x.test", a)
        assertEquals(a, p.me("tech@x.test").getString("tenantId"))
        assertEquals(0, JSONObject(client.get("/api/v1/members/invites") { bearerAuth(p.token("boss@b.test")) }.bodyAsText())
            .getJSONArray("items").length(), "the other invitation is gone")

        // Another company's admin can't remove (or sign out) A's members.
        val techId = p.me("tech@x.test").getString("userId")
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/members/$techId") { bearerAuth(p.token("boss@b.test")) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearerAuth(p.token("tech@x.test")) }.status)
        assertTrue(b.isNotEmpty())
    }

    @Test
    fun `removed members are blocked until an admin restores or re-invites them`() = testApplication {
        application { module(testConfig(dataDir), mailer) }
        val p = People(client, mailer)
        p.company("boss@a.test")
        val leaver = p.join("boss@a.test", "leaver@a.test")
        val boss = p.token("boss@a.test")
        val leaverToken = p.token("leaver@a.test")

        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/members/$leaver") { bearerAuth(boss) }.status)
        // Their sessions are ended.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(leaverToken) }.status)

        // Signing in again doesn't get them back in (checked on the store: a new code is rate limited for 30 s).
        val db = File(dataDir, "supportvisit.db")
        val account = AccountStore(db, "x".repeat(40)).userByEmail("leaver@a.test")!!
        val team = TeamStore(db)
        assertEquals("member_removed", runCatching { team.resolve(account) }.exceptionOrNull()?.message)
        assertEquals(1, JSONObject(client.get("/api/v1/members/removed") { bearerAuth(boss) }.bodyAsText()).getJSONArray("items").length())

        assertEquals(HttpStatusCode.OK, client.post("/api/v1/members/$leaver/restore") { bearerAuth(boss) }.status)
        assertEquals(Role.TECHNICIAN, team.resolve(account).role)

        // Removing and inviting again also lets them back in (once they accept).
        client.delete("/api/v1/members/$leaver") { bearerAuth(boss) }
        client.post("/api/v1/members/invites") { bearerAuth(boss); setBody("""{"email":"leaver@a.test","role":"MANAGER"}""") }
        team.acceptInvite(account, p.me("boss@a.test").getString("tenantId"))
        assertEquals(Role.MANAGER, team.resolve(account).role)
    }

    @Test
    fun `technicians see only their own visits, managers see all`() = testApplication {
        application { module(testConfig(dataDir), mailer) }
        val p = People(client, mailer)
        p.company("boss@a.test")
        for (who in listOf("dana", "omer")) {
            p.join("boss@a.test", "$who@a.test")
            client.put("/api/v1/visits/v-$who") { bearerAuth(p.token("$who@a.test")); setBody(JSONObject().put("id", "v-$who").toString()) }
        }
        fun ids(json: String) = JSONObject(json).getJSONArray("items").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") }.toSet() }
        assertEquals(setOf("v-dana"), ids(client.get("/api/v1/visits?since=0") { bearerAuth(p.token("dana@a.test")) }.bodyAsText()))
        assertEquals(setOf("v-dana", "v-omer"), ids(client.get("/api/v1/visits?since=0") { bearerAuth(p.token("boss@a.test")) }.bodyAsText()))
    }

    @Test
    fun `tasks are visible to creator and assignee, editable by them or managers`() = testApplication {
        application { module(testConfig(dataDir), mailer) }
        val p = People(client, mailer)
        p.company("boss@a.test")
        val dana = p.join("boss@a.test", "dana@a.test")
        p.join("boss@a.test", "omer@a.test")
        val task = JSONObject().put("id", "task1").put("title", "Replace router").put("assigneeId", dana)
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/tasks/task1") { bearerAuth(p.token("boss@a.test")); setBody(task.toString()) }.status)

        fun count(json: String) = JSONObject(json).getJSONArray("items").length()
        assertEquals(1, count(client.get("/api/v1/tasks?since=0") { bearerAuth(p.token("dana@a.test")) }.bodyAsText()))
        assertEquals(0, count(client.get("/api/v1/tasks?since=0") { bearerAuth(p.token("omer@a.test")) }.bodyAsText()))

        // The assignee may mark it done; an unrelated technician may not touch it.
        val done = JSONObject(task.toString()).put("done", true).toString()
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/tasks/task1") { bearerAuth(p.token("dana@a.test")); setBody(done) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/tasks/task1") { bearerAuth(p.token("omer@a.test")); setBody(done) }.status)
    }

    @Test
    fun `only admins change the company profile and roles and the last admin stays`() = testApplication {
        application { module(testConfig(dataDir), mailer) }
        val p = People(client, mailer)
        p.company("boss@a.test", "Starter name")
        val dana = p.join("boss@a.test", "dana@a.test")
        val bossId = p.me("boss@a.test").getString("userId")
        val profile = JSONObject().put("name", "Acme IT").put("companyNumber", "514000000").put("ignored", "x").toString()

        assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/company") { bearerAuth(p.token("dana@a.test")); setBody(profile) }.status)
        val saved = JSONObject(client.put("/api/v1/company") { bearerAuth(p.token("boss@a.test")); setBody(profile) }.bodyAsText())
        assertEquals("Acme IT", saved.getString("name"))
        assertTrue(!saved.has("ignored"))
        assertEquals("Acme IT", JSONObject(client.get("/api/v1/company") { bearerAuth(p.token("dana@a.test")) }.bodyAsText()).getString("name"))

        assertEquals(HttpStatusCode.Forbidden,
            client.put("/api/v1/members/$bossId/role") { bearerAuth(p.token("dana@a.test")); setBody("""{"role":"TECHNICIAN"}""") }.status)
        assertEquals(HttpStatusCode.BadRequest,
            client.put("/api/v1/members/$bossId/role") { bearerAuth(p.token("boss@a.test")); setBody("""{"role":"TECHNICIAN"}""") }.status)
        assertEquals(HttpStatusCode.OK,
            client.put("/api/v1/members/$dana/role") { bearerAuth(p.token("boss@a.test")); setBody("""{"role":"MANAGER"}""") }.status)
        assertEquals("MANAGER", p.me("dana@a.test").getString("role"))
    }

    @Test
    fun `a technician can read only their own visit files, a manager reads all`() = testApplication {
        application { module(testConfig(dataDir), mailer) }
        val p = People(client, mailer)
        p.company("boss@a.test")
        p.join("boss@a.test", "dana@a.test")
        p.join("boss@a.test", "omer@a.test")
        val dana = p.token("dana@a.test")
        val omer = p.token("omer@a.test")
        client.put("/api/v1/visits/v1") { bearerAuth(dana); setBody("""{"id":"v1"}""") }
        client.put("/api/v1/visits/v1/files/photo-1.jpg") { bearerAuth(dana); setBody("jpg".toByteArray()) }

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/visits/v1/files/photo-1.jpg") { bearerAuth(dana) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/visits/v1/files/photo-1.jpg") { bearerAuth(omer) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/visits/v1/files/photo-1.jpg/link") { bearerAuth(omer) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/visits/nope/files/photo-1.jpg") { bearerAuth(omer) }.status,
            "files of an unknown visit are not readable by technicians")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/visits/v1/files/photo-1.jpg") { bearerAuth(p.token("boss@a.test")) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/visits/v1/files/photo-1.jpg/link") { bearerAuth(p.token("boss@a.test")) }.status)
    }

    @Test
    fun `X-Forwarded-For is trusted only from the local proxy`() {
        assertTrue(isLoopback("127.0.0.1"))
        assertTrue(isLoopback("/0:0:0:0:0:0:0:1"))
        assertTrue(!isLoopback("10.10.0.5"))
        assertTrue(!isLoopback("81.218.149.33"))
    }

    @Test
    fun `reports are emailed from the server with the company name, and only by their creator or a manager`() = testApplication {
        application { module(testConfig(dataDir, mailDailyLimit = 2), mailer) }
        val p = People(client, mailer)
        p.company("boss@a.test")
        client.put("/api/v1/company") { bearerAuth(p.token("boss@a.test")); setBody("""{"name":"Acme IT","email":"office@acme.test"}""") }
        p.join("boss@a.test", "dana@a.test")
        p.join("boss@a.test", "omer@a.test")
        val dana = p.token("dana@a.test")
        client.put("/api/v1/visits/v1") { bearerAuth(dana); setBody("""{"id":"v1"}""") }
        val email = JSONObject().put("to", org.json.JSONArray(listOf("client@example.com"))).put("subject", "Visit report")
            .put("body", "Hello").put("fileName", "Visit_2026.pdf").toString()

        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/visits/v1/email") { bearerAuth(dana); setBody(email) }.status,
            "no report uploaded yet")
        client.put("/api/v1/visits/v1/files/report.pdf") { bearerAuth(dana); setBody("MZ not a pdf".toByteArray()) }
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/visits/v1/email") { bearerAuth(dana); setBody(email) }.status,
            "only a real PDF is sent")
        client.put("/api/v1/visits/v1/files/report.pdf") { bearerAuth(dana); setBody("%PDF-1.4".toByteArray()) }

        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/visits/v1/email") { bearerAuth(p.token("omer@a.test")); setBody(email) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/visits/v1/email") { bearerAuth(dana); setBody(email) }.status)
        val sent = mailer.sent.last()
        assertEquals(listOf("client@example.com"), sent.to)
        assertEquals("Acme IT (via Support Visit)", sent.fromName)
        assertEquals("office@acme.test", sent.replyTo)
        assertEquals("Visit_2026.pdf", sent.attachments.single().name)

        assertEquals(HttpStatusCode.OK, client.post("/api/v1/visits/v1/email") { bearerAuth(p.token("boss@a.test")); setBody(email) }.status)
        assertEquals(HttpStatusCode.TooManyRequests, client.post("/api/v1/visits/v1/email") { bearerAuth(dana); setBody(email) }.status,
            "daily limit per company")
    }

    @Test
    fun `monthly reports are emailed with their PDF and CSV only`() = testApplication {
        application { module(testConfig(dataDir), mailer) }
        val p = People(client, mailer)
        p.company("boss@a.test")
        val pdf = java.util.Base64.getEncoder().encodeToString("%PDF-1.4".toByteArray())
        fun body(type: String) = JSONObject().put("to", org.json.JSONArray(listOf("client@example.com"))).put("subject", "March")
            .put("attachments", org.json.JSONArray().put(JSONObject().put("name", "Report.pdf").put("contentType", type).put("data", pdf)))
            .toString()
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/reports/email") { bearerAuth(p.token("boss@a.test")); setBody(body("application/pdf")) }.status)
        assertEquals("Report.pdf", mailer.sent.last().attachments.single().name)
        // A CSV can't pose as something else: its extension comes from the checked type.
        val csv = JSONObject().put("to", org.json.JSONArray(listOf("client@example.com"))).put("subject", "March")
            .put("attachments", org.json.JSONArray().put(JSONObject().put("name", "invoice.pdf.exe").put("contentType", "text/csv")
                .put("data", java.util.Base64.getEncoder().encodeToString("a,b\n1,2\n".toByteArray())))).toString()
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/reports/email") { bearerAuth(p.token("boss@a.test")); setBody(csv) }.status)
        assertEquals("invoice_pdf.csv", mailer.sent.last().attachments.single().name)
        assertEquals(HttpStatusCode.BadRequest,
            client.post("/api/v1/reports/email") { bearerAuth(p.token("boss@a.test")); setBody(body("application/x-msdownload")) }.status)
    }

    @Test
    fun `plan grants, crash reports and events`() = testApplication {
        application { module(testConfig(dataDir), mailer) }
        val p = People(client, mailer)
        val tenant = p.company("boss@a.test")
        assertEquals("FREE", p.me("boss@a.test").getString("plan"))
        TeamStore(File(dataDir, "supportvisit.db")).grantPro(tenant, "family")
        assertEquals("PRO", p.me("boss@a.test").getString("plan"))

        val boss = p.token("boss@a.test")
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/crashes") {
            bearerAuth(boss); setBody(JSONObject().put("appVersion", "1.0").put("stack", "java.lang.X\n at Y").toString())
        }.status)
        client.post("/api/v1/events") { bearerAuth(boss); setBody("""{"events":["visit_submitted","visit_submitted","Bad Name!"]}""") }
        val team = TeamStore(File(dataDir, "supportvisit.db"))
        assertEquals(mapOf("visit_submitted" to 2), team.eventCounts(tenant))
        assertEquals(1, team.recentCrashes().length())
    }

    @Test
    fun `update manifest and legal pages are public`() = testApplication {
        application { module(testConfig(dataDir), mailer) }
        assertEquals(HttpStatusCode.NotFound, client.get("/updates/latest.json").status)
        File(dataDir, "updates").mkdirs()
        File(dataDir, "updates/latest.json").writeText("""{"versionCode":2}""")
        assertEquals(2, JSONObject(client.get("/updates/latest.json").bodyAsText()).getInt("versionCode"))

        File(dataDir, "legal").mkdirs()
        File(dataDir, "legal/privacy.md").writeText("# Privacy <script>")
        val html = client.get("/legal/privacy").bodyAsText()
        assertTrue(html.contains("&lt;script&gt;"), "content is escaped")
        assertEquals(HttpStatusCode.BadRequest, client.get("/legal/other").status, "only privacy and terms")
    }
}
