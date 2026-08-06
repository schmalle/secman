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

@MicronautTest(environments = ["test"], transactional = false)
@DisplayName("Workgroup Visibility Integration Tests")
class WorkgroupVisibilityIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var workgroupRepository: WorkgroupRepository

    @Inject
    lateinit var transactionOperations: TransactionOperations<Connection>

    @Serdeable
    data class LoginRequest(val username: String, val password: String)

    @Serdeable
    data class AuthResponse(
        val username: String,
        val workgroupCount: Long
    )

    @Serdeable
    data class WorkgroupListResponse(
        val id: Long,
        val name: String
    )

    @Serdeable
    data class WorkgroupTreeResponse(
        val id: Long,
        val name: String
    )

    @Test
    fun `regular user sees effective workgroups and auth count matches`() {
        val suffix = System.nanoTime()
        val parent = workgroupRepository.save(
            Workgroup(
                name = "Visibility Parent $suffix",
                criticality = Criticality.MEDIUM
            )
        )
        workgroupRepository.save(
            Workgroup(
                name = "Visibility Child $suffix",
                criticality = Criticality.MEDIUM,
                parent = parent
            )
        )
        val savedUser = userRepository.save(
            TestDataFactory.createRegularUser(
                username = "visibility-user-$suffix",
                email = "visibility-user-$suffix@test.com"
            )
        )
        assignUserToWorkgroup(savedUser.id!!, parent.id!!)

        assertThat(workgroupRepository.countEffectiveWorkgroupsByUserEmail(savedUser.email)).isEqualTo(2)

        val loginResponse = client.toBlocking().exchange(
            HttpRequest.POST("/api/auth/login", LoginRequest(savedUser.username, TestDataFactory.DEFAULT_PASSWORD)),
            AuthResponse::class.java
        )
        val cookie = loginResponse.cookies.get(AuthCookieService.AUTH_COOKIE_NAME)
            ?: throw IllegalStateException("Login response did not include ${AuthCookieService.AUTH_COOKIE_NAME} cookie")

        val workgroups = client.toBlocking().retrieve(
            HttpRequest.GET<Any>("/api/workgroups").cookie(cookie),
            Argument.listOf(WorkgroupListResponse::class.java)
        )
        val roots = client.toBlocking().retrieve(
            HttpRequest.GET<Any>("/api/workgroups/root").cookie(cookie),
            Argument.listOf(WorkgroupTreeResponse::class.java)
        )
        val status = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/auth/status").cookie(cookie),
            AuthResponse::class.java
        )

        assertThat(loginResponse.status).isEqualTo(HttpStatus.OK)
        assertThat(loginResponse.body()!!.workgroupCount).isEqualTo(2)
        assertThat(workgroups.map { it.name }).containsExactlyInAnyOrder(
            "Visibility Parent $suffix",
            "Visibility Child $suffix"
        )
        assertThat(roots.map { it.name }).containsExactly("Visibility Parent $suffix")
        assertThat(status.body()!!.workgroupCount).isEqualTo(2)
    }

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
