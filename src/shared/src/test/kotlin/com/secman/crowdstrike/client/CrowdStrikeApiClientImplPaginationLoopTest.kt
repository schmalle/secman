package com.secman.crowdstrike.client

import com.secman.crowdstrike.auth.CrowdStrikeAuthService
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.exception.NotFoundException
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pagination termination and hostname-resolution behavior of the Spotlight client.
 *
 * Motivated by the 2026-08-25 import: Falcon ping-ponged between two `after` cursors
 * (800/255-row pages alternating for 20+ pages), which the old consecutive-repeat
 * guard could not detect. The same rows were re-fetched until the 50-page cap and
 * inflated one host to ~27x its real row count. Separately, hostname resolution took
 * only `resources[0]` of the device lookup, so a re-imaged host with several aids
 * reported 0 rows whenever the first-returned aid was the stale one.
 */
class CrowdStrikeApiClientImplPaginationLoopTest {
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
    private val token = AuthToken("token", Instant.now().plusSeconds(3600))

    private fun vulnResource(id: String, aid: String = "device-1") = mapOf(
        "id" to id,
        "aid" to aid,
        "status" to "open",
        "created_timestamp" to "2026-01-05T10:00:00Z",
        "cve" to mapOf("id" to "CVE-2026-1234", "severity" to "HIGH", "base_score" to 8.1),
        "host_info" to mapOf("hostname" to "server01", "local_ip" to "10.1.2.3")
    )

    private fun spotlightPage(resources: List<Map<String, Any>>, after: String?, total: Int) = mapOf(
        "resources" to resources,
        "meta" to mapOf(
            "pagination" to buildMap<String, Any> {
                put("total", total)
                if (after != null) put("after", after)
            }
        )
    )

    private fun metadataResponse(vararg deviceIds: String) = mapOf(
        "resources" to deviceIds.map {
            mapOf("device_id" to it, "hostname" to "server01", "local_ip" to "10.1.2.3")
        }
    )

    private fun deviceQueryResponse(vararg deviceIds: String) = mapOf(
        "resources" to deviceIds.toList()
    )

    /**
     * Serves metadata lookups and hands out Spotlight pages from a queue.
     * Returns the list of captured requests for URI assertions.
     */
    private fun stubSpotlightPages(pages: List<Map<String, Any>>): MutableList<HttpRequest<Any>> {
        val requests = mutableListOf<HttpRequest<Any>>()
        val remaining = ArrayDeque(pages)
        every { blockingClient.exchange(capture(requests), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/entities/devices/v2") -> HttpResponse.ok(metadataResponse("device-1"))
                uri.contains("/spotlight/combined/vulnerabilities/v1") ->
                    HttpResponse.ok(remaining.removeFirstOrNull() ?: error("Spotlight queried after last stubbed page"))
                else -> error("Unexpected request: $uri")
            }
        }
        return requests
    }

    private fun spotlightCalls(requests: List<HttpRequest<Any>>) =
        requests.count { it.uri.toString().contains("/spotlight/combined/vulnerabilities/v1") }

    // --- queryBatchVulnerabilities (driven via queryVulnerabilitiesByDeviceIdsDetailed) ---

    @Test
    fun `cursor ping-pong stops the batch loop and fails the batch`() {
        // Full pages (limit 2) with cursors A, B, A - the old consecutive-repeat guard
        // never fired on this sequence and the loop re-fetched until the 50-page cap.
        val requests = stubSpotlightPages(
            listOf(
                spotlightPage(listOf(vulnResource("v1"), vulnResource("v2")), after = "A", total = 100),
                spotlightPage(listOf(vulnResource("v3"), vulnResource("v4")), after = "B", total = 100),
                spotlightPage(listOf(vulnResource("v5"), vulnResource("v6")), after = "A", total = 100)
            )
        )

        val result = client.queryVulnerabilitiesByDeviceIdsDetailed(
            deviceIds = listOf("device-1"),
            severity = "HIGH",
            minDaysOpen = 0,
            config = config,
            limit = 2
        )

        assertThat(spotlightCalls(requests)).isEqualTo(3)
        // Loop detection = incomplete data = the batch must fail so its hosts keep
        // their old rows and are excluded from the reconcile sweep.
        assertThat(result.failedDeviceIds).containsExactly("device-1")
    }

