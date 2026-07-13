import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';

/**
 * Active alert exception attached to a repository row
 * (GET /api/github/repositories).
 */
export interface GithubRepoActiveException {
  id: number;
  reason: string;
  expirationDate?: string | null;
  createdBy: string;
  createdAt: string;
}

/**
 * A GitHub repository as returned by GET /api/github/repositories.
 * Shape mirrors the backend GithubRepositoryDto.
 */
export interface GithubRepo {
  id: number;
  githubRepoId: number;
  name: string;
  owner: string;
  fullName: string;
  htmlUrl?: string | null;
  ownerEmail?: string | null;
  criticalCount: number;
  highCount: number;
  lastImportAt?: string | null;
  lastHighCriticalFindingAt?: string | null;
  archived: boolean;
  activeException?: GithubRepoActiveException | null;
}

/** Result of POST /api/github/import. */
export interface GithubImportResult {
  reposDiscovered: number;
  reposNew: number;
  reposUpdated: number;
  totalCritical: number;
  totalHigh: number;
  reposWithAlertsDisabled: string[];
  errors: string[];
  importedAt: string;
}

/** A repo alert exception row (GET /api/github/repo-alert-exceptions). */
export interface GithubRepoAlertException {
  id: number;
  githubRepositoryId: number;
  reason: string;
  expirationDate?: string | null;
  createdBy: string;
  createdAt: string;
}

/** Paged envelope shared with OutdatedAssetController-style endpoints. */
export interface PagedGithubRepos {
  content: GithubRepo[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface GithubRepoTotals {
  criticalTotal: number;
  highTotal: number;
  totalCount: number;
}

export async function getGithubRepositories(
  page: number,
  size: number,
  search?: string
): Promise<PagedGithubRepos> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (search) params.set('search', search);
  const response = await authenticatedGet(`/api/github/repositories?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch GitHub repositories: ${response.status}`);
  }
  return response.json();
}

export async function getGithubRepositoriesSummary(): Promise<GithubRepoTotals> {
  const response = await authenticatedGet('/api/github/repositories/summary');
  if (!response.ok) {
    throw new Error(`Failed to fetch GitHub repository totals: ${response.status}`);
  }
  return response.json();
}

export async function updateOwnerEmail(id: number, ownerEmail: string | null): Promise<void> {
  const response = await authenticatedPut(`/api/github/repositories/${id}/owner-email`, { ownerEmail });
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new Error(body?.error || `Failed to update owner email: ${response.status}`);
  }
}

export async function triggerGithubImport(): Promise<GithubImportResult> {
  const response = await authenticatedPost('/api/github/import', {});
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new Error(body?.error || `Import failed: ${response.status}`);
  }
  return response.json();
}

export async function createRepoAlertException(request: {
  githubRepositoryId: number;
  reason: string;
  expirationDate?: string | null;
}): Promise<GithubRepoAlertException> {
  const response = await authenticatedPost('/api/github/repo-alert-exceptions', request);
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new Error(body?.error || `Failed to create exception: ${response.status}`);
  }
  return response.json();
}

export async function deleteRepoAlertException(id: number): Promise<void> {
  const response = await authenticatedDelete(`/api/github/repo-alert-exceptions/${id}`);
  if (!response.ok && response.status !== 204) {
    throw new Error(`Failed to delete exception: ${response.status}`);
  }
}

/** A repo's Dependabot alert row (GET /api/github/repositories/{id}/alerts). */
export interface GithubRepoAlert {
  id: number;
  alertNumber: number;
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
  alertUpdatedAt?: string | null;
}

export async function getGithubRepoAlerts(repositoryId: number): Promise<GithubRepoAlert[]> {
  const response = await authenticatedGet(`/api/github/repositories/${repositoryId}/alerts`);
  if (!response.ok) {
    throw new Error(`Failed to fetch repository alerts: ${response.status}`);
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
