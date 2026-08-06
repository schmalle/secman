import assert from 'node:assert/strict';
import test from 'node:test';
import {
  validateWebhookUrl,
  validateChannel,
  validateTelegramBotToken,
  validateTelegramChatId,
  resolveSlackDestination,
  resolveTelegramDestination,
} from './chatValidation';

/**
 * The validators mirror the backend's, which is the real control. These tests exist so the
 * two cannot drift silently — a user typing a rejected value must be told why here rather
 * than getting an opaque 400, and the destination resolvers must agree with
 * ChatNotificationService about where a given configuration actually delivers.
 */

test('validateWebhookUrl accepts a genuine Slack incoming webhook', () => {
  assert.equal(validateWebhookUrl('https://hooks.slack.com/services/T000/B000/XXXX'), null);
});

test('validateWebhookUrl rejects anything that is not a Slack incoming webhook', () => {
  const rejected = [
    ['', 'empty'],
    ['   ', 'blank'],
    ['http://hooks.slack.com/services/T000', 'plain http'],
    ['https://evil.example/services/T000', 'unrelated host'],
    ['https://hooks.slack.com.evil.example/services/T000', 'host suffix attack'],
    ['https://user:pass@hooks.slack.com/services/T000', 'embedded credentials'],
    ['http://169.254.169.254/latest/meta-data/', 'cloud metadata endpoint'],
    ['not a url at all', 'garbage'],
  ];

  for (const [url, label] of rejected) {
    assert.notEqual(validateWebhookUrl(url), null, `expected '${url}' (${label}) to be rejected`);
  }
});

test('validateChannel accepts channel names, channel IDs and member IDs', () => {
  for (const channel of ['#alerts', 'alerts', 'C012AB3CD', 'U012AB3CD', '@someone', 'team-sec_1.2']) {
    assert.equal(validateChannel(channel), null, `expected '${channel}' to be accepted`);
  }
});

test('validateChannel rejects malformed channels', () => {
  for (const channel of ['', '   ', 'has space', 'a'.repeat(120), 'with"quote', 'semi;colon']) {
    assert.notEqual(validateChannel(channel), null, `expected '${channel}' to be rejected`);
  }
});

test('validateTelegramBotToken accepts a genuine bot token', () => {
  assert.equal(validateTelegramBotToken('123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw'), null);
});

test('validateTelegramBotToken rejects tokens that could escape the URL path', () => {
  // The backend interpolates the token into the request path (/bot<token>/sendMessage),
  // so these shapes matter beyond cosmetics.
  const rejected = [
    ['', 'empty'],
    ['not-a-token', 'no colon'],
    ['123456789:short', 'secret too short'],
    ['123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw/../evil', 'path traversal'],
    ['123456789:AAHdqTcvCH1vGW/deleteWebhook', 'path injection'],
    [':AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw', 'missing bot id'],
  ];

  for (const [token, label] of rejected) {
    assert.notEqual(validateTelegramBotToken(token), null, `expected token (${label}) to be rejected`);
  }
});

test('validateTelegramChatId accepts numeric IDs and public channel usernames', () => {
  for (const chatId of ['123456789', '-1001234567890', '@secman_alerts']) {
    assert.equal(validateTelegramChatId(chatId), null, `expected '${chatId}' to be accepted`);
  }
});

test('validateTelegramChatId rejects malformed chat IDs', () => {
  for (const chatId of ['', '   ', 'not a chat', '@ab', '12 34', '123;456', '@with-dash']) {
    assert.notEqual(validateTelegramChatId(chatId), null, `expected '${chatId}' to be rejected`);
  }
});

test('resolveSlackDestination prefers a personal webhook over the workspace bot', () => {
  const result = resolveSlackDestination({
    webhookUrlConfigured: true,
    channel: '#mine',
    workspaceBotAvailable: true,
    workspaceDefaultChannel: '#fallback',
  });

  assert.equal(result.kind, 'webhook');
});

test('resolveSlackDestination treats a freshly typed webhook as configured', () => {
  const result = resolveSlackDestination({
    webhookUrlConfigured: false,
    pendingWebhookUrl: 'https://hooks.slack.com/services/T000/B000/XXXX',
    channel: null,
    workspaceBotAvailable: false,
    workspaceDefaultChannel: null,
  });

  assert.equal(result.kind, 'webhook');
});

test('resolveSlackDestination falls back to the user channel then the workspace default', () => {
  const userChannel = resolveSlackDestination({
    webhookUrlConfigured: false,
    channel: '#mine',
    workspaceBotAvailable: true,
    workspaceDefaultChannel: '#fallback',
  });
  assert.deepEqual(userChannel, { kind: 'user-channel', channel: '#mine' });

  const workspaceChannel = resolveSlackDestination({
    webhookUrlConfigured: false,
    channel: null,
    workspaceBotAvailable: true,
    workspaceDefaultChannel: '#fallback',
  });
  assert.deepEqual(workspaceChannel, { kind: 'workspace-channel', channel: '#fallback' });
});

test('resolveSlackDestination warns when a channel is set but no workspace bot exists', () => {
  const result = resolveSlackDestination({
    webhookUrlConfigured: false,
    channel: '#mine',
    workspaceBotAvailable: false,
    workspaceDefaultChannel: null,
  });

  assert.equal(result.kind, 'none');
  assert.match(result.kind === 'none' ? result.reason : '', /workspace Slack bot is not configured/);
});

test('resolveSlackDestination warns when nothing at all is configured', () => {
  const result = resolveSlackDestination({
    webhookUrlConfigured: false,
    channel: null,
    workspaceBotAvailable: true,
    workspaceDefaultChannel: null,
  });

  assert.equal(result.kind, 'none');
});

test('resolveTelegramDestination requires a chat ID', () => {
  const result = resolveTelegramDestination({
    chatId: '',
    botTokenConfigured: true,
    workspaceBotAvailable: true,
  });

  assert.equal(result.kind, 'none');
  assert.match(result.kind === 'none' ? result.reason : '', /chat ID/);
});

test('resolveTelegramDestination prefers a personal bot token over the workspace bot', () => {
  const result = resolveTelegramDestination({
    chatId: '123456789',
    botTokenConfigured: true,
    workspaceBotAvailable: true,
  });

  assert.deepEqual(result, { kind: 'personal-bot', chatId: '123456789' });
});

test('resolveTelegramDestination uses the workspace bot when the user has none', () => {
  const result = resolveTelegramDestination({
    chatId: '123456789',
    botTokenConfigured: false,
    workspaceBotAvailable: true,
  });

  assert.deepEqual(result, { kind: 'workspace-bot', chatId: '123456789' });
});

test('resolveTelegramDestination warns when no bot is available at all', () => {
  const result = resolveTelegramDestination({
    chatId: '123456789',
    botTokenConfigured: false,
    workspaceBotAvailable: false,
  });

  assert.equal(result.kind, 'none');
  assert.match(result.kind === 'none' ? result.reason : '', /workspace Telegram bot is not configured/);
});
