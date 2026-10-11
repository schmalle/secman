import { authenticatedFetch } from '../utils/auth';
import { ApiError } from '../utils/apiJson';

export interface BuddyCapabilities { eligible: boolean; enabled?: boolean; available: boolean; roles: string[]; unavailableReason: string | null; inventoryInputSupported?: boolean }
export interface BuddyInventoryInput { build: string; edition: string; architecture: string; firefoxVersion: string; firefoxChannel: string }
export interface BuddyStatus { jobId: string; state: string; stage: string; error: { code: string; retryable: boolean } | null; reportAvailable: boolean }
export interface BuddyCheck { code: string; value: string | null; satisfied: boolean | null }
export interface BuddyAction {
  id: string; order: number; type: string; evidenceState: string; targetProduct: string | null;
  patchIds: string[]; targetVersion: string | null; applicability: BuddyCheck[]; prerequisites: BuddyCheck[];
  coveredCves: string[]; coveredFindingRefs: string[]; reboot: string; evidenceRefs: string[];
}
export interface BuddyFinding { ref: string; identifier: string | null; product: string | null; source: string; excepted: boolean; disposition: string; actionRefs: string[] }
export interface BuddyReport {
  schemaVersion: number; jobId: string; generatedAt: string; expiresAt: string;
  system: { displayName: string; osNameVersion: string | null; build: string | null; architecture: string | null };
  snapshot: { capturedAt: string; assetLastSeen: string | null; sensorLastSeen: string | null; vulnerabilityImportFrom: string | null; vulnerabilityImportTo: string | null; sourceChanged: boolean; stale: boolean; staleReasons: string[] };
  scope: { basis: string; retainedFindingCount: number; consideredFindingCount: number; uniqueCveCount: number; nonCveFindingCount: number; exceptedFindingCount: number; excludedFindingCount: number; duplicateRowCount: number };
  analysis: { completeness: string; model: string | null; verifiedCoveredFindingCount: number; conditionalFindingCount: number; noVerifiedRemediationCount: number; limitations: string[] };
  actions: BuddyAction[]; findings: BuddyFinding[];
  evidence: { ref: string; title: string; url: string | null; source: string; retrievedAt: string; providerAdvice?: string | null }[];
}
export async function buddyRequest<T>(path: string, method = 'GET', body?: unknown, signal?: AbortSignal): Promise<T> {
  const response = await authenticatedFetch(`/api/ai-buddy${path}`, { method, signal, cache: 'no-store',
    headers: body ? { 'Content-Type': 'application/json' } : undefined, body: body ? JSON.stringify(body) : undefined });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new ApiError(typeof error?.code === 'string' ? error.code : 'REQUEST_FAILED', response.status);
  }
  return response.json() as Promise<T>;
}
export function buddyEvidenceUrl(raw: string | null): string | null {
  if (!raw) return null;
  try {
    const url = new URL(raw);
    const safePath = url.hostname === 'www.mozilla.org'
      ? /^\/en-US\/security\/advisories\/mfsa[0-9]{4}-[0-9]+\/$/.test(url.pathname) && !url.search
      : url.hostname === 'product-details.mozilla.org'
        ? url.pathname === '/1.0/firefox_versions.json' && !url.search
        : url.hostname === 'api.msrc.microsoft.com'
      ? /^\/cvrf\/v3\.0\/cvrf\/\d{4}-(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)$/.test(url.pathname) && !url.search
      : url.hostname === 'support.microsoft.com'
        ? /^\/(help|kb)\/\d{6,8}\/?$/.test(url.pathname) && !url.search
        : url.hostname === 'msrc.microsoft.com'
          ? /^\/update-guide\/(?:en-US\/)?vulnerability\/CVE-\d{4}-\d{4,}$/.test(url.pathname) && !url.search
          : ['/Search.aspx', '/v7/site/Search.aspx'].includes(url.pathname) && /^\?q=KB\d{6,8}$/.test(url.search);
    return safePath && url.protocol === 'https:' && !url.username && !url.password && !url.hash && (!url.port || url.port === '443') &&
      ['api.msrc.microsoft.com', 'msrc.microsoft.com', 'support.microsoft.com', 'catalog.update.microsoft.com', 'www.catalog.update.microsoft.com', 'www.mozilla.org', 'product-details.mozilla.org'].includes(url.hostname)
      ? url.href : null;
  } catch { return null; }
}
export function buddyLabel(code: string): string {
  const setup: Record<string, string> = {
    SNAPSHOT: 'Checking recorded findings and installed software',
    EVIDENCE: 'Finding vendor-supported fixes',
    ANALYSIS: 'Preparing your patch steps',
    VALIDATION: 'Checking the guide and its supporting evidence',
    USER_SUPPLIED_INVENTORY_REQUIRES_CONFIRMATION: 'This guide uses temporary inventory facts you supplied; confirm them on the affected system before applying changes',
    INVENTORY_AMBIGUOUS: 'Recorded Firefox inventory contains multiple versions. Refresh and reconcile the inventory before providing a single Firefox version',
    FIREFOX_CHANNEL_AND_VERSION_REQUIRED: 'Record the exact Firefox version and Release/ESR channel in installed-product inventory',
    MOZILLA_EVIDENCE_UNAVAILABLE: 'Mozilla evidence could not be retrieved; retry after checking connectivity',
    MOZILLA_DOCUMENT_SEARCH_BOUNDED: 'Mozilla advisory search was limited; some older findings still need investigation',
    MOZILLA_NO_MATCHING_ADVISORY: 'No matching Mozilla advisory was found within the search scope',
    APPROVED_PROVIDER_REQUIRED: 'AI buddy is enabled, but no provider route is selected. Open LLM Config → Edit → AI buddy → Approved provider routes',
    SINGLE_INSTANCE_REQUIRED: 'Confirm the single backend instance setting in LLM Config',
    API_KEY_REQUIRED: 'Add an OpenRouter API key in LLM Config',
    MODEL_REQUIRED: 'Select a model in LLM Config',
    ACTIVE_CONFIGURATION_REQUIRED: 'Set this configuration as active',
    PROVIDER_ROUTE_UNSUPPORTED: 'A selected provider route does not meet the model, capability, privacy, or price requirements',
    PROVIDER_OFFLINE: 'Provider route is unavailable',
    STRUCTURED_OUTPUT_REQUIRED: 'Structured output support is required',
    OUTPUT_LIMIT_UNSUPPORTED: 'Provider output limit is too low',
    ZERO_RETENTION_REQUIRED: 'Route is not listed as eligible for zero data retention',
    PROVIDER_PRICE_LIMIT_EXCEEDED: 'Provider price exceeds the configured limit or could not be verified'
  };
  if (setup[code]) return setup[code];
  return code.toLowerCase().replaceAll('_', ' ').replace(/^./, c => c.toUpperCase()); }
