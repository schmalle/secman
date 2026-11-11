# Inactive user cleanup

A nightly job (03:15, after the 02:30 CrowdStrike cleanup) deletes accounts nobody has
used for `inactive-days` (default 365). It sends a warning first and only deletes someone
whose warning went out at least `warning-days` (default 30) earlier. It is **off by
default** and starts in **dry-run**.

Code: `InactiveUserCleanupScheduler` → `InactiveUserCleanupService` →
`InactiveUserDeletionExecutor` → `UserDeletionValidator` + `UserService.deleteUser`, the
same deletion path as `DELETE /api/users/{id}`.

## What counts as activity

`last activity = last_login`, or `created_at` for someone who never logged in.
`last_login` moves on:

- password login (`POST /api/auth/login`), OAuth login, passkey login;
- delegated MCP calls (`X-MCP-User-Email` on `/mcp` and the legacy MCP endpoint), written
  at most once per user per day.

The external heatmap's delegation does **not** count, so an automated poller cannot keep
a departed person's account alive. V297 back-filled `last_login` from
`passkey_credentials.last_used_at`, because passkey logins did not set it before. MCP-only
use from before this release is not back-filled; those users rely on the warning.

## What a run does

Candidates are users whose last activity lies before `now − (inactive-days − warning-days)`,
longest-inactive first, at most 20,000 per run (`inputTruncated` in the summary when hit).

1. **Exclusions**, checked only for users the run would act on. Excluded users are recorded
   as `SKIPPED` with a reason and are never warned:

   | Reason | Rule |
   |---|---|
   | `SERVICE_ACCOUNT` | `service_account = true` |
   | `ADMIN` | has ADMIN — reported, never deleted automatically |
   | `ACTIVE_API_KEY` | owns an MCP API key used within `inactive-days` |
   | `BLOCKING_REFERENCES` | `UserDeletionValidator` refuses (see below) |

2. **Warning.** A user inside the warning window with no warning since their last activity
   gets the `inactive-account-warning` mail, saying the account is deleted "on or after"
   a date (at least `warning-days` away). At most `max-users-per-run` mails per run.
   Outcome `WARNED`, or `WARNING_FAILED` when the mail could not be sent (that user is
   not warned, so not deletable, and is retried next night). A login invalidates an old
   warning automatically.
3. **Safety brake.** If the deletable users exceed `max-delete-percent` of *all* users, no
   one is deleted (`ABORTED_BRAKE`); warnings still go out. The brake is a hard stop: clear
   a genuine backlog by raising `max-delete-percent` once.
4. **Deletion**, at most `max-users-per-run`, each in its own transaction after re-reading
   the user. Someone who logged in meanwhile is `SKIPPED/ACTIVE_AGAIN`. Other skip reasons:
   `NOT_WARNED` (inactive, warning younger than `warning-days`), `RUN_CAP`, `SAFETY_BRAKE`.
5. **Record.** One `user_cleanup_run` row and one `user_cleanup_run_item` per user acted on,
   including the deleted user's id, username and email (decided: this is the only record of
   who was removed; kept indefinitely). A chat event `INACTIVE_USER_CLEANUP_COMPLETED` carries
   counts only.

A **dry run** sends nothing and writes only the run record. It reports `WOULD_WARN` and
`WOULD_DELETE` as if the warnings had already gone out, so it shows the eventual impact.

Rollout: enable with dry-run on, read `GET /api/user-cleanup/preview` or the run record,
then switch dry-run off. The first `warning-days` of live runs only warn.

## What deletion does to linked data

`UserDeletionValidator` blocks only on references the database cannot detach: last ADMIN,
risk-assessment assessor/requestor, demand requestor, requirement-file uploader and
integration-scanner service user. Everything else is detached by `deleteUser` — this also
changed the manual delete, which used to refuse users who had, for example, answered a risk
assessment or created a release.

- **Assets** stay. `owner` is free text and unchanged; creator/uploader links are nulled.
- **Workgroup membership** is removed. A workgroup left with no members is listed in the
  run's `orphanedWorkgroupIds` (its assets are then visible only to ADMIN/SECCHAMPION);
  one whose `owner_email` is the deleted user's is listed in `staleOwnerWorkgroupIds`.
  Neither is changed.
- **Personal AWS/AD mappings** are deleted; the next mapping import recreates them as
  future-user mappings. **AWS account sharing** is deleted in both directions; the
  counterparts are listed in the item's details.
- **MCP API keys**, Slack/Telegram settings, notification subscriptions and preferences,
  dashboard preferences, profile picture and relay identities are deleted.
- **Assessment assignments** are revoked (kept as history); export and AI-suggestion jobs
  keep their row with the user link nulled.
- **Exception requests** keep their row with requester/reviewer nulled; pending ones stay
  pending for reviewers.
- A deleted **OAuth user who signs in again** is provisioned afresh: USER role, no workgroups.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `USER_CLEANUP_ENABLED` | `false` | master switch for the nightly job and `run-now` |
| `USER_CLEANUP_DRY_RUN` | `true` | report only, send and delete nothing |
| `USER_CLEANUP_INACTIVE_DAYS` | `365` | inactivity before deletion |
| `USER_CLEANUP_WARNING_DAYS` | `30` | warning lead time; must be `> 0` and `< inactive-days` |
| `USER_CLEANUP_MAX_DELETE_PERCENT` | `10` | safety brake, share of all users |
| `USER_CLEANUP_MAX_USERS_PER_RUN` | `500` | cap on warnings and on deletions per run |

An invalid combination makes the run refuse to start (`400` on `run-now`, a warning in the
log for the cron).

## Endpoints (ADMIN only)

| Endpoint | Purpose |
|---|---|
| `GET /api/user-cleanup/config` | settings plus `validationError` |
| `GET /api/user-cleanup/preview?page&size` | tonight's run as a dry run, nothing written |
| `GET /api/user-cleanup/runs?page&size` | run history, newest first |
| `GET /api/user-cleanup/runs/{id}?page&size` | one run with its items |
| `POST /api/user-cleanup/run-now` | the scheduled policy now, brake included; `409` when disabled or already running |
