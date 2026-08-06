import React, { useState, useEffect } from 'react';
import {
  getSlackSettings,
  updateSlackSettings,
  sendSlackTestMessage,
  SECRET_MASK,
} from '../services/chatNotificationService';
import type { SlackSettings } from '../services/chatNotificationService';
import { validateWebhookUrl, validateChannel, resolveSlackDestination } from '../utils/chatValidation';
import NotificationEventSelector from './NotificationEventSelector';
import ChatDeliveryStatusLine from './ChatDeliveryStatusLine';

/**
 * Per-user Slack notification settings.
 *
 * Two independent choices: where messages go (personal webhook or a channel via the
 * workspace bot) and which events are reported. Subscriptions are stored per channel, so
 * what is ticked here is independent of the Telegram card.
 */
export default function SlackNotificationSettings() {
  const [settings, setSettings] = useState<SlackSettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Form state, kept separate from the loaded settings so an unsaved edit is never
  // mistaken for the persisted value.
  const [enabled, setEnabled] = useState(false);
  const [webhookUrl, setWebhookUrl] = useState('');
  const [clearWebhook, setClearWebhook] = useState(false);
  const [channel, setChannel] = useState('');
  const [selectedEvents, setSelectedEvents] = useState<string[]>([]);

  useEffect(() => {
    loadSettings();
  }, []);

  const applySettings = (data: SlackSettings) => {
    setSettings(data);
    setEnabled(data.enabled);
    setChannel(data.channel ?? '');
    setSelectedEvents(data.eventTypes);
    setWebhookUrl('');
    setClearWebhook(false);
  };

  const loadSettings = async () => {
    try {
      setLoading(true);
      setError(null);
      applySettings(await getSlackSettings());
    } catch (err: any) {
      setError(err?.response?.data?.error || err.message || 'Failed to load Slack settings');
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

    if (webhookUrl.trim().length > 0) {
      const webhookError = validateWebhookUrl(webhookUrl);
      if (webhookError) {
        setError(webhookError);
        return;
      }
    }
    if (channel.trim().length > 0) {
      const channelError = validateChannel(channel);
      if (channelError) {
        setError(channelError);
        return;
      }
    }

    // '' clears the stored URL, SECRET_MASK keeps it, anything else replaces it.
    let webhookPayload: string;
    if (clearWebhook) {
      webhookPayload = '';
    } else if (webhookUrl.trim().length > 0) {
      webhookPayload = webhookUrl.trim();
    } else {
      webhookPayload = SECRET_MASK;
    }

    try {
      setSaving(true);
      const updated = await updateSlackSettings({
        enabled,
        webhookUrl: webhookPayload,
        channel: channel.trim().length > 0 ? channel.trim() : null,
        eventTypes: selectedEvents,
      });
      applySettings(updated);
      setSuccessMessage('Slack settings saved');
      setTimeout(() => setSuccessMessage(null), 3000);
    } catch (err: any) {
      setError(err?.response?.data?.error || err.message || 'Failed to save Slack settings');
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    setError(null);
    setSuccessMessage(null);
    try {
      setTesting(true);
      const result = await sendSlackTestMessage();
      if (result.success) {
        setSuccessMessage(result.message);
      } else {
        setError(result.message);
      }
      // Refresh so the last-delivery line reflects the attempt just made.
      applySettings(await getSlackSettings());
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
    return <div className="alert alert-danger">Failed to load Slack settings</div>;
  }

  const destination = resolveSlackDestination({
    webhookUrlConfigured: settings.webhookUrlConfigured && !clearWebhook,
    pendingWebhookUrl: webhookUrl,
    channel,
    workspaceBotAvailable: settings.workspaceBotAvailable,
    workspaceDefaultChannel: settings.workspaceDefaultChannel,
  });

  return (
    <div className="card">
      <div className="card-header d-flex align-items-center">
        <i className="bi bi-slack me-2"></i>
        <h5 className="mb-0">Slack</h5>
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
            id="slackEnabled"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
            disabled={saving}
          />
          <label className="form-check-label" htmlFor="slackEnabled">
            <strong>Send me notifications via Slack</strong>
          </label>
        </div>

        <h6 className="text-muted text-uppercase small">Destination</h6>
        <hr className="mt-1" />

        <div className="mb-3">
          <label htmlFor="slackWebhookUrl" className="form-label">
            Personal incoming webhook URL
          </label>
          <input
            type="url"
            className="form-control"
            id="slackWebhookUrl"
            placeholder={
              settings.webhookUrlConfigured && !clearWebhook
                ? 'A webhook URL is stored — type a new one to replace it'
                : 'https://hooks.slack.com/services/...'
            }
            value={webhookUrl}
            onChange={(e) => setWebhookUrl(e.target.value)}
            disabled={saving || clearWebhook}
            autoComplete="off"
          />
          <div className="form-text">
            Create one under “Incoming Webhooks” in your Slack app. It is stored encrypted and
            never shown again.
          </div>
          {settings.webhookUrlConfigured && (
            <div className="form-check mt-2">
              <input
                className="form-check-input"
                type="checkbox"
                id="slackClearWebhook"
                checked={clearWebhook}
                onChange={(e) => {
                  setClearWebhook(e.target.checked);
                  if (e.target.checked) setWebhookUrl('');
                }}
                disabled={saving}
              />
              <label className="form-check-label" htmlFor="slackClearWebhook">
                Remove the stored webhook URL
              </label>
            </div>
          )}
        </div>

        <div className="mb-3">
          <label htmlFor="slackChannel" className="form-label">
            Channel or member ID
          </label>
          <input
            type="text"
            className="form-control"
            id="slackChannel"
            placeholder="#security-alerts"
            value={channel}
            onChange={(e) => setChannel(e.target.value)}
            disabled={saving || !settings.workspaceBotAvailable}
          />
          <div className="form-text">
            {settings.workspaceBotAvailable
              ? 'Used when no personal webhook URL is set. Delivered through the workspace Slack bot.'
              : 'The workspace Slack bot is not configured, so channel delivery is unavailable. Use a personal webhook URL.'}
          </div>
        </div>

        {destination.kind === 'none' ? (
          <div className="alert alert-warning py-2">
            <i className="bi bi-exclamation-triangle me-2"></i>
            {destination.reason}
          </div>
        ) : (
          <div className="alert alert-info py-2">
            <i className="bi bi-send me-2"></i>
            {destination.kind === 'webhook' && 'Messages go to your personal incoming webhook.'}
            {destination.kind === 'user-channel' && `Messages go to ${destination.channel}.`}
            {destination.kind === 'workspace-channel' &&
              `Messages go to the workspace default channel ${destination.channel}.`}
          </div>
        )}

        <h6 className="text-muted text-uppercase small mt-4">Report via Slack</h6>
        <hr className="mt-1" />

        <NotificationEventSelector
          idPrefix="slack"
          availableEventTypes={settings.availableEventTypes}
          selected={selectedEvents}
          disabled={saving}
          onToggle={toggleEvent}
        />

        <div className="d-flex gap-2 mt-4">
          <button type="button" className="btn btn-primary" onClick={handleSave} disabled={saving}>
            {saving ? 'Saving...' : 'Save Slack settings'}
          </button>
          <button
            type="button"
            className="btn btn-outline-secondary"
            onClick={handleTest}
            disabled={testing || saving || !settings.enabled}
            title={settings.enabled ? undefined : 'Enable and save Slack notifications first'}
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
