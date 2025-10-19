# Implementation Plan: Admin User Notification System

**Branch**: `027-admin-user-notifications` | **Date**: 2025-10-19 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/027-admin-user-notifications/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Implement an email notification system that sends alerts to all ADMIN users when new accounts are created (via "Manage Users" UI or OAuth). The system includes:
- Admin UI configuration to enable/disable notifications (enabled by default)
- Configurable sender email address (default: noreply@domain)
- HTML-formatted notification emails with user details
- Non-blocking email delivery (user creation never fails due to email issues)
- Single-attempt delivery with comprehensive logging
- 30-day audit log retention with automatic cleanup

**Technical Approach**: Extend existing backend notification infrastructure, add SystemSetting entity for configuration persistence, create EmailNotificationEvent entity for audit trail, implement async email service with failure isolation, add Admin UI configuration panel.

## Technical Context

**Language/Version**: Kotlin 2.1.0 / Java 21 (backend), TypeScript/JavaScript (frontend - Astro 5.14 + React 19)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, Micronaut Email (for SMTP), Astro, React 19, Bootstrap 5.3
**Storage**: MariaDB 11.4 via Hibernate JPA with auto-migration
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Linux server (backend), Modern web browsers (frontend)
**Project Type**: web (frontend + backend)
**Performance Goals**: Email delivery within 2 minutes, user creation completes in <3 seconds (non-blocking), configuration changes in <30 seconds
**Constraints**: Zero user creation failures from email errors, 99% email delivery success, 30-day audit retention
**Scale/Scope**: ~100 ADMIN users (recipients), ~1000 new users/month (notifications), ~30K audit records/month

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Principle I: Security-First ✅

- **File uploads**: N/A (no file uploads in this feature)
- **Input sanitization**: Email addresses validated (RFC format), sender address validated
- **RBAC enforcement**: @Secured(SecurityRule.IS_AUTHENTICATED) on all endpoints, ADMIN role checks for configuration
- **Sensitive data**: No sensitive data in emails (public user info only), failures logged without email content
- **Authentication tokens**: Uses existing JWT sessionStorage pattern

**Status**: PASS - All security requirements met

### Principle II: Test-Driven Development ✅

- **Contract tests**: Required for new endpoints (GET/PUT /api/settings/notifications, notification trigger points)
- **Integration tests**: User creation → email sending flow, configuration persistence
- **Unit tests**: Email service logic, template rendering, recipient resolution
- **Coverage target**: ≥80%
- **Testing stack**: JUnit 5 + MockK (backend), Playwright (frontend)

**Status**: PASS - TDD workflow enforced

### Principle III: API-First ✅

- **RESTful design**: GET/PUT /api/settings/notifications for configuration
- **OpenAPI documentation**: Will be maintained
- **Error handling**: Consistent error responses with HTTP status codes
- **Backward compatibility**: New endpoints, no breaking changes

**Status**: PASS - API-first principles followed

### Principle IV: User-Requested Testing ✅

- **Test planning**: Tests will be prepared per TDD principle (written first)
- **User request**: Not explicitly requested, but TDD mandates tests before implementation
- **Framework**: JUnit 5 + MockK + Playwright available

**Status**: PASS - TDD principle ensures tests written, no proactive test planning

### Principle V: Role-Based Access Control ✅

- **@Secured annotations**: All endpoints secured
- **Role checks**: ADMIN role required for configuration endpoints
- **Frontend role checks**: UI elements hidden for non-ADMIN users
- **Service layer authorization**: Email recipients filtered by ADMIN role in service layer

**Status**: PASS - RBAC consistently enforced

### Principle VI: Schema Evolution ✅

- **Hibernate auto-migration**: New entities (SystemSetting, EmailNotificationEvent) use JPA annotations
- **Database constraints**: Unique indexes, foreign keys, NOT NULL constraints defined
- **Indexes**: Created for frequently queried columns (setting keys, email timestamps)
- **Data loss prevention**: No existing data modified, only new tables added

**Status**: PASS - Schema evolution properly managed

**OVERALL GATE STATUS**: ✅ PASS - All constitutional principles satisfied

## Project Structure

### Documentation (this feature)

```
specs/027-admin-user-notifications/
├── spec.md              # Feature specification
├── plan.md              # This file (/speckit.plan output)
├── research.md          # Phase 0 output (technology choices, patterns)
├── data-model.md        # Phase 1 output (entities, relationships)
├── quickstart.md        # Phase 1 output (setup instructions)
├── contracts/           # Phase 1 output (OpenAPI specs)
│   ├── notification-settings.yaml
│   └── email-events.yaml
└── tasks.md             # Phase 2 output (/speckit.tasks - NOT created yet)
```

### Source Code (repository root)

