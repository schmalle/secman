import { authenticatedGet } from '../utils/auth';

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
  totalProducts: number;
  totalSystems: number;
}

export async function getInstalledProducts(search = ''): Promise<InstalledProductListResponse> {
  const params = new URLSearchParams();
  if (search.trim()) params.append('search', search.trim());
  const response = await authenticatedGet(`/api/installed-products?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch installed products: ${response.status}`);
  }
  return response.json();
}

export async function getInstalledProductsByServer(server = ''): Promise<InstalledProductListResponse> {
  const params = new URLSearchParams();
  if (server.trim()) params.append('server', server.trim());
  const response = await authenticatedGet(`/api/installed-products/by-server?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch installed products for server: ${response.status}`);
  }
  return response.json();
}
