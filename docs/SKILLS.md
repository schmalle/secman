# Which secman skill to use when

Twenty-two skills live in `.claude/skills/` (Claude Code) and again in
`.agents/skills/` (Codex). The two trees are one skill set: an edit to either
side must be ported to the other in the same commit — see `CLAUDE.md`
§"Tooling Conventions" and verify with `./scripts/check-skill-sync.sh`.
They overlap enough that picking the wrong one wastes a run — and in three cases
the wrong pick **destroys data**. This is the routing guide.

Invoke a skill as `/<name>`, or just describe the task; the `description:` in each
skill's frontmatter decides whether it triggers.

Codex has no slash commands, so `AGENTS.md` §Skills carries the condensed version
of this table plus the instruction to read `.agents/skills/<name>/SKILL.md` in
full. Keep the two in step when a skill is added, removed, or changes what it
writes.

---

## Start here

**Am I about to change data, or just look at it?**

| I want to… | Skill | Writes data? |
|---|---|---|
| Write code that satisfies the OWASP Top 10 by construction | `/secure-code` | No |
| Finish a change and check it before merging | `/finalizer` | Docs only |
| Run the unit/integration tests, or ask where coverage is thin | `/testsuite` | No |
| Verify the shared GitHub/Visual/Web checker result contract | `/integration-contract-test` | No |
| Improve code clarity and report risky renames | `/humanizer` | No |
| Find hot paths and repeated code | `/optimizer` | No |
| Prove no page throws JS errors | `/e2ejs` | No |
| Exercise the full exception lifecycle (MCP + UI) | `/e2evulnexception` | Disposable test DB only |
| Quick MCP-only exception smoke test | `/e2eexception` | Disposable test DB only |
| Test the end-of-life feature end to end | `/e2eeol` | Disposable test DB only |
| Test the admin add-system → user-visibility flow | `/admin-asset-e2e` | Disposable test DB only |
| Run and debug the CrowdStrike import | `/importtest` | Imports into disposable test DB only |
| Compare SecMan against Falcon without changing anything | `/crowdstrike-vuln-match` | No |
| Check a new AWS account starts a correctly scoped assessment | `/aws-account-risk-assessment` | Disposable test DB only |
| Check AWS owner notification reaches the test SMTP sink | `/aws-account-owner-email` | Disposable testbed, loopback mail sink; real inbox delivery requires a separate opt-in check |
| Import AWS display names and link matching workgroups | `/aws-account-workgroup-import` | Disposable test DB only |
| Test welcome mail + the guided questionnaire that scopes an assessment | `/account-onboarding` | Disposable test DB only |
| Test the full MCP assessment lifecycle with a manually supplied respondent email | `/mcp-risk-assessment-lifecycle` | Disposable test DB only |
| Audit retained/interrupted MCP assessment lifecycle data | `/cleanup-mcp-risk-assessment-lifecycle` | Removes verified runner-owned databases only |
| Test requirement/use-case create, list, assign, delete, and list-again through MCP | `/mcp-requirement-use-case-lifecycle` | Disposable test DB only |
| Test requirement export templates end to end | `/requirement-export-template` | Disposable test DB only |
| Get a fixture to click around in | `/createtestdata` | Manual fixture with explicit manifest cleanup |

**Database-mutating skills use the isolated runner.** It creates a test-only
schema and stack; direct test-driver calls are refused. Unverified old fixtures
in the current database are reported, never deleted by prefix alone.

---

## The two mandatory gates

CLAUDE.md Hard Principle 7 makes these non-negotiable after any change under
`src/`, `tests/`, or `scripts/`:

1. **`/e2ejs`** — 0 JS errors, for both the admin and normal-user runs.
2. **`/e2evulnexception`** — full vuln + exception lifecycle, 0 failures.

Doc-only edits outside those directories may skip both, but say so explicitly
rather than staying silent.

`./gradlew build` clean and a clean `./scripts/startbackenddev.sh` startup are
separate requirements (Hard Principle 5) — no skill runs them for you.
Compile-clean is not runtime-clean: Micronaut bean wiring, Flyway and the
SessionFactory only fail at startup.

`/testsuite` runs the test half of that (`:backendng:test`, `:cli:test`, and the
frontend `npm ci && npm test && npm run build` gate) but it deliberately never
starts the stack, so it does not discharge either Hard Principle 5 or the two
gates above. It is the cheap check you run *first*, not a substitute for them.

