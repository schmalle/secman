# AWS Sharing Invite-by-Email Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let any authenticated user invite a brand-new SecMan user via email — domain-scoped to the inviter's own email domain — directly from the AWS Account Sharing form. The invitee gets a `USER + VULN` OAuth account and an email saying their account was just created and access is waiting.

**Architecture:** Additive change. Reuses existing `AwsAccountSharingService.createSharingRule` and `UserResolutionService.resolveByIdOrEmail` paths. New explicit `inviteByEmail` flag in the create request gates a strict validator (well-formed email, domain match, not own email, not existing user, not pending mapping). The notification email is the existing `aws-sharing-granted` template extended with a conditional "your account was just created" block. Frontend gets a radio toggle on the target field: *Pick existing user* vs *Invite by email*.

**Tech Stack:** Kotlin 2.3 / Micronaut 4.10 / Hibernate JPA / JUnit 6 / MockK; Astro 6.2 + React 19 + Bootstrap on the frontend; existing test infra (`BaseIntegrationTest`, MariaDB Testcontainers).

**Reference spec:** `docs/superpowers/specs/2026-05-07-aws-sharing-invite-by-email-design.md`.

---

## File Map

**Create:**
- `src/backendng/src/main/kotlin/com/secman/util/EmailDomain.kt` — single source of truth for email parsing + domain comparison.
- `src/backendng/src/test/kotlin/com/secman/util/EmailDomainTest.kt` — unit tests for the helper.

**Modify (backend):**
- `src/backendng/src/main/kotlin/com/secman/dto/AwsAccountSharingDto.kt` — add `inviteByEmail` flag to `CreateAwsAccountSharingRequest`.
- `src/backendng/src/main/kotlin/com/secman/service/UserResolutionService.kt` — add optional `roles` parameter.
- `src/backendng/src/main/kotlin/com/secman/domain/AwsAccountSharingCreatedEvent.kt` — add `targetUserWasJustCreated` field.
- `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt` — branch on invite intent, capture `wasJustCreated`, pass roles, add audit log.
- `src/backendng/src/main/kotlin/com/secman/controller/AwsAccountSharingController.kt` — invite-mode validator before service delegation.
- `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingNotificationService.kt` — render conditional `{ifNewAccount}` block, substitute `{loginUrl}`.
- `src/backendng/src/main/resources/email-templates/aws-sharing-granted.html` — add conditional block.
- `src/backendng/src/main/resources/email-templates/aws-sharing-granted.txt` — add conditional block.

**Modify (backend tests):**
- `src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingServiceTest.kt` — invite-branch assertions, role pass-through, `wasJustCreated` flag.
- `src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingNotificationServiceTest.kt` — template substitution with/without flag.

**Modify (frontend):**
- `src/frontend/src/services/awsAccountSharingService.ts` — add `inviteByEmail` field to `CreateAwsAccountSharingRequest`.
- `src/frontend/src/components/AwsAccountSharingManager.tsx` — radio toggle, email textbox, domain validation, helper text.

---

## Task 1: Email-domain helper utility

This is the single source of truth for parsing emails and comparing domains. Used by the controller validator and (potentially) by future code paths. Implemented first because every other backend task depends on it.

**Files:**
- Create: `src/backendng/src/main/kotlin/com/secman/util/EmailDomain.kt`
- Test: `src/backendng/src/test/kotlin/com/secman/util/EmailDomainTest.kt`

- [ ] **Step 1: Write the failing tests**

Write to `src/backendng/src/test/kotlin/com/secman/util/EmailDomainTest.kt`:

```kotlin
package com.secman.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EmailDomainTest {

    @Test
    fun `extractDomain returns lowercase domain after the at sign`() {
        assertEquals("example.com", EmailDomain.extractDomain("alice@example.com"))
        assertEquals("example.com", EmailDomain.extractDomain("ALICE@Example.COM"))
        assertEquals("example.com", EmailDomain.extractDomain("  alice@example.com  "))
    }

    @Test
    fun `extractDomain returns null for malformed inputs`() {
        assertNull(EmailDomain.extractDomain(""))
        assertNull(EmailDomain.extractDomain("no-at-sign"))
        assertNull(EmailDomain.extractDomain("@no-local-part.com"))
        assertNull(EmailDomain.extractDomain("trailing-at@"))
        assertNull(EmailDomain.extractDomain("two@at@signs.com"))
        assertNull(EmailDomain.extractDomain(null))
    }

    @Test
    fun `sameDomain matches case-insensitively after at sign`() {
        assertTrue(EmailDomain.sameDomain("a@example.com", "B@EXAMPLE.COM"))
        assertTrue(EmailDomain.sameDomain("a@example.com", "b@example.com"))
    }

    @Test
    fun `sameDomain rejects subdomains as different`() {
        assertFalse(EmailDomain.sameDomain("a@example.com", "b@eu.example.com"))
        assertFalse(EmailDomain.sameDomain("a@eu.example.com", "b@example.com"))
    }

    @Test
    fun `sameDomain returns false when either input is malformed`() {
        assertFalse(EmailDomain.sameDomain("a@example.com", "no-at-sign"))
        assertFalse(EmailDomain.sameDomain("", "b@example.com"))
        assertFalse(EmailDomain.sameDomain(null, "b@example.com"))
    }

    @Test
    fun `isWellFormed enforces single at sign with non-empty parts`() {
        assertTrue(EmailDomain.isWellFormed("a@example.com"))
        assertFalse(EmailDomain.isWellFormed(""))
        assertFalse(EmailDomain.isWellFormed("no-at-sign"))
        assertFalse(EmailDomain.isWellFormed("@nolocal.com"))
        assertFalse(EmailDomain.isWellFormed("trailing@"))
        assertFalse(EmailDomain.isWellFormed("two@at@signs.com"))
        assertFalse(EmailDomain.isWellFormed(null))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
cd src/backendng && ./gradlew test --tests "com.secman.util.EmailDomainTest" 2>&1 | tail -30
```
Expected: compilation failure — `EmailDomain` is not defined.

- [ ] **Step 3: Implement the helper**

Write to `src/backendng/src/main/kotlin/com/secman/util/EmailDomain.kt`:

```kotlin
package com.secman.util

/**
 * Single source of truth for parsing user-typed email addresses and
 * comparing their domains. Used by AWS-sharing invite validation;
 * intentionally narrow so all email-domain decisions go through one
 * implementation.
 *
 * "Well-formed" here means: exactly one '@', non-empty local part,
 * non-empty domain part. We deliberately do NOT enforce full RFC 5322
 * — the cost is high and the value is low, given that storage already
 * accepts whatever the user typed and login goes through OAuth which
 * applies its own validation.
 */
object EmailDomain {

    /**
     * Returns the lowercase domain portion of [raw] (the substring after
     * the single '@'), or null if [raw] is not well-formed.
     */
    fun extractDomain(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (!isWellFormed(trimmed)) return null
        return trimmed.substringAfter('@').lowercase()
    }

    /**
     * Returns true when both inputs are well-formed and have equal
     * domains (case-insensitive). Subdomains are treated as different
     * domains: example.com != eu.example.com.
     */
    fun sameDomain(a: String?, b: String?): Boolean {
        val da = extractDomain(a) ?: return false
        val db = extractDomain(b) ?: return false
        return da == db
    }

    /**
     * Quick well-formedness check: exactly one '@', non-empty local
     * part, non-empty domain part. Whitespace is trimmed first.
     */
    fun isWellFormed(raw: String?): Boolean {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return false
        val atCount = s.count { it == '@' }
        if (atCount != 1) return false
        val local = s.substringBefore('@')
        val domain = s.substringAfter('@')
        return local.isNotEmpty() && domain.isNotEmpty()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd src/backendng && ./gradlew test --tests "com.secman.util.EmailDomainTest" 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, all 6 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/util/EmailDomain.kt \
        src/backendng/src/test/kotlin/com/secman/util/EmailDomainTest.kt
git commit -m "feat(aws-sharing): add EmailDomain helper for invite validation

Single source of truth for email parsing and case-insensitive
domain comparison. Strict subdomain semantics (example.com !=
eu.example.com). Will back the invite-by-email controller validator
in the next commit."
```

