package com.secman.controller

import com.secman.repository.UserRepository
import com.secman.testutil.BaseIntegrationTest
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

@MicronautTest(environments = ["test"], transactional = false)
class UserAccessOverviewControllerTest : BaseIntegrationTest() {
    @Inject @field:Client("/") lateinit var client: HttpClient
    @Inject lateinit var users: UserRepository

    @Test
    fun `only ADMIN can inspect a target and responses are not cached`() {
        val suffix = System.nanoTime()
        val admin = users.save(TestDataFactory.createAdminUser("overview-admin-$suffix", "overview-admin-$suffix@example.com"))
        val viewer = users.save(TestDataFactory.createRegularUser("overview-user-$suffix", "overview-user-$suffix@example.com"))
        val path = "/api/users/access-overview?email=${viewer.email}"
        try {
            val anonymous = assertThrows(HttpClientResponseException::class.java) { client.toBlocking().retrieve(HttpRequest.GET<Any>(path)) }
            assertThat(anonymous.status).isEqualTo(HttpStatus.UNAUTHORIZED)
            val denied = assertThrows(HttpClientResponseException::class.java) {
                client.toBlocking().retrieve(HttpRequest.GET<Any>(path).bearerAuth(TestAuthHelper.getAuthToken(client, viewer.username)))
            }
            assertThat(denied.status).isEqualTo(HttpStatus.FORBIDDEN)
            val response = client.toBlocking().exchange(HttpRequest.GET<Any>(path).bearerAuth(TestAuthHelper.getAuthToken(client, admin.username)), Map::class.java)
            assertThat(response.headers["Cache-Control"]).isEqualTo("no-store")
            assertThat(response.body()!!["totalAssets"] as Number).isEqualTo(0)
            assertThat(response.body()!!["globalAccess"]).isEqualTo(false)
            for (field in listOf("assets", "awsAccounts", "adDomains")) {
                assertThat(response.body()!![field] as? List<*>).isNotNull().isEmpty()
            }
        } finally {
            users.delete(viewer)
            users.delete(admin)
        }
    }
}
