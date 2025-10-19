/**
 * Asset service for API calls
 * Feature: 029-asset-bulk-operations
 *
 * Provides methods for:
 * - User Story 1: Bulk delete assets
 * - User Story 2: Export assets to Excel
 * - User Story 3: Import assets from Excel
 */

import { authenticatedDelete, authenticatedGet, authenticatedPost } from '../utils/auth';

/**
 * Response from bulk delete operation
 */
export interface BulkDeleteResult {
  deletedAssets: number;
  deletedVulnerabilities: number;
  deletedScanResults: number;
  message: string;
}

/**
 * Response from import operation
 */
export interface ImportResult {
  message: string;
  imported: number;
  skipped: number;
  assetsCreated?: number;
  assetsUpdated?: number;
  errors?: string[];
}

/**
 * Delete all assets (ADMIN only)
 * User Story 1: Bulk Delete Assets
 *
 * @returns BulkDeleteResult with counts of deleted entities
 * @throws Error on 403 (forbidden), 409 (conflict), 500 (server error)
 */
export async function bulkDeleteAssets(): Promise<BulkDeleteResult> {
  const response = await authenticatedDelete('/api/assets/bulk');

  if (response.ok) {
    return await response.json();
  }

  // Handle error responses
  if (response.status === 403) {
    throw new Error('Insufficient permissions. ADMIN role required.');
  }

  if (response.status === 409) {
    const error = await response.json();
    throw new Error(error.error || 'Bulk asset deletion already in progress');
  }

  if (response.status === 500) {
    const error = await response.json();
    throw new Error(error.error || 'Bulk delete failed. No assets were deleted.');
  }

  throw new Error(`Failed to delete assets: ${response.status}`);
}

/**
 * Export all accessible assets to Excel file
 * User Story 2: Export Assets to File
 *
 * @returns Blob containing Excel file
 * @throws Error on 400 (no data), 401 (unauthorized), 500 (server error)
 */
export async function exportAssets(): Promise<Blob> {
  const response = await authenticatedGet('/api/assets/export');

  if (response.ok) {
    return await response.blob();
  }

  // Handle error responses
  if (response.status === 400) {
    const error = await response.json();
    throw new Error(error.error || 'No assets available to export');
  }

  if (response.status === 401) {
    throw new Error('Unauthorized. Please login again.');
  }

  if (response.status === 500) {
    const error = await response.json();
    throw new Error(error.error || 'Failed to export assets');
  }

  throw new Error(`Failed to export assets: ${response.status}`);
}

/**
 * Import assets from Excel file
 * User Story 3: Import Assets from File
 *
 * @param file Excel file to import
 * @returns ImportResult with imported/skipped counts and errors
 * @throws Error on 400 (validation errors), 401 (unauthorized), 500 (server error)
 */
export async function importAssets(file: File): Promise<ImportResult> {
  const formData = new FormData();
  formData.append('xlsxFile', file);

  const response = await authenticatedPost('/api/import/upload-assets-xlsx', formData, {
    // Don't set Content-Type header - browser will set it with boundary for multipart/form-data
    headers: {}
  });

  if (response.ok) {
    return await response.json();
  }

  // Handle error responses
  if (response.status === 400) {
    const error = await response.json();
    throw new Error(error.error || 'Invalid file format or validation errors');
  }

  if (response.status === 401) {
    throw new Error('Unauthorized. Please login again.');
  }

  if (response.status === 500) {
    const error = await response.json();
    throw new Error(error.error || 'Failed to import assets');
  }

  throw new Error(`Failed to import assets: ${response.status}`);
}
