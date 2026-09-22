package com.secman.controller

import com.secman.dto.WorkgroupAwsAccountDto
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import com.secman.service.AwsAccountSharingService
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
 * - GET    /api/workgroups/{id}/aws-accounts                 — list (ADMIN or direct member)
 * - POST   /api/workgroups/{id}/aws-accounts                 — add (ADMIN, or a direct member who
 *                                                              already has access to the account)
 * - DELETE /api/workgroups/{id}/aws-accounts/{awsAccountId}  — remove (ADMIN or direct member)
 */
@Controller("/api/workgroups/{workgroupId}/aws-accounts")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class WorkgroupAwsAccountController(
    private val service: WorkgroupAwsAccountService,
    private val userRepository: UserRepository,
    private val workgroupRepository: WorkgroupRepository,
    private val userMappingRepository: UserMappingRepository,
    private val awsAccountSharingService: AwsAccountSharingService
) {
    private val logger = LoggerFactory.getLogger(WorkgroupAwsAccountController::class.java)

    /**
     * Caller must already be inside a transaction: the direct-membership check reads the LAZY
     * `Workgroup.users` collection, which throws without an open session.
     */
    private fun isMemberOrAdmin(workgroupId: Long, authentication: Authentication): Boolean {
        if (com.secman.security.GrantAuthority.canManage(authentication.roles)) return true
        val workgroup = workgroupRepository.findById(workgroupId).orElse(null) ?: return false
        val user = userRepository.findByUsername(authentication.name).orElse(null) ?: return false
        return workgroup.enabled && workgroup.users.any { it.id == user.id }
    }

    @Get(produces = [MediaType.APPLICATION_JSON])
    @jakarta.transaction.Transactional
    open fun list(@PathVariable workgroupId: Long, authentication: Authentication): HttpResponse<List<WorkgroupAwsAccountDto>> {
        if (!isMemberOrAdmin(workgroupId, authentication)) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
        }
        return try {
            val rows = service.list(workgroupId).map(WorkgroupAwsAccountDto::from)
            HttpResponse.ok(rows)
        } catch (e: IllegalArgumentException) {
            logger.warn("List workgroup AWS accounts failed: {}", e.message)
            HttpResponse.notFound()
        }
    }

    @Post(consumes = [MediaType.APPLICATION_JSON], produces = [MediaType.APPLICATION_JSON])
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @jakarta.transaction.Transactional
    open fun add(
        @PathVariable workgroupId: Long,
        @Body @Valid request: AddAwsAccountRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        val actor = userRepository.findByUsername(authentication.name).orElseThrow {
            IllegalStateException("Authenticated user not found: ${authentication.name}")
        }
        if (!com.secman.security.GrantAuthority.canManage(authentication.roles)) {
            return HttpResponse.status<Map<String, String>>(io.micronaut.http.HttpStatus.FORBIDDEN)
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

    @Get("/owner-sync")
    @Secured("ADMIN")
    @jakarta.transaction.Transactional
    open fun previewOwnerSync(workgroupId: Long, authentication: Authentication): Map<String, Any> =
        service.reconcileOwner(workgroupId, userRepository.findByUsername(authentication.name).orElseThrow().id!!, true, null)

    @Post("/owner-sync")
    @Secured("ADMIN")
    @jakarta.transaction.Transactional
    open fun applyOwnerSync(workgroupId: Long, @Body request: OwnerSyncRequest, authentication: Authentication): Map<String, Any> =
        service.reconcileOwner(workgroupId, userRepository.findByUsername(authentication.name).orElseThrow().id!!, false, request.expectedAccounts)

    /** Require the reviewed mapping set so a stale preview cannot silently grant new accounts. */
    @Serdeable
    data class OwnerSyncRequest(val expectedAccounts: Set<String>)

    @Delete("/{awsAccountId}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @jakarta.transaction.Transactional
    open fun remove(
        @PathVariable workgroupId: Long,
        @PathVariable awsAccountId: String,
        authentication: Authentication
    ): HttpResponse<Void> {
        if (!com.secman.security.GrantAuthority.canManage(authentication.roles)) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
        }
        val deleted = service.remove(workgroupId, awsAccountId)
        return if (deleted) HttpResponse.noContent() else HttpResponse.notFound()
    }
}

@Serdeable
data class AddAwsAccountRequest(
    @field:Pattern(regexp = "^\\d{12}$", message = "AWS Account ID must be exactly 12 numeric digits")
    val awsAccountId: String
)
