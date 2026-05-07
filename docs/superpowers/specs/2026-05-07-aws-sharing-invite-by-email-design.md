# AWS Account Sharing — Invite by Email

**Status:** Draft (design approved 2026-05-07)
**Owner:** Markus Schmall
**Related code:** `src/backendng/.../AwsAccountSharing*`, `src/frontend/src/components/AwsAccountSharingManager.tsx`

## Problem

Today, the AWS account sharing form lets a user pick a target from a dropdown
populated with existing SecMan users plus emails known via PENDING
`UserMapping` rows (typically imported from AWS account-mapping spreadsheets).

A user cannot share their AWS accounts with a colleague who has *neither* an
account *nor* a pending mapping — even when that colleague clearly belongs to
the same organization. The workaround today is to ask an admin to import a
mapping or pre-create the user, which is friction for a routine action.

## Goal

Let any authenticated user with sharing rights invite a new SecMan user by
typing their email address, provided that email belongs to the inviter's own
domain. The invitee gets:

- a SecMan account with roles `USER + VULN` and `authSource = OAUTH`,
- an email saying their account was just created and that AWS account access
  from the inviter is already waiting for them, with a link to log in via
  organization SSO.

## Non-goals

- Changing the legacy "type-an-email-of-a-pending-user-from-the-dropdown"
  lazy-create path. That path stays on its current `USER + VULN + REQ` default.
- Multi-domain allowlists (e.g. treating `covestro.net` and `covestro.com` as
  equivalent). Strict, single-domain match only.
- Local-auth password reset / set-password tokens. SSO is the assumed login
  path; lazy-created users have an unusable random password hash.
- A separate `Invitation` entity or dedicated audit table. The existing
  `AwsAccountSharing.createdBy` and `AUDIT:` log lines cover the audit need.
- Bulk invitations. One email per form submission.

## Decisions

| # | Question | Decision |
|---|---|---|
| 1 | Who can invite? | Any authenticated user who can already create their own outgoing share. Domain check is the safeguard. |
| 2 | What is "same domain"? | Strict, case-insensitive suffix match on the substring after the last `@`. Subdomains are different domains. |
| 3 | Roles for invited user | `USER + VULN` (only on this path; existing callers of `UserResolutionService` keep `USER + VULN + REQ`). |
| 4 | Login path | OAuth/SSO. Lazy-created `User` has `authSource = OAUTH` and an unusable password hash. Existing identity-provider machinery handles first login. |
| 5 | UI | Explicit radio toggle above the target field: *Pick existing user* (current dropdown) vs *Invite by email* (free-text email). Mutually exclusive. |
| 6 | Existing-email collisions | Reject. If the typed email matches an existing User or a PENDING UserMapping, return 409 and instruct the user to switch to "Pick existing user". |

## Architecture

Five components touched. No new entities. No DB migration.

### 1. Controller — `AwsAccountSharingController`

Add `inviteByEmail: Boolean = false` to `CreateAwsAccountSharingRequest`.
When `true`, the controller runs a dedicated validator before delegating to
the service:

- `targetUserEmail` is required, well-formed (exactly one `@`, non-empty
  local part, non-empty domain).
- The post-`@` substring (lowercased) must equal the caller's own
  post-`@` substring (lowercased).
- The email must not already belong to a `User` (case-insensitive) or to a
  PENDING `UserMapping` (case-insensitive).
- The email must not equal the caller's own email.
- The caller's own email must have a usable domain (not empty) — this should
  always be true for a real account; if it's not, return 500 with a clear
  message.

Failures map to:

| Condition | HTTP | Body message |
|---|---|---|
| Empty / malformed email | 400 | "A valid email address is required to invite a new user" |
| Domain mismatch | 400 | "You can only invite users whose email matches your own domain (`@<yourdomain>`)" |
| Caller has no usable domain | 500 | "Cannot determine your email domain — contact an administrator" |
| Email is caller's own | 400 | "You cannot share with yourself" |
| Email already a User | 409 | "This email is already a SecMan user — use 'Pick existing user' instead" |
| Email is a pending mapping | 409 | "This email is already known via mapping import — use 'Pick existing user' instead" |

