package com.secman.controller

import com.secman.repository.UserRepository
import com.secman.testutil.TestAuthHelper
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/** The controller check is the boundary (CLAUDE.md A01): every endpoint is ADMIN-only. */
@MicronautTest(environments = ["test"], transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserCleanupControllerSecurityTest {

    @Inject @field:Client("/") lateinit var client: HttpClient
    @Inject lateinit var userRepository: UserRepository

    private val tag = UUID.randomUUID().toString().take(8)
    private val ids = mutableListOf<Long>()
    private lateinit var userToken: String
    private lateinit var adminToken: String

    @BeforeAll
    fun users() {
        // SECCHAMPION manages grants but is deliberately not allowed to delete accounts.
        ids += userRepository.save(TestDataFactory.createSecChampionUser("champ-$tag", "champ-$tag@secman.test")).id!!
        ids += userRepository.save(TestDataFactory.createAdminUser("admin-$tag", "admin-$tag@secman.test")).id!!
        userToken = TestAuthHelper.getAuthToken(client, "champ-$tag")
        adminToken = TestAuthHelper.getAuthToken(client, "admin-$tag")
    }

    @AfterAll
    fun cleanUp() = ids.forEach { userRepository.deleteById(it) }

    @Test
    fun `non-admin gets 403 on every endpoint`() {
        val requests = listOf(
            HttpRequest.GET<Any>("/api/user-cleanup/config"),
            HttpRequest.GET<Any>("/api/user-cleanup/preview"),
            HttpRequest.GET<Any>("/api/user-cleanup/runs"),
            HttpRequest.GET<Any>("/api/user-cleanup/runs/1"),
            HttpRequest.POST("/api/user-cleanup/run-now", "")
        )
        requests.forEach { request ->
            val error = catchThrowableOfType(HttpClientResponseException::class.java) {
                client.toBlocking().exchange(request.bearerAuth(userToken), String::class.java)
            }
            assertThat(error?.status).describedAs(request.path).isEqualTo(HttpStatus.FORBIDDEN)
        }
    }

    @Test
    fun `admin reads config and a disabled run-now is refused`() {
        val config = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/user-cleanup/config").bearerAuth(adminToken), String::class.java
        )
        assertThat(config.status).isEqualTo(HttpStatus.OK)
        assertThat(config.body()).contains("\"enabled\":false")

        val error = catchThrowableOfType(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(
                HttpRequest.POST("/api/user-cleanup/run-now", "").bearerAuth(adminToken), String::class.java
            )
        }
        assertThat(error?.status).isEqualTo(HttpStatus.CONFLICT)
    }
}
