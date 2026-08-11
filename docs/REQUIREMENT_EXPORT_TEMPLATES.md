# Company Word templates for requirement exports

Requirement exports are Word documents that leave the organisation — they go to
auditors, suppliers and internal governance boards. They should look like the
company's documents, not like something a tool generated. This is how that works.

An admin uploads a `.docx` built in Word with the company's cover page, fonts,
colours, header and footer. SecMan substitutes a small set of `${...}`
placeholders and renders the requirement body at the point the template marks.
Everything else in the document is left exactly as the author wrote it.

---

## 1. Where templates come from

A template is a `.docx` stored as a row in `requirement_export_template`
(`LONGBLOB` content plus a SHA-256, the validation report, and a status). There
is no template on the filesystem and none is fetched at runtime.

Three ways one gets there:

| Source | Who | Notes |
|---|---|---|
| **Seeded example** | the application, on first start | Installed only into a completely empty table. See §7. |
| **Upload** | ADMIN / REQADMIN | `POST /api/requirement-export-templates`, or the admin page. |
| **Ad hoc** | any exporting user | Attached to a single export, never stored — unless "save as template" is ticked. |

**Start from the example.** `GET /api/requirement-export-templates/example` (or
the *Download example template* button) returns a document that already carries
every placeholder in a working arrangement. Restyle it in Word, keep the
placeholders, upload it back.

## 2. Model

```
requirement_export_template          requirement_export_template_usage
  id                                   id
  name, description, version_label     template_id  (FK, ON DELETE SET NULL)
  status                               template_sha256
  original_filename, content_type      exported_by
  file_size_bytes, sha256              export_scope   ALL|USE_CASE|RELEASE|TRANSLATED|MCP|CLI
  content            LONGBLOB          release_id, usecase_id
  validation_report_json               language
  uploaded_by                          template_mode  LATEST|SAVED|ADHOC|NONE
  created_at, activated_at             created_at
  deactivated_at, last_used_at
```

Statuses: `ACTIVE` (usable), `INACTIVE` (kept, not used), `RETIRED` (was used by
an export, so it is kept for the audit trail instead of being deleted),
`REJECTED`. Only `ACTIVE` templates are ever rendered with.

`template_sha256` is recorded on the *usage* row as well as on the template, so
"which bytes produced this document" survives the template being replaced.

Schema lives in `V231__requirement_export_templates.sql`. This feature adds no
new columns and therefore **no new migration**.

## 3. The placeholder contract

Write these anywhere in the document — body, tables, headers, footers.

| Placeholder | Renders |
|---|---|
| `${requirements}` | **A position, not a value.** See §4. |
| `${documentTitle}` | Title of the exported document |
| `${exportDate}` | `yyyy-MM-dd HH:mm:ss` the export ran |
| `${releaseName}` | `Release.name` |
| `${releaseVersion}` | `Release.version` |
| `${releaseDate}` | `Release.releaseDate`, falling back to `createdAt`, as `yyyy-MM-dd` |
| `${releaseStatus}` | `PREPARATION` \| `ALIGNMENT` \| `ACTIVE` \| `ARCHIVED` |
| `${releaseDescription}` | `Release.description` |
| `${useCaseName}` | Use case the export was narrowed to |
| `${exportedBy}` | Username that triggered the export (`anonymous` on the public page) |
| `${language}` | Export language |
| `${requirementCount}` | Number of requirements in the document |
| `${classification}` | Classification label chosen at export time |

Two rules that are easy to get wrong:

- **Unknown placeholders are left untouched, not blanked.** `${companyLogo}`
  stays in the document as literal text. Validation reports it as a warning so
  you find out at upload time rather than in a distributed PDF.
- **Release placeholders are empty when there is no release.** Exporting live
  requirements (no `releaseId`) is not a frozen collection, so there is nothing
  honest to put in those fields. Design the template so an empty release block
  still reads correctly, or export a release.

The release fields come from the `Release` entity. They previously did not:
`releaseVersion` was sliced out of the document title with
`title.substringAfter("Release ")` and `releaseStatus` was hard-coded to the
empty string, which is why a cover page could never show either reliably.

## 4. Where requirement content lands

`${requirements}` marks an insertion point. Requirement content is rendered
**in place of that paragraph**, so a template can put a cover page and an
introduction before it and an approval block or appendix after it.

Requirements are grouped by chapter, one `Heading1` per chapter, with each
requirement's short text on a shaded header line followed by its details,
motivation, example and norm reference.

