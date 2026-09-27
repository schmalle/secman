import assert from 'node:assert/strict';
import test from 'node:test';
import { delegationDomains } from './mcpDelegateSelection.ts';

test('selected people automatically receive their exact email domains without duplicates', () => {
  assert.equal(delegationDomains([
    { id: 1, username: 'One', email: 'one@Example.com' },
    { id: 2, username: 'Two', email: 'two@example.com' },
    { id: 3, username: 'Three', email: 'three@subsidiary.example.com' },
  ], ' @EXAMPLE.com, @another.org '), '@example.com,@another.org,@subsidiary.example.com');
});

test('removing selected people removes their automatically included domains', () => {
  assert.equal(delegationDomains([], ''), '');
  assert.equal(delegationDomains([], '@owner.example.com'), '@owner.example.com');
});
