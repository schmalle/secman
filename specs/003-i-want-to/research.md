# Research & Technical Decisions
**Feature**: Vulnerability Management System
**Date**: 2025-10-03

## Overview
This document captures technical research and design decisions made during the planning phase for the vulnerability management feature.

## Technical Decisions

### 1. Default Asset Values
**Decision**: When auto-creating assets from vulnerability data, use:
- owner: "Security Team"
- type: "Server"
- description: "Auto-created from vulnerability scan"

**Rationale**:
- Provides consistent, traceable defaults for audit purposes
- "Security Team" owner indicates automated creation context
- "Server" is most common asset type for vulnerability scans
- Description template enables filtering auto-created assets

**Alternatives Considered**:
- Empty/nullable owner: Rejected - violates Asset.owner non-null constraint and loses traceability
- OS-based type inference (Windows→Server, Linux→Server): Rejected - adds complexity without significant value
- Prompt user for defaults: Rejected - slows import workflow, defeats automation purpose

**Implementation Notes**: Apply defaults only when creating new assets, never overwrite existing asset data

---

### 2. Import Feedback Format
**Decision**: Show counts with warnings format:
```
"X imported, Y skipped (invalid), Z assets created"
```

**Rationale**:
- Balances detail with usability - users get key metrics at a glance
- Actionable feedback - "skipped" count prompts investigation of problematic data
- Asset creation tracking - visibility into auto-created records
- Matches existing import patterns in codebase

**Alternatives Considered**:
- Summary only ("X vulnerabilities imported"): Rejected - insufficient feedback on errors/warnings
- Detailed row-by-row breakdown: Rejected - overwhelming for large imports, better suited for export/log file
- Progress percentage during upload: Deferred - can add as enhancement, not MVP requirement

**Implementation Notes**: Store skipped row details (row number, reason) in response DTO for detailed error reporting if needed

---

### 3. Validation Error Handling
**Decision**: Skip invalid rows, import valid ones, report skipped rows in feedback

**Rationale**:
- Maximizes data recovery from partially corrupt files
- Prevents single bad row from blocking entire import
- Aligns with user expectation: "import what you can"
- Matches pattern in existing ImportController for requirements

**Alternatives Considered**:
- Fail entire import on first error: Rejected - poor UX for large files with minor issues
- Attempt best-effort parsing of invalid rows: Rejected - risks importing incorrect data
- Quarantine invalid rows for manual review: Deferred - adds complexity, not MVP

**Implementation Notes**:
- Validate each row independently in try-catch block
- Log warnings for skipped rows (row number + reason)
- Continue processing after errors
- Report skipped count and details in response

---

### 4. Duplicate Vulnerability Handling
**Decision**: Keep all duplicate vulnerability entries as separate records for historical tracking

**Rationale**:
- Enables vulnerability trend analysis over time (when did it first appear, is it recurring)
- Supports compliance requirements for vulnerability history audit trails
- scanTimestamp differentiates duplicates, making each record unique
- No risk of data loss from merge logic

**Alternatives Considered**:
- Reject duplicates, keep first occurrence: Rejected - loses historical context, no trend visibility
- Merge/update existing records: Rejected - loses scan history, can't track when vulnerability reappeared
- Configurable behavior (keep/merge/reject): Deferred - adds complexity, historical tracking is core requirement

**Implementation Notes**:
- No uniqueness constraint on (asset_id, vulnerability_id) combination
- Use scanTimestamp as differentiator
- Query optimization: index on (asset_id, scanTimestamp) for historical queries

---

### 5. Asset Conflict Resolution
**Decision**: Intelligent merge strategy:
- Append new groups to existing groups (comma-separated)
- Update IP address if changed
- Preserve other existing fields (owner, type, description)

**Rationale**:
- Prevents data loss - don't overwrite existing asset metadata
- Groups are additive - asset can belong to multiple groups over time
- IP updates reflect infrastructure changes (DHCP, redeployment)
- Preserves manually curated data (owner, description)

**Alternatives Considered**:
- Preserve all existing data, ignore updates: Rejected - IP becomes stale, groups incomplete
- Overwrite all fields with new data: Rejected - loses manually entered metadata
- Flag conflicts for manual review: Deferred - blocks automation, suitable for future enhancement

**Implementation Notes**:
- AssetMergeService.mergeAssetData(existing, imported)
- Group merge: split on comma, deduplicate, rejoin
- IP update: simple replacement if different
- Log merge actions for audit trail

---

### 6. Excel Parsing Library
**Decision**: Use Apache POI 5.3.0 (already in dependencies)

**Rationale**:
- Already used for requirements import (ImportController.kt)
- Proven to work in production deployment
- Consistent dependency management - avoid library proliferation
- Team familiar with API from existing code

**Alternatives Considered**:
- fastexcel: Rejected - not needed, POI sufficient for file sizes (up to 10MB limit)
- kotlin-excel: Rejected - adds Kotlin-specific dependency, POI more standard
- CSV parsing: Rejected - doesn't support .xlsx format required by spec

**Implementation Notes**:
- Reuse utility methods from existing ImportController (getCellValueAsString, etc.)
- XSSFWorkbook for .xlsx support
- DataFormatter for consistent numeric cell handling

