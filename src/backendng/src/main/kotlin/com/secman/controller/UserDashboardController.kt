package com.secman.controller

import com.secman.dto.UserDashboardResponse
import com.secman.service.UserDashboardService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import org.slf4j.LoggerFactory

/**
 * Personal todo dashboard for the logged-in user (Home page for users
 * without the ADMIN or SECCHAMPION role).
 *
 * GET /api/user-dashboard
 * Auth: any authenticated user (data is scoped to the caller server-side)
 *
 * Returns everything the "My Tasks" home dashboard needs in a single
 * response: accessible-asset count, open vulnerability severity counts,
 * overdue-patching summary (materialized view), the user's own exception
 * request counts, open risk assessments awaiting the user's response, and
 * flags for which per-user vulnerability views have data.
 */
@Controller("/api/user-dashboard")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class UserDashboardController(
    private val userDashboardService: UserDashboardService
) {

    private val logger = LoggerFactory.getLogger(UserDashboardController::class.java)

    @Serdeable
    data class ErrorResponse(
        val message: String,
        val status: Int
    )

    @Get
    open fun getDashboard(authentication: Authentication): HttpResponse<*> {
        return try {
            val dashboard: UserDashboardResponse = userDashboardService.getDashboard(authentication)
            logger.debug(
                "User dashboard for {}: {} assets, {} open vulns, {} overdue assets, {} risk assessment todos",
                authentication.name,
                dashboard.assetCount,
                dashboard.vulnerabilities.total,
                dashboard.overdue.assetCount,
                dashboard.riskAssessments.size
            )
            HttpResponse.ok(dashboard)
        } catch (e: Exception) {
            logger.error("Error building user dashboard for {}", authentication.name, e)
            HttpResponse.serverError(
                ErrorResponse("Internal server error", HttpStatus.INTERNAL_SERVER_ERROR.code)
            )
        }
    }
}
