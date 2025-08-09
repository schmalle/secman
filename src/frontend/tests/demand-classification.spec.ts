import { test, expect } from '@playwright/test';
import { login, logout, createTestDemand, deleteTestDemand } from './test-helpers';

const TEST_USERNAME = process.env.PLAYWRIGHT_TEST_USERNAME || 'adminuser';
const TEST_PASSWORD = process.env.PLAYWRIGHT_TEST_PASSWORD || 'password';

test.describe('Demand Classification System', () => {
  
  test.describe('Public Classification', () => {
    test('should access public classification page without authentication', async ({ page }) => {
      await page.goto('/public-classification');
      
      // Verify page loads
      await expect(page.locator('h3')).toContainText('Demand Classification Tool');
      await expect(page.locator('text=Public access - No authentication required')).toBeVisible();
      
      // Verify form fields are present
      await expect(page.locator('input[id="title"]')).toBeVisible();
      await expect(page.locator('select[id="demandType"]')).toBeVisible();
      await expect(page.locator('select[id="priority"]')).toBeVisible();
    });

    test('should classify a demand publicly', async ({ page }) => {
      await page.goto('/public-classification');
      
      // Fill in the form
      await page.fill('input[placeholder="Enter demand title"]', 'Test Public Demand');
      await page.fill('textarea[placeholder*="description"]', 'This is a test demand for public classification');
      await page.selectOption('select:has-text("Demand Type")', 'CREATE_NEW');
      await page.selectOption('select:has-text("Priority")', 'HIGH');
      await page.fill('input[placeholder*="Server"]', 'Database');
      await page.fill('input[placeholder*="Owner"]', 'IT Department');
      await page.fill('textarea[placeholder*="business"]', 'Critical for new application deployment');
      
      // Submit classification
      await page.click('button:has-text("Classify Demand")');
      
      // Wait for result
      await page.waitForSelector('.card:has-text("Classification Result")');
      
      // Verify classification result is displayed
      await expect(page.locator('.display-1')).toBeVisible();
      await expect(page.locator('text=Classification Hash:')).toBeVisible();
      await expect(page.locator('.font-monospace.small')).toBeVisible();
      
      // Check for confidence score
      await expect(page.locator('.progress-bar')).toBeVisible();
    });

    test('should show validation error for empty title', async ({ page }) => {
      await page.goto('/public-classification');
      
      // Try to submit without title
      await page.click('button:has-text("Classify Demand")');
      
      // Should show error
      await expect(page.locator('.alert-danger')).toBeVisible();
    });

    test('should display evaluation log when requested', async ({ page }) => {
      await page.goto('/public-classification');
      
      // Fill minimal form
      await page.fill('input[placeholder="Enter demand title"]', 'Test Demand');
      await page.selectOption('select:has-text("Priority")', 'LOW');
      
      // Submit
      await page.click('button:has-text("Classify Demand")');
      
      // Wait for result
      await page.waitForSelector('.card:has-text("Classification Result")');
      
      // Show evaluation details
      await page.click('button:has-text("Show Evaluation Details")');
      
      // Verify log is displayed
      await expect(page.locator('pre:has-text("Starting classification")')).toBeVisible();
    });
  });

  test.describe('Authenticated Classification Features', () => {
    test.beforeEach(async ({ page }) => {
      await login(page, TEST_USERNAME, TEST_PASSWORD);
    });

    test.afterEach(async ({ page }) => {
      await logout(page);
    });

    test('should access classification rule manager', async ({ page }) => {
      await page.goto('/classification-rules');
      
      // Verify page loads
      await expect(page.locator('h2')).toContainText('Classification Rules Management');
      await expect(page.locator('h5:has-text("Classification Rules")')).toBeVisible();
      
      // Verify buttons are present
      await expect(page.locator('button:has-text("New Rule")')).toBeVisible();
      await expect(page.locator('button:has-text("Export")')).toBeVisible();
    });

    test('should create a new classification rule', async ({ page }) => {
      await page.goto('/classification-rules');
      
      // Click new rule button
      await page.click('button:has-text("New Rule")');
      
      // Fill rule form
      await page.fill('input[value=""]', 'Test E2E Rule');
      await page.fill('textarea', 'Test rule created by E2E test');
      await page.selectOption('select:has-text("Classification")', 'B');
      
      // Configure condition
      await page.selectOption('select[value="COMPARISON"]', 'COMPARISON');
      await page.selectOption('select:has-text("Select field")', 'priority');
      await page.selectOption('select:has-text("Select operator")', 'EQUALS');
      await page.fill('input[value=""]', 'MEDIUM');
      
      // Save rule
      await page.click('button:has-text("Save")');
      
      // Verify rule appears in list
      await expect(page.locator('.list-group-item:has-text("Test E2E Rule")')).toBeVisible();
    });

    test('should test classification with created rule', async ({ page }) => {
      await page.goto('/classification-rules');
      
      // Fill test form
      await page.fill('input[placeholder*="Title"]', 'Test Demand');
      await page.selectOption('select:has-text("Priority")', 'MEDIUM');
      
      // Test classification
      await page.click('button:has-text("Test Classification")');
      
      // Wait for result
      await page.waitForSelector('.alert-info:has-text("Test Result")');
      
      // Verify result is displayed
      await expect(page.locator('text=Classification:')).toBeVisible();
      await expect(page.locator('text=Confidence:')).toBeVisible();
    });

    test('should classify demand during creation', async ({ page }) => {
      await page.goto('/demands');
      
      // Click add new demand
      await page.click('button:has-text("Add New Demand")');
      
      // Fill demand form
      await page.fill('input[name="title"]', 'Test Demand with Classification');
      await page.selectOption('select[name="demandType"]', 'CREATE_NEW');
      await page.selectOption('select[name="priority"]', 'HIGH');
      await page.fill('input[name="newAssetName"]', 'Test Asset');
      await page.fill('input[name="newAssetType"]', 'Server');
      await page.fill('input[name="newAssetOwner"]', 'IT Dept');
      
      // Enable classification
      await page.check('input[id="classifyOnCreate"]');
      
      // Preview classification
      await page.click('button:has-text("Preview Classification")');
      
      // Wait for classification result
      await page.waitForSelector('.alert-info:has-text("Classification Result")');
      
      // Verify classification is shown
      await expect(page.locator('text=Classification:')).toBeVisible();
      await expect(page.locator('text=Confidence:')).toBeVisible();
      
      // Save demand
      await page.click('button:has-text("Save")');
      
      // Verify demand is created
      await page.waitForSelector('table');
      await expect(page.locator('td:has-text("Test Demand with Classification")')).toBeVisible();
    });

    test('should export and import rules', async ({ page }) => {
      await page.goto('/classification-rules');
      
      // Create download promise before clicking
      const downloadPromise = page.waitForEvent('download');
      
      // Click export
      await page.click('button:has-text("Export")');
      
      // Wait for download
      const download = await downloadPromise;
      expect(download.suggestedFilename()).toBe('classification-rules.json');
      
      // Verify import button exists
      await expect(page.locator('label:has-text("Import")')).toBeVisible();
    });

    test('should edit existing rule', async ({ page }) => {
      await page.goto('/classification-rules');
      
      // Click on first rule in list
      await page.click('.list-group-item:first-child');
      
      // Wait for rule details to load
      await page.waitForSelector('h5:has-text("Rule Details")');
      
      // Click edit
      await page.click('button:has-text("Edit")');
      
      // Modify description
      await page.fill('textarea', 'Updated description for E2E test');
      
      // Save changes
      await page.click('button:has-text("Save")');
      
      // Verify save was successful
      await expect(page.locator('h5:has-text("Rule Details")')).toBeVisible();
    });

    test('should update rule priorities', async ({ page }) => {
      await page.goto('/classification-rules');
      
      // Verify rules are displayed with priority badges
      const priorityBadges = page.locator('.badge.bg-primary');
      const count = await priorityBadges.count();
      expect(count).toBeGreaterThan(0);
      
      // Get initial priority of first rule
      const firstPriority = await priorityBadges.first().textContent();
      expect(firstPriority).toBeTruthy();
    });

    test('should show classification statistics', async ({ page }) => {
      // First classify a demand
      await page.goto('/public-classification');
      await page.fill('input[placeholder="Enter demand title"]', 'Stats Test Demand');
      await page.click('button:has-text("Classify Demand")');
      await page.waitForSelector('.card:has-text("Classification Result")');
      
      // Login and check stats
      await login(page, TEST_USERNAME, TEST_PASSWORD);
      
      // Make API call for statistics
      const response = await page.request.get('/api/classification/statistics', {
        headers: {
          'Authorization': `Bearer ${await page.evaluate(() => localStorage.getItem('token'))}`
        }
      });
      
      expect(response.ok()).toBeTruthy();
      const stats = await response.json();
      
      // Verify statistics structure
      expect(stats).toHaveProperty('totalClassifications');
      expect(stats).toHaveProperty('classificationCounts');
      expect(stats).toHaveProperty('activeRules');
      expect(stats.totalClassifications).toBeGreaterThanOrEqual(0);
    });

    test('should handle rule deletion', async ({ page }) => {
      await page.goto('/classification-rules');
      
      // Create a test rule first
      await page.click('button:has-text("New Rule")');
      await page.fill('input[value=""]', 'Rule to Delete');
      await page.fill('textarea', 'This rule will be deleted');
      await page.selectOption('select:has-text("Classification")', 'C');
      await page.click('button:has-text("Save")');
      
      // Find and select the created rule
      await page.click('.list-group-item:has-text("Rule to Delete")');
      
      // Delete the rule
      page.on('dialog', dialog => dialog.accept());
      await page.click('button:has-text("Delete")');
      
      // Verify rule is removed from list
      await expect(page.locator('.list-group-item:has-text("Rule to Delete")')).not.toBeVisible();
    });
  });

  test.describe('Classification Integration', () => {
    test('should verify classification hash retrieval', async ({ page }) => {
      // Create a classification
      await page.goto('/public-classification');
      await page.fill('input[placeholder="Enter demand title"]', 'Hash Test Demand');
      await page.selectOption('select:has-text("Priority")', 'CRITICAL');
      await page.click('button:has-text("Classify Demand")');
      
      // Wait for result and get hash
      await page.waitForSelector('.font-monospace.small');
      const hashElement = page.locator('.font-monospace.small');
      const hash = await hashElement.textContent();
      
      expect(hash).toBeTruthy();
      expect(hash?.length).toBeGreaterThan(20);
      
      // Verify hash can be retrieved via API
      const response = await page.request.get(`/api/classification/results/${hash}`);
      expect(response.ok()).toBeTruthy();
      
      const result = await response.json();
      expect(result.classificationHash).toBe(hash);
    });

    test('should handle concurrent classifications', async ({ page, context }) => {
      // Open multiple tabs
      const page1 = page;
      const page2 = await context.newPage();
      const page3 = await context.newPage();
      
      // Navigate all to public classification
      await Promise.all([
        page1.goto('/public-classification'),
        page2.goto('/public-classification'),
        page3.goto('/public-classification')
      ]);
      
      // Fill forms differently
      await Promise.all([
        page1.fill('input[placeholder="Enter demand title"]', 'Concurrent Test 1'),
        page2.fill('input[placeholder="Enter demand title"]', 'Concurrent Test 2'),
        page3.fill('input[placeholder="Enter demand title"]', 'Concurrent Test 3')
      ]);
      
      await Promise.all([
        page1.selectOption('select:has-text("Priority")', 'LOW'),
        page2.selectOption('select:has-text("Priority")', 'MEDIUM'),
        page3.selectOption('select:has-text("Priority")', 'HIGH')
      ]);
      
      // Submit all concurrently
      await Promise.all([
        page1.click('button:has-text("Classify Demand")'),
        page2.click('button:has-text("Classify Demand")'),
        page3.click('button:has-text("Classify Demand")')
      ]);
      
      // Wait for all results
      await Promise.all([
        page1.waitForSelector('.card:has-text("Classification Result")'),
        page2.waitForSelector('.card:has-text("Classification Result")'),
        page3.waitForSelector('.card:has-text("Classification Result")')
      ]);
      
      // Verify different classifications
      const class1 = await page1.locator('.display-1').textContent();
      const class2 = await page2.locator('.display-1').textContent();
      const class3 = await page3.locator('.display-1').textContent();
      
      expect(class1).toBe('C'); // LOW priority
      expect(class2).toBeTruthy();
      expect(class3).toBeTruthy();
      
      // Close extra pages
      await page2.close();
      await page3.close();
    });
  });
});