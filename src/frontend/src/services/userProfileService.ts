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
}

export default new UserProfileService();
