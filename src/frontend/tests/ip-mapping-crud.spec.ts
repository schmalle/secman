import { test, expect } from '@playwright/test';

/**
 * E2E Tests for IP Address Mapping CRUD Operations
 * Feature: 020-i-want-to (IP Address Mapping)
 *
 * Covers:
 * - Single IP address mappings (192.168.1.100)
 * - CIDR range mappings (10.0.0.0/24)
 * - Dash range mappings (172.16.0.1-172.16.0.100)
 * - Combined AWS account + IP mappings
 * - List, filter, pagination
 * - Create, edit, delete operations
 * - Form validation
 * - Toast notifications
 */
test.describe('IP Address Mapping Management', () => {

  test.beforeEach(async ({ page }) => {
    // Login as admin user
    await page.goto('http://localhost:4321/login');
    await page.fill('input[type="text"]', 'admin');
    await page.fill('input[type="password"]', 'admin123');
    await page.click('button[type="submit"]');
    await expect(page).toHaveURL(/.*\/(dashboard|admin)/);
  });

  test('should navigate to user mappings management page', async ({ page }) => {
    // Navigate to Admin → User Mappings
    await page.goto('http://localhost:4321/admin/user-mappings');

    // Should show page header
    await expect(page.locator('h2')).toContainText('User Mapping Management');

    // Should show two tabs
    await expect(page.locator('.nav-tabs')).toContainText('Bulk Upload');
    await expect(page.locator('.nav-tabs')).toContainText('Manage Mappings');

    // Manage Mappings tab should be active by default
    const manageMappingsTab = page.locator('.nav-tabs .nav-link:has-text("Manage Mappings")');
    await expect(manageMappingsTab).toHaveClass(/active/);
  });

  test('should create single IP address mapping', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/user-mappings');

    // Mock successful create API response
    await page.route('**/api/user-mappings', async route => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            id: 1,
            email: 'testuser@example.com',
            ipAddress: '192.168.1.100',
            ipRangeType: 'SINGLE',
            ipCount: 1,
            domain: 'example.com',
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
          })
        });
      }
    });

    // Mock list API response (empty initially)
    await page.route('**/api/user-mappings?page=0&size=20', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            content: [],
            totalElements: 0,
            totalPages: 0,
            page: 0,
            size: 20
          })
        });
      }
    });

    // Click "Create New Mapping" button
    await page.click('button:has-text("Create New Mapping")');

    // Should show the form
    await expect(page.locator('.card-header h5')).toContainText('Create IP Mapping');

    // Fill out the form
    await page.fill('input[id="email"]', 'testuser@example.com');
    await page.fill('input[id="ipAddress"]', '192.168.1.100');
    await page.fill('input[id="domain"]', 'example.com');

    // Submit the form
    await page.click('button[type="submit"]:has-text("Create Mapping")');

    // Should show success toast
    await expect(page.locator('.toast.show.bg-success')).toBeVisible();
    await expect(page.locator('.toast-body')).toContainText('Mapping created successfully');

    // Should return to list view
    await expect(page.locator('button:has-text("Create New Mapping")')).toBeVisible();
  });

  test('should create CIDR range mapping', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/user-mappings');

    // Mock successful create API response
    await page.route('**/api/user-mappings', async route => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            id: 2,
            email: 'admin@example.com',
            ipAddress: '10.0.0.0/24',
            ipRangeType: 'CIDR',
            ipCount: 256,
            domain: 'example.com',
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
          })
        });
      }
    });

    await page.click('button:has-text("Create New Mapping")');

    // Fill form with CIDR notation
    await page.fill('input[id="email"]', 'admin@example.com');
    await page.fill('input[id="ipAddress"]', '10.0.0.0/24');
    await page.fill('input[id="domain"]', 'example.com');

    await page.click('button[type="submit"]:has-text("Create Mapping")');

    // Should show success toast
    await expect(page.locator('.toast.show.bg-success')).toBeVisible();
    await expect(page.locator('.toast-body')).toContainText('Mapping created successfully');
  });

  test('should create dash range mapping', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/user-mappings');

    // Mock successful create API response
    await page.route('**/api/user-mappings', async route => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            id: 3,
            email: 'team@example.com',
            ipAddress: '172.16.0.1-172.16.0.100',
            ipRangeType: 'DASH_RANGE',
            ipCount: 100,
            domain: 'team.example.com',
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
          })
        });
      }
    });

    await page.click('button:has-text("Create New Mapping")');

    // Fill form with dash range
    await page.fill('input[id="email"]', 'team@example.com');
    await page.fill('input[id="ipAddress"]', '172.16.0.1-172.16.0.100');
    await page.fill('input[id="domain"]', 'team.example.com');

    await page.click('button[type="submit"]:has-text("Create Mapping")');

    // Should show success toast
    await expect(page.locator('.toast.show.bg-success')).toBeVisible();
    await expect(page.locator('.toast-body')).toContainText('Mapping created successfully');
  });

  test('should create combined AWS account + IP mapping', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/user-mappings');

    // Mock successful create API response
    await page.route('**/api/user-mappings', async route => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            id: 4,
            email: 'hybrid@example.com',
            awsAccountId: '123456789012',
            ipAddress: '192.168.1.0/24',
            ipRangeType: 'CIDR',
            ipCount: 256,
            domain: 'hybrid.example.com',
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
          })
        });
      }
    });

    await page.click('button:has-text("Create New Mapping")');

    // Fill form with both AWS account and IP address
    await page.fill('input[id="email"]', 'hybrid@example.com');
    await page.fill('input[id="awsAccountId"]', '123456789012');
    await page.fill('input[id="ipAddress"]', '192.168.1.0/24');
    await page.fill('input[id="domain"]', 'hybrid.example.com');

    await page.click('button[type="submit"]:has-text("Create Mapping")');

    // Should show success toast
    await expect(page.locator('.toast.show.bg-success')).toBeVisible();
    await expect(page.locator('.toast-body')).toContainText('Mapping created successfully');
  });

  test('should display IP mappings table with badges', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/user-mappings');

    // Mock list API response with sample data
    await page.route('**/api/user-mappings?page=0&size=20', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            content: [
              {
                id: 1,
                email: 'user1@example.com',
                ipAddress: '192.168.1.100',
                ipRangeType: 'SINGLE',
                ipCount: 1,
                domain: 'example.com',
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString()
              },
              {
                id: 2,
                email: 'user2@example.com',
                ipAddress: '10.0.0.0/24',
                ipRangeType: 'CIDR',
                ipCount: 256,
                domain: 'example.com',
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString()
              },
              {
                id: 3,
                email: 'user3@example.com',
                ipAddress: '172.16.0.1-172.16.0.100',
                ipRangeType: 'DASH_RANGE',
                ipCount: 100,
                domain: 'team.example.com',
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString()
              }
            ],
            totalElements: 3,
            totalPages: 1,
            page: 0,
            size: 20
          })
        });
      }
    });

    // Reload to trigger data fetch
    await page.reload();

    // Should show table with data
    await expect(page.locator('table.table')).toBeVisible();

    // Should show all three mappings
    await expect(page.locator('table tbody tr')).toHaveCount(3);

    // Should show badge for Single IP (blue badge)
    await expect(page.locator('.badge.bg-primary:has-text("Single IP")')).toBeVisible();

    // Should show badge for CIDR Range (cyan badge)
    await expect(page.locator('.badge.bg-info:has-text("CIDR Range")')).toBeVisible();

    // Should show badge for Dash Range (yellow badge)
    await expect(page.locator('.badge.bg-warning:has-text("Dash Range")')).toBeVisible();

    // Should format IP counts correctly
    await expect(page.locator('table')).toContainText('1 IP');
    await expect(page.locator('table')).toContainText('256 IPs');
    await expect(page.locator('table')).toContainText('100 IPs');
  });

  test('should filter mappings by email', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/user-mappings');

    // Mock filtered API response
    await page.route('**/api/user-mappings?page=0&size=20&email=alice@example.com', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            content: [
              {
                id: 1,
                email: 'alice@example.com',
                ipAddress: '192.168.1.0/24',
                ipRangeType: 'CIDR',
                ipCount: 256,
                domain: 'alice.example.com',
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString()
              }
            ],
            totalElements: 1,
            totalPages: 1,
            page: 0,
            size: 20
          })
        });
      }
    });

    // Fill email filter
    await page.fill('input[placeholder="Filter by email..."]', 'alice@example.com');

    // Click search button
    await page.click('button:has(i.bi-search)');

    // Should show filtered results
    await expect(page.locator('table tbody tr')).toHaveCount(1);
    await expect(page.locator('table')).toContainText('alice@example.com');
  });

  test('should edit existing mapping', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/user-mappings');

    // Mock list API response
    await page.route('**/api/user-mappings?page=0&size=20', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            content: [
              {
                id: 1,
                email: 'testuser@example.com',
                ipAddress: '192.168.1.0/24',
                ipRangeType: 'CIDR',
                ipCount: 256,
                domain: 'example.com',
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString()
              }
            ],
            totalElements: 1,
            totalPages: 1,
            page: 0,
            size: 20
          })
        });
      }
    });

    // Mock update API response
    await page.route('**/api/user-mappings/1', async route => {
      if (route.request().method() === 'PUT') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            id: 1,
            email: 'testuser@example.com',
            ipAddress: '192.168.1.0/25',
            ipRangeType: 'CIDR',
            ipCount: 128,
            domain: 'updated.example.com',
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
          })
        });
      }
    });

    await page.reload();

    // Click edit button (pencil icon)
    await page.click('button[title="Edit mapping"] i.bi-pencil');

    // Should show edit form
    await expect(page.locator('.card-header h5')).toContainText('Edit IP Mapping');

    // Email field should be disabled
    await expect(page.locator('input[id="email"]')).toBeDisabled();

    // Modify IP address
    await page.fill('input[id="ipAddress"]', '192.168.1.0/25');
    await page.fill('input[id="domain"]', 'updated.example.com');

    // Submit update
    await page.click('button[type="submit"]:has-text("Update Mapping")');

    // Should show success toast
    await expect(page.locator('.toast.show.bg-success')).toBeVisible();
    await expect(page.locator('.toast-body')).toContainText('Mapping updated successfully');
  });

  test('should delete mapping with confirmation', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/user-mappings');

    // Mock list API response
    await page.route('**/api/user-mappings?page=0&size=20', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            content: [
              {
                id: 1,
                email: 'deleteme@example.com',
                ipAddress: '192.168.99.99',
                ipRangeType: 'SINGLE',
                ipCount: 1,
                domain: 'example.com',
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString()
              }
            ],
            totalElements: 1,
            totalPages: 1,
            page: 0,
            size: 20
          })
        });
      }
    });

    // Mock delete API response
    await page.route('**/api/user-mappings/1', async route => {
      if (route.request().method() === 'DELETE') {
        await route.fulfill({ status: 204 });
      }
    });

    await page.reload();

    // Click delete button (trash icon) - first click shows confirmation
    await page.click('button[title="Delete mapping"] i.bi-trash');

    // Should show Confirm/Cancel buttons
    await expect(page.locator('button:has-text("Confirm")')).toBeVisible();
    await expect(page.locator('button:has-text("Cancel")')).toBeVisible();

    // Click Confirm to delete
    await page.click('button:has-text("Confirm")');

    // Should show success toast
    await expect(page.locator('.toast.show.bg-success')).toBeVisible();
    await expect(page.locator('.toast-body')).toContainText('Mapping deleted successfully');
  });

  test('should validate form inputs', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/user-mappings');

    await page.click('button:has-text("Create New Mapping")');

    // Try to submit empty form
    await page.click('button[type="submit"]:has-text("Create Mapping")');

    // Should show validation error for email
    await expect(page.locator('.alert.alert-danger')).toBeVisible();
    await expect(page.locator('.alert.alert-danger')).toContainText('Valid email address is required');

    // Fill invalid email
    await page.fill('input[id="email"]', 'not-an-email');
    await page.click('button[type="submit"]:has-text("Create Mapping")');
    await expect(page.locator('.alert.alert-danger')).toContainText('Valid email address is required');

    // Fill valid email but no AWS account or IP
    await page.fill('input[id="email"]', 'valid@example.com');
    await page.click('button[type="submit"]:has-text("Create Mapping")');
    await expect(page.locator('.alert.alert-danger')).toContainText('At least one of AWS Account ID or IP Address must be provided');

    // Fill invalid AWS account ID (not 12 digits)
    await page.fill('input[id="awsAccountId"]', '123');
    await page.click('button[type="submit"]:has-text("Create Mapping")');
    await expect(page.locator('.alert.alert-danger')).toContainText('AWS Account ID must be exactly 12 digits');

    // Fill invalid IP address
    await page.fill('input[id="awsAccountId"]', '');
    await page.fill('input[id="ipAddress"]', '999.999.999.999');
    await page.click('button[type="submit"]:has-text("Create Mapping")');
    await expect(page.locator('.alert.alert-danger')).toContainText('IP address must be in format');
  });

  test('should show pagination controls', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/user-mappings');

    // Mock list API response with multiple pages
    await page.route('**/api/user-mappings?page=0&size=20', async route => {
      if (route.request().method() === 'GET') {
        const content = Array.from({ length: 20 }, (_, i) => ({
          id: i + 1,
          email: `user${i + 1}@example.com`,
          ipAddress: `192.168.1.${i + 1}`,
          ipRangeType: 'SINGLE',
          ipCount: 1,
          domain: 'example.com',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString()
        }));

        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            content,
            totalElements: 156,
            totalPages: 8,
            page: 0,
            size: 20
          })
        });
      }
    });

    await page.reload();

    // Should show pagination controls
    await expect(page.locator('.pagination')).toBeVisible();

    // Should show "Showing 1 to 20 of 156 entries"
    await expect(page.locator('text=Showing 1 to 20 of 156 entries')).toBeVisible();

    // Should show page numbers
    await expect(page.locator('.pagination .page-item')).toHaveCount({ min: 5 }); // At least 5 page items (prev, numbers, next)

    // Previous button should be disabled on first page
    await expect(page.locator('.pagination .page-item:first-child')).toHaveClass(/disabled/);
  });

  test('should dismiss toast notifications', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/user-mappings');

    // Mock successful create to trigger toast
    await page.route('**/api/user-mappings', async route => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            id: 1,
            email: 'testuser@example.com',
            ipAddress: '192.168.1.100',
            ipRangeType: 'SINGLE',
            ipCount: 1,
            domain: 'example.com',
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
          })
        });
      }
    });

    await page.click('button:has-text("Create New Mapping")');
    await page.fill('input[id="email"]', 'testuser@example.com');
    await page.fill('input[id="ipAddress"]', '192.168.1.100');
    await page.click('button[type="submit"]:has-text("Create Mapping")');

    // Toast should be visible
    await expect(page.locator('.toast.show.bg-success')).toBeVisible();

    // Click close button
    await page.click('.toast .btn-close');

    // Toast should disappear
    await expect(page.locator('.toast.show.bg-success')).not.toBeVisible();
  });

  test('should handle API errors gracefully', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/user-mappings');

    // Mock failed create API response
    await page.route('**/api/user-mappings', async route => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({
            error: 'Validation Error',
            message: 'Invalid IP address format: must be IPv4 address'
          })
        });
      }
    });

    await page.click('button:has-text("Create New Mapping")');
    await page.fill('input[id="email"]', 'testuser@example.com');
    await page.fill('input[id="ipAddress"]', '192.168.1.100');
    await page.click('button[type="submit"]:has-text("Create Mapping")');

    // Should show error in form
    await expect(page.locator('.alert.alert-danger')).toBeVisible();
    await expect(page.locator('.alert.alert-danger')).toContainText('Invalid IP address format');
  });

  test('should cancel form and return to list', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/user-mappings');

    await page.click('button:has-text("Create New Mapping")');

    // Should show form
    await expect(page.locator('.card-header h5')).toContainText('Create IP Mapping');

    // Click Cancel button
    await page.click('button:has-text("Cancel")');

    // Should return to list view
    await expect(page.locator('button:has-text("Create New Mapping")')).toBeVisible();
    await expect(page.locator('.card-header h5')).not.toContainText('Create IP Mapping');
  });

  test('should switch between Bulk Upload and Manage Mappings tabs', async ({ page }) => {
    await page.goto('http://localhost:4321/admin/user-mappings');

    // Should be on Manage Mappings tab by default
    const manageMappingsTab = page.locator('.nav-tabs .nav-link:has-text("Manage Mappings")');
    await expect(manageMappingsTab).toHaveClass(/active/);

    // Click Bulk Upload tab
    await page.click('.nav-tabs .nav-link:has-text("Bulk Upload")');

    // Should show upload interface
    const bulkUploadTab = page.locator('.nav-tabs .nav-link:has-text("Bulk Upload")');
    await expect(bulkUploadTab).toHaveClass(/active/);

    // Should show upload cards
    await expect(page.locator('text=Excel Upload')).toBeVisible();
    await expect(page.locator('text=CSV Upload')).toBeVisible();

    // Switch back to Manage Mappings
    await page.click('.nav-tabs .nav-link:has-text("Manage Mappings")');

    // Should show management interface
    await expect(manageMappingsTab).toHaveClass(/active/);
    await expect(page.locator('button:has-text("Create New Mapping")')).toBeVisible();
  });
});
