import { test, expect } from '@playwright/test';
import { loginAsAdmin, getTestCredentials } from './test-helpers';

test.describe('Simple Smoke Tests', () => {
  
  test('Basic Login Test', async ({ page }) => {
    // This test just verifies basic login functionality
    try {
      await loginAsAdmin(page);
      
      // Verify we're logged in by checking URL
      await expect(page).toHaveURL('/');
      
      // Verify page loads
      await expect(page.locator('body')).toBeVisible();
      
      console.log('Login test passed successfully');
    } catch (error) {
      console.error('Login test failed:', error);
      throw error;
    }
  });
  
  test('Frontend Server Responds', async ({ page }) => {
    // Just verify the frontend server is responding
    await page.goto('/login');
    await expect(page.locator('body')).toBeVisible();
    console.log('Frontend server is responding');
  });
  
  test('Backend API Health Check', async ({ page }) => {
    // Verify backend API is responding
    const response = await page.request.get('/api/auth/status');
    
    // Should get some response (200 or 401/403 are both acceptable)
    expect([200, 401, 403]).toContain(response.status());
    
    console.log(`Backend API responded with status: ${response.status()}`);
  });
});