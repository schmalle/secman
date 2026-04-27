# Contract: CLI manage-user-mappings --send-email

**Feature**: 085-cli-mappings-email
**Date**: 2026-04-08

This contract defines the two interfaces this feature exposes: (1) the new REST endpoint consumed by the CLI, and (2) the CLI command surface (flags, arguments, exit codes, output).

---

## 1. REST Endpoint: `POST /api/cli/user-mappings/send-statistics-email`

**Controller**: `CliController.kt` (add new method)
**Authorization**: `@Secured("ADMIN")` (inherited from class-level annotation at `CliController.kt:31`)
**Content-Type**: `application/json` (request + response)

### Request

```http
POST /api/cli/user-mappings/send-statistics-email HTTP/1.1
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "filterEmail": "foo@example.com",   // optional
  "filterStatus": "ACTIVE",           // optional, one of "ACTIVE" | "PENDING" | null
  "dryRun": false,                    // optional, default false
  "verbose": false                    // optional, default false
}
```

**Field definitions**:

| Field | Type | Required | Description |
|---|---|---|---|
| `filterEmail` | string? | no | Restrict the emailed report to mappings owned by this user email. |
| `filterStatus` | string? | no | Restrict to `ACTIVE` or `PENDING` mappings. |
| `dryRun` | boolean | no | If `true`, skip SMTP dispatch but still compute statistics and write an audit row with `status=DRY_RUN`. |
| `verbose` | boolean | no | Server-side: emit per-recipient log lines at INFO level. |

**Validation**:
- Invalid `filterStatus` → `400 Bad Request` with JSON `{"error": "Validation Error", "message": "Invalid status: ..."}`
- Any other body malformation → `400 Bad Request`

### Response — Success (200 OK)

```json
{
  "status": "SUCCESS",
  "recipientCount": 3,
  "emailsSent": 3,
  "emailsFailed": 0,
  "recipients": ["alice@ex.com", "bob@ex.com", "carol@ex.com"],
  "failedRecipients": [],
  "appliedFilters": {
    "email": "foo@example.com",
    "status": "ACTIVE"
  },
  "aggregates": {
    "totalUsers": 12,
    "totalMappings": 34,
    "activeMappings": 28,
    "pendingMappings": 6,
    "domainMappings": 20,
    "awsAccountMappings": 14
  }
}
```

**`status` values** (mirrors backend `ExecutionStatus` enum):

| Value | Meaning | HTTP code |
|---|---|---|
| `SUCCESS` | Every eligible recipient received the email | 200 |
| `PARTIAL_FAILURE` | ≥1 sent, ≥1 failed | 200 |
| `FAILURE` | 0 sent (with ≥1 recipient attempted) OR 0 eligible recipients | 200 |
| `DRY_RUN` | `dryRun=true` was set; no emails dispatched | 200 |

**Rationale for 200 on all non-exception outcomes**: The CLI maps `status` to its own exit code; HTTP status is reserved for endpoint-level errors (auth, validation, server exceptions).

### Response — Authorization denied (403 Forbidden)

```json
{
  "error": "Forbidden",
  "message": "ADMIN role required"
}
```

Emitted by Micronaut Security when the JWT lacks the ADMIN role. The CLI maps this to exit code **2**.

### Response — Validation error (400 Bad Request)

```json
{
  "error": "Validation Error",
  "message": "Invalid status filter: BOGUS (use ACTIVE or PENDING)"
}
```

### Response — Server error (500 Internal Server Error)

```json
{
  "error": "Internal Server Error",
  "message": "Failed to send user-mapping statistics email"
}
```

### Side effects

On every successful endpoint invocation (including dry-run and zero-recipient cases), a row is inserted into `user_mapping_statistics_log` capturing `invokedBy`, filters, aggregates, `recipientCount`, `emailsSent`, `emailsFailed`, `status`, and `dryRun`. If the audit insert itself fails, the service logs a warning but does **not** fail the request (matches `AdminSummaryService.logExecution` behavior at `AdminSummaryService.kt:260-278`).

---

## 2. CLI Command Surface

### 2.1 Modified command: `manage-user-mappings list`

