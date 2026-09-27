import { test, expect } from '@playwright/test';

// The runner owns this database, its accounts, and the loopback SMTP provider.
test('welcome delivery survives import replay, respects opt-out, and previews CSV without writes', async ({ page }) => {
  test.skip(!process.env.SECMAN_TEST_ISOLATED_DB, 'Requires disposable test runner');
  await page.goto('/login');
  await page.locator('input[name="username"], input#username').first().fill(process.env.SECMAN_ADMIN_NAME!);
  await page.locator('input[name="password"], input#password').first().fill(process.env.SECMAN_ADMIN_PASS!);
  await page.getByRole('button', { name: 'Login', exact: true }).click();
  await page.waitForURL(url => !url.pathname.includes('login'));
  const stamp = Date.now().toString().slice(-6);
  const account = `877${stamp}000`;
  const optedOut = `878${stamp}000`;
  const preview = `879${stamp}000`;
  const owner = `mail-${stamp}@example.test`;
  const secondOwner = `second-${stamp}@example.test`;
  // Run same-origin authenticated fetch so the application's CSRF cookie is echoed.
  const call = async (path: string, data?: unknown, method?: string) => page.evaluate(async ({ path, data, method }) => {
    const csrf = document.cookie.split('; ').find(item => item.startsWith('PLAY_CSRF_TOKEN='))?.split('=').slice(1).join('=');
    const response = await fetch(path, { method: method ?? (data ? 'POST' : 'GET'), credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...(csrf ? { 'Csrf-Token': decodeURIComponent(csrf) } : {}) },
      ...(data ? { body: JSON.stringify(data) } : {}) });
    const body = await response.text();
    return { status: response.status, body: body ? JSON.parse(body) : null };
  }, { path, data, method });
  const payload = { onboardingMode: 'WELCOME_ONLY', mappings: [
    { email: owner, awsAccountId: account },
    { email: owner.toUpperCase(), awsAccountId: account },
    { email: secondOwner, awsAccountId: account },
  ] };
  const imported = await call('/api/user-mappings/bulk', payload);
  expect(imported.status).toBe(200);
  expect(imported.body.onboarding).toHaveLength(2);
  for (const result of imported.body.onboarding) {
    expect(result.welcomeEmail).toMatchObject({ requested: true, status: 'SENT', retryable: false });
    expect(result.welcomeEmail.notificationId).toBeGreaterThan(0);
  }
  const replay = await call('/api/user-mappings/bulk', payload);
  expect(replay.status).toBe(200);
  expect(replay.body.onboarding ?? []).toHaveLength(0);
  const id = imported.body.onboarding[0].welcomeEmail.notificationId;
  expect((await call(`/api/admin/owner-mail-notifications/${id}/retry`, {})).status).toBe(409);
  const suppressed = await call('/api/user-mappings/bulk', {
    onboardingMode: 'WELCOME_ONLY', sendWelcomeEmail: false,
    mappings: [{ email: owner, awsAccountId: optedOut }],
  });
  expect(suppressed.body.onboarding[0].welcomeEmail).toMatchObject({ requested: false, status: 'SKIPPED' });
  const provider = await call('/api/email-config/active');
  expect(provider.status).toBe(200);
  let failedId: number;
  try {
    expect((await call(`/api/email-config/${provider.body.id}`, { isActive: false }, 'PUT')).status).toBe(200);
    const failed = await call('/api/user-mappings/bulk', {
      onboardingMode: 'WELCOME_ONLY', mappings: [{ email: owner, awsAccountId: `876${stamp}000` }],
    });
    expect(failed.status).toBe(200);
    expect(failed.body.created + failed.body.createdPending).toBe(1);
    expect(failed.body.onboarding[0].welcomeEmail).toMatchObject({ status: 'FAILED', retryable: true, errorCode: 'NO_ACTIVE_PROVIDER' });
    failedId = failed.body.onboarding[0].welcomeEmail.notificationId;
  } finally {
    expect((await call(`/api/email-config/${provider.body.id}`, { isActive: true }, 'PUT')).status).toBe(200);
  }
  await page.goto('/admin/account-onboarding');
  await page.getByRole('button', { name: `Retry notification #${failedId}` }).click();
  await expect(page.getByRole('button', { name: `Retry notification #${failedId}` })).not.toBeVisible();
  expect((await call(`/api/admin/owner-mail-notifications/${failedId}/retry`, {})).status).toBe(409);
  const csv = `account_id,owner_email\n${preview},${owner}\n`;
  const upload = async (dryRun: boolean) => page.evaluate(async ({ csv, dryRun }) => {
    const form = new FormData();
    form.append('csvFile', new Blob([csv], { type: 'text/csv' }), 'owners.csv');
    const csrf = document.cookie.split('; ').find(item => item.startsWith('PLAY_CSRF_TOKEN='))?.split('=').slice(1).join('=');
    const response = await fetch(`/api/import/upload-user-mappings-csv?onboardingMode=WELCOME_ONLY&dryRun=${dryRun}`, {
      method: 'POST', body: form, credentials: 'include',
      headers: csrf ? { 'Csrf-Token': decodeURIComponent(csrf) } : {},
    });
    const body = await response.text();
    return { status: response.status, body: body ? JSON.parse(body) : null };
  }, { csv, dryRun });
  const dry = await upload(true);
  expect(dry.status).toBe(200);
  expect(dry.body.onboarding[0].welcomeEmail.status).toBe('WOULD_SEND');
  expect(dry.body.onboarding[0].welcomeEmail.notificationId ?? null).toBeNull();
  const actual = await upload(false);
  expect(actual.status).toBe(200);
  expect(actual.body.onboarding[0].welcomeEmail.status).toBe('SENT');
  const deliveries = await call('/api/admin/owner-mail-notifications?page=0&pageSize=100');
  expect(deliveries.status).toBe(200);
  expect(deliveries.body.notifications.filter((row: any) => row.awsAccountId === account)).toHaveLength(2);
  await page.goto('/admin/account-onboarding');
  await expect(page.getByRole('heading', { name: 'Owner welcome-mail delivery' })).toBeVisible();
  await expect(page.getByRole('cell', { name: secondOwner, exact: true })).toBeVisible();
});
