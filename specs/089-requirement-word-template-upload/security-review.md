# Security Review — Requirement Word Export Templates

**Feature**: 089 — Requirement Word Export Templates
**Review status**: Design review complete; implementation must complete verification checklist before merge.
**Decision**: Approved to implement only with the mandatory controls below.

## Scope reviewed

The feature introduces upload, validation, persistence, rendering, and reuse of Microsoft Word `.docx` templates for requirement exports. It affects authenticated browser flows, CLI/MCP export paths, document generation, persistent file storage, audit logging, and role-based access control.

## Key assets

- Requirement content and release snapshots included in generated exports.
- Uploaded template binaries and metadata.
- User identity, role, and export audit history.
- Backend file storage and document processing runtime.
- Generated `.docx` output returned to users.

## Threat model

| Threat | Risk | Required mitigation |
|---|---|---|
| Malicious upload disguised as `.docx` | Remote code execution, parser exploit, malicious content propagation | Verify extension, MIME, ZIP signature, OpenXML content types, and reject macro-enabled or non-Word packages. Use patched Apache POI/XML libraries. |
| ZIP bomb or oversized package | Memory/CPU exhaustion | Enforce max compressed size, max uncompressed size, max entries, max XML part size, max image count/size, stream parsing where possible, and request body limits. |
| Macros, OLE, embedded packages, active content | Malware distribution to export recipients | Reject `vbaProject.bin`, OLE objects, embedded packages, ActiveX, remote templates, external relationships, and unsupported relationship types. |
| SSRF via external relationships or remote images | Backend network access during parsing/rendering | Disable external resource resolution; reject external targets; never fetch remote relationships. |
| Template injection into generated XML | Broken documents, data exfiltration links, stored XSS-like issues in Office | Escape placeholder values, restrict placeholders to allowlist, validate final package relationships, and do not allow arbitrary XML fragments as placeholder values. |
| Unauthorized template management | Official exports modified by untrusted users | Restrict upload/activation/deactivation/download to ADMIN/REQADMIN; enforce server-side checks independent of UI. |
| Unauthorized export data access | Requirement data disclosure | Reuse existing export authorization and scope filtering; template selection must not widen data access. |
| Path traversal or unsafe filenames | File overwrite/read, response header injection | Generate storage keys server-side; sanitize display filename; use safe `Content-Disposition`; never use client filenames as paths. |
| Persistent malicious file storage | Long-lived malware at rest | Store outside web root/object-store private bucket; scan if AV service is available; retain SHA-256; allow retirement/deletion according to policy. |
| Audit gap or repudiation | Cannot prove which template produced an official export | Log upload, validation, activation, deactivation, deletion/retirement, and every export usage with actor, template id/checksum, mode, scope, and timestamp. |
| Information leakage in validation errors | Server path or XML internals exposed | Return normalized validation codes/messages; log technical details server-side only. |
| Concurrency/default race | Wrong latest template used | Resolve template id at export start inside a transaction; record resolved id/checksum in usage event and output metadata. |
| Dependency vulnerabilities | Parser compromise | Pin and scan dependencies; include POI/XML parser CVE review before release. |

## Mandatory security controls

### Upload validation

- Accept only `.docx`, not `.doc`, `.dotm`, `.docm`, `.rtf`, `.html`, or renamed macro-enabled packages.
- Validate:
  - request content length and configured file-size limit;
  - ZIP magic bytes and valid central directory;
  - `[Content_Types].xml` OpenXML Word document type;
  - absence of `word/vbaProject.bin`, ActiveX, OLE, embedded package parts, and external relationship targets;
  - bounded number and size of ZIP entries, XML parts, media files, styles, numbering, headers, and footers;
  - presence of `${requirements}` unless append mode is explicitly selected.
- Compute SHA-256 before persistence and store it in metadata and audit events.

### Rendering isolation

- Render templates in a bounded worker path with configured timeouts and memory-conscious parsing.
- Disable XML external entity resolution and any external resource fetching.
- Treat all placeholder values as plain text and escape them through the document API.
- Re-validate the final generated package relationships before returning the download.

### Authorization and RBAC

- Upload, activate, deactivate, retire, delete, and original-template download: ADMIN/REQADMIN only.
- Export with active templates: roles already allowed to export requirements, without increasing the requirement data scope.
- Server-side role checks are mandatory for every endpoint; frontend role hiding is convenience only.
- MCP/CLI paths must use the same backend checks and must not bypass validation.

### Storage and retention

- Store templates outside the static web root or in a private object-store bucket with generated opaque keys.
- Do not persist ad hoc templates unless explicitly saved by an authorized user.
- If malware scanning infrastructure exists, scan saved templates before activation; otherwise document the residual risk and keep validation strict.
- Hard delete only unused drafts. Retire used templates to preserve export provenance.

### Audit and monitoring

- Audit events must include actor id, actor username/email where available, IP/session/request id where available, template id, checksum, selected mode, export scope, release/use-case/language, outcome, and timestamp.
- Add metrics/alerts for repeated validation failures, oversized uploads, rejected active content, and template render failures.
- Log validation details at a safe level without storing full document content in logs.

## Privacy and data classification

- Templates may contain company logos, classifications, and static introductory text. Treat saved templates as internal business documents.
- Exported documents contain requirement data and potentially release/use-case context. Existing download handling and transport security requirements continue to apply.
- `${exportedBy}` and audit events introduce user identity into documents/logs; this must be intentional and visible in the UI option labels.

## Residual risks

- Office clients may still warn users about content depending on local security policy even after server-side validation.
- Apache POI/OpenXML parsing can still receive malformed edge cases; dependency patching and resource limits are required operational controls.
- Without antivirus scanning, validation reduces but does not eliminate all malware risks in stored templates.

## Security verification checklist

- [ ] Unit tests reject macro-enabled files, OLE/embedded packages, external relationships, missing placeholders, and ZIP bombs.
- [ ] Integration tests prove REQ/REPORT cannot upload, activate, deactivate, delete, or download original templates.
- [ ] Export tests prove template selection does not change requirement scope or release/use-case filters.
- [ ] Audit tests prove upload, validation failure, activation/deactivation, ad hoc export, saved export, and no-template export are logged.
- [ ] Dependency scan confirms document-processing libraries have no known high/critical CVEs at release time.
- [ ] Manual security review verifies response headers, filename sanitization, and no server paths/stack traces in validation errors.

## Go/no-go recommendation

Proceed with implementation only if the validator is built before persistence/rendering and all upload endpoints are protected by ADMIN/REQADMIN. Do not ship ad hoc template upload until resource limits, forbidden relationship detection, and audit events are in place.