**The marker must sit in its own top-level paragraph in the document body.** Not
in a table cell, not in a header or footer — requirement content is inserted as
body-level paragraphs and none of those places can host them. Validation catches
this: an error in strict mode, a warning when append mode was chosen.

**If the template has no `${requirements}`**, requirement content is appended
after a page break at the end of the document. That is what
`missingPlaceholderBehavior=APPEND` allows; the default, `REJECT`, refuses to
store or use such a template so you find out before the document is circulated.

**Above 750 requirements the export also falls back to appending**, and says so
in the log. In-place insertion is quadratic — POI's `insertNewParagraph` walks
every previous sibling to work out where the new element belongs, and a
requirement emits about seven paragraphs — so an unbounded document would hand
an unauthenticated caller of `GET /api/requirements/export/docx` a
CPU-exhaustion primitive. The cap keeps that path bounded; the append path is
O(1) per paragraph and stays available at any size. A real requirements
document is far below the limit.

## 5. Validation, and what is rejected

Every uploaded template — including the shipped example, which is not treated as
more trustworthy than a request — goes through
`RequirementExportTemplateValidationService` before it is stored or rendered
with. An upload is a ZIP that Apache POI will parse, so this runs *before* any
parse.

Rejected:

- Anything not named `.docx`; `.docm` / `.dotm` explicitly (macros in an export
  template would run on every reader's machine)
- A package declaring `macroEnabled` or containing `vbaProject.bin`,
  `/activex/`, `/embeddings/`, or an OLE object
- A relationship with an external target — remote images, remote attached
  templates, anything that phones out when the document is opened. This one is a
  real control, not hygiene: an external `attachedTemplate` is fetched by the
  *recipient's* Word, so a UNC target leaks NetNTLM and a remote `.dotm` carries
  macros, and these documents go to auditors and suppliers. The attribute is
  matched with whitespace tolerance and after decoding character references
  (`TargetMode = "External"` and `TargetMode="&#69;xternal"` are both legal XML
  that Word resolves to External), and a `TargetMode` carrying any character
  reference is rejected outright
- A `${requirements}` marker split across two paragraphs — the renderer joins
  runs within one paragraph, so it would never find it, and the export would
  silently append instead of rendering at the marker
- ZIP entries containing `../` or starting with `/` (zip slip)
- More than `max-zip-entries` entries, or more than
  `max-uncompressed-size-bytes` inflated (zip bomb) — both checked while
  streaming, so a bomb is never fully expanded
- Files over `max-file-size-bytes`, empty files, and anything that is not a ZIP
- A missing `[Content_Types].xml`, a missing `word/document.xml`, or a package
  that does not declare the Word main-document content type
- A missing or unusable `${requirements}` insertion point, in strict mode

Warnings (stored, not blocking): unsupported placeholders, and a
`${requirements}` marker in an unusable position when append mode is chosen.

The report is stored on the row as `validation_report_json`, so you can see
later what a template was accepted on.

## 6. Access control

| Endpoint | Role |
|---|---|
| `GET /api/requirement-export-templates` | authenticated |
| `GET /api/requirement-export-templates/latest` | authenticated |
| `GET /api/requirement-export-templates/example` | ADMIN, REQADMIN |
| `POST /api/requirement-export-templates` | ADMIN, REQADMIN |
| `POST /api/requirement-export-templates/validate` | ADMIN, REQADMIN |
| `GET /api/requirement-export-templates/{id}` | ADMIN, REQADMIN |
| `GET /api/requirement-export-templates/{id}/download` | ADMIN, REQADMIN |
| `POST /api/requirement-export-templates/{id}/{activate,deactivate}` | ADMIN, REQADMIN |
| `DELETE /api/requirement-export-templates/{id}` | ADMIN, REQADMIN |
| `GET /api/requirement-export-templates/{id}/usage` | ADMIN, REQADMIN |

The admin page hides what a user cannot do, but the `@Secured` annotation on the
controller is the boundary; the UI check is UX.

### Accepted exposure: anonymous exports

`GET /api/requirements/export/docx` and `.../xlsx` are `@Secured(IS_ANONYMOUS)`
by design — they power the unauthenticated `/requirements/download` page. This
is a deliberate, reviewed decision, not an oversight, and it has consequences
worth stating plainly:

- An anonymous caller can export using `templateMode=LATEST`, so **the active
  company template's design is publicly reachable**. Do not put anything in a
  template that is not safe to disclose.
- An anonymous caller supplies `classification` freely. The value is sanitised
  (control characters stripped, length bounded) before it reaches the document
  or a log line, but it is not authorised — the label on a publicly exported
  document means nothing.
- Every export writes a `requirement_export_template_usage` row, including
  anonymous ones, so **that table grows with unauthenticated traffic**. It has
  no retention policy today. Watch it, or put the public page behind a rate
  limit at the edge.

To close this, restrict `templateMode` to authenticated callers on those two
endpoints. That was considered and deliberately not done.

## 7. Seeding and replacing the example

`RequirementExportTemplateSeeder` runs on `ApplicationStartupEvent` and installs
the shipped example as `ACTIVE`, uploaded by `system`.

It is deliberately conservative:

- **It seeds only into a completely empty table.** Not "when no ACTIVE template
  exists" — that would resurrect the example on every restart after an admin
  deliberately retired it.
- The shipped bytes are validated through the normal path. A build that ships a
  broken artefact says so in the log at startup rather than at the first export.
- Any failure is logged and swallowed. A missing company template degrades an
  export to the built-in layout; a failed boot takes the application down.

Turn it off with `SECMAN_REQUIREMENT_TEMPLATE_SEED_EXAMPLE=false`.

### Regenerating the example

The example is generated by `ExampleRequirementExportTemplateBuilder`, which is
the single source of truth. To change it, edit the builder — never a binary.

```bash
./gradlew :backendng:generateExampleRequirementTemplate
```

This writes `src/backendng/src/main/resources/templates/secman-company-requirements-template.docx`.

Committing that artefact is **optional**. When it is absent, the builder is
called at runtime and produces the same document, which is what keeps the two
from drifting. Commit it when you want a file to hand to a design team; keep it
out of the tree when you would rather not review an opaque binary.

## 8. Configuration

| Key | Env | Default |
|---|---|---|
| `secman.requirement-export-templates.max-file-size-bytes` | `SECMAN_REQUIREMENT_TEMPLATE_MAX_UPLOAD_BYTES` | `5242880` (5 MiB) |
| `secman.requirement-export-templates.max-uncompressed-size-bytes` | `SECMAN_REQUIREMENT_TEMPLATE_MAX_UNCOMPRESSED_BYTES` | `20971520` (20 MiB) |
| `secman.requirement-export-templates.max-zip-entries` | `SECMAN_REQUIREMENT_TEMPLATE_MAX_ZIP_ENTRIES` | `512` |
| `secman.requirement-export-templates.seed-example` | `SECMAN_REQUIREMENT_TEMPLATE_SEED_EXAMPLE` | `true` |

The three caps are decompression-bomb guards, not tuning knobs. They sit well
below `micronaut.server.multipart.max-file-size`, which stays large for the
XLSX importers.

## 9. Where it shows up

- **`/admin/requirement-export-templates`** — upload, validate, activate,
  deactivate, download, delete, usage history, and the example download.
- **`/export`** — the export page picks the template mode per export:
  `LATEST` (default), `SAVED` (a specific one), `ADHOC` (attach a file), `NONE`
  (built-in layout).
- **Release detail** — the Word export button sends `templateMode=LATEST` with
  `missingPlaceholderBehavior=APPEND`, so a release export always carries the
  company design and never 400s on a template without an insertion point.

Excel exports have no template concept.

Three other code paths build Word documents and are **not** template-aware:
`PublicRequirementDownloadController` (`/api/reqdl`), the MCP
`export_requirements` tool, and the translated-export builders when
`templateMode=NONE`. That is pre-existing and unchanged here.

## 10. Testing

```bash
# Unit: validation rules, the example builder, placeholder binding and placement
./gradlew :backendng:test --tests "*RequirementExportTemplate*"
./gradlew :backendng:test --tests "*ExampleRequirementExportTemplateBuilderTest*"
./gradlew :backendng:test --tests "*RequirementControllerWordExportTest*"

# Frontend logic tier
cd src/frontend && npm test

# End to end (needs a running stack; credentials from pass-cli)
pass-cli run --env-file ./secmanpp.env -- \
    ./scripts/test/test-e2e-requirement-export-template.sh --verbose
```

Or run the E2E driver through its skill: **`/requirement-export-template`**,
which cold-starts backend and frontend and iterates on failures.

The E2E driver builds its `.docx` fixtures with `zip` and reads the exported
document back with `unzip` + `sed`, so the runner needs no Word, no POI and no
document library.
