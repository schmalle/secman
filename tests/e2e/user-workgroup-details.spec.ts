import { test, expect, type Page } from '@playwright/test';

const admin = { id: 1, username: 'adminuser', email: 'admin@example.com', roles: ['ADMIN'] };
const group = { id: 42, name: 'AWS-Example', description: 'Example team', enabled: false, criticality: 'HIGH', ownerEmail: 'owner@example.com' };

async function prepare(page: Page, empty = false) {
  const login = await page.request.post('/api/auth/login', {
    data: { username: process.env.SECMAN_ADMIN_NAME!, password: process.env.SECMAN_ADMIN_PASS! },
  });
  expect(login.ok()).toBeTruthy();
  await page.addInitScript(user => sessionStorage.setItem('user', JSON.stringify(user)), admin);
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    let body: unknown;
    if (url.pathname === '/api/auth/status') body = admin;
    if (url.pathname === '/api/users') body = [{ ...admin, workgroups: [group, { id: 43, name: 'Another team' }] }];
    if (url.pathname === '/api/workgroups') body = [group];
    if (url.pathname === '/api/workgroups/42') body = group;
    if (url.pathname === '/api/workgroups/42/users') body = empty ? [] : [{ id: 2, username: 'member', email: 'member@example.com' }];
    if (url.pathname === '/api/workgroups/42/assets') body = empty ? [] : [{ id: 3, name: 'app-server', type: 'SERVER', ip: '192.0.2.10', ipAddresses: ['192.0.2.10', '2001:db8::10'], owner: 'Example owner' }];
    if (url.pathname === '/api/workgroups/42/aws-accounts') body = empty ? [] : [{ id: 4, awsAccountId: '123456789012' }];
    if (url.pathname === '/api/workgroups/42/ad-domains') body = empty ? [] : [{ id: 5, adDomain: 'corp.example.com' }];
    if (body === undefined) return route.continue();
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.goto('/admin/user-management');
  await page.getByRole('button', { name: group.name, exact: true }).click();
  return page.getByRole('dialog', { name: `Workgroup Details — ${group.name}` });
}

test('workgroup badge opens all assigned details in place and restores focus on Escape', async ({ page }, testInfo) => {
  const dialog = await prepare(page);
  await expect(dialog).toBeVisible();
  await expect(page).toHaveURL(/\/admin\/user-management$/);
  await expect(dialog.getByText('Disabled', { exact: true })).toBeVisible();
  await expect(dialog.getByText('owner@example.com', { exact: true })).toBeVisible();
  await expect(dialog.getByRole('region', { name: 'Users (1)' })).toContainText('member@example.com');
  const assets = dialog.getByRole('region', { name: 'Assets (1)' });
  await expect(assets).toContainText('app-server');
  await expect(assets).toContainText('192.0.2.10');
  await expect(assets).toContainText('2001:db8::10');
  await expect(assets).toContainText('Example owner');
  await expect(dialog.getByRole('region', { name: 'AWS Accounts (1)' })).toContainText('123456789012');
  await expect(dialog.getByRole('region', { name: 'AD Domains (1)' })).toContainText('corp.example.com');
  await expect(dialog.getByRole('link', { name: 'Open Workgroup Management' })).toHaveAttribute('href', '/workgroups?workgroupId=42');
  await page.screenshot({ path: testInfo.outputPath('workgroup-details.png') });
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole('button', { name: group.name, exact: true })).toBeFocused();
});

test('empty assignments are shown explicitly', async ({ page }) => {
  const dialog = await prepare(page, true);
  for (const message of ['No users assigned.', 'No assets directly assigned.', 'No AWS accounts assigned.', 'No AD domains assigned.']) {
    await expect(dialog.getByText(message, { exact: true })).toBeVisible();
  }
});

test('failed section cannot look empty and can be retried', async ({ page }) => {
  const dialog = await prepare(page);
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await page.route('**/api/workgroups/42/ad-domains', route => route.fulfill({ status: 500, body: '{}' }));
  await page.getByRole('button', { name: group.name, exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('Could not load all workgroup details');
  await expect(dialog.getByText('No AD domains assigned.')).toHaveCount(0);
  await page.unroute('**/api/workgroups/42/ad-domains');
  await dialog.getByRole('button', { name: 'Retry' }).click();
  await expect(dialog.getByRole('region', { name: 'AD Domains (1)' })).toContainText('corp.example.com');
});

test('denied workgroup details do not render assignments', async ({ page }) => {
  const dialog = await prepare(page);
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await page.route('**/api/workgroups/42', route => route.fulfill({ status: 403, body: '{}' }));
  await page.getByRole('button', { name: group.name, exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('You do not have permission');
  await expect(dialog.getByRole('region')).toHaveCount(0);
});
