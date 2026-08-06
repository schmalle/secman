/**
 * Domain Vulnerabilities Service
 *
 * Feature: 043-crowdstrike-domain-import
 *
 * Provides client-side service for querying domain-based vulnerabilities
 * from secman database via the backend endpoint.
 *
 * Similar to accountVulnsService but for domain-based queries.
 */

import { authenticatedGet } from '../utils/auth';

/**
 * Device vulnerability count
 */
export interface DeviceVulnCount {
  hostname: string;
  ip: string | null;
  vulnerabilityCount: number;
  criticalCount?: number;
  highCount?: number;
  mediumCount?: number;
  lowCount?: number;
}

/**
 * Domain group with devices and vulnerabilities
 */
export interface DomainGroup {
  domain: string;
  devices: DeviceVulnCount[];
  totalDevices: number;
  totalVulnerabilities: number;
  totalCritical?: number;
  totalHigh?: number;
  totalMedium?: number;
  totalLow?: number;
}

/**
 * Domain vulnerabilities summary
 */
export interface DomainVulnsSummary {
  domainGroups: DomainGroup[];
  totalDevices: number;
  totalVulnerabilities: number;
  globalCritical?: number;
  globalHigh?: number;
  globalMedium?: number;
  globalLow?: number;
  queriedAt: string;
  lastSyncedAt?: string;
}

/**
 * Domain sync result
 */
export interface DomainSyncResult {
  domain: string;
  syncedAt: string;
  devicesProcessed: number;
  devicesCreated: number;
  devicesUpdated: number;
  vulnerabilitiesImported: number;
}

/**
 * Get domain-based vulnerabilities from secman database
 *
 * Queries secman database based on user's domain mappings
 * and returns vulnerabilities grouped by Active Directory domain.
 *
 * @returns Promise resolving to domain vulnerabilities summary
 * @throws Error if request fails or user has no domain mappings
 */
export async function getDomainVulns(): Promise<DomainVulnsSummary> {
  const response = await authenticatedGet('/api/domain-vulns');

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.message || `Request failed with status ${response.status}`);
  }

  return response.json();
}

/**
 * Sync domain vulnerabilities from CrowdStrike
 *
 * Triggers a sync of vulnerabilities for the specified domain from CrowdStrike Falcon API.
 * This will refresh the database with the latest vulnerability data.
 *
 * @param domain AD domain name to sync
 * @returns Promise resolving to sync result
 * @throws Error if sync fails
 */
export async function syncDomainFromCrowdStrike(domain: string): Promise<DomainSyncResult> {
  // Authentication via HttpOnly secman_auth cookie (sent automatically with credentials: 'include')
  const response = await fetch(`/api/domain-vulns/sync/${encodeURIComponent(domain)}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.message || `Sync failed with status ${response.status}`);
  }

  return response.json();
}
