package com.secman.crowdstrike.client

import com.secman.crowdstrike.auth.CrowdStrikeAuthService
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.model.AuthToken
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class CrowdStrikeApiClientImplInstalledProductsTest {
    private val blockingClient = mockk<BlockingHttpClient>()
    private val httpClient = mockk<HttpClient> {
        every { toBlocking() } returns blockingClient
    }
    private val authService = mockk<CrowdStrikeAuthService> {
        every { authenticate(any()) } returns AuthToken("token", Instant.now().plusSeconds(3600))
    }
    private val client = CrowdStrikeApiClientImpl(httpClient, authService)
    private val config = FalconConfigDto(clientId = "client", clientSecret = "secret")

    @Test
    fun `queryInstalledProductsStreaming retries same page when CrowdStrike closes connection before response`() {
        val firstPage = mapOf(
            "resources" to listOf(
                mapOf(
                    "id" to "app-1",
                    "name" to "Product One",
                    "host" to mapOf("hostname" to "server01")
                )
            ),
            "meta" to mapOf("pagination" to mapOf("after" to "next-page", "total" to 2))
        )
        val secondPage = mapOf(
            "resources" to listOf(
                mapOf(
                    "id" to "app-2",
                    "name" to "Product Two",
                    "host" to mapOf("hostname" to "server02")
                )
            ),
            "meta" to mapOf("pagination" to mapOf("total" to 2))
        )

        every {
            blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java)
        } returns HttpResponse.ok(firstPage) andThenThrows HttpClientException(
            "Connection closed before response was received"
        ) andThen HttpResponse.ok(secondPage)

        val batches = mutableListOf<List<String>>()
        val total = client.queryInstalledProductsStreaming("SERVER", config, limit = 1000) { products ->
            batches += products.map { it.externalId.orEmpty() }
        }

        assertThat(total).isEqualTo(2)
        assertThat(batches).containsExactly(listOf("app-1"), listOf("app-2"))
        verify(exactly = 3) { blockingClient.exchange(any<HttpRequest<Any>>(), Map::class.java) }
    }
}
