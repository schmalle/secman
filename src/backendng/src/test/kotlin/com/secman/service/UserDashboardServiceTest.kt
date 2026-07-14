package com.secman.service

import com.secman.domain.AssessmentBasisType
import com.secman.domain.AssessmentToken
import com.secman.domain.Asset
import com.secman.domain.RiskAssessment
import com.secman.domain.User
import com.secman.domain.UserMapping
import com.secman.dto.ExceptionRequestSummaryDto
import com.secman.repository.AssessmentTokenRepository
import com.secman.repository.AssetRepository
import com.secman.repository.DemandRepository
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.WorkgroupRepository
import com.secman.repository.projection.OverdueAssetAggregateRow
import com.secman.repository.projection.SeverityDistributionRow
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional

@DisplayName("UserDashboardService")
@ExtendWith(MockKExtension::class)
class UserDashboardServiceTest {

    @MockK
    lateinit var userRepository: UserRepository

    @MockK
    lateinit var accessibleAssetIdsCache: AccessibleAssetIdsCache

    @MockK
    lateinit var vulnerabilityRepository: VulnerabilityRepository

    @MockK
    lateinit var outdatedAssetViewRepository: OutdatedAssetMaterializedViewRepository

    @MockK
    lateinit var exceptionRequestService: VulnerabilityExceptionRequestService

    @MockK
    lateinit var riskAssessmentRepository: RiskAssessmentRepository

    @MockK
    lateinit var assessmentTokenRepository: AssessmentTokenRepository

    @MockK
    lateinit var userMappingRepository: UserMappingRepository

    @MockK
    lateinit var workgroupRepository: WorkgroupRepository

    @MockK
    lateinit var assetRepository: AssetRepository

    @MockK
    lateinit var demandRepository: DemandRepository

    @MockK
    lateinit var authentication: Authentication

    private lateinit var service: UserDashboardService

    private val user = User(
        id = 7L,
        username = "jdoe",
        email = "jdoe@example.com",
        passwordHash = "irrelevant"
    )

    private val emptySummary = ExceptionRequestSummaryDto(0, 0, 0, 0, 0, 0)

    @BeforeEach
    fun setUp() {
        service = UserDashboardService(
            userRepository = userRepository,
            accessibleAssetIdsCache = accessibleAssetIdsCache,
            vulnerabilityRepository = vulnerabilityRepository,
            outdatedAssetViewRepository = outdatedAssetViewRepository,
            exceptionRequestService = exceptionRequestService,
            riskAssessmentRepository = riskAssessmentRepository,
            assessmentTokenRepository = assessmentTokenRepository,
            userMappingRepository = userMappingRepository,
            workgroupRepository = workgroupRepository,
            assetRepository = assetRepository,
            demandRepository = demandRepository
        )

        every { authentication.name } returns "jdoe"
        every { authentication.roles } returns listOf("USER")
        every { userRepository.findByUsername("jdoe") } returns Optional.of(user)
        every { exceptionRequestService.getUserRequestSummary(7L) } returns emptySummary
        every { riskAssessmentRepository.findByRespondentId(7L) } returns emptyList()
        every { userMappingRepository.findByEmail("jdoe@example.com") } returns emptyList()
        every { workgroupRepository.findWorkgroupsByUserEmail("jdoe@example.com") } returns emptyList()
        every { outdatedAssetViewRepository.findLatestCalculatedAt() } returns null
    }

    @Test
    fun `user with no accessible assets gets zero counts without touching vuln or view queries`() {
        every { accessibleAssetIdsCache.get(authentication) } returns emptySet()

        val dashboard = service.getDashboard(authentication)

        assertThat(dashboard.assetCount).isZero()
        assertThat(dashboard.vulnerabilities.total).isZero()
        assertThat(dashboard.overdue.assetCount).isZero()
        assertThat(dashboard.overdue.lastCalculatedAt).isNull()
        verify(exactly = 0) { vulnerabilityRepository.findSeverityDistributionForAssets(any()) }
        verify(exactly = 0) { outdatedAssetViewRepository.aggregateOverdueForAssets(any()) }
    }

    @Test
    fun `severity rows are bucketed case-insensitively with unknowns in other`() {
        val assetIds = setOf(1L, 2L)
        every { accessibleAssetIdsCache.get(authentication) } returns assetIds
        every { vulnerabilityRepository.findSeverityDistributionForAssets(assetIds) } returns listOf(
            SeverityDistributionRow("Critical", BigInteger.valueOf(3)),
            SeverityDistributionRow("HIGH", BigInteger.valueOf(5)),
            SeverityDistributionRow("medium", BigInteger.valueOf(2)),
            SeverityDistributionRow("Low", BigInteger.ONE),
            SeverityDistributionRow("Informational", BigInteger.valueOf(4)),
            SeverityDistributionRow(null, BigInteger.ONE)
        )
        every { outdatedAssetViewRepository.aggregateOverdueForAssets(assetIds) } returns
            OverdueAssetAggregateRow(2, 4, 6, 120)

        val dashboard = service.getDashboard(authentication)

        assertThat(dashboard.assetCount).isEqualTo(2)
        assertThat(dashboard.vulnerabilities.critical).isEqualTo(3)
        assertThat(dashboard.vulnerabilities.high).isEqualTo(5)
        assertThat(dashboard.vulnerabilities.medium).isEqualTo(2)
        assertThat(dashboard.vulnerabilities.low).isEqualTo(1)
        assertThat(dashboard.vulnerabilities.other).isEqualTo(5)
        assertThat(dashboard.vulnerabilities.total).isEqualTo(16)
        assertThat(dashboard.overdue.assetCount).isEqualTo(2)
        assertThat(dashboard.overdue.criticalCount).isEqualTo(4)
        assertThat(dashboard.overdue.highCount).isEqualTo(6)
        assertThat(dashboard.overdue.oldestVulnDays).isEqualTo(120)
    }

