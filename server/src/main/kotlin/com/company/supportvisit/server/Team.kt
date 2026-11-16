package com.company.supportvisit.server

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** What a member may do. The first person of a company to use the server becomes ADMIN. */
enum class Role {
    ADMIN, MANAGER, TECHNICIAN;

    val canSeeEverything: Boolean get() = this != TECHNICIAN
    val canManageCompany: Boolean get() = this == ADMIN
}

data class Member(
    val tenantId: String,
    val userId: String,
    val email: String,
    val name: String,
    val role: Role,
    val joinedAt: Long,
    val lastSeenAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("userId", userId).put("email", email).put("name", name)
        .put("role", role.name).put("joinedAt", joinedAt).put("lastSeenAt", lastSeenAt)
}

/**
 * Company-level data: members and roles, company profile (branding, email template), plan
 * grants, crash reports and anonymous usage events. Every query is scoped by tenant.
 */
class TeamStore(dbFile: File) {

    private val connection: Connection

    init {
        dbFile.parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS members (
                    tenant_id TEXT NOT NULL, user_id TEXT NOT NULL, email TEXT NOT NULL, name TEXT NOT NULL,
                    role TEXT NOT NULL, joined_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, user_id)
                )
                """.trimIndent()
            )
            st.execute("CREATE TABLE IF NOT EXISTS company (tenant_id TEXT PRIMARY KEY, data TEXT NOT NULL, updated_at INTEGER NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS plan_grants (tenant_id TEXT PRIMARY KEY, note TEXT NOT NULL, granted_at INTEGER NOT NULL)")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS crashes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, tenant_id TEXT NOT NULL, received_at INTEGER NOT NULL,
                    app_version TEXT NOT NULL, android TEXT NOT NULL, device TEXT NOT NULL, stack TEXT NOT NULL
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS events (
                    tenant_id TEXT NOT NULL, day TEXT NOT NULL, name TEXT NOT NULL, count INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, day, name)
                )
                """.trimIndent()
            )
        }
    }

    // region Members

    /** Registers or refreshes the caller; the first member of a company becomes ADMIN. */
    @Synchronized
    fun touch(caller: Caller, now: Long = System.currentTimeMillis()): Member {
        member(caller.tenantId, caller.userId)?.let { existing ->
            connection.prepareStatement(
                "UPDATE members SET email = ?, name = ?, last_seen_at = ? WHERE tenant_id = ? AND user_id = ?"
            ).use { ps ->
                ps.setString(1, caller.email); ps.setString(2, caller.name); ps.setLong(3, now)
                ps.setString(4, caller.tenantId); ps.setString(5, caller.userId); ps.executeUpdate()
            }
            return existing.copy(email = caller.email, name = caller.name, lastSeenAt = now)
        }
        val role = if (memberCount(caller.tenantId) == 0) Role.ADMIN else Role.TECHNICIAN
        connection.prepareStatement(
            "INSERT INTO members (tenant_id, user_id, email, name, role, joined_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, caller.tenantId); ps.setString(2, caller.userId); ps.setString(3, caller.email)
            ps.setString(4, caller.name); ps.setString(5, role.name); ps.setLong(6, now); ps.setLong(7, now)
            ps.executeUpdate()
        }
        return Member(caller.tenantId, caller.userId, caller.email, caller.name, role, now, now)
    }

    @Synchronized
    fun member(tenantId: String, userId: String): Member? =
        connection.prepareStatement("SELECT * FROM members WHERE tenant_id = ? AND user_id = ?").use { ps ->
            ps.setString(1, tenantId); ps.setString(2, userId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toMember() else null }
        }

    @Synchronized
    fun members(tenantId: String): List<Member> =
        connection.prepareStatement("SELECT * FROM members WHERE tenant_id = ? ORDER BY name COLLATE NOCASE").use { ps ->
            ps.setString(1, tenantId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toMember()) } }
        }

    @Synchronized
    fun memberCount(tenantId: String): Int =
        connection.prepareStatement("SELECT COUNT(*) FROM members WHERE tenant_id = ?").use { ps ->
            ps.setString(1, tenantId); ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    /** Changes a member's role. Refuses to remove the company's last admin. */
    @Synchronized
    fun setRole(tenantId: String, userId: String, role: Role): Boolean {
        val target = member(tenantId, userId) ?: return false
        if (target.role == Role.ADMIN && role != Role.ADMIN && members(tenantId).count { it.role == Role.ADMIN } <= 1) {
            throw IllegalArgumentException("A company needs at least one admin")
        }
        connection.prepareStatement("UPDATE members SET role = ? WHERE tenant_id = ? AND user_id = ?").use { ps ->
            ps.setString(1, role.name); ps.setString(2, tenantId); ps.setString(3, userId); ps.executeUpdate()
        }
        return true
    }

    @Synchronized
    fun removeMember(tenantId: String, userId: String) {
        connection.prepareStatement("DELETE FROM members WHERE tenant_id = ? AND user_id = ?").use { ps ->
            ps.setString(1, tenantId); ps.setString(2, userId); ps.executeUpdate()
        }
    }

    private fun java.sql.ResultSet.toMember() = Member(
        getString("tenant_id"), getString("user_id"), getString("email"), getString("name"),
        runCatching { Role.valueOf(getString("role")) }.getOrDefault(Role.TECHNICIAN),
        getLong("joined_at"), getLong("last_seen_at")
    )

    // endregion

    // region Company profile

    /** Fields an admin may set. Anything else in the request is ignored. */
    private val companyFields = listOf(
        "name", "address", "companyNumber", "phone", "email", "website",
        "emailRecipients", "emailSubjectTemplate", "emailBodyTemplate", "hasLogo"
    )

    @Synchronized
    fun company(tenantId: String): JSONObject =
        connection.prepareStatement("SELECT data, updated_at FROM company WHERE tenant_id = ?").use { ps ->
            ps.setString(1, tenantId)
            ps.executeQuery().use { rs ->
                if (rs.next()) JSONObject(rs.getString(1)).put("updatedAt", rs.getLong(2)) else JSONObject().put("updatedAt", 0)
            }
        }

    @Synchronized
    fun updateCompany(tenantId: String, changes: JSONObject, now: Long = System.currentTimeMillis()): JSONObject {
        val current = company(tenantId).apply { remove("updatedAt") }
        companyFields.forEach { key -> if (changes.has(key)) current.put(key, changes.get(key)) }
        connection.prepareStatement("INSERT OR REPLACE INTO company (tenant_id, data, updated_at) VALUES (?, ?, ?)").use { ps ->
            ps.setString(1, tenantId); ps.setString(2, current.toString()); ps.setLong(3, now); ps.executeUpdate()
        }
        return current.put("updatedAt", now)
    }

    // endregion

    // region Plans (the vendor can grant Pro for free)

    @Synchronized
    fun hasProGrant(tenantId: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM plan_grants WHERE tenant_id = ?").use { ps ->
            ps.setString(1, tenantId); ps.executeQuery().use { it.next() }
        }

    @Synchronized
    fun grantPro(tenantId: String, note: String) {
        connection.prepareStatement("INSERT OR REPLACE INTO plan_grants (tenant_id, note, granted_at) VALUES (?, ?, ?)").use { ps ->
            ps.setString(1, tenantId); ps.setString(2, note); ps.setLong(3, System.currentTimeMillis()); ps.executeUpdate()
        }
    }

    @Synchronized
    fun revokePro(tenantId: String) {
        connection.prepareStatement("DELETE FROM plan_grants WHERE tenant_id = ?").use { ps ->
            ps.setString(1, tenantId); ps.executeUpdate()
        }
    }

    @Synchronized
    fun grants(): List<Pair<String, String>> =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT tenant_id, note FROM plan_grants ORDER BY granted_at").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
            }
        }

    // endregion

    // region Crash reports and usage events (no personal data)

    @Synchronized
    fun addCrash(tenantId: String, report: JSONObject) {
        connection.prepareStatement(
            "INSERT INTO crashes (tenant_id, received_at, app_version, android, device, stack) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, tenantId); ps.setLong(2, System.currentTimeMillis())
            ps.setString(3, report.optString("appVersion").take(40))
            ps.setString(4, report.optString("android").take(20))
            ps.setString(5, report.optString("device").take(80))
            ps.setString(6, report.optString("stack").take(20_000))
            ps.executeUpdate()
        }
        // Keep the table bounded.
        connection.createStatement().use {
            it.execute("DELETE FROM crashes WHERE id NOT IN (SELECT id FROM crashes ORDER BY id DESC LIMIT 5000)")
        }
    }

    @Synchronized
    fun recentCrashes(limit: Int = 20): JSONArray =
        connection.prepareStatement("SELECT received_at, app_version, android, device, stack FROM crashes ORDER BY id DESC LIMIT ?").use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                JSONArray().apply {
                    while (rs.next()) put(
                        JSONObject().put("receivedAt", rs.getLong(1)).put("appVersion", rs.getString(2))
                            .put("android", rs.getString(3)).put("device", rs.getString(4)).put("stack", rs.getString(5))
                    )
                }
            }
        }

    /** Counts per event name per day: no user ids, no content. */
    @Synchronized
    fun addEvents(tenantId: String, day: String, names: List<String>) {
        names.filter { it.matches(Regex("^[a-z_]{1,40}$")) }.groupingBy { it }.eachCount().forEach { (name, count) ->
            connection.prepareStatement(
                "INSERT INTO events (tenant_id, day, name, count) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (tenant_id, day, name) DO UPDATE SET count = count + excluded.count"
            ).use { ps ->
                ps.setString(1, tenantId); ps.setString(2, day); ps.setString(3, name); ps.setInt(4, count); ps.executeUpdate()
            }
        }
    }

    @Synchronized
    fun eventCounts(tenantId: String): Map<String, Int> =
        connection.prepareStatement("SELECT name, SUM(count) FROM events WHERE tenant_id = ? GROUP BY name").use { ps ->
            ps.setString(1, tenantId)
            ps.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) } }
        }

    // endregion
}
