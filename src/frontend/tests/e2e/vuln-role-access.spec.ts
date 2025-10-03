/**
 * E2E Test: VULN Role Access Control
 *
 * Tests the VULN role access control:
 * 1. Normal user cannot see Vuln Management in sidebar
 * 2. Normal user gets 403 on direct URL access
 * 3. VULN user sees Vuln Management menu
 * 4. VULN user can access both pages
 * 5. ADMIN user has identical access
 *
 * Related to: Feature 004-i-want-to (VULN Role & Vulnerability Management UI)
 */

import { test, expect } from '@playwright/test';

test.describe('VULN Role Access Control', () => {
    test('normal user cannot see Vuln Management in sidebar', async ({ page }) => {
        // Login as normal user
        await page.goto('/login');
        await page.fill('input[name="username"]', 'normaluser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Verify sidebar does not show Vuln Management
        await expect(page.locator('text=Vuln Management')).not.toBeVisible();

        // Also verify the submenu items are not present
        await expect(page.locator('text=Current Vulnerabilities')).not.toBeVisible();
        await expect(page.locator('text=Vulnerability Exceptions')).not.toBeVisible();
    });

    test('normal user gets 403 on direct URL access to current vulnerabilities', async ({ page }) => {
        // Login as normal user
        await page.goto('/login');
        await page.fill('input[name="username"]', 'normaluser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Attempt direct access to current vulnerabilities page
        await page.goto('/vulnerabilities/current');

        // Should show access denied or error message
        await expect(page.locator('.alert-danger, text=Access Denied, text=403, text=Forbidden')).toBeVisible();
    });

    test('normal user gets 403 on direct URL access to vulnerability exceptions', async ({ page }) => {
        // Login as normal user
        await page.goto('/login');
        await page.fill('input[name="username"]', 'normaluser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Attempt direct access to exceptions page
        await page.goto('/vulnerabilities/exceptions');

        // Should show access denied or error message
        await expect(page.locator('.alert-danger, text=Access Denied, text=403, text=Forbidden')).toBeVisible();
    });

    test('VULN user sees Vuln Management menu', async ({ page }) => {
        // Login as VULN user (assuming vulnuser exists)
        await page.goto('/login');
        await page.fill('input[name="username"]', 'vulnuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Verify sidebar shows Vuln Management
        await expect(page.locator('text=Vuln Management')).toBeVisible();

        // Click to expand if collapsed
        const vulnManagementLink = page.locator('a:has-text("Vuln Management")');
        if (await vulnManagementLink.getAttribute('aria-expanded') === 'false') {
            await vulnManagementLink.click();
        }

        // Verify submenu items are visible
        await expect(page.locator('a:has-text("Current Vulnerabilities")')).toBeVisible();
        await expect(page.locator('a:has-text("Vulnerability Exceptions")')).toBeVisible();
    });

    test('VULN user can access current vulnerabilities page', async ({ page }) => {
        // Login as VULN user
        await page.goto('/login');
        await page.fill('input[name="username"]', 'vulnuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Navigate to current vulnerabilities page
        await page.goto('/vulnerabilities/current');

        // Verify page loads successfully
        await expect(page.locator('h3:has-text("Current Vulnerabilities")')).toBeVisible();

        // Verify table structure is present
        await expect(page.locator('table')).toBeVisible();
    });

    test('VULN user can access vulnerability exceptions page', async ({ page }) => {
        // Login as VULN user
        await page.goto('/login');
        await page.fill('input[name="username"]', 'vulnuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Navigate to exceptions page
        await page.goto('/vulnerabilities/exceptions');

        // Verify page loads successfully
        await expect(page.locator('h3:has-text("Vulnerability Exceptions")')).toBeVisible();

        // Verify Create Exception button is present
        await expect(page.locator('button:has-text("Create Exception")')).toBeVisible();
    });

    test('ADMIN user has identical access to VULN user', async ({ page }) => {
        // Login as admin
        await page.goto('/login');
        await page.fill('input[name="username"]', 'admin');
        await page.fill('input[name="password"]', 'admin');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Verify sidebar shows Vuln Management
        await expect(page.locator('text=Vuln Management')).toBeVisible();

        // Verify can access current vulnerabilities
        await page.goto('/vulnerabilities/current');
        await expect(page.locator('h3:has-text("Current Vulnerabilities")')).toBeVisible();

        // Verify can access exceptions
        await page.goto('/vulnerabilities/exceptions');
        await expect(page.locator('h3:has-text("Vulnerability Exceptions")')).toBeVisible();
        await expect(page.locator('button:has-text("Create Exception")')).toBeVisible();
    });

    test('VULN user can navigate between both pages using sidebar', async ({ page }) => {
        // Login as VULN user
        await page.goto('/login');
        await page.fill('input[name="username"]', 'vulnuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Expand Vuln Management menu if collapsed
        const vulnManagementLink = page.locator('a:has-text("Vuln Management")');
        if (await vulnManagementLink.getAttribute('aria-expanded') === 'false') {
            await vulnManagementLink.click();
        }

        // Navigate to Current Vulnerabilities
        await page.click('a:has-text("Current Vulnerabilities")');
        await expect(page.locator('h3:has-text("Current Vulnerabilities")')).toBeVisible();

        // Navigate to Vulnerability Exceptions
        await page.click('a:has-text("Vulnerability Exceptions")');
        await expect(page.locator('h3:has-text("Vulnerability Exceptions")')).toBeVisible();

        // Navigate back to Current Vulnerabilities
        await page.click('a:has-text("Current Vulnerabilities")');
        await expect(page.locator('h3:has-text("Current Vulnerabilities")')).toBeVisible();
    });
});
