package com.secman.service

import com.secman.domain.McpApiKey
import com.secman.repository.McpApiKeyRepository
import com.secman.repository.McpAuditLogRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

/** The verified-secret cache may skip BCrypt, never the live key-row checks. */
class McpAuthenticationServiceVerifiedKeyCacheTest {
    private val keys = mockk<McpApiKeyRepository>(relaxed = true)
    private val audit = mockk<McpAuditLogRepository>(relaxed = true)
    private val service = McpAuthenticationService(keys, audit)
    private val secret = "sk-" + "a".repeat(48)
    private val key = McpApiKey(
        id = 7L, keyId = "K".repeat(32), keyHash = service.hashApiKeySecret(secret),
        name = "test", userId = 3L, permissions = "ASSETS_READ", lastUsedAt = LocalDateTime.now()
    )

    @Test
    fun `second call with the same secret skips the scan of all active keys`() {
        every { keys.findAllActive() } returns listOf(key)
        every { keys.findById(7L) } returns Optional.of(key)

        assertTrue(service.authenticateApiKey(secret).success)
        assertTrue(service.authenticateApiKey(secret).success)

        verify(exactly = 1) { keys.findAllActive() }
    }

    @Test
    fun `revoked key is refused even when its secret was verified before`() {
        every { keys.findAllActive() } returns listOf(key)
        every { keys.findById(7L) } returns Optional.of(key)
        assertTrue(service.authenticateApiKey(secret).success)

        key.isActive = false
        every { keys.findAllActive() } returns emptyList()

        assertFalse(service.authenticateApiKey(secret).success)
    }

    @Test
    fun `rotated hash invalidates the remembered match`() {
        every { keys.findAllActive() } returns listOf(key)
        every { keys.findById(7L) } returns Optional.of(key)
        assertTrue(service.authenticateApiKey(secret).success)

        val rotated = key.copy(keyHash = service.hashApiKeySecret("sk-" + "b".repeat(48)))
        every { keys.findById(7L) } returns Optional.of(rotated)
        every { keys.findAllActive() } returns listOf(rotated)

        assertFalse(service.authenticateApiKey(secret).success)
    }

    @Test
    fun `recent lastUsedAt is not rewritten on every call`() {
        every { keys.findAllActive() } returns listOf(key)
        every { keys.findById(7L) } returns Optional.of(key)

        repeat(3) { service.authenticateApiKey(secret) }

        verify(exactly = 0) { keys.updateLastUsedAt(any(), any()) }
    }
}
