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
    open fun importProducts(products: List<InstalledProductDto>, dryRun: Boolean): InstalledProductImportResponse {
        require(products.size <= MAX_PRODUCTS_PER_REQUEST) {
            "At most $MAX_PRODUCTS_PER_REQUEST products can be imported per request"
        }

        var imported = 0
        var updated = 0
        var skipped = 0
        var unknownSystems = 0
        val errors = mutableListOf<String>()
        val now = LocalDateTime.now()

        products.forEach { dto ->
            try {
                val hostname = normalize(dto.hostname, 255)
                val name = normalize(dto.name, 512)
                if (name == null) {
                    skipped++
                    errors.add("Skipped blank product name for ${hostname ?: "unknown host"}")
                    return@forEach
                }

                val asset = resolveAsset(hostname)
                if (asset == null) {
                    unknownSystems++
                    skipped++
                    return@forEach
                }
                if (dryRun) {
                    imported++
                    return@forEach
                }

                val assetId = requireNotNull(asset.id)
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
                            importedAt = now
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

        return InstalledProductImportResponse(
            productsProcessed = products.size,
            productsImported = imported,
            productsUpdated = updated,
            productsSkipped = skipped,
            unknownSystems = unknownSystems,
            dryRun = dryRun,
            errors = errors
        )
    }

    private fun resolveAsset(hostname: String?): Asset? {
        if (hostname.isNullOrBlank()) return null
        return assetRepository.findByNameIgnoreCase(hostname)
            ?: hostname.substringBefore('.').takeIf { it.isNotBlank() && it != hostname }?.let { assetRepository.findByNameIgnoreCase(it) }
    }

    private fun normalize(value: String?, maxLength: Int): String? = value
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.take(maxLength)
}
