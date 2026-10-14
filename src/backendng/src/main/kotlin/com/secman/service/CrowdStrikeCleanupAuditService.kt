package com.secman.service

import com.secman.domain.CrowdStrikeCleanupRun
import com.secman.domain.CrowdStrikeCleanupStatus
import com.secman.dto.CrowdStrikeAssetCleanupErrorDto
import com.secman.dto.CrowdStrikeAssetCleanupResponse
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeCleanupRunRepository
import io.micronaut.context.annotation.Value
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

/** Manual and scheduled cleanup share selection, audit history and deletion safeguards. */
@Singleton
open class CrowdStrikeCleanupAuditService @Inject constructor(
    private val cleanupService: CrowdStrikeAssetCleanupService,
    private val runRepository: CrowdStrikeCleanupRunRepository,
    private val assetRepository: AssetRepository,
    private val notificationService: CrowdStrikeCleanupNotificationService,
    @Value("\${secman.crowdstrike.cleanup.include-legacy:false}") private val includeLegacyDefault: Boolean,
    private val importLease: CrowdStrikeImportRunLease = CrowdStrikeImportRunLease()
) {
    private val logger = LoggerFactory.getLogger(CrowdStrikeCleanupAuditService::class.java)
    private var clock: Clock = Clock.systemDefaultZone()

    constructor(cleanupService: CrowdStrikeAssetCleanupService, runRepository: CrowdStrikeCleanupRunRepository,
                assetRepository: AssetRepository, notificationService: CrowdStrikeCleanupNotificationService,
                clock: Clock, includeLegacyDefault: Boolean = false)
        : this(cleanupService, runRepository, assetRepository, notificationService, includeLegacyDefault) {
        this.clock = clock
    }

    fun run(days: Int, dryRun: Boolean, triggeredBy: String, maxDeletePercent: Int? = null,
            includeLegacy: Boolean? = null,
            basis: CrowdStrikeCleanupBasis = CrowdStrikeCleanupBasis.IMPORT_INACTIVITY): CrowdStrikeAssetCleanupResponse {
        require(days in 1..36500) { "Days must be between 1 and 36500" }
        require(maxDeletePercent == null || maxDeletePercent in 0..100) { "maxDeletePercent must be between 0 and 100" }
        val actor = triggeredBy.replace('\r', '_').replace('\n', '_').take(100)
        val legacy = includeLegacy ?: includeLegacyDefault
        val started = LocalDateTime.now(clock)
        val cutoff = started.minusDays(days.toLong())
        val selection = try {
            cleanupService.cleanup(days, true, actor, legacy, cutoff, basis)
        } catch (e: Exception) {
            logger.error("CrowdStrike cleanup actor={} basis={} outcome=selection-failed", actor, basis.value, e)
            CrowdStrikeAssetCleanupResponse(days, cutoff, dryRun, 0, 0, 0, emptyList(),
                listOf(CrowdStrikeAssetCleanupErrorDto(0, "(selection error)", "Selection failed; see backend log for details")),
                status = CrowdStrikeCleanupStatus.FAILED.name, basis = basis.value,
                cutoffUtc = cutoff.atZone(clock.zone).toInstant())
        }
        if (dryRun) return selection.copy(status = if (selection.errors.isEmpty()) "DRY_RUN" else "FAILED")

        // The same paged population supplies the brake and the deletion candidates.
        val totalTracked = selection.candidateCount.toLong() + selection.exclusions
            .filterKeys { it != "PROTECTED_INVENTORY" && it != "LEGACY_DISABLED" }.values.sum()
        val permit = importLease.tryBeginCleanup()
        try {
            var abortStatus: CrowdStrikeCleanupStatus? = null
            val blocked = if (maxDeletePercent != null && basis == CrowdStrikeCleanupBasis.IMPORT_INACTIVITY)
                importLease.cleanupBlockedReason() else null
            val abortMessage = when {
                !permit -> {
                    abortStatus = CrowdStrikeCleanupStatus.ABORTED_IMPORT_HEALTH
                    "A CrowdStrike import or cleanup is active; retry after it completes"
                }
                blocked != null -> { abortStatus = CrowdStrikeCleanupStatus.ABORTED_IMPORT_HEALTH; blocked }
                maxDeletePercent != null && maxDeletePercent < 100 && selection.candidateCount > 0 &&
                    (totalTracked <= 0 || selection.candidateCount.toDouble() * 100 / totalTracked > maxDeletePercent) -> {
                    abortStatus = CrowdStrikeCleanupStatus.ABORTED_SAFETY_BRAKE
                    "Safety brake: candidate percentage exceeds the configured limit of $maxDeletePercent%"
                }
                else -> null
            }
            val response = if (selection.errors.isNotEmpty()) selection.copy(dryRun = false)
                else if (abortMessage != null) selection.copy(dryRun = false,
                errors = listOf(CrowdStrikeAssetCleanupErrorDto(0, "(run-level safeguard)", abortMessage)),
                status = abortStatus!!.name) else try {
                cleanupService.cleanup(days, false, actor, legacy, cutoff, basis, selection)
            } catch (e: Exception) {
                logger.error("CrowdStrike cleanup actor={} basis={} outcome=failed", actor, basis.value, e)
                selection.copy(dryRun = false, status = CrowdStrikeCleanupStatus.FAILED.name,
                    errors = listOf(CrowdStrikeAssetCleanupErrorDto(0, "(run-level error)",
                        "Cleanup failed; see backend log for details")))
            }
            val status = response.status?.let(CrowdStrikeCleanupStatus::valueOf)
                ?: if (response.errors.isNotEmpty()) CrowdStrikeCleanupStatus.PARTIAL else CrowdStrikeCleanupStatus.SUCCESS
            val completed = LocalDateTime.now(clock)
            val saved = runRepository.save(CrowdStrikeCleanupRun(status = status, triggeredBy = actor,
                staleDays = days, cutoff = cutoff, candidateCount = response.candidateCount,
                deletedCount = response.deletedCount, errorCount = response.errors.size,
                legacyCandidateCount = response.legacyCandidateCount, legacyDeletedCount = response.legacyDeletedCount,
                totalCrowdStrikeTracked = totalTracked, startedAt = started, completedAt = completed,
                durationMs = Duration.between(started, completed).toMillis(), errorMessage = abortMessage ?: response.errors.firstOrNull()?.message,
                basis = basis.value, cutoffUtc = response.cutoffUtc, skippedCount = response.skippedCount))
            logger.info("CrowdStrike cleanup actor={} runId={} basis={} outcome={} deleted={} skipped={}",
                actor, saved.id, basis.value, status, response.deletedCount, response.skippedCount)
            if (response.deletedCount > 0 || response.errors.isNotEmpty()) notificationService.notifyAdmins(saved)
            return response.copy(status = status.name, runId = saved.id)
        } finally { if (permit) importLease.finishCleanup() }
    }
}
