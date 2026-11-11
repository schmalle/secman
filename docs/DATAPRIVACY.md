# Data privacy: deletion of users and user-related data

| | |
|---|---|
| **Analysis date** | 2026-11-11 |
| **Code revision analysed** | `main` @ `003401bab` (merge of PR #578, "inactive user cleanup") |
| **Method** | Source-code review of the backend (`src/backendng/`): entities, repositories, services, schedulers, Flyway migrations and `application.yml` defaults. Not a review of a running installation. |
| **Scope** | Data held in the secman application database. Out of scope: database backups, server/application log files, mail server copies, CrowdStrike/AWS source systems, and the separate `extensions/` clients. |

This document describes **what the code does**. Whether a feature is active in a
given installation depends on its configuration (see §7); defaults are stated
wherever they matter.

---

## 1. Summary

1. A user account can be deleted in three ways, all running the **same deletion
   routine** (`UserService.deleteUser`): manually by an ADMIN in the UI/REST API,
   by an ADMIN via the MCP tool `delete_user`, or automatically by the nightly
   **inactive user cleanup**.
2. The automatic cleanup deletes accounts inactive for **365 days**, after a
   **warning e-mail at least 30 days in advance**. It is **disabled by default**
   and starts in **dry-run mode**; it must be switched on by operations.
3. On deletion, **personal settings and credentials are deleted** (password hash,
   passkeys, API keys, chat/notification settings, profile picture, personal
   mappings, sharing grants, memberships).
4. **Business and audit records are kept** (vulnerability exceptions, exception
   requests, their audit trail, requirement comments, risk records). The link to
   the user account is removed, but **the username stored as text in these records
   remains**. These records are therefore *detached*, not *anonymised*.
5. **Vulnerability exceptions and exception requests have no automatic time-based
   deletion.** They expire (status change) but stay in the database until someone
   deletes them.

---

## 2. Personal data stored about a user

The `users` table (`domain/User.kt`) holds: username, e-mail, password hash
(BCrypt; empty for OAuth users), roles, enabled flag, service-account flag, MFA
flag, authentication source (LOCAL/OAuth), `created_at`, `updated_at`,
`last_login`, `inactivity_warning_sent_at`.

Linked per-user data: passkey credentials, MCP API keys, Slack/Telegram settings
(credentials encrypted at rest via `EncryptedStringConverter`), notification
subscriptions and preferences, dashboard preferences, profile picture, relay
identities, workgroup memberships, personal AWS-account/AD-domain mappings,
AWS-account sharing grants.

Business records in which a user appears as actor: exception requests (requester,
reviewer), exception audit log (actor, client IP), exceptions (`created_by`),
requirement comments (author), risk assessments (respondent e-mail), reviews and
alignment decisions, releases, demands, assets (manual creator, scan uploader),
MCP audit log (delegated user e-mail, client IP, user agent).

---

## 3. When users are deleted

### 3.1 Manual deletion (on request, e.g. an erasure request)

- REST `DELETE /api/users/{id}` — ADMIN only.
- MCP tool `delete_user` — ADMIN delegation only.
- Before deleting, `UserDeletionValidator` checks for references the database
  cannot detach. Deletion is **refused** (with the reason shown) when the user is:
  the last ADMIN, assessor or requestor of a risk assessment, requestor of a
  demand, uploader of a requirement file, or the service user of an integration
  scanner. Those references must be reassigned first.

### 3.2 Automatic deletion of inactive accounts

Code: `InactiveUserCleanupScheduler` → `InactiveUserCleanupService` →
`InactiveUserDeletionExecutor`. Full operator documentation: `docs/USER_CLEANUP.md`.

| Step | Timing / rule |
|---|---|
| Schedule | Nightly at **03:15** server time |
| Inactivity | No activity for **365 days** (`USER_CLEANUP_INACTIVE_DAYS`). Activity = last login (password, OAuth, passkey) or a delegated MCP call; for never-logged-in users the creation date. |
| Warning | E-mail `inactive-account-warning` sent once the user enters the last **30 days** (`USER_CLEANUP_WARNING_DAYS`), stating the earliest deletion date. |
| Deletion | Only if the warning was sent **at least 30 days earlier** and the user has not logged in since. A login cancels the pending deletion. |
| Never deleted automatically | ADMIN accounts, service accounts, owners of an MCP API key used within the inactivity period, users with blocking references (§3.1). These are recorded as "skipped" with a reason. |
| Safety brake | If more than **10 %** of all users would be deleted in one run, no one is deleted (`USER_CLEANUP_MAX_DELETE_PERCENT`). |
| Volume cap | At most **500** warnings and 500 deletions per run (`USER_CLEANUP_MAX_USERS_PER_RUN`). |
| Default state | **Disabled** (`USER_CLEANUP_ENABLED=false`) and **dry-run** (`USER_CLEANUP_DRY_RUN=true`). |

A failed warning e-mail means the user is not considered warned and therefore not
deleted; the warning is retried the next night.

---

## 4. What happens to the data on deletion

All steps run in one database transaction (`UserService.deleteUser`,
`UserSideDataRepositories.removeFor`). Either everything below happens, or nothing.

### 4.1 Deleted

| Data | Notes |
|---|---|
| User account row | incl. password hash, roles, MFA flag, timestamps |
| Passkey credentials | |
| MCP API keys | prevents a key outliving its owner (V298 additionally deactivated keys left by earlier deletions) |
| Slack / Telegram settings, notification subscriptions, notification preferences, dashboard preferences | |
| Profile picture | database cascade (`ON DELETE CASCADE`, V251) |
| Relay identities (mobile app) | |
| Personal AWS-account / AD-domain mappings | may be re-created as "future user" mappings by the next mapping import |
| AWS-account sharing | in both directions, and grants the user created |
| Workgroup memberships | the workgroups themselves stay |
| Alignment reviewer entries and the review decisions made by the user | |

### 4.2 Kept, link to the user removed ("detached")

The foreign key to the user is set to `NULL`; the record stays.

| Record | Personal data that **remains** in the record |
|---|---|
| Vulnerability exception requests | `requested_by_username`, `reviewed_by_username`, reason, review comment |
| Exception request audit log | `actor_username`, `client_ip` (audit log is immutable by design) |
| Assets (manual creator, scan uploader) | none; asset `owner` is free text and unchanged |
| Risks (owner), risk assessments (respondent link) | `respondent_email` text field |
| Releases, demands, classification rules, maintenance banners, workgroup AWS/AD grants (creator) | |
| Export jobs, AI suggestion jobs (actor) | |
| Assessment assignments | revoked, kept as history |

### 4.3 Kept unchanged (not touched by user deletion)

| Record | Personal data |
|---|---|
| Vulnerability exceptions | `created_by` (username, text) |
| Requirement comments | `author_name` (username) and comment text; `author_id` is a plain id, not a foreign key |
| MCP audit log | `delegated_user_email`, `client_ip`, `user_agent` |
| Notification logs | `owner_email` |
| Workgroups | `owner_email`, if the deleted user was the named owner (reported, not changed) |
| User cleanup run items | user id, username and e-mail of each deleted user — deliberately kept **indefinitely** as the only record of who was removed |

**Assessment note for data privacy:** after deletion, the username/e-mail of the
former user remains visible in the records of §4.2 and §4.3. If the requirement
is anonymisation or pseudonymisation of these fields, the current code does **not**
do that.

A deleted OAuth user who later signs in again gets a fresh account with the base
role `USER` and no workgroups; nothing from the old account is restored.

---

## 5. Exceptions and exception requests

### 5.1 Lifecycle

- A user files an **exception request** (status `PENDING`), which ADMIN/SECCHAMPION
  approve or reject. Approval creates a **vulnerability exception**. Every request
  has a mandatory expiration date.
- Each state change writes a row to the **exception request audit log** (actor,
  username, timestamp, client IP). The audit log has no update or delete
  operation (`ExceptionRequestAuditLogRepository`).

### 5.2 Time-based processing

| Job | When | Effect |
|---|---|---|
| `ExceptionExpirationScheduler.processExpirations` | daily 00:00 | `APPROVED` requests past their expiration date → status `EXPIRED`; related exception deactivated; requester notified; audit entry written |
| `ExceptionExpirationScheduler` reminder | daily 08:00 | reminder e-mail 7 days before expiry |
| `ExceptionMaterializationService` | hourly, plus full recompute daily 03:00 | expired exceptions stop hiding vulnerabilities |

**Expiry is a status change, not a deletion.** There is no scheduled job that
deletes expired, rejected or cancelled exceptions, requests or audit entries.
They are retained until deleted manually.

### 5.3 Manual deletion

| Action | Who | Effect |
|---|---|---|
| `DELETE /api/vulnerability-exception-requests/{id}` | the requester | *cancel*: status `CANCELLED`, record kept |
| `DELETE /api/vulnerability-exception-requests/{id}/delete` | the requester | **permanently deletes** the request (and its exception if approved); the audit log entries stay |
| `DELETE /api/vulnerability-exceptions/{id}` | ADMIN, SECCHAMPION | deletes one exception |
| `DELETE /api/vulnerability-exceptions/all` | ADMIN, with explicit confirmation body | deletes all exceptions |

### 5.4 On user deletion

See §4.2: requests stay with requester/reviewer link set to `NULL` and the
usernames kept as text. Pending requests stay pending so reviewers can still
decide them.

---

## 6. Other retention rules found in the code

| Data | Retention | Automatic? |
|---|---|---|
| Vulnerability export files | 24 h after completion (`secman.export.file-retention-hours`); job row kept as `EXPIRED` | yes, hourly |
| AWS account welcome-mail log (`OwnerMailNotification`) | owner e-mail and account ids redacted after 90 days | yes, hourly |
| OAuth login state | deleted after expiry | yes, every 5 min |
| AI Buddy jobs and reports | in memory only, removed after the configured retention | yes, every 15 s |
| MCP audit log | 90 days | **no** — only when an ADMIN triggers `POST /api/mcp/admin/maintenance` |
| Inactive MCP sessions | cleanup routine | **no** — same manual maintenance endpoint |
| Exception requests, exceptions, exception audit log, requirement comments, notification logs | no limit | no |
| User cleanup run records | indefinitely (by decision) | no |

---

## 7. Technical cornerstones

- **Single deletion path.** UI, REST, MCP and the nightly job all call
  `UserService.deleteUser`; behaviour cannot diverge between channels.
- **Atomic.** Deletion and all detach/delete steps share one transaction. The
  automatic cleanup additionally locks the user row (`SELECT … FOR UPDATE`) and
  re-checks activity, warning and exclusions before deleting, so a login that
  races the deletion wins.
- **Fail-closed validation.** `UserDeletionValidator` refuses deletions that
  would break mandatory references instead of deleting dependent business data.
- **Credentials do not outlive the account.** API keys, passkeys and chat
  credentials are deleted with the user; chat credentials are stored encrypted
  (`EncryptedStringConverter`) and never returned by the API.
- **Access control.** All user-deletion and cleanup endpoints are ADMIN-only
  (`@Secured("ADMIN")`); exception approval is ADMIN/SECCHAMPION; a requester can
  only cancel/delete their own requests.
- **Traceability.** Every automatic run writes a `user_cleanup_run` row and one
  `user_cleanup_run_item` per affected user (outcome, reason, details). ADMINs can
  read them via `GET /api/user-cleanup/runs[/{id}]` and preview the next run via
  `GET /api/user-cleanup/preview` without side effects.
- **Safety brake and dry run** protect against mass deletion caused by a faulty
  activity signal.
- **Passwords** are stored as BCrypt hashes only.

### Configuration (environment variables)

| Variable | Default |
|---|---|
| `USER_CLEANUP_ENABLED` | `false` |
| `USER_CLEANUP_DRY_RUN` | `true` |
| `USER_CLEANUP_INACTIVE_DAYS` | `365` |
| `USER_CLEANUP_WARNING_DAYS` | `30` |
| `USER_CLEANUP_MAX_DELETE_PERCENT` | `10` |
| `USER_CLEANUP_MAX_USERS_PER_RUN` | `500` |

---

## 8. Open points for the data privacy review

1. Usernames/e-mails remain in exception requests, exception audit log,
   exceptions, requirement comments, MCP audit log and notification logs after
   the account is deleted (§4.2, §4.3). Decide whether this is justified as an
   audit trail or must be pseudonymised.
2. No retention period exists for exceptions, exception requests and their audit
   log (§5.2).
3. The 90-day MCP audit log cleanup runs only when triggered manually (§6).
4. Automatic inactive-user deletion is off by default; whether it is enabled in
   production is a configuration fact to confirm with operations.
5. The user cleanup run record keeps username and e-mail of deleted users
   indefinitely (deliberate decision, §4.3).
6. Backups and log files are outside this analysis; their retention is governed
   by operations.

## Source references

`service/UserService.kt` (`deleteUser`), `service/UserSideDataRepositories.kt`,
`service/UserDeletionValidator.kt`, `service/InactiveUser*.kt`,
`scheduler/InactiveUserCleanupScheduler.kt`, `scheduler/ExceptionExpirationScheduler.kt`,
`service/VulnerabilityExceptionRequestService.kt` (`cancelRequest`, `deleteRequest`),
`controller/VulnerabilityExceptionRequestController.kt`,
`controller/VulnerabilityManagementController.kt`, `service/ExportJobService.kt`,
`service/OwnerMailDeliveryService.kt`, `service/McpAuthenticationService.kt`,
`controller/McpAdminController.kt`, `domain/VulnerabilityExceptionRequest.kt`,
`domain/ExceptionRequestAuditLog.kt`, `domain/RequirementComment.kt`,
`resources/application.yml` (`secman.user-cleanup`), migrations V251, V297, V298.
All paths relative to `src/backendng/src/main/kotlin/com/secman/` unless noted.
Operator detail: `docs/USER_CLEANUP.md`.
