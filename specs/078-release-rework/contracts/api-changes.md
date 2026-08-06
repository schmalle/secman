# API Contract Changes: Release Rework

**Feature**: 078-release-rework
**Date**: 2026-02-06

## Breaking Changes

### Release Status Values

All endpoints returning or accepting release status strings change:

| Endpoint | Old Values | New Values |
|----------|-----------|-----------|
| `POST /api/releases` | Returns `"DRAFT"` | Returns `"PREPARATION"` |
| `GET /api/releases` | Returns `DRAFT,IN_REVIEW,ACTIVE,LEGACY,PUBLISHED` | Returns `PREPARATION,ALIGNMENT,ACTIVE,ARCHIVED` |
| `GET /api/releases/{id}` | Same | Same |
| `PUT /api/releases/{id}/status` | Accepts `"ACTIVE"` | Accepts `"ACTIVE"` (no change) |
| `GET /api/releases?status=` | Accepts old values | Accepts `PREPARATION,ALIGNMENT,ACTIVE,ARCHIVED` |

### MCP Tools

| Tool | Old Behavior | New Behavior |
|------|-------------|-------------|
| `create_release` | Returns `status: "DRAFT"` | Returns `status: "PREPARATION"` |
| `list_releases` | Filter accepts `DRAFT,ACTIVE,LEGACY` | Filter accepts `PREPARATION,ALIGNMENT,ACTIVE,ARCHIVED` |
| `set_release_status` | Accepts `"ACTIVE"` only | Accepts `"ACTIVE"` only (no change) |

## No Changes Required

These endpoints are unaffected:

- `DELETE /api/releases/{id}` — status-independent (blocks ACTIVE only)
- `GET /api/releases/{id}/requirements` — returns snapshots, no status logic
- `GET /api/releases/compare` — compares snapshots, no status logic
- All export endpoints (`/api/requirements/export/*`) — use releaseId, not status
- `compare_releases` MCP tool — no status logic

## Frontend Session Storage

New: `selectedReleaseId` persisted in `sessionStorage` under key `secman_selectedReleaseId`.

- Value: release ID (number) or `null` (live/no active release)
- Set on: release selector change
- Read on: page load (RequirementManagement, Export)
- Cleared on: logout
