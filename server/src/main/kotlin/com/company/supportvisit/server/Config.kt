package com.company.supportvisit.server

import java.io.File

/** Outgoing email (sign-in codes, invitations, visit reports). */
data class SmtpConfig(
    val host: String,
    val port: Int = 587,
    val username: String = "",
    val password: String = "",
    /** The one address every email comes from, e.g. reports@example.com. */
    val from: String,
    /** STARTTLS on 587 (default); set SMTP_TLS=ssl for implicit TLS on 465. */
    val ssl: Boolean = false
)

/** Server settings, all from environment variables (see deploy/.env.example). */
data class Config(
    val port: Int,
    val dataDir: File,
    /** Public base URL, e.g. https://apps.example.com/supportvisit (used in file links). */
    val publicBaseUrl: String,
    /** Secret for signing temporary file links and hashing sign-in codes and tokens. */
    val linkSecret: String,
    /** Null = email not set up: sign-in codes can't be sent. */
    val smtp: SmtpConfig? = null,
    /** Product name used in emails. */
    val appName: String = "Support Visit",
    val maxUploadBytes: Long = 25L * 1024 * 1024,
    /** How long a deleted account can still be restored. */
    val deletionGraceMs: Long = 14L * 24 * 60 * 60 * 1000,
    val purgeIntervalMs: Long = 60L * 60 * 1000,
    /** Optional: companies given Pro for free via the environment (in addition to `grant-pro`). */
    val proTenants: Set<String> = emptySet(),
    /** Visit report emails per company per day (protects the sending address from abuse). */
    val mailDailyLimit: Int = 200,
    /** The same for a company's first 7 days, unless it has Pro. */
    val newCompanyMailDailyLimit: Int = 20,
    /** Report emails per day for the whole server. */
    val mailGlobalDailyLimit: Int = 2000,
    /** Optional fixed sign-in for app-store reviewers (no email is sent). Null = off. */
    val reviewLogin: ReviewLogin? = null,
    /**
     * Proxy addresses whose X-Forwarded-For header is believed (loopback always is), e.g. the
     * Docker bridge gateway 172.17.0.1 when the server runs in a container behind Apache.
     */
    val trustedProxies: Set<String> = emptySet()
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun required(name: String) = env[name]?.takeIf { it.isNotBlank() }
                ?: error("Environment variable $name is required")
            fun list(name: String) = env[name].orEmpty()
                .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            val smtp = env["SMTP_HOST"]?.takeIf { it.isNotBlank() }?.let { host ->
                SmtpConfig(
                    host = host,
                    port = env["SMTP_PORT"]?.toIntOrNull() ?: 587,
                    username = env["SMTP_USER"].orEmpty(),
                    password = env["SMTP_PASSWORD"].orEmpty(),
                    from = required("SMTP_FROM"),
                    ssl = env["SMTP_TLS"].equals("ssl", ignoreCase = true)
                )
            }
            return Config(
                port = env["PORT"]?.toIntOrNull() ?: 8090,
                dataDir = File(env["DATA_DIR"] ?: "/data"),
                publicBaseUrl = required("PUBLIC_BASE_URL").trimEnd('/'),
                linkSecret = required("LINK_SECRET").also {
                    require(it.length >= 32) { "LINK_SECRET must be at least 32 characters" }
                },
                smtp = smtp,
                appName = env["APP_NAME"]?.takeIf { it.isNotBlank() } ?: "Support Visit",
                proTenants = list("PRO_TENANT_IDS"),
                mailDailyLimit = env["MAIL_DAILY_LIMIT"]?.toIntOrNull() ?: 200,
                newCompanyMailDailyLimit = env["MAIL_NEW_COMPANY_DAILY_LIMIT"]?.toIntOrNull() ?: 20,
                mailGlobalDailyLimit = env["MAIL_GLOBAL_DAILY_LIMIT"]?.toIntOrNull() ?: 2000,
                reviewLogin = ReviewLogin.fromEnv(env["REVIEW_LOGIN_EMAIL"], env["REVIEW_LOGIN_CODE"]),
                trustedProxies = list("TRUSTED_PROXIES")
            )
        }
    }
}


/**
 * A fixed email + 6-digit code so Google Play reviewers can sign in without a mailbox.
 * Turn it on only while a review is pending. It locks itself after [MAX_FAILURES] wrong codes.
 */
data class ReviewLogin(val email: String, val code: String) {
    companion object {
        const val MAX_FAILURES = 20

        fun fromEnv(email: String?, code: String?): ReviewLogin? {
            if (email.isNullOrBlank() || code.isNullOrBlank()) return null
            require(code.matches(Regex("^\\d{6}$"))) { "REVIEW_LOGIN_CODE must be 6 digits" }
            return ReviewLogin(AccountStore.normalizeEmail(email), code)
        }
    }
}
