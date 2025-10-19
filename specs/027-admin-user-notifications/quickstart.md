# Quickstart Guide: Admin User Notification System

**Feature**: 027-admin-user-notifications
**Created**: 2025-10-19

## Overview

This guide covers setup, configuration, and testing of the admin user notification system.

## Prerequisites

- Secman backend running (Micronaut 4.4, Kotlin 2.1.0)
- Secman frontend running (Astro 5.14, React 19)
- MariaDB 11.4 database accessible
- SMTP server configured (for email sending)
- ADMIN user account for testing

## Backend Setup

### 1. Add Micronaut Email Dependency

Add to `src/backendng/build.gradle.kts`:

```kotlin
dependencies {
    // Existing dependencies...

    // Email support
    implementation("io.micronaut.email:micronaut-email-javamail")
}
```

### 2. Configure SMTP Settings

Add to `src/backendng/src/main/resources/application.yml`:

```yaml
micronaut:
  email:
    from:
      email: noreply@secman.local
      name: Secman Notifications
    javamail:
      properties:
        mail.smtp.host: localhost
        mail.smtp.port: 1025
        mail.smtp.auth: false
        mail.smtp.starttls.enable: false
        # For production with authentication:
        # mail.smtp.auth: true
        # mail.smtp.starttls.enable: true
        # mail.smtp.user: ${SMTP_USER}
        # mail.smtp.password: ${SMTP_PASSWORD}
```

**Note**: For local development, use MailHog or similar SMTP testing tool:
```bash
# Install MailHog (macOS)
brew install mailhog
mailhog

# Access web UI at http://localhost:8025
```

### 3. Database Migration

Hibernate auto-migration will create the new tables on application startup:
- `system_settings` - Configuration storage
- `email_notification_events` - Audit trail

No manual migration scripts needed. Tables will be created automatically when entities are detected.

### 4. Seed Initial Settings

Add an `@PostConstruct` bean to seed default settings on first startup:

```kotlin
@Singleton
class SystemSettingsInitializer(
    private val systemSettingRepository: SystemSettingRepository
) {
    @PostConstruct
    fun initializeDefaultSettings() {
        if (systemSettingRepository.count() == 0) {
            systemSettingRepository.saveAll(listOf(
                SystemSetting(
                    key = "notify_admins_on_new_user",
                    value = "true",
                    description = "Enable/disable email notifications to admins for new user registrations",
                    updatedBy = "system"
                ),
                SystemSetting(
                    key = "notification_sender_email",
                    value = "noreply@secman.local",
                    description = "Email address used as sender for notification emails",
                    updatedBy = "system"
                )
            ))
        }
    }
}
```

### 5. Verify Backend

Start the backend and verify:

```bash
cd src/backendng
./gradlew run

# Check logs for:
# - "Created table system_settings"
# - "Created table email_notification_events"
# - "Initialized default system settings"
```

Test API endpoint:
```bash
# Get notification settings (requires ADMIN JWT token)
curl -X GET http://localhost:8080/api/settings/notifications \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# Expected response:
# {"enabled":true,"senderEmail":"noreply@secman.local"}
```

## Frontend Setup

### 1. Install Dependencies (if needed)

```bash
cd src/frontend
npm install
# All required dependencies (React 19, Axios, Bootstrap 5.3) should already be present
```

### 2. Add Notification Settings Component

Create `src/frontend/src/components/NotificationSettings.tsx` (see implementation in plan.md).

### 3. Update Admin Settings Page

Modify `src/frontend/src/pages/admin/settings.astro` to include the NotificationSettings component:

```astro
---
import Layout from '../../layouts/Layout.astro';
import NotificationSettings from '../../components/NotificationSettings';
---

<Layout title="Admin Settings">
  <div class="container mt-4">
    <h1>Admin Settings</h1>

    <!-- Existing admin settings sections... -->

    <!-- NEW: Notification Settings -->
    <NotificationSettings client:load />
  </div>
</Layout>
```

### 4. Verify Frontend

Start the frontend:
```bash
npm run dev
# Frontend available at http://localhost:4321
```

Navigate to Admin Settings:
1. Log in as ADMIN user
2. Navigate to Admin → Settings
3. Verify "User Notification Settings" card appears
4. Toggle notification enabled/disabled
5. Change sender email address
6. Click "Save Settings"
7. Refresh page - settings should persist

## Testing

### Manual Testing Workflow

#### Test 1: Enable/Disable Notifications

1. **Setup**: Log in as ADMIN user, open Admin Settings
2. **Action**: Disable notifications, save settings
3. **Verify**: Create a new user via "Manage Users" UI
4. **Expected**: No notification emails sent
5. **Action**: Enable notifications, save settings
6. **Verify**: Create another new user
7. **Expected**: All ADMIN users receive notification emails

