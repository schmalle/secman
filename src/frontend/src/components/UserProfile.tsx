import React, { useState, useEffect } from 'react';
import userProfileService from '../services/userProfileService';
import type { UserProfileData } from '../services/userProfileService';
import PasskeyManagement from './PasskeyManagement';

/**
 * User Profile Component
 * Feature 028: User Profile Page
 * Feature 051: User Password Change
 *
 * Displays authenticated user's profile information:
 * - Username
 * - Email
 * - Roles (as colored badge pills)
 * - Password change form (for local users only)
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
  const [mfaEnabled, setMfaEnabled] = useState(false);
  const [mfaLoading, setMfaLoading] = useState(false);
  const [mfaError, setMfaError] = useState<string | null>(null);

  // Password change state (Feature 051)
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [passwordLoading, setPasswordLoading] = useState(false);
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [passwordSuccess, setPasswordSuccess] = useState<string | null>(null);
  const [showPasswordForm, setShowPasswordForm] = useState(false);

  useEffect(() => {
    fetchProfile();
  }, []);

  const fetchProfile = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await userProfileService.getProfile();
      setProfile(data);
      await fetchMfaStatus();
    } catch (err: any) {
      const errorMessage = err.response?.data?.message || 'Failed to load profile. Please try again.';
      setError(errorMessage);
    } finally {
      setLoading(false);
    }
  };

  const fetchMfaStatus = async () => {
    try {
      const status = await userProfileService.getMfaStatus();
      setMfaEnabled(status.enabled);
    } catch (err: any) {
      console.error('Failed to fetch MFA status', err);
    }
  };

  const handleMfaToggle = async () => {
    try {
      setMfaLoading(true);
      setMfaError(null);
      const result = await userProfileService.toggleMfa(!mfaEnabled);
      setMfaEnabled(result.mfaEnabled);
    } catch (err: any) {
      const errorMessage = err.response?.data?.error || err.response?.data?.message || 'Failed to toggle MFA';
      setMfaError(errorMessage);
    } finally {
      setMfaLoading(false);
    }
  };

  /**
   * Handle password change submission
   * Feature 051: User Password Change
   */
  const handlePasswordChange = async (e: React.FormEvent) => {
    e.preventDefault();
    setPasswordError(null);
    setPasswordSuccess(null);

    // Client-side validation
    if (newPassword.length < 8) {
      setPasswordError('Password must be at least 8 characters');
      return;
    }

    if (newPassword !== confirmPassword) {
      setPasswordError('New password and confirmation do not match');
      return;
    }

    try {
      setPasswordLoading(true);
      const result = await userProfileService.changePassword({
        currentPassword,
        newPassword,
        confirmPassword
      });
      setPasswordSuccess(result.message);
      // Clear form on success
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
      setShowPasswordForm(false);
    } catch (err: any) {
      const errorMessage = err.response?.data?.message || err.response?.data?.error || 'Failed to change password';
      setPasswordError(errorMessage);
    } finally {
      setPasswordLoading(false);
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

      {/* Security Settings Card */}
      <div className="card mt-3">
        <div className="card-body">
          <h5 className="card-title">Security Settings</h5>

          {/* MFA Toggle */}
          <div className="mb-4">
            <div className="d-flex align-items-center justify-content-between">
              <div>
                <h6>Multi-Factor Authentication (MFA)</h6>
                <p className="text-muted small mb-0">
                  Enhance your account security with passkey-based MFA
                </p>
              </div>
              <div className="form-check form-switch">
                <input
                  className="form-check-input"
                  type="checkbox"
                  role="switch"
                  id="mfaToggle"
                  checked={mfaEnabled}
                  onChange={handleMfaToggle}
                  disabled={mfaLoading}
                  style={{ cursor: mfaLoading ? 'wait' : 'pointer', width: '3rem', height: '1.5rem' }}
                />
                <label className="form-check-label" htmlFor="mfaToggle" style={{ marginLeft: '0.5rem' }}>
                  {mfaEnabled ? 'Enabled' : 'Disabled'}
                </label>
              </div>
            </div>
            {mfaError && (
              <div className="alert alert-danger mt-2 mb-0" role="alert">
                <i className="bi bi-exclamation-triangle-fill me-2"></i>
                {mfaError}
              </div>
            )}
          </div>

          {/* Passkey Management */}
          <div className="border-top pt-3">
            <h6>Passkeys</h6>
            <p className="text-muted small">
              Manage your registered passkeys for passwordless authentication
            </p>
            <PasskeyManagement client:load />
          </div>

          {/* Password Change - Feature 051 */}
          {profile?.canChangePassword && (
            <div className="border-top pt-3 mt-3">
              <div className="d-flex align-items-center justify-content-between">
                <div>
                  <h6>Change Password</h6>
                  <p className="text-muted small mb-0">
                    Update your account password
                  </p>
                </div>
                <button
                  className="btn btn-outline-primary btn-sm"
                  onClick={() => {
                    setShowPasswordForm(!showPasswordForm);
                    setPasswordError(null);
                    setPasswordSuccess(null);
                  }}
                >
                  {showPasswordForm ? 'Cancel' : 'Change Password'}
                </button>
              </div>

              {passwordSuccess && (
                <div className="alert alert-success mt-3 mb-0" role="alert">
                  <i className="bi bi-check-circle-fill me-2"></i>
                  {passwordSuccess}
                </div>
              )}

              {showPasswordForm && (
                <form onSubmit={handlePasswordChange} className="mt-3">
                  <div className="mb-3">
                    <label htmlFor="currentPassword" className="form-label">Current Password</label>
                    <input
                      type="password"
                      className="form-control"
                      id="currentPassword"
                      value={currentPassword}
                      onChange={(e) => setCurrentPassword(e.target.value)}
                      required
                      disabled={passwordLoading}
                    />
                  </div>
                  <div className="mb-3">
                    <label htmlFor="newPassword" className="form-label">New Password</label>
                    <input
                      type="password"
                      className="form-control"
                      id="newPassword"
                      value={newPassword}
                      onChange={(e) => setNewPassword(e.target.value)}
                      required
                      minLength={8}
                      disabled={passwordLoading}
                    />
                    <div className="form-text">Minimum 8 characters</div>
                  </div>
                  <div className="mb-3">
                    <label htmlFor="confirmPassword" className="form-label">Confirm New Password</label>
                    <input
                      type="password"
                      className="form-control"
                      id="confirmPassword"
                      value={confirmPassword}
                      onChange={(e) => setConfirmPassword(e.target.value)}
                      required
                      minLength={8}
                      disabled={passwordLoading}
                    />
                  </div>

                  {passwordError && (
                    <div className="alert alert-danger" role="alert">
                      <i className="bi bi-exclamation-triangle-fill me-2"></i>
                      {passwordError}
                    </div>
                  )}

                  <button
                    type="submit"
                    className="btn btn-primary"
                    disabled={passwordLoading || !currentPassword || !newPassword || !confirmPassword}
                  >
                    {passwordLoading ? (
                      <>
                        <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                        Changing Password...
                      </>
                    ) : (
                      'Update Password'
                    )}
                  </button>
                </form>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
