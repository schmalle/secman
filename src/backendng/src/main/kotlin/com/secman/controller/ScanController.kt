package com.secman.controller

import com.secman.dto.PortHistoryDTO
import com.secman.dto.ScanSummaryDTO
import com.secman.repository.ScanRepository
import com.secman.repository.ScanResultRepository
import com.secman.service.ScanImportService
import io.micronaut.data.model.Pageable
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.http.multipart.CompletedFileUpload
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import jakarta.validation.constraints.NotNull
import org.slf4j.LoggerFactory

/**
 * REST controller for scan management
 *
 * Endpoints:
 * - POST /api/scan/upload-nmap - Upload nmap XML file (ADMIN only)
 * - GET /api/scans - List all scans with pagination (ADMIN only)
 * - GET /api/scans/{id} - Get scan detail with hosts (ADMIN only)
 *
 * Related to:
 * - Feature: 002-implement-a-parsing (Nmap Scan Import)
 * - Contracts: specs/002-implement-a-parsing/contracts/
 * - FR-007, FR-008: Scans page with admin-only access
 */
@Controller("/api")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class ScanController(
    private val scanImportService: ScanImportService,
    private val scanRepository: ScanRepository,
    private val scanResultRepository: ScanResultRepository
) {
    private val logger = LoggerFactory.getLogger(ScanController::class.java)

    /**
     * Upload nmap XML scan file
     *
     * POST /api/scan/upload-nmap
     * Auth: ADMIN role required
     * Request: multipart/form-data with "file" field
     * Response: ScanSummaryDTO with import statistics
     *
     * Contract: specs/002-implement-a-parsing/contracts/upload-nmap.yaml
     */
    @Post("/scan/upload-nmap")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Secured("ADMIN")
    open fun uploadNmapScan(
        @Part @NotNull file: CompletedFileUpload,
        authentication: Authentication
    ): HttpResponse<*> {
        try {
            val username = authentication.name
            logger.info("Nmap upload request: filename=${file.filename}, user=$username, size=${file.size}")

            // Validate file
            if (file.size == 0L) {
                return HttpResponse.badRequest(mapOf("error" to "File is empty"))
            }

            // Check file size (10MB limit)
            val maxSize = 10 * 1024 * 1024L // 10MB
            if (file.size > maxSize) {
                return HttpResponse.status<Any>(HttpStatus.REQUEST_ENTITY_TOO_LARGE)
                    .body(mapOf("error" to "File exceeds 10MB limit"))
            }

            // Read file content
            val content = file.bytes

            // Import scan
            val summary = scanImportService.importNmapScan(
                xmlContent = content,
                filename = file.filename,
                username = username
            )

            // Convert to DTO
            val dto = ScanSummaryDTO(
                scanId = summary.scanId,
                filename = summary.filename,
                scanDate = summary.scanDate,
                hostsDiscovered = summary.hostsDiscovered,
                assetsCreated = summary.assetsCreated,
                assetsUpdated = summary.assetsUpdated,
                totalPorts = summary.totalPorts,
                duration = summary.duration
            )

            logger.info("Nmap upload successful: scanId=${dto.scanId}, hosts=${dto.hostsDiscovered}")
            return HttpResponse.ok(dto)

        } catch (e: Exception) {
            logger.error("Nmap upload failed: ${e.message}", e)
            return HttpResponse.badRequest(mapOf("error" to e.message))
        }
    }

    /**
     * List all scans with pagination
     *
     * GET /api/scans?page=0&size=20&scanType=nmap
     * Auth: ADMIN role required
     * Response: Page<ScanDTO>
     *
     * Contract: specs/002-implement-a-parsing/contracts/list-scans.yaml
     */
    @Get("/scans{?page,size,scanType}")
    @Produces(MediaType.APPLICATION_JSON)
    @Secured("ADMIN")
    open fun listScans(
        @QueryValue(defaultValue = "0") page: Int,
        @QueryValue(defaultValue = "20") size: Int,
        @QueryValue scanType: String?
    ): HttpResponse<*> {
        try {
            // Validate pagination parameters
            if (page < 0) {
                return HttpResponse.badRequest(mapOf("error" to "page must be non-negative"))
            }
            if (size <= 0) {
                return HttpResponse.badRequest(mapOf("error" to "size must be positive"))
            }

            val pageable = Pageable.from(page, size)

            val scansPage = if (scanType != null) {
                scanRepository.findByScanTypeOrderByScanDateDesc(scanType, pageable)
            } else {
                scanRepository.findAllOrderByScanDateDesc(pageable)
            }

            // Convert to DTOs
            val dtoContent = scansPage.content.map { scan ->
                mapOf(
                    "id" to scan.id,
                    "scanType" to scan.scanType,
                    "filename" to scan.filename,
                    "scanDate" to scan.scanDate,
                    "uploadedBy" to scan.uploadedBy,
                    "hostCount" to scan.hostCount,
                    "duration" to (scan.duration?.let { "${it}s" } ?: "unknown"),
                    "createdAt" to scan.createdAt
                )
            }

            val response = mapOf(
                "content" to dtoContent,
                "totalElements" to scansPage.totalSize,
                "totalPages" to scansPage.totalPages,
                "size" to scansPage.size,
                "number" to scansPage.pageNumber
            )

            return HttpResponse.ok(response)

        } catch (e: Exception) {
            logger.error("List scans failed: ${e.message}", e)
            return HttpResponse.serverError(mapOf("error" to "Failed to list scans"))
        }
    }

    /**
     * Get scan detail with host list
     *
     * GET /api/scans/{id}
     * Auth: ADMIN role required
     * Response: ScanDetailDTO
     *
     * Contract: specs/002-implement-a-parsing/contracts/list-scans.yaml
     */
    @Get("/scans/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Secured("ADMIN")
    open fun getScanDetail(@PathVariable id: Long): HttpResponse<*> {
        try {
            val scan = scanRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(mapOf("error" to "Scan not found"))

            // Get scan results (hosts) for this scan
            val scanResults = scanResultRepository.findByScanId(id)

            // Convert scan results to host DTOs
            val hosts = scanResults.map { result ->
                mapOf(
                    "ipAddress" to result.ipAddress,
                    "hostname" to result.hostname,
                    "discoveredAt" to result.discoveredAt,
                    "portCount" to result.ports.size
                )
            }

            val response = mapOf(
                "id" to scan.id,
                "scanType" to scan.scanType,
                "filename" to scan.filename,
                "scanDate" to scan.scanDate,
                "uploadedBy" to scan.uploadedBy,
                "hostCount" to scan.hostCount,
                "duration" to (scan.duration?.let { "${it}s" } ?: "unknown"),
                "createdAt" to scan.createdAt,
                "hosts" to hosts
            )

            return HttpResponse.ok(response)

        } catch (e: Exception) {
            logger.error("Get scan detail failed: ${e.message}", e)
            return HttpResponse.serverError(mapOf("error" to "Failed to get scan detail"))
        }
    }
}