    @Test
    fun `short page ends the batch loop without failing the batch`() {
        val requests = stubSpotlightPages(
            listOf(
                spotlightPage(listOf(vulnResource("v1"), vulnResource("v2")), after = "A", total = 3),
                spotlightPage(listOf(vulnResource("v3")), after = "B", total = 3)
            )
        )

        val result = client.queryVulnerabilitiesByDeviceIdsDetailed(
            deviceIds = listOf("device-1"),
            severity = "HIGH",
            minDaysOpen = 0,
            config = config,
            limit = 2
        )

        assertThat(spotlightCalls(requests)).isEqualTo(2)
        assertThat(result.vulnerabilities).hasSize(3)
        assertThat(result.failedDeviceIds).isEmpty()
    }

    @Test
    fun `pagination total ends the batch loop despite a dangling cursor`() {
        // Falcon returns a live `after` token even on the final page; the reported
        // total is the authoritative stop signal for a full final page.
        val requests = stubSpotlightPages(
            listOf(
                spotlightPage(listOf(vulnResource("v1"), vulnResource("v2")), after = "A", total = 2)
            )
        )

        val result = client.queryVulnerabilitiesByDeviceIdsDetailed(
            deviceIds = listOf("device-1"),
            severity = "HIGH",
            minDaysOpen = 0,
            config = config,
            limit = 2
        )

        assertThat(spotlightCalls(requests)).isEqualTo(1)
        assertThat(result.vulnerabilities).hasSize(2)
        assertThat(result.failedDeviceIds).isEmpty()
    }

    @Test
    fun `short page below the reported total fails the batch`() {
        // Falcon says 2000 rows exist but delivers one short page with a live token:
        // the result set is incomplete, so the hosts must not be treated as refreshed.
        stubSpotlightPages(
            listOf(
                spotlightPage(listOf(vulnResource("v1")), after = "A", total = 2000)
            )
        )

        val result = client.queryVulnerabilitiesByDeviceIdsDetailed(
            deviceIds = listOf("device-1"),
            severity = "HIGH",
            minDaysOpen = 0,
            config = config,
            limit = 2
        )

        assertThat(result.failedDeviceIds).containsExactly("device-1")
    }

    // --- querySpotlightApi (per-host / instance-id loop) ---

    @Test
    fun `querySpotlightApi stops on a repeated cursor`() {
        // Full pages are 500 rows on this path.
        fun fullPage(prefix: String, after: String) =
            spotlightPage((1..500).map { vulnResource("$prefix-$it") }, after = after, total = 5000)

        val requests = mutableListOf<HttpRequest<Any>>()
        val pages = ArrayDeque(listOf(fullPage("p1", "A"), fullPage("p2", "B"), fullPage("p3", "A")))
        every { blockingClient.exchange(capture(requests), Map::class.java) } answers {
            HttpResponse.ok(pages.removeFirstOrNull() ?: error("Spotlight queried after last stubbed page"))
        }

        val vulns = client.querySpotlightApi("device-1", "server01", token)

        assertThat(requests).hasSize(3)
        assertThat(vulns).hasSize(1500)
    }

    // --- multi-aid hostname resolution ---

