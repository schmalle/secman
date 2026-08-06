package com.secman.integration

import com.secman.domain.Asset
import com.secman.domain.Vulnerability
import com.secman.domain.VulnerabilityException
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityExceptionRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.projection.toSeverityDistributionDto
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Executes the four bounded statistics aggregates against a real database.
 *
 * These are the queries the statistics cache now delegates to, and they all share
 * `VulnQuerySql.NOT_EXCEPTED` — which changed from a correlated
 * `NOT EXISTS(ExceptionMatchSql.EXCEPTION_MATCH)` to the sargable `v.excepted = 0`. That constant is
 * interpolated into **12** repository methods, so a mistake in it is broad; and because Micronaut
 * Data does not validate a native query until it runs, only an integration test can catch it.
 *
 * The fixture deliberately uses TITLE-CASE severities ("Critical"), which is what the CrowdStrike
 * importer and addVulnerabilityFromCli actually store. The in-memory implementation these
 * aggregates replaced compared with `== "CRITICAL"` and therefore reported 0 for every imported
 * row; the assertions below are what that bug looked like when correct.
 */
@DisplayName("Statistics aggregates: bounded SQL over the materialized excepted flag")
class StatisticsAggregateIntegrationTest : BaseIntegrationTest() {

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var vulnerabilityRepository: VulnerabilityRepository

    @Inject
    lateinit var exceptionRepository: VulnerabilityExceptionRepository

    @Inject
    lateinit var entityManager: EntityManager

    @AfterEach
    fun cleanup() {
        exceptionRepository.deleteAll()
        vulnerabilityRepository.deleteAll()
        assetRepository.deleteAll()
    }

    private fun saveVuln(asset: Asset, cve: String, severity: String, product: String?) {
        vulnerabilityRepository.save(
            Vulnerability(
                asset = asset,
                vulnerabilityId = cve,
                cvssSeverity = severity,
                vulnerableProductVersions = product,
                scanTimestamp = LocalDateTime.now().minusDays(5)
            )
        )
    }

    /** See ExceptedFlagSqlAgreementIntegrationTest for why flush-then-recompute-then-clear. */
    private fun materializeExceptedFlag() {
        entityManager.flush()
        vulnerabilityRepository.recomputeExceptedAll()
        entityManager.clear()
    }

    private fun seed(): Pair<Asset, Asset> {
        val web = assetRepository.save(TestDataFactory.createAsset(name = "stats-web-1", type = "SERVER"))
        val db = assetRepository.save(TestDataFactory.createAsset(name = "stats-db-1", type = "SERVER"))

        // web-1: 3 vulns (2 x CVE-0001 shared with db-1), all on OpenSSL
        saveVuln(web, "CVE-2026-0001", "Critical", "OpenSSL 1.1.1")
        saveVuln(web, "CVE-2026-0002", "High", "OpenSSL 1.1.1")
        saveVuln(web, "CVE-2026-0003", "Medium", "OpenSSL 1.1.1")
        // db-1: 1 vuln, same CVE as web-1's first
        saveVuln(db, "CVE-2026-0001", "Critical", "OpenSSL 1.1.1")
        // A blank product must be excluded from most-vulnerable-products but counted elsewhere.
        saveVuln(db, "CVE-2026-0004", "Low", "")

        materializeExceptedFlag()
        return web to db
    }

    @Test
    @DisplayName("severity distribution counts title-case severities correctly")
    fun severityDistribution() {
        seed()

        val dto = vulnerabilityRepository.findSeverityDistributionForAll().toSeverityDistributionDto()

        assertThat(dto.critical).isEqualTo(2L)
        assertThat(dto.high).isEqualTo(1L)
        assertThat(dto.medium).isEqualTo(1L)
        assertThat(dto.low).isEqualTo(1L)
        assertThat(dto.unknown).isEqualTo(0L)
    }

    @Test
    @DisplayName("most common groups by CVE and severity, counting occurrences and distinct assets")
    fun mostCommon() {
        seed()

        val rows = vulnerabilityRepository.findMostCommonVulnerabilitiesForAll().map { it.toDto() }

        assertThat(rows).isNotEmpty()
        assertThat(rows.size).isLessThanOrEqualTo(10)
        val top = rows.first { it.vulnerabilityId == "CVE-2026-0001" }
        assertThat(top.occurrenceCount).isEqualTo(2L)      // on two assets
        assertThat(top.affectedAssetCount).isEqualTo(2L)
        assertThat(top.cvssSeverity).isEqualTo("Critical")  // stored casing preserved
    }

