import { test, expect } from '@playwright/test';

test('name search filters table and finds unexpanded children in tree while respecting AWS visibility', async ({ page }, testInfo) => {
  const login = await page.request.post('/api/auth/login', {
    data: { username: process.env.SECMAN_ADMIN_NAME!, password: process.env.SECMAN_ADMIN_PASS! },
  });
  expect(login.ok()).toBeTruthy();
  const admin = { id: 1, username: 'adminuser', roles: ['ADMIN'] };
  await page.addInitScript(user => sessionStorage.setItem('user', JSON.stringify(user)), admin);
  const base = { enabled: true, criticality: 'MEDIUM', userCount: 0, assetCount: 0, depth: 0, childCount: 0, hasChildren: false, ancestors: [], version: 0 };
  const groups = [
    { ...base, id: 1, name: 'Italy-OT', hasChildren: true, childCount: 1 },
    { ...base, id: 2, name: 'Italy-Applications', parentId: 1, depth: 1 },
    { ...base, id: 3, name: 'LEV-OT' },
    { ...base, id: 4, name: 'AWS-Italy' },
  ];
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const body = path === '/api/auth/status' ? admin
      : path === '/api/workgroups' || path === '/api/workgroups/tree' ? groups
        : path === '/api/workgroups/root' ? groups.filter(group => !('parentId' in group))
          : path === '/api/assets' || path === '/api/aws-account-sharing/users' ? []
            : undefined;
    if (body === undefined) return route.continue();
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.goto('/workgroups');
  const search = page.getByRole('searchbox', { name: 'Search workgroups' });
  await search.fill('  iTaLy  ');
  await expect(page.getByRole('row').filter({ hasText: 'Italy-OT' })).toHaveCount(1);
  await expect(page.getByRole('row').filter({ hasText: 'LEV-OT' })).toHaveCount(0);
  await expect(page.getByRole('row').filter({ hasText: 'AWS-Italy' })).toHaveCount(0);
  await page.getByLabel('Show AWS- workgroups').check();
  await expect(page.getByRole('row').filter({ hasText: 'AWS-Italy' })).toHaveCount(1);
  await search.fill('missing');
  await expect(page.getByText('No workgroups match your search.', { exact: true })).toBeVisible();
  await search.fill('Italy');
  await page.getByRole('button', { name: 'Tree View', exact: true }).click();
  await expect(search).toHaveValue('Italy');
  await page.getByRole('button', { name: 'Italy-Applications', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Italy-Applications', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'AWS-Italy', exact: true })).toBeVisible();
  await page.getByLabel('Show AWS- workgroups').uncheck();
  await expect(page.getByRole('button', { name: 'AWS-Italy', exact: true })).toHaveCount(0);
  await page.screenshot({ path: testInfo.outputPath('workgroup-search.png') });
  await search.fill('');
  await expect(page.getByText('Workgroup Hierarchy', { exact: true })).toBeVisible();
});
