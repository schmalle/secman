---
name: humanizer
description: >
  Make source code read as though a careful person wrote and maintained it:
  crisp comments that explain why rather than restate what, names a stranger
  can predict, functions that fit on a screen (50 lines target, 100 hard),
  files under 1000 lines, and layout that matches the file it lives in
  instead of generator boilerplate. Measures with
  `./scripts/humanizer-scan.sh` (BLOCK for what this change introduced,
  REVIEW for what it inherited), applies the safe fixes directly, and
  proposes the risky ones — renames and file splits break Jackson field
  names, JPA columns, Micronaut bean wiring, MCP tool ids and the
  `extensions/` clients silently. Use this skill whenever the user says
  "humanizer", "humanize", "clean up this code", "make this readable",
  "add comments", "comment this properly", "this function is too long",
  "split this class", "these names are terrible", "make it look
  hand-written", "code hygiene", "readability pass", or asks for a tidy-up
  before committing — and use it proactively after generating a large
  amount of new code, which is exactly when the machine tells are freshest.
---

> **Sync policy (two-way, mandatory)**: This file and
> `.claude/skills/humanizer/SKILL.md` are one skill kept in two harness trees
> — Codex reads this copy, Claude Code reads the other. Whichever copy an
> agent edits, the same change is ported to the other **in the same commit**;
> translate harness-specific mechanics rather than copying verbatim (e.g. `sandbox_permissions: "require_escalated"` ↔ Bash
> tool `dangerouslyDisableSandbox: true`). `.claude/skills/` is the tie-breaker when the two
> disagree — that is a conflict rule, not a licence to edit one side only.
> Verify with `./scripts/check-skill-sync.sh` (exit 0) before calling the
> change done. See `CLAUDE.md` §"Tooling Conventions" and `AGENTS.md` §Skills.

# Humanizer — code that reads like a person maintained it

Generated code passes review and fails maintenance. It compiles, the tests are
green, and then six months later someone opens a 400-line function with a
comment on every line explaining what the next line does, and none explaining
why any of it is there. Nothing in a build catches that.

This skill fixes it in the two halves the problem actually has:

- **Mechanical** — length, missing comments, junk names, generator residue.
  `./scripts/humanizer-scan.sh` turns those into a red or green exit code.
- **Judgment** — whether a comment is worth reading, whether a name is the
  right one, where a long function actually wants to be cut. No script will
  ever do this, and it is most of the value.

Run the scanner first so you spend your attention on the second half.

---

## Phase 1 — Fix the scope before touching anything

Scope is the decision that makes this skill useful or unusable. A full-repo
sweep of secman touches 1576 source files and surfaces ~4200 findings: 27
files already exceed 1000 lines and 233 functions exceed 100. Acting on that
produces a diff nobody can review, which means nobody does.

| The user said | Scope |
|---|---|
| "humanize", "clean this up", no target | changed files vs `origin/main` — the default |
| named a path or subtree | that subtree, as an audit |
| "the whole repo" | run `--all`, report the ranked findings, and agree a target before editing |
| just wrote a large amount of code with you | the files you just wrote |

```bash
./scripts/humanizer-scan.sh                 # this change vs origin/main
./scripts/humanizer-scan.sh src/relay       # audit a subtree
./scripts/humanizer-scan.sh --all --verbose # whole-repo audit, with source lines
```

Two severities, and the difference is the whole point:

- **BLOCK** — the declaration is new in this change. You wrote it; fix it.
- **REVIEW** — it was already there and you only worked nearby. Decide, say
  what you decided, move on. Silently skipping is the one thing not allowed.

`UNDOC-DECL` means a declaration has **no comment at all**. It says nothing
about whether the comments that do exist are any good — that is Phase 3, and
it is the more common failure.

---

## Phase 2 — Know what you may change on your own

Every fix here falls on one side of a line: does it stay inside one file, or
does it change something another system reads by name?

**Apply directly** — no call sites, no wire format:

