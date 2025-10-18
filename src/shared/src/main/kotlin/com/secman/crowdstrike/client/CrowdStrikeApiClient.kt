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
     * Get the authorization token
     *
     * @param config CrowdStrike Falcon configuration
     * @return AuthToken for API requests
     */
    fun getAuthToken(config: FalconConfigDto): AuthToken
}
