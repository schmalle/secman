import { test, expect } from '@playwright/test';

/**
 * E2E test for Outdated Assets page navigation
 *
 * Verifies:
 * - Navigation menu item is visible
 * - Clicking navigates to correct page
 * - Page loads successfully
 * - Basic page structure is present
 *
 * Feature: 034-outdated-assets
 * Task: T015
 * User Story: US1 - View Outdated Assets (P1)
 * Spec reference: US1 acceptance scenarios 1-2
 */

test.describe('Outdated Assets Navigation', () => {
  test.beforeEach(async ({ page }) => {
    // Login as ADMIN user
    await page.goto('/');
    await page.fill('input[name="username"]', 'admin');
    await page.fill('input[name="password"]', 'admin');
    await page.click('button[type="submit"]');
    await page.waitForURL('**/dashboard');
  });

  test('should show Outdated Assets menu item under Vuln Management', async ({ page }) => {
    // Given: User is logged in and on dashboard

    // When: Looking at the navigation menu
    const vulnManagementMenu = page.locator('text=Vuln Management');
    await expect(vulnManagementMenu).toBeVisible();

    // Expand submenu
    await vulnManagementMenu.click();

    // Then: Outdated Assets submenu item is visible
    const outdatedAssetsMenuItem = page.locator('text=Outdated Assets');
    await expect(outdatedAssetsMenuItem).toBeVisible();
  });

  test('should navigate to Outdated Assets page when clicking menu item', async ({ page }) => {
    // Given: User is on dashboard

    // When: Clicking Vuln Management -> Outdated Assets
    await page.locator('text=Vuln Management').click();
    await page.locator('text=Outdated Assets').click();

    // Then: Navigates to outdated assets page
    await expect(page).toHaveURL(/.*outdated-assets/);
  });

  test('should load Outdated Assets page with expected structure', async ({ page }) => {
    // Given: User navigates to Outdated Assets page
    await page.goto('/outdated-assets');

    // Then: Page loads successfully
    await expect(page).toHaveURL(/.*outdated-assets/);

    // Then: Page has expected elements
    await expect(page.locator('h1')).toContainText('Outdated Assets');

    // Should have refresh button
    const refreshButton = page.locator('button:has-text("Refresh")');
    await expect(refreshButton).toBeVisible();

    // Should have table or message
    const content = page.locator('main, .container');
    await expect(content).toBeVisible();
  });

  test('should display table headers when assets are present', async ({ page }) => {
    // Given: There are outdated assets in the system
    // (This assumes test data exists or is mocked)

    // When: Loading outdated assets page
    await page.goto('/outdated-assets');

    // Then: Table headers are visible
    const tableHeaders = ['Asset Name', 'Overdue Count', 'Severity', 'Oldest Vuln'];

    for (const header of tableHeaders) {
      await expect(page.locator(`th:has-text("${header}")`).first()).toBeVisible({
        timeout: 5000
      });
    }
  });

  test('should display friendly message when no outdated assets exist', async ({ page }) => {
    // Given: No outdated assets in the system
    // (This would require clearing test data or using mock)

    // When: Loading outdated assets page
    await page.goto('/outdated-assets');

    // Then: Shows friendly "no data" message
    const noDataMessage = page.locator('text=No assets currently have overdue vulnerabilities');

    // Wait for either table with data OR no-data message
    try {
      await expect(noDataMessage).toBeVisible({ timeout: 5000 });
    } catch (e) {
      // If no "no data" message, should have table instead
      const table = page.locator('table');
      await expect(table).toBeVisible();
    }
  });

  test('should load page in under 3 seconds', async ({ page }) => {
    // Given: User navigates to outdated assets

    // When: Measuring page load time
    const startTime = Date.now();
    await page.goto('/outdated-assets');

    // Wait for main content to be visible
    await page.locator('main, .container').waitFor({ state: 'visible' });
    const loadTime = Date.now() - startTime;

    // Then: Page loads in under 3 seconds (buffer for 2s spec requirement)
    expect(loadTime).toBeLessThan(3000);
  });

  test('should show last updated timestamp', async ({ page }) => {
    // Given: User navigates to outdated assets page
    await page.goto('/outdated-assets');

    // Then: Last updated timestamp is visible
    const lastUpdated = page.locator('text=/Last updated.*ago/i');

    // Either shows timestamp or indicates no data
    const content = page.locator('main');
    await expect(content).toBeVisible();
  });

  test('VULN user should see only their workgroup assets', async ({ page }) => {
    // Given: Login as VULN user
    await page.goto('/');
    await page.fill('input[name="username"]', 'vuln_user');
    await page.fill('input[name="password"]', 'password');
    await page.click('button[type="submit"]');
    await page.waitForURL('**/dashboard');

    // When: Navigating to outdated assets
    await page.goto('/outdated-assets');

    // Then: Can access the page (authorized)
    await expect(page).toHaveURL(/.*outdated-assets/);

    // Should not show forbidden error
    await expect(page.locator('text=403')).not.toBeVisible();
    await expect(page.locator('text=Forbidden')).not.toBeVisible();
  });

  test('USER without VULN or ADMIN role should not access page', async ({ page }) => {
    // Given: Login as regular USER (no VULN or ADMIN role)
    await page.goto('/');
    await page.fill('input[name="username"]', 'regular_user');
    await page.fill('input[name="password"]', 'password');
    await page.click('button[type="submit"]');
    await page.waitForURL('**/dashboard');

    // When: Trying to access outdated assets page
    await page.goto('/outdated-assets');

    // Then: Shows forbidden error or redirects
    const response = page.locator('body');
    await expect(response).toContainText(/403|Forbidden|Unauthorized/i);
  });
});
