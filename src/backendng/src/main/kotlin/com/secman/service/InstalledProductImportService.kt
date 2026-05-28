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

    @Transactional
    open fun importProducts(products: List<InstalledProductDto>, dryRun: Boolean): InstalledProductImportResponse {
        var imported = 0
        var updated = 0
        var skipped = 0
        var unknownSystems = 0
        val errors = mutableListOf<String>()
        val now = LocalDateTime.now()

        products.forEach { dto ->
            try {
                val asset = resolveAsset(dto.hostname)
                if (asset == null) {
                    unknownSystems++
                    skipped++
                    return@forEach
                }
                if (dto.name.isBlank()) {
                    skipped++
                    errors.add("Skipped blank product name for ${dto.hostname}")
                    return@forEach
                }
                if (dryRun) {
                    imported++
                    return@forEach
                }

                val assetId = requireNotNull(asset.id)
                val existing = dto.externalId?.takeIf { it.isNotBlank() }?.let { installedProductRepository.findByExternalId(it) }
                    ?: installedProductRepository.findLogicalDuplicate(assetId, dto.name, dto.vendor, dto.version)

                if (existing == null) {
                    installedProductRepository.save(
                        InstalledProduct(
                            asset = asset,
                            externalId = dto.externalId?.take(255),
                            crowdStrikeAid = dto.aid?.take(64),
                            name = dto.name.take(512),
                            vendor = dto.vendor?.take(255),
                            version = dto.version?.take(255),
                            category = dto.category?.take(255),
                            installationPath = dto.installationPath?.take(1024),
                            installedAt = dto.installedAt,
                            lastUsedAt = dto.lastUsedAt,
                            lastUpdatedAt = dto.lastUpdatedAt,
                            importedAt = now
                        )
                    )
                    imported++
                } else {
                    existing.asset = asset
                    existing.externalId = dto.externalId?.take(255) ?: existing.externalId
                    existing.crowdStrikeAid = dto.aid?.take(64)
                    existing.name = dto.name.take(512)
                    existing.vendor = dto.vendor?.take(255)
                    existing.version = dto.version?.take(255)
                    existing.category = dto.category?.take(255)
                    existing.installationPath = dto.installationPath?.take(1024)
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

    private fun resolveAsset(hostname: String): Asset? {
        val trimmed = hostname.trim()
        if (trimmed.isBlank()) return null
        return assetRepository.findByNameIgnoreCase(trimmed)
            ?: trimmed.substringBefore('.').takeIf { it.isNotBlank() && it != trimmed }?.let { assetRepository.findByNameIgnoreCase(it) }
    }
}
