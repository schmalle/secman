package com.secman.controller

import com.secman.domain.AwsAccount
import com.secman.repository.AwsAccountRepository
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Put
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.transaction.annotation.Transactional
import org.slf4j.LoggerFactory

/**
 * Admin-editable display names for AWS accounts.
 *
 * Rows are created lazily on first name assignment. A blank name clears the stored
 * name, which makes the account fall back to its bare 12-digit ID in every report.
 */
@Controller("/api/admin/aws-accounts")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class AwsAccountController(
    private val awsAccountRepository: AwsAccountRepository
) {
    private val logger = LoggerFactory.getLogger(AwsAccountController::class.java)

    private val accountIdPattern = Regex("^\\d{12}$")

    @Serdeable
    data class UpdateNameRequest(val name: String?)

    @Serdeable
    data class AwsAccountNameResponse(val awsAccountId: String, val name: String?)

    @Serdeable
    data class ErrorResponse(val message: String)

    @Put("/{awsAccountId}/name")
    @Transactional
    open fun updateName(
        @PathVariable awsAccountId: String,
        @Body request: UpdateNameRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        if (!accountIdPattern.matches(awsAccountId)) {
            return HttpResponse.badRequest(ErrorResponse("AWS Account ID must be exactly 12 numeric digits"))
        }

        val cleaned = request.name?.trim()?.ifBlank { null }

        return try {
            val existing = awsAccountRepository.findByAwsAccountId(awsAccountId)
            val saved = if (existing.isPresent) {
                val row = existing.get()
                row.name = cleaned
                row.updatedBy = authentication.name
                awsAccountRepository.update(row)
            } else {
                awsAccountRepository.save(
                    AwsAccount(awsAccountId = awsAccountId, name = cleaned, updatedBy = authentication.name)
                )
            }
            logger.info("AWS account {} renamed to '{}' by {}", awsAccountId, cleaned, authentication.name)
            HttpResponse.ok(AwsAccountNameResponse(saved.awsAccountId, saved.name))
        } catch (e: Exception) {
            logger.error("Failed to update name for AWS account {}", awsAccountId, e)
            HttpResponse.serverError(ErrorResponse("Internal server error"))
        }
    }
}
