import { authenticatedGet } from '../utils/auth';

/**
 * A GitHub Dependabot alert as returned by GET /api/dependabot-alerts.
 * Shape mirrors the backend DependabotAlert entity.
 */
export interface DependabotAlert {
  id: number;
  repository: string;
  alertNumber: number;
  state: string;
  packageName: string;
  ecosystem: string;
  manifestPath?: string | null;
  severity: string;
  ghsaId?: string | null;
  cveId?: string | null;
  summary?: string | null;
  vulnerableVersionRange?: string | null;
  firstPatchedVersion?: string | null;
  htmlUrl?: string | null;
  alertCreatedAt?: string | null;
  alertUpdatedAt?: string | null;
  dismissedAt?: string | null;
  fixedAt?: string | null;
  importedAt: string;
}

/**
 * Fetch all ingested Dependabot alerts.
 * @throws Error when the request fails or returns a non-2xx status.
 */
export async function getDependabotAlerts(): Promise<DependabotAlert[]> {
  const response = await authenticatedGet('/api/dependabot-alerts');
  if (!response.ok) {
    throw new Error(`Failed to fetch Dependabot alerts: ${response.status}`);
  }
  return response.json();
}

/** Sort rank for severity (higher = more severe), case-insensitive. */
export function severityRank(severity: string): number {
  switch (severity?.toLowerCase()) {
    case 'critical': return 4;
    case 'high': return 3;
    case 'medium': return 2;
    case 'low': return 1;
    default: return 0;
  }
}

/** Bootstrap badge background class for a severity value. */
export function severityBadgeClass(severity: string): string {
  switch (severity?.toLowerCase()) {
    case 'critical': return 'bg-danger';
    case 'high': return 'bg-warning text-dark';
    case 'medium': return 'bg-info text-dark';
    case 'low': return 'bg-secondary';
    default: return 'bg-light text-dark';
  }
}
