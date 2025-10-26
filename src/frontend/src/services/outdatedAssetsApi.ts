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

import { authenticatedGet } from '../utils/auth';

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
}

export interface LastRefreshResponse {
  lastRefreshTimestamp: string;
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

  const url = `/api/outdated-assets${queryParams.toString() ? '?' + queryParams.toString() : ''}`;
  return await authenticatedGet<OutdatedAssetsPage>(url);
}

/**
 * Get the timestamp of the last materialized view refresh
 *
 * @returns Last refresh timestamp or null if no refresh has occurred
 */
export async function getLastRefreshTimestamp(): Promise<string | null> {
  try {
    const data = await authenticatedGet<LastRefreshResponse>('/api/outdated-assets/last-refresh');
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
 * Get count of outdated assets visible to current user (workgroup-filtered)
 *
 * @returns Count of outdated assets
 */
export async function getOutdatedAssetsCount(): Promise<number> {
  const data = await authenticatedGet<CountResponse>('/api/outdated-assets/count');
  return data.count;
}
