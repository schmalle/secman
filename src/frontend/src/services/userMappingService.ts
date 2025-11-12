import { authenticatedPost, getAuthToken } from '../utils/auth';

/**
 * Service for User Mapping API operations
 * Features: 013-user-mapping-upload, 020-i-want-to (IP Address Mapping)
 */

export interface ImportResult {
  message: string;
  imported: number;
  skipped: number;
  errors?: string[];
}

/**
 * User Mapping DTO (Feature 020: extended with IP address fields, Feature 042: extended with future user mapping fields)
 */
export interface UserMapping {
  id: number;
  email: string;
  awsAccountId?: string;
  domain?: string;
  ipAddress?: string;
  ipRangeType?: 'SINGLE' | 'CIDR' | 'DASH_RANGE';
  ipCount?: number;
  userId?: number;              // Feature 042: Nullable user reference
  appliedAt?: string;           // Feature 042: Timestamp when mapping was applied
  isFutureMapping: boolean;     // Feature 042: True if user=null AND appliedAt=null
  createdAt: string;
  updatedAt: string;
}

/**
 * Create User Mapping Request
 */
export interface CreateUserMappingRequest {
  email: string;
  awsAccountId?: string;
  domain?: string;
  ipAddress?: string;
}

/**
 * Update User Mapping Request
 */
export interface UpdateUserMappingRequest {
  email: string;
  awsAccountId?: string;
  domain?: string;
  ipAddress?: string;
}

/**
 * Paginated list response
 */
