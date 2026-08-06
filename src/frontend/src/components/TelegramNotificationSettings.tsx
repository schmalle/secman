import React, { useState, useEffect } from 'react';
import {
  getTelegramSettings,
  updateTelegramSettings,
  sendTelegramTestMessage,
  SECRET_MASK,
} from '../services/chatNotificationService';
import type { TelegramSettings } from '../services/chatNotificationService';
import {
  validateTelegramChatId,
  validateTelegramBotToken,
  resolveTelegramDestination,
} from '../utils/chatValidation';
import NotificationEventSelector from './NotificationEventSelector';
import ChatDeliveryStatusLine from './ChatDeliveryStatusLine';

/**
 * Per-user Telegram notification settings.
 *
 * Telegram delivers to a *conversation*, so the chat ID is always required; the bot token
 * is the workspace one unless the user runs their own. Subscriptions are stored per
 * channel, so what is ticked here is independent of the Slack card.
 */
export default function TelegramNotificationSettings() {
  const [settings, setSettings] = useState<TelegramSettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const [enabled, setEnabled] = useState(false);
  const [chatId, setChatId] = useState('');
  const [botToken, setBotToken] = useState('');
  const [clearBotToken, setClearBotToken] = useState(false);
  const [selectedEvents, setSelectedEvents] = useState<string[]>([]);

  useEffect(() => {
    loadSettings();
  }, []);

  const applySettings = (data: TelegramSettings) => {
    setSettings(data);
    setEnabled(data.enabled);
    setChatId(data.chatId ?? '');
    setSelectedEvents(data.eventTypes);
    setBotToken('');
    setClearBotToken(false);
  };

  const loadSettings = async () => {
    try {
      setLoading(true);
      setError(null);
      applySettings(await getTelegramSettings());
    } catch (err: any) {
      setError(err?.response?.data?.error || err.message || 'Failed to load Telegram settings');
    } finally {
      setLoading(false);
    }
  };

  const toggleEvent = (name: string) => {
    setSelectedEvents((current) =>
      current.includes(name) ? current.filter((e) => e !== name) : [...current, name]
    );
  };

  const handleSave = async () => {
    setError(null);
    setSuccessMessage(null);

    if (chatId.trim().length > 0) {
      const chatIdError = validateTelegramChatId(chatId);
      if (chatIdError) {
        setError(chatIdError);
        return;
      }
    }
    if (botToken.trim().length > 0) {
      const tokenError = validateTelegramBotToken(botToken);
      if (tokenError) {
        setError(tokenError);
        return;
      }
    }

    // '' clears the stored token, SECRET_MASK keeps it, anything else replaces it.
    let tokenPayload: string;
    if (clearBotToken) {
      tokenPayload = '';
    } else if (botToken.trim().length > 0) {
      tokenPayload = botToken.trim();
    } else {
      tokenPayload = SECRET_MASK;
    }

    try {
      setSaving(true);
      const updated = await updateTelegramSettings({
        enabled,
        chatId: chatId.trim().length > 0 ? chatId.trim() : null,
        botToken: tokenPayload,
        eventTypes: selectedEvents,
      });
      applySettings(updated);
      setSuccessMessage('Telegram settings saved');
      setTimeout(() => setSuccessMessage(null), 3000);
    } catch (err: any) {
      setError(err?.response?.data?.error || err.message || 'Failed to save Telegram settings');
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    setError(null);
    setSuccessMessage(null);
    try {
      setTesting(true);
      const result = await sendTelegramTestMessage();
      if (result.success) {
        setSuccessMessage(result.message);
      } else {
        setError(result.message);
      }
      // Refresh so the last-delivery line reflects the attempt just made.
      applySettings(await getTelegramSettings());
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

  if (!settings) {
    return <div className="alert alert-danger">Failed to load Telegram settings</div>;
  }

  const destination = resolveTelegramDestination({
    chatId,
    botTokenConfigured: settings.botTokenConfigured && !clearBotToken,
    pendingBotToken: botToken,
    workspaceBotAvailable: settings.workspaceBotAvailable,
  });

  return (
    <div className="card">
      <div className="card-header d-flex align-items-center">
        <i className="bi bi-telegram me-2"></i>
        <h5 className="mb-0">Telegram</h5>
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

        <div className="form-check form-switch mb-4">
          <input
            className="form-check-input"
            type="checkbox"
            role="switch"
            id="telegramEnabled"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
            disabled={saving}
          />
          <label className="form-check-label" htmlFor="telegramEnabled">
            <strong>Send me notifications via Telegram</strong>
          </label>
        </div>

        <h6 className="text-muted text-uppercase small">Destination</h6>
        <hr className="mt-1" />

        <div className="mb-3">
          <label htmlFor="telegramChatId" className="form-label">
            Chat ID
          </label>
          <input
            type="text"
            className="form-control"
            id="telegramChatId"
            placeholder="123456789"
            value={chatId}
            onChange={(e) => setChatId(e.target.value)}
            disabled={saving}
          />
          <div className="form-text">
            Start a chat with the bot, then send it <code>/start</code> — it replies with your
            numeric chat ID. Group chats use a negative ID such as <code>-1001234567890</code>.
          </div>
        </div>

        <div className="mb-3">
          <label htmlFor="telegramBotToken" className="form-label">
            Personal bot token <span className="text-muted">(optional)</span>
          </label>
          <input
            type="password"
            className="form-control"
            id="telegramBotToken"
            placeholder={
              settings.botTokenConfigured && !clearBotToken
                ? 'A bot token is stored — type a new one to replace it'
                : '123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ'
            }
            value={botToken}
            onChange={(e) => setBotToken(e.target.value)}
            disabled={saving || clearBotToken}
            autoComplete="new-password"
          />
          <div className="form-text">
            {settings.workspaceBotAvailable
              ? 'Leave empty to use the workspace bot. Set one only if you run your own bot.'
              : 'The workspace Telegram bot is not configured, so your own bot token is required.'}{' '}
            Stored encrypted and never shown again.
          </div>
          {settings.botTokenConfigured && (
            <div className="form-check mt-2">
              <input
                className="form-check-input"
                type="checkbox"
                id="telegramClearBotToken"
                checked={clearBotToken}
                onChange={(e) => {
                  setClearBotToken(e.target.checked);
                  if (e.target.checked) setBotToken('');
                }}
                disabled={saving}
              />
              <label className="form-check-label" htmlFor="telegramClearBotToken">
                Remove the stored bot token
              </label>
            </div>
          )}
        </div>

        {destination.kind === 'none' ? (
          <div className="alert alert-warning py-2">
            <i className="bi bi-exclamation-triangle me-2"></i>
            {destination.reason}
          </div>
        ) : (
          <div className="alert alert-info py-2">
            <i className="bi bi-send me-2"></i>
            {destination.kind === 'personal-bot'
              ? `Messages go to chat ${destination.chatId} via your own bot.`
              : `Messages go to chat ${destination.chatId} via the workspace bot.`}
          </div>
        )}

        <h6 className="text-muted text-uppercase small mt-4">Report via Telegram</h6>
        <hr className="mt-1" />

        <NotificationEventSelector
          idPrefix="telegram"
          availableEventTypes={settings.availableEventTypes}
          selected={selectedEvents}
          disabled={saving}
          onToggle={toggleEvent}
        />

        <div className="d-flex gap-2 mt-4">
          <button type="button" className="btn btn-primary" onClick={handleSave} disabled={saving}>
            {saving ? 'Saving...' : 'Save Telegram settings'}
          </button>
          <button
            type="button"
            className="btn btn-outline-secondary"
            onClick={handleTest}
            disabled={testing || saving || !settings.enabled}
            title={settings.enabled ? undefined : 'Enable and save Telegram notifications first'}
          >
            {testing ? 'Sending...' : 'Send test message'}
          </button>
        </div>

        <ChatDeliveryStatusLine
          lastNotifiedAt={settings.lastNotifiedAt}
          lastDeliveryStatus={settings.lastDeliveryStatus}
          lastDeliveryError={settings.lastDeliveryError}
        />
      </div>
    </div>
  );
}
