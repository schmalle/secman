import { test, expect } from '@playwright/test';
import path from 'path';

/**
 * E2E Tests for User Mapping Upload Feature
 * Feature: 013-user-mapping-upload
 * Reference: specs/013-user-mapping-upload/tasks.md T019-T028
 */

// Test data directory
const TEST_DATA_DIR = path.join(__dirname, '../../testdata/user-mappings');

// Helper function to get test file path
function getTestFile(filename: string): string {
  return path.join(TEST_DATA_DIR, filename);
}

test.describe('User Mapping Upload - Access Control', () => {
  test.beforeEach(async ({ page }) => {
    // Navigate to login page
    await page.goto('/login');
  });

  test('T019: Non-admin user cannot access user mappings page', async ({ page }) => {
    // Login as non-admin user (USER role)
    await page.fill('input[name="username"]', 'testuser');
    await page.fill('input[name="password"]', 'password');
    await page.click('button[type="submit"]');

    // Wait for dashboard
    await page.waitForURL('/');

    // Try to access admin page
    await page.goto('/admin');

    // Should not see User Mappings card
    await expect(page.locator('text=User Mappings')).not.toBeVisible();

    // Try to access user mappings page directly
    await page.goto('/admin/user-mappings');

    // Should be redirected or see access denied
    await expect(page).toHaveURL(/\/(login|admin|403)/);
  });

  test('T019: Admin user can access user mappings page', async ({ page }) => {
    // Login as admin
    await page.fill('input[name="username"]', 'admin');
    await page.fill('input[name="password"]', 'admin');
    await page.click('button[type="submit"]');

    // Wait for dashboard
    await page.waitForURL('/');

    // Navigate to admin page
    await page.goto('/admin');

    // Should see User Mappings card
    await expect(page.locator('text=User Mappings')).toBeVisible();

    // Click on User Mappings card
    await page.click('a[href="/admin/user-mappings"]');

    // Should navigate to user mappings page
    await page.waitForURL('/admin/user-mappings');
    await expect(page.locator('h2:has-text("User Mapping Upload")')).toBeVisible();
  });
});

test.describe('User Mapping Upload - UI Components', () => {
  test.beforeEach(async ({ page }) => {
    // Login as admin
    await page.goto('/login');
    await page.fill('input[name="username"]', 'admin');
    await page.fill('input[name="password"]', 'admin');
    await page.click('button[type="submit"]');
    await page.waitForURL('/');

    // Navigate to user mappings page
    await page.goto('/admin/user-mappings');
    await page.waitForLoadState('networkidle');
  });

  test('T020: Page displays all required UI components', async ({ page }) => {
    // Check page title
    await expect(page.locator('h2:has-text("User Mapping Upload")')).toBeVisible();

    // Check breadcrumb
    await expect(page.locator('nav[aria-label="breadcrumb"]')).toBeVisible();
    await expect(page.locator('.breadcrumb-item:has-text("Admin")')).toBeVisible();

    // Check file requirements card
    await expect(page.locator('.card-header:has-text("File Requirements")')).toBeVisible();
    await expect(page.locator('text=Format: Excel (.xlsx)')).toBeVisible();
    await expect(page.locator('text=Max Size: 10 MB')).toBeVisible();
    await expect(page.locator('text=Email Address')).toBeVisible();
    await expect(page.locator('text=AWS Account ID')).toBeVisible();
    await expect(page.locator('text=Domain')).toBeVisible();

    // Check sample template download link
    await expect(page.locator('a:has-text("Download Sample")')).toBeVisible();

    // Check file input
    await expect(page.locator('input[type="file"]#userMappingFile')).toBeVisible();

    // Check upload button
    const uploadButton = page.locator('button:has-text("Upload")');
    await expect(uploadButton).toBeVisible();
    await expect(uploadButton).toBeDisabled(); // Disabled when no file selected
  });

  test('T020: Sample template download link is functional', async ({ page }) => {
    const downloadPromise = page.waitForEvent('download');
    await page.click('a:has-text("Download Sample")');
    const download = await downloadPromise;

    // Check download filename
    expect(download.suggestedFilename()).toContain('user-mapping-template');
    expect(download.suggestedFilename()).toContain('.xlsx');
  });

  test('T020: File selection enables upload button', async ({ page }) => {
    const uploadButton = page.locator('button:has-text("Upload")');
    const fileInput = page.locator('input[type="file"]#userMappingFile');

    // Initially disabled
    await expect(uploadButton).toBeDisabled();

    // Select a file
    await fileInput.setInputFiles(getTestFile('valid-mappings.xlsx'));

    // Should show selected file info
    await expect(page.locator('text=Selected: valid-mappings.xlsx')).toBeVisible();

    // Button should be enabled
    await expect(uploadButton).toBeEnabled();
  });
});

