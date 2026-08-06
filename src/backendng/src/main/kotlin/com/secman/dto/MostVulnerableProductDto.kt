package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * DTO for most vulnerable product statistics
 *
 * Represents a product ranked by its vulnerability count across
 * all accessible assets. Used for "Top 10 Most Vulnerable Products" display.
 *
 * Feature: 036-vuln-stats-lense
 * Spec reference: spec.md
 */
@Serdeable
data class MostVulnerableProductDto(
    /**
     * Product name/version string
     * Derived from vulnerability.vulnerableProductVersions field
     */
    val product: String,

    /**
     * Number of distinct vulnerabilities affecting this product
     */
    val vulnerabilityCount: Long,

    /**
     * Number of distinct assets with this product
     */
    val affectedAssetCount: Long,

    /**
     * Count of CRITICAL severity vulnerabilities for this product
     */
    val criticalCount: Long,

    /**
     * Count of HIGH severity vulnerabilities for this product
     */
    val highCount: Long
)
