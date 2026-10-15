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

test('user management offers every backend role for assignment', () => {
  const user = readFileSync(new URL('../../../backendng/src/main/kotlin/com/secman/domain/User.kt', import.meta.url), 'utf8');
  const availableRoles = source.match(/const AVAILABLE_ROLES = \[([^\]]+)\]/)?.[1];
  const backendRoles = user.match(/enum class Role\s*\{([^}]+)\}/)?.[1];

  assert.ok(availableRoles, 'UserManagement must declare its assignable roles');
  assert.ok(backendRoles, 'User must declare its supported roles');
  const assignable = [...availableRoles.matchAll(/'([^']+)'/g)].map(match => match[1]);
  const supported = backendRoles.split(',').map(role => role.trim());
  assert.deepEqual(assignable.sort(), supported.sort());
});
