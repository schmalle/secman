package com.secman.service

import com.secman.domain.OutdatedAssetMaterializedView
import com.secman.util.ExcelSanitizer
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
 */
@Singleton
class OutdatedAssetService(
    private val outdatedAssetRepository: OutdatedAssetMaterializedViewRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val assetRepository: AssetRepository,
    private val assetFilterService: AssetFilterService
) {
    private val log = LoggerFactory.getLogger(OutdatedAssetService::class.java)

    /** Resolve current grants before querying the materialized data. */
    private fun accessibleAssetIds(authentication: Authentication): Set<Long>? {
        if (authentication.roles.contains("ADMIN")) return null
        return assetFilterService.getAccessibleAssetIds(authentication)
    }

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
        val unsortedPageable = Pageable.from(pageable.number, pageable.size)
        val accessible = accessibleAssetIds(authentication)
        if (accessible == null) {
            return outdatedAssetRepository.findOutdatedAssets(
                null, searchTerm, minSeverity, adDomain, unsortedPageable
            )
        }
        if (accessible.isEmpty()) return Page.of(emptyList(), unsortedPageable, 0)

        // Filter by live grants before both pagination and COUNT; denormalized workgroups
        // can be stale and cannot represent personal mappings or directional sharing.
        return outdatedAssetRepository.findOutdatedAssetsForAssets(
            accessible, searchTerm, minSeverity, adDomain, unsortedPageable
        )
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
        val accessible = accessibleAssetIds(authentication) ?: return outdatedAssetRepository.count()
        if (accessible.isEmpty()) return 0L
        return outdatedAssetRepository.countByAssetIdIn(accessible)
    }

    /**
     * Get single outdated asset by ID with access control
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

        if (authentication.roles.contains("ADMIN")) return asset

        // Use unified access control. The previous workgroup-only check
        // had an explicit "no workgroups → allow" backward-compat bypass
        // that exposed every unassigned asset to every VULN user.
        val accessible = accessibleAssetIds(authentication) ?: return asset
        return if (accessible.contains(asset.assetId)) asset else null
    }

    /**
     * Get vulnerabilities for an outdated asset
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
     * Defense-in-depth variant: verify the user can access the asset before
     * returning its vulnerabilities. Prefer this over [getVulnerabilitiesForAsset]
     * for any new caller — the unchecked variant is retained only for the
     * existing controller path that already validates access via
     * [getOutdatedAssetById] immediately before calling.
     */
    fun getVulnerabilitiesForAssetWithAuth(
        assetId: Long,
        authentication: Authentication,
        pageable: Pageable
    ): Page<Vulnerability>? {
        if (!authentication.roles.contains("ADMIN")) {
            val accessible = accessibleAssetIds(authentication)
            if (accessible != null && !accessible.contains(assetId)) return null
        }
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
                row.createCell(0).setCellValue(ExcelSanitizer.sanitize(asset.assetName))
                row.createCell(1).setCellValue(ExcelSanitizer.sanitize(asset.assetType))
                row.createCell(2).setCellValue(ExcelSanitizer.sanitize(asset.adDomain))
                row.createCell(3).setCellValue(asset.totalOverdueCount.toDouble())
                row.createCell(4).setCellValue(asset.criticalCount.toDouble())
                row.createCell(5).setCellValue(asset.highCount.toDouble())
                row.createCell(6).setCellValue(asset.mediumCount.toDouble())
                row.createCell(7).setCellValue(asset.lowCount.toDouble())
                row.createCell(8).setCellValue(asset.oldestVulnDays.toDouble())
                row.createCell(9).setCellValue(ExcelSanitizer.sanitize(asset.oldestVulnId))
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
