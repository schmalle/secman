# Quickstart: CLI manage-user-mappings --send-email Option

**Feature**: 085-cli-mappings-email
**Date**: 2026-04-08

This guide walks through manual verification of the feature after implementation. It covers the happy path, each failure mode, and the documentation review checklist from FR-013.

## Prerequisites

- Backend running locally: `./gradlew :backendng:run` (default port 8080)
- CLI JAR built: `./gradlew :cli:shadowJar`
- Environment variables set:
  ```bash
  export SECMAN_HOST=http://localhost:8080
  export SECMAN_ADMIN_NAME=admin
  export SECMAN_ADMIN_PASS=<password>
  ```
- Test data: At least 2 users with `ADMIN` role (one is the invoker) and at least 1 user with `REPORT` role, each with a valid email address
- Some user-to-domain and user-to-AWS-account mappings created (use `manage-user-mappings add-domain` / `add-aws` or the import commands)

---

## Scenario 1: Happy path — send to all admins

```bash
./scriptpp/secman manage-user-mappings list --send-email
```

**Expected console output**:
1. The standard TABLE view of mappings (unchanged)
2. Followed by a new block:
   ```
   ============================================================
   Email Distribution
   ============================================================
   Recipients: 3
   Emails sent: 3
   Failures: 0
   Statistics delivered successfully.
   ```
3. Exit code `0`

**Verify**:
- Check each recipient's inbox (or SMTP log) — the email subject is "SecMan User Mapping Statistics Report" (or similar) and the body contains the aggregate counts PLUS a per-user table mirroring the TABLE console output
- Check the `user_mapping_statistics_log` table — a new row with `status=SUCCESS`, `dry_run=false`, matching recipient and aggregate counts

---

## Scenario 2: Dry-run preview

```bash
./scriptpp/secman manage-user-mappings list --send-email --dry-run
```

**Expected**:
- TABLE view printed (unchanged)
- Followed by:
  ```
  ============================================================
  Email Distribution (DRY RUN)
  ============================================================
  Would send to 3 ADMIN/REPORT recipients:
    - alice@example.com
    - bob@example.com
    - carol@example.com
  No emails dispatched.
  ```
- Exit code `0`
- **No email was actually sent** (verify by checking inboxes)
- A new row in `user_mapping_statistics_log` with `dry_run=true`, `status=DRY_RUN`, `emails_sent=0`

---

## Scenario 3: Filters flow through

```bash
./scriptpp/secman manage-user-mappings list --email foo@example.com --send-email
```

**Expected**:
- TABLE view shows only mappings where owner email = `foo@example.com`
- Email distribution block reflects the filtered view
- The email body lists the applied filters:
  ```
  Applied filters:
    email: foo@example.com
  ```
- Per-user detail section shows only the filtered user(s)
- `user_mapping_statistics_log.filter_email` = `foo@example.com`

---

## Scenario 4: JSON format + email

```bash
./scriptpp/secman manage-user-mappings list --format JSON --send-email
```

**Expected**:
- Console prints raw JSON (unchanged from today)
- Email distribution block still appears after the JSON
- **Email body is rendered as the plain-text template**, NOT raw JSON (FR-005)

---

## Scenario 5: Partial failure

**Setup**: Temporarily set one ADMIN user's email to a domain that will be rejected by the SMTP server (e.g., `nobody@invalid.invalid`).

```bash
./scriptpp/secman manage-user-mappings list --send-email --verbose
```

**Expected**:
- TABLE view printed
- Per-recipient status lines:
  ```
  SUCCESS alice@example.com
  FAILED  nobody@invalid.invalid
  SUCCESS bob@example.com
  ```
- Summary block:
  ```
  Recipients: 3
  Emails sent: 2
  Failures: 1
  Failed recipients:
    - nobody@invalid.invalid
  Email distribution completed with failures.
  ```
- Exit code `4` (partial failure)
- `user_mapping_statistics_log` row has `status=PARTIAL_FAILURE`, `emails_sent=2`, `emails_failed=1`

---

## Scenario 6: Zero eligible recipients

**Setup**: Temporarily remove the ADMIN and REPORT role from every user except the invoker, AND clear the invoker's email address.

