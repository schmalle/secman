# Pending Users in All User Selectors — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Project rule (CLAUDE.md):** "Never write testcases." This project does NOT practice TDD. Verification steps in this plan use `./gradlew build` (compile + existing tests pass) and manual UI walkthroughs in place of new test code. Do not add new test files.

**Goal:** Show pending users (recorded only via `UserMapping` PENDING rows) alongside active users in every user-selector in the UI; lazy-create a real `User` row when one is selected for assignment.

**Architecture:** Add `?includePending=true` to `GET /api/users` to return a unified list with an `isPending` flag and nullable `id`. Extract the existing lazy-create logic from `AwsAccountSharingService.resolveUser` into a new `UserResolutionService` that every save path delegates to. Update Workgroup, Risk Assessment, and AWS Sharing UIs/controllers to use the unified endpoint and accept `{id?, email?}` selectors with id-only legacy bodies still supported.

**Tech Stack:** Kotlin 2.3.20 / Micronaut 4.10 (backend) · Astro 6.1 / React 19 / TypeScript / Bootstrap 5.3 (frontend) · Hibernate JPA · MariaDB

**Spec:** `docs/superpowers/specs/2026-04-29-pending-users-everywhere-design.md`

---

## File Inventory

**New:**
- `src/backendng/src/main/kotlin/com/secman/service/UserResolutionService.kt`

**Modified backend:**
- `src/backendng/src/main/kotlin/com/secman/controller/UserController.kt`
- `src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt`
- `src/backendng/src/main/kotlin/com/secman/controller/RiskAssessmentController.kt`
- `src/backendng/src/main/kotlin/com/secman/controller/AwsAccountSharingController.kt`
- `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt`

**Modified frontend:**
- `src/frontend/src/components/WorkgroupManagement.tsx`
- `src/frontend/src/components/RiskAssessmentManagement.tsx`
- `src/frontend/src/components/RiskAssessmentManagement.new.tsx`
- `src/frontend/src/components/AwsAccountSharingManager.tsx`

---

## Task 1: Create `UserResolutionService` with extracted logic

**Files:**
- Create: `src/backendng/src/main/kotlin/com/secman/service/UserResolutionService.kt`

- [ ] **Step 1: Create the new service file**

Create `src/backendng/src/main/kotlin/com/secman/service/UserResolutionService.kt`:

```kotlin
package com.secman.service

import com.secman.domain.User
import com.secman.repository.UserRepository
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.hibernate.exception.ConstraintViolationException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.UUID

/**
 * Resolves a user by id or email, lazy-creating a real User row when an
 * email is given but no matching User exists yet (a "pending" user known
 * only via PENDING UserMapping rows).
 *
 * Extracted from AwsAccountSharingService.resolveUser so all save paths
 * (workgroup membership, risk assessment assignees, AWS sharing) share
 * the same materialization behavior.
 *
 * IMPORTANT: When a new User is created here we apply pending UserMappings
 * SYNCHRONOUSLY in the current transaction. We deliberately do NOT publish
 * UserCreatedEvent — see the inline comment for the Hibernate cross-session
 * collection-ownership reason.
 */
@Singleton
open class UserResolutionService(
    private val userRepository: UserRepository,
    private val userMappingService: UserMappingService
) {
    private val log = LoggerFactory.getLogger(UserResolutionService::class.java)
    private val passwordEncoder = BCryptPasswordEncoder()

    @Serdeable
    data class UserRef(val id: Long? = null, val email: String? = null)

    /**
     * @param userId  primary key — wins over email when both provided
     * @param email   case-insensitive email; required if userId is null/0
     * @param context short label used only in error messages
     */
    @Transactional
    open fun resolveByIdOrEmail(userId: Long?, email: String?, context: String): User {
        if (userId != null && userId > 0) {
            return userRepository.findById(userId)
                .orElseThrow { NoSuchElementException("$context user not found: id=$userId") }
        }
        val normalizedEmail = email?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("$context user identifier required (id or email)")

        userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null)?.let { return it }

        log.info("Creating User row on demand for {} email: {}", context, normalizedEmail)
        val username = resolveUniqueUsername(normalizedEmail.substringBefore("@").ifBlank { normalizedEmail })
        val newUser = User(
            username = username,
            email = normalizedEmail,
            passwordHash = passwordEncoder.encode(UUID.randomUUID().toString())!!,
            roles = mutableSetOf(User.Role.USER, User.Role.VULN, User.Role.REQ),
            authSource = User.AuthSource.OAUTH
        )
        val saved = try {
            userRepository.save(newUser)
        } catch (e: Exception) {
            // Another request may have created the same email between our check and our insert.
            // Walk the cause chain — Hibernate wraps unique-constraint hits as
            // org.hibernate.exception.ConstraintViolationException nested inside
            // PersistenceException / RollbackException depending on call site.
            val isUniqueRace = generateSequence<Throwable>(e) { it.cause }
                .any { it is ConstraintViolationException }
            if (!isUniqueRace) throw e
            log.info("Race on lazy User create for {} — re-querying", normalizedEmail)
            return userRepository.findByEmailIgnoreCase(normalizedEmail).orElseThrow {
                IllegalStateException("Lazy create failed and email still missing: $normalizedEmail", e)
            }
        }

        // Apply PENDING UserMappings synchronously in this transaction.
        //
        // We deliberately do NOT publish UserCreatedEvent: the default listener
        // (UserMappingService.onUserCreated) is @Async @Transactional, which opens
        // a SEPARATE Hibernate session while ours still owns the new User's
        // `workgroups` PersistentSet. That cross-session collection ownership
        // trips Hibernate's "Found shared references to a collection" check.
        try {
            val applied = userMappingService.applyFutureUserMapping(saved)
            if (applied > 0) {
                log.info("Applied {} pending mapping(s) to lazily-created user: {}", applied, saved.email)
            }
        } catch (e: Exception) {
            log.warn("Failed to apply pending mappings for {}: {}", saved.email, e.message)
        }
        return saved
    }

    @Transactional
    open fun resolveAll(refs: List<UserRef>, context: String): List<User> =
        refs.map { resolveByIdOrEmail(it.id, it.email, context) }

    /**
     * Append a numeric suffix when the email-prefix username is already taken.
     * Mirrors the existing AwsAccountSharingService.resolveUniqueUsername helper.
     */
    private fun resolveUniqueUsername(base: String): String {
        if (userRepository.findByUsername(base).isEmpty) return base
        var n = 2
        while (userRepository.findByUsername("$base$n").isPresent) n++
        return "$base$n"
    }
}
```

