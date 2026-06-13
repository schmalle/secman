package com.secman.controller

import com.secman.domain.Criticality
import com.secman.domain.User
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
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Workgroup Visibility Integration Tests")
class WorkgroupVisibilityIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var workgroupRepository: WorkgroupRepository

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
        val user = userRepository.save(
            TestDataFactory.createRegularUser(
                username = "visibility-user-$suffix",
                email = "visibility-user-$suffix@test.com"
            )
        )
        assignUserToWorkgroup(user, parent)

        assertThat(workgroupRepository.countEffectiveWorkgroupsByUserEmail(user.email)).isEqualTo(2)

        val loginResponse = client.toBlocking().exchange(
            HttpRequest.POST("/api/auth/login", LoginRequest(user.username, TestDataFactory.DEFAULT_PASSWORD)),
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

    private fun assignUserToWorkgroup(user: User, workgroup: Workgroup) {
        val managed = userRepository.findByIdWithWorkgroups(user.id!!).orElseThrow()
        managed.workgroups.add(workgroup)
        userRepository.update(managed)
    }
}
