package com.company.supportvisit.server

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
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
    private val config = Config(
        port = 0, dataDir = dataDir, clientId = "client", allowedTenants = emptySet(),
        publicBaseUrl = "https://example.test/sv", linkSecret = "x".repeat(40), proTenants = setOf("t-env")
    )
    private val fakeVerify: (String) -> Caller = { token ->
        val (tenant, email) = token.split(':')
        Caller(tenant, "oid-$email", email, email.substringBefore('@'))
    }

    private suspend fun HttpClient.me(token: String) = JSONObject(get("/api/v1/me") { bearerAuth(token) }.bodyAsText())

    @Test
    fun `first member is admin, later ones technicians`() = testApplication {
        application { module(config, fakeVerify) }
        assertEquals("ADMIN", client.me("t1:boss@a.test").getString("role"))
        assertEquals("TECHNICIAN", client.me("t1:tech@a.test").getString("role"))
        assertEquals("ADMIN", client.me("t2:other@b.test").getString("role"), "each company has its own first admin")
        assertEquals(2, client.me("t1:boss@a.test").getInt("memberCount"))
    }

    @Test
    fun `technicians see only their own visits, managers see all`() = testApplication {
        application { module(config, fakeVerify) }
        client.me("t1:boss@a.test")
        for (who in listOf("dana", "omer")) {
            client.put("/api/v1/visits/v-$who") { bearerAuth("t1:$who@a.test"); setBody(JSONObject().put("id", "v-$who").toString()) }
        }
        fun ids(json: String) = JSONObject(json).getJSONArray("items").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") }.toSet() }
        assertEquals(setOf("v-dana"), ids(client.get("/api/v1/visits?since=0") { bearerAuth("t1:dana@a.test") }.bodyAsText()))
        assertEquals(setOf("v-dana", "v-omer"), ids(client.get("/api/v1/visits?since=0") { bearerAuth("t1:boss@a.test") }.bodyAsText()))
    }

    @Test
    fun `tasks are visible to creator and assignee, editable by them or managers`() = testApplication {
        application { module(config, fakeVerify) }
        client.me("t1:boss@a.test")
        client.me("t1:dana@a.test")
        val task = JSONObject().put("id", "task1").put("title", "Replace router").put("assigneeId", "oid-dana@a.test")
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/tasks/task1") { bearerAuth("t1:boss@a.test"); setBody(task.toString()) }.status)

        fun count(json: String) = JSONObject(json).getJSONArray("items").length()
        assertEquals(1, count(client.get("/api/v1/tasks?since=0") { bearerAuth("t1:dana@a.test") }.bodyAsText()))
        assertEquals(0, count(client.get("/api/v1/tasks?since=0") { bearerAuth("t1:omer@a.test") }.bodyAsText()))

        // The assignee may mark it done; an unrelated technician may not touch it.
        val done = JSONObject(task.toString()).put("done", true).toString()
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/tasks/task1") { bearerAuth("t1:dana@a.test"); setBody(done) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/tasks/task1") { bearerAuth("t1:omer@a.test"); setBody(done) }.status)
    }

    @Test
    fun `only admins change the company profile and roles and the last admin stays`() = testApplication {
        application { module(config, fakeVerify) }
        client.me("t1:boss@a.test")
        client.me("t1:dana@a.test")
        val profile = JSONObject().put("name", "Acme IT").put("companyNumber", "514000000").put("ignored", "x").toString()

        assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/company") { bearerAuth("t1:dana@a.test"); setBody(profile) }.status)
        val saved = JSONObject(client.put("/api/v1/company") { bearerAuth("t1:boss@a.test"); setBody(profile) }.bodyAsText())
        assertEquals("Acme IT", saved.getString("name"))
        assertTrue(!saved.has("ignored"))
        assertEquals("Acme IT", JSONObject(client.get("/api/v1/company") { bearerAuth("t1:dana@a.test") }.bodyAsText()).getString("name"))

        assertEquals(HttpStatusCode.Forbidden,
            client.put("/api/v1/members/oid-boss@a.test/role") { bearerAuth("t1:dana@a.test"); setBody("""{"role":"TECHNICIAN"}""") }.status)
        assertEquals(HttpStatusCode.BadRequest,
            client.put("/api/v1/members/oid-boss@a.test/role") { bearerAuth("t1:boss@a.test"); setBody("""{"role":"TECHNICIAN"}""") }.status)
        assertEquals(HttpStatusCode.OK,
            client.put("/api/v1/members/oid-dana@a.test/role") { bearerAuth("t1:boss@a.test"); setBody("""{"role":"MANAGER"}""") }.status)
        assertEquals("MANAGER", client.me("t1:dana@a.test").getString("role"))
    }

    @Test
    fun `plan grants, crash reports and events`() = testApplication {
        application { module(config, fakeVerify) }
        assertEquals("FREE", client.me("t1:boss@a.test").getString("plan"))
        assertEquals("PRO", client.me("t-env:boss@e.test").getString("plan"), "granted through PRO_TENANT_IDS")
        TeamStore(File(dataDir, "supportvisit.db")).grantPro("t1", "family")
        assertEquals("PRO", client.me("t1:boss@a.test").getString("plan"))

        assertEquals(HttpStatusCode.OK, client.post("/api/v1/crashes") {
            bearerAuth("t1:boss@a.test"); setBody(JSONObject().put("appVersion", "1.0").put("stack", "java.lang.X\n at Y").toString())
        }.status)
        client.post("/api/v1/events") { bearerAuth("t1:boss@a.test"); setBody("""{"events":["visit_submitted","visit_submitted","Bad Name!"]}""") }
        val team = TeamStore(File(dataDir, "supportvisit.db"))
        assertEquals(mapOf("visit_submitted" to 2), team.eventCounts("t1"))
        assertEquals(1, team.recentCrashes().length())
    }

    @Test
    fun `update manifest and legal pages are public`() = testApplication {
        application { module(config, fakeVerify) }
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
