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
                    "resources" to ids,
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
            it.contains("product_type_desc:'Server'") && it.contains("last_seen:>'now-30d'")
        }
        assertThat(decodedUris).anyMatch {
            it.contains("product_type_desc:'Domain Controller'") && it.contains("last_seen:>'now-30d'")
        }
        assertThat(decodedUris).noneMatch { it.contains("product_type_desc:'Workstation'") }
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8)
}
