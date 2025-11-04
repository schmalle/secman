import { authenticatedGet } from '../utils/auth';
import type { CrowdStrikeImportStatus } from '../types/crowdstrike';

/**
 * Service for WG Vulns (Workgroup Vulnerabilities) API operations
 * Feature: 022-wg-vulns-handling
 */

/**
 * Single asset with its vulnerability count
 * Reuses the same interface as Account Vulns for consistency
 */
export interface AssetVulnCount {
  id: number;
  name: string;
  type: string;
  vulnerabilityCount: number;
  
  // Severity breakdown (optional for backward compatibility)
  criticalCount?: number;
  highCount?: number;
  mediumCount?: number;
}

/**
 * Single workgroup with its assets
 */
export interface WorkgroupGroup {
  workgroupId: number;
  workgroupName: string;
  workgroupDescription?: string;
  assets: AssetVulnCount[];
  totalAssets: number;
  totalVulnerabilities: number;
  
  // Severity aggregation (optional for backward compatibility)
  totalCritical?: number;
  totalHigh?: number;
  totalMedium?: number;
}

/**
 * Top-level response containing all workgroup groups
 */
export interface WorkgroupVulnsSummary {
  workgroupGroups: WorkgroupGroup[];
  totalAssets: number;
  totalVulnerabilities: number;
  
  // Global severity totals (optional for backward compatibility)
  globalCritical?: number;
  globalHigh?: number;
  globalMedium?: number;

  // Metadata about the most recent CrowdStrike import
  lastImport?: CrowdStrikeImportStatus | null;
}

/**
 * Admin redirect error response
 */
export interface AdminRedirectError {
  message: string;
  redirectUrl: string;
  status: number;
}

/**
 * Standard error response
 */
export interface ErrorResponse {
  message: string;
  status: number;
}

/**
 * Get vulnerability overview for user's workgroups
 *
 * @returns WorkgroupVulnsSummary with workgroup groups, assets, and vulnerability counts
 * @throws Error if request fails or user has no workgroup memberships
 */
export async function getWorkgroupVulns(): Promise<WorkgroupVulnsSummary> {
  console.log('[workgroupVulnsService] getWorkgroupVulns called');
  console.log('[workgroupVulnsService] Making authenticated GET request to /api/wg-vulns');

  const response = await authenticatedGet('/api/wg-vulns');

  console.log('[workgroupVulnsService] Response received:', {
    ok: response.ok,
    status: response.status,
    statusText: response.statusText,
    headers: Object.fromEntries(response.headers.entries())
  });

  if (!response.ok) {
    console.error('[workgroupVulnsService] Request failed with status:', response.status);
    const errorData = await response.json().catch(() => ({ error: 'Request failed' }));
    console.error('[workgroupVulnsService] Error data:', errorData);
    throw new Error(errorData.error || errorData.message || `Request failed with status ${response.status}`);
  }

  const data = await response.json();
  console.log('[workgroupVulnsService] Successfully parsed response JSON:', data);
  return data;
}
