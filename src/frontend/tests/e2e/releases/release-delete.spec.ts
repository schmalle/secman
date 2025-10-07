import { test, expect } from '@playwright/test';
import { 
  loginAsAdmin, 
  loginAsReleaseManager, 
  loginAsUser,
  createTestRelease,
  deleteTestRelease,
  cleanupTestReleases
} from '../helpers/releaseHelpers';

/**
 * E2E Tests for User Story 6: Delete Release
 * 
 * Tests RBAC enforcement for release deletion:
 * - ADMIN can delete any release
 * - RELEASE_MANAGER can delete only their own releases
 * - USER cannot delete releases
 * 
 * Tests confirm deletion with modal and error handling
 */

test.describe('Release Delete - RBAC Permissions', () => {
  let adminReleaseId: number;
  let releaseManagerReleaseId: number;
  let otherReleaseManagerReleaseId: number;

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();

    // Create test releases with different creators
    await loginAsAdmin(page);
    const adminRelease = await createTestRelease(page, {
      version: '99.0.0',
      name: 'Admin Test Release for Delete',
      description: 'Created by admin'
    });
    adminReleaseId = adminRelease.id;

    await loginAsReleaseManager(page, 'releasemanager1');
    const rm1Release = await createTestRelease(page, {
      version: '99.1.0',
      name: 'RM1 Test Release for Delete',
      description: 'Created by releasemanager1'
    });
    releaseManagerReleaseId = rm1Release.id;

    await loginAsReleaseManager(page, 'releasemanager2');
    const rm2Release = await createTestRelease(page, {
      version: '99.2.0',
      name: 'RM2 Test Release for Delete',
      description: 'Created by releasemanager2'
    });
    otherReleaseManagerReleaseId = rm2Release.id;

    await context.close();
  });

  test.afterAll(async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    await loginAsAdmin(page);
    
    // Clean up any remaining test releases
    await cleanupTestReleases(page, [
      adminReleaseId,
      releaseManagerReleaseId,
      otherReleaseManagerReleaseId
    ]);
    
    await context.close();
  });

  // T074: ADMIN sees delete button on all releases
  test('T074: ADMIN sees delete button on all releases', async ({ page }) => {
    await loginAsAdmin(page);
    await page.goto('/releases');
    await page.waitForLoadState('networkidle');

    // Check delete buttons are visible for all test releases
    const adminDeleteBtn = page.locator(`[data-testid="delete-release-${adminReleaseId}"]`);
    const rm1DeleteBtn = page.locator(`[data-testid="delete-release-${releaseManagerReleaseId}"]`);
    const rm2DeleteBtn = page.locator(`[data-testid="delete-release-${otherReleaseManagerReleaseId}"]`);

    await expect(adminDeleteBtn).toBeVisible();
    await expect(rm1DeleteBtn).toBeVisible();
    await expect(rm2DeleteBtn).toBeVisible();
  });

  test('T074b: ADMIN sees delete button on release detail page', async ({ page }) => {
    await loginAsAdmin(page);
    await page.goto(`/releases/${releaseManagerReleaseId}`);
    await page.waitForLoadState('networkidle');

    const deleteBtn = page.locator('[data-testid="delete-release-detail"]');
    await expect(deleteBtn).toBeVisible();
  });

  // T075: RELEASE_MANAGER sees delete only on releases they created
  test('T075: RELEASE_MANAGER sees delete button only on own releases', async ({ page }) => {
    await loginAsReleaseManager(page, 'releasemanager1');
    await page.goto('/releases');
    await page.waitForLoadState('networkidle');

    // Should see delete on their own release
    const ownDeleteBtn = page.locator(`[data-testid="delete-release-${releaseManagerReleaseId}"]`);
    await expect(ownDeleteBtn).toBeVisible();

    // Should NOT see delete on admin's release
    const adminDeleteBtn = page.locator(`[data-testid="delete-release-${adminReleaseId}"]`);
    await expect(adminDeleteBtn).not.toBeVisible();

    // Should NOT see delete on other release manager's release
    const otherDeleteBtn = page.locator(`[data-testid="delete-release-${otherReleaseManagerReleaseId}"]`);
    await expect(otherDeleteBtn).not.toBeVisible();
  });

  test('T075b: RELEASE_MANAGER does not see delete on others releases in detail', async ({ page }) => {
    await loginAsReleaseManager(page, 'releasemanager1');
    await page.goto(`/releases/${otherReleaseManagerReleaseId}`);
    await page.waitForLoadState('networkidle');

    const deleteBtn = page.locator('[data-testid="delete-release-detail"]');
    await expect(deleteBtn).not.toBeVisible();
  });

  // T076: Delete confirmation modal appears with warning
  test('T076: Delete confirmation modal appears with warning', async ({ page }) => {
    await loginAsAdmin(page);
    await page.goto('/releases');
    await page.waitForLoadState('networkidle');

    // Click delete button
    const deleteBtn = page.locator(`[data-testid="delete-release-${adminReleaseId}"]`);
    await deleteBtn.click();

    // Modal should appear
    const modal = page.locator('[data-testid="delete-confirm-modal"]');
    await expect(modal).toBeVisible();

    // Check warning message contains version
    const warningText = await modal.locator('.modal-body').textContent();
    expect(warningText).toContain('99.0.0');
    expect(warningText).toContain('This will remove all requirement snapshots');
    expect(warningText).toContain('cannot be undone');

    // Cancel without deleting
    const cancelBtn = modal.locator('[data-testid="cancel-delete"]');
    await cancelBtn.click();
    await expect(modal).not.toBeVisible();
  });

  // T077: Confirm delete removes release from list
  test('T077: Confirm delete removes release from list', async ({ page }) => {
    await loginAsAdmin(page);
    
    // Create a temporary release to delete
    const tempRelease = await createTestRelease(page, {
      version: '99.99.0',
      name: 'Temp Delete Test',
      description: 'Will be deleted'
    });

    await page.goto('/releases');
    await page.waitForLoadState('networkidle');

    // Verify release exists
    const releaseCard = page.locator(`[data-testid="release-card-${tempRelease.id}"]`);
    await expect(releaseCard).toBeVisible();

    // Click delete
    const deleteBtn = page.locator(`[data-testid="delete-release-${tempRelease.id}"]`);
    await deleteBtn.click();

    // Confirm deletion
    const modal = page.locator('[data-testid="delete-confirm-modal"]');
    await expect(modal).toBeVisible();
    
    const confirmBtn = modal.locator('[data-testid="confirm-delete"]');
    await confirmBtn.click();

    // Wait for success notification
    const successToast = page.locator('.toast.bg-success');
    await expect(successToast).toBeVisible();
    await expect(successToast).toContainText('deleted successfully');

    // Verify release no longer in list
    await page.waitForLoadState('networkidle');
    await expect(releaseCard).not.toBeVisible();
  });

  test('T077b: Delete from detail page navigates to list', async ({ page }) => {
    await loginAsAdmin(page);
    
    // Create a temporary release to delete
    const tempRelease = await createTestRelease(page, {
      version: '99.98.0',
      name: 'Temp Detail Delete Test',
      description: 'Will be deleted from detail'
    });

    await page.goto(`/releases/${tempRelease.id}`);
    await page.waitForLoadState('networkidle');

    // Click delete on detail page
    const deleteBtn = page.locator('[data-testid="delete-release-detail"]');
    await deleteBtn.click();

    // Confirm deletion
    const modal = page.locator('[data-testid="delete-confirm-modal"]');
    const confirmBtn = modal.locator('[data-testid="confirm-delete"]');
    await confirmBtn.click();

    // Should navigate back to list
    await page.waitForURL('/releases');
    await expect(page).toHaveURL('/releases');

    // Success notification should appear
    const successToast = page.locator('.toast.bg-success');
    await expect(successToast).toBeVisible();
  });

  // T078: USER does not see delete buttons
  test('T078: USER does not see delete buttons', async ({ page }) => {
    await loginAsUser(page);
    await page.goto('/releases');
    await page.waitForLoadState('networkidle');

    // Should not see any delete buttons
    const deleteButtons = page.locator('[data-testid^="delete-release-"]');
    await expect(deleteButtons).toHaveCount(0);
  });

  test('T078b: USER does not see delete button in detail', async ({ page }) => {
    await loginAsUser(page);
    await page.goto(`/releases/${adminReleaseId}`);
    await page.waitForLoadState('networkidle');

    const deleteBtn = page.locator('[data-testid="delete-release-detail"]');
    await expect(deleteBtn).not.toBeVisible();
  });

  // T079: RELEASE_MANAGER cannot delete others' releases (403 error)
  test('T079: RELEASE_MANAGER cannot delete others releases (403 error)', async ({ page }) => {
    await loginAsReleaseManager(page, 'releasemanager1');

    // Attempt API call directly (simulating manual request)
    const response = await page.request.delete(`/api/releases/${otherReleaseManagerReleaseId}`, {
      headers: {
        'Authorization': `Bearer ${await page.evaluate(() => sessionStorage.getItem('token'))}`
      }
    });

    expect(response.status()).toBe(403);
  });

  // T080: Network error displays error message
  test('T080: Network error displays error message', async ({ page }) => {
    await loginAsAdmin(page);
    await page.goto('/releases');
    await page.waitForLoadState('networkidle');

    // Intercept delete request and fail it
    await page.route(`/api/releases/${adminReleaseId}`, route => {
      route.abort('failed');
    });

    // Click delete
    const deleteBtn = page.locator(`[data-testid="delete-release-${adminReleaseId}"]`);
    await deleteBtn.click();

    // Confirm deletion
    const modal = page.locator('[data-testid="delete-confirm-modal"]');
    const confirmBtn = modal.locator('[data-testid="confirm-delete"]');
    await confirmBtn.click();

    // Error toast should appear
    const errorToast = page.locator('.toast.bg-danger');
    await expect(errorToast).toBeVisible();
    await expect(errorToast).toContainText('Failed to delete release');

    // Modal should close
    await expect(modal).not.toBeVisible();

    // Release should still be in list
    const releaseCard = page.locator(`[data-testid="release-card-${adminReleaseId}"]`);
    await expect(releaseCard).toBeVisible();
  });
});

