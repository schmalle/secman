package com.secman.security

import com.secman.domain.User
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.micronaut.security.token.generator.TokenGenerator
import java.util.Optional

/** Every login path issues through [SecmanTokenIssuer], so its claim set is the session contract. */
class SecmanTokenIssuerTest {
    private val generator = mockk<TokenGenerator>()
    private val issuer = SecmanTokenIssuer(generator)
    private val user = User(
        id = 17L, username = "alice", email = "alice@example.test", passwordHash = "x",
        roles = mutableSetOf(User.Role.USER, User.Role.VULN)
    )

    @Test
    fun `issues exactly the claim set every login path relied on`() {
        val claims = slot<Map<String, Any>>()
        every { generator.generateToken(capture(claims)) } returns Optional.of("jwt")

        assertThat(issuer.issue(user)).contains("jwt")
        assertThat(claims.captured).containsOnlyKeys("sub", "username", "email", "roles", "iss", "userId")
        assertThat(claims.captured["sub"]).isEqualTo("alice")
        assertThat(claims.captured["username"]).isEqualTo("alice")
        assertThat(claims.captured["email"]).isEqualTo("alice@example.test")
        assertThat(claims.captured["iss"]).isEqualTo("secman-backend-ng")
        // A string, not a number: readers parse attributes["userId"] with toString().toLong().
        assertThat(claims.captured["userId"]).isEqualTo("17")
        @Suppress("UNCHECKED_CAST")
        assertThat(claims.captured["roles"] as List<String>).containsExactlyInAnyOrder("USER", "VULN")
    }

    @Test
    fun `passes a generator refusal through as empty`() {
        every { generator.generateToken(any<Map<String, Any>>()) } returns Optional.empty()

        assertThat(issuer.issue(user)).isEmpty()
    }
}
