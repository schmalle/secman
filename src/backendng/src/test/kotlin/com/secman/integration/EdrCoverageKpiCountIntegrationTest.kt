package com.secman.integration

import com.secman.domain.Asset
import com.secman.domain.ExceptionKind
import com.secman.domain.VulnerabilityException
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityExceptionRepository
import com.secman.service.EdrCoverageKpiService
import com.secman.testutil.BaseIntegrationTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Covers the three SQL predicates behind the EDR-coverage KPI against a real database.
 *
 * `EdrCoverageKpiServiceTest` exercises the arithmetic with mocked repositories; it cannot
 * exercise these, and transcribing "is an EC2 instance", "was seen recently" and "has an
 * active NO_EDR exception" from prose into SQL is exactly where a silent regression hides.
 * A wrong predicate here does not throw — it just reports a plausible, wrong percentage.
 */
@DisplayName("EDR coverage KPI: population and exemption counts")
class EdrCoverageKpiCountIntegrationTest : BaseIntegrationTest() {

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var exceptionRepository: VulnerabilityExceptionRepository

    @AfterEach
    fun cleanup() {
        exceptionRepository.deleteAll()
        assetRepository.deleteAll()
    }

    private fun asset(
        name: String,
        instanceId: String?,
        agentSeenAt: LocalDateTime? = null
    ): Asset = assetRepository.save(
        Asset(name = name, type = "SERVER", owner = "ops", cloudAccountId = "111122223333")
            .apply {
                cloudInstanceId = instanceId
                crowdStrikeAgentSeenAt = agentSeenAt
            }
    )

    private fun noEdrException(assetId: Long?, expirationDate: LocalDateTime? = null) =
        exceptionRepository.save(
            VulnerabilityException(
                subject = VulnerabilityException.Subject.ALL_VULNS,
                scope = VulnerabilityException.Scope.ASSET,
                kind = ExceptionKind.NO_EDR,
                assetId = assetId,
                expirationDate = expirationDate,
                reason = "cannot host a sensor",
                createdBy = "tester"
            )
        )

    private fun fresh() = LocalDateTime.now().minusDays(1)
    private fun stale() = LocalDateTime.now().minusDays(EdrCoverageKpiService.AGENT_SEEN_FRESHNESS_DAYS + 1)

    /**
     * The population fence. Only assets with a non-blank cloudInstanceId count — which is what
     * excludes the synthetic "AWS Account <id>" placeholder assets (cloudAccountId but no
     * instance id) and every non-cloud asset.
     */
    @Test
    @DisplayName("only assets with a cloud instance id are in the EC2 population")
    fun populationExcludesNonEc2Assets() {
        asset("ec2-a", "i-0000000000000001")
        asset("ec2-b", "i-0000000000000002")
        asset("on-prem", null)
        asset("aws-account-placeholder", "")

        assertThat(assetRepository.countAllAwsAssetsWithInstanceId()).isEqualTo(2L)
    }

    @Test
    @DisplayName("only recently seen instances count as covered")
    fun freshnessWindowIsApplied() {
        asset("seen-yesterday", "i-0000000000000001", fresh())
        asset("seen-long-ago", "i-0000000000000002", stale())
        asset("never-seen", "i-0000000000000003", null)

        val cutoff = LocalDateTime.now().minusDays(EdrCoverageKpiService.AGENT_SEEN_FRESHNESS_DAYS)
        assertThat(assetRepository.countEc2AssetsWithFreshCrowdStrikeAgent(cutoff)).isEqualTo(1L)
    }

    @Test
    @DisplayName("a non-EC2 asset with a fresh sighting does not inflate the numerator")
    fun numeratorRespectsThePopulationFence() {
        asset("on-prem-with-sensor", null, fresh())

        val cutoff = LocalDateTime.now().minusDays(EdrCoverageKpiService.AGENT_SEEN_FRESHNESS_DAYS)
        assertThat(assetRepository.countEc2AssetsWithFreshCrowdStrikeAgent(cutoff)).isEqualTo(0L)
    }

