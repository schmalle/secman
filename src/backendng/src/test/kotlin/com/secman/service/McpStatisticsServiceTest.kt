package com.secman.service

import com.secman.domain.McpPermission
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.UseCaseRepository
import com.secman.repository.UserRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.projection.SeverityDistributionRow
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.time.Instant

class McpStatisticsServiceTest {
    private val assets = mockk<AssetRepository>(relaxed = true)
    private val vulnerabilities = mockk<VulnerabilityRepository>(relaxed = true)
    private val users = mockk<UserRepository>(relaxed = true)
    private val requirements = mockk<RequirementRepository>(relaxed = true)
    private val useCases = mockk<UseCaseRepository>(relaxed = true)
    private val assessments = mockk<RiskAssessmentRepository>(relaxed = true)
    private val service = McpStatisticsService(mockk(relaxed = true), assets, vulnerabilities, users, requirements, useCases, assessments)

    @Test
    fun `global statistics include requested counts and seven day login activity`() {
        every { assets.count() } returns 12
        every { vulnerabilities.count() } returns 34
        every { users.count() } returns 8
        every { users.countByLastLoginGreaterThanEqual(any<Instant>()) } returns 5
        every { users.countByLastLoginIsNull() } returns 2
        every { requirements.count() } returns 21
        every { useCases.count() } returns 4
        every { assessments.count() } returns 7
        every { assessments.countByStatus("STARTED") } returns 3
        every { assessments.countByStatus("COMPLETED") } returns 4

        val result = service.global(context(setOf("ADMIN"), isAdmin = true))

        assertThat(result["assets"]).isEqualTo(12L)
        assertThat(result["vulnerabilities"]).isEqualTo(34L)
        assertThat(result["users"]).isEqualTo(
            mapOf("total" to 8L, "loggedInLast7Days" to 5L, "neverLoggedIn" to 2L)
        )
        assertThat(result["riskAssessments"]).isEqualTo(
            mapOf("total" to 7L, "started" to 3L, "completed" to 4L)
        )
    }

    @Test
    fun `security statistics scope regular users to accessible assets`() {
        every { vulnerabilities.findSeverityDistributionForAssets(setOf(10, 11)) } returns listOf(
            SeverityDistributionRow("Critical", BigInteger.valueOf(2)),
            SeverityDistributionRow("informational", BigInteger.ONE)
        )

        val result = service.securityPosture(context(setOf("VULN"), assetIds = setOf(10, 11)))
        @Suppress("UNCHECKED_CAST")
        val summary = result["vulnerabilities"] as Map<String, Any>

        assertThat(result["scope"]).isEqualTo("DELEGATED_USER_ASSETS")
        assertThat(result["assetCount"]).isEqualTo(2L)
        assertThat(summary["total"]).isEqualTo(3L)
        @Suppress("UNCHECKED_CAST")
        assertThat((summary["bySeverity"] as Map<String, Long>)["OTHER"]).isEqualTo(1L)
        verify(exactly = 0) { vulnerabilities.findSeverityDistributionForAll() }
    }

    @Test
    fun `security statistics do not query vulnerabilities when user has no assets`() {
        val result = service.securityPosture(context(setOf("VULN"), assetIds = emptySet()))

        assertThat(result["assetCount"]).isEqualTo(0L)
        verify(exactly = 0) {
            vulnerabilities.findSeverityDistributionForAll()
            vulnerabilities.findSeverityDistributionForAssets(any())
        }
    }

    @Test
    fun `risk statistics are scoped by delegated actor and exact use case`() {
        every { assessments.findForMcp(null, "Cloud", 7, false, any(), any(), Pageable.from(0, 1)) } returns
            Page.of(emptyList(), Pageable.from(0, 1), 6)
        every { assessments.findForMcp("STARTED", "Cloud", 7, false, any(), any(), Pageable.from(0, 1)) } returns
            Page.of(emptyList(), Pageable.from(0, 1), 4)
        every { assessments.findForMcp("COMPLETED", "Cloud", 7, false, any(), any(), Pageable.from(0, 1)) } returns
            Page.of(emptyList(), Pageable.from(0, 1), 1)

        val result = service.riskAssessments(context(setOf("RISK")), " Cloud ")

        assertThat(result["total"]).isEqualTo(6L)
        assertThat(result["useCaseName"]).isEqualTo("Cloud")
        assertThat(result["byStatus"]).isEqualTo(mapOf("STARTED" to 4L, "COMPLETED" to 1L, "OTHER" to 1L))
    }

    private fun context(
        roles: Set<String>,
        isAdmin: Boolean = false,
        assetIds: Set<Long> = emptySet()
    ) = McpExecutionContext.forDelegatedUser(
        apiKeyId = 1,
        apiKeyName = "test",
        delegatedUserId = 7,
        delegatedUserEmail = "agent@example.com",
        delegatedUsername = "agent",
        delegatedUserRoles = roles,
        effectivePermissions = McpPermission.entries.toSet(),
        isAdmin = isAdmin,
        accessibleAssetIds = assetIds,
        accessibleWorkgroupIds = emptySet()
    )
}
