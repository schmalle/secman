package com.secman.crowdstrike.client

import com.secman.crowdstrike.dto.CrowdStrikeQueryResponse
import com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto
import com.secman.crowdstrike.dto.InstalledProductDto
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.model.AuthToken

/**
 * Summary of a streaming vulnerability query, retaining only hostname→count statistics.
 * Used for dry-run/display mode to avoid loading all vulnerability DTOs into memory.
 */
data class StreamingSummary(
    val totalVulnerabilities: Int,
    val hostCounts: Map<String, Int>,  // hostname -> vuln count
    val hostsWithOverdueVulns: Int = 0  // hosts with vulns open > overdueThreshold days
)

/**
 * One device from a streaming import run's Stage-1 queried population. Carries the
 * identifiers the backend uses to resolve it to persisted assets. Includes hosts that
 * returned zero matching vulnerabilities — those are exactly the fully-remediated hosts
 * the stale-reconcile sweep must clean up.
 */
data class QueriedHost(
    val hostname: String?,
    val instanceId: String?
)

/**
 * Result of [CrowdStrikeApiClient.queryServersWithFiltersStreaming]: the total number of
 * vulnerabilities streamed to the batch processor, plus the FULL set of devices this run
 * queried (Stage-1 population, including zero-vuln hosts). The caller forwards
 * [queriedHosts] to the backend so the stale-reconcile sweep is scoped to only the hosts
 * this run actually touched — a host outside `--last-seen-days` is never in this set and
 * so is never wiped.
 */
data class StreamingImportResult(
    val totalVulnerabilities: Int,
    val queriedHosts: Set<QueriedHost>
)

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
     * Query all vulnerabilities for a single hostname.
     *
     * The per-host Spotlight endpoint returns all of a device's vulnerabilities in a single
     * response, so this method does not accept a caller-supplied page size. Callers that need
     * a cap on results should slice the returned list themselves.
     *
     * Task: T032
     *
     * @param hostname System hostname to query
     * @param config CrowdStrike Falcon configuration
     * @return CrowdStrikeQueryResponse with all vulnerabilities
     */
    fun queryAllVulnerabilities(
        hostname: String,
        config: FalconConfigDto
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
     * @return [StreamingImportResult] with the total vulnerability count and the full
     *         Stage-1 queried device population (including zero-vuln hosts)
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
    ): StreamingImportResult

    /**
     * Query servers with filters in streaming batches but only retain summary statistics
     * (hostname → vulnerability count). Used for dry-run/display mode to avoid holding
     * all vulnerability DTOs in memory.
     *
     * @param deviceType Device type filter (e.g., "SERVER")
     * @param severity Severity filter (e.g., "HIGH,CRITICAL")
     * @param minDaysOpen Minimum days open filter (e.g., 30)
     * @param config CrowdStrike Falcon configuration
     * @param limit Page size for pagination
     * @param lastSeenDays Only include devices seen within N days (0 = all)
     * @param deviceBatchSize Number of device IDs to process per streaming batch
     * @return StreamingSummary with total count and per-host counts
     */
    fun queryServersWithFiltersSummary(
        deviceType: String = "SERVER",
        severity: String = "HIGH,CRITICAL",
        minDaysOpen: Int = 30,
        config: FalconConfigDto,
        limit: Int = 100,
        lastSeenDays: Int = 0,
        deviceBatchSize: Int = 200,
        overdueThreshold: Int = 30
    ): StreamingSummary

    /**
     * Query installed products from CrowdStrike Discover in streaming pages.
     *
     * @param deviceType Device type filter (SERVER, WORKSTATION, or ALL)
     * @param config CrowdStrike Falcon configuration
     * @param limit Page size for pagination (max 1000)
     * @param batchProcessor Callback invoked with each page of installed products
     * @return Total number of installed product rows processed
     */
    fun queryInstalledProductsStreaming(
        deviceType: String = "SERVER",
        config: FalconConfigDto,
        limit: Int = 1000,
        batchProcessor: (List<InstalledProductDto>) -> Unit
    ): Int
}