- [ ] **Step 2: Verify the existing `resolveUniqueUsername` signature matches**

Check that the helper in `AwsAccountSharingService` uses `userRepository.findByUsername(...)`. Run:

```bash
grep -n "resolveUniqueUsername\|findByUsername" src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt
```

Expected: a private function named `resolveUniqueUsername` calling `findByUsername`. If it differs (e.g. uses `existsByUsername` instead), update Step 1's helper to match the existing repository surface.

- [ ] **Step 3: Compile the backend**

Run:

```bash
./gradlew :backendng:compileKotlin
```

Expected: BUILD SUCCESSFUL. (No tests run yet; we just want the new service to compile.)

- [ ] **Step 4: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/service/UserResolutionService.kt
git commit -m "feat(backend): add UserResolutionService for lazy User materialization

Extracts the lazy-create-on-selection logic that currently lives only in
AwsAccountSharingService so every user-selector save path can reuse it.
"
```

---

## Task 2: Refactor `AwsAccountSharingService` to delegate

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt`

- [ ] **Step 1: Inject the new service**

Open `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt`. Find the constructor parameter list and add `userResolutionService: UserResolutionService`. Example:

```kotlin
class AwsAccountSharingService(
    private val sharingRepository: AwsAccountSharingRepository,
    private val userRepository: UserRepository,
    private val userMappingService: UserMappingService,
    private val userResolutionService: UserResolutionService
) { ... }
```

- [ ] **Step 2: Replace the body of `resolveUser`**

Find the existing `private fun resolveUser(userId: Long?, email: String?, side: String): User { ... }` (around line 144). Replace its body with a single delegation:

```kotlin
private fun resolveUser(userId: Long?, email: String?, side: String): User =
    userResolutionService.resolveByIdOrEmail(userId, email, side)
```

- [ ] **Step 3: Remove the now-unused private helpers**

The original `resolveUser` body relied on `passwordEncoder`, `resolveUniqueUsername`, and direct `userMappingService.applyFutureUserMapping` calls. Delete the private `resolveUniqueUsername` helper and the `passwordEncoder` private field IF AND ONLY IF they have no other callers in this file. Run:

```bash
grep -n "resolveUniqueUsername\|passwordEncoder" src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt
```

Expected: only the helper definitions remain. If yes, delete them. If they have other callers (unlikely), leave them.