    @Test
    fun `queryVulnerabilities merges rows from all aids of a hostname`() {
        val requests = mutableListOf<HttpRequest<Any>>()
        every { blockingClient.exchange(capture(requests), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/queries/devices/v1") ->
                    HttpResponse.ok(deviceQueryResponse("aid-1", "aid-2", "aid-3"))
                uri.contains("aid-1") -> HttpResponse.ok(spotlightPage(listOf(vulnResource("v1", "aid-1")), null, 1))
                uri.contains("aid-2") -> HttpResponse.ok(spotlightPage(emptyList(), null, 0))
                uri.contains("aid-3") -> HttpResponse.ok(spotlightPage(listOf(vulnResource("v2", "aid-3")), null, 1))
                else -> error("Unexpected request: $uri")
            }
        }

        val response = client.queryVulnerabilities("server01", config)

        // One device lookup (first strategy matched), one Spotlight query per aid.
        assertThat(requests.count { it.uri.toString().contains("/devices/queries/devices/v1") }).isEqualTo(1)
        assertThat(spotlightCalls(requests)).isEqualTo(3)
        assertThat(response.vulnerabilities.map { it.id }).containsExactlyInAnyOrder("v1", "v2")
        assertThat(response.deviceCount).isEqualTo(3)
    }

    @Test
    fun `hostname resolution uses the first non-empty strategy without unioning later ones`() {
        val requests = mutableListOf<HttpRequest<Any>>()
        var deviceQueries = 0
        every { blockingClient.exchange(capture(requests), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/queries/devices/v1") -> {
                    deviceQueries++
                    // Strategy 1 (exact) misses, strategy 2 (stemmed) matches.
                    if (deviceQueries == 1) HttpResponse.ok(deviceQueryResponse())
                    else HttpResponse.ok(deviceQueryResponse("aid-2", "aid-2", "aid-9"))
                }
                else -> error("Unexpected request: $uri")
            }
        }

        val deviceIds = client.getDeviceIdsByHostname("server01", token)

        // Cascade stopped at the first non-empty strategy; duplicates removed.
        assertThat(deviceQueries).isEqualTo(2)
        assertThat(deviceIds).containsExactly("aid-2", "aid-9")
    }

    @Test
    fun `one failing aid does not sink the other aids of the hostname`() {
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/queries/devices/v1") -> HttpResponse.ok(deviceQueryResponse("aid-1", "aid-2"))
                uri.contains("aid-1") ->
                    @Suppress("UNCHECKED_CAST")
                    (HttpResponse.serverError<Any>() as HttpResponse<Map<*, *>>)
                uri.contains("aid-2") -> HttpResponse.ok(spotlightPage(listOf(vulnResource("v2", "aid-2")), null, 1))
                else -> error("Unexpected request: $uri")
            }
        }

        val response = client.queryVulnerabilities("server01", config)

        assertThat(response.vulnerabilities.map { it.id }).containsExactly("v2")
        assertThat(response.deviceCount).isEqualTo(2)
    }

    @Test
    fun `unresolvable hostname still raises NotFoundException`() {
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } returns
            HttpResponse.ok(deviceQueryResponse())

        assertThatThrownBy { client.queryVulnerabilities("ghost-host", config) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `queryServersWithFilters reports not-found hostnames instead of swallowing them`() {
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                // ghost-host misses every strategy (incl. the upper/lowercase ones);
                // server01 resolves on the first.
                uri.contains("ghost-host", ignoreCase = true) -> HttpResponse.ok(deviceQueryResponse())
                uri.contains("/devices/queries/devices/v1") -> HttpResponse.ok(deviceQueryResponse("aid-1"))
                uri.contains("/spotlight/combined/vulnerabilities/v1") ->
                    HttpResponse.ok(spotlightPage(listOf(vulnResource("v1", "aid-1")), null, 1))
                else -> error("Unexpected request: $uri")
            }
        }

        val response = client.queryServersWithFilters(
            hostnames = listOf("ghost-host", "server01"),
            deviceType = "SERVER",
            severity = "HIGH",
            minDaysOpen = 0,
            config = config,
            limit = 100,
            lastSeenDays = 0
        )

        assertThat(response.notFoundHostnames).containsExactly("ghost-host")
        assertThat(response.vulnerabilities.map { it.id }).containsExactly("v1")
    }
}
