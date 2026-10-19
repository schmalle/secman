import { test, expect, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { loginViaApi } from './helpers/session';
import { requireIsolatedTarget } from './helpers/isolated-target';

// Fixture credentials are generated for this disposable schema; never retain them in traces.
test.use({ trace: 'off', screenshot: 'only-on-failure' });

async function api(page: Page, path: string, data?: unknown, method?: string) {
  return page.evaluate(async ({ path, data, method }) => {
    const csrf = document.cookie.split('; ').find(item => item.startsWith('PLAY_CSRF_TOKEN='))?.split('=').slice(1).join('=');
    const response = await fetch(path, {
      method: method ?? (data === undefined ? 'GET' : 'POST'), credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...(csrf ? { 'Csrf-Token': decodeURIComponent(csrf) } : {}) },
      ...(data === undefined ? {} : { body: JSON.stringify(data) }),
    });
    return { status: response.status, body: await response.json().catch(() => null) };
  }, { path, data, method });
}

async function ok(page: Page, path: string, data?: unknown, method?: string) {
  const response = await api(page, path, data, method);
  expect(response.status, path).toBeGreaterThanOrEqual(200);
  expect(response.status, path).toBeLessThan(300);
  return response.body;
}

test('REQ-only user discusses current requirements, release snapshots and releases with persistent scope and RBAC', async ({ page, browser }) => {
  test.setTimeout(240_000);
  requireIsolatedTarget();
  const suffix = randomUUID().slice(0, 8);
  const username = `req-${suffix}`;
  const password = randomUUID() + randomUUID();
  const title = `Comment workflow ${suffix}`;
  await loginViaApi(page);
  const reqUser = await ok(page, '/api/users', { username, email: `${username}@example.test`, password, roles: ['REQ'] });
  expect(reqUser.roles).toEqual(['REQ']);
  await ok(page, '/api/users', { username: `viewer-${suffix}`, email: `viewer-${suffix}@example.test`, password, roles: ['USER'] });
  const requirement = await ok(page, '/api/requirements', { shortreq: title, details: 'Original content', language: 'en' });
  const release = await ok(page, '/api/releases', { version: `1.0.${parseInt(suffix, 16)}`, name: title });
  const snapshotList = await ok(page, `/api/releases/${release.id}/requirements`);
  const snapshot = snapshotList.data.find((row: { originalRequirementId: number }) => row.originalRequirementId === requirement.id);
  expect(snapshot).toBeTruthy();
  const paths = {
    current: `/api/requirement-comments/REQUIREMENT/${requirement.id}`,
    release: `/api/requirement-comments/RELEASE/${release.id}`,
    snapshot: `/api/requirement-comments/SNAPSHOT/${snapshot.id}`,
  };

  const context = await browser.newContext();
  const viewerContext = await browser.newContext();
  const anonymous = await browser.newContext();
  try {
    const reqPage = await context.newPage();
    await loginViaApi(reqPage, username, password);
    expect((await ok(reqPage, '/api/auth/status')).roles).toEqual(['REQ']);
    // Reach the feature through the normal sidebar.
    await reqPage.goto('/');
    await reqPage.getByText('REQUIREMENTS', { exact: true }).click();
    await reqPage.locator('a[href="/requirements"]').click();
    const row = reqPage.getByRole('row').filter({ hasText: title }).first();
    await row.getByRole('button', { name: 'Expand requirement details' }).click();
    let discussion = reqPage.getByRole('region', { name: /Comments:.*current requirement/ });
    await expect(discussion.getByText('No comments yet.')).toBeVisible();
    expect((await ok(reqPage, paths.current)).data).toEqual([]);
    await discussion.getByLabel('Add a comment').fill('   ');
    await expect(discussion.getByRole('button', { name: 'Add comment', exact: true })).toBeDisabled();

    // A failed write preserves the draft; retry creates exactly one comment.
    await reqPage.route(`**${paths.current}`, async route => {
      if (route.request().method() === 'POST') {
        await route.fulfill({ status: 503, contentType: 'application/json', body: '{"message":"Temporary test failure"}' });
      } else await route.continue();
    });
    const currentText = '<script>window.commentExecuted=true</script> Current discussion';
    await discussion.getByLabel('Add a comment').fill(currentText);
    await discussion.getByRole('button', { name: 'Add comment', exact: true }).click();
    await expect(discussion.getByRole('alert')).toContainText('Temporary test failure');
    await expect(discussion.getByLabel('Add a comment')).toHaveValue(currentText);
    await reqPage.unroute(`**${paths.current}`);
    await discussion.getByRole('button', { name: 'Add comment', exact: true }).click();
    await expect(discussion.getByRole('listitem').getByText(currentText, { exact: true })).toBeVisible();
    expect(await reqPage.evaluate(() => (window as unknown as Record<string, unknown>).commentExecuted)).toBeUndefined();
    const currentComments = await ok(reqPage, paths.current);
    expect(currentComments.totalItems).toBe(1);
    expect(currentComments.data[0].authorName).toBe(username);
    await reqPage.reload();
    await reqPage.getByRole('row').filter({ hasText: title }).first().getByRole('button', { name: 'Expand requirement details' }).click();
    await expect(reqPage.getByText(currentText, { exact: true })).toBeVisible();
    // Another authorized actor can see the same discussion.
    expect((await ok(page, paths.current)).data[0].body).toBe(currentText);

    await reqPage.getByTestId('release-selector').selectOption(String(release.id));
    const expandSnapshot = reqPage.getByRole('row').filter({ hasText: title }).first().getByRole('button', { name: 'Expand requirement details' });
    if (await expandSnapshot.count()) await expandSnapshot.click();
    discussion = reqPage.getByRole('region', { name: new RegExp(`Comments:.*in release ${release.version}`) });
    await expect(discussion.getByText('No comments yet.')).toBeVisible();
    await discussion.getByLabel('Add a comment').fill('Snapshot discussion');
    await discussion.getByRole('button', { name: 'Add comment', exact: true }).click();
    await expect(discussion.getByRole('listitem').getByText('Snapshot discussion', { exact: true })).toBeVisible();
    const releaseDiscussion = reqPage.getByRole('region', { name: `Comments: Release ${release.version}: ${title}`, exact: true });
    await releaseDiscussion.getByLabel('Add a comment').fill('Whole release discussion');
    await releaseDiscussion.getByRole('button', { name: 'Add comment', exact: true }).click();
    await expect(releaseDiscussion.getByRole('listitem').getByText('Whole release discussion', { exact: true })).toBeVisible();

    // A slow live response must not replace a restored release's snapshot rows.
    await reqPage.route('**/api/requirements', async route => {
      const response = await route.fetch();
      await new Promise(resolve => setTimeout(resolve, 1500));
      await route.fulfill({ response });
    });
    await reqPage.reload();
    await reqPage.getByRole('row').filter({ hasText: title }).first()
      .getByRole('button', { name: 'Expand requirement details' }).click();
    discussion = reqPage.getByRole('region', { name: new RegExp(`Comments:.*in release ${release.version}`) });
    await expect(discussion.getByText('Snapshot discussion', { exact: true })).toBeVisible();
    await reqPage.waitForTimeout(1800);
    await expect(discussion.getByLabel('Add a comment')).toBeVisible();
    await reqPage.unroute('**/api/requirements');

    // The dedicated release detail and snapshot modal use the same persisted scopes.
    await reqPage.goto(`/releases/${release.id}`);
    await expect(reqPage.getByText('Whole release discussion', { exact: true })).toBeVisible();
    await reqPage.getByRole('row').filter({ hasText: title }).click();
    await expect(reqPage.getByRole('dialog').getByText('Snapshot discussion', { exact: true })).toBeVisible();
    await reqPage.getByRole('dialog').getByLabel('Add a comment').fill('Modal snapshot discussion');
    await reqPage.getByRole('dialog').getByRole('button', { name: 'Add comment', exact: true }).click();
    await expect(reqPage.getByRole('dialog').getByRole('listitem').getByText('Modal snapshot discussion', { exact: true })).toBeVisible();

    for (const body of ['', ' \n\t', 'x'.repeat(2001)]) {
      expect((await api(reqPage, paths.current, { body, expectedRevision: currentComments.targetRevision })).status).toBe(400);
    }
    const forged = await ok(reqPage, paths.release, { body: 'Identity test', authorName: 'admin', authorId: 1, createdAt: '2000-01-01T00:00:00Z' });
    expect(forged.authorName).toBe(username);
    expect(forged.createdAt).not.toBe('2000-01-01T00:00:00Z');
    for (const query of ['?size=101', '?size=0', '?page=-1']) {
      expect((await api(reqPage, paths.current + query)).status).toBe(400);
    }
    const paginated = await ok(reqPage, paths.snapshot + '?size=1');
    expect(paginated.data).toHaveLength(1);
    expect(paginated.totalPages).toBe(2);
    const older = await ok(reqPage, paths.snapshot + '?size=1&page=1');
    expect(older.data[0].id).not.toBe(paginated.data[0].id);
    expect((await api(reqPage, '/api/requirement-comments/RELEASE/999999999')).status).toBe(404);
    expect((await api(reqPage, '/api/requirement-comments/UNKNOWN/1')).status).toBe(400);

    const viewer = await viewerContext.newPage();
    await loginViaApi(viewer, `viewer-${suffix}`, password);
    const anonPage = await anonymous.newPage();
    await anonPage.goto('/login');
    for (const path of Object.values(paths)) {
      expect((await api(viewer, path)).status).toBe(403);
      expect((await api(viewer, path, { body: 'Denied', expectedRevision: 1 })).status).toBe(403);
      expect((await api(anonPage, path)).status).toBe(401);
      expect((await api(anonPage, path, { body: 'Denied', expectedRevision: 1 })).status).toBe(401);
    }

    // Comment privacy and deletion are enforced server-side in every scope.
    for (const role of ['REQ', 'REQADMIN', 'RELEASE_MANAGER', 'SECCHAMPION']) {
      const otherName = `privacy-${role.toLowerCase()}-${suffix}`;
      await ok(page, '/api/users', { username: otherName, email: `${otherName}@example.test`, password, roles: [role] });
      const otherContext = await browser.newContext();
      try {
        const other = await otherContext.newPage();
        await loginViaApi(other, otherName, password);
        for (const [scope, path] of Object.entries(paths)) {
          if (scope === 'current' && ['REQADMIN', 'RELEASE_MANAGER'].includes(role)) continue;
          const before = await ok(reqPage, path);
          const original = before.data[0];
          const view = await ok(other, path + '?size=1');
          expect(view.totalItems).toBe(role === 'SECCHAMPION' ? before.totalItems : 0);
          expect(view.data).toHaveLength(role === 'SECCHAMPION' ? 1 : 0);
          if (role !== 'SECCHAMPION') {
            expect((await api(other, `${path}/${original.id}`, undefined, 'DELETE')).status).toBe(404);
          }
          const created = await ok(other, path, { body: `Private ${role} ${scope}`, expectedRevision: before.targetRevision });
          expect(created.canDelete).toBe(true);
          const authorView = await ok(other, path);
          if (role !== 'SECCHAMPION') expect(authorView.totalItems).toBe(1);
          expect((await ok(reqPage, path)).totalItems).toBe(before.totalItems);
          expect((await ok(page, path)).data.some((entry: { id: number }) => entry.id === created.id)).toBe(true);
          // Wrong target must fail even for admins.
          const wrongPath = scope === 'release' ? paths.snapshot : paths.release;
          expect((await api(page, `${wrongPath}/${created.id}`, undefined, 'DELETE')).status).toBe(404);
          expect((await api(other, `${path}/${created.id}`, undefined, 'DELETE')).status).toBe(204);
          expect((await api(other, `${path}/${created.id}`, undefined, 'DELETE')).status).toBe(404);
          const moderated = await ok(reqPage, path, { body: 'Moderation test', expectedRevision: before.targetRevision });
          const moderator = role === 'SECCHAMPION' ? other : page;
          expect((await api(moderator, `${path}/${moderated.id}`, undefined, 'DELETE')).status).toBe(204);
        }
      } finally {
        await otherContext.close();
      }
    }

    // Current edits do not rewrite old comments or immutable snapshots.
    await ok(page, `/api/requirements/${requirement.id}`, { shortreq: `${title} revised` }, 'PUT');
    const revised = await ok(reqPage, paths.current);
    expect(revised.targetRevision).toBeGreaterThan(currentComments.targetRevision);
    expect(revised.data[0].requirementRevision).toBe(currentComments.targetRevision);
    expect((await api(reqPage, paths.current, { body: 'Stale', expectedRevision: currentComments.targetRevision })).status).toBe(409);
    expect((await ok(page, `/api/releases/${release.id}/requirements`)).data.find((row: { id: number }) => row.id === snapshot.id).shortreq).toBe(title);
    await ok(page, `/api/releases/${release.id}/status`, { status: 'ACTIVE' }, 'PUT');
    await ok(reqPage, paths.release, { body: 'Active release discussion' });
    const next = await ok(page, '/api/releases', { version: `2.0.${parseInt(suffix, 16)}`, name: `Next ${title}` });
    const nextSnapshot = (await ok(page, `/api/releases/${next.id}/requirements`)).data.find((row: { originalRequirementId: number }) => row.originalRequirementId === requirement.id);
    expect((await ok(reqPage, `/api/requirement-comments/RELEASE/${next.id}`)).data).toEqual([]);
    expect((await ok(reqPage, `/api/requirement-comments/SNAPSHOT/${nextSnapshot.id}`)).data).toEqual([]);
    await ok(page, `/api/releases/${next.id}/status`, { status: 'ACTIVE' }, 'PUT');
    for (const path of [paths.release, paths.snapshot]) {
      expect((await ok(reqPage, path)).canComment).toBe(false);
      expect((await api(reqPage, path, { body: 'Archived', expectedRevision: snapshot.revision })).status).toBe(409);
    }
    await reqPage.goto(`/releases/${release.id}`);
    await expect(reqPage.getByText('Whole release discussion', { exact: true })).toBeVisible();
    await expect(reqPage.getByText(/This discussion is read-only/)).toBeVisible();
    await expect(reqPage.getByLabel('Add a comment')).toHaveCount(0);
    await reqPage.getByRole('row').filter({ hasText: title }).click();
    await expect(reqPage.getByRole('dialog').getByText('Snapshot discussion', { exact: true })).toBeVisible();
    await expect(reqPage.getByRole('dialog').getByLabel('Add a comment')).toHaveCount(0);
    // Deletion remains available on archived discussions and updates the UI.
    reqPage.once('dialog', dialog => dialog.dismiss());
    const modalComment = reqPage.getByRole('dialog').getByRole('listitem').filter({ hasText: 'Modal snapshot discussion' });
    await modalComment.getByRole('button', { name: 'Delete comment', exact: true }).click();
    await expect(modalComment).toBeVisible();
    reqPage.once('dialog', dialog => dialog.accept());
    await modalComment.getByRole('button', { name: 'Delete comment', exact: true }).click();
    await expect(modalComment).toHaveCount(0);
    await reqPage.reload();
    await reqPage.getByRole('row').filter({ hasText: title }).click();
    await expect(reqPage.getByRole('dialog').getByText('Modal snapshot discussion', { exact: true })).toHaveCount(0);
  } finally {
    await context.close();
    await viewerContext.close();
    await anonymous.close();
  }
  // All fixtures belong to the runner schema, which the runner drops even on failure.
});
