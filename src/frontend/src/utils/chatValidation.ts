/**
 * Client-side validation for chat notification destination fields.
 *
 * This mirrors `SlackClient.validateWebhookUrl` / `validateChannel` and
 * `TelegramClient.validateBotToken` / `validateChatId` on the backend, and exists purely
 * to give immediate feedback in the form. It is NOT a security control — the backend
 * re-validates every value it stores or uses, and that check is the one that matters (the
 * server, not the browser, makes the outbound request, and the Telegram bot token ends up
 * in a request URL path).
 *
 * Kept in a plain `.ts` module rather than inside the `.tsx` components so the frontend
 * unit tier can exercise it; see docs/TESTING.md §Frontend.
 */

/** Must match `secman.slack.webhook-url-prefix` in the backend's application.yml. */
export const SLACK_WEBHOOK_PREFIX = 'https://hooks.slack.com/';

const SLACK_CHANNEL_PATTERN = /^[#@]?[A-Za-z0-9._-]{1,80}$/;
const TELEGRAM_BOT_TOKEN_PATTERN = /^[0-9]{1,20}:[A-Za-z0-9_-]{20,255}$/;
const TELEGRAM_CHAT_ID_PATTERN = /^(-?[0-9]{1,20}|@[A-Za-z][A-Za-z0-9_]{4,31})$/;

/**
 * @returns null when the URL looks like a Slack incoming webhook, otherwise a message
 *   to show under the field.
 */
export function validateWebhookUrl(url: string): string | null {
  const trimmed = url.trim();
  if (trimmed.length === 0) {
    return 'Webhook URL must not be empty';
  }
  if (!trimmed.startsWith(SLACK_WEBHOOK_PREFIX)) {
    return `Webhook URL must start with ${SLACK_WEBHOOK_PREFIX}`;
  }
  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return 'Webhook URL is not a valid URL';
  }
  if (parsed.protocol !== 'https:') {
    return 'Webhook URL must use https';
  }
  // A prefix match alone is not enough — 'https://hooks.slack.com/@evil.example'
  // starts with the prefix but resolves elsewhere. Compare the parsed host.
  if (parsed.hostname.toLowerCase() !== 'hooks.slack.com') {
    return 'Webhook URL must point at hooks.slack.com';
  }
  if (parsed.username || parsed.password) {
    return 'Webhook URL must not contain credentials';
  }
  return null;
}

/**
 * @returns null when the value is a usable Slack channel name, channel ID or member ID,
 *   otherwise a message to show under the field.
 */
export function validateChannel(channel: string): string | null {
  const trimmed = channel.trim();
  if (trimmed.length === 0) {
    return 'Channel must not be empty';
  }
  if (!SLACK_CHANNEL_PATTERN.test(trimmed)) {
    return 'Channel must be a channel name (#alerts), channel ID (C012AB3CD) or member ID (U012AB3CD)';
  }
  return null;
}

/**
 * @returns null when the value has Telegram's bot-token shape, otherwise a message to
 *   show under the field.
 */
export function validateTelegramBotToken(token: string): string | null {
  const trimmed = token.trim();
  if (trimmed.length === 0) {
    return 'Bot token must not be empty';
  }
  if (!TELEGRAM_BOT_TOKEN_PATTERN.test(trimmed)) {
    return 'Bot token must look like 123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ (from @BotFather)';
  }
  return null;
}

/**
 * @returns null when the value is a usable Telegram chat ID or public channel username,
 *   otherwise a message to show under the field.
 */
export function validateTelegramChatId(chatId: string): string | null {
  const trimmed = chatId.trim();
  if (trimmed.length === 0) {
    return 'Chat ID must not be empty';
  }
  if (!TELEGRAM_CHAT_ID_PATTERN.test(trimmed)) {
    return 'Chat ID must be a numeric ID (e.g. 123456789 or -1001234567890) or an @channelname';
  }
  return null;
}

/**
 * Describes where a user's Slack messages will actually land, given their own settings
 * and what the workspace provides. Returned as a discriminated result so the component
 * can render a warning rather than let a user save a setup that silently delivers
 * nothing — the most common failure mode is "enabled, events ticked, no destination".
 */
export type SlackDestination =
  | { kind: 'webhook' }
  | { kind: 'user-channel'; channel: string }
  | { kind: 'workspace-channel'; channel: string }
  | { kind: 'none'; reason: string };

export function resolveSlackDestination(input: {
  webhookUrlConfigured: boolean;
  /** Pending webhook input in the form, if the user typed a new one. */
  pendingWebhookUrl?: string;
  channel: string | null;
  workspaceBotAvailable: boolean;
  workspaceDefaultChannel: string | null;
}): SlackDestination {
  if (input.webhookUrlConfigured || (input.pendingWebhookUrl ?? '').trim().length > 0) {
    return { kind: 'webhook' };
  }

  const userChannel = (input.channel ?? '').trim();
  if (!input.workspaceBotAvailable) {
    return {
      kind: 'none',
      reason: userChannel
        ? 'A channel is set, but the workspace Slack bot is not configured — ask an administrator to set it up, or use a personal webhook URL instead.'
        : 'No webhook URL is set and the workspace Slack bot is not configured, so nothing can be delivered.',
    };
  }

  if (userChannel.length > 0) {
    return { kind: 'user-channel', channel: userChannel };
  }

  const workspaceChannel = (input.workspaceDefaultChannel ?? '').trim();
  if (workspaceChannel.length > 0) {
    return { kind: 'workspace-channel', channel: workspaceChannel };
  }

  return {
    kind: 'none',
    reason: 'Set a channel or a personal webhook URL — the workspace has no default channel.',
  };
}

/**
 * Telegram needs BOTH a chat ID and a bot token — a token addresses no conversation on
 * its own, and a chat ID cannot be reached without a bot. Mirrors
 * `ChatNotificationService.resolveTelegramDestination`.
 */
export type TelegramDestination =
  | { kind: 'personal-bot'; chatId: string }
  | { kind: 'workspace-bot'; chatId: string }
  | { kind: 'none'; reason: string };

export function resolveTelegramDestination(input: {
  chatId: string | null;
  botTokenConfigured: boolean;
  /** Pending bot token input in the form, if the user typed a new one. */
  pendingBotToken?: string;
  workspaceBotAvailable: boolean;
}): TelegramDestination {
  const chatId = (input.chatId ?? '').trim();
  if (chatId.length === 0) {
    return { kind: 'none', reason: 'Set your chat ID — Telegram delivers to a conversation, not to a person.' };
  }

  const hasPersonalToken =
    input.botTokenConfigured || (input.pendingBotToken ?? '').trim().length > 0;
  if (hasPersonalToken) {
    return { kind: 'personal-bot', chatId };
  }

  if (input.workspaceBotAvailable) {
    return { kind: 'workspace-bot', chatId };
  }

  return {
    kind: 'none',
    reason:
      'The workspace Telegram bot is not configured — ask an administrator to set it up, or supply your own bot token.',
  };
}
