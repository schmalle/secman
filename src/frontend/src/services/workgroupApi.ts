import { authenticatedPost, authenticatedGet, authenticatedPut, authenticatedDelete } from '../utils/auth';

/**
 * Service for Workgroup API operations
 * Features: 008-create-an-additional, 040-nested-workgroups
 */

/**
 * Breadcrumb item for ancestor path navigation
 * Feature 040: Nested Workgroups
 */
export interface BreadcrumbItem {
  id: number;
  name: string;
}

/**
 * Workgroup DTO with hierarchy information
 * Feature 040: Nested Workgroups
 */
export interface WorkgroupResponse {
  id: number;
  name: string;
  description?: string;
  parentId?: number;
  depth: number;
  childCount: number;
  hasChildren: boolean;
  ancestors: BreadcrumbItem[];
  createdAt: string;
  updatedAt: string;
  version: number;
}

/**
 * True when a workgroup's name marks it as AD-imported (the `adread --import`
 * tool names workgroups after `AWS-`-prefixed Azure AD groups). Matched
 * case-insensitively to mirror the Graph-side `AWS-/aws-/Aws-` prefixes.
 * The workgroup UI hides these by default behind a "Show AWS- workgroups" toggle.
 */
export const isAwsWorkgroup = (name: string): boolean =>
  name.trim().toUpperCase().startsWith('AWS-');

/**
 * Request to create a child workgroup
 * Feature 040: Nested Workgroups (User Story 1)
 */
export interface CreateChildWorkgroupRequest {
  name: string;
  description?: string;
}

/**
 * Request to move a workgroup
 * Feature 040: Nested Workgroups (User Story 3)
 */
export interface MoveWorkgroupRequest {
  newParentId?: number | null;
}

/**
 * Request to create a root-level workgroup
 * Feature 008: Workgroup Management
 */
export interface CreateWorkgroupRequest {
  name: string;
  description?: string;
  criticality?: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
}

/**
 * Create a child workgroup under a parent
 * Feature 040: Nested Workgroups (User Story 1)
 *
 * @param parentId ID of parent workgroup
 * @param request Create child workgroup request
 * @returns Created child workgroup with hierarchy info
 */
export async function createChildWorkgroup(
  parentId: number,
  request: CreateChildWorkgroupRequest
): Promise<WorkgroupResponse> {
  const response = await authenticatedPost(
    `/api/workgroups/${parentId}/children`,
    request
  );
  if (!response.ok) {
    throw new Error(await extractErrorMessage(response, 'Failed to create child workgroup'));
  }
  return await response.json();
}

/**
 * Get all direct children of a workgroup
 * Feature 040: Nested Workgroups (User Story 2)
 *
 * @param parentId ID of parent workgroup
 * @returns List of direct children
 */
export async function getWorkgroupChildren(
  parentId: number
): Promise<WorkgroupResponse[]> {
  const response = await authenticatedGet(
    `/api/workgroups/${parentId}/children`
  );
  return await response.json();
}

/**
 * Get all root-level workgroups (no parent)
 * Feature 040: Nested Workgroups (User Story 2)
 *
 * @returns List of root-level workgroups
 */
export async function getRootWorkgroups(): Promise<WorkgroupResponse[]> {
  const response = await authenticatedGet(
    `/api/workgroups/root`
  );
  return await response.json();
}

/**
 * Get all ancestors from root to immediate parent
 * Feature 040: Nested Workgroups (User Story 5)
 *
 * @param workgroupId ID of workgroup
 * @returns List of ancestors (root first, immediate parent last)
 */
export async function getWorkgroupAncestors(
  workgroupId: number
): Promise<BreadcrumbItem[]> {
  const response = await authenticatedGet(
    `/api/workgroups/${workgroupId}/ancestors`
  );
  return await response.json();
}

/**
 * Get all descendants (entire subtree)
 * Feature 040: Nested Workgroups (User Story 2)
 *
 * @param workgroupId ID of root workgroup
 * @returns List of all descendants
 */
export async function getWorkgroupDescendants(
  workgroupId: number
): Promise<WorkgroupResponse[]> {
  const response = await authenticatedGet(
    `/api/workgroups/${workgroupId}/descendants`
  );
  return await response.json();
}

/**
 * Move a workgroup to a new parent
 * Feature 040: Nested Workgroups (User Story 3)
 *
 * @param workgroupId ID of workgroup to move
 * @param request Move workgroup request (null newParentId moves to root)
 * @returns Updated workgroup
 */
export async function moveWorkgroup(
  workgroupId: number,
  request: MoveWorkgroupRequest
): Promise<WorkgroupResponse> {
  const response = await authenticatedPut(
    `/api/workgroups/${workgroupId}/parent`,
    request
  );
  if (!response.ok) {
    throw new Error(await extractErrorMessage(response, 'Failed to move workgroup'));
  }
  return await response.json();
}

async function extractErrorMessage(response: Response, fallback: string): Promise<string> {
  try {
    const text = await response.text();
    if (!text) return `${fallback} (HTTP ${response.status})`;
    try {
      const body = JSON.parse(text);
      if (body && typeof body.error === 'string') return body.error;
      if (body && typeof body.message === 'string') return body.message;
    } catch {
      return text;
    }
  } catch {
    // ignore
  }
  return `${fallback} (HTTP ${response.status})`;
}

/**
 * Delete a workgroup (children are promoted to grandparent)
 * Feature 040: Nested Workgroups (User Story 4)
 *
 * @param workgroupId ID of workgroup to delete
 */
export async function deleteWorkgroup(workgroupId: number): Promise<void> {
  await authenticatedDelete(`/api/workgroups/${workgroupId}`);
}

/**
 * Create a root-level workgroup
 * Feature 008: Workgroup Management
 *
 * @param request Create workgroup request
 * @returns Created workgroup
 */
export async function createWorkgroup(
  request: CreateWorkgroupRequest
): Promise<WorkgroupResponse> {
  const response = await authenticatedPost('/api/workgroups', request);
  return await response.json();
}

/**
 * Get workgroup details by ID
 * Feature 008: Workgroup Management
 *
 * @param workgroupId ID of workgroup
 * @returns Workgroup with hierarchy information
 */
export async function getWorkgroupById(
  workgroupId: number
): Promise<WorkgroupResponse> {
  const response = await authenticatedGet(
    `/api/workgroups/${workgroupId}`
  );
  return await response.json();
}
