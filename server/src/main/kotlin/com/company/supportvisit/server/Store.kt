package com.company.supportvisit.server

import org.json.JSONObject
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** A stored record: the app's JSON plus the server's change timestamp. */
data class Record(val id: String, val data: JSONObject, val serverUpdatedAt: Long)

/**
 * SQLite storage. Every row belongs to one tenant (company); queries always filter by tenant,
 * so companies never see each other's data.
 */
class Store(dbFile: File) {

    companion object {
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

    enum class Kind(val table: String) { VISITS("visits"), CLIENTS("clients") }

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
                st.execute(
                    "CREATE INDEX IF NOT EXISTS idx_${kind.table}_changes ON ${kind.table} (tenant_id, server_updated_at)"
                )
            }
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
    fun upsert(kind: Kind, tenantId: String, id: String, data: JSONObject, updatedBy: String): Long {
        val stamp = nextStamp()
        connection.prepareStatement(
            """
            INSERT INTO ${kind.table} (tenant_id, id, data, updated_by, server_updated_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, id) DO UPDATE SET
                data = excluded.data, updated_by = excluded.updated_by, server_updated_at = excluded.server_updated_at
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, tenantId)
            ps.setString(2, id)
            ps.setString(3, data.toString())
            ps.setString(4, updatedBy)
            ps.setLong(5, stamp)
            ps.executeUpdate()
        }
        return stamp
    }

    @Synchronized
    fun changesSince(kind: Kind, tenantId: String, since: Long, limit: Int = 500): List<Record> =
        connection.prepareStatement(
            "SELECT id, data, server_updated_at FROM ${kind.table} " +
                "WHERE tenant_id = ? AND server_updated_at > ? ORDER BY server_updated_at LIMIT ?"
        ).use { ps ->
            ps.setString(1, tenantId)
            ps.setLong(2, since)
            ps.setInt(3, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(Record(rs.getString(1), JSONObject(rs.getString(2)), rs.getLong(3)))
                }
            }
        }

    @Synchronized
    fun exists(kind: Kind, tenantId: String, id: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM ${kind.table} WHERE tenant_id = ? AND id = ?").use { ps ->
            ps.setString(1, tenantId)
            ps.setString(2, id)
            ps.executeQuery().use { it.next() }
        }
}
