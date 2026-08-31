import { authenticatedGet } from '../utils/auth';
import type { CrowdStrikeImportStatus } from '../types/crowdstrike';

/**
 * Service for Account Vulns API operations
 */

/**
 * Single asset with its vulnerability count
 * Feature 019: Added optional severity breakdown fields
 */
/**
 * Traffic-light indicator for "does this asset need manual intervention?".
 *
 * GREEN  - no vulnerabilities, or every one is covered by an active exception
 * YELLOW - non-excepted findings exist but all are inside the threshold window
 * RED    - at least one non-excepted finding is older than the threshold
 */
export type AssetInterventionStatus = 'GREEN' | 'YELLOW' | 'RED';

export interface AssetVulnCount {
  id: number;
  name: string;
  type: string;
  vulnerabilityCount: number;
  
  // Severity breakdown (Feature 019 - optional for backward compatibility)
  criticalCount?: number;
  highCount?: number;
  mediumCount?: number;

  // Exception breakdown (optional for backward compatibility)
  exceptedCount?: number;
  nonExceptedCount?: number;

  // Intervention status. nonExceptedOverdueCount explains *why* an asset is red.
  nonExceptedOverdueCount?: number;
  status?: AssetInterventionStatus;
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

  // Exception aggregation (optional for backward compatibility)
  totalExcepted?: number;
  totalNonExcepted?: number;

  assetsNeedingAttention?: number;
  status?: AssetInterventionStatus;
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

  // Global exception totals (optional for backward compatibility)
  globalExcepted?: number;
  globalNonExcepted?: number;

  globalStatus?: AssetInterventionStatus;
  assetsNeedingAttention?: number;
  /** Days after which a non-excepted finding counts as overdue (admin-configurable). */
  thresholdDays?: number;

  // Metadata about the most recent CrowdStrike import
  lastImport?: CrowdStrikeImportStatus | null;

  // Actual data freshness: most recent vulnerability import timestamp
  dataFreshness?: string | null;
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
    console.warn('[accountVulnsService] Request failed with status:', response.status);
    const errorData = await response.json().catch(() => ({ error: 'Request failed' }));
    console.warn('[accountVulnsService] Error data:', errorData);
    throw new Error(errorData.error || errorData.message || `Request failed with status ${response.status}`);
  }

  const data = await response.json();
  console.log('[accountVulnsService] Successfully parsed response JSON:', data);
  return data;
}
