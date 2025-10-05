/**
 * E2E Test: Export with Release Selection
 *
 * Tests export functionality with release version selection
 * Scenario from specs/011-i-want-to/quickstart.md: Scenario 3
 *
 * Related to: Feature 011-i-want-to (Release-Based Requirement Version Management)
 */

import { test, expect } from '@playwright/test';

test.describe('Release Export', () => {
    test('export current vs historical release', async ({ page, context }) => {
        // Login as user
        await page.goto('/login');
        await page.fill('input[name="username"]', 'testuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Navigate to Export page
        await page.click('text=Export');
        await page.waitForURL('/export');

        // Verify ReleaseSelector is visible
        await expect(page.locator('select#release-select')).toBeVisible();

        // Verify "Current Version (Live)" is default
        const releaseSelect = page.locator('select#release-select');
        await expect(releaseSelect).toHaveValue('');

        // Setup download listener
        const downloadPromise = page.waitForEvent('download');

        // Export current version to Excel
        await page.click('button:has-text("Export"):has-text("Excel - All")');

        // Wait for download
        const download = await downloadPromise;
        const filename = download.suggestedFilename();

        // Verify filename matches current version pattern
        expect(filename).toMatch(/requirements_export\.xlsx/);

        // Now select a release from dropdown
        await releaseSelect.selectOption({ index: 1 }); // Select first release (not "Current Version")

        // Verify helper text appears
        await expect(page.locator('text=You are viewing a historical snapshot')).toBeVisible();

        // Setup download listener for historical export
        const downloadPromise2 = page.waitForEvent('download');

        // Export from release
        await page.click('button:has-text("Export"):has-text("Excel - All")');

        // Wait for download
        const download2 = await downloadPromise2;
        const filename2 = download2.suggestedFilename();

        // Verify filename includes release version
        expect(filename2).toMatch(/requirements_v\d+\.\d+\.\d+_\d{8}\.xlsx/);

        // Filenames should be different
        expect(filename).not.toBe(filename2);
    });

    test('release selector shows all releases', async ({ page }) => {
        // Login
        await page.goto('/login');
        await page.fill('input[name="username"]', 'testuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Navigate to Export page
        await page.click('text=Export');
        await page.waitForURL('/export');

        // Open release dropdown
        const releaseSelect = page.locator('select#release-select');
        await expect(releaseSelect).toBeVisible();

        // Verify "Current Version" option exists
        await expect(releaseSelect.locator('option:has-text("Current Version")')).toBeVisible();

        // Count options (at least "Current Version" should exist)
        const optionCount = await releaseSelect.locator('option').count();
        expect(optionCount).toBeGreaterThanOrEqual(1);

        // If releases exist, verify format: "version - name (status)"
        if (optionCount > 1) {
            const firstReleaseOption = releaseSelect.locator('option').nth(1);
            const optionText = await firstReleaseOption.textContent();

            // Should match pattern: "1.0.0 - Release Name (DRAFT)"
            expect(optionText).toMatch(/\d+\.\d+\.\d+ - .+ \((DRAFT|PUBLISHED|ARCHIVED)\)/);
        }
    });

    test('export to Word also supports release selection', async ({ page }) => {
        // Login
        await page.goto('/login');
        await page.fill('input[name="username"]', 'testuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Navigate to Export page
        await page.click('text=Export');
        await page.waitForURL('/export');

        // Select a release (if available)
        const releaseSelect = page.locator('select#release-select');
        const optionCount = await releaseSelect.locator('option').count();

        if (optionCount > 1) {
            await releaseSelect.selectOption({ index: 1 });

            // Setup download listener
            const downloadPromise = page.waitForEvent('download');

            // Export to Word
            await page.click('button:has-text("Export"):has-text("Word - All")');

            // Wait for download
            const download = await downloadPromise;
            const filename = download.suggestedFilename();

            // Verify Word filename includes release version
            expect(filename).toMatch(/requirements_v\d+\.\d+\.\d+_\d{8}\.docx/);
        }
    });

    test('switching release updates export context', async ({ page }) => {
        // Login
        await page.goto('/login');
        await page.fill('input[name="username"]', 'testuser');
        await page.fill('input[name="password"]', 'password');
        await page.click('button[type="submit"]');
        await page.waitForURL('/');

        // Navigate to Export page
        await page.click('text=Export');
        await page.waitForURL('/export');

        const releaseSelect = page.locator('select#release-select');
        const optionCount = await releaseSelect.locator('option').count();

        if (optionCount > 2) {
            // Select first release
            await releaseSelect.selectOption({ index: 1 });
            await expect(page.locator('text=You are viewing a historical snapshot')).toBeVisible();

            // Switch to second release
            await releaseSelect.selectOption({ index: 2 });
            await expect(page.locator('text=You are viewing a historical snapshot')).toBeVisible();

            // Switch back to current
            await releaseSelect.selectOption({ value: '' });
            await expect(page.locator('text=You are viewing a historical snapshot')).not.toBeVisible();
        }
    });
});