- write, rewrite or delete a comment
- rename a local variable or parameter inside one function
- regroup blank lines, reorder statements where order does not matter
- delete commented-out code, dead imports your own change orphaned
- rename a `private` function whose call sites are all in the same file
  (grep first — `private` in Kotlin still means file-wide for top-level
  declarations)

**Propose, then wait** — ask the user directly with the call-site
count, and do not start until they answer:

- rename anything public: function, class, method, top-level property
- rename **any field on an entity, DTO or API request/response**
- split a file or extract a class
- change a signature — parameter order, count or type

That second bullet is the dangerous one, and it is dangerous *quietly*.
Jackson drops unknown JSON keys without an error, so renaming a DTO field
makes the `extensions/` clients keep succeeding while sending nothing. JPA
derives the column from the property name unless `@Column` pins it, so
Hibernate auto-update adds a **new** column and the old data looks deleted.
Micronaut resolves beans, `@Value` keys and `@Named` qualifiers by name at
startup, not at compile time. MCP tool ids are the wire protocol.

`references/rename-hazards.md` has the full list with the grep for each one.
Read it before proposing any rename — it is short, and every entry is a real
failure mode of this codebase.

A rename is never worth a silent production break. If you cannot enumerate the
call sites, say so and leave the name alone.

---

## Phase 3 — What "crisp" means

**The code says what. A comment says why, or it says nothing.**

That single rule removes most bad comments and writes most good ones. The
test for keeping a comment: delete it and reread the code. If nothing was
lost, it should stay deleted.

Every **public declaration** gets one line on its purpose, plus anything a
caller cannot see from the signature: nullability, units, side effects,
thread-safety, transaction boundaries, what happens on failure.

Every **non-obvious block** gets its reason: the invariant it protects, the
workaround it is, the incident it prevents.

`Asset.vulnerabilities` is the example to learn from, because of what its
comment does *not* say. The field deliberately omits `cascade = [CascadeType.ALL]`
while the two `@OneToMany` fields on either side of it use it — because JPA
cascade fights the CrowdStrike import's manual delete-insert and once silently
dropped 166,812 vulnerability rows to 1,819. None of that is in the file. Its
KDoc explains the foreign key and `@JsonIgnore`, which the reader could see,
and is silent on the one thing that would stop someone "tidying up" the
inconsistency and re-breaking production. (It also carries a
`Related to: Feature 003-i-want-to` line, which is the residue from Phase 3's
delete list.)

That is the shape of the work: the load-bearing reason is usually the one
nobody wrote down.

What to delete on sight:

| Delete | Because |
|---|---|
| `// increment the counter` above `counter++` | restates the line |
| `Feature: 032-…` / `Tasks: T019, T020` in KDoc | spec tracking belongs in the commit message; it rots in place |
| `// Step 1: … // Step 2: …` | narration of control flow the reader can already see |
| `// Added by … on 2026-03-04` | git knows |
| `/** Returns the name. */` on `fun getName()` | pure signature echo |
| commented-out code | git knows that too |
| banner walls of `====` or `****` | `// --- Section ---` is this repo's divider; the loud form is generator habit |
| emoji | decoration a maintainer reads past |

Length: if a function needs fifteen lines of prose to explain, the function is
the problem, not the comment. Fix the function.

`references/comment-doctrine.md` has per-language form (KDoc, TSDoc, Go doc
comments, docstrings) and worked before/after pairs.

---

## Phase 4 — Names a stranger can predict

The test: someone who has never opened this file reads the name and predicts
what it holds or does. If they would guess wrong, the name is wrong — however
short, however conventional.

- Functions are verb phrases. A function named after a noun is usually doing
  two things and wants splitting.
- Booleans read as predicates: `isStale`, `hasWorkgroup`, `canAccessAsset`.
- Do not encode types: `assetList` and `strName` say what the compiler
  already says. `staleAssets` says something new.
- Length scales with scope. `i` in a four-line loop is right; the same `i`
  200 lines from its declaration is not. The scanner only flags the second.
