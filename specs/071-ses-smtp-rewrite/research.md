# Research: SES SMTP Rewrite

**Feature**: 071-ses-smtp-rewrite
**Date**: 2026-01-29

## R1: AWS SES SMTP Endpoint Pattern

**Decision**: Use `email-smtp.{region}.amazonaws.com` as the SMTP host, derived from the existing `sesRegion` field in `EmailConfig`.

**Rationale**: AWS SES provides SMTP endpoints for every supported region following a consistent naming pattern. The existing `sesRegion` field (e.g., `eu-central-1`) maps directly to the SMTP hostname. Port 587 with STARTTLS is the recommended configuration.

**Alternatives considered**:
- Require manual SMTP host entry — rejected because the region-to-host mapping is deterministic and eliminating manual entry reduces misconfiguration.
- Support port 465 (SMTPS) — not needed; port 587 with STARTTLS is the AWS-recommended approach and matches the existing SMTP provider behavior.

## R2: Credential Reuse Strategy

**Decision**: Repurpose the existing `sesAccessKey` and `sesSecretKey` database columns to store SES SMTP credentials (username and password).

**Rationale**: AWS SES SMTP credentials are a username/password pair generated from IAM credentials via the AWS console. They are not the same as IAM access keys, but they serve the same purpose (authentication) and fit into the same storage model. The existing `EncryptedStringConverter` handles encryption. No schema migration is needed — the columns already exist and are encrypted.

**Alternatives considered**:
- Add new `ses_smtp_username`/`ses_smtp_password` columns — rejected because it would require a database migration for no functional benefit; the existing columns serve the identical purpose.
- Add SMTP fields to the existing `smtpUsername`/`smtpPassword` columns and set `smtpHost` automatically — rejected because it would conflate two different provider types and make the domain model ambiguous.

## R3: SesEmailService Rewrite Approach

**Decision**: Rewrite `SesEmailService.sendEmail()` to use Jakarta Mail's `Transport.send()` with SMTP properties derived from `EmailConfig.sesRegion`, reusing the same MIME message construction pattern already used in `EmailSender.sendEmailInternalWithDbConfig()`.

**Rationale**: The existing SMTP sending code in `EmailSender` already handles Jakarta Mail session creation, MIME multipart message construction, and transport. The SES SMTP path needs the same logic but with a different host/credentials source. Rather than duplicating code, `SesEmailService` constructs SMTP properties from the SES config fields and delegates to the same Jakarta Mail `Transport.send()` pattern.

**Alternatives considered**:
- Delete `SesEmailService` entirely and handle SES as a special SMTP case in `EmailSender` — this would simplify the code but removes the clean separation between provider-specific logic and routing. Keeping `SesEmailService` as a thin SMTP wrapper maintains the existing architecture.
- Use Micronaut's email abstraction — adds unnecessary framework coupling; the direct Jakarta Mail approach is already established.

## R4: AWS SDK Removal

**Decision**: Remove `software.amazon.awssdk:ses:2.41.8` and `software.amazon.awssdk:auth:2.41.8` from `build.gradle.kts`.

**Rationale**: With the SMTP rewrite, no AWS SDK classes are used. Removing the dependencies reduces the JAR size and eliminates transitive dependency risks. The SMTP approach uses only Jakarta Mail (`angus-mail:2.0.5`), which is already a project dependency.

**Alternatives considered**:
- Keep the dependencies for potential future use — rejected; YAGNI principle. They can be re-added if needed.

## R5: Verify Configuration Without SES API

**Decision**: Replace `SesEmailService.verifyConfiguration()` (which calls `GetAccountSendingEnabledRequest`) with an SMTP connection test using Jakarta Mail's `Transport.connect()`.

**Rationale**: The current verification uses the SES API to check if account sending is enabled. With SMTP, the equivalent is attempting an SMTP connection and authentication handshake. A successful `Transport.connect()` confirms the credentials are valid and the SMTP endpoint is reachable. This is the same pattern used for standard SMTP config validation.

**Alternatives considered**:
- Remove verification entirely — rejected because admins need to validate their configuration before activating it.
- Send a test email as the only verification — too intrusive; a connection test is sufficient for validation.
