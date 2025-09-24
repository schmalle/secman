import { test, expect } from '@playwright/test';

test.describe('Admin UI Email Configuration Access', () => {

  test.beforeEach(async ({ page }) => {
    // Login as admin user before each test
    await page.goto('http://localhost:4321/login');

    // Wait for login form to be visible
    await expect(page.locator('input[type="text"]')).toBeVisible();

    // Fill in admin credentials
    await page.fill('input[type="text"]', 'adminuser');
    await page.fill('input[type="password"]', 'password');

    // Submit login form
    await page.click('button[type="submit"]');

    // Wait for successful login redirect
    await expect(page).toHaveURL(/.*\/dashboard/);
  });

  test('should allow admin to access email configuration page', async ({ page }) => {
    // Navigate to admin email config page
    await page.goto('http://localhost:4321/admin/email-config');

    // Should load without authentication errors
    await expect(page).toHaveURL('http://localhost:4321/admin/email-config');

    // Should display email configuration form
    await expect(page.locator('h1')).toContainText('Email Configuration');

    // Should show email configuration management component
    await expect(page.locator('[data-testid="email-config-form"]')).toBeVisible();

    // Should display SMTP configuration fields
    await expect(page.locator('input[name="smtpHost"]')).toBeVisible();
    await expect(page.locator('input[name="smtpPort"]')).toBeVisible();
    await expect(page.locator('input[name="fromEmail"]')).toBeVisible();
    await expect(page.locator('input[name="fromName"]')).toBeVisible();
  });

  test('should display existing email configurations', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/email-config');

    // Should show configurations list
    await expect(page.locator('[data-testid="config-list"]')).toBeVisible();

    // Should show at least the headers even if no configs exist
    await expect(page.locator('thead')).toBeVisible();

    // Check for configuration table columns
    await expect(page.locator('th')).toContainText(['Name', 'SMTP Host', 'Status']);
  });

  test('should show proper navigation and breadcrumbs', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/email-config');

    // Should show admin section in navigation
    await expect(page.locator('[data-testid="admin-nav"]')).toBeVisible();

    // Should show current page in breadcrumbs
    await expect(page.locator('[data-testid="breadcrumb"]')).toContainText('Email Configuration');
  });

  test('should handle page refresh without losing authentication', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/email-config');

    // Refresh the page
    await page.reload();

    // Should still be on the email config page
    await expect(page).toHaveURL('http://localhost:4321/admin/email-config');

    // Should still show the email configuration form
    await expect(page.locator('[data-testid="email-config-form"]')).toBeVisible();
  });

  test('should show error handling for failed API calls', async ({ page }) => {
    // Intercept API call and make it fail
    await page.route('**/api/email-configs', route => {
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Internal Server Error' })
      });
    });

    await page.goto('http://localhost:4321/admin/email-config');

    // Should show error message
    await expect(page.locator('[data-testid="error-message"]')).toBeVisible();
    await expect(page.locator('[data-testid="error-message"]')).toContainText('Failed to load');
  });

  test('should redirect non-admin users', async ({ page }) => {
    // Logout first
    await page.goto('http://localhost:4321/logout');

    // Login as normal user
    await page.goto('http://localhost:4321/login');
    await page.fill('input[type="text"]', 'normaluser');
    await page.fill('input[type="password"]', 'password');
    await page.click('button[type="submit"]');

    // Try to access admin email config
    await page.goto('http://localhost:4321/admin/email-config');

    // Should be redirected to unauthorized or dashboard
    await expect(page).not.toHaveURL('http://localhost:4321/admin/email-config');

    // Should show access denied message or redirect to dashboard
    const url = page.url();
    expect(url).toMatch(/(dashboard|unauthorized|403)/);
  });

  test('should handle loading states properly', async ({ page }) => {
    // Slow down API response to test loading state
    await page.route('**/api/email-configs', async route => {
      await new Promise(resolve => setTimeout(resolve, 1000));
      route.continue();
    });

    await page.goto('http://localhost:4321/admin/email-config');

    // Should show loading indicator
    await expect(page.locator('[data-testid="loading-spinner"]')).toBeVisible();

    // Loading should disappear when data loads
    await expect(page.locator('[data-testid="loading-spinner"]')).not.toBeVisible({ timeout: 5000 });
  });

  test('should maintain responsive design on mobile', async ({ page }) => {
    // Set mobile viewport
    await page.setViewportSize({ width: 375, height: 667 });

    await page.goto('http://localhost:4321/admin/email-config');

    // Should show mobile-friendly layout
    await expect(page.locator('[data-testid="mobile-menu"]')).toBeVisible();

    // Form should be usable on mobile
    await expect(page.locator('[data-testid="email-config-form"]')).toBeVisible();

    // Fields should be properly sized
    const hostInput = page.locator('input[name="smtpHost"]');
    await expect(hostInput).toBeVisible();

    const boundingBox = await hostInput.boundingBox();
    expect(boundingBox?.width).toBeGreaterThan(200); // Reasonable width for mobile
  });
});