#### Test 2: Sender Email Configuration

1. **Setup**: Enable notifications
2. **Action**: Change sender email to custom address (e.g., admin@company.com)
3. **Verify**: Create a new user
4. **Expected**: Notification emails have "From: admin@company.com"

#### Test 3: Manual User Creation

1. **Setup**: Enable notifications
2. **Action**: Create user "test.user" with email "test@example.com" via "Manage Users" UI
3. **Expected**: All ADMIN users receive email with:
   - Subject: "New User Registered: test.user"
   - Body includes: username, email, timestamp, registration method (Manual), creator username

#### Test 4: OAuth User Registration

1. **Setup**: Enable notifications, configure OAuth (GitHub/Google)
2. **Action**: Complete OAuth registration flow for new user
3. **Expected**: All ADMIN users receive email with:
   - Subject: "New User Registered: [oauth-username]"
   - Body includes: username, email, timestamp, registration method (GitHub/Google), no creator (OAuth self-registration)

#### Test 5: Non-Blocking Behavior

1. **Setup**: Stop SMTP server (simulate email failure)
2. **Action**: Create new user via "Manage Users" UI
3. **Expected**:
   - User creation succeeds (returns 200 OK)
   - User appears in user list immediately
   - Backend logs show email failure (not propagated to client)

#### Test 6: Audit Trail

1. **Setup**: Enable notifications
2. **Action**: Create 3 new users
3. **Verify**: Query `email_notification_events` table
4. **Expected**:
   - N events per user (N = number of ADMIN users)
   - Each event has: recipient, subject, newUsername, sendStatus, timestamp

### Automated Testing

#### Backend Contract Tests

```kotlin
@MicronautTest
class NotificationSettingsContractTest {
    @Inject
    lateinit var client: HttpClient

    @Test
    fun `GET notifications settings returns 200 with settings`() {
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/settings/notifications")
                .bearerAuth(adminToken),
            NotificationSettingsDto::class.java
        )

        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())
        assertTrue(response.body()!!.enabled)
    }

    @Test
    fun `PUT notifications settings updates and returns 200`() {
        val request = NotificationSettingsDto(
            enabled = false,
            senderEmail = "custom@example.com"
        )

        val response = client.toBlocking().exchange(
            HttpRequest.PUT("/api/settings/notifications", request)
                .bearerAuth(adminToken),
            NotificationSettingsDto::class.java
        )

        assertEquals(HttpStatus.OK, response.status)
        assertEquals(false, response.body()!!.enabled)
        assertEquals("custom@example.com", response.body()!!.senderEmail)
    }

    @Test
    fun `PUT notifications settings returns 403 for non-ADMIN`() {
        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.PUT("/api/settings/notifications", NotificationSettingsDto(...))
                    .bearerAuth(userToken),
                String::class.java
            )
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }
}
```

#### Backend Integration Tests

```kotlin
@MicronautTest
class UserCreationNotificationTest {
    @Inject
    lateinit var userService: UserService

    @Inject
    lateinit var emailNotificationEventRepository: EmailNotificationEventRepository

    @MockBean(MailSender::class)
    fun mailSender(): MailSender<*, *> = mockk()

    @Test
    fun `createUser sends notifications to all ADMIN users`() {
        // Arrange
        val adminCount = userRepository.findByRolesContaining("ADMIN").size

        // Act
        val user = userService.createUser(CreateUserDto(...))

        // Wait for async email sending (or use awaitility)
        Thread.sleep(1000)

        // Assert
        val events = emailNotificationEventRepository.findByNewUsername(user.username)
        assertEquals(adminCount, events.size)
        events.forEach { event ->
            assertEquals("sent", event.sendStatus)
            assertEquals(user.username, event.newUsername)
            assertEquals("Manual", event.registrationMethod)
        }
    }

    @Test
    fun `createUser succeeds even when email sending fails`() {
        // Arrange
        every { mailSender.send(any()) } throws RuntimeException("SMTP error")

        // Act
        val user = userService.createUser(CreateUserDto(...))

        // Assert
        assertNotNull(user.id) // User created successfully

        // Wait and verify failure logged
        Thread.sleep(1000)
        val events = emailNotificationEventRepository.findByNewUsername(user.username)
        events.forEach { event ->
            assertEquals("failed", event.sendStatus)
            assertTrue(event.failureReason?.contains("SMTP error") == true)
        }
    }
}
```

#### Frontend E2E Tests

