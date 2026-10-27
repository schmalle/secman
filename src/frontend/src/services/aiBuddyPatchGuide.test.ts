import assert from 'node:assert/strict';
import { test } from 'node:test';
import { guideStatus, guideSteps } from './aiBuddyPatchGuide';
import { buddyEvidenceUrl, type BuddyAction } from './aiBuddyService';

const action: BuddyAction = {
  id: 'a', order: 1, type: 'APPLICATION_UPDATE', evidenceState: 'REQUIRES_APPLICABILITY_CHECK',
  targetProduct: 'mozilla firefox', patchIds: [], targetVersion: '128.7',
  applicability: [{ code: 'FIREFOX_PLATFORM_SUPPORT', value: null, satisfied: null }], prerequisites: [],
  coveredCves: [], coveredFindingRefs: [], reboot: 'NOT_REQUIRED', evidenceRefs: []
};

test('conditional Firefox targets put a platform check before installation and verify the version', () => {
  assert.equal(guideStatus(action), 'Confirm before applying');
  const steps = guideSteps(action);
  assert.match(steps[0], /supports this operating system/);
  assert.match(steps[1], /Once the checks above pass/);
  assert.match(steps[2], /confirm version 128.7/);
});

test('investigations and missing targets never manufacture installation instructions', () => {
  assert.ok(guideSteps({ ...action, evidenceState: 'INSUFFICIENT_INFORMATION' }).every(step => !/\b(?:deploy|install|apply)\b/i.test(step)));
  assert.match(guideSteps({ ...action, targetVersion: null }).at(-1)!, /Confirm the exact fix/);
});

test('Mozilla links have fixed HTTPS hosts and paths', () => {
  assert.ok(buddyEvidenceUrl('https://www.mozilla.org/en-US/security/advisories/mfsa2025-09/'));
  assert.equal(buddyEvidenceUrl('https://www.mozilla.org/redirect?url=evil'), null);
  assert.equal(buddyEvidenceUrl('https://www.mozilla.org.evil.test/en-US/security/advisories/mfsa2025-09/'), null);
});
