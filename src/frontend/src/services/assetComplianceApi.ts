/**
 * API client for Asset Compliance History endpoints
 * Requires authentication (ADMIN role)
 * Feature: ec2-vulnerability-tracking
 */

import { authenticatedGet, authenticatedPost } from '../utils/auth';

export interface AssetComplianceOverview {
  assetId: number;
  assetName: string;
  assetType: string | null;
  cloudInstanceId: string | null;
  currentStatus: 'COMPLIANT' | 'NON_COMPLIANT';
  lastChangeAt: string;
  overdueCount: number;
  oldestVulnDays: number | null;
  source: string;
}

export interface AssetComplianceOverviewPage {
  content: AssetComplianceOverview[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface AssetComplianceHistoryEntry {
  id: number;
  status: 'COMPLIANT' | 'NON_COMPLIANT';
  changedAt: string;
  overdueCount: number;
  oldestVulnDays: number | null;
  source: string;
  durationDays: number;
}

export interface AssetComplianceSummary {
  totalAssets: number;
  compliantCount: number;
  nonCompliantCount: number;
  neverAssessedCount: number;
  compliancePercentage: number;
}

export interface OverviewParams {
  page?: number;
  size?: number;
  searchTerm?: string;
  statusFilter?: string;
}

export async function getComplianceOverview(
  params: OverviewParams = {}
): Promise<AssetComplianceOverviewPage> {
  const queryParams = new URLSearchParams();
  if (params.page !== undefined) queryParams.append('page', params.page.toString());
  if (params.size !== undefined) queryParams.append('size', params.size.toString());
  if (params.searchTerm) queryParams.append('searchTerm', params.searchTerm);
  if (params.statusFilter) queryParams.append('statusFilter', params.statusFilter);

  const url = `/api/asset-compliance/overview${queryParams.toString() ? '?' + queryParams.toString() : ''}`;
  const response = await authenticatedGet(url);
  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.message || `Request failed with status ${response.status}`);
  }
  return await response.json();
}

export async function getComplianceSummary(): Promise<AssetComplianceSummary> {
  const response = await authenticatedGet('/api/asset-compliance/summary');
  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.message || `Request failed with status ${response.status}`);
  }
  return await response.json();
}

export async function getAssetComplianceHistory(
  assetId: number
): Promise<{ assetId: number; history: AssetComplianceHistoryEntry[] }> {
  const response = await authenticatedGet(`/api/asset-compliance/${assetId}/history`);
  return await response.json();
}

export async function triggerRecalculation(): Promise<{ message: string; assetsProcessed: number }> {
  const response = await authenticatedPost('/api/asset-compliance/recalculate');
  return await response.json();
}
