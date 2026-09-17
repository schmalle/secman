import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL('./AppSettingsAdmin.tsx', import.meta.url), 'utf8');

test('admin can configure the catch-all workgroup threshold', () => {
  assert.match(source, /id="catchAllWorkgroupUserThreshold"/);
  assert.match(source, /setCatchAllWorkgroupUserThreshold\(data\.catchAllWorkgroupUserThreshold\)/);
  assert.match(source, /disabled automatically/);
});

test('expired sessions redirect without a misleading request-failed alert', () => {
  assert.match(source, /if \(!sessionStorage\.getItem\('user'\)\) return/);
});
