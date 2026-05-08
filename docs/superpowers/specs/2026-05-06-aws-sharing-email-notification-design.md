# AWS Account Sharing — Email Notification to Target User

**Date:** 2026-05-06
**Status:** Approved (design); pending implementation plan
**Author:** Markus Schmall
**Scope:** Backend only

## Problem

When an admin (or a source user via MCP) creates an `AwsAccountSharing` rule, the target user gets no notification. They only discover the new access by browsing assets and noticing rows they did not previously see. This is poor UX and leaves an audit gap from the recipient's perspective.

## Goal

When an `AwsAccountSharing` rule is successfully created, send an email to the **target user** announcing that AWS account access has been shared with them.

**Non-goals:**

- No email on **deletion** of a sharing rule (out of scope; explicitly deferred).
- No email to the **source user** or to admins (the existing `log.info("AUDIT: ...")` line already covers that).
- No new notification preference flag. This is an admin-driven access change; opt-out would defeat the purpose.
- No frontend, schema, or API contract changes.

## Approach

Mirror the existing transactional-event-listener pattern used by `ExceptionRequestNotificationListener` / `ExceptionRequestNotificationService`. This is the canonical shape in this codebase for "send email after a domain mutation," and it satisfies three constraints simultaneously:

1. **Transaction safety** — email I/O must run **after** `COMMIT`, so SMTP latency cannot stall the DB write and SMTP failure cannot roll the rule back.
2. **Hibernate safety** — `AwsAccountSharing.sourceUser`, `targetUser`, and `createdBy` are `FetchType.LAZY`. We resolve them on the listener thread (which still has a Hibernate session), and pass plain primitives to the async dispatch lambda.
3. **Caller-agnostic** — the HTTP controller (`AwsAccountSharingController`) and the MCP tool (`CreateAwsAccountSharingTool`) both call `AwsAccountSharingService.createSharingRule()`. Publishing the event inside the service guarantees both call sites trigger the email — no duplication.

## Architecture

```
HTTP POST /api/aws-account-sharing  ─┐
MCP create_aws_account_sharing      ─┴─► AwsAccountSharingService.createSharingRule()
                                            │  @Transactional
                                            │  1. validate (existing)
                                            │  2. save sharing  (existing)
                                            │  3. publishEvent(AwsAccountSharingCreatedEvent)  (NEW)
                                            ▼
                                          [TX COMMIT]
                                            │
                                            ▼
            AwsAccountSharingNotificationListener  (@TransactionalEventListener AFTER_COMMIT)
                                            │  try { service.notifyTargetOfNewShare(event) }
                                            │  catch (e) { log.error(...) }   // never throws
                                            ▼
            AwsAccountSharingNotificationService.notifyTargetOfNewShare()
                                            │  resolve LAZY fields synchronously (caller thread)
                                            │  build subject + html + text
                                            │  CompletableFuture.supplyAsync {
                                            │     emailService.sendEmailWithInlineImages(...)
                                            │  }
                                            ▼
                                          target user receives email
```

## Components

### New files (5)

| File | Responsibility |
|---|---|
| `src/backendng/src/main/kotlin/com/secman/domain/AwsAccountSharingCreatedEvent.kt` | Plain Kotlin data class wrapping the saved `AwsAccountSharing` |
| `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingNotificationListener.kt` | `@TransactionalEventListener(TransactionPhase.AFTER_COMMIT)` → calls notification service; catches and logs all exceptions |
| `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingNotificationService.kt` | Resolves lazy fields, builds email content, dispatches async send via `EmailService.sendEmailWithInlineImages` |
| `src/backendng/src/main/resources/email-templates/aws-sharing-granted.html` | HTML template with inline `secman-logo` |
| `src/backendng/src/main/resources/email-templates/aws-sharing-granted.txt` | Plain-text fallback |

### Modified files (1)

| File | Change |
|---|---|
| `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt` | Inject `ApplicationEventPublisher<AwsAccountSharingCreatedEvent>`; add a single `eventPublisher.publishEvent(...)` call after `awsAccountSharingRepository.save(sharing)` in `createSharingRule()`. Pass `sharedAwsAccountCount` as a precomputed primitive on the event so the listener never re-queries. |

### No changes to

- DB schema (no migration)
- API contract (no new endpoint, no new field on existing responses)
- Frontend
- `NotificationPreference` table (kept scoped to its current single-purpose use)
- MCP tool definitions (the tool already calls the service; behavior changes transparently)

## Event shape

```kotlin
@Serdeable
data class AwsAccountSharingCreatedEvent(
    val sharingId: Long,
    val sourceUserEmail: String,
    val targetUserId: Long,
    val targetUserEmail: String,
    val targetUsername: String,
    val createdByEmail: String,
    val createdAtIso: String,
    val sharedAwsAccountCount: Int,
)
```

