import axios from 'axios';

// API base URL - always use relative URLs to go through Astro's proxy and avoid CORS issues
const API_BASE_URL = import.meta.env.PUBLIC_API_URL || '';

/**
 * Sentinel the backend sends in place of a stored credential, and accepts back verbatim
 * to mean "keep the stored value". Mirrors UserSlackSettings.WEBHOOK_MASK,
 * SlackConfig.TOKEN_MASK, UserTelegramSettings.TOKEN_MASK and TelegramConfig.TOKEN_MASK —
 * all deliberately the same string.
 */
export const SECRET_MASK = '***HIDDEN***';

export interface NotificationEventType {
  name: string;
  displayName: string;
  description: string;
}

/* ---------------------------------------------------------------- Slack */

export interface SlackSettings {
  enabled: boolean;
  /** The webhook URL itself is never returned — only whether one is stored. */
  webhookUrlConfigured: boolean;
  channel: string | null;
  eventTypes: string[];
  lastNotifiedAt: string | null;
  lastDeliveryStatus: string | null;
  lastDeliveryError: string | null;
  workspaceBotAvailable: boolean;
  workspaceDefaultChannel: string | null;
  availableEventTypes: NotificationEventType[];
}

export interface UpdateSlackSettingsRequest {
  enabled: boolean;
  /** Omit or send SECRET_MASK to keep the stored URL; send '' to clear it. */
  webhookUrl?: string | null;
  channel?: string | null;
  eventTypes: string[];
}

export interface SlackConfig {
  enabled: boolean;
  botTokenConfigured: boolean;
  defaultChannel: string | null;
}

export interface UpdateSlackConfigRequest {
  enabled: boolean;
  /** Omit or send SECRET_MASK to keep the stored token; send '' to clear it. */
  botToken?: string | null;
  defaultChannel?: string | null;
}

/* ------------------------------------------------------------- Telegram */

export interface TelegramSettings {
  enabled: boolean;
  chatId: string | null;
  /** The personal bot token itself is never returned — only whether one is stored. */
  botTokenConfigured: boolean;
  eventTypes: string[];
  lastNotifiedAt: string | null;
  lastDeliveryStatus: string | null;
  lastDeliveryError: string | null;
  workspaceBotAvailable: boolean;
  availableEventTypes: NotificationEventType[];
}

export interface UpdateTelegramSettingsRequest {
  enabled: boolean;
  chatId?: string | null;
  /** Omit or send SECRET_MASK to keep the stored token; send '' to clear it. */
  botToken?: string | null;
  eventTypes: string[];
}

export interface TelegramConfig {
  enabled: boolean;
  botTokenConfigured: boolean;
}

export interface UpdateTelegramConfigRequest {
  enabled: boolean;
  /** Omit or send SECRET_MASK to keep the stored token; send '' to clear it. */
  botToken?: string | null;
}

/* ---------------------------------------------------------------- Shared */

export interface ChatTestResult {
  success: boolean;
  message: string;
}

const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

/** Current user's Slack settings, plus the catalogue of subscribable events. */
export async function getSlackSettings(): Promise<SlackSettings> {
  const response = await axios.get(`${API_BASE_URL}/api/slack/settings`);
  return response.data;
}

export async function updateSlackSettings(
  request: UpdateSlackSettingsRequest
): Promise<SlackSettings> {
  const response = await axios.put(`${API_BASE_URL}/api/slack/settings`, request, JSON_HEADERS);
  return response.data;
}

export async function sendSlackTestMessage(): Promise<ChatTestResult> {
  const response = await axios.post(`${API_BASE_URL}/api/slack/settings/test`);
  return response.data;
}

/** Current user's Telegram settings, plus the catalogue of subscribable events. */
export async function getTelegramSettings(): Promise<TelegramSettings> {
  const response = await axios.get(`${API_BASE_URL}/api/telegram/settings`);
  return response.data;
}

export async function updateTelegramSettings(
  request: UpdateTelegramSettingsRequest
): Promise<TelegramSettings> {
  const response = await axios.put(`${API_BASE_URL}/api/telegram/settings`, request, JSON_HEADERS);
  return response.data;
}

export async function sendTelegramTestMessage(): Promise<ChatTestResult> {
  const response = await axios.post(`${API_BASE_URL}/api/telegram/settings/test`);
  return response.data;
}

/** Workspace Slack configuration (ADMIN only). */
export async function getSlackConfig(): Promise<SlackConfig> {
  const response = await axios.get(`${API_BASE_URL}/api/slack/config`);
  return response.data;
}

export async function updateSlackConfig(
  request: UpdateSlackConfigRequest
): Promise<SlackConfig> {
  const response = await axios.put(`${API_BASE_URL}/api/slack/config`, request, JSON_HEADERS);
  return response.data;
}

export async function sendSlackConfigTestMessage(): Promise<ChatTestResult> {
  const response = await axios.post(`${API_BASE_URL}/api/slack/config/test`);
  return response.data;
}

/** Workspace Telegram configuration (ADMIN only). */
export async function getTelegramConfig(): Promise<TelegramConfig> {
  const response = await axios.get(`${API_BASE_URL}/api/telegram/config`);
  return response.data;
}

export async function updateTelegramConfig(
  request: UpdateTelegramConfigRequest
): Promise<TelegramConfig> {
  const response = await axios.put(`${API_BASE_URL}/api/telegram/config`, request, JSON_HEADERS);
  return response.data;
}
