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

test('current vulnerabilities table labels identifiers as CVE or findings', () => {
  const source = readFileSync(new URL('./CurrentVulnerabilitiesTable.tsx', import.meta.url), 'utf8');

  assert.match(source, /CVE\/Finding/);
});

test('current vulnerabilities use a compact fixed shell with WebKit-safe sticky headers', () => {
  const source = readFileSync(new URL('./CurrentVulnerabilitiesTable.tsx', import.meta.url), 'utf8');
  const styleSource = readFileSync(new URL('./scrollableTableStyles.ts', import.meta.url), 'utf8');

  assert.match(source, /embedded \? "100%" : "calc\(100dvh - 9\.5rem\)"/);
  assert.match(source, /scrollContainerStyle, stickyHeaderCellStyle/);
  assert.match(styleSource, /overflow: 'auto'/);
  assert.match(styleSource, /export const stickyHeaderCellStyle: React\.CSSProperties/);
  assert.match(styleSource, /backgroundColor: 'var\(--bs-table-bg, #f8f9fa\)'/);
  assert.match(source, /borderCollapse: "separate"/);
  assert.match(source, /<th style=\{stickyHeaderCellStyle\}>Overdue Status<\/th>/);
  assert.doesNotMatch(source, /<thead[^>]+position: "sticky"/);
});

test('analytics embeds the vulnerability table without duplicate page padding', () => {
  const source = readFileSync(new URL('./AnalyticsTabs.tsx', import.meta.url), 'utf8');

  assert.match(source, /height: 'calc\(100dvh - 9\.5rem\)'/);
  assert.match(source, /<CurrentVulnerabilitiesTable embedded \/>/);
});
