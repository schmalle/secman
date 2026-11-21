import assert from 'node:assert/strict';
import test from 'node:test';
import { parseDaysOpen } from './daysOpen';

test('age values sort numerically and preserve zero and unknown', () => {
  const ages = ['100 days', 2, '10 days'].map(parseDaysOpen);
  assert.deepEqual(ages.sort((a, b) => a! - b!), [2, 10, 100]);
  assert.equal(parseDaysOpen('0 days'), 0);
  assert.equal(parseDaysOpen('unknown'), null);
  assert.equal(parseDaysOpen(null), null);
});