    @Test
    @DisplayName("an active NO_EDR exception excludes its asset and removes it from the numerator")
    fun activeNoEdrExceptionExcludesTheAsset() {
        val exempt = asset("appliance", "i-0000000000000001", fresh())
        asset("normal", "i-0000000000000002", fresh())
        noEdrException(exempt.id)

        assertThat(assetRepository.countEc2AssetsExcludedByNoEdrException()).isEqualTo(1L)

        // The exempted box leaves the numerator too, so numerator/denominator stay consistent:
        // counting it as covered while removing it from the denominator would exceed 100%.
        val cutoff = LocalDateTime.now().minusDays(EdrCoverageKpiService.AGENT_SEEN_FRESHNESS_DAYS)
        assertThat(assetRepository.countEc2AssetsWithFreshCrowdStrikeAgent(cutoff)).isEqualTo(1L)
    }

    @Test
    @DisplayName("an expired NO_EDR exception no longer excludes its asset")
    fun expiredNoEdrExceptionDoesNotExclude() {
        val previouslyExempt = asset("appliance", "i-0000000000000001")
        noEdrException(previouslyExempt.id, expirationDate = LocalDateTime.now().minusDays(1))

        assertThat(assetRepository.countEc2AssetsExcludedByNoEdrException()).isEqualTo(0L)
    }

    /**
     * The counterpart to the fail-safe in ExceptionMatchSql: there, a NULL kind is tolerated so
     * legacy rows keep suppressing. Here the opposite is required — reading an ordinary
     * exception as an EDR exemption would silently shrink the denominator across the fleet, so
     * the KPI queries use plain `kind = 'NO_EDR'` equality.
     */
    @Test
    @DisplayName("an ordinary vulnerability exception on the same asset excludes nothing")
    fun ordinaryExceptionsAreNotEdrExemptions() {
        val target = asset("normal", "i-0000000000000001", fresh())
        exceptionRepository.save(
            VulnerabilityException(
                subject = VulnerabilityException.Subject.ALL_VULNS,
                scope = VulnerabilityException.Scope.ASSET,
                kind = ExceptionKind.VULNERABILITY,
                assetId = target.id,
                reason = "a genuine suppression on the same asset",
                createdBy = "tester"
            )
        )

        assertThat(assetRepository.countEc2AssetsExcludedByNoEdrException()).isEqualTo(0L)
    }

    @Test
    @DisplayName("the ever-seen probe distinguishes zero coverage from no import yet")
    fun everSeenProbeReflectsStampedAssets() {
        asset("never-seen", "i-0000000000000001", null)
        assertThat(assetRepository.countAssetsWithAnyCrowdStrikeAgentSighting()).isEqualTo(0L)

        // Even a stale sighting counts here — the probe answers "has an import ever run?",
        // not "is this box currently covered?".
        asset("seen-long-ago", "i-0000000000000002", stale())
        assertThat(assetRepository.countAssetsWithAnyCrowdStrikeAgentSighting()).isEqualTo(1L)
    }

    /**
     * End-to-end over the real queries: 5 EC2 instances, 1 exempted, 3 of the remaining 4
     * covered → 75%, not 60% (which is what counting the exemption as a failure would give)
     * and not 80% (what counting it as covered would give).
     */
    @Test
    @DisplayName("the exemption changes the denominator, not the numerator")
    fun exemptionArithmeticEndToEnd() {
        asset("covered-1", "i-0000000000000001", fresh())
        asset("covered-2", "i-0000000000000002", fresh())
        asset("covered-3", "i-0000000000000003", fresh())
        asset("uncovered", "i-0000000000000004", null)
        val exempt = asset("appliance", "i-0000000000000005", null)
        noEdrException(exempt.id)

        val total = assetRepository.countAllAwsAssetsWithInstanceId()
        val excluded = assetRepository.countEc2AssetsExcludedByNoEdrException()
        val covered = assetRepository.countEc2AssetsWithFreshCrowdStrikeAgent(
            LocalDateTime.now().minusDays(EdrCoverageKpiService.AGENT_SEEN_FRESHNESS_DAYS)
        )

        assertThat(total).isEqualTo(5L)
        assertThat(excluded).isEqualTo(1L)
        assertThat(covered).isEqualTo(3L)
        assertThat(covered.toDouble() / (total - excluded)).isEqualTo(0.75)
    }
}
