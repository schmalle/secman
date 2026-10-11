# AI buddy patch guide: scoped source review

Date: 2026-10-11. Scope: the single-click guide, Mozilla evidence adapter, HIGH/CRITICAL snapshot restriction, optional temporary inventory input, report parsing and clipboard output added in this task. The checkout contains other pre-existing changes; this is not a review of that entire branch.

## Findings and controls

- A01: No authorization scope was broadened. The existing authenticated controller delegates starts to `AiBuddyAccessGuard` through `AiBuddyJobService.start`; every owned-job read/cancel still rechecks current roles and asset access. Temporary inventory belongs only to the authorized user's ephemeral job. No records or permissions are written.
- A02/A07: No new credentials, credential storage or authentication mechanism. Mozilla requests are public, and inventory fields exclude hostnames, IPs, account IDs and usernames. Clipboard output is an explicit local user action over an already authorized report.
- A03: The snapshot severity restriction uses fixed SQL literals with existing bound asset/cursor arguments. Inventory uses closed field and enum lists plus numeric bounds. All advisory titles and guide content render as React text; no HTML sink or executable model command was added.
- A04: Existing finding, inventory, report, job and evidence budgets remain active. Mozilla bodies are capped (catalogue 128 KiB, index 3 MiB, advisory 1 MiB), and advisory reads are limited to 24. Release/ESR and ESR major branches are checked separately. Ambiguous multi-product/branch advisories do not establish coverage. Missing facts and user confirmation never remove platform, servicing or current-inventory requirements.
- A05/A06/A08: No dependencies, database migration or security-header change. Existing Jsoup and strict JSON parsing are reused. The controller keeps its 1 KiB request cap; optional inventory does not accept arbitrary fields or HTML. JSON collections and nested report data are checked before rendering, and KB/version identifiers are constrained.
- A09: Existing actor/asset/job admission and generic errors are preserved; no supplied facts or secrets are added to logs. Evidence failure is visible as a limitation, and copied guides retain conditional status, expiry, scope and user-supplied-inventory disclosure.
- A10: Requests use `AiBuddyHttpTransport`, which validates HTTPS, fixed allowed hosts, public-address resolution and port/userinfo restrictions, and never follows redirects. Hosts are only `www.mozilla.org` and `product-details.mozilla.org`; advisory paths are matched against a fixed MFSA regex before a request. Displayed/copied links have equivalent HTTPS host/path restrictions. No user inventory field becomes a URL.

No HIGH or CRITICAL issue was identified in this scoped source review. This is a manual static review, not runtime or adversarial test evidence.

## Verification status

`./scripts/owasp-check.sh`: 0 BLOCK, 29 REVIEW notices across the wider dirty checkout. The touched controller notice about broad authenticated access was reviewed: `AiBuddyAccessGuard` enforces current BETA eligibility and asset authorization inside each operation. No control was relaxed to silence a notice; the other notices are outside this task.

Regression cases were written for product aliases, vendor fixed-version parsing, numeric version order, inventory input bounds, conditional step templates, safe links, malformed wire shapes and HIGH/CRITICAL snapshot filtering. They were not executed. Compilation, live report generation, browser interaction checks and service restart were not performed because the session retains the user's no-tests constraint and this task leaves the running stack alone. Before release, exercise the one-click flow, inventory completion, vendor failure/empty results, clipboard output, scope counts and current authorization against an isolated stack.
