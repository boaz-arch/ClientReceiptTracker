package com.company.supportvisit.server

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewLoginTest {

    private val dataDir: File = Files.createTempDirectory("svreview").toFile()
    private val review = ReviewLogin("reviewer@example.com", "424242")
    private val config = testConfig(dataDir).copy(reviewLogin = review)
    private val mailer = FakeMailer()

    private fun body(email: String, code: String? = null) =
        JSONObject().put("email", email).apply { code?.let { put("code", it) } }.toString()

    @Test
    fun `the review account signs in with its fixed code and gets no email`() = testApplication {
        application { module(config, mailer) }

        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/start") { setBody(body("Reviewer@Example.com")) }.status)
        assertTrue(mailer.sent.isEmpty(), "nothing is emailed to the review address")

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/verify") { setBody(body("reviewer@example.com", "111111")) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/verify") { setBody(body("reviewer@example.com", "424242")) }.status)
        // Can sign in again later (e.g. on a second test device).
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/verify") { setBody(body("reviewer@example.com", "424242")) }.status)

        // Everyone else still gets an emailed code; the review code means nothing for them.
        client.post("/api/v1/auth/start") { setBody(body("dana@example.com")) }
        assertEquals(1, mailer.sent.size)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/verify") { setBody(body("dana@example.com", "424242")) }.status)
    }

    @Test
    fun `the review sign-in locks after too many wrong codes`() {
        val accounts = AccountStore(File(dataDir, "accounts.db"), "x".repeat(40), reviewLogin = review)
        repeat(ReviewLogin.MAX_FAILURES) {
            assertFailsWith<AuthException> { accounts.verifyLogin("reviewer@example.com", "000000") }
        }
        assertFailsWith<AuthException>("even the right code fails once locked") { accounts.verifyLogin("reviewer@example.com", "424242") }
    }

    @Test
    fun `review sign-in is off unless both settings are given, and the code must be 6 digits`() {
        assertNull(ReviewLogin.fromEnv(null, null))
        assertNull(ReviewLogin.fromEnv("reviewer@example.com", ""))
        assertNull(Config.fromEnv(mapOf("PUBLIC_BASE_URL" to "https://x.test", "LINK_SECRET" to "y".repeat(40))).reviewLogin)
        assertEquals(review, ReviewLogin.fromEnv(" Reviewer@example.com ", "424242"))
        assertFailsWith<IllegalArgumentException> { ReviewLogin.fromEnv("reviewer@example.com", "1234") }
    }
}