If `userMappingService` is no longer used elsewhere in the file, also remove its constructor injection. Run:

```bash
grep -n "userMappingService" src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt
```

Keep it injected only if there is at least one other reference; otherwise remove the parameter.

- [ ] **Step 4: Compile and run all tests**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL with all existing tests passing. Sharing flows must still work — this is a pure refactor.

- [ ] **Step 5: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt
git commit -m "refactor(backend): delegate resolveUser to UserResolutionService

AwsAccountSharingService now uses the shared service for lazy User
materialization. Pure refactor — no behavior change.
"
```

---

## Task 3: Extend `GET /api/users` with `?includePending=true`

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/controller/UserController.kt`

- [ ] **Step 1: Make `id` nullable on `UserResponse` and add `isPending`**

Open `src/backendng/src/main/kotlin/com/secman/controller/UserController.kt`. Find the `data class UserResponse(...)` (around line 59). Update the declaration:

```kotlin
@Serdeable
data class UserResponse(
    val id: Long?,
    val username: String,
    val email: String,
    val roles: List<String>,
    val mfaEnabled: Boolean,
    val createdAt: String?,
    val updatedAt: String?,
    val lastLogin: String?,
    val workgroups: List<WorkgroupSummary>? = null,
    val workgroupCount: Int? = null,
    val isPending: Boolean = false
) {
    companion object {
        fun from(user: User, includeWorkgroups: Boolean = false): UserResponse {
            return UserResponse(
                id = user.id!!,
                username = user.username,
                email = user.email,
                roles = user.roles.map { it.name },
                mfaEnabled = user.mfaEnabled,
                createdAt = user.createdAt?.toString(),
                updatedAt = user.updatedAt?.toString(),
                lastLogin = user.lastLogin?.toString(),
                workgroups = if (includeWorkgroups) {
                    user.workgroups.map { WorkgroupSummary(it.id!!, it.name) }
                } else null,
                workgroupCount = if (includeWorkgroups) user.workgroups.size else null,
                isPending = false
            )
        }

        fun pending(email: String): UserResponse {
            val username = email.substringBefore("@").ifBlank { email }
            return UserResponse(
                id = null,
                username = username,
                email = email,
                roles = emptyList(),
                mfaEnabled = false,
                createdAt = null,
                updatedAt = null,
                lastLogin = null,
                workgroups = null,
                workgroupCount = null,
                isPending = true
            )
        }
    }
}
```

- [ ] **Step 2: Inject `UserMappingRepository` and update the `list` endpoint**

In the constructor parameter list, add:

```kotlin
private val userMappingRepository: com.secman.repository.UserMappingRepository,
```

Replace the existing `list(...)` function with:

```kotlin
@Get
fun list(
    @QueryValue(defaultValue = "false") includeWorkgroups: Boolean,
    @QueryValue(defaultValue = "false") includePending: Boolean
): HttpResponse<List<UserResponse>> {
    val activeUsers = if (includeWorkgroups) {
        userRepository.findAllWithWorkgroups().map { UserResponse.from(it, true) }
    } else {
        userRepository.findAll().map { UserResponse.from(it, false) }
    }

    if (!includePending) {
        return HttpResponse.ok(activeUsers)
    }

    val activeEmails = activeUsers
        .map { it.email.lowercase().trim() }
        .filter { it.isNotEmpty() }
        .toHashSet()

    val pendingEntries = userMappingRepository
        .findByStatus(com.secman.domain.MappingStatus.PENDING)
        .asSequence()
        .map { it.email.trim() }
        .filter { it.isNotEmpty() && it.contains("@") }
        .distinctBy { it.lowercase() }
        .filter { it.lowercase() !in activeEmails }
        .map { UserResponse.pending(it) }
        .toList()

    val combined = (activeUsers + pendingEntries).sortedBy { it.email.lowercase() }
    return HttpResponse.ok(combined)
}
```

- [ ] **Step 3: Verify imports**

Add these imports at the top of `UserController.kt` if not already present:

```kotlin
import com.secman.domain.MappingStatus
import com.secman.repository.UserMappingRepository
```

(If you used the fully qualified names in Step 2, this step is optional but nicer.)

- [ ] **Step 4: Compile**