---

## Task 2: Add `inviteByEmail` flag to the create-request DTO

Tiny additive change to a serialization DTO. No tests needed at this layer — exercised by the controller and service tests later.

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/dto/AwsAccountSharingDto.kt:55-62`

- [ ] **Step 1: Add the field**

Replace (in `src/backendng/src/main/kotlin/com/secman/dto/AwsAccountSharingDto.kt`):

```kotlin
@Serdeable
data class CreateAwsAccountSharingRequest(
    val sourceUserId: Long? = null,
    val sourceUserEmail: String? = null,
    val targetUserId: Long? = null,
    val targetUserEmail: String? = null,
    val awsAccountIds: List<String>? = null,
)
```

with:

```kotlin
@Serdeable
data class CreateAwsAccountSharingRequest(
    val sourceUserId: Long? = null,
    val sourceUserEmail: String? = null,
    val targetUserId: Long? = null,
    val targetUserEmail: String? = null,
    val awsAccountIds: List<String>? = null,
    /**
     * When true, the controller treats `targetUserEmail` as a brand-new
     * invite: it must be well-formed, share the caller's domain, and not
     * already correspond to an existing User or PENDING UserMapping.
     * The lazily-created User receives roles [USER, VULN] (not the
     * default [USER, VULN, REQ]) and the notification email contains a
     * "your account was just created" block.
     *
     * When false (default), the legacy create-or-find behavior applies.
     */
    val inviteByEmail: Boolean = false,
)
```

- [ ] **Step 2: Compile-check**

Run:
```bash
cd src/backendng && ./gradlew compileKotlin 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`. (Pure additive change with a default — no callers break.)

- [ ] **Step 3: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/dto/AwsAccountSharingDto.kt
git commit -m "feat(aws-sharing): add inviteByEmail flag to create-request DTO

Defaults to false so existing callers are unaffected. The controller
validator wired up in a later commit treats true as a brand-new
invite path with strict domain + non-collision checks."
```

---

## Task 3: Add optional `roles` parameter to `UserResolutionService.resolveByIdOrEmail`

Backward-compatible signature change. Default `null` keeps every existing caller on the current `[USER, VULN, REQ]` behavior. The new invite path will pass `setOf(USER, VULN)` later.

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/service/UserResolutionService.kt:42-95`

- [ ] **Step 1: Write a test that exercises the new parameter**

Append to `src/backendng/src/test/kotlin/com/secman/service/UserResolutionServiceTest.kt` (create the file if it doesn't exist):

```kotlin
package com.secman.service

import com.secman.domain.User
import com.secman.repository.UserRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

class UserResolutionServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var userMappingService: UserMappingService
    private lateinit var service: UserResolutionService

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        userMappingService = mockk(relaxed = true)
        service = UserResolutionService(userRepository, userMappingService)
    }

    @Test
    fun `lazy-create uses default roles when roles parameter is null`() {
        every { userRepository.findByEmailIgnoreCase("new@example.com") } returns Optional.empty()
        every { userRepository.existsByUsername(any()) } returns false
        val saved = slot<User>()
        every { userRepository.save(capture(saved)) } answers { saved.captured.also { it.id = 42L } }

        service.resolveByIdOrEmail(userId = null, email = "new@example.com", context = "target")

        assertEquals(setOf(User.Role.USER, User.Role.VULN, User.Role.REQ), saved.captured.roles)
    }

    @Test
    fun `lazy-create honors explicit roles parameter`() {
        every { userRepository.findByEmailIgnoreCase("invite@example.com") } returns Optional.empty()
        every { userRepository.existsByUsername(any()) } returns false
        val saved = slot<User>()
        every { userRepository.save(capture(saved)) } answers { saved.captured.also { it.id = 43L } }

        service.resolveByIdOrEmail(
            userId = null,
            email = "invite@example.com",
            context = "target",
            roles = setOf(User.Role.USER, User.Role.VULN),
        )

        assertEquals(setOf(User.Role.USER, User.Role.VULN), saved.captured.roles)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
cd src/backendng && ./gradlew test --tests "com.secman.service.UserResolutionServiceTest" 2>&1 | tail -20
```
Expected: compile error — `roles` is not a parameter of `resolveByIdOrEmail`.

- [ ] **Step 3: Update the signature**

In `src/backendng/src/main/kotlin/com/secman/service/UserResolutionService.kt`, replace the method header (around line 42-44):

```kotlin
    @Transactional
    open fun resolveByIdOrEmail(userId: Long?, email: String?, context: String): User {
```

with:

```kotlin
    @Transactional
    open fun resolveByIdOrEmail(
        userId: Long?,
        email: String?,
        context: String,
        roles: Set<User.Role>? = null,
    ): User {
```

Then, in the same file, replace the `roles = mutableSetOf(...)` line inside the lazy-create `User(...)` constructor (around line 60-61):

```kotlin
            roles = mutableSetOf(User.Role.USER, User.Role.VULN, User.Role.REQ),
```

with:

```kotlin
            roles = (roles ?: setOf(User.Role.USER, User.Role.VULN, User.Role.REQ)).toMutableSet(),
```

- [ ] **Step 4: Update the existing `resolveAll` helper**

In `src/backendng/src/main/kotlin/com/secman/service/UserResolutionService.kt`, the existing `resolveAll` calls `resolveByIdOrEmail(it.id, it.email, context)` — that still compiles thanks to the default. No change needed. Verify by reading the file.

- [ ] **Step 5: Run tests to verify they pass**

Run:
```bash
cd src/backendng && ./gradlew test --tests "com.secman.service.UserResolutionServiceTest" 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, both tests passing.

- [ ] **Step 6: Run the broader service test suite to confirm no regressions**

Run:
```bash
cd src/backendng && ./gradlew test --tests "com.secman.service.*" 2>&1 | tail -40
```
Expected: no new failures attributable to this change. (Pre-existing failures, if any, are out of scope.)

- [ ] **Step 7: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/service/UserResolutionService.kt \
        src/backendng/src/test/kotlin/com/secman/service/UserResolutionServiceTest.kt
git commit -m "feat(user-resolution): allow caller to override default lazy-create roles

Adds optional roles parameter (default null preserves current
[USER, VULN, REQ] behavior). Invite-by-email AWS-sharing flow will
pass [USER, VULN] in a subsequent commit."
```

---

## Task 4: Add `targetUserWasJustCreated` to the sharing-created event

Pure additive serialization change. The sole publisher (`AwsAccountSharingService.createSharingRule`) gets updated in Task 5; the listener and notification service get updated in Tasks 6-8.

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/domain/AwsAccountSharingCreatedEvent.kt:16-25`

- [ ] **Step 1: Add the field**

Replace (in `src/backendng/src/main/kotlin/com/secman/domain/AwsAccountSharingCreatedEvent.kt`):

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

with:

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
    /**
     * True when this share's target user did not exist as a SecMan User
     * before this rule was created (i.e. the sharing flow lazy-created
     * the row). The notification email uses this to decide whether to
     * include the "your account was just created" onboarding block.
     *
     * Note: under a tight race between two concurrent invites for the
     * same email, both events may carry true. That's accepted as a
     * benign inaccuracy — see design doc, "User-create races".
     */
    val targetUserWasJustCreated: Boolean = false,
)
```

- [ ] **Step 2: Compile-check**

Run:
```bash
cd src/backendng && ./gradlew compileKotlin 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`. (Pure additive change with default — no consumers break.)

- [ ] **Step 3: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/domain/AwsAccountSharingCreatedEvent.kt
git commit -m "feat(aws-sharing): add targetUserWasJustCreated to event payload

Defaults to false so the sole publisher (createSharingRule) and the
listener keep compiling. Will be set to true on the invite-by-email
path in a subsequent commit, and consumed by the notification
service to render an onboarding block."
```

---

## Task 5: Branch the service on invite intent and pass through the new event flag

This is the main business-logic change. The service captures whether the email was new before resolving the user, passes the explicit role set on the invite path, emits the augmented event, and adds an audit log line for the invite-create case.

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt:119-203`
- Test: `src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingServiceTest.kt`

- [ ] **Step 1: Write the failing test for invite branch (event flag = true and roles passed)**

Append to `src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingServiceTest.kt`:

```kotlin
    @Test
    fun `invite branch sets targetUserWasJustCreated true when email is new`() {
        val source = user(1L, "alice@example.com")
        val newTarget = user(2L, "newbie@example.com", "newbie")
        val admin = user(1L, "alice@example.com")  // self-as-admin for simple repo lookup

        every { userRepo.findByEmailIgnoreCase("newbie@example.com") } returns Optional.empty()
        every { resolver.resolveByIdOrEmail(any(), any(), "source") } returns source
        every {
            resolver.resolveByIdOrEmail(
                userId = null,
                email = "newbie@example.com",
                context = "target",
                roles = setOf(User.Role.USER, User.Role.VULN),
            )
        } returns newTarget
        every { userRepo.findById(1L) } returns Optional.of(admin)
        every { repo.existsBySourceUserIdAndTargetUserId(1L, 2L) } returns false
        every { mappingRepo.findDistinctAwsAccountIdByEmail("alice@example.com") } returns
            listOf("111111111111")

        val saved = AwsAccountSharing(
            id = 7L, sourceUser = source, targetUser = newTarget, createdBy = admin,
            createdAt = Instant.parse("2026-05-07T09:00:00Z"),
            updatedAt = Instant.parse("2026-05-07T09:00:00Z"),
        )
        every { repo.save(any()) } returns saved
        val captured = slot<AwsAccountSharingCreatedEvent>()
        every { publisher.publishEvent(capture(captured)) } just Runs

        val request = CreateAwsAccountSharingRequest(
            sourceUserId = 1L,
            targetUserEmail = "newbie@example.com",
            inviteByEmail = true,
        )
        service.createSharingRule(request, adminUserId = 1L)

        assertTrue(captured.captured.targetUserWasJustCreated)
        verify {
            resolver.resolveByIdOrEmail(
                userId = null,
                email = "newbie@example.com",
                context = "target",
                roles = setOf(User.Role.USER, User.Role.VULN),
            )
        }
    }

    @Test
    fun `invite branch sets targetUserWasJustCreated false when email already exists`() {
        val source = user(1L, "alice@example.com")
        val existingTarget = user(2L, "bob@example.com", "bob")
        val admin = user(1L, "alice@example.com")

        every { userRepo.findByEmailIgnoreCase("bob@example.com") } returns Optional.of(existingTarget)
        every { resolver.resolveByIdOrEmail(any(), any(), "source") } returns source
        every {
            resolver.resolveByIdOrEmail(
                userId = null,
                email = "bob@example.com",
                context = "target",
                roles = setOf(User.Role.USER, User.Role.VULN),
            )
        } returns existingTarget
        every { userRepo.findById(1L) } returns Optional.of(admin)
        every { repo.existsBySourceUserIdAndTargetUserId(1L, 2L) } returns false
        every { mappingRepo.findDistinctAwsAccountIdByEmail("alice@example.com") } returns
            listOf("111111111111")

        val saved = AwsAccountSharing(
            id = 8L, sourceUser = source, targetUser = existingTarget, createdBy = admin,
            createdAt = Instant.parse("2026-05-07T09:00:00Z"),
            updatedAt = Instant.parse("2026-05-07T09:00:00Z"),
        )
        every { repo.save(any()) } returns saved
        val captured = slot<AwsAccountSharingCreatedEvent>()
        every { publisher.publishEvent(capture(captured)) } just Runs

        service.createSharingRule(
            CreateAwsAccountSharingRequest(
                sourceUserId = 1L,
                targetUserEmail = "bob@example.com",
                inviteByEmail = true,
            ),
            adminUserId = 1L,
        )

        assertFalse(captured.captured.targetUserWasJustCreated)
    }
```

Note: the existing test file uses a 5-argument constructor missing `mcpAccessCacheInvalidator`. That's a pre-existing issue (it's already broken at compile time on `main`). If it doesn't compile when running the tests below, fix the `setUp()` method at the same time:

```kotlin
    private lateinit var mcpAccessCacheInvalidator: McpAccessibleAssetsCacheInvalidator
    // …
    @BeforeEach
    fun setUp() {
        repo = mockk()
        userRepo = mockk()
        mappingRepo = mockk()
        resolver = mockk()
        publisher = mockk(relaxed = true)
        mcpAccessCacheInvalidator = mockk(relaxed = true)
        service = AwsAccountSharingService(repo, userRepo, mappingRepo, resolver, publisher, mcpAccessCacheInvalidator)
    }
```

(Add the appropriate import for `McpAccessibleAssetsCacheInvalidator` at the top.)

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
cd src/backendng && ./gradlew test --tests "com.secman.service.AwsAccountSharingServiceTest" 2>&1 | tail -30
```
Expected: failures from the two new tests (resolver mock for the `roles=` overload doesn't match because the service still calls the 3-arg form, so the resolver returns null/throws). Or, if the constructor was already broken, expect compile failure first; fix per Step 1 note, then re-run.

- [ ] **Step 3: Branch the service on invite intent**

In `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt`, locate `createSharingRule` (around line 119). Replace the body's user-resolution lines (lines 121-122):

```kotlin
        val sourceUser = resolveUser(request.sourceUserId, request.sourceUserEmail, "source")
        val targetUser = resolveUser(request.targetUserId, request.targetUserEmail, "target")
```

with:

```kotlin
        val sourceUser = resolveUser(request.sourceUserId, request.sourceUserEmail, "source")

        // Capture before resolution: was the target email a new account
        // at the moment the request entered the service? Used to gate the
        // "your account was just created" block in the notification email.
        val targetWasNewBeforeResolve = request.inviteByEmail &&
            !request.targetUserEmail.isNullOrBlank() &&
            userRepository.findByEmailIgnoreCase(request.targetUserEmail).isEmpty

        val targetUser = if (request.inviteByEmail) {
            // Invite path: explicit USER+VULN role set; controller has
            // already validated domain + non-collision.
            userResolutionService.resolveByIdOrEmail(
                userId = null,
                email = request.targetUserEmail,
                context = "target",
                roles = setOf(User.Role.USER, User.Role.VULN),
            )
        } else {
            resolveUser(request.targetUserId, request.targetUserEmail, "target")
        }

        if (request.inviteByEmail && targetWasNewBeforeResolve) {
            log.info(
                "AUDIT: AWS sharing invited user created: email={}, inviter={}, roles={}",
                targetUser.email, sourceUser.email, "USER,VULN",
            )
        }
```

Add the import at the top of the file if not already present:

```kotlin
import com.secman.domain.User
```

- [ ] **Step 4: Pass the new flag into the published event**

In the same method, locate the `sharingCreatedEventPublisher.publishEvent(...)` block (around line 181-192). Replace:

```kotlin
        sharingCreatedEventPublisher.publishEvent(
            AwsAccountSharingCreatedEvent(
                sharingId = saved.id!!,
                sourceUserEmail = sourceUser.email,
                targetUserId = targetUser.id!!,
                targetUserEmail = targetUser.email,
                targetUsername = targetUser.username,
                createdByEmail = adminUser.email,
                createdAtIso = saved.createdAt!!.toString(),
                sharedAwsAccountCount = effectiveCount,
            )
        )
```

with:

```kotlin
        sharingCreatedEventPublisher.publishEvent(
            AwsAccountSharingCreatedEvent(
                sharingId = saved.id!!,
                sourceUserEmail = sourceUser.email,
                targetUserId = targetUser.id!!,
                targetUserEmail = targetUser.email,
                targetUsername = targetUser.username,
                createdByEmail = adminUser.email,
                createdAtIso = saved.createdAt!!.toString(),
                sharedAwsAccountCount = effectiveCount,
                targetUserWasJustCreated = targetWasNewBeforeResolve,
            )
        )
```

- [ ] **Step 5: Augment the existing AUDIT log line for sharing-created**

In the same method, the existing log call (around line 174-179) reads:

```kotlin
        log.info(
            "AUDIT: AWS account sharing created: source={}, target={}, admin={}, scope={}, sharedAccounts={}",
            sourceUser.email, targetUser.email, adminUser.email,
            if (resolvedScope.isEmpty()) "ALL" else "SELECTED(${resolvedScope.size})",
            effectiveCount,
        )
```

Replace with:

```kotlin
        log.info(
            "AUDIT: AWS account sharing created: source={}, target={}, admin={}, scope={}, sharedAccounts={}, inviteCreatedUser={}",
            sourceUser.email, targetUser.email, adminUser.email,
            if (resolvedScope.isEmpty()) "ALL" else "SELECTED(${resolvedScope.size})",
            effectiveCount,
            targetWasNewBeforeResolve,
        )
```

- [ ] **Step 6: Run the service tests to verify they pass**

Run:
```bash
cd src/backendng && ./gradlew test --tests "com.secman.service.AwsAccountSharingServiceTest" 2>&1 | tail -30
```
Expected: `BUILD SUCCESSFUL`. The new invite tests pass; the pre-existing event-publish test still passes (its DTO doesn't pass `inviteByEmail`, default is false, `targetWasNewBeforeResolve` evaluates false, behavior unchanged).

- [ ] **Step 7: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt \
        src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingServiceTest.kt
git commit -m "feat(aws-sharing): branch service on inviteByEmail flag

When inviteByEmail=true:
- captures whether the target email was new before resolution
- delegates to UserResolutionService with explicit [USER, VULN] roles
- emits an augmented audit log line for the user-creation event
- threads targetUserWasJustCreated into AwsAccountSharingCreatedEvent

Domain/collision validation lives in the controller (next commit);
the service trusts that pre-validation here."
```

---

## Task 6: Update HTML email template with conditional onboarding block

**Files:**
- Modify: `src/backendng/src/main/resources/email-templates/aws-sharing-granted.html`

- [ ] **Step 1: Insert the conditional block**

Replace the contents of `src/backendng/src/main/resources/email-templates/aws-sharing-granted.html` with:

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>AWS account access shared with you</title>
</head>
<body style="font-family: Arial, sans-serif; color: #222; max-width: 640px; margin: 0 auto; padding: 24px;">
  <p style="text-align: center;">
    <img src="cid:secman-logo" alt="SecMan" style="max-height: 64px;">
  </p>

  <h2 style="color: #1f4e79;">AWS account access shared with you</h2>

  <p>Hello {targetUsername},</p>

  {ifNewAccount}
  <div style="background: #eef5fc; border-left: 4px solid #1f4e79; padding: 12px 16px; margin: 0 0 16px;">
    <p style="margin: 0 0 8px;"><strong>A SecMan account has just been created for you.</strong></p>
    <p style="margin: 0;">Log in via your organization SSO at
      <a href="{loginUrl}" style="color: #1f4e79;">{loginUrl}</a>.
      Use the same email address as this message ({targetUserEmail}).</p>
  </div>
  {/ifNewAccount}

  <p><strong>{sourceUserEmail}</strong> has shared their AWS account access with you in SecMan.</p>

  <p>You can now see assets and vulnerabilities tied to {sourceUserEmail}'s AWS accounts.
     This share is one-way (only from {sourceUserEmail} to you) and is not transitive
     (no third party gains access through this rule).</p>

  <p>This share covers <strong>{sharedAwsAccountCount}</strong> AWS account(s).</p>

  <p style="color: #555; font-size: 0.9em;">
    Created by {createdByEmail} on {createdAtIso} (UTC).
  </p>

  <p>
    <a href="{assetsUrl}"
       style="background: #1f4e79; color: #fff; padding: 10px 18px; text-decoration: none; border-radius: 4px;">
      View assets in SecMan
    </a>
  </p>

  <hr style="border: none; border-top: 1px solid #ddd; margin: 32px 0 16px;">

  <p style="color: #777; font-size: 0.85em;">
    If you believe this share is incorrect, contact your SecMan administrator.
  </p>
</body>
</html>
```

- [ ] **Step 2: Commit (paired with the txt template in Task 7 for safety, but commit now is fine — incomplete state only lasts one task)**

We'll defer the commit to after Task 7 so the templates ship together.

---

## Task 7: Update TXT email template with conditional onboarding block

**Files:**
- Modify: `src/backendng/src/main/resources/email-templates/aws-sharing-granted.txt`

- [ ] **Step 1: Insert the conditional block**

Replace the contents of `src/backendng/src/main/resources/email-templates/aws-sharing-granted.txt` with:

```text
AWS account access shared with you in SecMan
=============================================

Hello {targetUsername},

{ifNewAccount}
A SecMan account has just been created for you.
Log in via your organization SSO at: {loginUrl}
Use the same email address as this message ({targetUserEmail}).

{/ifNewAccount}
{sourceUserEmail} has shared their AWS account access with you in SecMan.

You can now see assets and vulnerabilities tied to {sourceUserEmail}'s
AWS accounts. This share is one-way (only from {sourceUserEmail} to you)
and is not transitive (no third party gains access through this rule).

This share covers {sharedAwsAccountCount} AWS account(s).

Created by {createdByEmail} on {createdAtIso} (UTC).

View assets in SecMan: {assetsUrl}

---
If you believe this share is incorrect, contact your SecMan administrator.
```

Note the trailing blank line *after* `{/ifNewAccount}` — when the block is stripped (not a new account), the surrounding paragraphs collapse cleanly without leaving extra blank lines.

- [ ] **Step 2: Commit both template files together**

```bash
git add src/backendng/src/main/resources/email-templates/aws-sharing-granted.html \
        src/backendng/src/main/resources/email-templates/aws-sharing-granted.txt
git commit -m "feat(aws-sharing): add conditional onboarding block to email templates

Both .html and .txt now contain {ifNewAccount}…{/ifNewAccount} markers
that the notification service will render or strip based on
event.targetUserWasJustCreated. Substitution wiring lands in the next
commit."
```

---

## Task 8: Render the conditional block in the notification service

The substitution helper currently runs a series of `String.replace` calls. Add a small block-conditional step before/around those, plus a new `{loginUrl}` substitution and `{targetUserEmail}` (the latter wasn't substituted before — only used in the new block, but harmless to make available everywhere).

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingNotificationService.kt`
- Test: `src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingNotificationServiceTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingNotificationServiceTest.kt` (replace if a similar test already exists for substitute):

```kotlin
    @Test
    fun `substitute renders new-account block when targetUserWasJustCreated is true`() {
        val event = AwsAccountSharingCreatedEvent(
            sharingId = 1L,
            sourceUserEmail = "alice@example.com",
            targetUserId = 2L,
            targetUserEmail = "newbie@example.com",
            targetUsername = "newbie",
            createdByEmail = "alice@example.com",
            createdAtIso = "2026-05-07T09:00:00Z",
            sharedAwsAccountCount = 3,
            targetUserWasJustCreated = true,
        )
        val template = """
            Hello {targetUsername},
            {ifNewAccount}
            Account created. Log in at {loginUrl} using {targetUserEmail}.
            {/ifNewAccount}
            {sourceUserEmail} shared {sharedAwsAccountCount} accounts.
        """.trimIndent()

        val rendered = service.substituteForTest(template, event, assetsUrl = "https://app/assets", loginUrl = "https://app")

        assertTrue(rendered.contains("Account created. Log in at https://app using newbie@example.com."))
        assertFalse(rendered.contains("{ifNewAccount}"))
        assertFalse(rendered.contains("{/ifNewAccount}"))
    }

    @Test
    fun `substitute strips new-account block when flag is false`() {
        val event = AwsAccountSharingCreatedEvent(
            sharingId = 1L,
            sourceUserEmail = "alice@example.com",
            targetUserId = 2L,
            targetUserEmail = "bob@example.com",
            targetUsername = "bob",
            createdByEmail = "alice@example.com",
            createdAtIso = "2026-05-07T09:00:00Z",
            sharedAwsAccountCount = 3,
            targetUserWasJustCreated = false,
        )
        val template = """
            Hello {targetUsername},
            {ifNewAccount}
            Should be stripped.
            {/ifNewAccount}
            {sourceUserEmail} shared {sharedAwsAccountCount} accounts.
        """.trimIndent()

        val rendered = service.substituteForTest(template, event, assetsUrl = "https://app/assets", loginUrl = "https://app")

        assertFalse(rendered.contains("Should be stripped"))
        assertFalse(rendered.contains("{ifNewAccount}"))
        assertFalse(rendered.contains("{/ifNewAccount}"))
        assertTrue(rendered.contains("alice@example.com shared 3 accounts."))
    }
```

(If the test file is brand-new, prefix with the standard package + imports — see existing file in the same directory for the boilerplate.)

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
cd src/backendng && ./gradlew test --tests "com.secman.service.AwsAccountSharingNotificationServiceTest" 2>&1 | tail -20
```
Expected: compile failure — `substituteForTest` doesn't exist; `loginUrl` parameter isn't on `substitute`.

- [ ] **Step 3: Update the notification service**

In `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingNotificationService.kt`:

a. Add a constant for the login-URL path (none) and update the `notifyTargetOfNewShare` method to compute it. Replace lines 36-53 (the `try` block body up through the email send) with:

```kotlin
        return try {
            val htmlTemplate = readResource(HTML_TEMPLATE)
            val textTemplate = readResource(TEXT_TEMPLATE)
            val assetsUrl = appConfig.backend.baseUrl.trimEnd('/') + ASSETS_PATH
            val loginUrl = appConfig.frontend.baseUrl.trimEnd('/')

            val htmlBody = substitute(htmlTemplate, event, assetsUrl, loginUrl)
            val textBody = substitute(textTemplate, event, assetsUrl, loginUrl)
            val inlineImages = loadLogoInlineImage()

            val future = emailService.sendEmailWithInlineImages(
                to = event.targetUserEmail,
                subject = SUBJECT,
                textContent = textBody,
                htmlContent = htmlBody,
                inlineImages = inlineImages,
            )
```

b. Replace the `private fun substitute(...)` (currently around lines 77-86) with:

```kotlin
    private fun substitute(template: String, e: AwsAccountSharingCreatedEvent, assetsUrl: String, loginUrl: String): String {
        // Render-or-strip the conditional onboarding block before doing
        // simple field substitutions, so {loginUrl} inside the block is
        // resolved in the same pass.
        val withBlockRendered = renderConditionalBlock(template, "ifNewAccount", e.targetUserWasJustCreated)

        return withBlockRendered
            .replace("{targetUsername}", e.targetUsername)
            .replace("{targetUserEmail}", e.targetUserEmail)
            .replace("{sourceUserEmail}", e.sourceUserEmail)
            .replace("{sharedAwsAccountCount}", e.sharedAwsAccountCount.toString())
            .replace("{createdByEmail}", e.createdByEmail)
            .replace("{createdAtIso}", e.createdAtIso)
            .replace("{assetsUrl}", assetsUrl)
            .replace("{loginUrl}", loginUrl)
    }

    /**
     * Hand-rolled conditional block: replaces every
     *   {name}…{/name}
     * pair with either its inner content (when [include] is true) or
     * with empty string. The block is non-greedy so multiple blocks on
     * the same template each render independently. Single-line and
     * multi-line bodies are both supported.
     */
    private fun renderConditionalBlock(template: String, name: String, include: Boolean): String {
        val open = "{$name}"
        val close = "{/$name}"
        val pattern = Regex(
            Regex.escape(open) + "(.*?)" + Regex.escape(close),
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        return pattern.replace(template) { match ->
            if (include) match.groupValues[1] else ""
        }
    }

    /**
     * Test seam — the production code path uses the private `substitute`
     * directly. Exposed `internal` so unit tests can exercise the
     * rendering without going through I/O.
     */
    internal fun substituteForTest(
        template: String,
        event: AwsAccountSharingCreatedEvent,
        assetsUrl: String,
        loginUrl: String,
    ): String = substitute(template, event, assetsUrl, loginUrl)
```

(Keep `readResource` and `loadLogoInlineImage` as they are.)

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd src/backendng && ./gradlew test --tests "com.secman.service.AwsAccountSharingNotificationServiceTest" 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, both new tests passing alongside any existing ones in that file.

- [ ] **Step 5: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingNotificationService.kt \
        src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingNotificationServiceTest.kt
git commit -m "feat(aws-sharing): render onboarding block in notification email

Adds renderConditionalBlock helper that strips or unwraps
{ifNewAccount}…{/ifNewAccount} markers based on
event.targetUserWasJustCreated. Substitutes new {loginUrl} and
{targetUserEmail} placeholders. The block uses non-greedy regex so
multiple blocks per template render independently."
```

---

## Task 9: Controller validator — invite-mode pre-checks

Strict server-side validation. Any failure short-circuits with 400 or 409 before service delegation. The validator uses `EmailDomain` from Task 1 and queries `UserRepository.findByEmailIgnoreCase` plus `UserMappingRepository.findByEmailAndStatus(email, MappingStatus.PENDING)`.

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/controller/AwsAccountSharingController.kt:76-133`

- [ ] **Step 1: Locate the existing `createSharingRule` method body**

Open `src/backendng/src/main/kotlin/com/secman/controller/AwsAccountSharingController.kt`. The relevant block is `@Post open fun createSharingRule(...)` at around line 76.

- [ ] **Step 2: Insert the invite-mode validator immediately after authorization, before service delegation**

Inside `createSharingRule`, after the existing authorization block (which ends around line 105 with `request.copy(sourceUserId = currentUserId, sourceUserEmail = null)`/`else { request }`) and before the `awsAccountSharingService.createSharingRule(...)` call (around line 107), insert:

```kotlin
            // Invite-mode validation. The service trusts these checks have
            // already run; do NOT move them into the service unless you also
            // remove them from here and update the design doc.
            if (effectiveRequest.inviteByEmail) {
                val email = effectiveRequest.targetUserEmail?.trim().orEmpty()
                if (!com.secman.util.EmailDomain.isWellFormed(email)) {
                    return HttpResponse.badRequest(mapOf(
                        "error" to "Validation Error",
                        "message" to "A valid email address is required to invite a new user"
                    ))
                }

                val callerEmail = userRepository.findById(currentUserId)
                    .map { it.email }.orElse(null)
                val callerDomain = com.secman.util.EmailDomain.extractDomain(callerEmail)
                if (callerDomain.isNullOrBlank()) {
                    return HttpResponse.serverError(mapOf(
                        "error" to "Internal Server Error",
                        "message" to "Cannot determine your email domain — contact an administrator"
                    ))
                }

                if (!com.secman.util.EmailDomain.sameDomain(callerEmail, email)) {
                    return HttpResponse.badRequest(mapOf(
                        "error" to "Validation Error",
                        "message" to "You can only invite users whose email matches your own domain (@$callerDomain)"
                    ))
                }

                if (email.equals(callerEmail, ignoreCase = true)) {
                    return HttpResponse.badRequest(mapOf(
                        "error" to "Validation Error",
                        "message" to "You cannot share with yourself"
                    ))
                }

                if (userRepository.findByEmailIgnoreCase(email).isPresent) {
                    return HttpResponse.status<Any>(HttpStatus.CONFLICT).body(mapOf(
                        "error" to "Conflict",
                        "message" to "This email is already a SecMan user — use 'Pick existing user' instead"
                    ))
                }

                val pendingHits = userMappingRepository.findByEmailAndStatus(email, MappingStatus.PENDING)
                if (pendingHits.isNotEmpty()) {
                    return HttpResponse.status<Any>(HttpStatus.CONFLICT).body(mapOf(
                        "error" to "Conflict",
                        "message" to "This email is already known via mapping import — use 'Pick existing user' instead"
                    ))
                }
            }
```

(`MappingStatus` is already imported at line 6 of the controller. `HttpStatus` and `userRepository`/`userMappingRepository` are already in scope.)

- [ ] **Step 3: Compile-check**

Run:
```bash
cd src/backendng && ./gradlew compileKotlin 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Add controller tests for the validator**

Create or append to `src/backendng/src/test/kotlin/com/secman/controller/AwsAccountSharingControllerInviteValidatorTest.kt`:

```kotlin
package com.secman.controller

import com.secman.domain.MappingStatus
import com.secman.domain.User
import com.secman.domain.UserMapping
import com.secman.dto.AwsAccountSharingResponse
import com.secman.dto.CreateAwsAccountSharingRequest
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.service.AwsAccountSharingService
import io.micronaut.http.HttpStatus
import io.micronaut.security.authentication.Authentication
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

class AwsAccountSharingControllerInviteValidatorTest {

    private lateinit var service: AwsAccountSharingService
    private lateinit var userRepository: UserRepository
    private lateinit var userMappingRepository: UserMappingRepository
    private lateinit var controller: AwsAccountSharingController

    private val callerId = 1L
    private val caller = User(id = callerId, username = "alice", email = "alice@example.com", passwordHash = "x")

    @BeforeEach
    fun setUp() {
        service = mockk()
        userRepository = mockk()
        userMappingRepository = mockk()
        controller = AwsAccountSharingController(service, userRepository, userMappingRepository)
        every { userRepository.findById(callerId) } returns Optional.of(caller)
    }

    private fun authStub(): Authentication = mockk {
        every { attributes } returns mapOf("userId" to callerId)
        every { roles } returns setOf("USER", "VULN")
        every { name } returns caller.username
    }

    @Test
    fun `400 when email is malformed`() {
        val request = CreateAwsAccountSharingRequest(
            sourceUserId = callerId,
            targetUserEmail = "not-an-email",
            inviteByEmail = true,
        )
        val response = controller.createSharingRule(request, authStub())
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
    }

    @Test
    fun `400 when domain mismatch`() {
        val request = CreateAwsAccountSharingRequest(
            sourceUserId = callerId,
            targetUserEmail = "bob@otherco.com",
            inviteByEmail = true,
        )
        val response = controller.createSharingRule(request, authStub())
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
    }

    @Test
    fun `400 when target email equals caller`() {
        val request = CreateAwsAccountSharingRequest(
            sourceUserId = callerId,
            targetUserEmail = "alice@example.com",
            inviteByEmail = true,
        )
        val response = controller.createSharingRule(request, authStub())
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
    }

    @Test
    fun `409 when target email is already a User`() {
        every { userRepository.findByEmailIgnoreCase("bob@example.com") } returns
            Optional.of(User(id = 2L, username = "bob", email = "bob@example.com", passwordHash = "x"))
        val request = CreateAwsAccountSharingRequest(
            sourceUserId = callerId,
            targetUserEmail = "bob@example.com",
            inviteByEmail = true,
        )
        val response = controller.createSharingRule(request, authStub())
        assertEquals(HttpStatus.CONFLICT, response.status)
    }

    @Test
    fun `409 when target email matches a PENDING UserMapping`() {
        every { userRepository.findByEmailIgnoreCase("pending@example.com") } returns Optional.empty()
        every { userMappingRepository.findByEmailAndStatus("pending@example.com", MappingStatus.PENDING) } returns
            listOf(mockk<UserMapping>(relaxed = true))
        val request = CreateAwsAccountSharingRequest(
            sourceUserId = callerId,
            targetUserEmail = "pending@example.com",
            inviteByEmail = true,
        )
        val response = controller.createSharingRule(request, authStub())
        assertEquals(HttpStatus.CONFLICT, response.status)
    }

    @Test
    fun `delegates to service when validation passes`() {
        every { userRepository.findByEmailIgnoreCase("newbie@example.com") } returns Optional.empty()
        every { userMappingRepository.findByEmailAndStatus("newbie@example.com", MappingStatus.PENDING) } returns
            emptyList()
        every { service.createSharingRule(any(), callerId) } returns mockk<AwsAccountSharingResponse>()
        val request = CreateAwsAccountSharingRequest(
            sourceUserId = callerId,
            targetUserEmail = "newbie@example.com",
            inviteByEmail = true,
        )
        val response = controller.createSharingRule(request, authStub())
        assertEquals(HttpStatus.CREATED, response.status)
        verify { service.createSharingRule(match { it.inviteByEmail && it.targetUserEmail == "newbie@example.com" }, callerId) }
    }
}
```

- [ ] **Step 5: Run controller tests to verify they pass**

Run:
```bash
cd src/backendng && ./gradlew test --tests "com.secman.controller.AwsAccountSharingControllerInviteValidatorTest" 2>&1 | tail -30
```
Expected: `BUILD SUCCESSFUL`, all 6 tests passing.

- [ ] **Step 6: Run the full backend test suite to confirm no regressions**

Run:
```bash
cd src/backendng && ./gradlew test 2>&1 | tail -40
```
Expected: no new failures attributable to this change. (Pre-existing failures, if any, are out of scope.)

- [ ] **Step 7: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/controller/AwsAccountSharingController.kt \
        src/backendng/src/test/kotlin/com/secman/controller/AwsAccountSharingControllerInviteValidatorTest.kt
git commit -m "feat(aws-sharing): controller-side invite-by-email validator

Strict pre-checks before service delegation when inviteByEmail=true:
- well-formed email (single @, non-empty parts)
- caller has a usable domain
- target domain matches caller's, case-insensitive, no subdomains
- target is not the caller
- target is not an existing User
- target is not a PENDING UserMapping

Failures map to 400 (validation) or 409 (collision); the service
trusts these checks have run."
```

---

## Task 10: Verify backend startup and full build

This is the project's hard rule: a change is complete only when both the build and runtime startup are clean.

- [ ] **Step 1: Full build**

Run:
```bash
cd /Users/flake/sources/misc/secman && ./gradlew build 2>&1 | tail -30
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Backend dev startup**

Run:
```bash
cd /Users/flake/sources/misc/secman && ./scriptpp/startbackenddev.sh 2>&1 | tail -50
```
Wait until the Micronaut banner appears with "Started application in N seconds". Then send Ctrl-C to stop. Expected: clean startup, no Hibernate warnings about the new entities/events, no bean-wiring failures.

If the startup output goes to a log rather than stdout in your shell, instead:

```bash
cd /Users/flake/sources/misc/secman && (./scriptpp/startbackenddev.sh > /tmp/secman-backend.log 2>&1 &) && sleep 30 && tail -80 /tmp/secman-backend.log
```

Then verify the log shows "Started application", and stop the backend:

```bash
lsof -iTCP:8080 -sTCP:LISTEN -n -P -t | xargs -r kill
```

Expected: clean Micronaut startup, no exceptions related to the modified beans.

- [ ] **Step 3: No commit needed** — this is a verification gate, not a code change.

---

## Task 11: Frontend — extend the create-request type

**Files:**
- Modify: `src/frontend/src/services/awsAccountSharingService.ts:29-43`

- [ ] **Step 1: Add the field to the interface**

Replace (in `src/frontend/src/services/awsAccountSharingService.ts`):

```typescript
export interface CreateAwsAccountSharingRequest {
    // Either *UserId or *UserEmail must be provided for each side.
    // Use email when selecting a "pending" user (one known only via
    // UserMapping who hasn't logged in yet) — the backend creates the
    // User record lazily so the sharing rule's FK is satisfied.
    sourceUserId?: number | null;
    sourceUserEmail?: string | null;
    targetUserId?: number | null;
    targetUserEmail?: string | null;
    /**
     * Optional. Empty/omitted → share ALL of the source's accounts.
     * Non-empty → restrict the share to the listed account IDs.
     */
    awsAccountIds?: string[] | null;
}
```

with:

```typescript
export interface CreateAwsAccountSharingRequest {
    // Either *UserId or *UserEmail must be provided for each side.
    // Use email when selecting a "pending" user (one known only via
    // UserMapping who hasn't logged in yet) — the backend creates the
    // User record lazily so the sharing rule's FK is satisfied.
    sourceUserId?: number | null;
    sourceUserEmail?: string | null;
    targetUserId?: number | null;
    targetUserEmail?: string | null;
    /**
     * Optional. Empty/omitted → share ALL of the source's accounts.
     * Non-empty → restrict the share to the listed account IDs.
     */
    awsAccountIds?: string[] | null;
    /**
     * When true, the backend treats `targetUserEmail` as a brand-new
     * invite: must be well-formed, share the caller's domain, and not
     * already be an existing User or PENDING UserMapping. The created
     * user gets roles [USER, VULN] and an onboarding email.
     *
     * Default false; legacy callers are unaffected.
     */
    inviteByEmail?: boolean;
}
```

- [ ] **Step 2: Type-check**

Run:
```bash
cd src/frontend && npx tsc --noEmit 2>&1 | tail -20
```
Expected: no errors. (The field is optional, so existing call-sites pass type-checking.)

- [ ] **Step 3: Commit**

```bash
git add src/frontend/src/services/awsAccountSharingService.ts
git commit -m "feat(aws-sharing): add inviteByEmail to frontend create-request type"
```

---

## Task 12: Frontend — radio toggle, email textbox, and submit logic

This is the largest frontend change. New state: `targetMode`, `inviteEmail`. New UI: radio group above the target column. New submit branch: build the request from `inviteEmail` instead of `targetSelection`.

**Files:**
- Modify: `src/frontend/src/components/AwsAccountSharingManager.tsx`

- [ ] **Step 1: Add state for the new mode and email field**

In `AwsAccountSharingManager.tsx`, near the other create-form state declarations (around line 62-77, immediately after `const [showCreateForm, setShowCreateForm] = useState(false);`), add:

```tsx
    // Radio mode for the target column. 'existing' uses the dropdown
    // (legacy behavior); 'invite' uses a free-text email box scoped to
    // the inviter's own email domain.
    const [targetMode, setTargetMode] = useState<'existing' | 'invite'>('existing');
    const [inviteEmail, setInviteEmail] = useState<string>('');
```

- [ ] **Step 2: Add a helper for the inviter's domain**

Near the top of the component body (after `currentUser` is read but before the JSX), compute:

```tsx
    // Domain extracted from the logged-in user's email — drives the
    // helper text and the client-side domain match. Backend remains
    // authoritative on this check.
    const callerDomain = (currentUser?.email ?? '').split('@')[1]?.toLowerCase() ?? '';
```

(Insert just after the existing `useEffect(() => { setHasFullViewAccess(...); ... }, []);` block — around line 95.)

- [ ] **Step 3: Reset invite state when the form opens or closes**

Find the existing form-open toggle handler (around line 341, inside the *Create Sharing Rule* button's `onClick`). Replace:

```tsx
                onClick={() => {
                    if (showCreateForm) {
                        // Closing the form: drop any stale submission error
                        // so it doesn't reappear next time we open it.
                        setFormError(null);
                    }
                    setShowCreateForm(!showCreateForm);
                }}
```

with:

```tsx
                onClick={() => {
                    if (showCreateForm) {
                        // Closing the form: drop any stale submission error
                        // and reset all create-form local state so a fresh
                        // re-open starts clean.
                        setFormError(null);
                        setTargetMode('existing');
                        setInviteEmail('');
                    }
                    setShowCreateForm(!showCreateForm);
                }}
```

- [ ] **Step 4: Insert the radio toggle above the target column**

In the create form's JSX, find the comment line `/* VULN and other users: source is fixed to current user */` and the two `col-md-5` blocks for source/target (around lines 408-500). Replace the *target* column block (the entire `<div className="col-md-5">` containing `<label htmlFor="targetUser" ...>` and the IIFE that follows it, lines 447-500) with:

```tsx
                                <div className="col-md-5">
                                    <label className="form-label">Target User (receives visibility)</label>
                                    <div className="btn-group btn-group-sm mb-2" role="group" aria-label="Target user mode">
                                        <input
                                            type="radio"
                                            className="btn-check"
                                            name="targetMode"
                                            id="targetMode-existing"
                                            checked={targetMode === 'existing'}
                                            onChange={() => {
                                                setTargetMode('existing');
                                                setInviteEmail('');
                                                setFormError(null);
                                            }}
                                        />
                                        <label className="btn btn-outline-secondary" htmlFor="targetMode-existing">
                                            Pick existing user
                                        </label>
                                        <input
                                            type="radio"
                                            className="btn-check"
                                            name="targetMode"
                                            id="targetMode-invite"
                                            checked={targetMode === 'invite'}
                                            onChange={() => {
                                                setTargetMode('invite');
                                                setTargetSelection('');
                                                setTargetSearch('');
                                                setFormError(null);
                                            }}
                                            disabled={!callerDomain}
                                        />
                                        <label className="btn btn-outline-secondary" htmlFor="targetMode-invite">
                                            Invite by email
                                        </label>
                                    </div>

                                    {targetMode === 'existing' ? (
                                        (() => {
                                            const search = targetSearch.trim().toLowerCase();
                                            // Exclude whichever user is currently chosen as the source —
                                            // for non-ADMIN users that's themselves (sourceSelection is
                                            // forced to "id:<currentUser.id>" at submit time, but is
                                            // empty in state); for ADMIN/SECCHAMPION it's whatever they
                                            // picked in the source dropdown.
                                            const effectiveSource = canManageAnyRule
                                                ? sourceSelection
                                                : (currentUser ? `id:${currentUser.id}` : '');
                                            const eligible = users.filter(u =>
                                                encodeUserOption(u) !== effectiveSource
                                            );
                                            const filtered = search
                                                ? eligible.filter(u =>
                                                    u.username.toLowerCase().includes(search) ||
                                                    u.email.toLowerCase().includes(search))
                                                : eligible;
                                            return (
                                                <>
                                                    <input
                                                        type="search"
                                                        className="form-control form-control-sm mb-2"
                                                        placeholder="Search by username or email…"
                                                        value={targetSearch}
                                                        onChange={(e) => setTargetSearch(e.target.value)}
                                                        aria-label="Search target user"
                                                    />
                                                    <select
                                                        id="targetUser"
                                                        className="form-select"
                                                        value={targetSelection}
                                                        onChange={(e) => setTargetSelection(e.target.value)}
                                                        required={targetMode === 'existing'}
                                                    >
                                                        <option value="">
                                                            {filtered.length === 0
                                                                ? '-- No matching users --'
                                                                : `-- Select target user${search ? ` (${filtered.length} match${filtered.length === 1 ? '' : 'es'})` : ''} --`}
                                                        </option>
                                                        {filtered.map((user) => (
                                                            <option key={encodeUserOption(user)} value={encodeUserOption(user)}>
                                                                {user.username} ({user.email}){user.isPending ? ' — pending' : ''}
                                                            </option>
                                                        ))}
                                                    </select>
                                                </>
                                            );
                                        })()
                                    ) : (
                                        <>
                                            <input
                                                type="email"
                                                id="inviteEmail"
                                                className="form-control"
                                                placeholder={`name@${callerDomain || 'yourdomain'}`}
                                                value={inviteEmail}
                                                onChange={(e) => setInviteEmail(e.target.value)}
                                                required={targetMode === 'invite'}
                                                aria-describedby="inviteEmailHelp"
                                            />
                                            <small id="inviteEmailHelp" className="form-text text-muted d-block mt-1">
                                                Email must end in <code>@{callerDomain || '…'}</code>.
                                                A SecMan account will be created with <code>USER + VULN</code> roles
                                                and the invitee will be emailed a login link.
                                            </small>
                                        </>
                                    )}
                                </div>
```

- [ ] **Step 5: Update the submit handler**

Find `handleCreate` (around line 209) and replace it entirely with:

```tsx
    const handleCreate = async (e: React.FormEvent) => {
        e.preventDefault();
        setFormError(null);

        // Non-privileged users never pick a source — it's forced to self.
        const effectiveSourceSelection = canManageAnyRule
            ? sourceSelection
            : (currentUser ? `id:${currentUser.id}` : '');

        if (!effectiveSourceSelection) {
            setFormError(canManageAnyRule ? 'Please select a source user' : 'Cannot determine your user record');
            return;
        }

        if (targetMode === 'existing' && !targetSelection) {
            setFormError('Please select a target user');
            return;
        }
        if (targetMode === 'invite') {
            const e = inviteEmail.trim();
            const atCount = (e.match(/@/g) ?? []).length;
            if (atCount !== 1 || e.startsWith('@') || e.endsWith('@')) {
                setFormError('Please enter a valid email address');
                return;
            }
            const domain = e.split('@')[1].toLowerCase();
            if (!callerDomain || domain !== callerDomain) {
                setFormError(`Email must end in @${callerDomain || 'your domain'}`);
                return;
            }
            if (currentUser && e.toLowerCase() === currentUser.email.toLowerCase()) {
                setFormError('You cannot share with yourself');
                return;
            }
        }
        if (targetMode === 'existing' && effectiveSourceSelection === targetSelection) {
            setFormError('Source and target user cannot be the same');
            return;
        }
        if (!shareAll && selectedAccountIds.size === 0) {
            setFormError('Select at least one AWS account, or switch back to "Share all".');
            return;
        }

        const src = decodeUserOption(effectiveSourceSelection);

        setIsCreating(true);
        try {
            const result = await createSharingRule({
                sourceUserId: src.id ?? null,
                sourceUserEmail: src.email ?? null,
                targetUserId: targetMode === 'existing' ? (decodeUserOption(targetSelection).id ?? null) : null,
                targetUserEmail: targetMode === 'existing'
                    ? (decodeUserOption(targetSelection).email ?? null)
                    : inviteEmail.trim(),
                inviteByEmail: targetMode === 'invite',
                awsAccountIds: shareAll ? null : Array.from(selectedAccountIds),
            });
            const scopeMsg = result.shareAllAccounts
                ? `${result.sharedAwsAccountCount} accounts (all)`
                : `${result.sharedAwsAccountCount} of ${sourceAccounts.length} accounts`;
            const inviteSuffix = targetMode === 'invite' ? ' (account created, login link emailed)' : '';
            showSuccess(
                `Sharing rule created: ${result.sourceUserEmail} → ${result.targetUserEmail} (${scopeMsg})${inviteSuffix}`
            );
            setShowCreateForm(false);
            setSourceSelection('');
            setTargetSelection('');
            setTargetSearch('');
            setTargetMode('existing');
            setInviteEmail('');
            setShareAll(true);
            setSelectedAccountIds(new Set());
            setSourceAccounts([]);
            setFormError(null);
            fetchSharingRules();
            fetchUsers(); // Pending users / new invitees may have just materialized — refresh dropdowns.
        } catch (err: any) {
            setFormError(err.message || 'Failed to create sharing rule');
        } finally {
            setIsCreating(false);
        }
    };
```

- [ ] **Step 6: Type-check**

Run:
```bash
cd src/frontend && npx tsc --noEmit 2>&1 | tail -30
```
Expected: no errors.

- [ ] **Step 7: Visual verification in the browser**

Start frontend dev server (backend may still be running from Task 10 — if not, restart it):

```bash
cd src/frontend && npm run dev
```

Open the AWS Account Sharing page. Verify visually:

1. The *Create Sharing Rule* form now shows a *Pick existing user* / *Invite by email* toggle on the target side.
2. Switching to *Invite by email* hides the dropdown and shows an email field with helper text *"Email must end in `@<yourdomain>`"*.
3. Switching back restores the dropdown.
4. Submitting an email with the wrong domain produces a form-level error.
5. Submitting a well-formed in-domain email creates the rule and the page-level success toast contains *"(account created, login link emailed)"*.

(If the backend isn't running, you can still verify steps 1-3; steps 4-5 require backend.)

Stop the dev server when done (Ctrl-C).

- [ ] **Step 8: Commit**

```bash
git add src/frontend/src/components/AwsAccountSharingManager.tsx
git commit -m "feat(aws-sharing): UI for invite-by-email target mode

Adds a *Pick existing user* / *Invite by email* radio toggle above
the target column. The invite mode renders an email textbox with
helper text scoped to the caller's domain, validates client-side
before submit (backend remains authoritative), and submits the new
inviteByEmail flag in the create-rule POST. Success toast carries an
'account created, login link emailed' suffix when an invite was sent."
```

---

## Task 13: Mandatory project E2E gates

Per CLAUDE.md: changes are not complete until both `/e2ejs` and `/e2evulnexception` pass cleanly.

- [ ] **Step 1: Run JavaScript-error E2E**

```bash
# In the same Claude Code session, invoke the e2ejs skill.
# It runs a scanner against SECMAN_HOST as both ADMIN and USER and
# fails on any console.error or thrown JS error.
```

Invoke the `e2ejs` skill. Wait for both admin and normal-user runs to report **0 JS errors** on every page. Documented 403 / 404 responses (e.g. RBAC denials, `/api/wg-vulns` for users without mappings) are not JS errors.

If errors are found, fix them and re-run before proceeding.

- [ ] **Step 2: Run vulnerability + exception E2E**

```bash
# Invoke the e2evulnexception skill.
# It exercises the full vuln + exception lifecycle through MCP and
# the web UI; cleanup runs before and after.
```

Invoke the `e2evulnexception` skill. Wait for **0 failures** in both MCP and UI legs.

- [ ] **Step 3: No commit needed** — these are verification gates.

---

## Task 14: Final verification + summary commit

- [ ] **Step 1: One last full build**

```bash
cd /Users/flake/sources/misc/secman && ./gradlew build 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Confirm history**

Run:
```bash
git log --oneline main..HEAD 2>/dev/null || git log --oneline -15
```
Expected: a clean sequence of feature commits, one per task.

- [ ] **Step 3: No final commit needed** — every task above ended with its own atomic commit. The branch is ready for review/PR.

---

## Self-review notes

- **Spec coverage** — every decision in the design doc maps to a task: domain helper (T1), DTO flag (T2), role override (T3), event field (T4), service branch + audit log (T5), templates (T6/T7), notification rendering + login URL (T8), controller validator (T9), frontend service type (T11), frontend UI + validation (T12). Mandatory project gates (T10, T13). No spec sections left without an implementing task.
- **Placeholder check** — every step contains real code or a real command. No "TODO", no "TBD", no "similar to Task N".
- **Type consistency** — the optional `roles: Set<User.Role>?` parameter introduced in Task 3 is referenced verbatim in Task 5. The `targetUserWasJustCreated` field added in Task 4 matches the property name used in Task 5 publishing and Task 8 substitution. The frontend `inviteByEmail?: boolean` field added in Task 11 is read with the same name in Task 12. The `EmailDomain` object name and method names (`extractDomain`, `sameDomain`, `isWellFormed`) introduced in Task 1 match the call sites in Task 9.
