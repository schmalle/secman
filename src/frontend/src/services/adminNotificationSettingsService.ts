import { authenticatedGet, authenticatedPut } from '../utils/auth';

/**
 * Service for Admin Notification Settings API operations
 * Feature: 027-admin-user-notifications
 *
 * Provides methods to get and update admin notification settings
 * (email notifications sent to ADMIN users when new users are created)
 */

/**
 * Notification settings configuration
 */
export interface NotificationSettingsDto {
  /** Whether admin notification emails are enabled for new user registrations */
  enabled: boolean;

  /** Email address used as sender (From field) for notification emails */
  senderEmail: string;
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
 * Get current notification settings
 *
 * Requires: ADMIN role
 *
 * @returns Current notification settings (enabled status and sender email)
 * @throws Error if request fails or user lacks ADMIN role
 */
export async function getNotificationSettings(): Promise<NotificationSettingsDto> {
  console.log('[adminNotificationSettingsService] getNotificationSettings called');
  console.log('[adminNotificationSettingsService] Making authenticated GET to /api/settings/notifications');

  const response = await authenticatedGet('/api/settings/notifications');

  console.log('[adminNotificationSettingsService] Response received:', {
    ok: response.ok,
    status: response.status,
    statusText: response.statusText
  });

  if (!response.ok) {
    console.error('[adminNotificationSettingsService] Request failed with status:', response.status);
    const errorData = await response.json().catch(() => ({ message: 'Request failed' }));
    console.error('[adminNotificationSettingsService] Error data:', errorData);
    throw new Error(errorData.message || `Request failed with status ${response.status}`);
  }

  const data = await response.json();
  console.log('[adminNotificationSettingsService] Successfully parsed response:', data);
  return data;
}

/**
 * Update notification settings
 *
 * Requires: ADMIN role
 *
 * @param settings New notification settings (enabled and senderEmail)
 * @returns Updated notification settings
 * @throws Error if request fails, validation fails, or user lacks ADMIN role
 */
export async function updateNotificationSettings(
  settings: NotificationSettingsDto
): Promise<NotificationSettingsDto> {
  console.log('[adminNotificationSettingsService] updateNotificationSettings called with:', settings);
  console.log('[adminNotificationSettingsService] Making authenticated PUT to /api/settings/notifications');

  const response = await authenticatedPut('/api/settings/notifications', settings);

  console.log('[adminNotificationSettingsService] Response received:', {
    ok: response.ok,
    status: response.status,
    statusText: response.statusText
  });

  if (!response.ok) {
    console.error('[adminNotificationSettingsService] Update failed with status:', response.status);
    const errorData = await response.json().catch(() => ({ message: 'Update failed' }));
    console.error('[adminNotificationSettingsService] Error data:', errorData);
    throw new Error(errorData.message || `Update failed with status ${response.status}`);
  }

  const data = await response.json();
  console.log('[adminNotificationSettingsService] Successfully updated settings:', data);
  return data;
}
