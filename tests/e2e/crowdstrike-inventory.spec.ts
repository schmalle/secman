import { test, expect } from '@playwright/test';

test('Falcon inventory distinguishes retained instances from recent sensor contact', async ({ page }, testInfo) => {
  const login = await page.request.post('/api/auth/login', {
    data: { username: process.env.SECMAN_ADMIN_NAME!, password: process.env.SECMAN_ADMIN_PASS! },
  });
  expect(login.ok()).toBeTruthy();
  await page.addInitScript(user => sessionStorage.setItem('user', JSON.stringify(user)), await login.json());
  const cookie = (await page.context().cookies()).find(value => value.name === 'secman_auth');
  expect(cookie).toBeDefined();
  const headers = { Authorization: `Bearer ${cookie!.value}` };
  const suffix = Date.now();
  const hostname = `falcon-inventory-${suffix}`;
  const assetIds: number[] = [];
  try {
    for (const [index, ageHours] of [2, 72].entries()) {
      const device = {
        aid: `falcon-e2e-${suffix}-${index}`, hostname, instanceId: `i-${suffix}-${index}`,
        cloudAccountId: '123456789012', adDomain: null,
        firstSeen: new Date(Date.now() - 10 * 86400000).toISOString(),
        lastSeen: new Date(Date.now() - ageHours * 3600000).toISOString(),
        productType: index === 0 ? 'Server' : 'Domain Controller',
      };
      const response = await page.request.post('/api/crowdstrike/servers/import', { headers, data: [{
        hostname, groups: null, cloudAccountId: device.cloudAccountId, cloudInstanceId: device.instanceId,
        adDomain: null, osVersion: null, ip: null, vulnerabilities: [], runSeverities: ['HIGH'],
        crowdStrikeAids: [device.aid], deviceSelection: { selected: device, superseded: [] },
      }] });
      expect(response.ok()).toBeTruthy();
      expect((await response.json()).errors ?? []).toEqual([]);
    }
    const overview = await page.request.get(`/api/assets/search?name=${hostname}`, { headers });
    const inventory = await overview.json();
    assetIds.push(...inventory.items.map((item: { assetId: number }) => item.assetId));
    expect(inventory.matchingCount).toBe(2);
    await page.goto(`/assets?name=${hostname}`);
    await expect(page.locator('tbody tr').filter({ hasText: hostname })).toHaveCount(2);
    await expect(page.getByText('Domain controller', { exact: true })).toBeVisible();
    await page.getByLabel('Falcon sensor contact', { exact: true }).selectOption('recent');
    await expect(page).toHaveURL(/falconActivity=recent/);
    await expect(page.locator('tbody tr').filter({ hasText: hostname })).toHaveCount(1);
    await expect(page.getByText('Domain controller', { exact: true })).toHaveCount(0);
    await page.getByLabel('Falcon sensor contact', { exact: true }).selectOption('older');
    await expect(page.getByText('Domain controller', { exact: true })).toBeVisible();
    await expect(page.locator('tbody tr').filter({ hasText: hostname })).toHaveCount(1);
    await page.screenshot({ path: testInfo.outputPath('falcon-retained-inventory.png') });
    await page.getByLabel('Falcon sensor contact', { exact: true }).selectOption('unknown');
    await expect(page.getByText('No assets match the current filters.', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Clear all', exact: true }).click();
    await expect(page).not.toHaveURL(/falconActivity=/);
  } finally {
    for (const id of assetIds) {
      const removed = await page.request.delete(`/api/assets/${id}`, { headers });
      expect(removed.ok()).toBeTruthy();
    }
  }
});
