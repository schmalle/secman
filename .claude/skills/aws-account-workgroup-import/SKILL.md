---
name: aws-account-workgroup-import
description: >
  Run the end-to-end test for importing AWS account mappings and linking those
  accounts to workgroups by display name. Seeds a plain viewer user, a non-admin
  user and an asset carrying a cloud account id; imports accounts with display
  names through the CLI (Cloud Custodian JSON and CSV), REST (`/api/user-mappings/bulk`,
  the CSV upload and the XLSX upload) and MCP `import_user_mappings`; and asserts
  that an account named `DevOps-x` lands in the workgroup `aws-DevOps-x`, that the
  workgroup is created when missing, that the correction path links from stored
  names with no file and is idempotent, that all surfaces report the same summary,
  that a member of the workgroup can then see the account's assets and loses that
  access again when the account is unlinked, that the XLSX path deliberately links
  nothing, that every workgroup MCP tool is both listed and callable, that a
  renamed account keeps its old link, and that non-admins are refused on every
  surface. Cleans up before and after, including leftovers from earlier runs. Use
  this skill when the user says "aws account import test", "test aws account
  import", "workgroup linking test", "link workgroups e2e", "test adding aws
  accounts to workgroups", "does the import link workgroups", "display name
  workgroup", "aws-account-workgroup-import", or similar.
context: fork
---

> **Sync policy (two-way, mandatory)**: This file and
> `.agents/skills/aws-account-workgroup-import/SKILL.md` are one skill kept in two harness
> trees — Claude Code reads this copy, Codex reads the other. Whichever copy
> an agent edits, the same change is ported to the other **in the same
> commit**; translate harness-specific mechanics rather than copying verbatim
> (e.g. Bash tool `dangerouslyDisableSandbox: true` ↔ `sandbox_permissions:
> "require_escalated"`). `.claude/skills/` is the tie-breaker when the two
> disagree — that is a conflict rule, not a licence to edit one side only.
> Verify with `./scripts/check-skill-sync.sh` (exit 0) before calling the
> change done. See `CLAUDE.md` §"Tooling Conventions" and `AGENTS.md` §Skills.

# AWS Account Import -> Workgroup Linking E2E — Iterative Fix Loop

You are an orchestration agent that brings up a full-stack environment, executes
the AWS account import and workgroup-linking driver, and **iteratively fixes every
failure** until the driver passes or you have exhausted the retry budget.

> **Read `../_shared/stack-lifecycle.md` in full before touching the stack.** It
> defines the cold-start sequence, port-bind liveness, credentials, logging and
> the 5-iteration budget this skill assumes.

**This driver is non-destructive.** Everything it creates carries the
`e2e-awswg-` prefix (users, mappings, the probe asset) or the `aws-E2eAwswg`
workgroup-name prefix, and cleanup runs both before the test (unconditional, so a
crashed earlier run is cleared) and after it via `trap EXIT`. It never deletes a
workgroup, mapping, asset or user it did not create.

## Background

An AWS account whose `display_name` is `DevOps-x` belongs to the workgroup
`aws-DevOps-x`, which is created when missing. That is **an authorization
change, not bookkeeping**: linking an account to a workgroup grants every member
of that workgroup access to the account's assets — unified asset access
**rule #9**. The contract lives in `docs/AWS_ACCOUNT_WORKGROUP_LINKING.md` and the
single implementation is `WorkgroupAccountLinkService`.

The unit tier is already strong here and this driver deliberately does not repeat
it. `WorkgroupAccountLinkServiceTest` alone has 17 tests covering the naming rule,
both race variants, dry-run, renamed accounts and every error row — but every one
of them mocks the repositories. What no test below the HTTP boundary can prove is
that a link actually reaches the native rule-#9 clause in `AssetRepository`, and
CLAUDE.md is explicit that SQL pre-filters are perf hints, **never** the auth
boundary. Phase 10 is the only test in the repo that drives that path.

## What is under test

