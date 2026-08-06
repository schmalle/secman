package com.secman.controller

import com.secman.domain.GithubRepoAlertException
import com.secman.repository.GithubRepoAlertExceptionRepository
import com.secman.repository.GithubRepoDependabotAlertRepository
import com.secman.repository.GithubRepositoryRepository
import com.secman.service.CSVGithubOwnerEmailMappingParser
import com.secman.service.GithubOwnerEmailDiscoveryService
import com.secman.service.GithubOwnerEmailMappingService
import com.secman.service.GithubRepoImportService
import io.micronaut.core.annotation.Nullable
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.http.multipart.CompletedFileUpload
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

/**
 * GitHub repository inventory (Feature: GitHub repo vulnerability management).
 *
 * - `GET /api/github/repositories` — the Vulnerability Management → GitHub
 *   view, paginated (`page`/`size`/`sort` query params) with optional
 *   `search` across full name/owner/owner email. ADMIN/VULN/SECCHAMPION
 *   (mirrors Dependabot alerts).
 * - `GET /api/github/repositories/summary` — critical/high/total counts
 *   across all repositories, independent of pagination/search.
 *   ADMIN/VULN/SECCHAMPION.
 * - `PUT /api/github/repositories/{id}/owner-email` — maintain the alert
 *   recipient. ADMIN/VULN.
 * - `POST /api/github/import` — run the GitHub App import. ADMIN/VULN.
 * - `GET|POST|DELETE /api/github/repo-alert-exceptions` — exceptions from the
 *   30-day non-decrease alerting. Read: ADMIN/VULN/SECCHAMPION; write: ADMIN/VULN.
 * - `GET|POST|PUT|DELETE /api/github/owner-email-mappings` — default owner
 *   (org/user login) -> email mappings, auto-filling `ownerEmail` for repos
 *   that don't have one set. Read: ADMIN/VULN/SECCHAMPION; write: ADMIN/VULN.
 * - `POST /api/github/owner-email-mappings/upload-csv` — bulk-create the
 *   above from a CSV of `owner,email` rows. ADMIN/VULN.
 */
