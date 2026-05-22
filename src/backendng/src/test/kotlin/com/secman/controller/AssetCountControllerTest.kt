package com.secman.controller

import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestAuthHelper
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf

@DisplayName("Asset Count Controller Tests")
@EnabledIf("com.secman.testutil.DockerAvailable#isDockerAvailable")
class AssetCountControllerTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var assetRepository: AssetRepository

    private lateinit var username: String

    @Serdeable
    data class AssetCountResponse(val count: Long)

    @BeforeEach
    fun setupUser() {
        val suffix = System.nanoTime()
        username = "asset-count-user-$suffix"
        userRepository.save(TestDataFactory.createRegularUser(
            username = username,
            email = "$username@test.com"
        ))
    }

    @Test
    fun `count returns all assets for regular users`() {
        val token = TestAuthHelper.getAuthToken(client, username)
        val expectedCount = assetRepository.count()

        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/assets/count").bearerAuth(token),
            AssetCountResponse::class.java
        )

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat(response.body()!!.count).isEqualTo(expectedCount)
    }
}
