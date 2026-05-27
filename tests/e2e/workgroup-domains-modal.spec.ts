import { test, expect } from '@playwright/test';

test('AD domains modal lists existing secman domains', async ({ page }) => {
  const user = {
    id: 1,
    username: 'adminuser',
    email: 'admin@example.com',
    roles: ['ADMIN'],
  };
  await page.addInitScript((storedUser) => {
    window.sessionStorage.setItem('user', JSON.stringify(storedUser));
  }, user);

  await page.route('**/api/auth/status', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(user),
    });
  });
  await page.route('**/api/workgroups', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: 1,
          name: 'Demo',
          description: '',
          criticality: 'MEDIUM',
          userCount: 0,
          assetCount: 0,
          awsAccountsCount: 0,
          adDomainsCount: 0,
          createdAt: '2026-05-27T10:00:00Z',
          updatedAt: '2026-05-27T10:00:00Z',
        },
      ]),
    });
  });
  await page.route('**/api/aws-account-sharing/users', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: '[]' });
  });
  await page.route('**/api/assets', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: '[]' });
  });
  await page.route('**/api/workgroups/1/ad-domains', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: '[]' });
  });
  await page.route('**/api/vulnerability-ad-domains', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(['corp.example.com', 'emea.example.com']),
    });
  });

  await page.goto('/workgroups');
  await page.getByRole('button', { name: 'Domains' }).click();

  const listbox = page.getByRole('listbox', { name: 'Existing AD domains' });
  await expect(listbox).toBeVisible();
  await expect(listbox.locator('option')).toHaveText([
    'corp.example.com',
    'emea.example.com',
  ]);
});