`/secure-code` covers a third, orthogonal requirement: Hard Principle 1 and the
binding OWASP checklist. It is the only skill meant to run **before** the code
exists rather than after, and it is the only one whose output is a decision
about what to write. It does not discharge the gates above either.

---

## Disambiguation: the pairs that get confused

### `/e2eexception` vs `/e2evulnexception`

These sound identical and are not. The names are a historical accident.

| | `/e2eexception` | `/e2evulnexception` |
|---|---|---|
| Surface | MCP only | MCP **and** Web UI (Playwright) |
| Lifecycle | Create → approve | Create → approve, **reject, cancel** |
| Subject × scope matrix | No (one CVE × ASSET case) | Yes — 28 fixtures across 3 subjects × 5 scopes, incl. `OS` |
| Authorization negatives | No | Yes |
| Patch-notification path | No | Yes (Phase 8b) |
| Is a mandatory gate | No | **Yes** |
| Runtime | Shorter | Long |

**Default to `/e2evulnexception`.** Reach for `/e2eexception` only when you
explicitly want the fast MCP-only smoke test and accept that it covers none of
the above. If you are unsure, the broader one is the right answer — the narrow
one will report a confident pass while testing a fraction of the behaviour.

### `/importtest` vs `/crowdstrike-vuln-match`

Both involve CrowdStrike. Only one writes.

- **`/importtest`** runs the real ingestion (`secman query servers --save`) and
  iteratively fixes backend bugs it surfaces. It **imports live vulnerability
  data** and its delete-insert reconcile removes stale rows. Use it when the
  import itself is broken.
- **`/crowdstrike-vuln-match`** samples assets and compares stored rows against a
  fresh ad-hoc Falcon query **without `--save`**. Nothing is written. Use it when
  you suspect SecMan's data has diverged from Falcon and want to know by how much.

If the question is *"is our data right?"* use the matcher. If the question is
*"why does importing fail?"* use importtest.

### `/account-onboarding` vs `/aws-account-risk-assessment`

Both start from the same event — a mapping import introduces an AWS account SecMan
has never seen — and diverge on who decides what the assessment covers.

| | `/aws-account-risk-assessment` | `/account-onboarding` |
|---|---|---|
| Question | Does `--start-risk-assessment` create a correctly scoped assessment? | Do the three onboarding modes work, and does the *owner's* answer decide the scope? |
| Use case | One, named on the command line | Resolved from the owner's answers, possibly several unioned |
| Covers the welcome mail | No | Yes |
| Covers the public questionnaire | No | Yes — token, masking, replay, rate limiting |
| Covers `--start-risk-assessment` | Everything | Only that it still behaves **exactly** as before |

That last row is the point of overlap: `/account-onboarding` includes one phase
whose whole job is to prove the legacy flag was not changed by the new modes. If
that phase fails, stop — it is a backward-compatibility regression affecting the
`extensions/` clients, which nothing in this build compiles against.

### `/aws-account-risk-assessment` vs `/aws-account-owner-email`

Both import a never-before-seen AWS account and watch what the system does next.
They assert on opposite halves of it, and neither covers the other's half.

| | `/aws-account-risk-assessment` | `/aws-account-owner-email` |
|---|---|---|
| Question | Is the assessment created and scoped right? | Did the application send the owner notice to the local sink? |
| Asserts | Release pinning, questionnaire contents, no drift on later imports, idempotency, validation negatives | The `EmailService` INFO send line, per-import log window, recipient and account on the same line |
| Asserts about mail | **Nothing** | Local SMTP acceptance, not inbox delivery |
| Asserts about the questionnaire | Everything | **Nothing** |
| Sends real mail | No; the runner uses a loopback sink | No; the runner uses a loopback sink |
| Needs SMTP configured | No | The runner seeds local SMTP configuration |
| Needs a human | No | No for the routine sink test |
| Touches the ACTIVE release | Activates its own only in the disposable database | Reuses or seeds a release only in the disposable database |

**If mail is the thing you changed, the assessment skill will pass regardless** —
it never looks at the mail path, and that path swallows its own failures.

The release lifecycle remains terminal (`ARCHIVED` cannot become `ACTIVE`),
but these tests exercise it only inside their disposable database.

### `/createtestdata` vs the E2E skills

