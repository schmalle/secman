# Downloading a standard over plain HTTP GET

A security standard exists to be read by people who do not have a SecMan
account — suppliers, auditors, project teams, a colleague following a link in a
ticket. So the requirement export endpoints answer an unauthenticated `GET`.
No token, no cookie, no API key.

This page is the contract for scripting those downloads.

---

## 1. The short version

```bash
# The latest released version of the IT/OT Security standard, as Word
curl -OJ "https://secman.example.net/api/requirements/export/docx?standard=IT%2FOT%20Security&release=latest"

# The same thing as Excel
curl -OJ "https://secman.example.net/api/requirements/export/xlsx?standard=IT%2FOT%20Security&release=latest"
```

`-O` saves to a file, `-J` takes the filename from the `Content-Disposition`
header the server sends (e.g. `requirements_ITOT_Security_v98.739714.0_20260816.docx`).

Replace the host with your own — the SecMan host is not hardcoded anywhere in
this repo, and in this project's own scripts it comes from `pass-cli` as
`SECMAN_HOST` (see `docs/PASS_CLI.md`).

## 2. The endpoints

| Endpoint | Returns |
|---|---|
| `GET /api/requirements/export/docx` | Word document |
| `GET /api/requirements/export/xlsx` | Excel workbook |
| `GET /api/standards/public` | `[{id, name}]` — the standards you may ask for |
| `GET /api/releases` | every release, with `version` and `status` |
| `GET /api/usecases` | use cases, for the narrower per-use-case export |

All of them are `@Secured(SecurityRule.IS_ANONYMOUS)`. Everything else about
standards — descriptions, which use cases a standard maps to, timestamps —
requires a login (`GET /api/standards`).

## 3. Parameters

All four are optional and all four apply to both `docx` and `xlsx`.

| Parameter | Value | Meaning |
|---|---|---|
| `standard` | exact standard name, case-insensitive | Narrow to one standard |
| `standardId` | numeric id from `/api/standards/public` | Same, by id. **Wins** if both are given |
| `release` | `latest`, or an exact release version | Freeze to a release |
| `releaseId` | numeric release id | Same, by id |

With **no parameters at all** you get every requirement, live — the behaviour
that predates this feature and that the download page's *Complete Requirement
Set* button still uses.

### What `standard=` selects

A standard is a named set of **use cases**; a requirement belongs to a standard
when it is tagged with at least one of them. So `standard=IT/OT Security`
returns every requirement carrying any use case that standard maps to,
de-duplicated and ordered by chapter.

A standard with no use cases mapped selects nothing. You get `200` with
`{"message": "No requirements found for this standard"}` — deliberately not the
full corpus, so a half-configured standard cannot publish everything by
accident.

### What `release=latest` selects

The release whose status is `ACTIVE`. Exactly one release is ACTIVE at a time,
and creating a release freezes every requirement into a snapshot, so `latest`
means *the version currently in force* and the bytes you get for a given release
do not change as requirements are edited afterwards.

If **no** release is ACTIVE, `release=latest` returns `404 {"error": "No active
release"}`. It does not quietly fall back to the live requirement set: "the
latest release" and "whatever is in the editor right now" are different answers
and a download should not blur them.

Omit `release` entirely to get the live set on purpose.

## 4. Encoding the name

`IT/OT Security` contains a slash and a space, which is why the standard is a
**query parameter** and not a path segment. Encode it:

| Character | Encoded |
|---|---|
| `/` | `%2F` |
| space | `%20` or `+` (both are accepted) |

```bash
# All three of these request the same standard
...?standard=IT%2FOT%20Security
...?standard=IT%2FOT+Security
...?standard=it%2Fot+security          # matching ignores case
```

If you would rather not encode anything, look the id up once and use it:

```bash
curl -s "https://secman.example.net/api/standards/public"
# [{"id":1,"name":"IT/OT Security"},{"id":2,"name":"Test"}]

curl -OJ "https://secman.example.net/api/requirements/export/docx?standardId=1&release=latest"
```

## 5. Responses

| Status | When |
|---|---|
| `200` + `.docx`/`.xlsx` body | Normal. Filename is in `Content-Disposition` |
| `200` + `{"message": "No requirements found for this standard"}` | The standard matched but covers no requirements |
| `400` | `releaseId` and `release` identify **different** releases |
| `404` | Unknown standard, unknown release, or `release=latest` with none ACTIVE |

`400` and `404` carry a short generic `{"error": "..."}`; the specifics stay in
the server log.

## 6. Worked examples

```bash
HOST="https://secman.example.net"

# Latest released IT/OT Security standard, Word
curl -OJ "$HOST/api/requirements/export/docx?standard=IT%2FOT%20Security&release=latest"

# A specific historical release of the same standard
curl -OJ "$HOST/api/requirements/export/docx?standard=IT%2FOT%20Security&release=98.739714.0"

# The same standard as it stands right now, unfrozen
curl -OJ "$HOST/api/requirements/export/docx?standard=IT%2FOT%20Security"

# Every requirement in the active release, no standard filter
curl -OJ "$HOST/api/requirements/export/xlsx?release=latest"

# Narrow by use case instead of standard (use case stays a path segment)
curl -OJ "$HOST/api/requirements/export/docx/usecase/1?releaseId=42"

# Check what is available first
curl -s "$HOST/api/standards/public" | jq
curl -s "$HOST/api/releases" | jq '.[] | {version, status}'
```

Pinning a report to a fixed release is the `release=<version>` form; a dashboard
that should always show current policy is the `release=latest` form.

## 7. What the Word export looks like

Word exports render through the active company template when one is installed,
so a standard downloaded this way carries the company cover page, and the
release metadata placeholders (`${releaseName}`, `${releaseVersion}`,
`${releaseDate}`, `${releaseStatus}`) resolve from the release you selected.
See `docs/REQUIREMENT_EXPORT_TEMPLATES.md`. A live export (no `release`) leaves
those placeholders empty.

## 8. Why this is public, and what is not

Requirement *content* has been anonymously downloadable since the
`/requirements/download` page existed — that page is reachable logged out and is
the intended way for a supplier to obtain the standard they must comply with.
This feature adds the standard as a filter over the same data plus the standard
*names* needed to pick one. It does not widen what a caller can read.

Still behind authentication:

- `GET /api/standards` — descriptions, use-case mappings, timestamps
- everything that writes: creating, editing or deleting standards, use cases,
  requirements and releases
- every asset, vulnerability and assessment endpoint

**Not rate limited.** These endpoints generate documents on demand, which costs
CPU and memory, and an unauthenticated caller can loop them. That was true of
the existing public exports before this feature and remains a deployment-level
concern: put a rate limit or a cache in front of `/api/requirements/export/*` at
the reverse proxy if the instance is internet-facing.

## 9. Where this is implemented

| Piece | File |
|---|---|
| Endpoints | `RequirementController.exportToDocx` / `.exportToExcel` |
| Parameter resolution, filtering, filenames | `StandardExportScopeService` |
| Release snapshot filtering | `ReleaseRequirementScopeService` |
| Public standard list | `StandardController.listPublicStandards` |
| UI card + link building | `RequirementDownload.tsx`, `requirementDownloadUrl.ts` |
| Tests | `StandardExportScopeServiceTest`, `PublicStandardExportIntegrationTest`, `requirementDownloadUrl.test.ts` |
