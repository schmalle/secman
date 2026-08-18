import { expect, test } from '@playwright/test';
import { loginViaApi, requireEnv } from './helpers/session';

const SHOTS = process.env.SHOT_DIR || 'test-results/admin-sidebar-1b';

test('admin rail filters and folds', async ({ page }) => {
    requireEnv('SECMAN_ADMIN_NAME', 'SECMAN_ADMIN_PASS');
    await loginViaApi(page);

    await page.goto('/admin/falcon-config');
    const sidebar = page.locator('#sidebar');
    await expect(sidebar).toBeVisible();

    // Landing on an admin page unrolls ADMIN and the group holding this page.
    await expect(sidebar.getByRole('link', { name: 'CrowdStrike Falcon' })).toBeVisible();
    await expect(sidebar.getByRole('button', { name: /System Configuration/ })).toHaveAttribute('aria-expanded', 'true');
    // ...and the other groups stay shut, with their count showing.
    await expect(sidebar.getByRole('button', { name: /Users & Access/ })).toHaveAttribute('aria-expanded', 'false');
    await expect(sidebar.getByRole('link', { name: 'User Management' })).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/1-folded.png`, clip: { x: 0, y: 0, width: 320, height: 900 } });

    // Opening another group closes this one — only one at a time.
    await sidebar.getByRole('button', { name: /Users & Access/ }).click();
    await expect(sidebar.getByRole('link', { name: 'User Management' })).toBeVisible();
    await expect(sidebar.getByRole('link', { name: 'CrowdStrike Falcon' })).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/2-other-group.png`, clip: { x: 0, y: 0, width: 320, height: 900 } });

    // The filter reaches across folded groups.
    await sidebar.getByLabel('Filter admin menu').fill('not');
    await expect(sidebar.getByRole('link', { name: 'Notification Settings' })).toBeVisible();
    await expect(sidebar.getByRole('link', { name: 'Notification Logs' })).toBeVisible();
    await expect(sidebar.getByRole('link', { name: 'User Management' })).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/3-filtered.png`, clip: { x: 0, y: 0, width: 320, height: 900 } });

    await sidebar.getByLabel('Filter admin menu').fill('zzzz');
    await expect(sidebar.getByText(/Nothing matches/)).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/4-no-match.png`, clip: { x: 0, y: 0, width: 320, height: 700 } });
});
