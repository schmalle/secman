package com.secman.service

import com.secman.domain.EolFinding
import com.secman.domain.EolStatus
import com.secman.domain.EolSubjectType
import com.secman.domain.GithubRepository
import com.secman.repository.AssetRepository
import com.secman.repository.EolProductRepository
import com.secman.repository.EolReleaseRepository
import com.secman.repository.GithubRepoDependabotAlertRepository
import com.secman.repository.GithubRepositoryRepository
import com.secman.domain.ProductClass
import com.secman.repository.InstalledProductRepository
import io.micronaut.context.annotation.Value
import io.micronaut.data.model.Pageable
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.UUID

/**
 * Recomputes [EolFinding] rows by matching the current inventory against the
 * catalogue.
 *
 * Replace-per-run, like the CrowdStrike vulnerability import: every row written
 * carries the run's `scanRunId`, and rows from earlier runs are deleted at the
 * end. A component that was upgraded simply is not reproduced. The delete is
 * explicit here and `Asset` deliberately owns no cascade to `EolFinding` — see
 * CLAUDE.md §Transactional replace for why cascade and manual delete-insert must
 * not be mixed.
 *
 * Only [EolStatus.EOL] and [EolStatus.APPROACHING_EOL] are persisted; storing
 * supported components would mean a row per installed product per asset.
 */
