package com.secman.controller

import com.secman.dto.WorkgroupAdDomainDto
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import com.secman.service.DuplicateAdDomainException
import com.secman.service.WorkgroupAdDomainService
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
 * REST controller for AD domain assignments on workgroups.
 *
 * Endpoints:
 * - GET    /api/workgroups/{id}/ad-domains             — list (ADMIN or direct member)
 * - POST   /api/workgroups/{id}/ad-domains             — add (ADMIN, or a direct member who
 *                                                         already has access to the domain)
 * - DELETE /api/workgroups/{id}/ad-domains/{adDomain}  — remove (ADMIN or direct member)
 */
@Controller("/api/workgroups/{workgroupId}/ad-domains")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class WorkgroupAdDomainController(
    private val service: WorkgroupAdDomainService,
    private val userRepository: UserRepository,
    private val workgroupRepository: WorkgroupRepository,
    private val userMappingRepository: UserMappingRepository
) {
    private val logger = LoggerFactory.getLogger(WorkgroupAdDomainController::class.java)

    private fun isMemberOrAdmin(workgroupId: Long, authentication: Authentication): Boolean {
        if (authentication.roles.contains("ADMIN")) return true
        val workgroup = workgroupRepository.findById(workgroupId).orElse(null) ?: return false
        val user = userRepository.findByUsername(authentication.name).orElse(null) ?: return false
        return workgroup.users.any { it.id == user.id }
    }

    /**
     * SECURITY: membership alone must not authorize *binding* an arbitrary AD domain.
     *
     * Any authenticated user can create a workgroup and is auto-enrolled as a member, so
     * without this check they could bind any domain and AssetRepository.findAccessibleAssets
     * would hand them every asset in it (unified asset access criterion 10) — a privilege
     * escalation from USER to "reads an entire AD domain".
     *
     * A non-ADMIN actor may therefore only bind a domain they already reach through their own
     * domain UserMapping (criterion 6). Matching is case-insensitive, mirroring
     * AssetFilterService's domain comparison.
     */
    private fun canBindDomain(actorEmail: String, adDomain: String): Boolean {
        val ownDomains = userMappingRepository.findDistinctDomainByEmail(actorEmail)
        return ownDomains.any { it.equals(adDomain, ignoreCase = true) }
    }

    @Get(produces = [MediaType.APPLICATION_JSON])
    open fun list(@PathVariable workgroupId: Long, authentication: Authentication): HttpResponse<List<WorkgroupAdDomainDto>> {
        if (!isMemberOrAdmin(workgroupId, authentication)) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
        }
        return try {
            HttpResponse.ok(service.list(workgroupId).map(WorkgroupAdDomainDto::from))
        } catch (e: IllegalArgumentException) {
            logger.warn("List workgroup AD domains failed: {}", e.message)
            HttpResponse.notFound()
        }
    }

    @Post(consumes = [MediaType.APPLICATION_JSON], produces = [MediaType.APPLICATION_JSON])
    @jakarta.transaction.Transactional
    open fun add(
        @PathVariable workgroupId: Long,
        @Body @Valid request: AddAdDomainRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        val actor = userRepository.findByUsername(authentication.name).orElseThrow {
            IllegalStateException("Authenticated user not found: ${authentication.name}")
        }
        if (!isMemberOrAdmin(workgroupId, authentication)) {
            return HttpResponse.status<Map<String, String>>(io.micronaut.http.HttpStatus.FORBIDDEN)
        }
        if (!authentication.roles.contains("ADMIN") && !canBindDomain(actor.email, request.adDomain)) {
            logger.warn(
                "Rejected AD domain bind: user {} has no access to domain {} (workgroup {})",
                actor.username, request.adDomain, workgroupId
            )
            return HttpResponse.status<Map<String, String>>(io.micronaut.http.HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "You do not have access to AD domain ${request.adDomain}"))
        }
        return try {
            val saved = service.add(workgroupId, request.adDomain, actor.id!!)
            HttpResponse.created(WorkgroupAdDomainDto.from(saved))
        } catch (e: DuplicateAdDomainException) {
            HttpResponse.status<Map<String, String>>(io.micronaut.http.HttpStatus.CONFLICT)
                .body(mapOf("error" to (e.message ?: "duplicate")))
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "invalid request")))
        }
    }

    @Delete("/{adDomain}")
    @jakarta.transaction.Transactional
    open fun remove(
        @PathVariable workgroupId: Long,
        @PathVariable adDomain: String,
        authentication: Authentication
    ): HttpResponse<Void> {
        if (!isMemberOrAdmin(workgroupId, authentication)) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
        }
        val deleted = service.remove(workgroupId, adDomain)
        return if (deleted) HttpResponse.noContent() else HttpResponse.notFound()
    }
}

@Serdeable
data class AddAdDomainRequest(
    @field:Pattern(regexp = "^[a-zA-Z0-9.-]+$", message = "AD domain must contain only letters, numbers, dots, and hyphens")
    val adDomain: String
)
