# AWS Sharing Email Notification — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send an email to the **target user** when an AWS account sharing rule is successfully created.

**Architecture:** Mirror the exception-request transactional-event-listener pattern. `AwsAccountSharingService.createSharingRule()` publishes an `AwsAccountSharingCreatedEvent` carrying *primitive* fields (no Hibernate proxies). An `@TransactionalEventListener(AFTER_COMMIT)` consumes the event and dispatches the email asynchronously through `EmailService.sendEmailWithInlineImages`. Email failure cannot roll back the DB write; DB rollback cannot accidentally trigger an email.

**Tech Stack:** Kotlin 2.3 / JDK 21, Micronaut 4.10, Hibernate JPA, JUnit 6, Mockk, Gradle 9.5. Email infrastructure (`EmailService`, `email-templates/`) already exists and is reused as-is.

---

## File Map

**New files (5):**

- `src/backendng/src/main/kotlin/com/secman/domain/AwsAccountSharingCreatedEvent.kt` — event payload carrying primitives only
- `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingNotificationService.kt` — builds + dispatches email
- `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingNotificationListener.kt` — `AFTER_COMMIT` listener
- `src/backendng/src/main/resources/email-templates/aws-sharing-granted.html` — HTML template
- `src/backendng/src/main/resources/email-templates/aws-sharing-granted.txt` — plain-text fallback

**Modified files (1):**

- `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt` — inject `ApplicationEventPublisher`, publish event after `save()`

**New test files (3):**

- `src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingNotificationServiceTest.kt`
- `src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingNotificationListenerTest.kt`
- `src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingServiceTest.kt`

**No DB migration. No frontend change. No API contract change.**

---

## Task 1: Domain event class

**Files:**
- Create: `src/backendng/src/main/kotlin/com/secman/domain/AwsAccountSharingCreatedEvent.kt`

The event carries only primitives. We resolve all `User` fields from the lazy associations on the caller's transactional thread, then publish the event. This is a defensive choice: even though Micronaut delivers `@TransactionalEventListener(AFTER_COMMIT)` events on the same thread that called `publishEvent` (which still owns the Hibernate session), keeping primitives only in the event guarantees correctness even if the framework's threading model changes.

- [ ] **Step 1: Create the event class**

```kotlin
package com.secman.domain

import io.micronaut.serde.annotation.Serdeable

/**
 * Domain event published after an `AwsAccountSharing` rule has been
 * successfully persisted. Consumed AFTER_COMMIT by
 * `AwsAccountSharingNotificationListener` to send an email to the
 * target user announcing the new access.
 *
 * The payload carries primitives only — no Hibernate-managed entities
 * cross the event boundary. The publishing service is responsible for
 * resolving lazy `User` associations on its transactional thread.
 */
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

- [ ] **Step 2: Compile the new file**

Run from `src/backendng/`:

```bash
./gradlew :backendng:compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/domain/AwsAccountSharingCreatedEvent.kt
git commit -m "feat(aws-sharing): add AwsAccountSharingCreatedEvent domain event

Carries primitive fields only so async listeners never touch Hibernate
proxies. Will be published from AwsAccountSharingService after save."
```

---

## Task 2: Email templates

**Files:**
- Create: `src/backendng/src/main/resources/email-templates/aws-sharing-granted.html`
- Create: `src/backendng/src/main/resources/email-templates/aws-sharing-granted.txt`

Templates use simple `{placeholder}` tokens — no templating engine — to match how `ExceptionRequestNotificationService` builds emails (string interpolation in Kotlin code). The notification service in Task 3 reads the resource and replaces tokens.

The HTML version references the inline image with `cid:secman-logo` (matches how the exception flow loads `SecManLogo.png`).

- [ ] **Step 1: Create the HTML template**

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

- [ ] **Step 2: Create the plain-text template**

```text
AWS account access shared with you in SecMan
=============================================

Hello {targetUsername},

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

- [ ] **Step 3: Verify the files are picked up by Gradle resources**

Run from `src/backendng/`:

```bash
./gradlew :backendng:processResources
ls build/resources/main/email-templates/aws-sharing-granted.*
```

Expected output:

```
build/resources/main/email-templates/aws-sharing-granted.html
build/resources/main/email-templates/aws-sharing-granted.txt
```

