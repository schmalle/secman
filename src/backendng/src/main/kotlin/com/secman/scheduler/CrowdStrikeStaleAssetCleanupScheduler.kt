package com.secman.scheduler

import com.secman.service.CrowdStrikeCleanupAuditService
import io.micronaut.context.annotation.Value
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Scheduled cleanup of CrowdStrike-tracked assets that have not been re-imported
 * for `staleDays` days. Off by default; flip `secman.crowdstrike.cleanup.enabled=true`
 * (or env `CROWDSTRIKE_CLEANUP_ENABLED=true`) to opt in per environment.
 *
 * The orchestration (safety brake, audit row, admin notification) lives in
 * CrowdStrikeCleanupAuditService — this class only owns the cron timing and
 * the configuration plumbing.
 */
@Singleton
open class CrowdStrikeStaleAssetCleanupScheduler(
    @Inject private val auditService: CrowdStrikeCleanupAuditService,
    @Value("\${secman.crowdstrike.cleanup.enabled:false}") private val enabled: Boolean,
    @Value("\${secman.crowdstrike.cleanup.stale-days:30}") private val staleDays: Int,
    @Value("\${secman.crowdstrike.cleanup.max-delete-percent:10}") private val maxDeletePercent: Int
) {
    private val logger = LoggerFactory.getLogger(CrowdStrikeStaleAssetCleanupScheduler::class.java)

    /**
     * Daily at 02:30 — after typical CrowdStrike imports settle and before
     * business hours, so a notification reaches admins by morning.
     */
    @Scheduled(cron = "0 30 2 * * ?")
    open fun runScheduledCleanup() {
        if (!enabled) {
            logger.debug("CrowdStrike scheduled cleanup is disabled (secman.crowdstrike.cleanup.enabled=false)")
            return
        }
        if (staleDays <= 0) {
            logger.warn("CrowdStrike scheduled cleanup misconfigured: stale-days={} (must be > 0). Skipping run.", staleDays)
            return
        }

        logger.info(
            "Running CrowdStrike scheduled cleanup: staleDays={} maxDeletePercent={}",
            staleDays, maxDeletePercent
        )

        try {
            val response = auditService.run(
                days = staleDays,
                dryRun = false,
                triggeredBy = "scheduler",
                maxDeletePercent = maxDeletePercent
            )
            logger.info(
                "CrowdStrike scheduled cleanup finished: status={} candidates={} deleted={} errors={} runId={}",
                response.status, response.candidateCount, response.deletedCount,
                response.errors.size, response.runId
            )
        } catch (e: Exception) {
            logger.error("CrowdStrike scheduled cleanup threw an unhandled exception", e)
        }
    }
}
