---
name: requirement-export-template
description: >
  Run the company Word template E2E test for requirement exports, covering every
  function the feature adds: the seeded example template, the example download,
  upload and validation (including the macro and missing-insertion-point
  rejections), a release export that renders the release name / version / date /
  status onto the cover page, in-place placement of requirement content between
  the template's front and back matter, the usage audit row, the
  activate/deactivate/delete lifecycle, and the authorization negatives proving a
  plain user reaches none of the write verbs. Starts backend and frontend cold,
  runs the driver, and iteratively fixes failures in both layers. Use this skill
  when the user says "requirement export template", "company template", "test the
  export template", "reqtpl", "does the export use the company design", "check the
  Word template", "test template upload", or similar.
context: fork
---

> **Sync policy (two-way, mandatory)**: This file and
> `.claude/skills/requirement-export-template/SKILL.md` are one skill kept in two
> harness trees — Codex reads this copy, Claude Code reads the other. Whichever
> copy an agent edits, the same change is ported to the other **in the same
> commit**; translate harness-specific mechanics rather than copying verbatim
> (e.g. `sandbox_permissions: "require_escalated"` ↔ Bash tool
> `dangerouslyDisableSandbox: true`). `.claude/skills/` is the tie-breaker
> when the two disagree — that is a conflict rule, not a licence to edit one
> side only. Verify with `./scripts/check-skill-sync.sh` (exit 0) before
> calling the change done. See `CLAUDE.md` §"Tooling Conventions" and
> `AGENTS.md` §Skills.

# Requirement Export Template E2E — Iterative Fix Loop

You are an orchestration agent that brings up a full-stack environment, executes
the company-template E2E driver, and **iteratively fixes every failure** until
the driver passes or you have exhausted the retry budget.

> **Read `../_shared/stack-lifecycle.md` in full before touching the stack.** It
> defines the cold-start sequence, port-bind liveness, credentials, logging and
> the 5-iteration budget this skill assumes.

**This driver is non-destructive.** Everything it creates carries the
`e2e-reqtpl-` prefix and is removed by cleanup, which runs both before the test
(unconditional, so leftovers from a crashed earlier run are cleared) and after
it via `trap EXIT`. It never deletes a template, release or user it did not
create — in particular **it leaves the seeded example template alone**.

## Background

The feature reference is `docs/REQUIREMENT_EXPORT_TEMPLATES.md`. Read it before
diagnosing anything; most failures below are a rule in that document not holding.

The short version: an admin uploads a branded `.docx`, SecMan substitutes
`${...}` placeholders and renders requirement content **where `${requirements}`
sits**, and a shipped example is seeded ACTIVE on first start so a fresh
installation exports in a company design rather than the built-in layout.

## What is under test

| # | Surface | Assertion |
|---|---|---|
| 1 | Seeding | `GET /api/requirement-export-templates/latest` returns 200, not 204 — an ACTIVE template exists, so `templateMode=LATEST` (the export default) resolves |
| 2 | `GET .../example` | 200, a readable OOXML package, carrying `${requirements}` and all four release placeholders |
| 3 | `POST .../validate` | a macro-carrying package is rejected 400; a template with no insertion point is rejected 400 in strict mode and accepted 200 in append mode |
| 4 | `POST /api/requirement-export-templates` | 201, and `/latest` now resolves to the newly activated template |
| 5 | `GET /api/requirements/export/docx?releaseId=…` | the release **name, version, date and status** all appear in the rendered document |
| 6 | Placement | requirement content sits **between** the template's front matter and its back matter, and the `${requirements}` marker is gone |
| 7 | Usage audit | `GET .../{id}/usage` is non-empty and `lastUsedAt` advanced |
| 8 | Lifecycle | deactivate → INACTIVE, activate → ACTIVE, delete → 204 and the row is gone — for a used template too — while its usage row survives with `template_id` NULL |
| 9 | Authorization | a plain USER is denied upload, example download, activate and delete — all 403 |

### The three assertions that matter most

- **Row 6, placement.** Appending requirement content at the end of the document
  passes rows 1–5 and 7–9 unchanged. If the requirement lines are not strictly
  between the front-matter and back-matter lines, in-place insertion is broken
  even though everything else looks green.
- **Row 5, release status and date.** These were the two fields that used to be
  wrong: `releaseVersion` was sliced out of the document title and
  `releaseStatus` was hard-coded to the empty string. A regression here looks
  like "the cover page is blank in one row", which is easy to skim past.
- **Row 9, authorization.** Template CRUD is ADMIN/REQADMIN at the controller.
  The admin page hiding a button is not the control. A 200 here is an A01
  finding — fix it before anything else in the run.

## What is deliberately NOT tested

`GET /api/requirements/export/{docx,xlsx}` are `@Secured(IS_ANONYMOUS)` **by
design**, to power the public `/requirements/download` page. Do not add an
assertion that anonymous access is denied, and do not "fix" it when you notice
it. The exposure is documented and accepted in
`docs/REQUIREMENT_EXPORT_TEMPLATES.md` §6.