All fields are primitives/strings — no Hibernate proxies cross the event boundary. The service resolves them on its (transactional) thread before publishing. This eliminates the LAZY-proxy-in-async-thread bug class entirely, regardless of where the listener executes.

## Email content

**Subject:** `AWS account access shared with you in SecMan`

**Recipient:** `targetUserEmail` only. No CC, no BCC.

**Body** (HTML, mirrored verbatim in plain text):

- Salutation — `Hello {targetUsername},`
- Lead — `{sourceUserEmail} has shared their AWS account access with you in SecMan.`
- Explanation — Short paragraph: the target can now see assets and vulnerabilities tied to the source user's AWS accounts; access is one-way and non-transitive.
- Account count — `This share covers {sharedAwsAccountCount} AWS account(s).`
- Audit attribution — `Created by {createdByEmail} on {createdAtIso} (UTC).`
- Call to action — link to `${appConfig.backend.baseUrl}/assets`.
- Footer — `If you believe this share is incorrect, contact your SecMan administrator.`

The HTML version uses the same inline `secman-logo` CID image as `ExceptionRequestNotificationService` (loaded from `email-templates/SecManLogo.png`). The text version omits the image.

## Error handling

| Failure | Behavior |
|---|---|
| `EmailService` not configured (no SMTP) | `sendEmailWithInlineImages` returns `false`; service logs `WARN`; no exception propagates. Sharing rule still created. |
| Template resource missing | Notification service logs `ERROR` with the template path; skips send. Sharing rule still created. |
| SMTP timeout / network failure | `CompletableFuture` resolves to `false`; logged as `WARN`. Sharing rule still created. |
| Listener itself throws (defensive catch) | Caught in the listener; logged at `ERROR` with `sharingId`; never propagates. Transaction has already committed. |
| Source user has no AWS account mappings | Existing service-level validation rejects the request **before** save; no event is published; no email sent. |

The invariant: **email failures never affect the data outcome of `createSharingRule()`**. This matches `ExceptionRequestNotificationListener`'s contract.

## Testing

### Unit tests

1. `AwsAccountSharingNotificationServiceTest` (Mockk)
   - Verifies subject is `AWS account access shared with you in SecMan`.
   - Verifies HTML contains: source email, target username, account count, dashboard URL.
   - Verifies text content contains the same fields.
   - Verifies `EmailService.sendEmailWithInlineImages` is called exactly once with `to = targetUserEmail`.
   - Verifies graceful return when `EmailService` returns `false` (no exception thrown).

2. `AwsAccountSharingNotificationListenerTest` (Mockk)
   - Verifies `notifyTargetOfNewShare` is invoked on `AwsAccountSharingCreatedEvent`.
   - Verifies an exception thrown by the notification service is caught and logged, not propagated.

3. `AwsAccountSharingServiceTest` (extension of existing test class)
   - Verifies `ApplicationEventPublisher.publishEvent` is called exactly once after a successful `createSharingRule`.
   - Verifies the event is **not** published when validation throws (`DuplicateSharingException`, self-share, no source mappings).
   - Verifies the event payload carries the correct precomputed fields.

### Integration test (optional but recommended)

Extend an existing integration test under `BaseIntegrationTest` (Testcontainers MariaDB) to assert that calling the controller's `POST /api/aws-account-sharing` results in a published event. Stub `EmailService` so no actual SMTP traffic occurs. Gate with `@EnabledIf(DockerAvailable::isDockerAvailable)`.

### Mandatory CLAUDE.md gates

A change is complete only when:

1. `./gradlew build` is clean.
2. `./scripts/startbackenddev.sh` starts cleanly (verifies bean wiring of the new listener and event publisher) and is then stopped.
3. `/e2ejs` reports **0 JS errors** for both admin and normal-user runs.
4. `/e2evulnexception` reports **0 failures**.

Items 3 and 4 are non-negotiable per project policy, even though this feature does not touch those code paths. They serve as regression gates.

## Risks and considerations

- **Email volume**: AWS sharing creation is a low-frequency admin action. No throttling or batching needed.
- **Privacy**: The email reveals `sourceUserEmail` and `createdByEmail` to the target. All three users are already mutually visible in the SecMan UI (workgroups, sharing rules list), so this is not a new disclosure.
- **Internationalization**: The codebase has no i18n framework today; templates are English-only, consistent with existing email templates.
- **Inactive accounts**: If `targetUser.email` is invalid or the account is locked, the email bounces silently. The sharing rule still works as intended; the target will discover access via UI as before. Not a regression.

## Open questions

None. All design decisions resolved in brainstorming session 2026-05-06.

## Out of scope (deferred, not rejected)

- Email on **revocation** (`deleteSharingRule`).
- Email on **source-user-side** (e.g., "Your share to X was created").
- Per-user opt-out flag.

These can be added later as small follow-up changes against this same pattern.
