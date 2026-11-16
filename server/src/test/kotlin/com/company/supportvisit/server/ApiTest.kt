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
    private val config = Config(
        port = 0,
        dataDir = dataDir,
        clientId = "client",
        allowedTenants = emptySet(),
        publicBaseUrl = "https://example.test/supportvisit",
        linkSecret = "x".repeat(40)
    )

    /** Tokens in tests are just "<tenant>:<email>". */
    private val fakeVerify: (String) -> Caller = { token ->
        val (tenant, email) = token.split(':').also { if (it.size != 2) throw AuthException("bad") }
        Caller(tenant, "oid-$email", email, email)
    }

    @Test
    fun `visits are stored per tenant and returned as changes`() = testApplication {
        application { module(config, fakeVerify) }

        // The reader signs in first, so they are the company's admin and see everyone's visits.
        client.get("/api/v1/me") { bearerAuth("tenant-a:boss@a.test") }
        val visit = JSONObject().put("id", "v1").put("clientName", "Acme \"HQ\"")
        val put = client.put("/api/v1/visits/v1") { bearerAuth("tenant-a:tech@a.test"); setBody(visit.toString()) }
        assertEquals(HttpStatusCode.OK, put.status)

        val mine = JSONObject(client.get("/api/v1/visits?since=0") { bearerAuth("tenant-a:boss@a.test") }.bodyAsText())
        assertEquals(1, mine.getJSONArray("items").length())
        assertEquals("Acme \"HQ\"", mine.getJSONArray("items").getJSONObject(0).getString("clientName"))

        // Since the returned cursor, nothing new.
        val cursor = mine.getLong("cursor")
        val again = JSONObject(client.get("/api/v1/visits?since=$cursor") { bearerAuth("tenant-a:boss@a.test") }.bodyAsText())
        assertEquals(0, again.getJSONArray("items").length())

        // Another company sees nothing.
        val other = JSONObject(client.get("/api/v1/visits?since=0") { bearerAuth("tenant-b:x@b.test") }.bodyAsText())
        assertEquals(0, other.getJSONArray("items").length())
    }

    @Test
    fun `requests without a token are rejected`() = testApplication {
        application { module(config, fakeVerify) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/visits").status)
    }

    @Test
    fun `body id must match the url`() = testApplication {
        application { module(config, fakeVerify) }
        val response = client.put("/api/v1/clients/c1") {
            bearerAuth("t:a@b.c"); setBody(JSONObject().put("id", "other").toString())
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `files upload, download and open through a signed link only`() = testApplication {
        application { module(config, fakeVerify) }
        val bytes = ByteArray(300_000) { (it % 251).toByte() }

        val upload = client.put("/api/v1/visits/v1/files/report.pdf") { bearerAuth("t1:a@b.c"); setBody(bytes) }
        assertEquals(HttpStatusCode.OK, upload.status)
        assertTrue(JSONObject(upload.bodyAsText()).getString("url").endsWith("/api/v1/visits/v1/files/report.pdf"))

        val download = client.get("/api/v1/visits/v1/files/report.pdf") { bearerAuth("t1:a@b.c") }
        assertContentEquals(bytes, download.readRawBytes())

        // Path traversal attempts are refused.
        val bad = client.put("/api/v1/visits/v1/files/..secret") { bearerAuth("t1:a@b.c"); setBody(bytes) }
        assertEquals(HttpStatusCode.BadRequest, bad.status)

        val link = JSONObject(client.post("/api/v1/visits/v1/files/report.pdf/link") { bearerAuth("t1:a@b.c") }.bodyAsText())
        val path = link.getString("url").removePrefix("https://example.test/supportvisit")
        assertContentEquals(bytes, client.get(path).readRawBytes())

        // Tampered signature fails.
        assertEquals(HttpStatusCode.Forbidden, client.get(path.replace("sig=", "sig=x")).status)
        // Another tenant's path with the same signature fails.
        assertEquals(HttpStatusCode.Forbidden, client.get(path.replace("/files/t1/", "/files/t2/")).status)
    }
}
