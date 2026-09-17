import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const tableSource = readFileSync(new URL('./WorkgroupManagement.tsx', import.meta.url), 'utf8');
const shellSource = readFileSync(new URL('./WorkgroupManagementWithHierarchy.tsx', import.meta.url), 'utf8');

test('workgroup status control persists the inverse enabled state', () => {
  assert.match(tableSource, /putJson\(`\/api\/workgroups\/\$\{workgroup\.id\}`/);
  assert.match(tableSource, /\{ enabled: nextEnabled \}/);
  assert.match(tableSource, /useClientHasRole\(\['ADMIN', 'SECCHAMPION'\]\)/);
});

test('workgroup list uses WebKit-safe sticky header cells inside the fixed shell', () => {
  assert.match(shellSource, /height: 'calc\(100dvh - 9\.5rem\)'/);
  assert.match(tableSource, /overflowY: 'auto'/);
  assert.match(tableSource, /const stickyHeaderCellStyle: React\.CSSProperties/);
  assert.match(tableSource, /backgroundColor: 'var\(--bs-table-bg, #f8f9fa\)'/);
  assert.match(tableSource, /borderCollapse: 'separate'/);
  assert.match(tableSource, /<th style=\{stickyHeaderCellStyle\}>Parent<\/th>/);
  assert.doesNotMatch(tableSource, /<thead[^>]+position: 'sticky'/);
});

test('workgroup detail links select the requested workgroup in tree view', () => {
  assert.match(shellSource, /new URLSearchParams\(window\.location\.search\)\.get\('workgroupId'\)/);
  assert.match(shellSource, /getWorkgroupById\(workgroupId\)/);
  assert.match(shellSource, /setViewMode\('tree'\)/);
});
