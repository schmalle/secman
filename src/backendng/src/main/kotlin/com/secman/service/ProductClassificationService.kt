package com.secman.service

import com.secman.domain.ProductClass
import com.secman.repository.EolFindingRepository
import com.secman.repository.InstalledProductRepository
import com.secman.repository.ProductClassificationRuleRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.data.model.Pageable
import io.micronaut.scheduling.annotation.Async
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Maintains the materialized `product_class` column on `vulnerability`, `installed_product` and
 * `eol_finding`.
 *
 * Same shape as [ExceptionMaterializationService] — write-time materialization of a predicate
 * that would otherwise be re-derived per row on every read — with one structural difference:
 * the classification is a GLOB match evaluated in Kotlin, so it cannot be pushed into SQL the
 * way `ExceptionMatchSql.EXCEPTION_MATCH` can. Instead each pass resolves the small set of
 * DISTINCT product strings, classifies those, and drives bulk updates from a bound IN list.
 *
 * Direction of safety, identical to `excepted`: the default is visible. Only an explicit
 * INSTALLER_ARTIFACT hides a row, and UNKNOWN reads exactly like INSTALLED.
 */
@Singleton
open class ProductClassificationService(
    private val ruleRepository: ProductClassificationRuleRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val installedProductRepository: InstalledProductRepository,
    private val eolFindingRepository: EolFindingRepository
) {
    private val log = LoggerFactory.getLogger(ProductClassificationService::class.java)

    /**
     * Self-reference through the AOP proxy. `reclassifyVulnerabilities` drives one
     * `REQUIRES_NEW` transaction per id-range chunk; calling [resetAndMarkRange] directly would be
     * a plain self-invocation, which bypasses the proxy and silently loses the annotation — the
     * whole reclassify would then run in (or outside) a single transaction, holding locks across a
     * multi-million-row table. Same pattern and same reason as
     * `CrowdStrikeVulnerabilityImportService.selfProvider`.
     */
    @jakarta.inject.Inject
    private lateinit var selfProvider: jakarta.inject.Provider<ProductClassificationService>

    private val cachedRules = AtomicReference<List<ProductClassifier.CompiledRule>?>(null)

    /**
     * Compiled enabled rules, cached until [invalidateRules].
     *
     * Not `@Cacheable`: this is read once per import transaction and once per reclassify chunk,
     * and the invalidation is a single explicit call from the admin write path.
     */
    open fun rules(): List<ProductClassifier.CompiledRule> =
        cachedRules.get() ?: ProductClassifier.compile(
            ruleRepository.findEnabledOrdered()
        ).also { cachedRules.set(it) }

    open fun invalidateRules() {
        cachedRules.set(null)
    }

    /** Classify a single product string, for the admin "test this value" box. */
    open fun classifyProductName(value: String): ProductClass =
        ProductClassifier.classifyVulnerability(value, rules())

    /** Classify a single path, for the admin "test this value" box. */
    open fun classifyPath(name: String, path: String): ProductClass =
        ProductClassifier.classifyProduct(name, null, listOf(path), rules())

    // ------------------------------------------------------------------ per-asset (import path)

    /**
     * Reclassify one asset's vulnerability rows. Called from the CrowdStrike import beside the
     * existing per-asset exception recompute, so a freshly imported asset is never left at the
     * UNKNOWN default while the rest of the table is classified.
     */
    @Transactional
    open fun recomputeForAsset(assetId: Long) {
        val compiled = rules()
        vulnerabilityRepository.resetProductClassForAsset(assetId)
        if (compiled.isEmpty()) return

        val artifacts = vulnerabilityRepository.findDistinctProductsByAssetId(assetId)
            .filter { ProductClassifier.classifyVulnerability(it, compiled) == ProductClass.INSTALLER_ARTIFACT }
        if (artifacts.isNotEmpty()) {
            artifacts.chunked(IN_LIST_CHUNK).forEach {
                vulnerabilityRepository.markProductClassArtifactForAsset(assetId, it)
            }
        }
    }

    // ------------------------------------------------------------------ full reclassify

    /**
     * Full reclassify across all three tables. Runs off the request path — the admin endpoint
     * starts it and returns immediately, mirroring how a GLOBAL exception change is handled.
     */
    @Async
    open fun reclassifyAllAsync() {
        try {
            reclassifyAll()
        } catch (e: Exception) {
            // Never swallow silently (A09): a failed reclassify leaves stale classes, which
            // means rows stay VISIBLE. Safe, but an operator has to know it happened.
            log.error("Product reclassify failed; classes left stale (rows stay visible)", e)
        }
    }

    open fun reclassifyAll(): ReclassifyResult {
        invalidateRules()
        val compiled = rules()
        log.info("Product reclassify starting with {} compiled rules", compiled.size)

        val products = reclassifyInstalledProducts(compiled)
        val eol = eolFindingRepository.syncProductClassFromInstalledProducts()
        val vulns = reclassifyVulnerabilities(compiled)

        log.info(
            "Product reclassify complete: installed_product={} rows, eol_finding={} rows, vulnerability={} rows; " +
                "artifacts now installed_product={} eol_finding={} vulnerability={}",
            products, eol, vulns,
            installedProductRepository.countByProductClass(ProductClass.INSTALLER_ARTIFACT.name),
            eolFindingRepository.countInstallerArtifacts(),
            vulnerabilityRepository.countInstallerArtifacts()
        )
        return ReclassifyResult(products, eol, vulns)
    }

    /**
     * Page the installed-product table and update in batches grouped by resulting class.
     * Paged rather than bulk-updated because path rules are per-row: the same product name can
     * be an artifact on one host and a real install on another.
     */
    private fun reclassifyInstalledProducts(compiled: List<ProductClassifier.CompiledRule>): Long {
        var afterId = 0L
        var touched = 0L
        while (true) {
            val page = installedProductRepository.findForClassification(afterId, Pageable.from(0, PAGE_SIZE))
            if (page.isEmpty()) break
            val byClass = page.groupBy { product ->
                ProductClassifier.classifyProduct(
                    product.name,
                    product.vendor,
                    listOfNotNull(product.installationPath),
                    compiled
                )
            }
            byClass.forEach { (cls, rows) ->
                rows.mapNotNull { it.id }.chunked(IN_LIST_CHUNK).forEach { ids ->
                    touched += installedProductRepository.updateProductClass(cls.name, ids)
                }
            }
            afterId = page.last().id ?: break
        }
        return touched
    }

    /**
     * Reclassify `vulnerability` by DISTINCT product string, chunked by primary-key range.
     *
     * A single unbounded `UPDATE vulnerability SET product_class = ...` would hold locks across
     * a multi-million-row table; docs/CROWDSTRIKE_IMPORT.md documents that this table is the
     * one with real lock contention during import.
     */
    private fun reclassifyVulnerabilities(compiled: List<ProductClassifier.CompiledRule>): Long {
        val artifacts = vulnerabilityRepository
            .findDistinctProductsForClassification(MAX_DISTINCT_PRODUCTS)
            .filter { ProductClassifier.classifyVulnerability(it, compiled) == ProductClass.INSTALLER_ARTIFACT }
        log.info("Product reclassify: {} distinct product strings classify as installer artifacts", artifacts.size)

        val maxId = vulnerabilityRepository.maxId()
        var touched = 0L
        var fromId = 0L
        while (fromId <= maxId) {
            val toId = fromId + ID_RANGE_CHUNK
            touched += selfProvider.get().resetAndMarkRange(fromId, toId, artifacts)
            fromId = toId
        }
        return touched
    }

    /** One chunk in its own transaction, so a long reclassify never holds a single huge lock. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    open fun resetAndMarkRange(fromId: Long, toId: Long, artifacts: List<String>): Long {
        var touched = vulnerabilityRepository.resetProductClassInIdRange(fromId, toId)
        artifacts.chunked(IN_LIST_CHUNK).forEach {
            vulnerabilityRepository.markProductClassArtifactInIdRange(fromId, toId, it)
        }
        return touched
    }

    data class ReclassifyResult(
        val installedProductRows: Long,
        val eolFindingRows: Long,
        val vulnerabilityRows: Long
    )

    companion object {
        private const val PAGE_SIZE = 1000
        private const val ID_RANGE_CHUNK = 50_000L

        /** Keeps every generated `IN (...)` well under MariaDB's parameter limit. */
        private const val IN_LIST_CHUNK = 500

        /**
         * Bound on the distinct-product scan. The tenant measured ~thousands of distinct
         * strings; a runaway here would mean a data problem, not a bigger estate.
         */
        private const val MAX_DISTINCT_PRODUCTS = 50_000
    }
}
