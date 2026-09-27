# Repository Guidelines

`CLAUDE.md` is authoritative. This file is a short summary of the rules other AI agents (Codex, Gemini, etc.) need to know when contributing here.

## Layout
- `src/backendng/` — Micronaut/Kotlin (`src/main/kotlin`, `src/main/resources`).
- `src/frontend/` — Astro + React; pages in `src/pages/`, components in `src/components/`, Playwright in `tests/`.
- `src/cli/` — Picocli CLI.
- `src/shared/` — code shared between backend and CLI.
- `scripts/` — **all** scripts, invoked as `./scripts/<name>.sh` (canonical — do not call `gradlew`/`npm` directly for dev start). `tests/`, `docker/`, `docs/`.

## Branch guardrail

- `dev` is the default and only branch for agent-authored commits unless the user explicitly names another branch.
- Before editing and again before committing, verify `git branch --show-current` is `dev`. Never commit directly to `main` or `master` by inference.
- Repositories under `extensions/` are independent Git repositories; apply the same branch check inside each repository and commit there separately.

## Build, run, test
- Backend dev: `./scripts/startbackenddev.sh` (canonical — wraps `gradle run` with `pass-cli`-resolved env).
- Build everything (incl. tests): `./gradlew build`.
- Frontend: `cd src/frontend && npm install`, then `./scripts/startfrontenddev.sh` (canonical dev start — never `npm run dev` directly); production check `npm run build && npm run preview`; lint `npm run lint`.
- CLI: build once `./gradlew :cli:shadowJar`, then `./scripts/secman <cmd>`.

## Skills
Twenty-two project skills live in **`.agents/skills/`** — the Codex rendering of the
same skill set Claude Code loads from `.claude/skills/` (`CLAUDE.md` §Tooling
Conventions). They are plain Markdown: there is no slash command here, so read
the matching `.agents/skills/<name>/SKILL.md` **in full** and follow it.

### Two-way sync is mandatory

The two trees are two renderings of the *same* skill, not two skills.
**Whichever tree you edit, the same change must land in the counterpart file in
the same commit** — Codex editing `.agents/skills/` ports to `.claude/skills/`
exactly as Claude Code editing `.claude/skills/` ports to `.agents/skills/`. A
commit that touches one tree only is incomplete. This covers every `*.md` under
the trees (`SKILL.md`, `_shared/`, `references/`), not just `SKILL.md`.

- **Translate, don't copy.** Swap harness-specific mechanics for their equivalent: `sandbox_permissions: "require_escalated"` ↔ Bash tool `dangerouslyDisableSandbox: true`; "ask the user directly" ↔ `AskUserQuestion`; `.agents/skills/…` ↔ `.claude/skills/…` paths. Everything else — steps, commands, thresholds, credential handling — stays word-for-word identical.
- **Tie-breaker, not sole writer.** If the two copies already disagree and neither is clearly newer, `.claude/skills/` wins. That resolves existing drift; it does not make a Codex-side edit second-class and never excuses leaving `.claude/skills/` stale.
- **New skill → create both; delete → delete both.** If you *find* an entry that exists in only one tree, report it rather than synthesizing the missing side.
- **Gate**: `./scripts/check-skill-sync.sh` must exit 0 before a skill change is done (`--verbose` shows differing lines). It is report-only and never edits either tree.
- Each skill file carries a `> **Sync policy (two-way, mandatory)**` banner naming its counterpart. The checker strips banners before diffing, so their wording may differ.