```typescript
// src/frontend/tests/e2e/admin-notifications.spec.ts
import { test, expect } from '@playwright/test';

test.describe('Admin Notification Settings', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.fill('input[name="username"]', 'admin');
    await page.fill('input[name="password"]', 'admin123');
    await page.click('button[type="submit"]');
    await page.waitForURL('/dashboard');
  });

  test('should display notification settings in admin page', async ({ page }) => {
    await page.goto('/admin/settings');

    const card = page.locator('text=User Notification Settings');
    await expect(card).toBeVisible();

    const checkbox = page.locator('input[type="checkbox"]').first();
    await expect(checkbox).toBeVisible();

    const emailInput = page.locator('input[type="email"]').first();
    await expect(emailInput).toBeVisible();
  });

  test('should toggle notifications on and off', async ({ page }) => {
    await page.goto('/admin/settings');

    const checkbox = page.locator('input[type="checkbox"]').first();
    const initialState = await checkbox.isChecked();

    await checkbox.click();
    await page.click('button:has-text("Save Settings")');

    await page.waitForSelector('text=Settings saved successfully');
    await page.reload();

    const newState = await checkbox.isChecked();
    expect(newState).toBe(!initialState);
  });

  test('should update sender email address', async ({ page }) => {
    await page.goto('/admin/settings');

    const emailInput = page.locator('input[type="email"]').first();
    await emailInput.fill('custom@secman.com');
    await page.click('button:has-text("Save Settings")');

    await page.waitForSelector('text=Settings saved successfully');
    await page.reload();

    await expect(emailInput).toHaveValue('custom@secman.com');
  });
});
```

## Troubleshooting

### Issue: Emails not being sent

**Check**:
1. SMTP server running: `telnet localhost 1025`
2. Backend logs for email errors
3. Notification settings enabled: `curl http://localhost:8080/api/settings/notifications`
4. ADMIN users have valid email addresses in database

### Issue: User creation fails

**Check**:
1. Email sending should NOT block user creation
2. Check backend logs for transaction errors
3. Verify `@Async` and `@Transactional(propagation = REQUIRES_NEW)` annotations
4. Verify try-catch isolation in `EmailNotificationService`

### Issue: Settings not persisting

**Check**:
1. Database connection active
2. `system_settings` table exists
3. Check backend logs for Hibernate errors
4. Verify frontend saves settings (network tab shows PUT request)

### Issue: Frontend component not rendering

**Check**:
1. Component imported with `client:load` directive in Astro
2. Browser console for React errors
3. API endpoint returning 200 OK for GET request
4. JWT token valid and user has ADMIN role

## Performance Monitoring

### Key Metrics

Monitor these metrics in production:

1. **Email delivery success rate**: Target 99%
   ```sql
   SELECT
     COUNT(*) as total,
     SUM(CASE WHEN sendStatus = 'sent' THEN 1 ELSE 0 END) as sent,
     SUM(CASE WHEN sendStatus = 'failed' THEN 1 ELSE 0 END) as failed
   FROM email_notification_events
   WHERE timestamp > DATE_SUB(NOW(), INTERVAL 24 HOUR);
   ```

2. **User creation latency**: Target <3 seconds
   - Monitor `UserService.createUser()` execution time
   - Should not be affected by email sending

3. **Audit log retention**: Should be ~30 days
   ```sql
   SELECT
     MIN(timestamp) as oldest_record,
     MAX(timestamp) as newest_record,
     COUNT(*) as total_records
   FROM email_notification_events;
   ```

4. **Cleanup task execution**: Daily at 2 AM
   - Check backend logs for "Cleanup completed, deleted N records"

## Production Deployment Checklist

- [ ] SMTP server configured with authentication
- [ ] Default sender email configured (not localhost)
- [ ] ADMIN users have valid email addresses
- [ ] Notification settings initialized (enabled by default)
- [ ] Email delivery monitoring enabled
- [ ] Cleanup task verified (runs daily)
- [ ] TLS/SSL enabled for SMTP (if required)
- [ ] Rate limiting configured (if using external SMTP)
- [ ] Backup strategy for audit logs (before 30-day deletion)

## Next Steps

After verifying the feature works:

1. Run `/speckit.tasks` to generate implementation task breakdown
2. Follow TDD approach: Write tests first, then implementation
3. Implement backend entities, repositories, services
4. Implement frontend components
5. Write integration tests
6. Deploy to staging for E2E testing
7. Deploy to production with monitoring

## Support

For questions or issues:
- Check backend logs: `src/backendng/logs/`
- Check frontend console: Browser DevTools
- Review API contracts: `specs/027-admin-user-notifications/contracts/`
- Review data model: `specs/027-admin-user-notifications/data-model.md`