    @Test
    fun `admin roles use unscoped queries instead of an IN clause over all assets`() {
        every { authentication.roles } returns listOf("ADMIN")
        every { accessibleAssetIdsCache.get(authentication) } returns setOf(1L, 2L, 3L)
        every { vulnerabilityRepository.findSeverityDistributionForAll() } returns emptyList()
        every { outdatedAssetViewRepository.aggregateOverdueForAll() } returns
            OverdueAssetAggregateRow(0, 0, 0, 0)

        service.getDashboard(authentication)

        verify(exactly = 1) { vulnerabilityRepository.findSeverityDistributionForAll() }
        verify(exactly = 1) { outdatedAssetViewRepository.aggregateOverdueForAll() }
        verify(exactly = 0) { vulnerabilityRepository.findSeverityDistributionForAssets(any()) }
        verify(exactly = 0) { outdatedAssetViewRepository.aggregateOverdueForAssets(any()) }
    }

    @Test
    fun `risk assessments exclude closed statuses, sort by deadline and flag overdue`() {
        every { accessibleAssetIdsCache.get(authentication) } returns emptySet()

        val overdueAssessment = assessment(1L, LocalDate.now().minusDays(3), "STARTED")
        val upcomingAssessment = assessment(2L, LocalDate.now().plusDays(5), "IN_PROGRESS")
        val completedAssessment = assessment(3L, LocalDate.now().plusDays(1), "Completed")
        every { riskAssessmentRepository.findByRespondentId(7L) } returns
            listOf(upcomingAssessment, completedAssessment, overdueAssessment)

        every { assetRepository.findById(100L) } returns Optional.of(
            Asset(id = 100L, name = "web-frontend-01", type = "SERVER", owner = "jdoe")
        )
        every { assessmentTokenRepository.findByRiskAssessmentIdAndEmail(any(), any()) } returns Optional.empty()
        every { assessmentTokenRepository.findByRiskAssessmentIdAndEmail(1L, "jdoe@example.com") } returns Optional.of(
            AssessmentToken(
                token = "tok-123",
                email = "jdoe@example.com",
                expiresAt = LocalDateTime.now().plusDays(1),
                riskAssessment = overdueAssessment
            )
        )

        val dashboard = service.getDashboard(authentication)

        assertThat(dashboard.riskAssessments).hasSize(2)
        val first = dashboard.riskAssessments[0]
        assertThat(first.id).isEqualTo(1L)
        assertThat(first.overdue).isTrue()
        assertThat(first.daysUntilDue).isEqualTo(-3)
        assertThat(first.basisName).isEqualTo("web-frontend-01")
        assertThat(first.respondUrl).isEqualTo("/respond/tok-123")
        val second = dashboard.riskAssessments[1]
        assertThat(second.id).isEqualTo(2L)
        assertThat(second.overdue).isFalse()
        assertThat(second.respondUrl).isNull()
    }

    @Test
    fun `expired or used respond tokens produce no respond link`() {
        every { accessibleAssetIdsCache.get(authentication) } returns emptySet()

        val assessment = assessment(4L, LocalDate.now().plusDays(2), "STARTED")
        every { riskAssessmentRepository.findByRespondentId(7L) } returns listOf(assessment)
        every { assetRepository.findById(100L) } returns Optional.empty()
        every { assessmentTokenRepository.findByRiskAssessmentIdAndEmail(4L, "jdoe@example.com") } returns Optional.of(
            AssessmentToken(
                token = "tok-old",
                email = "jdoe@example.com",
                expiresAt = LocalDateTime.now().minusDays(1),
                riskAssessment = assessment
            )
        )

        val dashboard = service.getDashboard(authentication)

        assertThat(dashboard.riskAssessments).hasSize(1)
        assertThat(dashboard.riskAssessments[0].respondUrl).isNull()
        assertThat(dashboard.riskAssessments[0].basisName).isNull()
    }

    @Test
    fun `view flags derive from user mappings and workgroup membership`() {
        every { accessibleAssetIdsCache.get(authentication) } returns emptySet()
        every { userMappingRepository.findByEmail("jdoe@example.com") } returns listOf(
            UserMapping(email = "jdoe@example.com", awsAccountId = "123456789012", domain = null),
            UserMapping(email = "jdoe@example.com", awsAccountId = null, domain = "corp.example")
        )

        val dashboard = service.getDashboard(authentication)

        assertThat(dashboard.views.accountVulns).isTrue()
        assertThat(dashboard.views.domainVulns).isTrue()
        assertThat(dashboard.views.workgroupVulns).isFalse()
    }

    private fun assessment(id: Long, endDate: LocalDate, status: String): RiskAssessment {
        return RiskAssessment(
            id = id,
            startDate = LocalDate.now().minusDays(10),
            endDate = endDate,
            status = status,
            assessmentBasisType = AssessmentBasisType.ASSET,
            assessmentBasisId = 100L,
            assessor = user,
            requestor = user,
            respondent = user
        )
    }
}
