# Workgroup AWS Account Assignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add AWS cloud accounts as a third direct-membership resource on Workgroup, becoming access rule #9 in the existing 8-rule asset-access matrix.

**Architecture:** New entity `WorkgroupAwsAccount` with M:N-via-entity relationship to Workgroup (audit fields preserved). REST + MCP surface parallel to existing `/users` and `/assets` workgroup endpoints. `AssetFilterService` gains a fifth source list combining via OR with existing four. Frontend gets one new column, one new button, one new modal in the Workgroup Management table.

**Tech Stack:** Kotlin 2.3.20 / Micronaut 4.10 / Hibernate JPA, MariaDB 11.4 + Flyway, Astro 6.1 / React 19 / Bootstrap 5.3, Picocli (no MCP code yet — added via dedicated tool class).

**Testing policy:** Per CLAUDE.md principle #5 ("Never write testcases"), this plan uses `./gradlew build` and manual smoke tests for verification. New test files are deliberately deferred. The user may add them separately if desired (spec section 11 enumerates the desired coverage).

**Spec:** [docs/superpowers/specs/2026-04-28-workgroup-aws-account-assignment-design.md](../specs/2026-04-28-workgroup-aws-account-assignment-design.md)

---

## File Structure

### Backend — Create

| Path | Responsibility |
|------|----------------|
| `src/backendng/src/main/kotlin/com/secman/domain/WorkgroupAwsAccount.kt` | Entity: workgroup × awsAccountId × audit fields |
| `src/backendng/src/main/kotlin/com/secman/repository/WorkgroupAwsAccountRepository.kt` | Micronaut Data JPA repo with derived queries |
| `src/backendng/src/main/kotlin/com/secman/service/WorkgroupAwsAccountService.kt` | Business logic: validation, duplicate prevention, transactional CRUD |
| `src/backendng/src/main/kotlin/com/secman/dto/WorkgroupAwsAccountDto.kt` | Wire-format response (no entity reference cycles) |
| `src/backendng/src/main/kotlin/com/secman/controller/WorkgroupAwsAccountController.kt` | REST endpoints under `/api/workgroups/{id}/aws-accounts` |
| `src/backendng/src/main/kotlin/com/secman/mcp/tools/WorkgroupAwsAccountTools.kt` | Three MCP tools (list/add/remove) |
| `src/backendng/src/main/resources/db/migration/V201__add_workgroup_aws_account.sql` | DDL — explicit Flyway migration |

### Backend — Modify

| Path | Change |
|------|--------|
| `src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt` | Add `awsAccounts: MutableSet<WorkgroupAwsAccount>` back-reference (`mappedBy = "workgroup"`, `cascade = ALL`, `orphanRemoval = true`, `@JsonIgnore`) |
| `src/backendng/src/main/kotlin/com/secman/service/AssetFilterService.kt` | Append fifth source list `workgroupAccountAssets` to `findFilteredAssets` |
| `src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt` | Extend `clearWorkgroupMemberships` to also clear `workgroup_aws_account` rows on workgroup delete (FK constraint + audit consistency) |
| `CLAUDE.md` | Add access rule #9 to "Unified Access Control" section; add three MCP tools to MCP tools list under Workgroups |

### Frontend — Create

| Path | Responsibility |
|------|----------------|
| `src/frontend/src/components/WorkgroupAccountsModal.tsx` | Modal for listing/adding/removing AWS account IDs on a workgroup |

### Frontend — Modify

| Path | Change |
|------|--------|
| `src/frontend/src/components/WorkgroupManagement.tsx` | Add "Accounts" count column + "Accounts" action button per row |

---

### Task 1: Create entity, repository, and Flyway migration

**Files:**
- Create: `src/backendng/src/main/kotlin/com/secman/domain/WorkgroupAwsAccount.kt`
- Create: `src/backendng/src/main/kotlin/com/secman/repository/WorkgroupAwsAccountRepository.kt`
- Create: `src/backendng/src/main/resources/db/migration/V201__add_workgroup_aws_account.sql`

- [ ] **Step 1: Write the Flyway migration**

Create `src/backendng/src/main/resources/db/migration/V201__add_workgroup_aws_account.sql`:

```sql
-- V201__add_workgroup_aws_account.sql
-- New workgroup_aws_account table for workgroup-level AWS account assignment
-- Spec: docs/superpowers/specs/2026-04-28-workgroup-aws-account-assignment-design.md
-- Adds access rule #9 to the Unified Access Control matrix.

CREATE TABLE IF NOT EXISTS workgroup_aws_account (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    workgroup_id    BIGINT       NOT NULL,
    aws_account_id  VARCHAR(12)  NOT NULL,
    created_by_id   BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NULL,
    CONSTRAINT uk_workgroup_aws_account UNIQUE (workgroup_id, aws_account_id),
    CONSTRAINT fk_wg_aws_workgroup
        FOREIGN KEY (workgroup_id) REFERENCES workgroup(id) ON DELETE CASCADE,
    CONSTRAINT fk_wg_aws_created_by
        FOREIGN KEY (created_by_id) REFERENCES users(id),
    INDEX idx_wg_aws_workgroup  (workgroup_id),
    INDEX idx_wg_aws_account_id (aws_account_id)
);
```

- [ ] **Step 2: Write the entity class**

Create `src/backendng/src/main/kotlin/com/secman/domain/WorkgroupAwsAccount.kt`:

