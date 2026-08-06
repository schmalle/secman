package com.secman.integration

import com.secman.domain.Asset
import com.secman.domain.Vulnerability
import com.secman.repository.AssetRepository
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
 * `countLatestVulnerabilitiesBySeverityForAssetIds` must agree, count for count, with the entity
 * load it replaced on the Domain Vulnerabilities page.
 *
 * That page previously called `findLatestVulnerabilitiesForAssetIds` — one managed `Vulnerability`
 * per row for every asset in the user's AD domains — and derived `.size` and
 * `.count { severity.equals(..., ignoreCase = true) }` from it, on an interactive request. The two
 * queries share the same `DENSE_RANK` latest-import-per-asset filter, so this test asserts the
 * aggregate agrees with counting the entities, which is the property the swap relies on.
 */
@DisplayName("Latest-import per-asset severity counts")
class LatestVulnSeverityCountIntegrationTest : BaseIntegrationTest() {

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var vulnerabilityRepository: VulnerabilityRepository

    @AfterEach
    fun cleanup() {
        vulnerabilityRepository.deleteAll()
        assetRepository.deleteAll()
    }

    private fun saveVuln(asset: Asset, cve: String, severity: String?, importedAt: LocalDateTime?) {
        val vuln = TestDataFactory.createVulnerabilityWithTimestamp(
            asset = asset,
            cve = cve,
            severity = severity ?: "",
            scanTimestamp = LocalDateTime.now().minusDays(5)
        )
        vuln.cvssSeverity = severity
        vuln.importTimestamp = importedAt
        vulnerabilityRepository.save(vuln)
    }

    @Test
    @DisplayName("agrees with counting the entities the old query returned")
    fun aggregateAgreesWithEntityCounting() {
        val a = assetRepository.save(TestDataFactory.createAsset(name = "count-host-a"))
        val b = assetRepository.save(TestDataFactory.createAsset(name = "count-host-b"))
        val latest = LocalDateTime.now().minusHours(1)
        val older = LocalDateTime.now().minusDays(3)

        // Title-case severities, as the importer actually stores them.
        saveVuln(a, "CVE-2026-1001", "Critical", latest)
        saveVuln(a, "CVE-2026-1002", "High", latest)
        saveVuln(a, "CVE-2026-1003", "Medium", latest)
        saveVuln(a, "CVE-2026-1004", "Low", latest)
        // Superseded by the newer import — must be excluded by both queries.
        saveVuln(a, "CVE-2026-1099", "Critical", older)
        // A severity outside the four buckets, plus a NULL one.
        saveVuln(a, "CVE-2026-1005", "Informational", latest)
        saveVuln(a, "CVE-2026-1006", null, latest)

        saveVuln(b, "CVE-2026-2001", "High", latest)

        val assetIds = setOf(a.id!!, b.id!!)
        val entities = vulnerabilityRepository.findLatestVulnerabilitiesForAssetIds(assetIds)
        val aggregates = vulnerabilityRepository
            .countLatestVulnerabilitiesBySeverityForAssetIds(assetIds)
            .associateBy { it.assetId }

        // Same universe of rows.
        assertThat(aggregates.values.sumOf { it.totalCount!!.toInt() }).isEqualTo(entities.size)

        // Same per-asset, per-severity verdicts as the Kotlin this replaced.
        entities.groupBy { it.asset.id }.forEach { (assetId, vulns) ->
            val agg = aggregates[assetId]
            requireNotNull(agg) { "no aggregate row for asset $assetId" }
            assertThat(agg.totalCount!!.toInt()).isEqualTo(vulns.size)
            assertThat(agg.criticalCount!!.toInt())
                .isEqualTo(vulns.count { it.cvssSeverity.equals("Critical", ignoreCase = true) })
            assertThat(agg.highCount!!.toInt())
                .isEqualTo(vulns.count { it.cvssSeverity.equals("High", ignoreCase = true) })
            assertThat(agg.mediumCount!!.toInt())
                .isEqualTo(vulns.count { it.cvssSeverity.equals("Medium", ignoreCase = true) })
            assertThat(agg.lowCount!!.toInt())
                .isEqualTo(vulns.count { it.cvssSeverity.equals("Low", ignoreCase = true) })
        }

        // Spot-check the interesting asset explicitly: 6 rows in the latest import (the older
        // Critical is excluded), with Informational and NULL counted in the total but in no bucket.
        val aggA = aggregates[a.id]!!
        assertThat(aggA.totalCount!!.toInt()).isEqualTo(6)
        assertThat(aggA.criticalCount!!.toInt()).isEqualTo(1)
        assertThat(aggA.lowCount!!.toInt()).isEqualTo(1)
    }

    @Test
    @DisplayName("assets with no vulnerabilities simply get no row, so callers fall through to zero")
    fun assetsWithoutVulnerabilitiesAreAbsent() {
        val empty = assetRepository.save(TestDataFactory.createAsset(name = "count-host-empty"))

        val aggregates = vulnerabilityRepository
            .countLatestVulnerabilitiesBySeverityForAssetIds(setOf(empty.id!!))

        assertThat(aggregates).isEmpty()
    }
}
