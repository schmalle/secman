import { test, expect } from '@playwright/test';
import path from 'path';

/**
 * E2E tests for Masscan XML import functionality
 *
 * Tests:
 * - UI elements are present and functional
 * - File upload works
 * - Import summary is displayed correctly
 * - Error handling for invalid files
 *
 * Prerequisites:
 * - Backend server running on http://localhost:8080
 * - Test user credentials: user/user
 * - Test file: testdata/masscan.xml
 */

test.describe('Masscan Import', () => {
    test.beforeEach(async ({ page }) => {
        // Navigate to login page
        await page.goto('http://localhost:4321/login');

        // Login as test user
        await page.fill('input[name="username"]', 'user');
        await page.fill('input[name="password"]', 'user');
        await page.click('button[type="submit"]');

        // Wait for redirect to dashboard or home
        await page.waitForURL(/.*\/(?:dashboard|home|\/).*/, { timeout: 5000 });

        // Navigate to import page
        await page.goto('http://localhost:4321/import');
        await page.waitForLoadState('networkidle');
    });

    test('should display Masscan tab in import page', async ({ page }) => {
        // Check that Masscan tab is visible
        const masscanTab = page.locator('button:has-text("Masscan")');
        await expect(masscanTab).toBeVisible();

        // Check for the icon
        await expect(page.locator('.bi-hdd-network')).toBeVisible();
    });

    test('should switch to Masscan tab and show upload area', async ({ page }) => {
        // Click on Masscan tab
        await page.click('button:has-text("Masscan")');

        // Verify the tab is active
        const masscanTab = page.locator('button:has-text("Masscan")');
        await expect(masscanTab).toHaveClass(/active/);

        // Verify upload area is shown
        await expect(page.locator('text=Choose masscan XML file or drag & drop')).toBeVisible();

        // Verify file type hint
        await expect(page.locator('text=Supports .xml files up to 10MB')).toBeVisible();
    });

    test('should upload Masscan XML file and show success', async ({ page }) => {
        // Click on Masscan tab
        await page.click('button:has-text("Masscan")');

        // Prepare file path (assuming test runs from project root)
        const testFilePath = path.join(__dirname, '../../../../testdata/masscan.xml');

        // Set the file input
        const fileInput = page.locator('input[type="file"]');
        await fileInput.setInputFiles(testFilePath);

        // Verify file is selected
        await expect(page.locator('text=File Selected')).toBeVisible();
        await expect(page.locator('text=masscan.xml')).toBeVisible();

        // Click upload button
        await page.click('button:has-text("Upload and Import Masscan Results")');

        // Wait for upload to complete (adjust timeout as needed)
        await expect(page.locator('.alert-success'), { timeout: 15000 }).toBeVisible();

        // Verify success message
        await expect(page.locator('text=/Imported.*ports/')).toBeVisible();

        // Verify import summary is displayed
        await expect(page.locator('text=Import Summary')).toBeVisible();
        await expect(page.locator('text=Assets Created')).toBeVisible();
        await expect(page.locator('text=Assets Updated')).toBeVisible();
        await expect(page.locator('text=Ports Imported')).toBeVisible();
    });

    test('should show error for invalid XML file', async ({ page }) => {
        // Click on Masscan tab
        await page.click('button:has-text("Masscan")');

        // Create a temporary invalid XML file
        const invalidXmlContent = 'Not valid XML content';
        const buffer = Buffer.from(invalidXmlContent);

        // Set the invalid file
        const fileInput = page.locator('input[type="file"]');
        await fileInput.setInputFiles({
            name: 'invalid.xml',
            mimeType: 'text/xml',
            buffer: buffer
        });

        // Click upload button
        await page.click('button:has-text("Upload and Import Masscan Results")');

        // Wait for error message
        await expect(page.locator('.alert-danger'), { timeout: 10000 }).toBeVisible();

        // Verify error message is shown
        await expect(page.locator('text=/Error:.*/')).toBeVisible();
    });

    test('should allow file selection via drag-and-drop area click', async ({ page }) => {
        // Click on Masscan tab
        await page.click('button:has-text("Masscan")');

        // Click on the upload area (should trigger file input)
        await page.click('.border-dashed');

        // The file input should be triggered (we can't fully test the OS file dialog,
        // but we can verify the click doesn't cause errors)
        await expect(page.locator('input[type="file"]')).toBeAttached();
    });

    test('should clear selected file when clicking remove button', async ({ page }) => {
        // Click on Masscan tab
        await page.click('button:has-text("Masscan")');

        // Set a file
        const testFilePath = path.join(__dirname, '../../../../testdata/masscan.xml');
        const fileInput = page.locator('input[type="file"]');
        await fileInput.setInputFiles(testFilePath);

        // Verify file is selected
        await expect(page.locator('text=File Selected')).toBeVisible();

        // Click the remove button (X button)
        await page.click('button:has(.bi-x)');

        // Verify file is cleared
        await expect(page.locator('text=File Selected')).not.toBeVisible();
        await expect(page.locator('text=Choose masscan XML file or drag & drop')).toBeVisible();
    });

    test('should disable upload button when no file is selected', async ({ page }) => {
        // Click on Masscan tab
        await page.click('button:has-text("Masscan")');

        // Verify upload button is disabled
        const uploadButton = page.locator('button:has-text("Upload and Import Masscan Results")');
        await expect(uploadButton).toBeDisabled();
    });

    test('should enable upload button when file is selected', async ({ page }) => {
        // Click on Masscan tab
        await page.click('button:has-text("Masscan")');

        // Set a file
        const testFilePath = path.join(__dirname, '../../../../testdata/masscan.xml');
        const fileInput = page.locator('input[type="file"]');
        await fileInput.setInputFiles(testFilePath);

        // Verify upload button is enabled
        const uploadButton = page.locator('button:has-text("Upload and Import Masscan Results")');
        await expect(uploadButton).toBeEnabled();
    });
});
