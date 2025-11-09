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
