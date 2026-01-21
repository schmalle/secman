/**
 * API client for Outdated Assets endpoints
 *
 * Provides methods to fetch outdated assets with pagination, filtering, and sorting
 * Requires authentication (ADMIN or VULN role)
 *
 * Feature: 034-outdated-assets
 * Task: T024-T025
 * User Story: US1 - View Outdated Assets (P1)
 * Spec reference: contracts/01-get-outdated-assets.md
 */

import { authenticatedGet, authenticatedPost } from '../utils/auth';

export interface OutdatedAsset {
  id: number;
  assetId: number;
  assetName: string;
  assetType: string;
  totalOverdueCount: number;
  criticalCount: number;
  highCount: number;
  mediumCount: number;
  lowCount: number;
  oldestVulnDays: number;
  oldestVulnId: string | null;
  lastCalculatedAt: string; // ISO 8601 timestamp
}

export interface OutdatedAssetsPage {
  content: OutdatedAsset[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number; // Current page number (0-indexed)
}

export interface OutdatedAssetsParams {
  page?: number;
  size?: number;
  sort?: string; // e.g., "oldestVulnDays,desc"
  searchTerm?: string;
  minSeverity?: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';
  adDomain?: string;
}

export interface LastRefreshResponse {
  lastRefreshTimestamp: string;
}

export interface AdDomainsResponse {
  adDomains: string[];
}

export interface CountResponse {
  count: number;
}

/**
 * Fetch paginated list of outdated assets
 *
 * @param params Query parameters (page, size, sort, search, filter)
 * @returns Paginated list of outdated assets
 */
export async function getOutdatedAssets(
  params: OutdatedAssetsParams = {}
): Promise<OutdatedAssetsPage> {
  const queryParams = new URLSearchParams();

  if (params.page !== undefined) {
    queryParams.append('page', params.page.toString());
  }
  if (params.size !== undefined) {
    queryParams.append('size', params.size.toString());
  }
  if (params.sort) {
    queryParams.append('sort', params.sort);
  }
  if (params.searchTerm) {
    queryParams.append('searchTerm', params.searchTerm);
  }
  if (params.minSeverity) {
    queryParams.append('minSeverity', params.minSeverity);
  }
  if (params.adDomain) {
    queryParams.append('adDomain', params.adDomain);
  }

  const url = `/api/outdated-assets${queryParams.toString() ? '?' + queryParams.toString() : ''}`;
  const response = await authenticatedGet(url);
  return await response.json();
}

/**
 * Get the timestamp of the last materialized view refresh
 *
 * @returns Last refresh timestamp or null if no refresh has occurred
 */
export async function getLastRefreshTimestamp(): Promise<string | null> {
  try {
    const response = await authenticatedGet('/api/outdated-assets/last-refresh');
    if (response.status === 204) {
      return null;
    }
    const data: LastRefreshResponse = await response.json();
    return data.lastRefreshTimestamp;
  } catch (error: any) {
    // 204 No Content means no refresh has occurred yet
    if (error.response?.status === 204) {
      return null;
    }
    throw error;
  }
}

/**
 * Get distinct AD domains for filter dropdown
 *
 * @returns List of unique AD domain values
 */
export async function getAdDomains(): Promise<string[]> {
  const response = await authenticatedGet('/api/outdated-assets/ad-domains');
  const data: AdDomainsResponse = await response.json();
  return data.adDomains;
}

/**
 * Get count of outdated assets visible to current user (workgroup-filtered)
 *
 * @returns Count of outdated assets
 */
export async function getOutdatedAssetsCount(): Promise<number> {
  const response = await authenticatedGet('/api/outdated-assets/count');
  const data: CountResponse = await response.json();
  return data.count;
}

// ============================================================================
// User Story 2: View Asset Details
// ============================================================================

/**
 * Get single outdated asset by ID
 *
 * @param id Outdated asset materialized view ID
 * @returns Outdated asset details
 */
export async function getOutdatedAssetById(id: number): Promise<OutdatedAsset> {
  const response = await authenticatedGet(`/api/outdated-assets/${id}`);
  return await response.json();
}

/**
 * Vulnerability entity from backend
 */
export interface Vulnerability {
  id: number;
  vulnerabilityId: string;
  cvssSeverity: string;
  cvssscore: number | null;
  cvePublishedDate: string | null;
  vulnerableProductVersions: string | null;
  daysOpen: number;
  scanTimestamp: string;
  assetId: number;
}

/**
 * Page of vulnerabilities
 */
export interface VulnerabilitiesPage {
  content: Vulnerability[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

/**
 * Get vulnerabilities for an outdated asset
 *
 * @param id Outdated asset materialized view ID
 * @param page Page number (0-indexed)
 * @param size Page size
 * @param sort Sort string (e.g., "daysOpen,desc")
 * @returns Page of vulnerabilities for the asset
 */
export async function getAssetVulnerabilities(
  id: number,
  page: number = 0,
  size: number = 20,
  sort: string = 'daysOpen,desc'
): Promise<VulnerabilitiesPage> {
  const queryParams = new URLSearchParams();
  queryParams.append('page', page.toString());
  queryParams.append('size', size.toString());
  queryParams.append('sort', sort);

  const url = `/api/outdated-assets/${id}/vulnerabilities?${queryParams.toString()}`;
  const response = await authenticatedGet(url);
  return await response.json();
}

// ============================================================================
// User Story 3: Manual Refresh
// ============================================================================

export interface RefreshJob {
  id: number;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  triggeredBy: string;
  assetsProcessed: number;
  totalAssets: number;
  progressPercentage: number;
  startedAt: string;
  completedAt?: string;
  durationMs?: number;
  errorMessage?: string;
}

export interface RefreshProgressEvent {
  jobId: number;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  progressPercentage: number;
  assetsProcessed: number;
  totalAssets: number;
  message?: string;
}

/**
 * Trigger manual refresh of outdated assets materialized view
 * Returns immediately with job details
 *
 * @returns Refresh job details
 */
export async function triggerRefresh(): Promise<RefreshJob> {
  const response = await authenticatedPost('/api/materialized-view-refresh/trigger', {});
  return await response.json();
}

/**
 * Get current refresh job status
 *
 * @returns Current job or null if no refresh running
 */
export async function getRefreshStatus(): Promise<RefreshJob | null> {
  try {
    const response = await authenticatedGet('/api/materialized-view-refresh/status');
    if (response.status === 204) {
      return null;
    }
    return await response.json();
  } catch (error: any) {
    // 204 No Content means no refresh running
    if (error.response?.status === 204) {
      return null;
    }
    throw error;
  }
}

/**
 * Get recent refresh job history
 *
 * @returns List of recent refresh jobs
 */
export async function getRefreshHistory(): Promise<RefreshJob[]> {
  const response = await authenticatedGet('/api/materialized-view-refresh/history');
  return await response.json();
}
