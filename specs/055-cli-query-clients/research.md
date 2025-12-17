# Research: CLI Query Clients/Workstations

**Feature**: 055-cli-query-clients
**Date**: 2025-12-16

## Overview

This feature extends existing CLI functionality with minimal research required. The codebase already has a working implementation for server queries that can be parameterized.

## Research Topics

### 1. CrowdStrike Device Type Filtering

**Question**: What FQL filter values does CrowdStrike use to distinguish servers from workstations?

**Decision**: Use `product_type_desc` field with values `'Server'` and `'Workstation'`

**Rationale**:
- Existing implementation at `CrowdStrikeApiClientImpl.kt:594` uses `product_type_desc:'Server'`
- CrowdStrike Falcon API documentation confirms `product_type_desc` field supports: Server, Workstation, Domain Controller
- The implementation already filters by this field, just needs parameterization

**Alternatives Considered**:
- `platform_name` field: Less specific, indicates OS rather than device role
- `system_product_name` field: Hardware model, not device classification
- `device_type` field: Does not exist in CrowdStrike schema

### 2. Performance for Large Workstation Counts

**Question**: How will performance scale with potentially 50,000+ workstations?

**Decision**: Reuse existing batching and parallelism patterns

**Rationale**:
- Existing server implementation handles ~3,000 devices efficiently
- Batching (configurable via `secman.crowdstrike.batch-size`, default 20)
- Parallelism (configurable via `secman.crowdstrike.max-parallel-batches`, default up to 12)
- Rate limit handling with exponential backoff already implemented
- No architectural changes needed

**Alternatives Considered**:
- Increase default batch size for workstations: Rejected - let user configure via existing settings
- Add workstation-specific caching: Rejected - over-engineering for initial implementation

### 3. Backend Import Compatibility

**Question**: Can the existing backend import endpoint handle workstation data?

**Decision**: Yes, no changes required

**Rationale**:
- Backend endpoint `/api/crowdstrike/servers/import` processes `CrowdStrikeVulnerabilityBatchDto`
- The DTO is device-type agnostic - contains hostname, vulnerabilities, metadata
- Asset entity has no device type field; servers and workstations are just Assets
- Import logic creates/updates Asset records without distinguishing device type

**Alternatives Considered**:
- Add device type field to Asset: Rejected - scope creep, not in requirements
- Create separate workstation import endpoint: Rejected - unnecessary duplication

### 4. CLI Option Design

**Question**: Should `--device-type` be a string or use an enum?

**Decision**: Accept string input, validate against enum values internally

**Rationale**:
- Existing `deviceType: String = "SERVER"` property in ServersCommand.kt
- Case-insensitive parsing for user convenience
- Clear error message listing valid options on invalid input
- Enum provides type safety in API client layer

**Alternatives Considered**:
- Add new subcommands (`query workstations`, `query all`): Rejected - inconsistent with existing CLI structure
- Add `--include-workstations` flag: Rejected - less flexible than enum approach

## Dependencies

| Dependency | Version | Purpose | Verified |
|------------|---------|---------|----------|
| CrowdStrike Falcon API | v2 | Device and vulnerability queries | ✅ Existing |
| Picocli | 4.7 | CLI framework | ✅ Existing |
| Micronaut HTTP Client | 4.10 | API calls | ✅ Existing |

## Unknowns Resolved

All technical unknowns have been resolved. The implementation can proceed with the approach outlined in plan.md.

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Large workstation count causes timeout | Low | Medium | Existing batching/parallelism handles this |
| CrowdStrike API rate limits | Low | Low | Existing retry with exponential backoff |
| Workstation filter returns unexpected results | Low | Low | Test with `--dry-run` before import |
