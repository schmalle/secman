package com.secman.controller

import com.secman.domain.Norm
import com.secman.domain.Requirement
import com.secman.domain.User
import com.secman.repository.NormRepository
import com.secman.repository.RequirementRepository
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Coverage for admin "delete all requirements" also removing all standards (norms),
 * since norms only exist to be attached to requirements.
 */
@MicronautTest(environments = ["test"], transactional = false)
class RequirementDeleteAllIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var requirementRepository: RequirementRepository

    @Inject
    lateinit var normRepository: NormRepository

    private lateinit var adminUser: User
    private lateinit var reqUser: User

    @BeforeEach
    fun setUp() {
        val suffix = System.nanoTime()
        adminUser = userRepository.save(TestDataFactory.createAdminUser("reqdel-admin-$suffix", "reqdel-admin-$suffix@test.com"))
        reqUser = userRepository.save(TestDataFactory.createUserWithRoles("reqdel-req-$suffix", "reqdel-req-$suffix@test.com", User.Role.USER, User.Role.REQ))

        requirementRepository.deleteAll()
        normRepository.deleteAll()
    }

    @Test
    fun `admin delete-all requirements also deletes all norms`() {
        val norm = normRepository.save(Norm(name = "ISO 27001-${System.nanoTime()}", version = "2022"))
        requirementRepository.save(
            Requirement(
                internalId = "RD-${System.nanoTime() % 1_000_000_000_000L}",
                shortreq = "Test requirement",
                norms = mutableSetOf(norm)
            )
        )

        assertThat(requirementRepository.count()).isEqualTo(1)
        assertThat(normRepository.count()).isEqualTo(1)

        val adminToken = TestAuthHelper.getAuthToken(client, adminUser.username)
        val response = client.toBlocking().exchange(
            HttpRequest.DELETE<Any>("/api/requirements/all").bearerAuth(adminToken),
            Map::class.java
        )

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat(response.body()!!["deletedRequirements"]).isEqualTo(1)
        assertThat(response.body()!!["deletedNorms"]).isEqualTo(1)

        assertThat(requirementRepository.count()).isEqualTo(0)
        assertThat(normRepository.count()).isEqualTo(0)
    }

    @Test
    fun `non-admin cannot delete all requirements`() {
        requirementRepository.save(
            Requirement(
                internalId = "RD-${System.nanoTime() % 1_000_000_000_000L}",
                shortreq = "Test requirement"
            )
        )

        val reqToken = TestAuthHelper.getAuthToken(client, reqUser.username)

        val exception = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.DELETE<Any>("/api/requirements/all").bearerAuth(reqToken),
                Map::class.java
            )
        }
        assertThat(exception.status).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(requirementRepository.count()).isEqualTo(1)
    }
}
