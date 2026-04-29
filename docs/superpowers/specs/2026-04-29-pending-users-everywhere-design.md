# Pending Users in All User Selectors — Design

**Date:** 2026-04-29
**Status:** Draft (pending implementation)
**Author:** Markus Schmall (with Claude)

## Problem

Wherever secman lets a user be selected (workgroup membership, risk assessment
assignees, AWS account sharing), only **active** users —
those with a real `User` row — are listed. Admins commonly import AWS account
mappings (`UserMapping`) for people *before* those people ever log in, which
creates rows with `MappingStatus.PENDING` and `user_id = NULL`. These pending
emails are invisible to every selector except the AWS account sharing form.

The result: an admin who has just imported a mapping for `newhire@example.com`
cannot add that person to a workgroup or risk assessment until the person
logs in for the first time. This blocks legitimate access-control
preparation work.

(Demand requestors are derived server-side from the authenticated user as an
anti-impersonation measure, so demands have no user-selection UI to update.)

The AWS account sharing feature already solved this with a private endpoint
(`/api/aws-account-sharing/users`) that merges active Users + PENDING
UserMappings, plus a service-layer lazy-create
(`AwsAccountSharingService.resolveUser`) that materializes a `User` record on
first reference and applies any pending mappings synchronously.

This design generalizes that pattern across the application.

## Goals

1. Every UI user-selector shows pending users alongside active ones, with a
   visible badge.
2. Selecting a pending user materializes a real `User` row at save time and
   applies any pending `UserMapping`s for that email in the same transaction.
3. The unified read endpoint is `/api/users?includePending=true` (additive,
   backwards compatible).
4. The lazy-create logic lives in one place (`UserResolutionService`) and is
   reused by every save path.

## Non-Goals

- Admin → User Management CRUD UI does **not** show pending users; that
  surface manages real Users only.
- MCP tools (`assign_workgroup_users`, etc.) are **not** updated in this
  feature. Tracked as a follow-up.
- `UserMapping` schema is **not** changed.
- Notification preferences are unchanged: lazy-created users get the same
  defaults as OAuth-provisioned users (existing behavior).

## Architecture

### Backend

#### 1. `GET /api/users?includePending=true`

Extend the existing endpoint in
`src/backendng/src/main/kotlin/com/secman/controller/UserController.kt`.

Authorization: unchanged (`@Secured("ADMIN")`).

Response DTO change in `UserController.UserResponse`:

```kotlin
data class UserResponse(
    val id: Long?,             // CHANGED: nullable. null for pending entries.
    val username: String,
    val email: String,
    val roles: List<String>,    // empty list for pending entries
    val mfaEnabled: Boolean,    // false for pending entries
    val createdAt: String?,
    val updatedAt: String?,
    val lastLogin: String?,
    val workgroups: List<WorkgroupSummary>?,
    val workgroupCount: Int?,
    val isPending: Boolean = false   // NEW
)
```

Behavior:

- `includePending` absent or `false` → identical to today (back-compat).
- `includePending=true`:
  1. Load real Users (with or without workgroups depending on
     `includeWorkgroups`).
  2. Load `UserMapping` rows where `status = PENDING` via
     `UserMappingRepository.findByStatus(MappingStatus.PENDING)`.
  3. `distinctBy(email.lowercase())` and exclude any email already present in
     the real Users set.
  4. Map each remaining email to a `UserResponse` with `id = null`,
     `username = email.substringBefore("@").ifBlank { email }`,
     `roles = emptyList()`, `mfaEnabled = false`, `isPending = true`,
     and timestamp fields = `null`.
  5. Concatenate and sort by `email.lowercase()`.

Edge case: if a pending email is malformed or empty, skip it and log a
warning at `WARN` level (do not poison the dropdown).

#### 2. `UserResolutionService`

New file:
`src/backendng/src/main/kotlin/com/secman/service/UserResolutionService.kt`.

Extracts the body of `AwsAccountSharingService.resolveUser` (currently lines
130–186) plus the `resolveUniqueUsername` helper (which appends a numeric
suffix when the email-prefix username is already taken) and exposes them as
a reusable service.

