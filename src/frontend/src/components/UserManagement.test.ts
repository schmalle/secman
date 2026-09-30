import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL('./UserManagement.tsx', import.meta.url), 'utf8');

test('user workgroup badges open details without leaving user management', () => {
  assert.match(source, /onClick=\{\(\) => setDetailWorkgroup\(wg\)\}/);
  assert.match(source, /<WorkgroupDetailsModal/);
  assert.match(source, /workgroupId=\{detailWorkgroup\.id\}/);
  assert.match(source, /title=\{`View \$\{wg\.name\} workgroup details`\}/);
});
