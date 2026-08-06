package com.secman.controller

import com.secman.dto.AccountFindingAgeDto
import com.secman.service.AccountFindingAgeService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.QueryValue
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.transaction.annotation.Transactional
import org.slf4j.LoggerFactory

/**
 * Top AWS accounts by the age of their oldest still-open finding.
 *
 * ADMIN only — this is a global, unscoped view of the whole estate, deliberately
 * not filtered by the unified asset-access rules.
 */
@Controller("/api/admin/account-finding-age")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class AccountFindingAgeController(
    private val accountFindingAgeService: AccountFindingAgeService
) {
    private val logger = LoggerFactory.getLogger(AccountFindingAgeController::class.java)

    @Serdeable
    data class ErrorResponse(val message: String)

    @Get("/top")
    @Transactional(readOnly = true)
    open fun getTop(
        @QueryValue(defaultValue = "10") limit: Int,
        authentication: Authentication
    ): HttpResponse<*> {
        return try {
            val rows = accountFindingAgeService.getTopAccountsByOldestFinding(limit)
            logger.debug("Account finding-age report: {} accounts for {}", rows.size, authentication.name)
            HttpResponse.ok(rows.map { AccountFindingAgeDto.from(it) })
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(ErrorResponse(e.message ?: "Invalid limit"))
        } catch (e: Exception) {
            logger.error("Failed to build account finding-age report", e)
            HttpResponse.serverError(ErrorResponse("Internal server error"))
        }
    }
}