```kotlin
@Singleton
open class UserResolutionService(
    private val userRepository: UserRepository,
    private val userMappingService: UserMappingService
) {
    private val passwordEncoder = BCryptPasswordEncoder()
    private val log = LoggerFactory.getLogger(UserResolutionService::class.java)

    /**
     * Resolve a User by id or email, lazy-creating a real User row if only
     * an email is provided and no User exists for that email yet. When a new
     * User is created, all PENDING UserMappings for that email are applied
     * synchronously in the current transaction.
     *
     * @param userId  primary key — wins over email if both provided
     * @param email   case-insensitive email; required if userId is null/0
     * @param context short label used only in error messages
     *                (e.g. "workgroup member", "risk assessment assignee")
     */
    @Transactional
    open fun resolveByIdOrEmail(userId: Long?, email: String?, context: String): User { ... }

    /**
     * Bulk version. Returns a list in the same order as input. Throws on the
     * first unresolvable selector.
     */
    @Transactional
    open fun resolveAll(refs: List<UserRef>, context: String): List<User> =
        refs.map { resolveByIdOrEmail(it.id, it.email, context) }

    @Serdeable
    data class UserRef(val id: Long?, val email: String?)
}
```

Implementation rules — all carried over verbatim from the existing sharing
implementation:

1. If `userId != null && userId > 0`, look it up; throw
   `NoSuchElementException` on miss.
2. Else, normalize the email; throw `IllegalArgumentException` if missing.
3. Look up by email (case-insensitive). If found, return.
4. Otherwise create a new User with:
   - `username = resolveUniqueUsername(email.substringBefore("@"))`
   - `passwordHash = passwordEncoder.encode(UUID.randomUUID().toString())`
   - `roles = mutableSetOf(USER, VULN, REQ)` (matches OAuth provisioning)
   - `authSource = AuthSource.OAUTH`
5. Save, then call `userMappingService.applyFutureUserMapping(saved)`
   inside the same transaction. **Do not publish `UserCreatedEvent`** — the
   default async listener opens a separate Hibernate session and trips the
   "shared references to a collection" check on the new User's `workgroups`
   PersistentSet. The synchronous call achieves the same outcome safely.
6. Wrap mapping application in try/catch — log a warning but don't fail the
   resolution if mapping application fails.

Race-condition handling: catch `DataIntegrityViolationException` /
unique-constraint violations on the `userRepository.save(...)` call, then
re-query by email and return the now-existing row. (A second request created
the same email between our check and our insert.)

`AwsAccountSharingService.resolveUser` is reduced to a thin delegation:
`userResolutionService.resolveByIdOrEmail(userId, email, side)`.

#### 3. Save endpoints — selector-shaped input

All affected save paths accept the `UserRef` shape `{id?, email?}` while
remaining backwards-compatible with id-only payloads.

| Controller | Endpoint | Today | After |
|---|---|---|---|
| `WorkgroupController` | `POST /api/workgroups/{id}/users` | `{userIds: [Long]}` | accept either `{userIds: [Long]}` or `{userRefs: [{id?, email?}]}` |
| `RiskAssessmentController` | `assessorId`, `respondentId` on create/update | id-only | accept `assessorRef`, `respondentRef` (id-or-email); legacy id fields preserved |
| `AwsAccountSharingController` | unchanged | already accepts both | delegates to `UserResolutionService` |

For each, the controller calls
`userResolutionService.resolveAll(refs, "<context>")` (or the singular form)
before persisting the relation.

The legacy `userIds: [Long]` field stays in place to avoid breaking external
clients (CLI, scripts). When both shapes appear, `userRefs` wins.

#### 4. Sharing endpoint cleanup

`/api/aws-account-sharing/users` stays in place for one release with a
`@Deprecated` Kotlin annotation and a migration note. The frontend
`AwsAccountSharingManager.tsx` switches to the unified
`/api/users?includePending=true`. This is optional but completes the
unification.

### Frontend

Affected components (all in `src/frontend/src/components/`):

- `WorkgroupManagement.tsx`
- `RiskAssessmentManagement.tsx`
- `RiskAssessmentManagement.new.tsx`
- `AwsAccountSharingManager.tsx` (migrate to unified endpoint)

#### Selection model

State changes from `Array<number>` (user ids) to
`Array<{ id?: number; email: string }>`. The dedicated `email` field is
always present; `id` is present for active users only.

On checkbox toggle:
- Active user → push `{ id: user.id!, email: user.email }`
- Pending user → push `{ email: user.email }`

On submit:
- Workgroup: `POST /api/workgroups/{id}/users` body =
  `{ userRefs: selected }` (legacy `userIds` shape still accepted).
- Risk assessment create/update: body adds `assessorRef: {id?, email?}` and
  optional `respondentRef`; legacy `assessorId` / `respondentId` preserved.

#### Display

Inside each `users.map(...)` render block:

```tsx
<label>
  <input type="checkbox" ... />
  <strong>{user.username}</strong> ({user.email})
  {user.isPending && (
    <span
      className="badge bg-warning text-dark ms-2"
      title="This email is known via AWS / domain mapping but has never logged in. Selecting it will create an account placeholder."
    >
      pending
    </span>
  )}
</label>
```

