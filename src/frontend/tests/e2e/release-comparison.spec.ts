/**
 * E2E Test: Release Comparison UI
 *
 * Tests the release comparison feature with visual diff display
 * Scenario from specs/011-i-want-to/quickstart.md: Scenario 5
 *
 * Related to: Feature 011-i-want-to (Release-Based Requirement Version Management)
 */

import { test, expect } from '@playwright/test';

test.describe('Release Comparison', () => {
    test('compare two releases shows added, deleted, modified sections', async ({ page }) => {
        // Login as user
        await page.goto('/login');
        await page.fill('input[name="username"]', 'testuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Navigate to Comparison page (assuming there's a menu item or direct URL)
        await page.goto('/releases/compare');

        // Verify page title
        await expect(page.locator('h2:has-text("Compare Releases")')).toBeVisible();

        // Verify two ReleaseSelector dropdowns
        const fromSelector = page.locator('select#release-select').first();
        const toSelector = page.locator('select#release-select').nth(1);

        await expect(fromSelector).toBeVisible();
        await expect(toSelector).toBeVisible();

        // Verify Compare button
        const compareButton = page.locator('button:has-text("Compare")');
        await expect(compareButton).toBeVisible();

        // Should be disabled if no releases selected
        await expect(compareButton).toBeDisabled();

        // Select releases (assuming at least 2 exist)
        await fromSelector.selectOption({ index: 1 });
        await toSelector.selectOption({ index: 2 });

        // Compare button should now be enabled
        await expect(compareButton).toBeEnabled();

        // Click Compare
        await compareButton.click();

        // Wait for results
        await page.waitForTimeout(1000);

        // Verify summary stats appear
        await expect(page.locator('text=Added')).toBeVisible();
        await expect(page.locator('text=Deleted')).toBeVisible();
        await expect(page.locator('text=Modified')).toBeVisible();
        await expect(page.locator('text=Unchanged')).toBeVisible();

        // Verify color-coded cards
        await expect(page.locator('.card.bg-success:has-text("Added")')).toBeVisible();
        await expect(page.locator('.card.bg-danger:has-text("Deleted")')).toBeVisible();
        await expect(page.locator('.card.bg-warning:has-text("Modified")')).toBeVisible();
        await expect(page.locator('.card.bg-secondary:has-text("Unchanged")')).toBeVisible();
    });

    test('added requirements shown in green section', async ({ page }) => {
        // Login
        await page.goto('/login');
        await page.fill('input[name="username"]', 'testuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        await page.goto('/releases/compare');

        // Select releases
        const fromSelector = page.locator('select#release-select').first();
        const toSelector = page.locator('select#release-select').nth(1);

        await fromSelector.selectOption({ index: 1 });
        await toSelector.selectOption({ index: 2 });

        // Compare
        await page.locator('button:has-text("Compare")').click();
        await page.waitForTimeout(1000);

        // If there are added requirements, verify they appear in green section
        const addedSection = page.locator('.added-section');
        if (await addedSection.isVisible()) {
            await expect(addedSection.locator('h4.text-success')).toBeVisible();
            await expect(addedSection.locator('.list-group-item-success')).toBeVisible();
        }
    });

    test('deleted requirements shown in red section', async ({ page }) => {
        // Login
        await page.goto('/login');
        await page.fill('input[name="username"]', 'testuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        await page.goto('/releases/compare');

        // Select releases
        const fromSelector = page.locator('select#release-select').first();
        const toSelector = page.locator('select#release-select').nth(1);

        await fromSelector.selectOption({ index: 1 });
        await toSelector.selectOption({ index: 2 });

        // Compare
        await page.locator('button:has-text("Compare")').click();
        await page.waitForTimeout(1000);

        // If there are deleted requirements, verify they appear in red section
        const deletedSection = page.locator('.deleted-section');
        if (await deletedSection.isVisible()) {
            await expect(deletedSection.locator('h4.text-danger')).toBeVisible();
            await expect(deletedSection.locator('.list-group-item-danger')).toBeVisible();
        }
    });

    test('modified requirements show expandable field changes', async ({ page }) => {
        // Login
        await page.goto('/login');
        await page.fill('input[name="username"]', 'testuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        await page.goto('/releases/compare');

        // Select releases
        const fromSelector = page.locator('select#release-select').first();
        const toSelector = page.locator('select#release-select').nth(1);

        await fromSelector.selectOption({ index: 1 });
        await toSelector.selectOption({ index: 2 });

        // Compare
        await page.locator('button:has-text("Compare")').click();
        await page.waitForTimeout(1000);

        // If there are modified requirements, test expand/collapse
        const modifiedSection = page.locator('.modified-section');
        if (await modifiedSection.isVisible()) {
            const firstModifiedItem = modifiedSection.locator('.list-group-item').first();

            // Should show field count badge
            await expect(firstModifiedItem.locator('.badge:has-text("changed")')).toBeVisible();

            // Click to expand
            await firstModifiedItem.click();

            // Field changes table should appear
            await expect(firstModifiedItem.locator('table')).toBeVisible();
            await expect(firstModifiedItem.locator('th:has-text("Field")')).toBeVisible();
            await expect(firstModifiedItem.locator('th:has-text("Old Value")')).toBeVisible();
            await expect(firstModifiedItem.locator('th:has-text("New Value")')).toBeVisible();

            // Old values should be in red/strikethrough
            await expect(firstModifiedItem.locator('td.text-danger del')).toBeVisible();

            // New values should be in green/underline
            await expect(firstModifiedItem.locator('td.text-success ins')).toBeVisible();

            // Click again to collapse
            await firstModifiedItem.click();

            // Table should be hidden
            await expect(firstModifiedItem.locator('table')).not.toBeVisible();
        }
    });

    test('validation prevents comparing same release', async ({ page }) => {
        // Login
        await page.goto('/login');
        await page.fill('input[name="username"]', 'testuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        await page.goto('/releases/compare');

        // Select same release for both
        const fromSelector = page.locator('select#release-select').first();
        const toSelector = page.locator('select#release-select').nth(1);

        await fromSelector.selectOption({ index: 1 });
        await toSelector.selectOption({ index: 1 }); // Same as from

        // Click Compare
        await page.locator('button:has-text("Compare")').click();
        await page.waitForTimeout(500);

        // Should show error message
        await expect(page.locator('.alert-danger, text=different releases, text=same')).toBeVisible();
    });

    test('no changes shows info message', async ({ page }) => {
        // Login
        await page.goto('/login');
        await page.fill('input[name="username"]', 'testuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        await page.goto('/releases/compare');

        // Select releases
        const fromSelector = page.locator('select#release-select').first();
        const toSelector = page.locator('select#release-select').nth(1);

        await fromSelector.selectOption({ index: 1 });
        await toSelector.selectOption({ index: 2 });

        // Compare
        await page.locator('button:has-text("Compare")').click();
        await page.waitForTimeout(1000);

        // If no changes, should show info message
        const addedCount = await page.locator('.card.bg-success h3').textContent();
        const deletedCount = await page.locator('.card.bg-danger h3').textContent();
        const modifiedCount = await page.locator('.card.bg-warning h3').textContent();

        if (addedCount === '0' && deletedCount === '0' && modifiedCount === '0') {
            await expect(page.locator('.alert-info:has-text("No changes found")')).toBeVisible();
        }
    });
});
