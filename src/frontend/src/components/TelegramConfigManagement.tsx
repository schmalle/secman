import React, { useState, useEffect } from 'react';
import {
  getTelegramConfig,
  updateTelegramConfig,
  SECRET_MASK,
} from '../services/chatNotificationService';
import type { TelegramConfig } from '../services/chatNotificationService';
import { validateTelegramBotToken } from '../utils/chatValidation';

/**
 * Workspace-level Telegram configuration (ADMIN).
 *
 * Optional: a user who runs their own bot can store a personal token instead. Configuring
 * a shared bot here means users only need to supply their chat ID.
 *
 * There is no test button here, unlike Slack: a Telegram bot token addresses no
 * conversation on its own, so a meaningful test needs a user's chat ID and lives on the
 * Chat Notifications page.
 */
export default function TelegramConfigManagement() {
  const [config, setConfig] = useState<TelegramConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const [enabled, setEnabled] = useState(false);
  const [botToken, setBotToken] = useState('');
  const [clearToken, setClearToken] = useState(false);

  useEffect(() => {
    loadConfig();
  }, []);

  const applyConfig = (data: TelegramConfig) => {
    setConfig(data);
    setEnabled(data.enabled);
    setBotToken('');
    setClearToken(false);
  };

  const loadConfig = async () => {
    try {
      setLoading(true);
      setError(null);
      applyConfig(await getTelegramConfig());
    } catch (err: any) {
      setError(err?.response?.data?.error || err.message || 'Failed to load Telegram configuration');
    } finally {
      setLoading(false);
    }
  };

  const handleSave = async () => {
    setError(null);
    setSuccessMessage(null);

    if (botToken.trim().length > 0) {
      const tokenError = validateTelegramBotToken(botToken);
      if (tokenError) {
        setError(tokenError);
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
      applyConfig(await updateTelegramConfig({ enabled, botToken: tokenPayload }));
      setSuccessMessage('Telegram configuration saved');
      setTimeout(() => setSuccessMessage(null), 3000);
    } catch (err: any) {
      setError(err?.response?.data?.error || err.message || 'Failed to save Telegram configuration');
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

  if (!config) {
    return <div className="alert alert-danger">Failed to load Telegram configuration</div>;
  }

  return (
    <div className="card">
      <div className="card-header d-flex align-items-center">
        <i className="bi bi-telegram me-2"></i>
        <h5 className="mb-0">Telegram Workspace Configuration</h5>
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
          Optional. Users can supply their own bot token instead. With a shared bot configured
          here, they only need to enter their chat ID on the{' '}
          <a href="/chat-notifications">Chat Notifications</a> page.
        </p>

        <div className="form-check form-switch mb-4">
          <input
            className="form-check-input"
            type="checkbox"
            role="switch"
            id="telegramConfigEnabled"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
            disabled={saving}
          />
          <label className="form-check-label" htmlFor="telegramConfigEnabled">
            <strong>Enable workspace Telegram delivery</strong>
          </label>
        </div>

        <div className="mb-3">
          <label htmlFor="telegramConfigBotToken" className="form-label">
            Bot token
          </label>
          <input
            type="password"
            className="form-control"
            id="telegramConfigBotToken"
            placeholder={
              config.botTokenConfigured && !clearToken
                ? 'A bot token is stored — type a new one to replace it'
                : '123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ'
            }
            value={botToken}
            onChange={(e) => setBotToken(e.target.value)}
            disabled={saving || clearToken}
            autoComplete="new-password"
          />
          <div className="form-text">
            Create a bot with <code>@BotFather</code> and paste the token it gives you. Stored
            encrypted and never shown again.
          </div>
          {config.botTokenConfigured && (
            <div className="form-check mt-2">
              <input
                className="form-check-input"
                type="checkbox"
                id="telegramConfigClearToken"
                checked={clearToken}
                onChange={(e) => {
                  setClearToken(e.target.checked);
                  if (e.target.checked) setBotToken('');
                }}
                disabled={saving}
              />
              <label className="form-check-label" htmlFor="telegramConfigClearToken">
                Remove the stored bot token
              </label>
            </div>
          )}
        </div>

        <div className="d-flex gap-2 mt-4">
          <button type="button" className="btn btn-primary" onClick={handleSave} disabled={saving}>
            {saving ? 'Saving...' : 'Save configuration'}
          </button>
        </div>
      </div>
    </div>
  );
}
