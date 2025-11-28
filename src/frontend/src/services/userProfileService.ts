import axios from 'axios';

/**
 * User profile data interface
 * Feature 028: User Profile Page
 * Feature 051: User Password Change (added canChangePassword)
 */
export interface UserProfileData {
  username: string;
  email: string;
  roles: string[];
  canChangePassword: boolean;
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
 * Password change request
 * Feature 051: User Password Change
 */
export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

/**
 * Password change response
 * Feature 051: User Password Change
 */
export interface ChangePasswordResponse {
  success: boolean;
  message: string;
}

/**
 * Service for user profile API operations
 * Feature 028: User Profile Page
 * Feature 051: User Password Change
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

  /**
   * Change current user's password
   * Feature 051: User Password Change
   *
   * @param request - Password change request with current, new, and confirm passwords
   * @returns Promise<ChangePasswordResponse> Change result
   * @throws Error if request fails (validation error, wrong current password, etc.)
   */
  async changePassword(request: ChangePasswordRequest): Promise<ChangePasswordResponse> {
    const response = await axios.put<ChangePasswordResponse>(`${this.baseUrl}/profile/change-password`, request);
    return response.data;
  }
}

const userProfileServiceInstance = new UserProfileService();
export default userProfileServiceInstance;

// Re-export types explicitly for Vite/esbuild compatibility
export type { UserProfileData, MfaStatusResponse, MfaToggleResponse, ChangePasswordRequest, ChangePasswordResponse };
