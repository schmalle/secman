# Tasks — Requirement Word Export Templates

## Phase 1 — Backend foundation

- [ ] Add Flyway migration for `requirement_export_template` and `requirement_export_template_usage`.
- [ ] Add Kotlin entities, repositories, DTOs, and configuration properties.
- [ ] Extract current Word export creation into `RequirementWordExportService` while preserving existing output.
- [ ] Add `RequirementExportTemplateValidationService` with OpenXML validation and structured reports.
- [ ] Add immutable storage abstraction for saved template bytes.

## Phase 2 — API and rendering

- [ ] Add template management controller with ADMIN/REQADMIN RBAC.
- [ ] Extend Word export endpoints with `templateMode`, `templateId`, `classification`, and missing-placeholder behavior.
- [ ] Add multipart POST export endpoints for ad hoc templates.
- [ ] Implement template rendering with placeholder replacement and requirement insertion.
- [ ] Add audit events and usage recording.

## Phase 3 — UI, CLI, and MCP

- [ ] Add Word template section to export dialogs.
- [ ] Add ADMIN/REQADMIN template management panel.
- [ ] Extend CLI `export-requirements --format docx` with template options.
- [ ] Extend MCP `export_requirements` schema for saved/latest/no-template modes.

## Phase 4 — Tests and security verification

- [ ] Add validator unit tests for unsafe and valid templates.
- [ ] Add integration tests for RBAC, persistence, latest-template selection, and audit events.
- [ ] Add controller tests for latest/saved/ad hoc/no-template exports.
- [ ] Add frontend tests for UI states and permission visibility.
- [ ] Run `/e2ejs` and `/e2evulnexception` after implementation.
- [ ] Complete the security verification checklist in `security-review.md`.