test.describe('Release Delete - Edge Cases', () => {
  // Additional edge case tests
  test('Delete button is disabled during deletion', async ({ page }) => {
    await loginAsAdmin(page);
    
    const tempRelease = await createTestRelease(page, {
      version: '99.97.0',
      name: 'Loading State Test',
      description: 'For testing loading state'
    });

    await page.goto('/releases');
    await page.waitForLoadState('networkidle');

    // Click delete
    const deleteBtn = page.locator(`[data-testid="delete-release-${tempRelease.id}"]`);
    await deleteBtn.click();

    // Get confirm button
    const modal = page.locator('[data-testid="delete-confirm-modal"]');
    const confirmBtn = modal.locator('[data-testid="confirm-delete"]');

    // Slow down the network to test loading state
    await page.route(`/api/releases/${tempRelease.id}`, async route => {
      await new Promise(resolve => setTimeout(resolve, 1000));
      await route.continue();
    });

    // Click confirm
    await confirmBtn.click();

    // Button should be disabled during request
    await expect(confirmBtn).toBeDisabled();

    // Wait for completion
    await page.waitForLoadState('networkidle');
  });

  test('Cannot delete PUBLISHED release with associated snapshots (if enforced)', async ({ page }) => {
    // This test assumes backend prevents deletion of published releases
    // Skip if this business rule is not enforced
    test.skip(!process.env.ENFORCE_NO_DELETE_PUBLISHED, 'Business rule not enforced');

    await loginAsAdmin(page);
    
    // Create and publish a release
    const tempRelease = await createTestRelease(page, {
      version: '99.96.0',
      name: 'Published Delete Test',
      description: 'Testing published deletion'
    });

    // Publish it (assume publishTestRelease helper exists)
    await page.goto(`/releases/${tempRelease.id}`);
    await page.waitForLoadState('networkidle');
    
    const publishBtn = page.locator('[data-testid="publish-release"]');
    await publishBtn.click();
    await page.waitForLoadState('networkidle');

    // Try to delete
    const deleteBtn = page.locator('[data-testid="delete-release-detail"]');
    
    // Should either not show delete button OR show error on attempt
    if (await deleteBtn.isVisible()) {
      await deleteBtn.click();
      const modal = page.locator('[data-testid="delete-confirm-modal"]');
      const confirmBtn = modal.locator('[data-testid="confirm-delete"]');
      await confirmBtn.click();

      const errorToast = page.locator('.toast.bg-danger');
      await expect(errorToast).toBeVisible();
      await expect(errorToast).toContainText('Cannot delete');
    } else {
      // Delete button not shown for published releases
      await expect(deleteBtn).not.toBeVisible();
    }
  });
});
