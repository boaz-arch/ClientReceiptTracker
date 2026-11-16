package com.company.supportvisit.server

import java.io.File

/** Server settings, all from environment variables (see deploy/.env.example). */
data class Config(
    val port: Int,
    val dataDir: File,
    /** Application (client) ID of the Azure app registration; tokens must be issued for it. */
    val clientId: String,
    /** If not empty, only these Microsoft tenant IDs may use the server. */
    val allowedTenants: Set<String>,
    /** Public base URL, e.g. https://apps.livnelavan.com/supportvisit (used in file links). */
    val publicBaseUrl: String,
    /** Secret for signing temporary file links. */
    val linkSecret: String,
    val maxUploadBytes: Long = 25L * 1024 * 1024,
    /** How long a deleted account can still be restored. */
    val deletionGraceMs: Long = 14L * 24 * 60 * 60 * 1000,
    val purgeIntervalMs: Long = 60L * 60 * 1000,
    /** Optional: tenants given Pro for free via the environment (in addition to `grant-pro`). */
    val proTenants: Set<String> = emptySet(),
    /** Emails that become their company's first admin (in addition to `invite-admin`). */
    val adminEmails: Set<String> = emptySet()
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun required(name: String) = env[name]?.takeIf { it.isNotBlank() }
                ?: error("Environment variable $name is required")
            return Config(
                port = env["PORT"]?.toIntOrNull() ?: 8090,
                dataDir = File(env["DATA_DIR"] ?: "/data"),
                clientId = required("AZURE_CLIENT_ID"),
                allowedTenants = env["ALLOWED_TENANT_IDS"].orEmpty()
                    .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
                publicBaseUrl = required("PUBLIC_BASE_URL").trimEnd('/'),
                adminEmails = env["ADMIN_EMAILS"].orEmpty()
                    .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
                proTenants = env["PRO_TENANT_IDS"].orEmpty()
                    .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
                linkSecret = required("LINK_SECRET").also {
                    require(it.length >= 32) { "LINK_SECRET must be at least 32 characters" }
                }
            )
        }
    }
}
