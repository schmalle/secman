import assert from 'node:assert/strict';
import test from 'node:test';
import { normalizeChatSettings } from './chatSettingsNormalizer.ts';

/**
 * Regression: uncaught TypeError "Cannot read properties of undefined (reading 'includes')"
 * on /chat-notifications, which blanked both the Slack and the Telegram settings card.
 *
 * Micronaut Serde omits empty collections from a response body, so a user with no
 * subscriptions yet receives a payload with no `eventTypes` key at all. The component then
 * called `.includes()` on `undefined`.
 */
test('a response with no eventTypes key yields an empty selection, not undefined', () => {
  const raw = {
    enabled: false,
    webhookUrlConfigured: false,
    channel: null,
    availableEventTypes: [
      { name: 'CROWDSTRIKE_IMPORT_COMPLETED', displayName: 'CrowdStrike', description: 'x' },
    ],
  } as any;

  const normalized = normalizeChatSettings(raw);

  assert.deepEqual(normalized.eventTypes, []);
  assert.equal(normalized.availableEventTypes.length, 1);
});

test('a response with no availableEventTypes key yields an empty catalogue', () => {
  const normalized = normalizeChatSettings({ enabled: true, eventTypes: ['A'] } as any);

  assert.deepEqual(normalized.availableEventTypes, []);
  assert.deepEqual(normalized.eventTypes, ['A']);
});

test('populated arrays are passed through untouched', () => {
  const catalogue = [{ name: 'A', displayName: 'A', description: 'a' }];
  const normalized = normalizeChatSettings({
    enabled: true,
    eventTypes: ['A'],
    availableEventTypes: catalogue,
  } as any);

  assert.deepEqual(normalized.eventTypes, ['A']);
  assert.deepEqual(normalized.availableEventTypes, catalogue);
});

test('every other field survives normalization', () => {
  const normalized = normalizeChatSettings({
    enabled: true,
    chatId: '12345',
    botTokenConfigured: true,
    lastNotifiedAt: '2026-08-06T10:00:00',
    lastDeliveryStatus: 'SUCCESS',
    lastDeliveryError: null,
    workspaceBotAvailable: true,
  } as any);

  assert.equal(normalized.enabled, true);
  assert.equal((normalized as any).chatId, '12345');
  assert.equal((normalized as any).botTokenConfigured, true);
  assert.equal((normalized as any).lastDeliveryStatus, 'SUCCESS');
  assert.equal((normalized as any).lastDeliveryError, null);
});

// A body that is not an object at all (empty 200, proxy hiccup) must not throw either —
// the card should render its "no events available" state rather than crash the page.
test('a null body normalizes to empty arrays instead of throwing', () => {
  const normalized = normalizeChatSettings(null as any);

  assert.deepEqual(normalized.eventTypes, []);
  assert.deepEqual(normalized.availableEventTypes, []);
});
