# AI buddy(beta): implementation plan

Status: planning only; no application implementation or runtime validation performed.
Repository inspected: `dev`, commit `5a330dd8`, 2026-10-09. Initial working tree was clean. Recheck these references and the migration sequence before implementation.

## Configuration update — 2026-10-10

The user's implementation correction supersedes this plan's environment-based AI buddy settings: use the active existing `TranslationConfig` managed at `/admin/translation-config`. Reuse its credential, base URL, model and token limit, and extend the same record/form with beta enablement, approved providers, single-instance acknowledgment, budgets and evidence switches. `V285__openrouter_ai_buddy_settings.sql` adds safe disabled defaults; the existing encryption converter protects saved common keys and reads legacy plaintext until it is next saved. There is no AI buddy env fallback. Current implementation/operational details are in `docs/AI_BUDDY.md`; the original design below remains historical where it differs.

## 1. Feature and decisions

Add an optional **AI buddy(beta)** action to the existing single-system vulnerability views. It produces a temporary, structured remediation report for the complete recorded finding set of one authorized asset. Consolidate findings into shared remediation actions when independently validated evidence permits it; show unresolved findings and missing applicability information explicitly. OpenRouter helps select and organize evidence-backed candidates. SecMan determines scope, validates relationships, calculates coverage, and supplies system identity locally.

Success means a fixture with 150 CVEs and supported cumulative-update relationships produces a smaller, validated action set, while an equivalent fixture lacking build or prerequisite evidence produces conditional actions and explicit gaps. A model returning plausible text is not success.

Non-goals: installing anything, executing commands, changing vulnerability/exception status, remediation tickets or orchestration, fleet analysis, unrestricted chat, a feature-flag platform, permanent report history, exports, new software-inventory collection agents, and a general web crawler. Do not add MCP access in this beta. No generated report may mark findings resolved.

Recommended decisions, requiring no product clarification:

- `BETA` is additive; `ADMIN` and `SECCHAMPION` are eligible implicitly. Asset authorization remains independent.
- Analyze recorded data, not unsaved live lookup rows or visible table pages. Include exceptions as labeled accepted-risk findings; omit explicitly classified installer artifacts from remediation targets, with counts.
- Use asynchronous jobs with simple authenticated polling and a reusable inline report panel. Reuse existing libraries and HTTP/configuration conventions; do not reuse the risk-assessment write workflow.
- Keep jobs and reports in bounded backend memory, owner-scoped, for 30 minutes after completion. No cross-user report cache and no report table. Initial deployment must use one backend instance for this feature (see §11).
- Obtain evidence through narrowly implemented provider/vendor adapters. Do not use model browsing, an `:online` suffix, arbitrary URL retrieval, or model memory as patch evidence.
- Ship safe uncertainty: exact applicability often cannot be established with current inventory. Missing evidence must reduce the claim, never be filled by inference.

## 2. Repository findings and change anchors

Paths below are repository-relative. For compact references, **B** means `src/backendng/src/main/kotlin/com/secman/`, **F** means `src/frontend/src/`, and **S** means `src/shared/src/main/kotlin/com/secman/crowdstrike/`. Every B/F/S path expands under these roots; new files are explicitly marked proposed.

### Authorization, persistence and sessions

| Existing file / symbol | Observed behavior and implication |
| --- | --- |
| `B/domain/User.kt`, `User.Role`, `roles` | Actual roles: USER, ADMIN, VULN, RELEASE_MANAGER, REQ, RISK, SECCHAMPION, REQADMIN, REPORT. Eager element collection in `user_roles`; enum strings mapped to VARCHAR `role_name` with `@JdbcTypeCode(SqlTypes.VARCHAR)`. Add BETA without changing defaults or existing roles. |
| `B/controller/UserController.kt`, create/update role parsing | Uses `User.Role.valueOf(roleString.uppercase())`; preserves explicit roles and validates last-admin removal. User management remains ADMIN-only. Do not turn BETA into a user-management permission. |
| `F/components/UserManagement.tsx`, `AVAILABLE_ROLES`, create/edit forms | Explicit role list drives checkboxes and filters. Add BETA with additive-access wording. Saving must retain other selected roles. |
| `B/security/AuthenticationExtensions.kt`, `hasAnyRole`; `F/utils/permissions.ts` | Existing small reusable role helpers; extend here for beta eligibility rather than adding a flag framework. |
| `B/service/AssetFilterService.kt`, `canAccessAsset`, `getAccessibleAssetIds`; `B/repository/AssetAccessSql.kt` | ADMIN/SECCHAMPION shortcuts use authentication claims. Other access uses current workgroup membership, enabled grants, personal AWS/AD mappings, directional AWS sharing. Creator/owner/uploader are not grants. BETA must never enter global-visibility checks. Pass freshly reconstructed authentication to this service for AI operations. |
| `B/controller/AuthController.kt`, `refresh`, authenticated user response; `B/security/AuthenticationProviderUserPassword.kt` | Refresh reads database roles and issues a new HttpOnly cookie. Existing tokens may carry old roles. Resolve the current enabled user at AI boundaries instead of trusting those role claims. |
| `F/utils/auth.ts`, `refreshToken`; `F/utils/useClientAuth.ts`, `useClientRoles` | Refresh does not replace `sessionStorage.user`; role hook reads it only after mount. A new BETA assignment can remain invisible to the UI even after token refresh. Add a live beta-capability read for this feature; do not require logout or broaden every existing permission helper. |
| `src/backendng/src/main/resources/db/migration/V197__widen_native_enum_columns_to_varchar.sql`, V198 | Historical migration refers to `user_roles.role`, while current mapping uses `role_name`. Source inspection alone does not prove an existing deployment's column shape. Verify both fresh/upgraded schema in isolated tests and a read-only rollout preflight; do not blindly alter the historical column. |

No separate tenant discriminator was found on User or Asset. Workgroup/account/domain scope is the present boundary; this is not permission to bypass tenant restrictions elsewhere or invent a new tenant model. Any future tenant predicate must remain part of the shared asset policy.

### Vulnerability and inventory evidence

| Source / exact anchors | Available information | Limits and trust |
| --- | --- | --- |
| `B/domain/Asset.kt` | `osVersion`, type, `lastSeen`, `crowdStrikeLastImportedAt`, `crowdStrikeAgentSeenAt`, created/updated timestamps, cloud/network metadata | OS is one free-text field, sometimes only platform. No dedicated build, edition, architecture, installed KB list, pending reboot, or servicing-stack inventory. Names, addresses, account/domain metadata stay local. |
| `B/domain/Vulnerability.kt` | `vulnerabilityId`, severity, `vulnerableProductVersions`, source, scan/import timestamps, `firstSeenAt`, patch/CVE publication dates, `excepted`, product classification | No stored exact remedy, installed-update evidence, provider finding ID distinct from CVE, CVSS numeric score, resolution status, or description in this entity. `patchPublicationDate` can fall back to CVE disclosure date: not proof that a patch exists. First-seen/SLA anchors and discovery/import times are distinct. |
| `S/dto/CrowdStrikeVulnerabilityDto.kt`; `S/client/CrowdStrikeApiClientImpl.kt`, mapping and `extractPatchPublicationDate` | DTO has provider finding `id`, CVE, score, status, product text, detection/publication dates, host OS and identifiers. Raw parsing accesses `apps`, `host_info`, `cve` and remediation dates. | `apps[].product_name_version` becomes joined text; tuple boundaries can be lost. Detailed remediation identifiers/relationships are not retained by this DTO. Shared `S/model/Vulnerability.kt` has `remediation`, but that does not make it persisted in backend Vulnerability. Do not conflate these models. |
| `B/service/CrowdStrikeVulnerabilityImportService.kt`, entity creation and scoped deletion; `B/service/VulnerabilityPersistenceHelper.kt`, replace path; `src/cli/src/main/kotlin/com/secman/cli/service/VulnerabilityStorageService.kt` | Current import paths maintain recorded rows and prior anchors. CrowdStrike replacement can be scoped by source/severity; other paths have replacement behavior. | Provider finding IDs/remedy details disappear on persistence. Record IDs can change on reimport. Never use a vulnerability row ID as permanent provider identity. Do not change replacement, reconciliation, or cascade rules for this feature. |
| `B/domain/CrowdStrikeAssetIdentity.kt`; `B/repository/CrowdStrikeAssetIdentityRepository.kt` | Stable stored AID-to-asset association, source hostname, provider first/last seen | One asset may have multiple AIDs. Use all verified linked identities; never discover a different asset by a user-supplied hostname and assume it belongs to the requested system. AIDs never reach OpenRouter. |
| `B/domain/InstalledProduct.kt`; `S/dto/InstalledProductDto.kt`; `B/service/InstalledProductImportService.kt` | Name/vendor/version/category, product class, installation/update/use dates, import time/run ID; external ID, AID, path | Optional, independently imported inventory, not necessarily contemporaneous with findings. `installedAt` is product observation, not installed KB proof. Never send installation paths, AIDs or external IDs to the model. UNKNOWN classification remains included. |
| `B/domain/IntegrationFinding.kt`; `B/service/IntegrationScanService.kt`, projection updates; `B/repository/IntegrationRepository.kt`; `docs/INTEGRATION_RESULTS.md` | Rich recommendation/evidence, source/model/engine, OPEN/RESOLVED state, timestamps, vulnerability projection; code/URL/location metadata | Integrations project OPEN findings into Vulnerability and remove projections on resolution. Recommendations may themselves be AI-generated: provider provenance alone does not verify them. Enrich only the selected asset's matching projections. Do not send descriptions, code, file paths, URLs, attachments or repository identity to OpenRouter. |
| `B/domain/EolFinding.kt`; `B/service/EolCatalogClient.kt`; `docs/ENVIRONMENT.md` | Product lifecycle context and detected time; configurable lifecycle catalogue | Supplementary upgrade/migration warning, separately counted. Catalogue match is not vendor patch applicability. Do not double-count EOL rows as CVEs. |
| `B/repository/ExceptionMatchSql.kt`; `B/domain/VulnerabilityException.kt`, `matches`; `B/service/ExceptionMaterializationService.kt` | Shared exception kind/subject/scope semantics and materialized flag | `excepted` can lag expiration; determine active match at snapshot time using existing predicate semantics. NO_EDR is not CVE suppression. Do not send exception reasons/approvers externally. |

