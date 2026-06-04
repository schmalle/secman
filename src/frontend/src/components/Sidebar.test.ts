import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

test('sidebar labels the products link as vulnerable products', () => {
  const source = readFileSync(new URL('./Sidebar.tsx', import.meta.url), 'utf8');

  assert.match(source, /href="\/products"[\s\S]*Vulnerable products/);
  assert.doesNotMatch(source, /href="\/products"[\s\S]*> Products/);
});
