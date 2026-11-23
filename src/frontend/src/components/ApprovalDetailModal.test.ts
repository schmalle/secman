import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

test('the review dialog shows the requested IP as text even without a linked asset', () => {
  const source = readFileSync(new URL('./ApprovalDetailModal.tsx', import.meta.url), 'utf8');

  assert.match(
    source,
    /request\.scope === 'IP' && request\.scopeValue && \(\s*<div[^>]*>\s*<strong>Requested IP:<\/strong> <code[^>]*>\{request\.scopeValue\}<\/code>/
  );
});
