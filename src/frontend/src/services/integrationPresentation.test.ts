import assert from 'node:assert/strict';
import test from 'node:test';
import { findingQuery, safeEvidenceUrl, coverageLabel } from './integrationPresentation.ts';

test('finding filters encode values without injecting another filter', () => {
  const query = findingQuery({ owner: 'a&state=RESOLVED', source: 'VISUAL', state: 'OPEN' }, 2);
  const parsed = new URLSearchParams(query);
  assert.equal(parsed.get('owner'), 'a&state=RESOLVED');
  assert.equal(parsed.get('state'), 'OPEN');
  assert.equal(parsed.get('page'), '2');
  assert.equal(parsed.get('size'), '25');
});

test('evidence links reject executable schemes and embedded credentials', () => {
  assert.equal(safeEvidenceUrl('javascript:alert(1)'), null);
  assert.equal(safeEvidenceUrl('data:text/html,hi'), null);
  assert.equal(safeEvidenceUrl('https://user:password@example.com'), null);
  assert.equal(safeEvidenceUrl('https://example.com/issue/1'), 'https://example.com/issue/1');
});

test('coverage distinguishes never scanned, failed, stale and partial from healthy', () => {
  assert.equal(coverageLabel({ lastStatus: null, stale: true }), 'Not scanned');
  assert.equal(coverageLabel({ lastStatus: 'FAILED', stale: false }), 'Failed');
  assert.equal(coverageLabel({ lastStatus: 'SUCCESS', stale: true }), 'Stale');
  assert.equal(coverageLabel({ lastStatus: 'PARTIAL', stale: false }), 'Partial');
  assert.equal(coverageLabel({ lastStatus: 'SUCCESS', stale: false }), 'Current');
});