- [ ] **Step 4: Commit**

```bash
git add src/backendng/src/main/resources/email-templates/aws-sharing-granted.html \
        src/backendng/src/main/resources/email-templates/aws-sharing-granted.txt
git commit -m "feat(aws-sharing): add email templates for sharing-granted notification"
```

---

## Task 3: Notification service (TDD)

**Files:**
- Create: `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingNotificationService.kt`
- Test: `src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingNotificationServiceTest.kt`

This service:
1. Loads templates from classpath.
2. Substitutes tokens with event payload.
3. Loads inline logo (same as exception flow).
4. Calls `EmailService.sendEmailWithInlineImages(...)` and waits via `future.get()` to log success/failure.

It does **not** resolve any Hibernate fields. The event already carries primitives.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.secman.service

import com.secman.config.AppConfig
import com.secman.domain.AwsAccountSharingCreatedEvent
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class AwsAccountSharingNotificationServiceTest {

    private lateinit var emailService: EmailService
    private lateinit var appConfig: AppConfig
    private lateinit var service: AwsAccountSharingNotificationService

    private fun sampleEvent() = AwsAccountSharingCreatedEvent(
        sharingId = 42L,
        sourceUserEmail = "alice@example.com",
        targetUserId = 7L,
        targetUserEmail = "bob@example.com",
        targetUsername = "bob",
        createdByEmail = "admin@example.com",
        createdAtIso = "2026-05-06T10:15:00Z",
        sharedAwsAccountCount = 3,
    )

    @BeforeEach
    fun setUp() {
        emailService = mockk(relaxed = false)
        appConfig = mockk()
        val backendCfg = mockk<AppConfig.BackendConfig>()
        every { backendCfg.baseUrl } returns "https://secman.example.com/"
        every { appConfig.backend } returns backendCfg
        service = AwsAccountSharingNotificationService(emailService, appConfig)
    }

    @Test
    fun `sends email to target user with substituted fields`() {
        val toSlot = slot<String>()
        val subjectSlot = slot<String>()
        val textSlot = slot<String>()
        val htmlSlot = slot<String>()
        every {
            emailService.sendEmailWithInlineImages(
                capture(toSlot), capture(subjectSlot), capture(textSlot), capture(htmlSlot), any()
            )
        } returns CompletableFuture.completedFuture(true)

        service.notifyTargetOfNewShare(sampleEvent()).get()

        assert(toSlot.captured == "bob@example.com")
        assert(subjectSlot.captured == "AWS account access shared with you in SecMan")

        // Both bodies must contain key fields
        for (body in listOf(textSlot.captured, htmlSlot.captured)) {
            assert(body.contains("alice@example.com")) { "missing source email in: $body" }
            assert(body.contains("bob")) { "missing target username in: $body" }
            assert(body.contains("3")) { "missing account count in: $body" }
            assert(body.contains("admin@example.com")) { "missing createdBy email in: $body" }
            assert(body.contains("2026-05-06T10:15:00Z")) { "missing createdAtIso in: $body" }
            // baseUrl trailing slash is trimmed
            assert(body.contains("https://secman.example.com/assets")) { "missing assets url in: $body" }
        }

        verify(exactly = 1) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `returns false when emailService is not configured`() {
        every {
            emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any())
        } returns CompletableFuture.completedFuture(false)

        val result = service.notifyTargetOfNewShare(sampleEvent()).get()

        assertFalse(result)
    }

    @Test
    fun `does not throw when emailService throws`() {
        every {
            emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any())
        } throws RuntimeException("smtp blew up")

        // Must not propagate; CompletableFuture should resolve to false
        val result = service.notifyTargetOfNewShare(sampleEvent()).get()
        assertFalse(result)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (compilation error)**

```bash
./gradlew :backendng:test --tests "com.secman.service.AwsAccountSharingNotificationServiceTest" -i
```

Expected: compilation failure — `AwsAccountSharingNotificationService` does not exist yet.

- [ ] **Step 3: Implement `AwsAccountSharingNotificationService`**

```kotlin
package com.secman.service

import com.secman.config.AppConfig
import com.secman.domain.AwsAccountSharingCreatedEvent
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Builds and dispatches the email sent to a sharing rule's target user
 * when an AwsAccountSharing has just been created.
 *
 * Pattern matches ExceptionRequestNotificationService:
 *  - never throws upward
 *  - returns CompletableFuture<Boolean>
 *  - email I/O wrapped via EmailService.sendEmailWithInlineImages
 *
 * The input event carries primitives only — there are no Hibernate
 * lazy associations to worry about.
 */
@Singleton
open class AwsAccountSharingNotificationService(
    private val emailService: EmailService,
    private val appConfig: AppConfig,
) {
    private val log = LoggerFactory.getLogger(AwsAccountSharingNotificationService::class.java)

    companion object {
        private const val SUBJECT = "AWS account access shared with you in SecMan"
        private const val HTML_TEMPLATE = "/email-templates/aws-sharing-granted.html"
        private const val TEXT_TEMPLATE = "/email-templates/aws-sharing-granted.txt"
        private const val LOGO_PATH = "/email-templates/SecManLogo.png"
        private const val ASSETS_PATH = "/assets"
    }

    open fun notifyTargetOfNewShare(event: AwsAccountSharingCreatedEvent): CompletableFuture<Boolean> {
        return try {
            val htmlTemplate = readResource(HTML_TEMPLATE)
            val textTemplate = readResource(TEXT_TEMPLATE)
            val assetsUrl = appConfig.backend.baseUrl.trimEnd('/') + ASSETS_PATH

            val htmlBody = substitute(htmlTemplate, event, assetsUrl)
            val textBody = substitute(textTemplate, event, assetsUrl)
            val inlineImages = loadLogoInlineImage()

            val future = emailService.sendEmailWithInlineImages(
                to = event.targetUserEmail,
                subject = SUBJECT,
                textContent = textBody,
                htmlContent = htmlBody,
                inlineImages = inlineImages,
            )

            future.handle { sent, ex ->
                if (ex != null) {
                    log.error("Failed to send AWS sharing notification (sharingId={}): {}",
                        event.sharingId, ex.message)
                    false
                } else {
                    if (sent == true) {
                        log.info("Sent AWS sharing notification to {} (sharingId={})",
                            event.targetUserEmail, event.sharingId)
                    } else {
                        log.warn("AWS sharing notification not delivered to {} (sharingId={})",
                            event.targetUserEmail, event.sharingId)
                    }
                    sent == true
                }
            }
        } catch (e: Exception) {
            log.error("Unable to build AWS sharing notification (sharingId={}): {}",
                event.sharingId, e.message, e)
            CompletableFuture.completedFuture(false)
        }
    }

    private fun substitute(template: String, e: AwsAccountSharingCreatedEvent, assetsUrl: String): String {
        return template
            .replace("{targetUsername}", e.targetUsername)
            .replace("{sourceUserEmail}", e.sourceUserEmail)
            .replace("{sharedAwsAccountCount}", e.sharedAwsAccountCount.toString())
            .replace("{createdByEmail}", e.createdByEmail)
            .replace("{createdAtIso}", e.createdAtIso)
            .replace("{assetsUrl}", assetsUrl)
    }

    private fun readResource(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
            ?: throw IllegalStateException("Email template not found on classpath: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun loadLogoInlineImage(): Map<String, Pair<ByteArray, String>> {
        return try {
            val bytes = javaClass.getResourceAsStream(LOGO_PATH)?.readAllBytes()
            if (bytes != null) mapOf("secman-logo" to (bytes to "image/png")) else emptyMap()
        } catch (e: Exception) {
            log.warn("Failed to load SecManLogo.png: {}", e.message)
            emptyMap()
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :backendng:test --tests "com.secman.service.AwsAccountSharingNotificationServiceTest"
```

Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingNotificationService.kt \
        src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingNotificationServiceTest.kt
git commit -m "feat(aws-sharing): add AwsAccountSharingNotificationService

Builds + dispatches the email sent to a target user when a sharing
rule is created. Mirrors ExceptionRequestNotificationService's async
+ never-throws contract."
```

---

## Task 4: Transactional event listener (TDD)

**Files:**
- Create: `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingNotificationListener.kt`
- Test: `src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingNotificationListenerTest.kt`

The listener is intentionally tiny: receive event, call service, swallow + log any exception. Test verifies both: (a) it forwards the event, (b) it does not propagate exceptions.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.secman.service

import com.secman.domain.AwsAccountSharingCreatedEvent
import io.mockk.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class AwsAccountSharingNotificationListenerTest {

    private fun sampleEvent() = AwsAccountSharingCreatedEvent(
        sharingId = 1L,
        sourceUserEmail = "alice@example.com",
        targetUserId = 2L,
        targetUserEmail = "bob@example.com",
        targetUsername = "bob",
        createdByEmail = "admin@example.com",
        createdAtIso = "2026-05-06T10:15:00Z",
        sharedAwsAccountCount = 1,
    )

    @Test
    fun `forwards event to notification service`() {
        val service = mockk<AwsAccountSharingNotificationService>()
        every { service.notifyTargetOfNewShare(any()) } returns CompletableFuture.completedFuture(true)

        val listener = AwsAccountSharingNotificationListener(service)
        listener.onCreated(sampleEvent())

        verify(exactly = 1) { service.notifyTargetOfNewShare(any()) }
    }

    @Test
    fun `swallows exceptions thrown by notification service`() {
        val service = mockk<AwsAccountSharingNotificationService>()
        every { service.notifyTargetOfNewShare(any()) } throws RuntimeException("boom")

        val listener = AwsAccountSharingNotificationListener(service)
        // Must not throw
        listener.onCreated(sampleEvent())

        verify(exactly = 1) { service.notifyTargetOfNewShare(any()) }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (compilation)**

```bash
./gradlew :backendng:test --tests "com.secman.service.AwsAccountSharingNotificationListenerTest" -i
```

Expected: compilation failure — `AwsAccountSharingNotificationListener` does not exist.

- [ ] **Step 3: Implement the listener**

```kotlin
package com.secman.service

import com.secman.domain.AwsAccountSharingCreatedEvent
import io.micronaut.transaction.annotation.TransactionalEventListener
import io.micronaut.transaction.annotation.TransactionalEventListener.TransactionPhase
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * AFTER_COMMIT listener that triggers the email notification to the
 * target user of a newly created AwsAccountSharing rule.
 *
 * Lives outside AwsAccountSharingService so the @Transactional boundary
 * never encloses notification I/O. Exceptions are swallowed and logged
 * — a failed notification must never affect the data outcome.
 */
@Singleton
open class AwsAccountSharingNotificationListener(
    private val notificationService: AwsAccountSharingNotificationService,
) {
    private val log = LoggerFactory.getLogger(AwsAccountSharingNotificationListener::class.java)

    @TransactionalEventListener(TransactionPhase.AFTER_COMMIT)
    open fun onCreated(event: AwsAccountSharingCreatedEvent) {
        try {
            notificationService.notifyTargetOfNewShare(event)
        } catch (e: Exception) {
            log.error("Failed to dispatch AWS sharing notification (sharingId={}): {}",
                event.sharingId, e.message)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :backendng:test --tests "com.secman.service.AwsAccountSharingNotificationListenerTest"
```

Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingNotificationListener.kt \
        src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingNotificationListenerTest.kt
git commit -m "feat(aws-sharing): add AFTER_COMMIT listener for sharing-created event"
```

---

## Task 5: Wire event publisher into `AwsAccountSharingService` (TDD)

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt`
- Test: `src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingServiceTest.kt`

This is the only modification to existing code. We add an injected `ApplicationEventPublisher<AwsAccountSharingCreatedEvent>` and call `publishEvent(...)` after the existing `awsAccountSharingRepository.save(sharing)` line in `createSharingRule()`. The event payload is built from primitives we already have at that point (we already compute `sourceAwsAccounts.size` for the response).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.secman.service

import com.secman.domain.AwsAccountSharing
import com.secman.domain.AwsAccountSharingCreatedEvent
import com.secman.domain.User
import com.secman.dto.CreateAwsAccountSharingRequest
import com.secman.repository.AwsAccountSharingRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import io.micronaut.context.event.ApplicationEventPublisher
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class AwsAccountSharingServiceTest {

    private lateinit var repo: AwsAccountSharingRepository
    private lateinit var userRepo: UserRepository
    private lateinit var mappingRepo: UserMappingRepository
    private lateinit var resolver: UserResolutionService
    private lateinit var publisher: ApplicationEventPublisher<AwsAccountSharingCreatedEvent>
    private lateinit var service: AwsAccountSharingService

    private fun user(id: Long, email: String, username: String = email.substringBefore('@')) =
        User(id = id, username = username, email = email, password = "x", roles = mutableSetOf())

    @BeforeEach
    fun setUp() {
        repo = mockk()
        userRepo = mockk()
        mappingRepo = mockk()
        resolver = mockk()
        publisher = mockk(relaxed = true)
        service = AwsAccountSharingService(repo, userRepo, mappingRepo, resolver, publisher)
    }

    @Test
    fun `publishes AwsAccountSharingCreatedEvent after successful save`() {
        val source = user(1L, "alice@example.com")
        val target = user(2L, "bob@example.com", "bob")
        val admin  = user(3L, "admin@example.com")

        every { resolver.resolveByIdOrEmail(any(), any(), "source") } returns source
        every { resolver.resolveByIdOrEmail(any(), any(), "target") } returns target
        every { userRepo.findById(3L) } returns Optional.of(admin)
        every { repo.existsBySourceUserIdAndTargetUserId(1L, 2L) } returns false
        every { mappingRepo.findDistinctAwsAccountIdByEmail("alice@example.com") } returns
            listOf("111111111111", "222222222222")

        val savedSharing = AwsAccountSharing(
            id = 99L, sourceUser = source, targetUser = target, createdBy = admin,
            createdAt = Instant.parse("2026-05-06T10:15:00Z"),
            updatedAt = Instant.parse("2026-05-06T10:15:00Z"),
        )
        every { repo.save(any()) } returns savedSharing

        val req = CreateAwsAccountSharingRequest(
            sourceUserId = 1L, sourceUserEmail = null,
            targetUserId = 2L, targetUserEmail = null,
        )

        service.createSharingRule(req, adminUserId = 3L)

        val eventSlot = slot<AwsAccountSharingCreatedEvent>()
        verify(exactly = 1) { publisher.publishEvent(capture(eventSlot)) }

        val ev = eventSlot.captured
        assert(ev.sharingId == 99L)
        assert(ev.sourceUserEmail == "alice@example.com")
        assert(ev.targetUserId == 2L)
        assert(ev.targetUserEmail == "bob@example.com")
        assert(ev.targetUsername == "bob")
        assert(ev.createdByEmail == "admin@example.com")
        assert(ev.sharedAwsAccountCount == 2)
        assert(ev.createdAtIso == "2026-05-06T10:15:00Z")
    }

    @Test
    fun `does not publish when source equals target`() {
        val same = user(1L, "alice@example.com")
        every { resolver.resolveByIdOrEmail(any(), any(), "source") } returns same
        every { resolver.resolveByIdOrEmail(any(), any(), "target") } returns same

        val req = CreateAwsAccountSharingRequest(
            sourceUserId = 1L, sourceUserEmail = null,
            targetUserId = 1L, targetUserEmail = null,
        )

        assertThrows(IllegalArgumentException::class.java) {
            service.createSharingRule(req, adminUserId = 1L)
        }
        verify(exactly = 0) { publisher.publishEvent(any()) }
    }

    @Test
    fun `does not publish when duplicate sharing exists`() {
        val source = user(1L, "alice@example.com")
        val target = user(2L, "bob@example.com")
        val admin  = user(3L, "admin@example.com")

        every { resolver.resolveByIdOrEmail(any(), any(), "source") } returns source
        every { resolver.resolveByIdOrEmail(any(), any(), "target") } returns target
        every { userRepo.findById(3L) } returns Optional.of(admin)
        every { repo.existsBySourceUserIdAndTargetUserId(1L, 2L) } returns true

        val req = CreateAwsAccountSharingRequest(
            sourceUserId = 1L, sourceUserEmail = null,
            targetUserId = 2L, targetUserEmail = null,
        )

        assertThrows(DuplicateSharingException::class.java) {
            service.createSharingRule(req, adminUserId = 3L)
        }
        verify(exactly = 0) { publisher.publishEvent(any()) }
    }

    @Test
    fun `does not publish when source has no AWS mappings`() {
        val source = user(1L, "alice@example.com")
        val target = user(2L, "bob@example.com")
        val admin  = user(3L, "admin@example.com")

        every { resolver.resolveByIdOrEmail(any(), any(), "source") } returns source
        every { resolver.resolveByIdOrEmail(any(), any(), "target") } returns target
        every { userRepo.findById(3L) } returns Optional.of(admin)
        every { repo.existsBySourceUserIdAndTargetUserId(1L, 2L) } returns false
        every { mappingRepo.findDistinctAwsAccountIdByEmail("alice@example.com") } returns emptyList()

        val req = CreateAwsAccountSharingRequest(
            sourceUserId = 1L, sourceUserEmail = null,
            targetUserId = 2L, targetUserEmail = null,
        )

        assertThrows(IllegalArgumentException::class.java) {
            service.createSharingRule(req, adminUserId = 3L)
        }
        verify(exactly = 0) { publisher.publishEvent(any()) }
    }
}
```

> **Note on User constructor:** the test calls `User(id, username, email, password, roles)`. If the actual `User` constructor requires more (or fewer) named arguments, adjust the helper `user(...)` accordingly — open `src/backendng/src/main/kotlin/com/secman/domain/User.kt`, copy the primary constructor signature, and minimally fill it. The point of the helper is to produce a `User` with `id`, `email`, `username` set; everything else can take entity defaults.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :backendng:test --tests "com.secman.service.AwsAccountSharingServiceTest" -i
```

Expected: compilation failure — `AwsAccountSharingService` constructor does not accept a 5th `ApplicationEventPublisher` argument yet.

- [ ] **Step 3: Modify `AwsAccountSharingService`**

Open `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt`.

Add to the imports (top of file, alongside the existing imports):

```kotlin
import com.secman.domain.AwsAccountSharingCreatedEvent
import io.micronaut.context.event.ApplicationEventPublisher
import java.time.Instant
```

Change the primary constructor (currently lines 24–29) from:

```kotlin
@Singleton
open class AwsAccountSharingService(
    private val awsAccountSharingRepository: AwsAccountSharingRepository,
    private val userRepository: UserRepository,
    private val userMappingRepository: UserMappingRepository,
    private val userResolutionService: UserResolutionService
) {
```

…to:

```kotlin
@Singleton
open class AwsAccountSharingService(
    private val awsAccountSharingRepository: AwsAccountSharingRepository,
    private val userRepository: UserRepository,
    private val userMappingRepository: UserMappingRepository,
    private val userResolutionService: UserResolutionService,
    private val sharingCreatedEventPublisher: ApplicationEventPublisher<AwsAccountSharingCreatedEvent>,
) {
```

Then in `createSharingRule(...)`, locate the existing block (currently lines 119–123):

```kotlin
        val saved = awsAccountSharingRepository.save(sharing)
        log.info("AUDIT: AWS account sharing created: source={}, target={}, admin={}, sharedAccounts={}",
            sourceUser.email, targetUser.email, adminUser.email, sourceAwsAccounts.size)

        return saved.toResponse(sharedAwsAccountCount = sourceAwsAccounts.size)
```

…and replace it with:

```kotlin
        val saved = awsAccountSharingRepository.save(sharing)
        log.info("AUDIT: AWS account sharing created: source={}, target={}, admin={}, sharedAccounts={}",
            sourceUser.email, targetUser.email, adminUser.email, sourceAwsAccounts.size)

        sharingCreatedEventPublisher.publishEvent(
            AwsAccountSharingCreatedEvent(
                sharingId = saved.id!!,
                sourceUserEmail = sourceUser.email,
                targetUserId = targetUser.id!!,
                targetUserEmail = targetUser.email,
                targetUsername = targetUser.username,
                createdByEmail = adminUser.email,
                createdAtIso = (saved.createdAt ?: Instant.now()).toString(),
                sharedAwsAccountCount = sourceAwsAccounts.size,
            )
        )

        return saved.toResponse(sharedAwsAccountCount = sourceAwsAccounts.size)
```

The event is published *inside* the `@Transactional` method, but the listener consumes `AFTER_COMMIT`, so the email only goes out if the DB commit succeeds.

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :backendng:test --tests "com.secman.service.AwsAccountSharingServiceTest"
```

Expected: 4 tests pass.

- [ ] **Step 5: Run the full backend test suite**

```bash
./gradlew :backendng:test
```

Expected: all tests pass. No existing tests broken (we only added a constructor parameter that Micronaut DI fills automatically; service contract is unchanged).

- [ ] **Step 6: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt \
        src/backendng/src/test/kotlin/com/secman/service/AwsAccountSharingServiceTest.kt
git commit -m "feat(aws-sharing): publish AwsAccountSharingCreatedEvent on rule creation

Inject ApplicationEventPublisher and emit event with primitive fields
after a successful save. AFTER_COMMIT listener will translate this
into an email to the target user."
```

---

## Task 6: Build, runtime, and E2E gates

The test suite proves logic; this task proves the change is complete per CLAUDE.md.

- [ ] **Step 1: Clean build**

Run from repo root:

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL with no test failures across all modules.

- [ ] **Step 2: Backend startup smoke test**

```bash
./scriptpp/startbackenddev.sh
```

Watch for these log lines (in any order):

```
Bean of type [com.secman.service.AwsAccountSharingNotificationService] ...
Bean of type [com.secman.service.AwsAccountSharingNotificationListener] ...
Started Application in N seconds
```

Confirm Micronaut wired the new beans (no `BeanInstantiationException` or `NoSuchBeanException`). Then stop the backend (`Ctrl-C` or `kill` the gradle PID).

If you see a `BeanInstantiationException` mentioning `ApplicationEventPublisher<AwsAccountSharingCreatedEvent>`, ensure the event class is annotated `@Serdeable` (Task 1) and is on the classpath.

- [ ] **Step 3: Manual end-to-end verification (optional but recommended)**

With backend running and a configured email provider, hit the create endpoint:

```bash
SECMAN_HOST=$(pass-cli get secman/host)
ADMIN_TOKEN=$(...)   # use the project's standard token-fetch flow

curl -X POST "$SECMAN_HOST/api/aws-account-sharing" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sourceUserEmail":"<existing-source>","targetUserEmail":"<your-test-target>"}'
```

Verify:
- HTTP 200 response.
- Backend log contains `AUDIT: AWS account sharing created` followed by `Sent AWS sharing notification to <target> (sharingId=...)`.
- The target user's inbox receives an email with subject `AWS account access shared with you in SecMan`.

If no email provider is configured, the log will instead show `No active email configuration found. Skipping email to <target>` — that is also a successful path; the rule is still created.

- [ ] **Step 4: Run `/e2ejs`**

Trigger the skill (per CLAUDE.md "Mandatory post-change E2E gates"):

```
/e2ejs
```

Expected: 0 JS errors for both admin and normal-user runs against `SECMAN_HOST`.

- [ ] **Step 5: Run `/e2evulnexception`**

```
/e2evulnexception
```

Expected: 0 failures in the full vuln + exception lifecycle run (MCP + UI). This feature does not touch those code paths but the gate is mandatory per CLAUDE.md.

- [ ] **Step 6: Final review and merge readiness**

Verify the commit log on the branch shows the expected sequence:

```bash
git log --oneline main..HEAD
```

Expected (5 commits):

```
feat(aws-sharing): publish AwsAccountSharingCreatedEvent on rule creation
feat(aws-sharing): add AFTER_COMMIT listener for sharing-created event
feat(aws-sharing): add AwsAccountSharingNotificationService
feat(aws-sharing): add email templates for sharing-granted notification
feat(aws-sharing): add AwsAccountSharingCreatedEvent domain event
```

Branch is ready for PR.

---

## Self-Review Notes

- **Spec coverage:** all 5 new files + 1 modified file in the spec map to Tasks 1–5. Mandatory gates from spec map to Task 6. ✓
- **No placeholders:** every step contains concrete code or commands. The single `User` constructor caveat in Task 5 explicitly tells the engineer how to resolve it (open the file, copy the constructor, fill defaults) rather than leaving a TBD. ✓
- **Type consistency:** `AwsAccountSharingCreatedEvent` field names are identical across Tasks 1, 3, 4, 5. Method `notifyTargetOfNewShare(event)` and `onCreated(event)` are stable across Tasks 3 and 4. ✓
- **Order-of-execution:** Tasks 1→2→3→4→5→6. Task 5 depends on Tasks 1, 3, 4 (event class, listener, and service must exist before wiring). Task 6 depends on all preceding tasks. ✓
