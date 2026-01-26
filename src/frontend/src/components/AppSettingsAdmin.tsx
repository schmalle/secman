import React, { useState, useEffect } from 'react';
import {
  getAppSettings,
  updateAppSettings,
  type AppSettingsDto
} from '../services/appSettingsService';

/**
 * Application Settings Admin Component
 * Feature: 068-requirements-alignment-process
 *
 * Allows ADMIN users to configure application-wide settings like the base URL
 * used in email notifications (alignment review links, etc.).
 */
const AppSettingsAdmin: React.FC = () => {
  const [settings, setSettings] = useState<AppSettingsDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Form state
  const [baseUrl, setBaseUrl] = useState('');

  // Load settings on component mount
  useEffect(() => {
    loadSettings();
  }, []);

  const loadSettings = async () => {
    try {
      setLoading(true);
      setError(null);

      console.log('[AppSettingsAdmin] Loading application settings...');
      const data = await getAppSettings();

      console.log('[AppSettingsAdmin] Settings loaded:', data);
      setSettings(data);
      setBaseUrl(data.baseUrl);
    } catch (err) {
      console.error('[AppSettingsAdmin] Failed to load settings:', err);
      const errorMessage = err instanceof Error ? err.message : 'Failed to load application settings';
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
    if (!baseUrl.trim()) {
      setError('Base URL is required');
      return;
    }

    // URL format validation
    const urlRegex = /^https?:\/\/[a-zA-Z0-9][-a-zA-Z0-9.]*[a-zA-Z0-9](:[0-9]+)?(\/.*)?$/;
    if (!urlRegex.test(baseUrl)) {
      setError('Invalid URL format. Must start with http:// or https://');
      return;
    }

    try {
      setSaving(true);
      console.log('[AppSettingsAdmin] Saving settings:', { baseUrl });

      const updatedSettings = await updateAppSettings(baseUrl.replace(/\/$/, ''));

      console.log('[AppSettingsAdmin] Settings saved successfully:', updatedSettings);
      setSettings(updatedSettings);
      setBaseUrl(updatedSettings.baseUrl);
      setSuccessMessage('Settings saved successfully!');

      // Clear success message after 3 seconds
      setTimeout(() => setSuccessMessage(null), 3000);
    } catch (err) {
      console.error('[AppSettingsAdmin] Failed to save settings:', err);
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
          <h5 className="card-title mb-0">Application Settings</h5>
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
        <h5 className="card-title mb-0">Application Settings</h5>
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
          Configure application-wide settings. The base URL is used for generating links
          in email notifications (e.g., alignment review request emails).
        </p>

        <form onSubmit={handleSave}>
          {/* Base URL Input */}
          <div className="mb-4">
            <label htmlFor="baseUrl" className="form-label">
              <strong>Application Base URL</strong>
            </label>
            <input
              type="url"
              className="form-control"
              id="baseUrl"
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              disabled={saving}
              placeholder="https://secman.example.com"
              required
            />
            <small className="form-text text-muted">
              The base URL of your SecMan installation. This is used for generating links in email
              notifications. Example: <code>https://secman.example.com</code>
            </small>
          </div>

          {/* Last Updated Info */}
          {settings?.updatedBy && (
            <div className="mb-4 text-muted small">
              <i className="bi bi-info-circle me-1"></i>
              Last updated by <strong>{settings.updatedBy}</strong>
              {settings.updatedAt && (
                <> on {new Date(settings.updatedAt).toLocaleString()}</>
              )}
            </div>
          )}

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

export default AppSettingsAdmin;
