package com.secman.crowdstrike.client

import com.secman.crowdstrike.auth.CrowdStrikeAuthService
import com.secman.crowdstrike.dto.DeviceType
import com.secman.crowdstrike.model.AuthToken
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

class CrowdStrikeApiClientImplDeviceScopeTest {
    private val blockingClient = mockk<BlockingHttpClient>()
    private val httpClient = mockk<HttpClient> {
        every { toBlocking() } returns blockingClient
    }
    private val client = CrowdStrikeApiClientImpl(httpClient, mockk<CrowdStrikeAuthService>())
    private val token = AuthToken("token", Instant.now().plusSeconds(3600))

    @Test
    fun `server family queries both exact Falcon types and deduplicates aids`() {
        val requests = mutableListOf<HttpRequest<Any>>()
        every { blockingClient.exchange(capture(requests), Map::class.java) } answers {
            val uri = decode(firstArg<HttpRequest<Any>>().uri.toString())
            val ids = if (uri.contains("product_type_desc:'Domain Controller'")) {
                listOf("aid-dc", "aid-shared")
            } else {
                listOf("aid-server", "aid-shared")
            }
            HttpResponse.ok(
                mapOf(
                    "resources" to ids.map { mapOf("device_id" to it) },
                    "meta" to mapOf("pagination" to mapOf("total" to ids.size))
                )
            )
        }

        val result = client.getDeviceIdsFiltered(
            token = token,
            deviceType = DeviceType.SERVER_FAMILY,
            limit = 100,
            lastSeenDays = 30
        )

        assertThat(result).containsExactly("aid-server", "aid-shared", "aid-dc")
        assertThat(requests).hasSize(2)
        val decodedUris = requests.map { decode(it.uri.toString()) }
        assertThat(decodedUris).anyMatch {
            it.contains("product_type_desc:'Server'") && it.contains("last_seen:>'")
        }
        assertThat(decodedUris).anyMatch {
            it.contains("product_type_desc:'Domain Controller'") && it.contains("last_seen:>'")
        }
        assertThat(decodedUris).noneMatch { it.contains("product_type_desc:'Workstation'") }
    }

    @Test
    fun `discovery follows next cursor with stable ordering and rejects incomplete terminal pages`() {
        val requests = mutableListOf<HttpRequest<Any>>()
        every { blockingClient.exchange(capture(requests), Map::class.java) } returnsMany listOf(
            HttpResponse.ok(mapOf("resources" to listOf(mapOf("device_id" to "one")),
                "meta" to mapOf("pagination" to mapOf("total" to 2, "next" to "opaque-cursor")))),
            HttpResponse.ok(mapOf("resources" to listOf(mapOf("device_id" to "two")),
                "meta" to mapOf("pagination" to mapOf("total" to 2))))
        )
        assertThat(client.getDeviceIdsFiltered(token)).containsExactly("one", "two")
        assertThat(requests.last().parameters.get("offset")).isEqualTo("opaque-cursor")
        assertThat(requests.map { it.parameters.get("sort") }).containsOnly("device_id.asc")
        every { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) } returns
            HttpResponse.ok(mapOf("resources" to listOf(mapOf("device_id" to "one")),
                "meta" to mapOf("pagination" to mapOf("total" to 2))))
        org.assertj.core.api.Assertions.assertThatThrownBy { client.getDeviceIdsFiltered(token) }
            .hasMessageContaining("Incomplete")
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8)
}
