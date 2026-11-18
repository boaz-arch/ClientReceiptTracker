package com.company.supportvisit.server

import org.json.JSONObject
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** A stored record: the app's JSON plus the server's change timestamp. */
/** A stored record: the app's JSON, the server's change timestamp and who last wrote it. */
data class Record(val id: String, val data: JSONObject, val serverUpdatedAt: Long, val updatedBy: String = "") {
    /** True only for tombstones written by the server itself (never by a phone). */
    val isServerTombstone: Boolean get() = updatedBy == Store.SYSTEM_DELETION && data.optBoolean("deleted")
}

/**
 * SQLite storage. Every row belongs to one tenant (company); queries always filter by tenant,
 * so companies never see each other's data.
 */
class Store(dbFile: File) {

    companion object {
        const val SYSTEM_DELETION = "system:deletion"

        /** Consistent copy of the live database (safe while the server runs). */
        fun backup(source: File, target: File) {
            target.parentFile?.mkdirs()
            target.delete()
            DriverManager.getConnection("jdbc:sqlite:${source.absolutePath}").use { c ->
                c.prepareStatement("VACUUM INTO ?").use { it.setString(1, target.absolutePath); it.execute() }
            }
        }

        /** True when SQLite's integrity check passes. */
        fun check(file: File): Boolean {
            if (!file.exists()) return false
            return DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("PRAGMA integrity_check").use { rs -> rs.next() && rs.getString(1) == "ok" }
                }
            }
        }
    }

    enum class Kind(val table: String) { VISITS("visits"), CLIENTS("clients"), TASKS("tasks") }

    private val connection: Connection

    @Volatile
    private var lastStamp = 0L

    init {
        dbFile.parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA busy_timeout=5000")
            Kind.entries.forEach { kind ->
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${kind.table} (
                        tenant_id TEXT NOT NULL,
                        id TEXT NOT NULL,
                        data TEXT NOT NULL,
                        updated_by TEXT NOT NULL,
                        server_updated_at INTEGER NOT NULL,
                        PRIMARY KEY (tenant_id, id)
                    )
                    """.trimIndent()
                )
                // Who first stored the record (Microsoft user id). Set once, never updated.
                val hasCreator = st.executeQuery("PRAGMA table_info(${kind.table})").use { rs ->
                    var found = false
                    while (rs.next()) if (rs.getString("name") == "created_by") found = true
                    found
                }
                if (!hasCreator) st.execute("ALTER TABLE ${kind.table} ADD COLUMN created_by TEXT")
                st.execute(
                    "CREATE INDEX IF NOT EXISTS idx_${kind.table}_changes ON ${kind.table} (tenant_id, server_updated_at)"
                )
            }
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS deletion_requests (
                    tenant_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    email TEXT NOT NULL,
                    keep_shared INTEGER NOT NULL,
                    requested_at INTEGER NOT NULL,
                    purge_at INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, user_id)
                )
                """.trimIndent()
            )
            st.executeQuery("SELECT MAX(m) FROM (SELECT MAX(server_updated_at) m FROM visits UNION ALL SELECT MAX(server_updated_at) FROM clients)")
                .use { rs -> if (rs.next()) lastStamp = rs.getLong(1) }
        }
    }

    /** Strictly increasing timestamps, so "changes since X" never misses or repeats a row. */
    @Synchronized
    private fun nextStamp(): Long {
        lastStamp = maxOf(System.currentTimeMillis(), lastStamp + 1)
        return lastStamp
    }

    @Synchronized
    fun upsert(kind: Kind, tenantId: String, id: String, data: JSONObject, updatedBy: String, createdBy: String? = null): Long {
        val stamp = nextStamp()
        connection.prepareStatement(
            """
            INSERT INTO ${kind.table} (tenant_id, id, data, updated_by, server_updated_at, created_by) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, id) DO UPDATE SET
                data = excluded.data, updated_by = excluded.updated_by, server_updated_at = excluded.server_updated_at
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, tenantId)
            ps.setString(2, id)
            ps.setString(3, data.toString())
            ps.setString(4, updatedBy)
            ps.setLong(5, stamp)
            ps.setString(6, createdBy)
            ps.executeUpdate()
        }
        return stamp
    }

    /**
     * Restricts what a technician receives ([onlyFor] = their user id): their own visits, and
     * tasks they created or were assigned. Clients are shared with the whole company.
     */
    private fun visibilityClause(kind: Kind, onlyFor: String?): String = when {
        onlyFor == null || kind == Kind.CLIENTS -> ""
        kind == Kind.VISITS -> "AND created_by = ? "
        else -> "AND (created_by = ? OR json_extract(data, '$.assigneeId') = ?) "
    }

    /** The user id a task is assigned to, or null. */
    @Synchronized
    fun taskAssignee(tenantId: String, id: String): String? =
        connection.prepareStatement("SELECT json_extract(data, '$.assigneeId') FROM tasks WHERE tenant_id = ? AND id = ?").use { ps ->
            ps.setString(1, tenantId); ps.setString(2, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    /** Ownership of an existing record: who created it (null for rows that predate tracking) and who last wrote it. */
    data class Ownership(val createdBy: String?, val updatedBy: String)

    /** The stored JSON of one record, or null. */
    @Synchronized
    fun dataOf(kind: Kind, tenantId: String, id: String): JSONObject? =
        connection.prepareStatement("SELECT data FROM ${kind.table} WHERE tenant_id = ? AND id = ?").use { ps ->
            ps.setString(1, tenantId); ps.setString(2, id)
            ps.executeQuery().use { rs -> if (rs.next()) JSONObject(rs.getString(1)) else null }
        }

    /** Null when the record doesn't exist. */
    @Synchronized
    fun ownershipOf(kind: Kind, tenantId: String, id: String): Ownership? =
        connection.prepareStatement("SELECT created_by, updated_by FROM ${kind.table} WHERE tenant_id = ? AND id = ?").use { ps ->
            ps.setString(1, tenantId); ps.setString(2, id)
            ps.executeQuery().use { rs -> if (rs.next()) Ownership(rs.getString(1), rs.getString(2)) else null }
        }

    @Synchronized
    fun changesSince(kind: Kind, tenantId: String, since: Long, limit: Int = 500, onlyFor: String? = null): List<Record> =
        connection.prepareStatement(
            "SELECT id, data, server_updated_at, updated_by FROM ${kind.table} " +
                "WHERE tenant_id = ? AND server_updated_at > ? " + visibilityClause(kind, onlyFor) +
                "ORDER BY server_updated_at LIMIT ?"
        ).use { ps ->
            var i = 1
            ps.setString(i++, tenantId)
            ps.setLong(i++, since)
            if (onlyFor != null && kind != Kind.CLIENTS) {
                ps.setString(i++, onlyFor)
                if (kind == Kind.TASKS) ps.setString(i++, onlyFor)
            }
            ps.setInt(i, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(Record(rs.getString(1), JSONObject(rs.getString(2)), rs.getLong(3), rs.getString(4)))
                }
            }
        }

    // region Account deletion

    data class DeletionRequest(
        val tenantId: String,
        val userId: String,
        val email: String,
        val keepShared: Boolean,
        val requestedAt: Long,
        val purgeAt: Long
    )

    @Synchronized
    fun requestDeletion(r: DeletionRequest) {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO deletion_requests (tenant_id, user_id, email, keep_shared, requested_at, purge_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, r.tenantId); ps.setString(2, r.userId); ps.setString(3, r.email)
            ps.setInt(4, if (r.keepShared) 1 else 0); ps.setLong(5, r.requestedAt); ps.setLong(6, r.purgeAt)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun deletionRequest(tenantId: String, userId: String): DeletionRequest? =
        connection.prepareStatement(
            "SELECT tenant_id, user_id, email, keep_shared, requested_at, purge_at FROM deletion_requests " +
                "WHERE tenant_id = ? AND user_id = ?"
        ).use { ps ->
            ps.setString(1, tenantId); ps.setString(2, userId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toDeletion() else null }
        }

    @Synchronized
    fun cancelDeletion(tenantId: String, userId: String): Boolean =
        connection.prepareStatement("DELETE FROM deletion_requests WHERE tenant_id = ? AND user_id = ?").use { ps ->
            ps.setString(1, tenantId); ps.setString(2, userId)
            ps.executeUpdate() > 0
        }

    @Synchronized
    fun dueDeletions(now: Long): List<DeletionRequest> =
        connection.prepareStatement(
            "SELECT tenant_id, user_id, email, keep_shared, requested_at, purge_at FROM deletion_requests WHERE purge_at <= ?"
        ).use { ps ->
            ps.setLong(1, now)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toDeletion()) } }
        }

    /**
     * Ids of the visits a user created, by the immutable creator id the server recorded when each
     * visit was first stored (never by editable fields such as technicianEmail).
     */
    @Synchronized
    fun visitIdsCreatedBy(tenantId: String, userId: String): List<String> =
        connection.prepareStatement(
            // Only by the immutable creator id; rows without one are never purged automatically.
            "SELECT id FROM visits WHERE tenant_id = ? AND updated_by != ? AND created_by = ?"
        ).use { ps ->
            ps.setString(1, tenantId); ps.setString(2, SYSTEM_DELETION); ps.setString(3, userId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    /**
     * Replaces a record with a tombstone ({"id", "deleted": true}) so every phone that pulls
     * changes removes its copy; the record's content is gone from the server.
     */
    fun tombstone(kind: Kind, tenantId: String, id: String, keep: Map<String, Any?> = emptyMap()) {
        val data = JSONObject().put("id", id).put("deleted", true)
        keep.forEach { (k, v) -> if (v != null) data.put(k, v) }
        upsert(kind, tenantId, id, data, SYSTEM_DELETION)
    }

    private fun java.sql.ResultSet.toDeletion() = DeletionRequest(
        tenantId = getString(1), userId = getString(2), email = getString(3), keepShared = getInt(4) == 1,
        requestedAt = getLong(5), purgeAt = getLong(6)
    )

    // endregion

    @Synchronized
    fun exists(kind: Kind, tenantId: String, id: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM ${kind.table} WHERE tenant_id = ? AND id = ?").use { ps ->
            ps.setString(1, tenantId)
            ps.setString(2, id)
            ps.executeQuery().use { it.next() }
        }
}
