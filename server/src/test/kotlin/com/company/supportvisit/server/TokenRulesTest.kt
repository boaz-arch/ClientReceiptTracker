package com.company.supportvisit.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TokenRulesTest {

    private val clientId = "11111111-2222-3333-4444-555555555555"
    private val tenant = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val now = System.currentTimeMillis()

    private fun token(
        audience: String = "api://$clientId",
        tid: String = tenant,
        issuer: String = "https://sts.windows.net/$tenant/",
        scp: String = "access_as_user",
        expires: Long = now + 3_600_000
    ) = JWT.decode(
        JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim("tid", tid)
            .withClaim("oid", "user-1")
            .withClaim("scp", scp)
            .withClaim("upn", "Tech@Example.com")
            .withExpiresAt(Date(expires))
            .sign(Algorithm.HMAC256("test"))
    )

    @Test
    fun `valid v1 token gives the caller`() {
        val caller = TokenRules.check(token(), clientId, emptySet(), now)
        assertEquals(tenant, caller.tenantId)
        assertEquals("tech@example.com", caller.email)
    }

    @Test
    fun `v2 issuer and plain client id audience are accepted`() {
        TokenRules.check(
            token(audience = clientId, issuer = "https://login.microsoftonline.com/$tenant/v2.0"),
            clientId, emptySet(), now
        )
    }

    @Test
    fun `wrong audience is rejected`() {
        assertFailsWith<AuthException> { TokenRules.check(token(audience = "https://graph.microsoft.com"), clientId, emptySet(), now) }
    }

    @Test
    fun `issuer from another tenant is rejected`() {
        val other = "ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee"
        assertFailsWith<AuthException> {
            TokenRules.check(token(issuer = "https://sts.windows.net/$other/"), clientId, emptySet(), now)
        }
    }

    @Test
    fun `expired token is rejected`() {
        assertFailsWith<AuthException> { TokenRules.check(token(expires = now - 600_000), clientId, emptySet(), now) }
    }

    @Test
    fun `missing scope is rejected`() {
        assertFailsWith<AuthException> { TokenRules.check(token(scp = "User.Read"), clientId, emptySet(), now) }
    }

    @Test
    fun `tenant allow-list is enforced`() {
        assertFailsWith<AuthException> {
            TokenRules.check(token(), clientId, setOf("99999999-bbbb-cccc-dddd-eeeeeeeeeeee"), now)
        }
        TokenRules.check(token(), clientId, setOf(tenant), now)
    }
}
