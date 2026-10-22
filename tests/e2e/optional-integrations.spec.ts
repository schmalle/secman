import { test, expect } from '@playwright/test';

test('source results use exact bindings, reject resolution, and appear in integration history', async ({ page }, testInfo) => {
  const pageErrors: string[] = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  const base = process.env.SECMAN_E2E_BACKEND_URL!;
  expect(process.env.SECMAN_TEST_ISOLATED_DB).toBeTruthy();
  const login = await page.request.post(`${base}/api/auth/login`, { data: {
    username: process.env.SECMAN_ADMIN_NAME!, password: process.env.SECMAN_ADMIN_PASS!,
  } });
  expect(login.status()).toBe(200);
  const userResponse = await page.request.get(`${base}/api/auth/status`);
  const user = await userResponse.json();
  await page.addInitScript(value => sessionStorage.setItem('user', JSON.stringify(value)), user);
  const usersResponse = await page.request.get(`${base}/api/users`);
  const users = await usersResponse.json();
  const admin = users.find((item: { username: string }) => item.username === process.env.SECMAN_ADMIN_NAME);
  expect(admin).toBeTruthy();
  const name = `integration-source-${Date.now()}`;
  const assetResponse = await page.request.post(`${base}/api/assets`, { data: { name, type: 'REPOSITORY', owner: admin.username, uri: 'https://example.test/project' } });
  expect(assetResponse.status(), await assetResponse.text()).toBe(201);
  const asset = await assetResponse.json();
  const scannerResponse = await page.request.post(`${base}/api/integrations/v1/scanners`, { data: { name, source: 'SOURCE_CODE', serviceUserId: admin.id } });
  expect(scannerResponse.ok(), await scannerResponse.text()).toBeTruthy();
  const scanner = await scannerResponse.json();
  await page.goto('/integrations');
  await page.getByRole('combobox', { name: 'Scanner', exact: true }).selectOption(String(scanner.id));
  await page.getByRole('button', { name: 'Scanner settings', exact: true }).click();
  await page.getByLabel('Inventory ID', { exact: true }).fill(String(asset.id));
  await page.getByRole('button', { name: 'Inspect target', exact: true }).click();
  await expect(page.getByText('Confirm this exact target:')).toBeVisible();
  await expect(page.locator('dd').filter({ hasText: name })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('integration-exact-binding.png') });
  await page.getByRole('button', { name: 'Register this target' }).click();
  await expect(page.getByText('Target registered. The service user must separately have asset access.')).toBeVisible();
  const subjectsResponse = await page.request.get(`${base}/api/integrations/v1/scanners/${scanner.id}/subjects`);
  const subjects = await subjectsResponse.json();
  const subject = subjects.content.find((item: { assetId: number }) => item.assetId === asset.id);
  expect(subject).toBeTruthy();
  const now = new Date().toISOString();
  const body = { scannerId: scanner.id, subjectId: subject.id, runKey: name, status: 'SUCCESS', completeCoverage: false,
    startedAt: now, completedAt: now, metadataJson: JSON.stringify({ adapter: 'secman_source', coverageReason: 'SOURCE_REVIEW_NOT_PROOF_OF_ABSENCE' }),
    findings: [{ externalId: 'source-v1:fixture', severity: 'HIGH', title: 'Source integration fixture', description: 'Validation: candidate', filePath: 'app.py', lineRange: '4-6' }] };
  const rejected = await page.request.post(`${base}/api/integrations/v1/runs`, { data: { ...body, completeCoverage: true } });
  expect(rejected.status()).toBe(400);
  const submitted = await page.request.post(`${base}/api/integrations/v1/runs`, { data: body });
  expect(submitted.ok(), await submitted.text()).toBeTruthy();
  const first = await submitted.json();
  const replay = await page.request.post(`${base}/api/integrations/v1/runs`, { data: body });
  expect(await replay.json()).toMatchObject({ id: first.id, replayed: true });
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await page.getByRole('button', { name: 'Findings', exact: true }).click();
  await page.getByRole('button', { name: 'Source integration fixture', exact: true }).click();
  await expect(page.getByText('Validation: candidate')).toBeVisible();
  await page.getByRole('button', { name: 'Scan history', exact: true }).click();
  await page.getByRole('button', { name: 'Run details', exact: true }).click();
  await expect(page.getByText('Evidence as submitted in this run, independent of the finding’s current state.')).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('integration-source-history.png') });
  expect(pageErrors).toEqual([]);
});
