import React, { useState, useEffect } from 'react';
import userProfileService, { UserProfileData } from '../services/userProfileService';

/**
 * User Profile Component
 * Feature 028: User Profile Page
 *
 * Displays authenticated user's profile information:
 * - Username
 * - Email
 * - Roles (as colored badge pills)
 *
 * States:
 * - Loading: Shows spinner while fetching data
 * - Error: Shows alert with retry button if API fails
 * - Success: Shows profile data in card layout
 */
export default function UserProfile() {
  const [profile, setProfile] = useState<UserProfileData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchProfile();
  }, []);

  const fetchProfile = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await userProfileService.getProfile();
      setProfile(data);
    } catch (err: any) {
      const errorMessage = err.response?.data?.message || 'Failed to load profile. Please try again.';
      setError(errorMessage);
    } finally {
      setLoading(false);
    }
  };

  /**
   * Get Bootstrap badge class for role-specific colors
   * ADMIN = danger (red), RELEASE_MANAGER = warning (yellow),
   * VULN = info (blue), USER = secondary (gray)
   */
  const getRoleBadgeClass = (role: string): string => {
    const colorMap: Record<string, string> = {
      ADMIN: 'bg-danger',
      RELEASE_MANAGER: 'bg-warning text-dark',
      VULN: 'bg-info',
      USER: 'bg-secondary'
    };
    return colorMap[role] || 'bg-secondary';
  };

  // Loading state
  if (loading) {
    return (
      <div className="container mt-4">
        <h1>User Profile</h1>
        <div className="text-center my-5">
          <div className="spinner-border text-primary" role="status">
            <span className="visually-hidden">Loading profile...</span>
          </div>
        </div>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className="container mt-4">
        <h1>User Profile</h1>
        <div className="alert alert-danger d-flex align-items-center justify-content-between mt-3" role="alert">
          <div>
            <i className="bi bi-exclamation-triangle-fill me-2"></i>
            {error}
          </div>
          <button className="btn btn-outline-danger btn-sm" onClick={fetchProfile}>
            <i className="bi bi-arrow-clockwise me-1"></i>
            Retry
          </button>
        </div>
      </div>
    );
  }

  // Success state
  return (
    <div className="container mt-4">
      <h1>User Profile</h1>
      <div className="card mt-3">
        <div className="card-body">
          <div className="mb-3">
            <h5 className="card-title">Username</h5>
            <p className="card-text">{profile?.username}</p>
          </div>
          <div className="mb-3">
            <h5 className="card-title">Email</h5>
            <p className="card-text">{profile?.email}</p>
          </div>
          <div className="mb-3">
            <h5 className="card-title">Roles</h5>
            <div>
              {profile?.roles.map((role) => (
                <span key={role} className={`badge ${getRoleBadgeClass(role)} me-2`}>
                  {role}
                </span>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
