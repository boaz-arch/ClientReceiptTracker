package com.company.supportvisit.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {

    private val dataDir: File = Files.createTempDirectory("svtest").toFile()
    private val config = testConfig(dataDir)
    private val mailer = FakeMailer()

    @Test
    fun `visits are stored per company and returned as changes`() = testApplication {
        application { module(config, mailer) }
        val people = People(client, mailer)
        people.company("boss@a.test")
        people.join("boss@a.test", "tech@a.test")

        val visit = JSONObject().put("id", "v1").put("clientName", "Acme \"HQ\"")
        val put = client.put("/api/v1/visits/v1") { bearerAuth(people.token("tech@a.test")); setBody(visit.toString()) }
        assertEquals(HttpStatusCode.OK, put.status)

        val all = JSONObject(client.get("/api/v1/visits?since=0") { bearerAuth(people.token("boss@a.test")) }.bodyAsText())
        assertEquals(1, all.getJSONArray("items").length())
        assertEquals("Acme \"HQ\"", all.getJSONArray("items").getJSONObject(0).getString("clientName"))

        // Since the returned cursor, nothing new.
        val cursor = all.getLong("cursor")
        val again = JSONObject(client.get("/api/v1/visits?since=$cursor") { bearerAuth(people.token("boss@a.test")) }.bodyAsText())
        assertEquals(0, again.getJSONArray("items").length())

        // Another company sees nothing.
        people.company("x@b.test", "Other Co")
        val other = JSONObject(client.get("/api/v1/visits?since=0") { bearerAuth(people.token("x@b.test")) }.bodyAsText())
        assertEquals(0, other.getJSONArray("items").length())
    }

    @Test
    fun `requests without a valid session are rejected`() = testApplication {
        application { module(config, mailer) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/visits").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/visits") { bearerAuth("not-a-real-session-token-at-all") }.status)
    }

    @Test
    fun `signed in but not in a company yet`() = testApplication {
        application { module(config, mailer) }
        val people = People(client, mailer)
        val r = client.get("/api/v1/visits?since=0") { bearerAuth(people.token("new@c.test")) }
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertEquals("no_company", JSONObject(r.bodyAsText()).getString("error"))
    }

    @Test
    fun `body id must match the url`() = testApplication {
        application { module(config, mailer) }
        val people = People(client, mailer)
        people.company("a@b.test")
        val response = client.put("/api/v1/clients/c1") {
            bearerAuth(people.token("a@b.test")); setBody(JSONObject().put("id", "other").toString())
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `files upload, download and open through a signed link only`() = testApplication {
        application { module(config, mailer) }
        val people = People(client, mailer)
        val tenant = people.company("a@b.test")
        val token = people.token("a@b.test")
        val bytes = ByteArray(300_000) { (it % 251).toByte() }

        val upload = client.put("/api/v1/visits/v1/files/report.pdf") { bearerAuth(token); setBody(bytes) }
        assertEquals(HttpStatusCode.OK, upload.status)
        assertTrue(JSONObject(upload.bodyAsText()).getString("url").endsWith("/api/v1/visits/v1/files/report.pdf"))

        val download = client.get("/api/v1/visits/v1/files/report.pdf") { bearerAuth(token) }
        assertContentEquals(bytes, download.readRawBytes())

        // Path traversal attempts are refused.
        val bad = client.put("/api/v1/visits/v1/files/..secret") { bearerAuth(token); setBody(bytes) }
        assertEquals(HttpStatusCode.BadRequest, bad.status)

        val link = JSONObject(client.post("/api/v1/visits/v1/files/report.pdf/link") { bearerAuth(token) }.bodyAsText())
        val path = link.getString("url").removePrefix("https://example.test/sv")
        assertContentEquals(bytes, client.get(path).readRawBytes())

        // Tampered signature fails.
        assertEquals(HttpStatusCode.Forbidden, client.get(path.replace("sig=", "sig=x")).status)
        // Another company's path with the same signature fails.
        assertEquals(HttpStatusCode.Forbidden, client.get(path.replace("/files/$tenant/", "/files/c-other/")).status)
    }
}
