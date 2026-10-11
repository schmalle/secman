# AI buddy(beta)

AI buddy provides an evidence-supported consolidated remediation report for one accessible system. It does not install patches, execute commands, import provider results, or resolve findings. BETA is an additive feature-access role; ADMIN and SECCHAMPION are implicitly eligible. Every operation reloads the current user and applies the existing asset authorization policy with those current roles and mapping identity. An old session does not preserve a revoked beta/admin grant or prevent a newly assigned BETA role from working.

## Enablement

The feature is off by default. Before enabling it, the deployment owner must approve sending the documented public remediation facts to OpenRouter and the explicitly allowed provider(s), verify the required zero-data-retention route is available, and choose budgets. SecMan does not define organizational retention or approval policy. Do not enable this feature on multiple application instances: job ownership, admission and spend reservations are in memory on a single node. Configure an independent OpenRouter key/account spending limit as well; application daily reservations reset after a process restart.

Configure AI buddy in **Admin → LLM Configuration** (`/admin/translation-config`), using the existing active OpenRouter record. AI buddy has no environment configuration or key fallback. Its credential, base URL, model and maximum output token count come from that record; the output token count is capped at 4,096. Other consumers continue using the same record. Choose a concrete `author/model` identifier if enabling AI buddy; automatic aliases and search/online suffixes are not accepted for this feature.

Saving the shared configuration does not require AI buddy to be ready. An empty provider list, missing single-instance acknowledgment or unsupported AI buddy model can be saved; capabilities and every generation request still fail closed until setup is complete. Common field validity and finite positive budget/price bounds remain enforced.

The configuration list and edit form show AI buddy readiness separately from the shared record's Active status. An enabled record with missing settings is **Setup incomplete**; locally complete settings remain **Setup complete — provider verification pending**. The system-page setup warning links administrators to the active configuration and focuses **Approved provider routes**. Selecting a model never approves a provider automatically.

**Test LLM connection** retains the basic translation check. **Check AI buddy setup** is a separate ADMIN-only metadata preflight (`POST /api/translation-config/ai-buddy/setup`). It accepts draft settings and an optional saved configuration ID, keeps a stored key when the draft key is blank, and never saves settings or submits a completion. It reads the model's endpoint catalogue and the zero-retention endpoint preview using the existing bounded HTTPS transport. Offered routes show availability, structured-output support, output-token capacity, zero-retention eligibility and price-limit compatibility. The edit form loads suggestions automatically once a key and concrete model are present, with a short debounce when settings change. Compatible routes appear first; each route shows its ZDR eligibility and any compatibility limitations. Administrators select route checkboxes, then use Save or Update to persist their choices. Manual tags remain available under Advanced, and Refresh provider routes retries metadata loading. Metadata verification is temporary; generation still applies its existing privacy, capability and budget controls.

Provider metadata uses OpenRouter's [model endpoint catalogue](https://openrouter.ai/docs/api/api-reference/endpoints/list-all-endpoints-for-a-model) and [zero-retention endpoint preview](https://openrouter.ai/docs/api/api-reference/endpoints/preview-the-impact-of-zdr-on-the-available-endpoints). If the setup endpoint is absent on an older running backend, the UI explains that the backend must be rebuilt and restarted before this check becomes available.