| # | Surface | Assertion |
|---|---|---|
| 1 | Shipped fixtures, dry run | `testdata/user-mappings/accounts-with-display-name.json` and `…/mappings-with-display-name.csv` still parse and still produce the documented summary — 4 pairs, 3 linkable, `Data_Platform.01` an error — and persist nothing |
| 2 | CLI `import` (Cloud Custodian JSON) | mappings persist, `aws_account_name` is stored, workgroups are created, two accounts sharing one display name land in one workgroup |
| 3 | CLI `import` (CSV `email,type,value,display_name`) | links the same way; a `DOMAIN` row with a blank display name is not a candidate |
| 4 | `POST /api/user-mappings/bulk` | links via `mappings[].displayName`; a payload with no display name reports **no** linking at all |
| 5 | `POST /api/import/upload-user-mappings-csv` | the **other** dialect (`account_id,owner_email[,domain][,display_name]`) links equivalently, and the CLI dialect is rejected with a missing-column error |
| 6 | `POST /api/import/upload-user-mappings` (XLSX) | the orphaned fixture suite: valid/mixed/empty/invalid counts, `wrong-format.txt` and `missing-columns.xlsx` rejected with 400 — and this path links **nothing**, deliberately |
| 7 | MCP `import_user_mappings` | links over the streamable HTTP transport |
| 8 | Cross-surface | re-importing an already-linked pair yields the identical summary on CLI, REST and MCP |
| 9 | Correction path | CLI `link-workgroups` (and the REST/MCP equivalents) links from stored names with **no file**; a dry run changes nothing; a second run is all `alreadyLinked` and still exits 0 |
| 10 | **Rule #9** | a plain USER sees the account's asset only once the account is linked *and* they are a member |
| 11 | Revocation | `DELETE /api/workgroups/{id}/aws-accounts/{awsAccountId}` makes it invisible again; a repeat returns 404 |
| 12 | Round trip | `GET`/`POST`/`DELETE .../aws-accounts` plus the MCP trio; duplicate add → 409, non-12-digit → 400 |
| 13 | Authorization | non-admin refused on `/bulk`, `/link-workgroup-accounts` and the CSV upload; MCP without delegation refused; MCP non-admin delegate refused; and `canBindAccount` — workgroup membership alone does not let a member bind an account they cannot otherwise reach |
| 14 | MCP callability | every workgroup/mapping tool is in `tools/list` **and actually executes** |
| 15 | Renamed account | the new link is added and the **old one is left in place** |
| 16 | Error rows | illegal display name, non-12-digit id, >255-char name (dropped, never truncated), blank name |

### The three assertions that matter most

