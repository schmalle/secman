package com.secman.service

import com.secman.domain.OutdatedAssetMaterializedView
import com.secman.domain.Vulnerability
import com.secman.repository.AssetRepository
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream

/**
 * Service for accessing outdated assets with workgroup-based access control
 *
 * Responsibilities:
 * - Query materialized view with pagination, sorting, filtering
 * - Apply workgroup-based access control (ADMIN sees all, VULN sees assigned workgroups only)
 * - Support search and severity filtering
 *
 * Feature: 034-outdated-assets
 * Task: T016-T019
 * User Story: US1 - View Outdated Assets (P1)
 * Spec reference: spec.md FR-008, FR-009
 */
@Singleton
class OutdatedAssetService(
    private val outdatedAssetRepository: OutdatedAssetMaterializedViewRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val assetRepository: AssetRepository
) {
    private val log = LoggerFactory.getLogger(OutdatedAssetService::class.java)

    /**
     * Get outdated assets with workgroup-based access control
     *
     * Access Control:
     * - ADMIN role: sees all outdated assets (no filtering)
     * - VULN role: sees only assets from assigned workgroups
     * - No VULN/ADMIN: unauthorized (handled by controller @Secured)
     *
     * @param authentication Current user authentication context
     * @param searchTerm Optional search term for asset name (case-insensitive)
     * @param minSeverity Optional minimum severity filter (CRITICAL, HIGH, MEDIUM, LOW)
     * @param adDomain Optional AD domain filter (case-insensitive exact match)
     * @param pageable Pagination and sorting parameters
     * @return Page of outdated assets visible to the user
     */
    fun getOutdatedAssets(
        authentication: Authentication,
        searchTerm: String? = null,
        minSeverity: String? = null,
        adDomain: String? = null,
        pageable: Pageable
    ): Page<OutdatedAssetMaterializedView> {
        // Extract user's workgroup IDs from authentication attributes
        val workgroupIds = extractWorkgroupIds(authentication)

        // Check if user has ADMIN role - if so, they see everything
        val isAdmin = authentication.roles.contains("ADMIN")

        // Build workgroup filter parameter
        val workgroupFilter = if (isAdmin) {
            // ADMIN sees all - pass null to disable workgroup filtering
            null
        } else {
            // VULN user - filter by their assigned workgroups
            // Convert list of IDs to comma-separated string for LIKE query
            workgroupIds?.joinToString(",")
        }

        // Query repository with filters
        // Create pageable without sorting to avoid JPQL query issues
        val unsortedPageable = Pageable.from(pageable.number, pageable.size)

        return outdatedAssetRepository.findOutdatedAssets(
            workgroupId = workgroupFilter,
            searchTerm = searchTerm,
            minSeverity = minSeverity,
            adDomain = adDomain,
            pageable = unsortedPageable
        )
    }

    /**
     * Extract workgroup IDs from authentication context
     *
     * Workgroup IDs are stored in authentication attributes as "workgroupIds"
     * Expected format: List<Long>
     *
     * @param authentication User authentication context
     * @return List of workgroup IDs or null if not present
     */
    private fun extractWorkgroupIds(authentication: Authentication): List<Long>? {
        val workgroupIdsAttr = authentication.attributes["workgroupIds"] ?: return null

        return when (workgroupIdsAttr) {
            is List<*> -> workgroupIdsAttr.filterIsInstance<Long>()
            is Collection<*> -> workgroupIdsAttr.filterIsInstance<Long>()
            else -> null
        }
    }

    /**
     * Get the latest refresh timestamp
     *
     * @return Latest timestamp from materialized view, or null if no data
     */
    fun getLastRefreshTimestamp(): java.time.LocalDateTime? {
        return outdatedAssetRepository.findLatestCalculatedAt()
    }

    /**
     * Get distinct AD domains for filter dropdown
     *
     * Queries the Asset table directly to get all AD domains in the system,
     * rather than the materialized view which may not be refreshed.
     *
     * @return List of unique AD domain values, ordered alphabetically
     */
    fun getDistinctAdDomains(): List<String> {
        return assetRepository.findDistinctAdDomains()
    }

    /**
     * Count total outdated assets visible to user
     *
     * @param authentication Current user authentication context
     * @return Total count respecting workgroup access control
     */
    fun countOutdatedAssets(authentication: Authentication): Long {
        val isAdmin = authentication.roles.contains("ADMIN")

        return if (isAdmin) {
            outdatedAssetRepository.count()
        } else {
            val workgroupIds = extractWorkgroupIds(authentication)
            val workgroupFilter = workgroupIds?.joinToString(",")

            outdatedAssetRepository.countOutdatedAssets(
                workgroupId = workgroupFilter
            )
        }
    }

    /**
     * Get single outdated asset by ID with access control
     *
     * Task: T032-T034
     * User Story: US2 - View Asset Details
     *
     * @param id Outdated asset materialized view ID
     * @param authentication Current user authentication context
     * @return Outdated asset or null if not found or unauthorized
     */
    fun getOutdatedAssetById(
        id: Long,
        authentication: Authentication
    ): OutdatedAssetMaterializedView? {
        val asset = outdatedAssetRepository.findById(id).orElse(null) ?: return null

        // Check access control
        val isAdmin = authentication.roles.contains("ADMIN")
        if (isAdmin) {
            // ADMIN sees all
            return asset
        }

        // VULN user - check workgroup access
        val userWorkgroupIds = extractWorkgroupIds(authentication) ?: emptyList()

        // If asset has no workgroups, allow access (backward compatibility)
        if (asset.workgroupIds.isNullOrBlank()) {
            return asset
        }

        // Parse asset's workgroup IDs
        val workgroupIdsStr = asset.workgroupIds ?: ""
        val assetWorkgroupIds = workgroupIdsStr.split(",").mapNotNull { it.toLongOrNull() }

        // Check if user has access to any of the asset's workgroups
        val hasAccess = assetWorkgroupIds.any { it in userWorkgroupIds }

        return if (hasAccess) asset else null
    }

    /**
     * Get vulnerabilities for an outdated asset
     *
     * Task: T037-T038
     * User Story: US2 - View Asset Details
     *
     * @param assetId The actual asset ID (not materialized view ID)
     * @param pageable Pagination parameters
     * @return Page of vulnerabilities for the asset
     */
    fun getVulnerabilitiesForAsset(
        assetId: Long,
        pageable: Pageable
    ): Page<Vulnerability> {
        return vulnerabilityRepository.findByAssetId(assetId, pageable)
    }

    /**
     * Export outdated assets to Excel with the same filters as the list view
     *
     * @param authentication Current user authentication context
     * @param searchTerm Optional search term for asset name
     * @param minSeverity Optional minimum severity filter
     * @param adDomain Optional AD domain filter
     * @return ByteArrayOutputStream containing the Excel workbook
     */
    fun exportOutdatedAssets(
        authentication: Authentication,
        searchTerm: String? = null,
        minSeverity: String? = null,
        adDomain: String? = null
    ): ByteArrayOutputStream {
        // Fetch all matching assets (large page to get everything)
        val page = getOutdatedAssets(
            authentication = authentication,
            searchTerm = searchTerm,
            minSeverity = minSeverity,
            adDomain = adDomain,
            pageable = Pageable.from(0, 100_000)
        )
        val assets = page.content

        log.info("Exporting {} outdated assets to Excel for user: {}", assets.size, authentication.name)

        val workbook = SXSSFWorkbook(100)
        workbook.setCompressTempFiles(true)

        try {
            val sheet = workbook.createSheet("Outdated Assets")

            // Header style
            val headerStyle = workbook.createCellStyle().apply {
                fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
                val font = workbook.createFont()
                font.bold = true
                setFont(font)
            }

            // Header row
            val headerRow = sheet.createRow(0)
            val headers = listOf(
                "Asset Name", "Asset Type", "AD Domain",
                "Total Overdue", "Critical", "High", "Medium", "Low",
                "Oldest Vuln (Days)", "Oldest Vuln ID"
            )
            headers.forEachIndexed { index, header ->
                headerRow.createCell(index).apply {
                    setCellValue(header)
                    cellStyle = headerStyle
                }
            }

            // Data rows
            assets.forEachIndexed { index, asset ->
                val row = sheet.createRow(index + 1)
                row.createCell(0).setCellValue(asset.assetName)
                row.createCell(1).setCellValue(asset.assetType)
                row.createCell(2).setCellValue(asset.adDomain ?: "")
                row.createCell(3).setCellValue(asset.totalOverdueCount.toDouble())
                row.createCell(4).setCellValue(asset.criticalCount.toDouble())
                row.createCell(5).setCellValue(asset.highCount.toDouble())
                row.createCell(6).setCellValue(asset.mediumCount.toDouble())
                row.createCell(7).setCellValue(asset.lowCount.toDouble())
                row.createCell(8).setCellValue(asset.oldestVulnDays.toDouble())
                row.createCell(9).setCellValue(asset.oldestVulnId ?: "")
            }

            // Column widths
            sheet.setColumnWidth(0, 40 * 256)  // Asset Name
            sheet.setColumnWidth(1, 15 * 256)  // Asset Type
            sheet.setColumnWidth(2, 25 * 256)  // AD Domain
            sheet.setColumnWidth(3, 15 * 256)  // Total Overdue
            sheet.setColumnWidth(4, 12 * 256)  // Critical
            sheet.setColumnWidth(5, 12 * 256)  // High
            sheet.setColumnWidth(6, 12 * 256)  // Medium
            sheet.setColumnWidth(7, 12 * 256)  // Low
            sheet.setColumnWidth(8, 18 * 256)  // Oldest Vuln Days
            sheet.setColumnWidth(9, 20 * 256)  // Oldest Vuln ID

            val outputStream = ByteArrayOutputStream()
            workbook.write(outputStream)
            return outputStream
        } finally {
            @Suppress("DEPRECATION") workbook.dispose()
        }
    }
}
