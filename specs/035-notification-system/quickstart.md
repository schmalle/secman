# Quick Start Guide: Outdated Asset Notification System

**Feature**: [035-notification-system](spec.md)
**Date**: 2025-10-26

## Overview

This guide provides a quick-start reference for developers implementing the notification system. For detailed design decisions, see [research.md](research.md). For data model details, see [data-model.md](data-model.md).

## Prerequisites

- Secman backend running with Feature 034 (Outdated Assets) operational
- UserMapping table populated (Feature 013/016)
- SMTP server access (credentials configured in environment)
- Java 21, Kotlin 2.2.21, Micronaut 4.10

---

## Development Setup

### 1. Environment Configuration

Add SMTP settings to `.env` or `application.yml`:

```yaml
# application.yml
mail:
  smtp:
    host: ${SMTP_HOST:smtp.example.com}
    port: ${SMTP_PORT:587}
    username: ${SMTP_USERNAME}
    password: ${SMTP_PASSWORD}
    auth: true
    starttls:
      enable: true
      required: true
    timeout: 30000
    connection-timeout: 30000
  from:
    email: ${MAIL_FROM:noreply@example.com}
    name: ${MAIL_FROM_NAME:Security Management System}
```

**Environment Variables**:
```bash
export SMTP_HOST=smtp.gmail.com
export SMTP_PORT=587
export SMTP_USERNAME=your-email@gmail.com
export SMTP_PASSWORD=your-app-password
export MAIL_FROM=noreply@secman.example.com
export MAIL_FROM_NAME="Secman Notifications"
```

### 2. Add Dependencies

Add to `build.gradle.kts` (backend):

```kotlin
dependencies {
    // Email support
    implementation("io.micronaut.email:micronaut-email-javamail")

    // Thymeleaf for email templates
    implementation("io.micronaut.views:micronaut-views-thymeleaf")

    // Existing dependencies
    implementation("io.micronaut:micronaut-runtime")
    implementation("io.micronaut.data:micronaut-data-hibernate-jpa")
    // ...
}
```

### 3. Create Database Tables

Tables are auto-created by Hibernate on first run. To manually create:

```sql
-- See data-model.md for complete SQL schema
CREATE TABLE notification_preference (...);
CREATE TABLE asset_reminder_state (...);
CREATE TABLE notification_log (...);
```

---

## Implementation Steps (TDD Order)

### Step 1: Domain Layer (JPA Entities)

**Order**: Create entities first (no tests needed for simple POJOs with JPA annotations)

**Files to create**:
1. `src/backendng/src/main/kotlin/com/secman/domain/NotificationType.kt` (enum)
2. `src/backendng/src/main/kotlin/com/secman/domain/NotificationPreference.kt`
3. `src/backendng/src/main/kotlin/com/secman/domain/AssetReminderState.kt`
4. `src/backendng/src/main/kotlin/com/secman/domain/NotificationLog.kt`

**Example** (NotificationPreference.kt):

```kotlin
package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "notification_preference")
data class NotificationPreference(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "user_id", nullable = false, unique = true)
    val userId: Long,

    @Column(name = "enable_new_vuln_notifications", nullable = false)
    val enableNewVulnNotifications: Boolean = false,

    @Column(name = "last_vuln_notification_sent_at")
    val lastVulnNotificationSentAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now()
)
```

### Step 2: Repository Layer

**Order**: Write contract tests first, then implement repositories

**Files to create**:
1. `src/backendng/src/test/kotlin/com/secman/contract/NotificationPreferenceRepositoryTest.kt` (write first)
2. `src/backendng/src/main/kotlin/com/secman/repository/NotificationPreferenceRepository.kt` (implement after test)
3. Repeat for `AssetReminderStateRepository`, `NotificationLogRepository`

**Example** (Repository interface):

```kotlin
package com.secman.repository

import com.secman.domain.NotificationPreference
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

@Repository
interface NotificationPreferenceRepository : JpaRepository<NotificationPreference, Long> {
    fun findByUserId(userId: Long): Optional<NotificationPreference>
}
```

### Step 3: Service Layer

**Order**: Write unit tests first (TDD), then implement services

**Priority**:
1. `EmailTemplateService` (renders Thymeleaf templates)
2. `ReminderStateService` (tracks reminder levels)
3. `NotificationLogService` (audit logging)
4. `NotificationService` (orchestration)

**Example** (Unit test for NotificationService):

```kotlin
package com.secman.service

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class NotificationServiceTest {
    private val emailTemplateService = mockk<EmailTemplateService>(relaxed = true)
    private val reminderStateService = mockk<ReminderStateService>(relaxed = true)
    private val notificationLogService = mockk<NotificationLogService>(relaxed = true)

    private val notificationService = NotificationService(
        emailTemplateService,
        reminderStateService,
        notificationLogService
    )

    @Test
    fun `should aggregate multiple assets for same owner into one email`() {
        // Given: 2 assets with same owner
        // When: processOutdatedAssets()
        // Then: verify only 1 email sent
        // (write test first, implement NotificationService to make it pass)
    }
}
```