---

### 7. Date/Time Picker UI
**Decision**: HTML5 `<input type="datetime-local">` with Bootstrap 5 styling

**Rationale**:
- Native browser support - no additional JavaScript library needed
- Accessible by default (keyboard navigation, screen reader support)
- Mobile-friendly with native date/time pickers on iOS/Android
- Bootstrap styling provides consistent look with existing UI
- Zero bundle size impact

**Alternatives Considered**:
- React Date Picker library: Rejected - adds 50KB+ to bundle, unnecessary for simple date selection
- Flatpickr: Rejected - another dependency, native input sufficient
- Separate date + time inputs: Rejected - worse UX, more validation complexity

**Implementation Notes**:
- Format: ISO 8601 (YYYY-MM-DDTHH:mm) for HTML datetime-local
- Convert to LocalDateTime on backend
- Default value: current timestamp (pre-filled for convenience)
- Validation: required field, reasonable date range (not future, not >10 years past)

---

## Architecture Decisions

### Database Schema
- **Vulnerability** table (new):
  - id (PK), asset_id (FK), vulnerability_id, cvss_severity, vulnerable_product_versions, days_open, scan_timestamp, created_at
  - Index on (asset_id, scan_timestamp) for historical queries
  - Cascade delete with asset (orphan removal)

- **Asset** table (extended):
  - Add columns: groups, cloud_account_id, cloud_instance_id, ad_domain, os_version
  - All new fields nullable
  - Add index on name (hostname lookup)

### API Design
- POST `/api/import/upload-vulnerability-xlsx` - multipart/form-data (xlsxFile, scanDate)
- GET `/api/assets/{id}/vulnerabilities` - list vulnerabilities for asset
- Response format: JSON with counts (imported, skipped, assetsCreated) + optional skippedDetails array

### Service Layer
- **VulnerabilityImportService**: Parse Excel, validate rows, create Vulnerability entities
- **AssetMergeService**: Find-or-create assets, merge conflict resolution
- Transactional boundaries: Per-import transaction, rollback on critical errors only

### Frontend Components
- **VulnerabilityImportForm**: Form with file input + datetime-local picker, submit to API
- **Import.tsx**: Add "Vulnerabilities" tab alongside existing "Requirements" tab
- **Asset page**: Display vulnerabilities table with columns: CVE ID, Severity, Scan Date, Days Open

---

## Security Considerations
1. **File Upload Validation**:
   - Max size: 10MB (same as existing import)
   - Content-Type validation: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
   - File extension check: .xlsx only
   - Virus scan: Consider adding (deferred, not MVP)

2. **Input Sanitization**:
   - Excel cell values: Trim whitespace, validate format
   - Hostname validation: Basic format check, prevent injection
   - CVE ID validation: Optional regex pattern (CVE-\d{4}-\d{4,7})

3. **Authorization**:
   - @Secured(SecurityRule.IS_AUTHENTICATED) on import endpoint
   - Future enhancement: Admin-only import (RBAC check for 'adminuser' role)

4. **Audit Logging**:
   - Log import events: user, timestamp, file name, counts
   - Log asset creation/merge operations
   - Log skipped rows with reasons

---

## Performance Considerations
- **Import Speed**: Target <5s for 1000 rows
  - Batch insert vulnerabilities (JDBC batch)
  - Minimize database queries (asset lookup cache)
  - Stream Excel rows, don't load all into memory

- **Query Optimization**:
  - Index on vulnerability.asset_id for joins
  - Index on asset.name for hostname lookups
  - Lazy loading for asset.vulnerabilities relationship

- **Frontend**:
  - Progress indicator during upload (visual feedback)
  - Debounce file validation
  - Paginate vulnerability display if >100 records

---

## Testing Strategy
1. **Contract Tests** (Phase 1): Define API contracts, write failing tests
2. **Unit Tests**: VulnerabilityImportService parsing logic, AssetMergeService merge logic
3. **Integration Tests**: Full import flow, asset merge scenarios, error handling
4. **E2E Tests**: Frontend upload flow, view vulnerabilities on asset page
5. **Test Data**: Sample Excel with edge cases (empty cells, duplicates, invalid formats)

---

## Dependencies
No new dependencies required:
- Apache POI 5.3.0: Already in build.gradle.kts
- Micronaut Security: Already configured for authentication
- Bootstrap 5.3: Already in frontend package.json
- Playwright: Already configured for E2E tests

---

## Risks & Mitigations
| Risk | Impact | Mitigation |
|------|--------|-----------|
| Large file imports timeout | High | Add progress feedback, consider async processing for >5000 rows (deferred) |
| Excel format variations | Medium | Strict header validation, clear error messages, support only .xlsx |
| Asset hostname conflicts | Medium | Intelligent merge logic, audit logging, future: conflict review UI |
| Historical data volume | Low | Index optimization, pagination, archive old vulnerabilities (future) |

---

## Future Enhancements (Out of Scope for MVP)
- Export vulnerabilities to Excel/PDF
- Bulk vulnerability remediation tracking
- Integration with vulnerability databases (NVD, CVE.org)
- Automated scan scheduling
- Vulnerability age thresholds with alerting
- Asset conflict review UI for manual resolution
