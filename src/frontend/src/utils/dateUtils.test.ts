import assert from 'node:assert/strict';
import test from 'node:test';
import { parseServerDate, parseServerDateMs } from './dateUtils.ts';

// The whole point of parseServerDate is that its result does not depend on the browser JS engine.
// These cases lock the two divergence sources: missing timezone and >3-digit fractional seconds.

test('zoneless timestamp is interpreted as UTC (not browser-local)', () => {
  const d = parseServerDate('2026-07-02T04:14:18');
  assert.equal(d?.toISOString(), '2026-07-02T04:14:18.000Z');
});

test('microsecond precision is truncated to milliseconds and treated as UTC', () => {
  const d = parseServerDate('2026-07-02T04:14:18.246830');
  assert.equal(d?.toISOString(), '2026-07-02T04:14:18.246Z');
});

test('space-separated timestamp is normalized to ISO', () => {
  const d = parseServerDate('2026-07-02 04:14:18.246830');
  assert.equal(d?.toISOString(), '2026-07-02T04:14:18.246Z');
});

test('explicit Z is preserved and microseconds still truncated', () => {
  const d = parseServerDate('2026-07-02T04:14:18.246830Z');
  assert.equal(d?.toISOString(), '2026-07-02T04:14:18.246Z');
});

test('explicit numeric offset is respected (not double-appended)', () => {
  const d = parseServerDate('2026-07-02T06:14:18.246+02:00');
  assert.equal(d?.toISOString(), '2026-07-02T04:14:18.246Z');
});

test('empty / null / unparseable input returns null (never a NaN Date)', () => {
  assert.equal(parseServerDate(''), null);
  assert.equal(parseServerDate(null), null);
  assert.equal(parseServerDate(undefined), null);
  assert.equal(parseServerDate('not a date'), null);
});

test('parseServerDateMs returns epoch ms or null', () => {
  assert.equal(parseServerDateMs('2026-07-02T04:14:18Z'), Date.parse('2026-07-02T04:14:18Z'));
  assert.equal(parseServerDateMs('garbage'), null);
});
