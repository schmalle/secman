# Implementation Plan: S3 User Mapping Import

**Branch**: `065-s3-user-mapping-import` | **Date**: 2026-01-20 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/065-s3-user-mapping-import/spec.md`

## Summary

Add a new CLI subcommand `import-s3` under `manage-user-mappings` that downloads user mapping files from an AWS S3 bucket and imports them using the existing `UserMappingCliService.importMappingsFromFile` logic. This enables automated daily imports via cron, supporting the same CSV/JSON formats as the existing local file import. The implementation uses AWS SDK for Java v2 with standard credential chain authentication.

## Technical Context

**Language/Version**: Kotlin 2.3.0 / Java 21
**Primary Dependencies**: Micronaut 4.10, PicoCLI 4.7.5, AWS SDK for Java v2 (S3)
**Storage**: MariaDB 11.4 (existing user_mapping table), local temp files for S3 downloads
**Testing**: JUnit 5, Mockk 1.13.13, AssertJ 3.26.3
**Target Platform**: Linux/macOS server (CLI JAR via `./bin/secman`)
**Project Type**: Single project (CLI module within monorepo)
**Performance Goals**: Import 10,000 mapping entries within reasonable time
**Constraints**: 10MB max file size, temporary file cleanup required
**Scale/Scope**: Single daily cron job, single S3 bucket per invocation

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | ✅ PASS | Uses standard AWS SDK credential chain (no hardcoded credentials), validates file size before processing, sanitizes bucket/key inputs, follows existing RBAC pattern (ADMIN role required) |
| III. API-First | ✅ PASS | CLI command, not API endpoint; no API changes required |
| IV. User-Requested Testing | ✅ PASS | Test planning deferred per constitution; test infrastructure available when requested |
| V. Role-Based Access Control | ✅ PASS | Inherits existing `--admin-user` pattern from ManageUserMappingsCommand parent |
| VI. Schema Evolution | ✅ PASS | No database schema changes required; uses existing UserMapping entity |

**Constitution Check Result**: All gates PASS. Proceeding to Phase 0.

## Project Structure

### Documentation (this feature)

```text
specs/065-s3-user-mapping-import/
├── spec.md              # Feature specification
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (N/A - CLI command, not API)
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
src/cli/
├── src/main/kotlin/com/secman/cli/
│   ├── commands/
│   │   ├── ManageUserMappingsCommand.kt  # Parent command (existing)
│   │   ├── ImportCommand.kt              # Existing local file import
│   │   └── ImportS3Command.kt            # NEW: S3 import command
│   └── service/
│       ├── UserMappingCliService.kt      # Existing (reuse importMappingsFromFile)
│       └── S3DownloadService.kt          # NEW: S3 download logic
└── build.gradle.kts                       # Add AWS SDK dependency
```

**Structure Decision**: Extend existing CLI module with new command class and service. Follows established pattern from existing ImportCommand.

## Complexity Tracking

> No violations to justify. All implementation follows existing patterns.
