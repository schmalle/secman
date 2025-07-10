import { Page, expect } from '@playwright/test';

// Get test credentials from environment variables or use defaults
export const getTestCredentials = () => {
  return {
    username: process.env.PLAYWRIGHT_TEST_USERNAME || 'adminuser',
    password: process.env.PLAYWRIGHT_TEST_PASSWORD || 'password',
    backendUrl: process.env.PLAYWRIGHT_BACKEND_URL || 'http://localhost:9000',
    frontendUrl: process.env.PLAYWRIGHT_FRONTEND_URL || 'http://localhost:4321'
  };
};

// Login helper function that uses configurable credentials
export async function loginAsAdmin(page: Page, customUsername?: string, customPassword?: string) {
  const credentials = getTestCredentials();
  const username = customUsername || credentials.username;
  const password = customPassword || credentials.password;
  
  console.log(`Logging in with username: ${username}`);
  
  await page.goto('/login');
  await page.waitForLoadState('networkidle', { timeout: 10000 });
  
  // Wait for login form to be visible
  await page.waitForSelector('input[name="username"]', { timeout: 10000 });
  
  // Fill login form
  await page.fill('input[name="username"]', username);
  await page.fill('input[name="password"]', password);
  
  // Submit form
  await page.click('button[type="submit"]');
  
  // Wait for successful login - should redirect to home page
  await page.waitForURL('/', { timeout: 15000 });
  await page.waitForLoadState('networkidle', { timeout: 10000 });
  
  // Verify we're logged in by checking for page content
  await page.waitForSelector('body', { timeout: 5000 });
}

// Login as normal user (non-admin)
export async function loginAsNormalUser(page: Page) {
  await loginAsAdmin(page, 'normaluser', 'password');
}

// Logout helper function
export async function logout(page: Page) {
  try {
    // Try to navigate to logout endpoint directly
    await page.goto('/api/auth/logout');
    
    // Wait for redirect to login page
    await page.waitForURL('/login', { timeout: 10000 });
  } catch (error) {
    console.log('Logout navigation failed, trying alternative method');
    
    // Alternative: Look for logout button/link and click it
    const logoutButton = page.locator('button:has-text("Logout"), a:has-text("Logout"), [data-testid="logout"]');
    
    if (await logoutButton.isVisible({ timeout: 5000 })) {
      await logoutButton.click();
      await page.waitForURL('/login', { timeout: 10000 });
    } else {
      // Force navigation to login if all else fails
      await page.goto('/login');
    }
  }
}

// Wait for page to be fully loaded
export async function waitForPageLoad(page: Page, timeout: number = 30000) {
  await page.waitForLoadState('networkidle', { timeout });
}

// Check if user is authenticated
export async function isUserAuthenticated(page: Page): Promise<boolean> {
  try {
    const response = await page.request.get('/api/auth/status');
    return response.ok();
  } catch {
    return false;
  }
}

// Navigate to a page with authentication check
export async function navigateToPage(page: Page, url: string) {
  await page.goto(url);
  await waitForPageLoad(page, 10000);
  
  // If redirected to login, we're not authenticated
  if (page.url().includes('/login')) {
    throw new Error('User not authenticated - redirected to login');
  }
}

// Create test data helpers
export const testData = {
  // Sample requirement data
  requirement: {
    title: 'Test Requirement',
    description: 'This is a test requirement for E2E testing',
    priority: 'High'
  },
  
  // Sample risk data
  risk: {
    title: 'Test Risk',
    description: 'This is a test risk for E2E testing',
    likelihood: 'Medium',
    impact: 'High'
  },
  
  // Sample asset data
  asset: {
    name: 'Test Asset',
    description: 'This is a test asset for E2E testing',
    type: 'System'
  },
  
  // Sample norm data
  norm: {
    name: 'Test Norm',
    version: '1.0',
    year: '2024'
  }
};

// Clean up test data (helper to remove test data after tests)
export async function cleanupTestData(page: Page) {
  // This function can be expanded to clean up any test data created during tests
  console.log('Cleaning up test data...');
  
  // For now, just ensure we're logged out
  try {
    await logout(page);
  } catch (error) {
    console.log('Logout during cleanup failed, ignoring:', error);
    // Force navigation to login page as fallback
    try {
      await page.goto('/login');
    } catch {
      // Ignore any final navigation errors
    }
  }
}

// Utility to take screenshot on failure
export async function takeScreenshotOnFailure(page: Page, testName: string) {
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  const screenshotPath = `test-results/failure-${testName}-${timestamp}.png`;
  await page.screenshot({ path: screenshotPath, fullPage: true });
  console.log(`Screenshot saved: ${screenshotPath}`);
}

// Wait for API response
export async function waitForApiResponse(page: Page, urlPattern: string | RegExp, timeout: number = 10000) {
  return page.waitForResponse(response => {
    const url = response.url();
    if (typeof urlPattern === 'string') {
      return url.includes(urlPattern);
    } else {
      return urlPattern.test(url);
    }
  }, { timeout });
}

// Form helpers
export async function fillForm(page: Page, formData: Record<string, string>) {
  for (const [fieldName, value] of Object.entries(formData)) {
    await page.fill(`input[name="${fieldName}"], textarea[name="${fieldName}"], select[name="${fieldName}"]`, value);
  }
}

// Table helpers
export async function getTableRowCount(page: Page, tableSelector: string = 'table'): Promise<number> {
  const rows = page.locator(`${tableSelector} tbody tr`);
  return await rows.count();
}

export async function clickTableRowAction(page: Page, rowIndex: number, actionText: string, tableSelector: string = 'table') {
  const row = page.locator(`${tableSelector} tbody tr`).nth(rowIndex);
  await row.locator(`button:has-text("${actionText}"), a:has-text("${actionText}")`).click();
}

// Modal/Dialog helpers
export async function waitForModal(page: Page, modalSelector: string = '.modal, .dialog, [role="dialog"]') {
  await page.locator(modalSelector).waitFor({ state: 'visible' });
}

export async function closeModal(page: Page, modalSelector: string = '.modal, .dialog, [role="dialog"]') {
  const modal = page.locator(modalSelector);
  
  // Try to find and click close button
  const closeButton = modal.locator('button:has-text("Close"), button:has-text("Cancel"), .close, [aria-label="Close"]');
  
  if (await closeButton.isVisible()) {
    await closeButton.click();
  } else {
    // Try pressing Escape key
    await page.keyboard.press('Escape');
  }
  
  // Wait for modal to disappear
  await modal.waitFor({ state: 'hidden' });
}