---
name: optimizer
description: >
  Make secman code faster and stop the same code existing in five places —
  the two halves of one problem, because the slow query here is usually the
  slow query that got copied. Finds unbounded and in-memory-filtered queries,
  repository calls inside loops, transactions held across HTTP or SMTP,
  serial round-trips on the pages users wait for, timers that outlive their
  component, I/O under a held lock, and blocks of code duplicated across or
  within files. Measures with `./scripts/optimizer-scan.sh` (BLOCK for what
  this change introduced, REVIEW for what it inherited), applies the safe
  fixes, and proposes the ones that need a decision. Use this skill whenever
  the user says "optimizer", "optimize", "make this faster", "this is slow",
  "performance", "speed this up", "N+1", "why does this page take so long",
  "reduce duplication", "this is copy-pasted", "DRY this up", "we have three
  copies of this", "remove duplicate code", or asks for a performance or
  duplication pass before merging — and use it proactively after generating
  a large amount of new code, which is when pasted blocks and unbounded
  queries are freshest and cheapest to remove.
---

> **Sync policy (two-way, mandatory)**: This file and
> `.claude/skills/optimizer/SKILL.md` are one skill kept in two harness trees
> — Codex reads this copy, Claude Code reads the other. Whichever copy an
> agent edits, the same change is ported to the other **in the same commit**;
> translate harness-specific mechanics rather than copying verbatim (e.g. `sandbox_permissions: "require_escalated"` ↔ Bash
> tool `dangerouslyDisableSandbox: true`). `.claude/skills/` is the tie-breaker when the two
> disagree — that is a conflict rule, not a licence to edit one side only.
> Verify with `./scripts/check-skill-sync.sh` (exit 0) before calling the
> change done. See `CLAUDE.md` §"Tooling Conventions" and `AGENTS.md` §Skills.

# Optimizer — faster code, and one copy of it

Two findings, one pass, because in this repo they are usually the same finding.
`userRepository.findAll().filter { it.hasRole(...) }` is a full table scan; it
is also in eight services. Fix it once as a `findByRole` query and eight call
sites get faster. Fix it eight times and someone is already writing the ninth.

That is the shape of nearly every real performance problem here. The slow thing
is rarely clever — it is a shape that was reasonable at 200 rows, got copied
while it was still reasonable, and is now in the path of 1.8 million.

The work splits the way the problem does:

- **Mechanical** — which shapes are present, and where else the same block
  lives. `./scripts/optimizer-scan.sh` turns those into a red or green exit code.
- **Judgment** — whether a shape is on a path that matters, whether two copies
  are one concept or two that merely rhyme, and whether the "inefficiency" is
  load-bearing. No script decides those, and they are most of the value.

Run the scanner first so your attention goes to the second half.

---

## Phase 1 — Fix the scope before touching anything

Scope is what makes this skill usable rather than a diff nobody can review. A
whole-repo audit surfaces well over a thousand findings, most of them years old
and many of them deliberate.

| The user said | Scope |
|---|---|
| "optimize", "this is slow", no target | changed files vs `origin/main` — the default |
| named a path, page or endpoint | that subtree, as an audit |
| "the whole repo" | run `--all`, report the ranked findings, agree a target before editing |
| named a symptom ("the assets page takes 8s") | start from the symptom, not the scanner — Phase 2 |
| just wrote a large amount of code with you | the files you just wrote |

```bash
./scripts/optimizer-scan.sh                      # this change vs origin/main
./scripts/optimizer-scan.sh src/relay            # audit a subtree
./scripts/optimizer-scan.sh --all --verbose      # whole-repo audit, with source lines
./scripts/optimizer-scan.sh --no-clones          # hot-path rules only, fast
./scripts/optimizer-scan.sh --min-clone 20       # only substantial duplication
```

Two severities, and the difference is the whole point:

- **BLOCK** — the line is new in this change. You wrote it; fix it.
- **REVIEW** — it was already there and you worked nearby. Decide, say what you
  decided, move on. Silently skipping is the one thing not allowed.

The clone corpus is always the whole repo, even when the scope is one file.
That is deliberate: "you pasted this from somewhere else" is the finding that
matters most on new code, and it is invisible if changed files are only ever
compared with each other.

---

## Phase 2 — Measure before you rewrite

A rule fires on a *shape*. A shape is slow only on a *path*, and the scanner
cannot see paths. `findAll()` over `email_config` is four rows and always will
be; the identical line over `asset` is a full materialization on every request.

Before changing anything, answer three questions and write the answers down:

