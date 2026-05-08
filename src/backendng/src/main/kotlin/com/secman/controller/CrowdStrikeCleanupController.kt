package com.secman.controller

import com.secman.domain.CrowdStrikeCleanupRun
import com.secman.repository.CrowdStrikeCleanupRunRepository
import io.micronaut.context.annotation.Value
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.QueryValue
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.serde.annotation.Serdeable
import org.slf4j.LoggerFactory

/**
 * Read-only views into the CrowdStrike stale-asset cleanup history and config.
 *
 * Backs the admin "Stale Asset Cleanup" panel on the Falcon config page.
 * The cleanup itself is fired via POST /api/assets/delete-not-seen-by-crowdstrike
 * (manual) or by the daily scheduler (CrowdStrikeStaleAssetCleanupScheduler).
 */
@Controller("/api/crowdstrike/cleanup")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class CrowdStrikeCleanupController(
    private val runRepository: CrowdStrikeCleanupRunRepository,
    @Value("\${secman.crowdstrike.cleanup.enabled:false}") private val enabled: Boolean,
    @Value("\${secman.crowdstrike.cleanup.stale-days:30}") private val staleDays: Int,
    @Value("\${secman.crowdstrike.cleanup.max-delete-percent:10}") private val maxDeletePercent: Int
) {
    private val log = LoggerFactory.getLogger(CrowdStrikeCleanupController::class.java)

    @Serdeable
    data class CleanupConfigDto(
        val enabled: Boolean,
        val staleDays: Int,
        val maxDeletePercent: Int,
        val cron: String
    )

    @Get("/config")
    open fun getConfig(): HttpResponse<CleanupConfigDto> {
        return HttpResponse.ok(
            CleanupConfigDto(
                enabled = enabled,
                staleDays = staleDays,
                maxDeletePercent = maxDeletePercent,
                cron = "0 30 2 * * ?"
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
}
