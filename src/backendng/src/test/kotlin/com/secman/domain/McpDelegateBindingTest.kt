package com.secman.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Pins self-delegation and explicit additional identities. */
class McpDelegateBindingTest {
    @Test
    fun `domain eligibility never substitutes for explicit delegate binding`() {
        val key = McpApiKey(keyId = "test-key-identifier", keyHash = "unused", name = "fixture",
            userId = 1L, permissions = "ASSETS_READ", delegationEnabled = true,
            allowedDelegationDomains = "@example.test", allowedDelegateUserIds = "2,3")
        assertTrue(key.permitsDelegate(1L))
        assertTrue(key.permitsDelegate(2L))
        assertFalse(key.permitsDelegate(4L))
        assertFalse(key.copy(allowedDelegateUserIds = "").permitsDelegate(2L))
    }
}
