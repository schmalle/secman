# Request product exceptions for multiple AWS accounts

Open **Vulnerability Exceptions → Request product exceptions**. Select the accounts, paste one product label per line, select an inclusive UTC end date, and supply a business justification of 50–2048 characters. Use **Find labels** to search verified labels in the visible inventory of the selected accounts. Exact labels and wildcard patterns have separate input boxes. The same products apply to every selected account.

## Product patterns in Create Exception

For a single exception, open **Create Exception**, choose **vulnerabilities for product**, and select the scope. Enter `*net*,*oracle*` to except findings with an individual product label containing either “net” or “oracle”, ignoring ASCII case. Spaces after commas are optional. A plain name retains the existing substring match. Quote a name that contains a comma, for example `"Vendor, Enterprise",*oracle*`. The entries share one approval, justification and expiration. Wildcards match individual product labels. For older findings without label data, Create Exception also matches a stored product name without a comma. A comma in the older display field makes individual names ambiguous, so these findings need a refreshed import for wildcard matching.

Migration V309 adds nullable pattern-list storage to requests and active rules. Exports containing a list use schema version 3.

## CLI manifest

Save this example as `exception.json` and replace the example account IDs and reason.

```json
{
  "version": 1,
  "accounts": ["000000000001", "000000000002"],
  "products": {
    "exact": [".NET Framework 4.8", "SQL Server 2019"],
    "glob": [".NET Core 8.0.*"]
  },
  "through": "2027-03-29",
  "reason": "The account owner approved the migration window documented in the change record."
}
```

An exact selector matches the entire individual product label. ASCII case and surrounding spaces are ignored. Punctuation and version boundaries matter. An asterisk in the wildcard box matches zero or more characters in one label. For example, the example pattern matches a label ending in 8.0.12. Add the bare 8.0 label separately to cover it. Characters such as underscore, percent and question mark are literal. Keep 12-digit AWS IDs as strings.

## Review and submission

**Preview impact** shows visible findings and assets by account, the additional findings that approval would cover, and examples for each selector. Overlapping selectors can count the same finding in their individual totals. The account totals count each finding once. Unresolved findings lack verified individual product labels. A fresh CrowdStrike import with the updated importer supplies those labels. Historical joined strings and free-form spreadsheet values stay unresolved. A selector with zero matches can apply to a future import.

Preview IDs belong to their requester and expire after 15 minutes. Each requester can keep their ten most recent previews. A preview that exceeds the scan budget returns an error, so incomplete counts cannot be submitted. The inventory can change after preview. SUBMIT sends an ordinary user’s request for approval. ADMIN and SECCHAMPION submissions take effect immediately when authorized for every account. Save the batch ID on the receipt. A rejected batch can be copied into a new draft for revision. Reviewers can open the batch from Exception Approvals. A rejection records the reason. The requester can cancel a pending or self-auto-approved batch. ADMIN can revoke an approved batch. Independent matching rules still apply.

An end date of 29 March 2027 includes the whole UTC day. The exclusive endpoint is midnight at the start of 30 March. Live filters check the coverage end timestamp on read. Previously generated reports and cached statistics retain their snapshot timestamp.

The API accepts up to 50 accounts, 100 selectors and 1,000 unique combinations per draft. Errors apply to the whole request. Refresh a stale preview, fix inaccessible accounts, or inspect the receipt before retrying. The CLI prints JSON on stdout with `--output json`. Exit codes are 0 for a successful operation, 2 for invalid input, 3 for access failure, 4 for conflict, and 1 for other failures.

[CLI commands](CLI.md) · [MCP tools](MCP.md)

## Deployment and backup

Deploy the backend with Flyway migrations V307 and V308, then deploy the frontend and rebuild the importer CLI. V307 adds storage for individual source labels. V308 adds batch ownership, decisions, rule provenance and the materialized coverage end time. Existing product exceptions retain their legacy substring semantics. Run the usual authorized inventory import to populate source labels and use preview to check the unresolved count before requesting coverage.

Exports containing AWS batch selectors use schema version 2, or version 3 if they also contain a Create Exception product list. The receiving backend must support the exported version. The rule export preserves matching mode, account, reason and expiry. A restore creates independent effective rules. Use a database backup to preserve batch receipts and approval history across environments.
