import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buddySetupLabel, buddySetupMissing, orderBuddyProviders, parseBuddySetupResult } from './aiBuddySetup';

const config = {
  isActive: true, apiKey: '', baseUrl: 'https://openrouter.ai/api/v1', modelName: 'vendor/model',
  maxTokens: 4000, aiBuddyEnabled: true, aiBuddySingleInstance: true, aiBuddyProviders: '',
  aiBuddyMaxCostPerJobUsd: 0.5, aiBuddyDailyBudgetUsd: 10,
  aiBuddyMaxInputPricePerMillion: 5, aiBuddyMaxOutputPricePerMillion: 20
};

test('serialized compatible routes with omitted empty reasons can be sorted and rendered', () => {
  const result = parseBuddySetupResult(JSON.parse('{"ready":false,"reasons":["APPROVED_PROVIDER_REQUIRED"],"providers":[{"tag":"vendor","name":"Vendor","compatible":true}]}'));
  const provider = orderBuddyProviders(result.providers)[0];
  assert.deepEqual(provider.reasons, []);
  assert.equal(provider.reasons.includes('ZERO_RETENTION_REQUIRED'), false);
  assert.equal(result.ready, false);
});

test('empty and null response lists normalize and missing readiness stays false', () => {
  assert.deepEqual(parseBuddySetupResult({}), { ready: false, reasons: [], providers: [] });
  assert.deepEqual(parseBuddySetupResult({ ready: true, providers: null, reasons: null }),
    { ready: true, reasons: [], providers: [] });
});

test('malformed nested routes fail with an actionable message instead of granting compatibility', () => {
  const provider = { tag: 'vendor', name: 'Vendor', compatible: true };
  for (const malformed of [null, { ...provider, reasons: 'bad' }, { ...provider, compatible: 'true' },
    { ...provider, compatible: false }, { ...provider, reasons: ['ZERO_RETENTION_REQUIRED'] }]) {
    assert.throws(() => parseBuddySetupResult({ providers: [malformed] }), /server returned an invalid response/);
  }
});

test('valid incompatible routes retain the reasons that prevent approval and a ZDR badge', () => {
  const result = parseBuddySetupResult({ ready: false, providers: [
    { tag: 'vendor', name: 'Vendor', compatible: false, reasons: ['ZERO_RETENTION_REQUIRED'] }
  ] });
  assert.equal(result.providers[0].compatible, false);
  assert.ok(result.providers[0].reasons.includes('ZERO_RETENTION_REQUIRED'));
});

test('suggestions put compatible routes first, then ZDR routes with other limitations', () => {
  const routes = [
    { tag: 'a', name: 'A', compatible: false, reasons: ['ZERO_RETENTION_REQUIRED'] },
    { tag: 'c', name: 'C', compatible: false, reasons: ['PROVIDER_PRICE_LIMIT_EXCEEDED'] },
    { tag: 'b', name: 'B', compatible: true, reasons: [] }
  ];
  assert.deepEqual(orderBuddyProviders(routes).map(route => route.tag), ['b', 'c', 'a']);
  assert.deepEqual(routes.map(route => route.tag), ['a', 'c', 'b']);
});

test('active and enabled do not imply provider setup is complete', () => {
  assert.equal(buddySetupLabel(config, true), 'Setup incomplete');
  assert.deepEqual(buddySetupMissing(config, true), ['Select and approve at least one provider route']);
});

test('complete settings remain pending provider verification and blank edits retain the stored key', () => {
  const complete = { ...config, aiBuddyProviders: 'vendor' };
  assert.equal(buddySetupLabel(complete, true), 'Setup complete — provider verification pending');
  assert.ok(buddySetupMissing(complete).includes('Enter an OpenRouter API key'));
  assert.equal(buddySetupLabel({ ...complete, aiBuddyEnabled: false }, true), 'Disabled');
});

test('inactive configurations and invalid limits remain incomplete', () => {
  const missing = buddySetupMissing({ ...config, isActive: false, maxTokens: NaN, aiBuddyDailyBudgetUsd: 0 }, true);
  assert.ok(missing.includes('Set this configuration as active'));
  assert.ok(missing.some(value => value.startsWith('Set Max Tokens')));
  assert.ok(missing.includes('Enter positive budget and price limits'));
});