Other import sources can have less evidence than CrowdStrike. Preserve provenance, unknown dates and unknown versions. A stored record is trustworthy evidence of **what SecMan recorded**, not proof of current endpoint state or vendor applicability.

### Existing AI, HTTP and tests

- `B/config/AiRiskAssessmentConfig.kt` defines `OpenRouterConfig` (`secman.openrouter`, key/base URL). `B/service/ComplianceAssistantService.kt` has JDK HttpClient, dedicated `ai` executor, retry/cost/cache patterns. `application.yml` already declares that executor. Reuse those configuration and transport conventions, with the stricter controls in §9.
- `B/service/TranslationService.kt` and `NormMappingService.kt` use database-backed `TranslationConfig`. Keep those workflows intact. Do not silently choose between translation credentials/models and the environment OpenRouter configuration.
- `B/service/AiSuggestionJobService.kt` and `B/controller/AiSuggestionController.kt` illustrate asynchronous jobs/cancellation and repeated guards, but persist and apply assessment responses. Their entities, confidence scorer, 24-hour content cache and write operations do not fit advice-only remediation.
- `B/service/CitationValidator.kt`, `validate` checks HTTPS/host/size, not authority, source content or CVE coverage. It is insufficient for this report.
- `B/service/EolCatalogClient.kt`, `buildAndValidateUri` is the nearest source-fetching policy (HTTPS, exact host allowlist, public addresses, no redirects). Its body-size check happens after buffering: the new fetch path must enforce limits while reading. Reuse/extract its URL-policy control for multiple consumers rather than duplicating it; preserve existing EOL tests/behavior.
- `B/service/AccountOnboardingRateLimiter.kt` is an existing bounded limiter pattern, not a ready-made budget ledger. Implement a small AI-local limiter, no distributed framework.
- `scripts/startbackenddev.sh` supplies `SECMAN_OPENROUTER_API_KEY` through `pass-cli`; `application.yml` explicitly references `OPENROUTER_API_KEY`. Verify Micronaut relaxed-property precedence and test both supported names. Recommend documenting `SECMAN_OPENROUTER_API_KEY` as the canonical direct `secman.openrouter.api-key` binding with the existing alias still accepted. Do not add a second secret store or print either value.
- JUnit 6/Mockk/AssertJ, `BaseIntegrationTest`, `TestDataFactory`, `TestAuthHelper`; external MariaDB through `scripts/runbackendtests.sh` or disposable runner. No Testcontainers and no `@ParameterizedTest` dependency. Frontend uses Node tests for `.ts` logic; JSX needs browser tests. Existing E2E examples: `tests/e2e/ai-risk-assessment.spec.ts`, `crowdstrike-inventory.spec.ts`, `vuln-exception-full.spec.ts`, helpers in `tests/e2e/helpers/`.

## 3. Role and permission design

Define one backend pure predicate, e.g. `Set<User.Role>.isBetaEligible()` in `B/security/BetaFeatureAccess.kt` (proposed), and `canAccessBetaFeatures(roles)` in `F/utils/permissions.ts`. Rule: set intersects `{BETA, ADMIN, SECCHAMPION}`. Do not modify `isAdmin`, `hasVulnAccess`, grant-management helpers, asset SQL, OAuth auto-provision defaults or role hierarchy.

A proposed `BetaFeatureAccess.requireCurrentUser(authentication)` loads the authenticated database user by trusted principal ID, verifies name/ID consistency and `enabled`, reads current roles and email, and returns fresh `Authentication` attributes for the existing AssetFilterService. Do not accept an identity from request bodies. A small `AiBuddyAccessGuard` composes this with eligibility, existence/access and job ownership.

All AI endpoints use `@Secured(IS_AUTHENTICATED)` **plus an immediate explicit live beta guard** (not a TODO). This deliberate service-level role gate allows newly granted roles with old JWTs, and prevents removed ADMIN/SECCHAMPION roles from retaining a stale global shortcut. Add tests asserting the guard runs before any snapshot/provider lookup. Capabilities are the only authenticated endpoint that returns `eligible:false` instead of 403.

| Caller | Feature eligible | Asset scope | Generate/read/cancel own report | Manage BETA assignments |
| --- | --- | --- | --- | --- |
| Unauthenticated | No | None | 401 | No |
| USER (also VULN alone) without BETA | No | Existing scope unchanged | 403 | No |
| USER+BETA | Yes | Existing accessible assets only | Yes within scope | No |
| ADMIN without BETA | Yes | Existing global scope | Yes; no peer-report sharing | Yes through existing user UI |
| SECCHAMPION without BETA | Yes | Existing global asset scope | Yes; no peer-report sharing | No |
| BETA with inaccessible asset | Yes | Requested asset denied | 404, same as absent asset | No |
| Disabled/deleted user | No | None | 401; cancel/discard job | No |
| Formerly eligible/granted user | Re-evaluate live | Re-evaluate live | 403 for eligibility loss; 404 for scope loss | Existing policy |

Recheck at submission, before each external batch, before report publication, every poll/read/cancel, and before any future export. No ADMIN bypass of report owner matching: another administrator can create their own report. The in-flight network request cannot be recalled after dispatch; after revocation, stop remaining work and discard its result. An authorized response already delivered cannot be erased remotely; UI removes it at the next check/focus event.

Session behavior: load capabilities on mount, page visibility/focus, before Generate, and on periodic report polling. Apply the frontend helper to the fresh roles returned for the current user by capabilities; do not rely solely on cached `useClientRoles()`. No token exposure and no forced global refresh redesign. Existing `/api/auth/refresh` continues its present behavior.

## 4. UI inventory and precise placement

| Route / entry point | Existing rendering | Proposed placement and behavior |
| --- | --- | --- |
| `/vulnerabilities/system?hostname=…` | `F/pages/vulnerabilities/system.astro` → `CrowdStrikeVulnerabilityLookup.tsx` | Beside the system results header's export/refresh actions, outside the `sortedVulnerabilities.length > 0` condition. Analyze the resolved stored asset ID only. Display the data-source distinction prominently. |
| `/outdated-assets/{id}` | `F/pages/outdated-assets/[id].astro` → `OutdatedAssetDetail.tsx` | System summary header, before severity summary/table. Wording says all recorded findings, including non-overdue ones, regardless of current page. |
| `/assets`, `/asset` embedded vulnerability modal | `AssetManagement.tsx` → `VulnerabilityHistory.tsx` | In modal header/body toolbar beside title, opening the same report panel within the modal body. Do not nest another modal. The component name says history; current endpoint returns recorded rows, not a reliable archive. |
| `/assets/{id}` | `AssetIdRedirect.tsx` | Currently redirects to hostname lookup. Preserve the stable asset ID in the destination query (`/vulnerabilities/system?assetId=…`) to avoid hostname ambiguity. |
| `/analytics` | `AnalyticsTabs.tsx` embeds CurrentVulnerabilitiesTable and other aggregates | No separate per-system report here. Existing system links lead to the system page. |
| `/vulnerabilities/current`, `/account-vulns`, `/wg-vulns`, `/vulnerabilities/domain`, statistics/heatmap | Aggregate rows or `AssetVulnTable.tsx` and system links | No fleet buttons. Where an asset ID is already available, make system links prefer it. Avoid a broad routing rewrite. |
| `/vulnerabilities/eol`, `/vulnerabilities/eol/products/{product}` | Lifecycle/product lists | Not a system vulnerability-table surface; no beta button added. Lifecycle context appears inside the selected system's report. |

**Required reachability fix:** CrowdStrikeController is class-gated ADMIN/VULN; its lookup action does not accept Authentication. Do not widen that controller to BETA or SECCHAMPION. Add a recorded-system branch to `CrowdStrikeVulnerabilityLookup.tsx` for a stable asset ID and for eligible users lacking ADMIN/VULN. Use existing authorized `GET /api/assets/{id}` / `/api/assets/by-name/{name}` and asset vulnerability reads for display; the new AI controller separately performs strict access checks and backend snapshot loading. A no-access empty list from the legacy asset-vulnerability endpoint must never be treated as authorization. If a hostname resolves ambiguously, require selecting one accessible stored asset. Multiple devices aggregated by instance ID are not one analysis unless they already resolve to a single stored asset. Maintain existing live lookup permissions and behavior for existing callers.

