package com.secman.scheduler

import com.secman.dto.CrowdStrikeAssetCleanupResponse
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
        try {
            val response = runWithScheduledPolicy("scheduler")
            logger.info(
                "CrowdStrike scheduled cleanup finished: status={} candidates={} deleted={} errors={} runId={}",
                response.status, response.candidateCount, response.deletedCount,
                response.errors.size, response.runId
            )
        } catch (e: CleanupDisabledException) {
            logger.debug("CrowdStrike scheduled cleanup is disabled (secman.crowdstrike.cleanup.enabled=false)")
        } catch (e: CleanupMisconfiguredException) {
            logger.warn("CrowdStrike scheduled cleanup misconfigured: stale-days={} (must be > 0). Skipping run.", staleDays)
        } catch (e: Exception) {
            logger.error("CrowdStrike scheduled cleanup threw an unhandled exception", e)
        }
    }

    /**
     * Run exactly what the 02:30 cron runs: the configured stale-day threshold, the
     * configured include-legacy default (never overridden), and the safety brake.
     *
     * Shared by the cron wrapper above and the manual ADMIN trigger
     * (POST /api/crowdstrike/cleanup/run-now) so the two can never drift. In
     * particular `maxDeletePercent` MUST stay non-null here — that is the brake that
     * aborts a run which would delete more than the configured share of tracked
     * assets, and it is the one thing the brake-free manual endpoint
     * (POST /api/assets/delete-not-seen-by-crowdstrike) deliberately skips.
     *
     * @param triggeredBy short identifier persisted on the audit row ("scheduler",
     *                    "manual:<username>", ...)
     * @throws CleanupDisabledException if the feature is disabled
     * @throws CleanupMisconfiguredException if stale-days is misconfigured
     */
    open fun runWithScheduledPolicy(triggeredBy: String): CrowdStrikeAssetCleanupResponse {
        if (!enabled) {
            throw CleanupDisabledException("CrowdStrike cleanup is disabled (secman.crowdstrike.cleanup.enabled=false)")
        }
        if (staleDays <= 0) {
            throw CleanupMisconfiguredException("CrowdStrike cleanup misconfigured: stale-days=$staleDays (must be > 0)")
        }

        logger.info(
            "Running CrowdStrike scheduled cleanup: staleDays={} maxDeletePercent={} triggeredBy={}",
            staleDays, maxDeletePercent, triggeredBy
        )

        return auditService.run(
            days = staleDays,
            dryRun = false,
            triggeredBy = triggeredBy,
            maxDeletePercent = maxDeletePercent
            // includeLegacy intentionally omitted -> falls back to the configured
            // default, exactly as the scheduler has always done.
        )
    }
}

/**
 * The cleanup feature is switched off for this deployment.
 *
 * A dedicated type rather than a bare IllegalStateException so the cron wrapper can
 * demote *this* to debug without also swallowing an unrelated IllegalStateException
 * thrown from deep inside the run, which must stay an ERROR.
 */
class CleanupDisabledException(message: String) : IllegalStateException(message)

/** `stale-days` is not a usable threshold. See [CleanupDisabledException] for why this is its own type. */
class CleanupMisconfiguredException(message: String) : IllegalArgumentException(message)
