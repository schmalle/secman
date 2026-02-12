import axios from 'axios';

// API base URL - uses relative URLs in production to avoid CORS issues
const API_BASE_URL = import.meta.env.PUBLIC_API_URL ||
  (typeof window !== 'undefined' && window.location.hostname !== 'localhost'
    ? '' // Use relative URLs in production
    : 'http://localhost:8080'); // Use localhost in development

export interface NotificationPreference {
  id: number | null;
  userId: number;
  enableNewVulnNotifications: boolean;
  lastVulnNotificationSentAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UpdatePreferenceRequest {
  enableNewVulnNotifications: boolean;
}

export interface NotificationLog {
  id: number;
  assetId: number | null;
  assetName: string;
  ownerEmail: string;
  notificationType: 'OUTDATED_LEVEL1' | 'OUTDATED_LEVEL2' | 'NEW_VULNERABILITY';
  sentAt: string;
  status: 'SENT' | 'FAILED' | 'PENDING';
  errorMessage: string | null;
}

export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * Get current user's notification preferences
 */
export async function getUserPreferences(): Promise<NotificationPreference> {
  const response = await axios.get(`${API_BASE_URL}/api/notification-preferences`);
  return response.data;
}

/**
 * Update current user's notification preferences
 */
export async function updateUserPreferences(
  request: UpdatePreferenceRequest
): Promise<NotificationPreference> {
  const response = await axios.put(
    `${API_BASE_URL}/api/notification-preferences`,
    request,
    {
      headers: {
        'Content-Type': 'application/json'
      }
    }
  );
  return response.data;
}

/**
 * List notification logs (ADMIN only)
 */
export async function listNotificationLogs(params: {
  page?: number;
  size?: number;
  notificationType?: string;
  status?: string;
  ownerEmail?: string;
  startDate?: string;
  endDate?: string;
  sort?: string;
}): Promise<PagedResponse<NotificationLog>> {
  const response = await axios.get(`${API_BASE_URL}/api/notification-logs`, {
    params,
  });
  return response.data;
}

/**
 * Export notification logs to CSV (ADMIN only)
 * Uses axios with proper authorization to ensure JWT token is included
 */
export async function exportNotificationLogs(params: {
  notificationType?: string;
  status?: string;
  ownerEmail?: string;
  startDate?: string;
  endDate?: string;
}): Promise<void> {
  // Authentication via HttpOnly cookie (withCredentials set globally)
  const response = await axios.get(`${API_BASE_URL}/api/notification-logs/export`, {
    params,
    responseType: 'blob' // Important: handle binary data
  });

  // Create blob from response data
  const blob = new Blob([response.data], { type: 'text/csv' });
  const url = window.URL.createObjectURL(blob);

  // Trigger download
  const link = document.createElement('a');
  link.href = url;
  link.setAttribute('download', `notification-logs-${Date.now()}.csv`);
  document.body.appendChild(link);
  link.click();

  // Cleanup
  document.body.removeChild(link);
  window.URL.revokeObjectURL(url);
}
