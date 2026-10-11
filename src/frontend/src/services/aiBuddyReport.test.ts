import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseBuddyReport } from './aiBuddyReport';

function response() {
  return {
    schemaVersion: 1, jobId: 'job', generatedAt: '2026-10-11T12:00:00Z', expiresAt: '2026-10-11T12:30:00Z',
    system: { displayName: 'System', osNameVersion: null, build: null, architecture: null },
    snapshot: { capturedAt: '2026-10-11T12:00:00Z', assetLastSeen: null, sensorLastSeen: null,
      vulnerabilityImportFrom: null, vulnerabilityImportTo: null, stale: false, sourceChanged: false, staleReasons: [] },
    scope: { basis: 'RECORDED_HIGH_CRITICAL_ASSET_FINDINGS', retainedFindingCount: 0, consideredFindingCount: 0,
      uniqueCveCount: 0, nonCveFindingCount: 0, exceptedFindingCount: 0, excludedFindingCount: 0, duplicateRowCount: 0 },
    analysis: { completeness: 'PARTIAL', model: null, limitations: [], verifiedCoveredFindingCount: 0,
      conditionalFindingCount: 0, noVerifiedRemediationCount: 0 }, actions: [], findings: [], evidence: []
  };
}

test('empty serialized report collections are accepted before rendering the empty-result guide', () => {
  assert.equal(parseBuddyReport(JSON.parse(JSON.stringify(response()))).actions.length, 0);
});

test('missing collections and malformed nested actions produce a clear error rather than a renderer crash', () => {
  assert.throws(() => parseBuddyReport({ ...response(), actions: undefined }), /incomplete or invalid/);
  assert.throws(() => parseBuddyReport({ ...response(), actions: [{ id: 'a', evidenceState: 'VERIFIED' }] }), /incomplete or invalid/);
  assert.throws(() => parseBuddyReport({ ...response(), evidence: [null] }), /incomplete or invalid/);
});
