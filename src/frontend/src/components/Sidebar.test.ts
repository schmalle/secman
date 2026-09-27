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

test('account onboarding is available through the role-filtered admin menu', () => {
  const source = readFileSync(new URL('./Sidebar.tsx', import.meta.url), 'utf8');
  assert.match(source, /filterAdminNav\(adminNavForRoles\(userRoles\), adminQuery\)/);
  assert.match(source, /canAccessAccountOnboarding\(userRoles\) &&[\s\S]*ADMIN[\s\S]*visibleAdminGroups\.map/);
  assert.doesNotMatch(source, /href="\/admin\/account-onboarding"/);
});

test('vulnerability management is grouped into Analyze / Inventory / Workflow', () => {
  const source = readFileSync(new URL('./Sidebar.tsx', import.meta.url), 'utf8');

  // Order matters: the groups read analyze-then-inventory-then-workflow, which is
  // the sequence someone works in, not alphabetical.
  assert.match(
    source,
    /sidebar-subsection-header">Analyze[\s\S]*sidebar-subsection-header">Inventory[\s\S]*sidebar-subsection-header">Workflow/,
  );
  assert.match(source, /href="\/analytics"[\s\S]*Analytics/);
});

test('grouping the vulnerability rail dropped no destination', () => {
  // The restructure moved entries between groups and folded three analysis views
  // behind /analytics. Nothing was removed — this is the assertion that says so,
  // because a regrouping diff is large enough to lose a line in without noticing.
  const source = readFileSync(new URL('./Sidebar.tsx', import.meta.url), 'utf8');

  const destinations = [
    '/analytics',
    '/vulnerabilities/system',
    '/vulnerabilities/domain',
    '/account-vulns',
    '/wg-vulns',
    '/products',
    '/installed-products',
    '/outdated-assets',
    '/vulnerabilities/eol',
    '/github-repos',
    '/account-finding-age',
    '/vulnerabilities/exceptions',
    '/my-exception-requests',
    '/exception-approvals',
    '/admin/aws-account-sharing',
    '/aws-account-sharing',
  ];

  for (const href of destinations) {
    assert.ok(source.includes(`href="${href}"`), `${href} disappeared from the sidebar`);
  }
});

test('the standalone analysis routes survive the consolidation', () => {
  // /analytics is an additional entry point, not a replacement: the three routes
  // it hosts stay reachable so existing bookmarks and the E2E page sweep still work.
  const source = readFileSync(new URL('./AnalyticsTabs.tsx', import.meta.url), 'utf8');

  for (const href of ['/vulnerabilities/current', '/vulnerability-statistics', '/vulnerability-heatmap']) {
    assert.ok(source.includes(href), `${href} is no longer linked from the analytics page`);
  }
});

test('the admin rail filters and folds rather than unrolling every entry', () => {
  // Design 1b. The markup must stay driven by adminNav.ts: a hand-written <a>
  // list here can neither be filtered nor folded, which is the whole point.
  const source = readFileSync(new URL('./Sidebar.tsx', import.meta.url), 'utf8');

  assert.match(source, /from '\.\/adminNav'/);
  assert.match(source, /visibleAdminGroups\.map/);
  assert.match(source, /aria-label="Filter admin menu"/);
  // One group open at a time: toggling sets the key, it does not add to a set.
  assert.match(source, /setOpenAdminGroup\(\s*openAdminGroup === group\.key \? null : group\.key,?\s*\)/);
});