1. **How many rows, in production, not in the test fixture?** The repo has real
   numbers: ~1.8M vulnerability rows, asset counts that grow with every
   CrowdStrike import. A config table has tens.
2. **How often, and is a user waiting?** An interactive endpoint, a 15-second
   scheduler and a nightly job have different budgets. `docs/ARCHITECTURE.md`
   and the `@Scheduled` annotations tell you which one you are in.
3. **What is the actual cost today?** For a query, `EXPLAIN` or the log. For a
   page, the network panel. For a job, the existing timing log lines.

If you cannot answer 1 and 2, you are guessing, and a guess that rewrites a
working query is a regression waiting for production data to reveal it. Say so
and leave it — "REVIEW, not measured, here is what I would measure" is a
complete and honest outcome.

When the user came in with a symptom rather than a diff, start there instead:
reproduce it, find where the time goes, and only then look at whether the
scanner already named it. The scanner is a net, not a diagnosis.

---

## Phase 3 — What each finding means, and the fix that already exists

Every rule below has a control in this repo already. Reuse it; do not write a
second one.

| Rule | What it means | The fix that exists here |
|---|---|---|
| `FETCH-ALL-FILTER` | whole table materialized, then most of it discarded in Kotlin | a derived query or a projection — `findByRole`, `findAllIds()`, `findByLockedReleaseId` |
| `UNPAGED` | `Pageable.UNPAGED`, so the row count is whatever production grew to | page at the query; for vulnerabilities the SQL-status path already reads the materialized `excepted` flag |
| `QUERY-IN-LOOP` | one round-trip per element | the `In`-suffixed batch finder, or hoist a map before the loop |
| `TXN-BLOCKING-IO` | a pooled DB connection held for the length of a network timeout | hoist the call out of the transaction, or publish a `@EventListener @Async` post-commit — `ChatNotificationService` is the model |
| `AWAIT-IN-LOOP` | latency is the sum of the calls, not the max | `Promise.allSettled`, or a bulk endpoint returning a per-element result list |
| `SERIAL-AWAITS` | independent fetches chained with `await` | `Promise.allSettled` — it preserves per-item failure isolation, `Promise.all` does not |
| `TIMER-LEAK` | a poll that survives unmount and accumulates | clear the local handle in the effect's cleanup, never a state variable captured at mount |
| `LOCK-IO` | serialization or fsync while a mutex is held; every reader queues | mark dirty and let the maintenance goroutine persist — but only for state whose loss is bounded and harmless |
| `WIDE-FETCH` | a whole table fetched to fill a `<select>` | a typeahead endpoint (`?q=&limit=20`), scoped server-side like every other read |
| `EAGER-ISLAND` | `client:load` hydrates a large island before first paint | `client:visible` / `client:idle` for anything the user does not touch immediately |
| `DUP-BLOCK` | the same block of code in more than one place | Phase 4 |

`references/hot-paths.md` has the worked fix for each one, with the repository
method or component to copy from and the guardrail that goes with it.

**Two guardrails that outrank any speed win**, because both have already cost
this repo real data:

- **The auth boundary is not an optimization target.** Asset access resolves
  through `AssetFilterService`; a SQL pre-filter is a perf hint and never the
  boundary. Making a query faster by inlining or skipping that check is not a
  faster query, it is a vulnerability. Add projections beside the existing
  methods; do not change their shape, their ten access rules, or the documented
  ADMIN-only asymmetry of `getScopedAccessibleAssetIds`.
- **`Asset.vulnerabilities` must never gain `cascade` or `orphanRemoval`.**
  It looks like an inconsistency worth tidying. JPA cascade fights the
  CrowdStrike import's manual delete-insert and once silently dropped 166,812
  rows to 1,819.

---

## Phase 4 — Duplication: one concept, or two that rhyme?

`DUP-BLOCK` tells you two blocks are the same today. It cannot tell you whether
they are the same *thing*, and that is the entire decision.

**Extract when the copies share a reason to change.** If a bug in one is a bug
in all of them, they are one concept wearing several hats — the eight
`findAll().filter{role}` sites, the five CVE-list parsers, the four export
handlers that differ only in format and scope. These want one function, and the
call sites get shorter as a side effect.

**Leave them alone when they only resemble each other.** Two validators that
both happen to check a length and a range are not one validator; merging them
produces a function with a flag argument, and the next requirement makes it two
flags. Coincidental similarity is not duplication — it is two pieces of code
that will diverge, and forcing them together now means unpicking them later.