export interface UserMappingListResponse {
  content: UserMapping[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
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
  const token = getAuthToken();
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

// ========== IP Mapping CRUD Operations (Feature 020) ==========

/**
 * List user mappings with pagination
 * Feature: 020-i-want-to (IP Address Mapping)
 *
 * @param page Page number (0-indexed)
 * @param size Page size
 * @param email Optional email filter
 * @param domain Optional domain filter
 * @returns Paginated list of user mappings
 */
export async function listUserMappings(
  page: number = 0,
  size: number = 20,
  email?: string,
  domain?: string
): Promise<UserMappingListResponse> {
  const token = getAuthToken();
  if (!token) {
    throw new Error('Not authenticated');
  }

  const params = new URLSearchParams();
  params.append('page', page.toString());
  params.append('size', size.toString());
  if (email) params.append('email', email);
  if (domain) params.append('domain', domain);

  const response = await fetch(`/api/user-mappings?${params.toString()}`, {
    method: 'GET',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });

  if (!response.ok) {
    if (response.status === 401) {
      throw new Error('Authentication required');
    } else if (response.status === 403) {
      throw new Error('Insufficient permissions (ADMIN role required)');
    } else {
      throw new Error(`Failed to list user mappings: ${response.status}`);
    }
  }

  return await response.json();
}

/**
 * Get user mapping by ID
 * Feature: 020-i-want-to (IP Address Mapping)
 *
 * @param id Mapping ID
 * @returns User mapping details
 */
export async function getUserMapping(id: number): Promise<UserMapping> {
  const token = getAuthToken();
  if (!token) {
    throw new Error('Not authenticated');
  }

  const response = await fetch(`/api/user-mappings/${id}`, {
    method: 'GET',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });

  if (!response.ok) {
    if (response.status === 404) {
      throw new Error('User mapping not found');
    } else if (response.status === 401) {
      throw new Error('Authentication required');
    } else if (response.status === 403) {
      throw new Error('Insufficient permissions (ADMIN role required)');
    } else {
      throw new Error(`Failed to get user mapping: ${response.status}`);
    }
  }

  return await response.json();
}

/**
 * Create new user mapping (AWS account, IP address, or both)
 * Feature: 020-i-want-to (IP Address Mapping)
 *
 * @param request Create mapping request
 * @returns Created user mapping
 */
export async function createUserMapping(request: CreateUserMappingRequest): Promise<UserMapping> {
  const token = getAuthToken();
  if (!token) {
    throw new Error('Not authenticated');
  }

  const response = await fetch('/api/user-mappings', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({ message: 'Failed to create mapping' }));

    if (response.status === 400) {
      throw new Error(errorData.message || 'Invalid request');
    } else if (response.status === 401) {
      throw new Error('Authentication required');
    } else if (response.status === 403) {
      throw new Error('Insufficient permissions (ADMIN role required)');
    } else {
      throw new Error(errorData.message || `Failed to create user mapping: ${response.status}`);
    }
  }

  return await response.json();
}

/**
 * Update existing user mapping
 * Feature: 020-i-want-to (IP Address Mapping)
 *
 * @param id Mapping ID
 * @param request Update mapping request
 * @returns Updated user mapping
 */
export async function updateUserMapping(id: number, request: UpdateUserMappingRequest): Promise<UserMapping> {
  const token = getAuthToken();
  if (!token) {
    throw new Error('Not authenticated');
  }

  const response = await fetch(`/api/user-mappings/${id}`, {
    method: 'PUT',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({ message: 'Failed to update mapping' }));

    if (response.status === 404) {
      throw new Error('User mapping not found');
    } else if (response.status === 400) {
      throw new Error(errorData.message || 'Invalid request');
    } else if (response.status === 401) {
      throw new Error('Authentication required');
    } else if (response.status === 403) {
      throw new Error('Insufficient permissions (ADMIN role required)');
    } else {
      throw new Error(errorData.message || `Failed to update user mapping: ${response.status}`);
    }
  }

  return await response.json();
}

/**
 * Delete user mapping
 * Feature: 020-i-want-to (IP Address Mapping)
 *
 * @param id Mapping ID
 */
export async function deleteUserMapping(id: number): Promise<void> {
  const token = getAuthToken();
  if (!token) {
    throw new Error('Not authenticated');
  }

  const response = await fetch(`/api/user-mappings/${id}`, {
    method: 'DELETE',
    headers: {
      'Authorization': `Bearer ${token}`,
    },
  });

  if (!response.ok) {
    if (response.status === 404) {
      throw new Error('User mapping not found');
    } else if (response.status === 401) {
      throw new Error('Authentication required');
    } else if (response.status === 403) {
      throw new Error('Insufficient permissions (ADMIN role required)');
    } else {
      throw new Error(`Failed to delete user mapping: ${response.status}`);
    }
  }
}

// ========== Future User Mapping Support (Feature 042) ==========

/**
 * List current user mappings (future + active)
 * Feature: 042-future-user-mappings
 *
 * Returns mappings that have not been applied yet (appliedAt IS NULL).
 * Includes both future user mappings and active user mappings.
 *
 * @param page Page number (0-indexed)
 * @param size Page size
 * @returns Paginated list of current user mappings
 */
export async function listCurrentMappings(
  page: number = 0,
  size: number = 20
): Promise<UserMappingListResponse> {
  const token = getAuthToken();
  if (!token) {
    throw new Error('Not authenticated');
  }

  const params = new URLSearchParams();
  params.append('page', page.toString());
  params.append('size', size.toString());

  const response = await fetch(`/api/user-mappings/current?${params.toString()}`, {
    method: 'GET',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });

  if (!response.ok) {
    if (response.status === 401) {
      throw new Error('Authentication required');
    } else if (response.status === 403) {
      throw new Error('Insufficient permissions (ADMIN role required)');
    } else {
      throw new Error(`Failed to list current mappings: ${response.status}`);
    }
  }

  return await response.json();
}

/**
 * List applied historical user mappings
 * Feature: 042-future-user-mappings
 *
 * Returns mappings that have been applied to users (appliedAt IS NOT NULL).
 * These are historical records of when future user mappings were applied.
 *
 * @param page Page number (0-indexed)
 * @param size Page size
 * @returns Paginated list of applied historical user mappings
 */
export async function listAppliedHistory(
  page: number = 0,
  size: number = 20
): Promise<UserMappingListResponse> {
  const token = getAuthToken();
  if (!token) {
    throw new Error('Not authenticated');
  }

  const params = new URLSearchParams();
  params.append('page', page.toString());
  params.append('size', size.toString());

  const response = await fetch(`/api/user-mappings/applied-history?${params.toString()}`, {
    method: 'GET',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });

  if (!response.ok) {
    if (response.status === 401) {
      throw new Error('Authentication required');
    } else if (response.status === 403) {
      throw new Error('Insufficient permissions (ADMIN role required)');
    } else {
      throw new Error(`Failed to list applied history: ${response.status}`);
    }
  }

  return await response.json();
}
