package com.secman.repository

import com.secman.domain.OutdatedAssetMaterializedView
import com.secman.domain.Workgroup
import com.secman.service.AssetCurrentnessPolicy
import com.secman.service.AssetFilterService
import com.secman.service.AssetHeatmapService
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.QueryCounter
import com.secman.testutil.TestDataFactory
import io.micronaut.security.authentication.Authentication
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Read-path optimizations (docs/plans/DB_READ_OPTIMIZATION_PLAN.md): the generated columns must
 * agree with the definitions they replaced, and the access checks must stay single round trips.
 */
@MicronautTest(environments = ["test"], transactional = false, startApplication = false)
class ReadPathIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var groups: WorkgroupRepository
    @Inject lateinit var vulnerabilities: VulnerabilityRepository
    @Inject lateinit var filter: AssetFilterService
    @Inject lateinit var heatmap: AssetHeatmapService
    @Inject lateinit var heatmapEntries: AssetHeatmapRepository
    @Inject lateinit var outdated: OutdatedAssetMaterializedViewRepository

    private val now: LocalDateTime get() = LocalDateTime.now(ZoneOffset.UTC)

    @Test
    fun `observed_at agrees with the currentness policy for every evidence shape`() {
        val suffix = System.nanoTime()
        val fixtures = listOf(
            TestDataFactory.createAsset("scan-only-$suffix"),
            TestDataFactory.createAsset("falcon-fresh-$suffix", owner = "CrowdStrike Import").apply {
                crowdStrikeAgentSeenAt = now.minusHours(1)
            },
            // Falcon-managed: a fresh scan observation cannot stand in for stale sensor contact.
            TestDataFactory.createAsset("falcon-stale-$suffix").apply {
                crowdStrikeManaged = true
                crowdStrikeAgentSeenAt = now.minusHours(100)
            },
            TestDataFactory.createAsset("never-seen-$suffix").apply { verifiedObservedAt = null }
        ).map { assets.save(it) }

        val currentIds = assets.findCurrentIdsIn(fixtures.map { it.id!! }).toSet()
        val jpqlCurrentIds = assets.findCurrent().mapNotNull { it.id }.toSet()
        for (asset in fixtures) {
            val expected = AssetCurrentnessPolicy.isCurrent(asset)
            assertThat(asset.id in currentIds).describedAs("native ${asset.name}").isEqualTo(expected)
            assertThat(asset.id in jpqlCurrentIds).describedAs("JPQL ${asset.name}").isEqualTo(expected)
        }
    }

    @Test
    fun `overdue filter anchors on first_seen_at with scan_timestamp as the fallback`() {
        val name = "sla-${System.nanoTime()}"
        val asset = assets.save(TestDataFactory.createAsset(name))
        vulnerabilities.save(TestDataFactory.createVulnerability(asset, "CVE-2026-0001", daysOpen = 10)
            .apply { firstSeenAt = now.minusDays(400) })
        val legacy = vulnerabilities.save(TestDataFactory.createVulnerability(asset, "CVE-2026-0002", daysOpen = 10))
        vulnerabilities.update(legacy.apply { firstSeenAt = null })

        fun overdueSince(days: Long) = vulnerabilities.countOverdueVulnerabilitiesAdmin(
            LocalDateTime.now().minusDays(days), null, name, null, null, null, 1
        )
        // 30 days: only the row first seen 400 days ago. 5 days: the legacy row's scan date counts too.
        assertThat(overdueSince(30)).isEqualTo(1)
        assertThat(overdueSince(5)).isEqualTo(2)
    }

    @Test
    fun `access checks are one round trip and respect currentness`() {
        val suffix = System.nanoTime()
        val group = groups.save(Workgroup(name = "read-path-$suffix"))
        val user = users.save(TestDataFactory.createRegularUser("rp-$suffix", "rp-$suffix@test.com").apply {
            workgroups = mutableSetOf(group)
        })
        val current = assets.save(TestDataFactory.createAsset("rp-current-$suffix").apply { workgroups.add(group) })
        val stale = assets.save(TestDataFactory.createAsset("rp-stale-$suffix").apply {
            workgroups.add(group)
            verifiedObservedAt = now.minusHours(100)
        })
        val foreign = assets.save(TestDataFactory.createAsset("rp-foreign-$suffix"))
        val auth = Authentication.build(user.username, listOf("USER"), mapOf("userId" to user.id!!, "email" to user.email))

        var ids: Set<Long> = emptySet()
        val idStatements = QueryCounter.capture { ids = filter.getAccessibleAssetIds(auth) }
        assertThat(ids).containsExactly(current.id!!)
        assertThat(idStatements).hasSize(1)

        // Stored-record grants ignore currentness; an ungranted asset stays invisible.
        val checkStatements = QueryCounter.capture {
            assertThat(filter.canAccessAsset(stale.id!!, auth)).isTrue()
        }
        assertThat(checkStatements).hasSize(1)
        assertThat(filter.canAccessAsset(foreign.id!!, auth)).isFalse()
    }

    @Test
    fun `heatmap rebuild computes counts, heat level and workgroups in SQL`() {
        val suffix = System.nanoTime()
        val group = groups.save(Workgroup(name = "heat-$suffix"))
        val hot = assets.save(TestDataFactory.createAsset("heat-hot-$suffix").apply { workgroups.add(group) })
        val calm = assets.save(TestDataFactory.createAsset("heat-calm-$suffix"))
        vulnerabilities.save(TestDataFactory.createVulnerability(hot, "CVE-2026-1000", severity = "CRITICAL"))
        vulnerabilities.save(TestDataFactory.createVulnerability(hot, "CVE-2026-1001", severity = "HIGH"))
        vulnerabilities.save(TestDataFactory.createVulnerability(calm, "CVE-2026-1002", severity = "LOW"))

        heatmap.recalculateHeatmap()

        val byAsset = heatmapEntries.findByAssetIds(listOf(hot.id!!, calm.id!!)).associateBy { it.assetId }
        val hotEntry = byAsset.getValue(hot.id!!)
        assertThat(hotEntry.criticalCount).isEqualTo(1)
        assertThat(hotEntry.highCount).isEqualTo(1)
        assertThat(hotEntry.totalCount).isEqualTo(2)
        assertThat(hotEntry.heatLevel).isEqualTo("RED")
        assertThat(hotEntry.workgroupIds).isEqualTo(group.id.toString())
        val calmEntry = byAsset.getValue(calm.id!!)
        assertThat(calmEntry.heatLevel).isEqualTo("GREEN")
        assertThat(calmEntry.lowCount).isEqualTo(1)
        assertThat(calmEntry.workgroupIds).isNull()
    }

    @Test
    fun `outdated workgroup filter matches whole ids, not substrings`() {
        val base = 900_000L + (System.nanoTime() % 50_000L)
        fun row(wgs: String) = OutdatedAssetMaterializedView(
            assetId = base + wgs.length, assetName = "outdated-$wgs-$base", assetType = "SERVER",
            totalOverdueCount = 1, oldestVulnDays = 90, workgroupIds = wgs
        )
        // Rows without workgroups match every filter by design; count relative to them.
        val ungrouped = outdated.countOutdatedAssets("-1")
        outdated.saveAll(listOf(row("$base"), row("1$base,2$base")))

        assertThat(outdated.countOutdatedAssets("$base")).isEqualTo(ungrouped + 1)
        assertThat(outdated.countOutdatedAssets("2$base")).isEqualTo(ungrouped + 1)
    }
}
