import axios from 'axios';

// API base URL - always use relative URLs to go through Astro's proxy and avoid CORS issues
const API_BASE_URL = import.meta.env.PUBLIC_API_URL || '';

export interface DashboardPreference {
  id: number | null;
  userId: number;
  showAwsCleanServerKpi: boolean;
  showEdrCoverageKpi: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface UpdateDashboardPreferenceRequest {
  showAwsCleanServerKpi: boolean;
  showEdrCoverageKpi: boolean;
}

/**
 * Get current user's home dashboard KPI visibility preferences
 */
export async function getDashboardPreferences(): Promise<DashboardPreference> {
  const response = await axios.get(`${API_BASE_URL}/api/dashboard-preferences`);
  return response.data;
}

/**
 * Update current user's home dashboard KPI visibility preferences
 */
export async function updateDashboardPreferences(
  request: UpdateDashboardPreferenceRequest
): Promise<DashboardPreference> {
  const response = await axios.put(
    `${API_BASE_URL}/api/dashboard-preferences`,
    request,
    {
      headers: {
        'Content-Type': 'application/json'
      }
    }
  );
  return response.data;
}
