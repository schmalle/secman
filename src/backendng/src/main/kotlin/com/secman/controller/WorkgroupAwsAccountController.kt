package com.secman.controller

import com.secman.dto.WorkgroupAwsAccountDto
import com.secman.repository.UserRepository
import com.secman.service.DuplicateAccountException
import com.secman.service.WorkgroupAwsAccountService
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.slf4j.LoggerFactory

/**
 * REST controller for AWS account assignments on workgroups.
 * Spec: docs/superpowers/specs/2026-04-28-workgroup-aws-account-assignment-design.md
 *
 * Endpoints:
 * - GET    /api/workgroups/{id}/aws-accounts                 — list (authenticated; access enforced by service+filter)
 * - POST   /api/workgroups/{id}/aws-accounts                 — add (ADMIN)
 * - DELETE /api/workgroups/{id}/aws-accounts/{awsAccountId}  — remove (ADMIN)
 */
@Controller("/api/workgroups/{workgroupId}/aws-accounts")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class WorkgroupAwsAccountController(
    private val service: WorkgroupAwsAccountService,
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(WorkgroupAwsAccountController::class.java)

    @Get(produces = [MediaType.APPLICATION_JSON])
    open fun list(@PathVariable workgroupId: Long): HttpResponse<List<WorkgroupAwsAccountDto>> {
        return try {
            val rows = service.list(workgroupId).map(WorkgroupAwsAccountDto::from)
            HttpResponse.ok(rows)
        } catch (e: IllegalArgumentException) {
            logger.warn("List workgroup AWS accounts failed: {}", e.message)
            HttpResponse.notFound()
        }
    }

    @Post(consumes = [MediaType.APPLICATION_JSON], produces = [MediaType.APPLICATION_JSON])
    @Secured("ADMIN")
    open fun add(
        @PathVariable workgroupId: Long,
        @Body @Valid request: AddAwsAccountRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        val actor = userRepository.findByUsername(authentication.name).orElseThrow {
            IllegalStateException("Authenticated user not found: ${authentication.name}")
        }
        return try {
            val saved = service.add(workgroupId, request.awsAccountId, actor.id!!)
            HttpResponse.created(WorkgroupAwsAccountDto.from(saved))
        } catch (e: DuplicateAccountException) {
            HttpResponse.status<Map<String, String>>(io.micronaut.http.HttpStatus.CONFLICT)
                .body(mapOf("error" to (e.message ?: "duplicate")))
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "invalid request")))
        }
    }

    @Delete("/{awsAccountId}")
    @Secured("ADMIN")
    open fun remove(
        @PathVariable workgroupId: Long,
        @PathVariable awsAccountId: String
    ): HttpResponse<Void> {
        val deleted = service.remove(workgroupId, awsAccountId)
        return if (deleted) HttpResponse.noContent() else HttpResponse.notFound()
    }
}

@Serdeable
data class AddAwsAccountRequest(
    @field:Pattern(regexp = "^\\d{12}$", message = "AWS Account ID must be exactly 12 numeric digits")
    val awsAccountId: String
)