test.describe('User Mapping Upload - Valid File Upload', () => {
  test.beforeEach(async ({ page }) => {
    // Login as admin and navigate to page
    await page.goto('/login');
    await page.fill('input[name="username"]', 'admin');
    await page.fill('input[name="password"]', 'admin');
    await page.click('button[type="submit"]');
    await page.waitForURL('/');
    await page.goto('/admin/user-mappings');
    await page.waitForLoadState('networkidle');
  });

  test('T021: Upload valid mappings file successfully', async ({ page }) => {
    // Select valid file
    await page.locator('input[type="file"]').setInputFiles(getTestFile('valid-mappings.xlsx'));

    // Click upload
    await page.click('button:has-text("Upload")');

    // Wait for success message
    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('text=Import Complete')).toBeVisible();

    // Check import counts
    await expect(page.locator('text=Imported: 3')).toBeVisible();
    await expect(page.locator('text=Skipped: 0')).toBeVisible();

    // File input should be cleared
    const fileInput = page.locator('input[type="file"]');
    expect(await fileInput.inputValue()).toBe('');
  });

  test('T021: Upload large file (150 rows) successfully', async ({ page }) => {
    // Select large file
    await page.locator('input[type="file"]').setInputFiles(getTestFile('large-file.xlsx'));

    // Click upload
    await page.click('button:has-text("Upload")');

    // Wait for success message (may take longer)
    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 15000 });
    await expect(page.locator('text=Import Complete')).toBeVisible();

    // Check import counts
    await expect(page.locator('text=Imported: 150')).toBeVisible();
    await expect(page.locator('text=Skipped: 0')).toBeVisible();
  });

  test('T021: Upload file with special characters successfully', async ({ page }) => {
    // Select special characters file
    await page.locator('input[type="file"]').setInputFiles(getTestFile('special-characters.xlsx'));

    // Click upload
    await page.click('button:has-text("Upload")');

    // Wait for success message
    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('text=Import Complete')).toBeVisible();

    // Check import counts
    await expect(page.locator('text=Imported: 4')).toBeVisible();
  });
});

