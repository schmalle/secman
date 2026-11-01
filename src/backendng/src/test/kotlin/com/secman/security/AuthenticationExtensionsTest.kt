package com.secman.security

import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuthenticationExtensionsTest {

    private fun authWithRoles(vararg roles: String): Authentication {
        val auth = mockk<Authentication>()
        every { auth.roles } returns roles.toList()
        return auth
    }

    @Test
    fun `hasRole matches exact role membership only`() {
        val auth = authWithRoles("VULN", "USER")
        assertThat(auth.hasRole("VULN")).isTrue()
        assertThat(auth.hasRole("ADMIN")).isFalse()
        assertThat(auth.hasRole("vuln")).isFalse()
    }

    @Test
    fun `hasAnyRole is true when at least one candidate matches`() {
        val auth = authWithRoles("SECCHAMPION")
        assertThat(auth.hasAnyRole("ADMIN", "SECCHAMPION")).isTrue()
        assertThat(auth.hasAnyRole("ADMIN", "VULN")).isFalse()
        assertThat(auth.hasAnyRole()).isFalse()
    }

    @Test
    fun `isAdmin is true only for the ADMIN role`() {
        assertThat(authWithRoles("ADMIN", "USER").isAdmin()).isTrue()
        assertThat(authWithRoles("SECCHAMPION").isAdmin()).isFalse()
        assertThat(authWithRoles().isAdmin()).isFalse()
    }

    private fun authWithUserId(value: Any?): Authentication =
        Authentication.build("reader", listOf("USER"), if (value == null) emptyMap() else mapOf("userId" to value))

    @Test
    fun `requireUserId accepts the string claim the issuer writes and numeric attributes`() {
        assertThat(authWithUserId("17").requireUserId()).isEqualTo(17L)
        assertThat(authWithUserId(17L).requireUserId()).isEqualTo(17L)
        assertThat(authWithUserId(17).requireUserId()).isEqualTo(17L)
    }

    @Test
    fun `requireUserId fails loudly on a missing or malformed claim`() {
        org.assertj.core.api.Assertions.assertThatThrownBy { authWithUserId(null).requireUserId() }
            .isInstanceOf(IllegalStateException::class.java)
        org.assertj.core.api.Assertions.assertThatThrownBy { authWithUserId("not-a-number").requireUserId() }
            .isInstanceOf(NumberFormatException::class.java)
    }
}
