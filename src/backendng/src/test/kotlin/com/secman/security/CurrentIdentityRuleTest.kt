package com.secman.security

import com.secman.domain.User
import com.secman.repository.UserRepository
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRuleResult
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.util.Optional

/** Checks fresh identity and fail-closed behavior before endpoint role rules. */
class CurrentIdentityRuleTest {
    private val users = mockk<UserRepository>()
    private val rule = CurrentIdentityRule(users)
    private val user = User(id = 1L, username = "alice", email = "alice@example.test", passwordHash = "unused")
    private fun evaluateIdentity(roles: List<String> = listOf("USER")): SecurityRuleResult? = Mono.from(rule.check(null,
        Authentication.build("alice", roles, mapOf("userId" to "1", "email" to user.email)))).block()

    @Test
    fun `current identity leaves resource authorization to subsequent rules`() {
        every { users.findById(1L) } returns Optional.of(user)
        assertEquals(SecurityRuleResult.UNKNOWN, evaluateIdentity())
    }

    @Test
    fun `deleted identity and stale privilege are rejected`() {
        every { users.findById(1L) } returns Optional.empty()
        assertEquals(SecurityRuleResult.REJECTED, evaluateIdentity())
        every { users.findById(1L) } returns Optional.of(user)
        assertEquals(SecurityRuleResult.REJECTED, evaluateIdentity(listOf("ADMIN")))
    }

    @Test
    fun `unavailable identity store fails closed`() {
        every { users.findById(1L) } throws IllegalStateException("unavailable")
        assertEquals(SecurityRuleResult.REJECTED, evaluateIdentity())
    }
    @Test
    fun `suspended user cannot reuse a valid signed identity`() {
        user.enabled = false
        every { users.findById(1L) } returns Optional.of(user)
        assertEquals(SecurityRuleResult.REJECTED, evaluateIdentity())
    }

}
