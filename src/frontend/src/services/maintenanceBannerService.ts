import axios from 'axios';

/**
 * API base URL from environment or default to localhost
 */
const API_BASE = import.meta.env.PUBLIC_API_URL || 'http://localhost:8080/api';

/**
 * Maintenance Banner interface matching backend MaintenanceBannerResponse DTO
 */
export interface MaintenanceBanner {
  id: number;
  message: string;
  startTime: string;  // ISO-8601 timestamp string
  endTime: string;    // ISO-8601 timestamp string
  createdAt: string;
  createdByUsername: string | null;
  createdBy?: string | null;  // Alias for createdByUsername
  status: 'UPCOMING' | 'ACTIVE' | 'EXPIRED';
  isActive: boolean;
}

/**
 * Request interface for creating/updating banners
 */
export interface MaintenanceBannerRequest {
  message: string;
  startTime: string;  // ISO-8601 timestamp string
  endTime: string;    // ISO-8601 timestamp string
}

/**
 * Get all currently active maintenance banners (PUBLIC endpoint - no auth required)
 *
 * @returns Promise resolving to array of active banners
 */
export async function getActiveBanners(): Promise<MaintenanceBanner[]> {
  const response = await axios.get<MaintenanceBanner[]>(
    `${API_BASE}/maintenance-banners/active`
  );
  return response.data;
}

/**
 * Get all maintenance banners (admin only)
 *
 * @returns Promise resolving to array of all banners
 */
export async function getAllBanners(): Promise<MaintenanceBanner[]> {
  const token = localStorage.getItem('authToken');
  const response = await axios.get<MaintenanceBanner[]>(
    `${API_BASE}/maintenance-banners`,
    {
      headers: {
        Authorization: `Bearer ${token}`
      }
    }
  );
  return response.data;
}

/**
 * Get a specific maintenance banner by ID (admin only)
 *
 * @param id Banner ID
 * @returns Promise resolving to banner details
 */
export async function getBannerById(id: number): Promise<MaintenanceBanner> {
  const token = localStorage.getItem('authToken');
  const response = await axios.get<MaintenanceBanner>(
    `${API_BASE}/maintenance-banners/${id}`,
    {
      headers: {
        Authorization: `Bearer ${token}`
      }
    }
  );
  return response.data;
}

/**
 * Create a new maintenance banner (admin only)
 *
 * @param request Banner creation request
 * @returns Promise resolving to created banner
 */
export async function createBanner(request: MaintenanceBannerRequest): Promise<MaintenanceBanner> {
  const token = localStorage.getItem('authToken');
  const response = await axios.post<MaintenanceBanner>(
    `${API_BASE}/maintenance-banners`,
    request,
    {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json'
      }
    }
  );
  return response.data;
}

/**
 * Update an existing maintenance banner (admin only)
 *
 * @param id Banner ID
 * @param request Banner update request
 * @returns Promise resolving to updated banner
 */
export async function updateBanner(id: number, request: MaintenanceBannerRequest): Promise<MaintenanceBanner> {
  const token = localStorage.getItem('authToken');
  const response = await axios.put<MaintenanceBanner>(
    `${API_BASE}/maintenance-banners/${id}`,
    request,
    {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json'
      }
    }
  );
  return response.data;
}

/**
 * Delete a maintenance banner (admin only)
 *
 * @param id Banner ID
 * @returns Promise resolving when deletion is complete
 */
export async function deleteBanner(id: number): Promise<void> {
  const token = localStorage.getItem('authToken');
  await axios.delete(
    `${API_BASE}/maintenance-banners/${id}`,
    {
      headers: {
        Authorization: `Bearer ${token}`
      }
    }
  );
}
