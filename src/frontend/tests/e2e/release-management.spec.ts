/**
 * E2E Test: Release Management Workflow
 *
 * Tests release creation, viewing, and deletion workflows
 * Scenarios from specs/011-i-want-to/quickstart.md: Scenarios 1, 2, 6
 *
 * Related to: Feature 011-i-want-to (Release-Based Requirement Version Management)
 */

import { test, expect } from '@playwright/test';

test.describe('Release Management', () => {
    test('RELEASE_MANAGER can create and manage releases', async ({ page }) => {
        // Login as RELEASE_MANAGER
        await page.goto('/login');
        await page.fill('input[name="username"]', 'releasemanager');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Navigate to Releases page
        await page.click('text=Releases');
        await page.waitForURL('/releases');

        // Verify "Create New Release" button is visible
        await expect(page.locator('button:has-text("Create New Release")')).toBeVisible();

        // Click "Create Release"
        await page.click('button:has-text("Create New Release")');

        // Wait for modal to appear
        await expect(page.locator('.modal-title:has-text("Create New Release")')).toBeVisible();

        // Fill form
        await page.fill('input[placeholder*="1.0.0"]', '1.0.0');
        await page.fill('input[placeholder*="Q1 2024"]', 'Test Release');
        await page.fill('textarea[placeholder*="Describe"]', 'E2E test release');

        // Submit
        await page.click('button:has-text("Create Release")');

        // Wait for modal to close and success
        await page.waitForTimeout(1000);

        // Verify release appears in table
        await expect(page.locator('td:has-text("v1.0.0")')).toBeVisible();
        await expect(page.locator('td:has-text("Test Release")')).toBeVisible();

        // Create second release
        await page.click('button:has-text("Create New Release")');
        await expect(page.locator('.modal-title:has-text("Create New Release")')).toBeVisible();
        await page.fill('input[placeholder*="1.0.0"]', '1.1.0');
        await page.fill('input[placeholder*="Q1 2024"]', 'Second Test Release');
        await page.click('button:has-text("Create Release")');
        await page.waitForTimeout(1000);

        // Verify both releases appear
        await expect(page.locator('td:has-text("v1.0.0")')).toBeVisible();
        await expect(page.locator('td:has-text("v1.1.0")')).toBeVisible();

        // Delete first release
        const deleteButton = page.locator('tr:has-text("v1.0.0") button[title="Delete"]').first();
        await deleteButton.click();

        // Confirm deletion in alert dialog
        page.on('dialog', dialog => dialog.accept());

        // Wait for deletion to complete
        await page.waitForTimeout(1000);

        // Verify release is removed from table
        await expect(page.locator('td:has-text("v1.0.0")')).not.toBeVisible();

        // Second release should still be visible
        await expect(page.locator('td:has-text("v1.1.0")')).toBeVisible();
    });

    test('version validation enforces semantic versioning', async ({ page }) => {
        // Login as RELEASE_MANAGER
        await page.goto('/login');
        await page.fill('input[name="username"]', 'releasemanager');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Navigate to Releases page
        await page.click('text=Releases');
        await page.waitForURL('/releases');

        // Open create modal
        await page.click('button:has-text("Create New Release")');
        await expect(page.locator('.modal-title:has-text("Create New Release")')).toBeVisible();

        // Try invalid version format
        await page.fill('input[placeholder*="1.0.0"]', 'v1.0');
        await page.fill('input[placeholder*="Q1 2024"]', 'Invalid Version Test');

        // Verify validation error appears
        await expect(page.locator('text=semantic versioning')).toBeVisible();

        // Create button should be disabled
        const createButton = page.locator('.modal button:has-text("Create Release")');
        await expect(createButton).toBeDisabled();

        // Fix version
        await page.fill('input[placeholder*="1.0.0"]', '1.0.0');

        // Validation error should disappear and button enabled
        await expect(createButton).toBeEnabled();
    });

    test('duplicate version is rejected', async ({ page }) => {
        // Login as RELEASE_MANAGER
        await page.goto('/login');
        await page.fill('input[name="username"]', 'releasemanager');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Navigate to Releases page
        await page.click('text=Releases');
        await page.waitForURL('/releases');

        // Create first release
        await page.click('button:has-text("Create New Release")');
        await page.fill('input[placeholder*="1.0.0"]', '2.0.0');
        await page.fill('input[placeholder*="Q1 2024"]', 'Duplicate Test');
        await page.click('button:has-text("Create Release")');
        await page.waitForTimeout(1000);

        // Try to create duplicate
        await page.click('button:has-text("Create New Release")');
        await page.fill('input[placeholder*="1.0.0"]', '2.0.0');
        await page.fill('input[placeholder*="Q1 2024"]', 'Duplicate Test 2');
        await page.click('button:has-text("Create Release")');

        // Should show error
        await expect(page.locator('.alert-danger, text=already exists, text=duplicate')).toBeVisible();
    });

    test('release shows frozen requirement count', async ({ page }) => {
        // Login as RELEASE_MANAGER
        await page.goto('/login');
        await page.fill('input[name="username"]', 'releasemanager');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Navigate to Releases page
        await page.click('text=Releases');
        await page.waitForURL('/releases');

        // Create release
        await page.click('button:has-text("Create New Release")');
        await page.fill('input[placeholder*="1.0.0"]', '3.0.0');
        await page.fill('input[placeholder*="Q1 2024"]', 'Count Test');
        await page.click('button:has-text("Create Release")');
        await page.waitForTimeout(1000);

        // Verify requirement count badge appears in table
        const countRow = page.locator('tr:has-text("v3.0.0")');
        await expect(countRow.locator('.badge:has-text("frozen")')).toBeVisible();
    });
});
