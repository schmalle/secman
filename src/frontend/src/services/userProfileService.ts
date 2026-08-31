import axios from 'axios';
import { csrfDelete, csrfPost } from '../utils/csrf';

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
  hasProfilePicture: boolean;
  profilePictureUpdatedAt: string | null;
}

/**
 * Metadata about the current user's avatar
 */
export interface ProfilePictureMetadata {
  hasProfilePicture: boolean;
  contentType?: string | null;
  fileSizeBytes?: number | null;
  width?: number | null;
  height?: number | null;
  updatedAt?: string | null;
}

/**
 * MFA status response
 */
export interface MfaStatusResponse {
  enabled: boolean;
  passkeyCount: number;
  canDisable: boolean;
  message?: string;
}

/**
 * MFA toggle response
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

  /**
   * Upload or replace the current user's profile picture
   *
   * Uses csrfPost rather than bare axios: it adds the Csrf-Token header and is FormData-safe
   * (it does not force a Content-Type, so the browser sets the multipart boundary).
   *
   * @param image - Cropped image blob produced by the profile picture cropper
   * @param filename - Filename to send with the part
   * @returns Promise<ProfilePictureMetadata> Metadata for the stored picture
   */
  async uploadProfilePicture(image: Blob, filename = 'profile-picture.png'): Promise<ProfilePictureMetadata> {
    const formData = new FormData();
    formData.append('file', image, filename);
    const response = await csrfPost(`${this.baseUrl}/profile/picture`, formData);
    return response.data;
  }

  /**
   * Remove the current user's profile picture
   *
   * Idempotent server-side: succeeds whether or not a picture was set.
   */
  async deleteProfilePicture(): Promise<void> {
    await csrfDelete(`${this.baseUrl}/profile/picture`);
  }

  /**
   * Build the URL for the current user's profile picture
   *
   * Callers must only use this when the user is known to have a picture - requesting it
   * otherwise produces a 404 on every page load.
   *
   * @param updatedAt - Last-modified stamp, appended as a cache-busting query parameter
   */
  profilePictureUrl(updatedAt?: string | null): string {
    const base = `${this.baseUrl}/profile/picture`;
    return updatedAt ? `${base}?v=${encodeURIComponent(updatedAt)}` : base;
  }
}

const userProfileServiceInstance = new UserProfileService();
export default userProfileServiceInstance;
