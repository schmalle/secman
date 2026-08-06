package com.secman.integration

import com.secman.domain.Asset
import com.secman.repository.AssetRepository
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Semantics of the single-statement outdated-asset view rebuild
 * (OutdatedAssetMaterializedViewRepository.rebuildFromOverdueVulnerabilities).
 *
 * The rebuild replaced ~60 lines of Kotlin that loaded every overdue `Vulnerability` as a managed
 * entity and aggregated in heap — the largest contributor to the 2026-07-30 import
 * OutOfMemoryError. Transcribing that aggregation into SQL is exactly the kind of change that can
 * pass a compile and a mock test while silently producing different numbers, so each behaviour the
 * Kotlin guaranteed is pinned here against a real MariaDB.
 *
 * The SLA-anchor half of the contract lives in [OverdueAnchorIntegrationTest].
 */
@DisplayName("Outdated-asset view: single-statement rebuild semantics")
class OutdatedAssetRebuildIntegrationTest : BaseIntegrationTest() {

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var vulnerabilityRepository: VulnerabilityRepository

    @Inject
    lateinit var outdatedAssetRepository: OutdatedAssetMaterializedViewRepository

    private val threshold: LocalDateTime = LocalDateTime.now().minusDays(30)

    @AfterEach
    fun cleanup() {
        outdatedAssetRepository.deleteAll()
        vulnerabilityRepository.deleteAll()
        assetRepository.deleteAll()
    }

    private fun saveAsset(name: String): Asset =
        assetRepository.save(TestDataFactory.createAsset(name = name))

    /** Persist an overdue vulnerability, anchored `daysOld` days back. */
    private fun saveVuln(
        asset: Asset,
        cve: String,
        severity: String,
        daysOld: Long = 45,
        excepted: Boolean = false
    ) {
        val vuln = TestDataFactory.createVulnerabilityWithTimestamp(
            asset = asset,
            cve = cve,
            severity = severity,
            scanTimestamp = LocalDateTime.now().minusDays(daysOld)
        )
        vuln.firstSeenAt = LocalDateTime.now().minusDays(daysOld)
        vuln.excepted = excepted
        vulnerabilityRepository.save(vuln)
    }

    private fun rebuild(): List<com.secman.domain.OutdatedAssetMaterializedView> {
        outdatedAssetRepository.deleteAll()
        outdatedAssetRepository.rebuildFromOverdueVulnerabilities(threshold, LocalDateTime.now())
        return outdatedAssetRepository.findAll()
    }

    /**
     * The bug this whole change also fixed elsewhere: severity is stored TITLE-CASE ("Critical")
     * by the CrowdStrike importer and by addVulnerabilityFromCli. The Kotlin this SQL replaced
     * compared with `equals(..., ignoreCase = true)` precisely because an exact match collapses
     * every count to zero. utf8mb4_general_ci (V205) must give the SQL the same behaviour.
     */
    @Test
    @DisplayName("severity counts are case-insensitive against title-case stored values")
    fun severityCountsAreCaseInsensitive() {
        val asset = saveAsset("rebuild-severity")
        saveVuln(asset, "CVE-2026-0001", "Critical")
        saveVuln(asset, "CVE-2026-0002", "High")
        saveVuln(asset, "CVE-2026-0003", "Medium")
        saveVuln(asset, "CVE-2026-0004", "Low")

        val row = rebuild().single()

        assertThat(row.criticalCount).isEqualTo(1)
        assertThat(row.highCount).isEqualTo(1)
        assertThat(row.mediumCount).isEqualTo(1)
        assertThat(row.lowCount).isEqualTo(1)
        assertThat(row.totalOverdueCount).isEqualTo(4)
    }

