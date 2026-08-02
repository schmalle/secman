---
name: finalizer
description: >
  End-of-work finalization pass for the secman repo. Verifies that CLAUDE.md
  states the runtime versions the build files actually declare (Kotlin, Java,
  Micronaut, Gradle, Astro, React, MariaDB), re-verifies that every backend
  call made by the independent client repos under extensions/ still matches the
  live Kotlin controller contract (path, method, request fields, auth scheme,
  RBAC) and fixes plus commits drift inside those repos, runs a security review
  of the branch diff reporting only HIGH and CRITICAL findings, and compresses
  CLAUDE.md by archiving its changelog to docs/CHANGELOG.md while keeping the
  operational contracts verbatim. Runs entirely offline — no backend, frontend,
  or pass-cli needed. Use this skill whenever the user says "finalizer", "run
  the finalizer", "finalize", "wrap up this change", "pre-merge check",
  "ready to merge?", "check CLAUDE.md is current", "are the extensions still in
  sync", "tidy up CLAUDE.md", or asks for a final consistency, documentation,
  or contract-drift pass before committing or merging — even if they only
  mention one of the four jobs.
context: fork
---

# Finalizer — pre-merge consistency, contract, and security pass

You are finishing a piece of work in the secman repo. Four kinds of rot creep
in while a change is being built, and none of them are caught by `./gradlew
build` or the E2E gates:

1. **CLAUDE.md drifts from the build files.** Someone bumps Kotlin in
   `build.gradle.kts` and CLAUDE.md keeps advertising the old number. Every
   future session then starts from a false premise about the toolchain.
2. **The extension client repos drift from the backend.** They live in
   `extensions/`, they are *separate git repos with their own remotes*, and
   nothing in this build compiles against them — so a renamed endpoint or a
   changed request field breaks them silently, and you find out in production.
3. **Security regressions hide in a green build.** A missing `@Secured`, a
   concatenated native query, or a DTO that started leaking a secret all
   compile fine.
4. **CLAUDE.md grows monotonically.** It is prepended to every session's
   context, so every stale paragraph is a permanent tax on every future task.

Work through the steps in order. Steps 1–4 are independent, so a failure in one
does not excuse skipping the others — do all four, then report once.

## Scope fence

This skill is **static only**. Do not start the backend or frontend, do not
call `pass-cli`, do not run `./gradlew build` or the E2E gates. It reads files,
edits two things (CLAUDE.md and the extension repos), and reports. Keeping it
offline is what makes it cheap enough to run at the end of every change.

If the work you are finalizing touched `src/`, remind the user in the final
report that CLAUDE.md's mandatory gates (`./gradlew build` clean, backend
starts cleanly, `/e2ejs`, `/e2evulnexception`) still apply — but do not run
them.

---

## Step 0 — Resolve the review scope

The security review (step 3) needs a diff. Resolve it with this ladder and
**state which rung you landed on** in the report, so a reader knows what was
actually examined:

1. Commits ahead of the remote plus uncommitted work:
   `git diff origin/main...HEAD` combined with `git diff HEAD`.
2. If both are empty (you are on `main`, fully pushed, clean tree), fall back
   to the **last commit**: `git show HEAD`.
3. If that is somehow empty too, say so and skip step 3 rather than inventing
   a target.

Steps 1, 2 and 4 ignore the diff entirely — they verify current state from
scratch. This matters: drift introduced three commits ago is exactly the drift
a diff-based check misses, and it is the drift most likely to still be broken.

---

## Step 1 — CLAUDE.md must state what the build actually declares

Gather ground truth first, in one shot:

```bash
cd <repo-root>
echo "--- kotlin / micronaut / plugins"; grep -nE 'kotlin.jvm|kotlinVersion|micronautVersion|io.micronaut.application' build.gradle.kts
echo "--- gradle";   grep distributionUrl gradle/wrapper/gradle-wrapper.properties
echo "--- java";     grep -nE 'jvmToolchain|JavaVersion' src/backendng/build.gradle.kts src/cli/build.gradle.kts
echo "--- frontend"; grep -E '"(astro|react|axios|typescript)":' src/frontend/package.json
echo "--- cli deps"; grep -nE 'picocli|aws|software.amazon' src/cli/build.gradle.kts | head
echo "--- mariadb";  grep -rn 'image: mariadb' docker/
echo "--- claimed";  sed -n '/^## Stack/,/^## Roles/p' CLAUDE.md
```