```kotlin
package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.Pattern
import java.time.Instant

/**
 * WorkgroupAwsAccount entity — assigns an AWS cloud account ID to a workgroup.
 *
 * Spec: docs/superpowers/specs/2026-04-28-workgroup-aws-account-assignment-design.md
 *
 * Semantics:
 * - Adds access rule #9: a user sees an asset if Asset.cloudAccountId matches
 *   any AWS account assigned to a workgroup the user is a direct member of.
 * - Direct membership only — does not propagate through workgroup hierarchy.
 * - The same awsAccountId may be assigned to multiple workgroups.
 *
 * Business rules:
 * - awsAccountId must be exactly 12 numeric digits (matches UserMapping pattern).
 * - createdBy records the admin who granted access, for audit traceability.
 * - Unique constraint on (workgroup_id, aws_account_id) prevents duplicates.
 */
@Entity
@Table(
    name = "workgroup_aws_account",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_workgroup_aws_account",
            columnNames = ["workgroup_id", "aws_account_id"]
        )
    ],
    indexes = [
        Index(name = "idx_wg_aws_workgroup", columnList = "workgroup_id"),
        Index(name = "idx_wg_aws_account_id", columnList = "aws_account_id")
    ]
)
@Serdeable
data class WorkgroupAwsAccount(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workgroup_id", nullable = false)
    var workgroup: Workgroup,

    @Column(name = "aws_account_id", nullable = false, length = 12)
    @Pattern(regexp = "^\\d{12}$", message = "AWS Account ID must be exactly 12 numeric digits")
    var awsAccountId: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    var createdBy: User,

    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) {
    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorkgroupAwsAccount) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0

    override fun toString(): String =
        "WorkgroupAwsAccount(id=$id, workgroupId=${workgroup.id}, awsAccountId='$awsAccountId')"
}
```

- [ ] **Step 3: Write the repository**

Create `src/backendng/src/main/kotlin/com/secman/repository/WorkgroupAwsAccountRepository.kt`:

```kotlin
package com.secman.repository

import com.secman.domain.WorkgroupAwsAccount
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

@Repository
interface WorkgroupAwsAccountRepository : JpaRepository<WorkgroupAwsAccount, Long> {

    fun findByWorkgroupId(workgroupId: Long): List<WorkgroupAwsAccount>

    fun findByWorkgroupIdAndAwsAccountId(
        workgroupId: Long,
        awsAccountId: String
    ): Optional<WorkgroupAwsAccount>

    fun existsByWorkgroupIdAndAwsAccountId(
        workgroupId: Long,
        awsAccountId: String
    ): Boolean

    fun deleteByWorkgroupId(workgroupId: Long): Long
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :backendng:compileKotlin --quiet`
Expected: silent exit (zero errors).

- [ ] **Step 5: Smoke-test the migration**

Run: `./scriptpp/startbackenddev.sh` (background OK)
Expected: Flyway logs `Migrating schema 'secman' to version "201"` followed by `Successfully applied 1 migration`. Backend starts cleanly.

Verify the table:

```sql
SHOW CREATE TABLE workgroup_aws_account\G
```

Expected: matches the V201 schema (BIGINT id, VARCHAR(12) aws_account_id, FK constraints, unique key, indexes).

- [ ] **Step 6: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/domain/WorkgroupAwsAccount.kt
git add src/backendng/src/main/kotlin/com/secman/repository/WorkgroupAwsAccountRepository.kt
git add src/backendng/src/main/resources/db/migration/V201__add_workgroup_aws_account.sql
git commit -m "feat(backend): add WorkgroupAwsAccount entity, repository, V201 migration"
```

---

### Task 2: Wire Workgroup back-reference

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt`

- [ ] **Step 1: Add `awsAccounts` field to Workgroup**

After the existing `assets` field block (around line 96–100), insert:

```kotlin
    /**
     * AWS accounts assigned to this workgroup (Spec: workgroup-aws-account-assignment).
     * Direct membership grants asset visibility via access rule #9 — assets whose
     * cloudAccountId matches any awsAccountId in this set become visible to all
     * workgroup members. Hibernate cascade-removes child rows when the workgroup
     * is deleted.
     */
    @JsonIgnore
    @OneToMany(
        mappedBy = "workgroup",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    var awsAccounts: MutableSet<WorkgroupAwsAccount> = mutableSetOf(),
```

(Place it directly before the `createdAt` field so it follows the same M:N grouping as `users` and `assets`.)

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :backendng:compileKotlin --quiet`
Expected: zero errors.

- [ ] **Step 3: Smoke-test that Hibernate doesn't recreate the table differently**

Run: `./scriptpp/startbackenddev.sh`
Expected: backend starts; no `ALTER TABLE workgroup_aws_account` log lines (Hibernate sees the schema matches the entity).

Then run:

```sql
SELECT COUNT(*) FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA='secman' AND TABLE_NAME='workgroup_aws_account';
```

Expected: 4 rows (PRIMARY, uk_workgroup_aws_account, idx_wg_aws_workgroup, idx_wg_aws_account_id).

- [ ] **Step 4: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt
git commit -m "feat(backend): wire Workgroup.awsAccounts back-reference"
```

---

### Task 3: Service layer with validation and duplicate prevention

**Files:**
- Create: `src/backendng/src/main/kotlin/com/secman/service/WorkgroupAwsAccountService.kt`

- [ ] **Step 1: Write the service**

