package com.secman.controller

import com.secman.domain.Release
import com.secman.domain.RequirementSnapshot
import com.secman.repository.NormRepository
import com.secman.repository.RequirementSnapshotRepository
import com.secman.repository.UseCaseRepository
import com.secman.service.ReleaseService
import io.micronaut.core.annotation.Introspected
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Inject
import org.slf4j.LoggerFactory

@Controller("/api/releases")
@Secured(SecurityRule.IS_AUTHENTICATED)
class ReleaseController(
    @Inject private val releaseService: ReleaseService,
    @Inject private val snapshotRepository: RequirementSnapshotRepository,
    @Inject private val useCaseRepository: UseCaseRepository,
    @Inject private val normRepository: NormRepository
) {
    private val logger = LoggerFactory.getLogger(ReleaseController::class.java)

    /**
     * POST /api/releases - Create new release
     * Authorization: ADMIN or RELEASE_MANAGER only
     */
    @Post
    @Secured("ADMIN", "RELEASE_MANAGER")
    fun createRelease(
        @Body request: ReleaseCreateRequest,
        authentication: Authentication
    ): HttpResponse<Map<String, Any>> {
        logger.info("Creating release: version=${request.version}, name=${request.name}")

        try {
            val release = releaseService.createRelease(
                version = request.version,
                name = request.name,
                description = request.description,
                authentication = authentication
            )

            val snapshotCount = snapshotRepository.countByReleaseId(release.id!!)

            return HttpResponse.status<Map<String, Any>>(HttpStatus.CREATED)
                .body(toReleaseResponse(release, snapshotCount.toInt()))
        } catch (e: IllegalArgumentException) {
            logger.warn("Release creation failed: ${e.message}")
            return HttpResponse.badRequest(
                mapOf(
                    "error" to "Bad Request",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        }
    }

    /**
     * GET /api/releases - List all releases
     * Optional filter by status, with pagination
     */
    @Get
    fun listReleases(
        @QueryValue("status") status: Release.ReleaseStatus?,
        @QueryValue("page") page: Int?,
        @QueryValue("pageSize") pageSize: Int?
    ): HttpResponse<Map<String, Any>> {
        logger.debug("Listing releases with status filter: $status")

        val releases = releaseService.listReleases(status)
        val responseDtos = releases.map { release ->
            val snapshotCount = snapshotRepository.countByReleaseId(release.id!!)
            toReleaseResponse(release, snapshotCount.toInt())
        }

        // Simple pagination (client-side for now since we load all releases)
        val currentPage = page ?: 1
        val itemsPerPage = pageSize ?: 20
        val totalItems = responseDtos.size
        val totalPages = if (totalItems == 0) 1 else ((totalItems + itemsPerPage - 1) / itemsPerPage)

        val startIndex = (currentPage - 1) * itemsPerPage
        val endIndex = minOf(startIndex + itemsPerPage, totalItems)
        val paginatedData = if (startIndex < totalItems) {
            responseDtos.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        val paginatedResponse = mapOf(
            "data" to paginatedData,
            "currentPage" to currentPage,
            "totalPages" to totalPages,
            "totalItems" to totalItems,
            "pageSize" to itemsPerPage
        )

        return HttpResponse.ok(paginatedResponse)
    }

    /**
     * GET /api/releases/{id} - Get release details
     */
    @Get("/{id}")
    fun getReleaseById(@PathVariable id: Long): HttpResponse<Map<String, Any>> {
        logger.debug("Getting release by ID: $id")

        try {
            val release = releaseService.getReleaseById(id)
            val snapshotCount = snapshotRepository.countByReleaseId(id)

            return HttpResponse.ok(toReleaseResponse(release, snapshotCount.toInt()))
        } catch (e: NoSuchElementException) {
            logger.warn("Release not found: $id")
            return HttpResponse.notFound(
                mapOf(
                    "error" to "Not Found",
                    "message" to (e.message ?: "Release not found")
                )
            )
        }
    }

    /**
     * DELETE /api/releases/{id} - Delete release
     * Authorization: ADMIN or RELEASE_MANAGER only
     */
    @Delete("/{id}")
    @Secured("ADMIN", "RELEASE_MANAGER")
    fun deleteRelease(@PathVariable id: Long): HttpResponse<Void> {
        logger.info("Deleting release: $id")

        try {
            releaseService.deleteRelease(id)
            return HttpResponse.noContent()
        } catch (e: NoSuchElementException) {
            logger.warn("Release not found for deletion: $id")
            return HttpResponse.notFound()
        }
    }

    /**
     * PUT /api/releases/{id}/status - Update release status
     * Authorization: ADMIN or RELEASE_MANAGER only
     * Workflow: DRAFT → PUBLISHED → ARCHIVED (one-way transitions)
     */
    @Put("/{id}/status")
    @Secured("ADMIN", "RELEASE_MANAGER")
    fun updateReleaseStatus(
        @PathVariable id: Long,
        @Body request: ReleaseStatusUpdateRequest
    ): HttpResponse<Map<String, Any>> {
        logger.info("Updating release status: id=$id, newStatus=${request.status}")

        try {
            val updatedRelease = releaseService.updateReleaseStatus(id, request.status)
            val snapshotCount = snapshotRepository.countByReleaseId(id)

            return HttpResponse.ok(toReleaseResponse(updatedRelease, snapshotCount.toInt()))
        } catch (e: NoSuchElementException) {
            logger.warn("Release not found for status update: $id")
            return HttpResponse.notFound(
                mapOf(
                    "error" to "Not Found",
                    "message" to (e.message ?: "Release not found")
                )
            )
        } catch (e: IllegalStateException) {
            logger.warn("Invalid status transition for release $id: ${e.message}")
            return HttpResponse.badRequest(
                mapOf(
                    "error" to "Bad Request",
                    "message" to (e.message ?: "Invalid status transition")
                )
            )
        }
    }

    /**
     * GET /api/releases/{id}/requirements - List snapshots in release
     */
    @Get("/{id}/requirements")
    fun getReleaseRequirements(
        @PathVariable id: Long,
        @QueryValue("page") page: Int?,
        @QueryValue("pageSize") pageSize: Int?
    ): HttpResponse<*> {
        logger.debug("Getting requirements for release: $id")

        try {
            // Verify release exists
            releaseService.getReleaseById(id)

            // Get snapshots
            val snapshots = snapshotRepository.findByReleaseId(id)

            // Batch-resolve use case and norm IDs to full objects for display
            val allUsecaseIds = snapshots.flatMap { parseJsonIds(it.usecaseIdsSnapshot) }.distinct().toSet()
            val allNormIds = snapshots.flatMap { parseJsonIds(it.normIdsSnapshot) }.distinct().toSet()
            val useCaseMap = if (allUsecaseIds.isNotEmpty()) useCaseRepository.findAll().filter { it.id!! in allUsecaseIds }.associateBy { it.id!! } else emptyMap()
            val normMap = if (allNormIds.isNotEmpty()) normRepository.findAll().filter { it.id!! in allNormIds }.associateBy { it.id!! } else emptyMap()

            val responseDtos = snapshots.map { toSnapshotResponse(it, useCaseMap, normMap) }

            // Paginate the response
            val currentPage = page ?: 1
            val itemsPerPage = pageSize ?: 50
            val totalItems = responseDtos.size
            val totalPages = if (totalItems == 0) 1 else ((totalItems + itemsPerPage - 1) / itemsPerPage)

            val startIndex = (currentPage - 1) * itemsPerPage
            val endIndex = minOf(startIndex + itemsPerPage, totalItems)
            val paginatedData = if (startIndex < totalItems) {
                responseDtos.subList(startIndex, endIndex)
            } else {
                emptyList()
            }

            val paginatedResponse = mapOf(
                "data" to paginatedData,
                "currentPage" to currentPage,
                "totalPages" to totalPages,
                "totalItems" to totalItems,
                "pageSize" to itemsPerPage
            )

            return HttpResponse.ok(paginatedResponse)
        } catch (e: NoSuchElementException) {
            logger.warn("Release not found: $id")
            return HttpResponse.notFound(
                mapOf(
                    "error" to "Not Found",
                    "message" to (e.message ?: "Release not found")
                )
            )
        }
    }

    /**
     * Convert Release entity to response DTO
     */
    private fun toReleaseResponse(release: Release, requirementCount: Int): Map<String, Any> {
        return mapOf(
            "id" to release.id!!,
            "version" to release.version,
            "name" to release.name,
            "description" to (release.description ?: ""),
            "status" to release.status.name,
            "requirementCount" to requirementCount,
            "releaseDate" to (release.releaseDate?.toString() ?: ""),
            "createdBy" to (release.createdBy?.email ?: ""),
            "createdAt" to release.createdAt!!.toString(),
            "updatedAt" to release.updatedAt!!.toString()
        )
    }

    /**
     * Convert RequirementSnapshot to response DTO
     */
    private fun toSnapshotResponse(
        snapshot: RequirementSnapshot,
        useCaseMap: Map<Long, com.secman.domain.UseCase>,
        normMap: Map<Long, com.secman.domain.Norm>
    ): Map<String, Any> {
        // Parse JSON arrays for usecase and norm IDs
        val usecaseIds = parseJsonIds(snapshot.usecaseIdsSnapshot)
        val normIds = parseJsonIds(snapshot.normIdsSnapshot)

        // Resolve IDs to full objects for frontend display
        val usecases = usecaseIds.mapNotNull { id -> useCaseMap[id]?.let { mapOf("id" to it.id!!, "name" to it.name) } }
        val norms = normIds.mapNotNull { id -> normMap[id]?.let { uc ->
            val map = mutableMapOf<String, Any>("id" to uc.id!!, "name" to uc.name)
            if (uc.version.isNotBlank()) map["version"] = uc.version
            if (uc.year != null) map["year"] = uc.year!!
            map
        }}

        return mapOf(
            "id" to snapshot.id!!,
            "originalRequirementId" to snapshot.originalRequirementId,
            "internalId" to snapshot.internalId,
            "revision" to snapshot.revision,
            "idRevision" to snapshot.idRevision,
            "shortreq" to snapshot.shortreq,
            "details" to (snapshot.details ?: ""),
            "language" to (snapshot.language ?: ""),
            "example" to (snapshot.example ?: ""),
            "motivation" to (snapshot.motivation ?: ""),
            "usecase" to (snapshot.usecase ?: ""),
            "norm" to (snapshot.norm ?: ""),
            "chapter" to (snapshot.chapter ?: ""),
            "usecaseIds" to usecaseIds,
            "normIds" to normIds,
            "usecases" to usecases,
            "norms" to norms,
            "snapshotTimestamp" to snapshot.snapshotTimestamp.toString()
        )
    }

    /**
     * Parse JSON array string like "[1,2,3]" to List<Long>
     */
    private fun parseJsonIds(jsonString: String?): List<Long> {
        if (jsonString.isNullOrBlank() || jsonString == "[]") return emptyList()

        return try {
            jsonString.trim('[', ']')
                .split(",")
                .filter { it.isNotBlank() }
                .map { it.trim().toLong() }
        } catch (e: Exception) {
            logger.warn("Failed to parse JSON IDs: $jsonString", e)
            emptyList()
        }
    }
}

/**
 * Request DTO for creating a release
 */
@Serdeable
data class ReleaseCreateRequest(
    val version: String,
    val name: String,
    val description: String? = null
)

/**
 * Request DTO for updating release status
 */
@Serdeable
data class ReleaseStatusUpdateRequest(
    val status: Release.ReleaseStatus
)