```bash
./gradlew :backendng:compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Manual smoke test**

Start the backend and frontend per CLAUDE.md (`./scriptpp/startbackenddev.sh` and `npm run dev` in `src/frontend`). Once up, call:

```bash
curl -s -H "Authorization: Bearer $ADMIN_JWT" "http://localhost:8080/api/users" | jq 'length'
curl -s -H "Authorization: Bearer $ADMIN_JWT" "http://localhost:8080/api/users?includePending=true" | jq 'length'
```

Expected: the `includePending=true` count is greater than or equal to the plain count. Pending entries have `id: null` and `isPending: true`. Get a JWT by logging in via the frontend and copying `localStorage.authToken`.

- [ ] **Step 6: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/controller/UserController.kt
git commit -m "feat(backend): add ?includePending=true to GET /api/users

Returns active users plus distinct PENDING UserMapping emails. Response
DTO gains nullable id and isPending; existing callers are unaffected.
"
```

---

## Task 4: Workgroup assign — accept `userRefs`

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt`

- [ ] **Step 1: Extend `AssignUsersRequest`**

Open `src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt`. Find `data class AssignUsersRequest(...)` (line 781) and replace with:

```kotlin
@Serdeable
data class AssignUsersRequest(
    // Legacy id-only shape — kept for back-compat with CLI / scripts.
    val userIds: List<Long>? = null,
    // New shape — supports both real users (by id) and pending users (by email).
    val userRefs: List<com.secman.service.UserResolutionService.UserRef>? = null
) {
    fun isEmpty(): Boolean = userIds.isNullOrEmpty() && userRefs.isNullOrEmpty()
}
```

- [ ] **Step 2: Inject `UserResolutionService` into `WorkgroupController`**

In the controller's constructor parameter list, add:

```kotlin
private val userResolutionService: com.secman.service.UserResolutionService,
```

- [ ] **Step 3: Resolve refs before calling the service**

Replace the body of `assignUsers` (around line 198):

```kotlin
@Post("/{id}/users")
@Secured("ADMIN")
open fun assignUsers(
    @PathVariable id: Long,
    @Body @Valid request: AssignUsersRequest
): HttpResponse<Void> {
    return try {
        if (request.isEmpty()) {
            return HttpResponse.badRequest()
        }

        // userRefs wins when both shapes are present.
        val resolvedIds: List<Long> = if (!request.userRefs.isNullOrEmpty()) {
            userResolutionService
                .resolveAll(request.userRefs, "workgroup member")
                .map { it.id!! }
        } else {
            request.userIds!!
        }

        workgroupService.assignUsersToWorkgroup(id, resolvedIds)
        HttpResponse.ok()
    } catch (e: NoSuchElementException) {
        HttpResponse.notFound()
    } catch (e: IllegalArgumentException) {
        if (e.message?.contains("Workgroup not found") == true) {
            HttpResponse.notFound()
        } else {
            HttpResponse.badRequest()
        }
    }
}
```

- [ ] **Step 4: Compile and run all tests**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL. Existing workgroup tests must still pass — the legacy `userIds` shape is unchanged behavior.

- [ ] **Step 5: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt
git commit -m "feat(backend): workgroup assignUsers accepts {id?, email?} refs

Adds optional userRefs shape that resolves to real users via
UserResolutionService, lazy-creating User rows for pending emails.
Legacy userIds shape preserved for back-compat.
"
```

---

