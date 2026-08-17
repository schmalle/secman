import { test, expect, type Page } from '@playwright/test';
import { loginViaApi, requireEnv } from './helpers/session';

/**
 * Regression: a remembered release that no longer exists must not poison the tab.
 *
 * `secman_selectedReleaseId` in sessionStorage survives the release being deleted from another
 * session. The id was then trusted without validation, the release-scoped fetches 404'd, and
 * nothing cleared it — so the same 404s re-fired on every later visit and /requirements rendered
 * an empty "historical snapshot" instead of falling back to the live corpus.
 *
 * ## Why the release list is stubbed
 *
 * `ReleaseSelector` masks the bug whenever an ACTIVE release exists: it overwrites the stored id
 * with that release and the tab silently recovers. The failure only reaches the user when there is
 * nothing to fall back to — which is precisely the incident that surfaced it, where every release
 * had just been bulk-deleted. Reproducing that for real would mean deleting every release on the
 * target instance, so the two responses are stubbed instead: non-destructive, deterministic, and
 * it fails against the un-fixed code (verified by reverting the fix and re-running).
 *
 * The assertion that pins the bug is the SECOND load. A fix that merely handles the 404 gracefully
 * still leaves the dead id in storage and fails here.
 */

requireEnv('SECMAN_ADMIN_NAME', 'SECMAN_ADMIN_PASS');

/** An id no release will ever have — stands in for one deleted moments ago. */
const DEAD_RELEASE_ID = 999999999;
const STORAGE_KEY = 'secman_selectedReleaseId';

/** Every release is gone, so nothing can mask a stale id by overwriting it. */
async function stubEmptyReleaseList(page: Page) {
  await page.route(/\/api\/releases(\?.*)?$/, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
}

const readStoredId = (page: Page) =>
  page.evaluate((k) => sessionStorage.getItem(k), STORAGE_KEY);

/** Requests made against a given release path fragment, whatever their status. */
function trackCalls(page: Page, fragment: string): string[] {
  const seen: string[] = [];
  page.on('request', (r) => {
    if (r.url().includes(fragment)) seen.push(r.url());
  });
  return seen;
}

test('a deleted release is forgotten instead of re-requested on every visit', async ({ page }) => {
  await loginViaApi(page);
  await stubEmptyReleaseList(page);

  // Remember a release, then have it vanish — as if deleted from another session.
  await page.goto('/requirements');
  await page.evaluate(
    ([k, v]) => sessionStorage.setItem(k as string, v as string),
    [STORAGE_KEY, String(DEAD_RELEASE_ID)],
  );

  await page.goto('/requirements');
  await expect(page.getByText('Requirement Management')).toBeVisible();
  await page.waitForTimeout(2000); // let the release-scoped fetches settle

  expect(await readStoredId(page), 'the dead id must not survive being found missing')
    .not.toBe(String(DEAD_RELEASE_ID));

  // The bug itself: without clearing, this second load repeats the identical 404s, and so does
  // every load after it for the life of the tab.
  const secondLoad = trackCalls(page, `/api/releases/${DEAD_RELEASE_ID}`);
  await page.goto('/requirements');
  await expect(page.getByText('Requirement Management')).toBeVisible();
  await page.waitForTimeout(2000);

  expect(secondLoad, 'a forgotten release must never be requested again').toEqual([]);
});

test('a half-numeric stored id is rejected rather than silently addressing another release', async ({ page }) => {
  await loginViaApi(page);
  await stubEmptyReleaseList(page);
  await page.goto('/requirements');

  // parseInt('44727abc') is 44727 — the old code would happily fetch release 44727, showing the
  // user a *different* release with no error at all. That is worse than a 404.
  await page.evaluate((k) => sessionStorage.setItem(k, '44727abc'), STORAGE_KEY);

  const calls = trackCalls(page, '/api/releases/');
  await page.goto('/requirements');
  await expect(page.getByText('Requirement Management')).toBeVisible();
  await page.waitForTimeout(2000);

  expect(calls, 'a malformed id must be rejected before it becomes a URL').toEqual([]);
});
