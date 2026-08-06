# Data Model: REQADMIN Role for Release Management

**Feature**: 079-reqadmin-release-role
**Date**: 2026-02-06

## Overview

No schema changes required. This feature modifies authorization policy only.

## Entities

### User.Role (Enum - Modified)

The REQADMIN role was already added to the enum in 078-release-rework. No further changes needed.

```
USER, ADMIN, VULN, RELEASE_MANAGER, REQ, RISK, SECCHAMPION, REQADMIN
```

### Release (Entity - Unchanged)

No changes to the Release entity or its database table. Authorization changes are enforced at the controller and MCP tool layers.

## Authorization Matrix

### Before (Current State)

| Operation | ADMIN | RELEASE_MANAGER | REQADMIN | USER/REQ/etc |
|-----------|-------|-----------------|----------|--------------|
| Create Release | Yes | Yes | No | No |
| Delete Release | Yes (any) | Yes (own) | No | No |
| Update Status | Yes | Yes (own) | No | No |
| List Releases | Yes | Yes | Yes | Yes |
| Get Release | Yes | Yes | Yes | Yes |
| Compare | Yes | Yes | Yes | Yes |

### After (Target State)

| Operation | ADMIN | RELEASE_MANAGER | REQADMIN | USER/REQ/etc |
|-----------|-------|-----------------|----------|--------------|
| Create Release | Yes | **No** | **Yes** | No |
| Delete Release | Yes (any) | **No** | **Yes (own)** | No |
| Update Status | Yes | Yes (own) | No | No |
| List Releases | Yes | Yes | Yes | Yes |
| Get Release | Yes | Yes | Yes | Yes |
| Compare | Yes | Yes | Yes | Yes |

## State Transitions

No changes to release state machine:
```
PREPARATION → ALIGNMENT → ACTIVE → ARCHIVED
```

The state transitions remain gated by ADMIN or RELEASE_MANAGER. Only the entry (create) and exit (delete) of releases change to ADMIN or REQADMIN.
