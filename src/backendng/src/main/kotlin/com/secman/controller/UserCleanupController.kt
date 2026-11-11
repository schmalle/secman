package com.secman.controller

import com.secman.config.UserCleanupConfig
import com.secman.domain.UserCleanupRun
import com.secman.domain.UserCleanupRunItem
import com.secman.dto.UserCleanupConfigDto
import com.secman.dto.UserCleanupRunSummary
import com.secman.repository.UserCleanupRunItemRepository
import com.secman.repository.UserCleanupRunRepository
import com.secman.scheduler.InactiveUserCleanupScheduler
import com.secman.scheduler.UserCleanupDisabledException
import com.secman.service.InactiveUserCleanupService
import com.secman.service.UserCleanupInProgressException
import com.secman.service.UserCleanupMisconfiguredException
import io.micronaut.data.model.Pageable
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.serde.annotation.Serdeable
import org.slf4j.LoggerFactory

/** ADMIN view and manual trigger of the inactive-user cleanup (`docs/USER_CLEANUP.md`). */
@Controller("/api/user-cleanup")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class UserCleanupController(
    private val config: UserCleanupConfig,
    private val service: InactiveUserCleanupService,
    private val scheduler: InactiveUserCleanupScheduler,
    private val runRepository: UserCleanupRunRepository,
    private val itemRepository: UserCleanupRunItemRepository
) {
    private val log = LoggerFactory.getLogger(UserCleanupController::class.java)

    @Serdeable
    data class ErrorResponse(val error: String)

    @Serdeable
    data class RunDetail(val run: UserCleanupRun, val items: List<UserCleanupRunItem>, val totalItems: Long)

    @Get("/config")
    open fun getConfig(): UserCleanupConfigDto = UserCleanupConfigDto(
        enabled = config.enabled, dryRun = config.dryRun, inactiveDays = config.inactiveDays,
        warningDays = config.warningDays, maxDeletePercent = config.maxDeletePercent,
        maxUsersPerRun = config.maxUsersPerRun, validationError = config.validate()
    )

    /** Tonight's run as a dry run with the deployed settings; nothing is written or sent. */
    @Get("/preview")
    open fun preview(
        @QueryValue(defaultValue = "0") page: Int,
        @QueryValue(defaultValue = "50") size: Int
    ): HttpResponse<*> = try {
        val summary = service.preview()
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val from = (page.coerceAtLeast(0).toLong() * safeSize).coerceAtMost(summary.items.size.toLong()).toInt()
        HttpResponse.ok(summary.copy(items = summary.items.subList(from, minOf(from + safeSize, summary.items.size))))
    } catch (e: UserCleanupMisconfiguredException) {
        HttpResponse.badRequest(ErrorResponse(e.message ?: "Inactive user cleanup is misconfigured"))
    }

    @Get("/runs")
    open fun listRuns(
        @QueryValue(defaultValue = "0") page: Int,
        @QueryValue(defaultValue = "20") size: Int
    ): List<UserCleanupRun> =
        runRepository.findAllOrderByStartedAtDesc(Pageable.from(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE))).content

    @Get("/runs/{id}")
    open fun getRun(
        @PathVariable id: Long,
        @QueryValue(defaultValue = "0") page: Int,
        @QueryValue(defaultValue = "50") size: Int
    ): HttpResponse<RunDetail> {
        val run = runRepository.findById(id).orElse(null) ?: return HttpResponse.notFound()
        val items = itemRepository.findByRunIdOrderById(id, Pageable.from(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE)))
        return HttpResponse.ok(RunDetail(run, items.content, items.totalSize))
    }

    /** Pull tonight's run forward: same settings, same safety brake. There is no brake-free variant. */
    @Post("/run-now")
    open fun runNow(authentication: Authentication): HttpResponse<*> {
        val actor = authentication.name.replace(Regex("[\\r\\n]"), "_")
        return try {
            val summary: UserCleanupRunSummary = scheduler.runWithScheduledPolicy("manual:$actor")
            log.warn(
                "Manual inactive user cleanup by {}: status={} deleted={} warned={} runId={}",
                actor, summary.status, summary.deletedCount, summary.warnedCount, summary.runId
            )
            HttpResponse.ok(summary)
        } catch (e: UserCleanupDisabledException) {
            log.warn("Manual inactive user cleanup refused for {}: disabled", actor)
            HttpResponse.status<ErrorResponse>(HttpStatus.CONFLICT).body(ErrorResponse("Inactive user cleanup is disabled"))
        } catch (e: UserCleanupInProgressException) {
            log.warn("Manual inactive user cleanup refused for {}: already running", actor)
            HttpResponse.status<ErrorResponse>(HttpStatus.CONFLICT).body(ErrorResponse("An inactive user cleanup run is already in progress"))
        } catch (e: UserCleanupMisconfiguredException) {
            log.warn("Manual inactive user cleanup refused for {}: {}", actor, e.message)
            HttpResponse.badRequest(ErrorResponse(e.message ?: "Inactive user cleanup is misconfigured"))
        } catch (e: Exception) {
            log.error("Manual inactive user cleanup failed for {}", actor, e)
            HttpResponse.serverError<ErrorResponse>().body(ErrorResponse("Inactive user cleanup failed"))
        }
    }

    private companion object {
        const val MAX_PAGE_SIZE = 200
    }
}
