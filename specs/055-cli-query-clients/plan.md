# Implementation Plan: CLI Query Clients/Workstations

**Branch**: `055-cli-query-clients` | **Date**: 2025-12-16 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/055-cli-query-clients/spec.md`

## Summary

Extend the existing `query servers` CLI command to support querying CrowdStrike for workstation/client devices in addition to servers. Add a `--device-type` option accepting SERVER (default), WORKSTATION, or ALL values. The implementation follows existing patterns with minimal changes to the shared CrowdStrike API client.

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21
**Primary Dependencies**: Micronaut 4.10, Picocli 4.7 (CLI framework)
**Storage**: MariaDB 11.4 (existing Asset/Vulnerability entities - no changes required)
**Testing**: N/A (per constitution - testing only when requested)
**Target Platform**: CLI tool (JVM)
**Project Type**: Multi-module (cli, shared, backendng)
**Performance Goals**: Match existing server query performance (batching, parallelism)
**Constraints**: Backward compatible - default behavior unchanged
**Scale/Scope**: Workstation counts potentially 5-10x higher than servers (~50,000+ devices)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | ✅ PASS | No new input vectors; reuses existing auth patterns |
| III. API-First | ✅ PASS | No backend API changes; CLI-only feature |
| IV. User-Requested Testing | ✅ PASS | No test planning included |
| V. RBAC | ✅ PASS | No authorization changes; CLI uses existing backend auth |
| VI. Schema Evolution | ✅ PASS | No database schema changes |

**Gate Status**: PASSED - No violations requiring justification

## Project Structure

### Documentation (this feature)

```text
specs/055-cli-query-clients/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (files to modify)

```text
src/cli/src/main/kotlin/com/secman/cli/
├── commands/
│   └── ServersCommand.kt          # Add --device-type option parsing
└── SecmanCli.kt                   # (if needed) Add option wiring

src/shared/src/main/kotlin/com/secman/crowdstrike/
├── client/
│   ├── CrowdStrikeApiClient.kt    # No changes needed (deviceType already accepted)
│   └── CrowdStrikeApiClientImpl.kt # Add getDeviceIdsFiltered() with deviceType param
└── dto/
    └── DeviceType.kt              # NEW: Enum for SERVER, WORKSTATION, ALL
```

**Structure Decision**: Existing multi-module structure maintained. Changes isolated to CLI command layer and shared CrowdStrike client.

## Implementation Approach

### Key Changes

1. **ServersCommand.kt** (CLI layer)
   - Already has `deviceType: String = "SERVER"` property (line 42)
   - Need to: validate input, support case-insensitive matching, add error for invalid values
   - Log output should reflect actual device type being queried

2. **CrowdStrikeApiClientImpl.kt** (API client)
   - `getServerDeviceIdsFiltered()` hardcodes `product_type_desc:'Server'` (line 594)
   - Refactor to accept `deviceType` parameter
   - For `ALL`: Query both device types and combine results
   - Rename method to `getDeviceIdsFiltered(deviceType: DeviceType)`

3. **DeviceType.kt** (new enum)
   - Values: SERVER, WORKSTATION, ALL
   - Method: `toFqlFilter()` returns appropriate filter string

### CrowdStrike FQL Filters

| Device Type | FQL Filter |
|-------------|------------|
| SERVER | `product_type_desc:'Server'+last_seen:>'now-1d'` |
| WORKSTATION | `product_type_desc:'Workstation'+last_seen:>'now-1d'` |
| ALL | Query both filters separately, combine device IDs |

## Complexity Tracking

> No Constitution Check violations - this section is empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| (none) | - | - |
