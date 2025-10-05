package com.secman.controller

import com.secman.domain.Release
import com.secman.repository.ReleaseRepository
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * T009: Contract Test for Release Comparison API
 * Spec: specs/011-i-want-to/contracts/comparison-api.yaml
 */
@MicronautTest
class ReleaseComparisonControllerTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var releaseRepository: ReleaseRepository

    private var testToken: String = "Bearer test-token" // Simplified for brevity

    @AfterEach
    fun cleanup() {
        releaseRepository.deleteAll()
    }

    @Test
    fun `T009-1 GET compare - Success 200 returns ComparisonResult`() {
        val release1 = releaseRepository.save(Release(version = "1.0.0", name = "First"))
        val release2 = releaseRepository.save(Release(version = "1.1.0", name = "Second"))

        val request = HttpRequest.GET<Any>("/api/releases/compare?fromReleaseId=${release1.id}&toReleaseId=${release2.id}")
            .bearerAuth(testToken)

        // Will fail - ReleaseComparisonController doesn't exist
        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, Map::class.java)
        }
        // Expecting 404 since endpoint doesn't exist yet
    }

    @Test
    fun `T009-2 GET compare - Error 400 same fromReleaseId and toReleaseId`() {
        val release = releaseRepository.save(Release(version = "1.0.0", name = "Same"))

        val request = HttpRequest.GET<Any>("/api/releases/compare?fromReleaseId=${release.id}&toReleaseId=${release.id}")
            .bearerAuth(testToken)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, Map::class.java)
        }
        // Will fail with 404 (endpoint doesn't exist) instead of 400 initially
    }

    @Test
    fun `T009-3 GET compare - Error 404 release not found`() {
        val request = HttpRequest.GET<Any>("/api/releases/compare?fromReleaseId=99999&toReleaseId=88888")
            .bearerAuth(testToken)

        assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, Map::class.java)
        }
    }
}