## Task 5: Risk Assessment create/update — accept `assessorRef` / `respondentRef`

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/controller/RiskAssessmentController.kt`

- [ ] **Step 1: Add optional ref fields to request DTOs**

Open `src/backendng/src/main/kotlin/com/secman/controller/RiskAssessmentController.kt`. Find `data class CreateRiskAssessmentRequest(...)` near line 54 and add the two new optional fields BEFORE the closing paren — keep the existing `assessorId: Long` annotated `@NotNull` so legacy clients still work, but make the field's effective requirement conditional in code (Step 3). Result:

```kotlin
@Serdeable
data class CreateRiskAssessmentRequest(
    @NotNull val assessorId: Long,
    @Nullable val respondentId: Long? = null,
    /* ... other existing fields unchanged ... */
    @Nullable val assessorRef: com.secman.service.UserResolutionService.UserRef? = null,
    @Nullable val respondentRef: com.secman.service.UserResolutionService.UserRef? = null
)
```

Keep `@NotNull` on `assessorId` for now — see Step 3 for the lookup precedence.

Apply the same two-field addition to `data class CreateRiskAssessmentRequestDemand`, `CreateRiskAssessmentRequestAsset`, and `UpdateRiskAssessmentRequest` (all in this same file).

NOTE: If `@NotNull` on `assessorId` blocks requests that supply only `assessorRef`, change it to `@Nullable val assessorId: Long? = null` instead and let Step 3 enforce the "exactly one of id-or-ref must be present" rule. Pick one path consistently across all four DTOs.

- [ ] **Step 2: Inject `UserResolutionService`**

In the controller's constructor parameter list, add:

```kotlin
private val userResolutionService: com.secman.service.UserResolutionService,
```

- [ ] **Step 3: Resolve assessor and respondent via the service**

In `createRiskAssessment` (line 305), replace the assessor lookup at lines 318–320:

```kotlin
// Old:
//   val assessor = userRepository.findById(request.assessorId).orElse(null)
//       ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Assessor not found"))
//
// New:
val assessor = try {
    userResolutionService.resolveByIdOrEmail(
        userId = request.assessorRef?.id ?: request.assessorId,
        email = request.assessorRef?.email,
        context = "risk assessment assessor"
    )
} catch (e: NoSuchElementException) {
    return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Assessor not found"))
} catch (e: IllegalArgumentException) {
    return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", e.message ?: "Invalid assessor"))
}
```

Replace the respondent lookup at lines 327–330:

```kotlin
val respondent: User? = if (request.respondentRef != null || request.respondentId != null) {
    try {
        userResolutionService.resolveByIdOrEmail(
            userId = request.respondentRef?.id ?: request.respondentId,
            email = request.respondentRef?.email,
            context = "risk assessment respondent"
        )
    } catch (e: NoSuchElementException) {
        return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Respondent not found"))
    } catch (e: IllegalArgumentException) {
        return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", e.message ?: "Invalid respondent"))
    }
} else null
```

Apply the equivalent change inside `updateRiskAssessment` (which uses `UpdateRiskAssessmentRequest`). Search the file for every other place that calls `userRepository.findById(request.assessorId)` or `userRepository.findById(respondentId)` and rewrite them the same way. Run before editing:

```bash
grep -n "userRepository.findById(request.assessorId\|userRepository.findById(respondentId\|userRepository.findById(.*\.respondentId" src/backendng/src/main/kotlin/com/secman/controller/RiskAssessmentController.kt
```

Use the printed line numbers as your edit list.

- [ ] **Step 4: Compile and run all tests**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL. Existing risk-assessment tests must still pass — id-only requests behave exactly as before.

- [ ] **Step 5: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/controller/RiskAssessmentController.kt
git commit -m "feat(backend): risk assessment endpoints accept user refs

createRiskAssessment / updateRiskAssessment now accept assessorRef and
respondentRef ({id?, email?}). Lazy-creates User rows for pending emails
via UserResolutionService. Legacy assessorId / respondentId still work.
"
```

---

## Task 6: Frontend — `WorkgroupManagement.tsx` shows pending users

**Files:**
- Modify: `src/frontend/src/components/WorkgroupManagement.tsx`

- [ ] **Step 1: Update the User type and selection state**

Find the local `User` type / interface near the top of `src/frontend/src/components/WorkgroupManagement.tsx`. Make `id` nullable and add `isPending`:

```ts
interface User {
  id: number | null;
  username: string;
  email: string;
  // ... existing fields ...
  isPending?: boolean;
}
```

Replace the selection state from id-array to ref-array. Find:

```ts
const [selectedUserIds, setSelectedUserIds] = useState<number[]>([]);
```

Replace with:

```ts
type UserRef = { id?: number; email: string };
const [selectedUserRefs, setSelectedUserRefs] = useState<UserRef[]>([]);
```

- [ ] **Step 2: Update `fetchUsers` to request pending entries**

Find `fetchUsers` (line ~80). Change the URL from `/api/users` to `/api/users?includePending=true`. The response is already shape-compatible.

- [ ] **Step 3: Update the toggle helper**

Find `toggleUserSelection` (the function used at line 429). Replace its body:

```ts
const toggleUserSelection = (user: User) => {
  setSelectedUserRefs(prev => {
    const exists = prev.some(r =>
      (user.id != null && r.id === user.id) ||
      (user.id == null && r.email.toLowerCase() === user.email.toLowerCase())
    );
    if (exists) {
      return prev.filter(r =>
        !(user.id != null && r.id === user.id) &&
        !(user.id == null && r.email.toLowerCase() === user.email.toLowerCase())
      );
    }
    return user.id != null
      ? [...prev, { id: user.id, email: user.email }]
      : [...prev, { email: user.email }];
  });
};
```

- [ ] **Step 4: Update the render block**

Find the modal block at lines 412–448. Replace the `users.map(...)` render and the count line:

```tsx
<div className="list-group" style={{ maxHeight: '400px', overflowY: 'auto' }}>
  {users.map(user => {
    const checked = selectedUserRefs.some(r =>
      (user.id != null && r.id === user.id) ||
      (user.id == null && r.email.toLowerCase() === user.email.toLowerCase())
    );
    return (
      <label key={user.id ?? `pending:${user.email}`} className="list-group-item list-group-item-action">
        <input
          type="checkbox"
          className="form-check-input me-2"
          checked={checked}
          onChange={() => toggleUserSelection(user)}
        />
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
    );
  })}
</div>
<p className="mt-3 text-muted">{selectedUserRefs.length} user(s) selected</p>
```

- [ ] **Step 5: Update `submitAssignUsers` to send `userRefs`**

Find `submitAssignUsers` (line 208). Replace the body:

```ts
const submitAssignUsers = async () => {
  if (!selectedWorkgroup || selectedUserRefs.length === 0) {
    setError('Please select at least one user');
    return;
  }
  try {
    const response = await authenticatedPost(
      `/api/workgroups/${selectedWorkgroup.id}/users`,
      { userRefs: selectedUserRefs }
    );
    if (!response.ok) {
      const errorData = await response.json().catch(() => ({ error: 'Unknown error' }));
      throw new Error(errorData.error || `Failed to assign users: ${response.status}`);
    }
    await fetchWorkgroups();
    setShowAssignUsers(false);
    setSelectedWorkgroup(null);
    setSelectedUserRefs([]);
    setError(null);
  } catch (err) {
    setError(err instanceof Error ? err.message : 'An error occurred');
  }
};
```

- [ ] **Step 6: Find any other references to the old state name and remove them**

Run:

```bash
grep -n "selectedUserIds" src/frontend/src/components/WorkgroupManagement.tsx
```

Expected: zero matches. If any remain (e.g. setter calls in cancel/reset handlers), replace `setSelectedUserIds([])` with `setSelectedUserRefs([])`.

- [ ] **Step 7: TypeScript check**

```bash
cd src/frontend && npm run check
```

Expected: 0 errors.

- [ ] **Step 8: Manual UI walkthrough**

1. Import a UserMapping for an unknown email (e.g. via `/api/import/upload-user-mappings-csv` with a CSV that has `pending@example.com,123456789012,,`).
2. Open the workgroup-assign dialog at `/workgroups`.
3. Confirm `pending@example.com` appears with a yellow `pending` badge.
4. Select it, click Assign Users.
5. Confirm the workgroup now lists `pending` as a member, and the User table contains a new row with `auth_source = OAUTH`.

- [ ] **Step 9: Commit**

```bash
git add src/frontend/src/components/WorkgroupManagement.tsx
git commit -m "feat(frontend): show pending users in workgroup assign dialog

Switches to /api/users?includePending=true and submits userRefs.
Pending entries get a yellow badge and tooltip.
"
```

---

## Task 7: Frontend — `RiskAssessmentManagement.tsx` shows pending users

**Files:**
- Modify: `src/frontend/src/components/RiskAssessmentManagement.tsx`

- [ ] **Step 1: Update the local User type**

