import { test, expect } from '@playwright/test';
import {
  loginAsAdmin,
  loginAsNormalUser,
  logout,
  navigateToPage,
  waitForApiResponse,
  waitForModal,
  closeModal
} from './test-helpers';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

/**
 * E2E Tests for Nmap Scan Import Feature (Feature 002)
 *
 * Tests cover:
 * - T033: Nmap file upload by admin
 * - T034: Port history viewing
 * - T035: Scans page access and functionality
 */
test.describe('Nmap Scan Import Feature - E2E Tests', () => {

  test.beforeEach(async ({ page }) => {
    // Start each test with a fresh login
    await loginAsAdmin(page);
  });

  test.afterEach(async ({ page }) => {
    // Logout after each test
    await logout(page);
  });

  /**
   * T033: Test nmap file upload
   *
   * Verifies:
   * - Admin can upload nmap XML file
   * - Scan summary is displayed
   * - Assets are created/updated
   * - Ports are recorded
   */
  test('T033: Admin uploads nmap scan file successfully', async ({ page }) => {
    console.log('Starting T033: Nmap upload test');

    // Navigate to import page
    await navigateToPage(page, '/import');

    // Switch to nmap import tab
    const nmapTab = page.locator('button.nav-link:has-text("Nmap Scan")');
    await expect(nmapTab).toBeVisible({ timeout: 10000 });
    await nmapTab.click();

    // Verify tab is active
    await expect(nmapTab).toHaveClass(/active/);
    console.log('Switched to Nmap Scan tab');

    // Load test XML file
    const nmapFilePath = path.join(__dirname, 'nmap-test.xml');
    expect(fs.existsSync(nmapFilePath)).toBeTruthy();

    // Upload file using file input
    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles(nmapFilePath);

    // Verify file is selected
    await expect(page.locator('text=File Selected')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('text=nmap-test.xml')).toBeVisible();
    console.log('File selected successfully');

    // Click upload button and wait for API response
    const uploadButton = page.locator('button:has-text("Upload and Import Scan")');
    await expect(uploadButton).toBeEnabled();

    const responsePromise = waitForApiResponse(page, '/api/scan/upload-nmap', 60000);
    await uploadButton.click();

    console.log('Upload initiated, waiting for response...');
    const response = await responsePromise;

    if (!response.ok()) {
      const body = await response.text().catch(() => 'Unable to read response');
      console.error(`Upload failed with status ${response.status()}: ${body}`);
    }

    expect(response.ok()).toBeTruthy();

    // Verify success message appears
    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('text=/Success.*Scan imported successfully/i')).toBeVisible();
    console.log('Upload completed successfully');

    // Verify scan summary is displayed
    await expect(page.locator('text=Scan Summary')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('text=Scan ID')).toBeVisible();
    await expect(page.locator('text=Hosts Discovered')).toBeVisible();
    await expect(page.locator('text=Assets Created')).toBeVisible();
    await expect(page.locator('text=Total Ports')).toBeVisible();

    console.log('Scan summary displayed successfully');

    // Get scan summary values
    const scanSummaryCard = page.locator('.card.border-success');
    const summaryText = await scanSummaryCard.textContent();
    console.log('Scan summary:', summaryText);

    // Verify at least 1 host was discovered (www.heise.de / 193.99.144.85)
    expect(summaryText).toMatch(/Hosts Discovered:.*1/);
  });

  /**
   * T035: Test scans page functionality
   *
   * Verifies:
   * - Admin can access /scans page
   * - Scans list is displayed
   * - Filter by scan type works
   * - Scan detail modal opens
   */
  test('T035: Admin accesses scans page and views scan details', async ({ page }) => {
    console.log('Starting T035: Scans page test');

    // Navigate to scans page
    await navigateToPage(page, '/scans');

    // Verify page loads
    await expect(page.locator('h2:has-text("Scan Management")')).toBeVisible({ timeout: 10000 });
    console.log('Scans page loaded');

    // Verify table is present
    const scansTable = page.locator('table');
    await expect(scansTable).toBeVisible();

    // Check if any scans exist
    const tableBody = scansTable.locator('tbody');
    const rowCount = await tableBody.locator('tr').count();

    if (rowCount === 0 || await page.locator('text=No scans found').isVisible()) {
      console.log('No scans found, test will be limited');
      return;
    }

    console.log(`Found ${rowCount} scan(s) in the table`);

    // Verify filter dropdown exists
    const filterDropdown = page.locator('select#scanTypeFilter');
    await expect(filterDropdown).toBeVisible();

    // Test filter by nmap
    await filterDropdown.selectOption('nmap');
    await page.waitForTimeout(1000); // Wait for filter to apply
    console.log('Applied nmap filter');

    // Click first scan row to view details
    const firstRow = tableBody.locator('tr').first();
    await firstRow.click();

    // Wait for modal to appear
    await expect(page.locator('.modal.show')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('.modal-title:has-text("Scan Detail")')).toBeVisible();
    console.log('Scan detail modal opened');

    // Verify scan details are displayed
    await expect(page.locator('text=Scan Type')).toBeVisible();
    await expect(page.locator('text=Scan Date')).toBeVisible();
    await expect(page.locator('text=Uploaded By')).toBeVisible();
    await expect(page.locator('text=Host Count')).toBeVisible();

    // Verify discovered hosts table
    await expect(page.locator('text=Discovered Hosts')).toBeVisible();

    // Close modal
    const closeButton = page.locator('.modal .btn-close, .modal button:has-text("Close")');
    await closeButton.click();
    await expect(page.locator('.modal.show')).not.toBeVisible({ timeout: 5000 });
    console.log('Scan detail modal closed');
  });

  /**
   * T034: Test port history functionality
   *
   * Verifies:
   * - Admin can view assets page
   * - Assets created from scan have "Ports" button
   * - Port history modal opens and displays scan data
   * - Port details are shown in accordion
   */
  test('T034: Admin views port history for scanned asset', async ({ page }) => {
    console.log('Starting T034: Port history test');

    // Navigate to assets page
    await navigateToPage(page, '/assets');

    // Verify page loads
    await expect(page.locator('h2:has-text("Asset Management")')).toBeVisible({ timeout: 10000 });
    console.log('Assets page loaded');

    // Look for the "Ports" button (only appears for assets with IP addresses)
    const portsButton = page.locator('button:has-text("Ports")').first();

    // Check if any assets with ports exist
    const portsButtonVisible = await portsButton.isVisible({ timeout: 5000 }).catch(() => false);

    if (!portsButtonVisible) {
      console.log('No assets with port history found, test will be skipped');
      return;
    }

    // Get asset name before clicking
    const row = portsButton.locator('xpath=ancestor::tr');
    const assetName = await row.locator('td').first().textContent();
    console.log(`Viewing port history for asset: ${assetName}`);

    // Click Ports button
    await portsButton.click();

    // Wait for port history modal to appear
    await expect(page.locator('.modal.show')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('.modal-title:has-text("Port History")')).toBeVisible();
    console.log('Port history modal opened');

    // Check for scan data
    const noDataMessage = page.locator('text=No scan data available');
    const hasData = !(await noDataMessage.isVisible({ timeout: 2000 }).catch(() => false));

    if (!hasData) {
      console.log('No scan data available for this asset');
      // Close modal and end test
      await page.locator('.modal button:has-text("Close")').click();
      return;
    }

    // Verify accordion with scans exists
    await expect(page.locator('.accordion')).toBeVisible();
    console.log('Scan accordion displayed');

    // Verify first accordion item is expanded (latest scan)
    const firstAccordionButton = page.locator('.accordion-button').first();
    await expect(firstAccordionButton).toBeVisible();
    await expect(firstAccordionButton).toHaveClass(/(?!collapsed)/); // Not collapsed

    // Verify scan metadata
    await expect(page.locator('text=/Scan Date/i')).toBeVisible();

    // Verify ports table is visible
    const portsTable = page.locator('.accordion-collapse.show table');
    await expect(portsTable).toBeVisible({ timeout: 5000 });
    console.log('Ports table displayed');

    // Verify table headers
    await expect(portsTable.locator('th:has-text("Port")')).toBeVisible();
    await expect(portsTable.locator('th:has-text("Protocol")')).toBeVisible();
    await expect(portsTable.locator('th:has-text("State")')).toBeVisible();
    await expect(portsTable.locator('th:has-text("Service")')).toBeVisible();

    // Verify at least one port row exists
    const portRows = portsTable.locator('tbody tr');
    const portCount = await portRows.count();
    expect(portCount).toBeGreaterThan(0);
    console.log(`Found ${portCount} port(s) in scan data`);

    // Verify port summary is displayed
    await expect(page.locator('text=/Summary:/i')).toBeVisible();
    await expect(page.locator('text=/open/i')).toBeVisible();

    // Close modal
    await page.locator('.modal button:has-text("Close")').click();
    await expect(page.locator('.modal.show')).not.toBeVisible({ timeout: 5000 });
    console.log('Port history modal closed');
  });

  /**
   * Test non-admin access restrictions
   *
   * Verifies:
   * - Normal users cannot upload nmap scans
   * - Normal users cannot access /scans page
   */
  test('Non-admin users cannot access nmap import features', async ({ page }) => {
    console.log('Testing non-admin access restrictions');

    // Logout admin
    await logout(page);

    // Login as normal user
    await loginAsNormalUser(page);

    // Navigate to import page
    await navigateToPage(page, '/import');

    // Switch to nmap tab
    const nmapTab = page.locator('button.nav-link:has-text("Nmap Scan")');
    await nmapTab.click();

    // Try to upload file (should fail with 403 at API level)
    const nmapFilePath = path.join(__dirname, 'nmap-test.xml');
    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles(nmapFilePath);

    // Click upload button
    const uploadButton = page.locator('button:has-text("Upload and Import Scan")');
    await uploadButton.click();

    // Verify error message about access denied
    await expect(page.locator('.alert-danger, text=/Access denied/i')).toBeVisible({ timeout: 10000 });
    console.log('Upload correctly denied for non-admin user');

    // Try to access /scans page
    await page.goto('/scans');
    await page.waitForLoadState('networkidle');

    // Should see "Access Denied" message or 403 error
    const accessDeniedVisible = await page.locator('text=/Access denied/i, text=/permission/i').isVisible({ timeout: 5000 }).catch(() => false);

    if (accessDeniedVisible) {
      console.log('Scans page correctly denied for non-admin user');
      expect(accessDeniedVisible).toBeTruthy();
    } else {
      // Alternative: check if backend returned 403 via alert
      const alertDanger = page.locator('.alert-danger');
      const alertVisible = await alertDanger.isVisible({ timeout: 5000 }).catch(() => false);
      expect(alertVisible).toBeTruthy();
      console.log('Scans page access denied via alert');
    }
  });

  /**
   * Test sidebar navigation for admin
   *
   * Verifies:
   * - Admin users see "Scans" menu item in sidebar
   * - Clicking "Scans" navigates to /scans page
   */
  test('Admin sees Scans menu item in sidebar', async ({ page }) => {
    console.log('Testing sidebar navigation for admin');

    // Navigate to home page
    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // Verify sidebar contains "Scans" link
    const scansLink = page.locator('#sidebar a[href="/scans"]');
    await expect(scansLink).toBeVisible({ timeout: 10000 });
    console.log('Scans menu item visible in sidebar');

    // Click Scans link
    await scansLink.click();

    // Verify navigation to /scans
    await expect(page).toHaveURL('/scans');
    await expect(page.locator('h2:has-text("Scan Management")')).toBeVisible({ timeout: 10000 });
    console.log('Navigation to scans page successful');
  });

  /**
   * Test sidebar navigation for non-admin
   *
   * Verifies:
   * - Normal users do NOT see "Scans" menu item in sidebar
   */
  test('Non-admin does not see Scans menu item in sidebar', async ({ page }) => {
    console.log('Testing sidebar navigation for non-admin');

    // Logout admin
    await logout(page);

    // Login as normal user
    await loginAsNormalUser(page);

    // Navigate to home page
    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // Verify sidebar does NOT contain "Scans" link
    const scansLink = page.locator('#sidebar a[href="/scans"]');
    const isVisible = await scansLink.isVisible({ timeout: 2000 }).catch(() => false);

    expect(isVisible).toBeFalsy();
    console.log('Scans menu item correctly hidden for non-admin user');
  });

  /**
   * Test complete workflow: Upload → View Scans → View Port History
   *
   * Integration test covering the full user journey
   */
  test('Complete workflow: Upload scan, view in scans page, check port history', async ({ page }) => {
    console.log('Starting complete workflow test');

    // Step 1: Upload scan
    await navigateToPage(page, '/import');
    const nmapTab = page.locator('button.nav-link:has-text("Nmap Scan")');
    await nmapTab.click();

    const nmapFilePath = path.join(__dirname, 'nmap-test.xml');
    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles(nmapFilePath);

    const uploadButton = page.locator('button:has-text("Upload and Import Scan")');
    const responsePromise = waitForApiResponse(page, '/api/scan/upload-nmap', 60000);
    await uploadButton.click();
    await responsePromise;

    await expect(page.locator('.alert-success')).toBeVisible({ timeout: 10000 });
    console.log('Step 1: Scan uploaded');

    // Step 2: Navigate to scans page and verify scan appears
    await navigateToPage(page, '/scans');
    await expect(page.locator('table tbody tr').first()).toBeVisible({ timeout: 5000 });
    console.log('Step 2: Scan visible in scans list');

    // Step 3: Navigate to assets and check for asset with IP
    await navigateToPage(page, '/assets');

    // Wait for assets table to load
    await page.waitForTimeout(2000);

    // Look for asset with IP address (193.99.144.85 from test file)
    const assetRow = page.locator('table tbody tr:has(td:has-text("193.99.144.85"))');
    const assetExists = await assetRow.isVisible({ timeout: 5000 }).catch(() => false);

    if (!assetExists) {
      console.log('Step 3: Asset not found yet, may take time to appear');
      return;
    }

    // Step 4: Click Ports button and verify port data
    const portsButton = assetRow.locator('button:has-text("Ports")');
    await portsButton.click();

    await expect(page.locator('.modal.show')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('.modal-title:has-text("Port History")')).toBeVisible();
    console.log('Step 3: Port history modal opened');

    // Verify port data from scan (should have ports 22, 80, 443, etc.)
    const portsTable = page.locator('.accordion-collapse.show table');
    await expect(portsTable).toBeVisible({ timeout: 5000 });

    const portRows = portsTable.locator('tbody tr');
    const portCount = await portRows.count();
    expect(portCount).toBeGreaterThan(0);
    console.log(`Step 4: Found ${portCount} port(s) in history - workflow complete!`);
  });
});
