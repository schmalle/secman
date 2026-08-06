# Feature 089 — Requirement Word Export Templates

**Branch**: `work`
**Status**: Planned — ready for implementation
**Owner roles touched**: REQ, REQADMIN, ADMIN, REPORT
**Security review**: Required because the feature adds authenticated file upload, server-side document parsing, persistent binary storage, and export-time template processing.

## Problem

Requirement Word exports currently generate a fixed `.docx` from code. Users can export current requirements or release/use-case subsets, but they cannot apply a company-specific Word template containing headers, footers, logos, introductory text, document control pages, or approved styles. This creates manual post-processing after every export and increases the risk that official requirement documents diverge from the exported source data.

## Goal

Allow authorized users to upload a reusable `.docx` export template into Secman and select it for Word requirement exports. The export window must support both:

1. **Ad hoc template upload**: use a file for this export run without making it the default.
2. **Saved latest template**: persist approved templates in Secman and automatically use the latest active template on the next Word export unless the user opts out or picks another template.

## Non-goals

- No template support for Excel exports in v1.
- No arbitrary scripting, macros, external template links, or embedded active content.
- No rich template designer in Secman; users prepare `.docx` files in Word/LibreOffice.
- No public unauthenticated template management endpoint.
- No replacement of requirement data authorization; exports must keep the existing requirement/release/use-case filtering behavior.

## User stories

### US1 (P1) — Upload and save a default Word template

As a REQADMIN, I want to upload a corporate `.docx` template and save it in Secman so that future Word exports automatically use the latest approved formatting, headers, footer text, and introductory content.

**Independent test**: Upload a valid `.docx` with a header, footer, logo, intro section, and the `${requirements}` placeholder; verify it appears in the template list as active; export requirements without choosing a template; verify the generated Word file uses that latest active template.

### US2 (P1) — Use a one-time ad hoc template during export

As a user exporting requirements, I want to attach a template directly in the export dialog for this one export without changing the stored default so that I can produce a project-specific document.

**Independent test**: With default template A saved, upload ad hoc template B in the export dialog, export once, and verify the output uses B while the latest saved template remains A for the next export.

### US3 (P2) — Choose template behavior per export

As a user exporting requirements, I want to choose whether the export uses the latest saved template, a specific saved template, an ad hoc upload, or the system default so that I can generate the right output for draft, official, and customer-specific scenarios.

**Independent test**: Export the same requirement subset using each mode and verify the output reflects the selected mode while the underlying requirement content and authorization scope remain unchanged.

### US4 (P2) — Review template metadata and audit usage

As an ADMIN or REQADMIN, I want to see who uploaded a template, when it was uploaded, whether it is active, and where it has been used so that document provenance can be audited.

**Independent test**: Upload two templates, deactivate the older one, export with the latest active template, and verify audit entries capture uploader, selected template, checksum, export actor, scope, and timestamp.

### US5 (P3) — Preview template validity before saving

As a REQADMIN, I want Secman to validate a template and show detected placeholders/options before saving it so that broken templates are not discovered during an official export.

**Independent test**: Upload templates with missing `${requirements}`, duplicate placeholders, external relationships, and unsupported file type; verify validation warnings/errors before persistence.

## Functional requirements

- FR-1: Secman must accept only `.docx` template uploads with the OpenXML Word MIME type and a valid ZIP/OpenXML structure.
- FR-2: Template uploads must be restricted to ADMIN and REQADMIN by default. REQ and REPORT users may select active templates for export but must not create, update, or delete templates unless an explicit configuration enables it.
- FR-3: The export dialog must offer template modes: `Latest saved template`, `Specific saved template`, `Ad hoc upload for this export`, and `No template / Secman default`.
- FR-4: If the user leaves the template mode unchanged, Word exports must use the latest active saved template when one exists; otherwise they must fall back to the existing generated document layout.
- FR-5: Ad hoc templates must be processed only for the current request and must not be persisted as saved templates unless the user also has upload permission and explicitly chooses `Save as reusable template`.
- FR-6: Saved templates must store metadata: display name, description, version label, uploaded filename, content type, file size, SHA-256 checksum, status, uploadedBy, createdAt, activatedAt, deactivatedAt, lastUsedAt, and optional retention/deletion fields.
- FR-7: The system must keep immutable binary contents for a template version. Editing metadata must not mutate the original uploaded bytes; replacing a template creates a new version row.
- FR-8: The export renderer must insert generated requirement content at `${requirements}`. If the placeholder is missing and the user selected `append mode`, generated requirements must be appended after the template body; otherwise validation must reject the template.
- FR-9: Supported placeholders in v1 must include `${requirements}`, `${documentTitle}`, `${exportDate}`, `${releaseVersion}`, `${releaseStatus}`, `${useCaseName}`, `${exportedBy}`, `${language}`, `${requirementCount}`, and `${classification}`.
- FR-10: The renderer must preserve template headers, footers, page margins, numbering definitions, styles, static intro text, and static images that pass validation.
- FR-11: The renderer must strip or reject macros, OLE objects, embedded packages, external relationships, external hyperlinks in headers/footers unless allowed by configuration, remote images, and active content.
- FR-12: The export API must support template selection for all current Word export variants: all requirements, use-case exports, release snapshots, translated exports, and MCP/CLI Word exports.
- FR-13: Template validation failures must produce user-friendly error messages without leaking server paths, stack traces, or raw document XML.
- FR-14: Every upload, validation failure, activation/deactivation, deletion, and export usage must be written to the audit log.
- FR-15: Downloading a saved template for review must be restricted to ADMIN and REQADMIN and must set safe `Content-Disposition` filenames.
- FR-16: Template size, placeholder count, image count, and total uncompressed ZIP size must be bounded by configuration.
- FR-17: The feature must be covered by backend unit/integration tests, frontend component/E2E tests, CLI tests for template options, and MCP contract tests where applicable.

