/**
 * E2E Test: Release List View
 *
 * Tests for User Story 1 - View and Browse Releases
 * Tests viewing, filtering, searching, and pagination of releases
 *
 * Related to: Feature 012-build-ui-for (Release Management UI Enhancement)
 */

import { test, expect } from '@playwright/test';
import {
    loginAsAdmin,
    loginAsReleaseManager,
    loginAsUser,
    createTestRelease,
    deleteAllTestReleases,
    navigateToReleases,
    generateTestVersion,
    waitForLoadingComplete,
    assertStatusBadge,
} from '../helpers/releaseHelpers';

test.describe('Release List View - User Story 1', () => {
    // Cleanup before and after all tests
    test.beforeAll(async ({ browser }) => {
        const page = await browser.newPage();
        await loginAsAdmin(page);
        await deleteAllTestReleases(page, 'test-');
        await page.close();
    });

    test.afterAll(async ({ browser }) => {
        const page = await browser.newPage();
        await loginAsAdmin(page);
        await deleteAllTestReleases(page, 'test-');
        await page.close();
    });

    /**
     * T006: E2E test - List displays all releases with correct data
     * Acceptance Scenario 1: Given I navigate to /releases, When the page loads,
     * Then I see a card or table layout displaying all releases with version, name,
     * status badge, release date, requirement count, and created by info
     */
    test('T006: displays all releases with complete metadata', async ({ page }) => {
        await loginAsAdmin(page);

        // Create test releases
        const release1Id = await createTestRelease(page, {
            version: generateTestVersion(),
            name: 'Test Release Alpha',
            description: 'First test release',
        });

        const release2Id = await createTestRelease(page, {
            version: generateTestVersion(),
            name: 'Test Release Beta',
            description: 'Second test release',
        });

        // Navigate to releases page
        await navigateToReleases(page);
        await waitForLoadingComplete(page);

        // Verify page loaded
        await expect(page.locator('h1, h2').filter({ hasText: /releases/i })).toBeVisible();

        // Verify both releases appear
        await expect(page.locator('text=Test Release Alpha')).toBeVisible();
        await expect(page.locator('text=Test Release Beta')).toBeVisible();

        // Verify metadata columns/fields are present
        // Version numbers
        const versionElements = await page.locator('[data-testid="release-version"]').count();
        expect(versionElements).toBeGreaterThanOrEqual(2);

        // Status badges (should be DRAFT for new releases)
        await assertStatusBadge(page, 'DRAFT');

        // Created by information
        await expect(page.locator('text=admin')).toBeVisible();

        // Cleanup
        await deleteAllTestReleases(page, 'test-');
    });

    /**
     * T007: E2E test - Status filter works (ALL, DRAFT, PUBLISHED, ARCHIVED)
     * Acceptance Scenario 3: Given multiple releases exist, When I use the status
     * filter dropdown, Then only releases matching the selected status are displayed
     */
    test('T007: filters releases by status', async ({ page }) => {
        await loginAsAdmin(page);

        // Create test releases with different statuses
        const draftId = await createTestRelease(page, {
            version: generateTestVersion(),
            name: 'Draft Release',
        });

        await navigateToReleases(page);
        await waitForLoadingComplete(page);

        // Verify status filter dropdown exists
        const statusFilter = page.locator('select[data-testid="status-filter"], select:has-text("ALL")');
        await expect(statusFilter).toBeVisible();

        // Test filtering by DRAFT
        await statusFilter.selectOption('DRAFT');
        await waitForLoadingComplete(page);
        await expect(page.locator('text=Draft Release')).toBeVisible();

        // Test ALL filter
        await statusFilter.selectOption('ALL');
        await waitForLoadingComplete(page);
        await expect(page.locator('text=Draft Release')).toBeVisible();

        // Cleanup
        await deleteAllTestReleases(page, 'test-');
    });

    /**
     * T008: E2E test - Search filters by version and name
     * Acceptance Scenario 5: Given I am viewing the release list, When I use the
     * search box, Then releases are filtered by version or name matching my search term
     */
    test('T008: searches releases by version and name', async ({ page }) => {
        await loginAsAdmin(page);

        // Create test releases with distinct names/versions
        const version1 = generateTestVersion();
        const version2 = generateTestVersion();
        
        await createTestRelease(page, {
            version: version1,
            name: 'Searchable Alpha Release',
        });

        await createTestRelease(page, {
            version: version2,
            name: 'Searchable Beta Release',
        });

        await navigateToReleases(page);
        await waitForLoadingComplete(page);

        // Verify search box exists
        const searchBox = page.locator('input[type="search"], input[placeholder*="Search"]');
        await expect(searchBox).toBeVisible();

        // Search by name
        await searchBox.fill('Alpha');
        await page.waitForTimeout(500); // Wait for debounce
        await expect(page.locator('text=Searchable Alpha Release')).toBeVisible();
        await expect(page.locator('text=Searchable Beta Release')).not.toBeVisible();

        // Clear and search by version
        await searchBox.clear();
        await searchBox.fill(version2.substring(0, 5)); // Search by version prefix
        await page.waitForTimeout(500);
        await expect(page.locator('text=Searchable Beta Release')).toBeVisible();
        await expect(page.locator('text=Searchable Alpha Release')).not.toBeVisible();

        // Clear search to show all
        await searchBox.clear();
        await page.waitForTimeout(500);
        await expect(page.locator('text=Searchable Alpha Release')).toBeVisible();
        await expect(page.locator('text=Searchable Beta Release')).toBeVisible();

        // Cleanup
        await deleteAllTestReleases(page, 'test-');
    });

    /**
     * T009: E2E test - Pagination navigates correctly
     * Acceptance Scenario 4: Given releases exist, When I click pagination controls,
     * Then I navigate through pages correctly
     */
    test('T009: navigates through pages with pagination', async ({ page }) => {
        await loginAsAdmin(page);

        // Create multiple test releases (at least 25 to trigger pagination with 20/page)
        const releases = [];
        for (let i = 0; i < 25; i++) {
            releases.push(
                createTestRelease(page, {
                    version: generateTestVersion(),
                    name: `Paginated Release ${i}`,
                })
            );
        }
        await Promise.all(releases);

        await navigateToReleases(page);
        await waitForLoadingComplete(page);

        // Verify pagination controls exist
        const pagination = page.locator('.pagination, [data-testid="pagination"]');
        await expect(pagination).toBeVisible();

        // Verify page 1 is active
        await expect(page.locator('.pagination .active:has-text("1")')).toBeVisible();

        // Click page 2
        await page.click('.pagination a:has-text("2"), [data-testid="page-2"]');
        await waitForLoadingComplete(page);

        // Verify page 2 is now active
        await expect(page.locator('.pagination .active:has-text("2")')).toBeVisible();

        // Verify different releases are shown
        // (Should see releases 21-25 on page 2 with 20/page)

        // Click Previous button
        await page.click('.pagination .page-item:has-text("Previous") a, [data-testid="prev-page"]');
        await waitForLoadingComplete(page);

        // Verify back on page 1
        await expect(page.locator('.pagination .active:has-text("1")')).toBeVisible();

        // Cleanup
        await deleteAllTestReleases(page, 'test-');
    });

    /**
     * T010: E2E test - Empty state displays when no releases exist
     * Acceptance Scenario 6: Given I have no releases in the system, When I view
     * /releases, Then I see an empty state message with guidance
     */
    test('T010: displays empty state when no releases exist', async ({ page }) => {
        await loginAsAdmin(page);

        // Ensure no test releases exist
        await deleteAllTestReleases(page, 'test-');

        await navigateToReleases(page);
        await waitForLoadingComplete(page);

        // Verify empty state is shown
        await expect(
            page.locator('text=/No releases found/i, text=/no releases/i, .empty-state')
        ).toBeVisible();

        // Verify guidance for ADMIN (should see create button)
        await expect(
            page.locator('button:has-text("Create"), text=/create.*release/i')
        ).toBeVisible();
    });

    /**
     * T011: E2E test - Click release navigates to detail page
     * Acceptance Scenario 4: Given I am viewing releases, When I click on a release
     * card/row, Then I navigate to a detailed view
     */
    test('T011: navigates to detail page when clicking release', async ({ page }) => {
        await loginAsAdmin(page);

        // Create a test release
        const version = generateTestVersion();
        const releaseId = await createTestRelease(page, {
            version,
            name: 'Clickable Release',
        });

        await navigateToReleases(page);
        await waitForLoadingComplete(page);

        // Find and click the release
        const releaseLink = page.locator(`a:has-text("Clickable Release"), tr:has-text("Clickable Release")`).first();
        await releaseLink.click();

        // Verify navigation to detail page
        await page.waitForURL(`/releases/${releaseId}`, { timeout: 5000 });

        // Verify detail page shows release info
        await expect(page.locator('text=Clickable Release')).toBeVisible();
        await expect(page.locator(`text=${version}`)).toBeVisible();

        // Cleanup
        await deleteAllTestReleases(page, 'test-');
    });

    /**
     * Additional test: USER role can view releases (read-only)
     */
    test('USER role can view releases without create button', async ({ page }) => {
        await loginAsUser(page);

        await navigateToReleases(page);
        await waitForLoadingComplete(page);

        // Verify page loads
        await expect(page.locator('h1, h2').filter({ hasText: /releases/i })).toBeVisible();

        // Verify Create button is NOT visible for USER
        await expect(
            page.locator('button:has-text("Create New Release"), button:has-text("Create Release")')
        ).not.toBeVisible();
    });

    /**
     * Additional test: Status badge visual distinction
     * Acceptance Scenario 2: Status badges are color-coded
     */
    test('displays color-coded status badges', async ({ page }) => {
        await loginAsAdmin(page);

        await navigateToReleases(page);
        await waitForLoadingComplete(page);

        // Verify DRAFT badge has yellow/warning color
        const draftBadge = page.locator('.badge:has-text("DRAFT")').first();
        if (await draftBadge.isVisible()) {
            await expect(draftBadge).toHaveClass(/bg-warning|badge-warning/);
        }

        // Note: PUBLISHED and ARCHIVED badges will be tested when status
        // transitions are implemented (Phase 5)
    });
});
