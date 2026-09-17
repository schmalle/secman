import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const configSource = readFileSync(new URL('./IntegrationScannerConfig.tsx', import.meta.url), 'utf8');
const findingsSource = readFileSync(new URL('./IntegrationFindingList.tsx', import.meta.url), 'utf8');

test('web security scanners can be registered and filtered', () => {
  assert.match(configSource, /<option value="WEB_SECURITY">Web security scanner<\/option>/);
  assert.match(findingsSource, /<option value="WEB_SECURITY">Web security scanner<\/option>/);
});
