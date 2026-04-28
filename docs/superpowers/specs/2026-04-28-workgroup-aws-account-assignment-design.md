# Workgroup AWS Account Assignment — Design Spec

**Date:** 2026-04-28
**Status:** Approved (brainstorming phase)
**Scope:** Backend (Kotlin/Micronaut), Frontend (Astro/React), Database (Flyway), MCP tools

## 1. Goal

Add AWS cloud accounts as a third direct-membership resource on `Workgroup`, alongside users and assets. Assigning AWS account `123456789012` to a workgroup grants every workgroup member visibility to all assets whose `cloudAccountId` matches that account. This becomes **rule #9** in the existing 8-rule access matrix — strictly additive, fully backward-compatible.

## 2. Motivation

Today, AWS-account-based asset visibility is granted per-user via `UserMapping.awsAccountId` (rule 5) or admin-mediated user-to-user `AwsAccountSharing` (rule 7). Both scale poorly for teams: each new team member needs their own UserMapping rows, and `AwsAccountSharing` is directional and non-transitive, requiring O(team_size × accounts) admin actions to grant a team broad account access. Workgroup-level account assignment is an O(accounts) operation regardless of team size.

## 3. Non-Goals

- **Hierarchy propagation** — workgroup parent/child does not propagate account access (consistent with users/assets today). Future feature, would touch all three axes.
- **Migrating existing `AwsAccountSharing` data** — `AwsAccountSharing` keeps working unchanged. Cleanup is a separate decision.
- **AD domains on workgroups** — explicitly scoped to AWS only this iteration.
- **AWS account ID validation against AWS API** — same `^\d{12}$` pattern as `UserMapping`. No round-trip.

## 4. Architecture

### 4.1 New Entity

```kotlin
@Entity
@Table(
  name = "workgroup_aws_account",
  uniqueConstraints = [UniqueConstraint(
    name = "uk_workgroup_aws_account",
    columnNames = ["workgroup_id", "aws_account_id"]
  )],
  indexes = [
    Index(name = "idx_wg_aws_workgroup", columnList = "workgroup_id"),
    Index(name = "idx_wg_aws_account_id", columnList = "aws_account_id")
  ]
)
@Serdeable
data class WorkgroupAwsAccount(
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
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
)
```

### 4.2 Workgroup back-reference

Add to `Workgroup`:

```kotlin
@JsonIgnore
@OneToMany(mappedBy = "workgroup", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
var awsAccounts: MutableSet<WorkgroupAwsAccount> = mutableSetOf()
```

### 4.3 Audit semantics

`createdBy` records the admin who granted the account. Mirrors `AwsAccountSharing.createdBy`. Useful for compliance review of "how did this user gain access to this asset."

## 5. Access-control integration

In `AssetFilterService.findFilteredAssets()`, append a fifth source list. The existing `(workgroupAssets + awsAccountAssets + domainAssets + sharedAwsAccountAssets + ownerAssets).distinct()` becomes:

```kotlin
val workgroupAccountIds: Set<String> = currentUser.workgroups
    .flatMap { wg -> wg.awsAccounts }
    .map { it.awsAccountId }
    .toSet()

val workgroupAccountAssets =
    if (workgroupAccountIds.isNotEmpty())
        assetRepository.findByCloudAccountIdIn(workgroupAccountIds)
    else emptyList()

return (workgroupAssets + awsAccountAssets + domainAssets +
        sharedAwsAccountAssets + workgroupAccountAssets +
        ownerAssets).distinct()
```

Reuses existing `AssetRepository.findByCloudAccountIdIn`. No new SQL.

`AssetFilterService.findFilteredScans()` and any vulnerability-filter equivalent need the same fifth-source treatment — to be enumerated during planning.

## 6. REST API

| Method | Path | Auth | Body | Response |
|--------|------|------|------|----------|
| `POST` | `/api/workgroups/{id}/aws-accounts` | ADMIN | `{"awsAccountId":"123456789012"}` | `201 WorkgroupAwsAccount` |
| `DELETE` | `/api/workgroups/{workgroupId}/aws-accounts/{awsAccountId}` | ADMIN | — | `204` |
| `GET` | `/api/workgroups/{id}/aws-accounts` | ADMIN or workgroup member | — | `200 List<WorkgroupAwsAccount>` |

`POST` returns 409 on duplicate (workgroup_id, aws_account_id). `DELETE` returns 404 if not found. `POST` returns 400 on `awsAccountId` not matching `^\d{12}$`.

