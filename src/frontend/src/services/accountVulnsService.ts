import { authenticatedGet } from '../utils/auth';
import type { CrowdStrikeImportStatus } from '../types/crowdstrike';

/**
 * Service for Account Vulns API operations
 * Feature: 018-under-vuln-management
 */

/**
 * Single asset with its vulnerability count
 * Feature 019: Added optional severity breakdown fields
 */
export interface AssetVulnCount {
  id: number;
  name: string;
  type: string;
  vulnerabilityCount: number;
  
  // Severity breakdown (Feature 019 - optional for backward compatibility)
  criticalCount?: number;
  highCount?: number;
  mediumCount?: number;
}

/**
 * Single AWS account group with its assets
 * Feature 019: Added optional severity aggregation fields
 */
export interface AccountGroup {
  awsAccountId: string;
  assets: AssetVulnCount[];
  totalAssets: number;
  totalVulnerabilities: number;
  
  // Severity aggregation (Feature 019 - optional for backward compatibility)
  totalCritical?: number;
  totalHigh?: number;
  totalMedium?: number;
}

/**
 * Top-level response containing all account groups
 * Feature 019: Added optional global severity fields
 */
export interface AccountVulnsSummary {
  accountGroups: AccountGroup[];
  totalAssets: number;
  totalVulnerabilities: number;
  
  // Global severity totals (Feature 019 - optional for backward compatibility)
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
 * Get vulnerability overview for user's AWS accounts
 *
 * @returns AccountVulnsSummary with account groups, assets, and vulnerability counts
 * @throws Error if request fails or user has no AWS account mappings
 */
export async function getAccountVulns(): Promise<AccountVulnsSummary> {
  console.log('[accountVulnsService] getAccountVulns called');
  console.log('[accountVulnsService] Making authenticated GET request to /api/account-vulns');

  const response = await authenticatedGet('/api/account-vulns');

  console.log('[accountVulnsService] Response received:', {
    ok: response.ok,
    status: response.status,
    statusText: response.statusText,
    headers: Object.fromEntries(response.headers.entries())
  });

  if (!response.ok) {
    console.error('[accountVulnsService] Request failed with status:', response.status);
    const errorData = await response.json().catch(() => ({ error: 'Request failed' }));
    console.error('[accountVulnsService] Error data:', errorData);
    throw new Error(errorData.error || errorData.message || `Request failed with status ${response.status}`);
  }

  const data = await response.json();
  console.log('[accountVulnsService] Successfully parsed response JSON:', data);
  return data;
}
