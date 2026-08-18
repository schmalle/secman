import { test } from '@playwright/test';
import { loginViaApi } from './helpers/session';

test('debug count', async ({ page }) => {
    await loginViaApi(page);
    await page.goto('/admin/falcon-config');
    await page.waitForSelector('#sidebar .admin-nav-group');
    const info = await page.evaluate(() => {
        return Array.from(document.querySelectorAll('#sidebar .admin-nav-group')).map((b) => ({
            text: (b as HTMLElement).innerText,
            expanded: b.getAttribute('aria-expanded'),
            countHtml: b.querySelector('.admin-nav-count')?.outerHTML ?? null,
            countRect: JSON.stringify(b.querySelector('.admin-nav-count')?.getBoundingClientRect()),
            btnRect: JSON.stringify(b.getBoundingClientRect()),
        }));
    });
    console.log(JSON.stringify(info, null, 2));
});
