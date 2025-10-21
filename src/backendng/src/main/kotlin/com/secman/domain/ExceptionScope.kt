package com.secman.domain

/**
 * Exception scope enumeration for vulnerability exception requests.
 *
 * Determines the applicability of an approved exception:
 * - SINGLE_VULNERABILITY: Exception applies only to the specific vulnerability on the specific asset
 * - CVE_PATTERN: Exception applies to all vulnerabilities with the same CVE across all assets
 *
 * Related to: Feature 031-vuln-exception-approval (User Story 4 - Flexible Exception Scope)
 *
 * When approved:
 * - SINGLE_VULNERABILITY → Creates ASSET-type VulnerabilityException (asset_id = request.vulnerability.asset.id)
 * - CVE_PATTERN → Creates PRODUCT-type VulnerabilityException (target_value = request.vulnerability.cveId)
 */
enum class ExceptionScope {
    /**
     * Exception applies only to this specific vulnerability on this specific asset
     * Example: CVE-2022-0001 on server-prod-01
     */
    SINGLE_VULNERABILITY,

    /**
     * Exception applies to all vulnerabilities with this CVE across all assets
     * Example: All instances of CVE-2022-0001 on any asset
     */
    CVE_PATTERN
}