| Skill | Use it to | Writes data? |
|---|---|---|
| `secure-code` | Write code that satisfies OWASP Top 10:2021 by construction, then verify it (`./scripts/owasp-check.sh`) | no |
| `humanizer` | Crisp comments, names a stranger can predict, screen-sized functions; verifies with `./scripts/humanizer-scan.sh` and proposes risky renames instead of applying them | no |
| `optimizer` | Hot-path performance and copy-paste blocks; verifies with `./scripts/optimizer-scan.sh` and proposes extractions instead of applying them | no |
| `finalizer` | Pre-merge pass: version/doc drift, `extensions/` contract drift, HIGH/CRITICAL security review, skill sync | docs only |
| `testsuite` | Fast test tier (backend, CLI, frontend) + name-reference coverage gaps | no |
| `integration-contract-test` | Shared v1 SecMan contract for GitHub, Visual, and Web checkers | no |
| `e2ejs` | Scan every page for JS errors as admin *and* normal user | no |
| `e2evulnexception` | Full vuln + exception lifecycle over MCP and the UI | disposable test DB only |
| `e2eexception` | Fast MCP-only exception smoke test | disposable test DB only |
| `e2eeol` | Full end-of-life lifecycle: catalogue sync, matching, owner mail, top-10 repos, authz negatives | disposable test DB only |
| `admin-asset-e2e` | Admin adds a system + vulnerability, normal user sees it | disposable test DB only |
| `importtest` | Run and debug the CrowdStrike import | imports into disposable test DB only |
| `crowdstrike-vuln-match` | Compare stored rows against a fresh Falcon query | no |
| `aws-account-risk-assessment` | New AWS account starts a correctly scoped assessment | disposable test DB only |
| `aws-account-owner-email` | Verify owner notification reaches a loopback SMTP sink | disposable test DB; real delivery separate opt-in |
| `account-onboarding` | Welcome mail, direct and guided assessments, the owner's tokenized questionnaire | disposable test DB only |
| `mcp-risk-assessment-lifecycle` | Full MCP setup, respondent answer/submit, and assessor evaluation with a manually supplied email | disposable test DB only |
| `cleanup-mcp-risk-assessment-lifecycle` | Audit interrupted lifecycle fixtures | removes only verified runner-owned databases |
| `mcp-requirement-use-case-lifecycle` | Requirement create/list, use-case create/list, assignment verification, deletion, and list-again through MCP | disposable test DB only |
| `aws-account-workgroup-import` | AWS display-name import and workgroup linking | disposable test DB only |
| `requirement-export-template` | Word export-template lifecycle and validation | disposable test DB only |
| `createtestdata` | Seed a fixture to click through | manual fixture with explicit manifest cleanup |

Database-mutating test skills use `./scripts/test/run-isolated-e2e.sh`; direct
driver execution against the current database is forbidden. The runner creates
a marked schema and restricted user, checks and removes its own abandoned
schemas before a run, and drops its schema on exit. Unverified legacy fixtures
are preserved and reported. `/createtestdata` is a separate manual utility.

`.agents/skills/_shared/stack-lifecycle.md` is **mandatory reading** before any
skill that touches the running stack: its database-safety override puts mutating
tests in a separate 18080/14321 stack, while the normal cold-start rules apply
only to read-only skills on 8080/4321. It also defines `pass-cli` credentials, the log
paths and the 5-iteration fix budget. The start scripts need `pass-cli`, so run
them with `sandbox_permissions: "require_escalated"` — a sandboxed shell cannot
reach the vault and the process fails to start.

`docs/SKILLS.md` is the longer routing guide, including the pairs that get
confused (`e2eexception` vs `e2evulnexception`, `importtest` vs
`crowdstrike-vuln-match`, the two AWS-account skills).

Not mirrored, Claude Code only: the `speckit.*` commands in `.claude/commands/`
(spec-driven-development pipeline, see `docs/SKILLS_AND_AGENTS.md`) and the two
subagents in `.claude/agents/`, which no skill spawns.

## Style
- Kotlin: 4-space indent, `UpperCamelCase` types, constructor injection, immutable data classes; ktlint if configured.
- TS/TSX: 2-space indent, named exports, ESLint via `npm run lint`. Import order: external → internal → relative.

## Tests (mandatory)
Always write tests for new code. JUnit 6 + Mockk for unit; integration tests run against an **external MariaDB** (no Docker/Testcontainers — removed from the build) via `BaseIntegrationTest`, using the disposable schema created by `./scripts/runbackendtests.sh`. Normal-stack HTTP checks use `SECMAN_HOST` from `pass-cli`; the isolated runner supplies its own loopback URLs. After **every** change, the `e2ejs` and `e2evulnexception` skills (§Skills) must exit clean (see `CLAUDE.md` principle 7).

## Commits / PRs
- `type(scope): description` (Conventional Commits) or short `Type: Summary` form.
- PR body: motivation, verification (`./gradlew build`, `npm run lint`), screenshots for UI changes.
- Flag schema changes, feature toggles, manual data steps, new dependencies.

## Security & secrets
- **All code you generate must comply with the OWASP Top 10 checklist in `CLAUDE.md` §OWASP Top 10 Compliance** (A01–A10, pinned to the 2021 list). It is binding, names the existing control to reuse for each category, and requires you to state the A01–A10 result for your diff before calling a change complete. A HIGH-or-above finding blocks the change.
- Secrets via `pass-cli` only — never commit.
- Copy `.env.example` → `.env` for local overrides (gitignored).
- Update sample configs and docs when ports/env vars change.
- Authentication, encryption, RBAC, or export changes require a security review before merge.
