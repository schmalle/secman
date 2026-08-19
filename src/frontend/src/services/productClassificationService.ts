import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';

export type ProductClass = 'INSTALLED' | 'INSTALLER_ARTIFACT' | 'UNKNOWN';
export type RuleMatchField = 'PRODUCT_NAME' | 'VENDOR' | 'INSTALL_PATH';

export interface ProductClassificationRule {
  id: number;
  matchField: RuleMatchField;
  pattern: string;
  classification: ProductClass;
  priority: number;
  enabled: boolean;
  description?: string | null;
  createdBy?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface ProductClassificationRuleInput {
  matchField: RuleMatchField;
  pattern: string;
  classification: ProductClass;
  priority: number;
  enabled: boolean;
  description?: string | null;
}

export interface ProductClassificationStats {
  installedProductArtifacts: number;
  eolFindingArtifacts: number;
  vulnerabilityArtifacts: number;
  enabledRules: number;
}

export interface ProductClassificationTestResult {
  value: string;
  classification: ProductClass;
  matchedRuleId?: number | null;
  matchedPattern?: string | null;
}

const BASE = '/api/product-classification';

/** Surface the backend's message rather than a bare status: rule validation is user-facing. */
async function readError(response: Response, fallback: string): Promise<never> {
  let message = fallback;
  try {
    const body = await response.json();
    if (body?.error) message = body.error;
  } catch {
    // Non-JSON body (proxy error page); keep the fallback.
  }
  throw new Error(message);
}

export async function getRules(): Promise<ProductClassificationRule[]> {
  const response = await authenticatedGet(`${BASE}/rules`);
  if (!response.ok) await readError(response, `Failed to load rules: ${response.status}`);
  return (await response.json()) ?? [];
}

export async function createRule(input: ProductClassificationRuleInput): Promise<ProductClassificationRule> {
  const response = await authenticatedPost(`${BASE}/rules`, input);
  if (!response.ok) await readError(response, `Failed to create rule: ${response.status}`);
  return await response.json();
}

export async function updateRule(
  id: number,
  input: ProductClassificationRuleInput,
): Promise<ProductClassificationRule> {
  const response = await authenticatedPut(`${BASE}/rules/${id}`, input);
  if (!response.ok) await readError(response, `Failed to update rule: ${response.status}`);
  return await response.json();
}

export async function deleteRule(id: number): Promise<void> {
  const response = await authenticatedDelete(`${BASE}/rules/${id}`);
  if (!response.ok) await readError(response, `Failed to delete rule: ${response.status}`);
}

export async function testValue(
  value: string,
  matchField: RuleMatchField,
): Promise<ProductClassificationTestResult> {
  const response = await authenticatedPost(`${BASE}/test`, { value, matchField });
  if (!response.ok) await readError(response, `Failed to test value: ${response.status}`);
  return await response.json();
}

export async function reclassifyAll(): Promise<void> {
  const response = await authenticatedPost(`${BASE}/reclassify`);
  if (!response.ok) await readError(response, `Failed to start reclassify: ${response.status}`);
}

export async function getStats(): Promise<ProductClassificationStats> {
  const response = await authenticatedGet(`${BASE}/stats`);
  if (!response.ok) await readError(response, `Failed to load stats: ${response.status}`);
  return await response.json();
}
