/**
 * CVE Lookup Service
 *
 * Fetches CVE descriptions from backend proxy (NVD API).
 * Includes in-memory cache and request deduplication.
 *
 * Feature: 072-cve-link-lookup
 */

import { authenticatedGet } from '../utils/auth';

export interface CveLookupResult {
  cveId: string;
  description: string | null;
  severity: string | null;
  cvssScore: number | null;
  publishedDate: string | null;
  references: string[];
}

const cache = new Map<string, CveLookupResult>();
const pendingRequests = new Map<string, Promise<CveLookupResult | null>>();

/**
 * Look up CVE details with caching and request deduplication.
 *
 * @param cveId CVE identifier (e.g., "CVE-2023-12345")
 * @returns CVE details or null if lookup fails
 */
export async function lookupCve(cveId: string): Promise<CveLookupResult | null> {
  // Check in-memory cache first
  const cached = cache.get(cveId);
  if (cached) return cached;

  // Deduplicate concurrent requests for the same CVE
  const pending = pendingRequests.get(cveId);
  if (pending) return pending;

  const request = fetchCve(cveId);
  pendingRequests.set(cveId, request);

  try {
    const result = await request;
    if (result) {
      cache.set(cveId, result);
    }
    return result;
  } finally {
    pendingRequests.delete(cveId);
  }
}

async function fetchCve(cveId: string): Promise<CveLookupResult | null> {
  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 5000);

    const response = await authenticatedGet(`/api/cve/${encodeURIComponent(cveId)}`);
    clearTimeout(timeoutId);

    if (!response.ok) return null;

    return await response.json();
  } catch {
    return null;
  }
}
