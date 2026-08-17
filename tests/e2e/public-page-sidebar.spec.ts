import { test, expect } from '@playwright/test';
import { loginViaApi, requireEnv } from './helpers/session';

/**
 * Regression: a public page must still carry the app chrome for a signed-in visitor.
 *
 * `Layout.astro` used one `isPublicPage` flag for two unrelated things — whether a page requires a
 * login (auth policy) and whether it renders the sidebar (page chrome). They coincided while
 * /login was the only public page. /requirements/download broke that: it is reachable
 * anonymously *and* navigated to from the sidebar, so a signed-in user lost the whole navigation.
 *
 * Both directions are asserted, because each has its own failure mode:
 *  - anonymous must stay clean (a visible-but-empty sidebar would be worse than none), and
 *  - authenticated must be *populated*, not just present — the sidebar sources its role-gated
 *    entries from `sessionStorage`, so a sidebar shown on a different signal could render as an
 *    empty shell.
 *
 * Paths, not screenshots: the assertion is on computed visibility and layout width, so it survives
 * restyling and only fails when the chrome decision itself regresses.
 */

requireEnv('SECMAN_ADMIN_NAME', 'SECMAN_ADMIN_PASS');

/** The one public page that is also a normal in-app destination. */
const PUBLIC_PAGE = '/requirements/download';

/** Fraction of the viewport the main column occupies once the sidebar takes its share. */
const NARROWED_BELOW = 0.9;

async function mainWidthFraction(page: import('@playwright/test').Page): Promise<number> {
  const main = await page.locator('main.main-col').evaluate((el) => el.getBoundingClientRect().width);
  const body = await page.evaluate(() => document.body.clientWidth);
  return main / body;
}

test('an anonymous visitor gets the public page with no sidebar', async ({ page }) => {
  await page.goto(PUBLIC_PAGE);

  await expect(page.getByText('Requirement Download')).toBeVisible();
  await expect(page.locator('.sidebar-col')).toBeHidden();
  expect(await mainWidthFraction(page)).toBeGreaterThan(NARROWED_BELOW);
});

test('a signed-in visitor gets the sidebar on that same public page', async ({ page }) => {
  await loginViaApi(page);

  await page.goto(PUBLIC_PAGE);

  await expect(page.getByText('Requirement Download')).toBeVisible();
  await expect(page.locator('.sidebar-col')).toBeVisible();
  // Populated, not an empty shell — proves visibility and contents read the same signal.
  await expect(page.locator('.sidebar-col').getByText(/ASSETS/i)).toBeVisible();
  expect(await mainWidthFraction(page)).toBeLessThan(NARROWED_BELOW);
});