    @Test
    @DisplayName("excepted vulnerabilities are excluded, and an asset with only excepted ones gets no row")
    fun exceptedVulnerabilitiesAreExcluded() {
        val partly = saveAsset("rebuild-partly-excepted")
        saveVuln(partly, "CVE-2026-0010", "Critical", excepted = true)
        saveVuln(partly, "CVE-2026-0011", "High")

        val fully = saveAsset("rebuild-fully-excepted")
        saveVuln(fully, "CVE-2026-0012", "Critical", excepted = true)

        val rows = rebuild()

        assertThat(rows.map { it.assetName }).containsExactly("rebuild-partly-excepted")
        val row = rows.single()
        assertThat(row.totalOverdueCount).isEqualTo(1)
        assertThat(row.criticalCount).isEqualTo(0)
        assertThat(row.highCount).isEqualTo(1)
    }

    @Test
    @DisplayName("oldest vulnerability wins on age, and ties resolve to the smallest CVE id")
    fun oldestVulnerabilityIsPickedDeterministically() {
        val asset = saveAsset("rebuild-oldest")
        saveVuln(asset, "CVE-2026-0100", "High", daysOld = 40)
        saveVuln(asset, "CVE-2026-0200", "High", daysOld = 90)  // oldest
        saveVuln(asset, "CVE-2026-0300", "High", daysOld = 50)

        val row = rebuild().single()

        assertThat(row.oldestVulnId).isEqualTo("CVE-2026-0200")
        // TIMESTAMPDIFF(DAY, ...) truncates toward zero, like ChronoUnit.DAYS.between
        assertThat(row.oldestVulnDays).isEqualTo(90)
    }

    @Test
    @DisplayName("ties for oldest resolve to the lexicographically smallest vulnerability id")
    fun oldestVulnerabilityTieIsDeterministic() {
        val asset = saveAsset("rebuild-tie")
        val anchor = LocalDateTime.now().minusDays(60)
        listOf("CVE-2026-0900", "CVE-2026-0500", "CVE-2026-0700").forEach { cve ->
            val vuln = TestDataFactory.createVulnerabilityWithTimestamp(
                asset = asset, cve = cve, severity = "High", scanTimestamp = anchor
            )
            vuln.firstSeenAt = anchor
            vulnerabilityRepository.save(vuln)
        }

        // Deterministic where the old Kotlin `maxByOrNull` returned whichever row the result set
        // happened to yield first.
        assertThat(rebuild().single().oldestVulnId).isEqualTo("CVE-2026-0500")
    }

    @Test
    @DisplayName("assets whose vulnerabilities are all within the threshold produce no rows")
    fun recentVulnerabilitiesProduceNoRows() {
        val asset = saveAsset("rebuild-recent")
        saveVuln(asset, "CVE-2026-0400", "Critical", daysOld = 5)

        assertThat(rebuild()).isEmpty()
    }

    @Test
    @DisplayName("one row per asset, and the row count is what the rebuild reports")
    fun oneRowPerAssetAndReportedCountMatches() {
        val a = saveAsset("rebuild-multi-a")
        val b = saveAsset("rebuild-multi-b")
        saveVuln(a, "CVE-2026-0500", "High")
        saveVuln(a, "CVE-2026-0501", "High")
        saveVuln(b, "CVE-2026-0502", "Critical")

        outdatedAssetRepository.deleteAll()
        val reported = outdatedAssetRepository
            .rebuildFromOverdueVulnerabilities(threshold, LocalDateTime.now())

        assertThat(reported).isEqualTo(2)
        assertThat(outdatedAssetRepository.findAll()).hasSize(2)
        // The progress denominator must agree with what the rebuild actually writes.
        assertThat(outdatedAssetRepository.countAssetsWithOverdueVulnerabilities(threshold))
            .isEqualTo(2L)
    }

    @Test
    @DisplayName("assets with no workgroups get an empty workgroup_ids string, never null")
    fun workgroupIdsDefaultsToEmptyString() {
        val asset = saveAsset("rebuild-no-workgroups")
        saveVuln(asset, "CVE-2026-0600", "High")

        // Readers match with LIKE CONCAT('%', :id, '%') and treat blank as "visible to all",
        // so the COALESCE to '' must hold rather than leaving NULL.
        assertThat(rebuild().single().workgroupIds).isEqualTo("")
    }
}
