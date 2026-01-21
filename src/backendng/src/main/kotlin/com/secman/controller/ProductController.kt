package com.secman.controller

import com.secman.dto.PaginatedProductSystemsResponse
import com.secman.dto.ProductListResponse
import com.secman.dto.TopProductsResponse
import com.secman.service.ProductService
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.QueryValue
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.serde.annotation.Serdeable
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Controller for Products Overview feature
 *
 * Endpoints:
 * - GET /api/products - List unique products from vulnerability data
 * - GET /api/products/{product}/systems - Get systems running a specific product
 *
 * Access: ADMIN, VULN, and SECCHAMPION roles
 * Feature: 054-products-overview
 */
@Controller("/api/products")
@Secured("ADMIN", "VULN", "SECCHAMPION")
@ExecuteOn(TaskExecutors.BLOCKING)
open class ProductController(
    private val productService: ProductService
) {
    private val log = LoggerFactory.getLogger(ProductController::class.java)

    @Serdeable
    data class ErrorResponse(val error: String)

    /**
     * Get top products by vulnerability count
     *
     * GET /api/products/top?limit=15
     * Auth: ADMIN, VULN, or SECCHAMPION role
     * Response: TopProductsResponse
     *
     * Query parameters:
     * - limit: Maximum number of products to return (default: 15, max: 50)
     *
     * Non-admin users only see products from assets they have access to.
     */
    @Get("/top")
    open fun getTopProducts(
        authentication: Authentication,
        @Nullable @QueryValue limit: Int?
    ): HttpResponse<TopProductsResponse> {
        val effectiveLimit = minOf(maxOf(limit ?: 15, 1), 50)
        log.debug("Getting top {} products for user: {}", effectiveLimit, authentication.name)

        val response = productService.getTopProducts(authentication, effectiveLimit)

        log.debug("Returning {} top products", response.totalCount)
        return HttpResponse.ok(response)
    }

    /**
     * Get list of unique products from vulnerability data
     * Task: T010, T021 (search support)
     *
     * GET /api/products?search=term
     * Auth: ADMIN, VULN, or SECCHAMPION role
     * Response: ProductListResponse
     *
     * Query parameters:
     * - search: Optional search term for filtering products (case-insensitive)
     *
     * Non-admin users only see products from assets they have access to.
     */
    @Get
    open fun getProducts(
        authentication: Authentication,
        @Nullable @QueryValue search: String?
    ): HttpResponse<ProductListResponse> {
        log.debug("Getting products for user: {} (search={})", authentication.name, search)

        val response = productService.getProducts(authentication, search)

        log.debug("Returning {} products", response.totalCount)
        return HttpResponse.ok(response)
    }

    /**
     * Get paginated list of systems running a specific product
     * Task: T011
     *
     * GET /api/products/{product}/systems?page=0&size=50
     * Auth: ADMIN, VULN, or SECCHAMPION role
     * Response: PaginatedProductSystemsResponse
     *
     * Path parameters:
     * - product: URL-encoded product name
     *
     * Query parameters:
     * - page: Page number (0-indexed, default: 0)
     * - size: Page size (default: 50, max: 500)
     *
     * Non-admin users only see systems they have access to.
     */
    @Get("/{product}/systems")
    open fun getProductSystems(
        authentication: Authentication,
        @PathVariable product: String,
        @Nullable @QueryValue page: Int?,
        @Nullable @QueryValue size: Int?
    ): HttpResponse<PaginatedProductSystemsResponse> {
        // Decode URL-encoded product name
        val decodedProduct = URLDecoder.decode(product, StandardCharsets.UTF_8)

        log.debug("Getting systems for product: {} (page={}, size={})", decodedProduct, page, size)

        val response = productService.getProductSystems(
            authentication = authentication,
            product = decodedProduct,
            page = page ?: 0,
            size = size ?: 50
        )

        log.debug("Returning {} systems for product: {}", response.content.size, decodedProduct)
        return HttpResponse.ok(response)
    }

    /**
     * Export systems running a specific product to Excel
     * Task: T027
     *
     * GET /api/products/{product}/export
     * Auth: ADMIN, VULN, or SECCHAMPION role
     * Response: Excel file (.xlsx)
     *
     * Path parameters:
     * - product: URL-encoded product name
     *
     * Non-admin users only see systems they have access to.
     */
    @Get("/{product}/export")
    open fun exportProductSystems(
        authentication: Authentication,
        @PathVariable product: String
    ): HttpResponse<*> {
        return try {
            // Decode URL-encoded product name
            val decodedProduct = URLDecoder.decode(product, StandardCharsets.UTF_8)

            log.info("Product systems export request for: {} from user: {}", decodedProduct, authentication.name)

            val outputStream = productService.exportProductSystems(authentication, decodedProduct)

            // Generate safe filename (replace unsafe characters)
            val safeProductName = decodedProduct
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .take(50)  // Limit filename length
            val dateStr = java.time.LocalDate.now().toString()
            val filename = "product_systems_${safeProductName}_$dateStr.xlsx"

            log.info("Product systems export successful for: {} by user: {}", decodedProduct, authentication.name)

            HttpResponse.ok(outputStream.toByteArray())
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header("Content-Disposition", "attachment; filename=\"$filename\"")

        } catch (e: Exception) {
            log.error("Product systems export failed for user: {}", authentication.name, e)
            HttpResponse.serverError<ErrorResponse>()
                .body(ErrorResponse("Failed to export product systems: ${e.message}"))
        }
    }
}
