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
 */
export interface CrowdStrikeQueryResponse {
    hostname: string;
    vulnerabilities: CrowdStrikeVulnerabilityDto[];
    totalCount: number;
    queriedAt: string;
}

/**
 * Save Request
 * Matches backend CrowdStrikeSaveRequest
 */
export interface CrowdStrikeSaveRequest {
    hostname: string;
    vulnerabilities: CrowdStrikeVulnerabilityDto[];
}

/**
 * Save Response
 * Matches backend CrowdStrikeSaveResponse
 * Feature 030 - CrowdStrike Asset Auto-Creation
 */
export interface CrowdStrikeSaveResponse {
    message: string;
    vulnerabilitiesSaved: number;
    vulnerabilitiesSkipped: number;  // Feature 030: Added for T014
    assetsCreated: number;
    errors: string[];
}

/**
 * Query CrowdStrike for system vulnerabilities
 *
 * Task: T030 [US1-Impl]
 *
 * @param hostname System hostname to query
 * @returns Query response with vulnerabilities
 * @throws Error if authentication fails, hostname not found, rate limit exceeded, or API error
 */
export async function queryVulnerabilities(
    hostname: string
): Promise<CrowdStrikeQueryResponse> {
    console.log('[CrowdStrikeService] queryVulnerabilities called');
    console.log('[CrowdStrikeService] Hostname:', hostname);

    // Get JWT token from localStorage
    const token = localStorage.getItem('authToken');
    console.log('[CrowdStrikeService] JWT token present:', !!token);

    if (!token) {
        console.error('[CrowdStrikeService] No JWT token found in localStorage');
        throw new Error('Not authenticated. Please log in.');
    }

    const url = `/api/crowdstrike/vulnerabilities?hostname=${encodeURIComponent(hostname)}`;
    console.log('[CrowdStrikeService] Calling API:', url);

    try {
        const response = await fetch(url, {
            method: 'GET',
            headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json'
            }
        });

        console.log('[CrowdStrikeService] Response status:', response.status);
        console.log('[CrowdStrikeService] Response ok:', response.ok);

        // Handle specific error cases
        if (!response.ok) {
            // Try to parse error response
            try {
                const errorData = await response.json();
                console.error('[CrowdStrikeService] Error response:', errorData);
                const errorMessage = errorData.error || `Query failed with status ${response.status}`;

                // Handle specific HTTP status codes
                if (response.status === 401) {
                    // Redirect to login
                    window.location.href = '/login';
                    throw new Error('Session expired. Please log in again.');
                } else if (response.status === 403) {
                    throw new Error('Insufficient permissions. ADMIN or VULN role required.');
                } else if (response.status === 404) {
                    throw new Error(`System '${hostname}' not found in CrowdStrike`);
                } else if (response.status === 429) {
                    const retryAfter = response.headers.get('Retry-After') || '30';
                    throw new Error(`Rate limit exceeded. Try again in ${retryAfter} seconds.`);
                } else if (response.status === 500) {
                    throw new Error(errorMessage);
                }

                throw new Error(errorMessage);
            } catch (e) {
                if (e instanceof Error && e.message !== `Query failed with status ${response.status}`) {
                    throw e;
                }
                console.error('[CrowdStrikeService] Failed to parse error:', e);
                throw new Error(`Query failed with status ${response.status}`);
            }
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

    // Get JWT token from localStorage
    const token = localStorage.getItem('authToken');
    console.log('[CrowdStrikeService] JWT token present:', !!token);

    if (!token) {
        console.error('[CrowdStrikeService] No JWT token found in localStorage');
        throw new Error('Not authenticated. Please log in.');
    }

    const url = '/api/crowdstrike/vulnerabilities/save';
    console.log('[CrowdStrikeService] Calling API:', url);

    try {
        const response = await fetch(url, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(request)
        });

        console.log('[CrowdStrikeService] Response status:', response.status);
        console.log('[CrowdStrikeService] Response ok:', response.ok);

        if (!response.ok) {
            // Try to parse error response
            try {
                const errorData = await response.json();
                console.error('[CrowdStrikeService] Error response:', errorData);
                const errorMessage = errorData.error || `Save failed with status ${response.status}`;

                // Handle specific HTTP status codes
                if (response.status === 401) {
                    window.location.href = '/login';
                    throw new Error('Session expired. Please log in again.');
                } else if (response.status === 403) {
                    throw new Error('Insufficient permissions. ADMIN or VULN role required.');
                } else if (response.status === 400) {
                    throw new Error(`Validation error: ${errorMessage}`);
                } else if (response.status === 500) {
                    throw new Error(errorMessage);
                }

                throw new Error(errorMessage);
            } catch (e) {
                if (e instanceof Error && e.message !== `Save failed with status ${response.status}`) {
                    throw e;
                }
                console.error('[CrowdStrikeService] Failed to parse error:', e);
                throw new Error(`Save failed with status ${response.status}`);
            }
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
