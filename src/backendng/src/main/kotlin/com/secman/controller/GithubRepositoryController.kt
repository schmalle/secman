package com.secman.controller

import com.secman.domain.GithubRepoAlertException
import com.secman.repository.GithubRepoAlertExceptionRepository
import com.secman.repository.GithubRepositoryRepository
import com.secman.service.GithubRepoImportService
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.transaction.annotation.Transactional
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * GitHub repository inventory (Feature: GitHub repo vulnerability management).
 *
 * - `GET /api/github/repositories` — the Vulnerability Management → GitHub
 *   view. ADMIN/VULN/SECCHAMPION (mirrors Dependabot alerts).
 * - `PUT /api/github/repositories/{id}/owner-email` — maintain the alert
 *   recipient. ADMIN/VULN.
 * - `POST /api/github/import` — run the GitHub App import. ADMIN/VULN.
 * - `GET|POST|DELETE /api/github/repo-alert-exceptions` — exceptions from the
 *   30-day non-decrease alerting. Read: ADMIN/VULN/SECCHAMPION; write: ADMIN/VULN.
 */
@Controller("/api/github")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class GithubRepositoryController(
    private val githubRepositoryRepository: GithubRepositoryRepository,
    private val exceptionRepository: GithubRepoAlertExceptionRepository,
    private val importService: GithubRepoImportService
) {
    private val log = LoggerFactory.getLogger(GithubRepositoryController::class.java)

    @Serdeable
    data class ActiveExceptionDto(
        val id: Long,
        val reason: String,
        val expirationDate: Instant?,
        val createdBy: String,
        val createdAt: Instant
    )

    @Serdeable
    data class GithubRepositoryDto(
        val id: Long,
        val githubRepoId: Long,
        val name: String,
        val owner: String,
        val fullName: String,
        val htmlUrl: String?,
        val ownerEmail: String?,
        val criticalCount: Int,
        val highCount: Int,
        val lastImportAt: Instant?,
        val lastHighCriticalFindingAt: Instant?,
        val archived: Boolean,
        val activeException: ActiveExceptionDto?
    )

    @Serdeable
    data class UpdateOwnerEmailRequest(@Nullable val ownerEmail: String? = null)

    @Serdeable
    data class CreateExceptionRequest(
        val githubRepositoryId: Long,
        @NotBlank val reason: String,
        @Nullable val expirationDate: Instant? = null
    )

    @Serdeable
    data class ErrorResponse(val error: String)

    private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    @Get("/repositories")
    @Secured("ADMIN", "VULN", "SECCHAMPION")
    @Transactional(readOnly = true)
    open fun listRepositories(): HttpResponse<List<GithubRepositoryDto>> {
        val now = Instant.now()
        val repos = githubRepositoryRepository.listOrderByFullName().map { repo ->
            val activeException = exceptionRepository.findByGithubRepositoryId(repo.id!!)
                .filter { it.isActive(now) }
                .maxByOrNull { it.createdAt }
            GithubRepositoryDto(
                id = repo.id!!,
                githubRepoId = repo.githubRepoId,
                name = repo.name,
                owner = repo.owner,
                fullName = repo.fullName,
                htmlUrl = repo.htmlUrl,
                ownerEmail = repo.ownerEmail,
                criticalCount = repo.criticalCount,
                highCount = repo.highCount,
                lastImportAt = repo.lastImportAt,
                lastHighCriticalFindingAt = repo.lastHighCriticalFindingAt,
                archived = repo.archived,
                activeException = activeException?.let {
                    ActiveExceptionDto(
                        id = it.id!!,
                        reason = it.reason,
                        expirationDate = it.expirationDate,
                        createdBy = it.createdBy,
                        createdAt = it.createdAt
                    )
                }
            )
        }
        return HttpResponse.ok(repos)
    }

    @Put("/repositories/{id}/owner-email")
    @Secured("ADMIN", "VULN")
    @Transactional
    open fun updateOwnerEmail(
        id: Long,
        @Body request: UpdateOwnerEmailRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        val repo = githubRepositoryRepository.findById(id).orElse(null)
            ?: return HttpResponse.notFound(ErrorResponse("Repository not found"))

        val email = request.ownerEmail?.trim()?.takeIf { it.isNotBlank() }
        if (email != null && !emailRegex.matches(email)) {
            return HttpResponse.badRequest(ErrorResponse("Invalid email address"))
        }
        repo.ownerEmail = email?.lowercase()
        val saved = githubRepositoryRepository.update(repo)
        log.info("Owner email for {} set to {} by {}", saved.fullName, saved.ownerEmail ?: "(none)", authentication.name)
        return HttpResponse.ok(mapOf("id" to saved.id, "ownerEmail" to saved.ownerEmail))
    }

    @Post("/import")
    @Secured("ADMIN", "VULN")
    open fun importRepositories(authentication: Authentication): HttpResponse<*> {
        log.info("GitHub repo import triggered by {}", authentication.name)
        return try {
            HttpResponse.ok(importService.importRepositories())
        } catch (e: IllegalStateException) {
            HttpResponse.badRequest(ErrorResponse(e.message ?: "No active GitHub App configuration"))
        } catch (e: Exception) {
            log.error("GitHub repo import failed", e)
            HttpResponse.serverError(ErrorResponse(e.message ?: "Import failed"))
        }
    }

    @Get("/repo-alert-exceptions")
    @Secured("ADMIN", "VULN", "SECCHAMPION")
    @Transactional(readOnly = true)
    open fun listExceptions(): HttpResponse<List<GithubRepoAlertException>> {
        return HttpResponse.ok(exceptionRepository.findAll().sortedByDescending { it.createdAt })
    }

    @Post("/repo-alert-exceptions")
    @Secured("ADMIN", "VULN")
    @Transactional
    open fun createException(
        @Valid @Body request: CreateExceptionRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        val repo = githubRepositoryRepository.findById(request.githubRepositoryId).orElse(null)
            ?: return HttpResponse.badRequest(ErrorResponse("Repository not found"))
        if (request.reason.isBlank()) {
            return HttpResponse.badRequest(ErrorResponse("Reason is required"))
        }
        if (request.expirationDate != null && !request.expirationDate.isAfter(Instant.now())) {
            return HttpResponse.badRequest(ErrorResponse("Expiration date must be in the future"))
        }
        val exception = exceptionRepository.save(
            GithubRepoAlertException(
                githubRepositoryId = repo.id!!,
                reason = request.reason.trim(),
                expirationDate = request.expirationDate,
                createdBy = authentication.name,
                createdAt = Instant.now()
            )
        )
        log.info("GitHub repo alert exception created for {} by {}", repo.fullName, authentication.name)
        return HttpResponse.created(exception)
    }

    @Delete("/repo-alert-exceptions/{id}")
    @Secured("ADMIN", "VULN")
    @Transactional
    open fun deleteException(id: Long, authentication: Authentication): HttpResponse<*> {
        val exception = exceptionRepository.findById(id).orElse(null)
            ?: return HttpResponse.notFound(ErrorResponse("Exception not found"))
        exceptionRepository.delete(exception)
        log.info("GitHub repo alert exception {} deleted by {}", id, authentication.name)
        return HttpResponse.noContent<Any>()
    }
}
