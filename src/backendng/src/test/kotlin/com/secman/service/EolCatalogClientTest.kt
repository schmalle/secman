package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [EolCatalogClient] is the one place in this feature where the backend makes an
 * outbound request. The base URL is operator configuration rather than request
 * input, but a mis-set value would still turn it into an SSRF primitive against
 * the backend's own network, and the product key is interpolated into the
 * request path. Both boundaries are asserted here (CLAUDE.md §A10, §A03).
 *
 * These cases exercise validation only — no test performs a network call.
 *
 * ID prefix: ECC-*
 */
class EolCatalogClientTest {

    private fun client(
        baseUrl: String = "https://endoflife.date",
        allowedHosts: String = "endoflife.date"
    ) = EolCatalogClient(
        objectMapper = ObjectMapper(),
        baseUrl = baseUrl,
        allowedHosts = allowedHosts,
        timeoutSeconds = 5,
        maxResponseBytes = 1024
    )

    @Test
    @DisplayName("ECC-001: accepts the configured default source")
    fun acceptsDefaultSource() {
        val uri = client().buildAndValidateUri("/api/v1/products")

        assertThat(uri.host).isEqualTo("endoflife.date")
        assertThat(uri.scheme).isEqualTo("https")
        assertThat(uri.path).isEqualTo("/api/v1/products")
    }

    @Test
    @DisplayName("ECC-002: rejects plaintext http")
    fun rejectsHttp() {
        assertThatThrownBy { client(baseUrl = "http://endoflife.date").buildAndValidateUri("/api/v1/products") }
            .isInstanceOf(EolCatalogClient.EolSourceException::class.java)
            .hasMessageContaining("https")
    }

    @Test
    @DisplayName("ECC-003: rejects a host that is not on the allowlist even when it is public")
    fun rejectsUnlistedHost() {
        assertThatThrownBy {
            client(baseUrl = "https://evil.example", allowedHosts = "endoflife.date")
                .buildAndValidateUri("/api/v1/products")
        }
            .isInstanceOf(EolCatalogClient.EolSourceException::class.java)
            .hasMessageContaining("allowed-hosts")
    }

    @Test
    @DisplayName("ECC-004: rejects loopback, private and cloud-metadata targets even when allowlisted")
    fun rejectsInternalTargets() {
        // Allowlisting is not enough on its own — an operator who adds a host that
        // resolves internally must still be stopped, which is the whole point of
        // re-resolving on every call.
        listOf(
            "https://localhost",
            "https://127.0.0.1",
            "https://169.254.169.254",
            "https://10.0.0.1",
            "https://192.168.1.1"
        ).forEach { url ->
            val host = java.net.URI(url).host
            assertThatThrownBy { client(baseUrl = url, allowedHosts = host).buildAndValidateUri("/api/v1/products") }
                .describedAs("base url %s", url)
                .isInstanceOf(EolCatalogClient.EolSourceException::class.java)
        }
    }

    @Test
    @DisplayName("ECC-005: rejects embedded credentials and non-default ports")
    fun rejectsCredentialsAndPorts() {
        assertThatThrownBy {
            client(baseUrl = "https://user:pass@endoflife.date").buildAndValidateUri("/api/v1/products")
        }.isInstanceOf(EolCatalogClient.EolSourceException::class.java)

        assertThatThrownBy {
            client(baseUrl = "https://endoflife.date:8443").buildAndValidateUri("/api/v1/products")
        }.isInstanceOf(EolCatalogClient.EolSourceException::class.java)
    }

    @Test
    @DisplayName("ECC-006: product keys accept the upstream character class and nothing else")
    fun sanitizesProductKeys() {
        val subject = client()

        listOf("ubuntu", "windows-server", "node.js", "dotnetfx", "amazon-linux", "log4j_2")
            .forEach { key ->
                assertThat(subject.sanitizeProductKey(key)).describedAs("key %s", key).isEqualTo(key)
            }
        assertThat(subject.sanitizeProductKey("  Ubuntu  ")).isEqualTo("ubuntu")
    }

    @Test
    @DisplayName("ECC-007: a product key can never escape its path segment")
    fun rejectsPathTraversalInProductKeys() {
        val subject = client()

        listOf(
            "../../etc/passwd",
            "..",
            "a..b",
            "ubuntu/../admin",
            "ubuntu?x=1",
            "ubuntu#frag",
            "ubuntu%2F..",
            "http://evil.example",
            "",
            "   ",
            "-leading-dash",
            "a".repeat(200)
        ).forEach { key ->
            assertThatThrownBy { subject.sanitizeProductKey(key) }
                .describedAs("key %s", key)
                .isInstanceOf(EolCatalogClient.EolSourceException::class.java)
        }
    }
}
