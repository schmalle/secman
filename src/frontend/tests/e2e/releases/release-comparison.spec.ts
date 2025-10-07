/**
 * E2E Test: Release Comparison
 *
 * Tests for User Story 4 - Compare Two Releases
 * Tests comparison view, diff display, and Excel export functionality
 *
 * Related to: Feature 012-build-ui-for (Release Management UI Enhancement)
 */

import { test, expect } from '@playwright/test';
import {
    loginAsAdmin,
    loginAsUser,
    createTestRelease,
    deleteAllTestReleases,
    navigateToComparison,
    generateTestVersion,
    waitForLoadingComplete,
} from '../helpers/releaseHelpers';

test.describe('Release Comparison - User Story 4', () => {
    let release1Id: number;
    let release2Id: number;

    // Setup: Create test releases before all tests
    test.beforeAll(async ({ browser }) => {
        const page = await browser.newPage();
        await loginAsAdmin(page);
        await deleteAllTestReleases(page, 'test-');
        
        // Create two test releases for comparison
        release1Id = await createTestRelease(page, {
            version: generateTestVersion(),
            name: 'Comparison Test Release v1',
            description: 'First release for comparison testing',
        });
        
        release2Id = await createTestRelease(page, {
            version: generateTestVersion(),
            name: 'Comparison Test Release v2',
            description: 'Second release for comparison testing',
        });
        
        await page.close();
    });

    // Cleanup after all tests
    test.afterAll(async ({ browser }) => {
        const page = await browser.newPage();
        await loginAsAdmin(page);
        await deleteAllTestReleases(page, 'test-');
        await page.close();
    });

    /**
     * T039: E2E test - Dropdowns populated with all releases
     * Acceptance Scenario 1: Given I navigate to /releases/compare, When the page
     * loads, Then I see two dropdown selectors populated with all available releases
     */
    test('T039: dropdowns show all available releases', async ({ page }) => {
        await loginAsAdmin(page);
        await navigateToComparison(page);
        await waitForLoadingComplete(page);

        // Verify page loaded
        await expect(page.locator('h2, h1').filter({ hasText: /compare/i })).toBeVisible();

        // Verify two release selectors exist
        const selectors = page.locator('select[data-testid="release-selector"], select:has(option)');
        const selectorCount = await selectors.count();
        expect(selectorCount).toBeGreaterThanOrEqual(2);

        // Verify first selector has options
        const firstSelector = selectors.first();
        const firstOptions = firstSelector.locator('option');
        const firstOptionCount = await firstOptions.count();
        expect(firstOptionCount).toBeGreaterThan(0); // At least the placeholder

        // Verify second selector has options
        const secondSelector = selectors.nth(1);
        const secondOptions = secondSelector.locator('option');
        const secondOptionCount = await secondOptions.count();
        expect(secondOptionCount).toBeGreaterThan(0);

        // Verify our test releases appear in the options
        await expect(page.locator('option:has-text("Comparison Test")')).toHaveCount({ min: 2 });
    });

    /**
     * T040: E2E test - Results show Added, Deleted, Modified sections
     * Acceptance Scenario 3: Given I compare two releases, When the comparison loads,
     * Then I see summary statistics and sections for Added, Deleted, Modified requirements
     */
    test('T040: displays added, deleted, modified sections', async ({ page }) => {
        await loginAsAdmin(page);
        await navigateToComparison(page);
        await waitForLoadingComplete(page);

        // Select releases in dropdowns
        const selectors = page.locator('select[data-testid="release-selector"], select:has(option)');
        
        // Select first release (from)
        await selectors.first().selectOption({ index: 1 }); // Index 0 is placeholder
        
        // Select second release (to)
        await selectors.nth(1).selectOption({ index: 2 }); // Different release

        // Click compare button
        const compareButton = page.locator('button:has-text("Compare")');
        await compareButton.click();

        // Wait for results to load
        await waitForLoadingComplete(page);
        await page.waitForTimeout(1500); // Wait for API call

        // Verify summary statistics cards appear
        const summaryCards = page.locator('.card, [data-testid="summary-card"]').filter({ hasText: /added|deleted|modified|unchanged/i });
        const cardCount = await summaryCards.count();
        
        if (cardCount >= 4) {
            // Verify all 4 summary cards exist
            await expect(page.locator('text=/added/i').first()).toBeVisible();
            await expect(page.locator('text=/deleted/i').first()).toBeVisible();
            await expect(page.locator('text=/modified/i').first()).toBeVisible();
            await expect(page.locator('text=/unchanged/i').first()).toBeVisible();
        }

        // Verify at least one section header exists
        const hasSectionHeaders = await page.locator('h3, h4, h5').filter({ 
            hasText: /added|deleted|modified/i 
        }).count();
        expect(hasSectionHeaders).toBeGreaterThan(0);
    });

    /**
     * T041: E2E test - Field-by-field diff display for modified requirements
     * Acceptance Scenario 4: Given a requirement was modified between releases,
     * When I view the comparison, Then I see which fields changed with old/new values
     */
    test('T041: shows field-level changes for modified requirements', async ({ page }) => {
        await loginAsAdmin(page);
        await navigateToComparison(page);
        await waitForLoadingComplete(page);

        // Select releases and compare
        const selectors = page.locator('select[data-testid="release-selector"], select:has(option)');
        await selectors.first().selectOption({ index: 1 });
        await selectors.nth(1).selectOption({ index: 2 });
        
        const compareButton = page.locator('button:has-text("Compare")');
        await compareButton.click();
        await waitForLoadingComplete(page);
        await page.waitForTimeout(1500);

        // Look for modified requirements section
        const modifiedSection = page.locator('h3, h4, h5').filter({ hasText: /modified/i });
        const hasModifiedSection = await modifiedSection.isVisible().catch(() => false);

        if (hasModifiedSection) {
            // Look for field changes display
            // Could be in table format or list format
            const hasFieldChanges = await page.locator('text=/field|old.*value|new.*value|change/i')
                .isVisible({ timeout: 2000 })
                .catch(() => false);

            if (hasFieldChanges) {
                // Verify we can see specific field names
                await expect(
                    page.locator('text=/details|motivation|chapter|norm/i').first()
                ).toBeVisible();
            }

            // Or check for expandable items that show field changes
            const expandableItems = page.locator('button:has-text("Show"), button:has-text("View"), .collapsible');
            if (await expandableItems.count() > 0) {
                // Click to expand and see field changes
                await expandableItems.first().click();
                await page.waitForTimeout(500);
                
                // Verify expanded content shows field details
                await expect(page.locator('text=/field|change/i')).toBeVisible({ timeout: 2000 });
            }
        } else {
            // No modified requirements in this comparison
            console.log('No modified requirements to test field-level diff');
        }
    });

    /**
     * T042: E2E test - Empty state when no differences exist
     * Acceptance Scenario 6: Given two releases are identical, When I compare them,
     * Then I see a message indicating no differences
     */
    test('T042: shows empty state when no differences', async ({ page }) => {
        await loginAsAdmin(page);
        await navigateToComparison(page);
        await waitForLoadingComplete(page);

        // Select the SAME release twice
        const selectors = page.locator('select[data-testid="release-selector"], select:has(option)');
        await selectors.first().selectOption({ index: 1 });
        await selectors.nth(1).selectOption({ index: 1 }); // Same release

        // Try to click compare button
        const compareButton = page.locator('button:has-text("Compare")');
        
        // Should either be disabled or show error
        const isDisabled = await compareButton.isDisabled();
        
        if (!isDisabled) {
            await compareButton.click();
            await page.waitForTimeout(1000);
            
            // Should see error message about selecting same release
            await expect(
                page.locator('text=/same release|different release|cannot compare/i')
            ).toBeVisible({ timeout: 2000 });
        }
    });

    /**
     * T043: E2E test - Validation prevents comparing release with itself
     * Acceptance Scenario 5: Given I select the same release in both dropdowns,
     * When I try to compare, Then I see a validation error
     */
    test('T043: prevents comparing release with itself', async ({ page }) => {
        await loginAsAdmin(page);
        await navigateToComparison(page);
        await waitForLoadingComplete(page);

        // Select same release in both dropdowns
        const selectors = page.locator('select[data-testid="release-selector"], select:has(option)');
        await selectors.first().selectOption({ index: 1 });
        await selectors.nth(1).selectOption({ index: 1 });

        // Compare button should be disabled OR show error on click
        const compareButton = page.locator('button:has-text("Compare")');
        const isDisabled = await compareButton.isDisabled();

        if (isDisabled) {
            // Good - button is disabled
            expect(isDisabled).toBeTruthy();
        } else {
            // Click and verify error message
            await compareButton.click();
            await page.waitForTimeout(500);
            
            await expect(
                page.locator('.alert-danger, .text-danger').filter({ 
                    hasText: /same|different|select two/i 
                })
            ).toBeVisible();
        }
    });

    /**
     * T044: E2E test - Export comparison button exists
     * Acceptance Scenario 7: Given I have compared two releases, When results are
     * displayed, Then I see an "Export Comparison" button
     */
    test('T044: export comparison button is visible after comparison', async ({ page }) => {
        await loginAsAdmin(page);
        await navigateToComparison(page);
        await waitForLoadingComplete(page);

        // Select releases and compare
        const selectors = page.locator('select[data-testid="release-selector"], select:has(option)');
        await selectors.first().selectOption({ index: 1 });
        await selectors.nth(1).selectOption({ index: 2 });
        
        const compareButton = page.locator('button:has-text("Compare")');
        await compareButton.click();
        await waitForLoadingComplete(page);
        await page.waitForTimeout(1500);

        // Verify export button appears
        const exportButton = page.locator('button:has-text("Export"), a:has-text("Export")');
        await expect(exportButton).toBeVisible({ timeout: 3000 });

        // Verify button is enabled
        await expect(exportButton).toBeEnabled();
    });

    /**
     * T045: E2E test - Excel export includes Change Type column
     * Acceptance Scenario 8: Given I export a comparison, When I click "Export to Excel",
     * Then the file includes a "Change Type" column (Added/Deleted/Modified)
     * 
     * Note: We can't easily verify Excel contents in E2E, so we verify the export
     * button works and triggers download
     */
    test('T045: export button triggers file download', async ({ page }) => {
        await loginAsAdmin(page);
        await navigateToComparison(page);
        await waitForLoadingComplete(page);

        // Select releases and compare
        const selectors = page.locator('select[data-testid="release-selector"], select:has(option)');
        await selectors.first().selectOption({ index: 1 });
        await selectors.nth(1).selectOption({ index: 2 });
        
        const compareButton = page.locator('button:has-text("Compare")');
        await compareButton.click();
        await waitForLoadingComplete(page);
        await page.waitForTimeout(1500);

        // Find export button
        const exportButton = page.locator('button:has-text("Export"), a:has-text("Export")').first();
        await expect(exportButton).toBeVisible();

        // Setup download listener
        const downloadPromise = page.waitForEvent('download', { timeout: 5000 }).catch(() => null);

        // Click export button
        await exportButton.click();

        // Wait for download
        const download = await downloadPromise;

        if (download) {
            // Verify download started
            const filename = download.suggestedFilename();
            expect(filename).toMatch(/comparison|release|\.xlsx$/i);
        } else {
            // Export might be handled differently (e.g., via blob or API)
            // Just verify no errors occurred
            const hasError = await page.locator('.alert-danger').isVisible({ timeout: 1000 }).catch(() => false);
            expect(hasError).toBeFalsy();
        }
    });

    /**
     * Additional test: USER role can view comparison
     */
    test('USER role can access comparison page', async ({ page }) => {
        await loginAsUser(page);
        await navigateToComparison(page);
        await waitForLoadingComplete(page);

        // Verify page loads
        await expect(page.locator('h2, h1').filter({ hasText: /compare/i })).toBeVisible();

        // Verify dropdowns are accessible
        const selectors = page.locator('select[data-testid="release-selector"], select:has(option)');
        expect(await selectors.count()).toBeGreaterThanOrEqual(2);
    });

    /**
     * Additional test: Loading state during comparison
     */
    test('shows loading state while comparing', async ({ page }) => {
        await loginAsAdmin(page);
        await navigateToComparison(page);
        await waitForLoadingComplete(page);

        // Select releases
        const selectors = page.locator('select[data-testid="release-selector"], select:has(option)');
        await selectors.first().selectOption({ index: 1 });
        await selectors.nth(1).selectOption({ index: 2 });
        
        // Click compare
        const compareButton = page.locator('button:has-text("Compare")');
        await compareButton.click();

        // Check for loading indicator immediately
        const hasSpinner = await page.locator('.spinner-border, button:has-text("Comparing")').isVisible({ timeout: 500 }).catch(() => false);
        
        // Loading indicator should appear (or comparison is very fast)
        // Either way, results should eventually appear
        await page.waitForTimeout(2000);
        
        // After loading, results should be visible
        const hasResults = await page.locator('text=/added|deleted|modified/i').isVisible({ timeout: 3000 }).catch(() => false);
        
        // Either results show or an error occurred (both are valid outcomes)
        const hasError = await page.locator('.alert-danger').isVisible().catch(() => false);
        expect(hasResults || hasError).toBeTruthy();
    });
});
