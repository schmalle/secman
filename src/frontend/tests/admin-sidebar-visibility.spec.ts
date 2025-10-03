import { test, expect } from '@playwright/test';

test.describe('Admin Sidebar Visibility', () => {

  // T001: Admin user sees Admin menu item
  test('admin user sees admin menu item', async ({ page }) => {
    // Login as admin user
    await page.goto('http://localhost:4321/login');

    // Wait for login form to be visible
    await expect(page.locator('input[type="text"]')).toBeVisible();

    // Fill in admin credentials
    await page.fill('input[type="text"]', 'adminuser');
    await page.fill('input[type="password"]', 'password');

    // Submit login form
    await page.click('button[type="submit"]');

    // Wait for successful login redirect (could be /dashboard or /)
    await page.waitForURL(/.*\/(dashboard)?$/);

    // Verify Admin menu item is visible in sidebar
    const adminLink = page.locator('a[href="/admin"]');
    await expect(adminLink).toBeVisible();

    // Verify Admin menu item has correct text
    await expect(adminLink).toContainText(/Admin/i);

    // Verify Admin menu is clickable and navigates correctly
    await adminLink.click();
    await expect(page).toHaveURL(/.*\/admin/);
  });

  // T002: Regular user does NOT see Admin menu item
  test('regular user does not see admin menu item', async ({ page }) => {
    // Login as regular user
    await page.goto('http://localhost:4321/login');

    // Wait for login form to be visible
    await expect(page.locator('input[type="text"]')).toBeVisible();

    // Fill in regular user credentials
    await page.fill('input[type="text"]', 'regularuser');
    await page.fill('input[type="password"]', 'password');

    // Submit login form
    await page.click('button[type="submit"]');

    // Wait for successful login redirect
    await page.waitForURL(/.*\/(dashboard)?$/);

    // Wait a moment for sidebar to render
    await page.waitForTimeout(1000);

    // Verify Admin menu item is NOT visible
    const adminLink = page.locator('a[href="/admin"]');

    // Check that the admin link either doesn't exist or is not visible
    const count = await adminLink.count();
    if (count > 0) {
      await expect(adminLink).not.toBeVisible();
    } else {
      // Admin link doesn't exist in DOM - this is also acceptable
      expect(count).toBe(0);
    }

    // Verify other menu items are still visible (sanity check)
    await expect(page.locator('a[href="/requirements"]')).toBeVisible();
  });

  // T003: Admin menu visibility persists across navigation
  test('admin menu visibility persists across navigation', async ({ page }) => {
    // Login as admin user
    await page.goto('http://localhost:4321/login');

    // Wait for login form to be visible
    await expect(page.locator('input[type="text"]')).toBeVisible();

    // Fill in admin credentials
    await page.fill('input[type="text"]', 'adminuser');
    await page.fill('input[type="password"]', 'password');

    // Submit login form
    await page.click('button[type="submit"]');

    // Wait for successful login redirect
    await page.waitForURL(/.*\/(dashboard)?$/);

    // Verify Admin menu visible on dashboard
    let adminLink = page.locator('a[href="/admin"]');
    await expect(adminLink).toBeVisible();

    // Navigate to Requirements page
    await page.goto('http://localhost:4321/requirements');
    await page.waitForLoadState('networkidle');
    adminLink = page.locator('a[href="/admin"]');
    await expect(adminLink).toBeVisible();

    // Navigate to Assets page
    await page.goto('http://localhost:4321/assets');
    await page.waitForLoadState('networkidle');
    adminLink = page.locator('a[href="/admin"]');
    await expect(adminLink).toBeVisible();

    // Navigate to Standards page
    await page.goto('http://localhost:4321/standards');
    await page.waitForLoadState('networkidle');
    adminLink = page.locator('a[href="/admin"]');
    await expect(adminLink).toBeVisible();
  });

});
