import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL('./UserManagement.tsx', import.meta.url), 'utf8');

test('user workgroup badges link directly to workgroup details', () => {
  assert.match(source, /href=\{`\/workgroups\?workgroupId=\$\{encodeURIComponent\(wg\.id\)\}`\}/);
  assert.match(source, /title=\{`View \$\{wg\.name\} workgroup details`\}/);
});
