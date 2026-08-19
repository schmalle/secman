# End-of-life (EOL) lifecycle management

Tracks which operating systems, installed software and repository dependencies in
secman's inventory have reached — or are about to reach — the end of vendor
support, surfaces that in the UI, and mails the owners before it bites.

---

## 1. Where the data comes from

### The source, and why

**Default: [endoflife.date](https://endoflife.date)** (`secman.eol.base-url`).

It was picked over the alternatives for four reasons:

| Candidate | Coverage | Auth | Verdict |
|---|---|---|---|
| **endoflife.date** | ~350 products: Windows / Windows Server, RHEL, Ubuntu, Debian, SLES, Amazon Linux, Oracle Linux, Rocky/Alma, ESXi, Java, .NET, Python, Node.js, Go, nginx, Apache, Tomcat, PostgreSQL, MySQL, MariaDB, Spring Boot, Django, Rails, Kubernetes … | none | **chosen** — one feed covers both the OS and the platform-software halves of this inventory |
| Microsoft Product Lifecycle API | Microsoft only, but authoritative and richer (LTSC/SAC channels) | Entra app registration | good *complement* for a Windows-heavy estate; a second integration, not a replacement |
| NVD / CISA KEV | vulnerabilities, not lifecycles | — | wrong data — knowing a CVE exists says nothing about support status |
| Per-vendor pages | authoritative | varies | one integration per vendor; unmaintainable |

If a Windows-heavy estate needs vendor-authoritative dates later, add the
Microsoft Lifecycle API as a **second `sourceKey`** rather than replacing this
one: `EolProduct` is already keyed on `(source_key, product_key)` for exactly
that.

For a restricted network, point `secman.eol.base-url` at an internal mirror and
add its host to `secman.eol.allowed-hosts` — the two are a deliberate pair.

### The fetch runs in the backend, not the CLI

Same shape as `import-github-repos`. It keeps the outbound host allowlist, the
SSRF checks and the audit record in one place, and it means the CLI host needs no
internet access to the source. The CLI triggers; the backend fetches.

---

## 2. Model

| Entity | Role |
|---|---|
| `EolProduct` | one catalogue product (`ubuntu`, `windows-server`, `java`), keyed `(source_key, product_key)`; `aliases` drives name matching |
| `EolRelease` | one release cycle of a product (`24.04`, `2019`, `17`) with its EOL date |
| `EolFinding` | one *matched* component that is EOL or approaching EOL |
| `EolSyncRun` | audit row per admin-triggered sync: actor, counts, outcome |

**Only EOL and approaching-EOL components are stored.** Persisting supported ones
would mean a row per installed product per asset — a seven-figure table for no
reporting value.

`EolFinding` is written **replace-per-run**: every row carries the run's
`scan_run_id`, and rows from earlier runs are deleted at the end of the scan. A
component that was upgraded simply is not reproduced.

> `Asset` deliberately owns **no cascade** to `EolFinding`. Mixing JPA cascade
> with a manual delete-insert is what silently dropped 99 % of rows in the
> CrowdStrike import (166,812 → 1,819). See CLAUDE.md §Transactional replace.

---

## 3. Matching

`EolVersionMatcher` is pure — no Micronaut, no JPA, no clock — which is what makes
the rules testable (`EolVersionMatcherTest`).

1. **Tokenize** the observed name: lowercase, drop parenthesised segments and
   architecture/edition noise (`x64`, `(64-bit)`, `Edition`).
2. **Resolve the product** longest-alias-first, shortening from the *right*:
   `windows server 2019` → tries `windows server 2019`, then `windows server`
   (hit), leaving `2019` as the version. Shortening from the right is what stops
   `microsoft sql server` resolving to a product called `server`.
3. **Resolve the cycle** by longest dot-segment prefix: `22.04.3` matches cycle
   `22.04`; `4.10` never matches cycle `4.1`, because segments compare whole.
4. **Classify** against today and the horizon.

Aliases come from the catalogue itself (upstream aliases, label, de-hyphenated
key) plus a small curated table for vendor spellings upstream does not carry
(`Red Hat Enterprise Linux` → `rhel`). Curated entries are **additive** — they
only fill gaps, never override a name upstream already owns.

### The matcher never guesses

A component whose version cannot be parsed, or whose version matches no cycle,
produces **no finding**. A false "end of life" on a production server costs more
than a miss: it lands on an owner's remediation list and erodes trust in the
whole report.

Two consequences worth knowing:

- **A version embedded in the name wins over the version field.** Windows Server
  reports a build number (`10.0.17763`) as its version, which matches no cycle;
  the `2019` in the name does.
- **A dateless EOL flag carries a null horizon, not zero days.** Upstream can
  mark a cycle EOL without publishing a date. That is reported as end of life,
  but never counted into "EOL within N months" — the owner mail promises a window
  and must not put undated cycles in it.

### Distribution-packaged builds are rejected, not matched

A version carrying a packaging revision — `1.18.0-6ubuntu14.4`,
`2026c-1.el8_10`, `3.4.1-11.amzn2`, `23.01+dfsg-11ubuntu0.1~esm1` — is supported
by the *distribution*, not by upstream. Canonical still supports nginx 1.18 long
after upstream dropped it, so an upstream cycle table says nothing about that
build. `EolVersionMatcher.hasDistroRevision` rejects those outright on the
component path.

This costs coverage on Linux hosts and is the right trade: the alternative is
reporting supported production software as end of life, which is the one failure
this matcher exists to avoid. The OS path is unaffected — `Ubuntu 22.04.3` is a
point release, not a packaging revision.

The **vendor** is checked too (`hasDistroVendor`). A `.deb`'s vendor field is its
maintainer, so `Ubuntu Developers <ubuntu-devel-discuss@…>` proves the component
is distribution-packaged even when the version carries no visible revision:
`node-arrify 2.0.1-2` looks like a plain version but is an Ubuntu npm-library
package, and matching it against the Node.js *runtime* lifecycle reported
"Node.js 2, end of life" for a library with no relation to the runtime's support
window. Red Hat and Amazon Linux are deliberately **not** vendor-blocked — their
packages carry `.el8` / `.amzn2` in the version and are caught by the revision
rule, and blocking those vendors would make `redhat-build-of-openjdk`
unmatchable.

### Labelled cycles are never matched from a bare version

A cycle carrying an edition or channel label — `10-1507`, `11-ltsc`,
`11-24h2-e-lts`, `2026c` — is skipped (`isNumericCycle`).

Cycle comparison keeps only the leading numeric run, so every labelled cycle
collapses to its major version: `10-1507` becomes `10`. A Windows Server 2022
build reports itself as `10.0.20348.3451`, which then prefix-matches `10-1507`
and is reported as **Windows 10 version 1507, end of life since 2017** — chosen
by nothing more than release list order. Which edition a bare build number
belongs to cannot be recovered without a build-to-release table the catalogue
does not publish.

This is why the `windows` product yields no findings from a build number, and
why Windows Server still does: its cycles (`2019`, `2022`) are plain numbers.

### Product names that collide with a shorter alias

Microsoft ships the .NET desktop runtime as **"Microsoft Windows Desktop Runtime
- 8.0.21"**. Resolution is longest-alias-first, but without an entry for the full
name the shorter `microsoft windows` alias wins and .NET 8 is reported as Windows
8, end of life since 2016. Curated entries for `microsoft windows desktop
runtime` and `windows desktop runtime` resolve it to `dotnet`.

The general lesson: when a vendor's product name *starts with* another product's
name, the longer form must be curated explicitly or the shorter one silently
captures it.

### Java is nine products, not one

endoflife.date publishes no generic `java` product. It publishes `oracle-jdk`,
`amazon-corretto`, `eclipse-temurin`, `azul-zulu`, `microsoft-build-of-openjdk`,
`redhat-build-of-openjdk`, `openjdk-builds-from-oracle`, `graalvm-ce` and
`oracle-graalvm`, because their support windows genuinely differ — Oracle JDK 8,
Corretto 8 and Temurin 8 all end on different dates.

Two rules follow, both in `EolVersionMatcher`:

- **Each observed spelling maps to the distribution that ships it.** A build
  whose distribution cannot be identified (a bare `OpenJDK Platform` with no
  recognised vendor) produces **no finding** rather than borrowing another
  vendor's date. Where the name alone is ambiguous the vendor decides, via the
  vendor-widened candidate the matcher already builds.
- **Legacy `1.x` versions are normalized.** Java numbered releases `1.x` up to
  Java 8 and plainly `x` from Java 9 on: `1.8.0_371` *is* Java 8. Catalogue
  cycles use the modern form, so `1.8.0.392` is rewritten to `8.0.392` before
  cycle resolution. Applied only to the Java distributions and only when the
  leading segment is exactly `1`; `1.4` becomes `4`, which no cycle carries, so
  it fails closed.

A curated alias whose target product key does not exist upstream is dropped
silently at index build time. That is deliberate — it lets speculative entries
be harmless — but it also means **a typo'd or non-existent target disables the
alias with no error**. Aliases pointing at a `java` key behaved exactly that way
and made Java unmatchable; check a new target against `eol_product.product_key`
before relying on it.

`ASP .Net Core` maps to `dotnet`: it has no product of its own upstream and its
versions track .NET exactly (ASP.NET Core 8.0.29 ships with .NET 8.0). Note the
alias is `asp net core` — tokenization strips the leading dot.

### Repository dependencies are an under-approximation

A Dependabot alert names the vulnerable *range*, never the version resolved in
the lockfile. The scan uses the range's upper bound: from `< 4.17.21` it knows
the dependency is below 4.17.21, so **if the cycle containing 4.17.21 is already
EOL, whatever is installed is in that cycle or an older one and is EOL too.**

That direction only ever under-reports, which is the correct way to be wrong
here. Consequently:

- only `EOL` is recorded for repository components — "approaching" says nothing
  about the older version actually in use;
- a range with no parseable upper bound is skipped, not guessed at.

---

## 4. Access control

| Surface | Who | Boundary |
|---|---|---|
| `GET /api/eol/findings`, `/summary`, `/assets/{id}` | any authenticated user | **asset-scoped** via `AssetFilterService` / `AccessibleAssetIdsCache` |
| `GET /api/eol/products/{product}/assets` | any authenticated user | **asset-scoped**, same cache; backs the systems-affected-by-product drilldown page |
| `GET /api/eol/catalog/status` | any authenticated user | reference data only, no per-tenant rows |
| `GET /api/eol/repositories/top` | ADMIN, SECCHAMPION | mirrors `GithubRepositoryController` |
| `POST /api/eol/catalog/sync` | ADMIN | logs actor + outcome |
| `POST /api/eol/notifications/send` | ADMIN | logs actor + outcome |
| `GET/POST /api/admin/email-broadcast/eol/*` | ADMIN, SECCHAMPION | "Contact affected owners" compose-and-send from the product drilldown page; see §6 |

Every asset-scoped read resolves the caller's accessible asset ids **first** and
passes them as a bound `IN` list. `EolFindingRepository` has no unscoped
"find all findings" read used by a user-facing path — the table denormalizes
hostnames, owners and account ids, so an unscoped variant would be one careless
call site away from a cross-tenant leak.

`GET /api/eol/assets/{id}` answers **404** for an asset outside the caller's
scope — identical to a nonexistent id, so an out-of-scope id is not
distinguishable from a missing one.

---

## 5. Notifications

`secman send-eol-notifications` (→ `POST /api/eol/notifications/send`) groups
findings whose EOL date falls in the next `--months` months (default 12) by
recipient and sends one consolidated mail each.

Two safety valves, both ADMIN-only like the rest of this endpoint:

- **`--dry-run`** resolves every recipient and their findings exactly as a real
  run would, but sends no mail — `EolNotificationResponse.dryRun` is echoed
  back and every `EolNotificationRecipientResult.sent` is `false`. Use it to
  preview who is about to be mailed before committing to a real run.
- **`--only-email <address>`** restricts *delivery* to one address
  (case-insensitive); every other resolved recipient is dropped before send
  and does not appear in the response at all. This is how one account owner
  can be notified — or re-notified after a delivery failure — without mailing
  the rest of the estate. It composes with `--dry-run`: `--only-email` alone
  narrows who is *mailed*, `--dry-run` alone narrows to *nobody*, and both
  together preview exactly what that one recipient would receive.

Recipients reuse `AwsAccountRecipientResolver` — the single source of truth for
"who owns this AWS account", already shared by the vulnerability and
outdated-asset mails: the account's `UserMapping` owners, members of workgroups
holding assets in the account, and users the account is shared with. Systems with
no cloud account fall back to `Asset.owner` resolved through `UserRepository`.
**No bespoke ownership model is introduced.**

Addresses are **rejected, not repaired**. The value becomes an SMTP recipient, a
stored result and a log line — three sinks from one string — so CR/LF, commas,
semicolons and angle brackets are disqualifying. Component names and hostnames
come from imported inventory and are HTML-escaped and CR/LF-stripped before they
reach the body or a log line.

---

## 6. Where it shows up

| Place | What |
|---|---|
| **Vulnerability Management → End of life** (`/vulnerabilities/eol`) | the main view: counters, per-account rollup, filterable findings table |
| Same page, ADMIN/SECCHAMPION only | **Top 10 repositories with the most EOL components** |
| Same page, ADMIN only | "Sync catalogue & rescan" button |
| **Vulnerability Statistics** (`/vulnerability-statistics`) | "Top 10 Most Often EOL Products" table; each row links to the drilldown below |
| **EOL product systems** (`/vulnerabilities/eol/products/{product}`) | every accessible system a product is EOL/approaching-EOL on, plus (ADMIN/SECCHAMPION) a **Contact affected owners** button — compose a message in-browser and mail every owner/creator/uploader/mapped-user of those systems, reusing `HtmlEditor` and the async broadcast-job machinery from the admin product-notify feature. Distinct from `secman send-eol-notifications` (§5): this is ad-hoc, one product, admin-authored copy; that is scheduled, horizon-based, and templated |
| **Installed products** (`/installed-products`) | a **Lifecycle** badge per row, linking through to the EOL view |
| Sidebar → Vulnerability Management | "End of life" entry |

---

## 7. Operating it

```bash
# One-off: download the catalogue and match the inventory
secman eol-sync --verbose

# Refresh a subset cheaply
secman eol-sync --products ubuntu,rhel,windows-server

# Re-match without re-downloading (e.g. after a CrowdStrike import)
secman eol-sync --scan-only

# Who would be notified, without sending anything
secman send-eol-notifications --dry-run --verbose

# Preview exactly what one owner would receive, without sending
secman send-eol-notifications --dry-run --only-email owner@example.com --verbose

# Notify (or re-notify) just that one owner, e.g. after a delivery failure
secman send-eol-notifications --only-email owner@example.com --verbose

# Send to everyone
secman send-eol-notifications --months 12
```

Suggested schedule: `eol-sync` nightly (the catalogue changes slowly, the
inventory does not), `send-eol-notifications` monthly.

### Configuration

| Key | Env | Default |
|---|---|---|
| `secman.eol.base-url` | `SECMAN_EOL_BASE_URL` | `https://endoflife.date` |
| `secman.eol.allowed-hosts` | `SECMAN_EOL_ALLOWED_HOSTS` | `endoflife.date` |
| `secman.eol.horizon-months` | `SECMAN_EOL_HORIZON_MONTHS` | `12` |
| `secman.eol.timeout-seconds` | `SECMAN_EOL_TIMEOUT_SECONDS` | `20` |
| `secman.eol.max-response-bytes` | `SECMAN_EOL_MAX_RESPONSE_BYTES` | `8388608` |
| `secman.eol.max-products` | `SECMAN_EOL_MAX_PRODUCTS` | `2000` |
| `secman.eol.scan-page-size` | `SECMAN_EOL_SCAN_PAGE_SIZE` | `500` |

`base-url` and `allowed-hosts` are a pair. Changing one without the other fails
closed — that is the SSRF guard working, not a bug.

---

## 8. Testing

- `EolVersionMatcherTest` — matching and classification rules (pure, no DB).
  EVM-015..018 cover the application path (Java distributions, the legacy `1.x`
  scheme, the .NET family); EVM-019 pins the distro-revision rejection in both
  directions; EVM-020 pins that the OS path is unchanged by those guards;
  EVM-021 pins the labelled-cycle refusal with the real Windows Server 2022 build
  number; EVM-022 the maintainer-vendor refusal; EVM-023 the .NET desktop runtime
  not resolving to Windows
- `EolCatalogClientTest` — SSRF URL validation and product-key sanitization
- `EolNotificationBoundaryTest` — recipient address boundary
- `EolScanUpperBoundTest` — Dependabot range upper-bound selection
- `eolFormat.test.ts` — frontend presentation helpers
- `/e2eeol` (`scripts/test/test-e2e-eol.sh`) — full lifecycle against a running
  stack, including the authorization negatives

The E2E driver reports **SKIP**, not FAIL, when the backend cannot reach the
upstream source: an air-gapped runner is not a broken feature. A run whose
catalogue assertions all skipped is a partial result, not a clean pass.

## Installer / setup payload findings

`EolFinding.productClass` marks findings whose subject is an installer payload rather than deployed
software — `Chrome Installer` (222 findings), `Photon Setup` (24), `SQL Server Setup Bootstrapper`
(9): 255 of 4210 `ASSET_PRODUCT` findings, ~6%. They are hidden from `GET /api/eol/findings`, the
summary rollups, the product drilldown and the owner notification mail unless the caller passes
`includeInstallerFindings=true` (a checkbox on the EOL dashboard, which badges the rows when shown).

`ASSET_OS` and `REPOSITORY_COMPONENT` findings are always `INSTALLED` — an operating system is not a
cached payload and a repository dependency is not a file on disk.

Rules are ADMIN-managed under `/admin/product-classification`; the classification itself, and why it
keys on product identity rather than the installation path, is documented in
`docs/CROWDSTRIKE_IMPORT.md` §Installer / setup payload classification.
