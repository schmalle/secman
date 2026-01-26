import { authenticatedGet, authenticatedPut } from '../utils/auth';

/**
 * Service for Application Settings API operations
 * Feature: 068-requirements-alignment-process
 *
 * Provides methods to get and update application-wide settings
 * such as the base URL used in email notifications.
 */

/**
 * Application settings configuration
 */
export interface AppSettingsDto {
  /** Base URL of the application frontend (e.g., https://secman.example.com) */
  baseUrl: string;

  /** Username of admin who last updated settings */
  updatedBy: string | null;

  /** Timestamp of last update */
  updatedAt: string | null;
}

/**
 * Standard error response
 */
export interface ErrorResponse {
  status: number;
  message: string;
  timestamp: string;
}

/**
 * Get current application settings
 *
 * Requires: ADMIN role
 *
 * @returns Current application settings
 * @throws Error if request fails or user lacks ADMIN role
 */
export async function getAppSettings(): Promise<AppSettingsDto> {
  console.log('[appSettingsService] getAppSettings called');
  console.log('[appSettingsService] Making authenticated GET to /api/settings/app');

  const response = await authenticatedGet('/api/settings/app');

  console.log('[appSettingsService] Response received:', {
    ok: response.ok,
    status: response.status,
    statusText: response.statusText
  });

  if (!response.ok) {
    console.error('[appSettingsService] Request failed with status:', response.status);
    const errorData = await response.json().catch(() => ({ message: 'Request failed' }));
    console.error('[appSettingsService] Error data:', errorData);
    throw new Error(errorData.message || `Request failed with status ${response.status}`);
  }

  const data = await response.json();
  console.log('[appSettingsService] Successfully parsed response:', data);
  return data;
}

/**
 * Update application settings
 *
 * Requires: ADMIN role
 *
 * @param baseUrl New base URL
 * @returns Updated application settings
 * @throws Error if request fails, validation fails, or user lacks ADMIN role
 */
export async function updateAppSettings(baseUrl: string): Promise<AppSettingsDto> {
  console.log('[appSettingsService] updateAppSettings called with:', baseUrl);
  console.log('[appSettingsService] Making authenticated PUT to /api/settings/app');

  const response = await authenticatedPut('/api/settings/app', { baseUrl });

  console.log('[appSettingsService] Response received:', {
    ok: response.ok,
    status: response.status,
    statusText: response.statusText
  });

  if (!response.ok) {
    console.error('[appSettingsService] Update failed with status:', response.status);
    const errorData = await response.json().catch(() => ({ message: 'Update failed' }));
    console.error('[appSettingsService] Error data:', errorData);
    throw new Error(errorData.message || `Update failed with status ${response.status}`);
  }

  const data = await response.json();
  console.log('[appSettingsService] Successfully updated settings:', data);
  return data;
}
