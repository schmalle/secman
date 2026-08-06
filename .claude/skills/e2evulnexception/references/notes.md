# Important notes

- **Never commit or push** — only edit files locally. The user drives commits.
- **Secrets via Proton Pass** — both `scripts/startbackenddev.sh`,
  `scripts/startfrontenddev.sh`, and the test driver use `pass-cli run`.
  Never hardcode credentials.
- **No `localhost` literals in tests** — use `BASE_URL` / `FRONTEND_URL` /
  `SECMAN_BASE_URL`. Liveness checks use port binding, not HTTP probes.
- **Logs**: backend → `.e2e-logs/backend.log`; frontend → `.e2e-logs/frontend.log`;
  driver → `.e2e-logs/e2e-vuln-exception-run-<N>.log`. The directory is gitignored.
- **MariaDB**: cleanup uses `mariadb -h 127.0.0.1 -u secman -pCHANGEME secman`.
  MariaDB must be running.
- **Roles**: `e2etestuser1`/`e2etestuser2` MUST stay non-admin and non-secchampion
  so exception requests land as PENDING. If they ever get auto-approved, check
  the `roles` argument in Phase 1 and the auto-approve logic in
  `VulnerabilityExceptionRequestService`.
- **Overdue threshold** is `VulnerabilityConfig.reminderOneDays` (default 30).
  `vuln1` is 40d (overdue) and `vuln2` is 5d (not). If the threshold changes,
  update both this skill and the driver constants.
- **AWS sharing scope** — Phase 8 is the scope-leak guard. The sharing rule is
  created with `selectedAwsAccountIds=[A]` *while* user1 still only owns
  account A. After rule creation, account C is added to user1; the test
  asserts user2 still only sees A and B, never C. If that assertion ever
  flips, the scoping codepath in `AwsAccountSharingRepository`
  (`findSharedAwsAccountIdsByTargetUserId`) is broken and ALL existing scoped
  rules in production are silently leaking.
- **AWS sharing cleanup** — `cleanup()` deletes from `aws_account_sharing`
  (cascades to `aws_account_sharing_account` via V207's FK) and `user_mapping`
  by both `user_id` and `email` so future-user/PENDING mapping rows are also
  swept. Both run **before** the user delete because source/target user FKs
  are NOT NULL with no cascade.
- **Account IDs are 12 digits** — `UserMapping.awsAccountId` is validated by
  `@Pattern(regexp = "^\\d{12}$")`. The hard-coded test IDs
  (`123456789012` / `876543210987` / `555555555555`) satisfy that regex.
  If the constants change, keep them 12-digit numeric.
- **Phase 10 is destructive on real data**. Steps 10.2 and 10.7 issue
  `delete_all_vulnerability_exceptions`, which wipes every row in the
  DB — including any pre-existing real exceptions on the dev/test
  machine. Step 10.16 re-imports the baseline file captured at 10.1 to
  restore them. The trap cleanup then removes only test rows by
  `reason LIKE 'E2E TEST %'`. Never weaken the cleanup to match by
  `created_by` — that would nuke real admin-authored exceptions.

## Idempotency verification

Once green, **re-run the driver immediately** (same command) without doing any
cleanup yourself. The trap and pre-run cleanup should mean the second pass is
also green with zero fixes. If the second pass fails, treat it as a regression
in the cleanup logic and fix `cleanup()` in
`scripts/test/test-e2e-vuln-exception-full.sh`.