The selector lists concrete `openai/gpt-6.1-sol`, `openai/gpt-6.1-sol-pro` and `anthropic/claude-sonnet-5.5` first. New UI configurations default to GPT-6.1 Sol; editing retains the stored model, including a custom ID. IDs were verified on 2026-10-10 against the [OpenRouter models catalogue](https://openrouter.ai/api/v1/models), [GPT-6.1 Sol](https://openrouter.ai/openai/gpt-6.1-sol) and [Sonnet 5.5](https://openrouter.ai/anthropic/claude-sonnet-5.5). Runtime endpoint, structured-output, pricing and privacy checks still apply separately.

The existing form is extended with:

| Admin setting | Default | Meaning |
|---|---|---|
| Enable AI buddy(beta) | false | Master switch on this record; only the active record is used |
| Single backend instance acknowledgment | false | Required because jobs and spending reservations are process-local |
| Approved provider endpoint tags | empty | Comma-separated, explicitly approved OpenRouter routes |
| Maximum reservation per report | USD 0.50 | Includes a bounded second provider attempt |
| Daily AI buddy reservation budget | USD 10 | Process-local UTC daily ceiling |
| Maximum input/output price per million tokens | USD 5 / 20 | Provider routing price ceilings |
| Microsoft evidence | true | Read-only public CVRF enrichment |
| CrowdStrike evidence | false | Opt-in read-only Spotlight enrichment with existing Falcon credentials |

Saving, deactivating, deleting or switching the active common configuration takes effect without restarting the service. Running analyses stop at their next checkpoint if the settings changed. An existing completed report is labelled stale when the active model, provider, budgets, evidence options or record revision changes; disabling AI buddy or removing the active configuration makes it unavailable. No environment fallback silently revives it.

Migration `V285__openrouter_ai_buddy_settings.sql` adds fields to `translation_config` with beta disabled for existing rows. The API key uses the existing `EncryptedStringConverter`; legacy plaintext remains readable and is encrypted on its next save. The column is widened for ciphertext. Read responses keep the `***HIDDEN***` mask; leaving the edit field blank or returning that mask preserves the actual key. Supply real credentials through the existing secure admin workflow; never place one in source, test fixtures or browser storage. Do not roll back to a binary that cannot decrypt newly encrypted keys; retain converter support or arrange a reviewed credential migration.

Resource ceilings are fixed in `AiBuddyConfig.kt`, rather than exposed through environment overrides: two concurrent jobs, five user starts/hour, ten asset starts/hour, 1,000 global starts/hour, 60-second provider request/client lease, 300-second job deadline, 30-minute report retention, 100 retained jobs/100 MiB, 50,000 findings, 10,000 inventory rows, 20 MiB snapshot, 10 MiB report, 512 KiB model request, 100 evidence requests/24 documents/40 MiB. Approved endpoint metadata must confirm structured outputs, context, completion capacity and current prices before a paid request. Routing requires `only`, `allow_fallbacks:false`, `require_parameters:true`, `data_collection:deny`, `zdr:true` and maximum prices. These privacy requirements cannot be weakened in the form.

Requests have at most two attempts for timeouts/server failures, within the job deadline. Rate limits return Retry-After without immediate retry. Credentials/credit/policy/output failures are not retried. Reservations are retained for ambiguous billing outcomes. Logs record job outcome, counts, payload/report size, usage token counts and cost reservation, never prompts, model text, credentials or inventory text. No new dependency is required.

## Patch guide workflow

**Create patch guide · AI buddy** starts analysis in one click. The first result is a numbered checklist with update targets, prerequisite checks, restart/verification steps and allowlisted vendor links. Detailed findings, evidence and timestamps remain expandable. Copy patch guide includes the instructions, actual report scope, expiry, conditional status and remaining product groups. It does not run installation commands or mark findings resolved.

Windows Server 2019 1809 and FireFox aliases are recognized explicitly. The release designation 1809 is not converted into a full build. A full build, edition and architecture are extracted only when present in the recorded OS string. Microsoft recommendations remain conditional where variant, servicing or installed-update evidence is missing. Existing same-variant supersedence consolidation remains authoritative.

Mozilla enrichment reads the public version catalogue and fixed-version statements in Mozilla security advisories using the existing bounded transport. Release and ESR remain distinct; ESR targets stay within the recorded major branch. At most 24 advisory documents are read per guide, so older unmatched CVEs remain investigation items. Ambiguous multi-product/multi-branch pages are rejected until per-CVE branch applicability can be established. Supported-platform and current-inventory checks remain explicit prerequisites. A latest-version catalogue entry alone never proves CVE coverage.

The completion form accepts only full Windows build, Standard/Datacenter edition, allowlisted architecture, numeric Firefox version and explicit Release/ESR channel. Firefox version and channel must be provided together. These facts are temporary inputs to the owned job, not database updates or installation evidence. Multiple recorded Firefox versions require reconciliation instead of being replaced by one supplied version. Every guide using supplied facts is labelled as requiring confirmation. Existing asset access, current-role checks, budgets, report expiry and cancellation still apply. The optional `inventory` object on job creation is advertised by `inventoryInputSupported` capabilities; older backends continue accepting the original request.

The UI normalizes and validates the report wire shape before sorting/rendering. Ready to apply, Confirm before applying and Needs investigation are evidence states, not proof of completed installation. If no target is supported, the guide offers focused inventory collection and vendor investigation rather than fabricating a fix. Read-only PowerShell collection examples run only if the user chooses to run them on the affected machine; full output is not sent to the model.

## Evidence and scope

A short dedicated repeatable-read JDBC transaction captures currently retained HIGH and CRITICAL vulnerability rows for the asset across sources, installed-product context, provider identities, integration/EOL context versions, last seen and timestamps. Keyset pagination is used, not a table-page subset or latest-import slice. Exception matching uses `ExceptionMatchSql.EXCEPTION_MATCH` at one captured database time. Excepted findings remain included and labelled. Explicit INSTALLER_ARTIFACT rows are excluded and counted; UNKNOWN rows remain included. Resolved integration projections that have already been removed by existing imports are not recreated. No new suppressed/resolved flags are invented.

Microsoft CVRF documents are fetched from a fixed HTTPS API. Product-tree IDs, affected product statuses and vendor-fix remediation entries create the product/CVE/KB edges. Same-variant supersedence can inherit older supported coverage and citations. Different product variants cannot share an edge; cycles fail and conflicting build/reboot records are not selected. Greedy consolidation aims for a small supported set; it does not establish mathematical minimality. Unverified provider investigations are grouped by recognized product instead of generating one generic action per CVE; these groups carry no verified patch coverage.

SecMan does not currently have reliable Windows build/edition/architecture and installed-update inventory. Therefore Microsoft recommendations remain **requires applicability check** until those conditions and servicing prerequisites have evidence. Numeric patch/build identifiers come from typed vendor records, never model knowledge. A vendor-listed cumulative update is not automatically applicable to the system. Review vendor servicing-stack prerequisites, product branch, reboot and installation order. A recommendation is not installation evidence; a recorded finding is not conclusive evidence of current presence. No report changes vulnerability status.

CrowdStrike enrichment validates AID identity, pagination totals/cursors and product/CVE matching before accepting a device's evidence. Imported provider text cannot establish exact patch applicability. Current enrichment retrieves typed Spotlight remediation IDs from matched applications and reads the v2 remediation entities. It displays their bounded advice as untrusted plain text with an investigation check, rather than extracting exact KBs, versions or commands from free text. Advice is never sent to the model. Missing/unsupported structured vendor data remains an explicit gap. Application/configuration/firmware/driver/unsupported-product/no-patch cases without verified relationships require vendor investigation, an upgrade/migration or a documented mitigation; the assistant cannot invent one.

The MSRC search and public parsed-document cache are bounded (24 documents, at most 24 hours). A report may be partial when a relevant older document is outside the search, evidence is unavailable, or budgets are exhausted. Candidate graphs are bounded to 1,000 unique action IDs; overflow is explicitly partial and unanalysed findings remain in the ledger with no verified remediation. Supersedence traversal is memoized and bounded in depth. Every finding in this HIGH/CRITICAL snapshot still appears in the coverage ledger. Unknown products and findings without CVEs are not silently dropped.

## Model and privacy boundary

Only closed candidate IDs, recognized public product names, numeric vendor patch/build/version identifiers, action/evidence-state enums, prerequisite codes, dependency IDs and aggregate coverage counts leave SecMan for OpenRouter. Hostnames, IPs, account/user IDs, usernames, AIDs, raw finding descriptions, paths, documents and provider recommendations do not enter the model payload. IDs are hashes of public candidate facts; database finding IDs are not sent. The model only orders the supplied candidates, returning each known ID once. Unknown fields, fabricated IDs/citations, duplicates, dependency inversion and truncated/invalid output fail validation. The backend builds the complete report, verifies its counts/references/evidence states and joins local system identity afterwards.

When no supported candidate exists, no paid model request is needed. When model/context/output validation fails, a deterministic evidence report is explicitly marked partial with that failure. This is a deliberate focused implementation of the plan's model role: there is no multi-request narrative summarization or chat. Large recorded sets are fully represented locally; excessive model candidate input is labelled SCOPE_TOO_LARGE in the partial report rather than truncated.

Evidence retrieval is separate from OpenRouter. It uses fixed public host allowlists, HTTPS, public-address checks, no redirects, bounded response bodies/depth and request deadlines. Deployment egress rules must also prohibit private destinations to close the DNS-resolution/connection rebinding gap. No user-supplied URL is fetched. The browser renders plain React text and allowlisted HTTPS citations with no-referrer/noopener/noreferrer; it does not render model HTML or Markdown.

## API and lifecycle

All routes are authenticated, no-store, live beta/asset guarded and owner-scoped:

- `GET /api/ai-buddy/capabilities`: live roles, eligibility, availability and safe disablement reason.
- `POST /api/ai-buddy/assets/{assetId}/jobs`: strict `{requestId:<lowercase UUID>}`, 202 with job ID.
- `GET /api/ai-buddy/assets/{assetId}/jobs/{jobId}`: stage/status and client-lease heartbeat.
- `GET /api/ai-buddy/assets/{assetId}/jobs/{jobId}/report`: validated complete coverage ledger; current fingerprint/evidence expiry is checked and stale data labelled.
- `DELETE /api/ai-buddy/assets/{assetId}/jobs/{jobId}`: best-effort cancellation/deletion of report content.

States are RUNNING, COMPLETE, FAILED, CANCELLED; stages are SNAPSHOT, EVIDENCE, ANALYSIS, VALIDATION and terminal state. `report.analysis.completeness` distinguishes partial evidence/model results. Empty findings produce an empty complete report without a model call. Duplicate request IDs are bound to owner/asset, and another click for an active owner/asset reuses that job. A new generation after completion uses a new UUID and a fresh snapshot. There is no silent report-result cache.

Reports are temporary, owned by their requesting user and asset; no database report entity/migration exists. They expire in at most 30 minutes, disappear on restart/cancel/feature disablement and are inaccessible after a role or asset grant is revoked. Running permissions are checked at each boundary and by a periodic sweep. Deleted assets fail the same authorization boundary as inaccessible assets. Retained source fingerprints detect imports during and after generation. Report retrieval indicates changed source data, expired/revised public evidence, or changed model configuration. The browser clears expired/unauthorized reports, checks capabilities on mount/focus/every 30 seconds and polls active jobs every two seconds. Navigation cancels best effort; an abandoned client lease expires within 60 seconds.

Safe error codes include AUTH_REQUIRED (401), BETA_REQUIRED (403), ASSET_OR_JOB_NOT_FOUND (404), INVALID_REQUEST (400), REPORT_NOT_READY/REQUEST_ID_CONFLICT, REQUEST_LIMITED/CAPACITY_EXHAUSTED/BUDGET_EXHAUSTED (429), SCOPE_TOO_LARGE (422), FEATURE_DISABLED/NOT_CONFIGURED/MODEL_UNSUPPORTED/provider failures (503). Failure detail never contains provider bodies or stack traces.

## UI and verification

The inline panel is available on `/vulnerabilities/system` (recorded assetId branch or accessible legacy hostname), `/outdated-assets/{id}` and embedded `VulnerabilityHistory`. Asset redirects retain assetId. Plain users and champions use the recorded-data branch; ADMIN/VULN legacy query behavior is preserved when no assetId is provided. The existing CrowdStrike controller is not widened. An accessible recorded asset is required even when legacy live results are visible. Scope/privacy text appears before generation. Keyboard users can activate controls, move into the report and close with Escape; progress/errors use live/status regions. Findings are displayed 50 per page, independent of backend scope.

Controlled unit/integration fixtures cover role additions/revocation, scope and fingerprints, public MSRC relationships, 150-to-one consolidation, thousands of unresolved findings, product branch/cycle safeguards, closed output, privacy/routing, provider failure states and owner/cancellation/repeated-click behavior. Provider parsing follows the [official Spotlight operations](https://developer.crowdstrike.com/api-reference/collections/spotlight-vulnerabilities/) and the [provider-owned detailed-event example](https://library.humio.com/examples/examples-crowdstrike-spotlight-793e2249-352a-4204-9250-1dfecc460968.html) for top-level AID and application remediation IDs. The reduced MSRC fixture records facts from `https://api.msrc.microsoft.com/cvrf/v3.0/cvrf/2025-Jul` (retrieved 2026-10-09); it is evidence about that response, not a current patch recommendation for arbitrary systems. Default tests do not call paid models.

Run backend tests only through `./scripts/runbackendtests.sh` or the disposable runner. Full verification commands and plan remain in `docs/plans/ai-buddy-beta-implementation-plan.md`. Rollback: disable AI buddy in the active admin OpenRouter configuration and remove the UI/backend change through normal deployment. The additive role can remain stored and grants no administrative or object access by itself. Existing users retain their roles unchanged; VARCHAR role persistence accepts BETA without a new schema migration. Do not deploy an old binary to users with BETA assignments unless those assignments are removed first: old enum readers cannot deserialize an unknown role.


## Previous implementation verification — 2026-10-09

Final-tree checks completed on `dev`:

- `./scripts/test/run-isolated-e2e.sh --database-only -- ./gradlew build`: 1,739 backend tests (including 30 AI buddy tests), 164 CLI tests and 75 shared tests; zero failures, errors or skips.
- Frontend `npm test`, `npm run build`, `npm run lint`: 318 tests passed, production build passed, zero lint errors; 348 existing repository warnings remain.
- AI buddy Playwright fixture: both tests passed, including live BETA assignment with an old session, USER+BETA page access, object isolation, owner isolation, revocation, empty scope and keyboard/close behavior. The final run also asserted explicit report completeness wording.
- `e2ejs`: all 74 discovered pages clean as admin and all 74 clean as normal user; zero JavaScript errors.
- `e2evulnexception`: two consecutive full runs passed (phases 0–10, MCP, browser and cleanup).
- `./scripts/owasp-check.sh`: zero blocking findings; nine REVIEW items; feature-related items assessed in the security review and pre-existing branch items identified as outside this patch. `./scripts/check-skill-sync.sh` and `git diff --check` passed.

Database and browser verification used marked disposable schemas through the isolated runner. The normal stack was not restarted and its database was not used for fixture mutations. All provider tests used controlled transport fixtures. The original AI browser fixture used environment flags; the revised fixture stores `fixture/model`, `fixture` routing and both disabled evidence adapters in the common admin record; no paid model call or production provider canary was performed. Actual enablement remains subject to the deployment conditions above.

The HTTP contract explicitly preserves empty arrays, zero counts, false booleans and null inventory values through Micronaut Serde. The central identity rule retains its enabled-user/name/email checks and stale-role rejection on other paths; only `/api/ai-buddy/` defers role equality to the feature's mandatory current-user and asset guard.


## Shared configuration verification — 2026-10-10

AI buddy uses the active admin-managed OpenRouter record exclusively; no `AI_BUDDY_*` environment entries or `secman.ai.buddy` bindings remain in the application configuration. Current gates passed on `dev`:

- `./scripts/test/run-isolated-e2e.sh --database-only -- ./gradlew build`: 1,743 backend tests (34 AI buddy tests), 164 CLI and 75 shared tests; zero failures, errors or skips. CLI/shared tasks were up-to-date from their successful prior build.
- Frontend `npm test`, `npm run build`, `npm run lint`: 318 tests passed, production build passed, zero lint errors; 348 existing warnings remain.
- Three AI buddy browser tests passed: live role/object/owner authorization, honest empty report and keyboard interaction, and admin editing the common controls without replacing the stored key. A direct configuration mutation by USER+BETA was forbidden. The test waits for login-form hydration before filling fields to avoid a cold-page timing race.
- Dual-role JavaScript scans passed all 74 routes for ADMIN and all 74 for the normal user, with zero errors.
- Two consecutive full vulnerability-exception lifecycle runs passed through phase 10 and cleanup.
- `owasp-check.sh`: zero blocking findings, nine review items assessed in the security review; skill sync and whitespace checks passed.

All database writes and service lifecycle operations for these checks used disposable isolated schemas/stacks. No normal-stack restart, production database mutation or paid provider call was performed. The isolated stack applied migration V285 successfully; the encryption persistence test also ran against a disposable MariaDB schema. Existing legacy keys remain readable until saved. Provider metadata, approved route availability and real credential behavior still require the deliberate rollout canary described above.


## Save regression and model preset verification — 2026-10-10

The admin save validator no longer conflates persisted shared settings with beta generation readiness. A regression test failed before the fix and passed afterward. The existing request-time gates still reject incomplete beta configuration. The model selector and `/api/translation-config/models` now include the current concrete GPT-6.1 Sol, GPT-6.1 Sol Pro and Sonnet 5.5 IDs; only new UI records default to GPT-6.1 Sol.

Verification passed: full build with 1,744 backend, 164 CLI and 75 shared tests (zero failures/errors/skips); 318 frontend tests, build and lint (zero errors, 348 existing warnings); all three AI buddy browser tests including enabled-but-incomplete save and unavailable capabilities; 74-page scans as each role; two consecutive vulnerability-exception lifecycle runs. The capability assertion uses browser fetch to retain secure cookie semantics on the isolated loopback target. All test mutations used disposable schemas. No paid model request was made. OWASP remained zero blocking/nine reviewed findings; no new HIGH/CRITICAL issue was identified in the save correction.

After verification, the normal backend and frontend were restarted through the canonical scripts and left running at the user’s request. Both ports bound; authenticated API login and the updated model-list endpoint passed through the configured SecMan HTTPS host with system TLS verification. No saved LLM configuration was changed by the smoke check.

The capability response distinguishes `enabled` from `available`. An enabled
configuration with missing prerequisites keeps the AI buddy panel accessible,
explains the specific missing setting, and withholds report generation until
setup is complete. A blank approved provider list reports
`APPROVED_PROVIDER_REQUIRED`; it never silently chooses another provider.
