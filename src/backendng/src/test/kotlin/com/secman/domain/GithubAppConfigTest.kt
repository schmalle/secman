package com.secman.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GithubAppConfigTest {

    private val config = GithubAppConfig(
        id = 1,
        appId = "12345",
        privateKeyPem = "-----BEGIN RSA PRIVATE KEY-----\nsecret\n-----END RSA PRIVATE KEY-----",
        installationId = "678",
        organization = "my-org"
    )

    @Test
    fun `toSafeResponse masks only the private key`() {
        val safe = config.toSafeResponse()
        assertThat(safe.privateKeyPem).isEqualTo(GithubAppConfig.PRIVATE_KEY_MASK)
        assertThat(safe.appId).isEqualTo("12345")
        assertThat(safe.installationId).isEqualTo("678")
        assertThat(safe.organization).isEqualTo("my-org")
    }

    @Test
    fun `masked sentinel does not overwrite the stored key`() {
        assertThat(config.shouldUpdateCredentials(GithubAppConfig.PRIVATE_KEY_MASK)).isFalse()
        assertThat(config.shouldUpdateCredentials(null)).isFalse()
        val updated = config.withUpdatedCredentials(GithubAppConfig.PRIVATE_KEY_MASK)
        assertThat(updated.privateKeyPem).isEqualTo(config.privateKeyPem)
    }

    @Test
    fun `real new key replaces the stored key`() {
        val newKey = "-----BEGIN PRIVATE KEY-----\nnew\n-----END PRIVATE KEY-----"
        assertThat(config.shouldUpdateCredentials(newKey)).isTrue()
        assertThat(config.withUpdatedCredentials(newKey).privateKeyPem).isEqualTo(newKey)
    }

    @Test
    fun `validate rejects non-numeric appId and non-PEM key`() {
        assertThat(config.validate()).isEmpty()
        assertThat(config.copy(appId = "abc").validate()).anySatisfy {
            assertThat(it).contains("App ID must be numeric")
        }
        assertThat(config.copy(privateKeyPem = "not a pem").validate()).anySatisfy {
            assertThat(it).contains("PEM")
        }
        assertThat(config.copy(installationId = "x1").validate()).anySatisfy {
            assertThat(it).contains("Installation ID must be numeric")
        }
    }
}
