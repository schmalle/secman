package com.secman.mcp

import com.secman.controller.PersonalMcpKeyController
import com.secman.domain.McpApiKey
import com.secman.domain.User
import com.secman.repository.McpApiKeyRepository
import com.secman.repository.UserRepository
import com.secman.service.ApiKeyPair
import com.secman.service.McpAuthenticationService
import io.micronaut.security.authentication.Authentication
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class PersonalMcpKeyControllerTest {
    private val principal = Authentication.build("analyst")
    private val owner = mockk<User> {
        every { id } returns 42L
        every { email } returns "analyst@example.test"
        every { enabled } returns true
    }
    private val users = mockk<UserRepository> {
        every { findByUsername("analyst") } returns Optional.of(owner)
        every { findByUsernameForUpdate("analyst") } returns Optional.of(owner)
    }
    private val keys = mockk<McpApiKeyRepository>()
    private val auth = mockk<McpAuthenticationService>()
    private val controller = PersonalMcpKeyController(users, keys, auth)

    @Test
    fun `creation binds the owner and saves only the hash`() {
        val pair = ApiKeyPair("public-id", java.util.UUID.randomUUID().toString(), "test-hash")
        every { auth.generateApiKeyPair() } returns pair
        every { keys.findActiveByUserId(42L) } returns emptyList()
        val key = slot<McpApiKey>()
        every { keys.save(capture(key)) } answers { key.captured }
        val response = controller.create(principal)
        assertThat(response.code()).isEqualTo(201)
        assertThat(response.body()?.get("apiKey")).isEqualTo(pair.keySecret)
        assertThat(key.captured.keyHash).isEqualTo(pair.keyHash)
        assertThat(key.captured.permissions).isEqualTo("PERSONAL_INVENTORY_READ")
        assertThat(key.captured.permitsDelegate(42L)).isTrue()
        assertThat(key.captured.permitsDelegate(43L)).isFalse()
        assertThat(key.captured.isDelegationAllowedForEmail(owner.email)).isTrue()
    }

    @Test
    fun `another owner cannot revoke the key`() {
        val other = mockk<McpApiKey> { every { userId } returns 43L }
        every { keys.findByKeyIdAndActive("other") } returns Optional.of(other)
        assertThat(controller.revoke("other", principal).code()).isEqualTo(404)
        verify(exactly = 0) { keys.deactivateByKeyId(any()) }
    }
}
