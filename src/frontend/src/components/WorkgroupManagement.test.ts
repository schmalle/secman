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

test('workgroup list scrolls inside a fixed management shell', () => {
  assert.match(shellSource, /height: 'calc\(100dvh - 9\.5rem\)'/);
  assert.match(tableSource, /overflowY: 'auto'/);
  assert.match(tableSource, /position: 'sticky'/);
});