### Step 4: Controller Layer

**Order**: Write contract tests first, then implement controllers

**Files to create**:
1. `src/backendng/src/test/kotlin/com/secman/contract/NotificationPreferenceContractTest.kt` (write first)
2. `src/backendng/src/main/kotlin/com/secman/controller/NotificationPreferenceController.kt` (implement)
3. Repeat for `NotificationLogController`

**Example** (Contract test):

```kotlin
package com.secman.contract

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import org.junit.jupiter.api.Test
import jakarta.inject.Inject
import kotlin.test.assertEquals

@MicronautTest
class NotificationPreferenceContractTest {
    @Inject
    lateinit var client: HttpClient

    @Test
    fun `GET notification-preferences should return 401 when not authenticated`() {
        val request = HttpRequest.GET<Any>("/api/notification-preferences")
        val response = client.toBlocking().exchange(request, String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
    }
}
```

### Step 5: CLI Command

**Order**: Write tests first, then implement CLI command

**Files to create**:
1. `src/cli/src/test/kotlin/com/secman/cli/commands/SendNotificationsCommandTest.kt` (write first)
2. `src/cli/src/main/kotlin/com/secman/cli/commands/SendNotificationsCommand.kt` (implement)

**Example** (CLI command with Picocli):

```kotlin
package com.secman.cli.commands

import picocli.CommandLine.*
import jakarta.inject.Inject

@Command(
    name = "send-notifications",
    description = ["Send email notifications for outdated assets and new vulnerabilities"]
)
class SendNotificationsCommand : Runnable {
    @Option(names = ["--dry-run"], description = ["Report planned notifications without sending"])
    var dryRun: Boolean = false

    @Option(names = ["--verbose"], description = ["Detailed logging"])
    var verbose: Boolean = false

    @Inject
    lateinit var notificationService: NotificationService

    override fun run() {
        println("Starting notification process...")
        val result = notificationService.processNotifications(dryRun, verbose)
        println("Summary: ${result.emailsSent} emails sent, ${result.failures} failures")
    }
}
```

### Step 6: Frontend Components

**Order**: Write Playwright E2E tests first, then implement React components

**Files to create**:
1. `src/frontend/tests/notification-preferences.spec.ts` (write first)
2. `src/frontend/src/components/NotificationPreferences.tsx` (implement)
3. `src/frontend/src/pages/notification-preferences.astro`
4. `src/frontend/src/components/NotificationLogTable.tsx` (ADMIN only)
5. `src/frontend/src/pages/admin/notification-logs.astro`

### Step 7: Email Templates

**Order**: Create templates, then write integration tests

**Files to create**:
1. `src/backendng/src/main/resources/email-templates/outdated-reminder-level1.html`
2. `src/backendng/src/main/resources/email-templates/outdated-reminder-level1.txt`
3. `src/backendng/src/main/resources/email-templates/outdated-reminder-level2.html`
4. `src/backendng/src/main/resources/email-templates/outdated-reminder-level2.txt`
5. `src/backendng/src/main/resources/email-templates/new-vulnerabilities.html`
6. `src/backendng/src/main/resources/email-templates/new-vulnerabilities.txt`

**Integration Test**:

```kotlin
@Test
fun `email template should render with valid context`() {
    val context = EmailContext(
        recipientEmail = "test@example.com",
        recipientName = "Test User",
        assets = listOf(/* test data */),
        totalCount = 5,
        criticalCount = 2,
        highCount = 3,
        mediumCount = 0,
        lowCount = 0,
        dashboardUrl = "https://dashboard.example.com"
    )

    val html = emailTemplateService.render("outdated-reminder-level1", context)
    assert(html.contains("Action Requested"))
    assert(html.contains("Test User"))
}
```

---

## Running the System

### Backend Server

```bash
cd src/backendng
./gradlew run
```

### CLI Command

```bash
# Dry run (reports planned notifications without sending)
./gradlew cli:run --args='send-notifications --dry-run'

# Actual run (sends emails)
./gradlew cli:run --args='send-notifications'

# Verbose mode
./gradlew cli:run --args='send-notifications --verbose'
```

### Frontend Dev Server

```bash
cd src/frontend
npm run dev
```

### Run Tests

```bash
# Backend tests
cd src/backendng
./gradlew test

# Frontend tests
cd src/frontend
npm test

# CLI tests
cd src/cli
./gradlew test
```

---

## Testing Strategy

### Unit Tests

**Focus**: Business logic in services

**Example**: `NotificationServiceTest.kt`
- Test email aggregation logic
- Test reminder level escalation
- Test duplicate prevention

### Contract Tests

**Focus**: API endpoint behavior

**Example**: `NotificationPreferenceContractTest.kt`
- Test HTTP status codes
- Test request/response formats
- Test authentication/authorization

