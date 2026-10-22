import { test, expect, type Page } from '@playwright/test';

const fixtures = [41, 42, 43].map(assetId => ({ assetId, name: `Selected asset ${assetId}`, owner: 'Test', type: 'SERVER' }));

async function prepare(page: Page, role = 'ADMIN', failSecond = false) {
  const login = await page.request.post(`${process.env.SECMAN_E2E_BACKEND_URL}/api/auth/login`, {
    data: { username: process.env.SECMAN_ADMIN_NAME!, password: process.env.SECMAN_ADMIN_PASS! },
  });
  expect(login.status()).toBe(200);
  const user = { id: 1, username: 'selection-test', email: 'selection@example.com', roles: [role] };
  await page.addInitScript(user => sessionStorage.setItem('user', JSON.stringify(user)), user);
  let items = [...fixtures];
  const submissions: number[][] = [];
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    let body: unknown;
    if (url.pathname === '/api/auth/status') body = user;
    if (url.pathname === '/api/workgroups') body = [];
    if (url.pathname === '/api/assets/count') body = { count: items.length };
    if (url.pathname === '/api/assets/search') {
      const name = url.searchParams.get('name') ?? '';
      const matching = items.filter(asset => asset.name.includes(name));
      body = { items: matching, matchingCount: matching.length, page: 0, pageSize: 50, totalPages: 2 };
    }
    if (url.pathname === '/api/assets/deletion-preview') {
      const { hostnames } = route.request().postDataJSON();
      expect(hostnames).toBe('host-one, host-two, missing-host');
      body = { assets: fixtures.slice(0, 2).map(asset => ({ id: asset.assetId, name: asset.name,
        hostname: 'host-one', cloudAccountId: '012345678901', cloudInstanceId: `i-${asset.assetId}` })),
        unmatchedHostnames: ['host-two', 'missing-host'] };
    }
    if (url.pathname === '/api/assets/delete-selected') {
      const { assetIds } = route.request().postDataJSON();
      submissions.push(assetIds);
      const results = assetIds.map((assetId: number) => ({ assetId, deleted: !(failSecond && assetId === 42),
        message: failSecond && assetId === 42 ? 'Asset is referenced by a demand.' : 'Asset deleted.' }));
      items = items.filter(asset => !results.some((result: { assetId: number; deleted: boolean }) => result.assetId === asset.assetId && result.deleted));
      body = { results };
    }
    if (body === undefined) return route.continue();
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.goto('/assets');
  await expect(page.getByRole('cell', { name: 'Selected asset 41 SecMan #41', exact: true })).toBeVisible();
  return submissions;
}

