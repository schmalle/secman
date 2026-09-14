---
name: integration-contract-test
description: >
  Verify that secman_web_check, secman_visual_check, and secman_ai_github use
  SecMan's shared version-1 integration-result contract. Compares the canonical
  fixture and runs the focused backend and extension client tests without a
  database or running stack. Use when changing integration DTOs, scanner
  subject discovery, run submission, or any of the three checker clients.
context: fork
---

> **Sync policy (two-way, mandatory)**: This file and
> `.agents/skills/integration-contract-test/SKILL.md` are one skill kept in two
> harness trees — Claude Code reads this copy, Codex reads the other. Change
> both in the same commit, translating harness-specific mechanics. Verify with
> `./scripts/check-skill-sync.sh` before calling the change done.

# Integration contract test

This is a read-only, database-free contract gate for the GitHub, visual, and
web-security checkers. It does not start SecMan, scan targets, or submit data.

## Run

1. Confirm the root repository and all three extension repositories are on
   `dev` unless the user explicitly selected another branch.
2. Ensure each extension has its project-local virtual environment. Set these
   only when the defaults are unsuitable:

   ```bash
   export SECMAN_GITHUB_TEST_PYTHON=/path/to/python
   export SECMAN_VISUAL_TEST_PYTHON=/path/to/python
   export SECMAN_WEB_TEST_PYTHON=/path/to/python
   ```

3. Run:

   ```bash
   ./scripts/check-integration-contract.sh --run
   ```

The script must prove all three fixtures exactly match
`docs/contracts/integration-run-v1.json`, then pass the focused backend and
Python client tests. A fixture-only pass is insufficient.

## Failure loop

Fix only the mismatched contract implementation or fixture, then rerun. Stop
after five unsuccessful iterations and report the exact failing command and
error. Never weaken validation or delete assertions to make the gate green.

## Completion

Report the canonical fixture comparison and the backend, GitHub, visual, and
web checker test results separately. Also report `./scripts/check-skill-sync.sh`.
