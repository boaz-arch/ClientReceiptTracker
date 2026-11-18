package com.company.supportvisit.server

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A person who can sign in (by email). Which company they belong to is in [TeamStore]. */
data class UserAccount(val userId: String, val email: String, val name: String)

class RateLimitedException(val retryAfterSeconds: Long) : Exception("Too many attempts")

/**
 * Passwordless sign-in: the server emails a 6-digit code, the app trades it for a long-lived
 * session token. Codes and tokens are stored only as HMACs, so a copy of the database can't
 * be used to sign in.
 */
class AccountStore(
    dbFile: File,
    secret: String,
    private val random: SecureRandom = SecureRandom(),
    private val reviewLogin: ReviewLogin? = null
) {

    private var reviewFailures = 0

    /** True for the app-store review account: no code is emailed to it. */
    fun isReviewAccount(email: String): Boolean = reviewLogin != null && normalizeEmail(email) == reviewLogin.email

    companion object {
        const val CODE_TTL_MS = 10L * 60 * 1000
        const val MAX_CODE_ATTEMPTS = 5
        const val RESEND_AFTER_MS = 30_000L
        const val CODES_PER_HOUR = 5
        const val SESSION_TTL_MS = 180L * 24 * 60 * 60 * 1000
        private val EMAIL = Regex("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$")

        fun normalizeEmail(raw: String): String {
            val e = raw.trim().lowercase()
            require(e.length <= 254 && EMAIL.matches(e)) { "Invalid email" }
            return e
        }
    }

    private val key = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
    private val connection: Connection

    init {
        dbFile.parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.execute(
                "CREATE TABLE IF NOT EXISTS users (user_id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, " +
                    "name TEXT NOT NULL, created_at INTEGER NOT NULL)"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS login_codes (email TEXT PRIMARY KEY, code_hash TEXT NOT NULL, " +
                    "expires_at INTEGER NOT NULL, attempts INTEGER NOT NULL, sent_at INTEGER NOT NULL, " +
                    "hour_start INTEGER NOT NULL, hour_count INTEGER NOT NULL)"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS sessions (token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, last_used_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)"
            )
            st.execute("CREATE INDEX IF NOT EXISTS sessions_user ON sessions (user_id)")
        }
    }

    /** Creates a new code for [email] (replacing any earlier one) and returns it for emailing. */
    @Synchronized
    fun startLogin(email: String, now: Long = System.currentTimeMillis()): String {
        val e = normalizeEmail(email)
        var hourStart = now
        var hourCount = 0
        connection.prepareStatement("SELECT sent_at, hour_start, hour_count FROM login_codes WHERE email = ?").use { ps ->
            ps.setString(1, e)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val sentAt = rs.getLong(1)
                    if (now - sentAt < RESEND_AFTER_MS) throw RateLimitedException((RESEND_AFTER_MS - (now - sentAt)) / 1000 + 1)
                    if (now - rs.getLong(2) < 3_600_000) {
                        hourStart = rs.getLong(2)
                        hourCount = rs.getInt(3)
                    }
                }
            }
        }
        if (hourCount >= CODES_PER_HOUR) throw RateLimitedException((hourStart + 3_600_000 - now) / 1000 + 1)
        val code = (random.nextInt(900_000) + 100_000).toString()
        connection.prepareStatement(
            "INSERT OR REPLACE INTO login_codes (email, code_hash, expires_at, attempts, sent_at, hour_start, hour_count) " +
                "VALUES (?, ?, ?, 0, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, e); ps.setString(2, hmac("code:$e:$code")); ps.setLong(3, now + CODE_TTL_MS)
            ps.setLong(4, now); ps.setLong(5, hourStart); ps.setInt(6, hourCount + 1)
            ps.executeUpdate()
        }
        return code
    }

    /**
     * Checks the code. On success the code is used up and a session token is returned with the
     * account; [existingUserId] lets a person who used the server before keep their user id.
     */
    @Synchronized
    fun verifyLogin(
        email: String,
        code: String,
        now: Long = System.currentTimeMillis(),
        existingUserId: (String) -> String? = { null }
    ): Pair<String, UserAccount> {
        val e = normalizeEmail(email)
        if (reviewLogin != null && e == reviewLogin.email) return verifyReviewLogin(e, code, now, existingUserId)
        val row = connection.prepareStatement("SELECT code_hash, expires_at, attempts FROM login_codes WHERE email = ?").use { ps ->
            ps.setString(1, e)
            ps.executeQuery().use { rs -> if (rs.next()) Triple(rs.getString(1), rs.getLong(2), rs.getInt(3)) else null }
        } ?: throw AuthException("invalid_code")
        val (hash, expiresAt, attempts) = row
        if (now > expiresAt || attempts >= MAX_CODE_ATTEMPTS) throw AuthException("code_expired")
        val ok = MessageDigest.isEqual(hash.toByteArray(), hmac("code:$e:${code.trim()}").toByteArray())
        if (!ok) {
            connection.prepareStatement("UPDATE login_codes SET attempts = attempts + 1 WHERE email = ?").use { ps ->
                ps.setString(1, e); ps.executeUpdate()
            }
            throw AuthException("invalid_code")
        }
        // Used up, but keep the hourly counters so the rate limit still applies.
        connection.prepareStatement("UPDATE login_codes SET expires_at = 0 WHERE email = ?").use { ps ->
            ps.setString(1, e); ps.executeUpdate()
        }
        val user = userByEmail(e) ?: createUser(e, existingUserId(e), now)
        return newSession(user.userId, now) to user
    }

    private fun verifyReviewLogin(
        e: String,
        code: String,
        now: Long,
        existingUserId: (String) -> String?
    ): Pair<String, UserAccount> {
        val review = reviewLogin!!
        if (reviewFailures >= ReviewLogin.MAX_FAILURES) throw AuthException("code_expired")
        if (!MessageDigest.isEqual(review.code.toByteArray(), code.trim().toByteArray())) {
            reviewFailures++
            throw AuthException("invalid_code")
        }
        val user = userByEmail(e) ?: createUser(e, existingUserId(e), now)
        return newSession(user.userId, now) to user
    }

    @Synchronized
    fun authenticate(token: String, now: Long = System.currentTimeMillis()): UserAccount {
        if (token.length !in 20..200) throw AuthException("invalid_session")
        val th = hmac("session:$token")
        val userId = connection.prepareStatement("SELECT user_id, expires_at, last_used_at FROM sessions WHERE token_hash = ?").use { ps ->
            ps.setString(1, th)
            ps.executeQuery().use { rs ->
                if (!rs.next()) throw AuthException("invalid_session")
                if (now > rs.getLong(2)) throw AuthException("session_expired")
                val lastUsed = rs.getLong(3)
                val id = rs.getString(1)
                // Sliding expiry, written at most once a day.
                if (now - lastUsed > 24 * 60 * 60 * 1000L) {
                    connection.prepareStatement("UPDATE sessions SET last_used_at = ?, expires_at = ? WHERE token_hash = ?").use { up ->
                        up.setLong(1, now); up.setLong(2, now + SESSION_TTL_MS); up.setString(3, th); up.executeUpdate()
                    }
                }
                id
            }
        }
        return user(userId) ?: throw AuthException("invalid_session")
    }

    @Synchronized
    fun logout(token: String) {
        connection.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use { ps ->
            ps.setString(1, hmac("session:$token")); ps.executeUpdate()
        }
    }

    /** Signs the person out on every phone. */
    @Synchronized
    fun revokeAll(userId: String) {
        connection.prepareStatement("DELETE FROM sessions WHERE user_id = ?").use { ps -> ps.setString(1, userId); ps.executeUpdate() }
    }

    @Synchronized
    fun setName(userId: String, name: String) {
        connection.prepareStatement("UPDATE users SET name = ? WHERE user_id = ?").use { ps ->
            ps.setString(1, name.trim().take(80)); ps.setString(2, userId); ps.executeUpdate()
        }
    }

    /** Removes the account entirely (after an account deletion is carried out). */
    @Synchronized
    fun deleteUser(userId: String) {
        revokeAll(userId)
        val email = user(userId)?.email
        connection.prepareStatement("DELETE FROM users WHERE user_id = ?").use { ps -> ps.setString(1, userId); ps.executeUpdate() }
        if (email != null) connection.prepareStatement("DELETE FROM login_codes WHERE email = ?").use { ps -> ps.setString(1, email); ps.executeUpdate() }
    }

    @Synchronized
    fun user(userId: String): UserAccount? =
        connection.prepareStatement("SELECT user_id, email, name FROM users WHERE user_id = ?").use { ps ->
            ps.setString(1, userId)
            ps.executeQuery().use { rs -> if (rs.next()) UserAccount(rs.getString(1), rs.getString(2), rs.getString(3)) else null }
        }

    @Synchronized
    fun userByEmail(email: String): UserAccount? =
        connection.prepareStatement("SELECT user_id, email, name FROM users WHERE email = ?").use { ps ->
            ps.setString(1, email.trim().lowercase())
            ps.executeQuery().use { rs -> if (rs.next()) UserAccount(rs.getString(1), rs.getString(2), rs.getString(3)) else null }
        }

    private fun createUser(email: String, reuseId: String?, now: Long): UserAccount {
        val user = UserAccount(reuseId?.takeIf { user(it) == null } ?: "u-${UUID.randomUUID()}", email, email.substringBefore('@'))
        connection.prepareStatement("INSERT INTO users (user_id, email, name, created_at) VALUES (?, ?, ?, ?)").use { ps ->
            ps.setString(1, user.userId); ps.setString(2, user.email); ps.setString(3, user.name); ps.setLong(4, now)
            ps.executeUpdate()
        }
        return user
    }

    private fun newSession(userId: String, now: Long): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        connection.prepareStatement(
            "INSERT INTO sessions (token_hash, user_id, created_at, last_used_at, expires_at) VALUES (?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, hmac("session:$token")); ps.setString(2, userId)
            ps.setLong(3, now); ps.setLong(4, now); ps.setLong(5, now + SESSION_TTL_MS)
            ps.executeUpdate()
        }
        return token
    }

    private fun hmac(value: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        return Base64.getEncoder().encodeToString(mac.doFinal(value.toByteArray()))
    }
}

/** Simple in-memory limiter per key (e.g. client IP) for the sign-in endpoints. */
class WindowLimiter(private val max: Int, private val windowMs: Long) {
    private val hits = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun check(key: String, now: Long = System.currentTimeMillis()) {
        val q = hits.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst()
        if (q.size >= max) throw RateLimitedException((q.first() + windowMs - now) / 1000 + 1)
        q.addLast(now)
        if (hits.size > 10_000) hits.entries.removeIf { it.value.isEmpty() || now - it.value.last() > windowMs }
    }
}
