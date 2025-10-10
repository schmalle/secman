import { authenticatedPost } from '../utils/auth';

/**
 * Service for User Mapping API operations
 * Feature: 013-user-mapping-upload
 */

export interface ImportResult {
  message: string;
  imported: number;
  skipped: number;
  errors?: string[];
}

/**
 * Upload user mapping Excel file
 *
 * @param file Excel file with user mappings
 * @returns ImportResult with counts and error details
 * @throws Error if upload fails or validation errors occur
 */
export async function uploadUserMappings(file: File): Promise<ImportResult> {
  const formData = new FormData();
  formData.append('xlsxFile', file);

  const response = await authenticatedPost('/api/import/upload-user-mappings', formData);

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({ error: 'Upload failed' }));
    throw new Error(errorData.error || `Upload failed with status ${response.status}`);
  }

  return await response.json();
}

/**
 * Get URL for sample user mapping template file
 * 
 * @returns URL to download sample template
 */
export function getSampleFileUrl(): string {
  return '/sample-files/user-mapping-template.xlsx';
}
