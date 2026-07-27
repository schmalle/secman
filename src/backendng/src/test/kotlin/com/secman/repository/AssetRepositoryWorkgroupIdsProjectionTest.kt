package com.secman.repository

import com.secman.domain.Asset
import com.secman.domain.Criticality
import com.secman.domain.Workgroup
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Regression test for the row *shape* returned by
 * [AssetRepository.findWorkgroupIdsByAssetIds].
 *
 * The method used to be declared with `nativeQuery = true`, which made Micronaut Data
 * hand back one-element rows even though the query selected two columns. The only
 * caller — MaterializedViewRefreshService.loadWorkgroupIdsByAsset — reads `row[1]`,
 * so every materialized-view refresh over an asset that actually had a workgroup
 * died with `ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 1`.
 * Assets without workgroups returned zero rows and never tripped it, which is why
 * this hid behind a single test.
 *
 * These assertions lock the two-column contract itself, not just "the caller happens
 * to work": converting the query back to a native one fails `hasSize(2)` immediately.
 *
 * `transactional = false` follows the precedent in [AssetRepositoryLegacyStaleTest] —
 * the rows must really be committed for the projection to see them.
 */
@MicronautTest(environments = ["test"], transactional = false)
open class AssetRepositoryWorkgroupIdsProjectionTest : BaseIntegrationTest() {

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var workgroupRepository: WorkgroupRepository

    private var asset: Asset? = null
    private val workgroups = mutableListOf<Workgroup>()

    @AfterEach
    fun tearDown() {
        asset?.let { assetRepository.delete(it) }
        workgroups.forEach { workgroupRepository.delete(it) }
        asset = null
        workgroups.clear()
    }

    @Test
    fun `returns two-column rows pairing each asset id with each of its workgroup ids`() {
        val suffix = System.nanoTime()

        val wgA = workgroupRepository.save(Workgroup(name = "wg-a-$suffix", criticality = Criticality.HIGH))
        val wgB = workgroupRepository.save(Workgroup(name = "wg-b-$suffix", criticality = Criticality.LOW))
        workgroups += listOf(wgA, wgB)

        val toSave = TestDataFactory.createAsset(name = "wg-projection-asset-$suffix", owner = "owner-$suffix")
        toSave.workgroups = mutableSetOf(wgA, wgB)
        asset = assetRepository.save(toSave)

        val assetId = asset!!.id!!
        val rows = assetRepository.findWorkgroupIdsByAssetIds(listOf(assetId))

        // One row per (asset, workgroup) pair.
        assertThat(rows).hasSize(2)

        // The actual regression: each row must carry BOTH selected columns.
        rows.forEach { row -> assertThat(row).hasSize(2) }

        val pairs = rows.map { (it[0] as Number).toLong() to (it[1] as Number).toLong() }
        assertThat(pairs).containsExactlyInAnyOrder(
            assetId to wgA.id!!,
            assetId to wgB.id!!
        )
    }

    @Test
    fun `returns no rows for an asset with no workgroups`() {
        val suffix = System.nanoTime()

        val toSave = TestDataFactory.createAsset(name = "wg-less-asset-$suffix", owner = "owner-$suffix")
        asset = assetRepository.save(toSave)

        assertThat(assetRepository.findWorkgroupIdsByAssetIds(listOf(asset!!.id!!))).isEmpty()
    }
}