**Binary**: `./scriptpp/secman manage-user-mappings list [options]`
**Picocli class**: `src/cli/src/main/kotlin/com/secman/cli/commands/ListCommand.kt`

### 2.2 New flags (added to `ListCommand`)

```kotlin
@Option(
    names = ["--send-email"],
    description = ["Email the statistics report to all ADMIN and REPORT users. Console output is still printed."]
)
var sendEmail: Boolean = false

@Option(
    names = ["--dry-run"],
    description = ["Used with --send-email: print intended recipient list without dispatching any email."]
)
var dryRun: Boolean = false

@Option(
    names = ["--verbose", "-v"],
    description = ["Used with --send-email: print per-recipient send status."]
)
var verbose: Boolean = false
```

### 2.3 Existing flags (unchanged)

```kotlin
@Option(names = ["--email"]) var email: String? = null
@Option(names = ["--status"]) var statusFilter: String? = null
@Option(names = ["--format"], defaultValue = "TABLE") var format: String = "TABLE"
```

Plus the parent command's `--username`, `--password`, `--backend-url`, `--insecure` inherited via `@ParentCommand`.

### 2.4 Behavior

1. **Always**: Authenticate against backend, fetch mappings via existing `userMappingCliService.listMappings(...)`, print to console per `--format` (TABLE/JSON/CSV). Behavior **unchanged** when `--send-email` is not set.
2. **If `--send-email`**: After printing, POST to `/api/cli/user-mappings/send-statistics-email` with `filterEmail`, `filterStatus`, `dryRun`, `verbose` derived from the same flags. Parse response, print summary, exit with appropriate code.
3. **If `--dry-run` without `--send-email`**: Error — `--dry-run` is meaningless without `--send-email`. Print `Error: --dry-run requires --send-email` to stderr and exit with code 1.
4. **If `--verbose` without `--send-email`**: Silently ignored (no-op) — Picocli allows extra flags; a warning would be noisy.

### 2.5 Exit codes (new contract)

| Code | Meaning | Trigger |
|---|---|---|
| 0 | Success | Default `list` (no `--send-email`), OR `--send-email` with server `status=SUCCESS` or `status=DRY_RUN` |
| 1 | Generic error | Network failure, JSON parse error, unexpected exception, `--dry-run` without `--send-email` |
| 2 | Authorization denied | Server returned `403` |
| 3 | No eligible recipients | Server returned `status=FAILURE` AND `recipientCount=0` |
| 4 | Partial failure | Server returned `status=PARTIAL_FAILURE` |
| 5 | Full failure | Server returned `status=FAILURE` AND `recipientCount>0` |

**Backward compatibility**: Exit codes 2–5 are only emitted when `--send-email` is set. Without it, the command's exit behavior is unchanged (0 on success, 1 on any error — FR-014).

### 2.6 Console output additions (when `--send-email` is set)

After the existing TABLE/JSON/CSV body, a new block is appended to stdout:

**Dry-run**:
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

**Success**:
```
============================================================
Email Distribution
============================================================
Recipients: 3
Emails sent: 3
Failures: 0
Statistics delivered successfully.
```

**Partial failure** (+ verbose or non-verbose):
```
============================================================
Email Distribution
============================================================
Recipients: 3
Emails sent: 2
Failures: 1
Failed recipients:
  - carol@example.com
Email distribution completed with failures.
```

**No eligible recipients**:
```
============================================================
Email Distribution
============================================================
No eligible recipients found.
Reason: no users with ADMIN or REPORT role have a valid email address.
```

### 2.7 Help text contracts

**`manage-user-mappings list --help`** must include the `--send-email`, `--dry-run`, and `--verbose` options with the descriptions above. Verified by FR-011, FR-012, SC-006.

**`manage-user-mappings --help`** (top level) must update the `list` subcommand description from:
> "List existing user mappings"

to something like:
> "List existing user mappings; optionally email statistics to ADMIN/REPORT users via --send-email"

This satisfies FR-012.

---

## 3. Contract Stability

This contract is additive — no existing endpoint, CLI flag, or exit code is changed. The `list` subcommand's default behavior (no `--send-email`) is byte-identical to the pre-feature baseline (SC-005, FR-014).
