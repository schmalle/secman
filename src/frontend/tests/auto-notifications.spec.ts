import { test, expect } from '@playwright/test';

test.describe('Automatic Risk Assessment Notifications', () => {

  test.beforeEach(async ({ page }) => {
    // Login as admin user
    await page.goto('http://localhost:4321/login');
    await page.fill('input[type="text"]', 'adminuser');
    await page.fill('input[type="password"]', 'password');
    await page.click('button[type="submit"]');
    await expect(page).toHaveURL(/.*\/dashboard/);
  });

  test('should configure notification recipients successfully', async ({ page }) => {
    // Navigate to notification configuration
    await page.goto('http://localhost:4321/admin/notifications');

    // Create new notification config
    await page.click('[data-testid="create-notification-config-btn"]');

    // Fill notification configuration
    await page.fill('input[name="name"]', 'Security Team');
    await page.fill('input[name="recipientEmails"]', 'security@company.com,admin@company.com');

    // Set conditions for high-risk assessments
    await page.click('[data-testid="add-condition-btn"]');
    await page.selectOption('[data-testid="condition-field"]', 'riskLevel');
    await page.selectOption('[data-testid="condition-operator"]', 'equals');
    await page.fill('input[name="condition-value"]', 'HIGH');

    // Save configuration
    await page.click('[data-testid="save-notification-config-btn"]');

    // Should show success message
    await expect(page.locator('[data-testid="success-message"]')).toBeVisible();
    await expect(page.locator('[data-testid="success-message"]')).toContainText('Notification configuration saved');

    // Should appear in configurations list
    await expect(page.locator('[data-testid="notification-configs"]')).toContainText('Security Team');
  });

  test('should trigger email notifications when creating high-risk assessment', async ({ page }) => {
    // Mock email notification API
    let notificationTriggered = false;
    await page.route('**/api/notifications/send', route => {
      notificationTriggered = true;
      route.fulfill({
        status: 202,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Notification queued for sending', notificationId: 123 })
      });
    });

    // Navigate to create risk assessment
    await page.goto('http://localhost:4321/risk-assessments/create');

    // Fill high-risk assessment form
    await page.fill('input[name="title"]', 'Critical Security Vulnerability');
    await page.fill('textarea[name="description"]', 'SQL injection vulnerability discovered in user authentication module');
    await page.selectOption('select[name="riskLevel"]', 'HIGH');
    await page.selectOption('select[name="probability"]', 'HIGH');
    await page.selectOption('select[name="impact"]', 'HIGH');

    // Submit risk assessment
    await page.click('[data-testid="create-assessment-btn"]');

    // Should show success message
    await expect(page.locator('[data-testid="success-message"]')).toBeVisible();
    await expect(page.locator('[data-testid="success-message"]')).toContainText('Risk assessment created');

    // Should trigger notification (check API call was made)
    await page.waitForTimeout(1000); // Wait for async notification
    expect(notificationTriggered).toBe(true);
  });

  test('should not trigger notifications for low-risk assessments', async ({ page }) => {
    // Mock email notification API
    let notificationTriggered = false;
    await page.route('**/api/notifications/send', route => {
      notificationTriggered = true;
      route.fulfill({
        status: 202,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Notification queued' })
      });
    });

    // Navigate to create risk assessment
    await page.goto('http://localhost:4321/risk-assessments/create');

    // Fill low-risk assessment form
    await page.fill('input[name="title"]', 'Minor Documentation Update');
    await page.fill('textarea[name="description"]', 'Update user manual with new feature information');
    await page.selectOption('select[name="riskLevel"]', 'LOW');
    await page.selectOption('select[name="probability"]', 'LOW');
    await page.selectOption('select[name="impact"]', 'LOW');

    // Submit risk assessment
    await page.click('[data-testid="create-assessment-btn"]');

    // Should show success message
    await expect(page.locator('[data-testid="success-message"]')).toBeVisible();

    // Should NOT trigger notification
    await page.waitForTimeout(1000);
    expect(notificationTriggered).toBe(false);
  });

  test('should view notification logs and delivery status', async ({ page }) => {
    // Navigate to notification logs
    await page.goto('http://localhost:4321/admin/notification-logs');

    // Should show logs table
    await expect(page.locator('[data-testid="notification-logs-table"]')).toBeVisible();

    // Should show column headers
    await expect(page.locator('thead')).toContainText(['Risk Assessment', 'Recipients', 'Status', 'Sent At']);

    // Filter by risk assessment
    await page.fill('input[name="riskAssessmentFilter"]', 'Critical Security');
    await page.click('[data-testid="apply-filter-btn"]');

    // Should filter logs
    await expect(page.locator('[data-testid="logs-filtered"]')).toBeVisible();

    // Check individual log entry
    const firstLog = page.locator('[data-testid="log-entry-1"]');
    await expect(firstLog).toContainText('Critical Security Vulnerability');
    await expect(firstLog).toContainText('security@company.com');
  });

  test('should handle failed email deliveries with retry', async ({ page }) => {
    // Mock failed then successful delivery
    let callCount = 0;
    await page.route('**/api/notifications/send', route => {
      callCount++;
      if (callCount === 1) {
        // First call fails
        route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'SMTP server unavailable' })
        });
      } else {
        // Retry succeeds
        route.fulfill({
          status: 202,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'Notification queued for sending', notificationId: 124 })
        });
      }
    });

    await page.goto('http://localhost:4321/admin/notification-logs');

    // Find failed notification and retry
    await page.click('[data-testid="retry-notification-1"]');

    // Should show retry confirmation
    await expect(page.locator('[data-testid="retry-confirm"]')).toBeVisible();

    // Confirm retry
    await page.click('[data-testid="confirm-retry-btn"]');

    // Should show retry initiated message
    await expect(page.locator('[data-testid="retry-message"]')).toContainText('Retry initiated');

    // Log status should update to RETRYING then SENT
    await expect(page.locator('[data-testid="log-status-1"]')).toContainText('RETRYING');
  });

  test('should send manual notifications for existing assessments', async ({ page }) => {
    // Navigate to risk assessment details
    await page.goto('http://localhost:4321/risk-assessments/1');

    // Click send notification button
    await page.click('[data-testid="send-notification-btn"]');

    // Should show manual notification dialog
    await expect(page.locator('[data-testid="manual-notification-dialog"]')).toBeVisible();

    // Fill notification details
    await page.fill('input[name="recipientEmails"]', 'manager@company.com,ceo@company.com');
    await page.fill('input[name="subject"]', 'Urgent: Critical Risk Assessment Review Required');
    await page.fill('textarea[name="message"]', 'This high-risk assessment requires immediate attention and approval.');

    // Choose HTML format
    await page.check('input[name="useHtml"]');

    // Send notification
    await page.click('[data-testid="send-manual-notification-btn"]');

    // Should show success message
    await expect(page.locator('[data-testid="notification-sent"]')).toBeVisible();
    await expect(page.locator('[data-testid="notification-sent"]')).toContainText('Notification sent');
  });

  test('should validate notification configuration fields', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/notifications');

    await page.click('[data-testid="create-notification-config-btn"]');

    // Try to save empty form
    await page.click('[data-testid="save-notification-config-btn"]');

    // Should show validation errors
    await expect(page.locator('[data-testid="error-name"]')).toBeVisible();
    await expect(page.locator('[data-testid="error-recipients"]')).toBeVisible();

    // Fill invalid email addresses
    await page.fill('input[name="recipientEmails"]', 'invalid-email,another-invalid');

    // Should show email validation errors
    await expect(page.locator('[data-testid="error-recipients"]')).toContainText('valid email addresses');

    // Fix validation errors
    await page.fill('input[name="name"]', 'Valid Config');
    await page.fill('input[name="recipientEmails"]', 'valid1@example.com,valid2@example.com');

    // Should allow saving
    await page.click('[data-testid="save-notification-config-btn"]');
    await expect(page.locator('[data-testid="success-message"]')).toBeVisible();
  });

  test('should handle notification preferences and unsubscribe', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/notifications');

    // Edit existing notification config
    await page.click('[data-testid="edit-config-1"]');

    // Should show notification preferences
    await expect(page.locator('[data-testid="notification-preferences"]')).toBeVisible();

    // Configure notification timing
    await page.selectOption('[data-testid="notification-timing"]', 'immediate');

    // Configure notification frequency
    await page.selectOption('[data-testid="notification-frequency"]', 'all');

    // Add conditions for specific risk types
    await page.click('[data-testid="add-condition-btn"]');
    await page.selectOption('[data-testid="condition-field"]', 'category');
    await page.selectOption('[data-testid="condition-operator"]', 'contains');
    await page.fill('input[name="condition-value"]', 'security');

    // Save preferences
    await page.click('[data-testid="save-preferences-btn"]');

    // Should show preferences saved message
    await expect(page.locator('[data-testid="preferences-saved"]')).toBeVisible();
  });

  test('should show notification delivery statistics', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/notification-stats');

    // Should show delivery statistics
    await expect(page.locator('[data-testid="delivery-stats"]')).toBeVisible();

    // Should show success rate
    await expect(page.locator('[data-testid="success-rate"]')).toBeVisible();

    // Should show failed deliveries
    await expect(page.locator('[data-testid="failed-count"]')).toBeVisible();

    // Should show recent activity
    await expect(page.locator('[data-testid="recent-activity"]')).toBeVisible();

    // Should allow filtering by date range
    await page.fill('input[name="startDate"]', '2024-01-01');
    await page.fill('input[name="endDate"]', '2024-12-31');
    await page.click('[data-testid="filter-stats-btn"]');

    // Stats should update
    await expect(page.locator('[data-testid="stats-filtered"]')).toBeVisible();
  });
});