@Controller("/api/github")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class GithubRepositoryController(
    private val githubRepositoryRepository: GithubRepositoryRepository,
    private val exceptionRepository: GithubRepoAlertExceptionRepository,
    private val importService: GithubRepoImportService,
    private val alertRepository: GithubRepoDependabotAlertRepository,
    private val ownerEmailMappingService: GithubOwnerEmailMappingService,
    private val ownerEmailMappingCsvParser: CSVGithubOwnerEmailMappingParser,
    private val ownerEmailDiscoveryService: GithubOwnerEmailDiscoveryService
) {
    private val log = LoggerFactory.getLogger(GithubRepositoryController::class.java)

    companion object {
        private const val MAX_MAPPING_CSV_SIZE = 10 * 1024 * 1024L // 10MB
    }

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
    data class GithubRepositorySummaryDto(
        val criticalTotal: Long,
        val highTotal: Long,
        val totalCount: Long
    )

    @Serdeable
    data class GithubRepoAlertDto(
        val id: Long,
        val alertNumber: Int,
        val packageName: String,
        val ecosystem: String,
        val manifestPath: String?,
        val severity: String,
        val ghsaId: String?,
        val cveId: String?,
        val summary: String?,
        val vulnerableVersionRange: String?,
        val firstPatchedVersion: String?,
        val htmlUrl: String?,
        val alertUpdatedAt: Instant?
    )

    @Serdeable
    data class UpdateOwnerEmailRequest(@Nullable val ownerEmail: String? = null)

    @Serdeable
    data class GithubOwnerEmailMappingDto(
        val id: Long,
        val owner: String,
        val email: String,
        val repoCount: Long,
        val createdBy: String,
        val createdAt: Instant,
        val updatedAt: Instant
    )

    @Serdeable
    data class CreateOwnerEmailMappingRequest(@NotBlank val owner: String, @NotBlank val email: String)

    @Serdeable
    data class UpdateOwnerEmailMappingRequest(@NotBlank val email: String)

    @Serdeable
    data class DiscoverOwnerEmailMappingsRequest(val dryRun: Boolean = false)

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
    open fun listRepositories(
        @Nullable @QueryValue search: String?,
        pageable: Pageable
    ): HttpResponse<Map<String, Any>> {
        val now = Instant.now()
        val term = search?.trim()?.takeIf { it.isNotBlank() }

        val size = pageable.size.let { if (it <= 0) 50 else it.coerceAtMost(200) }
        val sort = if (pageable.sort.isSorted) pageable.sort else Sort.of(
            Sort.Order.desc("criticalCount"),
            Sort.Order.desc("highCount"),
            Sort.Order.asc("fullName")
        )
        val effectivePageable = Pageable.from(pageable.number, size, sort)

        val page = if (term != null) {
            githubRepositoryRepository.findByFullNameContainingIgnoreCaseOrOwnerContainingIgnoreCaseOrOwnerEmailContainingIgnoreCase(
                term, term, term, effectivePageable
            )
        } else {
            githubRepositoryRepository.findAll(effectivePageable)
        }

        val exceptionsByRepo = exceptionRepository
            .findByGithubRepositoryIdIn(page.content.map { it.id!! })
            .groupBy { it.githubRepositoryId }

        val dtos = page.content.map { repo ->
            val activeException = exceptionsByRepo[repo.id!!]
                ?.filter { it.isActive(now) }
                ?.maxByOrNull { it.createdAt }
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

        return HttpResponse.ok(
            mapOf(
                "content" to dtos,
                "totalElements" to page.totalSize,
                "totalPages" to page.totalPages,
                "size" to page.size,
                "number" to page.pageNumber
            )
        )
    }

    @Get("/repositories/summary")
    @Secured("ADMIN", "VULN", "SECCHAMPION")
    @Transactional(readOnly = true)
    open fun repositoriesSummary(): HttpResponse<GithubRepositorySummaryDto> {
        return HttpResponse.ok(
            GithubRepositorySummaryDto(
                criticalTotal = githubRepositoryRepository.sumCriticalCount(),
                highTotal = githubRepositoryRepository.sumHighCount(),
                totalCount = githubRepositoryRepository.count()
            )
        )
    }

    @Get("/repositories/{id}/alerts")
    @Secured("ADMIN", "VULN", "SECCHAMPION")
    @Transactional(readOnly = true)
    open fun listRepositoryAlerts(id: Long): HttpResponse<*> {
        if (githubRepositoryRepository.findById(id).isEmpty) {
            return HttpResponse.notFound(ErrorResponse("Repository not found"))
        }
        val alerts = alertRepository.findByGithubRepositoryId(id).map {
            GithubRepoAlertDto(
                id = it.id!!,
                alertNumber = it.alertNumber,
                packageName = it.packageName,
                ecosystem = it.ecosystem,
                manifestPath = it.manifestPath,
                severity = it.severity,
                ghsaId = it.ghsaId,
                cveId = it.cveId,
                summary = it.summary,
                vulnerableVersionRange = it.vulnerableVersionRange,
                firstPatchedVersion = it.firstPatchedVersion,
                htmlUrl = it.htmlUrl,
                alertUpdatedAt = it.alertUpdatedAt
            )
        }
        return HttpResponse.ok(alerts)
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

    @Get("/owner-email-mappings")
    @Secured("ADMIN", "VULN", "SECCHAMPION")
    open fun listOwnerEmailMappings(): HttpResponse<List<GithubOwnerEmailMappingDto>> {
        val dtos = ownerEmailMappingService.list().map { m ->
            GithubOwnerEmailMappingDto(
                id = m.id!!,
                owner = m.owner,
                email = m.email,
                repoCount = ownerEmailMappingService.repoCountFor(m.owner),
                createdBy = m.createdBy,
                createdAt = m.createdAt,
                updatedAt = m.updatedAt
            )
        }
        return HttpResponse.ok(dtos)
    }

    @Post("/owner-email-mappings")
    @Secured("ADMIN", "VULN")
    open fun createOwnerEmailMapping(
        @Valid @Body request: CreateOwnerEmailMappingRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        return try {
            val mapping = ownerEmailMappingService.create(request.owner, request.email, authentication.name)
            HttpResponse.created(
                GithubOwnerEmailMappingDto(
                    id = mapping.id!!,
                    owner = mapping.owner,
                    email = mapping.email,
                    repoCount = ownerEmailMappingService.repoCountFor(mapping.owner),
                    createdBy = mapping.createdBy,
                    createdAt = mapping.createdAt,
                    updatedAt = mapping.updatedAt
                )
            )
        } catch (e: GithubOwnerEmailMappingService.DuplicateOwnerException) {
            HttpResponse.status<ErrorResponse>(HttpStatus.CONFLICT).body(ErrorResponse(e.message ?: "Duplicate owner"))
        } catch (e: GithubOwnerEmailMappingService.InvalidEmailException) {
            HttpResponse.badRequest(ErrorResponse(e.message ?: "Invalid email address"))
        }
    }

    @Put("/owner-email-mappings/{id}")
    @Secured("ADMIN", "VULN")
    open fun updateOwnerEmailMapping(
        id: Long,
        @Valid @Body request: UpdateOwnerEmailMappingRequest
    ): HttpResponse<*> {
        return try {
            val mapping = ownerEmailMappingService.update(id, request.email)
            HttpResponse.ok(
                GithubOwnerEmailMappingDto(
                    id = mapping.id!!,
                    owner = mapping.owner,
                    email = mapping.email,
                    repoCount = ownerEmailMappingService.repoCountFor(mapping.owner),
                    createdBy = mapping.createdBy,
                    createdAt = mapping.createdAt,
                    updatedAt = mapping.updatedAt
                )
            )
        } catch (e: GithubOwnerEmailMappingService.NotFoundException) {
            HttpResponse.notFound(ErrorResponse(e.message ?: "Mapping not found"))
        } catch (e: GithubOwnerEmailMappingService.InvalidEmailException) {
            HttpResponse.badRequest(ErrorResponse(e.message ?: "Invalid email address"))
        }
    }

    @Delete("/owner-email-mappings/{id}")
    @Secured("ADMIN", "VULN")
    open fun deleteOwnerEmailMapping(id: Long): HttpResponse<*> {
        return try {
            ownerEmailMappingService.delete(id)
            HttpResponse.noContent<Any>()
        } catch (e: GithubOwnerEmailMappingService.NotFoundException) {
            HttpResponse.notFound(ErrorResponse(e.message ?: "Mapping not found"))
        }
    }

    @Post("/owner-email-mappings/discover")
    @Secured("ADMIN", "VULN")
    open fun discoverOwnerEmailMappings(
        @Body request: DiscoverOwnerEmailMappingsRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        log.info("GitHub owner email discovery triggered by {} (dryRun={})", authentication.name, request.dryRun)
        return try {
            HttpResponse.ok(ownerEmailDiscoveryService.discover(request.dryRun, authentication.name))
        } catch (e: IllegalStateException) {
            HttpResponse.badRequest(ErrorResponse(e.message ?: "No active GitHub App configuration"))
        } catch (e: Exception) {
            log.error("GitHub owner email discovery failed", e)
            HttpResponse.serverError(ErrorResponse(e.message ?: "Discovery failed"))
        }
    }

    @Post("/owner-email-mappings/upload-csv")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Secured("ADMIN", "VULN")
    open fun uploadOwnerEmailMappingsCsv(
        @Part csvFile: CompletedFileUpload,
        authentication: Authentication
    ): HttpResponse<*> {
        return try {
            if (csvFile.size > MAX_MAPPING_CSV_SIZE) {
                return HttpResponse.status<ErrorResponse>(HttpStatus.REQUEST_ENTITY_TOO_LARGE)
                    .body(ErrorResponse("File size exceeds maximum limit of ${MAX_MAPPING_CSV_SIZE / 1024 / 1024}MB"))
            }
            val filename = csvFile.filename.orEmpty()
            if (!filename.lowercase().endsWith(".csv")) {
                return HttpResponse.badRequest(ErrorResponse("Only .csv files are supported"))
            }
            if (csvFile.size == 0L) {
                return HttpResponse.badRequest(ErrorResponse("Empty file uploaded"))
            }

            val tempPath = try {
                val attrs = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
                Files.createTempFile("gh_owner_email_csv_", ".csv", attrs)
            } catch (e: UnsupportedOperationException) {
                Files.createTempFile("gh_owner_email_csv_", ".csv")
            }
            try {
                csvFile.inputStream.use { input ->
                    Files.newOutputStream(tempPath).use { output -> input.copyTo(output) }
                }
                val result = ownerEmailMappingCsvParser.parse(tempPath.toFile(), authentication.name)
                HttpResponse.ok(result)
            } finally {
                Files.deleteIfExists(tempPath)
            }
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(ErrorResponse(e.message ?: "Invalid CSV format"))
        } catch (e: IOException) {
            HttpResponse.status<ErrorResponse>(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse("Failed to read CSV file"))
        }
    }
}
