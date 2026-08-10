import assert from 'node:assert/strict';
import test from 'node:test';

import {
  assetComponentKey,
  componentLabel,
  describeDeadline,
  statusBadge,
  subjectLabel,
  urgencyRank,
} from './eolFormat';

test('statusBadge distinguishes end of life from approaching end of life', () => {
  assert.equal(statusBadge('EOL').className, 'badge bg-danger');
  assert.equal(statusBadge('APPROACHING_EOL').className, 'badge bg-warning text-dark');
  assert.equal(statusBadge('SUPPORTED').className, 'badge bg-success');
});

test('describeDeadline reports a dateless EOL cycle as already end of life', () => {
  // The catalogue can flag a cycle EOL without a date; rendering "in 0 days"
  // there would imply a precision we do not have.
  assert.equal(describeDeadline(null, null), 'already end of life (no date published)');
  assert.equal(describeDeadline(undefined, undefined), 'already end of life (no date published)');
});

test('describeDeadline renders past, present and future deadlines distinctly', () => {
  assert.equal(describeDeadline('2024-04-30', -400), '2024-04-30 (400 days ago)');
  assert.equal(describeDeadline('2026-08-10', 0), '2026-08-10 (today)');
  assert.equal(describeDeadline('2027-01-01', 144), '2027-01-01 (in 144 days)');
});

test('describeDeadline falls back to the bare date when no day count is known', () => {
  assert.equal(describeDeadline('2027-01-01', null), '2027-01-01');
});

test('urgencyRank puts already-EOL components ahead of every future deadline', () => {
  assert.ok(urgencyRank('EOL', -10) < urgencyRank('APPROACHING_EOL', 0));
  assert.ok(urgencyRank('APPROACHING_EOL', 30) < urgencyRank('APPROACHING_EOL', 300));
  // Unknown horizon sorts last rather than first.
  assert.ok(urgencyRank('APPROACHING_EOL', 300) < urgencyRank('APPROACHING_EOL', null));
});

test('componentLabel does not repeat a vendor already present in the name', () => {
  assert.equal(componentLabel('Google Chrome', 'Google', '120.0.6099.109'), 'Google Chrome 120.0.6099.109');
  assert.equal(componentLabel('Chrome', 'Google', '120'), 'Google Chrome 120');
  assert.equal(componentLabel('Ubuntu', null, null), 'Ubuntu');
});

test('assetComponentKey normalizes case and whitespace on both sides', () => {
  assert.equal(assetComponentKey(7, '  Google Chrome '), assetComponentKey(7, 'google chrome'));
  assert.notEqual(assetComponentKey(7, 'Google Chrome'), assetComponentKey(8, 'Google Chrome'));
  assert.equal(assetComponentKey(null, 'x'), '0::x');
});

test('subjectLabel names each finding source', () => {
  assert.equal(subjectLabel('ASSET_OS'), 'Operating system');
  assert.equal(subjectLabel('ASSET_PRODUCT'), 'Installed software');
  assert.equal(subjectLabel('REPOSITORY_COMPONENT'), 'Repository dependency');
  assert.equal(subjectLabel('SOMETHING_NEW'), 'SOMETHING_NEW');
});