Compare each claim in CLAUDE.md's **Stack** section against its source of
truth. Where they disagree, the build file wins — edit CLAUDE.md, never the
build.

A judgment call worth getting right: match the **precision CLAUDE.md already
uses**. It says "React 19", not "React 19.2.8" — a major-version claim is still
true after a patch bump and does not need touching. Only rewrite a number that
is actually *wrong*, not one that is merely less precise than the lockfile.
Churning CLAUDE.md on every patch release makes the file's git history useless.

Call out **major-version drift separately** in the report. A stale patch number
is cosmetic; a stale major (CLAUDE.md saying Micronaut 4 when the build is on 5,
or Astro 6 when it is on 7) actively misleads — every future session reasons
about the wrong framework generation, and the advice it produces is wrong in
ways that compile. Treat those as the headline result of step 1, not a row in a
table.

### 1b — Mechanically verifiable claims beyond versions

While you have the ground truth open, check the small set of CLAUDE.md claims
that can be *proven* against the tree, because they rot the same way version
numbers do and mislead just as badly:

- **Auth mechanism** — does the Auth pattern section match reality? Check
  `src/frontend/src/middleware.ts` and `src/frontend/src/utils/` for whether
  the JWT lives in `localStorage` or an HttpOnly cookie, and check
  `AuthController` for the cookie name.
- **Script names** — every `./scripts/…` path named in CLAUDE.md exists.
- **File-layout paths** — the directories under "File Layout" exist.
- **Endpoint paths** — spot-check a handful from the API table against
  `@Controller`/`@Get`/`@Post` annotations; correct any that moved.

Do not extend this into a general prose review. The test for inclusion is
"could a grep prove this wrong?" If it needs judgment, leave it alone — that is
the user's call, not yours.

---

## Step 2 — Extension repos must still match the backend contract

`extensions/` holds independent Python client repos (currently
`secman_ai_github` and `secman_visual_check`). They are **gitignored by the
parent repo** and have **their own GitHub remotes**, so changes you make there
are invisible to `git status` at the repo root. Be deliberate.

### 2a — Record what was already dirty

Before touching anything:

```bash
for d in extensions/*/; do
  [ -d "$d/.git" ] && { echo "== $d"; git -C "$d" status --porcelain; }
done
```

Keep this list. At least one of these repos routinely carries unrelated
uncommitted work, and the whole point of the commit rule in 2d is to not
swallow it.

### 2b — Discover the call sites (do not work from a hardcoded list)

```bash
grep -rnE '/api/[A-Za-z0-9/_.{}$-]+|"/mcp"|X-MCP-User-Email|tools/call' extensions \
  --include='*.py' --exclude-dir=.venv --exclude-dir=.git --exclude-dir=node_modules
```

Discovery rather than a fixed checklist is deliberate: when someone adds a
sixth backend call, a hardcoded list silently ignores it, and the missing check
looks identical to a passing one.

As a sanity check on your grep, discovery should surface at least
`POST /api/auth/login`, `POST /api/vulnerabilities/cli-add`,
`PUT /api/assets/import`, `GET /api/vulnerabilities/current`, and the MCP
`/mcp` JSON-RPC path. If you find fewer, your search was too narrow — widen it
before concluding anything is fine.

Ignore hits inside each repo's `tests/` for the purpose of *finding* the
contract (they mock it), but do come back to them in 2c: a test asserting the
old shape is itself drift and must be updated with the client.

### 2c — Verify each call against the Kotlin controller

For each discovered call, open the client function *and* the backing
controller, and compare all five dimensions. Anything that differs is a
finding:

