package com.secman.service.aibuddy

import com.secman.domain.User
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.security.BetaFeatureAccess
import com.secman.security.isBetaEligible
import com.secman.service.AssetFilterService
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional

class AiBuddyAccessTest {
    private val users = mockk<UserRepository>()
    private val assets = mockk<AssetRepository>()
    private val filter = mockk<AssetFilterService>()
    private val beta = BetaFeatureAccess(users)
    private val guard = AiBuddyAccessGuard(beta, assets, filter)
    private fun user(roles: Set<User.Role>) = User(username = "viewer", email = "viewer@test.invalid", passwordHash = "unused", roles = roles.toMutableSet()).apply { id = 7 }
    private val auth = Authentication.build("viewer", listOf("ADMIN"), mapOf("userId" to "7", "email" to "old@test.invalid"))

    @Test fun `BETA is additive and only beta admin and champion are eligible`() {
        for (role in User.Role.entries) assertThat(setOf(role).isBetaEligible()).isEqualTo(role in setOf(User.Role.BETA, User.Role.ADMIN, User.Role.SECCHAMPION))
        val ordinary = user(setOf(User.Role.USER))
        every { users.findById(7) } returns Optional.of(ordinary)
        assertThat(assertThrows<HttpStatusException> { guard.check(2, auth) }.status).isEqualTo(HttpStatus.FORBIDDEN)
        verify(exactly = 0) { filter.canAccessAsset(any(), any()) }
    }

    @Test fun `fresh roles replace stale admin claims before object authorization`() {
        every { users.findById(7) } returns Optional.of(user(setOf(User.Role.USER, User.Role.BETA)))
        val current = slot<Authentication>()
        every { filter.canAccessAsset(2, capture(current)) } returns false
        assertThat(assertThrows<HttpStatusException> { guard.check(2, auth) }.status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(current.captured.roles).contains("BETA").doesNotContain("ADMIN")
        assertThat(current.captured.attributes["email"]).isEqualTo("viewer@test.invalid")
        verify(exactly = 0) { assets.existsById(any()) }
    }

    @Test fun `role addition in an old session works and revocation takes effect immediately`() {
        val oldAuth = Authentication.build("viewer", listOf("USER"), mapOf("userId" to "7"))
        every { users.findById(7) } returns Optional.of(user(setOf(User.Role.USER, User.Role.BETA)))
        every { filter.canAccessAsset(2, any()) } returns true
        every { assets.existsById(2) } returns true
        assertThat(guard.check(2, oldAuth).roles).contains(User.Role.BETA)
        every { users.findById(7) } returns Optional.of(user(setOf(User.Role.USER)))
        assertThat(assertThrows<HttpStatusException> { guard.check(2, oldAuth) }.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test fun `disabled users absent identity and mismatched principal are unauthenticated`() {
        every { users.findById(7) } returns Optional.of(user(setOf(User.Role.BETA)).apply { enabled = false })
        assertThat(assertThrows<HttpStatusException> { beta.current(auth) }.status).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThrows<HttpStatusException> { beta.current(Authentication.build("viewer")) }
        every { users.findById(7) } returns Optional.of(user(setOf(User.Role.BETA)))
        assertThrows<HttpStatusException> { beta.current(Authentication.build("other", listOf("ADMIN"), mapOf("userId" to "7"))) }
    }
}