test.describe('User Mapping Upload - Invalid File Handling', () => {
  test.beforeEach(async ({ page }) => {
    // Login as admin and navigate to page
    await page.goto('/login');
    await page.fill('input[name="username"]', 'admin');
    await page.fill('input[name="password"]', 'admin');
    await page.click('button[type="submit"]');
    await page.waitForURL('/');
    await page.goto('/admin/user-mappings');
    await page.waitForLoadState('networkidle');
  });

  test('T022: Upload file with invalid emails shows errors', async ({ page }) => {
    // Select invalid emails file
    await page.locator('input[type="file"]').setInputFiles(getTestFile('invalid-emails.xlsx'));

    // Click upload
    await page.click('button:has-text("Upload")');

    // Wait for success message (partial success)
    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 10000 });

    // Should have imported 0 and skipped all
    await expect(page.locator('text=Imported: 0')).toBeVisible();
    await expect(page.locator('text=Skipped: 4')).toBeVisible();

    // Should show error details
    await expect(page.locator('text=Details:')).toBeVisible();
    await expect(page.locator('.alert-success ul.small')).toBeVisible();
  });

  test('T022: Upload file with invalid AWS account IDs shows errors', async ({ page }) => {
    // Select invalid AWS accounts file
    await page.locator('input[type="file"]').setInputFiles(getTestFile('invalid-aws-accounts.xlsx'));

    // Click upload
    await page.click('button:has-text("Upload")');

    // Wait for success message
    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 10000 });

    // Should have skipped all
    await expect(page.locator('text=Imported: 0')).toBeVisible();
    await expect(page.locator('text=Skipped: 5')).toBeVisible();

    // Should show error details
    await expect(page.locator('text=Details:')).toBeVisible();
  });

  test('T022: Upload file with invalid domains shows errors', async ({ page }) => {
    // Select invalid domains file
    await page.locator('input[type="file"]').setInputFiles(getTestFile('invalid-domains.xlsx'));

    // Click upload
    await page.click('button:has-text("Upload")');

    // Wait for success message
    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 10000 });

    // Should have imported 1 (UPPERCASE.COM gets normalized) and skipped 3
    await expect(page.locator('text=Imported: 1')).toBeVisible();
    await expect(page.locator('text=Skipped: 3')).toBeVisible();
  });

  test('T023: Upload file with missing columns shows error', async ({ page }) => {
    // Select missing columns file
    await page.locator('input[type="file"]').setInputFiles(getTestFile('missing-columns.xlsx'));

    // Click upload
    await page.click('button:has-text("Upload")');

    // Wait for error message
    await expect(page.locator('.alert-danger')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('text=Upload Failed')).toBeVisible();
    await expect(page.locator('.alert-danger p')).toContainText('Domain');
  });

  test('T023: Upload empty file shows appropriate message', async ({ page }) => {
    // Select empty file
    await page.locator('input[type="file"]').setInputFiles(getTestFile('empty-file.xlsx'));

    // Click upload
    await page.click('button:has-text("Upload")');

    // Wait for response (success with 0 imported)
    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('text=Imported: 0')).toBeVisible();
    await expect(page.locator('text=Skipped: 0')).toBeVisible();
  });

  test('T024: Upload wrong file format shows error', async ({ page }) => {
    // Select text file
    await page.locator('input[type="file"]').setInputFiles(getTestFile('wrong-format.txt'));

    // Click upload
    await page.click('button:has-text("Upload")');

    // Wait for error message
    await expect(page.locator('.alert-danger')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('text=Upload Failed')).toBeVisible();
  });
});

test.describe('User Mapping Upload - Mixed and Duplicate Data', () => {
  test.beforeEach(async ({ page }) => {
    // Login as admin and navigate to page
    await page.goto('/login');
    await page.fill('input[name="username"]', 'admin');
    await page.fill('input[name="password"]', 'admin');
    await page.click('button[type="submit"]');
    await page.waitForURL('/');
    await page.goto('/admin/user-mappings');
    await page.waitForLoadState('networkidle');
  });

  test('T025: Upload mixed valid/invalid file shows partial success', async ({ page }) => {
    // Select mixed file
    await page.locator('input[type="file"]').setInputFiles(getTestFile('mixed-valid-invalid.xlsx'));

    // Click upload
    await page.click('button:has-text("Upload")');

    // Wait for success message
    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 10000 });

    // Should have imported 3 valid and skipped 2 invalid
    await expect(page.locator('text=Imported: 3')).toBeVisible();
    await expect(page.locator('text=Skipped: 2')).toBeVisible();

    // Should show error details for skipped rows
    await expect(page.locator('text=Details:')).toBeVisible();
  });

  test('T025: Upload file with duplicates shows skipped count', async ({ page }) => {
    // First upload - all should be imported
    await page.locator('input[type="file"]').setInputFiles(getTestFile('duplicates.xlsx'));
    await page.click('button:has-text("Upload")');
    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 10000 });

    // Close alert
    await page.click('.alert-success .btn-close');
    await expect(page.locator('.alert-success')).not.toBeVisible();

    // Second upload - duplicates should be skipped
    await page.locator('input[type="file"]').setInputFiles(getTestFile('duplicates.xlsx'));
    await page.click('button:has-text("Upload")');
    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 10000 });

    // All should be skipped (already exist)
    await expect(page.locator('text=Imported: 0')).toBeVisible();
    await expect(page.locator('text=Skipped: 2')).toBeVisible();
  });

  test('T026: Upload file with empty cells shows errors', async ({ page }) => {
    // Select empty cells file
    await page.locator('input[type="file"]').setInputFiles(getTestFile('empty-cells.xlsx'));

    // Click upload
    await page.click('button:has-text("Upload")');

    // Wait for success message
    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 10000 });

    // Should have imported 1 valid and skipped 4 with missing data
    await expect(page.locator('text=Imported: 1')).toBeVisible();
    await expect(page.locator('text=Skipped: 4')).toBeVisible();
  });
});

