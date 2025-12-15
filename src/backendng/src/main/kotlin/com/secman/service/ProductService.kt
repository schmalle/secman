package com.secman.service

import com.secman.dto.PaginatedProductSystemsResponse
import com.secman.dto.ProductListResponse
import com.secman.dto.ProductSystemDto
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.data.model.Pageable
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

/**
 * Service for product overview operations
 * Aggregates vulnerability data to provide product-centric views
 *
 * Feature: 054-products-overview
 *
 * Products are extracted from the vulnerableProductVersions field in vulnerability data.
 * Access control is applied using AssetFilterService to ensure users only see
 * products and systems they have access to.
 */
@Singleton
open class ProductService(
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val assetRepository: AssetRepository,
    private val assetFilterService: AssetFilterService
) {
    private val log = LoggerFactory.getLogger(ProductService::class.java)

    /**
     * Get list of unique products from vulnerability data
     * Task: T008, T020 (search support)
     *
     * @param authentication Current user authentication
     * @param search Optional search term for filtering products (case-insensitive)
     * @return ProductListResponse with unique product names sorted alphabetically
     */
    fun getProducts(authentication: Authentication, search: String? = null): ProductListResponse {
        val isAdmin = hasRole(authentication, "ADMIN")
        val searchTerm = search?.trim()?.takeIf { it.isNotEmpty() }

        val products = if (isAdmin) {
            if (searchTerm != null) {
                log.debug("Admin user - fetching products matching: {}", searchTerm)
                vulnerabilityRepository.findDistinctProductsForAllFiltered(searchTerm)
            } else {
                log.debug("Admin user - fetching all products")
                vulnerabilityRepository.findDistinctProductsForAll()
            }
        } else {
            val accessibleAssetIds = assetFilterService.getAccessibleAssets(authentication)
                .mapNotNull { it.id }
                .toSet()

            if (accessibleAssetIds.isEmpty()) {
                log.debug("User has no accessible assets - returning empty product list")
                return ProductListResponse(products = emptyList(), totalCount = 0)
            }

            if (searchTerm != null) {
                log.debug("Non-admin user - fetching products matching: {} from {} accessible assets", searchTerm, accessibleAssetIds.size)
                vulnerabilityRepository.findDistinctProductsForAssetsFiltered(accessibleAssetIds, searchTerm)
            } else {
                log.debug("Non-admin user - fetching products for {} accessible assets", accessibleAssetIds.size)
                vulnerabilityRepository.findDistinctProductsForAssets(accessibleAssetIds)
            }
        }

        return ProductListResponse(
            products = products,
            totalCount = products.size
        )
    }

    /**
     * Get paginated list of systems (assets) running a specific product
     * Task: T009
     *
     * @param authentication Current user authentication
     * @param product Product name to search for
     * @param page Page number (0-indexed)
     * @param size Page size (default 50)
     * @return PaginatedProductSystemsResponse with systems running the product
     */
    fun getProductSystems(
        authentication: Authentication,
        product: String,
        page: Int = 0,
        size: Int = 50
    ): PaginatedProductSystemsResponse {
        val pageNumber = maxOf(page, 0)
        val pageSize = minOf(maxOf(size, 1), 500)
        val pageable = Pageable.from(pageNumber, pageSize)

        val isAdmin = hasRole(authentication, "ADMIN")

        val assetsPage = if (isAdmin) {
            log.debug("Admin user - fetching all systems for product: {}", product)
            assetRepository.findAssetsByProductForAll(product, pageable)
        } else {
            val accessibleAssetIds = assetFilterService.getAccessibleAssets(authentication)
                .mapNotNull { it.id }
                .toSet()

            if (accessibleAssetIds.isEmpty()) {
                log.debug("User has no accessible assets - returning empty systems list")
                return PaginatedProductSystemsResponse(
                    content = emptyList(),
                    totalElements = 0,
                    totalPages = 0,
                    currentPage = pageNumber,
                    pageSize = pageSize,
                    hasNext = false,
                    hasPrevious = false,
                    productName = product
                )
            }

            log.debug("Non-admin user - fetching systems for product: {} from {} accessible assets", product, accessibleAssetIds.size)
            assetRepository.findAssetsByProductWithAccessControl(product, accessibleAssetIds, pageable)
        }

        val content = assetsPage.content.map { asset ->
            ProductSystemDto(
                assetId = asset.id!!,
                name = asset.name,
                ip = asset.ip,
                adDomain = asset.adDomain
            )
        }

        val totalPages = if (assetsPage.totalSize > 0) {
            ceil(assetsPage.totalSize.toDouble() / pageSize).toInt()
        } else {
            0
        }

        return PaginatedProductSystemsResponse(
            content = content,
            totalElements = assetsPage.totalSize,
            totalPages = totalPages,
            currentPage = pageNumber,
            pageSize = pageSize,
            hasNext = assetsPage.hasNext(),
            hasPrevious = pageNumber > 0,
            productName = product
        )
    }

    /**
     * Export all systems running a specific product to Excel format
     * Task: T025
     *
     * @param authentication Current user authentication
     * @param product Product name to export systems for
     * @return ByteArrayOutputStream containing Excel workbook
     */
    fun exportProductSystems(
        authentication: Authentication,
        product: String
    ): ByteArrayOutputStream {
        val isAdmin = hasRole(authentication, "ADMIN")

        // Get all systems (without pagination limit)
        val systems = if (isAdmin) {
            log.debug("Admin user - exporting all systems for product: {}", product)
            assetRepository.findAssetsByProductForAllNoLimit(product)
        } else {
            val accessibleAssetIds = assetFilterService.getAccessibleAssets(authentication)
                .mapNotNull { it.id }
                .toSet()

            if (accessibleAssetIds.isEmpty()) {
                log.debug("User has no accessible assets - returning empty export")
                return writeProductSystemsToExcel(emptyList(), product)
            }

            log.debug("Non-admin user - exporting systems for product: {} from {} accessible assets", product, accessibleAssetIds.size)
            assetRepository.findAssetsByProductWithAccessControlNoLimit(product, accessibleAssetIds)
        }

        val systemDtos = systems.map { asset ->
            ProductSystemDto(
                assetId = asset.id!!,
                name = asset.name,
                ip = asset.ip,
                adDomain = asset.adDomain
            )
        }

        log.info("Exporting {} systems for product: {}", systemDtos.size, product)
        return writeProductSystemsToExcel(systemDtos, product)
    }

    /**
     * Write product systems to Excel workbook
     * Task: T026
     *
     * @param systems List of ProductSystemDto to export
     * @param productName Product name for the export
     * @return ByteArrayOutputStream containing Excel workbook
     */
    private fun writeProductSystemsToExcel(
        systems: List<ProductSystemDto>,
        productName: String
    ): ByteArrayOutputStream {
        log.debug("Writing {} systems to Excel format", systems.size)

        val workbook = SXSSFWorkbook(100)
        workbook.setCompressTempFiles(true)

        try {
            val sheet = workbook.createSheet("Systems")

            // Create header style
            val headerStyle = workbook.createCellStyle().apply {
                fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
                val font = workbook.createFont()
                font.bold = true
                setFont(font)
            }

            // Write header row
            val headerRow = sheet.createRow(0)
            val headers = listOf("System Name", "IP Address", "Domain")
            headers.forEachIndexed { index, header ->
                headerRow.createCell(index).apply {
                    setCellValue(header)
                    cellStyle = headerStyle
                }
            }

            // Write data rows
            systems.forEachIndexed { index, system ->
                val row = sheet.createRow(index + 1)
                row.createCell(0).setCellValue(system.name)
                row.createCell(1).setCellValue(system.ip ?: "")
                row.createCell(2).setCellValue(system.adDomain ?: "")
            }

            // Set fixed column widths
            sheet.setColumnWidth(0, 40 * 256)  // System Name - 40 chars
            sheet.setColumnWidth(1, 20 * 256)  // IP Address - 20 chars
            sheet.setColumnWidth(2, 30 * 256)  // Domain - 30 chars

            // Write to output stream
            val outputStream = ByteArrayOutputStream()
            workbook.write(outputStream)
            return outputStream

        } finally {
            workbook.dispose()
        }
    }

    /**
     * Check if authentication has a specific role
     */
    private fun hasRole(authentication: Authentication, role: String): Boolean {
        return authentication.roles.contains(role)
    }
}
