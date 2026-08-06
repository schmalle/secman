package com.secman.service

import com.secman.crowdstrike.dto.InstalledProductDto
import com.secman.domain.Asset
import com.secman.domain.InstalledProduct
import com.secman.dto.InstalledProductImportResponse
import com.secman.repository.AssetRepository
import com.secman.repository.InstalledProductRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

@Singleton
open class InstalledProductImportService(
    private val assetRepository: AssetRepository,
    private val installedProductRepository: InstalledProductRepository
) {
    private val log = LoggerFactory.getLogger(InstalledProductImportService::class.java)

    companion object {
        const val MAX_PRODUCTS_PER_REQUEST = 5_000
    }

    @Transactional
    open fun importProducts(
        products: List<InstalledProductDto>,
        dryRun: Boolean,
        importRunId: String? = null
    ): InstalledProductImportResponse {
        require(products.size <= MAX_PRODUCTS_PER_REQUEST) {
            "At most $MAX_PRODUCTS_PER_REQUEST products can be imported per request"
        }

        var imported = 0
        var updated = 0
        var skipped = 0
        var deleted = 0
        val errors = mutableListOf<String>()
        val now = LocalDateTime.now()
        // Assets whose stale products have already been cleared during this request.
        val clearedAssetIds = mutableSetOf<Long>()

        // Distinct hosts (normalized) we could not resolve. Tracked as sets so the
        // reported "unknown systems" count reflects hosts, not product rows: a single
        // unmatched host typically carries many products (avg ~6.5).
        val unknownHosts = LinkedHashSet<String>()
        val ambiguousHosts = LinkedHashSet<String>()
        var unknownRows = 0

        // Resolve hostnames against an in-memory index built once per request. The asset
        // table and the installed-product feed come from two different CrowdStrike APIs
        // that may name the same host differently (short vs FQDN), so we match in both
        // directions. Asset count is small (low thousands); a full scan here is cheap.
        val allAssets = assetRepository.findAll()
        val exactByName = HashMap<String, Asset>(allAssets.size * 2)
        val assetsByShortName = HashMap<String, MutableList<Asset>>()
        allAssets.forEach { a ->
            val norm = normHost(a.name) ?: return@forEach
            exactByName.putIfAbsent(norm, a)
            val short = norm.substringBefore('.')
            assetsByShortName.getOrPut(short) { mutableListOf() }.add(a)
        }

        products.forEach { dto ->
            try {
                val hostname = normalize(dto.hostname, 255)
                val name = normalize(dto.name, 512)
                if (name == null) {
                    skipped++
                    errors.add("Skipped blank product name for ${hostname ?: "unknown host"}")
                    return@forEach
                }

                val asset = when (val resolution = resolveAsset(hostname, exactByName, assetsByShortName)) {
                    is Resolution.Found -> resolution.asset
                    Resolution.Ambiguous -> {
                        ambiguousHosts.add(normHost(hostname) ?: "(no hostname)")
                        unknownRows++
                        skipped++
                        return@forEach
                    }
                    Resolution.NotFound -> {
                        unknownHosts.add(normHost(hostname) ?: "(no hostname)")
                        unknownRows++
                        skipped++
                        return@forEach
                    }
                }
                if (dryRun) {
                    imported++
                    return@forEach
                }

                val assetId = requireNotNull(asset.id)

                // Clean-state replace: the first time we touch an asset in this request, delete its
                // existing products so the import leaves an exact snapshot. When an importRunId is
                // supplied, rows already written by this run (possibly in an earlier batch) are kept.
                if (clearedAssetIds.add(assetId)) {
                    deleted += if (importRunId != null) {
                        installedProductRepository.deleteByAssetIdAndImportRunIdNot(assetId, importRunId)
                    } else {
                        installedProductRepository.deleteByAssetId(assetId)
                    }
                }

                val externalId = normalize(dto.externalId, 255)
                val vendor = normalize(dto.vendor, 255)
                val version = normalize(dto.version, 255)
                val existing = externalId?.let { installedProductRepository.findByExternalIdAndAssetId(it, assetId) }
                    ?: installedProductRepository.findLogicalDuplicate(assetId, name, vendor, version)

                val conflictingAssetId = externalId
                    ?.let { installedProductRepository.findByExternalId(it) }
                    ?.asset
                    ?.id
                    ?.takeIf { it != assetId }
                if (conflictingAssetId != null) {
                    skipped++
                    val message = "Skipped product '$name' for '$hostname': external id is already assigned to another asset"
                    log.warn("{} (externalId={}, conflictingAssetId={})", message, externalId, conflictingAssetId)
                    if (errors.size < 100) errors.add(message)
                    return@forEach
                }

                if (existing == null) {
                    installedProductRepository.save(
                        InstalledProduct(
                            asset = asset,
                            externalId = externalId,
                            crowdStrikeAid = normalize(dto.aid, 64),
                            name = name,
                            vendor = vendor,
                            version = version,
                            category = normalize(dto.category, 255),
                            installationPath = normalize(dto.installationPath, 1024),
                            installedAt = dto.installedAt,
                            lastUsedAt = dto.lastUsedAt,
                            lastUpdatedAt = dto.lastUpdatedAt,
                            importedAt = now,
                            importRunId = importRunId
                        )
                    )
                    imported++
                } else {
                    existing.asset = asset
                    existing.externalId = externalId ?: existing.externalId
                    existing.crowdStrikeAid = normalize(dto.aid, 64)
                    existing.name = name
                    existing.vendor = vendor
                    existing.version = version
                    existing.category = normalize(dto.category, 255)
                    existing.installationPath = normalize(dto.installationPath, 1024)
                    existing.installedAt = dto.installedAt
                    existing.lastUsedAt = dto.lastUsedAt
                    existing.lastUpdatedAt = dto.lastUpdatedAt
                    existing.importedAt = now
                    existing.importRunId = importRunId
                    installedProductRepository.update(existing)
                    updated++
                }
            } catch (e: Exception) {
                skipped++
                val message = "Failed to import product '${dto.name}' for '${dto.hostname}': ${e.message}"
                log.warn(message, e)
                if (errors.size < 100) errors.add(message)
            }
        }

        val unknownSamples = (unknownHosts + ambiguousHosts).take(25)
        if (unknownHosts.isNotEmpty() || ambiguousHosts.isNotEmpty()) {
            log.warn(
                "Installed products: {} rows skipped across {} unknown system(s) and {} ambiguous host(s); sample: {}",
                unknownRows, unknownHosts.size, ambiguousHosts.size, unknownSamples
            )
        }

        return InstalledProductImportResponse(
            productsProcessed = products.size,
            productsImported = imported,
            productsUpdated = updated,
            productsSkipped = skipped,
            productsDeleted = deleted,
            unknownSystems = unknownHosts.size + ambiguousHosts.size,
            dryRun = dryRun,
            errors = errors,
            unknownSystemSamples = unknownSamples
        )
    }

    /** Outcome of resolving a CrowdStrike hostname to a SECMan asset. */
    private sealed interface Resolution {
        data class Found(val asset: Asset) : Resolution
        /** No asset matches the hostname in either direction. */
        object NotFound : Resolution
        /** A short name matched multiple FQDN assets — refuse to guess. */
        object Ambiguous : Resolution
    }

    /**
     * Match a CrowdStrike hostname to an asset, in both directions, against the
     * pre-built indices:
     *   1. exact (case-insensitive) name match
     *   2. CrowdStrike FQDN → asset short name (strip the CrowdStrike domain)
     *   3. CrowdStrike short name → asset stored as FQDN (unique short name only)
     */
    private fun resolveAsset(
        hostname: String?,
        exactByName: Map<String, Asset>,
        assetsByShortName: Map<String, List<Asset>>
    ): Resolution {
        val norm = normHost(hostname) ?: return Resolution.NotFound
        exactByName[norm]?.let { return Resolution.Found(it) }

        val short = norm.substringBefore('.')
        if (short != norm) {
            exactByName[short]?.let { return Resolution.Found(it) }
        }

        val byShort = assetsByShortName[short].orEmpty()
        return when (byShort.size) {
            0 -> Resolution.NotFound
            1 -> Resolution.Found(byShort.first())
            else -> Resolution.Ambiguous
        }
    }

    /** Normalize a hostname for matching: trim, lowercase, drop a trailing dot. */
    private fun normHost(value: String?): String? = value
        ?.trim()
        ?.lowercase()
        ?.trimEnd('.')
        ?.takeIf { it.isNotBlank() }

    private fun normalize(value: String?, maxLength: Int): String? = value
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.take(maxLength)
}
