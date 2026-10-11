import { test, expect, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { requireIsolatedTarget } from './helpers/isolated-target';

// The isolated fixture uses the common admin config with both evidence adapters off.
let sharedConfigId: number;
test.beforeAll(async ({ playwright }) => {
  requireIsolatedTarget();
  const loginContext = await playwright.request.newContext({ baseURL: process.env.FRONTEND_URL! });
  const cookie = await login(loginContext, process.env.SECMAN_ADMIN_NAME!, process.env.SECMAN_ADMIN_PASS!);
  const admin = await playwright.request.newContext({ baseURL: process.env.FRONTEND_URL!, extraHTTPHeaders: { Cookie: cookie } });
  const response = await admin.post('/api/translation-config', { data: {
    apiKey: randomUUID(), baseUrl: 'https://openrouter.ai/api/v1', modelName: 'fixture/model', maxTokens: 512,
    isActive: true, aiBuddyEnabled: true, aiBuddySingleInstance: true, aiBuddyProviders: 'fixture',
    aiBuddyMicrosoftEvidenceEnabled: false, aiBuddyCrowdStrikeEvidenceEnabled: false
  } });
  expect(response.status()).toBe(201);
  const saved = await response.json();
  expect(saved.apiKey).toBe('***HIDDEN***');
  expect(saved.aiBuddyMicrosoftEvidenceEnabled).toBe(false);
  expect(saved.aiBuddyCrowdStrikeEvidenceEnabled).toBe(false);
  sharedConfigId = saved.id;
  await admin.dispose(); await loginContext.dispose();
});
test.afterAll(async ({ playwright }) => {
  if (!sharedConfigId) return;
  const loginContext = await playwright.request.newContext({ baseURL: process.env.FRONTEND_URL! });
  const cookie = await login(loginContext, process.env.SECMAN_ADMIN_NAME!, process.env.SECMAN_ADMIN_PASS!);
  const admin = await playwright.request.newContext({ baseURL: process.env.FRONTEND_URL!, extraHTTPHeaders: { Cookie: cookie } });
  await admin.delete(`/api/translation-config/${sharedConfigId}`);
  await admin.dispose(); await loginContext.dispose();
});
async function login(api: APIRequestContext, username: string, password: string) {
  const response = await api.post('/api/auth/login', { data: { username, password } });
  expect(response.status()).toBe(200);
  const cookie = response.headersArray().filter(header => header.name.toLowerCase() === 'set-cookie')
    .map(header => header.value.split(';')[0]).find(value => value.startsWith('secman_auth='));
  if (!cookie) throw new Error('Login did not set the HttpOnly authentication cookie');
  return cookie;
}
async function create(api: APIRequestContext, path: string, data: unknown): Promise<{ id: number }> {
  const response = await api.post(path, { data });
  expect(response.ok(), `create ${path}: ${response.status()}`).toBeTruthy();
  return response.json();
}

test('live authorization scope old session role refresh report ownership and revocation', async ({ playwright, page }) => {
  const baseURL = process.env.FRONTEND_URL!;
  const loginContext = await playwright.request.newContext({ baseURL });
  const adminCookie = await login(loginContext, process.env.SECMAN_ADMIN_NAME!, process.env.SECMAN_ADMIN_PASS!);
  const admin = await playwright.request.newContext({ baseURL, extraHTTPHeaders: { Cookie: adminCookie } });
  let viewer = await playwright.request.newContext({ baseURL });
  const anon = await playwright.request.newContext({ baseURL });
  const suffix = randomUUID().slice(0, 8);
  const password = `${randomUUID()}aA7!`;
  const group = await create(admin, '/api/workgroups', { name: `buddy-${suffix}` });
  const user = await create(admin, '/api/users', { username: `buddy-${suffix}`, email: `buddy-${suffix}@e2e.test`, password, roles: ['USER'], workgroupIds: [group.id] });
  const asset = await create(admin, '/api/assets', { name: `buddy-${suffix}`, type: 'SERVER', owner: 'fixture', workgroupIds: [group.id] });
  const privateAsset = await create(admin, '/api/assets', { name: `buddy-private-${suffix}`, type: 'SERVER', owner: 'fixture' });
  try {
    const viewerCookie = await login(viewer, `buddy-${suffix}`, password);
    await viewer.dispose();
    viewer = await playwright.request.newContext({ baseURL, extraHTTPHeaders: { Cookie: viewerCookie } });
    expect((await anon.get('/api/ai-buddy/capabilities')).status()).toBe(401);
    expect((await viewer.post(`/api/ai-buddy/assets/${asset.id}/jobs`, { data: { requestId: randomUUID() } })).status()).toBe(403);
    expect((await (await admin.get('/api/ai-buddy/capabilities')).json()).eligible).toBe(true);
    expect((await admin.put(`/api/users/${user.id}`, { data: { roles: ['USER', 'BETA'] } })).ok()).toBeTruthy();
    // The viewer deliberately keeps its original USER-only cookie.
    expect((await (await viewer.get('/api/ai-buddy/capabilities')).json()).eligible).toBe(true);
    expect((await viewer.put(`/api/translation-config/${sharedConfigId}`, { data: { aiBuddyEnabled: false } })).status()).toBe(403);
    expect((await viewer.post(`/api/ai-buddy/assets/${privateAsset.id}/jobs`, { data: { requestId: randomUUID() } })).status()).toBe(404);
    expect((await admin.post('/api/vulnerabilities/cli-add', { data: { hostname: `buddy-${suffix}`, cve: 'CVE-2025-1111', criticality: 'LOW', daysOpen: 1 } })).ok()).toBeTruthy();
    await page.goto('/login');
    await expect(page.locator('astro-island').filter({ has: page.locator('#username') })).not.toHaveAttribute('ssr', '');
    await page.locator('#username').fill(`buddy-${suffix}`);
    await page.locator('#password').fill(password);
    await page.locator('button[type=submit]').click();
    await page.waitForURL(url => !url.pathname.includes('/login'));
    await page.goto(`/vulnerabilities/system?assetId=${asset.id}`);
    await expect(page.getByRole('button', { name: 'AI buddy(beta)', exact: true })).toBeEnabled();
    await expect(page.getByText('CVE-2025-1111', { exact: true })).toBeVisible();
    const requestId = randomUUID();
    const response = await viewer.post(`/api/ai-buddy/assets/${asset.id}/jobs`, { data: { requestId } });
    expect(response.status()).toBe(202);
    expect(response.headers()['cache-control']).toContain('no-store');
    const started = await response.json();
    const repeated = await viewer.post(`/api/ai-buddy/assets/${asset.id}/jobs`, { data: { requestId } });
    expect((await repeated.json()).jobId).toBe(started.jobId);
    const path = `/api/ai-buddy/assets/${asset.id}/jobs/${started.jobId}`;
    await expect.poll(async () => (await (await viewer.get(path)).json()).state).toBe('COMPLETE');
    const reportResponse = await viewer.get(`${path}/report`);
    expect(reportResponse.status()).toBe(200);
    const report = await reportResponse.json();
    expect(report.scope.consideredFindingCount).toBe(1);
    expect(report.analysis.noVerifiedRemediationCount).toBe(1);
    expect(report.analysis.model).toBeNull();
    expect(report.actions).toHaveLength(0);
    expect((await admin.get(`${path}/report`)).status()).toBe(404); // even admins do not own another user's temporary job
    await admin.put(`/api/users/${user.id}`, { data: { roles: ['USER'] } });
    expect((await viewer.get(`${path}/report`)).status()).toBe(403);
    await admin.put(`/api/users/${user.id}`, { data: { roles: ['USER', 'SECCHAMPION'] } });
    expect((await (await viewer.get('/api/ai-buddy/capabilities')).json()).eligible).toBe(true);
    await admin.put(`/api/users/${user.id}`, { data: { roles: ['USER', 'BETA'] } });
    await admin.delete(`/api/workgroups/${group.id}/assets/${asset.id}`);
    expect((await viewer.get(path)).status()).toBe(404);
  } finally {
    await admin.delete(`/api/assets/${asset.id}`); await admin.delete(`/api/assets/${privateAsset.id}`);
    await admin.delete(`/api/users/${user.id}`); await admin.delete(`/api/workgroups/${group.id}`);
    await admin.dispose(); await viewer.dispose(); await anon.dispose(); await loginContext.dispose();
  }
});

test('keyboard panel gives honest empty scope and cancellation without changing findings', async ({ page, playwright }) => {
  await page.goto('/login');
  await expect(page.locator('astro-island').filter({ has: page.locator('#username') })).not.toHaveAttribute('ssr', '');
  await page.locator('#username').fill(process.env.SECMAN_ADMIN_NAME!);
  await page.locator('#password').fill(process.env.SECMAN_ADMIN_PASS!);
  await page.locator('button[type=submit]').click();
  await page.waitForURL(url => !url.pathname.includes('/login'));
  const loginContext = await playwright.request.newContext({ baseURL: process.env.FRONTEND_URL! });
  const cookie = await login(loginContext, process.env.SECMAN_ADMIN_NAME!, process.env.SECMAN_ADMIN_PASS!);
  const admin = await playwright.request.newContext({ baseURL: process.env.FRONTEND_URL!, extraHTTPHeaders: { Cookie: cookie } });
  const asset = await create(admin, '/api/assets', { name: `buddy-ui-${randomUUID().slice(0, 8)}`, type: 'SERVER', owner: 'fixture' });
  try {
    await page.goto(`/vulnerabilities/system?assetId=${asset.id}&severity=CRITICAL&page=99`);
    const button = page.getByRole('button', { name: 'AI buddy(beta)', exact: true });
    await expect(button).toBeEnabled();
    await button.focus(); await page.keyboard.press('Enter');
    await expect(page.getByText('Only recognized public product/version information', { exact: false })).toBeVisible();
    await page.getByRole('button', { name: 'Generate report', exact: true }).click();
    await expect(page.getByText('0 findings considered;', { exact: false })).toBeVisible();
    await expect(page.getByText('Report completeness: Complete', { exact: false })).toBeVisible();
    await expect(page.getByText('This report is partial.', { exact: false })).toHaveCount(0);
    await expect(page.getByText('No verified remediation candidates were found.', { exact: false })).toBeVisible();
    await page.getByRole('heading', { name: 'AI buddy(beta)', exact: true }).focus();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('heading', { name: 'AI buddy(beta)', exact: true })).toHaveCount(0);
    await expect(button).toBeFocused();
    expect((await (await admin.get(`/api/assets/${asset.id}/vulnerabilities`)).json())).toHaveLength(0);
  } finally { await admin.delete(`/api/assets/${asset.id}`); await admin.dispose(); await loginContext.dispose(); }
});


