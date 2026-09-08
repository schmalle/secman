package com.secman.controller

import com.secman.domain.Criticality
import com.secman.domain.Workgroup
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import com.secman.service.AuthCookieService
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import io.micronaut.core.type.Argument
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection

/**
 * A01 Broken Access Control regression coverage for
 * `GET /api/workgroups/{id}/{ancestors,descendants}`.
 *
 * Prior to this fix, both endpoints took no `Authentication` parameter and never
 * checked membership — unlike every sibling endpoint on this controller
 * (getWorkgroup, getChildren, getRootWorkgroups, getWorkgroupTree), which all gate
 * on `isMemberOrAdmin` / `accessibleWorkgroupIdsOrNull`. Any authenticated user
 * (including one with no workgroup memberships) could read the ancestor chain and
 * full descendant subtree of an arbitrary workgroup id.
 */
@MicronautTest(environments = ["test"], transactional = false)
@DisplayName("Workgroup hierarchy endpoint access control")
class WorkgroupHierarchyAccessControlIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var workgroupRepository: WorkgroupRepository

    @Inject
    lateinit var transactionOperations: TransactionOperations<Connection>

    /** Login payload; the response cookie is the only thing this test needs from auth. */
    @Serdeable
    data class LoginRequest(val username: String, val password: String)

    @Test
    fun `unrelated authenticated user cannot read ancestors or descendants of a workgroup they do not belong to`() {
        val suffix = System.nanoTime()
        val workgroup = workgroupRepository.save(
            Workgroup(name = "Hierarchy Outsider $suffix", criticality = Criticality.MEDIUM)
        )
        val outsider = userRepository.save(
            TestDataFactory.createRegularUser(
                username = "wg-hier-outsider-$suffix",
                email = "wg-hier-outsider-$suffix@test.com"
            )
        )
        val cookie = login(outsider.username)

        val ancestorsEx = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/workgroups/${workgroup.id}/ancestors").cookie(cookie),
                Argument.listOf(Map::class.java)
            )
        }
        assertThat(ancestorsEx.status).isEqualTo(HttpStatus.NOT_FOUND)

        val descendantsEx = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/workgroups/${workgroup.id}/descendants").cookie(cookie),
                Argument.listOf(Map::class.java)
            )
        }
        assertThat(descendantsEx.status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `direct member can read ancestors and descendants of their own workgroup`() {
        val suffix = System.nanoTime()
        val workgroup = workgroupRepository.save(
            Workgroup(name = "Hierarchy Member $suffix", criticality = Criticality.MEDIUM)
        )
        val member = userRepository.save(
            TestDataFactory.createRegularUser(
                username = "wg-hier-member-$suffix",
                email = "wg-hier-member-$suffix@test.com"
            )
        )
        assignUserToWorkgroup(member.id!!, workgroup.id!!)
        val cookie = login(member.username)

        val ancestors = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/workgroups/${workgroup.id}/ancestors").cookie(cookie),
            Argument.listOf(Map::class.java)
        )
        val descendants = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/workgroups/${workgroup.id}/descendants").cookie(cookie),
            Argument.listOf(Map::class.java)
        )

        assertThat(ancestors.status).isEqualTo(HttpStatus.OK)
        assertThat(descendants.status).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `admin can read ancestors and descendants of any workgroup`() {
        val suffix = System.nanoTime()
        val workgroup = workgroupRepository.save(
            Workgroup(name = "Hierarchy Admin $suffix", criticality = Criticality.MEDIUM)
        )
        val admin = userRepository.save(
            TestDataFactory.createAdminUser(
                username = "wg-hier-admin-$suffix",
                email = "wg-hier-admin-$suffix@test.com"
            )
        )
        val cookie = login(admin.username)

        val ancestors = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/workgroups/${workgroup.id}/ancestors").cookie(cookie),
            Argument.listOf(Map::class.java)
        )
        val descendants = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/workgroups/${workgroup.id}/descendants").cookie(cookie),
            Argument.listOf(Map::class.java)
        )

        assertThat(ancestors.status).isEqualTo(HttpStatus.OK)
        assertThat(descendants.status).isEqualTo(HttpStatus.OK)
    }

    private fun login(username: String) =
        client.toBlocking().exchange(
            HttpRequest.POST("/api/auth/login", LoginRequest(username, TestDataFactory.DEFAULT_PASSWORD)),
            Argument.of(Map::class.java)
        ).cookies.get(AuthCookieService.AUTH_COOKIE_NAME)
            ?: throw IllegalStateException("Login response did not include ${AuthCookieService.AUTH_COOKIE_NAME} cookie")

    private fun assignUserToWorkgroup(userId: Long, workgroupId: Long) {
        transactionOperations.executeWrite<Unit> { status ->
            status.connection.prepareStatement("INSERT INTO user_workgroups (user_id, workgroup_id) VALUES (?, ?)").use { ps ->
                ps.setLong(1, userId)
                ps.setLong(2, workgroupId)
                ps.executeUpdate()
            }
        }
    }
}
