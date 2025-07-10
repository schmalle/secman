import { test, expect } from '@playwright/test';
import { loginAsAdmin, getTestCredentials, navigateToPage } from './test-helpers';

test.describe('Norm Management - Delete All Functionality', () => {
  
  test.beforeEach(async ({ page }) => {
    // Login using configurable credentials
    await loginAsAdmin(page);
    
    // Navigate to norms page
    try {
      await navigateToPage(page, '/norms');
    } catch (error) {
      console.log(`Navigation to norms failed: ${error}`);
      // Try direct navigation
      await page.goto('/norms');
      await page.waitForLoadState('networkidle', { timeout: 10000 });
    }
  });

  test('should display delete all button when norms exist', async ({ page }) => {
    // First, create some test norms if none exist
    await ensureNormsExist(page);
    
    // Reload the page to see updated norms
    await page.reload();
    await page.waitForLoadState('networkidle');
    
    // Check that Delete All button is visible and enabled
    const deleteAllButton = page.getByRole('button', { name: /delete all norms/i });
    await expect(deleteAllButton).toBeVisible();
    await expect(deleteAllButton).toBeEnabled();
    
    // Check button styling
    await expect(deleteAllButton).toHaveClass(/btn-outline-danger/);
  });

  test('should disable delete all button when no norms exist', async ({ page }) => {
    // First delete all norms if any exist
    await deleteAllNormsIfExist(page);
    
    // Reload the page
    await page.reload();
    await page.waitForLoadState('networkidle');
    
    // Check that Delete All button is disabled
    const deleteAllButton = page.getByRole('button', { name: /delete all norms/i });
    await expect(deleteAllButton).toBeVisible();
    await expect(deleteAllButton).toBeDisabled();
  });

  test('should show confirmation dialog and cancel deletion', async ({ page }) => {
    // Ensure norms exist
    await ensureNormsExist(page);
    await page.reload();
    await page.waitForLoadState('networkidle');
    
    // Set up dialog handler to dismiss the confirmation
    page.on('dialog', async dialog => {
      expect(dialog.type()).toBe('confirm');
      expect(dialog.message()).toContain('Are you sure you want to delete ALL');
      expect(dialog.message()).toContain('norms?');
      expect(dialog.message()).toContain('This action cannot be undone');
      await dialog.dismiss();
    });
    
    // Click Delete All button
    const deleteAllButton = page.getByRole('button', { name: /delete all norms/i });
    await deleteAllButton.click();
    
    // Wait a moment and verify norms still exist
    await page.waitForTimeout(1000);
    
    // Check that norms table still has entries
    const normRows = page.locator('table tbody tr');
    const rowCount = await normRows.count();
    expect(rowCount).toBeGreaterThan(0);
  });

  test('should successfully delete all norms when confirmed', async ({ page }) => {
    // Ensure norms exist
    await ensureNormsExist(page);
    await page.reload();
    await page.waitForLoadState('networkidle');
    
    // Count initial norms
    const initialNormRows = page.locator('table tbody tr');
    const initialCount = await initialNormRows.count();
    expect(initialCount).toBeGreaterThan(0);
    
    // Set up dialog handler to accept the confirmation
    page.on('dialog', async dialog => {
      expect(dialog.type()).toBe('confirm');
      expect(dialog.message()).toContain('Are you sure you want to delete ALL');
      await dialog.accept();
    });
    
    // Set up alert handler for success message
    let alertMessage = '';
    page.on('dialog', async dialog => {
      if (dialog.type() === 'alert') {
        alertMessage = dialog.message();
        await dialog.accept();
      }
    });
    
    // Click Delete All button
    const deleteAllButton = page.getByRole('button', { name: /delete all norms/i });
    await deleteAllButton.click();
    
    // Wait for deletion to complete
    await page.waitForTimeout(2000);
    
    // Verify success message appeared
    expect(alertMessage).toContain('deleted successfully');
    expect(alertMessage).toMatch(/\d+ norms deleted/);
    
    // Check that no norms exist in table
    const noNormsMessage = page.getByText('No norms found. Click "Add Norm" to create one.');
    await expect(noNormsMessage).toBeVisible();
    
    // Verify Delete All button is now disabled
    await expect(deleteAllButton).toBeDisabled();
  });

  test('should handle API errors gracefully', async ({ page }) => {
    // Ensure norms exist
    await ensureNormsExist(page);
    await page.reload();
    await page.waitForLoadState('networkidle');
    
    // Mock API to return error
    await page.route('/api/norms/all', async route => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Database connection failed' })
      });
    });
    
    // Set up dialog handler to accept confirmation
    page.on('dialog', async dialog => {
      if (dialog.type() === 'confirm') {
        await dialog.accept();
      }
    });
    
    // Click Delete All button
    const deleteAllButton = page.getByRole('button', { name: /delete all norms/i });
    await deleteAllButton.click();
    
    // Wait for error to appear
    await page.waitForTimeout(1000);
    
    // Check that error message is displayed
    const errorAlert = page.locator('.alert-danger');
    await expect(errorAlert).toBeVisible();
    await expect(errorAlert).toContainText('Failed to delete all norms');
  });

});

// Helper function to ensure norms exist for testing
async function ensureNormsExist(page: any) {
  // Check if norms already exist
  const existingNorms = page.locator('table tbody tr');
  const count = await existingNorms.count();
  
  if (count === 0 || (count === 1 && await page.getByText('No norms found').isVisible())) {
    // Create test norms
    const testNorms = [
      { name: 'ISO 27001', version: '2013', year: '2013' },
      { name: 'NIST CSF', version: '1.1', year: '2018' },
      { name: 'IEC 62443-3-3', version: '', year: '2013' }
    ];
    
    for (const norm of testNorms) {
      await createNorm(page, norm);
    }
  }
}

// Helper function to create a norm
async function createNorm(page: any, norm: { name: string, version: string, year: string }) {
  // Click Add Norm button
  await page.getByRole('button', { name: /add norm/i }).click();
  
  // Fill in form
  await page.fill('input[name="name"]', norm.name);
  if (norm.version) {
    await page.fill('input[name="version"]', norm.version);
  }
  if (norm.year) {
    await page.fill('input[name="year"]', norm.year);
  }
  
  // Submit form
  await page.getByRole('button', { name: /save/i }).click();
  
  // Wait for creation to complete
  await page.waitForTimeout(1000);
}

// Helper function to delete all norms if they exist
async function deleteAllNormsIfExist(page: any) {
  const deleteAllButton = page.getByRole('button', { name: /delete all norms/i });
  
  // Check if button is enabled (meaning norms exist)
  if (await deleteAllButton.isEnabled()) {
    // Set up dialog handler to accept confirmation
    page.on('dialog', async dialog => {
      if (dialog.type() === 'confirm') {
        await dialog.accept();
      } else if (dialog.type() === 'alert') {
        await dialog.accept();
      }
    });
    
    // Click the button
    await deleteAllButton.click();
    
    // Wait for deletion to complete
    await page.waitForTimeout(2000);
  }
}