import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// The Overdue Patching tile reads the outdated-asset materialized view. When that
// view has never been calculated (lastCalculatedAt is null), asserting "No assets
// past their remediation SLA" is a false claim — the tile must say the data is not
// available instead of showing a confident zero.
test('overdue patching tile does not claim a clean SLA when the view was never calculated', () => {
  const source = readFileSync(new URL('./UserTodoDashboard.tsx', import.meta.url), 'utf8');

  assert.match(source, /overdueDataAvailable/);
  assert.match(source, /lastCalculatedAt\s*!==?\s*null|lastCalculatedAt\s*==?\s*null/);
  assert.match(source, /Not available yet/);
});
