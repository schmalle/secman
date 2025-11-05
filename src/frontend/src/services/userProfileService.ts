import axios from 'axios';

/**
 * User profile data interface
 * Feature 028: User Profile Page
 */
export interface UserProfileData {
  username: string;
  email: string;
  roles: string[];
}

/**
 * MFA status response
 * Feature: Passkey MFA Support
 */
export interface MfaStatusResponse {
  enabled: boolean;
  passkeyCount: number;
  canDisable: boolean;
  message?: string;
}

/**
 * MFA toggle response
 * Feature: Passkey MFA Support
 */
export interface MfaToggleResponse {
  success: boolean;
  mfaEnabled: boolean;
  message: string;
}

/**
 * Service for user profile API operations
 * Feature 028: User Profile Page
 */
class UserProfileService {
  private readonly baseUrl = '/api/users';

  /**
   * Get current user's profile
   *
   * Fetches profile information for the authenticated user from the backend API.
   * Requires valid JWT token in session storage.
   *
   * @returns Promise<UserProfileData> User's profile data
   * @throws Error if request fails (network error, 401, 404, etc.)
   */
  async getProfile(): Promise<UserProfileData> {
    const response = await axios.get<UserProfileData>(`${this.baseUrl}/profile`);
    return response.data;
  }

  /**
   * Get MFA status for current user
   * Feature: Passkey MFA Support
   *
   * @returns Promise<MfaStatusResponse> MFA status information
   * @throws Error if request fails
   */
  async getMfaStatus(): Promise<MfaStatusResponse> {
    const response = await axios.get<MfaStatusResponse>(`${this.baseUrl}/profile/mfa-status`);
    return response.data;
  }

  /**
   * Toggle MFA on/off for current user
   * Feature: Passkey MFA Support
   *
   * @param enabled - Whether to enable or disable MFA
   * @returns Promise<MfaToggleResponse> Toggle result
   * @throws Error if request fails
   */
  async toggleMfa(enabled: boolean): Promise<MfaToggleResponse> {
    const response = await axios.put<MfaToggleResponse>(`${this.baseUrl}/profile/mfa-toggle`, { enabled });
    return response.data;
  }
}

export default new UserProfileService();
