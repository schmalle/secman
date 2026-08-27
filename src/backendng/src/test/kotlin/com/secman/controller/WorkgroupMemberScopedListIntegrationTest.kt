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
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.sql.Connection

/**
 * Regression coverage for the member-scoped `list` handlers on a workgroup's child resources.
 *
 * Both controllers authorize through `isMemberOrAdmin`, which reads the LAZY `Workgroup.users`
 * collection. `add`/`remove` are `@Transactional` so a session is open; `list` was not, so a
 * non-ADMIN member got a 500 (`LazyInitializationException`) instead of their domains/accounts.
 *
 * The sibling Mockk unit tests cannot catch this: they build the workgroup with a plain
 * `mutableSetOf(...)`, which is never a Hibernate `PersistentSet` and so never needs a session.
 * Only a real request against a real entity reproduces it — hence this integration test.
 *
 * ADMIN callers were unaffected (`isMemberOrAdmin` short-circuits on the role before touching
 * the collection), so the caller here is deliberately a plain member.
 */
@MicronautTest(environments = ["test"], transactional = false)
@DisplayName("Workgroup member-scoped child resource listing")
class WorkgroupMemberScopedListIntegrationTest : BaseIntegrationTest() {

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
    fun `direct member can list workgroup ad domains and aws accounts`() {
        val suffix = System.nanoTime()
        val workgroup = workgroupRepository.save(
            Workgroup(name = "Member Scoped $suffix", criticality = Criticality.MEDIUM)
        )
        val user = userRepository.save(
            TestDataFactory.createRegularUser(
                username = "member-scoped-$suffix",
                email = "member-scoped-$suffix@test.com"
            )
        )
        assignUserToWorkgroup(user.id!!, workgroup.id!!)

        val cookie = login(user.username)

        val adDomains = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/workgroups/${workgroup.id}/ad-domains").cookie(cookie),
            Argument.listOf(Map::class.java)
        )
        val awsAccounts = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/workgroups/${workgroup.id}/aws-accounts").cookie(cookie),
            Argument.listOf(Map::class.java)
        )

        assertThat(adDomains.status).isEqualTo(HttpStatus.OK)
        assertThat(adDomains.body()).isEmpty()
        assertThat(awsAccounts.status).isEqualTo(HttpStatus.OK)
        assertThat(awsAccounts.body()).isEmpty()
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
