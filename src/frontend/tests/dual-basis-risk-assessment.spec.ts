import { test, expect } from '@playwright/test';

test.describe('Dual-Basis Risk Assessment', () => {
  let page;
  
  test.beforeEach(async ({ browser }) => {
    page = await browser.newPage();
    
    // Login as admin user
    await page.goto('http://localhost:4321/login');
    await page.fill('input[name="username"]', 'adminuser');
    await page.fill('input[name="password"]', 'password');
    await page.click('button[type="submit"]');
    
    // Wait for successful login and redirect
    await page.waitForURL('http://localhost:4321/', { timeout: 10000 });
  });

  test('should display basis type selector in risk assessment form', async () => {
    // Navigate to risk assessments page
    await page.goto('http://localhost:4321/risk-assessments');
    
    // Wait for page to load
    await page.waitForSelector('h2:has-text("Risk Assessment Management")');
    
    // Click Add New Risk Assessment button
    await page.click('button:has-text("Add New Risk Assessment")');
    
    // Check that the basis type selector is present
    await expect(page.locator('select[name="assessmentBasisType"]')).toBeVisible();
    
    // Check the options in the basis type selector
    const basisOptions = page.locator('select[name="assessmentBasisType"] option');
    await expect(basisOptions.nth(0)).toHaveText('Demand (Change Request)');
    await expect(basisOptions.nth(1)).toHaveText('Asset (Direct Assessment)');
  });

  test('should show demand selector when DEMAND basis is selected', async () => {
    await page.goto('http://localhost:4321/risk-assessments');
    await page.waitForSelector('h2:has-text("Risk Assessment Management")');
    await page.click('button:has-text("Add New Risk Assessment")');
    
    // Select DEMAND basis type (should be default)
    await page.selectOption('select[name="assessmentBasisType"]', 'DEMAND');
    
    // Check that demand selector is visible
    await expect(page.locator('label:has-text("Approved Demand")')).toBeVisible();
    await expect(page.locator('select[name="assessmentBasisId"]')).toBeVisible();
    
    // Check that asset selector is not visible
    await expect(page.locator('label:has-text("Asset")')).not.toBeVisible();
  });

  test('should show asset selector when ASSET basis is selected', async () => {
    await page.goto('http://localhost:4321/risk-assessments');
    await page.waitForSelector('h2:has-text("Risk Assessment Management")');
    await page.click('button:has-text("Add New Risk Assessment")');
    
    // Select ASSET basis type
    await page.selectOption('select[name="assessmentBasisType"]', 'ASSET');
    
    // Check that asset selector is visible
    await expect(page.locator('label:has-text("Asset") >> nth=1')).toBeVisible(); // nth=1 to avoid the header label
    
    // Check that demand selector is not visible
    await expect(page.locator('label:has-text("Approved Demand")')).not.toBeVisible();
  });

  test('should display both basis types in the risk assessments table', async () => {
    await page.goto('http://localhost:4321/risk-assessments');
    await page.waitForSelector('h2:has-text("Risk Assessment Management")');
    
    // Check that the basis column exists
    await expect(page.locator('th:has-text("Basis")')).toBeVisible();
    
    // Check for both basis types in the table
    await expect(page.locator('span:has-text("DEMAND")').first()).toBeVisible();
    await expect(page.locator('span:has-text("ASSET")').first()).toBeVisible();
  });

  test('should allow filtering by basis type', async () => {
    await page.goto('http://localhost:4321/risk-assessments');
    await page.waitForSelector('h2:has-text("Risk Assessment Management")');
    
    // Check that the basis type filter exists
    await expect(page.locator('select#basisTypeFilter')).toBeVisible();
    
    // Test filtering by DEMAND
    await page.selectOption('select#basisTypeFilter', 'DEMAND');
    
    // Verify only DEMAND assessments are shown
    const demandRows = page.locator('tbody tr:has(span:has-text("DEMAND"))');
    const assetRows = page.locator('tbody tr:has(span:has-text("ASSET"))');
    
    await expect(demandRows).toHaveCount(await demandRows.count());
    await expect(assetRows).toHaveCount(0);
    
    // Test filtering by ASSET
    await page.selectOption('select#basisTypeFilter', 'ASSET');
    
    // Verify only ASSET assessments are shown
    await expect(demandRows).toHaveCount(0);
    await expect(assetRows.first()).toBeVisible();
    
    // Test showing all
    await page.selectOption('select#basisTypeFilter', 'ALL');
    
    // Verify both types are shown
    await expect(demandRows.first()).toBeVisible();
    await expect(assetRows.first()).toBeVisible();
  });

  test('should display correct information for demand-based assessments', async () => {
    await page.goto('http://localhost:4321/risk-assessments');
    await page.waitForSelector('h2:has-text("Risk Assessment Management")');
    
    // Look for a demand-based assessment
    const demandRow = page.locator('tbody tr:has(span:has-text("DEMAND"))').first();
    await expect(demandRow).toBeVisible();
    
    // Check that it shows demand information in the Target column
    const targetCell = demandRow.locator('td').nth(1); // Target column
    await expect(targetCell).toContainText('Network Infrastructure Upgrade');
  });

  test('should display correct information for asset-based assessments', async () => {
    await page.goto('http://localhost:4321/risk-assessments');
    await page.waitForSelector('h2:has-text("Risk Assessment Management")');
    
    // Look for an asset-based assessment
    const assetRow = page.locator('tbody tr:has(span:has-text("ASSET"))').first();
    await expect(assetRow).toBeVisible();
    
    // Check that it shows asset information in the Target column
    const targetCell = assetRow.locator('td').nth(1); // Target column
    await expect(targetCell).toContainText('Production Database');
  });
});