package com.secman.service

import com.secman.domain.CrowdStrikeCleanupRun
import com.secman.domain.CrowdStrikeCleanupStatus
import com.secman.dto.CrowdStrikeAssetCleanupErrorDto
import com.secman.dto.CrowdStrikeAssetCleanupResponse
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeCleanupRunRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

/**
 * Orchestrates CrowdStrike stale-asset cleanup runs:
 * - applies the optional safety brake (max-delete percent)
 * - persists a CrowdStrikeCleanupRun audit row for non-dry-run executions
 * - notifies admins when the run produced deletions, errors, or was aborted
 *
 * Manual API runs and the scheduled job both go through this service so the
 * history view shows every actual deletion regardless of trigger.
 */
@Singleton
open class CrowdStrikeCleanupAuditService(
    @Inject private val cleanupService: CrowdStrikeAssetCleanupService,
    @Inject private val runRepository: CrowdStrikeCleanupRunRepository,
    @Inject private val assetRepository: AssetRepository,
    @Inject private val notificationService: CrowdStrikeCleanupNotificationService
) {
    private val logger = LoggerFactory.getLogger(CrowdStrikeCleanupAuditService::class.java)
    private var clock: Clock = Clock.systemDefaultZone()

    constructor(
        cleanupService: CrowdStrikeAssetCleanupService,
        runRepository: CrowdStrikeCleanupRunRepository,
        assetRepository: AssetRepository,
        notificationService: CrowdStrikeCleanupNotificationService,
        clock: Clock
    ) : this(cleanupService, runRepository, assetRepository, notificationService) {
        this.clock = clock
    }

    /**
     * Execute a cleanup run.
     *
     * @param days stale threshold in days (must be > 0)
     * @param dryRun if true, no deletes occur and no audit row is written
     * @param triggeredBy short identifier of the caller (username, "scheduler", ...)
     * @param maxDeletePercent if set (0..100), aborts the run when the candidate
     *                         set exceeds this percentage of CrowdStrike-tracked
     *                         assets. Manual runs typically pass null.
     */
    fun run(
        days: Int,
        dryRun: Boolean,
        triggeredBy: String,
        maxDeletePercent: Int? = null
    ): CrowdStrikeAssetCleanupResponse {
        require(days > 0) { "Days must be greater than zero" }

        if (dryRun) {
            return cleanupService.cleanup(days, dryRun = true, username = triggeredBy)
        }

        val startedAt = LocalDateTime.now(clock)

        if (maxDeletePercent != null) {
            val brakeOutcome = checkSafetyBrake(days, triggeredBy, startedAt, maxDeletePercent)
            if (brakeOutcome != null) return brakeOutcome
        }

        val response = try {
            cleanupService.cleanup(days, dryRun = false, username = triggeredBy)
        } catch (e: Exception) {
            logger.error("CrowdStrike cleanup run failed (triggeredBy={})", triggeredBy, e)
            val failed = persistRun(
                status = CrowdStrikeCleanupStatus.FAILED,
                triggeredBy = triggeredBy,
                staleDays = days,
                cutoff = LocalDateTime.now(clock).minusDays(days.toLong()),
                candidateCount = 0,
                deletedCount = 0,
                errorCount = 1,
                totalTracked = safeTotal(),
                startedAt = startedAt,
                errorMessage = e.message?.take(1000) ?: e.javaClass.simpleName
            )
            notificationService.notifyAdmins(failed)
            return CrowdStrikeAssetCleanupResponse(
                days = days,
                cutoff = failed.cutoff,
                dryRun = false,
                candidateCount = 0,
                deletedCount = 0,
                skippedCount = 0,
                candidates = emptyList(),
                errors = listOf(
                    CrowdStrikeAssetCleanupErrorDto(
                        assetId = 0L,
                        assetName = "(run-level error)",
                        message = failed.errorMessage ?: "Unknown error"
                    )
                ),
                status = CrowdStrikeCleanupStatus.FAILED.name,
                runId = failed.id
            )
        }

        val status = when {
            response.errors.isNotEmpty() -> CrowdStrikeCleanupStatus.PARTIAL
            else -> CrowdStrikeCleanupStatus.SUCCESS
        }

        val saved = persistRun(
            status = status,
            triggeredBy = triggeredBy,
            staleDays = days,
            cutoff = response.cutoff,
            candidateCount = response.candidateCount,
            deletedCount = response.deletedCount,
            errorCount = response.errors.size,
            totalTracked = safeTotal(),
            startedAt = startedAt,
            errorMessage = null
        )

        if (response.deletedCount > 0 || response.errors.isNotEmpty()) {
            notificationService.notifyAdmins(saved)
        }

        return response.copy(status = status.name, runId = saved.id)
    }

    private fun checkSafetyBrake(
        days: Int,
        triggeredBy: String,
        startedAt: LocalDateTime,
        maxDeletePercent: Int
    ): CrowdStrikeAssetCleanupResponse? {
        if (maxDeletePercent >= 100) return null

        val cutoff = LocalDateTime.now(clock).minusDays(days.toLong())
        val candidates = assetRepository.findByCrowdStrikeLastImportedAtBefore(cutoff)
            .count { it.crowdStrikeLastImportedAt != null && it.id != null }
        val totalTracked = safeTotal()
        if (totalTracked <= 0L) return null

        val percent = (candidates.toDouble() / totalTracked.toDouble()) * 100.0
        if (percent <= maxDeletePercent.toDouble()) return null

        logger.warn(
            "CrowdStrike cleanup safety brake tripped: {} of {} tracked assets ({}%) exceeds max {}%",
            candidates, totalTracked, "%.2f".format(percent), maxDeletePercent
        )

        val saved = persistRun(
            status = CrowdStrikeCleanupStatus.ABORTED_SAFETY_BRAKE,
            triggeredBy = triggeredBy,
            staleDays = days,
            cutoff = cutoff,
            candidateCount = candidates,
            deletedCount = 0,
            errorCount = 0,
            totalTracked = totalTracked,
            startedAt = startedAt,
            errorMessage = "Safety brake: ${"%.2f".format(percent)}% of CrowdStrike-tracked assets " +
                "would be deleted (limit ${maxDeletePercent}%). Investigate before re-running."
        )
        notificationService.notifyAdmins(saved)

        return CrowdStrikeAssetCleanupResponse(
            days = days,
            cutoff = cutoff,
            dryRun = false,
            candidateCount = candidates,
            deletedCount = 0,
            skippedCount = candidates,
            candidates = emptyList(),
            errors = listOf(
                CrowdStrikeAssetCleanupErrorDto(
                    assetId = 0L,
                    assetName = "(safety-brake)",
                    message = saved.errorMessage ?: "Safety brake aborted run"
                )
            ),
            status = CrowdStrikeCleanupStatus.ABORTED_SAFETY_BRAKE.name,
            runId = saved.id
        )
    }

    private fun persistRun(
        status: CrowdStrikeCleanupStatus,
        triggeredBy: String,
        staleDays: Int,
        cutoff: LocalDateTime,
        candidateCount: Int,
        deletedCount: Int,
        errorCount: Int,
        totalTracked: Long,
        startedAt: LocalDateTime,
        errorMessage: String?
    ): CrowdStrikeCleanupRun {
        val completedAt = LocalDateTime.now(clock)
        val run = CrowdStrikeCleanupRun(
            status = status,
            triggeredBy = triggeredBy.take(100),
            staleDays = staleDays,
            cutoff = cutoff,
            candidateCount = candidateCount,
            deletedCount = deletedCount,
            errorCount = errorCount,
            totalCrowdStrikeTracked = totalTracked,
            startedAt = startedAt,
            completedAt = completedAt,
            durationMs = Duration.between(startedAt, completedAt).toMillis(),
            errorMessage = errorMessage
        )
        return runRepository.save(run)
    }

    private fun safeTotal(): Long = try {
        assetRepository.countCrowdStrikeTracked()
    } catch (e: Exception) {
        logger.warn("Failed to count CrowdStrike-tracked assets: {}", e.message)
        0L
    }
}
