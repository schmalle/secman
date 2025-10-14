import { test, expect } from '@playwright/test';

test.describe('User Mapping Management', () => {
  test.beforeEach(async ({ page }) => {
    // Login as admin
    await page.goto('http://localhost:4321/login');
    await page.fill('input[name="email"]', 'admin@secman.local');
    await page.fill('input[name="password"]', 'admin123');
    await page.click('button[type="submit"]');
    await page.waitForURL('**/dashboard');
    
    // Navigate to user edit
    await page.goto('http://localhost:4321/admin/users/1/edit');
  });
  
  test('displays Access Mappings section in user edit dialog', async ({ page }) => {
    await expect(page.locator('h3:has-text("Access Mappings")')).toBeVisible();
  });
  
  test('shows table with columns AWS Account ID, Domain, Created', async ({ page }) => {
    await expect(page.locator('th:has-text("AWS Account ID")')).toBeVisible();
    await expect(page.locator('th:has-text("Domain")')).toBeVisible();
    await expect(page.locator('th:has-text("Created")')).toBeVisible();
  });
  
  test('displays existing mappings correctly', async ({ page }) => {
    // Wait for mappings to load
    await page.waitForTimeout(1000);
    
    // Check if table is present
    const table = page.locator('table');
    await expect(table).toBeVisible();
  });
  
  test('displays empty state message when user has no mappings', async ({ page }) => {
    // Wait for mappings to load
    await page.waitForTimeout(1000);
    
    // If no mappings, should show empty state
    const emptyState = page.locator('text=/No mappings configured/i');
    const tableRows = page.locator('tbody tr');
    
    // Either empty state visible OR some rows exist
    const hasEmptyState = await emptyState.isVisible().catch(() => false);
    const hasRows = await tableRows.count() > 0;
    
    expect(hasEmptyState || hasRows).toBeTruthy();
  });
  
  test('can add a new AWS account mapping', async ({ page }) => {
    // Fill in AWS Account ID
    await page.fill('input[placeholder="123456789012"]', '123456789012');
    
    // Click add button
    await page.click('button:has-text("Add Mapping")');
    
    // Wait for mapping to appear in table
    await page.waitForTimeout(1000);
    
    // Verify mapping appears
    await expect(page.locator('text=123456789012')).toBeVisible();
  });
  
  test('can add a new domain mapping', async ({ page }) => {
    // Fill in domain
    await page.fill('input[placeholder="example.com"]', 'test.example.com');
    
    // Click add button
    await page.click('button:has-text("Add Mapping")');
    
    // Wait for mapping to appear in table
    await page.waitForTimeout(1000);
    
    // Verify mapping appears
    await expect(page.locator('text=test.example.com')).toBeVisible();
  });
  
  test('shows validation error when both fields are empty', async ({ page }) => {
    // Click add button without filling fields
    await page.click('button:has-text("Add Mapping")');
    
    // Should show error message
    await expect(page.locator('text=/at least one field/i')).toBeVisible();
  });
  
  test('validates AWS Account ID format', async ({ page }) => {
    // Fill in invalid AWS Account ID
    await page.fill('input[placeholder="123456789012"]', 'invalid');
    
    // Click add button
    await page.click('button:has-text("Add Mapping")');
    
    // Should show error message
    await expect(page.locator('text=/12 digits/i')).toBeVisible();
  });
  
  test('can delete a mapping with confirmation', async ({ page }) => {
    // Wait for mappings to load
    await page.waitForTimeout(1000);
    
    // Get initial count
    const initialCount = await page.locator('tbody tr').count();
    
    if (initialCount > 0) {
      // Setup dialog handler before clicking delete
      page.once('dialog', dialog => {
        expect(dialog.message()).toContain('delete');
        dialog.accept();
      });
      
      // Click first delete button
      await page.click('button:has-text("Delete")').first();
      
      // Wait for deletion
      await page.waitForTimeout(1000);
      
      // Verify count decreased
      const newCount = await page.locator('tbody tr').count();
      expect(newCount).toBeLessThan(initialCount);
    }
  });
  
  test('can edit a mapping inline', async ({ page }) => {
    // Wait for mappings to load
    await page.waitForTimeout(1000);
    
    const rowCount = await page.locator('tbody tr').count();
    
    if (rowCount > 0) {
      // Click edit button on first row
      await page.click('tbody tr:first-child button:has-text("Edit")');
      
      // Wait for edit mode
      await page.waitForTimeout(500);
      
      // Should see input fields and Save/Cancel buttons
      await expect(page.locator('tbody tr:first-child input')).toBeVisible();
      await expect(page.locator('tbody tr:first-child button:has-text("Save")')).toBeVisible();
      await expect(page.locator('tbody tr:first-child button:has-text("Cancel")')).toBeVisible();
    }
  });
  
  test('can save edited mapping', async ({ page }) => {
    // Wait for mappings to load
    await page.waitForTimeout(1000);
    
    const rowCount = await page.locator('tbody tr').count();
    
    if (rowCount > 0) {
      // Click edit button on first row
      await page.click('tbody tr:first-child button:has-text("Edit")');
      
      // Wait for edit mode
      await page.waitForTimeout(500);
      
      // Modify AWS Account ID field
      const awsInput = page.locator('tbody tr:first-child input').first();
      await awsInput.fill('999999999999');
      
      // Click save
      await page.click('tbody tr:first-child button:has-text("Save")');
      
      // Wait for save
      await page.waitForTimeout(1000);
      
      // Should exit edit mode and show updated value
      await expect(page.locator('text=999999999999')).toBeVisible();
      await expect(page.locator('tbody tr:first-child button:has-text("Save")')).not.toBeVisible();
    }
  });
  
  test('can cancel editing a mapping', async ({ page }) => {
    // Wait for mappings to load
    await page.waitForTimeout(1000);
    
    const rowCount = await page.locator('tbody tr').count();
    
    if (rowCount > 0) {
      // Get original value
      const originalValue = await page.locator('tbody tr:first-child td').first().textContent();
      
      // Click edit button
      await page.click('tbody tr:first-child button:has-text("Edit")');
      
      // Wait for edit mode
      await page.waitForTimeout(500);
      
      // Modify field
      const input = page.locator('tbody tr:first-child input').first();
      await input.fill('000000000000');
      
      // Click cancel
      await page.click('tbody tr:first-child button:has-text("Cancel")');
      
      // Wait for cancel
      await page.waitForTimeout(500);
      
      // Should exit edit mode and show original value
      await expect(page.locator('tbody tr:first-child button:has-text("Edit")')).toBeVisible();
      const currentValue = await page.locator('tbody tr:first-child td').first().textContent();
      expect(currentValue).toBe(originalValue);
    }
  });
});