`/createtestdata` is not a test. It seeds one fixture — a user, a system, a
vulnerability, and a `PENDING` exception request — and leaves it there for you to
click through. It asserts its invariants but verifies no behaviour.

It is also the only skill that **does not cold-restart the stack**, deliberately:
restarting would kill the browser session you are about to use.

---

## Per-skill reference

### `/secure-code` — OWASP Top 10 by construction
**Offline for the parts that matter.** Phases 1-3b need no stack, no `pass-cli`,
no network.

The only skill designed to run *before* you write code. It routes the change to
the A01-A10 categories it can actually violate, names the existing repo control
to reuse for each (`AssetFilterService`, `ExcelSanitizer`, the `NmapParserService`
XXE block, `SlackClient.validateWebhookUrl`, …), then verifies three ways:
`./scripts/owasp-check.sh` for the mechanical rules, a semantic re-read against
`references/blind-spots.md` for the rules no grep can see, and `/security-review`
when the diff touches auth, crypto, upload, export or outbound HTTP.

`./scripts/owasp-check.sh` is diff-scoped by default and **includes untracked
files** — a freshly generated controller is exactly what it is for. Findings are
`BLOCK` (this change introduced it; exit 1) or `REVIEW` (decide and say what you
decided). `--all` audits the whole repo and is for audits only: the repo has
substantial pre-existing findings, and a gate that is red on arrival is a gate
people learn to ignore.

Use it whenever you are about to add an endpoint, a query, an MCP tool, an
upload, an export, an HTML sink or an outbound HTTP call — and any time the
answer to "is this secure?" needs to be more than an opinion.

### `/finalizer` — pre-merge consistency pass
**Offline.** No backend, no frontend, no `pass-cli`.

Four checks plus a fifth: CLAUDE.md states the versions the build files actually
declare; the `extensions/` client repos still match the Kotlin controller
contract; a security review of the branch diff reporting **HIGH/CRITICAL only**;
CLAUDE.md compression (changelog archived to `docs/CHANGELOG.md`); and
`./scripts/check-skill-sync.sh` for two-tree skill drift.

Run it at the end of any change, before committing. It is cheap because it never
starts the stack. It **fixes and commits** drift inside the `extensions/` repos
(never pushes) but only **reports** security findings — those usually encode a
design decision that is yours to make.

Does **not** run the mandatory gates. It will remind you they still apply.

### `/testsuite` — fast test tier + coverage evaluation
**Never starts or stops the regular stack.** Backend integration tests use a
fresh disposable database and leave the existing 8080/4321 services untouched.

Runs `./scripts/runbackendtests.sh` (needs `TEST_DB_*` from `pass-cli`),
`./gradlew :cli:test`, `npm ci && npm test && npm run build` in `src/frontend`,
`./scripts/check-skill-sync.sh`, `./scripts/test/owasp-check-test.sh`, and
finally `./scripts/test-coverage-report.sh`.
Fixes what it can, up to 5 iterations, and never disables or deletes a test to
reach green.