    /** The regression that mattered most: these counts were 0 for every imported row. */
    @Test
    @DisplayName("most vulnerable products reports non-zero critical/high for title-case severities")
    fun mostVulnerableProducts() {
        seed()

        val rows = vulnerabilityRepository.findMostVulnerableProductsForAll().map { it.toDto() }

        assertThat(rows.size).isLessThanOrEqualTo(10)
        val openssl = rows.single { it.product == "OpenSSL 1.1.1" }
        // distinct CVEs, not row count: 0001, 0002, 0003
        assertThat(openssl.vulnerabilityCount).isEqualTo(3L)
        assertThat(openssl.affectedAssetCount).isEqualTo(2L)
        assertThat(openssl.criticalCount).isEqualTo(1L)
        assertThat(openssl.highCount).isEqualTo(1L)
        // The blank-product row must not appear at all.
        assertThat(rows.map { it.product }).doesNotContain("")
    }

    /** Same regression on the other aggregate. */
    @Test
    @DisplayName("top assets reports non-zero severity breakdown for title-case severities")
    fun topAssets() {
        seed()

        val rows = vulnerabilityRepository.findTopAssetsByVulnerabilitiesForAll().map { it.toDto() }

        assertThat(rows.size).isLessThanOrEqualTo(50)
        val web = rows.single { it.assetName == "stats-web-1" }
        assertThat(web.totalVulnerabilityCount).isEqualTo(3L)
        assertThat(web.criticalCount).isEqualTo(1L)
        assertThat(web.highCount).isEqualTo(1L)
        assertThat(web.mediumCount).isEqualTo(1L)
        assertThat(web.lowCount).isEqualTo(0L)
        // Ordered by total desc, so the 3-vuln asset precedes the 2-vuln one.
        assertThat(rows.first().assetName).isEqualTo("stats-web-1")
    }

    /**
     * The point of the whole change: exception filtering happens in SQL via the materialized flag,
     * and it must actually suppress rows across every aggregate.
     */
    @Test
    @DisplayName("an active exception is honoured by all four aggregates via the excepted flag")
    fun exceptionsSuppressRowsInEverySeriesAggregate() {
        seed()

        exceptionRepository.save(
            VulnerabilityException(
                subject = VulnerabilityException.Subject.CVE,
                scope = VulnerabilityException.Scope.GLOBAL,
                subjectValue = "CVE-2026-0001",
                reason = "suppress the shared critical",
                createdBy = "tester"
            )
        )
        materializeExceptedFlag()

        val dist = vulnerabilityRepository.findSeverityDistributionForAll().toSeverityDistributionDto()
        assertThat(dist.critical).isEqualTo(0L)   // both Critical rows were CVE-2026-0001
        assertThat(dist.high).isEqualTo(1L)

        val common = vulnerabilityRepository.findMostCommonVulnerabilitiesForAll().map { it.toDto() }
        assertThat(common.map { it.vulnerabilityId }).doesNotContain("CVE-2026-0001")

        val products = vulnerabilityRepository.findMostVulnerableProductsForAll().map { it.toDto() }
        val openssl = products.single { it.product == "OpenSSL 1.1.1" }
        assertThat(openssl.vulnerabilityCount).isEqualTo(2L)  // 0001 suppressed
        assertThat(openssl.criticalCount).isEqualTo(0L)

        val assets = vulnerabilityRepository.findTopAssetsByVulnerabilitiesForAll().map { it.toDto() }
        assertThat(assets.single { it.assetName == "stats-web-1" }.totalVulnerabilityCount).isEqualTo(2L)
        assertThat(assets.single { it.assetName == "stats-web-1" }.criticalCount).isEqualTo(0L)
    }

    /**
     * Structural proof that NOT_EXCEPTED is no longer a correlated subquery. If someone reverts it,
     * the plan regains a DEPENDENT SUBQUERY over vulnerability_exception and this fails.
     */
    @Test
    @DisplayName("the aggregates no longer plan a dependent subquery over vulnerability_exception")
    fun predicateIsSargableNotCorrelated() {
        seed()

        val plan = entityManager
            .createNativeQuery(
                "EXPLAIN SELECT COALESCE(v.cvss_severity, 'UNKNOWN') AS severity, COUNT(*) " +
                    "FROM vulnerability v JOIN asset a ON v.asset_id = a.id " +
                    "WHERE v.excepted = 0 GROUP BY COALESCE(v.cvss_severity, 'UNKNOWN')"
            )
            .resultList
            .joinToString(" | ") { row -> (row as Array<*>).joinToString(",") { "$it" } }

        assertThat(plan).doesNotContainIgnoringCase("DEPENDENT SUBQUERY")
        assertThat(plan).doesNotContainIgnoringCase("vulnerability_exception")
    }
}