- **Match the vocabulary already in the repo.** Asset, Workgroup, UserMapping,
  Vulnerability, Release, Exception all mean specific things here. A fresh
  synonym for an existing concept is worse than a mediocre name, because now
  the reader has to work out whether they are the same thing.

Naming is where you should be most willing to leave something alone. A name
that is merely unexciting is not a defect.

---

## Phase 5 — Layout that reads as maintained, not generated

This repo has **no formatter** — no ktlint, no spotless, no prettier, only
eslint. Do not add one: it is a new dependency (A06) and it would rewrite
every file in the repo. Match the file you are in instead.

The tell is not any single thing, it is **uniformity**. Human-maintained code
is a little uneven, because it was written at different times for different
reasons. Generated code is perfectly regular, and that regularity is what
reads as machine-made:

- an identical divider between every function
- a doc comment of exactly the same shape on every method, including trivial ones
- every variable annotated with its type where the file elsewhere infers
- exhaustive `try`/`catch` around things that cannot fail, several swallowing
  the exception (also an A09 violation — never leave an empty catch)
- parallel structure where the cases are not actually parallel

Group statements into the steps a reader thinks in, separated by blank lines,
and let the shape follow the logic rather than a template.

---

## Phase 6 — Splitting, once approved

Only after the user has agreed (Phase 2).

Cut along a seam that already exists. A 1200-line service usually contains two
or three responsibilities that barely reference each other — find those and
extract them whole. A controller that is too long is usually doing work that
belongs in a service.

Never split to satisfy the number. A 1001-line file cut into two arbitrary
halves is worse than what you started with: now the reader needs both files
and neither has a coherent story. If the only available cut is arbitrary, say
so and leave it, recording why.

The same applies to a long function: extract the part that has a *name*. If
the extracted piece needs six parameters and returns a tuple, you found a
boundary that is not there.

`references/rename-hazards.md` covers what moving a class between files does
to Micronaut bean resolution and JPA entity scanning.

---

## Phase 7 — Verify, then report

Comment-only edits still have to build: a stray character inside a KDoc block
takes a Kotlin file with it (comments nest in Kotlin, so `/api/**` inside a
KDoc closes it early).

Run what your change touched — these are `CLAUDE.md` Principles 5, 5a and 5a-relay:

```bash
./gradlew build                                   # any Kotlin/Java edit
cd src/frontend && npm ci && npm run build        # any frontend edit
cd src/relay && go build ./... && go vet ./... && gofmt -l . && go test ./...
./scripts/owasp-check.sh                          # you edited code; the gate is diff-scoped
./scripts/humanizer-scan.sh                       # confirm the findings are gone
```

If you changed the scanner or its rules, `./scripts/test/humanizer-scan-test.sh`
must pass. It asserts both directions per rule — that a violation fires *and*
that clean code stays silent — because a rule that quietly stopped firing looks
exactly like a clean repo.

If you touched the backend, Principle 5 also requires
`./scripts/startbackenddev.sh` to start cleanly (run it outside the sandbox —
`sandbox_permissions: "require_escalated"`), then stop it. Compile-clean is
not runtime-clean: Micronaut bean wiring, Flyway and the SessionFactory only
check at startup, which is exactly where a rename fails.

If you renamed anything the `extensions/` clients call, Principle 5b applies —
rediscover the surface, do not trust a written list:

```bash
grep -rnE '/api/|"/mcp"|X-MCP-User-Email' extensions --include='*.py' --exclude-dir=.venv
```

Report in this shape, so the user can see what they still owe a decision on:

```
Humanizer — <scope>

Applied     <n> comments rewritten, <n> deleted, <n> local names, <n> layout
Proposed    <n> renames / splits — awaiting your call (listed below)
Deferred    <n> REVIEW findings left alone — <one line saying why>
Verified    gradlew build ✓  humanizer-scan ✓  owasp-check ✓
```

Deferred needs a reason, not a count. "Left alone" without one is the failure
mode this whole skill exists to prevent.
