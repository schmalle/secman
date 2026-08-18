import test from 'node:test';
import assert from 'node:assert/strict';
import { summarizeImportResult } from './requirementImport.ts';

test('a clean import reads as a plain success', () => {
  const s = summarizeImportResult({ message: 'File processed successfully.', requirementsProcessed: 159 });
  assert.match(s.headline, /159 requirements added/);
  assert.equal(s.hasSkips, false);
  assert.deepEqual(s.details, []);
});

test('a partial import says so in the headline, not only in the details', () => {
  // The whole point: "Success: … (105 requirements added)" gave no hint that 64 rows were dropped.
  const s = summarizeImportResult({
    message: 'File processed.',
    requirementsProcessed: 105,
    rowsSkipped: 64,
    skipReasons: ["Row 2: no value in the 'Short req' column"],
  });
  assert.equal(s.hasSkips, true);
  assert.match(s.headline, /105 requirements added/);
  assert.match(s.headline, /64 rows skipped/);
  assert.deepEqual(s.details, ["Row 2: no value in the 'Short req' column"]);
});

test('singular and plural both read correctly', () => {
  const one = summarizeImportResult({ requirementsProcessed: 1, rowsSkipped: 1 });
  assert.match(one.headline, /1 requirement added/);
  assert.match(one.headline, /1 row skipped/);
  assert.doesNotMatch(one.headline, /1 requirements|1 rows/);
});

test('an older backend without the skip fields still renders', () => {
  // rowsSkipped/skipReasons are absent when the client runs ahead of the server.
  const s = summarizeImportResult({ message: 'File processed successfully.', requirementsProcessed: 12 });
  assert.equal(s.hasSkips, false);
  assert.match(s.headline, /12 requirements added/);
});

test('a missing or empty response does not render undefined', () => {
  for (const input of [null, undefined, {}]) {
    const s = summarizeImportResult(input as never);
    assert.doesNotMatch(s.headline, /undefined|NaN/);
    assert.match(s.headline, /0 requirements added/);
  }
});
