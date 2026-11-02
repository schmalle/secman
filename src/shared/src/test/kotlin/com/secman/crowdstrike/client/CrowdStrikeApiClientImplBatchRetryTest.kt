package com.secman.crowdstrike.client

import com.secman.crowdstrike.auth.CrowdStrikeAuthService
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.model.AuthToken
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientException
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Transient network failures inside a Spotlight vulnerability batch.
 *
 * On the 2026-11-02 import a single `Connection reset` from api.crowdstrike.com failed
 * batch 1/8 outright: Micronaut wraps the reset as an HttpClientException whose message
 * ("Error occurred reading HTTP response: ...") matched none of the batch path's phrases,
 * so 25 hosts were reported as incomplete instead of the page being retried.
 */
class CrowdStrikeApiClientImplBatchRetryTest {
    private val blockingClient = mockk<BlockingHttpClient>()
    private val httpClient = mockk<HttpClient> { every { toBlocking() } returns blockingClient }
    private val authService = mockk<CrowdStrikeAuthService> {
        every { authenticate(any()) } returns AuthToken("token", Instant.now().plusSeconds(3600))
        every { clearCache() } just Runs
    }
    private val client = CrowdStrikeApiClientImpl(httpClient, authService)
    private val config = FalconConfigDto(clientId = "client", clientSecret = "secret")

    private val metadata = mapOf("resources" to listOf(mapOf(
        "device_id" to "device-1", "hostname" to "server01", "instance_id" to "i-1",
        "local_ip" to "10.1.2.3", "first_seen" to "2026-01-01T00:00:00Z",
        "last_seen" to Instant.now().toString())))

    private val singlePage = mapOf(
        "resources" to listOf(mapOf(
            "id" to "v1", "aid" to "device-1", "status" to "open",
            "created_timestamp" to "2026-01-05T10:00:00Z",
            "cve" to mapOf("id" to "CVE-2026-1234", "severity" to "HIGH", "base_score" to 8.1),
            "host_info" to mapOf("hostname" to "server01", "local_ip" to "10.1.2.3"))),
        "meta" to mapOf("pagination" to mapOf("total" to 1)))

    @Test
    fun `connection reset wrapped in HttpClientException retries the page instead of failing the batch`() {
        var spotlightCalls = 0
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/entities/devices/v2") -> HttpResponse.ok(metadata)
                uri.contains("/spotlight/combined/vulnerabilities/v1") -> {
                    spotlightCalls++
                    if (spotlightCalls == 1) throw HttpClientException(
                        "Client 'https://api.crowdstrike.com': Error occurred reading HTTP response: Connection reset",
                        java.net.SocketException("Connection reset"))
                    HttpResponse.ok(singlePage)
                }
                else -> error("Unexpected request: $uri")
            }
        }

        val result = client.queryVulnerabilitiesByDeviceIdsDetailed(
            deviceIds = listOf("device-1"), severity = "HIGH", minDaysOpen = 0, config = config, limit = 2)

        assertThat(spotlightCalls).isEqualTo(2)
        assertThat(result.failedDeviceIds).isEmpty()
        assertThat(result.vulnerabilities).hasSize(1)
    }
}
