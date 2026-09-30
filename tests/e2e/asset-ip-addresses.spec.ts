import { test, expect, type Page } from '@playwright/test';

const admin = { id: 1, username: 'adminuser', email: 'admin@example.com', roles: ['ADMIN'] };
const addresses = ['10.4.0.10', '10.77.32.9', '10.8.0.10', '2001:db8::9'];
const asset = { assetId: 42, name: 'Multi-IP system', ipAddress: addresses[0], ipAddresses: addresses, owner: 'Example owner' };

async function prepare(page: Page, omitEmptyItems = false) {
  const login = await page.request.post('/api/auth/login', {
    data: { username: process.env.SECMAN_ADMIN_NAME!, password: process.env.SECMAN_ADMIN_PASS! },
  });
  expect(login.ok()).toBeTruthy();
  await page.addInitScript(user => sessionStorage.setItem('user', JSON.stringify(user)), admin);
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    let body: unknown;
    if (url.pathname === '/api/auth/status') body = admin;
    if (url.pathname === '/api/workgroups') body = [];
    if (url.pathname === '/api/assets/count') body = { count: 1 };
    if (url.pathname === '/api/assets/search') {
      const ip = url.searchParams.get('ip');
      const matches = !ip || addresses.some(address => address.includes(ip));
      body = { items: matches ? [asset] : omitEmptyItems ? undefined : [], matchingCount: matches ? 1 : 0, page: 0, pageSize: 50, totalPages: matches ? 1 : 0 };
    }
    if (body === undefined) return route.continue();
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.goto('/assets');
  await expect(page.getByRole('cell', { name: asset.name, exact: true })).toBeVisible();
}

test('inventory displays IPv4 and IPv6 addresses and retains a secondary-IP match', async ({ page }, testInfo) => {
  await prepare(page);
  const row = page.getByRole('row').filter({ has: page.getByRole('cell', { name: asset.name, exact: true }) });
  for (const address of addresses) await expect(row.getByText(address, { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'More filters', exact: true }).click();
  await page.getByLabel('IP Address', { exact: true }).fill('10.77.32.9');
  await expect(page).toHaveURL(/ip=10\.77\.32\.9/);
  await expect(row).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('multiple-ip-addresses.png') });
  await page.getByLabel('IP Address', { exact: true }).fill('192.0.2.254');
  await expect(page.getByText('No assets match the current filters.', { exact: true })).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('older API responses that omit empty items do not crash the inventory', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await prepare(page, true);
  await page.getByRole('button', { name: 'More filters', exact: true }).click();
  await page.getByLabel('IP Address', { exact: true }).fill('192.0.2.254');
  await expect(page.getByText('No assets match the current filters.', { exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});

test('imported addresses survive a UI edit and partial refresh and remain searchable', async ({ page }, testInfo) => {
  const login = await page.request.post('/api/auth/login', {
    data: { username: process.env.SECMAN_ADMIN_NAME!, password: process.env.SECMAN_ADMIN_PASS! },
  });
  expect(login.ok()).toBeTruthy();
  await page.addInitScript(user => sessionStorage.setItem('user', JSON.stringify(user)), await login.json());
  // APIRequestContext does not send Secure cookies over the isolated HTTP transport.
  const authCookie = (await page.context().cookies()).find(cookie => cookie.name === 'secman_auth');
  expect(authCookie).toBeDefined();
  const headers = { Authorization: `Bearer ${authCookie!.value}` };
  const hostname = `multi-ip-e2e-${Date.now()}`;
  let assetId: number | undefined;
  const importAddresses = async (reported: string[]) => {
    const response = await page.request.post('/api/crowdstrike/servers/import', { headers, data: [{
      hostname, ip: addresses[0], ipAddresses: reported, groups: null, adDomain: null,
      cloudAccountId: null, cloudInstanceId: null, osVersion: null, vulnerabilities: [],
    }] });
    expect(response.status()).toBe(200);
    expect((await response.json()).errors ?? []).toEqual([]);
  };
  try {
    await importAddresses(addresses);
    const overview = await page.request.get(`/api/assets/search?name=${hostname}&ip=${addresses[1]}`, { headers });
    expect(overview.ok()).toBeTruthy();
    const result = await overview.json();
    expect(result.matchingCount).toBe(1);
    expect(result.items[0].ipAddresses).toEqual(addresses);
    assetId = result.items[0].assetId;
    await page.goto(`/assets?name=${hostname}`);
    const row = page.getByRole('row').filter({ has: page.getByRole('cell', { name: hostname, exact: true }) });
    for (const address of addresses) await expect(row.getByText(address, { exact: true })).toBeVisible();
    await row.getByRole('button', { name: /Edit$/ }).click();
    await expect(page.getByLabel('Type *', { exact: true })).toHaveValue('SERVER');
    await page.getByLabel('Primary IP Address', { exact: true }).fill('10.4.0.11');
    await page.getByRole('button', { name: 'Update', exact: true }).click();
    await expect(row.getByText('10.4.0.11', { exact: true })).toBeVisible();
    const edited = await page.request.get(`/api/assets/${assetId}`, { headers });
    expect((await edited.json()).type).toBe('SERVER');
    for (const address of addresses) await expect(row.getByText(address, { exact: true })).toBeVisible();
    await importAddresses([addresses[0]]);
    await page.reload();
    for (const address of [...addresses, '10.4.0.11']) await expect(row.getByText(address, { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'More filters', exact: true }).click();
    const searched = page.waitForResponse(response => {
      const url = new URL(response.url());
      return url.pathname === '/api/assets/search' && url.searchParams.get('ip') === addresses[1];
    });
    await page.getByLabel('IP Address', { exact: true }).fill(addresses[1]);
    expect((await (await searched).json()).matchingCount).toBe(1);
    await expect(row).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath('imported-multiple-ip-addresses.png') });
  } finally {
    if (assetId !== undefined) {
      const removed = await page.request.delete(`/api/assets/${assetId}`, { headers });
      expect(removed.ok()).toBeTruthy();
    }
  }
});
