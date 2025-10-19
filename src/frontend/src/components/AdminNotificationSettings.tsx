import React, { useState, useEffect } from 'react';
import {
  getNotificationSettings,
  updateNotificationSettings,
  type NotificationSettingsDto
} from '../services/adminNotificationSettingsService';

/**
 * Admin Notification Settings Component
 * Feature: 027-admin-user-notifications
 *
 * Allows ADMIN users to configure email notifications for new user registrations.
 * Displays a toggle to enable/disable notifications and an input for sender email.
 */
const AdminNotificationSettings: React.FC = () => {
  const [settings, setSettings] = useState<NotificationSettingsDto>({
    enabled: true,
    senderEmail: 'noreply@secman.local'
  });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Form state for controlled inputs
  const [enabled, setEnabled] = useState(true);
  const [senderEmail, setSenderEmail] = useState('noreply@secman.local');

  // Load settings on component mount
  useEffect(() => {
    loadSettings();
  }, []);

  const loadSettings = async () => {
    try {
      setLoading(true);
      setError(null);

      console.log('[AdminNotificationSettings] Loading notification settings...');
      const data = await getNotificationSettings();

      console.log('[AdminNotificationSettings] Settings loaded:', data);
      setSettings(data);
      setEnabled(data.enabled);
      setSenderEmail(data.senderEmail);
    } catch (err) {
      console.error('[AdminNotificationSettings] Failed to load settings:', err);
      const errorMessage = err instanceof Error ? err.message : 'Failed to load notification settings';
      setError(errorMessage);
    } finally {
      setLoading(false);
    }
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();

    // Clear previous messages
    setError(null);
    setSuccessMessage(null);

    // Client-side validation
    if (!senderEmail.trim()) {
      setError('Sender email is required');
      return;
    }

    // Basic email format validation
    const emailRegex = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/;
    if (!emailRegex.test(senderEmail)) {
      setError('Invalid email format');
      return;
    }

    try {
      setSaving(true);
      console.log('[AdminNotificationSettings] Saving settings:', { enabled, senderEmail });

      const updatedSettings = await updateNotificationSettings({
        enabled,
        senderEmail
      });

      console.log('[AdminNotificationSettings] Settings saved successfully:', updatedSettings);
      setSettings(updatedSettings);
      setSuccessMessage('Settings saved successfully!');

      // Clear success message after 3 seconds
      setTimeout(() => setSuccessMessage(null), 3000);
    } catch (err) {
      console.error('[AdminNotificationSettings] Failed to save settings:', err);
      const errorMessage = err instanceof Error ? err.message : 'Failed to save settings';
      setError(errorMessage);
    } finally {
      setSaving(false);
    }
  };

  const handleRefresh = () => {
    setError(null);
    setSuccessMessage(null);
    loadSettings();
  };

  if (loading) {
    return (
      <div className="card">
        <div className="card-header">
          <h5 className="card-title mb-0">User Notification Settings</h5>
        </div>
        <div className="card-body">
          <div className="d-flex justify-content-center align-items-center" style={{ minHeight: '150px' }}>
            <div className="spinner-border text-primary" role="status">
              <span className="visually-hidden">Loading...</span>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="card">
      <div className="card-header d-flex justify-content-between align-items-center">
        <h5 className="card-title mb-0">User Notification Settings</h5>
        <button
          type="button"
          className="btn btn-sm btn-outline-secondary"
          onClick={handleRefresh}
          disabled={saving}
          title="Refresh settings"
        >
          <i className="bi bi-arrow-clockwise"></i> Refresh
        </button>
      </div>
      <div className="card-body">
        {/* Error Alert */}
        {error && (
          <div className="alert alert-danger alert-dismissible fade show" role="alert">
            <i className="bi bi-exclamation-triangle-fill me-2"></i>
            {error}
            <button
              type="button"
              className="btn-close"
              onClick={() => setError(null)}
              aria-label="Close"
            ></button>
          </div>
        )}

        {/* Success Alert */}
        {successMessage && (
          <div className="alert alert-success alert-dismissible fade show" role="alert">
            <i className="bi bi-check-circle-fill me-2"></i>
            {successMessage}
            <button
              type="button"
              className="btn-close"
              onClick={() => setSuccessMessage(null)}
              aria-label="Close"
            ></button>
          </div>
        )}

        <p className="text-muted">
          Configure email notifications sent to all ADMIN users when new users are created
          (via OAuth or manual creation through the "Manage Users" interface).
        </p>

        <form onSubmit={handleSave}>
          {/* Enable/Disable Toggle */}
          <div className="mb-4">
            <div className="form-check form-switch">
              <input
                className="form-check-input"
                type="checkbox"
                id="notificationsEnabled"
                checked={enabled}
                onChange={(e) => setEnabled(e.target.checked)}
                disabled={saving}
              />
              <label className="form-check-label" htmlFor="notificationsEnabled">
                <strong>Enable notifications for new user registrations</strong>
              </label>
            </div>
            <small className="form-text text-muted ms-4">
              {enabled
                ? 'Notifications are enabled. ADMIN users will receive emails when new users are created.'
                : 'Notifications are disabled. No emails will be sent for new user registrations.'}
            </small>
          </div>

          {/* Sender Email Input */}
          <div className="mb-4">
            <label htmlFor="senderEmail" className="form-label">
              <strong>Sender Email Address</strong>
            </label>
            <input
              type="email"
              className="form-control"
              id="senderEmail"
              value={senderEmail}
              onChange={(e) => setSenderEmail(e.target.value)}
              disabled={saving}
              placeholder="noreply@secman.local"
              required
            />
            <small className="form-text text-muted">
              Email address used in the "From:" field of notification emails sent to ADMIN users.
            </small>
          </div>

          {/* Save Button */}
          <div className="d-flex justify-content-end">
            <button
              type="submit"
              className="btn btn-primary"
              disabled={saving}
            >
              {saving ? (
                <>
                  <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                  Saving...
                </>
              ) : (
                <>
                  <i className="bi bi-save me-2"></i>
                  Save Settings
                </>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default AdminNotificationSettings;
