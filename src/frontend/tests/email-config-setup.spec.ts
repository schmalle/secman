import { test, expect } from '@playwright/test';

test.describe('Email Configuration Setup', () => {

  test.beforeEach(async ({ page }) => {
    // Login as admin user
    await page.goto('http://localhost:4321/login');
    await page.fill('input[type="text"]', 'adminuser');
    await page.fill('input[type="password"]', 'password');
    await page.click('button[type="submit"]');
    await expect(page).toHaveURL(/.*\/dashboard/);

    // Navigate to email config page
    await page.goto('http://localhost:4321/admin/email-config');
  });

  test('should create new email configuration successfully', async ({ page }) => {
    // Click create new configuration button
    await page.click('[data-testid="create-config-btn"]');

    // Fill in SMTP configuration form
    await page.fill('input[name="name"]', 'Test SMTP Config');
    await page.fill('input[name="smtpHost"]', 'smtp.gmail.com');
    await page.fill('input[name="smtpPort"]', '587');
    await page.check('input[name="smtpTls"]');
    await page.fill('input[name="smtpUsername"]', 'test@gmail.com');
    await page.fill('input[name="smtpPassword"]', 'app-password');
    await page.fill('input[name="fromEmail"]', 'test@gmail.com');
    await page.fill('input[name="fromName"]', 'SecMan Test');

    // Submit the form
    await page.click('[data-testid="save-config-btn"]');

    // Should show success message
    await expect(page.locator('[data-testid="success-message"]')).toBeVisible();
    await expect(page.locator('[data-testid="success-message"]')).toContainText('Configuration saved');

    // Should appear in configurations list
    await expect(page.locator('[data-testid="config-list"]')).toContainText('Test SMTP Config');
  });

  test('should activate email configuration', async ({ page }) => {
    // Assume a configuration exists (created in previous test or setup)
    await page.click('[data-testid="activate-config-1"]');

    // Should show confirmation dialog
    await expect(page.locator('[data-testid="confirm-activate"]')).toBeVisible();

    // Confirm activation
    await page.click('[data-testid="confirm-activate-btn"]');

    // Should show success message
    await expect(page.locator('[data-testid="success-message"]')).toContainText('Configuration activated');

    // Should show active status
    await expect(page.locator('[data-testid="config-status-1"]')).toContainText('Active');
  });

  test('should test email configuration', async ({ page }) => {
    // Mock successful email test
    await page.route('**/api/email-configs/*/test', route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ success: true, message: 'Test email sent successfully' })
      });
    });

    // Click test configuration button
    await page.click('[data-testid="test-config-1"]');

    // Should show test email dialog
    await expect(page.locator('[data-testid="test-email-dialog"]')).toBeVisible();

    // Fill test email address
    await page.fill('input[name="testEmail"]', 'test@example.com');

    // Send test email
    await page.click('[data-testid="send-test-btn"]');

    // Should show success message
    await expect(page.locator('[data-testid="test-success"]')).toBeVisible();
    await expect(page.locator('[data-testid="test-success"]')).toContainText('Test email sent');
  });

  test('should validate form inputs', async ({ page }) => {
    await page.click('[data-testid="create-config-btn"]');

    // Try to submit empty form
    await page.click('[data-testid="save-config-btn"]');

    // Should show validation errors
    await expect(page.locator('[data-testid="error-name"]')).toBeVisible();
    await expect(page.locator('[data-testid="error-smtpHost"]')).toBeVisible();
    await expect(page.locator('[data-testid="error-fromEmail"]')).toBeVisible();

    // Fill invalid email
    await page.fill('input[name="fromEmail"]', 'invalid-email');

    // Should show email validation error
    await expect(page.locator('[data-testid="error-fromEmail"]')).toContainText('valid email');

    // Fill invalid port
    await page.fill('input[name="smtpPort"]', '99999');

    // Should show port validation error
    await expect(page.locator('[data-testid="error-smtpPort"]')).toContainText('valid port');
  });

  test('should handle API errors gracefully', async ({ page }) => {
    // Mock API error
    await page.route('**/api/email-configs', route => {
      route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Invalid configuration', message: 'SMTP host is unreachable' })
      });
    });

    await page.click('[data-testid="create-config-btn"]');

    // Fill valid form
    await page.fill('input[name="name"]', 'Invalid Config');
    await page.fill('input[name="smtpHost"]', 'invalid.smtp.server');
    await page.fill('input[name="smtpPort"]', '587');
    await page.fill('input[name="fromEmail"]', 'test@example.com');
    await page.fill('input[name="fromName"]', 'Test');

    await page.click('[data-testid="save-config-btn"]');

    // Should show API error
    await expect(page.locator('[data-testid="error-message"]')).toBeVisible();
    await expect(page.locator('[data-testid="error-message"]')).toContainText('SMTP host is unreachable');
  });

  test('should hide sensitive information in display', async ({ page }) => {
    // Configuration should be visible in list
    await expect(page.locator('[data-testid="config-list"]')).toBeVisible();

    // SMTP password should be masked
    const passwordField = page.locator('[data-testid="smtp-password-1"]');
    if (await passwordField.isVisible()) {
      const passwordText = await passwordField.textContent();
      expect(passwordText).toMatch(/\*+/); // Should show asterisks
    }

    // SMTP username might be partially masked
    const usernameField = page.locator('[data-testid="smtp-username-1"]');
    if (await usernameField.isVisible()) {
      const usernameText = await usernameField.textContent();
      // Should either be fully visible (for username) or partially masked
      expect(usernameText).toBeTruthy();
    }
  });

  test('should support different SMTP providers', async ({ page }) => {
    await page.click('[data-testid="create-config-btn"]');

    // Test Gmail preset
    await page.selectOption('[data-testid="provider-preset"]', 'gmail');

    // Should auto-fill Gmail settings
    await expect(page.locator('input[name="smtpHost"]')).toHaveValue('smtp.gmail.com');
    await expect(page.locator('input[name="smtpPort"]')).toHaveValue('587');
    await expect(page.locator('input[name="smtpTls"]')).toBeChecked();

    // Test Outlook preset
    await page.selectOption('[data-testid="provider-preset"]', 'outlook');

    // Should auto-fill Outlook settings
    await expect(page.locator('input[name="smtpHost"]')).toHaveValue('smtp-mail.outlook.com');
    await expect(page.locator('input[name="smtpPort"]')).toHaveValue('587');

    // Test custom preset
    await page.selectOption('[data-testid="provider-preset"]', 'custom');

    // Should clear auto-filled values
    await expect(page.locator('input[name="smtpHost"]')).toHaveValue('');
  });

  test('should handle concurrent configuration operations', async ({ page }) => {
    // Test creating multiple configurations without conflicts
    await page.click('[data-testid="create-config-btn"]');

    // Fill first config
    await page.fill('input[name="name"]', 'Config 1');
    await page.fill('input[name="smtpHost"]', 'smtp1.example.com');
    await page.fill('input[name="smtpPort"]', '587');
    await page.fill('input[name="fromEmail"]', 'test1@example.com');
    await page.fill('input[name="fromName"]', 'Test 1');

    await page.click('[data-testid="save-config-btn"]');

    // Wait for first config to be saved
    await expect(page.locator('[data-testid="success-message"]')).toBeVisible();

    // Create second config
    await page.click('[data-testid="create-config-btn"]');

    await page.fill('input[name="name"]', 'Config 2');
    await page.fill('input[name="smtpHost"]', 'smtp2.example.com');
    await page.fill('input[name="smtpPort"]', '465');
    await page.fill('input[name="fromEmail"]', 'test2@example.com');
    await page.fill('input[name="fromName"]', 'Test 2');

    await page.click('[data-testid="save-config-btn"]');

    // Both configs should be visible
    await expect(page.locator('[data-testid="config-list"]')).toContainText('Config 1');
    await expect(page.locator('[data-testid="config-list"]')).toContainText('Config 2');
  });

  test('should enforce single active configuration', async ({ page }) => {
    // Activate first configuration
    await page.click('[data-testid="activate-config-1"]');
    await page.click('[data-testid="confirm-activate-btn"]');

    // Try to activate second configuration
    await page.click('[data-testid="activate-config-2"]');

    // Should show warning about deactivating current config
    await expect(page.locator('[data-testid="deactivate-warning"]')).toBeVisible();
    await expect(page.locator('[data-testid="deactivate-warning"]')).toContainText('deactivate the current');

    // Confirm activation
    await page.click('[data-testid="confirm-activate-btn"]');

    // First config should be inactive, second should be active
    await expect(page.locator('[data-testid="config-status-1"]')).toContainText('Inactive');
    await expect(page.locator('[data-testid="config-status-2"]')).toContainText('Active');
  });
});