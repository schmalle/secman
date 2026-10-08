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

    private fun metadataResponse(vararg deviceIds: String, sameHostname: Boolean = false) = mapOf(
        "resources" to deviceIds.mapIndexed { index, id ->
            mapOf("device_id" to id, "hostname" to if (sameHostname || deviceIds.size == 1) "server01" else "host-$id",
                "local_ip" to "10.1.2.3", "first_seen" to Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index.toLong()).toString(),
                "last_seen" to Instant.now().toString())
        }
    )

    private fun deviceQueryResponse(vararg deviceIds: String) = mapOf(
        "resources" to deviceIds.toList()
    )

    private fun combinedDeviceResponse(vararg ids: String) = mapOf(
        "resources" to ids.map { mapOf("device_id" to it) },
        "meta" to mapOf("pagination" to mapOf("total" to ids.size))
    )

    /**
     * Serves metadata lookups and hands out Spotlight pages from a queue.
     * Returns the list of captured requests for URI assertions.
     */
    private fun stubSpotlightPages(pages: List<Map<String, Any>>, deviceIds: List<String> = listOf("device-1")): MutableList<HttpRequest<Any>> {
        val requests = mutableListOf<HttpRequest<Any>>()
        val remaining = ArrayDeque(pages)
        every { blockingClient.exchange(capture(requests), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/entities/devices/v2") -> HttpResponse.ok(metadataResponse(*deviceIds.toTypedArray()))
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
        assertThat(result.vulnerabilities).isEmpty()
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
        assertThat(result.vulnerabilities).isEmpty()
    }

    @Test
    fun `age filtered page does not hide later matching findings`() {
        val recent = vulnResource("recent").toMutableMap().apply {
            put("created_timestamp", Instant.now().toString())
        }
        val requests = stubSpotlightPages(listOf(
            spotlightPage(listOf(vulnResource("v1")), "A", 4),
            spotlightPage(listOf(vulnResource("v2")), "B", 4),
            spotlightPage(listOf(recent), "C", 4),
            spotlightPage(listOf(vulnResource("v4")), "D", 4)
        ))

        val result = client.queryVulnerabilitiesByDeviceIdsDetailed(
            listOf("device-1"), "HIGH", 1, config, limit = 1
        )

        assertThat(spotlightCalls(requests)).isEqualTo(4)
        assertThat(result.vulnerabilities.map { it.id }).containsExactly("v1", "v2", "v4")
        assertThat(result.failedDeviceIds).isEmpty()
    }

    @Test
    fun `oversized shard retries children without retaining parent partial rows`() {
        val pages = (1..50).map {
            spotlightPage(listOf(vulnResource("partial-$it")), "cursor-$it", 51)
        } + listOf(
            spotlightPage(listOf(vulnResource("complete-1", "device-1")), null, 1),
            spotlightPage(listOf(vulnResource("complete-2", "device-2")), null, 1)
        )
        val requests = stubSpotlightPages(pages, listOf("device-1", "device-2"))
        val result = client.queryVulnerabilitiesByDeviceIdsDetailed(
            listOf("device-1", "device-2"), "HIGH", 0, config, limit = 1)
        assertThat(result.vulnerabilities.map { it.id }).containsExactly("complete-1", "complete-2")
        assertThat(result.failedDeviceIds).isEmpty()
        assertThat(spotlightCalls(requests)).isEqualTo(52)
    }

    @Test
    fun `page cap discards incomplete results`() {
        stubSpotlightPages((1..50).map {
            spotlightPage(listOf(vulnResource("v$it")), "cursor-$it", 51)
        })
        val result = client.queryVulnerabilitiesByDeviceIdsDetailed(
            listOf("device-1"), "HIGH", 0, config, limit = 1
        )
        assertThat(result.vulnerabilities).isEmpty()
        assertThat(result.failedDeviceIds).containsExactly("device-1")
    }

    @Test
    fun `failed device batch preserves the independently selected successful host`() {
        val parallelClient = object : CrowdStrikeApiClientImpl(httpClient, authService) {
            init { configuredBatchSize = 5 }
        }
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/entities/devices/v2") ->
                    HttpResponse.ok(metadataResponse(*(1..6).map { "aid-$it" }.toTypedArray()))
                uri.contains("/network-address-history/") -> HttpResponse.ok(mapOf("resources" to emptyList<Any>()))
                uri.contains("aid-6") -> HttpResponse.ok(spotlightPage(listOf(vulnResource("good", "aid-6")), null, 1))
                uri.contains("/spotlight/") -> HttpResponse.ok(spotlightPage(listOf(vulnResource("partial", "aid-1")), "A", 10))
                else -> error("Unexpected request: $uri")
            }
        }
        val result = parallelClient.queryVulnerabilitiesByDeviceIdsDetailed(
            (1..6).map { "aid-$it" }, "HIGH", 0, config, limit = 2
        )
        assertThat(result.vulnerabilities.map { it.id }).containsExactly("good")
        assertThat(result.failedDeviceIds).containsExactlyInAnyOrderElementsOf((1..5).map { "aid-$it" })
    }

    @Test
    fun `streaming reuses metadata and sends only the latest sibling`() {
        val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequest<Any>>()
        val ids = (1..6).map { "aid-$it" }
        every { blockingClient.exchange(capture(requests), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/combined/devices/v1") -> HttpResponse.ok(combinedDeviceResponse(*ids.toTypedArray()))
                uri.contains("/devices/entities/devices/v2") -> HttpResponse.ok(metadataResponse(*ids.toTypedArray(), sameHostname = true))
                uri.contains("/network-address-history/") -> HttpResponse.ok(mapOf("resources" to emptyList<Any>()))
                uri.contains("/spotlight/") -> HttpResponse.ok(spotlightPage(listOf(vulnResource("aid-6", "aid-6")), null, 1))
                else -> error("Unexpected request: $uri")
            }
        }
        val stored = mutableListOf<List<com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto>>()
        val result = client.queryServersWithFiltersStreaming("SERVER", "HIGH", 0, config, 100, 30, 2) {
            stored.add(it.vulnerabilities)
        }
        assertThat(stored).hasSize(1)
        assertThat(stored.single()).hasSize(1)
        assertThat(result.failedDeviceCount).isZero()
        assertThat(result.queriedHosts).hasSize(1)
        assertThat(requests.count { it.uri.toString().contains("/devices/entities/devices/v2") }).isEqualTo(1)
        assertThat(requests.count { it.uri.toString().contains("/network-address-history/") }).isZero()
    }

    @Test
    fun `cloud instance aliases share one canonical host payload across outer boundaries`() {
        val ids = listOf("aid-1", "aid-2")
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/combined/devices/v1") -> HttpResponse.ok(combinedDeviceResponse(*ids.toTypedArray()))
                uri.contains("/devices/entities/devices/v2") -> HttpResponse.ok(mapOf("resources" to ids.mapIndexed { i, id ->
                    mapOf("device_id" to id, "hostname" to "alias-$i", "instance_id" to "i-shared", "first_seen" to Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i.toLong()).toString(), "last_seen" to Instant.now().toString())
                }))
                uri.contains("/network-address-history/") -> HttpResponse.ok(mapOf("resources" to emptyList<Any>()))
                uri.contains("/spotlight/") -> HttpResponse.ok(spotlightPage(listOf(vulnResource("winner", "aid-2")), null, 1))
                else -> error("Unexpected request: $uri")
            }
        }
        val stored = mutableListOf<List<com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto>>()
        client.queryServersWithFiltersStreaming("SERVER", "HIGH", 0, config, 100, 30, 1) { stored.add(it.vulnerabilities) }
        assertThat(stored).hasSize(1)
        assertThat(stored.single().map { it.hostname }.toSet()).containsExactly("alias-1")
        assertThat(stored.single()).hasSize(1)
    }

    @Test
    fun `short name collision across different cloud instances preserves distinct hostnames`() {
        val ids = listOf("aid-1", "aid-2")
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/combined/devices/v1") -> HttpResponse.ok(combinedDeviceResponse(*ids.toTypedArray()))
                uri.contains("/devices/entities/devices/v2") -> HttpResponse.ok(mapOf("resources" to ids.mapIndexed { i, id ->
                    mapOf("device_id" to id, "hostname" to "server.domain-$i", "instance_id" to "i-$id", "first_seen" to "2026-01-01T00:00:00Z", "last_seen" to Instant.now().toString())
                }))
                uri.contains("/network-address-history/") -> HttpResponse.ok(mapOf("resources" to emptyList<Any>()))
                uri.contains("/spotlight/") -> {
                    val filter = firstArg<HttpRequest<Any>>().parameters.get("filter").orEmpty()
                    val aid = ids.single { filter.contains(it) }
                    HttpResponse.ok(spotlightPage(listOf(vulnResource(aid, aid)), null, 1))
                }
                else -> error("Unexpected request: $uri")
            }
        }
        val stored = mutableListOf<List<com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto>>()
        client.queryServersWithFiltersStreaming("SERVER", "HIGH", 0, config, 100, 30, 1) { stored.add(it.vulnerabilities) }
        assertThat(stored).hasSize(2)
        assertThat(stored.flatten().map { it.hostname }.toSet()).containsExactlyInAnyOrder("server.domain-0", "server.domain-1")
    }

    @Test
    fun `incomplete streaming results never reach storage`() {
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/combined/devices/v1") -> HttpResponse.ok(combinedDeviceResponse("device-1"))
                uri.contains("/devices/entities/devices/v2") -> HttpResponse.ok(metadataResponse("device-1"))
                uri.contains("/network-address-history/") -> HttpResponse.ok(mapOf("resources" to emptyList<Any>()))
                uri.contains("/spotlight/") -> HttpResponse.ok(spotlightPage(listOf(vulnResource("partial")), "A", 10))
                else -> error("Unexpected request: $uri")
            }
        }
        var storageCalls = 0
        val result = client.queryServersWithFiltersStreaming("SERVER", "HIGH", 0, config, 2, 30, 200) {
            storageCalls++
        }
        assertThat(storageCalls).isZero()
        assertThat(result.totalVulnerabilities).isZero()
        assertThat(result.failedDeviceCount).isEqualTo(1)
        assertThat(result.failedHosts.map { it.crowdStrikeAid }).containsExactly("device-1")
    }

    @Test
    fun `metadata waves overlap requests without fetching devices twice`() {
        val ids = (1..201).map { "aid-$it" }
        val active = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val historyCalls = java.util.concurrent.atomic.AtomicInteger()
        val detailsCalls = java.util.concurrent.atomic.AtomicInteger()
        val waveStarted = java.util.concurrent.CountDownLatch(3)
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri
            when {
                uri.path.contains("/devices/combined/devices/v1") -> HttpResponse.ok(combinedDeviceResponse(*ids.toTypedArray()))
                uri.path.contains("/network-address-history/") -> {
                    historyCalls.incrementAndGet()
                    HttpResponse.ok(mapOf("resources" to emptyList<Any>()))
                }
                uri.path.contains("/devices/entities/devices/v2") -> {
                    detailsCalls.incrementAndGet()
                    peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    waveStarted.countDown()
                    check(waveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    active.decrementAndGet()
                    val chunk = io.micronaut.http.uri.UriBuilder.of(uri).build().query.split("&")
                        .filter { it.startsWith("ids=") }.map { it.substringAfter("=") }
                    HttpResponse.ok(mapOf("resources" to chunk.map { mapOf("device_id" to it, "hostname" to it, "first_seen" to "2026-01-01T00:00:00Z", "last_seen" to Instant.now().toString()) }))
                }
                uri.path.contains("/spotlight/") -> HttpResponse.ok(spotlightPage(emptyList(), null, 0))
                else -> error("Unexpected request: $uri")
            }
        }
        val result = client.queryServersWithFiltersStreaming("SERVER", "HIGH", 0, config, 500, 30, 200) {
            assertThat(it.vulnerabilities).isEmpty()
        }
        assertThat(peak.get()).isEqualTo(3)
        assertThat(historyCalls.get()).isZero()
        assertThat(detailsCalls.get()).isEqualTo(3)
        assertThat(result.queriedHosts).hasSize(201)
        assertThat(result.failedDeviceCount).isZero()
    }

    // --- querySpotlightApi (per-host / instance-id loop) ---

    @Test
    fun `querySpotlightApi rejects incomplete repeated cursor results`() {
        // Full pages are 500 rows on this path.
        fun fullPage(prefix: String, after: String) =
            spotlightPage((1..500).map { vulnResource("$prefix-$it") }, after = after, total = 5000)

        val requests = mutableListOf<HttpRequest<Any>>()
        val pages = ArrayDeque(listOf(fullPage("p1", "A"), fullPage("p2", "B"), fullPage("p3", "A")))
        every { blockingClient.exchange(capture(requests), Map::class.java) } answers {
            HttpResponse.ok(pages.removeFirstOrNull() ?: error("Spotlight queried after last stubbed page"))
        }

        assertThatThrownBy { client.querySpotlightApi("device-1", "server01", token) }
            .isInstanceOf(com.secman.crowdstrike.exception.CrowdStrikeException::class.java)
        assertThat(requests).hasSize(3)
    }

    @Test
    fun `per-host completed total stops despite a lingering cursor`() {
        val requests = stubSpotlightPages(listOf(
            spotlightPage(listOf(vulnResource("one")), "terminal", 1)
        ))
        assertThat(client.querySpotlightApi("device-1", "server01", token).map { it.id }).containsExactly("one")
        assertThat(spotlightCalls(requests)).isEqualTo(1)
    }

    @Test
    fun `per-host blank cursor ends pagination without a reported total`() {
        val requests = stubSpotlightPages(listOf(mapOf(
            "resources" to listOf(vulnResource("one")),
            "meta" to mapOf("pagination" to mapOf("after" to ""))
        )))
        assertThat(client.querySpotlightApi("device-1", "server01", token).map { it.id }).containsExactly("one")
        assertThat(spotlightCalls(requests)).isEqualTo(1)
    }

    @Test
    fun `per-host confirmed zero accepts an empty page with a cursor`() {
        val requests = stubSpotlightPages(listOf(spotlightPage(emptyList(), "terminal", 0)))
        assertThat(client.querySpotlightApi("device-1", "server01", token)).isEmpty()
        assertThat(spotlightCalls(requests)).isEqualTo(1)
    }

    @Test
    fun `per-host duplicate rows cannot satisfy reported total`() {
        stubSpotlightPages(listOf(
            spotlightPage(listOf(vulnResource("one")), "A", 2),
            spotlightPage(listOf(vulnResource("one")), "A", 2)
        ))
        assertThatThrownBy { client.querySpotlightApi("device-1", "server01", token) }
            .hasMessageContaining("repeated cursor before completion")
    }

    @Test
    fun `per-host completed total overrides a repeated terminal cursor`() {
        val requests = stubSpotlightPages(listOf(
            spotlightPage(listOf(vulnResource("one")), "A", 2),
            spotlightPage(listOf(vulnResource("one"), vulnResource("two")), "A", 2)
        ))
        assertThat(client.querySpotlightApi("device-1", "server01", token).map { it.id }).containsExactly("one", "two")
        assertThat(spotlightCalls(requests)).isEqualTo(2)
    }

    @Test
    fun `per-host missing rows fail even if cursor disappears`() {
        val incompletePages = listOf(
            spotlightPage(listOf(vulnResource("one")), null, 2),
            spotlightPage(emptyList(), "terminal", 2),
            mapOf("resources" to emptyList<Any>(), "meta" to mapOf("pagination" to mapOf("after" to "terminal")))
        )
        incompletePages.forEach { page ->
            stubSpotlightPages(listOf(page))
            assertThatThrownBy { client.querySpotlightApi("device-1", "server01", token) }
                .isInstanceOf(com.secman.crowdstrike.exception.CrowdStrikeException::class.java)
                .hasMessageContaining("Incomplete Spotlight pagination")
        }
    }

    @Test
    fun `per-host missing resource identity cannot satisfy reported total`() {
        stubSpotlightPages(listOf(spotlightPage(listOf(mapOf("status" to "open")), "terminal", 1)))
        assertThatThrownBy { client.querySpotlightApi("device-1", "server01", token) }
            .hasMessageContaining("Missing Spotlight resource identity")
    }

    @Test
    fun `per-host thrown 404 on first page returns no vulnerabilities`() {
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } throws
            io.micronaut.http.client.exceptions.HttpClientResponseException("not found", HttpResponse.notFound<Any>())
        assertThat(client.querySpotlightApi("device-1", "server01", token)).isEmpty()
    }

    @Test
    fun `per-host thrown 404 after cursor fails incomplete query`() {
        var calls = 0
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } answers {
            if (++calls == 1) HttpResponse.ok(spotlightPage(listOf(vulnResource("one")), "A", 2))
            else throw io.micronaut.http.client.exceptions.HttpClientResponseException("not found", HttpResponse.notFound<Any>())
        }
        assertThatThrownBy { client.querySpotlightApi("device-1", "server01", token) }
            .hasMessageContaining("404 after cursor")
    }

    @Test
    fun `per-host thrown 429 preserves retry-after`() {
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } throws
            io.micronaut.http.client.exceptions.HttpClientResponseException(
                "rate limited", HttpResponse.status<Any>(io.micronaut.http.HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "7"))
        val error = org.junit.jupiter.api.Assertions.assertThrows(com.secman.crowdstrike.exception.RateLimitException::class.java) {
            client.querySpotlightApi("device-1", "server01", token)
        }
        assertThat(error.retryAfterSeconds).isEqualTo(7L)
    }

    @Test
    fun `per-host thrown 503 retries the same page successfully`() {
        val requests = mutableListOf<HttpRequest<Any>>()
        var calls = 0
        every { blockingClient.exchange(capture(requests), Map::class.java) } answers {
            if (++calls == 1) throw io.micronaut.http.client.exceptions.HttpClientResponseException(
                "unavailable", HttpResponse.status<Any>(io.micronaut.http.HttpStatus.SERVICE_UNAVAILABLE))
            HttpResponse.ok(spotlightPage(listOf(vulnResource("one")), "terminal", 1))
        }
        assertThat(client.querySpotlightApi("device-1", "server01", token).map { it.id }).containsExactly("one")
        assertThat(requests).hasSize(2)
        assertThat(requests[0].uri).isEqualTo(requests[1].uri)
    }

    // --- multi-aid hostname resolution ---

    @Test
    fun `queryVulnerabilities queries only the newest enrollment of a hostname`() {
        val requests = mutableListOf<HttpRequest<Any>>()
        every { blockingClient.exchange(capture(requests), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/entities/devices/v2") -> HttpResponse.ok(metadataResponse("aid-1", "aid-2", "aid-3", sameHostname = true))
                uri.contains("/network-address-history/") -> HttpResponse.ok(mapOf("resources" to emptyList<Any>()))
                uri.contains("/devices/queries/devices/v1") ->
                    HttpResponse.ok(deviceQueryResponse("aid-1", "aid-2", "aid-3"))
                uri.contains("aid-1") -> HttpResponse.ok(spotlightPage(listOf(vulnResource("v1", "aid-1")), null, 1))
                uri.contains("aid-2") -> HttpResponse.ok(spotlightPage(emptyList(), null, 0))
                uri.contains("aid-3") -> HttpResponse.ok(spotlightPage(listOf(vulnResource("v2", "aid-3")), null, 1))
                else -> error("Unexpected request: $uri")
            }
        }

        val response = client.queryVulnerabilities("server01", config)

        // Discovery retains old records as cleanup evidence, but only the winner reaches Spotlight.
        assertThat(requests.count { it.uri.toString().contains("/devices/queries/devices/v1") }).isEqualTo(1)
        assertThat(spotlightCalls(requests)).isEqualTo(1)
        assertThat(response.vulnerabilities.map { it.id }).containsExactly("v2")
        assertThat(response.deviceCount).isEqualTo(1)
        assertThat(response.failedAids).isEmpty()
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
                    else HttpResponse.ok(deviceQueryResponse("aid-2", "aid-9"))
                }
                else -> error("Unexpected request: $uri")
            }
        }

        val deviceIds = client.getDeviceIdsByHostname("server01", token)

        // Cascade stops at the first non-empty strategy.
        assertThat(deviceQueries).isEqualTo(2)
        assertThat(deviceIds).containsExactly("aid-2", "aid-9")
    }

    @Test
    fun `retired aid is ignored even when its Spotlight lookup would fail`() {
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/entities/devices/v2") -> HttpResponse.ok(metadataResponse("aid-1", "aid-2", sameHostname = true))
                uri.contains("/network-address-history/") -> HttpResponse.ok(mapOf("resources" to emptyList<Any>()))
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
        assertThat(response.deviceCount).isEqualTo(1)
        assertThat(response.failedAids).isEmpty()
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
                uri.contains("/devices/entities/devices/v2") -> HttpResponse.ok(metadataResponse("aid-1"))
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
    @Test
    fun `streaming envelope retains the zero finding winner and retired evidence`() {
        val ids = listOf("aid-1", "aid-2")
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/combined/") -> HttpResponse.ok(combinedDeviceResponse(*ids.toTypedArray()))
                uri.contains("/devices/entities/") -> HttpResponse.ok(metadataResponse(*ids.toTypedArray(), sameHostname = true))
                uri.contains("/network-address-history/") -> HttpResponse.ok(mapOf("resources" to emptyList<Any>()))
                uri.contains("/spotlight/") -> HttpResponse.ok(spotlightPage(emptyList(), null, 0))
                else -> error("Unexpected request: $uri")
            }
        }
        val stored = mutableListOf<StreamingVulnerabilityBatch>()
        client.queryServersWithFiltersStreaming("SERVER", "HIGH", 0, config, 100, 0, 1) { stored.add(it) }
        assertThat(stored).hasSize(1)
        assertThat(stored.single().devices.map { it.crowdStrikeAid }).containsExactly("aid-2")
        assertThat(stored.single().vulnerabilities).isEmpty()
    }

    @Test
    fun `per-host pagination follows short pages when cursor continues`() {
        val pages = ArrayDeque(listOf(
            spotlightPage(listOf(vulnResource("one")), "next", 2),
            spotlightPage(listOf(vulnResource("two")), null, 2)
        ))
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } answers {
            HttpResponse.ok(pages.removeFirst())
        }
        assertThat(client.querySpotlightApi("device-1", "server01", token).map { it.id }).containsExactly("one", "two")
    }

    @Test
    fun `nameless cloud winner uses its instance identity and retires its named sibling`() {
        val requests = mutableListOf<HttpRequest<Any>>()
        every { blockingClient.exchange(capture(requests), Map::class.java) } answers {
            when {
                firstArg<HttpRequest<Any>>().uri.toString().contains("/devices/combined/") ->
                    HttpResponse.ok(combinedDeviceResponse("old", "winner"))
                firstArg<HttpRequest<Any>>().uri.toString().contains("/devices/entities/") ->
                    HttpResponse.ok(mapOf("resources" to listOf(
                        mapOf("device_id" to "old", "hostname" to "old-server", "instance_id" to "i-shared",
                            "first_seen" to "2026-01-01T00:00:00Z"),
                        mapOf("device_id" to "winner", "instance_id" to "i-shared",
                            "first_seen" to "2026-02-01T00:00:00Z")
                    )))
                else -> HttpResponse.ok(spotlightPage(emptyList(), null, 0))
            }
        }
        val devices = mutableListOf<QueriedHost>()
        client.queryServersWithFiltersStreaming("SERVER", "HIGH", 0, config, 100, 0, 100) {
            devices.addAll(it.devices)
        }
        val winner = devices.single()
        assertThat(winner.hostname).isEqualTo("i-shared")
        assertThat(winner.deviceSelection!!.selected.aid).isEqualTo("winner")
        assertThat(winner.deviceSelection!!.superseded.single().aid).isEqualTo("old")
        val spotlight = requests.filter { it.uri.path.contains("/spotlight/") }
        assertThat(spotlight).hasSize(1)
        assertThat(spotlight.single().parameters.get("filter")).contains("winner").doesNotContain("old")
    }

    @Test
    fun `nameless devices with confirmed zero open findings do not block streaming`() {
        val requests = java.util.Collections.synchronizedList(mutableListOf<HttpRequest<Any>>())
        every { blockingClient.exchange(capture(requests), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/combined/") -> HttpResponse.ok(combinedDeviceResponse("known", "nameless"))
                uri.contains("/devices/entities/") -> HttpResponse.ok(mapOf("resources" to listOf(
                    mapOf("device_id" to "known", "hostname" to "server01", "first_seen" to "2026-01-01T00:00:00Z"),
                    mapOf("device_id" to "nameless")
                )))
                uri.contains("/network-address-history/") -> HttpResponse.ok(mapOf("resources" to emptyList<Any>()))
                uri.contains("/spotlight/") -> HttpResponse.ok(spotlightPage(emptyList(), null, 0))
                else -> error("Unexpected request: $uri")
            }
        }
        val result = client.queryServersWithFiltersStreaming("SERVER", "HIGH", 30, config, 100, 0, 200) {
            assertThat(it.vulnerabilities).isEmpty()
        }
        assertThat(result.queriedHosts.map { it.crowdStrikeAid }).containsExactly("known")
        assertThat(result.failedDeviceCount).isZero()
        val spotlight = requests.filter { it.uri.path.contains("/spotlight/") }
        assertThat(spotlight).hasSize(2)
        assertThat(spotlight.first().parameters.get("filter")).isEqualTo("aid:'nameless'+status:'open'")
        assertThat(spotlight.first().parameters.get("limit")).isEqualTo("1")
        assertThat(spotlight.last().parameters.get("filter")).doesNotContain("nameless")
    }

    @Test
    fun `nameless devices abort before replacement unless zero open findings are proven`() {
        val inconclusivePages = listOf(
            spotlightPage(listOf(vulnResource("finding", "nameless")), null, 1),
            spotlightPage(emptyList(), null, 1),
            mapOf("resources" to emptyList<Any>()),
            mapOf("meta" to mapOf("pagination" to mapOf("total" to 0))),
            spotlightPage(emptyList(), null, 0) + mapOf("errors" to listOf(mapOf("code" to 500)))
        )
        inconclusivePages.forEach { page ->
            every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } answers {
                val uri = firstArg<HttpRequest<Any>>().uri.toString()
                when {
                    uri.contains("/devices/combined/") -> HttpResponse.ok(combinedDeviceResponse("known", "nameless"))
                    uri.contains("/devices/entities/") -> HttpResponse.ok(mapOf("resources" to listOf(
                        mapOf("device_id" to "known", "hostname" to "server01", "first_seen" to "2026-01-01T00:00:00Z"),
                        mapOf("device_id" to "nameless", "hostname" to " ")
                    )))
                    uri.contains("/network-address-history/") -> HttpResponse.ok(mapOf("resources" to emptyList<Any>()))
                    uri.contains("/spotlight/") -> {
                        assertThat(firstArg<HttpRequest<Any>>().parameters.get("filter"))
                            .isEqualTo("aid:'nameless'+status:'open'")
                        HttpResponse.ok(page)
                    }
                    else -> error("Unexpected request: $uri")
                }
            }
            assertThatThrownBy {
                client.queryServersWithFiltersStreaming("SERVER", "HIGH", 30, config, 100, 0, 200) {
                    error("Must not store")
                }
            }.isInstanceOf(com.secman.crowdstrike.exception.CrowdStrikeException::class.java)
                .hasMessageContaining("Incomplete device metadata")
        }
    }

    @Test
    fun `missing metadata aborts streaming before any replacement`() {
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/combined/") -> HttpResponse.ok(combinedDeviceResponse("known", "missing"))
                uri.contains("/devices/entities/") -> HttpResponse.ok(metadataResponse("known"))
                uri.contains("/network-address-history/") -> HttpResponse.ok(mapOf("resources" to emptyList<Any>()))
                else -> error("Vulnerability fetch must not start: $uri")
            }
        }
        assertThatThrownBy {
            client.queryServersWithFiltersStreaming("SERVER", "HIGH", 0, config, 100, 0, 200) { error("Must not store") }
        }.isInstanceOf(com.secman.crowdstrike.exception.CrowdStrikeException::class.java)
            .hasMessageContaining("Incomplete device metadata")
    }

    @Test
    fun `canonical finding names preserve original metadata names for representative selection`() {
        val ids = listOf("aid-old", "aid-new")
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            when {
                uri.contains("/devices/combined/") -> HttpResponse.ok(combinedDeviceResponse(*ids.toTypedArray()))
                uri.contains("/devices/entities/") -> HttpResponse.ok(mapOf("resources" to ids.mapIndexed { index, id ->
                    mapOf("device_id" to id, "hostname" to if (index == 0) "old-alias" else "new-alias", "instance_id" to "i-shared", "first_seen" to Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index.toLong()).toString(),
                        "last_seen" to if (index == 0) "2026-01-01T00:00:00Z" else "2026-02-01T00:00:00Z")
                }))
                uri.contains("/network-address-history/") -> HttpResponse.ok(mapOf("resources" to emptyList<Any>()))
                uri.contains("/spotlight/") -> HttpResponse.ok(spotlightPage(listOf(vulnResource("one", "aid-new")), null, 1))
                else -> error("Unexpected request: $uri")
            }
        }
        val batches = mutableListOf<StreamingVulnerabilityBatch>()
        client.queryServersWithFiltersStreaming("SERVER", "HIGH", 0, config, 100, 0, 1) { batches.add(it) }
        assertThat(batches.single().devices.map { it.hostname }).containsExactly("new-alias")
        assertThat(batches.single().devices.maxBy { it.lastSeen!! }.hostname).isEqualTo("new-alias")
    }

    @Test
    fun `hostname discovery follows pagination beyond one hundred device IDs`() {
        val ids = (1..101).map { "aid-$it" }
        val requests = mutableListOf<HttpRequest<Any>>()
        every { blockingClient.exchange(capture(requests), Map::class.java) } answers {
            val offset = firstArg<HttpRequest<Any>>().parameters.get("offset")!!.toInt()
            HttpResponse.ok(mapOf("resources" to ids.drop(offset).take(100),
                "meta" to mapOf("pagination" to mapOf("total" to 101))))
        }
        assertThat(client.getDeviceIdsByHostname("server01", token)).containsExactlyElementsOf(ids)
        assertThat(requests.map { it.parameters.get("offset") }).containsExactly("0", "100")
    }

    @Test
    fun `selected device fetch failure never falls back to a retired ID`() {
        val selectingClient = io.mockk.spyk(client)
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } answers {
            val uri = firstArg<HttpRequest<Any>>().uri.toString()
            if (uri.contains("/devices/entities/")) HttpResponse.ok(metadataResponse("aid-1", "aid-2", sameHostname = true))
            else HttpResponse.ok(deviceQueryResponse("aid-1", "aid-2"))
        }
        every { selectingClient.querySpotlightApi("aid-2", any(), any()) } throws
            com.secman.crowdstrike.exception.CrowdStrikeException("Incomplete selected-device fetch")
        val response = selectingClient.queryVulnerabilities("server01", config)
        assertThat(response.vulnerabilities).isEmpty()
        assertThat(response.failedAids).containsExactly("aid-2")
        io.mockk.verify(exactly = 0) { selectingClient.querySpotlightApi("aid-1", any(), any()) }
    }

}
