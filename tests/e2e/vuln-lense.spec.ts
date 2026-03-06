import { test, expect } from '@playwright/test';

// --- Environment variable validation (FR-007, FR-010) ---
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

// --- Shared test flow ---

async function loginAndNavigateToLense(
  page: import('@playwright/test').Page,
  username: string,
  password: string,
  consoleErrors: string[],
) {
  // Monitor console errors throughout the test (FR-005, FR-011)
  page.on('console', (msg) => {
    if (msg.type() === 'error') {
      consoleErrors.push(msg.text());
    }
  });

  // Step 1: Navigate to login page
  await page.goto('/login');
  await page.waitForLoadState('networkidle');

  // Step 2: Fill credentials and submit (FR-001)
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('button[type="submit"]').click();

  // Step 3: Wait for login to complete (redirect away from /login)
  await page.waitForURL((url) => !url.pathname.includes('/login'), {
    timeout: 15_000,
  });

  // Step 4: Expand Vulnerability Management sidebar submenu (FR-003)
  await page.getByText('VULNERABILITY MANAGEMENT').click();

  // Step 5: Click "Lense" menu item
  await page.getByRole('link', { name: 'Lense' }).click();

  // Step 6: Verify page renders structurally (FR-004)
  await expect(
    page.getByRole('heading', { name: /Vulnerability Statistics Lense/ }),
  ).toBeVisible({ timeout: 15_000 });
}

// --- Test suites ---

test.describe('Admin user', () => {
  test('login and navigate to Vulnmanagement Lense', async ({ page }) => {
    const consoleErrors: string[] = [];

    await loginAndNavigateToLense(page, ADMIN_USER, ADMIN_PASS, consoleErrors);

    // Assert zero JS console errors (FR-005)
    expect(consoleErrors, 'Expected no JavaScript console errors').toEqual([]);
  });
});

test.describe('Normal user', () => {
  test('login and navigate to Vulnmanagement Lense', async ({ page }) => {
    const consoleErrors: string[] = [];

    await loginAndNavigateToLense(page, NORMAL_USER, NORMAL_PASS, consoleErrors);

    // Assert zero JS console errors (FR-005)
    expect(consoleErrors, 'Expected no JavaScript console errors').toEqual([]);
  });
});
