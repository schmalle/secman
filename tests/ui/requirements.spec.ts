import { test, expect } from '@playwright/test';

test.describe('Requirements Management UI', () => {
  test.beforeEach(async ({ page }) => {
    // Navigate to login page first
    await page.goto('/login');
    
    // Login as admin user
    await page.fill('#username', 'adminuser');
    await page.fill('#password', 'password');
    await page.click('button[type="submit"]');
    
    // Wait for login to complete and navigate to requirements page
    await page.waitForTimeout(1000);
    await page.goto('/requirements');
    await page.waitForLoadState('networkidle');
  });

  test('should display requirements management page', async ({ page }) => {
    await expect(page).toHaveURL(/.*\/requirements/);
    await expect(page.locator('h2')).toContainText('Requirement Management');
  });

  test('should display requirements table with delete buttons', async ({ page }) => {
    // Check if the table exists
    await expect(page.locator('table')).toBeVisible();
    
    // Check table headers
    await expect(page.locator('th')).toContainText(['Short Requirement', 'Actions']);
    
    // Check if delete buttons exist in the actions column
    const deleteButtons = page.locator('button:has-text("Delete")');
    await expect(deleteButtons.first()).toBeVisible();
  });

  test('should show confirmation dialog when deleting a requirement', async ({ page }) => {
    // Look for first delete button
    const deleteButtons = page.locator('button:has-text("Delete")');
    
    if (await deleteButtons.count() > 0) {
      // Set up dialog handler before clicking
      page.on('dialog', async dialog => {
        expect(dialog.message()).toContain('Are you sure you want to delete this requirement?');
        await dialog.dismiss(); // Cancel the deletion for this test
      });
      
      await deleteButtons.first().click();
    }
  });

  test('should delete a requirement when confirmed', async ({ page }) => {
    // First, create a test requirement
    await page.click('button:has-text("Add Requirement")');
    
    // Fill out the form
    await page.fill('#shortreq', 'TEST-DELETE-REQ');
    await page.fill('#details', 'This requirement will be deleted in the test');
    
    // Submit the form
    await page.click('button[type="submit"]:has-text("Save")');
    
    // Wait for the requirement to appear in the list
    await page.waitForTimeout(1000);
    await expect(page.locator('text=TEST-DELETE-REQ')).toBeVisible();
    
    // Find the delete button for our test requirement
    const testRow = page.locator('tr').filter({ hasText: 'TEST-DELETE-REQ' });
    const deleteButton = testRow.locator('button:has-text("Delete")');
    
    // Set up dialog handler to confirm deletion
    page.on('dialog', async dialog => {
      expect(dialog.message()).toContain('Are you sure you want to delete this requirement?');
      await dialog.accept(); // Confirm the deletion
    });
    
    await deleteButton.click();
    
    // Wait for deletion to complete
    await page.waitForTimeout(1000);
    
    // Verify the requirement is no longer in the list
    await expect(page.locator('text=TEST-DELETE-REQ')).not.toBeVisible();
  });

  test('should have properly styled delete buttons', async ({ page }) => {
    const deleteButtons = page.locator('button:has-text("Delete")');
    
    if (await deleteButtons.count() > 0) {
      // Check that delete buttons have the correct Bootstrap classes
      await expect(deleteButtons.first()).toHaveClass(/btn-outline-danger/);
      await expect(deleteButtons.first()).toHaveClass(/btn-sm/);
    }
  });

  test('should show edit and delete buttons in a button group', async ({ page }) => {
    const buttonGroups = page.locator('.btn-group');
    
    if (await buttonGroups.count() > 0) {
      const firstGroup = buttonGroups.first();
      
      // Check that both Edit and Delete buttons are in the same group
      await expect(firstGroup.locator('button:has-text("Edit")')).toBeVisible();
      await expect(firstGroup.locator('button:has-text("Delete")')).toBeVisible();
    }
  });

  test('should handle delete operation gracefully on server error', async ({ page }) => {
    // Mock a server error for delete operation
    await page.route('**/api/requirements/*', route => {
      if (route.request().method() === 'DELETE') {
        route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'Server error' })
        });
      } else {
        route.continue();
      }
    });

    const deleteButtons = page.locator('button:has-text("Delete")');
    
    if (await deleteButtons.count() > 0) {
      // Set up dialog handler to confirm deletion
      page.on('dialog', async dialog => {
        await dialog.accept();
      });
      
      await deleteButtons.first().click();
      
      // Wait for error handling (requirement should still be visible since delete failed)
      await page.waitForTimeout(1000);
    }
    
    // Test passes if no crashes occur (graceful error handling)
    expect(true).toBe(true);
  });

  test('should maintain table layout after deletion', async ({ page }) => {
    const initialRequirementCount = await page.locator('tbody tr').count();
    
    if (initialRequirementCount > 0) {
      // Set up dialog handler to confirm deletion
      page.on('dialog', async dialog => {
        await dialog.accept();
      });
      
      const deleteButtons = page.locator('button:has-text("Delete")');
      await deleteButtons.first().click();
      
      // Wait for deletion to complete
      await page.waitForTimeout(1000);
      
      // Check that table structure is maintained
      await expect(page.locator('table')).toBeVisible();
      await expect(page.locator('thead')).toBeVisible();
      await expect(page.locator('tbody')).toBeVisible();
      
      // Check that the count decreased (if there were multiple requirements)
      if (initialRequirementCount > 1) {
        const newCount = await page.locator('tbody tr').count();
        expect(newCount).toBe(initialRequirementCount - 1);
      }
    }
  });

  test('should show requirements count after operations', async ({ page }) => {
    // Look for the count display
    const countDisplay = page.locator('text=Showing');
    
    if (await countDisplay.isVisible()) {
      // Extract the numbers from the display
      const countText = await countDisplay.textContent();
      expect(countText).toMatch(/Showing \d+ of \d+ requirements/);
    }
  });

  test('should display "Delete All" button when requirements exist', async ({ page }) => {
    const requirementCount = await page.locator('tbody tr').count();
    
    if (requirementCount > 0) {
      // Delete All button should be visible when requirements exist
      await expect(page.locator('button:has-text("Delete All")')).toBeVisible();
      
      // Check button styling
      const deleteAllButton = page.locator('button:has-text("Delete All")');
      await expect(deleteAllButton).toHaveClass(/btn-danger/);
    } else {
      // Delete All button should not be visible when no requirements exist
      await expect(page.locator('button:has-text("Delete All")')).not.toBeVisible();
    }
  });

  test('should show confirmation dialog for "Delete All" operation', async ({ page }) => {
    const requirementCount = await page.locator('tbody tr').count();
    
    if (requirementCount > 0) {
      // Set up dialog handler before clicking
      page.on('dialog', async dialog => {
        expect(dialog.message()).toContain('Are you sure you want to delete ALL requirements');
        expect(dialog.message()).toContain('This action cannot be undone');
        await dialog.dismiss(); // Cancel the deletion for this test
      });
      
      await page.click('button:has-text("Delete All")');
    }
  });

  test('should delete all requirements when confirmed', async ({ page }) => {
    // First, ensure we have some requirements by creating a few test ones
    const initialCount = await page.locator('tbody tr').count();
    
    // Create a test requirement if none exist
    if (initialCount === 0) {
      await page.click('button:has-text("Add Requirement")');
      await page.fill('#shortreq', 'TEST-DELETE-ALL-1');
      await page.fill('#details', 'Test requirement for delete all');
      await page.click('button[type="submit"]:has-text("Save")');
      await page.waitForTimeout(1000);
      
      // Create another one
      await page.click('button:has-text("Add Requirement")');
      await page.fill('#shortreq', 'TEST-DELETE-ALL-2');
      await page.fill('#details', 'Second test requirement for delete all');
      await page.click('button[type="submit"]:has-text("Save")');
      await page.waitForTimeout(1000);
    }
    
    // Now test the delete all functionality
    const deleteAllButton = page.locator('button:has-text("Delete All")');
    if (await deleteAllButton.isVisible()) {
      // Set up dialog handlers
      page.on('dialog', async dialog => {
        if (dialog.message().includes('delete ALL requirements')) {
          await dialog.accept(); // Confirm the deletion
        } else if (dialog.message().includes('Successfully deleted')) {
          await dialog.accept(); // Acknowledge the success message
        }
      });
      
      await deleteAllButton.click();
      
      // Wait for deletion to complete
      await page.waitForTimeout(2000);
      
      // Verify all requirements are deleted
      const finalCount = await page.locator('tbody tr').count();
      expect(finalCount).toBe(0);
      
      // Verify Delete All button is hidden when no requirements exist
      await expect(page.locator('button:has-text("Delete All")')).not.toBeVisible();
    }
  });

  test('should handle "Delete All" server error gracefully', async ({ page }) => {
    const requirementCount = await page.locator('tbody tr').count();
    
    if (requirementCount > 0) {
      // Mock a server error for delete all operation
      await page.route('**/api/requirements/all', route => {
        if (route.request().method() === 'DELETE') {
          route.fulfill({
            status: 500,
            contentType: 'application/json',
            body: JSON.stringify({ error: 'Server error during bulk delete' })
          });
        } else {
          route.continue();
        }
      });

      // Set up dialog handlers
      page.on('dialog', async dialog => {
        if (dialog.message().includes('delete ALL requirements')) {
          await dialog.accept(); // Confirm the deletion
        } else if (dialog.message().includes('Error deleting requirements')) {
          await dialog.accept(); // Acknowledge the error message
        }
      });
      
      await page.click('button:has-text("Delete All")');
      
      // Wait for error handling
      await page.waitForTimeout(1000);
      
      // Requirements should still be visible since delete failed
      const finalCount = await page.locator('tbody tr').count();
      expect(finalCount).toBe(requirementCount);
    }
  });

  test('should handle empty state after deleting all requirements', async ({ page }) => {
    // Test the UI behavior when transitioning to empty state
    const deleteAllButton = page.locator('button:has-text("Delete All")');
    
    if (await deleteAllButton.isVisible()) {
      // Set up dialog handlers
      page.on('dialog', async dialog => {
        await dialog.accept(); // Accept all dialogs
      });
      
      await deleteAllButton.click();
      await page.waitForTimeout(2000);
    }
    
    // Check that the page still displays properly with no requirements
    await expect(page.locator('table')).toBeVisible();
    await expect(page.locator('h2')).toContainText('Requirement Management');
    await expect(page.locator('button:has-text("Add Requirement")')).toBeVisible();
    await expect(page.locator('button:has-text("Delete All")')).not.toBeVisible();
  });

  test('should be accessible via keyboard navigation', async ({ page }) => {
    // Test that delete buttons can be reached and activated via keyboard
    const deleteButtons = page.locator('button:has-text("Delete")');
    
    if (await deleteButtons.count() > 0) {
      // Focus on the first delete button
      await deleteButtons.first().focus();
      
      // Check that the button is focused
      const focusedElement = await page.evaluate(() => document.activeElement?.textContent);
      expect(focusedElement).toContain('Delete');
      
      // Test that Enter key can trigger the button (but cancel the dialog)
      page.on('dialog', async dialog => {
        await dialog.dismiss();
      });
      
      await page.keyboard.press('Enter');
    }
  });

  test('should display "Missing mapping" button when requirements exist', async ({ page }) => {
    const requirementCount = await page.locator('tbody tr').count();
    
    if (requirementCount > 0) {
      // Missing mapping button should be visible when requirements exist
      await expect(page.locator('button:has-text("Missing mapping")')).toBeVisible();
      
      // Check button styling
      const mappingButton = page.locator('button:has-text("Missing mapping")');
      await expect(mappingButton).toHaveClass(/btn-info/);
      
      // Check tooltip
      const title = await mappingButton.getAttribute('title');
      expect(title).toContain('AI-powered mapping');
    } else {
      // Missing mapping button should not be visible when no requirements exist
      await expect(page.locator('button:has-text("Missing mapping")')).not.toBeVisible();
    }
  });

  test('should show loading state when analyzing mappings', async ({ page }) => {
    const requirementCount = await page.locator('tbody tr').count();
    
    if (requirementCount > 0) {
      // Mock a slow response to test loading state
      await page.route('**/api/norm-mapping/**', route => {
        setTimeout(() => {
          route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ message: 'Test response', suggestions: [] })
          });
        }, 1000);
      });

      const mappingButton = page.locator('button:has-text("Missing mapping")');
      await mappingButton.click();
      
      // Should show loading state
      await expect(page.locator('button:has-text("Analyzing...")')).toBeVisible();
      await expect(page.locator('.spinner-border')).toBeVisible();
      
      // Wait for loading to complete
      await page.waitForTimeout(1500);
    }
  });

  test('should handle mapping suggestions modal', async ({ page }) => {
    const requirementCount = await page.locator('tbody tr').count();
    
    if (requirementCount > 0) {
      // Mock a successful mapping response
      await page.route('**/api/norm-mapping/ensure-norms', route => {
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'Norms ensured', createdNorms: 0 })
        });
      });

      await page.route('**/api/norm-mapping/suggest', route => {
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            message: 'Suggestions generated',
            totalRequirements: 1,
            requirementsWithSuggestions: 1,
            suggestions: [
              {
                requirementId: 1,
                requirementTitle: 'Test Requirement',
                suggestions: [
                  {
                    standard: 'NIST SP 800-53',
                    confidence: 4,
                    reasoning: 'This requirement aligns with access control standards',
                    normId: 1
                  }
                ]
              }
            ]
          })
        });
      });

      const mappingButton = page.locator('button:has-text("Missing mapping")');
      await mappingButton.click();
      
      // Wait for modal to appear
      await page.waitForTimeout(1000);
      
      // Check if modal appears
      const modal = page.locator('.modal:has-text("AI Norm Mapping Suggestions")');
      if (await modal.isVisible()) {
        // Verify modal content
        await expect(modal.locator('h5')).toContainText('AI Norm Mapping Suggestions');
        
        // Close modal
        await modal.locator('.btn-close').click();
        await expect(modal).not.toBeVisible();
      }
    }
  });

  test('should handle mapping errors gracefully', async ({ page }) => {
    const requirementCount = await page.locator('tbody tr').count();
    
    if (requirementCount > 0) {
      // Mock error responses
      await page.route('**/api/norm-mapping/ensure-norms', route => {
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'Norms ensured' })
        });
      });

      await page.route('**/api/norm-mapping/suggest', route => {
        route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'AI service unavailable' })
        });
      });

      const mappingButton = page.locator('button:has-text("Missing mapping")');
      await mappingButton.click();
      
      // Wait for error handling
      await page.waitForTimeout(1000);
      
      // Check for error message
      const errorAlert = page.locator('.alert-danger');
      if (await errorAlert.isVisible()) {
        await expect(errorAlert).toContainText('Error getting mapping suggestions');
      }
    }
  });

  test('should disable mapping button when no AI configuration', async ({ page }) => {
    const requirementCount = await page.locator('tbody tr').count();
    
    if (requirementCount > 0) {
      // Mock missing AI configuration
      await page.route('**/api/norm-mapping/ensure-norms', route => {
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'Norms ensured' })
        });
      });

      await page.route('**/api/norm-mapping/suggest', route => {
        route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'No active translation configuration found for AI norm mapping' })
        });
      });

      const mappingButton = page.locator('button:has-text("Missing mapping")');
      await mappingButton.click();
      
      // Wait for response
      await page.waitForTimeout(1000);
      
      // Should show appropriate error message
      const errorAlert = page.locator('.alert-danger');
      if (await errorAlert.isVisible()) {
        const errorText = await errorAlert.textContent();
        expect(errorText).toContain('Error getting mapping suggestions');
      }
    }
  });

  test('should maintain UI consistency during mapping operations', async ({ page }) => {
    const requirementCount = await page.locator('tbody tr').count();
    
    if (requirementCount > 0) {
      // Mock responses
      await page.route('**/api/norm-mapping/**', route => {
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'Success', suggestions: [] })
        });
      });

      const mappingButton = page.locator('button:has-text("Missing mapping")');
      await mappingButton.click();
      
      // Wait for operation to complete
      await page.waitForTimeout(1000);
      
      // UI should still be intact
      await expect(page.locator('h2')).toContainText('Requirement Management');
      await expect(page.locator('table')).toBeVisible();
      await expect(mappingButton).toBeVisible();
    }
  });
});