Pending entries are not styled differently otherwise — same row, same
checkbox affordance — so the admin can act on them without friction.

#### Fetch

The three selection components (`WorkgroupManagement`, the two
`RiskAssessmentManagement` variants) plus `AwsAccountSharingManager` switch
to `authenticatedGet('/api/users?includePending=true')`. The response shape
is back-compat (existing fields stay), with two new optional fields:
`id?: number | null` and `isPending?: boolean`.

### Authorization

`/api/users?includePending=true` requires ADMIN, same as today. Workgroup,
risk assessment, and demand save endpoints are already access-controlled —
no change to their security rules.

## Data Flow — End to End

1. Admin opens the workgroup-assign dialog.
2. Frontend calls `GET /api/users?includePending=true`.
3. Backend merges active Users + PENDING UserMapping emails, returns combined
   list with `isPending` flags.
4. Admin checks "newhire@example.com" (rendered with [pending] badge).
5. Frontend submits `POST /api/workgroups/42/users` with body
   `{ userRefs: [{ email: "newhire@example.com" }, { id: 7, email: "..." }] }`.
6. `WorkgroupController` calls `userResolutionService.resolveAll(refs, "workgroup member")`.
7. For the pending entry: `UserResolutionService` finds no matching User,
   creates one (OAuth shape, unusable password), applies any PENDING
   UserMappings for that email (linking them to the new User and flipping
   them to ACTIVE).
8. Workgroup membership is persisted with the freshly-resolved User ids.
9. When `newhire@example.com` later logs in via OAuth, `UserService` finds
   the existing User by email and reuses it; the workgroup membership and
   AWS mappings are already in place.

## Error Handling

| Scenario | Handling |
|---|---|
| Race: two requests pick the same pending email at the same time | First wins; second catches `DataIntegrityViolationException`, re-queries by email, returns existing row. No user-facing error. |
| Pending email collides with an active User mid-session | `resolveByIdOrEmail` finds the active row by email and uses it. Selection succeeds. |
| Pending UserMapping row has malformed email | Skipped during merge; logged at WARN. Dropdown stays clean. |
| Mapping application fails after lazy-create | Logged at WARN; lazy-create still succeeds. Workgroup save proceeds. (Existing sharing behavior.) |
| Selector contains neither id nor email | `IllegalArgumentException` with the context label, returned as HTTP 400. |
| Selector id refers to deleted user | `NoSuchElementException`, returned as HTTP 404. |

## Testing Strategy

Per CLAUDE.md "Never write testcases" the project policy is to skip
test-writing during development. Verification will be done via:

- `./gradlew build` to ensure compilation and existing tests still pass.
- Manual UI walkthrough of each affected selector with at least one pending
  email present.
- E2E flow: import a UserMapping for a non-existent email, open the
  workgroup-assign dialog, select the pending entry, save, verify a real
  User row exists with the expected mappings applied.

## Migration / Rollout

- No database schema changes.
- No breaking API changes (additive flag, additive response fields,
  back-compat request bodies).
- Old `/api/aws-account-sharing/users` endpoint kept for one release with a
  deprecation note.
- No feature flag required — the change is invisible to non-ADMIN users and
  to admins who never click the new pending entries.

## Open Follow-ups (out of scope)

- Update MCP tools (`assign_workgroup_users`, etc.) to accept email-or-id
  selectors using `UserResolutionService`.
- After one release, delete `/api/aws-account-sharing/users`.
- Consider an admin-only "convert pending to real user" action in
  UserManagement.tsx for advance provisioning without going through a
  selector.

## File Inventory

New files:

- `src/backendng/src/main/kotlin/com/secman/service/UserResolutionService.kt`

Modified files (backend):

- `src/backendng/src/main/kotlin/com/secman/controller/UserController.kt`
- `src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt`
- `src/backendng/src/main/kotlin/com/secman/controller/RiskAssessmentController.kt`
- `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt`
  (delegate `resolveUser` to the new service; remove duplicated logic)
- `src/backendng/src/main/kotlin/com/secman/controller/AwsAccountSharingController.kt`
  (mark `/users` endpoint deprecated)

Modified files (frontend):

- `src/frontend/src/components/WorkgroupManagement.tsx`
- `src/frontend/src/components/RiskAssessmentManagement.tsx`
- `src/frontend/src/components/RiskAssessmentManagement.new.tsx`
- `src/frontend/src/components/AwsAccountSharingManager.tsx`
  (switch to unified endpoint)
