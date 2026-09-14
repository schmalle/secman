import assert from 'node:assert/strict';
import test from 'node:test';
import {
  hasAllMcpPermissions,
  MCP_PERMISSION_KEYS,
  selectAllMcpPermissions,
} from './mcpPermissionSelection.ts';

test('all-permissions selection includes every supported MCP permission once', () => {
  const selected = selectAllMcpPermissions(true);

  assert.equal(selected.length, 20);
  assert.equal(new Set(selected).size, selected.length);
  assert.deepEqual(selected, MCP_PERMISSION_KEYS);
  assert.ok(selected.includes('INTEGRATIONS_READ'));
  assert.ok(selected.includes('INTEGRATIONS_WRITE'));
  assert.equal(hasAllMcpPermissions(selected), true);
});

test('all-permissions selection can be cleared', () => {
  assert.deepEqual(selectAllMcpPermissions(false), []);
  assert.equal(hasAllMcpPermissions(['REQUIREMENTS_READ']), false);
});
