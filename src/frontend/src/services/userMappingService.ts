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
 * Upload user mapping CSV file
 * Feature: 016-i-want-to (CSV-Based User Mapping Upload - User Story 1)
 *
 * Validates file client-side, uploads CSV to backend for parsing and import.
 * Supports comma, semicolon, tab delimiters; case-insensitive headers;
 * scientific notation account IDs; and partial success (skips invalid rows).
 *
 * @param file CSV file with user mappings
 * @returns ImportResult with counts and error details
 * @throws Error if upload fails or validation errors occur
 */
export async function uploadUserMappingsCSV(file: File): Promise<ImportResult> {
  // Client-side validation
  if (!file.name.toLowerCase().endsWith('.csv')) {
    throw new Error('File must be a CSV file (.csv extension)');
  }

  if (file.size > 10 * 1024 * 1024) {
    throw new Error('File size must not exceed 10 MB');
  }

  if (file.size === 0) {
    throw new Error('File is empty');
  }

  const formData = new FormData();
  formData.append('csvFile', file);

  const response = await authenticatedPost('/api/import/upload-user-mappings-csv', formData);

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({ error: 'Upload failed' }));
    throw new Error(errorData.error || `Upload failed with status ${response.status}`);
  }

  return await response.json();
}

/**
 * Get URL for sample user mapping template file (Excel)
 *
 * @returns URL to download sample Excel template
 */
export function getSampleFileUrl(): string {
  return '/sample-files/user-mapping-template.xlsx';
}

/**
 * Download CSV user mapping template
 * Feature: 016-i-want-to (CSV-Based User Mapping Upload - User Story 3)
 *
 * Makes authenticated request to backend endpoint to download CSV template.
 * Template includes headers and example row for user mapping import.
 *
 * @throws Error if download fails or user is not authenticated
 */
export async function downloadCSVTemplate(): Promise<void> {
  const token = sessionStorage.getItem('token');
  if (!token) {
    throw new Error('Not authenticated');
  }

  try {
    const response = await fetch('/api/import/user-mapping-template-csv', {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`,
      },
    });

    if (!response.ok) {
      if (response.status === 401) {
        throw new Error('Authentication required');
      } else if (response.status === 403) {
        throw new Error('Insufficient permissions (ADMIN role required)');
      } else {
        throw new Error(`Download failed with status ${response.status}`);
      }
    }

    // Get the blob from response
    const blob = await response.blob();

    // Create download link and trigger download
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'user-mapping-template.csv';
    document.body.appendChild(link);
    link.click();

    // Cleanup
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);
  } catch (error) {
    if (error instanceof Error) {
      throw error;
    }
    throw new Error('Failed to download CSV template');
  }
}