- **Rule #9 (row 10).** The probe is built so that no other access rule can
  reach the asset: the viewer is a plain USER (no ADMIN/SECCHAMPION
  short-circuit), has no `UserMapping` for the account (#5) and no
  `AwsAccountSharing` (#7), the asset's owner is a stub string rather than the
  viewer's username (#8), and the asset is in no workgroup directly (#2). If it
  becomes visible, rule #9 is the only thing that could have done it. **If this
  assertion has never been seen to fail, it is not yet evidence** — break the
  `workgroup_aws_account` clause in `AssetRepository.findAccessibleAssets` once
  and confirm it goes red.
- **Revocation (row 11).** Same user, same membership; only the account link
  changes. That is what isolates rule #9 from every other path — a visibility
  test that only ever asserts the positive can pass against a filter that returns
  everything.
- **MCP callability (row 14).** A tool in `McpToolPermissions.LISTING` but
  missing from `.CALLING` is listed and then silently denied. Six workgroup tools
  shipped in exactly that state. `tools/list` cannot see it; only calling can.

## Two CSV dialects, and why row 5 exists

They are genuinely different and are easy to confuse:

| Surface | Header |
|---|---|
| CLI `manage-user-mappings import` | `email,type,value,display_name` |
| `POST /api/import/upload-user-mappings-csv` | `account_id,owner_email[,domain][,display_name]` |

`CSVUserMappingParser.REQUIRED_HEADERS` is the authority for the second. Row 5
feeds each dialect to the wrong endpoint on purpose: the failure mode being
guarded against is a file that imports zero rows while reporting success.

Note that `docs/CLI.md` also documents a third shape (`email,awsAccountId,domain`)
in its "Import file formats" block. If a phase-3 or phase-5 assertion fails on the
header, check which dialect the code actually accepts before editing the driver —
the doc may be the thing that is wrong.

## Running it

```bash
pass-cli run --env-file ./secmanpp.env -- \
  ./scripts/test/test-e2e-aws-account-workgroup-import.sh --verbose
```

Required env (all via `pass-cli`): `SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS`,
`SECMAN_ADMIN_EMAIL`, `SECMAN_MCP_KEY`, and `BASE_URL` / `SECMAN_BACKEND_URL`.
Never hardcode `localhost:8080` or `localhost:4321`.

Flags: `--skip-cli`, `--skip-mcp`, `--skip-rest`, `--with-supplementary`,
`--verbose`.

The driver needs the CLI jar. Build it once if missing — the driver will do it
itself, but doing it up front makes the first run faster:

```bash
./gradlew :cli:shadowJar
```

Success is `Failed: 0` in the summary block. `[WARN]` lines are not failures.

### `--with-supplementary`

Additionally runs two drivers that cover adjacent ground and that **no other
skill invokes**, so they otherwise never execute:

- `tests/bulk-user-mapping-test.sh` — `/api/user-mappings/bulk` dry-run, create,
  verify and validation-error, without display names.
- `tests/mcp-e2e-workgroup-test.sh` — workgroup ↔ **asset** access, the sibling
  of rule #9 that this driver does not cover.

`tests/s3-user-mapping-import-e2e-test.sh` is deliberately **not** included: it
needs the `secman-s3` vault and a reachable bucket. Say so when reporting rather
than letting its absence read as coverage.

## Sequence

1. **Cold start.** Follow `../_shared/stack-lifecycle.md` exactly: stop both
   services unconditionally, confirm the ports are free, start both via the
   canonical scripts, wait for port-bind liveness (8080 / 4321).
2. **Build the CLI jar** if `src/cli/build/libs/cli-0.1.0-all.jar` is missing.
3. **Run the driver** with `--verbose`, teeing to `.e2e-logs/e2e-awswg-run-<N>.log`.
4. **Classify each failure** using the table below and fix it in source.
5. **Restart the backend** after any Kotlin change (frontend edits hot-reload).
6. **Re-run.** Maximum 5 iterations.

## Error classification

| Symptom | Layer | Likely cause |
|---|---|---|
| `workgroup 'aws-X' was not created` | backend | `WorkgroupAccountLinkService.linkOne` — the `findByNameIgnoreCase` lookup or the `createWorkgroup` fallback; check the audit log for `CREATE_WORKGROUP_FOR_AWS_ACCOUNT` |
| Workgroup exists but the account is not in it | backend | linking ran before the mappings committed, or `workgroupAwsAccountService.add` swallowed the write — linking must happen **after** the commit |
| `aws_account_name persisted` fails | backend | `UserMappingRepository.updateAwsAccountName`, or the display name never reached the entity; remember it is deliberately **not** part of `uk_user_mapping_composite` |
| Only the CLI phases fail | CLI | `UserMappingCliService.parseWorkgroupLinks` / `WorkgroupLinkPrinter` — the driver reads the printer's counter lines, and the printer omits any line that is zero and swaps the verb in a dry run |
| `REST CSV rejects the CLI dialect` fails | backend | `CSVUserMappingParser.validateHeaders` stopped enforcing `REQUIRED_HEADERS` |
| XLSX path reports workgroup linking | backend | someone wired linking into `UserMappingImportService.importFromExcel`; that path has no `display_name` column and must stay unlinked |
| **Asset visible before the link** | **backend, security** | another access rule is granting it — check owner, workgroup assignment, a `UserMapping` for the viewer's email, or an `AwsAccountSharing`. A filter that returns everything looks identical to a working rule #9 |
| **Asset invisible after the link** | backend | the rule-#9 clause in `AssetRepository.findAccessibleAssets`, or `AssetFilterService`'s multi-query fallback when `secman.memory.lazy-loading` is on — both paths must agree |
| Asset still visible after unlinking | **backend, security** | a cached accessible-asset set is not invalidated; an A01 finding, fix before anything else |
| MCP tool listed but not callable | backend | missing entry in `McpToolPermissions.CALLING` — **separate from the `LISTING` map**, and a missing entry denies silently |
| `DELEGATION_REQUIRED` where admin expected | config | `X-MCP-User-Email` is missing or the key lacks the permission; the header identifies, it never authenticates |
| `ADMIN_REQUIRED` on every MCP call | config | `SECMAN_MCP_KEY` needs `WORKGROUPS_WRITE` and User Delegation enabled |
| Renaming removed the old assignment | backend | `linkFromStoredMappings` is revoking under the previous name; removing a link revokes a workgroup's access and a correction run must never do that unasked |
| A `>255-char` name was truncated, not dropped | backend | `CSVUserMappingParser` / the bulk path — a truncated name resolves to a different, wrong workgroup |
| Everything fails at login | environment | admin credentials out of step with this instance; see `../_shared/stack-lifecycle.md` §5 |

## Reporting

Report a table of phase, assertion, result, and for anything still failing the
file and the reason. Then state plainly:

- whether the CLI, REST and MCP phases each **ran** or were skipped — a skipped
  surface is a gap, not a pass;
- whether row 10 (rule #9) passed, and whether you confirmed it can fail;
- whether `--with-supplementary` ran, and that the S3 driver was not covered;
- every fix you applied, with the file and the reason.

Never report a pass while any assertion failed, and never let a skipped surface
read as a clean run.
