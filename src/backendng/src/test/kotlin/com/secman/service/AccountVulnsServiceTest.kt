package com.secman.service

import com.secman.domain.Asset
import com.secman.dto.AssetInterventionStatus
import com.secman.repository.CrowdStrikeImportHistoryRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDateTime

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
    lateinit var assetVulnCountsQuery: AssetVulnCountsQuery

    @MockK
    lateinit var importHistoryRepository: CrowdStrikeImportHistoryRepository

    @MockK
    lateinit var awsAccountSharingService: AwsAccountSharingService

    private lateinit var service: AccountVulnsService

    private val email = "account-vulns-exceptions@secman.test"
    private val awsAccountId = "123456789012"

    @BeforeEach
    fun setUp() {
        service = AccountVulnsService(
            userMappingRepository = userMappingRepository,
            assetFilterService = assetFilterService,
            vulnerabilityRepository = vulnerabilityRepository,
            assetVulnCountsQuery = assetVulnCountsQuery,
            importHistoryRepository = importHistoryRepository,
            awsAccountSharingService = awsAccountSharingService
        )
    }

    private fun asset(id: Long, name: String) = Asset(
        id = id,
        name = name,
        type = "SERVER",
        owner = "test-owner",
        cloudAccountId = awsAccountId
    )

    private fun counts(
        total: Int, high: Int = total, excepted: Int = 0,
        nonExcepted: Int = total - excepted, nonExceptedOverdue: Int = 0
    ) = AssetVulnCountsQuery.AssetVulnCounts(
        total = total, critical = 0, high = high, medium = 0, low = 0, unknown = 0,
        excepted = excepted, nonExcepted = nonExcepted, nonExceptedOverdue = nonExceptedOverdue
    )

    private fun stubCommon(assets: List<Asset>, countsByAsset: Map<Long, AssetVulnCountsQuery.AssetVulnCounts>) {
        every { userMappingRepository.findDistinctAwsAccountIdByEmail(email) } returns listOf(awsAccountId)
        every { awsAccountSharingService.getSharedAwsAccountIdsByEmail(email) } returns emptyList()
        every { assetFilterService.getAccessibleAssets(any()) } returns assets
        every { assetVulnCountsQuery.thresholdDays() } returns 30
        every { assetVulnCountsQuery.countByAsset(any(), any<LocalDateTime>()) } returns countsByAsset
        every { importHistoryRepository.findLatest() } returns null
        every { vulnerabilityRepository.findLatestImportTimestampByAssetIds(any()) } returns null
    }

    private fun run() = service.getAccountVulnsSummary(
        Authentication.build("account-vulns-user", listOf("USER"), mapOf("email" to email))
    )

    @Test
    fun `account summary includes excepted vulnerabilities and reports exception breakdown`() {
        stubCommon(listOf(asset(42L, "account-vulns-host")), mapOf(42L to counts(total = 2, excepted = 1)))

        val summary = run()

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
    }

    @Test
    fun `asset with only excepted findings is GREEN`() {
        stubCommon(listOf(asset(1L, "all-excepted")), mapOf(1L to counts(total = 4, excepted = 4)))

        val assetSummary = run().accountGroups.single().assets.single()

        assertThat(assetSummary.status).isEqualTo(AssetInterventionStatus.GREEN)
        assertThat(assetSummary.nonExceptedOverdueCount).isEqualTo(0)
    }

    @Test
    fun `asset with no vulnerabilities at all is GREEN`() {
        // No counts row exists for an asset without vulnerabilities.
        stubCommon(listOf(asset(1L, "clean")), emptyMap())

        val assetSummary = run().accountGroups.single().assets.single()

        assertThat(assetSummary.status).isEqualTo(AssetInterventionStatus.GREEN)
        assertThat(assetSummary.vulnerabilityCount).isEqualTo(0)
    }

    @Test
    fun `account status is the worst of its assets and counts those needing attention`() {
        stubCommon(
            listOf(asset(1L, "green"), asset(2L, "yellow"), asset(3L, "red")),
            mapOf(
                1L to counts(total = 2, excepted = 2),
                2L to counts(total = 1),
                3L to counts(total = 1, nonExceptedOverdue = 1)
            )
        )

        val summary = run()
        val account = summary.accountGroups.single()

        assertThat(account.status).isEqualTo(AssetInterventionStatus.RED)
        assertThat(account.assetsNeedingAttention).isEqualTo(2)
        assertThat(summary.globalStatus).isEqualTo(AssetInterventionStatus.RED)
        assertThat(summary.assetsNeedingAttention).isEqualTo(2)
    }

    @Test
    fun `assets are ordered worst status first`() {
        stubCommon(
            listOf(asset(1L, "green"), asset(2L, "yellow"), asset(3L, "red")),
            mapOf(
                // The GREEN asset deliberately carries the most vulnerabilities, so ordering by
                // count alone would put it first.
                1L to counts(total = 50, excepted = 50),
                2L to counts(total = 1),
                3L to counts(total = 1, nonExceptedOverdue = 1)
            )
        )

        val names = run().accountGroups.single().assets.map { it.name }

        assertThat(names).containsExactly("red", "yellow", "green")
    }

    @Test
    fun `admins are served instead of being redirected to System Vulns`() {
        // Previously threw IllegalStateException("Admin users should use System Vulns view").
        // The lamp is only useful if the people who act on it can see it.
        stubCommon(listOf(asset(1L, "host")), mapOf(1L to counts(total = 1, nonExceptedOverdue = 1)))

        val summary = service.getAccountVulnsSummary(
            Authentication.build("admin-user", listOf("ADMIN"), mapOf("email" to email))
        )

        assertThat(summary.accountGroups).hasSize(1)
        assertThat(summary.globalStatus).isEqualTo(AssetInterventionStatus.RED)
    }

    @Test
    fun `an estate larger than the render cap is refused with actionable guidance`() {
        // ADMIN/SECCHAMPION reach every asset, so this view is bounded only by estate size.
        val tooMany = (1L..(AccountVulnsService.MAX_RENDERED_ASSETS + 1L)).map { asset(it, "host-$it") }
        stubCommon(tooMany, emptyMap())

        val error = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            service.getAccountVulnsSummary(
                Authentication.build("admin-user", listOf("ADMIN"), mapOf("email" to email))
            )
        }

        assertThat(error.message).contains("System Vulnerabilities")
        assertThat(error.message).contains("${AccountVulnsService.MAX_RENDERED_ASSETS}")
    }

    @Test
    fun `an estate at exactly the cap is still served`() {
        val atCap = (1L..AccountVulnsService.MAX_RENDERED_ASSETS.toLong()).map { asset(it, "host-$it") }
        stubCommon(atCap, emptyMap())

        val summary = service.getAccountVulnsSummary(
            Authentication.build("admin-user", listOf("ADMIN"), mapOf("email" to email))
        )

        assertThat(summary.totalAssets).isEqualTo(AccountVulnsService.MAX_RENDERED_ASSETS)
    }

    @Test
    fun `threshold days is echoed so the UI need not hardcode it`() {
        stubCommon(listOf(asset(1L, "host")), mapOf(1L to counts(total = 1)))

        assertThat(run().thresholdDays).isEqualTo(30)
    }
}
