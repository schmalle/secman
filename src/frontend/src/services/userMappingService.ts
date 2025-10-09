import axios from 'axios';

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

  const response = await axios.post<ImportResult>(
    '/api/import/upload-user-mappings',
    formData,
    {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    }
  );

  return response.data;
}

/**
 * Get URL for sample user mapping template file
 * 
 * @returns URL to download sample template
 */
export function getSampleFileUrl(): string {
  return '/sample-files/user-mapping-template.xlsx';
}
