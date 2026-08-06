# Research: CLI User Mapping Management

**Date**: 2025-11-19
**Feature**: 049-cli-user-mappings

## Overview

This document captures research findings and design decisions for implementing CLI user mapping management commands.

## 1. CLI Framework & Pattern Analysis

### Decision: Use Picocli with Micronaut Dependency Injection

**Rationale**:
- Existing CLI already uses Picocli 4.7 (seen in `SendNotificationsCommand.kt`)
- Picocli provides rich annotation-based command definition (@Command, @Option, @Parameters)
- Integrates seamlessly with Micronaut's @Singleton DI
- Built-in help generation (mixinStandardHelpOptions = true)
- Type-safe command-line argument parsing

**Alternatives Considered**:
- **Clikt**: Modern Kotlin CLI library, but would introduce new dependency
- **Apache Commons CLI**: More verbose, less Kotlin-friendly
- **Custom argument parser**: Reinventing the wheel

**Pattern from SendNotificationsCommand**:
```kotlin
@Singleton
@Command(
    name = "command-name",
    description = ["Description"],
    mixinStandardHelpOptions = true
)
class CommandName : Runnable {
    @Option(names = ["--flag"], description = ["..."])
    var flag: Boolean = false

    @Spec
    lateinit var spec: Model.CommandSpec

    lateinit var service: SomeService  // DI injected

    override fun run() {
        // Command logic
    }
}
```

## 2. Subcommand Structure

### Decision: Use Picocli Subcommand Groups

**Rationale**:
- Cleaner UX: `manage-user-mappings add` vs `add-user-mapping`
- Logical grouping: all mapping operations under one parent command
- Follows Git/Docker pattern: `git commit`, `docker run`
- Picocli supports this via @Command(subcommands = [...])

**Command Structure**:
```
./gradlew cli:run --args='manage-user-mappings <subcommand> [options]'

Subcommands:
  add-domain      Add domain-to-user mapping(s)
  add-aws         Add AWS-account-to-user mapping(s)
  remove          Remove mapping(s) by email/domain/aws-account
  list            List all mappings (with optional email filter)
  import          Batch import from CSV/JSON file
```

**Alternatives Considered**:
- **Flat commands**: `add-domain-mapping`, `add-aws-mapping` - verbose, cluttered
- **Single command with type flag**: `add-mapping --type=domain` - less intuitive

## 3. UserMapping Entity Extension

### Decision: Add `status` Enum Field (ACTIVE, PENDING)

**Current State** (from UserMapping.kt):
- Already has `user: User?` (nullable - supports future users per Feature 042)
- Already has `appliedAt: Instant?` (tracks when applied)
- Has `isFutureMapping()` and `isAppliedMapping()` helper methods

**Problem**:
- Existing `isFutureMapping()` checks `user == null && appliedAt == null`
- Existing `isAppliedMapping()` checks `appliedAt != null`
- No explicit "ACTIVE" vs "PENDING" distinction in database

**Solution**: Add status enum for clarity

```kotlin
@Column(name = "status", nullable = false, length = 20)
@Enumerated(EnumType.STRING)
var status: MappingStatus = MappingStatus.ACTIVE
```

```kotlin
enum class MappingStatus {
    PENDING,  // Created for non-existent user
    ACTIVE    // Applied to existing user
}
```