## 7. UI changes

### 7.1 Workgroup Management table (`/workgroups`)

- New **Accounts** count column between **Assets** and **Created**.
- New **Accounts** button in the **Actions** column, between **Users** and **Assets** buttons. Opens `WorkgroupAccountsModal`.

### 7.2 New `WorkgroupAccountsModal.tsx`

Modeled directly on the existing `WorkgroupAssetsModal` / `WorkgroupUsersModal` patterns:
- Lists current `WorkgroupAwsAccount` rows for the selected workgroup with delete-row buttons
- Single text input for adding a new account ID, validated client-side against `^\d{12}$`
- Submit → `POST /api/workgroups/{id}/aws-accounts`
- Inline error messages for 400 (validation) and 409 (duplicate)

### 7.3 Tree-view consistency

The `WorkgroupTree.tsx` view does not need accounts UI in this iteration — accounts management lives only in the table view. (Tree view shows hierarchy; accounts are managed per-workgroup.)

## 8. MCP tools

Three new MCP tools, paralleling the existing `*_workgroup_users` / `*_workgroup_assets` patterns:

| Tool | Roles | Notes |
|------|-------|-------|
| `list_workgroup_aws_accounts` | ADMIN + User Delegation | Returns full list for a given workgroup |
| `add_workgroup_aws_account` | ADMIN + User Delegation | Body: `{workgroupId, awsAccountId}` |
| `remove_workgroup_aws_account` | ADMIN + User Delegation | Body: `{workgroupId, awsAccountId}` |

Validation and error handling mirror REST.

## 9. Database migration

`V201__add_workgroup_aws_account.sql`:

```sql
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

Hibernate auto-update would also create this from the entity, but explicit Flyway is the project policy after the V196–V200 saga (Hibernate auto-update has caused trouble; explicit migrations are the safer path).

## 10. Validation rules

- `awsAccountId` must match `^\d{12}$` — same as `UserMapping.awsAccountId`. Enforced at `@Pattern`, service-layer, and UI.
- ADMIN role required for all mutations. `GET` allowed for workgroup members.
- Cannot insert duplicate `(workgroup_id, aws_account_id)` — DB unique constraint + service-layer pre-check for clean 409.
- Same AWS account ID **may** appear in multiple workgroups (no global uniqueness).
- Same AWS account ID may also appear in `UserMapping` and `AwsAccountSharing` — no cross-table conflict (additive access).

## 11. Testing

### Unit tests
- `WorkgroupAwsAccountServiceTest`: create / list / delete / duplicate-rejection / non-12-digit rejection
- `AssetFilterServiceTest`: assert `findFilteredAssets` returns assets reachable via workgroup-account, and that the fifth-source list combines correctly with the existing four

### Integration tests
- `WorkgroupAwsAccountControllerIntegrationTest`: full HTTP round-trip, ADMIN role enforcement, 409 on duplicate, 404 on missing
- End-to-end access verification: create workgroup → add user as member → add AWS account → create asset with matching `cloudAccountId` → verify user's `GET /api/assets` includes the asset

### E2E (Playwright)
- Admin opens Workgroup Management → clicks Accounts button → adds 12-digit account → confirms count increments → non-admin member logs in → confirms asset visible in Asset Overview

## 12. CLAUDE.md update

In the Unified Access Control section, append rule #9:

> 9. Asset's cloudAccountId matches an AWS account assigned to a workgroup the user belongs to (via WorkgroupAwsAccount, direct membership only — no hierarchy propagation)

## 13. Rollout

This is purely additive — existing access continues to work, no data migration required, no breaking API changes. Rollout is a normal deploy.

## 14. Future considerations (out of scope this iteration)

- **Hierarchy propagation**: a separate feature affecting users, assets, AND accounts uniformly. Whichever decision is made (inherit from parent vs. flat-only) should apply to all three.
- **AwsAccountSharing deprecation**: option B from brainstorming. Could be a follow-up that flips new admin tooling to favor workgroup-account, with `AwsAccountSharing` becoming legacy/read-only.
- **AD domain assignment to workgroups**: same pattern, different column (`Asset.adDomain`). Trivially adaptable from this design.
- **Asset-level cloud account picker** in the workgroup-account add UI (autocomplete from existing `Asset.cloudAccountId` distinct values) instead of free-form input. Nice UX polish, not required for v1.
