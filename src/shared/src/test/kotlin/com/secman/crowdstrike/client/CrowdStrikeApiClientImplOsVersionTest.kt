package com.secman.crowdstrike.client

import com.secman.crowdstrike.auth.CrowdStrikeAuthService
import com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto
import com.secman.crowdstrike.model.AuthToken
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Verifies that operating-system metadata from the CrowdStrike API is mapped
 * onto CrowdStrikeVulnerabilityDto.osVersion so it can be stored per asset.
 */
class CrowdStrikeApiClientImplOsVersionTest {
    private val blockingClient = mockk<BlockingHttpClient>()
    private val httpClient = mockk<HttpClient> {
        every { toBlocking() } returns blockingClient
    }
    private val authService = mockk<CrowdStrikeAuthService> {
        every { authenticate(any()) } returns AuthToken("token", Instant.now().plusSeconds(3600))
    }
    private val client = CrowdStrikeApiClientImpl(httpClient, authService)

    /**
     * Invoke the private mapResponseToDtos(resources, hostname) overload by reflection.
     */
    @Suppress("UNCHECKED_CAST")
    private fun mapWithHostname(resources: List<*>, hostname: String): List<CrowdStrikeVulnerabilityDto> {
        val method = CrowdStrikeApiClientImpl::class.java.declaredMethods.first {
            it.name == "mapResponseToDtos" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[1] == String::class.java
        }
        method.isAccessible = true
        return method.invoke(client, resources, hostname) as List<CrowdStrikeVulnerabilityDto>
    }

    @Test
    fun `maps os_version from host_info`() {
        val resources = listOf(
            mapOf(
                "id" to "vuln-1",
                "host_info" to mapOf("os_version" to "Windows Server 2019"),
                "cve" to mapOf("id" to "CVE-2024-0001", "severity" to "HIGH")
            )
        )

        val dtos = mapWithHostname(resources, "server01")

        assertThat(dtos).hasSize(1)
        assertThat(dtos[0].osVersion).isEqualTo("Windows Server 2019")
    }

    @Test
    fun `falls back to platform when os_version is absent`() {
        val resources = listOf(
            mapOf(
                "id" to "vuln-2",
                "host_info" to mapOf("platform" to "Linux"),
                "cve" to mapOf("id" to "CVE-2024-0002", "severity" to "CRITICAL")
            )
        )

        val dtos = mapWithHostname(resources, "server02")

        assertThat(dtos).hasSize(1)
        assertThat(dtos[0].osVersion).isEqualTo("Linux")
    }

    @Test
    fun `leaves os_version null when host_info has no OS fields`() {
        val resources = listOf(
            mapOf(
                "id" to "vuln-3",
                "host_info" to mapOf("local_ip" to "10.0.0.1"),
                "cve" to mapOf("id" to "CVE-2024-0003", "severity" to "LOW")
            )
        )

        val dtos = mapWithHostname(resources, "server03")

        assertThat(dtos).hasSize(1)
        assertThat(dtos[0].osVersion).isNull()
    }
}
