package com.secman.service

import com.secman.dto.CrowdStrikeAssetCleanupCandidateDto
import com.secman.dto.CrowdStrikeAssetCleanupErrorDto
import com.secman.dto.CrowdStrikeAssetCleanupResponse
import com.secman.repository.AssetRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Singleton
open class CrowdStrikeAssetCleanupService(
    private val assetRepository: AssetRepository,
    private val assetCascadeDeleteService: AssetCascadeDeleteService
) {
    private val log = LoggerFactory.getLogger(CrowdStrikeAssetCleanupService::class.java)
    private var clock: Clock = Clock.systemDefaultZone()

    constructor(
        assetRepository: AssetRepository,
        assetCascadeDeleteService: AssetCascadeDeleteService,
        clock: Clock
    ) : this(assetRepository, assetCascadeDeleteService) {
        this.clock = clock
    }

    fun cleanup(days: Int, dryRun: Boolean, username: String): CrowdStrikeAssetCleanupResponse {
        require(days > 0) { "Days must be greater than zero" }

        val cutoff = LocalDateTime.now(clock).minusDays(days.toLong())
        val candidates = assetRepository.findByCrowdStrikeLastImportedAtBefore(cutoff)
            .mapNotNull { asset ->
                val importedAt = asset.crowdStrikeLastImportedAt ?: return@mapNotNull null
                val assetId = asset.id ?: return@mapNotNull null
                CrowdStrikeAssetCleanupCandidateDto(
                    assetId = assetId,
                    name = asset.name,
                    crowdStrikeLastImportedAt = importedAt
                )
            }
            .sortedBy { it.name.lowercase() }

        if (dryRun) {
            return CrowdStrikeAssetCleanupResponse(
                days = days,
                cutoff = cutoff,
                dryRun = true,
                candidateCount = candidates.size,
                deletedCount = 0,
                skippedCount = candidates.size,
                candidates = candidates,
                errors = emptyList()
            )
        }

        val operationId = UUID.randomUUID().toString()
        val errors = mutableListOf<CrowdStrikeAssetCleanupErrorDto>()
        var deletedCount = 0

        candidates.forEach { candidate ->
            try {
                assetCascadeDeleteService.deleteAsset(
                    assetId = candidate.assetId,
                    username = username,
                    forceTimeout = true,
                    bulkOperationId = operationId
                )
                deletedCount++
            } catch (e: Exception) {
                log.warn("Failed to delete CrowdStrike-stale asset {} ({})", candidate.assetId, candidate.name, e)
                errors.add(
                    CrowdStrikeAssetCleanupErrorDto(
                        assetId = candidate.assetId,
                        assetName = candidate.name,
                        message = e.message ?: e.javaClass.simpleName
                    )
                )
            }
        }

        return CrowdStrikeAssetCleanupResponse(
            days = days,
            cutoff = cutoff,
            dryRun = false,
            candidateCount = candidates.size,
            deletedCount = deletedCount,
            skippedCount = errors.size,
            candidates = candidates,
            errors = errors
        )
    }
}
