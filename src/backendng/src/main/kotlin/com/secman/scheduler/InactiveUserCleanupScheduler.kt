package com.secman.scheduler

import com.secman.config.UserCleanupConfig
import com.secman.dto.UserCleanupRunSummary
import com.secman.service.InactiveUserCleanupService
import com.secman.service.UserCleanupInProgressException
import com.secman.service.UserCleanupMisconfiguredException
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

class UserCleanupDisabledException : RuntimeException("Inactive user cleanup is disabled (secman.user-cleanup.enabled=false)")

/**
 * Nightly inactive-user cleanup at 03:15, after the 02:30 CrowdStrike cleanup. Only the
 * timing and the enabled flag live here; the policy is [InactiveUserCleanupService].
 */
@Singleton
open class InactiveUserCleanupScheduler(
    private val service: InactiveUserCleanupService,
    private val config: UserCleanupConfig
) {
    private val log = LoggerFactory.getLogger(InactiveUserCleanupScheduler::class.java)

    @Scheduled(cron = "0 15 3 * * ?")
    open fun runScheduledCleanup() {
        try {
            runWithScheduledPolicy("scheduler")
        } catch (e: UserCleanupDisabledException) {
            log.debug("Inactive user cleanup is disabled")
        } catch (e: UserCleanupMisconfiguredException) {
            log.warn("Inactive user cleanup skipped: {}", e.message)
        } catch (e: UserCleanupInProgressException) {
            log.info("Inactive user cleanup skipped: a run is already in progress")
        } catch (e: Exception) {
            log.error("Inactive user cleanup threw an unhandled exception", e)
        }
    }

    /** Exactly what the cron runs, including the brake; shared with the ADMIN run-now endpoint. */
    open fun runWithScheduledPolicy(triggeredBy: String): UserCleanupRunSummary {
        if (!config.enabled) throw UserCleanupDisabledException()
        return service.run(triggeredBy, config)
    }
}
