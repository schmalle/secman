package com.secman.crowdstrike.client

import com.secman.crowdstrike.dto.CrowdStrikeQueryResponse
import com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.model.AuthToken

/**
 * Interface for CrowdStrike Falcon API client
 *
 * Defines contract for querying CrowdStrike Spotlight API for vulnerabilities
 */
interface CrowdStrikeApiClient {
    /**
     * Query vulnerabilities for a specific hostname
     *
     * Task: T030
     *
     * @param hostname System hostname to query
     * @param config CrowdStrike Falcon configuration
     * @return CrowdStrikeQueryResponse with vulnerabilities
     */
    fun queryVulnerabilities(hostname: String, config: FalconConfigDto): CrowdStrikeQueryResponse

    /**
     * Query all vulnerabilities with automatic pagination
     *
     * Task: T032
     *
     * @param hostname System hostname to query
     * @param config CrowdStrike Falcon configuration
     * @param limit Page size (default: 100)
     * @return CrowdStrikeQueryResponse with all vulnerabilities
     */
    fun queryAllVulnerabilities(
        hostname: String,
        config: FalconConfigDto,
        limit: Int = 100
    ): CrowdStrikeQueryResponse

    /**
     * Query servers with filters (device type, severity, days open)
     *
     * Feature: 032-servers-query-import
     * Task: T007, T008
     * Spec reference: FR-002, FR-003, FR-004, FR-001a
     *
     * @param hostnames Optional list of specific hostnames to query (null = all servers)
     * @param deviceType Device type filter (e.g., "SERVER")
     * @param severity Severity filter (e.g., "HIGH,CRITICAL")
     * @param minDaysOpen Minimum days open filter (e.g., 30)
     * @param config CrowdStrike Falcon configuration
     * @param limit Page size for pagination
     * @return CrowdStrikeQueryResponse with filtered vulnerabilities
     */
    fun queryServersWithFilters(
        hostnames: List<String>? = null,
        deviceType: String = "SERVER",
        severity: String = "HIGH,CRITICAL",
        minDaysOpen: Int = 30,
        config: FalconConfigDto,
        limit: Int = 100
    ): CrowdStrikeQueryResponse

    /**
     * Get the authorization token
     *
     * @param config CrowdStrike Falcon configuration
     * @return AuthToken for API requests
     */
    fun getAuthToken(config: FalconConfigDto): AuthToken
}
