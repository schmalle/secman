package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDateTime

/**
 * DTO for asset port history response
 *
 * Returned by GET /api/assets/{id}/ports
 *
 * Related to:
 * - Feature: 002-implement-a-parsing (Nmap Scan Import)
 * - Contract: specs/002-implement-a-parsing/contracts/asset-ports.yaml
 * - FR-011: Display port scan history
 */
@Serdeable
data class PortHistoryDTO(
    /**
     * Asset ID
     */
    val assetId: Long,

    /**
     * Asset name
     */
    val assetName: String,

    /**
     * List of scans for this asset, ordered by scanDate DESC (newest first)
     */
    val scans: List<ScanPortsDTO>
)

/**
 * Port data grouped by scan
 */
@Serdeable
data class ScanPortsDTO(
    /**
     * Scan ID
     */
    val scanId: Long,

    /**
     * When this scan was performed
     * ISO 8601 format
     */
    val scanDate: LocalDateTime,

    /**
     * Scan type (e.g., "nmap", "masscan")
     */
    val scanType: String,

    /**
     * List of ports discovered in this scan
     */
    val ports: List<PortDTO>
)

/**
 * Individual port data
 */
@Serdeable
data class PortDTO(
    /**
     * Port number (1-65535)
     */
    val portNumber: Int,

    /**
     * Protocol ("tcp" or "udp")
     */
    val protocol: String,

    /**
     * Port state ("open", "filtered", "closed")
     */
    val state: String,

    /**
     * Service name (e.g., "http", "ssh")
     * Nullable - may not be detected
     */
    val service: String?,

    /**
     * Service version (e.g., "nginx 1.18.0")
     * Nullable - may not be detected
     */
    val version: String?
)