In `src/frontend/src/components/RiskAssessmentManagement.tsx`, locate the `User` type (or the inline type used at line 68's `useState<User[]>`). Allow nullable id and add `isPending`:

```ts
interface User {
  id: number | null;
  username: string;
  email: string;
  isPending?: boolean;
}
```

- [ ] **Step 2: Update `fetchUsers`**

Find the `/api/users` call at line ~166. Change to:

```ts
const response = await authenticatedGet('/api/users?includePending=true');
```

- [ ] **Step 3: Update each `users.map(...)` selector to render pending badges**

There are two `users.map((user) => ...` blocks, at lines 558 and 576. Each renders a `<select>` `<option>`. Replace both blocks with the same shape:

```tsx
{users.map((user) => (
  <option
    key={user.id ?? `pending:${user.email}`}
    value={user.id != null ? `id:${user.id}` : `email:${user.email}`}
  >
    {user.username} ({user.email}){user.isPending ? ' [pending]' : ''}
  </option>
))}
```

The `value` is encoded so the form submit can disambiguate. The frontend submit code (Step 4) decodes it.

- [ ] **Step 4: Update form submit to send refs**

Find every place this component constructs the `assessorId` (and optionally `respondentId`) for the create / update request body. For each, replace the id read with this helper:

```ts
const decodeRef = (raw: string): { id?: number; email?: string } => {
  if (raw.startsWith('id:')) return { id: Number(raw.slice(3)) };
  if (raw.startsWith('email:')) return { email: raw.slice(6) };
  return {};
};
```

Build the request body using `assessorRef` (and `respondentRef` if applicable) instead of `assessorId` / `respondentId`. Example for create:

```ts
const payload = {
  ...formFields,
  assessorRef: decodeRef(form.assessor),    // form.assessor is the <select> string
  respondentRef: form.respondent ? decodeRef(form.respondent) : undefined,
};
```

Drop the legacy id fields from the payload — the backend accepts either, but cleaner to send only the ref.

If the existing form state stores assessor as a numeric id, change its TypeScript type to `string` and migrate the `setForm` calls to use the encoded value (`id:123`). Run:

```bash
grep -n "assessorId\|respondentId" src/frontend/src/components/RiskAssessmentManagement.tsx
```

Use the printed line numbers as the edit list.

- [ ] **Step 5: TypeScript check**

```bash
cd src/frontend && npm run check
```

Expected: 0 errors.

- [ ] **Step 6: Commit**

```bash
git add src/frontend/src/components/RiskAssessmentManagement.tsx
git commit -m "feat(frontend): risk assessment selectors include pending users

Switches to /api/users?includePending=true. <select> options encode the
selection as id:N or email:addr; submit decodes into assessorRef /
respondentRef.
"
```

---

## Task 8: Frontend — `RiskAssessmentManagement.new.tsx` shows pending users

**Files:**
- Modify: `src/frontend/src/components/RiskAssessmentManagement.new.tsx`

- [ ] **Step 1: Apply the same changes as Task 7**

This file has three `users.map((user) => ...` blocks (lines 298, 317, 335). Apply identical changes:

1. `User` type: nullable `id`, add `isPending`.
2. `fetchUsers`: append `?includePending=true`.
3. Each `users.map(...)` block: render pending badge as `[pending]` and encode `value` as `id:${id}` or `email:${email}` per Task 7 Step 3.
4. Submit: build `assessorRef` / `respondentRef` via the `decodeRef` helper from Task 7 Step 4.

- [ ] **Step 2: Find every `assessorId` / `respondentId` reference and migrate**

```bash
grep -n "assessorId\|respondentId" src/frontend/src/components/RiskAssessmentManagement.new.tsx
```

For each line printed, switch the read from numeric id to the decoded ref shape per Task 7.

- [ ] **Step 3: TypeScript check**

```bash
cd src/frontend && npm run check
```

Expected: 0 errors.

- [ ] **Step 4: Commit**

```bash
git add src/frontend/src/components/RiskAssessmentManagement.new.tsx
git commit -m "feat(frontend): risk assessment (new variant) selectors include pending users

Same treatment as the main RiskAssessmentManagement component.
"
```

---

## Task 9: Migrate `AwsAccountSharingManager.tsx` to the unified endpoint

**Files:**
- Modify: `src/frontend/src/components/AwsAccountSharingManager.tsx`
- Modify: `src/backendng/src/main/kotlin/com/secman/controller/AwsAccountSharingController.kt`

- [ ] **Step 1: Update the frontend fetch URL**

In `src/frontend/src/components/AwsAccountSharingManager.tsx`, find the `fetchUsers` callback (line ~94). Replace:

```ts
const response = await authenticatedGet('/api/aws-account-sharing/users');
```

with:

```ts
const response = await authenticatedGet('/api/users?includePending=true');
```

The response is shape-compatible: `id: number | null`, `email: string`, `username: string`, `isPending: boolean`. Existing render code already handles `isPending`.

NOTE: `/api/users` is `@Secured("ADMIN")`. The current sharing form is reachable by non-ADMIN users (e.g. VULN). Before merging this step, verify the security rule on `GET /api/users` either:
1. accepts non-ADMIN when `?includePending=true` is set with no other side effects, OR
2. is downgraded by adding a class-level secondary mapping such that the pending-list path is reachable.

Decision: keep ADMIN-only on `/api/users` and instead REVERT this step — leave the sharing component on `/api/aws-account-sharing/users` for now. Verify the access requirement first:

```bash
grep -n "@Secured" src/backendng/src/main/kotlin/com/secman/controller/UserController.kt
```

If the file says `@Secured("ADMIN")` at class level (it does), STOP this step. Skip the frontend change and instead skip directly to Step 3 (deprecate the sharing-specific endpoint with a doc note but keep it functional). Document in the commit that the unification is deferred until the user-list endpoint is opened up to non-ADMIN with a public-safe DTO.

- [ ] **Step 2: (Conditional) If proceeding with unification**

Only run this step if Step 1's NOTE was resolved by widening `/api/users` access. If you did widen access, also confirm the response DTO no longer leaks ADMIN-only fields (workgroups, lastLogin, mfaEnabled) for non-ADMIN callers — add a query-param-gated projection if needed.

- [ ] **Step 3: Add a deprecation marker on the sharing-specific endpoint**

In `src/backendng/src/main/kotlin/com/secman/controller/AwsAccountSharingController.kt`, find `@Get("/users")` (line 188) and add a Kotlin doc note plus `@Deprecated`:

```kotlin
/**
 * @deprecated Will be replaced by `GET /api/users?includePending=true` once
 * that endpoint is opened to non-ADMIN callers with a public-safe DTO.
 * Kept in place for one release.
 */
@Deprecated("Use /api/users?includePending=true once non-ADMIN access is added")
@Get("/users")
fun listUsersForSharing(): HttpResponse<List<SharingUserResponse>> { ... }
```

- [ ] **Step 4: Compile and run all tests**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL. The deprecation annotation produces a warning, not an error.

- [ ] **Step 5: TypeScript check**

```bash
cd src/frontend && npm run check
```

Expected: 0 errors.

- [ ] **Step 6: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/controller/AwsAccountSharingController.kt src/frontend/src/components/AwsAccountSharingManager.tsx
git commit -m "chore: deprecate /api/aws-account-sharing/users

Marks the sharing-specific user-list endpoint as deprecated. The unified
/api/users?includePending=true endpoint is the long-term home, but
unifying clients is deferred until the user-list endpoint can serve a
public-safe DTO to non-ADMIN callers.
"
```

---

## Task 10: End-to-end manual verification

**Files:** none (verification only)

- [ ] **Step 1: Start the stack**

```bash
./scriptpp/startbackenddev.sh   # in one terminal
cd src/frontend && npm run dev  # in another
```

- [ ] **Step 2: Seed a pending user**

Via the admin UI under I/O → Upload User Mappings, upload a CSV containing:

```
email,aws_account_id,domain,ip_address
pending-test@example.com,123456789012,,
```

Confirm in the User Mappings → Current tab that the row shows up with status PENDING.

- [ ] **Step 3: Workgroup assignment**

Navigate to `/workgroups`. Pick or create a workgroup, click Users. Confirm `pending-test@example.com` appears with the yellow `pending` badge. Select it, click Assign Users. Confirm:
- The dialog closes without error.
- The workgroup row shows the new member.
- A new User row exists in the database (`SELECT id, username, email, auth_source FROM user WHERE email='pending-test@example.com'`).
- The previously-pending UserMapping is now linked (`SELECT user_id, status FROM user_mapping WHERE email='pending-test@example.com'`).

- [ ] **Step 4: Risk assessment assignment**

Re-seed a fresh pending user (`pending-ra@example.com`). Navigate to risk assessments. Open the create form. Confirm the assessor `<select>` shows `pending-ra@example.com [pending]`. Select it and submit. Confirm:
- The risk assessment is created without error.
- A User row was lazy-created.
- The risk assessment's `assessor_id` matches the new User.

- [ ] **Step 5: AWS account sharing (regression check)**

Navigate to AWS Account Sharing. Confirm the existing flow still works (the deprecated endpoint is still in service). Create a sharing rule with a pending email. Confirm no regression versus pre-change behavior.

- [ ] **Step 6: Idempotency check**

Re-seed the same pending email a second time after it was materialized. Confirm `/api/users?includePending=true` does NOT show it as `isPending: true` anymore (it is now an active User).

- [ ] **Step 7: Final commit (if any verification fixes were needed)**

If any small fix-ups were made during verification:

```bash
git add -p
git commit -m "fix: address verification findings for pending-user selectors"
```

If no fixes were needed, this step is a no-op.

---

## Out of Scope (explicit non-tasks)

- MCP tools (`assign_workgroup_users`, etc.) — separate follow-up.
- Admin → User Management CRUD UI — managed real users only; intentionally unchanged.
- Demand requestors — server-derived from authenticated user; no selector to update.
- Notification preferences for lazy-created users — already handled by existing User-create defaults.
- Removal of `/api/aws-account-sharing/users` — deferred until the unified endpoint can serve non-ADMIN callers safely.
