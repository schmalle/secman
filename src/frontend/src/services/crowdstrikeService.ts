/**
 * CrowdStrike Service
 *
 * Handles API calls for CrowdStrike Falcon vulnerability lookup functionality
 *
 * Related to: Feature 015-we-have-currently (CrowdStrike System Vulnerability Lookup)
 * Tasks: T029-T030 [US1-Impl], T052-T054 [US3-Impl]
 */

/**
 * CrowdStrike Vulnerability DTO
 * Matches backend CrowdStrikeVulnerabilityDto
 */
export interface CrowdStrikeVulnerabilityDto {
    id: string;
    hostname: string;
    ip: string | null;
    cveId: string | null;
    severity: string;
    cvssScore: number | null;
    affectedProduct: string | null;
    daysOpen: string | null;
    detectedAt: string;
    status: string;
    hasException: boolean;
    exceptionReason: string | null;
}

/**
 * Query Response
 * Matches backend CrowdStrikeQueryResponse
 * Feature: 041-falcon-instance-lookup
 */
export interface CrowdStrikeQueryResponse {
    hostname: string;
    instanceId?: string | null;
    deviceCount?: number | null;
    vulnerabilities: CrowdStrikeVulnerabilityDto[];
    totalCount: number;
    queriedAt: string;
}

/**
 * Save Request
 * Matches backend CrowdStrikeSaveRequest
 * Feature: 041-falcon-instance-lookup
 */
export interface CrowdStrikeSaveRequest {
    hostname: string;
    vulnerabilities: CrowdStrikeVulnerabilityDto[];
    instanceId?: string; // T050: Optional instance ID for asset enrichment
}

/**
 * Save Response
 * Matches backend CrowdStrikeSaveResponse
 * Feature 030 - CrowdStrike Asset Auto-Creation
 * Feature 043 - CrowdStrike Domain Import (added domain statistics)
 */
export interface CrowdStrikeSaveResponse {
    message: string;
    vulnerabilitiesSaved: number;
    vulnerabilitiesSkipped: number;  // Feature 030: Added for T014
    assetsCreated: number;
    uniqueDomainCount?: number;  // Feature 043: Number of unique domains discovered
    discoveredDomains?: string[];  // Feature 043: List of unique domain names
    errors: string[];
}

/**
 * Query CrowdStrike for system vulnerabilities
 *
 * Feature: 041-falcon-instance-lookup
 * Task: T030 [US1-Impl], T036 [US3-Impl]
 *
 * @param hostname System hostname or AWS instance ID to query
 * @param force If true, bypass cache and fetch fresh data (Feature 041, Task T035)
 * @returns Query response with vulnerabilities
 * @throws Error if authentication fails, hostname not found, rate limit exceeded, or API error
 */
export async function queryVulnerabilities(
    hostname: string,
    force: boolean = false
): Promise<CrowdStrikeQueryResponse> {
    console.log('[CrowdStrikeService] queryVulnerabilities called');
    console.log('[CrowdStrikeService] Hostname:', hostname);
    console.log('[CrowdStrikeService] Force refresh:', force);

    // Use new endpoint that supports both hostname and instance ID (Feature 041)
    const forceParam = force ? '&force=true' : '';
    const url = `/api/vulnerabilities?hostname=${encodeURIComponent(hostname)}${forceParam}`;
    console.log('[CrowdStrikeService] Calling API:', url);

    try {
        const response = await fetch(url, {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            },
            credentials: 'include'  // Send HttpOnly cookie for authentication
        });

        console.log('[CrowdStrikeService] Response status:', response.status);
        console.log('[CrowdStrikeService] Response ok:', response.ok);

        // Handle specific error cases
        if (!response.ok) {
            // Handle specific HTTP status codes first (before trying to parse body)
            if (response.status === 401) {
                window.location.href = '/login';
                throw new Error('Session expired. Please log in again.');
            } else if (response.status === 403) {
                throw new Error('Insufficient permissions. ADMIN or VULN role required.');
            } else if (response.status === 404) {
                const isInstanceId = hostname.trim().toLowerCase().startsWith('i-');
                const identifier = isInstanceId ? 'instance ID' : 'hostname';
                throw new Error(`System not found with ${identifier}: ${hostname}`);
            } else if (response.status === 429) {
                const retryAfter = response.headers.get('Retry-After') || '30';
                throw new Error(`Rate limit exceeded. Try again in ${retryAfter} seconds.`);
            }

            // Try to parse error response as JSON
            const contentType = response.headers.get('content-type');
            if (contentType && contentType.includes('application/json')) {
                try {
                    const errorData = await response.json();
                    console.error('[CrowdStrikeService] Error response:', errorData);
                    const errorMessage = errorData.error || errorData.message || `Query failed with status ${response.status}`;
                    throw new Error(errorMessage);
                } catch (e) {
                    if (e instanceof Error && !e.message.includes('Query failed')) {
                        throw e;
                    }
                    console.error('[CrowdStrikeService] Failed to parse JSON error:', e);
                }
            } else {
                // Non-JSON response (likely HTML error page)
                const text = await response.text();
                console.error('[CrowdStrikeService] Non-JSON error response:', text.substring(0, 200));

                // Extract meaningful error from HTML if possible
                if (response.status === 500 || response.status === 502 || response.status === 503) {
                    throw new Error('CrowdStrike API error: Server temporarily unavailable. Please try again.');
                } else if (response.status === 504) {
                    throw new Error('CrowdStrike API error: Request timed out. The system may have too many vulnerabilities. Please try again.');
                }
            }

            throw new Error(`CrowdStrike API error: Failed to query Spotlight API: Read Timeout`);
        }

        // Parse successful response
        const contentType = response.headers.get('content-type');
        if (!contentType || !contentType.includes('application/json')) {
            const text = await response.text();
            console.error('[CrowdStrikeService] Unexpected non-JSON success response:', text.substring(0, 200));
            throw new Error('Unexpected response format from server');
        }

        const result = await response.json();
        console.log('[CrowdStrikeService] Success response:', result);
        console.log('[CrowdStrikeService] Vulnerabilities count:', result.totalCount);
        return result;
    } catch (error) {
        console.error('[CrowdStrikeService] Fetch error:', error);
        throw error;
    }
}