### Integration Tests

**Focus**: Cross-component interactions

**Example**: `NotificationServiceIntegrationTest.kt`
- Test email sending end-to-end (use mock SMTP server)
- Test database queries with joins
- Test template rendering

### E2E Tests

**Focus**: User workflows in browser

**Example**: `notification-preferences.spec.ts` (Playwright)
- Test user toggling preferences
- Test ADMIN viewing audit logs
- Test CSV export

---

## Key Integration Points

### With Feature 034 (Outdated Assets)

**Query**:
```kotlin
val outdatedAssets = entityManager.createQuery(
    """
    SELECT oamv, a, um
    FROM OutdatedAssetMaterializedView oamv
    JOIN Asset a ON oamv.assetId = a.id
    JOIN UserMapping um ON a.owner = um.awsAccountId
    WHERE oamv.totalOverdueCount > 0
    """,
    Tuple::class.java
).resultList
```

### With Feature 013/016 (UserMapping)

**Email Resolution**:
```kotlin
val ownerEmail = userMappingRepository
    .findByAwsAccountId(asset.owner)
    ?.email
    ?: run {
        logger.warn("No email found for asset owner: ${asset.owner}")
        null
    }
```

---

## Common Issues & Solutions

### Issue: SMTP Authentication Failure

**Symptom**: `AuthenticationFailedException: 535 Authentication failed`

**Solution**:
- Check `SMTP_USERNAME` and `SMTP_PASSWORD` environment variables
- For Gmail: Enable "Less secure app access" or use App Password
- Test SMTP connection: `telnet smtp.gmail.com 587`

### Issue: Email Not Rendering

**Symptom**: Email shows raw HTML or blank content

**Solution**:
- Verify Thymeleaf dependency is in `build.gradle.kts`
- Check template file is in `src/main/resources/email-templates/`
- Verify template syntax (Thymeleaf requires `th:` prefix)

### Issue: Duplicate Emails Sent

**Symptom**: Same recipient receives multiple emails for same asset

**Solution**:
- Check `AssetReminderState.lastSentAt` is being updated after sending
- Verify duplicate prevention logic: `if (lastSentAt.date == today) skip`

### Issue: No Assets Found

**Symptom**: CLI command reports "0 assets processed"

**Solution**:
- Verify OutdatedAssetMaterializedView is populated (run refresh job)
- Check UserMapping has entries for asset owners
- Verify SQL join: `Asset.owner = UserMapping.awsAccountId`

---

## Performance Optimization Tips

1. **Batch Processing**: Process assets in batches of 1,000 to limit memory usage
2. **Connection Pooling**: Configure Micronaut SMTP connection pool (default is fine)
3. **Avoid N+1 Queries**: Use single JOIN query instead of individual lookups
4. **Async Logging**: Use `@Async` for NotificationLog creation (non-critical path)
5. **Cache UserMapping**: Cache email lookups during single CLI run

**Example** (Batch processing):

```kotlin
val batchSize = 1000
outdatedAssets.chunked(batchSize).forEach { batch ->
    processBatch(batch)
}
```

---

## Deployment Checklist

- [ ] SMTP credentials configured in environment
- [ ] Database migrations tested in dev environment
- [ ] Email templates reviewed and tested with real data
- [ ] All tests passing (unit, contract, integration, E2E)
- [ ] Performance tested with 10,000 assets
- [ ] Email deliverability tested (verify SPF/DKIM/DMARC)
- [ ] ADMIN audit log UI tested with different filters
- [ ] CLI command added to cron job (optional, for automated runs)

---

## Monitoring & Observability

### Metrics to Track

- Emails sent per run (success rate)
- Email failures (by error type)
- CLI execution time
- Assets processed per run
- Notification preferences update rate

### Logging

**Important Logs**:
- Email send failures (with error message)
- Missing UserMapping warnings
- Template rendering errors
- SMTP connection failures

**Example** (Structured logging):

```kotlin
logger.info("Email sent successfully", mapOf(
    "recipientEmail" to recipientEmail,
    "assetId" to assetId,
    "notificationType" to notificationType
))
```

---

## Future Enhancements (Out of Scope)

- Notification templates for other events (asset created, vuln resolved)
- Multi-language support (EN, DE, ES, FR)
- Alternative delivery channels (Slack, Teams, SMS)
- User-configurable notification frequency (daily/weekly digest)
- Email bounce handling (mark invalid emails)
- Retention policy automation (auto-delete old logs)

---

## Resources

- **API Documentation**: See `contracts/` directory for OpenAPI specs
- **Data Model**: See [data-model.md](data-model.md)
- **Research**: See [research.md](research.md) for design decisions
- **Feature Spec**: See [spec.md](spec.md) for requirements

---

## Support

For questions or issues:
1. Check this guide first
2. Review [research.md](research.md) for design rationale
3. Check test files for usage examples
4. Contact the development team
