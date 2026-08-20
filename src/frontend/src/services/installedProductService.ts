import { authenticatedGet } from '../utils/auth';

/**
 * Rows per page. Matches the backend default
 * (`InstalledProductListService.DEFAULT_PAGE_SIZE`); the table has ~500k rows,
 * so the page must never ask for all of them.
 */
export const PAGE_SIZE = 100;

export interface InstalledProductResponse {
  id: number;
  assetId: number;
  hostname: string;
  cloudAccountId?: string | null;
  name: string;
  vendor?: string | null;
  version?: string | null;
  category?: string | null;
  installationPath?: string | null;
  installedAt?: string | null;
  lastUsedAt?: string | null;
  lastUpdatedAt?: string | null;
  importedAt: string;
}

export interface InstalledProductListResponse {
  products: InstalledProductResponse[];
  /** Total matching products, not the length of `products` — see the pager. */
  totalProducts: number;
  totalSystems: number;
  page: number;
  pageSize: number;
}

export interface InstalledProductNamesResponse {
  names: string[];
}

export async function getInstalledProducts(
  search = '',
  page = 0,
  pageSize = PAGE_SIZE,
): Promise<InstalledProductListResponse> {
  const params = new URLSearchParams();
  if (search.trim()) params.append('search', search.trim());
  params.append('page', String(page));
  params.append('pageSize', String(pageSize));
  const response = await authenticatedGet(`/api/installed-products?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch installed products: ${response.status}`);
  }
  return response.json();
}

export async function getInstalledProductsByServer(
  server = '',
  page = 0,
  pageSize = PAGE_SIZE,
): Promise<InstalledProductListResponse> {
  const params = new URLSearchParams();
  if (server.trim()) params.append('server', server.trim());
  params.append('page', String(page));
  params.append('pageSize', String(pageSize));
  const response = await authenticatedGet(`/api/installed-products/by-server?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch installed products for server: ${response.status}`);
  }
  return response.json();
}

export async function getInstalledProductNames(): Promise<string[]> {
  const response = await authenticatedGet('/api/installed-products/names');
  if (!response.ok) {
    throw new Error(`Failed to fetch installed product names: ${response.status}`);
  }
  const data: InstalledProductNamesResponse = await response.json();
  // Empty lists are omitted entirely from the JSON body by the backend
  // serializer, so `names` can be undefined here (not just []).
  return data.names ?? [];
}
