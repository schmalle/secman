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
        limit: Int = 100,
        lastSeenDays: Int = 0
    ): CrowdStrikeQueryResponse

    /**
     * Get the authorization token
     *
     * @param config CrowdStrike Falcon configuration
     * @return AuthToken for API requests
     */
    fun getAuthToken(config: FalconConfigDto): AuthToken

    /**
     * Query vulnerabilities by AWS EC2 Instance ID
     *
     * Feature: 041-falcon-instance-lookup
     * Task: T011
     *
     * @param instanceId AWS EC2 Instance ID (format: i-XXXXXXXXX...)
     * @param config CrowdStrike Falcon configuration
     * @return CrowdStrikeQueryResponse with vulnerabilities from all devices with this instance ID
     */
    fun queryVulnerabilitiesByInstanceId(instanceId: String, config: FalconConfigDto): CrowdStrikeQueryResponse

    /**
     * Query vulnerabilities by Active Directory domains
     *
     * Feature: 042-domain-vulnerabilities-view
     *
     * @param domains List of AD domain names (e.g., ["CONTOSO", "EXAMPLE"])
     * @param severity Severity filter (e.g., "HIGH,CRITICAL")
     * @param minDaysOpen Minimum days open filter
     * @param config CrowdStrike Falcon configuration
     * @param limit Page size for pagination
     * @return CrowdStrikeQueryResponse with vulnerabilities from all devices in these domains
     */
    fun queryVulnerabilitiesByDomains(
        domains: List<String>,
        severity: String = "HIGH,CRITICAL",
        minDaysOpen: Int = 0,
        config: FalconConfigDto,
        limit: Int = 1000
    ): CrowdStrikeQueryResponse

    /**
     * Query servers with filters, processing results in streaming batches to reduce peak memory usage.
     *
     * Instead of accumulating all vulnerabilities in memory, this method processes device ID
     * batches incrementally and passes each batch's results to the batchProcessor callback.
     * This reduces peak memory from O(all_vulns) to O(batch_vulns).
     *
     * @param deviceType Device type filter (e.g., "SERVER")
     * @param severity Severity filter (e.g., "HIGH,CRITICAL")
     * @param minDaysOpen Minimum days open filter (e.g., 30)
     * @param config CrowdStrike Falcon configuration
     * @param limit Page size for pagination
     * @param lastSeenDays Only include devices seen within N days (0 = all)
     * @param deviceBatchSize Number of device IDs to process per streaming batch
     * @param batchProcessor Callback invoked with each batch of filtered vulnerabilities
     * @return Total number of vulnerabilities processed across all batches
     */
    fun queryServersWithFiltersStreaming(
        deviceType: String = "SERVER",
        severity: String = "HIGH,CRITICAL",
        minDaysOpen: Int = 30,
        config: FalconConfigDto,
        limit: Int = 100,
        lastSeenDays: Int = 0,
        deviceBatchSize: Int = 200,
        batchProcessor: (List<CrowdStrikeVulnerabilityDto>) -> Unit
    ): Int
}
