# Feature Specification: Fix CrowdStrike Instance ID Update

**Feature Branch**: `082-fix-cs-instance-update`
**Created**: 2026-03-12
**Status**: Draft
**Input**: User description: "Please implement a fix for the import logic from Crowdstrike. There are cases, where Account ID and Hostname stay the same, but the Instance ID is changed. Currently the old instance ID is used and the new one is ignored. Wanted behavior for the import is, whenever for a Account/Hostname combination a new Instance ID is identified, the instance ID will be replaced in secman."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Instance ID is updated when EC2 instance is replaced (Priority: P1)

As a security analyst, when CrowdStrike reports a new Instance ID for a server that already exists in secman (same hostname + same AWS Account ID), I expect secman to update the stored Instance ID to the new value so that the asset record reflects the current cloud infrastructure state.

**Why this priority**: This is the core bug fix. Stale Instance IDs cause confusion when correlating secman data with the actual AWS Console, making it difficult to identify which EC2 instance a vulnerability belongs to.

**Independent Test**: Import vulnerabilities for a known server (e.g., EC2AMAZ-X) with Account ID 000 and Instance ID `i-0affe`. Verify the asset record in secman shows the new Instance ID instead of the old one (`i-0487`).

**Acceptance Scenarios**:

1. **Given** an asset "EC2AMAZ-X" exists with cloudAccountId "000" and cloudInstanceId "i-OLD", **When** CrowdStrike import runs with the same hostname and account ID but cloudInstanceId "i-NEW", **Then** the asset's cloudInstanceId is updated to "i-NEW"
2. **Given** an asset exists with a cloudInstanceId, **When** CrowdStrike import provides the same cloudInstanceId, **Then** no unnecessary update is performed (no-op for unchanged values)
3. **Given** an asset exists with a cloudInstanceId, **When** CrowdStrike import provides a null or blank cloudInstanceId, **Then** the existing cloudInstanceId is preserved (not overwritten with null)

---

### User Story 2 - No duplicate assets created for replaced instances (Priority: P1)

As a security analyst, when an EC2 instance is replaced (same hostname, same account, new instance ID), I expect secman to update the existing asset rather than creating a duplicate entry, so that vulnerability history remains consolidated under one asset record.

**Why this priority**: Duplicate assets inflate vulnerability counts and make it impossible to get an accurate security posture view per server.

**Independent Test**: Verify that after import, there is only one asset record for a given hostname + account ID combination, not two records with different instance IDs.

**Acceptance Scenarios**:

1. **Given** asset "SERVER-A" exists with cloudAccountId "123" and cloudInstanceId "i-OLD", **When** CrowdStrike import provides hostname "SERVER-A" with cloudAccountId "123" and cloudInstanceId "i-NEW", **Then** only one asset record exists for "SERVER-A" with cloudInstanceId "i-NEW"
2. **Given** two different servers share the same hostname but have different cloudAccountIds, **When** CrowdStrike import runs, **Then** both assets are maintained separately (different cloud accounts = different logical assets)

---

### User Story 3 - Import logging reflects instance ID changes (Priority: P2)

As an administrator reviewing import logs, I want to see when an Instance ID changes for a server so I can track infrastructure changes over time.

**Why this priority**: Observability of instance ID changes aids in auditing and troubleshooting.

**Independent Test**: Run an import with a changed Instance ID and verify the log output includes the old and new values.

**Acceptance Scenarios**:

1. **Given** an asset with a stale Instance ID, **When** CrowdStrike import updates the Instance ID, **Then** an info-level log entry records the change with old and new values

---

### Edge Cases

- What happens when the same hostname exists across multiple AWS accounts (different cloudAccountId values)? Each combination should be treated as a separate asset.
- What happens when CrowdStrike reports a hostname with no instance ID? The existing instance ID should be preserved.
- What happens when the very first import for a new hostname includes an instance ID? It should be stored normally on the new asset.
- What happens during concurrent imports for the same hostname? The pessimistic lock already in place should prevent race conditions.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST update the cloudInstanceId of an existing asset when a CrowdStrike import provides a different, non-blank cloudInstanceId for the same hostname
- **FR-002**: System MUST NOT create duplicate asset records when only the cloudInstanceId has changed for a given hostname
- **FR-003**: System MUST preserve the existing cloudInstanceId when the incoming value is null or blank
- **FR-004**: System MUST log instance ID changes at info level, including the old and new values
- **FR-005**: System MUST ensure that all import paths (CLI streaming, CLI hostname-specific, REST API batch import) consistently carry the cloudInstanceId from CrowdStrike through to the asset update logic

### Key Entities

- **Asset**: Core entity with fields `name` (hostname), `cloudAccountId` (AWS account), and `cloudInstanceId` (AWS EC2 instance ID). The cloudInstanceId must reflect the most recent value reported by CrowdStrike for the hostname + account combination.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After a CrowdStrike import where an EC2 instance was replaced, the asset in secman displays the correct (new) Instance ID matching the AWS Console within the same import cycle
- **SC-002**: No duplicate assets are created for hostname + account combinations where only the instance ID differs
- **SC-003**: Existing instance IDs are never overwritten with null or blank values
- **SC-004**: The fix applies consistently across all import paths (CLI `query servers --save`, CLI streaming mode, REST batch endpoint)

## Assumptions

- The CrowdStrike API returns the correct, current EC2 Instance ID in the `instance_id` field of the device metadata. If this field is not populated by CrowdStrike, secman cannot update it.
- The existing duplicate detection logic (matching by hostname shortname) is sufficient for finding existing assets. The fix focuses on ensuring the cloudInstanceId is properly updated during the merge, not on changing the duplicate detection criteria.
- The `cloudInstanceId` field in the Asset entity refers to the AWS EC2 Instance ID (format `i-<hex>`), which is sourced from CrowdStrike's `instance_id` / `service_provider_account_id` device metadata fields.