test.describe('User Mapping Upload - UI Interactions', () => {
  test.beforeEach(async ({ page }) => {
    // Login as admin and navigate to page
    await page.goto('/login');
    await page.fill('input[name="username"]', 'admin');
    await page.fill('input[name="password"]', 'admin');
    await page.click('button[type="submit"]');
    await page.waitForURL('/');
    await page.goto('/admin/user-mappings');
    await page.waitForLoadState('networkidle');
  });

  test('T027: Upload button shows loading state during upload', async ({ page }) => {
    // Select a file
    await page.locator('input[type="file"]').setInputFiles(getTestFile('valid-mappings.xlsx'));

    // Click upload
    const uploadButton = page.locator('button:has-text("Upload")');
    await uploadButton.click();

    // Should show loading state
    await expect(page.locator('button:has-text("Uploading...")')).toBeVisible();
    await expect(page.locator('.spinner-border')).toBeVisible();

    // Wait for completion
    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 10000 });

    // Loading state should be gone
    await expect(page.locator('button:has-text("Uploading...")')).not.toBeVisible();
    await expect(page.locator('button:has-text("Upload")')).toBeVisible();
  });

  test('T027: Error alert can be dismissed', async ({ page }) => {
    // Select invalid file to trigger error
    await page.locator('input[type="file"]').setInputFiles(getTestFile('missing-columns.xlsx'));
    await page.click('button:has-text("Upload")');

    // Wait for error
    await expect(page.locator('.alert-danger')).toBeVisible({ timeout: 10000 });

    // Dismiss error
    await page.click('.alert-danger .btn-close');

    // Error should be gone
    await expect(page.locator('.alert-danger')).not.toBeVisible();
  });

  test('T027: Success alert can be dismissed', async ({ page }) => {
    // Upload valid file
    await page.locator('input[type="file"]').setInputFiles(getTestFile('valid-mappings.xlsx'));
    await page.click('button:has-text("Upload")');

    // Wait for success
    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 10000 });

    // Dismiss success
    await page.click('.alert-success .btn-close');

    // Success should be gone
    await expect(page.locator('.alert-success')).not.toBeVisible();
  });

  test('T028: Multiple file uploads work correctly', async ({ page }) => {
    // First upload
    await page.locator('input[type="file"]').setInputFiles(getTestFile('valid-mappings.xlsx'));
    await page.click('button:has-text("Upload")');
    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('text=Imported: 3')).toBeVisible();

    // Dismiss alert
    await page.click('.alert-success .btn-close');
    await expect(page.locator('.alert-success')).not.toBeVisible();

    // Second upload (different file)
    await page.locator('input[type="file"]').setInputFiles(getTestFile('special-characters.xlsx'));
    await page.click('button:has-text("Upload")');
    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('text=Imported: 4')).toBeVisible();
  });

  test('T028: Breadcrumb navigation works', async ({ page }) => {
    // Click on Admin breadcrumb
    await page.click('.breadcrumb-item a:has-text("Admin")');

    // Should navigate to admin page
    await page.waitForURL('/admin');
    await expect(page.locator('text=Administration')).toBeVisible();
  });
});
