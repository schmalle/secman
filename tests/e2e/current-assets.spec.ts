import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { requireIsolatedTarget } from './helpers/isolated-target';

test('current inventory hides stale and unobserved assets and accepts re-observation', async ({ page }) => {
  requireIsolatedTarget();
  const login = await page.request.post('/api/auth/login', {
    data: { username: process.env.SECMAN_ADMIN_NAME!, password: process.env.SECMAN_ADMIN_PASS! },
  });
  expect(login.ok()).toBeTruthy();
  await page.addInitScript(user => sessionStorage.setItem('user', JSON.stringify(user)), await login.json());
  const cookie = (await page.context().cookies()).find(value => value.name === 'secman_auth');
  expect(cookie).toBeDefined();
  const headers = { Authorization: `Bearer ${cookie!.value}` };
  const prefix = `current-${randomUUID().slice(0, 8)}`;
  const ids: number[] = [];
  const importAsset = async (name: string, observedAt?: string) => {
    const response = await page.request.put('/api/assets/import', {
      headers, data: { name, type: 'SERVER', owner: 'fixture', observedAt },
    });
    expect(response.status()).toBe(200);
    const body = await response.json();
    if (body.created) ids.push(body.asset.id);
    return body.asset;
  };
  try {
    const current = await importAsset(`${prefix}-current`, new Date(Date.now() - 3600000).toISOString());
    const stale = await importAsset(`${prefix}-stale`, new Date(Date.now() - 25 * 3600000).toISOString());
    const unknown = await importAsset(`${prefix}-unknown`);
    const search = async () => {
      const response = await page.request.get(`/api/assets/search?name=${prefix}`, { headers });
      expect(response.ok()).toBeTruthy();
      return response.json();
    };
    let result = await search();
    expect(result.matchingCount).toBe(1);
    expect(result.items.map((item: { assetId: number }) => item.assetId)).toEqual([current.id]);
    for (const asset of [stale, unknown]) {
      expect((await page.request.get(`/api/assets/${asset.id}`, { headers })).status()).toBe(404);
    }
    const future = await page.request.put('/api/assets/import', {
      headers, data: { name: stale.name, type: 'SERVER', owner: 'fixture',
        observedAt: new Date(Date.now() + 3600000).toISOString() },
    });
    expect(future.status()).toBe(400);
    expect((await search()).matchingCount).toBe(1);
    const restored = await importAsset(stale.name, new Date(Date.now() - 1000).toISOString());
    expect(restored.id).toBe(stale.id);
    result = await search();
    expect(result.matchingCount).toBe(2);
    expect(ids).toHaveLength(3);
    await page.goto(`/assets?name=${prefix}`);
    await expect(page.locator('tbody tr').filter({ hasText: prefix })).toHaveCount(2);
    await expect(page.getByLabel('Falcon sensor contact', { exact: true }).locator('option').first()).toHaveText('All current assets (seen within 24 hours)');

    for (const asset of [current, unknown]) {
      const finding = await page.request.post('/api/vulnerabilities/cli-add', {
        headers, data: { hostname: asset.name, cve: 'CVE-2099-8321', criticality: 'HIGH', daysOpen: 5 },
      });
      expect(finding.ok()).toBeTruthy();
    }
    await page.goto('/analytics');
    const filteredRows = page.waitForResponse(response => {
      const url = new URL(response.url());
      return url.pathname === '/api/vulnerabilities/current' && url.searchParams.get('system') === prefix;
    });
    await page.getByLabel('System', { exact: true }).fill(prefix);
    expect((await filteredRows).ok()).toBeTruthy();
    await expect(page.getByRole('heading', { name: 'Vulnerabilities (1 not excepted)', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: current.name, exact: true })).toHaveAttribute('href', new RegExp(`assetId=${current.id}(?:&|$)`));
    await expect(page.getByRole('link', { name: unknown.name, exact: true })).toHaveCount(0);
    await page.getByLabel('Overdue Status', { exact: true }).selectOption('all');
    await expect(page.getByRole('heading', { name: 'Vulnerabilities (1 incl. excepted)', exact: true })).toBeVisible();
    const workbook = page.waitForEvent('download', { timeout: 20_000 });
    const exportRequest = page.waitForRequest(request =>
      new URL(request.url()).pathname === '/api/vulnerabilities/export' && request.method() === 'POST');
    await page.getByRole('button', { name: /\bExport$/ }).click();
    expect((await exportRequest).postDataJSON()).toMatchObject({ system: prefix, exceptionStatus: 'all' });
    const download = await workbook;
    expect(download.suggestedFilename()).toMatch(/^vulnerabilities_export_.*\.xlsx$/);
    expect(await download.failure()).toBeNull();
  } finally {
    for (const id of ids) {
      expect((await page.request.delete(`/api/assets/${id}`, { headers })).ok()).toBeTruthy();
    }
  }
});