| Dimension | Where the truth lives |
|---|---|
| Path and HTTP method | `@Controller("…")` + `@Get`/`@Post`/`@Put`/`@Delete` in `src/backendng/src/main/kotlin/com/secman/controller/` |
| Request body field names and types | the `@Serdeable data class …Request` the endpoint takes |
| Response fields the client reads | the response DTO the endpoint returns |
| Auth scheme | `AuthController` — login returns the JWT **only** in `Set-Cookie: secman_auth=…`; clients re-send it as `Authorization: Bearer …` |
| Required roles / headers | `@Secured(…)` on the endpoint; for MCP, the mandatory `X-MCP-User-Email` header |

Field-name mismatches are the most common and the least visible failure —
Jackson silently drops an unknown key, so the client "succeeds" while sending
nothing. Compare names character by character rather than eyeballing shape.

An RBAC tightening (an endpoint that gained a role requirement the extension's
service account may not hold) is a **finding to report, not to fix** — you
cannot see which roles that account has. Say what changed and let the user
decide.

### 2d — Fix and commit, path-scoped, never push

For drift you can fix unambiguously (renamed path, renamed field, changed
method, changed auth header), edit the client and any test that asserts the old
shape. Then commit **only the files you edited**:

```bash
git -C extensions/<repo> log --oneline -5          # match the local message style
git -C extensions/<repo> commit -m "<message>" -- <file> [<file> …]
```

Use the path-scoped form. `git commit -- <paths>` commits exactly those paths
from the working tree and ignores everything else, staged or not — which is the
only safe way to commit inside a repo that was already dirty when you arrived.
Never use `git add -A`, `git commit -a`, or a bare `git commit`.

**Never push.** These repos have their own remotes and their own review
expectations; pushing on the user's behalf publishes work they have not seen.

Report every commit you made, with repo, hash, and the file list.

---

## Step 3 — Security review, HIGH and CRITICAL only

Review the diff from step 0. The user wants signal, not a catalogue: report
HIGH and CRITICAL findings in full, and reduce everything below that to a
single line stating the count. Resist the urge to list the medium ones "just in
case" — a report that buries two real findings under fifteen nits gets skimmed
and ignored, which is worse than not running the review.

**CRITICAL** — unauthenticated access to data or actions, cross-user data
exposure, secret disclosure, authentication bypass, remote code execution.

**HIGH** — privilege escalation between roles, injection (SQL/command/path),
sensitive data exposure to a lower-privileged role, a mutating endpoint with no
ownership check.

Codebase-specific patterns that earn those ratings here:

- **A new or modified controller endpoint without `@Secured`**, or with a role
  list weaker than its siblings in the same controller.
- **Bypassing the asset authorization boundary.** CLAUDE.md is explicit:
  `AssetFilterService.getAccessibleAssets()` is the authoritative filter, and
  SQL pre-filters in materialized views are performance hints *only*. Code that
  treats a view's pre-filter as the auth check is a HIGH finding even when it
  returns correct-looking data today.
- **String-concatenated native SQL.** This codebase uses a lot of hand-written
  native queries; any user-controlled value reaching one via interpolation
  rather than a bound parameter is an injection finding.
- **Secrets in source or logs.** The convention is `pass-cli` and
  `EncryptedStringConverter` with `***HIDDEN***` masking; a hardcoded
  credential, or a log line printing a token, key, or password, is CRITICAL.
- **DTO field leakage.** A response DTO that newly exposes a password hash,
  private key, API key, or another user's data.
- **Upload endpoints skipping validation.** The house pattern is size (≤10MB),
  MIME, and extension checks before parsing.

**Report; do not auto-fix.** Give file:line, why it is exploitable, and the
precise patch you would apply — then stop. Security fixes usually involve a
design decision (which roles, which boundary) that belongs to the user, and a
wrong "fix" here is worse than an open finding because it looks resolved. If
the user asks you to apply them afterwards, do it then.

If the diff contains no security-relevant change at all, say exactly that. A
confident "no HIGH/CRITICAL findings in <n> changed files" is a useful result;
padding it with speculation is not.

---

## Step 4 — Make CLAUDE.md crisp

CLAUDE.md is loaded into every session, so its length is a recurring cost paid
by every future task. The goal is fewer words carrying the same operating
knowledge — **relocation, not deletion**.

### 4a — Archive the changelog

