package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.EolProduct
import com.secman.domain.EolRelease
import com.secman.domain.ProductClass
import com.secman.repository.AssetRepository
import com.secman.repository.EolProductRepository
import com.secman.repository.EolReleaseRepository
import com.secman.repository.InstalledProductRepository
import com.secman.repository.InstalledProductScanRow
import io.micronaut.data.model.Pageable
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The installed-product sweep walks the whole table one page at a time. It used
 * to do that with `LIMIT :size OFFSET :page*:size` over a query that fetch-joined
 * `asset`, which MariaDB planned by driving from `asset` (full scan) and then
 * sorting the entire 497k-row join into a temporary table **per page** — ~5s a
 * page, ~1.5h for one sweep, and quadratic in the table's growth.
 *
 * The replacement pages by key (`WHERE p.id > :afterId ORDER BY p.id`) against
 * `installed_product` alone. That only stays correct if each page resumes from
 * the previous page's *last id* rather than a row offset, so the contract is
 * pinned here: every row is visited exactly once, no row is skipped or repeated,
 * and ids need not be dense (deleted rows leave gaps).
 *
 * ID prefix: EKP-*
 */
class EolScanKeysetPagingTest {

    private val installedProductRepository = mockk<InstalledProductRepository>()
    private val assetRepository = mockk<AssetRepository>(relaxed = true)
    private val eolProductRepository = mockk<EolProductRepository>()
    private val eolReleaseRepository = mockk<EolReleaseRepository>()

    private val pageSize = 500

    private fun service() = EolScanService(
        assetRepository = assetRepository,
        installedProductRepository = installedProductRepository,
        githubRepositoryRepository = mockk(relaxed = true),
        dependabotAlertRepository = mockk(relaxed = true),
        eolProductRepository = eolProductRepository,
        eolReleaseRepository = eolReleaseRepository,
        eolWriter = mockk(relaxed = true),
        matcher = EolVersionMatcher(),
        defaultHorizonMonths = 12,
        pageSize = pageSize
    )

    /** A non-empty catalogue, otherwise [EolScanService.scan] short-circuits before the sweep. */
    private fun stubCatalogue() {
        every { eolProductRepository.findAllOrdered(any()) } returns listOf(
            EolProduct(id = 1L, productKey = "nothing-matches-this", label = "Nothing")
        )
        every { eolReleaseRepository.findAllOrdered(any()) } returns listOf(
            EolRelease(id = 1L, eolProductId = 1L, cycle = "1.0", eolDate = LocalDate.of(2020, 1, 1))
        )
        // The asset sweep and the repository sweep are not under test here.
        every { assetRepository.findAll(any<Pageable>()) } returns io.micronaut.data.model.Page.empty()
    }

    /**
     * Deliberately sparse ids: an offset-based sweep and a keyset sweep behave
     * identically on 1,2,3... and only diverge once rows have been deleted.
     */
    private fun tableOf(rowCount: Int): List<InstalledProductScanRow> =
        (1..rowCount).map { n ->
            InstalledProductScanRow(
                productId = n.toLong() * 7,
                assetId = 1L,
                name = "Product $n",
                vendor = null,
                version = "1.0",
                productClass = ProductClass.UNKNOWN
            )
        }

    private fun stubKeysetSweep(table: List<InstalledProductScanRow>, seen: MutableList<Long>) {
        every { installedProductRepository.findScanRowsAfter(any(), any()) } answers {
            val afterId = firstArg<Long>()
            seen += afterId
            table.filter { it.productId > afterId }.take(pageSize)
        }
        every { assetRepository.findByIdIn(any()) } returns listOf(Asset(id = 1L, name = "host-1", type = "SERVER", owner = "owner"))
    }

    @Test
    @DisplayName("EKP-001: sweeps every installed product exactly once across page boundaries")
    fun sweepsEveryRowOnce() {
        stubCatalogue()
        val table = tableOf(1_250)
        val seen = mutableListOf<Long>()
        stubKeysetSweep(table, seen)

        val result = service().scan(12)

        assertThat(result.installedProductsScanned)
            .describedAs("every row visited exactly once (1250 rows, %d-row pages)", pageSize)
            .isEqualTo(1_250)
    }

    @Test
    @DisplayName("EKP-002: each page resumes from the previous page's last id, not a row offset")
    fun resumesFromLastId() {
        stubCatalogue()
        val table = tableOf(1_250)
        val seen = mutableListOf<Long>()
        stubKeysetSweep(table, seen)

        service().scan(12)

        // 1250 rows / 500 per page = 3 reads. The first starts at the beginning;
        // each later one resumes at the last id of the page before it — 500*7 and
        // 1000*7 — which an offset-based sweep (0, 500, 1000) would never produce.
        assertThat(seen).isEqualTo(listOf(0L, 3_500L, 7_000L))
    }

    @Test
    @DisplayName("EKP-003: an empty table ends the sweep without a second read")
    fun stopsOnEmptyTable() {
        stubCatalogue()
        val seen = mutableListOf<Long>()
        stubKeysetSweep(emptyList(), seen)

        val result = service().scan(12)

        assertThat(result.installedProductsScanned).isZero()
        assertThat(seen).isEqualTo(listOf(0L))
    }
}
