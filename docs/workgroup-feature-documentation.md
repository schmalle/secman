# Workgroup Feature Documentation

## Purpose
This document summarizes the Secman Workgroup feature and its access-rights model.

## What is a Workgroup?
- A workgroup is a scoped collaboration boundary for assets and vulnerabilities.
- Users only see data for assets linked to workgroups they can access.
- Workgroups can also carry AWS account assignments for cloud ownership mapping.

## Core Entities
- **Workgroup**: name, description, criticality, optional creator.
- **User ↔ Workgroup**: membership mapping controls access.
- **Asset ↔ Workgroup**: asset scope mapping controls visibility.
- **Workgroup ↔ AWS account**: cloud account ownership mapping.

## Access Control Model
- **RBAC gate**: endpoint/tool permission checks (e.g., write-level workgroup operations).
- **Workgroup scope gate**: row/data-level filtering by accessible workgroups.
- Regular users can operate only on accessible resources.
- Admin users can manage global assignments and lifecycle operations.

## Functional Rights
- Read rights: list/view accessible workgroups and scoped resources.
- Write rights: create/delete workgroups, assign users/assets.
- Cloud rights: add/remove/list workgroup AWS accounts.
- Deletion safety: non-privileged users can only delete workgroups they created.

## MCP/API Operations
- `create_workgroup(name, description)`
- `delete_workgroup(workgroupId)`
- `assign_assets_to_workgroup(workgroupId, assetIds[])`
- `assign_users_to_workgroup(workgroupId, userIds[])`
- `list_workgroup_aws_account(workgroupId)`
- `add_workgroup_aws_account(workgroupId, cloudAccountId)`
- `remove_workgroup_aws_account(workgroupId, cloudAccountId)`

## Governance Workflow
1. Create a workgroup for a team/domain boundary.
2. Assign responsible users.
3. Assign relevant assets.
4. Link AWS accounts if cloud ownership applies.
5. Validate scoped visibility with non-admin users.

## Security and Operations
- Enforce least privilege and grant write rights sparingly.
- Review memberships periodically.
- Audit denied access attempts for policy tuning.
- Use consistent naming and remove stale mappings.
