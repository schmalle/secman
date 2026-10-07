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

test('popup enables, cancels disabling, and preserves details when a save fails', async ({ page }) => {
  const dialog = await prepare(page);
  let updates = 0;
  await page.route('**/api/workgroups/42', async route => {
    if (route.request().method() !== 'PUT') return route.fallback();
    updates++;
    expect(route.request().postDataJSON()).toEqual({ enabled: updates === 1 });
    await route.fulfill(updates === 1
      ? { contentType: 'application/json', body: JSON.stringify({ ...group, enabled: true }) }
      : { status: 500, contentType: 'application/json', body: JSON.stringify({ error: 'Failed to update workgroup status' }) });
  });
  await dialog.getByRole('button', { name: 'Enable', exact: true }).click();
  await expect(dialog.getByText('Enabled', { exact: true })).toBeVisible();
  page.once('dialog', confirmation => confirmation.dismiss());
  await dialog.getByRole('button', { name: 'Disable', exact: true }).click();
  expect(updates).toBe(1);
  page.once('dialog', async confirmation => {
    expect(confirmation.message()).toContain('will no longer grant access');
    await confirmation.accept();
  });
  await dialog.getByRole('button', { name: 'Disable', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('Failed to update workgroup status');
  await expect(dialog.getByText('Enabled', { exact: true })).toBeVisible();
  await expect(dialog.getByRole('region', { name: 'Users (1)' })).toContainText('member@example.com');
});

test('popup status changes persist through the real API and reopening', async ({ page }, testInfo) => {
  const login = await page.request.post('/api/auth/login', {
    data: { username: process.env.SECMAN_ADMIN_NAME!, password: process.env.SECMAN_ADMIN_PASS! },
  });
  expect(login.ok()).toBeTruthy();
  const users = await (await page.request.get('/api/users')).json();
  const currentUser = users.find((user: { username: string }) => user.username === process.env.SECMAN_ADMIN_NAME);
  expect(currentUser).toBeDefined();
  const name = `Popup status ${Date.now()}`;
  const created = await page.request.post('/api/workgroups', { data: { name, criticality: 'MEDIUM' } });
  expect(created.ok()).toBeTruthy();
  const workgroup = await created.json();
  try {
    const assigned = await page.request.post(`/api/workgroups/${workgroup.id}/users`, { data: { userIds: [currentUser.id] } });
    expect(assigned.ok()).toBeTruthy();
    await page.goto('/admin/user-management');
    await page.getByRole('button', { name, exact: true }).click();
    const dialog = page.getByRole('dialog', { name: `Workgroup Details — ${name}` });
    page.once('dialog', confirmation => confirmation.accept());
    await dialog.getByRole('button', { name: 'Disable', exact: true }).click();
    await expect(dialog.getByText('Disabled', { exact: true })).toBeVisible();
    expect((await (await page.request.get(`/api/workgroups/${workgroup.id}`)).json()).enabled).toBe(false);
    await page.screenshot({ path: testInfo.outputPath('workgroup-status-control.png') });
    await dialog.getByRole('button', { name: 'Close', exact: true }).click();
    await page.getByRole('button', { name, exact: true }).click();
    await expect(dialog.getByText('Disabled', { exact: true })).toBeVisible();
    await dialog.getByRole('button', { name: 'Enable', exact: true }).click();
    await expect(dialog.getByText('Enabled', { exact: true })).toBeVisible();
    expect((await (await page.request.get(`/api/workgroups/${workgroup.id}`)).json()).enabled).toBe(true);
  } finally {
    expect((await page.request.delete(`/api/workgroups/${workgroup.id}`)).ok()).toBeTruthy();
  }
});