test('admin edits shared AI controls without replacing the common key', async ({ page }) => {
  await page.goto('/login');
  await expect(page.locator('astro-island').filter({ has: page.locator('#username') })).not.toHaveAttribute('ssr', '');
  await page.locator('#username').fill(process.env.SECMAN_ADMIN_NAME!);
  await page.locator('#password').fill(process.env.SECMAN_ADMIN_PASS!);
  await page.locator('button[type=submit]').click();
  await page.waitForURL(url => !url.pathname.includes('/login'));
  await page.goto('/admin/translation-config');
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await expect(page.locator('#aiBuddyEnabled')).toBeChecked();
  await expect(page.locator('#aiBuddyProviders')).toHaveValue('fixture');
  await page.locator('#aiBuddyDailyBudgetUsd').fill('3.50');
  await page.getByRole('button', { name: 'Update', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Update', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await expect(page.locator('#aiBuddyDailyBudgetUsd')).toHaveValue('3.5');
  await expect(page.locator('#apiKey')).toHaveValue('');
  await expect(page.locator('#modelOption option').first()).toHaveAttribute('value', 'openai/gpt-6.1-sol');
  await expect(page.locator('#modelOption option[value="anthropic/claude-sonnet-5.5"]')).toHaveCount(1);
  // A shared configuration must save while the optional beta setup is incomplete.
  await page.locator('#modelOption').selectOption('openai/gpt-6.1-sol');
  await page.locator('#aiBuddyProviders').fill('');
  await page.locator('#aiBuddySingleInstance').uncheck();
  await page.getByRole('button', { name: 'Update', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Update', exact: true })).toHaveCount(0);
  const capabilities = await page.evaluate(async () => {
    const response = await fetch('/api/ai-buddy/capabilities', { credentials: 'include' });
    return { status: response.status, body: await response.json() };
  });
  expect(capabilities.status).toBe(200);
  expect(capabilities.body.available).toBe(false);
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await expect(page.locator('#modelOption')).toHaveValue('openai/gpt-6.1-sol');
  await expect(page.locator('#aiBuddyProviders')).toHaveValue('');
  await expect(page.locator('#aiBuddyEnabled')).toBeChecked();
  await expect(page.locator('#apiKey')).toHaveValue('');
});
