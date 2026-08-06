package com.secman.mcp.tools

import com.secman.domain.AssessmentBasisType
import com.secman.domain.AwsAccountRiskAssessment
import com.secman.domain.Release
import com.secman.domain.RiskAssessment
import com.secman.domain.User
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AwsAccountRiskAssessmentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ListAwsAccountRiskAssessmentsToolTest {

    private val trackingRepository = mockk<AwsAccountRiskAssessmentRepository>(relaxed = true)
    private val tool = ListAwsAccountRiskAssessmentsTool(trackingRepository)

    private val release = Release(id = 42L, version = "2.3.0", name = "Q3 baseline")
    private val champion = user(1L, "champ", "champ@corp.com", User.Role.SECCHAMPION)
    private val owner = user(7L, "alice", "alice@corp.com", User.Role.USER)

    private fun user(id: Long, name: String, email: String, role: User.Role) =
        User(id = id, username = name, email = email, passwordHash = "x", roles = mutableSetOf(role))

    private fun ctx(isAdmin: Boolean = true, hasDelegation: Boolean = true) =
        mockk<McpExecutionContext>().also {
            every { it.hasDelegation() } returns hasDelegation
            every { it.isAdmin } returns isAdmin
            every { it.delegatedUserRoles } returns if (isAdmin) setOf("ADMIN") else setOf("USER")
        }

    private fun tracking(pinned: Release? = release) = AwsAccountRiskAssessment(
        id = 300L,
        awsAccountId = "111111111111",
        ownerEmail = "alice@corp.com",
        riskAssessment = RiskAssessment(
            id = 1000L,
            startDate = LocalDate.of(2026, 8, 2),
            endDate = LocalDate.of(2026, 8, 9),
            assessmentBasisType = AssessmentBasisType.ASSET,
            assessmentBasisId = 55L,
            assessor = champion,
            requestor = champion
        ).also {
            it.respondent = owner
            it.lockedRelease = pinned
        },
        useCaseName = "Cloud Onboarding"
    )

    @Suppress("UNCHECKED_CAST")
    private fun rows(result: McpToolResult) =
        ((result as McpToolResult.Success).content as Map<String, Any?>)["assessments"] as List<Map<String, Any?>>

    @BeforeEach
    fun setup() {
        every { trackingRepository.findByFilters(any(), any(), any()) } returns listOf(tracking())
    }

    // --- guards ---------------------------------------------------------------

    @Test
    fun `delegation is required`() = runBlocking<Unit> {
        val result = tool.execute(emptyMap(), ctx(hasDelegation = false))

        assertThat((result as McpToolResult.Error).code).isEqualTo("DELEGATION_REQUIRED")
    }

    @Test
    fun `non-admin is rejected`() = runBlocking<Unit> {
        val result = tool.execute(emptyMap(), ctx(isAdmin = false))

        assertThat((result as McpToolResult.Error).code).isEqualTo("ADMIN_REQUIRED")
        verify(exactly = 0) { trackingRepository.findByFilters(any(), any(), any()) }
    }

    @Test
    fun `limit outside 1 to 100 is rejected`() = runBlocking<Unit> {
        assertThat((tool.execute(mapOf("limit" to 0), ctx()) as McpToolResult.Error).code)
            .isEqualTo("INVALID_ARGUMENT")
        assertThat((tool.execute(mapOf("limit" to 101), ctx()) as McpToolResult.Error).code)
            .isEqualTo("INVALID_ARGUMENT")
    }

    // --- behaviour ------------------------------------------------------------

    @Test
    fun `returns the pinned requirements version alongside the assessment`() = runBlocking<Unit> {
        val row = rows(tool.execute(emptyMap(), ctx())).single()

        assertThat(row["riskAssessmentId"]).isEqualTo(1000L)
        assertThat(row["awsAccountId"]).isEqualTo("111111111111")
        assertThat(row["ownerEmail"]).isEqualTo("alice@corp.com")
        assertThat(row["useCase"]).isEqualTo("Cloud Onboarding")
        assertThat(row["releaseVersion"]).isEqualTo("2.3.0")
        assertThat(row["releaseName"]).isEqualTo("Q3 baseline")
        assertThat(row["assessor"]).isEqualTo("champ@corp.com")
        assertThat(row["respondent"]).isEqualTo("alice@corp.com")
        assertThat(row["startDate"]).isEqualTo("2026-08-02")
        assertThat(row["endDate"]).isEqualTo("2026-08-09")
        assertThat(row["status"]).isEqualTo("STARTED")
    }

    @Test
    fun `assessments started before release pinning report a null version`() = runBlocking<Unit> {
        every { trackingRepository.findByFilters(any(), any(), any()) } returns listOf(tracking(pinned = null))

        val row = rows(tool.execute(emptyMap(), ctx())).single()

        assertThat(row["releaseVersion"]).isNull()
        assertThat(row["releaseName"]).isNull()
    }

    @Test
    fun `filters are passed through, blank ones as null`() = runBlocking<Unit> {
        tool.execute(
            mapOf("awsAccountId" to "111111111111", "ownerEmail" to "  ", "status" to "STARTED"),
            ctx()
        )

        verify { trackingRepository.findByFilters("111111111111", null, "STARTED") }
    }

    @Test
    fun `limit caps the returned rows`() = runBlocking<Unit> {
        every { trackingRepository.findByFilters(any(), any(), any()) } returns
            List(5) { tracking() }

        assertThat(rows(tool.execute(mapOf("limit" to 2), ctx()))).hasSize(2)
    }

    @Test
    fun `repository failure becomes EXECUTION_ERROR`() = runBlocking<Unit> {
        every { trackingRepository.findByFilters(any(), any(), any()) } throws RuntimeException("db down")

        val result = tool.execute(emptyMap(), ctx())

        assertThat((result as McpToolResult.Error).code).isEqualTo("EXECUTION_ERROR")
    }
}
