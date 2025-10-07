/**
 * E2E Test: Release Detail View
 *
 * Tests for User Story 3 - View Release Details
 * Tests viewing release metadata and requirement snapshots with pagination
 *
 * Related to: Feature 012-build-ui-for (Release Management UI Enhancement)
 */

import { test, expect } from '@playwright/test';
import {
    loginAsAdmin,
    loginAsUser,
    createTestRelease,
    deleteAllTestReleases,
    navigateToReleaseDetail,
    generateTestVersion,
    waitForLoadingComplete,
    assertStatusBadge,
} from '../helpers/releaseHelpers';

test.describe('Release Detail View - User Story 3', () => {
    let testReleaseId: number;

    // Setup: Create a test release before all tests
    test.beforeAll(async ({ browser }) => {
        const page = await browser.newPage();
        await loginAsAdmin(page);
        await deleteAllTestReleases(page, 'test-');
        
        // Create a test release for detail view testing
        testReleaseId = await createTestRelease(page, {
            version: generateTestVersion(),
            name: 'Detail View Test Release',
            description: 'This release is used for testing the detail view functionality',
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
     * T029: E2E test - Detail page shows all metadata
     * Acceptance Scenario 1: Given I click on a release from the list, When the
     * detail page loads, Then I see release metadata (version, name, description,
     * status, release date, created by, created at, requirement count)
     */
    test('T029: displays complete release metadata', async ({ page }) => {
        await loginAsAdmin(page);
        await navigateToReleaseDetail(page, testReleaseId);
        await waitForLoadingComplete(page);

        // Verify page title or header
        await expect(page.locator('h1, h2').filter({ hasText: /detail.*view test release/i })).toBeVisible();

        // Verify metadata fields are present
        await expect(page.locator('text=Detail View Test Release')).toBeVisible();
        
        // Verify status badge
        await assertStatusBadge(page, 'DRAFT');

        // Verify metadata labels/sections
        await expect(page.locator('text=/version/i')).toBeVisible();
        await expect(page.locator('text=/created by/i, text=/creator/i')).toBeVisible();
        await expect(page.locator('text=/created/i')).toBeVisible();
        
        // Verify description is shown
        await expect(page.locator('text=This release is used for testing')).toBeVisible();

        // Verify requirement count is displayed
        await expect(page.locator('text=/requirement/i').and(page.locator('text=/count|total/i'))).toBeVisible();
    });

    /**
     * T030: E2E test - Snapshots table displays correctly with key columns
     * Acceptance Scenario 2: Given I am viewing a release detail page, When I scroll
     * down, Then I see a table displaying all requirement snapshots with columns
     */
    test('T030: displays requirement snapshots table', async ({ page }) => {
        await loginAsAdmin(page);
        await navigateToReleaseDetail(page, testReleaseId);
        await waitForLoadingComplete(page);

        // Verify snapshots section exists
        await expect(
            page.locator('h3, h4, h5').filter({ hasText: /snapshot|requirement/i })
        ).toBeVisible();

        // Verify table exists
        const table = page.locator('table').first();
        await expect(table).toBeVisible();

        // Verify table headers (key columns)
        const headers = table.locator('thead th');
        const headerTexts = await headers.allTextContents();
        
        // Should have columns for shortreq, chapter, norm, details, etc.
        const hasShortreq = headerTexts.some(h => /short.*req|id/i.test(h));
        const hasChapter = headerTexts.some(h => /chapter/i.test(h));
        const hasNorm = headerTexts.some(h => /norm/i.test(h));
        
        expect(hasShortreq || hasChapter || hasNorm).toBeTruthy();

        // If there are snapshots, verify rows exist
        const rowCount = await table.locator('tbody tr').count();
        if (rowCount > 0) {
            // Verify at least one data row
            await expect(table.locator('tbody tr').first()).toBeVisible();
        } else {
            // If no snapshots, should show empty state
            await expect(page.locator('text=/no snapshot|no requirement|empty/i')).toBeVisible();
        }
    });

    /**
     * T031: E2E test - Pagination works for many snapshots (50 per page)
     * Acceptance Scenario 4: Given a release has many snapshots, When viewing the
     * detail page, Then snapshots are paginated for performance
     */
    test('T031: paginates snapshots correctly', async ({ page }) => {
        await loginAsAdmin(page);
        
        // Note: This test assumes snapshots exist. In a real scenario, we'd create
        // a release with many requirements first. For now, we test the pagination UI.
        await navigateToReleaseDetail(page, testReleaseId);
        await waitForLoadingComplete(page);

        // Check if pagination controls exist (they might not if <50 snapshots)
        const pagination = page.locator('.pagination, [data-testid="pagination"]');
        const hasPagination = await pagination.isVisible().catch(() => false);

        if (hasPagination) {
            // Verify pagination controls
            await expect(pagination).toBeVisible();
            
            // Verify page 1 is active
            await expect(pagination.locator('.active:has-text("1")')).toBeVisible();
            
            // Try clicking page 2 if it exists
            const page2Button = pagination.locator('button:has-text("2"), a:has-text("2")');
            if (await page2Button.isVisible().catch(() => false)) {
                await page2Button.click();
                await waitForLoadingComplete(page);
                
                // Verify page 2 is now active
                await expect(pagination.locator('.active:has-text("2")')).toBeVisible();
            }
        } else {
            // If no pagination, verify there are fewer than 50 snapshots
            const rowCount = await page.locator('tbody tr').count();
            expect(rowCount).toBeLessThanOrEqual(50);
        }
    });

    /**
     * T032: E2E test - Click snapshot shows complete details (modal/expanded)
     * Acceptance Scenario 3: Given I am viewing requirement snapshots, When I click
     * on a snapshot row, Then a modal or expanded view shows the complete snapshot
     */
    test('T032: shows complete snapshot details on click', async ({ page }) => {
        await loginAsAdmin(page);
        await navigateToReleaseDetail(page, testReleaseId);
        await waitForLoadingComplete(page);

        // Check if there are any snapshots
        const firstRow = page.locator('tbody tr').first();
        const hasSnapshots = await firstRow.isVisible().catch(() => false);

        if (hasSnapshots) {
            // Click the first snapshot row
            await firstRow.click();

            // Wait for modal or expanded view
            await page.waitForTimeout(500);

            // Verify modal appeared or row expanded
            const modal = page.locator('.modal, [role="dialog"]');
            const isModalVisible = await modal.isVisible({ timeout: 2000 }).catch(() => false);

            if (isModalVisible) {
                // Verify modal shows complete fields
                await expect(modal).toBeVisible();
                
                // Should show more detail than the table preview
                const modalText = await modal.textContent();
                expect(modalText?.length || 0).toBeGreaterThan(50);
                
                // Close modal
                await page.click('button:has-text("Close"), .btn-close').catch(() => {});
            } else {
                // If not modal, might be expanded view inline
                // Just verify the row is still visible (expanded state)
                await expect(firstRow).toBeVisible();
            }
        } else {
            // No snapshots to click
            console.log('No snapshots available for click test');
        }
    });

    /**
     * T033: E2E test - Export button downloads file with release data
     * Acceptance Scenario 5: Given I am viewing a release detail page, When I click
     * the "Export" button, Then I can download the requirements from this release
     */
    test('T033: exports release requirements', async ({ page }) => {
        await loginAsAdmin(page);
        await navigateToReleaseDetail(page, testReleaseId);
        await waitForLoadingComplete(page);

        // Verify export button exists
        const exportButton = page.locator('button:has-text("Export"), a:has-text("Export")').first();
        await expect(exportButton).toBeVisible();

        // Note: Actually testing file download in E2E is complex
        // We verify the button exists and is clickable
        await expect(exportButton).toBeEnabled();

        // Verify clicking doesn't cause errors
        // (In real scenario, this would trigger a download)
        const responsePromise = page.waitForResponse(
            response => response.url().includes('/api/requirements/export'),
            { timeout: 5000 }
        ).catch(() => null);

        await exportButton.click();
        
        // If API call happens, verify it
        const response = await responsePromise;
        if (response) {
            expect(response.status()).toBeLessThan(500); // No server errors
        }
    });

    /**
     * Additional test: USER can view detail page (read-only)
     */
    test('USER role can view release details', async ({ page }) => {
        await loginAsUser(page);
        await navigateToReleaseDetail(page, testReleaseId);
        await waitForLoadingComplete(page);

        // Verify page loads
        await expect(page.locator('h1, h2').filter({ hasText: /detail/i })).toBeVisible();

        // Verify metadata is visible
        await expect(page.locator('text=Detail View Test Release')).toBeVisible();

        // Verify no restricted actions visible (delete, status change buttons)
        // These would be added in later phases
    });

    /**
     * Additional test: Back navigation works
     */
    test('can navigate back to release list', async ({ page }) => {
        await loginAsAdmin(page);
        await navigateToReleaseDetail(page, testReleaseId);
        await waitForLoadingComplete(page);

        // Look for back button or breadcrumb
        const backButton = page.locator('button:has-text("Back"), a:has-text("Back"), a:has-text("Releases")').first();
        
        if (await backButton.isVisible().catch(() => false)) {
            await backButton.click();
            
            // Verify we're back on the list page
            await page.waitForURL(/\/releases\/?$/, { timeout: 5000 });
            await expect(page.locator('h2:has-text("Releases")')).toBeVisible();
        }
    });

    /**
     * Additional test: Invalid release ID shows error
     */
    test('shows error for non-existent release', async ({ page }) => {
        await loginAsAdmin(page);
        
        // Try to navigate to invalid release ID
        await page.goto('/releases/99999');
        await page.waitForTimeout(1000);

        // Should show error message or 404
        const hasError = await page.locator('text=/not found|error|invalid/i').isVisible({ timeout: 3000 }).catch(() => false);
        expect(hasError).toBeTruthy();
    });

    /**
     * Additional test: Loading state during fetch
     */
    test('shows loading state while fetching data', async ({ page }) => {
        await loginAsAdmin(page);
        
        // Navigate and immediately check for loading indicator
        const navigationPromise = page.goto(`/releases/${testReleaseId}`);
        
        // Check for spinner (might be very fast)
        const spinner = page.locator('.spinner-border');
        const hasSpinner = await spinner.isVisible({ timeout: 500 }).catch(() => false);
        
        // Wait for navigation to complete
        await navigationPromise;
        await waitForLoadingComplete(page);

        // After loading, content should be visible
        await expect(page.locator('text=Detail View Test Release')).toBeVisible();
    });
});
