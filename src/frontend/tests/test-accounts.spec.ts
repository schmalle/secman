import { test, expect } from '@playwright/test';

test.describe('Test Email Account Management', () => {

  test.beforeEach(async ({ page }) => {
    // Login as admin user
    await page.goto('http://localhost:4321/login');
    await page.fill('input[type="text"]', 'adminuser');
    await page.fill('input[type="password"]', 'password');
    await page.click('button[type="submit"]');
    await expect(page).toHaveURL(/.*\/dashboard/);
  });

  test('should create test email account successfully', async ({ page }) => {
    // Navigate to test accounts management
    await page.goto('http://localhost:4321/admin/test-accounts');

    // Create new test account
    await page.click('[data-testid="create-test-account-btn"]');

    // Fill test account form
    await page.fill('input[name="name"]', 'Gmail Test Account');
    await page.fill('input[name="emailAddress"]', 'test.account@gmail.com');
    await page.selectOption('select[name="provider"]', 'GMAIL');

    // Fill provider-specific credentials
    await page.fill('input[name="credentials.username"]', 'test.account@gmail.com');
    await page.fill('input[name="credentials.password"]', 'gmail-app-password');

    // Save test account
    await page.click('[data-testid="save-test-account-btn"]');

    // Should show success message
    await expect(page.locator('[data-testid="success-message"]')).toBeVisible();
    await expect(page.locator('[data-testid="success-message"]')).toContainText('Test account created');

    // Should appear in accounts list
    await expect(page.locator('[data-testid="test-accounts-list"]')).toContainText('Gmail Test Account');

    // Should have VERIFICATION_PENDING status initially
    await expect(page.locator('[data-testid="account-status-1"]')).toContainText('VERIFICATION_PENDING');
  });

  test('should verify test email account credentials', async ({ page }) => {
    // Mock successful verification
    await page.route('**/api/test-email-accounts/*/verify', route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          testType: 'CONNECTIVITY',
          timestamp: new Date().toISOString(),
          details: { connectionTime: 1200, authSuccess: true }
        })
      });
    });

    await page.goto('http://localhost:4321/admin/test-accounts');

    // Verify test account
    await page.click('[data-testid="verify-account-1"]');

    // Should show verification dialog
    await expect(page.locator('[data-testid="verify-dialog"]')).toBeVisible();

    // Confirm verification
    await page.click('[data-testid="confirm-verify-btn"]');

    // Should show verification success
    await expect(page.locator('[data-testid="verify-success"]')).toBeVisible();
    await expect(page.locator('[data-testid="verify-success"]')).toContainText('Verification successful');

    // Status should update to ACTIVE
    await expect(page.locator('[data-testid="account-status-1"]')).toContainText('ACTIVE');
  });

  test('should test email sending functionality', async ({ page }) => {
    // Mock successful email test
    await page.route('**/api/test-email-accounts/*/test', route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          messageId: 'test-message-123',
          testType: 'SEND',
          timestamp: new Date().toISOString(),
          details: { deliveryTime: 2500 }
        })
      });
    });

    await page.goto('http://localhost:4321/admin/test-accounts');

    // Test email sending
    await page.click('[data-testid="test-account-1"]');

    // Should show test email dialog
    await expect(page.locator('[data-testid="test-email-dialog"]')).toBeVisible();

    // Fill test email details
    await page.fill('input[name="subject"]', 'SecMan Test Email');
    await page.fill('textarea[name="content"]', 'This is a test email to verify account functionality.');

    // Send test email
    await page.click('[data-testid="send-test-email-btn"]');

    // Should show test success
    await expect(page.locator('[data-testid="test-success"]')).toBeVisible();
    await expect(page.locator('[data-testid="test-success"]')).toContainText('Test email sent');

    // Should show message ID
    await expect(page.locator('[data-testid="message-id"]')).toContainText('test-message-123');
  });

  test('should handle different email providers', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/test-accounts');

    // Test Outlook provider
    await page.click('[data-testid="create-test-account-btn"]');

    await page.fill('input[name="name"]', 'Outlook Test Account');
    await page.fill('input[name="emailAddress"]', 'test@outlook.com');
    await page.selectOption('select[name="provider"]', 'OUTLOOK');

    // Should show Outlook-specific credential fields
    await expect(page.locator('[data-testid="outlook-credentials"]')).toBeVisible();

    // Fill Outlook credentials
    await page.fill('input[name="credentials.username"]', 'test@outlook.com');
    await page.fill('input[name="credentials.password"]', 'outlook-password');

    await page.click('[data-testid="save-test-account-btn"]');

    // Should create Outlook account
    await expect(page.locator('[data-testid="test-accounts-list"]')).toContainText('Outlook Test Account');
  });

  test('should handle custom SMTP configuration', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/test-accounts');

    await page.click('[data-testid="create-test-account-btn"]');

    await page.fill('input[name="name"]', 'Custom SMTP Account');
    await page.fill('input[name="emailAddress"]', 'test@custom-domain.com');
    await page.selectOption('select[name="provider"]', 'SMTP_CUSTOM');

    // Should show custom SMTP configuration fields
    await expect(page.locator('[data-testid="custom-smtp-config"]')).toBeVisible();

    // Fill custom SMTP settings
    await page.fill('input[name="credentials.host"]', 'mail.custom-domain.com');
    await page.fill('input[name="credentials.port"]', '587');
    await page.fill('input[name="credentials.username"]', 'test@custom-domain.com');
    await page.fill('input[name="credentials.password"]', 'custom-password');
    await page.check('input[name="credentials.tls"]');

    await page.click('[data-testid="save-test-account-btn"]');

    // Should create custom SMTP account
    await expect(page.locator('[data-testid="test-accounts-list"]')).toContainText('Custom SMTP Account');
  });

  test('should view test account history and results', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/test-accounts');

    // View test history for account
    await page.click('[data-testid="view-history-1"]');

    // Should show test history dialog
    await expect(page.locator('[data-testid="test-history-dialog"]')).toBeVisible();

    // Should show history table
    await expect(page.locator('[data-testid="history-table"]')).toBeVisible();

    // Should show column headers
    await expect(page.locator('thead')).toContainText(['Test Type', 'Result', 'Timestamp', 'Details']);

    // Should show individual test results
    const testResults = page.locator('[data-testid="test-result"]');
    if (await testResults.count() > 0) {
      await expect(testResults.first()).toContainText(/SEND|CONNECTIVITY|RECEIVE/);
      await expect(testResults.first()).toContainText(/Success|Failed/);
    }
  });

  test('should filter test accounts by status and provider', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/test-accounts');

    // Filter by status
    await page.selectOption('[data-testid="status-filter"]', 'ACTIVE');
    await page.click('[data-testid="apply-filter-btn"]');

    // Should show only active accounts
    const accountsList = page.locator('[data-testid="test-accounts-list"]');
    const statusElements = accountsList.locator('[data-testid*="account-status"]');

    if (await statusElements.count() > 0) {
      for (let i = 0; i < await statusElements.count(); i++) {
        await expect(statusElements.nth(i)).toContainText('ACTIVE');
      }
    }

    // Filter by provider
    await page.selectOption('[data-testid="provider-filter"]', 'GMAIL');
    await page.click('[data-testid="apply-filter-btn"]');

    // Should show only Gmail accounts
    const providerElements = accountsList.locator('[data-testid*="account-provider"]');
    if (await providerElements.count() > 0) {
      for (let i = 0; i < await providerElements.count(); i++) {
        await expect(providerElements.nth(i)).toContainText('GMAIL');
      }
    }
  });

  test('should handle failed verification gracefully', async ({ page }) => {
    // Mock failed verification
    await page.route('**/api/test-email-accounts/*/verify', route => {
      route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({
          success: false,
          error: 'Authentication failed: Invalid credentials',
          testType: 'CONNECTIVITY',
          timestamp: new Date().toISOString()
        })
      });
    });

    await page.goto('http://localhost:4321/admin/test-accounts');

    // Try to verify failed account
    await page.click('[data-testid="verify-account-2"]');
    await page.click('[data-testid="confirm-verify-btn"]');

    // Should show verification error
    await expect(page.locator('[data-testid="verify-error"]')).toBeVisible();
    await expect(page.locator('[data-testid="verify-error"]')).toContainText('Authentication failed');

    // Status should update to FAILED
    await expect(page.locator('[data-testid="account-status-2"]')).toContainText('FAILED');
  });

  test('should edit and update test account credentials', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/test-accounts');

    // Edit existing test account
    await page.click('[data-testid="edit-account-1"]');

    // Should show edit dialog with current values
    await expect(page.locator('[data-testid="edit-account-dialog"]')).toBeVisible();

    // Update account details
    await page.fill('input[name="name"]', 'Updated Gmail Account');
    await page.fill('input[name="credentials.password"]', 'new-app-password');

    // Save changes
    await page.click('[data-testid="save-changes-btn"]');

    // Should show update success
    await expect(page.locator('[data-testid="update-success"]')).toBeVisible();

    // Should update name in list
    await expect(page.locator('[data-testid="test-accounts-list"]')).toContainText('Updated Gmail Account');

    // Status should change to VERIFICATION_PENDING due to credential update
    await expect(page.locator('[data-testid="account-status-1"]')).toContainText('VERIFICATION_PENDING');
  });

  test('should delete test account with confirmation', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/test-accounts');

    // Delete test account
    await page.click('[data-testid="delete-account-1"]');

    // Should show confirmation dialog
    await expect(page.locator('[data-testid="delete-confirm-dialog"]')).toBeVisible();
    await expect(page.locator('[data-testid="delete-warning"]')).toContainText('permanently delete');

    // Confirm deletion
    await page.click('[data-testid="confirm-delete-btn"]');

    // Should show deletion success
    await expect(page.locator('[data-testid="delete-success"]')).toBeVisible();

    // Account should be removed from list
    await expect(page.locator('[data-testid="test-accounts-list"]')).not.toContainText('Gmail Test Account');
  });

  test('should validate test account form inputs', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/test-accounts');

    await page.click('[data-testid="create-test-account-btn"]');

    // Try to save empty form
    await page.click('[data-testid="save-test-account-btn"]');

    // Should show validation errors
    await expect(page.locator('[data-testid="error-name"]')).toBeVisible();
    await expect(page.locator('[data-testid="error-emailAddress"]')).toBeVisible();
    await expect(page.locator('[data-testid="error-provider"]')).toBeVisible();

    // Fill invalid email
    await page.fill('input[name="emailAddress"]', 'not-an-email');

    // Should show email validation error
    await expect(page.locator('[data-testid="error-emailAddress"]')).toContainText('valid email');

    // Fix validation errors
    await page.fill('input[name="name"]', 'Valid Account');
    await page.fill('input[name="emailAddress"]', 'valid@example.com');
    await page.selectOption('select[name="provider"]', 'GMAIL');

    // Should clear validation errors
    await expect(page.locator('[data-testid="error-name"]')).not.toBeVisible();
    await expect(page.locator('[data-testid="error-emailAddress"]')).not.toBeVisible();
  });

  test('should show account statistics and usage', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/test-accounts');

    // Should show statistics panel
    await expect(page.locator('[data-testid="accounts-stats"]')).toBeVisible();

    // Should show total counts
    await expect(page.locator('[data-testid="total-accounts"]')).toBeVisible();
    await expect(page.locator('[data-testid="active-accounts"]')).toBeVisible();
    await expect(page.locator('[data-testid="failed-accounts"]')).toBeVisible();

    // Should show provider distribution
    await expect(page.locator('[data-testid="provider-stats"]')).toBeVisible();

    // Should show recent test activity
    await expect(page.locator('[data-testid="recent-tests"]')).toBeVisible();
  });
});