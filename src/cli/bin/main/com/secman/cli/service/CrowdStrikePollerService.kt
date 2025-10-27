package com.secman.cli.service

import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.dto.CrowdStrikeQueryResponse
import com.secman.crowdstrike.dto.FalconConfigDto
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Service for polling CrowdStrike API for HIGH and CRITICAL vulnerabilities
 *
 * Functionality:
 * - Query all devices or specific hostnames
 * - Filter for HIGH and CRITICAL severity
 * - Store results via VulnerabilityStorageService
 * - Handle pagination and rate limiting
 *
 * Related to: Feature 026-crowdstrike-polling-monitor
 * Tasks: T5-T9
 */
@Singleton
class CrowdStrikePollerService(
    private val apiClient: CrowdStrikeApiClient,
    private val storageService: VulnerabilityStorageService
) {
    private val log = LoggerFactory.getLogger(CrowdStrikePollerService::class.java)
    
    var backendUrl: String? = null
    var dryRun: Boolean = false
    var verbose: Boolean = false
    
    /**
     * Poll all devices in CrowdStrike for HIGH/CRITICAL vulnerabilities
     *
     * Task: T5
     *
     * @param config CrowdStrike configuration
     * @return PollResult with statistics
     */
    fun pollAllDevices(config: FalconConfigDto): PollResult {
        log.info("Polling all devices for HIGH and CRITICAL vulnerabilities")
        
        // For now, we need to query specific hostnames since CrowdStrike API
        // requires device ID or hostname. In production, you would:
        // 1. First query /devices/queries/devices/v1 to get all device IDs
        // 2. Then query each device for vulnerabilities
        // This is a simplified implementation that requires hostnames
        
        log.warn("Poll all devices not fully implemented - requires hostname list")
        return PollResult(
            devicesQueried = 0,
            totalVulnerabilities = 0,
            stored = 0,
            skipped = 0,
            errors = emptyList()
        )
    }
    
    /**
     * Poll specific hostnames for HIGH/CRITICAL vulnerabilities
     *
     * Task: T5-T6
     *
     * @param hostnames List of hostnames to query
     * @param config CrowdStrike configuration
     * @return PollResult with statistics
     */
    fun pollHostnames(hostnames: List<String>, config: FalconConfigDto): PollResult {
        log.info("Polling {} hostnames for HIGH and CRITICAL vulnerabilities", hostnames.size)
        
        var devicesQueried = 0
        var totalVulnerabilities = 0
        var stored = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        
        for (hostname in hostnames) {
            try {
                log.debug("Querying hostname: {}", hostname)
                
                // Query CrowdStrike API
                val response = apiClient.queryAllVulnerabilities(hostname, config, limit = 5000)
                devicesQueried++
                
                // Filter for HIGH and CRITICAL severity (Task: T6)
                val highCriticalVulns = filterHighCritical(response)
                totalVulnerabilities += highCriticalVulns.size
                
                if (highCriticalVulns.isEmpty()) {
                    log.debug("No HIGH/CRITICAL vulnerabilities found for {}", hostname)
                    continue
                }
                
                log.info("Found {} HIGH/CRITICAL vulnerabilities for {}", highCriticalVulns.size, hostname)
                
                // Store vulnerabilities if not in dry-run mode
                if (!dryRun) {
                    val storeResult = storageService.storeVulnerabilities(
                        hostname = hostname,
                        vulnerabilities = highCriticalVulns,
                        backendUrl = backendUrl
                    )
                    stored += storeResult.stored
                    skipped += storeResult.skipped
                } else {
                    log.debug("Dry run mode - skipping storage")
                }
            } catch (e: Exception) {
                val errorMsg = "Failed to poll $hostname: ${e.message}"
                log.error(errorMsg, e)
                errors.add(errorMsg)
            }
        }
        
        log.info("Poll complete: devices={}, vulnerabilities={}, stored={}, skipped={}, errors={}",
            devicesQueried, totalVulnerabilities, stored, skipped, errors.size)
        
        return PollResult(
            devicesQueried = devicesQueried,
            totalVulnerabilities = totalVulnerabilities,
            stored = stored,
            skipped = skipped,
            errors = errors
        )
    }
    
    /**
     * Filter vulnerabilities for HIGH and CRITICAL severity
     *
     * Task: T6
     *
     * @param response CrowdStrike query response
     * @return Filtered response with only HIGH/CRITICAL vulnerabilities
     */
    private fun filterHighCritical(response: CrowdStrikeQueryResponse): List<com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto> {
        return response.vulnerabilities.filter { vuln ->
            vuln.severity.uppercase() in listOf("HIGH", "CRITICAL")
        }
    }
}

/**
 * Result of a poll operation
 */
data class PollResult(
    val devicesQueried: Int,
    val totalVulnerabilities: Int,
    val stored: Int,
    val skipped: Int,
    val errors: List<String>
)
