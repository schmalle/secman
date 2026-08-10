package com.secman.service

import com.secman.domain.EolFinding
import com.secman.domain.EolProduct
import com.secman.domain.EolRelease
import com.secman.repository.EolFindingRepository
import com.secman.repository.EolProductRepository
import com.secman.repository.EolReleaseRepository
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.time.Instant

/**
 * Transactional write side for the EOL catalogue and findings.
 *
 * It is a separate bean on purpose: Micronaut applies `@Transactional` through a
 * generated interceptor, so a `@Transactional` method called from *within* the
 * same bean runs with no transaction at all. Both writes here need real atomicity
 * — a product whose releases were deleted but not reinserted would silently drop
 * every finding referencing it — so the callers reach them across a bean boundary.
 */
@Singleton
open class EolWriter(
    private val eolProductRepository: EolProductRepository,
    private val eolReleaseRepository: EolReleaseRepository,
    private val eolFindingRepository: EolFindingRepository
) {

    /** Upsert one catalogue product and atomically replace its release rows. */
    @Transactional
    open fun persistProduct(
        sourceKey: String,
        productKey: String,
        label: String,
        category: String?,
        aliasBlob: String,
        uri: String?,
        releases: List<EolCatalogClient.ReleaseDetail>
    ): Int {
        val existing = eolProductRepository.findBySourceKeyAndProductKey(sourceKey, productKey)
        val product = if (existing == null) {
            eolProductRepository.save(
                EolProduct(
                    sourceKey = sourceKey,
                    productKey = productKey,
                    label = label,
                    category = category,
                    aliases = aliasBlob,
                    uri = uri,
                    lastSyncedAt = Instant.now()
                )
            )
        } else {
            existing.label = label
            existing.category = category
            existing.aliases = aliasBlob
            existing.uri = uri
            existing.lastSyncedAt = Instant.now()
            eolProductRepository.update(existing)
        }

        val productId = product.id ?: return 0
        eolReleaseRepository.deleteByEolProductId(productId)
        val rows = releases.map { release ->
            EolRelease(
                eolProductId = productId,
                cycle = release.cycle,
                label = release.label,
                releaseDate = release.releaseDate,
                eolDate = release.eolDate,
                supportEndDate = release.supportEndDate,
                alreadyEol = release.alreadyEol,
                eolUnknown = release.eolUnknown,
                lts = release.lts,
                latestVersion = release.latestVersion
            )
        }
        eolReleaseRepository.saveAll(rows)
        return rows.size
    }

    @Transactional
    open fun saveFindings(findings: List<EolFinding>) {
        if (findings.isEmpty()) return
        eolFindingRepository.saveAll(findings)
    }

    /** Drop every finding not written by [scanRunId]. Returns rows removed. */
    @Transactional
    open fun deleteFindingsFromOtherRuns(scanRunId: String): Int =
        eolFindingRepository.deleteByScanRunIdNot(scanRunId)
}
