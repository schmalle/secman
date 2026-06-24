package com.secman.service

import com.secman.domain.Asset
import com.secman.repository.CrowdStrikeImportHistoryRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@DisplayName("AccountVulnsService")
@ExtendWith(MockKExtension::class)
class AccountVulnsServiceTest {

    @MockK
    lateinit var userMappingRepository: UserMappingRepository

    @MockK
    lateinit var assetFilterService: AssetFilterService

    @MockK
    lateinit var vulnerabilityRepository: VulnerabilityRepository

    @MockK
    lateinit var entityManager: EntityManager

    @MockK
    lateinit var importHistoryRepository: CrowdStrikeImportHistoryRepository

    @MockK
    lateinit var awsAccountSharingService: AwsAccountSharingService

    @MockK
    lateinit var query: Query

    private lateinit var service: AccountVulnsService

    @BeforeEach
    fun setUp() {
        service = AccountVulnsService(
            userMappingRepository = userMappingRepository,
            assetFilterService = assetFilterService,
            vulnerabilityRepository = vulnerabilityRepository,
            entityManager = entityManager,
            importHistoryRepository = importHistoryRepository,
            awsAccountSharingService = awsAccountSharingService
        )
    }

    @Test
    fun `account summary includes excepted vulnerabilities and reports exception breakdown`() {
        val email = "account-vulns-exceptions@secman.test"
        val awsAccountId = "123456789012"
        val asset = Asset(
            id = 42L,
            name = "account-vulns-host",
            type = "SERVER",
            owner = "test-owner",
            cloudAccountId = awsAccountId
        )
        val capturedSql = slot<String>()

        every { userMappingRepository.findDistinctAwsAccountIdByEmail(email) } returns listOf(awsAccountId)
        every { awsAccountSharingService.getSharedAwsAccountIdsByEmail(email) } returns emptyList()
        // Unified asset access is now the single source of truth (matches Current Vulns view).
        every { assetFilterService.getAccessibleAssets(any()) } returns listOf(asset)
        every { entityManager.createNativeQuery(capture(capturedSql)) } returns query
        every { query.setParameter("assetIds", listOf(42L)) } returns query
        every { query.resultList } returns listOf(
            arrayOf(42L, 2L, 0L, 2L, 0L, 0L, 0L, 1L, 1L)
        )
        every { importHistoryRepository.findLatest() } returns null
        every { vulnerabilityRepository.findLatestImportTimestampByAssetIds(setOf(42L)) } returns null

        val summary = service.getAccountVulnsSummary(
            Authentication.build("account-vulns-user", listOf("USER"), mapOf("email" to email))
        )

        assertThat(summary.totalVulnerabilities).isEqualTo(2)
        assertThat(summary.globalHigh).isEqualTo(2)
        assertThat(summary.globalExcepted).isEqualTo(1)
        assertThat(summary.globalNonExcepted).isEqualTo(1)

        val account = summary.accountGroups.single()
        assertThat(account.totalVulnerabilities).isEqualTo(2)
        assertThat(account.totalHigh).isEqualTo(2)
        assertThat(account.totalExcepted).isEqualTo(1)
        assertThat(account.totalNonExcepted).isEqualTo(1)

        val assetSummary = account.assets.single()
        assertThat(assetSummary.vulnerabilityCount).isEqualTo(2)
        assertThat(assetSummary.highCount).isEqualTo(2)
        assertThat(assetSummary.exceptedCount).isEqualTo(1)
        assertThat(assetSummary.nonExceptedCount).isEqualTo(1)
        assertThat(capturedSql.captured).contains("excepted_count", "non_excepted_count")
        assertThat(capturedSql.captured).doesNotContain("WHERE v.asset_id IN (:assetIds)\nAND NOT EXISTS")
    }
}
