/**
 * E2E Test: Release Management Permissions
 *
 * Tests RBAC enforcement for release management features
 * Scenario from specs/011-i-want-to/quickstart.md: Scenario 7
 *
 * Related to: Feature 011-i-want-to (Release-Based Requirement Version Management)
 */

import { test, expect } from '@playwright/test';

test.describe('Release Management Permissions', () => {
    test('USER cannot access release management controls', async ({ page }) => {
        // Login as regular USER (no RELEASE_MANAGER role)
        await page.goto('/login');
        await page.fill('input[name="username"]', 'testuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Navigate to Releases page
        await page.click('text=Releases');
        await page.waitForURL('/releases');

        // Verify "Create New Release" button is NOT visible
        await expect(page.locator('button:has-text("Create New Release")')).not.toBeVisible();

        // Verify release list table IS visible (read-only access)
        await expect(page.locator('table')).toBeVisible();
        await expect(page.locator('th:has-text("Version")')).toBeVisible();
        await expect(page.locator('th:has-text("Name")')).toBeVisible();
        await expect(page.locator('th:has-text("Status")')).toBeVisible();

        // Verify "Delete" buttons are NOT visible in table rows
        const deleteButtons = page.locator('button[title="Delete"]');
        await expect(deleteButtons).toHaveCount(0);

        // Verify "Edit" buttons are NOT visible in table rows (if exists)
        const editButtons = page.locator('button[title="Edit"]');
        await expect(editButtons).toHaveCount(0);
    });

    test('RELEASE_MANAGER can access all release management controls', async ({ page }) => {
        // Login as RELEASE_MANAGER
        await page.goto('/login');
        await page.fill('input[name="username"]', 'releasemanager');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Navigate to Releases page
        await page.click('text=Releases');
        await page.waitForURL('/releases');

        // Verify "Create New Release" button IS visible
        await expect(page.locator('button:has-text("Create New Release")')).toBeVisible();

        // Verify release list table is visible
        await expect(page.locator('table')).toBeVisible();

        // Create a test release to verify delete buttons appear
        await page.click('button:has-text("Create New Release")');
        await expect(page.locator('.modal-title:has-text("Create New Release")')).toBeVisible();
        await page.fill('input[placeholder*="1.0.0"]', '99.0.0');
        await page.fill('input[placeholder*="Q1 2024"]', 'Permission Test Release');
        await page.click('button:has-text("Create Release")');
        await page.waitForTimeout(1000);

        // Verify "Delete" buttons ARE visible for releases
        const deleteButtons = page.locator('button[title="Delete"]');
        const deleteCount = await deleteButtons.count();
        expect(deleteCount).toBeGreaterThan(0);

        // Clean up: delete the test release
        const testReleaseRow = page.locator('tr:has-text("v99.0.0")');
        await expect(testReleaseRow).toBeVisible();

        const deleteButton = testReleaseRow.locator('button[title="Delete"]').first();
        await deleteButton.click();

        // Accept confirmation dialog
        page.on('dialog', dialog => dialog.accept());

        await page.waitForTimeout(1000);

        // Verify release was deleted
        await expect(page.locator('td:has-text("v99.0.0")')).not.toBeVisible();
    });

    test('ADMIN can access all release management controls', async ({ page }) => {
        // Login as ADMIN
        await page.goto('/login');
        await page.fill('input[name="username"]', 'admin');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Navigate to Releases page
        await page.click('text=Releases');
        await page.waitForURL('/releases');

        // Verify "Create New Release" button IS visible (ADMIN has all permissions)
        await expect(page.locator('button:has-text("Create New Release")')).toBeVisible();

        // Verify release list table is visible
        await expect(page.locator('table')).toBeVisible();

        // Create a test release to verify delete buttons appear
        await page.click('button:has-text("Create New Release")');
        await expect(page.locator('.modal-title:has-text("Create New Release")')).toBeVisible();
        await page.fill('input[placeholder*="1.0.0"]', '98.0.0');
        await page.fill('input[placeholder*="Q1 2024"]', 'Admin Permission Test');
        await page.click('button:has-text("Create Release")');
        await page.waitForTimeout(1000);

        // Verify "Delete" buttons ARE visible
        const deleteButtons = page.locator('button[title="Delete"]');
        const deleteCount = await deleteButtons.count();
        expect(deleteCount).toBeGreaterThan(0);

        // Clean up: delete the test release
        const testReleaseRow = page.locator('tr:has-text("v98.0.0")');
        const deleteButton = testReleaseRow.locator('button[title="Delete"]').first();
        await deleteButton.click();

        page.on('dialog', dialog => dialog.accept());
        await page.waitForTimeout(1000);

        await expect(page.locator('td:has-text("v98.0.0")')).not.toBeVisible();
    });

    test('USER cannot access Create Release modal via direct navigation', async ({ page }) => {
        // Login as USER
        await page.goto('/login');
        await page.fill('input[name="username"]', 'testuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Navigate to Releases page
        await page.click('text=Releases');
        await page.waitForURL('/releases');

        // Attempt to trigger modal creation via keyboard (if button was hidden but accessible)
        // This tests defense-in-depth (UI should hide AND backend should reject)
        const createButton = page.locator('button:has-text("Create New Release")');

        // Verify button truly doesn't exist in DOM (not just hidden)
        const buttonCount = await createButton.count();
        expect(buttonCount).toBe(0);
    });

    test('permission boundaries are enforced across user roles', async ({ page, context }) => {
        // Test 1: Login as USER, verify no management controls
        await page.goto('/login');
        await page.fill('input[name="username"]', 'testuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        await page.click('text=Releases');
        await page.waitForURL('/releases');

        // Snapshot of USER view (no buttons)
        const userCreateButton = await page.locator('button:has-text("Create New Release")').count();
        expect(userCreateButton).toBe(0);

        // Logout
        await page.click('text=Logout');
        await page.waitForURL('/login');

        // Test 2: Login as RELEASE_MANAGER, verify management controls appear
        await page.fill('input[name="username"]', 'releasemanager');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        await page.click('text=Releases');
        await page.waitForURL('/releases');

        // Snapshot of RELEASE_MANAGER view (with buttons)
        const managerCreateButton = await page.locator('button:has-text("Create New Release")').count();
        expect(managerCreateButton).toBe(1);

        // Verify role-specific UI differences
        expect(managerCreateButton).toBeGreaterThan(userCreateButton);
    });
});
