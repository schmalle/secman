import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

test('sidebar labels the products link as vulnerable products', () => {
  const source = readFileSync(new URL('./Sidebar.tsx', import.meta.url), 'utf8');

  assert.match(source, /href="\/products"[\s\S]*Vulnerable products/);
  assert.doesNotMatch(source, /href="\/products"[\s\S]*> Products/);
});

test('sidebar links the requirement download page below requirements overview', () => {
  const source = readFileSync(new URL('./Sidebar.tsx', import.meta.url), 'utf8');

  assert.match(source, /href="\/requirements"[\s\S]*href="\/requirements\/download"[\s\S]*Requirement download/);
});

test('account onboarding sits in RISK MANAGEMENT, not the admin-only block', () => {
  const source = readFileSync(new URL('./Sidebar.tsx', import.meta.url), 'utf8');

  // Gated on the ADMIN-or-SECCHAMPION predicate, not on isAdmin. The whole ADMIN section is
  // `{isAdmin && …}`, so a link placed there would be invisible to a SECCHAMPION even though
  // the page and its API allow them — the bug this assertion exists to prevent.
  assert.match(
    source,
    /canAccessAccountOnboarding\(userRoles\) &&[\s\S]*href="\/admin\/account-onboarding"/,
  );

  // It must appear before the ADMIN section opens.
  const linkIndex = source.indexOf('href="/admin/account-onboarding"');
  const adminSectionIndex = source.indexOf('{isAdmin && (');
  assert.ok(linkIndex > 0, 'account onboarding link is missing');
  assert.ok(adminSectionIndex > 0, 'admin section marker moved - update this assertion');
  assert.ok(
    linkIndex < adminSectionIndex,
    'account onboarding link must not be inside the isAdmin-only section',
  );
});
