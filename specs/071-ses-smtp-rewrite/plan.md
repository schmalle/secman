# Implementation Plan: SES SMTP Rewrite

**Branch**: `071-ses-smtp-rewrite` | **Date**: 2026-01-29 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/071-ses-smtp-rewrite/spec.md`

## Summary

Replace AWS SES API-based email sending (`ses:SendRawEmail` via AWS SDK) with standard SMTP sending to the SES SMTP endpoint. This eliminates the IAM `ses:SendRawEmail` permission requirement. The `SesEmailService` class is rewritten to use Jakarta Mail SMTP (already available via `angus-mail`) instead of the AWS SES SDK. The `EmailConfig` entity's SES fields (`sesAccessKey`/`sesSecretKey`) are repurposed as SMTP username/password. The AWS SES SDK dependencies are removed from the build.

## Technical Context

**Language/Version**: Kotlin 2.3.0 / Java 25
**Primary Dependencies**: Micronaut 4.10, Jakarta Mail (angus-mail 2.0.5), Hibernate JPA
**Storage**: MariaDB 11.4 (existing `email_configs` table)
**Testing**: JUnit 5, Mockk (user-requested only per constitution)
**Target Platform**: Linux server (backend)
**Project Type**: Web application (backend only, no frontend changes)
**Performance Goals**: Email send latency unchanged (existing 10s connection timeout, 30s read timeout)
**Constraints**: Must maintain backward compatibility with existing SES database configurations; must not break standard SMTP provider path
**Scale/Scope**: Rewrite 1 service class, modify 2 existing files, remove 1 Gradle dependency; ~100 lines changed

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | PASS | SMTP credentials remain encrypted via `EncryptedStringConverter`; STARTTLS enforced; header injection prevention preserved |
| III. API-First | PASS | No new API endpoints; existing email config REST endpoints unchanged |
| IV. User-Requested Testing | PASS | No test tasks included unless user requests them |
| V. RBAC | PASS | Email configuration management remains ADMIN-only |
| VI. Schema Evolution | PASS | No database schema changes; existing `ses_access_key`/`ses_secret_key` columns repurposed as SMTP credentials |

No violations. All gates pass.

## Project Structure

### Documentation (this feature)

```text
specs/071-ses-smtp-rewrite/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # N/A - no new APIs
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
src/backendng/
├── build.gradle.kts                                          # Remove AWS SES SDK dependencies
├── src/main/kotlin/com/secman/
│   ├── service/
│   │   ├── SesEmailService.kt                                # Rewrite: SES API → SMTP via Jakarta Mail
│   │   ├── EmailSender.kt                                    # Update: sendEmailViaSes() uses SMTP path
│   │   └── EmailProviderConfigService.kt                     # Update: verifySesConfig() validates SMTP connectivity
│   └── domain/
│       └── EmailConfig.kt                                    # Update: SES config derives SMTP host from region
```

**Structure Decision**: Backend-only changes in existing files. No new files needed. The feature rewrites the SES sending path to use SMTP instead of the AWS SES SDK.

## Complexity Tracking

No violations to justify.