**Migration Strategy**:
- Hibernate DDL: `ALTER TABLE user_mapping ADD COLUMN status VARCHAR(20) DEFAULT 'ACTIVE'`
- Existing records get ACTIVE (they're already applied)
- New future mappings get PENDING
- On user creation, update PENDING → ACTIVE

**Alternative Considered**:
- **Keep using appliedAt logic**: Works but less explicit, harder to query
- **Rejected**: Explicit status field improves readability and query performance

## 4. Batch File Format

### Decision: Support Both CSV and JSON

**CSV Format** (simpler for bulk operations):
```csv
email,type,value
user1@example.com,domain,example.com
user1@example.com,aws,123456789012
user2@example.com,domain,test.com
```

**JSON Format** (richer structure):
```json
{
  "mappings": [
    {
      "email": "user1@example.com",
      "domains": ["example.com", "test.com"],
      "awsAccounts": ["123456789012", "987654321098"]
    },
    {
      "email": "user2@example.com",
      "domains": ["another.com"]
    }
  ]
}
```

**Rationale**:
- CSV: Easy to generate from Excel/database exports, simple parsing (Apache Commons CSV)
- JSON: More structured, supports multiple mappings per user in single row
- Existing codebase uses both (see import controllers)

**Parsing Libraries**:
- CSV: Apache Commons CSV 1.11.0 (already in project)
- JSON: Jackson (Micronaut built-in via micronaut-serde-jackson)

## 5. Authentication & Authorization

### Decision: Reuse Existing Security Infrastructure

**Analysis of Existing System**:
- Backend uses `@Secured(SecurityRule.IS_AUTHENTICATED)` for endpoints
- Controllers check `authentication.roles.contains("ADMIN")`
- CLI commands currently don't show explicit auth in SendNotificationsCommand

**Problem**: CLI runs server-side, but needs to know which admin user is executing

**Solution**: Add authentication context to CLI

**Options**:
1. **Environment variable**: `SECMAN_ADMIN_EMAIL=admin@example.com`
2. **Config file**: `~/.secman/credentials.yaml`
3. **Command-line argument**: `--admin-user=admin@example.com`
4. **Interactive prompt**: Ask for email on first run

**Decision**: Command-line argument with environment variable fallback

```kotlin
@Option(
    names = ["--admin-user"],
    description = ["Admin user email (or set SECMAN_ADMIN_EMAIL)"],
    required = false
)
var adminUser: String? = null

// In run():
val admin = adminUser ?: System.getenv("SECMAN_ADMIN_EMAIL")
    ?: throw IllegalArgumentException("Admin user required")
```

**Rationale**:
- Explicit and auditable (who ran what)
- Supports automation (cron jobs with env var)
- No security token needed (CLI runs server-side with direct DB access)
- Audit logs capture this email

## 6. Duplicate Handling Strategy

### Decision: Idempotent Operations (Skip + Report)

**Per Clarification**: "Skip silently, report in results as 'already exists'"

**Implementation**:
```kotlin
fun addMapping(email: String, domain: String?): MappingResult {
    // Check for duplicate using repository method
    if (repository.existsByEmailAndAwsAccountIdAndDomain(email, null, domain)) {
        return MappingResult.SKIPPED("Mapping already exists")
    }

    // Create new mapping
    val mapping = UserMapping(...)
    repository.save(mapping)
    return MappingResult.CREATED
}
```

**Batch Import**:
```kotlin
data class BatchResult(
    val created: Int,
    val skipped: Int,
    val errors: List<String>
)
```

**Rationale**:
- Idempotent: Running same command multiple times = same result
- Automation-friendly: Scripts can retry without errors
- Informative: User sees what happened (created vs skipped)

## 7. Pending Mapping Application

### Decision: Event-Driven User Creation Hook

**Requirement**: "Automatically apply when user is created"

**Current System** (from Feature 042):
- `UserMapping` already supports future users (`user: User?` is nullable)
- `appliedAt` tracks when mapping was applied

**Solution**: UserService event listener

```kotlin
@Singleton
class UserMappingApplicationService(
    private val userMappingRepository: UserMappingRepository
) {

    @EventListener
    fun onUserCreated(event: UserCreatedEvent) {
        val email = event.user.email.lowercase()

        // Find all pending mappings for this email
        val pendingMappings = userMappingRepository.findByEmail(email)
            .filter { it.isFutureMapping() }

        // Apply mappings
        pendingMappings.forEach { mapping ->
            mapping.user = event.user
            mapping.status = MappingStatus.ACTIVE
            mapping.appliedAt = Instant.now()
            userMappingRepository.update(mapping)
        }
    }
}
```

**Event Publishing** (from existing codebase):
- Feature 042 already has `UserCreatedEvent`
- Published when user created via web UI or API
- Need to ensure CLI-created users also publish event

**Alternatives Considered**:
- **Polling**: Cron job to check for new users - inefficient
- **Manual command**: `apply-pending-mappings` - requires admin intervention
- **Rejected**: Event-driven is immediate and automatic

## 8. Error Handling & Validation

### Decision: Fail-Fast with Descriptive Messages

**Validation Layers**:

1. **Input Validation** (Picocli + Jakarta Validation):
```kotlin
@Email(message = "Invalid email format")
var email: String

@Pattern(regexp = "^\\d{12}$", message = "AWS Account ID must be 12 digits")
var awsAccountId: String?
```

2. **Business Validation** (Service Layer):
```kotlin
// Email format
if (!email.matches(Regex("^[^@]+@[^@]+\\.[^@]+$"))) {
    throw IllegalArgumentException("Invalid email: $email")
}

// AWS account ID
if (awsAccountId != null && !awsAccountId.matches(Regex("^\\d{12}$"))) {
    throw IllegalArgumentException("Invalid AWS account ID: $awsAccountId")
}
```

3. **Error Messages** (CLI Output):
```
❌ Error: Invalid email format: 'not-an-email'
❌ Error: AWS Account ID must be exactly 12 digits: '12345'
✅ Success: Created 5 mappings, skipped 2 duplicates
⚠️  Warning: User 'future@example.com' not found - created pending mapping
```

**Batch Import Error Handling**:
```kotlin
// Continue processing on error (partial success mode)
file.forEachLine { line ->
    try {
        val result = processLine(line)
        results.add(result)
    } catch (e: Exception) {
        errors.add("Line ${lineNum}: ${e.message}")
        // Continue to next line
    }
}
```

## 9. Audit Logging

### Decision: Structured Logging with Metadata

**Per Clarification**: "operation type, actor, timestamp, affected entities"

**Implementation**:
```kotlin
@Singleton
class AuditLogger {
    private val log = LoggerFactory.getLogger(AuditLogger::class.java)

    fun logMappingChange(
        operation: String,  // "CREATE", "DELETE", "IMPORT"
        actor: String,      // Admin email
        entity: UserMapping,
        metadata: Map<String, Any> = emptyMap()
    ) {
        val logEntry = mapOf(
            "timestamp" to Instant.now(),
            "operation" to operation,
            "actor" to actor,
            "entity_type" to "UserMapping",
            "entity_id" to entity.id,
            "email" to entity.email,
            "domain" to entity.domain,
            "aws_account_id" to entity.awsAccountId,
            "status" to entity.status
        ) + metadata

        log.info("AUDIT: {}", JsonMapper.toJson(logEntry))
    }
}
```

**Alternatives Considered**:
- **Database audit table**: More structured, queryable - future enhancement
- **Current**: Logging sufficient for MVP, can add DB later

## 10. CLI Documentation

### Decision: Comprehensive Markdown File + Inline Help

**Location**: `src/cli/src/main/resources/cli-docs/USER_MAPPING_COMMANDS.md`

**Content Structure**:
1. Overview & Prerequisites
2. Command Reference (all subcommands)
3. Usage Examples
4. Batch File Format Specifications
5. Troubleshooting
6. Security & Best Practices

**Inline Help** (via Picocli):
```bash
./gradlew cli:run --args='manage-user-mappings --help'
./gradlew cli:run --args='manage-user-mappings add-domain --help'
```

**Rationale**:
- FR-012: "comprehensive CLI documentation file"
- FR-017: "help text accessible via --help flag"
- FR-005: "enable administrators to complete tasks without support in 90% of cases"

## Summary of Key Decisions

| Area | Decision | Rationale |
|------|----------|-----------|
| CLI Framework | Picocli with Micronaut DI | Already in use, rich features |
| Command Structure | Subcommand groups | Better UX, logical grouping |
| Entity Extension | Add `status` enum field | Explicit PENDING/ACTIVE state |
| Batch Format | CSV + JSON support | Flexibility for different use cases |
| Authentication | CLI arg + env var | Explicit, auditable, automation-friendly |
| Duplicate Handling | Idempotent skip + report | Automation-safe, informative |
| Pending Application | Event-driven listener | Immediate, automatic |
| Error Handling | Fail-fast with clear messages | User-friendly, debuggable |
| Audit Logging | Structured logging | Meets compliance, queryable |
| Documentation | Markdown + inline help | Comprehensive, accessible |

## References

- Existing CLI: `src/cli/src/main/kotlin/com/secman/cli/commands/SendNotificationsCommand.kt`
- UserMapping entity: `src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt`
- UserMapping repository: `src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt`
- Feature 042: Future User Mappings (provides foundation for pending mappings)
- Picocli docs: https://picocli.info/
- Apache Commons CSV: https://commons.apache.org/proper/commons-csv/
