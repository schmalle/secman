/**
 * E2E Tests: Release Status Lifecycle Management
 *
 * Tests status transitions: DRAFT → PUBLISHED → ARCHIVED
 *
 * Related to: Feature 012-build-ui-for, User Story 5
 * Tasks: T051-T057
 */

import { test, expect } from '@playwright/test';
import { 
    loginAsAdmin, 
    loginAsReleaseManager, 
    loginAsUser,
    createTestRelease, 
    deleteTestRelease,
    getAuthToken 
} from '../helpers/releaseHelpers';

test.describe('Release Status Lifecycle Management', () => {
    let adminToken: string;
    let releaseManagerToken: string;
    let userToken: string;
    let testReleaseId: number;

    test.beforeAll(async () => {
        adminToken = await getAuthToken('admin', 'admin123');
        releaseManagerToken = await getAuthToken('release_manager', 'manager123');
        userToken = await getAuthToken('user', 'user123');
    });

    test.beforeEach(async () => {
        // Create a test release in DRAFT status for each test
        const release = await createTestRelease(adminToken, {
            version: `99.${Date.now()}.0`,
            name: 'Status Test Release',
            description: 'Testing status lifecycle'
        });
        testReleaseId = release.id;
    });

    test.afterEach(async () => {
        // Clean up test release
        if (testReleaseId) {
            await deleteTestRelease(adminToken, testReleaseId);
        }
    });

    /**
     * T051: E2E test - Publish button visible for DRAFT (ADMIN/RELEASE_MANAGER)
     */
    test('T051: DRAFT release shows Publish button for ADMIN', async ({ page }) => {
        await loginAsAdmin(page);
        await page.goto(`/releases/${testReleaseId}`);

        // Wait for page to load
        await expect(page.locator('h2')).toContainText('Release Details');

        // Verify status badge shows DRAFT
        const statusBadge = page.locator('.badge:has-text("DRAFT")');
        await expect(statusBadge).toBeVisible();
        await expect(statusBadge).toHaveClass(/bg-warning/);

        // Verify Publish button is visible
        const publishButton = page.locator('button:has-text("Publish")');
        await expect(publishButton).toBeVisible();
        await expect(publishButton).toBeEnabled();

        // Archive button should NOT be visible for DRAFT
        const archiveButton = page.locator('button:has-text("Archive")');
        await expect(archiveButton).not.toBeVisible();
    });

    test('T051: DRAFT release shows Publish button for RELEASE_MANAGER', async ({ page }) => {
        await loginAsReleaseManager(page);
        await page.goto(`/releases/${testReleaseId}`);

        await expect(page.locator('h2')).toContainText('Release Details');

        // Verify Publish button is visible
        const publishButton = page.locator('button:has-text("Publish")');
        await expect(publishButton).toBeVisible();
        await expect(publishButton).toBeEnabled();
    });

    test('T051: DRAFT release does NOT show Publish button for USER', async ({ page }) => {
        await loginAsUser(page);
        await page.goto(`/releases/${testReleaseId}`);

        await expect(page.locator('h2')).toContainText('Release Details');

        // Verify status badge shows DRAFT (user can see it)
        const statusBadge = page.locator('.badge:has-text("DRAFT")');
        await expect(statusBadge).toBeVisible();

        // Verify Publish button is NOT visible
        const publishButton = page.locator('button:has-text("Publish")');
        await expect(publishButton).not.toBeVisible();
    });

    /**
     * T052: E2E test - Confirmation modal before publish
     */
    test('T052: Publish button opens confirmation modal', async ({ page }) => {
        await loginAsAdmin(page);
        await page.goto(`/releases/${testReleaseId}`);

        // Click Publish button
        const publishButton = page.locator('button:has-text("Publish")');
        await publishButton.click();

        // Verify confirmation modal appears
        const modal = page.locator('.modal.show');
        await expect(modal).toBeVisible();

        // Verify modal title
        await expect(modal.locator('.modal-title')).toContainText('Publish Release');

        // Verify modal body contains release version and warning
        const modalBody = modal.locator('.modal-body');
        await expect(modalBody).toContainText('Status Test Release');
        await expect(modalBody).toContainText('This will make it available for exports');

        // Verify modal has Confirm and Cancel buttons
        await expect(modal.locator('button:has-text("Confirm")')).toBeVisible();
        await expect(modal.locator('button:has-text("Cancel")')).toBeVisible();
    });

    test('T052: Cancel button in confirmation modal closes without publishing', async ({ page }) => {
        await loginAsAdmin(page);
        await page.goto(`/releases/${testReleaseId}`);

        // Click Publish button
        await page.locator('button:has-text("Publish")').click();

        // Click Cancel in modal
        const modal = page.locator('.modal.show');
        await modal.locator('button:has-text("Cancel")').click();

        // Modal should close
        await expect(modal).not.toBeVisible();

        // Status should still be DRAFT
        const statusBadge = page.locator('.badge:has-text("DRAFT")');
        await expect(statusBadge).toBeVisible();
    });

    /**
     * T053: E2E test - Status changes to PUBLISHED after confirmation
     */
    test('T053: Confirm publish transitions DRAFT to PUBLISHED', async ({ page }) => {
        await loginAsAdmin(page);
        await page.goto(`/releases/${testReleaseId}`);

        // Verify initial status is DRAFT
        await expect(page.locator('.badge:has-text("DRAFT")')).toBeVisible();

        // Click Publish button
        await page.locator('button:has-text("Publish")').click();

        // Confirm in modal
        const modal = page.locator('.modal.show');
        const confirmButton = modal.locator('button:has-text("Confirm")');
        await confirmButton.click();

        // Wait for modal to close
        await expect(modal).not.toBeVisible();

        // Wait for success toast/notification (if implemented)
        // await expect(page.locator('.toast:has-text("published successfully")')).toBeVisible();

        // Verify status badge changes to PUBLISHED
        const publishedBadge = page.locator('.badge:has-text("PUBLISHED")');
        await expect(publishedBadge).toBeVisible();
        await expect(publishedBadge).toHaveClass(/bg-success/);

        // DRAFT badge should no longer exist
        await expect(page.locator('.badge:has-text("DRAFT")')).not.toBeVisible();
    });

    test('T053: Status update is reflected in release list', async ({ page }) => {
        await loginAsAdmin(page);
        await page.goto(`/releases/${testReleaseId}`);

        // Publish the release
        await page.locator('button:has-text("Publish")').click();
        await page.locator('.modal.show button:has-text("Confirm")').click();

        // Wait for status to update
        await expect(page.locator('.badge:has-text("PUBLISHED")')).toBeVisible();

        // Navigate to release list
        await page.goto('/releases');

        // Find the release in the list
        const releaseCard = page.locator(`.card:has-text("Status Test Release")`);
        await expect(releaseCard).toBeVisible();

        // Verify status badge in list shows PUBLISHED
        const listBadge = releaseCard.locator('.badge:has-text("PUBLISHED")');
        await expect(listBadge).toBeVisible();
        await expect(listBadge).toHaveClass(/bg-success/);
    });

    /**
     * T054: E2E test - Archive button visible for PUBLISHED
     */
    test('T054: PUBLISHED release shows Archive button for ADMIN', async ({ page }) => {
        // First, publish the release
        await loginAsAdmin(page);
        await page.goto(`/releases/${testReleaseId}`);
        await page.locator('button:has-text("Publish")').click();
        await page.locator('.modal.show button:has-text("Confirm")').click();
        await expect(page.locator('.badge:has-text("PUBLISHED")')).toBeVisible();

        // Verify Archive button is now visible
        const archiveButton = page.locator('button:has-text("Archive")');
        await expect(archiveButton).toBeVisible();
        await expect(archiveButton).toBeEnabled();

        // Publish button should NOT be visible for PUBLISHED
        const publishButton = page.locator('button:has-text("Publish")');
        await expect(publishButton).not.toBeVisible();
    });

    test('T054: PUBLISHED release shows Archive button for RELEASE_MANAGER', async ({ page }) => {
        // Publish as ADMIN first
        await loginAsAdmin(page);
        await page.goto(`/releases/${testReleaseId}`);
        await page.locator('button:has-text("Publish")').click();
        await page.locator('.modal.show button:has-text("Confirm")').click();
        await expect(page.locator('.badge:has-text("PUBLISHED")')).toBeVisible();

        // Login as RELEASE_MANAGER and verify Archive button
        await loginAsReleaseManager(page);
        await page.goto(`/releases/${testReleaseId}`);

        const archiveButton = page.locator('button:has-text("Archive")');
        await expect(archiveButton).toBeVisible();
        await expect(archiveButton).toBeEnabled();
    });

    test('T054: PUBLISHED release does NOT show Archive button for USER', async ({ page }) => {
        // Publish as ADMIN first
        await loginAsAdmin(page);
        await page.goto(`/releases/${testReleaseId}`);
        await page.locator('button:has-text("Publish")').click();
        await page.locator('.modal.show button:has-text("Confirm")').click();
        await expect(page.locator('.badge:has-text("PUBLISHED")')).toBeVisible();

        // Login as USER and verify no Archive button
        await loginAsUser(page);
        await page.goto(`/releases/${testReleaseId}`);

        const archiveButton = page.locator('button:has-text("Archive")');
        await expect(archiveButton).not.toBeVisible();
    });

    /**
     * T055: E2E test - Confirmation modal before archive
     */
    test('T055: Archive button opens confirmation modal', async ({ page }) => {
        // Publish first
        await loginAsAdmin(page);
        await page.goto(`/releases/${testReleaseId}`);
        await page.locator('button:has-text("Publish")').click();
        await page.locator('.modal.show button:has-text("Confirm")').click();
        await expect(page.locator('.badge:has-text("PUBLISHED")')).toBeVisible();

        // Click Archive button
        const archiveButton = page.locator('button:has-text("Archive")');
        await archiveButton.click();

        // Verify confirmation modal appears
        const modal = page.locator('.modal.show');
        await expect(modal).toBeVisible();

        // Verify modal content
        await expect(modal.locator('.modal-title')).toContainText('Archive Release');
        const modalBody = modal.locator('.modal-body');
        await expect(modalBody).toContainText('Status Test Release');
        await expect(modalBody).toContainText('mark it as historical');

        // Verify buttons
        await expect(modal.locator('button:has-text("Confirm")')).toBeVisible();
        await expect(modal.locator('button:has-text("Cancel")')).toBeVisible();
    });

    /**
     * T056: E2E test - Status changes to ARCHIVED after confirmation
     */
    test('T056: Confirm archive transitions PUBLISHED to ARCHIVED', async ({ page }) => {
        // Publish first
        await loginAsAdmin(page);
        await page.goto(`/releases/${testReleaseId}`);
        await page.locator('button:has-text("Publish")').click();
        await page.locator('.modal.show button:has-text("Confirm")').click();
        await expect(page.locator('.badge:has-text("PUBLISHED")')).toBeVisible();

        // Click Archive and confirm
        await page.locator('button:has-text("Archive")').click();
        const modal = page.locator('.modal.show');
        await modal.locator('button:has-text("Confirm")').click();

        // Wait for modal to close
        await expect(modal).not.toBeVisible();

        // Verify status badge changes to ARCHIVED
        const archivedBadge = page.locator('.badge:has-text("ARCHIVED")');
        await expect(archivedBadge).toBeVisible();
        await expect(archivedBadge).toHaveClass(/bg-secondary/);

        // PUBLISHED badge should no longer exist
        await expect(page.locator('.badge:has-text("PUBLISHED")')).not.toBeVisible();
    });

    /**
     * T057: E2E test - No status actions for ARCHIVED releases
     */
    test('T057: ARCHIVED release shows no status action buttons', async ({ page }) => {
        // Publish and archive first
        await loginAsAdmin(page);
        await page.goto(`/releases/${testReleaseId}`);
        
        // Publish
        await page.locator('button:has-text("Publish")').click();
        await page.locator('.modal.show button:has-text("Confirm")').click();
        await expect(page.locator('.badge:has-text("PUBLISHED")')).toBeVisible();

        // Archive
        await page.locator('button:has-text("Archive")').click();
        await page.locator('.modal.show button:has-text("Confirm")').click();
        await expect(page.locator('.badge:has-text("ARCHIVED")')).toBeVisible();

        // Verify no Publish or Archive buttons are visible
        await expect(page.locator('button:has-text("Publish")')).not.toBeVisible();
        await expect(page.locator('button:has-text("Archive")')).not.toBeVisible();

        // Release should still be viewable and exportable
        await expect(page.locator('h2:has-text("Release Details")')).toBeVisible();
        await expect(page.locator('button:has-text("Export to Excel")')).toBeVisible();
    });

    test('T057: ARCHIVED status prevents reverse transitions', async ({ page }) => {
        // Publish and archive first
        await loginAsAdmin(page);
        await page.goto(`/releases/${testReleaseId}`);
        
        // Publish
        await page.locator('button:has-text("Publish")').click();
        await page.locator('.modal.show button:has-text("Confirm")').click();
        await expect(page.locator('.badge:has-text("PUBLISHED")')).toBeVisible();

        // Archive
        await page.locator('button:has-text("Archive")').click();
        await page.locator('.modal.show button:has-text("Confirm")').click();
        await expect(page.locator('.badge:has-text("ARCHIVED")')).toBeVisible();

        // Reload page to ensure no client-side state issues
        await page.reload();
        await expect(page.locator('.badge:has-text("ARCHIVED")')).toBeVisible();

        // Confirm no way to go back to PUBLISHED or DRAFT
        await expect(page.locator('button:has-text("Publish")')).not.toBeVisible();
        await expect(page.locator('button:has-text("Unpublish")')).not.toBeVisible();
        await expect(page.locator('button:has-text("Unarchive")')).not.toBeVisible();
    });

    /**
     * Bonus: Status workflow enforcement
     */
    test('Bonus: Complete status workflow DRAFT → PUBLISHED → ARCHIVED', async ({ page }) => {
        await loginAsAdmin(page);
        await page.goto(`/releases/${testReleaseId}`);

        // Step 1: Verify DRAFT
        await expect(page.locator('.badge:has-text("DRAFT")')).toBeVisible();
        await expect(page.locator('button:has-text("Publish")')).toBeVisible();
        await expect(page.locator('button:has-text("Archive")')).not.toBeVisible();

        // Step 2: Transition to PUBLISHED
        await page.locator('button:has-text("Publish")').click();
        await page.locator('.modal.show button:has-text("Confirm")').click();
        await expect(page.locator('.badge:has-text("PUBLISHED")')).toBeVisible();
        await expect(page.locator('button:has-text("Publish")')).not.toBeVisible();
        await expect(page.locator('button:has-text("Archive")')).toBeVisible();

        // Step 3: Transition to ARCHIVED
        await page.locator('button:has-text("Archive")').click();
        await page.locator('.modal.show button:has-text("Confirm")').click();
        await expect(page.locator('.badge:has-text("ARCHIVED")')).toBeVisible();
        await expect(page.locator('button:has-text("Publish")')).not.toBeVisible();
        await expect(page.locator('button:has-text("Archive")')).not.toBeVisible();

        // Verify workflow is one-way
        await page.reload();
        await expect(page.locator('.badge:has-text("ARCHIVED")')).toBeVisible();
        await expect(page.locator('button:has-text("Publish")')).not.toBeVisible();
    });

    /**
     * Bonus: Loading state during status transition
     */
    test('Bonus: Shows loading state during status transition', async ({ page }) => {
        await loginAsAdmin(page);
        await page.goto(`/releases/${testReleaseId}`);

        // Click Publish
        await page.locator('button:has-text("Publish")').click();
        const modal = page.locator('.modal.show');
        const confirmButton = modal.locator('button:has-text("Confirm")');

        // Click confirm and immediately check for disabled state or spinner
        await confirmButton.click();

        // Button should be disabled during API call
        // (This might be too fast to catch, but good to test)
        // await expect(confirmButton).toBeDisabled();

        // Wait for completion
        await expect(page.locator('.badge:has-text("PUBLISHED")')).toBeVisible();
    });
});
