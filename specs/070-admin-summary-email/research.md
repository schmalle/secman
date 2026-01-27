# Research: Admin Summary Email

**Feature**: 070-admin-summary-email
**Date**: 2026-01-27

## Research Topics

### 1. Existing CLI Command Pattern

**Decision**: Follow the `SendNotificationsCommand` pattern

**Rationale**: The existing `SendNotificationsCommand` demonstrates the established pattern:
- PicoCLI `@Command` annotation with name, description, mixinStandardHelpOptions
- `@Option` annotations for `--dry-run` and `--verbose` flags
- Service injection via `lateinit var`
- `Runnable` interface implementation
- Exit codes: 0 for success, 1 for failures

**Alternatives considered**:
- Picocli subcommand under existing command - rejected (feature is distinct enough)
- HTTP endpoint instead of CLI - rejected (spec requires CLI)

### 2. Email Service Integration

**Decision**: Reuse `EmailService.sendEmail()` method directly

**Rationale**: The `EmailService` already provides:
- `sendEmail(to, subject, textContent, htmlContent)` method
- Automatic SMTP configuration lookup via `getActiveEmailConfig()`
- HTML + plain text multipart email support
- Retry logic and error handling

**Alternatives considered**:
- Create new email sending method - rejected (unnecessary duplication)
- Use bulk sending - rejected (admin list is small, individual sending gives better error tracking)

### 3. Statistics Query Strategy

**Decision**: Create dedicated service methods with count queries

**Rationale**: Use repository `count()` methods for efficiency:
- `UserRepository.count()` - total users
- `VulnerabilityRepository.count()` - total vulnerabilities
- `AssetRepository.count()` - total assets
- `UserRepository.findByRolesContaining(User.Role.ADMIN)` - ADMIN recipients

**Alternatives considered**:
- Load full entities and count - rejected (inefficient for large datasets)
- Cache statistics - rejected (overkill for periodic CLI command)

### 4. Execution Logging Entity

**Decision**: Create `AdminSummaryLog` entity with minimal fields

**Rationale**: Match spec FR-013 requirements:
- `id` (Long, auto-generated)
- `executedAt` (Instant, timestamp of execution)
- `recipientCount` (Int, number of recipients)
- `userCount` (Long, users statistic sent)
- `vulnerabilityCount` (Long, vulnerabilities statistic sent)
- `assetCount` (Long, assets statistic sent)
- `emailsSent` (Int, successful sends)
- `emailsFailed` (Int, failed sends)
- `status` (Enum: SUCCESS, PARTIAL_FAILURE, FAILURE)

**Alternatives considered**:
- Reuse `EmailNotificationLog` - rejected (different purpose, different schema)
- Log to file only - rejected (user requested database logging in clarification)

### 5. Email Template Design

**Decision**: Create new `admin-summary.html` and `admin-summary.txt` templates

**Rationale**:
- Follow existing template pattern (`new-vulnerability-notification.html/txt`)
- Use placeholders: `{{executionDate}}`, `{{userCount}}`, `{{vulnerabilityCount}}`, `{{assetCount}}`
- Professional styling matching existing email design

**Alternatives considered**:
- Generate HTML in code - rejected (harder to maintain, less flexible)
- Single template with conditionals - rejected (HTML and text need different formatting)

### 6. Error Handling Strategy

**Decision**: Continue on individual email failures, report summary

**Rationale**:
- If 10 ADMIN users, and 1 email fails, send to remaining 9
- Report partial success in summary output
- Exit code 1 if any failures (for cron monitoring)
- Log entry captures exact counts for audit

**Alternatives considered**:
- Stop on first failure - rejected (other admins should still receive email)
- Ignore failures - rejected (need visibility for troubleshooting)

## Summary

All technical decisions are resolved. No NEEDS CLARIFICATION items remain. Ready for Phase 1 design.
