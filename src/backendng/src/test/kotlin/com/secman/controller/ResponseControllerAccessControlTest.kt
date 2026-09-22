package com.secman.controller

import com.secman.domain.Asset
import com.secman.domain.RiskAssessment
import com.secman.repository.AssetRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.UserRepository
import com.secman.service.AuthCookieService
import com.secman.service.AssessmentWorkflowService
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * A01 Broken Access Control regression coverage for ResponseController's
 * "authenticated" (non-token) assessment endpoints. Prior to this fix, any
 * authenticated user could read and mutate another user's risk-assessment
 * responses by guessing/incrementing the assessment id, since the endpoints
 * only checked @Secured(IS_AUTHENTICATED) and never verified the caller was
 * an explicitly assigned participant or held a privileged role.
 */
@MicronautTest(environments = ["test"], transactional = false)
@DisplayName("ResponseController Access Control Tests")
class ResponseControllerAccessControlTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var riskAssessmentRepository: RiskAssessmentRepository

    @Inject
    lateinit var workflow: AssessmentWorkflowService

    @Serdeable
    data class LoginRequest(val username: String, val password: String)

    @Serdeable
    data class LoginResponse(val username: String)

    @Serdeable
    data class BulkSaveResponseBody(val responses: List<Map<String, Any>>)

    private fun login(username: String): io.micronaut.http.cookie.Cookie {
        val response = client.toBlocking().exchange(
            HttpRequest.POST("/api/auth/login", LoginRequest(username, TestDataFactory.DEFAULT_PASSWORD)),
            LoginResponse::class.java
        )
        return response.cookies.get(AuthCookieService.AUTH_COOKIE_NAME)
            ?: throw IllegalStateException("Login response did not include ${AuthCookieService.AUTH_COOKIE_NAME} cookie")
    }

    private fun setUp(suffix: Long): Triple<RiskAssessment, String, String> {
        val assessor = userRepository.save(
            TestDataFactory.createRegularUser(username = "resp-assessor-$suffix", email = "resp-assessor-$suffix@test.com")
        )
        val outsider = userRepository.save(
            TestDataFactory.createRegularUser(username = "resp-outsider-$suffix", email = "resp-outsider-$suffix@test.com")
        )
        val asset = assetRepository.save(TestDataFactory.createAsset(name = "resp-asset-$suffix"))
        val assessment = riskAssessmentRepository.save(
            RiskAssessment(
                startDate = LocalDate.now(),
                endDate = LocalDate.now().plusDays(7),
                asset = asset,
                assessor = assessor,
                requestor = assessor
            )
        )
        workflow.initialize(assessment)
        return Triple(assessment, assessor.username, outsider.username)
    }

    @Test
    fun `unrelated authenticated user cannot read another user's assessment responses`() {
        val (assessment, _, outsiderUsername) = setUp(System.nanoTime())
        val cookie = login(outsiderUsername)

        val ex = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange<Any, Any>(
                HttpRequest.GET<Any>("/api/responses/assessment/${assessment.id}/authenticated").cookie(cookie)
            )
        }
        assertThat(ex.status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `unrelated authenticated user cannot bulk-save another user's assessment responses`() {
        val (assessment, _, outsiderUsername) = setUp(System.nanoTime())
        val cookie = login(outsiderUsername)

        val ex = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange<BulkSaveResponseBody, Any>(
                HttpRequest.POST(
                    "/api/responses/assessment/${assessment.id}/bulk-save",
                    BulkSaveResponseBody(
                        responses = listOf(mapOf("requirementId" to 1, "answerType" to "YES"))
                    )
                ).cookie(cookie)
            )
        }
        assertThat(ex.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `assessment's own assessor can read their assessment responses`() {
        val (assessment, assessorUsername, _) = setUp(System.nanoTime())
        val cookie = login(assessorUsername)

        val response = client.toBlocking().exchange<Any, Any>(
            HttpRequest.GET<Any>("/api/responses/assessment/${assessment.id}/authenticated").cookie(cookie)
        )
        assertThat(response.status).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `admin can read any assessment's responses`() {
        val (assessment, _, _) = setUp(System.nanoTime())
        val admin = userRepository.save(
            TestDataFactory.createAdminUser(username = "resp-admin-${System.nanoTime()}", email = "resp-admin-${System.nanoTime()}@test.com")
        )
        val cookie = login(admin.username)

        val response = client.toBlocking().exchange<Any, Any>(
            HttpRequest.GET<Any>("/api/responses/assessment/${assessment.id}/authenticated").cookie(cookie)
        )
        assertThat(response.status).isEqualTo(HttpStatus.OK)
    }
    @Test
    fun `suspended user cannot obtain a new password session`() {
        val suffix = System.nanoTime()
        val user = userRepository.save(TestDataFactory.createRegularUser("suspended-$suffix", "suspended-$suffix@test.com").apply { enabled = false })
        val denied = org.junit.jupiter.api.assertThrows<HttpClientResponseException> { login(user.username) }
        assertThat(denied.status).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Inject lateinit var requirements: com.secman.repository.RequirementRepository
    @Inject lateinit var responses: com.secman.repository.ResponseRepository

    @Test
    fun `task assessor risk creation returns no linked asset or user graph`() {
        val suffix = System.nanoTime()
        val (assessment, assessorUsername, _) = setUp(suffix)
        assessment.authorshipComplete = false
        riskAssessmentRepository.update(assessment)
        val requirement = requirements.save(com.secman.domain.Requirement(internalId = "R-$suffix", shortreq = "Noncompliance"))
        responses.save(com.secman.domain.Response(riskAssessment = assessment, requirement = requirement,
            answerType = com.secman.domain.AnswerType.NO, answer = "NO"))
        val cookie = login(assessorUsername)
        val hiddenAsset = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(HttpRequest.GET<Any>("/api/assets/${assessment.asset!!.id}").cookie(cookie), Map::class.java)
        }
        assertThat(hiddenAsset.status).isEqualTo(HttpStatus.NOT_FOUND)
        val result = client.toBlocking().exchange(
            HttpRequest.POST("/api/responses/assessment/${assessment.id}/create-risk",
                ResponseController.CreateRiskFromResponseRequest(requirement.id!!, "Track assessment risk")).cookie(cookie), Map::class.java)
        assertThat(result.status).isEqualTo(HttpStatus.CREATED)
        assertThat(result.body()!!.keys).containsExactlyInAnyOrder("id", "name", "status")
    }

}