```
# Web application structure (existing)
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   ├── SystemSetting.kt          # NEW: Configuration entity
│   │   └── EmailNotificationEvent.kt # NEW: Audit entity
│   ├── repository/
│   │   ├── SystemSettingRepository.kt          # NEW
│   │   └── EmailNotificationEventRepository.kt # NEW
│   ├── service/
│   │   ├── SystemSettingService.kt             # NEW
│   │   ├── EmailNotificationService.kt         # NEW
│   │   └── UserService.kt                      # MODIFIED: Trigger notifications
│   ├── controller/
│   │   └── NotificationSettingsController.kt   # NEW: Config API
│   └── dto/
│       └── NotificationSettingsDto.kt          # NEW
└── src/test/kotlin/com/secman/
    ├── contract/
    │   └── NotificationSettingsContractTest.kt # NEW
    ├── service/
    │   └── EmailNotificationServiceTest.kt     # NEW
    └── integration/
        └── UserCreationNotificationTest.kt     # NEW

src/frontend/
├── src/
│   ├── components/
│   │   └── NotificationSettings.tsx            # NEW: Admin config UI
│   ├── pages/
│   │   └── admin/
│   │       └── settings.astro                  # MODIFIED: Add notification settings
│   └── services/
│       └── notificationSettingsService.ts      # NEW: API client
└── tests/e2e/
    └── admin-notifications.spec.ts             # NEW: E2E tests
```

**Structure Decision**: Follows existing web application structure (frontend + backend). New backend components follow established patterns (domain entities, repositories, services, controllers). Frontend extends existing admin pages with new configuration component. No structural changes to project layout required.

## Complexity Tracking

*No violations requiring justification - all constitutional principles satisfied.*

## Phase 0: Research & Technology Choices

### Email Delivery Technology

**Decision**: Use Micronaut Email module with JavaMail SMTP integration

**Rationale**:
- Native Micronaut integration (consistent with existing stack)
- Non-blocking async email support via `@Async` annotation
- Configurable SMTP settings via application.yml
- Template rendering support for HTML emails
- Battle-tested JavaMail foundation
- Existing Micronaut expertise in team

**Alternatives Considered**:
- **SendGrid/AWS SES**: Rejected - adds external dependency, cost, vendor lock-in
- **Spring Mail**: Rejected - wrong framework (we use Micronaut, not Spring)
- **Custom SMTP client**: Rejected - reinventing the wheel, maintenance burden

**Implementation Pattern**:
```kotlin
// Service with async email sending
class EmailNotificationService(
    private val mailSender: MailSender<*, *>
) {
    @Async
    fun sendAdminNotification(newUser: User, createdBy: User?, method: String) {
        // Non-blocking email send
    }
}
```

### Configuration Storage Pattern

**Decision**: Store settings in database using SystemSetting entity with in-memory caching

**Rationale**:
- Persistent across restarts (FR-002)
- Centralized configuration (single source of truth)
- Cacheable for performance (avoid DB hit per email)
- Auditable (who changed settings when)
- Consistent with existing data storage patterns

**Alternatives Considered**:
- **application.yml**: Rejected - requires restart to change, not user-configurable via UI
- **Environment variables**: Rejected - same issues as application.yml, plus deployment complexity
- **Redis/external cache**: Rejected - adds infrastructure dependency, overkill for ~2 settings

**Implementation Pattern**:
```kotlin
@Entity
@Table(name = "system_settings")
data class SystemSetting(
    @Id @GeneratedValue
    val id: Long? = null,

    @Column(unique = true, nullable = false)
    val key: String,

    @Column(nullable = false)
    val value: String,

    // ... timestamps
)

// Service with caching
@Singleton
class SystemSettingService {
    private val cache = ConcurrentHashMap<String, String>()

    fun getSetting(key: String, default: String): String {
        return cache.getOrPut(key) {
            repository.findByKey(key)?.value ?: default
        }
    }
}
```

### Email Template Rendering

**Decision**: Use Kotlin string templates with data classes for HTML email generation

**Rationale**:
- Simple, type-safe, maintainable
- No external template engine dependency
- Easy to test and version control
- Sufficient for structured notification emails
- Fast compilation and rendering

**Alternatives Considered**:
- **Thymeleaf**: Rejected - overkill for simple notification emails, adds dependency
- **FreeMarker**: Rejected - same as Thymeleaf, additional learning curve
- **Mustache/Handlebars**: Rejected - external dependency for minimal benefit

**Implementation Pattern**:
```kotlin
data class NotificationEmailData(
    val username: String,
    val email: String,
    val method: String,
    val timestamp: Instant,
    val createdBy: String?
)

fun generateEmailHtml(data: NotificationEmailData): String = """
<!DOCTYPE html>
<html>
<head><style>/* Professional CSS */</style></head>
<body>
    <h1>New User Registered</h1>
    <table>
        <tr><th>Username:</th><td>${data.username}</td></tr>
        <tr><th>Email:</th><td>${data.email}</td></tr>
        <!-- ... -->
    </table>
</body>
</html>
""".trimIndent()
```