For live results: if a stored asset resolves uniquely, enable the button with “Analyzes recorded SecMan findings; unsaved live results are not included.” If no stored asset exists, disable with “Import this system before using AI buddy(beta).” Do not import or save as a side effect. A stored asset with zero rows returns the empty-state report.

Use proposed `AiBuddyButton.tsx`, `AiBuddyReportPanel.tsx`, `services/aiBuddyService.ts`, and a pure `.ts` report-state/formatting module. Inline expandable Bootstrap card is simplest: long reports can scroll with the page, and the existing history modal can host it without focus layering. Do not add a dedicated route or side-panel framework.

User wording:

- Button: **AI buddy(beta)**.
- Before starting, visible next to the button/panel: “Analyzes all recorded findings for this system, regardless of table filters. Sends CVE IDs, normalized product/version details and remediation evidence to OpenRouter and the configured model provider. Hostnames, IP addresses, account IDs and user identities are not sent. Advice only; nothing is installed or marked resolved.” Button click starts work; no recurring confirmation dialog.
- Report heading: “Consolidated remediation advice — recorded snapshot”. Explain “Smallest evidence-supported action set found; not a guarantee of the absolute minimum or complete remediation.”
- Empty: “No recorded findings in this snapshot. This is not proof that the system is vulnerability-free.” No paid model call.
- Missing evidence: “Exact applicability could not be verified. Check the listed inventory and vendor prerequisites before applying these actions.”
- Stale: “Recorded data changed after this snapshot. Generate a new report before using these recommendations.”

Lifecycle: show named stages (snapshot/evidence/analysis/validation), elapsed time and completed/total batches when real; no invented percentages or chain-of-thought stream. Disable Generate while a start/job is pending, reopen current in-flight job on repeated clicks, show explicit Cancel. Collapse keeps polling; closing the enclosing modal or unmounting stops polling and attempts authenticated DELETE. The server also expires jobs with no client heartbeat for 60 seconds; cancellation is best effort, an already dispatched call may still be charged. On visibility return, revalidate before showing content. Never auto-retry a paid failed job after page reload.

While a completed report remains open, continue a lightweight authorization/status check every 10 seconds and refresh the report's source-freshness check every 30 seconds, plus on focus. These reads never invoke the model. Stop timers on unmount/expiry; hide cached content while revalidating after a hidden tab resumes. This prevents a report from indefinitely appearing current after an import or permission change.

Keyboard: real buttons, `aria-expanded`/`aria-controls`, focus report heading on open and return focus on close, `aria-busy`, polite `role=status` stage announcements, `role=alert` for errors. Do not announce every poll. Modal retains focus trap/Escape behavior; add missing accessible close label when touching its controls. Report tables have captions/headers, evidence states use text as well as color, and long lists are expandable/paginated. React text nodes only; no Markdown/HTML rendering library.

## 5. Scope, snapshot and freshness

Implement proposed `AiBuddySnapshotService` and a small asset-scoped read projection repository. Read all retained Vulnerability rows for the authorized asset, not UI filters, overdue materializations, or the maximum import timestamp alone. `VulnerabilityRepository.findLatestVulnerabilitiesForAssetIds` uses DENSE_RANK partitions, whereas `AssetFilterService.getAssetVulnerabilities` reads all stored rows. Latest-import-only selection can omit deliberately retained severities or other sources. This feature intentionally follows complete **recorded** rows and labels that choice.

Rules:

- Include all severities, non-CVE identifiers and unknown classifications. Source-specific import configuration may already have excluded rows upstream; report cannot reconstruct them. Declare “recorded inventory only; provider-wide completeness is not asserted.”
- Include active excepted/suppressed findings with their status. Distinguish accepted risk from fixed risk. Count them separately and do not treat exception approval as remediation. Re-evaluate active exceptions with the shared rules at snapshot time. Provider suppression not persisted is unknown; live enrichment may label it without deleting the stored finding.
- Exclude explicit `INSTALLER_ARTIFACT` rows from action targets; list count/reason and retain local ledger entries. UNKNOWN remains in scope. Ignore current UI installer/exception/severity/product filters.
- Resolved integration records without a current projection are not current vulnerabilities. Count known linked resolved records as excluded historical context if read; never claim a complete historical resolved count for sources that delete rows. No disappearance-to-installed-patch inference.
- Deduplicate exact source/product/version/CVE-or-provider-key tuples for reasoning only; retain original row refs and multiplicity. Same CVE in different products/versions remains distinct coverage obligations. Ambiguous joined product text stays ambiguous, not split on commas heuristically.
- Preserve missing/stale last-seen values. Default stale warning threshold: seven days, configurable; separately display sensor last seen, import time and inventory age. This warning does not exclude findings or prove the device offline.

Read asset fields, retained findings, relevant installed products, exception matches, linked integration evidence and linked AIDs in a **single short read-only REPEATABLE READ database transaction**, with deterministic keyset batches (500 rows), DTO projections and bounds. No external calls in that transaction. Verify the actual Micronaut/JPA isolation configuration in MariaDB tests; a readOnly annotation alone is not a snapshot guarantee. Findings and product inventory imported in different transactions may still have different ages: report both rather than claiming one collector run.

Snapshot holds `capturedAt`, per-source observed/import ranges, counts and a SHA-256 digest of a versioned canonical representation of all advice-relevant values and local finding references (including exception expiry/classification). SHA-256 here is a change digest, not credential hashing. Local-only asset identity is not part of the model payload. Read-only enrichment has separate `retrievedAt` and provider observation times; it never changes the snapshot finding scope.

At completion and on report retrieval, obtain a new bounded coherent digest. Differences set `sourceChanged=true`, `stale=true`; keep the immutable old snapshot visible with its warning, never relabel it current. No automatic generation loop while imports run. Authorization is checked again independently. Deleted asset: cancel/purge and return 404. Live evidence missing a recorded CVE means “not observed in this evidence response,” not resolved. Time passing can expire an exception/evidence TTL even without a DB write; freshness evaluation must include time.

Practical bounds: 50,000 finding rows, 10,000 inventory rows, 20 MiB normalized snapshot per system; detect count/size overflow and return `SCOPE_TOO_LARGE` before a model call. Do not silently analyze the first N. Within bounds, all rows must enter a coverage ledger; thousands of rows can be grouped/chunked. If a later evidence/model budget is exhausted, return a clearly partial report with all skipped/unverified groups enumerated. The initial DB scope itself must be complete or fail.

## 6. Evidence retrieval, consolidation and limits

### Evidence sources and adapter boundary

Use a narrow proposed `RemediationEvidenceSource` abstraction returning typed facts, provenance, timestamps, completeness and errors. Two implementations are sufficient: `CrowdStrikeRemediationEvidenceSource` and `MicrosoftRemediationEvidenceSource`. No plugin registry, vector DB, agent tools, search engine or general crawler.

1. Gather current stored product/platform facts and source provenance locally. Linked integration recommendations are untrusted candidate hints only. EOL data can supply a separate lifecycle warning. Do not include arbitrary prose in model input.
2. For CrowdStrike-sourced findings, use stored verified `CrowdStrikeAssetIdentity` AIDs and existing `CrowdStrikeAuthService` / `CrowdStrikeApiClientImpl` transport. Add a **read-only** typed evidence operation rather than calling import/save endpoints. Query every linked AID with pagination/error completeness checks and request the remediation facet; fetch returned remediation IDs through the official remediation endpoint. Match returned AID + CVE + structured product/version to snapshot obligations. Ambiguous/missing mappings cannot produce a verified edge. Do not broaden the target by hostname, merge unrelated live findings, or replace stored rows.
3. CrowdStrike officially documents `GET /spotlight/combined/vulnerabilities/v1` and `GET /spotlight/entities/remediations/v2` under Vulnerabilities: READ. Public response documentation leaves several facet objects unspecified. Validate the exact nested fields against a sanctioned, sanitized response fixture and the tenant's entitlement before enabling this adapter. Do not invent a remediation schema from field names in old examples. [Official Spotlight reference](https://developer.crowdstrike.com/api-reference/collections/spotlight-vulnerabilities/).
4. For recognized Microsoft product families, use the official MSRC CVRF v3 update catalogue/document API, through a fixed HTTPS adapter. Resolve product-tree IDs and product-specific remediation records before making a CVE→KB edge. Pin response fixtures and parser expectations; do not infer product ID by substring alone. Microsoft's published API definition and version announcement establish the API, not a guarantee that every applicability condition is represented. [MSRC API definition](https://github.com/microsoft/MSRC-Microsoft-Security-Updates-API/blob/main/docs/swagger.json), [CVRF v3 announcement](https://www.microsoft.com/en-us/msrc/blog/2024/07/announcing-the-cvrf-api-3-0-upgrade/).
5. Discover release documents from the API's update index, bounded by published configuration (initially 24 relevant documents/job, 100 HTTP requests/job). Select relevant CVE release documents plus current product security release candidates; do not assume CVE year equals patch year. Follow only typed catalogue references on allowlisted hosts. If the discovery cap prevents establishing coverage, list the affected CVEs as evidence incomplete. Prefer shared public-evidence cache entries to repeated catalogue downloads.
6. Public evidence cache: parsed vendor facts only, keyed by source/document revision+parser version, max 100 MiB, 24-hour TTL, conditional HTTP revalidation when available. No asset payloads or CrowdStrike tenant evidence in this shared cache. Cache failures briefly (60 seconds), never convert them into “no patch”. User reports record the evidence digest/retrieval time; a newly learned revision makes them stale on next access.
7. Other vendors/configuration/firmware: use a provider's explicit typed remedy when it supports the claim; otherwise group as investigation/conditional advice with missing evidence. Do not build a separate feed for every vendor in beta. A product update supplied only as free text remains conditional. Links alone are not proof.

