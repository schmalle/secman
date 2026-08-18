import assert from 'node:assert/strict';
import test from 'node:test';

import { ADMIN_NAV, activeHrefForPath, filterAdminNav, groupKeyForPath } from './adminNav';

test('every admin destination the old markup listed is still reachable', () => {
  // The rail moved from hand-written markup to this list. That diff is large
  // enough to lose a line in without noticing, so the destinations are pinned.
  const hrefs = ADMIN_NAV.flatMap((g) => g.items.map((i) => i.href));

  for (const href of [
    '/admin',
    '/admin/user-management',
    '/workgroups',
    '/admin/user-mappings',
    '/admin/identity-providers',
    '/admin/email-config',
    '/admin/vulnerability-config',
    '/admin/notification-settings',
    '/admin/chat-config',
    '/admin/falcon-config',
    '/admin/github-config',
    '/admin/translation-config',
    '/admin/mcp-api-keys',
    '/admin/requirement-export-templates',
    '/admin/classification-rules',
    '/admin/requirements',
    '/scans',
    '/admin/add-system',
    '/import',
    '/export',
    '/export?type=assets',
    '/admin/config-bundle',
    '/notification-preferences',
    '/notification-logs',
    '/admin/ec2-compliance',
  ]) {
    assert.ok(hrefs.includes(href), `${href} disappeared from the admin rail`);
  }
});

test('an empty filter leaves the rail untouched', () => {
  assert.equal(filterAdminNav(ADMIN_NAV, ''), ADMIN_NAV);
  assert.equal(filterAdminNav(ADMIN_NAV, '   '), ADMIN_NAV);
});

test('filtering keeps only matching items and drops emptied groups', () => {
  const result = filterAdminNav(ADMIN_NAV, 'notif');
  const labels = result.flatMap((g) => g.items.map((i) => i.label));

  assert.deepEqual(labels.sort(), ['Notification Logs', 'Notification Settings', 'Notifications']);
  // No group survives with zero items — a bare heading is noise.
  assert.ok(result.every((g) => g.items.length > 0));
});

test('filtering is case-insensitive and matches mid-label', () => {
  const labels = filterAdminNav(ADMIN_NAV, 'CROWD').flatMap((g) => g.items.map((i) => i.label));
  assert.deepEqual(labels, ['CrowdStrike Falcon']);
});

test('a group whose heading matches keeps all of its items', () => {
  const result = filterAdminNav(ADMIN_NAV, 'users & access');
  assert.equal(result.length, 1);
  assert.equal(result[0].items.length, 4);
});

test('a query matching nothing yields no groups', () => {
  assert.deepEqual(filterAdminNav(ADMIN_NAV, 'zzzz'), []);
});

test('the open group is derived from the page you are on', () => {
  assert.equal(groupKeyForPath(ADMIN_NAV, '/admin/falcon-config'), 'system');
  assert.equal(groupKeyForPath(ADMIN_NAV, '/admin/user-management'), 'users');
  assert.equal(groupKeyForPath(ADMIN_NAV, '/notification-logs'), 'monitoring');
  // Outside the admin rail entirely: nothing unfolds.
  assert.equal(groupKeyForPath(ADMIN_NAV, '/assets'), null);
});

test('the longest matching href wins, so /admin does not claim its children', () => {
  assert.equal(activeHrefForPath(ADMIN_NAV, '/admin'), '/admin');
  assert.equal(activeHrefForPath(ADMIN_NAV, '/admin/mcp-api-keys'), '/admin/mcp-api-keys');
  assert.equal(groupKeyForPath(ADMIN_NAV, '/admin/mcp-api-keys'), 'system');
});

test('a sub-route and a trailing slash still mark their entry', () => {
  assert.equal(activeHrefForPath(ADMIN_NAV, '/admin/user-management/42'), '/admin/user-management');
  assert.equal(activeHrefForPath(ADMIN_NAV, '/scans/'), '/scans');
});

test('query strings do not take part in the active match', () => {
  // Both export entries strip to /export; the first one listed takes the mark
  // rather than the row flickering between them.
  assert.equal(activeHrefForPath(ADMIN_NAV, '/export'), '/export');
});
