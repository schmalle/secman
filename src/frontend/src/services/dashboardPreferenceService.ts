import axios from 'axios';

// API base URL - always use relative URLs to go through Astro's proxy and avoid CORS issues
const API_BASE_URL = import.meta.env.PUBLIC_API_URL || '';

/** Visibility flags for the home dashboard cards — the full set the PUT body carries. */
export interface DashboardCardVisibility {
  showAwsCleanServerKpi: boolean;
  showEdrCoverageKpi: boolean;
  showAccountFindingAge: boolean;
  showAssetInventory: boolean;
  showUsers: boolean;
  showActiveUsers: boolean;
  showActiveReleases: boolean;
  showRunningRiskAssessments: boolean;
  showLastCrowdStrikeImport: boolean;
}

export interface DashboardPreference extends DashboardCardVisibility {
  id: number | null;
  userId: number;
  createdAt: string;
  updatedAt: string;
}

export type UpdateDashboardPreferenceRequest = DashboardCardVisibility;

/** Every card visible — the shape the API also defaults to when no row exists. */
export const ALL_CARDS_VISIBLE: DashboardCardVisibility = {
  showAwsCleanServerKpi: true,
  showEdrCoverageKpi: true,
  showAccountFindingAge: true,
  showAssetInventory: true,
  showUsers: true,
  showActiveUsers: true,
  showActiveReleases: true,
  showRunningRiskAssessments: true,
  showLastCrowdStrikeImport: true
};

/** Strip the persistence fields, leaving just the flags a PUT accepts. */
export function toCardVisibility(preference: DashboardCardVisibility): DashboardCardVisibility {
  return {
    showAwsCleanServerKpi: preference.showAwsCleanServerKpi,
    showEdrCoverageKpi: preference.showEdrCoverageKpi,
    showAccountFindingAge: preference.showAccountFindingAge,
    showAssetInventory: preference.showAssetInventory,
    showUsers: preference.showUsers,
    showActiveUsers: preference.showActiveUsers,
    showActiveReleases: preference.showActiveReleases,
    showRunningRiskAssessments: preference.showRunningRiskAssessments,
    showLastCrowdStrikeImport: preference.showLastCrowdStrikeImport
  };
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
