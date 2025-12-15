package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Response DTO for GET /api/products endpoint
 * Contains list of unique product names from vulnerability data
 *
 * Related to: Feature 054-products-overview
 */
@Serdeable
data class ProductListResponse(
    val products: List<String>,      // Distinct product names, alphabetically sorted
    val totalCount: Int              // Total number of unique products
)

/**
 * DTO representing a system (asset) running a specific product
 *
 * Related to: Feature 054-products-overview
 */
@Serdeable
data class ProductSystemDto(
    val assetId: Long,
    val name: String,                // Asset name
    val ip: String?,                 // IP address (nullable)
    val adDomain: String?            // AD domain (nullable)
)

/**
 * Paginated response DTO for GET /api/products/{product}/systems endpoint
 *
 * Related to: Feature 054-products-overview
 */
@Serdeable
data class PaginatedProductSystemsResponse(
    val content: List<ProductSystemDto>,
    val totalElements: Long,
    val totalPages: Int,
    val currentPage: Int,
    val pageSize: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val productName: String          // Echo back the selected product
)
