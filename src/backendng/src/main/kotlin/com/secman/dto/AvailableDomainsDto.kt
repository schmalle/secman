package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * DTO for available domains endpoint response
 *
 * Returns list of unique AD domains from assets the user has access to.
 * Used to populate the domain selector dropdown on the vulnerability statistics page.
 *
 * Feature: 059-vuln-stats-domain-filter
 * Task: T001
 * Spec reference: spec.md FR-002
 * Contract: contracts/domain-filter-api.yaml
 * Data model: data-model.md Section "AvailableDomainsDto"
 */
@Serdeable
data class AvailableDomainsDto(
    /**
     * Sorted list of unique domain names (lowercase, alphabetically sorted)
     * Never null; may be empty if no domains are available
     */
    val domains: List<String>,

    /**
     * Total count of assets with domains that the user has access to
     */
    val totalAssetCount: Int
)
