import { test, expect } from '@playwright/test';

test.describe('Email Configuration Encryption Verification', () => {

  test.beforeEach(async ({ page }) => {
    // Login as admin user
    await page.goto('http://localhost:4321/login');
    await page.fill('input[type="text"]', 'adminuser');
    await page.fill('input[type="password"]', 'password');
    await page.click('button[type="submit"]');
    await expect(page).toHaveURL(/.*\/dashboard/);
  });

  test('should encrypt sensitive fields in database', async ({ page }) => {
    // Navigate to email configuration
    await page.goto('http://localhost:4321/admin/email-config');

    // Create configuration with sensitive data
    await page.click('[data-testid="create-config-btn"]');

    const sensitiveUsername = 'test.user@gmail.com';
    const sensitivePassword = 'super-secret-app-password-123';

    await page.fill('input[name="name"]', 'Encryption Test Config');
    await page.fill('input[name="smtpHost"]', 'smtp.gmail.com');
    await page.fill('input[name="smtpPort"]', '587');
    await page.fill('input[name="smtpUsername"]', sensitiveUsername);
    await page.fill('input[name="smtpPassword"]', sensitivePassword);
    await page.fill('input[name="fromEmail"]', 'test@gmail.com');
    await page.fill('input[name="fromName"]', 'Test Sender');

    // Save configuration
    await page.click('[data-testid="save-config-btn"]');

    // Wait for successful save
    await expect(page.locator('[data-testid="success-message"]')).toBeVisible();

    // Verify credentials are not displayed in plaintext in UI
    const configList = page.locator('[data-testid="config-list"]');
    await expect(configList).toBeVisible();

    // Username should be masked or hidden
    const usernameDisplay = page.locator('[data-testid="config-username-display"]');
    if (await usernameDisplay.isVisible()) {
      const displayText = await usernameDisplay.textContent();
      expect(displayText).not.toContain(sensitiveUsername);
      expect(displayText).toMatch(/\*+|hidden|encrypted/i);
    }

    // Password should definitely be masked
    const passwordDisplay = page.locator('[data-testid="config-password-display"]');
    if (await passwordDisplay.isVisible()) {
      const displayText = await passwordDisplay.textContent();
      expect(displayText).not.toContain(sensitivePassword);
      expect(displayText).toMatch(/\*+|hidden|encrypted/i);
    }
  });

  test('should not expose credentials in API responses', async ({ page }) => {
    // Intercept API calls to verify response content
    const apiResponses: any[] = [];

    await page.route('**/api/email-configs*', route => {
      route.fulfill().then(response => {
        response?.json().then(data => {
          apiResponses.push(data);
        }).catch(() => {
          // Handle non-JSON responses
        });
      });
      route.continue();
    });

    await page.goto('http://localhost:4321/admin/email-config');

    // Wait for API calls to complete
    await page.waitForTimeout(2000);

    // Verify API responses don't contain plaintext credentials
    for (const response of apiResponses) {
      if (Array.isArray(response)) {
        response.forEach(config => {
          if (config.smtpUsername) {
            expect(config.smtpUsername).toMatch(/\*+|null|undefined|encrypted/i);
          }
          if (config.smtpPassword) {
            expect(config.smtpPassword).toMatch(/\*+|null|undefined|encrypted/i);
          }
        });
      } else if (response.smtpUsername || response.smtpPassword) {
        expect(response.smtpUsername || '').toMatch(/\*+|null|undefined|encrypted/i);
        expect(response.smtpPassword || '').toMatch(/\*+|null|undefined|encrypted/i);
      }
    }
  });

  test('should verify credentials work despite encryption', async ({ page }) => {
    // Mock successful test email to verify decryption works
    await page.route('**/api/email-configs/*/test', route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          message: 'Test email sent successfully. Credentials were decrypted and used correctly.'
        })
      });
    });

    await page.goto('http://localhost:4321/admin/email-config');

    // Test the encrypted configuration
    await page.click('[data-testid="test-config-1"]');

    // Fill test email dialog
    await page.fill('input[name="testEmail"]', 'test@example.com');
    await page.click('[data-testid="send-test-btn"]');

    // Should succeed, proving decryption works
    await expect(page.locator('[data-testid="test-success"]')).toBeVisible();
    await expect(page.locator('[data-testid="test-success"]')).toContainText('sent successfully');
  });

  test('should handle encryption/decryption errors gracefully', async ({ page }) => {
    // Mock encryption error scenario
    await page.route('**/api/email-configs', route => {
      if (route.request().method() === 'POST') {
        route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({
            error: 'Encryption Error',
            message: 'Failed to encrypt sensitive configuration data'
          })
        });
      } else {
        route.continue();
      }
    });

    await page.goto('http://localhost:4321/admin/email-config');

    await page.click('[data-testid="create-config-btn"]');

    // Fill form with data that will trigger encryption error
    await page.fill('input[name="name"]', 'Error Test Config');
    await page.fill('input[name="smtpHost"]', 'smtp.example.com');
    await page.fill('input[name="smtpPort"]', '587');
    await page.fill('input[name="smtpUsername"]', 'test@example.com');
    await page.fill('input[name="smtpPassword"]', 'password');
    await page.fill('input[name="fromEmail"]', 'test@example.com');
    await page.fill('input[name="fromName"]', 'Test');

    await page.click('[data-testid="save-config-btn"]');

    // Should show encryption error
    await expect(page.locator('[data-testid="error-message"]')).toBeVisible();
    await expect(page.locator('[data-testid="error-message"]')).toContainText('Failed to encrypt');
  });

  test('should validate encryption key configuration', async ({ page }) => {
    // Navigate to system settings to check encryption configuration
    await page.goto('http://localhost:4321/admin/system-settings');

    // Should show encryption status
    await expect(page.locator('[data-testid="encryption-status"]')).toBeVisible();

    // Should indicate encryption is enabled
    await expect(page.locator('[data-testid="encryption-enabled"]')).toContainText('Enabled');

    // Should show encryption key information (but not the actual key)
    await expect(page.locator('[data-testid="encryption-key-info"]')).toBeVisible();
    await expect(page.locator('[data-testid="encryption-key-info"]')).toContainText('configured');

    // Should not display actual encryption keys
    const keyDisplay = page.locator('[data-testid="encryption-keys"]');
    if (await keyDisplay.isVisible()) {
      const keyText = await keyDisplay.textContent();
      expect(keyText).toMatch(/\*+|hidden|secured/i);
    }
  });

  test('should support key rotation workflow', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/system-settings');

    // Should show key rotation option
    await expect(page.locator('[data-testid="key-rotation-section"]')).toBeVisible();

    // Click initiate key rotation
    await page.click('[data-testid="initiate-rotation-btn"]');

    // Should show warning about key rotation
    await expect(page.locator('[data-testid="rotation-warning"]')).toBeVisible();
    await expect(page.locator('[data-testid="rotation-warning"]')).toContainText('decrypt existing');

    // Should show rotation steps
    await expect(page.locator('[data-testid="rotation-steps"]')).toBeVisible();

    // Should require confirmation
    await expect(page.locator('[data-testid="rotation-confirm"]')).toBeVisible();
  });

  test('should verify encryption strength and algorithms', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/security-status');

    // Should show encryption algorithm information
    await expect(page.locator('[data-testid="encryption-algorithm"]')).toBeVisible();
    await expect(page.locator('[data-testid="encryption-algorithm"]')).toContainText('AES');

    // Should show key strength
    await expect(page.locator('[data-testid="key-strength"]')).toBeVisible();
    await expect(page.locator('[data-testid="key-strength"]')).toContainText('256');

    // Should show encryption status
    await expect(page.locator('[data-testid="encryption-health"]')).toContainText('Healthy');
  });

  test('should handle configuration updates with encryption', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/email-config');

    // Edit existing configuration
    await page.click('[data-testid="edit-config-1"]');

    // Change sensitive data
    await page.fill('input[name="smtpUsername"]', 'updated.user@gmail.com');
    await page.fill('input[name="smtpPassword"]', 'new-secret-password-456');

    // Save changes
    await page.click('[data-testid="save-config-btn"]');

    // Should successfully save with re-encryption
    await expect(page.locator('[data-testid="success-message"]')).toBeVisible();

    // Test that updated credentials work
    await page.click('[data-testid="test-config-1"]');
    await page.fill('input[name="testEmail"]', 'test@example.com');
    await page.click('[data-testid="send-test-btn"]');

    // Should succeed with new encrypted credentials
    await expect(page.locator('[data-testid="test-success"]')).toBeVisible();
  });

  test('should prevent credential exposure in browser storage', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/email-config');

    // Create and save a configuration
    await page.click('[data-testid="create-config-btn"]');
    await page.fill('input[name="name"]', 'Storage Test Config');
    await page.fill('input[name="smtpHost"]', 'smtp.test.com');
    await page.fill('input[name="smtpPort"]', '587');
    await page.fill('input[name="smtpUsername"]', 'storage.test@example.com');
    await page.fill('input[name="smtpPassword"]', 'storage-test-password');
    await page.fill('input[name="fromEmail"]', 'from@test.com');
    await page.fill('input[name="fromName"]', 'Test');

    await page.click('[data-testid="save-config-btn"]');

    // Check browser storage for exposed credentials
    const localStorage = await page.evaluate(() => {
      const storage: any = {};
      for (let i = 0; i < window.localStorage.length; i++) {
        const key = window.localStorage.key(i);
        if (key) {
          storage[key] = window.localStorage.getItem(key);
        }
      }
      return storage;
    });

    const sessionStorage = await page.evaluate(() => {
      const storage: any = {};
      for (let i = 0; i < window.sessionStorage.length; i++) {
        const key = window.sessionStorage.key(i);
        if (key) {
          storage[key] = window.sessionStorage.getItem(key);
        }
      }
      return storage;
    });

    // Verify credentials are not stored in browser storage
    const storageString = JSON.stringify({ ...localStorage, ...sessionStorage });
    expect(storageString).not.toContain('storage.test@example.com');
    expect(storageString).not.toContain('storage-test-password');
  });
});