# Which secman skill to use when

Eight skills live in `.claude/skills/` (mirrored to `.agents/skills/` for Codex).
They overlap enough that picking the wrong one wastes a run — and in three cases
the wrong pick **destroys data**. This is the routing guide.

Invoke a skill as `/<name>`, or just describe the task; the `description:` in each
skill's frontmatter decides whether it triggers.

---

## Start here

**Am I about to change data, or just look at it?**

| I want to… | Skill | Writes data? |
|---|---|---|
| Finish a change and check it before merging | `/finalizer` | Docs only |
| Prove no page throws JS errors | `/e2ejs` | No |
| Exercise the full exception lifecycle (MCP + UI) | `/e2evulnexception` | **Wipes the DB** |
| Quick MCP-only exception smoke test | `/e2eexception` | **Deletes all assets** |
| Test the admin add-system → user-visibility flow | `/admin-asset-e2e` | Adds one asset |
| Run and debug the CrowdStrike import | `/importtest` | **Imports live data** |
| Compare SecMan against Falcon without changing anything | `/crowdstrike-vuln-match` | No |
| Get a fixture to click around in | `/createtestdata` | Adds a fixture |

**The three destructive ones are not safe against a shared instance.** Check what
`SECMAN_HOST` / `BASE_URL` resolves to before running `/e2evulnexception`,
`/e2eexception`, or `/importtest`.

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

### `/createtestdata` vs the E2E skills

`/createtestdata` is not a test. It seeds one fixture — a user, a system, a
vulnerability, and a `PENDING` exception request — and leaves it there for you to
click through. It asserts its invariants but verifies no behaviour.

It is also the only skill that **does not cold-restart the stack**, deliberately:
restarting would kill the browser session you are about to use.

---

## Per-skill reference

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
⚠️ **Destructive: Phase 10 wipes every row in the target database.**

Two users, two assets, three vulnerability rows; the exception lifecycle
(approve, reject, cancel) plus authorization negatives via MCP; then the same
state verified through the Astro/React UI with Playwright. Cleans up before and
after, with a `trap EXIT` so DB cleanup survives failure.

Supports a **read-only QA mode**: ask for a static review and it inspects the
driver, the spec and the cleanup semantics without starting anything.

### `/e2eexception` — narrow MCP exception test
⚠️ **Destructive: step 2 deletes every asset.**

An 11-step MCP workflow ending in approval. Prefer `/e2evulnexception` unless you
specifically want the fast path.

### `/admin-asset-e2e` — admin add-system flow
Playwright test of a single user-visible flow: admin creates a "DUMMY" asset
owned by a normal user, adds a HIGH vulnerability, and the normal user can then
see it. Verifies the asset-access boundary end to end from the UI.

Use it after touching `AssetController`, `AssetFilterService`, the add-system
page, or workgroup/ownership logic.

### `/importtest` — CrowdStrike import debugging
⚠️ **Writes real data.**

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

### `/createtestdata` — seed a fixture
Additive only, nothing is deleted, fixtures accumulate without bound. Do not run
it in a loop. Leaves the exception request `PENDING` on purpose.

---

## What every stack-touching skill assumes

All of them inherit `.claude/skills/_shared/stack-lifecycle.md`. Worth knowing
even if you never read a skill:

- **Cold start is mandatory** — both stop scripts run unconditionally before
  starting, because a running instance may predate the working tree.
  `/createtestdata` is the single documented exception.
- **Liveness is port-bind, not HTTP** — `lsof -iTCP:8080` (120s) and `:4321` (60s).
- **`SECMAN_HOST` from `pass-cli`, never `localhost`.**
- **5 fix iterations total per run**, not per phase.
- Two failure modes that masquerade as bugs: the stop scripts always exit 0 even
  when a kill fails (so the port must be re-polled), and Vite may bind **4322**
  when 4321 is taken, making a successful frontend look like a failed one.

---

## Known rough edges

- `scripts/test/test-e2e-exception-workflowsupport.sh:33` still defaults
  `BASE_URL` to `http://localhost:8080` and carries the dev DB password inline.
  `/e2eexception` overrides the URL, but the script's own default predates the
  current convention.
- `e2e-runner.config.json` is dead pre-script-era config — it points at a
  `./frontend` directory that does not exist and invokes `gradle :backendng:run`
  directly. Nothing should read it.