Before coding adapter field mappings, create fixture-based discovery tests that establish actual contracts. If a production provider cannot supply the documented fields or credentials lack scope, the feature still accounts for the entire recorded set, but those findings remain unverified. **Do not call a text-only implementation complete:** at least one real typed mapping adapter and independently validated consolidation must work, with representative Windows cumulative-update fixtures. Broad live tenant applicability remains a rollout gate, not a fabricated capability.

### Windows rules

- Separate Windows OS, .NET, Office, browser and other application servicing families. A Windows Server 2022 label does not establish exact edition/build/architecture or patch applicability.
- Form coverage edges only from explicit CVE+product+remediation records. Same KB used for multiple products does not make those products interchangeable.
- Supersedence edges must carry source, product/branch/architecture constraints, observation time and prerequisite information. Never infer supersedence from a larger KB number, newer date or the word “cumulative”. If a typed source does not expose it, retain both candidates/mark manual verification rather than auto-scraping arbitrary support pages.
- Require positive evidence of supported platform, installed baseline and prerequisites before calling installation applicability verified. Missing build, architecture or installed-update inventory normally means `REQUIRES_APPLICABILITY_CHECK`, even when the KB→CVE relation is verified.
- Servicing stack and cumulative updates can be combined, but prerequisite conditions remain product/release-specific. Consult the applicable vendor article; no blanket “latest CU is enough.” [Microsoft servicing-stack documentation](https://learn.microsoft.com/en-us/windows/deployment/update/servicing-stack-updates).
- Checkpoint cumulative-update installation is an additional release-specific dependency case. Model it as a prerequisite graph only with supporting evidence; do not apply Windows 11/Server 2025 rules to Server 2022 by analogy. [Microsoft checkpoint documentation](https://learn.microsoft.com/en-us/windows/deployment/update/catalog-checkpoint-cumulative-updates).
- Reboot is `REQUIRED`, `NOT_REQUIRED`, `CONDITIONAL` or `UNKNOWN`, with evidence; absence of a restart flag is UNKNOWN. Installed patch evidence, pending reboot and refreshed vulnerability detection are separate facts.

### Deterministic action selection

Construct a local obligation ledger keyed by snapshot finding/product group, not just unique CVE. Evidence facts generate candidate actions of type OS_UPDATE, APPLICATION_UPDATE, CONFIGURATION_CHANGE, FIRMWARE_DRIVER_UPDATE, PRODUCT_UPGRADE, MITIGATION, INVESTIGATION. Each candidate has a canonical target, product/platform conditions, prerequisites, known reboot state, explicitly supported coverage edges, and evidence refs.

Eliminate exact duplicate candidates. Remove an older action only when a validated superseding candidate covers its obligations under compatible applicability constraints and preserves required prerequisites. Detect cycles/conflicts, mutually exclusive products and conflicting versions; quarantine affected candidates for investigation. Choose deterministically among verified compatible candidates (maximum newly covered obligations, then fewer additional prerequisites, then stable candidate ID), remove redundant actions, and topologically order dependencies. This is a bounded greedy consolidation, **not a proof of mathematical minimality**. Conditional candidates are considered separately and never inflate verified coverage.

OpenRouter receives the bounded candidate/obligation graph and selects/organizes references and explanation-template codes. Backend recomputes coverage, fills any omitted obligations, checks ordering, and rejects inconsistent choices. Do not let the model create edges, identifiers, versions, URLs or commands. Do not rely on regex checks to establish that a plausible KB is real.

A verified **remediation recommendation** means evidence supports the action and known applicability conditions; it does not mean installed. An installed-update observation requires a separate authoritative inventory observation, unavailable in current normal storage. “No longer present” requires a later complete provider scan/import result. If a patch is reported installed but a finding remains, present the conflict, check reboot/build/component applicability and rescan manually; never mark it fixed.

## 7. Structured contracts and validation

Use explicit Micronaut `@Serdeable` DTOs and a versioned JSON Schema resource. No arbitrary polymorphic deserialization. Reject unknown fields, duplicate IDs, wrong types and over-limit arrays/strings. Use Jackson tree/manual schema checks with existing dependencies; introducing a new JSON Schema library is unnecessary for this small closed contract. One contract fixture should feed backend and frontend tests.

### HTTP endpoints (proposed)

Base: `/api/assets/{assetId}/ai-buddy`. All responses use `Cache-Control: no-store`.

| Endpoint | Request / response |
| --- | --- |
| `GET /api/ai-buddy/capabilities` | Authenticated current-user read: `{schemaVersion:1, eligible:boolean, roles:string[], available:boolean, unavailableReason:code|null, disclosureVersion:"1"}`. Only own roles; no secrets/provider account details. Feature disabled reports availability false. |
| `POST {base}/jobs` | JSON `{requestId: UUID}` only, max 1 KiB; no findings, prompt, hostname, model, filters or URL accepted. `202` with `{schemaVersion:1, jobId:UUID, state, createdAt, expiresAt:null, pollAfterMs:2000}`. Repeated same user+asset+requestId returns same job while retained. Same user+asset in flight returns that job; other users never see its ID. |
| `GET {base}/jobs/{jobId}` | `{schemaVersion, jobId, state, stage, completedBatches, totalBatches, createdAt, startedAt, finishedAt, expiresAt, error:null|{code,retryable,retryAfterSeconds}, reportAvailable:boolean}`. Poll every 2s running, 5s queued; request is a client lease heartbeat. No partial model text. |
| `GET {base}/jobs/{jobId}/report` | Validated report below; 409 if still running. Recheck ownership/current eligibility/asset access and source/evidence/config freshness. |
| `DELETE {base}/jobs/{jobId}` | 204; idempotently cancel running work and purge completed report. Retain a bounded owner-only cancellation tombstone until normal expiry so races cannot republish it. |

States: QUEUED → SNAPSHOTTING → FETCHING_EVIDENCE → ANALYZING → VALIDATING → COMPLETED or PARTIAL; CANCELLED/FAILED from any running state. State transitions use atomic ownership/state checks. EMPTY is a COMPLETED report with empty counts/actions. Failed jobs may expose a separately validated partial report only if some groups completed and every remaining obligation is accounted for. Never render fragments of malformed JSON.

Errors: 400 INVALID_REQUEST; 401 AUTH_REQUIRED; 403 BETA_REQUIRED; 404 ASSET_OR_JOB_NOT_FOUND (includes scope/owner mismatch, expired/restarted jobs); 409 REPORT_NOT_READY or REQUEST_ID_CONFLICT; 422 SCOPE_TOO_LARGE; 429 REQUEST_LIMIT with Retry-After; 503 FEATURE_DISABLED/NOT_CONFIGURED/CAPACITY_EXCEEDED. Accepted jobs carry safe terminal codes PROVIDER_TIMEOUT, PROVIDER_RATE_LIMIT, PROVIDER_UNAVAILABLE, PROVIDER_AUTH, PROVIDER_CREDIT, MODEL_UNSUPPORTED, OUTPUT_INVALID, EVIDENCE_UNAVAILABLE, BUDGET_EXHAUSTED, SOURCE_UNAVAILABLE. Provider 401 is not a user-login 401. No raw upstream body in the UI or logs.

### Server-owned report shape

All fields are required unless shown nullable. Times are ISO-8601 UTC; existing LocalDateTime fields need the repository's documented timezone conversion, with tests, not an assumed offset.

```text
AiBuddyReport {
  schemaVersion: 1,
  jobId: UUID,
  generatedAt: instant,
  expiresAt: instant,
  system: { assetId: long, displayName: string,
            osNameVersion: string|null, build: string|null,
            edition: string|null, architecture: string|null },
  snapshot: { capturedAt: instant, fingerprint: string,
              assetLastSeen: instant|null, sensorLastSeen: instant|null,
              vulnerabilityImportFrom: instant|null, vulnerabilityImportTo: instant|null,
              inventoryImportedAt: instant|null, evidenceRetrievedAt: instant|null,
              sourceChanged: boolean, stale: boolean, staleReasons: code[] },
  scope: { basis: "ALL_RECORDED_ASSET_FINDINGS", tableFiltersApplied: false,
           retainedFindingCount: int, consideredFindingCount: int,
           uniqueCveCount: int, nonCveFindingCount: int, duplicateRowCount: int,
           exceptedFindingCount: int, excludedFindingCount: int,
           exclusions: [{ reason: code, count: int }],
           sourceCompleteness: "RECORDED_ONLY", sourceCounts: [{source,count}] },
  analysis: { completeness: "COMPLETE"|"PARTIAL", model: string|null,
              promptVersion: string, evidenceVersion: string,
              verifiedCoveredFindingCount: int, conditionalFindingCount: int,
              noVerifiedRemediationCount: int, limitations: code[] },
  actions: [{ id: string, type: ActionType, order: int,
              evidenceState: EvidenceState, targetProduct: string|null,
              patchIds: string[], targetVersion: string|null,
              applicability: FactOrCheck[], prerequisites: FactOrCheck[],
              dependsOnActionIds: string[], coveredFindingRefs: string[],
              coveredCves: string[], coverageKind: "VERIFIED"|"CONDITIONAL",
              reboot: RebootState, evidenceRefs: string[], explanationCode: code }],
  findings: [{ ref: string, source: string, identifier: string|null,
               product: string|null, duplicateCount: int, excepted: boolean,
               disposition: "VERIFIED_ACTION"|"CONDITIONAL_ACTION"|
                            "NO_VERIFIED_REMEDIATION"|"EXCLUDED",
               actionRefs: string[], reasonCodes: code[] }],
  evidence: [{ ref: string, source: "CROWDSTRIKE"|"MICROSOFT"|"LOCAL",
               title: string, url: string|null, retrievedAt: instant,
               publishedAt: instant|null, revision: string|null,
               supports: code[] }],
  remediationObservations: [{ actionRef: string,
                              installationState: "UNKNOWN"|"REPORTED_INSTALLED",
                              observedAt: instant|null, evidenceRefs: string[],
                              vulnerabilityState: "STILL_RECORDED" }],
  missingInformation: [{ code: code, findingRefs: string[], checkCode: code }],
  postRemediationChecks: [{ code: code, actionRefs: string[] }]
}
```

`FactOrCheck` is `{code, value:string|null, evidenceRefs:string[], required:boolean, satisfied:boolean|null}`. Closed codes render reviewed human wording; null satisfied is unknown, never true. All IDs are local opaque job-scoped refs; identity/timestamps/counts/status/evidence labels come from SecMan, never the LLM. Display raw local product text escaped, but outbound product names must meet the minimization rules in §10.

Installation observations default to UNKNOWN. REPORTED_INSTALLED requires an explicit independently sourced patch observation, not InstalledProduct.installedAt or a model statement. The beta snapshot still records the vulnerability, so the report does not offer a model-generated RESOLVED state. Later scan evidence belongs to a newly captured report; a future scan or absent live finding cannot rewrite this snapshot.

Evidence states:

- **VERIFIED**: independent source supports the exact action/coverage relationship and all required applicability facts are positively satisfied by sufficiently fresh, non-conflicting evidence. This still is advice, not an installation guarantee.
- **REQUIRES_APPLICABILITY_CHECK**: remedy relationship is supported, but platform/baseline/prerequisite applicability is missing or stale. UI labels covered CVEs “conditional coverage”.
- **INSUFFICIENT_INFORMATION**: no trustworthy exact remedy relationship, incomplete retrieval or conflicting evidence. No exact fix asserted. Investigation actions have empty verified coverage.

`noVerifiedRemediationCount` includes conditional and unresolved obligations; display subcounts, not an additive total that double-counts them. Findings are the authoritative exhaustive ledger; union coverage over finding refs, not summed CVE counts across actions. Every considered obligation has a disposition; a CVE shared by two products is fully covered only if both obligations are covered. Include excluded local findings/counts without sending them externally.

### Model-owned contract (intentionally smaller)

```text
{ schemaVersion:1,
  groups:[{ candidateIds:string[], explanationCode:AllowedExplanationCode }],
  orderedCandidateIds:string[],
  manualCheckIds:string[] }
```

The model only selects IDs and reviewed explanation codes supplied in the request. It cannot return a new patch ID, target version, command, URL or arbitrary prose. Render titles/facts/checks from validated candidates and templates. This constrained generation meets the recommendation objective and makes fabricated claims rejectable outside the model; unrestricted prose would be impossible to fully validate. Provider refusal, truncation or non-schema output is OUTPUT_INVALID, not permissive JSON repair.

Validation sequence: bound bytes/depth; check provider finish reason; strict shape/enums; membership of every reference; validate patch/product/CVE/evidence graph; reject conflicting candidates/dependency cycles; rebuild installation order; compute coverage and missing obligations; join local system metadata; enforce report byte/count bounds. A fabricated reference invalidates that batch. Retain other independently validated batches only with explicit partial status. Backend may add omitted investigation entries, but cannot promote a conditional action to verified because the model chose it.

## 8. End-to-end execution and large systems

1. UI resolves exactly one stored asset, gets live beta capability and shows disclosure.
2. POST authorizes current user, asset and feature configuration, applies limits and atomically allocates/deduplicates an owner job. Reserve spend before enqueueing. Worker is bounded; no unbounded executor queue.
3. Worker rechecks access, captures complete coherent local snapshot, and releases DB transaction. Zero findings produces a deterministic report without OpenRouter.
4. Fetch typed evidence with separate timestamps, hard request/byte/time limits and no snapshot mutation. Normalize only recognized public product/platform fields. Missing evidence becomes ledger gaps.
5. Build candidate graph and deterministic consolidation. Group by product/servicing family and retain cross-group dependency refs. If no usable candidates, generate an uncertainty report locally without spending on an LLM to invent one.
6. Partition bounded candidate graphs, keeping a servicing family intact where possible. When a family is too large, deterministic coverage/supersedence work stays local; send compact candidate IDs and counts, not thousands of repeated CVE strings. Maintain a complete local expansion map. Every chunk is strictly below the configured model/context/output limit. Process sequentially within a job; no recursive summarization of summaries and no silent context truncation.
7. OpenRouter returns selection/order/check refs. Validate each batch and deterministically merge duplicate candidates/coverage and prerequisites globally. No additional “final synthesis” call required.
8. Recheck fresh permissions, cancellation, configuration/evidence versions and source digest; publish immutable report or discard. UI fetches the validated report and displays current freshness.

Async is justified by multiple evidence/model calls, rate limits and thousands of findings. Synchronous execution is fewer endpoints but ties up browser/proxy requests and complicates reliable cancellation. Polling is simpler than a new SSE stream and avoids token-in-URL patterns. Reuse the configured `ai` executor carefully: orchestration must not block all its threads while waiting on nested tasks in the same pool (see TranslationService deadlock note). One blocking worker per admitted job is sufficient; outbound HttpClient uses its own executor.

## 9. OpenRouter configuration and operational controls

Add `B/config/AiBuddyConfig.kt` binding `secman.ai.buddy`, environment documentation and `.env.example` placeholders. Use existing `OpenRouterConfig`, JDK HTTP, Jackson and Caffeine. Add a focused `AiBuddyModelClient` interface and `OpenRouterAiBuddyClient` implementation; do not refactor all legacy AI features to share a new framework.

| Configuration | Initial recommendation |
| --- | --- |
| `AI_BUDDY_ENABLED` | false; independent of risk-assessment DB toggle |
| `AI_BUDDY_MODEL` | required explicit model ID, no default, no auto/online/fallback model alias |
| `AI_BUDDY_PROVIDERS` | required approved provider endpoint allowlist |
| OpenRouter key/base URL | existing config, secret from pass-cli; fixed approved HTTPS host, no browser exposure |
| `AI_BUDDY_MAX_COST_PER_JOB_USD` / `AI_BUDDY_DAILY_BUDGET_USD` | $0.50 / $10 initial defaults, operator-adjustable; these are proposed limits, not price claims |
| global / per-user / per-asset running jobs | 2 / 1 / 1; asset-level collision from another user returns generic busy, never peer identity/report |
| per-user starts / per-asset starts | 5/hour / 10/hour, including failures that reached external work |
| outbound connect / request / whole-job timeout | 10s / 60s / 5min |
| retries | at most 1 additional attempt for transient transport/429/502/503/504, within the same budget/deadline |
| model batches / output tokens per batch | max 8 / 4096, further limited by verified model capability |
| request payload / provider response | max 512 KiB / 1 MiB, bounded while reading |
| public evidence request | max 10 MiB decompressed/document, 15s/request, job request/byte caps |
| report/job retention | 30min terminal, bounded 100 jobs and 100 MiB total; evict expired/old terminal jobs, never silently drop active jobs |
| stale inventory warning | 7 days; unknown always disclosed |

Before enabling, verify the configured model and endpoint metadata, supported structured-output parameters, context/output limits and current pricing. `GET /api/v1/models` exposes model metadata; verify endpoint support, not just model-family marketing. Store a validated capability/config digest in memory and reject unavailable/unknown capabilities; refresh at most daily and on config changes. Run one small paid contract canary only as an explicit rollout check, never automated CI. [OpenRouter models API](https://openrouter.ai/docs/api/api-reference/models/get-models).

Send `response_format` with a strict JSON Schema and `provider.require_parameters=true`. Schema support is model-dependent and still needs application validation. [OpenRouter structured-output documentation](https://openrouter.ai/docs/guides/features/structured-outputs).

Explicit routing: `provider.only` approved endpoints, `allow_fallbacks:false`, `data_collection:"deny"`, `zdr:true`, and a price ceiling. No model fallback array, plugins, tool calls or online search. If the approved route cannot satisfy these requirements, fail closed. ZDR/routing settings do not establish organizational approval or regional residency; verify account/provider terms separately. [OpenRouter provider-routing controls](https://openrouter.ai/docs/guides/routing/provider-selection).

Token/cost guard: estimate conservatively using UTF-8 byte bounds plus message/schema overhead and full reserved output; do not assume characters/4 is a safe upper bound for arbitrary input. Reserve worst-case cost for all admitted batches and one retry using verified endpoint prices; reserve atomically against job and daily budgets. Use a provider-side key spending limit as an additional ceiling, because timeouts/cancellation may still be billed and in-memory counters reset on restart. Unknown price or usage means charge the reservation, not zero. Lower `max_tokens` only when output can still represent all selected refs; otherwise shrink a batch. Preserve actual usage/cost when returned, without raw response storage. A bounded request cannot guarantee final billing in isolation; document that distinction.

Handle 429 with bounded Retry-After+jitter; don't sleep beyond job deadline. Authentication/insufficient-credit/configuration/model-not-found/invalid-output errors do not trigger automatic retries. Distinguish provider 402 insufficient balance from documented transient in-flight budget conditions, but beta may conservatively stop both and require manual retry. Provider errors may also occur in a nominal HTTP success envelope: inspect error and finish reason. [OpenRouter error reference](https://github.com/OpenRouterTeam/docs/blob/main/api_reference/errors-and-debugging.mdx).

Metrics: stage latency, jobs admitted/denied/cancelled/expired, provider status class, actual/reserved tokens/cost, validation rejection reasons, coverage-state counts, evidence failures and stale completions. No asset/user IDs as metric labels. Existing local security logs may record actor ID + asset ID + job ID + outcome per A09; no hostnames, prompts, raw provider documents, model output, API keys, cookies or upstream error bodies. Use bounded sanitized codes for failures. Missing secrets leave AI buddy unavailable; do not fail the rest of SecMan startup.

## 10. Security and privacy review

Outbound OpenRouter DTO is an allowlist: random per-job opaque group/candidate refs; validated public CVE identifiers; canonical recognized vendor/product names and versions; recognized OS family/build/architecture when actually evidenced; validated remedy relationships, prerequisite codes and counts. Do not serialize domain entities.

**Never send:** hostnames/FQDNs/IP/MAC addresses, account/instance/AID IDs, usernames/emails, owner/workgroup/domain names, SecMan URLs, job/user/asset database IDs, exception reasons, file paths, code snippets, credentials, raw finding descriptions, raw retrieved documents, arbitrary vendor text, identifying request headers or customer-specific model `user` fields. System display identity is joined locally after generation. Public vendor-source links stay in the local evidence registry; the model uses opaque evidence refs.

Product strings are untrusted and can embed identities or instructions. Normalize against an explicit recognized product dictionary/source product ID; validate version grammar/length; unknown custom strings stay local and become “unrecognized product; manual check required.” Regex redaction alone is not enough. Scan the final serialized request for known local sensitive values and generic email/IP/URI patterns as defense in depth; block rather than log rejected content. Evidence retrieval to CrowdStrike necessarily uses its own AIDs within that existing integration; this is distinct from disclosure to OpenRouter. Public vendor requests contain public identifiers only.

| OWASP 2021 | Feature controls / repository requirement |
| --- | --- |
| A01 Access control | `@Secured`, live BETA guard, enabled-user check, fresh roles passed to AssetFilterService; job owner + asset ID binding at every route and external batch; no peer report cache; 404 scope masking. |
| A02 Cryptographic failures | pass-cli/env, existing HttpOnly cookie and auth flow, TLS verification; no new credential persistence; digests only for content identity. |
| A03 Injection | Bound SQL parameters and allowlisted sort; strict DTOs; untrusted evidence only as typed data; no tools/commands; React text rendering, no Markdown/HTML sink. Model cannot create URLs or facts. |
| A04 Insecure design | Advice-only invariant, exhaustive coverage ledger, honest partial/freshness states, limits on input/queue/retries/cost, no speculative exact patch claims. |
| A05 Misconfiguration | Default off, explicit model/providers, no CSP/CORS weakening, safe error codes, test-only fixtures unavailable in production. Keep SecurityHeadersFilter. |
| A06 Components | Existing stdlib/framework dependencies; no SDK/crawler/tokenizer library assumed. Any necessary new dependency needs a documented justification and pinned version. |
| A07 Authentication | Existing authentication only, current enabled user reloaded, no stale claim grants, no session lifetime changes; standard authenticated frontend helpers/CSRF and origin controls. |
| A08 Integrity | Typed evidence contracts and provenance; strict model schema/ref validation; unsupported parser shape fails closed; no remotely supplied code. Prefer JSON; if XML becomes necessary, existing XXE-disabled parser controls are mandatory. |
| A09 Logging | Actor/target/outcome locally; low-cardinality metrics, secret/body-free logs, explicit denied/invalid-output outcomes; no swallowed failures. |
| A10 SSRF | Fixed provider/vendor endpoints; HTTPS exact-host/path allowlists, no userinfo/nonstandard ports, reject private/loopback/link-local/metadata/IPv6 ULA and mapped-private targets; redirects disabled. No model-supplied fetches. Apply egress policy at network boundary to close DNS validation/connect-time rebinding gaps. |

Source links: render only backend evidence-registry URLs from approved authoritative hosts, sanitized typed paths and no credentials/query identifiers. A valid HTTPS URL is not sufficient. Use descriptive source/title/date, `rel="noopener noreferrer"` and no-referrer behavior for external links. Unknown provider links may be described as unavailable evidence, not clickable model citations. No automatic preview/image fetches. Source authority does not make source text instructions trusted.

Prompt injection defenses rely on architecture, not a magic system prompt: imported text is never an instruction channel, raw documents are not sent, model has no tool or action access, output is closed references, and verification is deterministic. System prompt reiterates this boundary but is not the security boundary. Log rejection codes, not attack strings.

Organizational approval remains a **deployment prerequisite**: authorize disclosure of de-identified vulnerability/product information, approve OpenRouter and the selected provider/region/retention terms and budgets. Even de-identified software/CVE combinations can reveal a security posture. The recommendation is no retained report content and ZDR routing, not a claim of established company policy. No product decision blocks writing this plan; no external disclosure should be enabled until those operational decisions are recorded.

## 11. Persistence, caching and migration

No new report entity, Flyway report table, attachment or permanent audit-content store. Memory state holds owner ID, asset ID, canonical snapshot, evidence refs, state/cancel token, limits/reservations and validated report. Controller rejects a job with a mismatched route asset ID even if owner matches. Dispose of snapshots/raw evidence promptly after validation; retain only the bounded report and required freshness digest metadata until expiry. Browser holds report only in component memory; no localStorage/sessionStorage/IndexedDB/service-worker caching of reports.

Reusing the same in-flight request is deduplication, not stale-report caching. A new Generate after completion starts a new snapshot and evidence check. Refresh is another POST with a new requestId, subject to cost limits. Reports expire at 30 minutes, on explicit deletion, logout-driven cleanup when possible, revoked access checks or system deletion discovery. Authorization is always checked before returning cached bytes. No model/provider/config/prompt/evidence-version change may silently reuse a report as current.

Single-instance beta is the deliberate simple deployment assumption. Process restart loses jobs; clients receive 404 and “Report expired or server restarted; generate again.” Polling renews a 60-second lease for active jobs; background/closed tabs can lose work, a stated tradeoff. Rate/budget counters are also process-local; a provider key budget prevents restart-based unlimited spend. If production actually requires multiple backend replicas, **block enablement** until single-instance routing/ownership is assured or approve a separately designed durable job store/distributed limits. Do not add Redis speculatively or pretend sticky sessions solve global spend limits.

BETA migration: for the current VARCHAR enum mapping, adding an enum member does not need a data backfill or schema change; existing roles and default-off behavior remain untouched. Verify role_name type in the isolated fresh and upgraded schema, and use a read-only deployment schema check. If an enum/check constraint exists, add a narrowly targeted idempotent Flyway migration at the next free version, tested against that shape. Do not rely on Hibernate to silently repair an unsupported column or modify the wrong historical `role` column. Any new entity in a later design must use GenerationType.IDENTITY.

Deploy binaries that recognize BETA everywhere before assigning it; old binaries can fail enum hydration. Rollback order: disable feature, cancel/purge jobs, remove only BETA role assignments through the ADMIN user API (retain every other role), then revert binaries. Roll forward is safer if assignments cannot first be removed. Never downgrade enum support while BETA rows remain. No vulnerability data rollback is required because analysis writes none.

## 12. Acceptance-test matrix

U = JUnit/Mockk unit or frontend pure Node test; I = real MariaDB `BaseIntegrationTest` + authenticated HTTP; E = Playwright on the disposable runner. Provider/evidence responses are controlled fixtures by default, including failures and synthetic large sets. Block outbound real OpenRouter/CrowdStrike calls in automated test profiles. Tests must assert exact calls/payload boundaries, access decisions, coverage ledger and no mutation, not just visible text.

| Case | Expected outcome / evidence | Tier |
| --- | --- | --- |
| USER without BETA; VULN without BETA | No button after capabilities; direct POST/read/cancel denied 403; zero provider calls | U/I/E |
| USER+BETA | Allowed on own granted asset only; retains other roles; no new admin/endpoints/grants | U/I/E |
| ADMIN without BETA | Eligible; existing global asset visibility; report owner rule still applies | I/E |
| SECCHAMPION without BETA | Eligible; usable recorded-system branch despite old CrowdStrike role gate | I/E |
| BETA, no asset access | GET/POST/read/cancel give same 404 as absent asset, no counts or calls leaked | I/E |
| Unauthenticated | 401 across all feature endpoints | I/E |
| Role revoked during generation | Before next external batch or publication job stops, result discarded; old JWT cannot override DB | I |
| Workgroup disabled/membership/account/domain/shared grant revoked | Live AssetFilterService denies next operation, no cached report exposure | I/E |
| ADMIN/SECCHAMPION removed during run | Fresh auth loses global shortcut; cannot retain access from stale token | I |
| User disabled/deleted | Denied, no report released | I |
| BETA added, session/JWT still old | Capability sees DB grant; button and POST work without logout; stale sessionStorage not authoritative | I/E |
| Peer job guessed; route asset substituted | 404 even if requester can access both assets; no peer dedupe ID disclosure | I |
| No vulnerabilities / only excluded installers | Complete empty actionable scope, accurate exclusion counts, zero model calls, no “system clean” claim | U/I/E |
| One CVE with verified facts | One supported action and exact coverage/evidence; installation/resolution still unproven | U/I |
| 150 CVEs and supported cumulative updates | Smaller deterministic action set, superseded actions removed only on validated edges; all 150 obligations reconciled; no minimum guarantee | U/I/E |
| Same 150 with missing baseline/build | Supported KB relationships remain conditional; verified coverage is not inflated | U/I/E |
| Thousands beyond a single context | Complete snapshot, grouped/chunked inputs below limits, exact union coverage including final chunk | U/I |
| Beyond snapshot hard limits | SCOPE_TOO_LARGE, zero paid calls, no truncated “complete” report | I |
| Model/evidence budget ends mid-job | PARTIAL with every remaining group in unresolved ledger and reason | U/I/E |
| Duplicate CVEs/products | Exact tuple duplicates consolidated with multiplicity; different product obligations remain independent | U/I |
| No CVE identifiers | Retain source finding refs and investigate; never invent a CVE | U/I |
| Missing OS build/architecture/edition/version/KB inventory | Explicit checks, conditional/insufficient state, no exact applicability claim | U/I/E |
| Stale or missing last seen/inventory | Warning and timestamps/unknown fields; no implicit exclusion or invented recency | U/E |
| Conflicting platform/provider/vendor evidence | Affected candidates quarantined, conflict/manual checks shown | U/I |
| Mixed OS/app/config/firmware/driver | Distinct action types/families and safe dependency order; one OS patch cannot cover unrelated products | U/I |
| Unsupported software | Evidence-backed lifecycle warning, upgrade/migration check; no arbitrary target release | U/I |
| No patch vs retrieval failure | Explicit vendor no-patch may support mitigation; unavailable source means unknown, not “no patch exists” | U/I |
| Installed patch but recorded finding persists | Conflict and rescan/reboot/applicability checks; no automatic resolution | U/I |
| Only some verified fixes | Accurate verified vs conditional vs unresolved counts; complete analysis may still have low remediation coverage | U/E |
| Excepted, expired exception, NO_EDR, installer artifact | Shared current exception semantics; accepted risk not fixed; expiry time affects fingerprint; installer exclusion counted | U/I |
| Import during snapshot / during model call | Snapshot internally coherent; changed-at-completion flag and warning; no DB lock held during provider delay | I/E |
| Reimport recreates IDs / integration resolves projection | Detect changes; no incorrect permanent identity assumption | I |
| Provider timeout / network error | Bounded retry/deadline, safe error, spend reservation retained, no unvalidated report | U/I/E |
| Provider 429 / outage | Retry-After bounded; no retry storm or model fallback; manual retry available | U/I/E |
| Provider invalid key / credit exhausted | Safe operational error, no login redirect, no automatic paid loop | U/E |
| Unsupported model/schema/context | Fail configuration or reject before paid request; never downgrade to unconstrained text | U/I |
| Invalid/truncated/refused output; HTTP 200 error | OUTPUT_INVALID or mapped provider error; no partial JSON rendering | U/E |
| Fabricated KB/version/citation/candidate ID | Unknown output fields or refs rejected; no clickable fabricated link | U/I/E |
| Contradictory actions, supersedence/prerequisite cycle | Reject affected batch/candidates, unresolved ledger retained | U |
| Prompt injection in finding/product/source document | Attack text not sent as instructions or displayed as HTML; cannot create actions/links/tool calls | U/I/E |
| Sensitive values embedded in software string | Field omitted/group classified unknown; serialized outbound fixture contains none of the sentinel identities | U/I |
| Evidence URL SSRF/redirect/DNS/private IPv6/decompression bomb | Denied or bounded before unsafe connection/large allocation; production transport never allows test localhost | U/I |
| Repeated clicks, duplicate request ID, concurrent users | One paid job per allowed owner/system; race-safe dedupe/limits; no peer report reuse | I/E |
| Navigate away, cancel during provider return | Stop polling, best-effort cancel, lease expiry; late response cannot republish; cancellation cost caveat | I/E |
| Cached/retained report after permission loss | Live guard denies and purges before bytes returned; UI clears on focus/poll | I/E |
| Config/evidence changed, TTL elapsed, process restart | Stale warning or expiry, no silent reuse or automatic new charge | U/I/E |
| Accessibility/rendering | Keyboard open/cancel/close, correct focus, screen-reader states, XSS payloads inert, mobile/long-report usability | E |
| Advice-only invariance | Before/after DB comparison: no changed vulnerability/exception/inventory rows from all success/error/cancel paths | I/E |
| BETA schema/rolling rollback | Fresh/upgraded schema accepts role, other roles unchanged, old binary rollback precondition documented | I |

Security assertions must cover each shared access path: direct enabled workgroup membership, AWS/AD workgroup grants, personal mappings, directional sharing, and unrelated workgroups. Verify owner/creator/uploader do not grant access. Use a paused fake provider to deterministically test permission/import/cancellation races.

## 13. Ordered implementation steps

Each step is independently reviewable; do not enable the feature until all dependent gates pass. Re-read AGENTS.md/CLAUDE.md and verify `dev` before editing. Preserve unrelated changes. Use the secure-code skill before endpoint/query/outbound changes; no commits unless requested.

### Step 1 — Lock contracts and evidence fixtures

Dependencies: none. Add proposed `docs/AI_BUDDY.md`, backend test resources `ai-buddy/` (sanitized CrowdStrike remediation and MSRC CVRF fixtures, expected normalized graph, strict model response fixture, adversarial cases). Define DTO/schema enums and reviewed explanation/manual-check text. Inspect actual sanctioned source schemas before writing field mappings. No live endpoint patch advice inferred from examples.

Files: proposed `B/dto/AiBuddyDtos.kt`, `src/backendng/src/main/resources/ai-prompts/ai-buddy.txt`, `ai-schemas/ai-buddy-model-v1.json`; corresponding tests/resources. Verify fixture provenance and 150-CVE graph includes explicit coverage/supersedence/prerequisites, not just a model assertion. Gate: parser/contract tests fail closed on unknown shapes and all identifiers originate from fixtures.

### Step 2 — Add BETA and live access guard

Dependencies: Step 1. Modify User.Role, UserManagement.AVAILABLE_ROLES, permissions.ts; add BetaFeatureAccess and AiBuddyAccessGuard and capability DTO/controller. Reuse UserRepository and AssetFilterService; do not change asset SQL or admin helpers. Add schema compatibility checks and only a conditional migration if the tested schema requires one. Document stale UI/JWT handling.

Verification: `./scripts/runbackendtests.sh --tests '*BetaFeature*' --tests '*AiBuddyAccess*'`; `(cd src/frontend && npm test)`. Test the role matrix, stale ADMIN/SECCHAMPION revocation, enabled-user state and role persistence. Read existing OAuth role serialization and confirm BETA passes through without auto-assignment.

### Step 3 — Complete recorded snapshot and coverage ledger

Dependencies: Steps 1–2. Add proposed `B/service/AiBuddySnapshotService.kt`, `B/repository/AiBuddySnapshotRepository.kt` (projections/parameterized asset reads) and snapshot models in AiBuddyDtos or a focused internal model file. Reuse exception predicate, product class, InstalledProduct and integration projection semantics. Add bounded keyset reads, explicit isolation, digest and freshness logic. Do not modify importer behavior.

Verification: `./scripts/runbackendtests.sh --tests '*AiBuddySnapshot*'`. Real MariaDB tests for interleaved import and snapshot, retained source/severity rows, duplicate/product scope, empty scope vs inaccessible asset, expiry/classification changes and hard limits. Assert SQL query bounds and that provider I/O occurs after transaction release.

### Step 4 — Typed remediation evidence and deterministic consolidation

Dependencies: Steps 1 and 3. Add proposed `B/service/RemediationEvidenceSource.kt`, CrowdStrike/Microsoft adapters, `RemediationPlanner.kt`, `AiBuddyReportValidator.kt`. Add only focused read-only methods/DTOs under `S/client/CrowdStrikeApiClient.kt`, `CrowdStrikeApiClientImpl.kt`, `S/dto/` as required to expose typed evidence; preserve existing DTO/import contracts. Reuse CrowdStrikeAuthService and verified AID resolution. Add fixed MSRC JSON adapter and bounded public cache. Extract/reuse EolCatalogClient URL safety where needed, retaining EOL compatibility tests.

Verification: `./scripts/runbackendtests.sh --tests '*Remediation*' --tests '*AiBuddyReportValidator*' --tests '*EolCatalogClient*'`; `./scripts/test/run-isolated-e2e.sh --database-only -- ./gradlew :shared:test :cli:test`. Tests demonstrate real typed candidate relationships, 150→smaller-set consolidation, ambiguity/conflict/no-patch distinctions, bad URLs, partial pagination and no importer mutation. Gate: unsupported evidence must yield explicit gaps, never synthetic exact patches.

### Step 5 — OpenRouter boundary and controls

Dependencies: Steps 1, 4. Add AiBuddyConfig, AiBuddyModelClient/OpenRouterAiBuddyClient, sanitized outbound DTO builder and byte/depth-bounded response parsing. Bind existing OpenRouterConfig. Add config/env docs and placeholders; do not change legacy AI model selections. Verify key env alias precedence in config tests. Implement metadata/schema/provider-route validation, strict output contract, timeout/retry and usage reservation rules.

Verification: `./scripts/runbackendtests.sh --tests '*OpenRouterAiBuddy*' --tests '*AiBuddyPayload*' --tests '*AiBuddyConfig*'`. Fake HTTP transport records exact serialized payload/provider routing and returns fixture errors; prove no secrets/identity/raw prose leaves the boundary. No paid CI calls. Run existing ComplianceAssistant/Translation tests if shared config/transport files change.

### Step 6 — Temporary job API and lifecycle

Dependencies: Steps 2–5. Add proposed `B/controller/AiBuddyController.kt`, `B/service/AiBuddyJobService.kt`, small job state/limiter classes only where needed. Atomic dedupe/reservations, bounded active/terminal state, owner/asset binding, polling lease, cancellation, source-change checks and generic errors. Use existing configured executor without nested-pool deadlocks. No JPA job entity.

Verification: `./scripts/runbackendtests.sh --tests '*AiBuddyJob*' --tests '*AiBuddyController*'`. Test concurrency races with latches, expiry clock injection, cancellation before/after provider completion, role/access revocation, absent asset, restart simulation, budget accounting including uncertain usage. Compare DB rows before/after all paths.

### Step 7 — Reachable single-system UI and report panel

Dependencies: Steps 2, 6. Add AiBuddyButton/ReportPanel/service and pure state helper; integrate all three rendering locations in §4. Modify AssetIdRedirect to preserve asset ID, and only affected existing links where needed. Implement recorded-system branch in CrowdStrikeVulnerabilityLookup; do not broaden CrowdStrikeController. Add live capabilities on mount/focus/poll and clear reports on auth change. Display scope/privacy text before Generate, partial/freshness/evidence status and safe citations.

Verification: `(cd src/frontend && npm test)`; `(cd src/frontend && npm ci && npm run build && npm run lint)` at implementation time. Add Node tests beside `.ts` helpers; Playwright verifies all entry points, USER+BETA/SECCHAMPION reachability, empty/live/multi-device states, full-scope count vs table page/filter, keyboard behavior and XSS fixtures. No UI command execution/export buttons.

### Step 8 — End-to-end regression, security and rollout documentation

Dependencies: Steps 1–7. Add `tests/e2e/ai-buddy.spec.ts` and a canonical `scripts/test/ai-buddy-e2e.sh` driver if deterministic provider fixtures require setup. The driver must run only through `run-isolated-e2e.sh`, supply stub endpoints/injected fixture beans via the test profile, and never relax production SSRF or secret validation. Do not use browser request mocks as the only backend authorization proof. Verify the stub replaces the actual backend provider, not just the UI response.

Verification commands for implementation (not executed during planning):

```bash
./scripts/runbackendtests.sh
./scripts/test/run-isolated-e2e.sh --database-only -- ./gradlew build
(cd src/frontend && npm ci && npm test && npm run build && npm run lint)
./scripts/test/run-isolated-e2e.sh -- ./scripts/test/ai-buddy-e2e.sh
./scripts/owasp-check.sh
./scripts/check-skill-sync.sh
git diff --check
```

`ai-buddy-e2e.sh` is a proposed deliverable, not an existing command. It should call the existing Playwright suite with the new spec after setting up only owned disposable fixtures; `tests/e2e/run-e2e.sh` already supports runner-owned execution. Read `.agents/skills/_shared/stack-lifecycle.md` before stack operations. Complete mandatory **e2ejs** (admin and normal user, zero JS errors) and **e2evulnexception** (full isolated lifecycle, zero failures), following their full skill instructions. Build/startup gate remains mandatory: canonical `./scripts/startbackenddev.sh`, pass-cli outside sandbox, clean startup evidence and canonical stop, coordinated with those skills. Never point a mutating test at normal data. Perform security review for authorization/outbound HTTP and state the A01–A10 result; HIGH+ findings block enablement. New routes do not require existing extension-client changes unless shared contracts are altered; if altered, perform CLAUDE.md's five-dimension extension compatibility review.

Record runtime/build/E2E/security results honestly in implementation handoff. Do not claim the full feature verified from unit tests alone. No skill files need changing; if later changed, mirror both trees and require sync gate.

## 14. Rollout, disablement and open blockers

1. Deploy default-off code and verify role schema/config binding without assigning BETA or contacting paid providers. Check new endpoint routes/guards and no change in existing user visibility.
2. Complete deterministic tests/security gates and approve data handling, provider route, budget and one-instance deployment. Verify actual CrowdStrike remediation fields/entitlement and MSRC parser with sanctioned public/provider samples. No credentials or production payloads in fixtures.
3. Configure an explicit model/provider route and run a small paid schema canary with non-identifying fixture data. An “API key works” test is insufficient: check schema enforcement, context limit, errors, pricing and actual routing.
4. Enable for a small pilot (ADMIN/SECCHAMPION already eligible; assign BETA additively to selected normal users). Compare report coverage against manual vendor/provider evidence on representative systems, including an intentionally incomplete Windows inventory. Never run a patch to validate this advisory feature.
5. Observe validation failures, partial reports, cost and source latency. Expand only after reports consistently account for every recorded finding and label missing applicability honestly.
6. Disable by setting AI_BUDDY_ENABLED=false and deploying/reloading through the approved operational process. New starts/reads fail closed; cancel/purge in-flight state as the flag is applied. An env-only flag normally needs a process restart; do not promise instant UI disablement without it. Role removal is an immediate per-user control at the next live check. Revoke the dedicated provider credential if an urgent external-spend stop is required, considering other features if a key is shared.
7. Roll back per §11, preserving all vulnerability data and non-BETA roles. No destructive “cleanup” SQL or automatic role backfill.

Unresolved items are explicit rollout/implementation gates, not reasons to guess:

| Item | Recommendation / consequence |
| --- | --- |
| Organization's provider, region, retention and disclosure approval | Default off until recorded; recommended ZDR/no-training route does not substitute for approval. |
| Selected model and endpoint support/pricing | Explicit configuration with metadata+schema canary; no “best model” hardcode. |
| Deployed user_roles shape | Read-only check plus isolated migration tests; migration only if actual enum/constraint requires it. |
| Backend replica count | Temporary in-memory beta requires one instance; otherwise revisit job storage/limits before enablement. |
| Actual CrowdStrike remedy schema and licensed API access | Establish typed sanitized fixtures; unavailable fields produce uncertainty. No fabricated IDs/relationships. |
| Installed-update/build/architecture evidence | Current persistent data usually insufficient. Beta supports conditional recommendations. A future inventory enhancement is separately scoped; do not imply it already exists. |
| Full vendor supersedence/applicability coverage | Limited to explicit typed evidence. Missing chains and unsupported vendors remain visible gaps; no promise of universal smallest-set solving. |
| Existing lookup authorization mismatch | Solve reachability using an authorized recorded-data branch; do not grant BETA the old broad live-lookup API. Any separate security finding in that legacy path should be tracked without unrelated changes. |

Planning verification: source inspection and official-document research only. No database queries/mutations, dependency installation, paid model invocation, service start/restart, build or runtime tests were performed. This document-only change is outside `src/`, `tests/`, `scripts/`; CLAUDE.md principle 7 explicitly permits skipping application E2E gates for this planning change. All proposed application controls above remain to be implemented and verified.