The `## Recent Changes` section is an append-only changelog that grows without
bound and typically accounts for half the file. Almost none of it is needed to
*operate* in the repo — it is history, and history belongs in a file you read
on demand.

- Keep the **three newest** entries in CLAUDE.md. The entries carry no dates,
  so do not assume file order is chronological — confirm it with
  `git log --follow -p -- CLAUDE.md | grep '^+- \*\*'` (which entry was added
  last?) before you slice. Getting this backwards archives the three entries
  that mattered most and keeps thirteen stale ones.
- Move every older entry, **verbatim**, to `docs/CHANGELOG.md`, newest first.
  Create that file if it does not exist, with a one-line header explaining it
  holds entries archived from CLAUDE.md.
- Never summarize on the way out. The point is that the detail survives
  somewhere greppable; a lossy move is just deletion with extra steps.

### 4b — Never touch these

These are operational contracts, and compressing them changes behavior:

- Tooling Conventions
- Hard Principles (including the numbered gates)
- Roles (RBAC)
- Unified Asset Access — the numbered list is the auth model
- The API Endpoints table
- Test Infrastructure setup, including the SQL and the `TEST_DB_*` warning
- E2E Runner

You may fix a factual error inside them (step 1b). You may not shorten them.

### 4c — Tighten what is left

- Collapse facts stated in two places into one.
- Cut narrative framing ("it is worth noting that…") that carries no fact.
- Drop dead references — sections describing files, scripts, or features that
  no longer exist.
- Update the `*Last updated: …*` line to today's date.

Report before/after line and word counts, and list what moved where. If the
file was already tight, say so and change nothing — a finalizer that always
finds something to trim will eventually trim something load-bearing.

---

## Step 4b — Skill mirror check

The repo keeps two skill trees — `.claude/skills/` (canonical) and
`.agents/skills/` (Codex) — that CLAUDE.md requires to move together. They do
not stay together on their own: 7 of 8 skills had drifted before this check
existed, one of them into two materially different documents.

```bash
./scripts/check-skill-sync.sh
```

Exit `0` is in sync, `1` is real drift, `2` is a script or environment error.
Report the result either way — a check whose green result is never mentioned
provides no assurance.

On drift, report what it found; **do not fix it as part of finalizing**. Deciding
which tree is correct is a judgment call: `.claude` is authoritative by policy,
but it has been the stale side before, so a mechanical resync can propagate wrong
content or delete right content. Surface it and let the user choose.

Also surface any `WARNING: normalization rule … matched nothing` line. That
means a rule no longer matches the wording it was written for, so a green result
from that run is weaker than it looks.

## Step 5 — Report

Your final message is the deliverable. Because this skill runs in a forked
context, nothing you did along the way is visible to the user unless it appears
here. Write the whole report in the last message; do not refer to earlier
output.

Use this structure:

```
# Finalizer report

## 1. CLAUDE.md version accuracy
<table: claim | actual | source file | action taken>
<or: all claims accurate>

## 2. Extension contract
<per repo: calls checked, drift found, fixes applied, commits made (hash + files)>
<findings reported but not fixed, and why>

## 3. Security review (HIGH / CRITICAL only)
Scope: <which rung of the step 0 ladder, n files>
<findings with file:line, exploitability, proposed patch>
<n medium/low findings not listed>

## 4. CLAUDE.md crispness
Before: <n> lines / <n> words → After: <n> lines / <n> words
<what moved to docs/CHANGELOG.md, what was tightened>

## 4b. Skill mirror
./scripts/check-skill-sync.sh → exit <0|1|2>
<in sync, or the findings verbatim; plus any dead-normalization-rule warnings>

## Still required
<the mandatory gates from CLAUDE.md, if src/ was touched>
```

Two reporting rules that matter more than they look:

- **Distinguish "checked and clean" from "not checked."** Silence reads as a
  pass, and a step you skipped looks identical to a step that found nothing.
  If you could not complete a step, say which one and why.
- **Every claim needs a locator.** `file:line` for findings, a commit hash for
  every commit, a source file for every version. A report the user cannot
  verify in ten seconds is a report they will stop trusting.
