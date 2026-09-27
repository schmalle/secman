import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

test('emailed assessment responses use the guest layout without session checks', () => {
  const page = readFileSync(new URL('../pages/respond/[token].astro', import.meta.url), 'utf8');
  assert.match(page, /import PublicLayout from/);
  assert.match(page, /<PublicLayout/);
  assert.doesNotMatch(page, /layouts\/Layout\.astro/);
  const layout = readFileSync(new URL('./PublicLayout.astro', import.meta.url), 'utf8');
  assert.doesNotMatch(layout, /checkAuthAndRedirect|redirectToLogin|\/api\/auth\/status/);
});
