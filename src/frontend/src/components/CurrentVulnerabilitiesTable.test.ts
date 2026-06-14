import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

test('current vulnerabilities table requests deferred counts before exact totals', () => {
  const source = readFileSync(new URL('./CurrentVulnerabilitiesTable.tsx', import.meta.url), 'utf8');

  assert.match(source, /const countMode = knownTotal !== undefined \? ["']exact["'] : ["']deferred["']/);
  assert.match(source, /fetchExactVulnerabilityCount/);
});

test('current vulnerabilities table ignores stale exact count responses', () => {
  const source = readFileSync(new URL('./CurrentVulnerabilitiesTable.tsx', import.meta.url), 'utf8');

  assert.match(source, /countRequestKeyRef/);
  assert.match(source, /if\s*\(\s*countRequestKeyRef\.current\s*!==\s*requestKey\s*\)\s*return/);
});
