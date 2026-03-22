package com.secman.service

import com.secman.domain.AssetComplianceHistory
import com.secman.domain.ComplianceStatus
import com.secman.dto.AssetComplianceHistoryDto
import com.secman.dto.AssetComplianceOverviewDto
import com.secman.dto.AssetComplianceSummaryDto
import com.secman.repository.AssetComplianceHistoryRepository
import com.secman.repository.AssetRepository
import io.micronaut.data.model.Pageable
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Service for tracking asset vulnerability compliance status changes.
 *
 * Core principle: Only store status TRANSITIONS, not every import.
 * If an asset stays COMPLIANT across 27 imports, only 1 record exists.
 *
 * Feature: ec2-vulnerability-tracking
 */
@Singleton
open class AssetComplianceTrackingService(
    private val complianceHistoryRepository: AssetComplianceHistoryRepository,
    private val vulnerabilityConfigService: VulnerabilityConfigService,
    private val assetRepository: AssetRepository,
    private val entityManager: EntityManager
) {
    private val log = LoggerFactory.getLogger(AssetComplianceTrackingService::class.java)

    /**
     * Track compliance status after a vulnerability import for a single asset.
     * Called within the import transaction — only inserts a record if status changed.
     *
     * @param assetId The asset that was just imported
     * @param source Import source (e.g., "CROWDSTRIKE_IMPORT", "EXCEL_IMPORT")
     */
    open fun trackComplianceAfterImport(assetId: Long, source: String) {
        try {
            val thresholdDays = vulnerabilityConfigService.getReminderOneDays()
            val thresholdDate = LocalDateTime.now().minusDays(thresholdDays.toLong())

            // Count overdue vulnerabilities for this asset using a lightweight query
            val overdueResult = entityManager.createQuery(
                """
                SELECT COUNT(v), MIN(v.scanTimestamp)
                FROM Vulnerability v
                WHERE v.asset.id = :assetId AND v.scanTimestamp < :thresholdDate
                """.trimIndent()
            )
                .setParameter("assetId", assetId)
                .setParameter("thresholdDate", thresholdDate)
                .singleResult as Array<*>

            val overdueCount = (overdueResult[0] as Long).toInt()
            val oldestScanTimestamp = overdueResult[1] as? LocalDateTime
            val oldestVulnDays = oldestScanTimestamp?.let {
                ChronoUnit.DAYS.between(it, LocalDateTime.now()).toInt()
            }

            val currentStatus = if (overdueCount == 0) ComplianceStatus.COMPLIANT else ComplianceStatus.NON_COMPLIANT

            // Get the latest stored status for this asset
            val latestRecords = complianceHistoryRepository.findLatestByAssetId(assetId, Pageable.from(0, 1))
            val previousStatus = latestRecords.firstOrNull()?.status

            // Only insert if status changed or no previous record exists
            if (previousStatus == null || previousStatus != currentStatus) {
                val record = AssetComplianceHistory(
                    assetId = assetId,
                    status = currentStatus,
                    changedAt = LocalDateTime.now(),
                    overdueCount = overdueCount,
                    oldestVulnDays = oldestVulnDays,
                    source = source
                )
                complianceHistoryRepository.save(record)

                if (previousStatus == null) {
                    log.debug("Initial compliance status for asset {}: {} (overdue={})", assetId, currentStatus, overdueCount)
                } else {
                    log.info("Compliance status changed for asset {}: {} -> {} (overdue={})", assetId, previousStatus, currentStatus, overdueCount)
                }
            }
        } catch (e: Exception) {
            log.error("Failed to track compliance status for asset {}: {}", assetId, e.message)
        }
    }

    /**
     * Recalculate compliance status for all assets that don't have history yet.
     * Used for initial seeding after deployment.
     */
    @Transactional
    open fun recalculateAllStatuses(source: String): Int {
        log.info("Starting bulk compliance status recalculation")
        val existingAssetIds = complianceHistoryRepository.findDistinctAssetIds().toSet()
        val allAssetIds = assetRepository.findAllIds()
        val newAssetIds = allAssetIds.filter { it !in existingAssetIds }

        log.info("Found {} assets without compliance history (out of {} total)", newAssetIds.size, allAssetIds.size)

        var tracked = 0
        newAssetIds.chunked(1000).forEach { chunk ->
            chunk.forEach { assetId ->
                trackComplianceAfterImport(assetId, source)
                tracked++
            }
            entityManager.flush()
            entityManager.clear()
            log.debug("Recalculated compliance for {}/{} assets", tracked, newAssetIds.size)
        }

        log.info("Bulk compliance recalculation complete: {} assets processed", tracked)
        return tracked
    }

    /**
     * Get paginated overview of latest compliance status per asset.
     */
    open fun getOverview(
        searchTerm: String?,
        statusFilter: String?,
        page: Int,
        size: Int
    ): Map<String, Any> {
        val effectiveSearch = searchTerm?.takeIf { it.isNotBlank() }
        val effectiveStatus = statusFilter?.takeIf { it.isNotBlank() }

        val total = complianceHistoryRepository.countLatestStatusOverview(effectiveSearch, effectiveStatus)
        val results = complianceHistoryRepository.findLatestStatusOverview(
            effectiveSearch, effectiveStatus, size, page * size
        )

        val content = results.mapNotNull { row ->
            try {
                val cols = when (row) {
                    is Array<*> -> row
                    else -> {
                        log.warn("Unexpected row type in compliance overview: {}", row?.javaClass?.name)
                        return@mapNotNull null
                    }
                }
                AssetComplianceOverviewDto(
                    assetId = (cols[1] as Number).toLong(),
                    assetName = cols[7] as String,
                    assetType = cols[9] as? String,
                    cloudInstanceId = cols[8] as? String,
                    currentStatus = ComplianceStatus.valueOf(cols[2] as String),
                    lastChangeAt = cols[3] as LocalDateTime,
                    overdueCount = (cols[4] as Number).toInt(),
                    oldestVulnDays = (cols[5] as? Number)?.toInt(),
                    source = cols[6] as String
                )
            } catch (e: Exception) {
                log.error("Failed to map compliance overview row: {}", e.message)
                null
            }
        }

        return mapOf(
            "content" to content,
            "totalElements" to total,
            "totalPages" to ((total + size - 1) / size),
            "size" to size,
            "number" to page
        )
    }

    /**
     * Get compliance history timeline for a single asset.
     */
    open fun getAssetHistory(assetId: Long): List<AssetComplianceHistoryDto> {
        val records = complianceHistoryRepository.findByAssetIdOrderByChangedAtDesc(assetId)
        val now = LocalDateTime.now()

        return records.mapIndexed { index, record ->
            // Duration = time until next change (or until now for the latest record)
            val endTime = if (index == 0) now else records[index - 1].changedAt
            val durationDays = ChronoUnit.DAYS.between(record.changedAt, endTime).toInt()

            AssetComplianceHistoryDto(
                id = record.id!!,
                status = record.status,
                changedAt = record.changedAt,
                overdueCount = record.overdueCount,
                oldestVulnDays = record.oldestVulnDays,
                source = record.source,
                durationDays = durationDays
            )
        }
    }

    /**
     * Get summary counts for dashboard cards.
     */
    open fun getSummary(): AssetComplianceSummaryDto {
        val compliantCount = complianceHistoryRepository.countByLatestStatus(ComplianceStatus.COMPLIANT)
        val nonCompliantCount = complianceHistoryRepository.countByLatestStatus(ComplianceStatus.NON_COMPLIANT)
        val trackedAssets = compliantCount + nonCompliantCount
        val totalAssets = assetRepository.count()
        val neverAssessed = totalAssets - trackedAssets
        val compliancePercentage = if (trackedAssets > 0) {
            (compliantCount.toDouble() / trackedAssets * 100).let { Math.round(it * 10) / 10.0 }
        } else 0.0

        return AssetComplianceSummaryDto(
            totalAssets = totalAssets,
            compliantCount = compliantCount,
            nonCompliantCount = nonCompliantCount,
            neverAssessedCount = neverAssessed,
            compliancePercentage = compliancePercentage
        )
    }
}
