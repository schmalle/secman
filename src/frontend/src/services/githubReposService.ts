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

export async function getGithubRepositories(): Promise<GithubRepo[]> {
  const response = await authenticatedGet('/api/github/repositories');
  if (!response.ok) {
    throw new Error(`Failed to fetch GitHub repositories: ${response.status}`);
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