## Running it

```bash
pass-cli run --env-file ./secmanpp.env -- \
  ./scripts/test/test-e2e-requirement-export-template.sh --verbose
```

Flags: `--verbose`.

The driver needs `curl`, `jq`, `zip` and `unzip`. It builds its `.docx` fixtures
with `zip` and reads the exported document back with `unzip` + `sed`, so no Word,
POI or document library is required on the runner.

Required credentials (all from `pass-cli`): `SECMAN_ADMIN_NAME`,
`SECMAN_ADMIN_PASS`, `SECMAN_USER_NAME`, `SECMAN_USER_PASS`, and
`BASE_URL`/`SECMAN_BACKEND_URL`. Never a localhost literal.

## Sequence

1. **Cold start.** Follow `../_shared/stack-lifecycle.md` exactly: stop both
   services unconditionally, confirm the ports are free, start both via the
   canonical scripts, wait for port-bind liveness (8080 / 4321).
2. **Run the driver** with `--verbose`.
3. **Classify each failure** using the table below and fix it in source.
4. **Restart the backend** after any Kotlin change (frontend edits hot-reload).
5. **Re-run.** Maximum 5 iterations.

## Error classification

| Symptom | Layer | Likely cause |
|---|---|---|
| Row 1 fails with 204 | backend | `RequirementExportTemplateSeeder` did not run. It seeds only into a *completely empty* table by design — check whether a row already exists before calling it a bug. Otherwise check `.e2e-logs/backend.log` for the swallowed-and-logged seed error, or `SECMAN_REQUIREMENT_TEMPLATE_SEED_EXAMPLE=false` |
| Row 2: example is not a valid ZIP | backend | `ExampleRequirementExportTemplateBuilder.build()` threw or produced a truncated stream; check the POI usage in the builder |
| Row 2: a placeholder is missing | backend | the builder and `ALLOWED_PLACEHOLDERS` have drifted — `ExampleRequirementExportTemplateBuilderTest` should have caught this, so run it too |
| Row 3: macro template accepted | **backend, security** | `isForbiddenEntry` in `RequirementExportTemplateValidationService` stopped matching `vbaProject.bin`. A08 finding, fix first |
| Row 3: append-mode template rejected | backend | `requireRequirementsPlaceholder=false` is not being honoured — check the `@Part` binding on `validate` |
| Row 4: upload returns 400 | backend | read the returned validation report; the driver's fixture is a minimal but valid OOXML package, so a rejection is usually a real over-strict rule |
| Row 5: release name/version missing | backend | `resolveRequirementExportData` is not carrying the `Release` entity through to `buildPlaceholderValues`, or the placeholder key was renamed on one side only |
| Row 5: status or date blank | backend | the regression this feature fixed: `releaseStatus` hard-coded to `""`, or `releaseDate` not falling back to `createdAt` |
| Row 6: content after the back matter | backend | `findRequirementsAnchor` returned null (the marker was split across runs and the join is missing) or `createTemplatedWordDocument` took the append branch |
| Row 6: marker still present | backend | the anchor paragraph was not removed. Check that removal indexes into `document.bodyElements`, **not** `getPosOfParagraph` — the latter counts paragraphs only and deletes the wrong element once the template contains a table |
| Row 6: template front/back matter vanished | backend | `replacePlaceholdersInParagraph` is dropping runs it should keep, or `removeBodyElement` removed the wrong index |
| Row 7: no usage row | backend | `exportTemplateUsageRepository.save` is not reached — usually an early return in `exportWordDocument` |
| Row 8: delete leaves a used template in the list | backend | `RequirementExportTemplateController.delete` is not calling `nullifyTemplateForTemplate` before `deleteById`, so the usage FK blocks the row (or an old retire-instead-of-delete branch is back) |
| Row 8: delete returns 500 for a used template | backend | the usage rows were not detached first — `requirement_export_template_usage.template_id` FK violation |
| Row 8: usage history disappeared with the template | backend | the usage rows were cascade-deleted instead of detached. The audit trail must outlive the template (A09) |
| Row 9: any 200 or 404 instead of 403 | **backend, security** | a missing or widened `@Secured` on `RequirementExportTemplateController`. A01 finding, fix before anything else |
| Frontend page blank at `/admin/requirement-export-templates` | frontend | check the browser console; usually a bad import in `RequirementExportTemplateManagement.tsx` or a missing export in `services/requirementExportTemplates.ts` |

## Reporting

Report per-phase pass/skip/fail counts, then state plainly:

- whether the seeded example was present (row 1), since rows 2–7 assume a
  working template path;
- whether the release under test actually contained requirements — if it did
  not, row 6's placement assertion **skipped** and the run is partial;
- every fix you applied, with the file and the reason.

Never report a pass while any assertion failed, and never report a clean pass
when the placement assertion skipped — call that a partial run.