Create `src/backendng/src/main/kotlin/com/secman/service/WorkgroupAwsAccountService.kt`:

```kotlin
package com.secman.service

import com.secman.domain.User
import com.secman.domain.WorkgroupAwsAccount
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import com.secman.repository.WorkgroupRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

/**
 * Service for managing AWS account assignments on workgroups.
 * Spec: docs/superpowers/specs/2026-04-28-workgroup-aws-account-assignment-design.md
 *
 * All mutations require ADMIN role — enforced by the controller layer.
 * This service trusts callers to have already authorized the operation.
 */
@Singleton
open class WorkgroupAwsAccountService(
    private val workgroupAwsAccountRepository: WorkgroupAwsAccountRepository,
    private val workgroupRepository: WorkgroupRepository,
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(WorkgroupAwsAccountService::class.java)
    private val accountIdPattern = Regex("^\\d{12}$")

    /**
     * List all AWS accounts assigned to the given workgroup.
     */
    fun list(workgroupId: Long): List<WorkgroupAwsAccount> {
        require(workgroupRepository.findById(workgroupId).isPresent) {
            "Workgroup not found: $workgroupId"
        }
        return workgroupAwsAccountRepository.findByWorkgroupId(workgroupId)
    }

    /**
     * Assign an AWS account to a workgroup. Throws on duplicate or invalid input.
     *
     * @throws IllegalArgumentException if the workgroup or actor user is not found,
     *         or if awsAccountId is not exactly 12 numeric digits.
     * @throws DuplicateAccountException if the (workgroup, account) pair already exists.
     */
    @Transactional
    open fun add(workgroupId: Long, awsAccountId: String, actorUsername: String): WorkgroupAwsAccount {
        require(accountIdPattern.matches(awsAccountId)) {
            "AWS Account ID must be exactly 12 numeric digits (got '$awsAccountId')"
        }

        val workgroup = workgroupRepository.findById(workgroupId).orElseThrow {
            IllegalArgumentException("Workgroup not found: $workgroupId")
        }
        val actor: User = userRepository.findByUsername(actorUsername).orElseThrow {
            IllegalArgumentException("Actor user not found: $actorUsername")
        }

        if (workgroupAwsAccountRepository.existsByWorkgroupIdAndAwsAccountId(workgroupId, awsAccountId)) {
            throw DuplicateAccountException(
                "AWS account $awsAccountId is already assigned to workgroup $workgroupId"
            )
        }

        val entity = WorkgroupAwsAccount(
            workgroup = workgroup,
            awsAccountId = awsAccountId,
            createdBy = actor
        )
        val saved = workgroupAwsAccountRepository.save(entity)
        logger.info(
            "Assigned AWS account {} to workgroup {} (actor={}, id={})",
            awsAccountId, workgroupId, actorUsername, saved.id
        )
        return saved
    }

    /**
     * Remove an AWS account assignment from a workgroup. No-op if not present.
     *
     * @return true if a row was deleted, false otherwise.
     */
    @Transactional
    open fun remove(workgroupId: Long, awsAccountId: String): Boolean {
        val existing = workgroupAwsAccountRepository
            .findByWorkgroupIdAndAwsAccountId(workgroupId, awsAccountId)
        return if (existing.isPresent) {
            workgroupAwsAccountRepository.delete(existing.get())
            logger.info("Removed AWS account {} from workgroup {}", awsAccountId, workgroupId)
            true
        } else {
            false
        }
    }
}

/**
 * Thrown when attempting to assign an AWS account that's already on the workgroup.
 * Mapped to HTTP 409 by the controller.
 */
class DuplicateAccountException(message: String) : RuntimeException(message)
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :backendng:compileKotlin --quiet`
Expected: zero errors.

- [ ] **Step 3: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/service/WorkgroupAwsAccountService.kt
git commit -m "feat(backend): add WorkgroupAwsAccountService with validation and audit"
```

---

### Task 4: DTO for wire-format response

**Files:**
- Create: `src/backendng/src/main/kotlin/com/secman/dto/WorkgroupAwsAccountDto.kt`

- [ ] **Step 1: Write the DTO + mapper**

Create `src/backendng/src/main/kotlin/com/secman/dto/WorkgroupAwsAccountDto.kt`:

```kotlin
package com.secman.dto

import com.secman.domain.WorkgroupAwsAccount
import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

/**
 * Wire-format DTO for WorkgroupAwsAccount.
 *
 * Avoids serialization cycles caused by the entity's @ManyToOne references
 * to Workgroup and User. Only IDs and the account string are exposed; the
 * createdBy-username string is included for UI display ("granted by X").
 */