## UI proposal

### Export window attributes/options

Add a **Word template** section that appears only when the selected export format is Word (`.docx`). Meaningful fields:

| Field | Type | Default | Purpose |
|---|---|---:|---|
| Template mode | Radio group | Latest saved template | Controls automatic latest-template behavior, specific selection, ad hoc upload, or no template. |
| Saved template | Select | Latest active | Lists active templates with name, version, uploader, upload date, and last-used date. Enabled for `Specific saved template`. |
| Ad hoc template file | File picker | Empty | Accepts one `.docx` for `Ad hoc upload for this export`. |
| Save as reusable template | Checkbox | Off | Only visible for ADMIN/REQADMIN during ad hoc upload; persists the uploaded file after successful validation. |
| Template name | Text | Filename stem | Required when saving reusable templates. |
| Version label | Text | Auto timestamp | Optional human-readable version such as `Corporate 2026-Q2`. |
| Description | Text area | Empty | Documents template intent, approved audience, or project/customer context. |
| Missing placeholder behavior | Select | Reject | `Reject` requires `${requirements}`; `Append after template body` appends generated content. |
| Include cover metadata | Checkbox | On | Populates document title, release/use-case/language, export date, exported-by, and requirement count placeholders. |
| Classification | Select/text | Internal | Populates `${classification}` and supports future policy-driven labels. |
| Validate only | Button | — | Runs server validation and reports placeholders, blocked objects, size, images, and styles before export/save. |
| Preview summary | Read-only panel | — | Shows selected template checksum prefix, validation status, active/latest marker, and warnings. |

### Template management window

Add an ADMIN/REQADMIN management panel under Import/Export or Admin → Requirements:

- Upload new template.
- List saved templates with active/latest badges.
- Activate/deactivate a template version.
- Download original template for review.
- Delete only unused draft templates; otherwise mark retired to preserve audit history.
- Show validation report, checksum, createdBy, createdAt, lastUsedAt, and export usage count.

## API plan

All management endpoints require authentication and ADMIN or REQADMIN unless noted.

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `GET` | `/api/requirement-export-templates` | — | `200 TemplateSummary[]` | REQ/REPORT can list active summaries for selection; ADMIN/REQADMIN also see inactive/retired with query flags. |
| `POST` | `/api/requirement-export-templates/validate` | multipart `.docx` | `200 ValidationReport` | Does not persist bytes. Used by export dialog and management page. |
| `POST` | `/api/requirement-export-templates` | multipart `.docx` + metadata | `201 TemplateSummary` | Persists immutable version after validation. |
| `GET` | `/api/requirement-export-templates/latest` | — | `200 TemplateSummary` or `204` | Returns latest active template metadata. |
| `GET` | `/api/requirement-export-templates/{id}` | — | `200 TemplateDetail` | Metadata and validation report. |
| `GET` | `/api/requirement-export-templates/{id}/download` | — | `.docx` | ADMIN/REQADMIN only. |
| `POST` | `/api/requirement-export-templates/{id}/activate` | — | `200 TemplateSummary` | Marks active and deactivates conflicting latest if single-active policy is enabled. |
| `POST` | `/api/requirement-export-templates/{id}/deactivate` | — | `200 TemplateSummary` | Retains template for audit. |
| `DELETE` | `/api/requirement-export-templates/{id}` | — | `204` | Hard delete only if never used and policy permits; otherwise `409` with retire guidance. |
| `GET` | `/api/requirement-export-templates/{id}/usage` | — | `200 UsageEvent[]` | Audit/traceability. |

Extend existing Word export endpoints with query/form parameters:

- `templateMode=LATEST|SAVED|ADHOC|NONE`
- `templateId=<id>` when `SAVED`
- `missingPlaceholderBehavior=REJECT|APPEND`
- `classification=<value>`
- Multipart body with `templateFile` when `ADHOC`
- Backward-compatible default: omitted parameters behave as `LATEST` when an active template exists, else current built-in renderer.

For `GET` exports that need ad hoc upload, add parallel `POST` endpoints (for example `/api/requirements/export/docx`) accepting multipart form data while keeping the current `GET` endpoints for saved/latest/none modes.

## Data model plan