If `inviteByEmail = false` (the default), the controller behaves exactly as
today; legacy callers are unaffected.

### 2. Service — `AwsAccountSharingService`

In `createSharingRule`, when invite intent is set:

1. Capture `wasJustCreated = userRepository.findByEmailIgnoreCase(targetEmail).isEmpty`
   *before* calling the resolution service.
2. Call `userResolutionService.resolveByIdOrEmail(null, targetEmail, "target",
   roles = setOf(User.Role.USER, User.Role.VULN))`.
3. Continue with the existing rule-creation logic (source AWS account check,
   per-account scope resolution, persist, publish event, invalidate MCP cache).
4. Pass `targetUserWasJustCreated = wasJustCreated` into
   `AwsAccountSharingCreatedEvent`.

When invite intent is *not* set, the existing code path is unchanged.

### 3. UserResolutionService

Add an optional parameter to `resolveByIdOrEmail`:

```kotlin
@Transactional
open fun resolveByIdOrEmail(
    userId: Long?,
    email: String?,
    context: String,
    roles: Set<User.Role>? = null,   // NEW — null preserves existing default
): User
```

When `roles == null` (every existing caller), keep the current default
`mutableSetOf(USER, VULN, REQ)`. When non-null, use the provided set verbatim
(converted to `MutableSet`).

Behavior is otherwise unchanged: the unique-username collision retry, the
synchronous PENDING-mapping apply, and the cross-session-event suppression all
remain in place.

### 4. Event + Notification

Add `targetUserWasJustCreated: Boolean` to `AwsAccountSharingCreatedEvent`.
The data class is `@Serdeable`; this is a backward-compatible additive change.

In `AwsAccountSharingNotificationService.substitute()`, support a
hand-rolled conditional block in the templates:

```
{ifNewAccount}
A SecMan account has just been created for you.
Log in via your organization SSO at {loginUrl}.
{/ifNewAccount}
```

When `event.targetUserWasJustCreated == true`, replace the markers with their
inner content and substitute `{loginUrl}` with `appConfig.frontend.baseUrl`
(the existing `AppConfig` already exposes this field). Otherwise, strip the
entire block including the markers. This keeps a single template for both
flows and is straightforward to unit-test.

Update both `aws-sharing-granted.html` and `aws-sharing-granted.txt`.

### 5. Frontend — `AwsAccountSharingManager.tsx`

Above the target field, add a Bootstrap radio group:

- `( ) Pick existing user` (default; current dropdown + search; current behavior)
- `( ) Invite by email` (renders an email textbox; helper text shows the
  inviter's domain rule, e.g. *"Email must end in `@example.com`"*)

State changes:

- `targetMode: 'existing' | 'invite'` — current default `'existing'`.
- `inviteEmail: string` — bound to the new textbox.

Submit-time logic:

- If `targetMode === 'invite'`: validate locally (well-formed email, domain
  match against `currentUser.email`), then submit
  `{ ..., targetUserEmail: inviteEmail, inviteByEmail: true }` (no
  `targetUserId`).
- If `targetMode === 'existing'`: behave exactly as today; do not send
  `inviteByEmail` or send `false`.

The per-account picker keys off the source user, which is unchanged in invite
mode (always the current user). When `targetMode` flips, clear `targetSearch`,
`targetSelection`, and `inviteEmail` to avoid stale state crossing modes.

Backend 409 responses (existing-email collision) are surfaced as a form-level
error suggesting the user switch to *Pick existing user*.

## Data flow (invite path)

```
Frontend form (Invite by email mode)
  └─ POST /api/aws-account-sharing
        body: { sourceUserId: <self>,
                targetUserEmail: "<typed>",
                inviteByEmail: true,
                awsAccountIds: [...] | null }

Controller
  ├─ Auth check (existing): non-privileged users may only share their own accounts
  ├─ Invite-mode validator (NEW): well-formed, domain match, not own,
  │  not existing User, not pending mapping
  └─ Delegate to AwsAccountSharingService.createSharingRule(...)

Service
  ├─ wasJustCreated = userRepo.findByEmailIgnoreCase(targetEmail).isEmpty
  ├─ resolveByIdOrEmail(null, targetEmail, "target", roles = {USER, VULN})
  │      └─ creates User row if absent
  ├─ Source AWS-mappings exist? (existing check)
  ├─ Resolve per-account scope (existing logic)
  ├─ Save AwsAccountSharing
  ├─ Publish AwsAccountSharingCreatedEvent(..., targetUserWasJustCreated = wasJustCreated)
  └─ mcpAccessCacheInvalidator.invalidate()

201 Created  ──▶  @Async listener
                    └─ AwsAccountSharingNotificationService
                         └─ Renders "AWS access shared" + (if new account)
                            an "account just created — log in via SSO" block
```

## Audit logging

- Augment the existing `AUDIT: AWS account sharing created: ...` line with
  `inviteCreatedUser=true|false`.
- New explicit line when an invite materializes a User:
  `AUDIT: AWS sharing invited user created: email={}, inviter={}, roles={USER, VULN}`.

## Tests

### Unit

- **Service**:
  - Domain match: positive, negative, subdomain rejected, case-insensitive both sides.
  - Email rejection cases: existing User, pending mapping, self-email.
  - New-user roles: assert the saved `User.roles` equals `{USER, VULN}` exactly.
  - Event payload: `targetUserWasJustCreated == true` on first create, `false` on subsequent share with the same (now-existing) email.
- **Notification**: template substitution renders the new-account block when flag true, strips the markers and inner text when false. Both `.html` and `.txt`.

### Controller

- 400 on missing/malformed email (invite mode).
- 400 on domain mismatch.
- 400 on self-invite.
- 409 on existing User collision.
- 409 on PENDING-mapping collision.
- 201 on successful invite.
- Authorization unchanged for the non-invite path.

### Integration (`@MicronautTest`, `BaseIntegrationTest`, gated by Docker)

- Full end-to-end invite: POST returns 201; `User` row exists with roles
  `{USER, VULN}`; `AwsAccountSharing` row exists; event published; (optionally)
  email service was invoked with the new-account block visible in the body.

### Manual / E2E

- `/e2ejs` admin + user runs must remain clean (mandatory project gate).
- `/e2evulnexception` must stay clean (mandatory project gate).
- A Playwright case for invite-by-email is recommended but not blocking for v1.

## Risks & considerations

1. **Domain extraction.** Use a single helper that returns the domain or
   throws on malformed input; share it between caller-self and target
   validation.
2. **Event-payload change.** `AwsAccountSharingCreatedEvent` is `@Serdeable`;
   adding a non-nullable Boolean is a backward-compatible additive change as
   long as all publishers are updated. Verify there's only one publisher
   (`AwsAccountSharingService.createSharingRule`).
3. **Template parity.** Both `.html` and `.txt` need the new block; the
   substitution unit test should cover both.
4. **User-create races.** `UserResolutionService` has a unique-constraint
   retry. Two concurrent invites for the same email resolve to one User; the
   second sharing rule may then fall under `DuplicateSharingException` (HTTP
   409) — acceptable. Note the `wasJustCreated` flag is captured before
   `resolveByIdOrEmail` runs, so under a tight race both invites may emit
   events with `targetUserWasJustCreated = true`. The downstream effect is
   that one of the two notification emails redundantly says "your account
   was just created" — accepted as a benign inaccuracy rather than locking
   the read+create into a serializable transaction.
5. **Privilege awareness.** Any authenticated user can now provision a
   `User` row via this endpoint, scoped by domain. The audit log line and
   `createdBy` field cover after-the-fact accountability.
6. **Login wall.** If identity providers are disabled or the domain is
   removed from the SSO allowlist between invite and first login, the user
   is stranded. Not unique to this feature; acceptable for v1.

## Migration / rollout

- No DB migration.
- Feature is additive; default form behavior is unchanged.
- Roll out together: backend + frontend in the same release. The new
  `inviteByEmail` flag defaults to `false`, so an old frontend talking to a
  new backend keeps working. A new frontend talking to an old backend would
  hit the legacy lazy-create path with `USER + VULN + REQ` roles and no
  domain check — therefore deploy backend first if blue/green.

## Open questions

None. (Resolve before implementation if any arise during planning.)
