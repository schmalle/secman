import React, { useState, useEffect } from 'react';
import { getUserPreferences, updateUserPreferences } from '../services/notificationService';
import type { NotificationPreference } from '../services/notificationService';

export default function NotificationPreferences() {
  const [preferences, setPreferences] = useState<NotificationPreference | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  useEffect(() => {
    loadPreferences();
  }, []);

  const loadPreferences = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await getUserPreferences();
      setPreferences(data);
    } catch (err: any) {
      setError(err.message || 'Failed to load preferences');
    } finally {
      setLoading(false);
    }
  };

  const handleToggle = async (enableNewVulnNotifications: boolean) => {
    try {
      setSaving(true);
      setError(null);
      setSuccessMessage(null);

      const updated = await updateUserPreferences({
        enableNewVulnNotifications
      });

      setPreferences(updated);
      setSuccessMessage('Preferences saved successfully');

      // Clear success message after 3 seconds
      setTimeout(() => setSuccessMessage(null), 3000);
    } catch (err: any) {
      setError(err.message || 'Failed to save preferences');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="text-center py-4">
        <div className="spinner-border" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
      </div>
    );
  }

  if (!preferences) {
    return (
      <div className="alert alert-danger">
        Failed to load notification preferences
      </div>
    );
  }

  return (
    <div className="card">
      <div className="card-header">
        <h5 className="mb-0">Notification Preferences</h5>
      </div>
      <div className="card-body">
        {error && (
          <div className="alert alert-danger alert-dismissible fade show" role="alert">
            {error}
            <button
              type="button"
              className="btn-close"
              onClick={() => setError(null)}
              aria-label="Close"
            ></button>
          </div>
        )}

        {successMessage && (
          <div className="alert alert-success alert-dismissible fade show" role="alert">
            {successMessage}
            <button
              type="button"
              className="btn-close"
              onClick={() => setSuccessMessage(null)}
              aria-label="Close"
            ></button>
          </div>
        )}

        <div className="form-check form-switch">
          <input
            className="form-check-input"
            type="checkbox"
            role="switch"
            id="enableNewVulnNotifications"
            checked={preferences.enableNewVulnNotifications}
            onChange={(e) => handleToggle(e.target.checked)}
            disabled={saving}
          />
          <label className="form-check-label" htmlFor="enableNewVulnNotifications">
            <strong>Notify me of new vulnerabilities on my assets</strong>
          </label>
        </div>

        <div className="mt-3 text-muted small">
          <p>
            When enabled, you will receive email notifications when new vulnerabilities are
            discovered on assets you own. If you have multiple assets, notifications will be
            combined into a single email.
          </p>
          <p className="mb-0">
            <strong>Note:</strong> You will always receive reminders for outdated assets,
            regardless of this setting.
          </p>
        </div>

        {preferences.lastVulnNotificationSentAt && (
          <div className="mt-3">
            <small className="text-muted">
              Last vulnerability notification sent:{' '}
              {new Date(preferences.lastVulnNotificationSentAt).toLocaleString()}
            </small>
          </div>
        )}
      </div>
    </div>
  );
}
