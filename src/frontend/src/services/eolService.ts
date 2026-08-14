import { authenticatedGet, authenticatedPost } from '../utils/auth';

export type EolStatus = 'EOL' | 'APPROACHING_EOL' | 'SUPPORTED';
export type EolSubjectType = 'ASSET_OS' | 'ASSET_PRODUCT' | 'REPOSITORY_COMPONENT';

export interface EolFinding {
  id: number;
  subjectType: EolSubjectType;
  assetId?: number | null;
  assetName?: string | null;
  cloudAccountId?: string | null;
  adDomain?: string | null;
  assetOwner?: string | null;
  repositoryId?: number | null;
  repositoryFullName?: string | null;
  componentName: string;
  componentVendor?: string | null;
  componentVersion?: string | null;
  ecosystem?: string | null;
  productKey: string;
  cycle: string;
  eolDate?: string | null;
  status: EolStatus;
  daysUntilEol?: number | null;
  detectedAt?: string | null;
}

export interface EolFindingList {
  findings: EolFinding[];
  total: number;
  page: number;
  pageSize: number;
}

export interface EolAccountSummary {
  cloudAccountId: string;
  eolCount: number;
  approachingCount: number;
}

export interface EolComponentSummary {
  componentName: string;
  productKey: string;
  cycle: string;
  status: EolStatus;
  affectedAssets: number;
}

export interface EolSummary {
  eolCount: number;
  approachingCount: number;
  affectedAssets: number;
  horizonMonths: number;
  accounts: EolAccountSummary[];
  topComponents: EolComponentSummary[];
  lastScanAt?: string | null;
}

export interface EolRepositoryRank {
  rank: number;
  repositoryId: number;
  fullName: string;
  distinctEolComponents: number;
  eolFindings: number;
  approachingFindings: number;
}

export interface EolCatalogStatus {
  sourceKey: string;
  products: number;
  releases: number;
  findings: number;
  lastSyncStatus?: string | null;
  lastSyncAt?: string | null;
  lastSyncTriggeredBy?: string | null;
  lastSyncError?: string | null;
}

export interface EolFindingQuery {
  status?: string;
  search?: string;
  cloudAccountId?: string;
  page?: number;
  pageSize?: number;
}

/**
 * Empty collections are omitted entirely from the backend's JSON body by the
 * Micronaut serializer, so every list read here defaults rather than assuming
 * the key exists.
 */
export async function getEolFindings(query: EolFindingQuery = {}): Promise<EolFindingList> {
  const params = new URLSearchParams();
  if (query.status) params.append('status', query.status);
  if (query.search?.trim()) params.append('search', query.search.trim());
  if (query.cloudAccountId?.trim()) params.append('cloudAccountId', query.cloudAccountId.trim());
  if (query.page !== undefined) params.append('page', String(query.page));
  if (query.pageSize !== undefined) params.append('pageSize', String(query.pageSize));

  const response = await authenticatedGet(`/api/eol/findings?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch EOL findings: ${response.status}`);
  }
  const data = await response.json();
  return {
    findings: data.findings ?? [],
    total: data.total ?? 0,
    page: data.page ?? 0,
    pageSize: data.pageSize ?? 100,
  };
}

export async function getEolSummary(): Promise<EolSummary> {
  const response = await authenticatedGet('/api/eol/summary');
  if (!response.ok) {
    throw new Error(`Failed to fetch EOL summary: ${response.status}`);
  }
  const data = await response.json();
  return {
    eolCount: data.eolCount ?? 0,
    approachingCount: data.approachingCount ?? 0,
    affectedAssets: data.affectedAssets ?? 0,
    horizonMonths: data.horizonMonths ?? 12,
    accounts: data.accounts ?? [],
    topComponents: data.topComponents ?? [],
    lastScanAt: data.lastScanAt ?? null,
  };
}

export async function getEolFindingsForAsset(assetId: number): Promise<EolFinding[]> {
  const response = await authenticatedGet(`/api/eol/assets/${assetId}`);
  if (response.status === 404) return [];
  if (!response.ok) {
    throw new Error(`Failed to fetch EOL findings for system: ${response.status}`);
  }
  const data = await response.json();
  return data.findings ?? [];
}

/** ADMIN / SECCHAMPION only — a 403 here is expected for other roles, not an error. */
export async function getTopEolRepositories(limit = 10): Promise<EolRepositoryRank[]> {
  const response = await authenticatedGet(`/api/eol/repositories/top?limit=${encodeURIComponent(String(limit))}`);
  if (response.status === 403) return [];
  if (!response.ok) {
    throw new Error(`Failed to fetch top EOL repositories: ${response.status}`);
  }
  const data = await response.json();
  return data.repositories ?? [];
}

export async function getEolCatalogStatus(): Promise<EolCatalogStatus | null> {
  const response = await authenticatedGet('/api/eol/catalog/status');
  if (!response.ok) return null;
  return response.json();
}

const SYNC_POLL_INTERVAL_MS = 5000;

/** Matches the backend's own stale-run threshold — past it, waiting cannot help. */
const SYNC_MAX_WAIT_MS = 60 * 60 * 1000;

const SYNC_STATUS_RUNNING = 'RUNNING';

/**
 * ADMIN only. Kicks off the catalogue download plus the matching scan and
 * resolves with the finished counts.
 *
 * The endpoint answers 202 as soon as the run is recorded and does the work on
 * a background thread: a full run takes minutes, which is longer than the 60s
 * read timeout Apache and nginx both apply by default, so a synchronous reply
 * was being severed by the proxy as a 504. Polling here keeps that detail out
 * of the caller — it still awaits one promise and gets the terminal result.
 */
export async function triggerEolSync(scanOnly = false): Promise<Record<string, unknown>> {
  const response = await authenticatedPost('/api/eol/catalog/sync', {
    products: [],
    scan: true,
    scanOnly,
  });
  if (!response.ok) {
    throw new Error(`EOL sync failed: ${response.status}`);
  }

  let latest: Record<string, unknown> = await response.json();
  const runId = String(latest.runId ?? '');
  if (!runId) return latest;

  const deadline = Date.now() + SYNC_MAX_WAIT_MS;
  while (String(latest.status ?? '') === SYNC_STATUS_RUNNING) {
    if (Date.now() > deadline) {
      throw new Error(`EOL sync is still running after 60 minutes (run ${runId})`);
    }
    await new Promise((resolve) => setTimeout(resolve, SYNC_POLL_INTERVAL_MS));
    const poll = await authenticatedGet(`/api/eol/catalog/sync/${encodeURIComponent(runId)}`);
    if (!poll.ok) {
      throw new Error(`EOL sync status check failed: ${poll.status}`);
    }
    latest = await poll.json();
  }
  return latest;
}
