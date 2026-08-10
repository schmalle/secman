package com.secman.service

import com.secman.domain.EolProduct
import io.micronaut.context.annotation.Value
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Downloads the EOL catalogue and upserts it into [EolProduct] / [EolRelease].
 *
 * Upsert, not replace: a product that disappears upstream (renamed, temporarily
 * 500ing) keeps its stored cycles rather than silently deleting every finding
 * that references it. Releases *within* a synced product are replaced, so a
 * corrected EOL date propagates on the next run.
 *
 * Each product is committed in its own transaction so a source hiccup halfway
 * through ~350 products leaves a usable partial catalogue instead of rolling
 * back the lot.
 */
@Singleton
open class EolCatalogSyncService(
    private val catalogClient: EolCatalogClient,
    private val eolWriter: EolWriter,

    @Value("\${secman.eol.max-products:2000}")
    private val maxProducts: Int
) {
    private val log = LoggerFactory.getLogger(EolCatalogSyncService::class.java)

    @Serdeable
    data class CatalogSyncResult(
        val sourceKey: String,
        val productsRequested: Int,
        val productsSynced: Int,
        val releasesSynced: Int,
        val productsFailed: List<String>,
        val errorSummary: String?
    )

    /**
     * @param onlyProducts when non-empty, sync just these product keys (used by
     *   `secman eol-sync --products ubuntu,rhel` to refresh a subset cheaply).
     */
    open fun sync(onlyProducts: Collection<String> = emptyList()): CatalogSyncResult {
        val sourceKey = EolProduct.DEFAULT_SOURCE_KEY
        val requested: List<String> = if (onlyProducts.isNotEmpty()) {
            onlyProducts.mapNotNull { runCatching { catalogClient.sanitizeProductKey(it) }.getOrNull() }.distinct()
        } else {
            catalogClient.listProductKeys().distinct()
        }

        if (requested.isEmpty()) {
            return CatalogSyncResult(sourceKey, 0, 0, 0, emptyList(), "EOL source returned no products")
        }
        // Bound the fan-out: the product index is upstream-controlled data and a
        // runaway list must not turn one admin action into an unbounded crawl.
        val bounded = requested.take(maxProducts.coerceIn(1, 10_000))

        var productsSynced = 0
        var releasesSynced = 0
        val failed = mutableListOf<String>()

        for (productKey in bounded) {
            try {
                val detail = catalogClient.fetchProduct(productKey)
                if (detail == null) {
                    failed += productKey
                    continue
                }
                releasesSynced += persistProduct(sourceKey, detail)
                productsSynced++
            } catch (e: Exception) {
                // Logged with the target and outcome (§A09) — never swallowed.
                log.warn("EOL catalogue sync failed for product {}: {}", productKey, e.message)
                failed += productKey
            }
        }

        val errorSummary = when {
            failed.isEmpty() -> null
            else -> "${failed.size} of ${bounded.size} products could not be synced"
        }
        log.info(
            "EOL catalogue sync finished: source={} requested={} synced={} releases={} failed={}",
            sourceKey, bounded.size, productsSynced, releasesSynced, failed.size
        )
        return CatalogSyncResult(
            sourceKey = sourceKey,
            productsRequested = bounded.size,
            productsSynced = productsSynced,
            releasesSynced = releasesSynced,
            productsFailed = failed.take(50),
            errorSummary = errorSummary
        )
    }

    /** Upsert one product and replace its release rows. Returns releases written. */
    private fun persistProduct(sourceKey: String, detail: EolCatalogClient.ProductDetail): Int =
        eolWriter.persistProduct(
            sourceKey = sourceKey,
            productKey = detail.productKey,
            label = detail.label,
            category = detail.category,
            aliasBlob = buildAliasBlob(detail),
            uri = detail.uri,
            releases = detail.releases
        )

    /**
     * Alias blob consumed by [EolVersionMatcher]: the upstream aliases plus the
     * label and the de-hyphenated key, lowercased and comma-joined.
     */
    fun buildAliasBlob(detail: EolCatalogClient.ProductDetail): String {
        val aliases = LinkedHashSet<String>()
        aliases += detail.productKey
        aliases += detail.productKey.replace('-', ' ')
        aliases += detail.label
        detail.aliases.forEach { aliases += it }
        return aliases
            .map { it.lowercase().trim() }
            .filter { it.isNotEmpty() && !it.contains(',') }
            .joinToString(",")
            .take(2048)
    }
}