### Audit Log Cleanup Strategy

**Decision**: Scheduled task (cron-style) running daily at 2 AM to delete records >30 days old

**Rationale**:
- Low-traffic time (2 AM) minimizes user impact
- Daily frequency sufficient for 30-day retention (not time-critical)
- Micronaut @Scheduled annotation built-in, no extra dependencies
- Batch deletion efficient for database performance
- Standard pattern for log cleanup

**Alternatives Considered**:
- **TTL in database**: Rejected - MariaDB doesn't have native TTL like Redis
- **Cleanup on read**: Rejected - adds latency to queries, inconsistent cleanup
- **Manual cleanup**: Rejected - error-prone, requires operational overhead

**Implementation Pattern**:
```kotlin
@Singleton
class EmailNotificationCleanupTask(
    private val repository: EmailNotificationEventRepository
) {
    @Scheduled(cron = "0 0 2 * * *") // Daily at 2 AM
    fun cleanupOldRecords() {
        val cutoffDate = Instant.now().minus(30, ChronoUnit.DAYS)
        repository.deleteByTimestampBefore(cutoffDate)
    }
}
```

### Non-Blocking User Creation Pattern

**Decision**: Fire-and-forget async email with try-catch isolation in separate transaction

**Rationale**:
- User creation never blocks on email (FR-011, SC-004, SC-006)
- Email failures logged but don't rollback user creation
- Separate transaction prevents cascade failure
- @Async ensures background execution
- Aligns with clarification (no retries, single attempt)

**Alternatives Considered**:
- **Message queue (RabbitMQ/Kafka)**: Rejected - infrastructure overkill, adds complexity for no retry requirement
- **Synchronous with timeout**: Rejected - still blocks user creation, doesn't meet SC-004 (<3 sec)
- **Spring @TransactionalEventListener**: Rejected - wrong framework (Micronaut, not Spring)

**Implementation Pattern**:
```kotlin
// User creation service
@Transactional
fun createUser(userData: CreateUserDto): User {
    val user = userRepository.save(User(...))

    // Fire-and-forget notification (separate transaction)
    emailNotificationService.sendAdminNotification(user, currentUser, "Manual")

    return user
}

// Email service with isolation
@Async
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun sendAdminNotification(...) {
    try {
        if (!settingsService.isNotificationEnabled()) return

        val admins = userRepository.findByRole("ADMIN")
        admins.forEach { admin ->
            try {
                mailSender.send(buildEmail(admin.email, ...))
                auditRepository.save(EmailNotificationEvent(status = "sent", ...))
            } catch (e: Exception) {
                auditRepository.save(EmailNotificationEvent(status = "failed", reason = e.message, ...))
            }
        }
    } catch (e: Exception) {
        // Log but don't propagate - user creation must succeed
        logger.error("Notification send failed", e)
    }
}
```

### Admin UI Configuration Component

**Decision**: React component with Bootstrap form controls, inline in existing Admin Settings page

**Rationale**:
- Consistent with existing frontend patterns (React islands in Astro)
- Bootstrap 5.3 styling matches existing UI
- Inline placement reduces navigation complexity
- Form validation with client-side feedback
- Axios API client consistent with existing services

**Alternatives Considered**:
- **Separate page**: Rejected - overkill for 2 settings, adds navigation complexity
- **Vue component**: Rejected - inconsistent with React 19 standard
- **Pure HTML form**: Rejected - loses React state management benefits

**Implementation Pattern**:
```tsx
// NotificationSettings.tsx
export function NotificationSettings() {
    const [enabled, setEnabled] = useState(true);
    const [senderEmail, setSenderEmail] = useState('');
    const [loading, setLoading] = useState(false);

    const handleSave = async () => {
        setLoading(true);
        try {
            await notificationSettingsService.update({ enabled, senderEmail });
            // Show success message
        } catch (error) {
            // Show error message
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="card mb-4">
            <div className="card-header">User Notification Settings</div>
            <div className="card-body">
                <div className="form-check mb-3">
                    <input type="checkbox" checked={enabled} onChange={...} />
                    <label>Send notifications for new users</label>
                </div>
                <div className="mb-3">
                    <label>Sender Email Address</label>
                    <input type="email" value={senderEmail} onChange={...} />
                </div>
                <button onClick={handleSave} disabled={loading}>Save Settings</button>
            </div>
        </div>
    );
}
```

## Phase 1: Data Model & Contracts

See generated files:
- [data-model.md](data-model.md) - Entity definitions, relationships, validation rules
- [contracts/](contracts/) - OpenAPI specifications for new endpoints
- [quickstart.md](quickstart.md) - Setup and testing instructions
