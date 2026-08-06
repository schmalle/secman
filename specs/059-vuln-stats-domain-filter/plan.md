# Implementation Plan: Vulnerability Statistics Domain Filter

**Branch**: `059-vuln-stats-domain-filter` | **Date**: 2026-01-04 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/059-vuln-stats-domain-filter/spec.md`

## Summary

Add a domain selector dropdown to the vulnerability statistics page that allows users to filter all statistics (Top 10 Vulnerabilities, Top 10 Products, Severity Distribution) by Active Directory domain. The filter will be applied as an additional constraint on top of existing access control, with session persistence and independent loading behavior.

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21 (backend), TypeScript/React 19 (frontend)
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, Axios, Bootstrap 5.3
**Storage**: MariaDB 11.4 (existing `asset` table with `ad_domain` column)
**Testing**: User-requested (per Constitution Principle IV)
**Target Platform**: Web application (Linux server backend, modern browsers frontend)
**Project Type**: Web application (backend + frontend)
**Performance Goals**: Domain filter response < 3 seconds (SC-001)
**Constraints**: Must maintain existing access control, session storage for persistence
**Scale/Scope**: Support users with 10+ accessible domains (SC-005)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | ✅ PASS | Domain filter applied on top of existing RBAC; no bypass possible |
| III. API-First | ✅ PASS | New endpoint follows RESTful patterns; backward compatible |
| IV. User-Requested Testing | ✅ PASS | No test tasks included unless requested |
| V. RBAC | ✅ PASS | Filter uses existing workgroup-based filtering logic |
| VI. Schema Evolution | ✅ PASS | No schema changes needed; uses existing `ad_domain` column |

## Project Structure

### Documentation (this feature)

```text
specs/059-vuln-stats-domain-filter/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── domain-filter-api.yaml
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
backend/
├── src/backendng/src/main/kotlin/com/secman/
│   ├── controller/
│   │   └── VulnerabilityStatisticsController.kt  # Add domain parameter to existing endpoints
│   ├── service/
│   │   └── VulnerabilityStatisticsService.kt     # Add domain filtering logic
│   └── dto/
│       └── AvailableDomainsDto.kt                # New DTO for domains list

frontend/
├── src/frontend/src/
│   ├── components/statistics/
│   │   ├── DomainSelector.tsx                    # New component
│   │   ├── MostCommonVulnerabilities.tsx         # Add domain prop
│   │   ├── MostVulnerableProducts.tsx            # Add domain prop
│   │   └── SeverityDistributionChart.tsx         # Add domain prop
│   ├── pages/
│   │   └── vulnerability-statistics.astro        # Integrate DomainSelector
│   └── services/api/
│       └── vulnerabilityStatisticsApi.ts         # Add domain param to methods
```

**Structure Decision**: Extends existing web application structure. No new modules required; modifies existing statistics controller, service, and frontend components.

## Complexity Tracking

> No violations to justify - all requirements fit within existing patterns.
