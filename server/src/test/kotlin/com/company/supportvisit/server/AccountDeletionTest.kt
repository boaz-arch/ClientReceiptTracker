package com.company.supportvisit.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountDeletionTest {

    private val dataDir: File = Files.createTempDirectory("svdel").toFile()
    private val config = Config(
        port = 0, dataDir = dataDir, clientId = "client", allowedTenants = emptySet(),
        publicBaseUrl = "https://example.test/sv", linkSecret = "x".repeat(40),
        purgeIntervalMs = 3_600_000
    )
    private val fakeVerify: (String) -> Caller = { token ->
        val (tenant, email) = token.split(':')
        Caller(tenant, "oid-$email", email, email)
    }

    private fun visit(id: String, tech: String) =
        JSONObject().put("id", id).put("technicianEmail", tech).put("clientName", "Acme").toString()

    @Test
    fun `deletion can be scheduled, checked and cancelled`() = testApplication {
        application { module(config, fakeVerify) }
        val me = "t1:tech@a.test"

        val scheduled = JSONObject(client.post("/api/v1/me/deletion") { bearerAuth(me); setBody("""{"keepShared":false}""") }.bodyAsText())
        assertTrue(scheduled.getBoolean("pending"))
        val inFourteenDays = System.currentTimeMillis() + 14L * 24 * 3600 * 1000
        assertTrue(Math.abs(scheduled.getLong("purgeAt") - inFourteenDays) < 60_000)

        assertTrue(JSONObject(client.get("/api/v1/me/deletion") { bearerAuth(me) }.bodyAsText()).getBoolean("pending"))
        assertTrue(JSONObject(client.delete("/api/v1/me/deletion") { bearerAuth(me) }.bodyAsText()).getBoolean("cancelled"))
        assertFalse(JSONObject(client.get("/api/v1/me/deletion") { bearerAuth(me) }.bodyAsText()).getBoolean("pending"))
    }

    @Test
    fun `purge wipes only the user's visits and files and leaves tombstones`() {
        val store = Store(File(dataDir, "a.db"))
        val files = FileStore(dataDir, 1_000_000)
        store.upsert(Store.Kind.VISITS, "t1", "v1", JSONObject(visit("v1", "Tech@A.test")), "tech@a.test", createdBy = "oid")
        store.upsert(Store.Kind.VISITS, "t1", "v2", JSONObject(visit("v2", "tech@a.test")), "other@a.test", createdBy = "oid-other")
        store.upsert(Store.Kind.VISITS, "t2", "v3", JSONObject(visit("v3", "tech@a.test")), "tech@a.test", createdBy = "oid") // other company
        files.save("t1", "v1", "report.pdf", "pdf".byteInputStream())

        store.requestDeletion(Store.DeletionRequest("t1", "oid", "tech@a.test", false, 0, purgeAt = 1000))
        assertEquals(0, AccountPurge.run(store, files, now = 999), "not purged before the grace period ends")
        assertEquals(1, AccountPurge.run(store, files, now = 1000))

        val changes = store.changesSince(Store.Kind.VISITS, "t1", 0).associateBy { it.id }
        assertTrue(changes.getValue("v1").data.getBoolean("deleted"))
        assertFalse(changes.getValue("v1").data.has("clientName"), "content is gone")
        assertFalse(changes.getValue("v2").data.optBoolean("deleted"), "coworker's visit stays, even with a forged technicianEmail")
        assertFalse(store.changesSince(Store.Kind.VISITS, "t2", 0).single().data.optBoolean("deleted"), "other company untouched")
        assertFalse(files.file("t1", "v1", "report.pdf").exists())
        assertEquals(null, store.deletionRequest("t1", "oid"))
    }

    @Test
    fun `keepShared leaves the user's reports in the company log`() {
        val store = Store(File(dataDir, "b.db"))
        val files = FileStore(dataDir, 1_000_000)
        store.upsert(Store.Kind.VISITS, "t1", "v1", JSONObject(visit("v1", "tech@a.test")), "tech@a.test", createdBy = "oid")

        store.requestDeletion(Store.DeletionRequest("t1", "oid", "tech@a.test", true, 0, purgeAt = 1000))
        AccountPurge.run(store, files, now = 2000)

        assertFalse(store.changesSince(Store.Kind.VISITS, "t1", 0).single().data.optBoolean("deleted"))
        assertEquals(null, store.deletionRequest("t1", "oid"))
    }

    @Test
    fun `tombstones reach other phones through the change feed`() = testApplication {
        application { module(config, fakeVerify) }
        client.put("/api/v1/visits/v9") { bearerAuth("t9:a@b.c"); setBody(visit("v9", "a@b.c")) }
        val cursor = JSONObject(client.get("/api/v1/visits?since=0") { bearerAuth("t9:x@b.c") }.bodyAsText()).getLong("cursor")

        val store = Store(File(dataDir, "supportvisit.db"))
        store.tombstone(Store.Kind.VISITS, "t9", "v9")

        val items = JSONObject(client.get("/api/v1/visits?since=$cursor") { bearerAuth("t9:x@b.c") }.bodyAsText())
            .getJSONArray("items")
        assertEquals(1, items.length())
        assertTrue(items.getJSONObject(0).getBoolean("deleted"))
    }

    @Test
    fun `phones cannot write tombstones or overwrite another user's visit`() = testApplication {
        application { module(config, fakeVerify) }
        client.put("/api/v1/visits/v5") { bearerAuth("t5:owner@a.test"); setBody(visit("v5", "owner@a.test")) }

        // A coworker tries to overwrite it, and to push a fake tombstone.
        val overwrite = client.put("/api/v1/visits/v5") {
            bearerAuth("t5:mallory@a.test"); setBody(JSONObject(visit("v5", "owner@a.test")).put("deleted", true).toString())
        }
        assertEquals(io.ktor.http.HttpStatusCode.Forbidden, overwrite.status)

        // The owner's own "deleted" flag is ignored too.
        client.put("/api/v1/visits/v5") {
            bearerAuth("t5:owner@a.test"); setBody(JSONObject(visit("v5", "owner@a.test")).put("deleted", true).toString())
        }
        val item = JSONObject(client.get("/api/v1/visits?since=0") { bearerAuth("t5:x@a.test") }.bodyAsText())
            .getJSONArray("items").getJSONObject(0)
        assertFalse(item.optBoolean("deleted"))
        assertEquals("Acme", item.getString("clientName"))
    }

    @Test
    fun `rows without a recorded creator are read-only and never auto-purged`() = testApplication {
        application { module(config, fakeVerify) }
        val store = Store(File(dataDir, "supportvisit.db"))
        // Simulates a row stored before creators were tracked.
        store.upsert(Store.Kind.VISITS, "t7", "old", JSONObject(visit("old", "tech@a.test")), "tech@a.test", createdBy = null)

        val write = client.put("/api/v1/visits/old") { bearerAuth("t7:tech@a.test"); setBody(visit("old", "tech@a.test")) }
        assertEquals(io.ktor.http.HttpStatusCode.Forbidden, write.status)

        store.requestDeletion(Store.DeletionRequest("t7", "oid-tech@a.test", "tech@a.test", false, 0, purgeAt = 1))
        AccountPurge.run(store, FileStore(dataDir, 1_000_000), now = 2)
        assertFalse(store.changesSince(Store.Kind.VISITS, "t7", 0).single().data.optBoolean("deleted"))
    }
}
