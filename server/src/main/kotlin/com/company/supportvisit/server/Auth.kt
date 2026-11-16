package com.company.supportvisit.server

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/** Who is calling: their Microsoft tenant (= company) and identity. */
data class Caller(
    val tenantId: String,
    val userId: String,
    val email: String,
    val name: String,
    /** Entra directory role template ids of the user (the "wids" claim). */
    val directoryRoles: Set<String> = emptySet()
) {
    /** Global Administrator of their Microsoft 365 tenant. */
    val isTenantAdmin: Boolean get() = GLOBAL_ADMIN_ROLE in directoryRoles

    companion object {
        const val GLOBAL_ADMIN_ROLE = "62e90394-69f5-4237-9190-012177145e10"
    }
}

class AuthException(message: String) : Exception(message)

/** The checks on a token's claims, separated from signature checking so they can be unit tested. */
object TokenRules {

    const val REQUIRED_SCOPE = "access_as_user"

    private val V2_ISSUER = Regex("""^https://login\.microsoftonline\.com/([0-9a-f-]{36})/v2\.0$""")
    private val V1_ISSUER = Regex("""^https://sts\.windows\.net/([0-9a-f-]{36})/$""")

    fun check(jwt: DecodedJWT, clientId: String, allowedTenants: Set<String>, nowMs: Long = System.currentTimeMillis()): Caller {
        val audiences = jwt.audience.orEmpty()
        if (audiences.none { it == clientId || it == "api://$clientId" }) throw AuthException("Token is not for this app")

        val expiresAt = jwt.expiresAt ?: throw AuthException("Token has no expiry")
        // 2 minutes of clock skew.
        if (expiresAt.time + 120_000 < nowMs) throw AuthException("Token expired")

        val tid = jwt.getClaim("tid").asString()?.lowercase() ?: throw AuthException("Token has no tenant")
        val issuer = jwt.issuer.orEmpty()
        val issuerTenant = (V2_ISSUER.find(issuer) ?: V1_ISSUER.find(issuer))?.groupValues?.get(1)?.lowercase()
        if (issuerTenant != tid) throw AuthException("Unexpected token issuer")

        if (allowedTenants.isNotEmpty() && tid !in allowedTenants) throw AuthException("Company not allowed on this server")

        val scopes = jwt.getClaim("scp").asString().orEmpty().split(' ')
        if (REQUIRED_SCOPE !in scopes) throw AuthException("Token is missing the $REQUIRED_SCOPE scope")

        val oid = jwt.getClaim("oid").asString() ?: throw AuthException("Token has no user id")
        val email = listOf("preferred_username", "upn", "email", "unique_name")
            .firstNotNullOfOrNull { jwt.getClaim(it).asString()?.takeIf { v -> v.contains('@') } }
            .orEmpty()
        val wids = jwt.getClaim("wids").asList(String::class.java).orEmpty().map { it.lowercase() }.toSet()
        return Caller(tid, oid, email.lowercase(), jwt.getClaim("name").asString().orEmpty(), wids)
    }
}

/** Verifies Microsoft Entra ID access tokens issued for this app's API scope. */
class EntraTokenVerifier(private val config: Config) {

    private val keys: JwkProvider = JwkProviderBuilder(
        URI("https://login.microsoftonline.com/common/discovery/v2.0/keys").toURL()
    )
        .cached(20, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    fun verify(bearer: String): Caller {
        val unverified = try {
            JWT.decode(bearer)
        } catch (e: Exception) {
            throw AuthException("Malformed token")
        }
        val keyId = unverified.keyId ?: throw AuthException("Token has no key id")
        val publicKey = try {
            keys.get(keyId).publicKey as RSAPublicKey
        } catch (e: Exception) {
            throw AuthException("Unknown signing key")
        }
        val verified = try {
            JWT.require(Algorithm.RSA256(publicKey, null)).acceptLeeway(120).build().verify(bearer)
        } catch (e: Exception) {
            throw AuthException("Invalid token signature")
        }
        return TokenRules.check(verified, config.clientId, config.allowedTenants)
    }
}
