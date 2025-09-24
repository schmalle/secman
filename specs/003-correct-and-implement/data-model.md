# Data Model: Email Functionality Implementation

**Phase 1 Output** | **Date**: 2025-09-21

## Enhanced Entities

### EmailConfig (Enhanced)
**Purpose**: Store email server configuration with encryption
**Changes**: Add encryption for sensitive fields

```kotlin
@Entity
@Table(name = "email_configs")
data class EmailConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 100)
    val name: String,

    @Column(nullable = false, length = 255)
    val smtpHost: String,

    @Column(nullable = false)
    val smtpPort: Int,

    @Column(nullable = false)
    val smtpTls: Boolean = true,

    @Column(nullable = false)
    val smtpSsl: Boolean = false,

    @Column(nullable = true, length = 255)
    @Convert(converter = EncryptedStringConverter::class) // NEW: Encryption
    val smtpUsername: String? = null,

    @Column(nullable = true, columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter::class) // NEW: Encryption
    val smtpPassword: String? = null,

    @Column(nullable = false, length = 255)
    val fromEmail: String,

    @Column(nullable = false, length = 255)
    val fromName: String,

    @Column(nullable = false)
    val isActive: Boolean = false,

    // NEW: IMAP support
    @Column(nullable = true, length = 255)
    val imapHost: String? = null,

    @Column(nullable = true)
    val imapPort: Int? = null,

    @Column(nullable = false)
    val imapEnabled: Boolean = false,

    @CreationTimestamp
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    val updatedAt: LocalDateTime? = null
)
```

**Validation Rules**:
- SMTP host must be valid hostname or IP
- SMTP port must be 1-65535
- From email must be valid email format
- Only one active configuration allowed
- Username/password required if authentication enabled

### TestEmailAccount (New)
**Purpose**: Manage test email accounts for validation
**Relationships**: None (standalone testing entity)

```kotlin
@Entity
@Table(name = "test_email_accounts")
data class TestEmailAccount(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 100)
    val name: String, // Descriptive name for the test account

    @Column(nullable = false, length = 255)
    val emailAddress: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val provider: EmailProvider, // GMAIL, OUTLOOK, SMTP_CUSTOM, etc.

    @Column(nullable = true, columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter::class)
    val credentials: String? = null, // JSON with provider-specific auth

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: TestAccountStatus = TestAccountStatus.ACTIVE,

    @Column(nullable = true, columnDefinition = "TEXT")
    val lastTestResult: String? = null, // JSON with test results

    @Column(nullable = true)
    val lastTestedAt: LocalDateTime? = null,

    @CreationTimestamp
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    val updatedAt: LocalDateTime? = null
)

enum class EmailProvider {
    GMAIL, OUTLOOK, YAHOO, SMTP_CUSTOM, IMAP_CUSTOM
}

enum class TestAccountStatus {
    ACTIVE, INACTIVE, FAILED, VERIFICATION_PENDING
}
```

### EmailNotificationLog (New)
**Purpose**: Track email delivery attempts and status
**Relationships**: Links to risk assessments and email configs

```kotlin
@Entity
@Table(name = "email_notification_logs")
data class EmailNotificationLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val riskAssessmentId: Long, // FK to RiskAssessment

    @Column(nullable = false)
    val emailConfigId: Long, // FK to EmailConfig used

    @Column(nullable = false, length = 255)
    val recipientEmail: String,

    @Column(nullable = false, length = 500)
    val subject: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: EmailStatus = EmailStatus.PENDING,

    @Column(nullable = true, columnDefinition = "TEXT")
    val errorMessage: String? = null,

    @Column(nullable = false)
    val attempts: Int = 0,

    @Column(nullable = true)
    val sentAt: LocalDateTime? = null,

    @Column(nullable = true)
    val nextRetryAt: LocalDateTime? = null,

    @CreationTimestamp
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    val updatedAt: LocalDateTime? = null
)

enum class EmailStatus {
    PENDING, SENT, FAILED, RETRYING, PERMANENTLY_FAILED
}
```

### RiskAssessmentNotificationConfig (New)
**Purpose**: Configure who receives notifications for risk assessments
**Relationships**: Links to risk assessments and user groups

```kotlin
@Entity
@Table(name = "risk_assessment_notification_configs")
data class RiskAssessmentNotificationConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 100)
    val name: String, // e.g., "Security Team", "Management"

    @Column(nullable = false, columnDefinition = "TEXT")
    val recipientEmails: String, // JSON array of email addresses

    @Column(nullable = true, columnDefinition = "TEXT")
    val conditions: String? = null, // JSON with notification conditions

    @Column(nullable = false)
    val isActive: Boolean = true,

    @CreationTimestamp
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    val updatedAt: LocalDateTime? = null
)
```

## Events and DTOs

### RiskAssessmentCreatedEvent (New)
**Purpose**: Event published when new risk assessment created

```kotlin
data class RiskAssessmentCreatedEvent(
    val riskAssessmentId: Long,
    val title: String,
    val riskLevel: String,
    val createdBy: String,
    val createdAt: LocalDateTime
)
```

### Email Request/Response DTOs

```kotlin
// Test email sending
data class SendTestEmailRequest(
    val testAccountId: Long,
    val subject: String,
    val content: String
)

data class EmailDeliveryResponse(
    val success: Boolean,
    val messageId: String?,
    val error: String?
)

// Notification configuration
data class NotificationConfigRequest(
    val name: String,
    val recipientEmails: List<String>,
    val conditions: Map<String, Any>?
)
```

## State Transitions

### EmailNotificationLog Status Flow
```
PENDING → SENT (successful delivery)
PENDING → RETRYING (temporary failure)
RETRYING → SENT (retry successful)
RETRYING → RETRYING (retry failed, more attempts remain)
RETRYING → PERMANENTLY_FAILED (max retries exceeded)
PENDING → FAILED (immediate permanent failure)
```

### TestEmailAccount Status Flow
```
ACTIVE ↔ INACTIVE (admin control)
ACTIVE → VERIFICATION_PENDING (credential update)
VERIFICATION_PENDING → ACTIVE (validation success)
VERIFICATION_PENDING → FAILED (validation failure)
FAILED → VERIFICATION_PENDING (retry validation)
```

## Database Indexes

**Required for performance**:
- `email_notification_logs(status, next_retry_at)` - for retry processing
- `email_notification_logs(risk_assessment_id)` - for lookup by risk assessment
- `test_email_accounts(status)` - for active account queries
- `email_configs(is_active)` - for active config lookup (unique constraint)

## Encryption Implementation

### EncryptedStringConverter
```kotlin
@Converter
class EncryptedStringConverter : AttributeConverter<String?, String?> {
    private val textEncryptor = Encryptors.text(
        getEncryptionPassword(),
        getEncryptionSalt()
    )

    override fun convertToDatabaseColumn(attribute: String?): String? {
        return attribute?.let { textEncryptor.encrypt(it) }
    }

    override fun convertToEntityAttribute(dbData: String?): String? {
        return dbData?.let { textEncryptor.decrypt(it) }
    }
}
```

**Security Requirements**:
- Encryption key stored separately from JWT secret
- Salt generated per installation
- Key rotation capability for future enhancement
- No plaintext sensitive data in logs or dumps