@Singleton
open class EolScanService(
    private val assetRepository: AssetRepository,
    private val installedProductRepository: InstalledProductRepository,
    private val githubRepositoryRepository: GithubRepositoryRepository,
    private val dependabotAlertRepository: GithubRepoDependabotAlertRepository,
    private val eolProductRepository: EolProductRepository,
    private val eolReleaseRepository: EolReleaseRepository,
    private val eolWriter: EolWriter,
    private val matcher: EolVersionMatcher,

    @Value("\${secman.eol.horizon-months:12}")
    private val defaultHorizonMonths: Long,

    @Value("\${secman.eol.scan-page-size:500}")
    private val pageSize: Int
) {
    private val log = LoggerFactory.getLogger(EolScanService::class.java)

    @Serdeable
    data class ScanResult(
        val scanRunId: String,
        val horizonMonths: Long,
        val catalogProducts: Int,
        val assetsScanned: Int,
        val installedProductsScanned: Int,
        val repositoriesScanned: Int,
        val repositoryComponentsScanned: Int,
        val findingsWritten: Int,
        val eolFindings: Int,
        val approachingFindings: Int,
        val findingsRemoved: Int,
        val errorSummary: String?
    )

    /**
     * @param horizonMonths how far ahead counts as "approaching EOL"; the owner
     *   notification default (12 months) is the product requirement, so the scan
     *   must persist at least that far ahead or the mail has nothing to read.
     */
    open fun scan(horizonMonths: Long? = null): ScanResult {
        val horizon = (horizonMonths ?: defaultHorizonMonths).coerceIn(1L, MAX_HORIZON_MONTHS)
        val runId = UUID.randomUUID().toString()
        val today = LocalDate.now()
        val effectivePageSize = pageSize.coerceIn(50, 5_000)

        val index = buildIndex()
        if (index == null) {
            return ScanResult(
                scanRunId = runId,
                horizonMonths = horizon,
                catalogProducts = 0,
                assetsScanned = 0,
                installedProductsScanned = 0,
                repositoriesScanned = 0,
                repositoryComponentsScanned = 0,
                findingsWritten = 0,
                eolFindings = 0,
                approachingFindings = 0,
                findingsRemoved = 0,
                errorSummary = "EOL catalogue is empty — run the catalogue sync first"
            )
        }

        val counters = Counters()

        scanAssetOperatingSystems(index.matcherIndex, runId, today, horizon, effectivePageSize, counters)
        scanInstalledProducts(index.matcherIndex, runId, today, horizon, effectivePageSize, counters)
        scanRepositories(index.matcherIndex, runId, today, horizon, effectivePageSize, counters)

        val removed = deleteStaleFindings(runId)

        log.info(
            "EOL scan finished: runId={} horizonMonths={} assets={} products={} repos={} findings={} removed={}",
            runId, horizon, counters.assets, counters.installedProducts, counters.repositories,
            counters.written, removed
        )

        return ScanResult(
            scanRunId = runId,
            horizonMonths = horizon,
            catalogProducts = index.productCount,
            assetsScanned = counters.assets,
            installedProductsScanned = counters.installedProducts,
            repositoriesScanned = counters.repositories,
            repositoryComponentsScanned = counters.repositoryComponents,
            findingsWritten = counters.written,
            eolFindings = counters.eol,
            approachingFindings = counters.approaching,
            findingsRemoved = removed,
            errorSummary = null
        )
    }

    // ------------------------------------------------------------------ catalog

    /** Public, not private: both are referenced from `open` members. */
    class LoadedIndex(val matcherIndex: EolVersionMatcher.Index, val productCount: Int)

    class Counters {
        var assets = 0
        var installedProducts = 0
        var repositories = 0
        var repositoryComponents = 0
        var written = 0
        var eol = 0
        var approaching = 0
    }

    /**
     * Load the whole catalogue once per scan. Micronaut Data repository methods
     * are individually transactional, so no outer transaction is needed here —
     * and an outer read-only transaction spanning the entire scan would hold a
     * connection for its full duration.
     */
    open fun buildIndex(): LoadedIndex? {
        val products = eolProductRepository.findAllOrdered(Pageable.from(0, MAX_CATALOG_PRODUCTS))
        if (products.isEmpty()) return null
        val releases = eolReleaseRepository
            .findAllOrdered(Pageable.from(0, MAX_CATALOG_RELEASES))
            .groupBy { it.eolProductId }

        val catalogProducts = products.mapNotNull { product ->
            val productId = product.id ?: return@mapNotNull null
            EolVersionMatcher.CatalogProduct(
                productId = productId,
                productKey = product.productKey,
                label = product.label,
                category = product.category,
                aliases = product.aliases?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                    ?: emptySet(),
                // Explicit label: this lambda nests inside the outer mapNotNull, and
                // a bare `return@mapNotNull` there reads as if it skipped the product.
                releases = releases[productId].orEmpty().mapNotNull cycle@{ release ->
                    val releaseId = release.id ?: return@cycle null
                    EolVersionMatcher.CatalogRelease(
                        releaseId = releaseId,
                        cycle = release.cycle,
                        label = release.label,
                        eolDate = release.eolDate,
                        alreadyEol = release.alreadyEol,
                        eolUnknown = release.eolUnknown,
                        lts = release.lts
                    )
                }
            )
        }
        return LoadedIndex(EolVersionMatcher.Index.build(catalogProducts), catalogProducts.size)
    }

    // ------------------------------------------------------------------- assets

    private fun scanAssetOperatingSystems(
        index: EolVersionMatcher.Index,
        runId: String,
        today: LocalDate,
        horizon: Long,
        pageSize: Int,
        counters: Counters
    ) {
        var page = 0
        while (page < MAX_PAGES) {
            val assets = assetRepository.findAll(Pageable.from(page, pageSize)).content
            if (assets.isEmpty()) break
            val batch = mutableListOf<EolFinding>()
            for (asset in assets) {
                counters.assets++
                val assetId = asset.id ?: continue
                val match = matcher.matchOs(index, asset.osVersion, today, horizon) ?: continue
                if (match.status == EolStatus.SUPPORTED) continue
                batch += EolFinding(
                    subjectType = EolSubjectType.ASSET_OS,
                    assetId = assetId,
                    assetName = asset.name.take(512),
                    cloudAccountId = asset.cloudAccountId,
                    adDomain = asset.adDomain,
                    assetOwner = asset.owner.take(255),
                    componentName = (asset.osVersion ?: "").take(512),
                    componentVersion = asset.osVersion?.take(255),
                    eolProductId = match.product.productId,
                    eolProductKey = match.product.productKey,
                    eolReleaseId = match.release.releaseId,
                    eolCycle = match.release.cycle,
                    eolDate = match.release.eolDate,
                    status = match.status,
                    daysUntilEol = match.daysUntilEol,
                    scanRunId = runId,
                    // An operating system is never an installer payload.
                    productClass = ProductClass.INSTALLED
                )
            }
            persistBatch(batch, counters)
            if (assets.size < pageSize) break
            page++
        }
    }

    private fun scanInstalledProducts(
        index: EolVersionMatcher.Index,
        runId: String,
        today: LocalDate,
        horizon: Long,
        pageSize: Int,
        counters: Counters
    ) {
        var page = 0
        while (page < MAX_PAGES) {
            val products = loadInstalledProductPage(page, pageSize)
            if (products.isEmpty()) break
            val batch = mutableListOf<EolFinding>()
            for (product in products) {
                counters.installedProducts++
                val asset = product.asset
                val assetId = asset.id ?: continue
                val match = matcher.matchComponent(
                    index = index,
                    name = product.name,
                    vendor = product.vendor,
                    version = product.version,
                    today = today,
                    horizonMonths = horizon
                ) ?: continue
                if (match.status == EolStatus.SUPPORTED) continue
                batch += EolFinding(
                    subjectType = EolSubjectType.ASSET_PRODUCT,
                    assetId = assetId,
                    assetName = asset.name.take(512),
                    cloudAccountId = asset.cloudAccountId,
                    adDomain = asset.adDomain,
                    assetOwner = asset.owner.take(255),
                    installedProductId = product.id,
                    componentName = product.name.take(512),
                    componentVendor = product.vendor?.take(255),
                    componentVersion = product.version?.take(255),
                    eolProductId = match.product.productId,
                    eolProductKey = match.product.productKey,
                    eolReleaseId = match.release.releaseId,
                    eolCycle = match.release.cycle,
                    eolDate = match.release.eolDate,
                    status = match.status,
                    daysUntilEol = match.daysUntilEol,
                    scanRunId = runId,
                    // Carried from the source row rather than recomputed: the installed-product
                    // class is already materialized, and denormalizing it here keeps the EOL
                    // read queries single-table (as assetName / cloudAccountId / assetOwner do).
                    productClass = product.productClass
                )
            }
            persistBatch(batch, counters)
            if (products.size < pageSize) break
            page++
        }
    }

    private fun loadInstalledProductPage(page: Int, pageSize: Int) =
        installedProductRepository.findAllWithAssetOrdered(Pageable.from(page, pageSize))

    // ------------------------------------------------------------- repositories

    /**
     * Repository components come from Dependabot alerts, which carry the package
     * and the *vulnerable range* but never the version actually resolved in the
     * lockfile. The scan therefore uses the range's upper bound as a sound upper
     * approximation: from `< 4.17.21` we know the dependency is below 4.17.21, so
     * if the cycle containing 4.17.21 is already EOL then whatever is installed
     * is in that cycle or an older one and is EOL too.
     *
     * That direction only ever under-reports. A repository dependency whose range
     * has no parseable upper bound is skipped rather than guessed at.
     */
    private fun scanRepositories(
        index: EolVersionMatcher.Index,
        runId: String,
        today: LocalDate,
        horizon: Long,
        pageSize: Int,
        counters: Counters
    ) {
        var page = 0
        while (page < MAX_PAGES) {
            // Paged, never findAll(): the repository table is import-driven and
            // unbounded from this service's point of view (CLAUDE.md §A04).
            val repositories = githubRepositoryRepository.findAll(Pageable.from(page, pageSize)).content
            if (repositories.isEmpty()) break
            repositories.forEach { repository ->
                scanRepository(index, repository, runId, today, horizon, counters)
            }
            if (repositories.size < pageSize) break
            page++
        }
    }

    private fun scanRepository(
        index: EolVersionMatcher.Index,
        repository: GithubRepository,
        runId: String,
        today: LocalDate,
        horizon: Long,
        counters: Counters
    ) {
        counters.repositories++
        val repositoryId = repository.id ?: return
        val alerts = dependabotAlertRepository.findByGithubRepositoryId(repositoryId)
        if (alerts.isEmpty()) return

        val batch = mutableListOf<EolFinding>()
        val seen = HashSet<String>()
        for (alert in alerts) {
            counters.repositoryComponents++
            val upperBound = upperBoundOf(alert.vulnerableVersionRange) ?: continue
            val match = matcher.matchComponent(
                index = index,
                name = alert.packageName,
                vendor = null,
                version = upperBound,
                today = today,
                horizonMonths = horizon
            ) ?: continue
            // Only a cycle that is already EOL is sound for an upper bound —
            // "approaching" says nothing about the older version in use.
            if (match.status != EolStatus.EOL) continue
            if (!seen.add("${alert.packageName.lowercase()}@${match.release.cycle}")) continue

            batch += EolFinding(
                subjectType = EolSubjectType.REPOSITORY_COMPONENT,
                githubRepositoryId = repositoryId,
                repositoryFullName = repository.fullName.take(512),
                componentName = alert.packageName.take(512),
                componentVersion = "< $upperBound".take(255),
                ecosystem = alert.ecosystem.take(64),
                eolProductId = match.product.productId,
                eolProductKey = match.product.productKey,
                eolReleaseId = match.release.releaseId,
                eolCycle = match.release.cycle,
                eolDate = match.release.eolDate,
                status = match.status,
                daysUntilEol = match.daysUntilEol,
                scanRunId = runId,
                // A repository dependency is not a file on disk, so it cannot be a payload.
                productClass = ProductClass.INSTALLED
            )
        }
        persistBatch(batch, counters)
    }

    /** Smallest `<`/`<=` bound in a Dependabot range such as `>= 4.0.0, < 4.17.21`. */
    fun upperBoundOf(range: String?): String? {
        if (range.isNullOrBlank()) return null
        val bounds = UPPER_BOUND.findAll(range).mapNotNull { it.groupValues.getOrNull(1) }.toList()
        if (bounds.isEmpty()) return null
        return bounds.minByOrNull { candidate ->
            EolVersionMatcher.segments(candidate).joinToString(".") { it.padStart(8, '0') }
        }
    }

    // ------------------------------------------------------------------ writing

    private fun persistBatch(batch: List<EolFinding>, counters: Counters) {
        if (batch.isEmpty()) return
        eolWriter.saveFindings(batch)
        counters.written += batch.size
        counters.eol += batch.count { it.status == EolStatus.EOL }
        counters.approaching += batch.count { it.status == EolStatus.APPROACHING_EOL }
    }

    private fun deleteStaleFindings(runId: String): Int = eolWriter.deleteFindingsFromOtherRuns(runId)

    companion object {
        private const val MAX_HORIZON_MONTHS = 120L
        private const val MAX_CATALOG_PRODUCTS = 5_000
        private const val MAX_CATALOG_RELEASES = 200_000

        /** Backstop against an unbounded paging loop if a page never shrinks. */
        private const val MAX_PAGES = 100_000

        private val UPPER_BOUND = Regex("<=?\\s*(\\d+(?:\\.\\d+)*)")
    }
}
