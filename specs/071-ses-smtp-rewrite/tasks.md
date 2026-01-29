# Tasks: SES SMTP Rewrite

**Input**: Design documents from `/specs/071-ses-smtp-rewrite/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Add SMTP helper methods to `EmailConfig` that both user stories depend on

- [X] T001 [US1] Add `getSesSmtpHost()` method to `src/backendng/src/main/kotlin/com/secman/domain/EmailConfig.kt` — returns `email-smtp.{sesRegion}.amazonaws.com`
- [X] T002 [US1] Add `getSesSmtpProperties()` method to `src/backendng/src/main/kotlin/com/secman/domain/EmailConfig.kt` — returns Jakarta Mail properties map (host, port 587, STARTTLS enabled, auth enabled)

**Checkpoint**: EmailConfig now provides SMTP connection details derived from the SES region field

---

## Phase 2: User Story 1 — Send Emails via SMTP Instead of SES API (Priority: P1) 🎯 MVP

**Goal**: Rewrite `SesEmailService` to send emails using Jakarta Mail SMTP instead of the AWS SES SDK `SendRawEmail` API

**Independent Test**: Configure SES in admin UI with SMTP credentials, send a test email — email should be delivered via SMTP

### Implementation for User Story 1

- [X] T003 [US1] Rewrite `sendEmail()` in `src/backendng/src/main/kotlin/com/secman/service/SesEmailService.kt` — replace AWS SES `SendRawEmailRequest` with Jakarta Mail `Transport.send()` using SMTP properties from `EmailConfig.getSesSmtpProperties()` and credentials from `sesAccessKey`/`sesSecretKey`
- [X] T004 [US1] Rewrite `sendTestEmail()` in `src/backendng/src/main/kotlin/com/secman/service/SesEmailService.kt` — use the new SMTP-based `sendEmail()` path instead of SES API
- [X] T005 [US1] Rewrite `verifyConfiguration()` in `src/backendng/src/main/kotlin/com/secman/service/SesEmailService.kt` — replace `GetAccountSendingEnabledRequest` with Jakarta Mail `Transport.connect()` to validate SMTP credentials and endpoint reachability
- [X] T006 [US1] Remove all AWS SDK imports from `src/backendng/src/main/kotlin/com/secman/service/SesEmailService.kt` — remove `software.amazon.awssdk.*` imports, replace with Jakarta Mail imports as needed
- [X] T007 [US1] Remove AWS SES SDK dependencies from `src/backendng/build.gradle.kts` — remove `software.amazon.awssdk:ses:2.41.8` and `software.amazon.awssdk:auth:2.41.8`

**Checkpoint**: SES email sending now uses SMTP. AWS SES SDK is fully removed. Build should compile cleanly.

---

## Phase 3: User Story 2 — Backward-Compatible Configuration (Priority: P2)

**Goal**: Ensure existing SES configurations in the database continue to work and can be validated through the admin UI

**Independent Test**: Load an existing SES configuration, update credentials, verify configuration via admin UI

### Implementation for User Story 2

- [X] T008 [US2] Verify `verifySesConfig()` in `src/backendng/src/main/kotlin/com/secman/service/EmailProviderConfigService.kt` works with the rewritten `SesEmailService.verifyConfiguration()` — the interface is unchanged, so this should work without code changes, but verify the call chain
- [X] T009 [US2] Verify `sendEmailViaSes()` in `src/backendng/src/main/kotlin/com/secman/service/EmailSender.kt` delegates correctly to the rewritten `SesEmailService.sendEmail()` — no code changes expected, verify the call chain

**Checkpoint**: Existing SES configurations load, validate, and send emails through the SMTP path without any manual reconfiguration beyond updating credentials.

---

## Phase 4: Polish & Verification

**Purpose**: Build verification and cleanup

- [X] T010 Build project with `./gradlew build` — confirm no compilation errors after AWS SDK removal
- [X] T011 Verify no AWS SES SDK imports remain with `grep -r "software.amazon.awssdk" src/backendng/src/main/kotlin/`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately
- **Phase 2 (US1)**: Depends on Phase 1 (T001, T002) — `SesEmailService` needs `getSesSmtpHost()` and `getSesSmtpProperties()`
- **Phase 3 (US2)**: Depends on Phase 2 — verification tasks require the rewritten service
- **Phase 4 (Polish)**: Depends on Phase 2 and Phase 3

### Within Phase 2

- T003 depends on T001, T002 (needs SMTP properties)
- T004 depends on T003 (reuses the new `sendEmail()`)
- T005 can run in parallel with T004 (independent method)
- T006 is done as part of T003–T005 (remove imports as methods are rewritten)
- T007 should be done last in Phase 2 (removing deps before rewrite would break compilation)

### Execution Order

```
T001, T002 → T003 → T004, T005 → T006 → T007 → T008, T009 → T010, T011
```

---

## Notes

- No new files are created — all changes are to existing files
- No database schema changes — existing columns are repurposed
- No frontend changes — admin UI fields remain the same
- No test tasks included (per project constitution: tests only when explicitly requested)
- `sanitizeEmailHeader()` in `SesEmailService.kt` must be preserved unchanged
- The `EmailSender.sendEmailViaSes()` method should not need changes since it delegates to `SesEmailService.sendEmail()`
