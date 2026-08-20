package com.secman.controller

import com.secman.domain.CrowdStrikeCleanupRun
import com.secman.repository.CrowdStrikeCleanupRunRepository
import com.secman.scheduler.CleanupDisabledException
import com.secman.scheduler.CleanupMisconfiguredException
import com.secman.scheduler.CrowdStrikeStaleAssetCleanupScheduler
import io.micronaut.context.annotation.Value
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.serde.annotation.Serdeable
import org.slf4j.LoggerFactory

/**
 * Read-only views into the CrowdStrike stale-asset cleanup history and config.
 *
 * Backs the admin "Stale Asset Cleanup" panel on the Falcon config page.
 * The cleanup itself is fired via POST /api/assets/delete-not-seen-by-crowdstrike
 * (manual, ad-hoc threshold, no safety brake), by POST run-now below (manual replay
 * of the scheduled policy, brake included), or by the daily scheduler
 * (CrowdStrikeStaleAssetCleanupScheduler).
 */
@Controller("/api/crowdstrike/cleanup")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class CrowdStrikeCleanupController(
    private val runRepository: CrowdStrikeCleanupRunRepository,
    private val scheduler: CrowdStrikeStaleAssetCleanupScheduler,
    @Value("\${secman.crowdstrike.cleanup.enabled:false}") private val enabled: Boolean,
    @Value("\${secman.crowdstrike.cleanup.stale-days:30}") private val staleDays: Int,
    @Value("\${secman.crowdstrike.cleanup.max-delete-percent:10}") private val maxDeletePercent: Int,
    // Feature 087: configured default for the legacy rule (rule B). Surfaced to
    // the admin UI so the toggle initial state matches the backend (spec SC-006).
    @Value("\${secman.crowdstrike.cleanup.include-legacy:false}") private val includeLegacy: Boolean
) {
    private val log = LoggerFactory.getLogger(CrowdStrikeCleanupController::class.java)

    @Serdeable
    data class ErrorResponse(val error: String)

    @Serdeable
    data class CleanupConfigDto(
        val enabled: Boolean,
        val staleDays: Int,
        val maxDeletePercent: Int,
        val cron: String,
        // Feature 087.
        val includeLegacy: Boolean
    )

    @Get("/config")
    open fun getConfig(): HttpResponse<CleanupConfigDto> {
        return HttpResponse.ok(
            CleanupConfigDto(
                enabled = enabled,
                staleDays = staleDays,
                maxDeletePercent = maxDeletePercent,
                cron = "0 30 2 * * ?",
                includeLegacy = includeLegacy
            )
        )
    }

    @Get("/runs")
    open fun listRuns(
        @QueryValue(defaultValue = "20") limit: Int
    ): HttpResponse<List<CrowdStrikeCleanupRun>> {
        val safeLimit = limit.coerceIn(1, 200)
        val pageable = Pageable.from(0, safeLimit, Sort.of(Sort.Order.desc("startedAt")))
        return HttpResponse.ok(runRepository.findAll(pageable).content)
    }

    /**
     * Replay the 02:30 scheduled cleanup on demand.
     *
     * Delegates to CrowdStrikeStaleAssetCleanupScheduler.runWithScheduledPolicy, so this
     * applies the configured stale-days, the configured include-legacy default AND the
     * safety brake — unlike POST /api/assets/delete-not-seen-by-crowdstrike, which takes
     * an ad-hoc threshold and deliberately runs brake-free. Use this to pull tonight's
     * run forward or to verify what it will do.
     *
     * A brake trip is not an error: the run returns status ABORTED with a persisted
     * audit row and deletes nothing.
     */
    @Post("/run-now")
    open fun runNow(authentication: Authentication): HttpResponse<*> {
        return try {
            val response = scheduler.runWithScheduledPolicy("manual:${authentication.name}")
            // Destructive admin action: actor + outcome must be in the log (OWASP A09).
            log.warn(
                "Manual scheduled-policy cleanup by {}: status={} candidates={} deleted={} errors={} runId={}",
                authentication.name, response.status, response.candidateCount,
                response.deletedCount, response.errors.size, response.runId
            )
            HttpResponse.ok(response)
        } catch (e: CleanupDisabledException) {
            log.warn("Manual scheduled-policy cleanup refused for {}: feature disabled", authentication.name)
            HttpResponse.status<ErrorResponse>(HttpStatus.CONFLICT)
                .body(ErrorResponse("CrowdStrike cleanup is disabled (secman.crowdstrike.cleanup.enabled=false)"))
        } catch (e: CleanupMisconfiguredException) {
            log.warn("Manual scheduled-policy cleanup refused for {}: {}", authentication.name, e.message)
            HttpResponse.badRequest(ErrorResponse(e.message ?: "CrowdStrike cleanup is misconfigured"))
        } catch (e: Exception) {
            // Detail to the log, generic message to the client (OWASP A05).
            log.error("Manual scheduled-policy cleanup failed for {}", authentication.name, e)
            HttpResponse.serverError<ErrorResponse>()
                .body(ErrorResponse("CrowdStrike scheduled cleanup failed"))
        }
    }
}
