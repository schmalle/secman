import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buddyEvidenceUrl, buddyLabel } from './aiBuddyService.ts';
import { canAccessBetaFeatures } from '../utils/permissions.ts';

test('beta access is additive and excludes ordinary vulnerability roles', () => {
  for (const role of ['BETA', 'ADMIN', 'SECCHAMPION']) assert.equal(canAccessBetaFeatures(['USER', role]), true);
  for (const roles of [undefined, [], ['USER'], ['VULN'], ['REPORT']]) assert.equal(canAccessBetaFeatures(roles), false);
});
test('evidence links cannot introduce HTML javascript credentials or foreign destinations', () => {
  assert.equal(buddyEvidenceUrl('https://api.msrc.microsoft.com/cvrf/v3.0/cvrf/2025-Jul'), 'https://api.msrc.microsoft.com/cvrf/v3.0/cvrf/2025-Jul');
  for (const url of [null, 'javascript:alert(1)', 'http://msrc.microsoft.com', 'https://msrc.microsoft.com.evil.example', 'https://user@msrc.microsoft.com', 'https://127.0.0.1', 'https://msrc.microsoft.com:8080', 'https://msrc.microsoft.com/#inject', 'https://msrc.microsoft.com/redirect?url=https://evil.example']) assert.equal(buddyEvidenceUrl(url), null);
  assert.equal(buddyLabel('REQUIRES_APPLICABILITY_CHECK'), 'Requires applicability check');
});

test('enabled AI buddy explains the missing approved provider setting', () => {
  assert.equal(buddyLabel('APPROVED_PROVIDER_REQUIRED'), 'AI buddy is enabled, but no provider route is selected. Open LLM Config → Edit → AI buddy → Approved provider routes');
});