**The dangerous middle is the fork that has already drifted.** `Export.tsx` and
`ImportExport.tsx` duplicate four handlers with a 33-line divergence: one side
migrated to `requestWordExport()`, the other still calls `authenticatedFetch`
directly. That is the state where the duplication has stopped being harmless
and has not yet become a bug. Extract these first — a drifting fork is the
finding with the shortest fuse.

A useful question when you are unsure: *if this changes, must the other change
in the same commit?* Yes means extract. No means leave it and say why.

**Extraction is a rename in disguise**, so Phase 2 of `/humanizer` applies in
full: moving a function changes what Micronaut resolves by name, moving a class
changes JPA entity scanning, and renaming a DTO field makes the `extensions/`
clients keep succeeding while sending nothing. Read
`.agents/skills/humanizer/references/rename-hazards.md` before extracting
anything public, and propose rather than apply.

Duplication the scanner reports but you should not act on is listed in
`references/deliberate-duplication.md` — read it before extracting anything in
`src/relay`, `src/clinotify`, or `McpToolPermissions`.

---

## Phase 5 — What must not be optimized

These look like findings and are robustness by design. Every one is documented
in `docs/SOURCE_REVIEW_COMPLEXITY_SPEED.md` §5. "Simplifying" any of them is a
regression, not a win:

- **Boot-fail validators** (`JwtSigningValidator`, `DatabaseCredentialValidator`,
  `DatasourceUrlValidator`) — failing startup on weak config is the feature.
- **Transactional-replace without JPA cascade** — see Phase 3.
- **The relay's hand-rolled ACME/JWS/JWT and rate limiter**, and `src/clinotify` —
  zero third-party dependencies is a stated supply-chain decision for a DMZ
  component. Replacing hand-rolled protocol code with a library would cut lines
  and *raise* risk.
- **`store.Section`'s defensive copy** — the immutability guarantee is correct.
  Only the mechanism may change (freeze-on-publish), never the guarantee.
- **`OAuthService`'s non-transactional callback** with REQUIRES_NEW
  micro-transactions — deliberate, documented, and the pattern to copy.
- **`ExportJobService`'s REQUIRES_NEW per progress tick** — independent commits
  are what make progress observable and crash-survivable.
- **Per-card `try`/`catch` in the home dashboard** — parallelize it with
  `allSettled` and keep every catch exactly where it is.
- **`McpToolPermissions` LISTING and CALLING as two maps** — the redundancy is
  the fail-closed design. Merging them is a security regression; the residual
  risk of divergence wants a test, not a merge.

If a finding is on this list, say so in the report and move on. Recording the
decision is the point — a future pass will find it again.

---

## Phase 6 — Verify, then report

A performance change is exactly the kind that compiles, passes, and is wrong.
Run what your change touched — these are `CLAUDE.md` Principles 5, 5a and 5a-relay:

```bash
./gradlew build                                   # any Kotlin/Java edit
cd src/frontend && npm ci && npm run build        # any frontend edit
cd src/relay && go build ./... && go vet ./... && gofmt -l . && go test ./...
./scripts/owasp-check.sh                          # you edited code; the gate is diff-scoped
./scripts/optimizer-scan.sh                       # confirm the findings are gone
```

If you changed the scanner or its rules, `./scripts/test/optimizer-scan-test.sh`
must pass. It asserts both directions per rule — that a violation fires *and*
that clean code stays silent — because a rule that quietly stopped firing looks
exactly like a fast, clean repo.

If you touched the backend, Principle 5 also requires
`./scripts/startbackenddev.sh` to start cleanly (run it outside the sandbox —
`sandbox_permissions: "require_escalated"`), then stop it. A query change that
compiles can still fail Hibernate's parse at SessionFactory build, which only
happens at startup.

Frontend changes additionally need the Principle 7 gates: `/e2ejs` for both
roles (hydration and timing changes are exactly what it catches) and
`/e2evulnexception`. If you touched a query behind vulnerabilities or
exceptions, run `ExceptedFlagSqlAgreementIntegrationTest` — the `excepted`
semantics are pinned there and nowhere else.

Report in this shape, so the user can see what they still owe a decision on:

```
Optimizer — <scope>

Measured    <what you actually measured, with the number>
Applied     <n> queries, <n> round-trips, <n> extractions
Proposed    <n> changes needing your call — awaiting an answer (listed below)
Deferred    <n> REVIEW findings left alone — <one line each saying why>
Verified    gradlew build ✓  optimizer-scan ✓  owasp-check ✓  <E2E gates>
OWASP       <categories touched> — clean
```

Deferred needs a reason, not a count. A finding left alone without one is
indistinguishable from a finding nobody looked at, which is the state this
skill exists to end.