### `requirement_export_template`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | Template version identifier. |
| `name` | VARCHAR(200) | User-visible display name. |
| `description` | TEXT nullable | Administrative description. |
| `version_label` | VARCHAR(100) nullable | Human-readable version. |
| `status` | ENUM(`ACTIVE`,`INACTIVE`,`RETIRED`,`REJECTED`) | Only ACTIVE can be used by default exports. |
| `original_filename` | VARCHAR(255) | Sanitized filename. |
| `content_type` | VARCHAR(128) | Must be `.docx` MIME. |
| `file_size_bytes` | BIGINT | Enforce configured max. |
| `sha256` | CHAR(64) | Deduplication and audit. |
| `storage_key` | VARCHAR(512) | Object-store/file-store key, not a raw path. |
| `validation_report_json` | JSON | Placeholder and sanitization results. |
| `uploaded_by_user_id` | BIGINT FK | Uploader. |
| `created_at` | TIMESTAMP | Upload timestamp. |
| `activated_at` | TIMESTAMP nullable | Activation timestamp. |
| `deactivated_at` | TIMESTAMP nullable | Deactivation timestamp. |
| `last_used_at` | TIMESTAMP nullable | Export usage tracking. |

### `requirement_export_template_usage`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | Usage event. |
| `template_id` | BIGINT FK nullable | Null for ad hoc or system default exports. |
| `template_sha256` | CHAR(64) nullable | Captures ad hoc template checksum too. |
| `exported_by_user_id` | BIGINT FK | Actor. |
| `export_scope` | ENUM(`ALL`,`USE_CASE`,`RELEASE`,`TRANSLATED`,`MCP`,`CLI`) | Export type. |
| `release_id` | BIGINT nullable | Scope detail. |
| `usecase_id` | BIGINT nullable | Scope detail. |
| `language` | VARCHAR(32) nullable | Translation export language. |
| `template_mode` | ENUM(`LATEST`,`SAVED`,`ADHOC`,`NONE`) | Selected mode. |
| `created_at` | TIMESTAMP | Export timestamp. |

## Implementation plan

1. **Backend domain**
   - Add entities/repositories for templates and usage events.
   - Add Flyway migration with indexes on `status`, `created_at`, `sha256`, and `last_used_at`.
   - Add configuration properties for upload size, uncompressed ZIP limit, image count, external relationship policy, and ad hoc retention.

2. **Validation and sanitization service**
   - Build a `RequirementExportTemplateValidationService` that opens `.docx` as ZIP/OpenXML, checks MIME/extension/signature, detects placeholders, rejects forbidden relationship types and macros, enforces limits, and returns a structured validation report.
   - Build a storage abstraction that writes immutable bytes outside the web root or to an object store using generated keys.

3. **Rendering service**
   - Extract current Word export creation from `RequirementController` into a `RequirementWordExportService`.
   - Add template-aware rendering: load template, populate placeholders, insert generated requirement blocks at `${requirements}` or append mode, preserve allowed styles/headers/footers, and write final `.docx`.
   - Keep a no-template code path that matches current output for backward compatibility.

4. **API/controllers**
   - Add template management controller.
   - Extend existing export endpoints with template mode/ID for saved/latest/none and add multipart POST variants for ad hoc uploads.
   - Update translated, release, use-case, MCP, and CLI export code paths to use the shared rendering service.

5. **Frontend**
   - Update the Export and Import/Export requirement panels with the Word template section.
   - Add template management UI for ADMIN/REQADMIN.
   - Use `data-testid` selectors for template mode, saved template select, ad hoc file picker, validate button, and save checkbox.

6. **CLI/MCP**
   - CLI: add `--template latest|none|<id>`, `--template-file <path>`, `--save-template`, `--template-name`, and `--classification` for `export-requirements --format docx`.
   - MCP: extend `export_requirements` schema with `templateMode`, `templateId`, and `classification`; do not support binary ad hoc upload through MCP in v1 unless a safe file-reference mechanism already exists.

7. **Observability and audit**
   - Emit structured audit events for upload, validation failure, activation/deactivation, deletion/retirement, and export usage.
   - Add metrics for validation failures by reason, template export count, and render latency.

8. **Tests**
   - Unit tests for validator with valid `.docx`, macro-enabled renamed files, ZIP bombs, external relationships, missing placeholders, and oversized images.
   - Integration tests for role enforcement and persistence using MariaDB/Testcontainers.
   - Controller tests for saved/latest/ad hoc/no-template export modes.
   - Frontend tests for UI state transitions and permissions.
   - E2E tests for upload latest template, ad hoc export, and fallback to default renderer.

## Acceptance criteria

- A REQADMIN can upload and activate a `.docx` template with header/footer/intro and `${requirements}`.
- A normal export uses the latest active template by default and falls back to current generated layout when no active template exists.
- A user can perform one export with an ad hoc template without changing the saved latest template.
- The UI clearly shows which template will be used before export.
- Invalid or unsafe templates are rejected with actionable messages.
- Exports are audited with actor, template identity/checksum, scope, and timestamp.
- All current Word export variants continue to work, including release/use-case/translated exports, CLI, and MCP.
