package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDateTime

/**
 * DTO for scan upload response
 *
 * Returned by POST /api/scan/upload-nmap
 *
 * Related to:
 * - Feature: 002-implement-a-parsing (Nmap Scan Import)
 * - Contract: specs/002-implement-a-parsing/contracts/upload-nmap.yaml
 * - FR-002: Automatically create assets
 */
@Serdeable
data class ScanSummaryDTO(
    /**
     * ID of the created scan record
     */
    val scanId: Long,

    /**
     * Original uploaded filename
     */
    val filename: String,

    /**
     * When the scan was performed (from XML)
     * ISO 8601 format
     */
    val scanDate: LocalDateTime,

    /**
     * Number of hosts discovered in the scan
     */
    val hostsDiscovered: Int,

    /**
     * Number of new assets created from this scan
     */
    val assetsCreated: Int,

    /**
     * Number of existing assets updated with new scan data
     */
    val assetsUpdated: Int,

    /**
     * Total number of ports imported across all hosts
     */
    val totalPorts: Int,

    /**
     * Scan/import duration
     * Format: "Xs" or "Xm Ys"
     * Examples: "5s", "2m 30s"
     */
    val duration: String
)
