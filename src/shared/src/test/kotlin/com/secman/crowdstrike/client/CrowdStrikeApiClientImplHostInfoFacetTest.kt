package com.secman.crowdstrike.client

import com.secman.crowdstrike.auth.CrowdStrikeAuthService
import com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.dto.resolveHostIp
import com.secman.crowdstrike.model.AuthToken
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

/**
 * Spotlight returns the host_info object - and with it local_ip, machine_domain, instance_id,
 * service_provider_account_id and os_version - ONLY when facet=host_info is requested. Every
 * mapper below reads those fields off host_info, so dropping the facet does not fail the import,
 * it stores assets with a NULL ip and leaves the IP column of the vulnerability export blank.
 * The query string is asserted here for the same reason the install_usage facet is asserted in
 * CrowdStrikeApiClientImplInstalledProductsTest.
 */
class CrowdStrikeApiClientImplHostInfoFacetTest {
    private val blockingClient = mockk<BlockingHttpClient>()
    private val httpClient = mockk<HttpClient> {
        every { toBlocking() } returns blockingClient
    }
    private val authService = mockk<CrowdStrikeAuthService> {
        every { authenticate(any()) } returns AuthToken("token", Instant.now().plusSeconds(3600))
        every { clearCache() } just Runs
    }
    private val client = CrowdStrikeApiClientImpl(httpClient, authService)
    private val config = FalconConfigDto(clientId = "client", clientSecret = "secret")

    private fun vulnerabilityResource(localIp: String?) = buildMap<String, Any> {
        put("id", "vuln-1")
        put("aid", "device-1")
        put("status", "open")
        put("created_timestamp", "2026-01-05T10:00:00Z")
        put("cve", mapOf("id" to "CVE-2026-1234", "severity" to "HIGH", "base_score" to 8.1))
        put(
            "host_info",
            buildMap {
                put("hostname", "server01")
                put("machine_domain", "ad.example.net")
                put("os_version", "Windows Server 2019")
                if (localIp != null) put("local_ip", localIp)
            }
        )
    }

    private fun page(vararg resources: Map<String, Any>) = mapOf(
        "resources" to resources.toList(),
        "meta" to mapOf("pagination" to mapOf("total" to resources.size))
    )

    @Test
    fun `querySpotlightApi requests the host_info facet and maps local_ip onto the DTO`() {
        val requests = mutableListOf<HttpRequest<Any>>()
        every {
            blockingClient.exchange(capture(requests), Map::class.java)
        } returns HttpResponse.ok(page(vulnerabilityResource("10.1.2.3")))

        val vulns = client.querySpotlightApi(
            deviceId = "device-1",
            hostname = "server01",
            token = AuthToken("token", Instant.now().plusSeconds(3600))
        )

        val uri = requests.single().uri.toString()
        assertThat(uri).contains("facet=cve")
        assertThat(uri).contains("facet=host_info")

        val vuln = vulns.single()
        assertThat(vuln.ip).isEqualTo("10.1.2.3")
        assertThat(vuln.adDomain).isEqualTo("ad.example.net")
        assertThat(vuln.osVersion).isEqualTo("Windows Server 2019")
    }

    @Test
    fun `queryAllVulnerabilitiesBulk requests the host_info facet`() {
        val requests = mutableListOf<HttpRequest<Any>>()
        every {
            blockingClient.exchange(capture(requests), Map::class.java)
        } returns HttpResponse.ok(page(vulnerabilityResource("10.4.5.6")))

        val response = client.queryAllVulnerabilitiesBulk(
            severity = "HIGH",
            minDaysOpen = 0,
            deviceType = "ALL",
            config = config
        )

        val uri = requests.single().uri.toString()
        assertThat(uri).contains("facet=cve")
        assertThat(uri).contains("facet=host_info")
        assertThat(response.vulnerabilities.single().ip).isEqualTo("10.4.5.6")
    }

    @Test
    fun `queryVulnerabilitiesByDeviceIds requests the host_info facet`() {
        val requests = mutableListOf<HttpRequest<Any>>()
        every {
            blockingClient.exchange(capture(requests), Map::class.java)
        } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            if (uri.contains("/devices/entities/devices/v2")) {
                HttpResponse.ok(
                    mapOf(
                        "resources" to listOf(
                            mapOf(
                                "device_id" to "device-1",
                                "hostname" to "server01",
                                "local_ip" to "10.7.8.9"
                            )
                        )
                    )
                )
            } else {
                HttpResponse.ok(page(vulnerabilityResource(null)))
            }
        }

        val vulns = client.queryVulnerabilitiesByDeviceIds(
            deviceIds = listOf("device-1"),
            severity = "HIGH",
            minDaysOpen = 0,
            config = config
        )

        val spotlightUri = requests
            .map { it.uri.toString() }
            .single { it.contains("/spotlight/combined/vulnerabilities/v1") }
        assertThat(spotlightUri).contains("facet=cve")
        assertThat(spotlightUri).contains("facet=host_info")

        // Device metadata still wins when the vulnerability row itself carries no local_ip.
        assertThat(vulns.single().ip).isEqualTo("10.7.8.9")
    }

    @Test
    fun `resolveHostIp takes the first non-blank IP across a hosts rows`() {
        val rows = listOf(
            vulnerabilityDto(ip = null),
            vulnerabilityDto(ip = "   "),
            vulnerabilityDto(ip = "10.11.12.13"),
            vulnerabilityDto(ip = "10.99.99.99")
        )

        assertThat(rows.resolveHostIp()).isEqualTo("10.11.12.13")
        assertThat(listOf(vulnerabilityDto(ip = null)).resolveHostIp()).isNull()
        assertThat(emptyList<CrowdStrikeVulnerabilityDto>().resolveHostIp()).isNull()
    }

    private fun vulnerabilityDto(ip: String?) = CrowdStrikeVulnerabilityDto(
        id = "vuln-1",
        hostname = "server01",
        ip = ip,
        cveId = "CVE-2026-1234",
        severity = "HIGH",
        cvssScore = 8.1,
        affectedProduct = "Product 1.0",
        daysOpen = "10 days",
        detectedAt = LocalDateTime.now(),
        status = "open",
        hasException = false
    )
}
