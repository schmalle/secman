# Test data safety and harness parity

Codex reads `.agents/skills/`; Claude Code reads `.claude/skills/`. Both trees
describe the same 33 Markdown files, and `./scripts/check-skill-sync.sh` checks
their normalized content. The runner and test scripts are shared by both.

## Database-mutating test entry points

| Entry points | Safety boundary |
|---|---|
| `scripts/test/test-e2e-*.sh`, `scripts/release-e2e-test.sh`, and the mutating `tests/*e2e-test.sh`, `tests/bulk-user-mapping-test.sh`, `tests/mcp-e2e-*.sh` | Require the runner's exact schema name, URL, credentials, and ownership token before writes. This includes historical global-delete and release-activation phases. |
| `tests/e2e/run-e2e.sh` and the mutating Playwright specs | Auto-wrap in the isolated runner; specs verify the runner target in `beforeAll`. |
| `scripts/runbackendtests.sh` and `:backendng:test` | Use `--database-only` runner; Gradle refuses a direct test task without its ownership variables. |
| `scripts/test/provision-test-user*.sh` | Only provision a user in the runner-owned database. |
| `scripts/import.sh` when invoked by `importtest` | Uses the runner's database and URL; the ordinary operator import command remains separate. |

The runner creates a random `secman_e2e_*` schema and a same-named MariaDB user
restricted to that schema. It copies table structure and Flyway metadata only,
never application rows. Full-stack tests use backend 18080, frontend 14321,
and a loopback SMTP sink on 1925. The runner drops its schema and user on exit,
including failure. Before a new run it removes abandoned schemas only when the
database marker matches a separate ownership manifest and the recorded runner
PID is no longer live.
An unmarked or otherwise ambiguous schema is preserved and reported.

## Explicit exceptions

`scripts/test/create-test-data.sh` is a manual fixture utility, not an automated
test. It records the target and exact object IDs in
`.e2e-logs/manual-fixtures/`. The user removes that fixture with
`scripts/manual/cleanup-test-data.sh <manifest>`, which checks IDs and names
before deleting. A legacy fixture without that manifest is not deleted by name
or prefix alone.

The routine AWS owner-email test proves the application sends to the loopback
sink. It does **not** claim external mailbox delivery; a real-mail check needs
a separate explicit opt-in and recipient. The opt-in AI risk-assessment UI spec
still needs its own seeded assessment to be self-contained; do not enable it
against a persistent database or infer a pass from a skipped run.
