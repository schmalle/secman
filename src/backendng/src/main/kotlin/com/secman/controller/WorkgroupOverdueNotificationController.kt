package com.secman.controller

import com.secman.dto.OverdueNotificationRunRequest
import com.secman.service.WorkgroupOverdueNotificationService
import com.secman.service.WorkgroupOverdueNotificationService.Companion.sanitizeForLog
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import org.slf4j.LoggerFactory

/**
 * Backend for `secman show-notified-users` and `secman notify-users-overdue`.
 * ADMIN only; see docs/OVERDUE_NOTIFICATIONS.md for the recipient rule.
 */
@Controller("/api/overdue-notifications")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
class WorkgroupOverdueNotificationController(private val service: WorkgroupOverdueNotificationService) {
    private val logger = LoggerFactory.getLogger(WorkgroupOverdueNotificationController::class.java)

    /** `show-notified-users`: read-only, never sends. */
    @Get("/users")
    fun overview(
        @QueryValue email: String,
        @QueryValue(defaultValue = "30") days: Int,
        authentication: Authentication
    ): HttpResponse<*> = guarded("show-notified-users", authentication) {
        val result = service.overview(email, days)
        logger.info("show-notified-users: actor={}, targetId={}, status={}",
            sanitizeForLog(authentication.name), result.userId, result.status)
        HttpResponse.ok(result).header("Cache-Control", "no-store")
    }

    /** `notify-users-overdue`: sends, or with dryRun only resolves, the digests. */
    @Post("/send")
    fun send(@Body request: OverdueNotificationRunRequest, authentication: Authentication): HttpResponse<*> =
        guarded("notify-users-overdue", authentication) {
            HttpResponse.ok(service.run(request.thresholdDays, request.dryRun, request.notificationUser, authentication.name))
        }

    /** Bad input → 400 with a fixed message; anything else → 500 with the detail kept in the server log. */
    private fun guarded(operation: String, authentication: Authentication, block: () -> HttpResponse<*>): HttpResponse<*> =
        try {
            block()
        } catch (e: WorkgroupOverdueNotificationService.InvalidRequest) {
            logger.warn("{} rejected for actor={}: {}", operation, sanitizeForLog(authentication.name), e.message)
            HttpResponse.badRequest(mapOf("error" to "Invalid request: ${e.message}"))
        } catch (e: Exception) {
            logger.error("{} failed for actor={}", operation, sanitizeForLog(authentication.name), e)
            HttpResponse.serverError(mapOf("error" to "Internal error; see server log"))
        }
}