@Serdeable
data class WorkgroupAwsAccountDto(
    val id: Long,
    val workgroupId: Long,
    val awsAccountId: String,
    val createdByUsername: String,
    val createdAt: Instant?,
    val updatedAt: Instant?
) {
    companion object {
        fun from(entity: WorkgroupAwsAccount): WorkgroupAwsAccountDto {
            return WorkgroupAwsAccountDto(
                id = entity.id ?: error("Entity must be persisted before mapping to DTO"),
                workgroupId = entity.workgroup.id ?: error("Workgroup must be persisted"),
                awsAccountId = entity.awsAccountId,
                createdByUsername = entity.createdBy.username,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt
            )
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :backendng:compileKotlin --quiet`
Expected: zero errors.

- [ ] **Step 3: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/dto/WorkgroupAwsAccountDto.kt
git commit -m "feat(backend): add WorkgroupAwsAccountDto for wire-format response"
```

---

### Task 5: REST controller

**Files:**
- Create: `src/backendng/src/main/kotlin/com/secman/controller/WorkgroupAwsAccountController.kt`

- [ ] **Step 1: Write the controller**

Create `src/backendng/src/main/kotlin/com/secman/controller/WorkgroupAwsAccountController.kt`:

```kotlin
package com.secman.controller

import com.secman.dto.WorkgroupAwsAccountDto
import com.secman.service.DuplicateAccountException
import com.secman.service.WorkgroupAwsAccountService
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.slf4j.LoggerFactory

/**
 * REST controller for AWS account assignments on workgroups.
 * Spec: docs/superpowers/specs/2026-04-28-workgroup-aws-account-assignment-design.md
 *
 * Endpoints:
 * - GET    /api/workgroups/{id}/aws-accounts                 — list (authenticated; access enforced by service+filter)
 * - POST   /api/workgroups/{id}/aws-accounts                 — add (ADMIN)
 * - DELETE /api/workgroups/{id}/aws-accounts/{awsAccountId}  — remove (ADMIN)
 */
@Controller("/api/workgroups/{workgroupId}/aws-accounts")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class WorkgroupAwsAccountController(
    private val service: WorkgroupAwsAccountService
) {
    private val logger = LoggerFactory.getLogger(WorkgroupAwsAccountController::class.java)

    @Get(produces = [MediaType.APPLICATION_JSON])
    open fun list(@PathVariable workgroupId: Long): HttpResponse<List<WorkgroupAwsAccountDto>> {
        return try {
            val rows = service.list(workgroupId).map(WorkgroupAwsAccountDto::from)
            HttpResponse.ok(rows)
        } catch (e: IllegalArgumentException) {
            logger.warn("List workgroup AWS accounts failed: {}", e.message)
            HttpResponse.notFound()
        }
    }

    @Post(consumes = [MediaType.APPLICATION_JSON], produces = [MediaType.APPLICATION_JSON])
    @Secured("ADMIN")
    open fun add(
        @PathVariable workgroupId: Long,
        @Body @Valid request: AddAwsAccountRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        return try {
            val saved = service.add(workgroupId, request.awsAccountId, authentication.name)
            HttpResponse.created(WorkgroupAwsAccountDto.from(saved))
        } catch (e: DuplicateAccountException) {
            HttpResponse.status<Map<String, String>>(io.micronaut.http.HttpStatus.CONFLICT)
                .body(mapOf("error" to (e.message ?: "duplicate")))
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "invalid request")))
        }
    }

    @Delete("/{awsAccountId}")
    @Secured("ADMIN")
    open fun remove(
        @PathVariable workgroupId: Long,
        @PathVariable awsAccountId: String
    ): HttpResponse<Void> {
        val deleted = service.remove(workgroupId, awsAccountId)
        return if (deleted) HttpResponse.noContent() else HttpResponse.notFound()
    }
}

@Serdeable
data class AddAwsAccountRequest(
    @field:Pattern(regexp = "^\\d{12}$", message = "AWS Account ID must be exactly 12 numeric digits")
    val awsAccountId: String
)
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :backendng:compileKotlin --quiet`
Expected: zero errors.

- [ ] **Step 3: Smoke-test the HTTP surface**

Start the backend (`./scriptpp/startbackenddev.sh`), then with a fresh ADMIN JWT:

```bash
# Replace WG_ID with an existing workgroup id and TOKEN with a valid admin JWT.
WG_ID=1
TOKEN="..."

# 1. Add account — expect 201
curl -i -X POST "http://localhost:8080/api/workgroups/${WG_ID}/aws-accounts" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"awsAccountId":"123456789012"}'

# 2. Add same account again — expect 409
curl -i -X POST "http://localhost:8080/api/workgroups/${WG_ID}/aws-accounts" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"awsAccountId":"123456789012"}'

# 3. Add invalid account — expect 400
curl -i -X POST "http://localhost:8080/api/workgroups/${WG_ID}/aws-accounts" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"awsAccountId":"too-short"}'

# 4. List — expect 200 with one row
curl -s "http://localhost:8080/api/workgroups/${WG_ID}/aws-accounts" \
  -H "Authorization: Bearer ${TOKEN}" | jq

# 5. Remove — expect 204
curl -i -X DELETE "http://localhost:8080/api/workgroups/${WG_ID}/aws-accounts/123456789012" \
  -H "Authorization: Bearer ${TOKEN}"
```

- [ ] **Step 4: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/controller/WorkgroupAwsAccountController.kt
git commit -m "feat(backend): add REST controller for workgroup AWS accounts"
```

---

### Task 6: Integrate access rule #9 into AssetFilterService

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/service/AssetFilterService.kt`

- [ ] **Step 1: Add the fifth source list to `findFilteredAssets`**

Open `AssetFilterService.kt` and locate the regular-user/VULN filtering block (around line 107–160 per the earlier grep). It currently builds four source lists and combines them as:

```kotlin
return (workgroupAssets + awsAccountAssets + domainAssets + sharedAwsAccountAssets + ownerAssets).distinct()
```

Insert the new computation immediately before the `return`:

```kotlin
        // Access rule #9: assets whose cloudAccountId matches an AWS account
        // assigned to a workgroup the user belongs to (direct membership only).
        val workgroupAccountIds: Set<String> = currentUser.workgroups
            .flatMap { wg -> wg.awsAccounts }
            .map { it.awsAccountId }
            .toSet()
        val workgroupAccountAssets = if (workgroupAccountIds.isNotEmpty()) {
            assetRepository.findByCloudAccountIdIn(workgroupAccountIds)
        } else {
            emptyList()
        }
```

Then update the return to include the new list:

```kotlin
        return (workgroupAssets + awsAccountAssets + domainAssets + sharedAwsAccountAssets +
                workgroupAccountAssets + ownerAssets).distinct()
```

- [ ] **Step 2: Verify `findByCloudAccountIdIn` exists on AssetRepository**

Run: `grep -n "findByCloudAccountIdIn" src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt`

If a derived query exists, proceed. If not, add the following method to `AssetRepository`:

```kotlin
    fun findByCloudAccountIdIn(cloudAccountIds: Collection<String>): List<Asset>
```

- [ ] **Step 3: Verify Workgroup.awsAccounts is reachable on `currentUser.workgroups`**

The expression `currentUser.workgroups.flatMap { wg -> wg.awsAccounts }` requires a transactional context (LAZY collections). The existing method that calls `findFilteredAssets` is already inside `@Transactional` boundaries via service annotations; verify by running:

`grep -B 3 "fun findFilteredAssets" src/backendng/src/main/kotlin/com/secman/service/AssetFilterService.kt`

Expected: surrounding scope shows `@Transactional` or the calling controller wraps in transaction. If neither, prefer fetching account IDs via repository:

```kotlin
val workgroupAccountIds: Set<String> = workgroupAwsAccountRepository
    .findByWorkgroupIdInAccountIds(currentUser.workgroups.mapNotNull { it.id })
    .toSet()
```

(adding the corresponding repository method that returns just the awsAccountId column).

For this task, default to the in-memory `flatMap` if the surrounding context is transactional; else, switch to the repository path. Document the chosen approach in a one-line comment.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :backendng:compileKotlin --quiet`
Expected: zero errors.

- [ ] **Step 5: Smoke-test rule #9 end-to-end**

After backend restart, with database access:

```sql
-- Pick a non-admin user, give them a workgroup, give the workgroup an AWS account,
-- give an asset a matching cloudAccountId, then log in as the user and call /api/assets.
INSERT INTO workgroup_aws_account (workgroup_id, aws_account_id, created_by_id, created_at)
  VALUES (1, '999999999999', 1, NOW());
UPDATE asset SET cloud_account_id = '999999999999' WHERE id = 100;
```

Then with a non-admin JWT for a user who is in workgroup 1:

```bash
curl -s "http://localhost:8080/api/assets" \
  -H "Authorization: Bearer ${TOKEN}" | jq '.[] | select(.id==100)'
```

Expected: asset 100 is returned (rule #9 matched).

Cleanup:

```sql
DELETE FROM workgroup_aws_account WHERE aws_account_id = '999999999999';
UPDATE asset SET cloud_account_id = NULL WHERE id = 100;
```

- [ ] **Step 6: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/service/AssetFilterService.kt
# include AssetRepository.kt only if Step 2 required adding the method
git diff --cached --name-only
git commit -m "feat(backend): add access rule #9 — workgroup-AWS-account asset visibility"
```

---

### Task 7: Workgroup delete cascade — clear workgroup_aws_account rows

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt`

The `ON DELETE CASCADE` foreign key in V201 already handles row removal at the DB level, but the in-memory `Workgroup.awsAccounts` collection should be cleared in the same place where `users` and `assets` are detached, so that JPA stays in sync.

- [ ] **Step 1: Extend `clearWorkgroupMemberships`**

Open `WorkgroupService.kt`, locate `clearWorkgroupMemberships` (added in the previous commit), and append the following block at the end of the function body, after the asset-clearing loop:

```kotlin
        // Workgroup AWS accounts are owned-side ManyToOne pointing at workgroup,
        // and the FK is ON DELETE CASCADE — but we still flush the orphans here
        // for consistency with users/assets and to avoid stale JPA cache state.
        workgroupAwsAccountRepository.deleteByWorkgroupId(workgroupId)
```

Add the constructor injection:

```kotlin
    private val workgroupAwsAccountRepository: WorkgroupAwsAccountRepository,
```

(Place it after the other repository params.)

- [ ] **Step 2: Add the import**

Add to the imports section:

```kotlin
import com.secman.repository.WorkgroupAwsAccountRepository
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :backendng:compileKotlin --quiet`
Expected: zero errors.

- [ ] **Step 4: Smoke-test workgroup deletion**

After backend restart:

```sql
-- Insert a probe row.
INSERT INTO workgroup (name, criticality) VALUES ('TEMP-DELETE-PROBE', 'MEDIUM');
SET @wgid = LAST_INSERT_ID();
INSERT INTO workgroup_aws_account (workgroup_id, aws_account_id, created_by_id, created_at)
  VALUES (@wgid, '111111111111', 1, NOW());
SELECT @wgid AS probe_workgroup_id;
```

Delete via REST:

```bash
curl -i -X DELETE "http://localhost:8080/api/workgroups/${PROBE_ID}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

Expected: 204. Then:

```sql
SELECT COUNT(*) FROM workgroup_aws_account WHERE workgroup_id = @wgid;
SELECT COUNT(*) FROM workgroup WHERE id = @wgid;
```

Expected: both 0.

- [ ] **Step 5: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt
git commit -m "feat(backend): clear workgroup_aws_account on workgroup delete"
```

---

### Task 8: MCP tools

**Files:**
- Create: `src/backendng/src/main/kotlin/com/secman/mcp/tools/WorkgroupAwsAccountTools.kt`

**Note:** before writing this task's code, locate the MCP tool registration mechanism in this project. From the spec context (CLAUDE.md mentions tools like `list_aws_account_sharing`), there should be an existing tools class for AWS account sharing. Match its structure.

- [ ] **Step 1: Locate the MCP tool registration pattern**

Run: `grep -rln 'list_aws_account_sharing\|@McpTool\|McpToolRegistry' src/backendng/src/main/kotlin/`
Expected: a tools file emerges (e.g., `mcp/tools/AwsAccountSharingTools.kt`). Open and inspect its structure — note the annotation/registration pattern, parameter binding, and how it returns results.

- [ ] **Step 2: Implement three parallel tools**

Create `src/backendng/src/main/kotlin/com/secman/mcp/tools/WorkgroupAwsAccountTools.kt` mirroring the structure of `AwsAccountSharingTools.kt`:

- `list_workgroup_aws_accounts(workgroupId: Long): List<WorkgroupAwsAccountDto>` — ADMIN + User Delegation
- `add_workgroup_aws_account(workgroupId: Long, awsAccountId: String): WorkgroupAwsAccountDto` — ADMIN + User Delegation
- `remove_workgroup_aws_account(workgroupId: Long, awsAccountId: String): {deleted: Boolean}` — ADMIN + User Delegation

All three delegate to `WorkgroupAwsAccountService`. Validation errors and duplicate exceptions map to MCP-spec error envelopes per the existing pattern in `AwsAccountSharingTools.kt`.

(Exact code intentionally omitted here because it depends on the project-specific MCP tool annotation/registration pattern observed in step 1; the implementation is mechanical once the pattern is known.)

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :backendng:compileKotlin --quiet`
Expected: zero errors.

- [ ] **Step 4: Smoke-test via MCP client**

Using an MCP client (or `tools/list` over HTTP):

```bash
# Replace TOKEN with a valid ADMIN JWT and EMAIL with the user-delegation header.
curl -s -X POST "http://localhost:8080/mcp/tools/call" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-MCP-User-Email: ${EMAIL}" \
  -H "Content-Type: application/json" \
  -d '{"name":"add_workgroup_aws_account","arguments":{"workgroupId":1,"awsAccountId":"222222222222"}}'
```

Expected: success response containing `WorkgroupAwsAccountDto`. Repeat with an invalid 11-digit ID — expect validation error.

- [ ] **Step 5: Commit**

```bash
git add src/backendng/src/main/kotlin/com/secman/mcp/tools/WorkgroupAwsAccountTools.kt
git commit -m "feat(mcp): add list/add/remove tools for workgroup AWS accounts"
```

---

### Task 9: Frontend — `WorkgroupAccountsModal` component

**Files:**
- Create: `src/frontend/src/components/WorkgroupAccountsModal.tsx`

- [ ] **Step 1: Locate the existing modal pattern**

Run: `ls src/frontend/src/components/ | grep -iE "Workgroup(Users|Assets)Modal"`

If found, open it and copy its structure. If neither exists, examine `WorkgroupManagement.tsx` for inline modal logic and adapt.

- [ ] **Step 2: Write the modal component**

Create `src/frontend/src/components/WorkgroupAccountsModal.tsx`:

```tsx
import { useEffect, useState } from 'react';
import axios from 'axios';

interface WorkgroupAwsAccountDto {
  id: number;
  workgroupId: number;
  awsAccountId: string;
  createdByUsername: string;
  createdAt: string | null;
  updatedAt: string | null;
}

interface Props {
  workgroupId: number;
  workgroupName: string;
  isOpen: boolean;
  onClose: () => void;
  onChange?: () => void;  // notify parent so it can refresh counts
}

export default function WorkgroupAccountsModal({
  workgroupId,
  workgroupName,
  isOpen,
  onClose,
  onChange,
}: Props) {
  const [accounts, setAccounts] = useState<WorkgroupAwsAccountDto[]>([]);
  const [newAccountId, setNewAccountId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const fetchAccounts = async () => {
    setLoading(true);
    setError(null);
    try {
      const token = localStorage.getItem('authToken');
      const res = await axios.get<WorkgroupAwsAccountDto[]>(
        `/api/workgroups/${workgroupId}/aws-accounts`,
        { headers: { Authorization: `Bearer ${token}` } }
      );
      setAccounts(res.data);
    } catch (e: any) {
      setError(e.response?.data?.error ?? 'Failed to load accounts');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (isOpen) {
      fetchAccounts();
      setNewAccountId('');
      setError(null);
    }
  }, [isOpen, workgroupId]);

  const handleAdd = async () => {
    setError(null);
    if (!/^\d{12}$/.test(newAccountId)) {
      setError('AWS Account ID must be exactly 12 numeric digits');
      return;
    }
    try {
      const token = localStorage.getItem('authToken');
      await axios.post(
        `/api/workgroups/${workgroupId}/aws-accounts`,
        { awsAccountId: newAccountId },
        { headers: { Authorization: `Bearer ${token}` } }
      );
      setNewAccountId('');
      await fetchAccounts();
      onChange?.();
    } catch (e: any) {
      const status = e.response?.status;
      if (status === 409) setError('That account is already assigned to this workgroup');
      else if (status === 400) setError(e.response?.data?.error ?? 'Invalid account ID');
      else setError('Failed to add account');
    }
  };

  const handleRemove = async (awsAccountId: string) => {
    setError(null);
    try {
      const token = localStorage.getItem('authToken');
      await axios.delete(
        `/api/workgroups/${workgroupId}/aws-accounts/${awsAccountId}`,
        { headers: { Authorization: `Bearer ${token}` } }
      );
      await fetchAccounts();
      onChange?.();
    } catch (e: any) {
      setError('Failed to remove account');
    }
  };

  if (!isOpen) return null;

  return (
    <div
      className="modal show d-block"
      tabIndex={-1}
      style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}
    >
      <div className="modal-dialog modal-lg">
        <div className="modal-content">
          <div className="modal-header">
            <h5 className="modal-title">
              AWS Accounts — {workgroupName}
            </h5>
            <button type="button" className="btn-close" onClick={onClose} />
          </div>
          <div className="modal-body">
            {error && <div className="alert alert-danger">{error}</div>}

            <div className="input-group mb-3">
              <input
                type="text"
                className="form-control"
                placeholder="12-digit AWS account ID"
                value={newAccountId}
                onChange={(e) => setNewAccountId(e.target.value.trim())}
                pattern="\d{12}"
                maxLength={12}
              />
              <button
                type="button"
                className="btn btn-primary"
                onClick={handleAdd}
                disabled={!/^\d{12}$/.test(newAccountId)}
              >
                Add
              </button>
            </div>

            {loading ? (
              <div>Loading…</div>
            ) : accounts.length === 0 ? (
              <div className="text-muted">No AWS accounts assigned.</div>
            ) : (
              <table className="table table-sm">
                <thead>
                  <tr>
                    <th>AWS Account ID</th>
                    <th>Granted By</th>
                    <th>Granted At</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {accounts.map((a) => (
                    <tr key={a.id}>
                      <td><code>{a.awsAccountId}</code></td>
                      <td>{a.createdByUsername}</td>
                      <td>{a.createdAt ?? '—'}</td>
                      <td className="text-end">
                        <button
                          type="button"
                          className="btn btn-sm btn-outline-danger"
                          onClick={() => handleRemove(a.awsAccountId)}
                        >
                          Remove
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
          <div className="modal-footer">
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Close
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Verify build**

Run: `cd src/frontend && npm run build`
Expected: build succeeds; no TypeScript errors.

- [ ] **Step 4: Commit**

```bash
git add src/frontend/src/components/WorkgroupAccountsModal.tsx
git commit -m "feat(frontend): add WorkgroupAccountsModal component"
```

---

### Task 10: Frontend — wire modal into Workgroup Management table

**Files:**
- Modify: `src/frontend/src/components/WorkgroupManagement.tsx`

- [ ] **Step 1: Read the current table column structure**

Run: `head -120 src/frontend/src/components/WorkgroupManagement.tsx`

Find: (a) the `<thead>` with column headers, (b) the row body with Edit/Users/Assets/Delete buttons, (c) any existing `Modal` state hooks.

- [ ] **Step 2: Add state + import**

In the component file, near the top of the function body, add:

```tsx
import WorkgroupAccountsModal from './WorkgroupAccountsModal';
// ...
const [accountsModalState, setAccountsModalState] = useState<{
  isOpen: boolean;
  workgroupId: number | null;
  workgroupName: string;
}>({ isOpen: false, workgroupId: null, workgroupName: '' });
```

- [ ] **Step 3: Add the count column to the header**

In the `<thead>`, after the existing **Assets** `<th>`, insert:

```tsx
<th>Accounts</th>
```

- [ ] **Step 4: Add the count cell to each row**

In the row body, after the existing Assets-count `<td>`, insert (assuming each row's data shape exposes `awsAccountsCount`):

```tsx
<td>
  <span className="badge bg-secondary">{wg.awsAccountsCount ?? 0}</span>
</td>
```

The `awsAccountsCount` field needs to be supplied by the backend `GET /api/workgroups` listing endpoint. If it doesn't already include it, **add an additional sub-step** to extend `WorkgroupResponse` (a DTO in `src/backendng/src/main/kotlin/com/secman/dto/WorkgroupResponse.kt`) to include `awsAccountsCount: Long` populated from `workgroup.awsAccounts.size`. If the listing endpoint is read-only and doesn't load awsAccounts (LAZY), use a repository count query instead:

```kotlin
val awsAccountsCount = workgroupAwsAccountRepository.countByWorkgroupId(wg.id!!)
```

(Add `fun countByWorkgroupId(workgroupId: Long): Long` to the repository.)

- [ ] **Step 5: Add the Accounts action button**

In the Actions cell, between the existing Users and Assets buttons, insert:

```tsx
<button
  type="button"
  className="btn btn-sm btn-info ms-1"
  onClick={() =>
    setAccountsModalState({
      isOpen: true,
      workgroupId: wg.id,
      workgroupName: wg.name,
    })
  }
>
  Accounts
</button>
```

- [ ] **Step 6: Render the modal**

At the bottom of the JSX (just before the component's closing `</>` or root element), render:

```tsx
{accountsModalState.workgroupId !== null && (
  <WorkgroupAccountsModal
    workgroupId={accountsModalState.workgroupId}
    workgroupName={accountsModalState.workgroupName}
    isOpen={accountsModalState.isOpen}
    onClose={() =>
      setAccountsModalState({ isOpen: false, workgroupId: null, workgroupName: '' })
    }
    onChange={() => {
      // refresh the workgroup list so the count column updates
      fetchWorkgroups();
    }}
  />
)}
```

(Use whatever the existing list-fetcher in this component is named — the grep in step 1 should reveal it.)

- [ ] **Step 7: Verify build**

Run: `cd src/frontend && npm run build`
Expected: success.

- [ ] **Step 8: Manual UI smoke test**

Start the frontend dev server (`cd src/frontend && npm run dev`) and navigate to `/workgroups`:
- Confirm the new **Accounts** column appears with a count badge per row.
- Click the **Accounts** button on a row → modal opens.
- Add `123456789012` → row appears in modal table; count in main table increments after modal closes.
- Try adding the same ID again → inline error "already assigned".
- Try `12345` → inline error "must be 12 digits".
- Click Remove on the row → row disappears; count decrements.

- [ ] **Step 9: Commit**

```bash
git add src/frontend/src/components/WorkgroupManagement.tsx
# include any backend DTO/repository changes from Step 4
git diff --cached --name-only
git commit -m "feat(frontend): add Accounts column and modal trigger to Workgroup Management"
```

---

### Task 11: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Locate "Unified Access Control" section**

Run: `grep -n "Unified Access Control" CLAUDE.md`
Note the line number.

- [ ] **Step 2: Append rule #9**

Insert after the existing rule #8 ("Asset's owner matches user's username"):

```markdown
9. Asset's `cloudAccountId` matches an AWS account assigned to a workgroup the user belongs to (via WorkgroupAwsAccount, direct membership only — no hierarchy propagation)
```

- [ ] **Step 3: Add MCP tools to the workgroup section**

Find the existing **Workgroups** API endpoint section. After the existing `POST /api/workgroups/{id}/{users,assets}` line, add:

```markdown
- POST/DELETE/GET /api/workgroups/{id}/aws-accounts (ADMIN)
- MCP tools: `list_workgroup_aws_accounts`, `add_workgroup_aws_account`, `remove_workgroup_aws_account` (ADMIN + User Delegation)
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: add workgroup-AWS-account access rule #9 to CLAUDE.md"
```

---

## Self-Review

**Spec coverage check:**
- §4.1 entity → Task 1 ✓
- §4.2 back-reference → Task 2 ✓
- §4.3 audit semantics → Task 1 (createdBy column) + Task 3 (service captures actor) ✓
- §5 access-control integration → Task 6 ✓
- §6 REST API → Task 5 ✓
- §7 UI changes → Tasks 9, 10 ✓
- §8 MCP tools → Task 8 ✓
- §9 migration → Task 1 ✓
- §10 validation rules → Tasks 3 (service), 5 (controller @Pattern), 9 (frontend) ✓
- §11 testing → **deliberately deferred** per CLAUDE.md principle #5; documented in plan header
- §12 CLAUDE.md update → Task 11 ✓
- §13 rollout → no migration of existing data; pure additive (covered by design, no task needed)
- §14 future considerations → out of scope (covered by design, no task needed)

**Placeholder scan:** Task 8 (MCP tools) intentionally defers exact code to step 2 because the project-specific MCP registration pattern is unknown until step 1's grep — this is acknowledged with a structural breakdown rather than a `TODO`. All other tasks have complete code.

**Type consistency:** `WorkgroupAwsAccount` (entity), `WorkgroupAwsAccountDto` (wire), `WorkgroupAwsAccountService` (service), `WorkgroupAwsAccountRepository` (repo), `WorkgroupAwsAccountController` (REST), `WorkgroupAwsAccountTools` (MCP), `WorkgroupAccountsModal` (frontend) — naming is consistent across the stack. Field names match between entity / DTO / DB column (snake_case in DB, camelCase in Kotlin/TS).

**Cross-task dependencies:**
- Task 2 depends on Task 1 (Workgroup back-reference references the new entity class).
- Task 3 depends on Tasks 1 + 2 (service uses entity + repository).
- Task 4 depends on Task 1 (DTO maps from entity).
- Task 5 depends on Tasks 3 + 4 (controller uses service + DTO).
- Task 6 depends on Task 2 (uses Workgroup.awsAccounts collection).
- Task 7 depends on Task 1 (calls deleteByWorkgroupId on repository).
- Task 8 depends on Tasks 3 + 4 (tools delegate to service, return DTOs).
- Task 9 depends on Tasks 4 + 5 (frontend consumes the DTO via the REST endpoints).
- Task 10 depends on Task 9 (renders the modal).
- Task 11 depends on no code; can run last or any time after the feature is functional.

Tasks must be executed in order 1 → 11 to satisfy dependencies cleanly.

---

## Out-of-scope cross-checks

- **Hierarchy propagation** — explicitly out of scope (spec §3, §14). No task addresses parent/child inheritance.
- **AwsAccountSharing migration** — explicitly out of scope. No data migration task.
- **Test files** — deliberately deferred per CLAUDE.md principle #5. The user may add them in a separate effort.
- **Asset's cloudAccountId picker UX in modal** — flagged as future consideration in spec §14.

End of plan.
