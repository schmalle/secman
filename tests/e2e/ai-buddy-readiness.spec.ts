import { markAssetObserved } from './helpers/isolated-target';
import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { requireIsolatedTarget } from './helpers/isolated-target';

test('enabled AI buddy explains missing provider and becomes ready after configuration', async ({ page, playwright }) => {
  requireIsolatedTarget();
  await page.goto('/login');
  await expect(page.locator('astro-island').filter({ has: page.locator('#username') })).not.toHaveAttribute('ssr', '');
  await page.locator('#username').fill(process.env.SECMAN_ADMIN_NAME!);
  await page.locator('#password').fill(process.env.SECMAN_ADMIN_PASS!);
  await page.locator('button[type=submit]').click();
  await page.waitForURL(url => !url.pathname.includes('/login'));
  const loginContext = await playwright.request.newContext({ baseURL: process.env.FRONTEND_URL! });
  const login = await loginContext.post('/api/auth/login', { data: {
    username: process.env.SECMAN_ADMIN_NAME!, password: process.env.SECMAN_ADMIN_PASS!
  } });
  expect(login.status()).toBe(200);
  const cookie = login.headersArray().filter(header => header.name.toLowerCase() === 'set-cookie')
    .map(header => header.value.split(';')[0]).find(value => value.startsWith('secman_auth='));
  expect(cookie).toBeTruthy();
  const api = await playwright.request.newContext({ baseURL: process.env.FRONTEND_URL!, extraHTTPHeaders: { Cookie: cookie! } });
  let configId: number | undefined;
  let assetId: number | undefined;
  try {
    const config = await api.post('/api/translation-config', { data: {
      apiKey: randomUUID(), modelName: 'fixture/model', baseUrl: 'https://openrouter.ai/api/v1',
      maxTokens: 512, isActive: true, aiBuddyEnabled: true, aiBuddySingleInstance: true,
      aiBuddyProviders: '', aiBuddyMicrosoftEvidenceEnabled: false
    } });
    expect(config.status()).toBe(201);
    configId = (await config.json()).id;
    const asset = await api.post('/api/assets', { data: { name: `buddy-readiness-${randomUUID()}`, type: 'SERVER', owner: 'fixture' } });
    expect(asset.status()).toBe(201);
    const savedAsset = await asset.json();
    assetId = savedAsset.id;
    markAssetObserved(savedAsset.id);
    const finding = await api.post('/api/vulnerabilities/cli-add', { data: {
      hostname: savedAsset.name, cve: 'CVE-2026-90001', criticality: 'HIGH', daysOpen: 1
    } });
    expect(finding.ok()).toBeTruthy();
    const capabilities = await (await api.get('/api/ai-buddy/capabilities')).json();
    expect(capabilities).toMatchObject({ enabled: true, available: false, unavailableReason: 'APPROVED_PROVIDER_REQUIRED' });
    await page.goto(`/vulnerabilities/system?assetId=${assetId}`);
    const button = page.getByRole('button', { name: 'AI buddy(beta)', exact: true });
    await expect(button).toBeEnabled();
    await expect(page.getByText('AI buddy is enabled; setup required:', { exact: false })).toBeVisible();
    await button.click();
    await expect(page.getByRole('status').filter({ hasText: 'Add an approved OpenRouter provider in LLM Config' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Generate report', exact: true })).toHaveCount(0);
    await expect(page.locator('#severityFilter option')).toHaveText(['High and Critical', 'Critical', 'High']);
    const update = await api.put(`/api/translation-config/${configId}`, { data: { aiBuddyProviders: 'fixture' } });
    expect(update.ok()).toBeTruthy();
    await page.reload();
    await expect(button).toBeEnabled();
    await expect(page.getByText('AI buddy is enabled; setup required:', { exact: false })).toHaveCount(0);
    await button.click();
    await expect(page.getByRole('button', { name: 'Generate report', exact: true })).toBeVisible();
  } finally {
    if (assetId) await api.delete(`/api/assets/${assetId}`);
    if (configId) await api.delete(`/api/translation-config/${configId}`);
    await api.dispose();
    await loginContext.dispose();
  }
});
