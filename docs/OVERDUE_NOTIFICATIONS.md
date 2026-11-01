# Workgroup overdue notifications

`secman notify-users-overdue` emails workgroup members about overdue vulnerabilities
on the assets their workgroups cover. `secman show-notified-users --email X` shows,
for one user, exactly what that run would send and why. Command reference, options,
sample output and exit codes: [`CLI.md`](CLI.md#notify-users-overdue-and-show-notified-users--workgroup-overdue-digests).

This is a separate notification function. `send-notification-users` (and the MCP tool
`send_vulnerability_notifications`) keep their own recipient rule, flags and email.
Neither command calls the other, and they do not share a send log. A user who
qualifies under both can receive both emails. The two are distinguishable by subject:
`SecMan: Overdue vulnerabilities on your workgroup assets` for this function,
`SecMan: Vulnerability Alert for Your AWS Accounts` for the other.

## Recipient rule

User U is mailed about asset A when all of the following hold:

1. A has at least one **overdue** finding: visible in the current CrowdStrike snapshot,
   not excepted (`vulnerability.excepted = 0`), and first seen (`first_seen_at`, else
   `scan_timestamp`) more than `--days` days ago (default 30).
2. A is **current**: observed within the inclusive UTC window `[now - 72h, now]`.
   Falcon-managed assets use Falcon sensor contact, other assets `verified_observed_at`
   (the rule in [`CURRENT_ASSETS.md`](CURRENT_ASSETS.md)).
3. U is an **enabled** user.
4. U is a **direct member** of an **enabled** workgroup W whose name starts with
   the exact, case-sensitive prefix **`aws-`**, and at least one of these holds:
   - **`DIRECT_ASSIGNMENT`**: A is assigned to W.
   - **`AWS_WORKGROUP`**: W has a whole-account grant (`workgroup_aws_account`) for
     A's `cloud_account_id`. Assets that enter the account after the grant are included.

`aws-DevOps-example` qualifies; `AWS-Backend-Operations`, `Aws-DevOps-example`,
`root`, and `team-aws-example` do not. This applies to both paths. An asset reached
through eligible and excluded groups is counted once, with only eligible reasons.

Nothing else makes someone a recipient. These grant **nothing** here:

| Not a path | Why |
|---|---|
| AWS account owner mapping (`user_mapping`) | Decided for this function. Still a path for `send-notification-users` |
| Received AWS account sharing | Same decision. Still a path for `send-notification-users` |
| Asset `owner`, manual creator, scan uploader | Metadata, never access (CLAUDE.md §Unified Asset Access) |
| Membership of a parent workgroup | Hierarchy does not propagate membership |
| Disabled workgroup, disabled user | A disabled workgroup grants nothing; a disabled user is never mailed |
| ADMIN / SECCHAMPION role | Global visibility is not responsibility |
| AD-domain grants | AD-domain grants are outside this function, which covers asset assignment and AWS accounts only |

## Deduplication

- One email per recipient per run. Emails are compared trimmed and lower-cased.
- Each asset appears once per recipient, with every distinct reason
  (`DIRECT_ASSIGNMENT:<workgroup>`, `AWS_WORKGROUP:<workgroup>/<account>`). A user who
  reaches the same asset through an assignment and two account grants sees one row
  with three reasons. The finding counts are not multiplied.
- Once per UTC calendar day: a recipient with a successful delivery of this digest
  since 00:00 UTC (a `notification_log` row of type `OVERDUE_WORKGROUP_DIGEST`,
  status `SENT`) is skipped as `SKIPPED_ALREADY_NOTIFIED`. A failed delivery is
  logged as `FAILED` and does not block the next run.

## Edge cases

- **Workgroup without its own assets.** A workgroup with zero assigned assets but an
  AWS account notifies all its members about that account's overdue assets. See the
  reference case below.
- **Assets without an AWS account.** An asset with a null or blank
  `cloud_account_id` can reach users only through `DIRECT_ASSIGNMENT`.
- **Leading zeros in account ids.** AWS account ids are compared as 12-character
  strings, so leading zeros are kept.
- **No overdue findings.** An asset with only young or excepted findings produces no
  email. A user whose assets are all in that state gets `NO_OVERDUE_ASSETS`.
- **Unknown `--notification-user`.** The run sends nothing and exits `2`.
  `show-notified-users` for an unknown email reports `USER_NOT_FOUND` and exits `0`.

## Reference case

Production workgroup `aws-DevOps-uuorndojrd`, child of `root`, enabled, 4 members,
**0 assigned assets**, 1 AWS account, 0 AD domains. All 4 members receive every
overdue current asset in that account, with reason
`AWS_WORKGROUP:aws-DevOps-uuorndojrd/<account>`. Members of `root` alone receive
nothing from it. `WorkgroupOverdueNotificationIntegrationTest` reproduces this shape.

## Implementation

| Part | Location |
|---|---|
| Recipient SQL (one set-based query, bound parameters) | `WorkgroupOverdueRecipientQuery` |
| Overview, send loop, same-day guard, rendering | `WorkgroupOverdueNotificationService` |
| REST (ADMIN) | `GET /api/overdue-notifications/users?email=&days=`, `POST /api/overdue-notifications/send` |
| CLI | `ShowNotifiedUsersCommand`, `NotifyUsersOverdueCommand`, `OverdueNotificationCliService` |
| Email | `email-templates/workgroup-overdue-notification.{html,txt}`; asset names are HTML-escaped |

`show-notified-users` and `notify-users-overdue` read the same query result. The
overview equals the sender's dry run for that user, except that the overview also
reports all of the user's workgroups, including disabled ones and names without the
`aws-` prefix, to explain a `NO_PATHS` result. Their assigned-asset counts are
membership information, not affected-asset totals. Only eligible workgroups count
when deciding whether the user has a notification path.

The send loop is not transactional: each recipient costs one SMTP round-trip.
`AwsAccountRecipientResolver` is not used and is unchanged, so `send-notification-users`,
`send-notifications` and the EOL notifications keep their recipients.

Logs record the actor (CR/LF stripped), counts and outcome. The requested email is not
logged, and no mail body or credential is logged.