test('admin confirms exact selections and only selected IDs are sent', async ({ page }, testInfo) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const submissions = await prepare(page);
  const deleteButton = page.getByRole('button', { name: 'Delete selected assets (0)', exact: true });
  await expect(deleteButton).toBeDisabled();
  await page.getByLabel('Select Selected asset 41 (SecMan #41)', { exact: true }).check();
  await page.getByLabel('Select Selected asset 43 (SecMan #43)', { exact: true }).check();
  await page.getByRole('button', { name: 'Delete selected assets (2)', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Delete selected assets', exact: true });
  await expect(dialog.getByText('Selected asset 41 (SecMan #41)', { exact: true })).toBeVisible();
  await expect(dialog.getByText('Selected asset 43 (SecMan #43)', { exact: true })).toBeVisible();
  await expect(dialog.getByText('Selected asset 42', { exact: false })).toHaveCount(0);
  await page.screenshot({ path: testInfo.outputPath('selected-delete-confirmation.png') });
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(submissions).toEqual([]);
  await page.getByRole('button', { name: 'Delete selected assets (2)', exact: true }).click();
  await dialog.getByRole('button', { name: 'Permanently delete 2 assets', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Deleted 2 of 2 selected assets.');
  expect(submissions).toEqual([[41, 43]]);
  await expect(page.getByRole('cell', { name: 'Selected asset 42 SecMan #42', exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'Selected asset 41 SecMan #41', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Delete selected assets (0)', exact: true })).toBeDisabled();
  expect(errors).toEqual([]);
});

test('select all is scoped to the page and selection clears on filter and page changes', async ({ page }) => {
  await prepare(page);
  await page.getByLabel('Select all assets on this page', { exact: true }).check();
  await expect(page.getByRole('button', { name: 'Delete selected assets (3)', exact: true })).toBeEnabled();
  await page.getByLabel('Name', { exact: true }).fill('Selected asset 41');
  await expect(page.getByRole('button', { name: 'Delete selected assets (0)', exact: true })).toBeDisabled();
  await expect(page.getByRole('cell', { name: 'Selected asset 42 SecMan #42', exact: true })).toHaveCount(0);
  await page.getByLabel('Select all assets on this page', { exact: true }).check();
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Delete selected assets (0)', exact: true })).toBeDisabled();
});

test('partial failure remains visible after the asset list refreshes', async ({ page }, testInfo) => {
  await prepare(page, 'ADMIN', true);
  await page.getByLabel('Select all assets on this page', { exact: true }).check();
  await page.getByRole('button', { name: 'Delete selected assets (3)', exact: true }).click();
  await page.getByRole('button', { name: 'Permanently delete 3 assets', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Deleted 2 of 3 selected assets.');
  await expect(page.getByRole('alert')).toContainText('SecMan #42: Asset is referenced by a demand.');
  await expect(page.getByRole('cell', { name: 'Selected asset 42 SecMan #42', exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('selected-delete-partial-failure.png') });
});

for (const role of ['USER', 'SECCHAMPION']) {
  test(`${role} cannot see selected asset deletion controls`, async ({ page }) => {
    await prepare(page, role);
    await expect(page.getByRole('button', { name: /Delete selected assets/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Delete by hostname', exact: true })).toHaveCount(0);
    await expect(page.getByLabel('Select all assets on this page', { exact: true })).toHaveCount(0);
    await expect(page.getByLabel('Select Selected asset 41 (SecMan #41)', { exact: true })).toHaveCount(0);
  });
}


test('hostname deletion previews duplicates, invalidates edits and reports partial results', async ({ page }, testInfo) => {
  const submissions = await prepare(page, 'ADMIN', true);
  await page.getByRole('button', { name: 'Delete by hostname', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Delete assets by hostname', exact: true });
  await expect(dialog.getByRole('button', { name: /Permanently delete/ })).toBeDisabled();
  await dialog.getByLabel('Comma-separated hostnames').fill('host-one, host-two, missing-host');
  await dialog.getByRole('button', { name: 'Preview matching assets', exact: true }).click();
  await expect(dialog.getByText('No matching assets:', { exact: false })).toContainText('missing-host');
  await expect(dialog.getByRole('cell', { name: 'host-one', exact: true })).toHaveCount(2);
  expect(submissions).toEqual([]);
  await dialog.getByLabel('Comma-separated hostnames').fill('changed');
  await expect(dialog.getByRole('button', { name: /Permanently delete/ })).toBeDisabled();
  await dialog.getByLabel('Comma-separated hostnames').fill('host-one, host-two, missing-host');
  await dialog.getByRole('button', { name: 'Preview matching assets', exact: true }).click();
  await expect(dialog.getByRole('button', { name: 'Permanently delete 2 assets', exact: true })).toBeEnabled();
  await page.screenshot({ path: testInfo.outputPath('hostname-delete-preview.png') });
  await dialog.getByRole('button', { name: 'Permanently delete 2 assets', exact: true }).click();
  await expect(dialog.getByRole('status')).toContainText('1 deleted; 1 retained.');
  await expect(dialog.getByRole('status')).toContainText('SecMan #42: Retained');
  await expect(dialog.getByRole('button', { name: /Permanently delete/ })).toBeDisabled();
  expect(submissions).toEqual([[41, 42]]);
  await dialog.getByRole('button', { name: 'Close', exact: true }).last().click();
  await expect(page.getByRole('cell', { name: 'Selected asset 41 SecMan #41', exact: true })).toHaveCount(0);
  await expect(page.getByRole('cell', { name: 'Selected asset 42 SecMan #42', exact: true })).toBeVisible();
});


test('admin section opens the hostname deletion dialog', async ({ page }) => {
  await prepare(page);
  await page.goto('/admin');
  await page.getByRole('button', { name: 'Delete by hostname', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Delete assets by hostname', exact: true });
  await expect(dialog.getByLabel('Comma-separated hostnames')).toBeVisible();
  await expect(dialog.getByRole('button', { name: /Permanently delete/ })).toBeDisabled();
});
