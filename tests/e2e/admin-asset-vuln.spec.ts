import { test, expect } from '@playwright/test';

// --- Environment variable validation ---
const requiredEnvVars = [
  'SECMAN_ADMIN_USER',
  'SECMAN_ADMIN_PASS',
  'SECMAN_USER_USER',
  'SECMAN_USER_PASS',
] as const;

const missing = requiredEnvVars.filter((v) => !process.env[v]);
if (missing.length > 0) {
  throw new Error(
    `Missing required environment variables: ${missing.join(', ')}. ` +
    `Set them directly or use ./run-e2e.sh with 1Password.`
  );
}

const ADMIN_USER = process.env.SECMAN_ADMIN_USER!;
const ADMIN_PASS = process.env.SECMAN_ADMIN_PASS!;
const NORMAL_USER = process.env.SECMAN_USER_USER!;
const NORMAL_PASS = process.env.SECMAN_USER_PASS!;

// Unique asset name to avoid collisions across test runs
const DUMMY_ASSET_NAME = `DUMMY-${Date.now()}`;
const DUMMY_CVE = 'CVE-2024-99999';

// --- Helpers ---

async function login(
  page: import('@playwright/test').Page,
  username: string,
  password: string,
) {
  await page.goto('/login');
  await page.waitForLoadState('domcontentloaded');
  // Wait for the login form to be fully hydrated by React
  // The submit button becomes a React-controlled element after hydration
  const submitBtn = page.locator('button[type="submit"]');
  await submitBtn.waitFor({ state: 'visible', timeout: 15_000 });
  // Small delay to ensure React hydration completes
  await page.waitForTimeout(1000);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await submitBtn.click();
  // Wait for redirect after successful login (the Login component uses window.location.href = '/')
  await page.waitForURL((url) => !url.pathname.includes('/login'), {
    timeout: 30_000,
  });
}

async function logout(page: import('@playwright/test').Page) {
  await page.evaluate(() => localStorage.clear());
  await page.context().clearCookies();
}

// --- Test suite (serial: steps depend on each other) ---

test.describe.serial('Admin add system and vulnerability', () => {
  const consoleErrors: string[] = [];

  test('Step 1: Normal user sees asset list without DUMMY', async ({ page }) => {
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    await login(page, NORMAL_USER, NORMAL_PASS);

    // Navigate to assets page
    await page.goto('/assets');
    await page.waitForLoadState('domcontentloaded');

    // Wait for the asset table/list to load
    await page.waitForTimeout(2000);

    // Verify the DUMMY asset does NOT exist yet
    const pageContent = await page.textContent('body');
    expect(pageContent).not.toContain(DUMMY_ASSET_NAME);
  });

  test('Step 2: Admin adds DUMMY system with normal user as owner', async ({ page }) => {
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    await login(page, ADMIN_USER, ADMIN_PASS);

    // Navigate to admin add-system page
    await page.goto('/admin/add-system');
    await page.waitForLoadState('domcontentloaded');

    // Verify page loaded
    await expect(
      page.getByRole('heading', { name: /Add System & Vulnerability/ }),
    ).toBeVisible({ timeout: 10_000 });

    // Fill the asset form
    await page.locator('#asset-name').fill(DUMMY_ASSET_NAME);
    await page.locator('#asset-type').clear();
    await page.locator('#asset-type').fill('SERVER');
    await page.locator('#asset-owner').fill(NORMAL_USER);
    await page.locator('#asset-description').fill('E2E test asset');

    // Submit
    await page.locator('#asset-submit').click();

    // Wait for success message
    await expect(
      page.locator('.alert-success').first(),
    ).toBeVisible({ timeout: 10_000 });

    // Verify success message contains the asset name
    const successText = await page.locator('.alert-success').first().textContent();
    expect(successText).toContain(DUMMY_ASSET_NAME);
    expect(successText).toContain('created successfully');
  });

  test('Step 3: Admin adds HIGH vulnerability (60 days old) to DUMMY', async ({ page }) => {
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    await login(page, ADMIN_USER, ADMIN_PASS);

    // Navigate to admin add-system page
    await page.goto('/admin/add-system');
    await page.waitForLoadState('domcontentloaded');

    // Fill the vulnerability form
    await page.locator('#vuln-hostname').fill(DUMMY_ASSET_NAME);
    await page.locator('#vuln-cve').fill(DUMMY_CVE);
    await page.locator('#vuln-criticality').selectOption('HIGH');
    await page.locator('#vuln-days-open').clear();
    await page.locator('#vuln-days-open').fill('60');

    // Submit
    await page.locator('#vuln-submit').click();

    // Wait for success message
    await expect(
      page.locator('.alert-success').last(),
    ).toBeVisible({ timeout: 10_000 });

    // Verify success message
    const successText = await page.locator('.alert-success').last().textContent();
    expect(successText).toContain(DUMMY_CVE);
    expect(successText).toContain(DUMMY_ASSET_NAME);
  });

  test('Step 4: Normal user can now see DUMMY in asset list', async ({ page }) => {
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    await login(page, NORMAL_USER, NORMAL_PASS);

    // Navigate to assets page
    await page.goto('/assets');
    await page.waitForLoadState('domcontentloaded');

    // Wait for assets to load and search for the DUMMY asset
    await page.waitForTimeout(2000);

    // The asset should be visible because the owner matches the normal user
    await expect(
      page.getByText(DUMMY_ASSET_NAME),
    ).toBeVisible({ timeout: 15_000 });
  });

  test('No JavaScript console errors during the workflow', async () => {
    expect(consoleErrors, 'Expected no JavaScript console errors').toEqual([]);
  });
});