/**
 * Save CrowdStrike vulnerabilities to database
 *
 * Task: T054 [US3-Impl]
 *
 * @param request Save request with hostname and vulnerabilities
 * @returns Save response with results
 * @throws Error if authentication fails, validation fails, or database error
 */
export async function saveVulnerabilities(
    request: CrowdStrikeSaveRequest
): Promise<CrowdStrikeSaveResponse> {
    console.log('[CrowdStrikeService] saveVulnerabilities called');
    console.log('[CrowdStrikeService] Hostname:', request.hostname);
    console.log('[CrowdStrikeService] Vulnerabilities count:', request.vulnerabilities.length);

    const url = '/api/crowdstrike/vulnerabilities/save';
    console.log('[CrowdStrikeService] Calling API:', url);

    try {
        const response = await fetch(url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            credentials: 'include',  // Send HttpOnly cookie for authentication
            body: JSON.stringify(request)
        });

        console.log('[CrowdStrikeService] Response status:', response.status);
        console.log('[CrowdStrikeService] Response ok:', response.ok);

        if (!response.ok) {
            // Handle specific HTTP status codes first
            if (response.status === 401) {
                window.location.href = '/login';
                throw new Error('Session expired. Please log in again.');
            } else if (response.status === 403) {
                throw new Error('Insufficient permissions. ADMIN or VULN role required.');
            }

            // Try to parse error response as JSON
            const contentType = response.headers.get('content-type');
            if (contentType && contentType.includes('application/json')) {
                try {
                    const errorData = await response.json();
                    console.error('[CrowdStrikeService] Error response:', errorData);
                    const errorMessage = errorData.error || errorData.message || `Save failed with status ${response.status}`;

                    if (response.status === 400) {
                        throw new Error(`Validation error: ${errorMessage}`);
                    }
                    throw new Error(errorMessage);
                } catch (e) {
                    if (e instanceof Error && !e.message.includes('Save failed')) {
                        throw e;
                    }
                    console.error('[CrowdStrikeService] Failed to parse JSON error:', e);
                }
            } else {
                // Non-JSON response (likely HTML error page)
                const text = await response.text();
                console.error('[CrowdStrikeService] Non-JSON error response:', text.substring(0, 200));
            }

            throw new Error(`Save failed with status ${response.status}`);
        }

        // Parse successful response
        const contentType = response.headers.get('content-type');
        if (!contentType || !contentType.includes('application/json')) {
            const text = await response.text();
            console.error('[CrowdStrikeService] Unexpected non-JSON success response:', text.substring(0, 200));
            throw new Error('Unexpected response format from server');
        }

        const result = await response.json();
        console.log('[CrowdStrikeService] Success response:', result);
        console.log('[CrowdStrikeService] Vulnerabilities saved:', result.vulnerabilitiesSaved);
        console.log('[CrowdStrikeService] Assets created:', result.assetsCreated);
        return result;
    } catch (error) {
        console.error('[CrowdStrikeService] Fetch error:', error);
        throw error;
    }
}
