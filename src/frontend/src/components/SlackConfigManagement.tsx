import React, { useState, useEffect } from 'react';
import {
  getSlackConfig,
  updateSlackConfig,
  sendSlackConfigTestMessage,
  SECRET_MASK,
} from '../services/chatNotificationService';
import type { SlackConfig } from '../services/chatNotificationService';
import { validateChannel } from '../utils/chatValidation';

/**
 * Workspace-level Slack configuration (ADMIN).
 *
 * Optional: without a bot token users can still receive Slack notifications through
 * their own incoming webhook. Configuring one here additionally lets users pick a
 * channel by name.
 */
export default function SlackConfigManagement() {
  const [config, setConfig] = useState<SlackConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const [enabled, setEnabled] = useState(false);
  const [botToken, setBotToken] = useState('');
  const [clearToken, setClearToken] = useState(false);
  const [defaultChannel, setDefaultChannel] = useState('');

  useEffect(() => {
    loadConfig();
  }, []);

  const applyConfig = (data: SlackConfig) => {
    setConfig(data);
    setEnabled(data.enabled);
    setDefaultChannel(data.defaultChannel ?? '');
    setBotToken('');
    setClearToken(false);
  };

  const loadConfig = async () => {
    try {
      setLoading(true);
      setError(null);
      applyConfig(await getSlackConfig());
    } catch (err: any) {
      setError(err?.response?.data?.error || err.message || 'Failed to load Slack configuration');
    } finally {
      setLoading(false);
    }
  };

  const handleSave = async () => {
    setError(null);
    setSuccessMessage(null);

    if (defaultChannel.trim().length > 0) {
      const channelError = validateChannel(defaultChannel);
      if (channelError) {
        setError(channelError);
        return;
      }
    }

    let tokenPayload: string;
    if (clearToken) {
      tokenPayload = '';
    } else if (botToken.trim().length > 0) {
      tokenPayload = botToken.trim();
    } else {
      tokenPayload = SECRET_MASK;
    }

    try {
      setSaving(true);
      applyConfig(
        await updateSlackConfig({
          enabled,
          botToken: tokenPayload,
          defaultChannel: defaultChannel.trim().length > 0 ? defaultChannel.trim() : null,
        })
      );
      setSuccessMessage('Slack configuration saved');
      setTimeout(() => setSuccessMessage(null), 3000);
    } catch (err: any) {
      setError(err?.response?.data?.error || err.message || 'Failed to save Slack configuration');
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    setError(null);
    setSuccessMessage(null);
    try {
      setTesting(true);
      const result = await sendSlackConfigTestMessage();
      if (result.success) {
        setSuccessMessage(result.message);
      } else {
        setError(result.message);
      }
    } catch (err: any) {
      setError(err?.response?.data?.error || err.message || 'Failed to send test message');
    } finally {
      setTesting(false);
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

  if (!config) {
    return <div className="alert alert-danger">Failed to load Slack configuration</div>;
  }

  return (
    <div className="card">
      <div className="card-header d-flex align-items-center">
        <i className="bi bi-slack me-2"></i>
        <h5 className="mb-0">Slack Workspace Configuration</h5>
      </div>
      <div className="card-body">
        {error && (
          <div className="alert alert-danger alert-dismissible fade show" role="alert">
            {error}
            <button type="button" className="btn-close" onClick={() => setError(null)} aria-label="Close"></button>
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

        <p className="text-muted">
          Optional. Users can always receive Slack notifications through their own incoming
          webhook URL. Configuring a bot token here additionally lets them choose a channel by
          name on the <a href="/chat-notifications">Chat Notifications</a> page.
        </p>

        <div className="form-check form-switch mb-4">
          <input
            className="form-check-input"
            type="checkbox"
            role="switch"
            id="slackConfigEnabled"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
            disabled={saving}
          />
          <label className="form-check-label" htmlFor="slackConfigEnabled">
            <strong>Enable workspace Slack delivery</strong>
          </label>
        </div>

        <div className="mb-3">
          <label htmlFor="slackBotToken" className="form-label">
            Bot token
          </label>
          <input
            type="password"
            className="form-control"
            id="slackBotToken"
            placeholder={
              config.botTokenConfigured && !clearToken
                ? 'A bot token is stored — type a new one to replace it'
                : 'xoxb-...'
            }
            value={botToken}
            onChange={(e) => setBotToken(e.target.value)}
            disabled={saving || clearToken}
            autoComplete="new-password"
          />
          <div className="form-text">
            Requires the <code>chat:write</code> scope. Stored encrypted and never shown again.
          </div>
          {config.botTokenConfigured && (
            <div className="form-check mt-2">
              <input
                className="form-check-input"
                type="checkbox"
                id="slackClearToken"
                checked={clearToken}
                onChange={(e) => {
                  setClearToken(e.target.checked);
                  if (e.target.checked) setBotToken('');
                }}
                disabled={saving}
              />
              <label className="form-check-label" htmlFor="slackClearToken">
                Remove the stored bot token
              </label>
            </div>
          )}
        </div>

        <div className="mb-3">
          <label htmlFor="slackDefaultChannel" className="form-label">
            Default channel
          </label>
          <input
            type="text"
            className="form-control"
            id="slackDefaultChannel"
            placeholder="#security-alerts"
            value={defaultChannel}
            onChange={(e) => setDefaultChannel(e.target.value)}
            disabled={saving}
          />
          <div className="form-text">
            Used for subscribers who set neither a webhook URL nor a channel of their own.
          </div>
        </div>

        <div className="d-flex gap-2 mt-4">
          <button type="button" className="btn btn-primary" onClick={handleSave} disabled={saving}>
            {saving ? 'Saving...' : 'Save configuration'}
          </button>
          <button
            type="button"
            className="btn btn-outline-secondary"
            onClick={handleTest}
            disabled={testing || saving || !config.enabled || !config.botTokenConfigured}
            title={
              config.enabled && config.botTokenConfigured
                ? undefined
                : 'Save an enabled configuration with a bot token first'
            }
          >
            {testing ? 'Sending...' : 'Send test message'}
          </button>
        </div>
      </div>
    </div>
  );
}
