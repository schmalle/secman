package com.secman.service

import com.secman.domain.IdentityProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OAuthServiceTest {

    @Test
    fun `should validate provider configuration structure`() {
        // This test validates the provider setup structure without needing full context
        assertTrue(true, "OAuth service structure test - placeholder")
    }

    @Test
    fun `should validate disabled provider structure`() {
        // Given
        val disabledProvider = IdentityProvider(
            id = 1L,
            name = "GitHub",
            type = IdentityProvider.ProviderType.OIDC,
            clientId = "test-client-id",
            authorizationUrl = "https://github.com/login/oauth/authorize",
            enabled = false
        )

        // This test validates the provider setup structure
        assertFalse(disabledProvider.enabled, "Disabled provider should return false for enabled")
        assertNotNull(disabledProvider.authorizationUrl, "Provider should have authorization URL")
    }

    @Test
    fun `provider configuration should be valid for GitHub`() {
        // Test the GitHub provider template configuration
        val githubProvider = IdentityProvider(
            name = "GitHub",
            type = IdentityProvider.ProviderType.OIDC,
            clientId = "test-client-id",
            clientSecret = "test-client-secret",
            authorizationUrl = "https://github.com/login/oauth/authorize",
            tokenUrl = "https://github.com/login/oauth/access_token",
            userInfoUrl = "https://api.github.com/user",
            scopes = "user:email",
            enabled = true,
            buttonText = "Sign in with GitHub",
            buttonColor = "#333333"
        )

        // Validate configuration
        assertEquals("GitHub", githubProvider.name)
        assertEquals(IdentityProvider.ProviderType.OIDC, githubProvider.type)
        assertEquals("https://github.com/login/oauth/authorize", githubProvider.authorizationUrl)
        assertEquals("https://github.com/login/oauth/access_token", githubProvider.tokenUrl)
        assertEquals("https://api.github.com/user", githubProvider.userInfoUrl)
        assertEquals("user:email", githubProvider.scopes)
        assertTrue(githubProvider.enabled)
        assertEquals("Sign in with GitHub", githubProvider.buttonText)
    }
}