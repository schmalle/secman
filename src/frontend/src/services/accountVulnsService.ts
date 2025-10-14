import { authenticatedGet } from '../utils/auth';

/**
 * Service for Account Vulns API operations
 * Feature: 018-under-vuln-management
 */

/**
 * Single asset with its vulnerability count
 */
export interface AssetVulnCount {
  id: number;
  name: string;
  type: string;
  vulnerabilityCount: number;
}

/**
 * Single AWS account group with its assets
 */
export interface AccountGroup {
  awsAccountId: string;
  assets: AssetVulnCount[];
  totalAssets: number;
  totalVulnerabilities: number;
}

/**
 * Top-level response containing all account groups
 */
export interface AccountVulnsSummary {
  accountGroups: AccountGroup[];
  totalAssets: number;
  totalVulnerabilities: number;
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
  const response = await authenticatedGet('/api/account-vulns');

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({ error: 'Request failed' }));
    throw new Error(errorData.error || errorData.message || `Request failed with status ${response.status}`);
  }

  return await response.json();
}