Use it when you want to know whether the units are sound, or which areas have no
tests at all. Its coverage output is **name-reference** coverage, not line
coverage — see [Testing → Coverage](TESTING.md#coverage) for how far to trust it.

### `/e2ejs` — JS error scanner (mandatory gate)
Cold-starts the stack, then scans every discovered page twice — once as admin,
once as a normal user — via `https://secman.covestro.net`, and iteratively fixes
what it finds.

Routes are **discovered** from `src/frontend/src/pages/`, so new pages are picked
up automatically; dynamic routes only get exercised if data exists to link to
them.

RBAC 403s on role-gated endpoints are not JS errors. A page that throws or logs
`console.error` is. A non-zero **Expired** count means a partial scan, not a
clean one.

Needs `SECMAN_ADMIN_NAME/PASS` and `SECMAN_USER_USER/PASS` (vault field
`SECMAN_USER_NAME`). If the normal-user account does not exist, run
`./scripts/test/provision-test-user.sh` — idempotent.

### `/e2evulnexception` — full vuln + exception loop (mandatory gate)
⚠️ **Run only in the disposable `secman_e2e_*` database created by the isolated runner.** Phase 10 is global within that database.

Two users, two assets, three vulnerability rows; the exception lifecycle
(approve, reject, cancel) plus authorization negatives via MCP; then the same
state verified through the Astro/React UI with Playwright. Cleans up before and
after, with a `trap EXIT` so DB cleanup survives failure.

Supports a **read-only QA mode**: ask for a static review and it inspects the
driver, the spec and the cleanup semantics without starting anything.

### `/e2eexception` — narrow MCP exception test
⚠️ **Step 2 deletes every asset in its disposable test database only.**

An 11-step MCP workflow ending in approval. Prefer `/e2evulnexception` unless you
specifically want the fast path.

### `/e2eeol` — end-of-life lifecycle
Non-destructive. Everything it creates carries the `e2e-eol-` prefix and is
removed by cleanup that runs both before (unconditional) and after (`trap EXIT`).

Covers every function the EOL feature added: the catalogue sync and matching
scan, the owner-notification run, the asset-scoped read APIs, the
ADMIN/SECCHAMPION top-10 repository ranking, both CLI commands, and the
authorization negatives.

Two assertions carry the run. The **false-positive check** seeds a system on a
current LTS and requires it *not* to appear — a matcher that flags everything
passes everything else. The **scoping check** requires a plain user to see zero
of the admin-owned findings, and is only meaningful because the admin phase
proved those rows exist.

It does rebuild the `eol_finding` table, which is derived data regenerated from
the inventory on every sync — a refresh, not data loss.

**A skipped catalogue assertion is not a failure.** The upstream source is a live
third party; when the backend cannot reach it the driver reports SKIP and the run
can still pass. That is a *partial* result — report it as one, and use
`--offline` deliberately rather than treating the skip as a bug.

### `/admin-asset-e2e` — admin add-system flow
Playwright test of a single user-visible flow: admin creates a "DUMMY" asset
owned by a normal user, adds a HIGH vulnerability, and the normal user can then
see it. Verifies the asset-access boundary end to end from the UI.

Use it after touching `AssetController`, `AssetFilterService`, the add-system
page, or workgroup/ownership logic.

### `/importtest` — CrowdStrike import debugging
⚠️ **Imports live Falcon data into the disposable database only.**

Runs `./scripts/import.sh` and watches the backend log for ERROR-level stack
traces in the import window, fixing and re-running up to 5 times. Catches the
failures that only appear under real data volume: InnoDB deadlocks, transaction
misconfiguration, Hibernate batch flush errors, missing FK rows.

Success needs all three: a clean backend log, `Errors (0)` from the CLI, and
`IMPORT_EXIT == 0`. `Deadlock retry N/3` at WARN is the retry path working — not
a failure, do not "fix" it.

### `/crowdstrike-vuln-match` — SecMan ↔ Falcon comparison
Read-only. Samples assets (200 by default), compares stored CrowdStrike rows
against a fresh Falcon query, and reports rows missing on either side plus
severity drift.

**Exit code 1 means mismatches were found — that is the deliverable, not a
failure.** Only exit 2 is a real error.

### `/aws-account-owner-email` — owner notification to the local sink
Routine runs send only to the runner's loopback SMTP sink. Real inbox delivery
needs a separate, explicit opt-in and is not claimed by this skill.

Imports one new account via the CLI and another via MCP, both mapped to a
reserved `.test` address, then asserts the `EmailService` INFO line inside a **per-import byte
window** of `.e2e-logs/backend.log` — so a leftover line from an earlier run
cannot pass the test. This proves local SMTP acceptance, not external delivery.

Aborts in preflight when no SMTP config is active, before creating anything: a
send that silently no-ops is indistinguishable from a regression, so a green run
without SMTP would be a lie. Use `ALLOW_PLACEHOLDER_RECIPIENT=true` with the
runner's local sink; never use a real recipient in the routine gate.

Two things it deliberately will not do — **create or delete a user for the
recipient address** (usually a real account; the mail is sent with or without
one), and **activate a release when one is already ACTIVE** (that would archive
yours, terminally). Cleanup is scoped to `e2e-awsmail-` and the `884…`/`885…`
accounts it generates.

### `/account-onboarding` — welcome mail, direct and guided assessments
Covers the three modes an import can run for the owner of a brand-new AWS account:
`WELCOME_ONLY` (a mail), `DIRECT` (an assessment for a use case you name) and
`GUIDED` (a one-time link whose answers decide the scope).

The phases worth knowing, because they are the ones that catch real regressions:

- **The compatibility gate.** One phase imports with a bare
  `--start-risk-assessment` and asserts it behaves exactly as it did before
  onboarding modes existed — one assessment, and **no welcome mail**. Every
  `extensions/` client sends only that flag and nothing in this build compiles
  against them, so this phase is the closest thing to a compiler for that
  contract. If it fails, stop and fix it before anything else.
- **The union.** Answers matching two rules must produce **one** assessment
  covering both rules' use cases, with a questionnaire equal to the union of the
  ACTIVE release's requirements for them.
- **The token negatives.** Replay, an unknown 64-hex value and a malformed string
  must all return a byte-identical 404, and a burst must hit 429. These assert the
  absence of an enumeration oracle, which is easy to reintroduce by "improving"
  an error message.
- **Nothing matched.** The answers are recorded, the link still works, and the
  response is 409 — a submission that resolves to nothing must not consume the
  owner's one-time link.

It never prints an invite token, and asserts that the CLI, the MCP result and
every dry run do not either. Do not "fix" those assertions by exposing one.

The owner-flow phases need `.e2e-logs/backend.log` to recover the token from the
rendered mail (no API returns one, by design). Without it they report `[WARN]` and
are **skipped** — say so in the report rather than counting them as passes; point
`SECMAN_BACKEND_LOG` at the real path instead.

Cleanup is scoped to `e2e-onb-` and the `87…000` accounts it mints, and deletes
rules before questions because the API refuses to delete a question a rule still
references.

### `/mcp-risk-assessment-lifecycle` — complete delegated MCP workflow

Takes an unused email address from the user and proves the lifecycle that an
agent such as PaperclipAI needs: create the respondent and assessor, create one
use case with exactly one linked requirement, add the user's AWS mapping and
account-native basis, start the assessment, list it open by use case, read and answer
the questionnaire as that respondent, submit it, and evaluate it as the assigned
assessor. Every business operation is performed through MCP.

Unlike `/aws-account-risk-assessment`, this skill does not activate a requirements
release and therefore cannot terminally archive the environment's current
baseline. Cleanup runs before and after and matches exact `e2e-mcp-ra-` fixture
names. The runner removes the entire owned database after the test.

### `/cleanup-mcp-risk-assessment-lifecycle` — remove a retained fixture

Routine cleanup is automatic: the isolated runner removes verified abandoned
test databases before each run. Legacy fixtures on a persistent database have
no durable ownership proof; report them for review and do not delete them.

### `/mcp-requirement-use-case-lifecycle` — reversible requirement relationship test

Creates one uniquely marked requirement and proves it is listable, creates one
uniquely marked use case and proves it is listable, assigns that use case to the
requirement, and verifies the stable ID in `useCaseAssignments`. It then removes
the relationship, deletes the use case and requirement with explicit
confirmation, and lists both collections again to prove the fixtures are gone.

Every operation uses MCP. Cleanup is limited to the exact IDs returned by the
create calls, including the failure trap, so the skill is safe to run against a
shared instance when its delegated identity and key have the documented
permissions.

### `/createtestdata` — seed a fixture
Additive only, nothing is deleted, fixtures accumulate without bound. Do not run
it in a loop. Leaves the exception request `PENDING` on purpose.

---

## What every stack-touching skill assumes

All of them inherit `.claude/skills/_shared/stack-lifecycle.md`. Worth knowing
even if you never read a skill:

- **Mutating E2E skills use the isolated runner** — it creates and removes an
  owned MariaDB schema and starts separate 18080/14321 services without touching
  the regular stack. `/createtestdata` is manual and records a cleanup manifest.
- **Liveness is port-bind, not HTTP** — the runner checks its own 18080/14321 ports.
- **Use runner-provided URLs** for tests; never aim at the current SecMan database.
- **5 fix iterations total per run**, not per phase.
- If an isolated port is occupied, inspect it; the runner refuses to displace
  the listener. It only stops processes it started and verifies by working directory.

---

## Known rough edges

- Older skill sections still describe direct dev-stack starts; their prominent
  database-safety overrides take precedence. The direct mutating drivers now
  reject targets without the isolated runner's ownership marker.
- `e2e-runner.config.json` is dead pre-script-era config — it points at a
  `./frontend` directory that does not exist and invokes `gradle :backendng:run`
  directly. Nothing should read it.