```bash
./scriptpp/secman manage-user-mappings list --send-email
```

**Expected**:
- TABLE view printed
- Distribution block:
  ```
  No eligible recipients found.
  Reason: no users with ADMIN or REPORT role have a valid email address.
  ```
- Exit code `3` (no eligible recipients)
- `user_mapping_statistics_log` row has `status=FAILURE`, `recipient_count=0`

**Important**: Restore roles and email after this test.

---

## Scenario 7: Authorization denied

**Setup**: Create a non-ADMIN user (e.g., role `USER` only) with valid credentials. Set `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` to those credentials.

```bash
./scriptpp/secman manage-user-mappings list --send-email
```

**Expected**:
- Error printed to stderr
- Exit code `2` (authorization denied)
- **No new row** in `user_mapping_statistics_log` (the request never reached the service — it was blocked at the Micronaut Security layer)

---

## Scenario 8: Backward compatibility (SC-005)

```bash
./scriptpp/secman manage-user-mappings list > /tmp/baseline-after.txt

# Compare against a snapshot taken BEFORE implementation
diff /tmp/baseline-before.txt /tmp/baseline-after.txt
```

**Expected**: Zero differences. The default `list` output must be byte-identical to the pre-feature baseline (SC-005).

---

## Scenario 9: Help text verification (FR-011, FR-012, SC-006)

```bash
./scriptpp/secman manage-user-mappings list --help
```

**Expected**: Output must include:
- `--send-email` option with description mentioning ADMIN/REPORT users
- `--dry-run` option with description mentioning preview-without-dispatch
- `--verbose` option with description mentioning per-recipient status

```bash
./scriptpp/secman manage-user-mappings --help
```

**Expected**: The `list` subcommand description mentions email distribution.

---

## Scenario 10: Documentation review (FR-013, SC-004)

Run this search and verify each match has been updated with `--send-email` context:

```bash
grep -rn "manage-user-mappings" \
  CLAUDE.md README.md INSTALL.md \
  docs/CLI.md docs/ARCHITECTURE.md \
  src/cli/src/main/resources/cli-docs/USER_MAPPING_COMMANDS.md \
  scripts/secmancli
```

Checklist — every file mentioning `manage-user-mappings` must also mention `--send-email`:

- [ ] `CLAUDE.md` — CLI commands section
- [ ] `README.md` — feature list (if applicable)
- [ ] `INSTALL.md` — CLI usage examples (if applicable)
- [ ] `docs/CLI.md` — `list` subcommand reference
- [ ] `docs/ARCHITECTURE.md` — CLI data flow (if applicable)
- [ ] `src/cli/src/main/resources/cli-docs/USER_MAPPING_COMMANDS.md` — dedicated doc
- [ ] `scripts/secmancli` — shell wrapper help text
- [ ] `ManageUserMappingsCommand.kt` — `@Command(description = ...)` at class level
- [ ] `ListCommand.kt` — `@Command` + new `@Option` annotations

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Authentication failed` | Wrong `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` | Verify env vars |
| `403 Forbidden` response | Invoker lacks ADMIN role | Log in as an ADMIN user |
| Emails not arriving (not in failed list either) | SMTP relay silently dropping | Check backend `EmailService` logs; verify `secman.email.*` config |
| `user_mapping_statistics_log` has no row after successful send | Audit insert failed silently (logged as WARN) | Check backend logs; verify Flyway ran |
| `--dry-run requires --send-email` error | Using `--dry-run` alone | Add `--send-email` |

---

## Success Criteria Check

At the end of this walkthrough, all six success criteria from the spec should be verifiable:

- ✅ **SC-001**: Scenario 1 — single command replaces copy-paste
- ✅ **SC-002**: Scenario 1 — 60-second delivery window
- ✅ **SC-003**: Scenarios 5, 6, 7 — distinct non-zero exit codes per failure mode
- ✅ **SC-004**: Scenario 10 — documentation sweep complete
- ✅ **SC-005**: Scenario 8 — byte-identical default behavior
- ✅ **SC-006**: Scenario 9 — help text discoverable
