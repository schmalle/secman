# Web Security Scanner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a production-oriented Python web security scanner in `extensions/secman_web_check` that scans one target or a text-file target list, reports security-header/TLS/misconfiguration findings in four formats, optionally stores results in MariaDB, and optionally submits them to SecMan under a new `WEB_SECURITY` source.

**Architecture:** The extension owns all scanner code, checks, tests, scripts, SQL, examples and scanner documentation. Native Python components collect bounded HTTP evidence and run HTTP checks; an SSLyze adapter performs advanced TLS analysis; every output and integration consumes one immutable normalized result model. The parent SecMan repository changes only to accept and display `WEB_SECURITY` and to document the integration.

**Tech Stack:** Python 3.12+, uv, Typer, Rich, HTTPX/httpcore, SSLyze, PyMySQL, pytest, pytest-httpx, Ruff, mypy; MariaDB 11.4; Kotlin 2.4.10/Micronaut 5.1; Astro 7.2/React 19.

**Spec:** `docs/superpowers/specs/2026-09-09-web-security-scanner-design.md`

## Global Constraints

- All scanner implementation files live under `extensions/secman_web_check`; do not add scanner runtime code to the parent project.
- Python requires version 3.12 or newer and all dependency operations use `uv` with a committed lockfile.
- Default scans are non-invasive; active probes require `--active`.
- Private targets require `--allow-private-targets`; loopback, link-local, multicast and unspecified addresses remain denied.
- Scans are restricted to explicit targets: no crawling, port discovery, exploitation, authentication attacks, brute force or arbitrary fuzzing.
- Terminal, JSON, SARIF and self-contained HTML render from the same normalized model.
- MariaDB persistence and SecMan REST upload are separately opt-in and must not destroy local report results on failure.
- Secrets come from environment variables populated by `pass-cli`; never accept a password/token literal on the command line or commit one.
- The parent integration contract remains version 1; `WEB_SECURITY` is additive and must not change existing `GITHUB_AI` or `VISUAL` behavior.
- Each extension task commits in the nested repository with `git -C extensions/secman_web_check`; parent changes commit from the SecMan root.
- Follow TDD: observe each focused test fail before implementing, then run the focused file and the accumulated extension suite.
- Do not run destructive SecMan E2E tests until `SECMAN_HOST` is confirmed to be a disposable instance.

---

## File Structure

Extension-owned files:

```text
extensions/secman_web_check/
├── .env.example
├── .gitignore
├── .python-version
├── README.md
├── pyproject.toml
├── uv.lock
├── db/
│   ├── grants.example.sql
│   └── migrations/001_initial.sql
├── docs/
│   ├── CHECKS.md
│   ├── CONFIGURATION.md
│   ├── DATABASE.md
│   ├── REPORTS.md
│   ├── SAFETY.md
│   └── SECMAN.md
├── examples/
│   ├── config.toml
│   └── targets.txt
├── scripts/
│   ├── install-db.sh
│   ├── scan-list.sh
│   ├── scan-single.sh
│   ├── setup.sh
│   └── verify.sh
├── src/secman_web_check/
│   ├── __init__.py
│   ├── __main__.py
│   ├── checks/{__init__,active,content,cookies,cors,headers,registry}.py
│   ├── cli.py
│   ├── config.py
│   ├── http.py
│   ├── models.py
│   ├── orchestrator.py
│   ├── reports/{__init__,html,json_report,sarif,terminal}.py
│   ├── secman.py
│   ├── storage.py
│   ├── targets.py
│   └── tls.py
└── tests/
    ├── fixtures/integration-run-v1.json
    ├── test_active_checks.py
    ├── test_cli.py
    ├── test_config.py
    ├── test_content_checks.py
    ├── test_cookie_checks.py
    ├── test_cors_checks.py
    ├── test_header_checks.py
    ├── test_http.py
    ├── test_models.py
    ├── test_orchestrator.py
    ├── test_reports.py
    ├── test_secman.py
    ├── test_storage.py
    ├── test_targets.py
    └── test_tls.py
```

Parent-owned modifications:

```text
src/backendng/src/main/kotlin/com/secman/service/IntegrationRunValidator.kt
src/backendng/src/test/kotlin/com/secman/service/IntegrationRunValidatorTest.kt
src/frontend/src/components/IntegrationScannerConfig.tsx
src/frontend/src/components/IntegrationFindingList.tsx
src/frontend/src/services/integrationSources.ts
src/frontend/src/services/integrationSources.test.ts
docs/EXTENSIONS.md
docs/INTEGRATION_RESULTS.md
docs/contracts/integration-run-v1.json
```

---

### Task 1: Package skeleton, normalized models and configuration

**Files:**
- Create: `extensions/secman_web_check/pyproject.toml`
- Create: `extensions/secman_web_check/.python-version`
- Create: `extensions/secman_web_check/.gitignore`
- Create: `extensions/secman_web_check/.env.example`
- Create: `extensions/secman_web_check/src/secman_web_check/{__init__,__main__,models,config}.py`
- Create: `extensions/secman_web_check/tests/{test_models,test_config}.py`
- Modify: `extensions/secman_web_check/README.md`

**Interfaces:**
- Produces: `Severity`, `TargetStatus`, `Finding`, `TargetResult`, `ScanRun`, `ScannerConfig`.
- Produces: `load_config(config_path: Path | None, environ: Mapping[str, str], **overrides: object) -> ScannerConfig`.
- Consumes: no scanner code; this establishes all shared names.

- [ ] **Step 1: Write model and configuration tests**

```python
def test_finding_identity_ignores_mutable_title_and_severity():
    first = Finding.create("WEB-HSTS-001", "https://example.com/", Severity.HIGH, "Missing HSTS")
    changed = Finding.create("WEB-HSTS-001", "https://example.com/", Severity.LOW, "New title")
    assert first.external_id == changed.external_id

def test_cli_values_override_environment_and_toml(tmp_path):
    path = tmp_path / "scanner.toml"
    path.write_text('[scan]\nconcurrency = 4\n', encoding="utf-8")
    config = load_config(path, {"SECMAN_WEB_CHECK_CONCURRENCY": "6"}, concurrency=8)
    assert config.concurrency == 8
```

- [ ] **Step 2: Run the tests and verify the imports fail**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_models.py tests/test_config.py -q`

Expected: FAIL because `secman_web_check.models` and `secman_web_check.config` do not exist.

- [ ] **Step 3: Add package metadata and minimal immutable types**

Use `dataclasses.dataclass(frozen=True, slots=True)`, UTC-aware `datetime`, and enums with exact wire values. The core shape must include:

```python
class Severity(StrEnum):
    INFO = "INFO"; LOW = "LOW"; MEDIUM = "MEDIUM"; HIGH = "HIGH"; CRITICAL = "CRITICAL"

@dataclass(frozen=True, slots=True)
class Finding:
    rule_id: str
    external_id: str
    severity: Severity
    confidence: float
    title: str
    description: str
    recommendation: str
    evidence: str
    url: str
```

Add Typer, Rich, HTTPX, httpcore, SSLyze and PyMySQL runtime dependencies; pytest, pytest-httpx, Ruff and mypy development dependencies. Pin compatible HTTPX/httpcore minor versions because the security transport directly implements httpcore's documented backend protocol. Configure Ruff for Python 3.12 and a 100-column limit, mypy strict mode for `src`, and pytest `testpaths = ["tests"]`. Generate `uv.lock` with `uv lock`.

- [ ] **Step 4: Run focused and package checks**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_models.py tests/test_config.py -q && uv run ruff check src tests && uv run mypy src`

Expected: all commands exit 0.

- [ ] **Step 5: Commit in the extension repository**

```bash
git -C extensions/secman_web_check add pyproject.toml uv.lock .python-version .gitignore .env.example README.md src tests/test_models.py tests/test_config.py
git -C extensions/secman_web_check commit -m "feat: scaffold scanner models and configuration"
```

### Task 2: Target parsing and network-address policy

**Files:**
- Create: `extensions/secman_web_check/src/secman_web_check/targets.py`
- Create: `extensions/secman_web_check/tests/test_targets.py`

**Interfaces:**
- Consumes: `ScannerConfig`.
- Produces: `NormalizedTarget`, `AddressPolicy`, `normalize_target(value: str) -> NormalizedTarget`, `load_targets(single: str | None, file: Path | None) -> tuple[NormalizedTarget, ...]`, `resolve_allowed(target, policy, resolver=socket.getaddrinfo) -> tuple[str, ...]`.

- [ ] **Step 1: Write parsing and policy tests**

```python
@pytest.mark.parametrize("value", ["http://user:pass@example.com", "file:///etc/passwd", "https://example.com/#secret"])
def test_rejects_unsafe_target_forms(value):
    with pytest.raises(TargetError):
        normalize_target(value)

def test_private_addresses_require_opt_in():
    target = normalize_target("https://internal.example")
    resolver = lambda *_: [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("10.1.2.3", 443))]
    with pytest.raises(AddressDenied):
        resolve_allowed(target, AddressPolicy(), resolver)
    assert resolve_allowed(target, AddressPolicy(allow_private=True), resolver) == ("10.1.2.3",)

def test_loopback_remains_denied_with_private_opt_in():
    assert not AddressPolicy(allow_private=True).allows(ipaddress.ip_address("127.0.0.1"))
```

- [ ] **Step 2: Verify focused tests fail**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_targets.py -q`

Expected: FAIL because the target API is absent.

- [ ] **Step 3: Implement normalization, file parsing and IP classification**

Normalize IDNA hostnames, lowercase scheme/host, remove default ports, retain non-root paths, strip fragments, reject userinfo and non-HTTP schemes, and use HTTPS when the input has no scheme. Treat every address from `getaddrinfo` as untrusted and require all returned addresses to pass policy. Batch parsing must ignore blank/comment lines, preserve first-seen order and deduplicate normalized URLs.

- [ ] **Step 4: Run target tests and accumulated suite**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_targets.py -q && uv run pytest -q`

Expected: PASS.

- [ ] **Step 5: Commit in the extension repository**

```bash
git -C extensions/secman_web_check add src/secman_web_check/targets.py tests/test_targets.py
git -C extensions/secman_web_check commit -m "feat: validate scanner targets and address policy"
```

### Task 3: Bounded HTTP collection and redirect revalidation

**Files:**
- Create: `extensions/secman_web_check/src/secman_web_check/http.py`
- Create: `extensions/secman_web_check/tests/test_http.py`

**Interfaces:**
- Consumes: `NormalizedTarget`, `AddressPolicy`, `resolve_allowed`.
- Produces: `HttpRequest`, `HttpResponseEvidence`, `PinnedNetworkBackend`, `HttpCollector.collect(target, method="GET", headers=None) -> tuple[HttpResponseEvidence, ...]`.
- Guarantees: every connection dials a previously approved IP while preserving the original hostname for `Host`, TLS SNI and certificate verification; every redirect is independently resolved and policy-checked; body bytes and redirect count are bounded; no automatic redirect following.

- [ ] **Step 1: Write collection safety tests**

```python
def test_redirect_destination_is_checked_before_second_request(httpx_mock, collector):
    httpx_mock.add_response(url="https://example.com/", status_code=302, headers={"Location": "http://127.0.0.1/admin"})
    with pytest.raises(AddressDenied):
        collector.collect(normalize_target("https://example.com"))
    assert len(httpx_mock.get_requests()) == 1

def test_body_is_truncated_at_configured_limit(httpx_mock, collector):
    httpx_mock.add_response(url="https://example.com/", content=b"x" * 2048)
    response = collector.with_max_body_bytes(1024).collect(normalize_target("https://example.com"))[-1]
    assert len(response.body) == 1024
    assert response.truncated is True

def test_transport_dials_only_the_address_that_passed_policy(pinned_backend, collector):
    collector.collect(normalize_target("https://example.com"))
    assert pinned_backend.dialed_hosts == ["93.184.216.34"]
    assert pinned_backend.tls_server_names == ["example.com"]
```

- [ ] **Step 2: Verify focused tests fail**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_http.py -q`

Expected: FAIL because `HttpCollector` does not exist.

- [ ] **Step 3: Implement the bounded collector**

Use an HTTPX client with `follow_redirects=False`, certificate verification enabled, separate connect/read/write/pool timeouts, `Accept-Encoding: identity`, a fixed identifying user agent, and streaming reads stopped at `max_body_bytes`. Implement a narrowly scoped httpcore `SyncBackend` adapter whose `connect_tcp` substitutes one approved resolved IP for the hostname while `start_tls` retains the original hostname as `server_hostname`; create a bounded pool for that target/redirect and fail over only to another address from the already approved set. Re-run normalization, DNS resolution and address policy for each redirect. Reject missing/invalid/cross-scheme locations according to configuration and record sanitized timing/status/header/body evidence. Never include URL userinfo, authorization headers, cookie values or response `Set-Cookie` values in an exception message.

- [ ] **Step 4: Run focused tests, security cases and accumulated suite**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_http.py -q && uv run pytest -q`

Expected: PASS, including redirect-to-private and oversized-response cases.

- [ ] **Step 5: Commit in the extension repository**

```bash
git -C extensions/secman_web_check add src/secman_web_check/http.py tests/test_http.py
git -C extensions/secman_web_check commit -m "feat: collect bounded HTTP evidence"
```

### Task 4: Passive HTTP security checks

**Files:**
- Create: `extensions/secman_web_check/src/secman_web_check/checks/{__init__,registry,headers,cookies,cors,content}.py`
- Create: `extensions/secman_web_check/tests/{test_header_checks,test_cookie_checks,test_cors_checks,test_content_checks}.py`

**Interfaces:**
- Consumes: `HttpResponseEvidence`, `Finding`, `Severity`.
- Produces: `CheckContext`, `Check = Callable[[CheckContext], tuple[Finding, ...]]`, `PASSIVE_CHECKS`, and check functions whose stable IDs begin `WEB-HEADER-`, `WEB-COOKIE-`, `WEB-CORS-`, `WEB-CONTENT-` or `WEB-TRANSPORT-`.

- [ ] **Step 1: Write table-driven tests for each rule family**

```python
@pytest.mark.parametrize(("headers", "rule"), [
    ({}, "WEB-HEADER-HSTS-MISSING"),
    ({"strict-transport-security": "max-age=60"}, "WEB-HEADER-HSTS-WEAK"),
    ({"content-security-policy": "default-src * 'unsafe-inline'"}, "WEB-HEADER-CSP-UNSAFE"),
])
def test_header_rules(headers, rule, https_context):
    assert rule in {finding.rule_id for finding in check_headers(https_context(headers=headers))}

def test_cookie_evidence_never_contains_cookie_value(context):
    findings = check_cookies(context(set_cookie="session=top-secret; Path=/"))
    assert findings and "top-secret" not in findings[0].evidence
```

Cover HSTS, CSP, X-Content-Type-Options, framing, Referrer-Policy, Permissions-Policy, COOP/COEP/CORP, cache policy, disclosure headers, cookie flags/prefixes/scope, wildcard/reflected CORS evidence, downgrade redirects, directory listing, debug/error leakage, internal-address leakage, insecure forms, mixed active content and security.txt status.

- [ ] **Step 2: Verify all four focused files fail**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_header_checks.py tests/test_cookie_checks.py tests/test_cors_checks.py tests/test_content_checks.py -q`

Expected: FAIL because the check modules do not exist.

- [ ] **Step 3: Implement the explicit passive rule catalogue**

Each rule must define immutable metadata:

```python
Rule("WEB-HEADER-HSTS-MISSING", Severity.MEDIUM, "HSTS header is missing", confidence=1.0)
```

Parse headers case-insensitively while retaining duplicates, parse CSP directives without evaluating script, parse cookies without values in evidence, and parse HTML with `html.parser` rather than executing it. Bound every evidence string before creating a finding. Heuristic content rules use confidence below `1.0` and require multiple stable indicators where possible.

- [ ] **Step 4: Run rule tests, full suite, Ruff and mypy**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_*checks.py -q && uv run pytest -q && uv run ruff check src tests && uv run mypy src`

Expected: all commands exit 0.

- [ ] **Step 5: Commit in the extension repository**

```bash
git -C extensions/secman_web_check add src/secman_web_check/checks tests/test_header_checks.py tests/test_cookie_checks.py tests/test_cors_checks.py tests/test_content_checks.py
git -C extensions/secman_web_check commit -m "feat: add passive web security checks"
```

### Task 5: Explicit active misconfiguration probes

**Files:**
- Create: `extensions/secman_web_check/src/secman_web_check/checks/active.py`
- Create: `extensions/secman_web_check/tests/test_active_checks.py`

**Interfaces:**
- Consumes: `HttpCollector`, `CheckContext`, `Finding`.
- Produces: `ActiveProbe`, `ACTIVE_PROBES`, `run_active_checks(context, collector) -> tuple[Finding, ...]`.
- Guarantees: zero active requests unless `ScannerConfig.active is True`.

- [ ] **Step 1: Write gating and false-positive tests**

```python
def test_active_probes_make_no_requests_when_disabled(recording_collector, context):
    assert run_active_checks(context, recording_collector, enabled=False) == ()
    assert recording_collector.requests == []

def test_env_probe_requires_signature(not_found_shaped_as_200, context):
    findings = run_probe(ENV_PROBE, context, not_found_shaped_as_200)
    assert findings == ()
```

Add tests for TRACE reflection, unsafe OPTIONS methods, `.env`, `.git/HEAD`, server-status and backup/config probes, and prove redirect/address policy is reused for every request.

- [ ] **Step 2: Verify focused tests fail**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_active_checks.py -q`

Expected: FAIL because active probes are absent.

- [ ] **Step 3: Implement a fixed allowlist and content signatures**

Each `ActiveProbe` specifies method, relative path, safe headers, maximum response size and a predicate requiring content indicators plus negative controls. Do not accept operator-supplied arbitrary paths or methods. Send no credentials or cookies. Use synthetic origins such as `https://scanner.invalid` for active CORS tests and mark those findings with the exact probe evidence.

- [ ] **Step 4: Run focused and accumulated tests**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_active_checks.py -q && uv run pytest -q`

Expected: PASS.

- [ ] **Step 5: Commit in the extension repository**

```bash
git -C extensions/secman_web_check add src/secman_web_check/checks/active.py tests/test_active_checks.py
git -C extensions/secman_web_check commit -m "feat: add opt-in active misconfiguration probes"
```

### Task 6: SSLyze TLS adapter and TLS finding translation

**Files:**
- Create: `extensions/secman_web_check/src/secman_web_check/tls.py`
- Create: `extensions/secman_web_check/tests/test_tls.py`

**Interfaces:**
- Consumes: `NormalizedTarget`, `Finding`, `Severity`.
- Produces: `TlsEvidence`, `TlsScanner.scan(target) -> tuple[TlsEvidence, tuple[Finding, ...]]`.
- Guarantees: SSLyze objects never leave this module; unsupported/incomplete scan commands are explicit errors, not clean results.

- [ ] **Step 1: Write adapter tests against small fake SSLyze result objects**

```python
def test_tls_10_support_is_high_severity(fake_tls_result):
    fake_tls_result.accepts_protocol("TLS_1_0")
    _, findings = TlsScanner(backend=fake_tls_result).scan(target("https://example.com"))
    assert ("WEB-TLS-PROTOCOL-1-0", Severity.HIGH) in {(f.rule_id, f.severity) for f in findings}

def test_unsupported_tls_command_marks_scan_partial(fake_tls_result):
    fake_tls_result.command_error("HEARTBLEED")
    evidence, _ = TlsScanner(backend=fake_tls_result).scan(target("https://example.com"))
    assert evidence.complete is False
```

Cover certificate trust/hostname/expiry, key size/signature algorithm, TLS 1.0/1.1, weak ciphers, renegotiation, compression, fallback, OCSP stapling, Heartbleed, ROBOT and CCS injection.

- [ ] **Step 2: Verify focused tests fail**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_tls.py -q`

Expected: FAIL because `TlsScanner` does not exist.

- [ ] **Step 3: Implement and pin the SSLyze adapter**

Use SSLyze's documented Python `Scanner`, `ServerScanRequest` and scan-command results. Request only the commands needed by the catalogue, enforce connection/network timeouts, and translate enums/objects immediately into the extension model. Update the dependency constraint and run `uv lock` so the tested SSLyze API is reproducible. Do not invoke an external executable.

- [ ] **Step 4: Run TLS tests and dependency checks**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_tls.py -q && uv run pytest -q && uv lock --check && uv run mypy src`

Expected: all commands exit 0.

- [ ] **Step 5: Commit in the extension repository**

```bash
git -C extensions/secman_web_check add pyproject.toml uv.lock src/secman_web_check/tls.py tests/test_tls.py
git -C extensions/secman_web_check commit -m "feat: analyze TLS configuration with SSLyze"
```

### Task 7: Scan orchestration, concurrency and CLI behavior

**Files:**
- Create: `extensions/secman_web_check/src/secman_web_check/{orchestrator,cli}.py`
- Modify: `extensions/secman_web_check/src/secman_web_check/__main__.py`
- Create: `extensions/secman_web_check/tests/{test_orchestrator,test_cli}.py`

**Interfaces:**
- Consumes: `ScannerConfig`, target APIs, `HttpCollector`, `TlsScanner`, passive and active check catalogues.
- Produces: `scan_target(target, config) -> TargetResult`, `scan_all(targets, config) -> ScanRun`, Typer commands `scan`, `db install`, `db status`, `history list`.
- Produces exit code policy: 0 clean/below threshold, 1 threshold met, 2 operational failure.

- [ ] **Step 1: Write orchestration and CLI tests**

```python
def test_batch_continues_after_one_target_fails(fake_components):
    run = scan_all((target("https://bad.example"), target("https://good.example")), fake_components.config)
    assert [result.status for result in run.targets] == [TargetStatus.FAILED, TargetStatus.SUCCESS]

def test_cli_rejects_missing_or_conflicting_target_sources(runner):
    assert runner.invoke(app, ["scan"]).exit_code == 2
    assert runner.invoke(app, ["scan", "https://example.com", "--targets-file", "targets.txt"]).exit_code == 2
```

Test threshold ordering, deterministic target ordering after concurrent work, `--active`, `--allow-private-targets`, repeated formats, database/upload opt-ins and sanitized errors.

- [ ] **Step 2: Verify focused tests fail**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_orchestrator.py tests/test_cli.py -q`

Expected: FAIL because orchestration and commands are absent.

- [ ] **Step 3: Implement bounded orchestration and CLI parsing**

Use a `ThreadPoolExecutor(max_workers=config.concurrency)` for independent target scans, keep per-target operations sequential, and sort final results back to input order. Create one UUIDv7-compatible or UUID4 run ID once per invocation. Mark incomplete TLS/HTTP/check stages as partial, preserve sanitized stage errors, and never allow an exception from one future to cancel others.

Typer callbacks construct `ScannerConfig` only after parsing, reject mutually exclusive target sources, and pass immutable configuration to the orchestrator. Stub database/report/upload adapters through protocols so Tasks 8-10 can connect them without changing scan logic.

- [ ] **Step 4: Run focused tests and accumulated quality gates**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_orchestrator.py tests/test_cli.py -q && uv run pytest -q && uv run ruff check src tests && uv run mypy src`

Expected: all commands exit 0.

- [ ] **Step 5: Commit in the extension repository**

```bash
git -C extensions/secman_web_check add src/secman_web_check/orchestrator.py src/secman_web_check/cli.py src/secman_web_check/__main__.py tests/test_orchestrator.py tests/test_cli.py
git -C extensions/secman_web_check commit -m "feat: orchestrate single and batch scans"
```

### Task 8: Terminal, JSON, SARIF and HTML reports

**Files:**
- Create: `extensions/secman_web_check/src/secman_web_check/reports/{__init__,terminal,json_report,sarif,html}.py`
- Create: `extensions/secman_web_check/tests/test_reports.py`
- Create: `extensions/secman_web_check/docs/REPORTS.md`
- Modify: `extensions/secman_web_check/src/secman_web_check/cli.py`
- Modify: `extensions/secman_web_check/tests/test_cli.py`

**Interfaces:**
- Consumes: `ScanRun` only.
- Produces: `render_terminal(run, console)`, `write_json(run, path)`, `write_sarif(run, path)`, `write_html(run, path)`.
- Produces: JSON schema marker `schemaVersion: "1.0"`; SARIF version `2.1.0`.

- [ ] **Step 1: Write cross-renderer and injection tests**

```python
def test_all_machine_reports_contain_same_external_id(sample_run, tmp_path):
    json_path, sarif_path, html_path = render_all(sample_run, tmp_path)
    assert sample_run.targets[0].findings[0].external_id in json_path.read_text()
    assert sample_run.targets[0].findings[0].external_id in sarif_path.read_text()
    assert sample_run.targets[0].findings[0].external_id in html_path.read_text()

def test_html_escapes_target_controlled_content(malicious_run, tmp_path):
    path = write_html(malicious_run, tmp_path / "report.html")
    assert "<script>" not in path.read_text(encoding="utf-8")
    assert "&lt;script&gt;" in path.read_text(encoding="utf-8")
```

- [ ] **Step 2: Verify focused tests fail**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_reports.py -q`

Expected: FAIL because report modules do not exist.

- [ ] **Step 3: Implement all renderers from the normalized model**

Use Rich for terminal tables; `json.dumps` with explicit dataclass conversion for JSON/SARIF; and `html.escape` for every dynamic HTML value. HTML contains inline CSS only and a restrictive CSP meta tag, no JavaScript, remote fonts or remote resources. Write through a same-directory temporary file followed by `Path.replace` for atomic output. Reject output paths that resolve outside the selected output directory. Wire repeatable `--format` and `--output-dir` CLI values to these adapters; terminal remains the default when no format is supplied.

- [ ] **Step 4: Run focused tests and inspect representative files**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_reports.py -q && uv run pytest -q && uv run secman-web-check --help`

Expected: tests pass and help lists all four formats.

- [ ] **Step 5: Commit in the extension repository**

```bash
git -C extensions/secman_web_check add src/secman_web_check/reports src/secman_web_check/cli.py tests/test_reports.py tests/test_cli.py docs/REPORTS.md
git -C extensions/secman_web_check commit -m "feat: render terminal JSON SARIF and HTML reports"
```

### Task 9: Optional MariaDB storage and SQL installation

**Files:**
- Create: `extensions/secman_web_check/src/secman_web_check/storage.py`
- Create: `extensions/secman_web_check/tests/test_storage.py`
- Create: `extensions/secman_web_check/db/migrations/001_initial.sql`
- Create: `extensions/secman_web_check/db/grants.example.sql`
- Create: `extensions/secman_web_check/scripts/install-db.sh`
- Create: `extensions/secman_web_check/docs/DATABASE.md`
- Modify: `extensions/secman_web_check/src/secman_web_check/cli.py`
- Modify: `extensions/secman_web_check/tests/test_cli.py`

**Interfaces:**
- Consumes: `ScanRun`, database settings from `ScannerConfig`.
- Produces: `MariaDbStore.install()`, `MariaDbStore.status() -> SchemaStatus`, `MariaDbStore.save(run)`, `MariaDbStore.list_runs(limit=50) -> tuple[StoredRunSummary, ...]`.
- Guarantees: parameterized queries; one transaction per target plus run summary; secrets excluded from errors.

- [ ] **Step 1: Write repository tests using a fake DB-API connection**

```python
def test_store_uses_parameters_for_target_controlled_values(fake_connection, sample_run):
    MariaDbStore(fake_connection).save(sample_run)
    sql, params = fake_connection.cursor.executions_for("scan_finding")[0]
    assert sample_run.targets[0].target.url not in sql
    assert sample_run.targets[0].target.url in params

def test_target_transaction_rolls_back_without_losing_report(fake_connection, sample_run):
    fake_connection.fail_on("INSERT INTO scan_finding")
    with pytest.raises(StorageError):
        MariaDbStore(fake_connection).save(sample_run)
    assert fake_connection.rollback_count == 1
```

- [ ] **Step 2: Verify focused tests fail**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_storage.py -q`

Expected: FAIL because storage is absent.

- [ ] **Step 3: Implement schema migration and repository**

`001_initial.sql` creates `schema_version`, `scan_run`, `scan_target` and `scan_finding` with `utf8mb4`, InnoDB, foreign keys, uniqueness on `(scan_target_id, external_id)`, and indexes from the spec. Apply migrations in filename order, store SHA-256 checksums of non-secret migration content, and refuse a checksum mismatch. Use PyMySQL with `autocommit=False`, `DictCursor`, TLS options from configuration and placeholders `%s` exclusively.

The shell installer must use `set -euo pipefail`, require `SECMAN_WEB_CHECK_DB_*` environment values, invoke `uv run secman-web-check db install`, and never echo a password. `grants.example.sql` uses obvious placeholders and grants only `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `CREATE`, `ALTER` and `INDEX` on the scanner database. Wire `db install`, `db status`, `history list` and scan-time `--store-db` to `MariaDbStore`; without the opt-in, constructing a scan must not import or connect through PyMySQL.

- [ ] **Step 4: Run focused tests and validate SQL syntax without applying it**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_storage.py -q && uv run pytest -q && sh -n scripts/install-db.sh`

Expected: all commands exit 0. Do not connect to any configured MariaDB during this task.

- [ ] **Step 5: Commit in the extension repository**

```bash
git -C extensions/secman_web_check add src/secman_web_check/storage.py src/secman_web_check/cli.py tests/test_storage.py tests/test_cli.py db scripts/install-db.sh docs/DATABASE.md
git -C extensions/secman_web_check commit -m "feat: persist scan history in MariaDB"
```

### Task 10: SecMan REST integration and shared contract fixture

**Files:**
- Create: `extensions/secman_web_check/src/secman_web_check/secman.py`
- Create: `extensions/secman_web_check/tests/test_secman.py`
- Create: `extensions/secman_web_check/tests/fixtures/integration-run-v1.json`
- Create: `extensions/secman_web_check/docs/SECMAN.md`
- Modify: `extensions/secman_web_check/src/secman_web_check/cli.py`
- Modify: `extensions/secman_web_check/src/secman_web_check/orchestrator.py`

**Interfaces:**
- Consumes: `TargetResult`, `ScannerConfig`, SecMan v1 subject/run DTOs.
- Produces: `SecManClient.login()`, `list_subjects(scanner_id)`, `match_subject(target, subjects)`, `build_run(target_result, scanner_id, subject_id)`, `submit_run(payload)`.
- Guarantees: explicit opt-in, URI-first/hostname-second unambiguous matching, no legacy fallback, idempotent run key, no resolution on incomplete results.

- [ ] **Step 1: Write REST and payload tests**

```python
def test_ambiguous_hostname_match_fails():
    subjects = [subject(1, "https://example.com/a"), subject(2, "https://example.com/b")]
    with pytest.raises(AmbiguousSubject):
        match_subject(target("https://example.com/"), subjects)

def test_partial_scan_never_claims_complete_coverage(partial_target):
    payload = build_run(partial_target, scanner_id=7, subject_id=9)
    assert payload["status"] == "PARTIAL"
    assert payload["completeCoverage"] is False
```

Test cookie/bearer handling without exposing tokens, pagination capped at server page size 100, zero-finding success, evidence bounds, replay response, 4xx rejection without legacy calls, URL TLS verification and sanitized exceptions. Compare the generated representative payload deeply with `tests/fixtures/integration-run-v1.json`.

- [ ] **Step 2: Verify focused tests fail**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_secman.py -q`

Expected: FAIL because `SecManClient` does not exist.

- [ ] **Step 3: Implement the v1 REST client**

Authenticate with `POST /api/auth/login`, obtain the current secure session cookie, call `GET /api/integrations/v1/scanners/{id}/subjects?page=N&size=100`, and submit `POST /api/integrations/v1/runs`. Use the normalized target URI for exact matching before canonical hostname matching. Bound textual evidence to the backend limits and reject more than 500 findings rather than truncating silently.

The run key must be stable across a retry of the same local target result, for example `web-security:{local_run_id}:{target_external_key}`. Map finding fields exactly to the version-1 contract and include `metadataJson` with scanner version, mode and completeness reasons. Never send report files as attachments in the first release. Wire `--push-to-secman` after report generation and optional storage; upload every matched target independently, retain all acknowledgements/errors in the final run summary, and leave the client unconstructed when upload is not requested.

- [ ] **Step 4: Run contract and accumulated tests**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_secman.py -q && uv run pytest -q && uv run ruff check src tests && uv run mypy src`

Expected: all commands exit 0.

- [ ] **Step 5: Commit in the extension repository**

```bash
git -C extensions/secman_web_check add src/secman_web_check/secman.py src/secman_web_check/cli.py src/secman_web_check/orchestrator.py tests/test_secman.py tests/fixtures/integration-run-v1.json docs/SECMAN.md
git -C extensions/secman_web_check commit -m "feat: submit web findings to SecMan"
```

### Task 11: Operational scripts, examples and complete extension documentation

**Files:**
- Create: `extensions/secman_web_check/scripts/{setup,scan-single,scan-list,verify}.sh`
- Create: `extensions/secman_web_check/examples/{config.toml,targets.txt}`
- Create: `extensions/secman_web_check/docs/{CHECKS,CONFIGURATION,SAFETY}.md`
- Modify: `extensions/secman_web_check/README.md`

**Interfaces:**
- Consumes: completed CLI and configuration variables.
- Produces: documented install, safe scan, batch scan, all-format reporting, MariaDB and SecMan workflows.

- [ ] **Step 1: Write script smoke tests in `tests/test_cli.py`**

```python
def test_help_documents_safety_opt_ins(runner):
    result = runner.invoke(app, ["scan", "--help"])
    assert "--active" in result.output
    assert "--allow-private-targets" in result.output
    assert "--push-to-secman" in result.output
    assert "--store-db" in result.output
```

Add a subprocess test that runs each script with `--help` or a no-network validation mode and asserts no secret environment value appears in stdout/stderr.

- [ ] **Step 2: Verify the documentation/script test fails**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_cli.py -q`

Expected: FAIL until the final help text and scripts exist.

- [ ] **Step 3: Add scripts, examples and documentation**

All scripts use `#!/usr/bin/env bash`, `set -euo pipefail`, calculate the repository directory without assuming the current directory, and execute `uv sync --locked` or `uv run --locked secman-web-check`. `scan-single.sh` requires exactly one target; `scan-list.sh` requires exactly one readable file; both forward additional options after `--`.

README sections must include purpose, authorization warning, features, prerequisites, uv installation, five-minute quick start, single/batch examples, four outputs, active/private flags, configuration precedence, MariaDB opt-in, SecMan registration as `WEB_SECURITY`, exit codes, links to all detailed docs, testing and limitations. `CHECKS.md` lists every rule ID with severity/confidence/rationale/remediation; `SAFETY.md` lists every active request and prohibited behavior.

- [ ] **Step 4: Run the extension verification script**

Run: `cd extensions/secman_web_check && ./scripts/verify.sh`

Expected: `uv lock --check`, pytest, Ruff, mypy and package build all exit 0; the script prints a concise pass summary.

- [ ] **Step 5: Commit in the extension repository**

```bash
git -C extensions/secman_web_check add README.md scripts examples docs tests/test_cli.py
git -C extensions/secman_web_check commit -m "docs: add scanner operations and examples"
```

### Task 12: Add the `WEB_SECURITY` source to the parent SecMan application

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/service/IntegrationRunValidator.kt`
- Modify: `src/backendng/src/test/kotlin/com/secman/service/IntegrationRunValidatorTest.kt`
- Create: `src/frontend/src/services/integrationSources.ts`
- Create: `src/frontend/src/services/integrationSources.test.ts`
- Modify: `src/frontend/src/components/IntegrationScannerConfig.tsx`
- Modify: `src/frontend/src/components/IntegrationFindingList.tsx`

**Interfaces:**
- Produces backend source allowlist: `GITHUB_AI`, `VISUAL`, `WEB_SECURITY`.
- Produces frontend `INTEGRATION_SOURCES` and `integrationSourceLabel(value)` used by both components.
- Consumes no scanner internals; version-1 payload shape is unchanged.

- [ ] **Step 1: Add failing backend and frontend tests**

```kotlin
@Test
fun `web security is accepted and unknown scanner sources remain rejected`() {
    assertThat(IntegrationRunValidator.SOURCES).contains("WEB_SECURITY")
    assertThat(IntegrationRunValidator.SOURCES).doesNotContain("UNKNOWN")
}
```

```typescript
test('integration source catalogue includes web security without changing existing values', () => {
  assert.deepEqual(INTEGRATION_SOURCES.map(source => source.value), ['VISUAL', 'GITHUB_AI', 'WEB_SECURITY']);
  assert.equal(integrationSourceLabel('WEB_SECURITY'), 'Web security');
});
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew :backendng:test --tests '*IntegrationRunValidatorTest*'`

Run: `cd src/frontend && npm test -- --test-name-pattern='integration source catalogue'`

Expected: FAIL because `WEB_SECURITY` and the frontend catalogue are absent.

- [ ] **Step 3: Add the source surgically**

Change `IntegrationRunValidator.SOURCES` to `setOf("GITHUB_AI", "VISUAL", "WEB_SECURITY")`. Create the typed frontend catalogue and map it into both source `<select>` elements. Keep `GITHUB_AI` repository-specific subject behavior; `WEB_SECURITY` uses the existing asset-hostname path.

- [ ] **Step 4: Run focused backend/frontend tests and builds**

Run: `./gradlew :backendng:test --tests '*IntegrationRunValidatorTest*'`

Run: `cd src/frontend && npm test && npm run lint && npm run build`

Expected: all commands exit 0.

- [ ] **Step 5: Commit in the parent repository**

```bash
git add src/backendng/src/main/kotlin/com/secman/service/IntegrationRunValidator.kt src/backendng/src/test/kotlin/com/secman/service/IntegrationRunValidatorTest.kt src/frontend/src/components/IntegrationScannerConfig.tsx src/frontend/src/components/IntegrationFindingList.tsx src/frontend/src/services/integrationSources.ts src/frontend/src/services/integrationSources.test.ts
git commit -m "feat(integrations): add web security scanner source"
```

### Task 13: Update central extension and contract documentation

**Files:**
- Modify: `docs/EXTENSIONS.md`
- Modify: `docs/INTEGRATION_RESULTS.md`
- Modify: `docs/contracts/integration-run-v1.json`

**Interfaces:**
- Consumes: extension commands and `WEB_SECURITY` parent behavior.
- Produces: operator instructions for registration, subject binding and explicit upload.

- [ ] **Step 1: Add a contract-fixture parity test in the extension**

```python
def test_secman_contract_fixture_matches_parent_copy():
    extension = json.loads(Path("tests/fixtures/integration-run-v1.json").read_text())
    parent = json.loads(Path("../../docs/contracts/integration-run-v1.json").read_text())
    assert extension.keys() == parent.keys()
    assert extension["findings"][0].keys() == parent["findings"][0].keys()
```

- [ ] **Step 2: Run the parity test and verify it detects drift if present**

Run: `cd extensions/secman_web_check && uv run pytest tests/test_secman.py::test_secman_contract_fixture_matches_parent_copy -q`

Expected: PASS only when both fixtures carry the same version-1 fields.

- [ ] **Step 3: Document the new extension and source**

Add `secman_web_check` to the current-extension table with repository URL and `WEB_SECURITY`. Document admin registration, dedicated service user, asset subject binding, URI matching, incomplete-run safety, explicit `--push-to-secman`, and the fact that database persistence is independent from central upload. Update the example fixture metadata/engine to identify web security without changing its field shape.

- [ ] **Step 4: Run documentation and contract checks**

Run: `git diff --check && cd extensions/secman_web_check && uv run pytest tests/test_secman.py -q`

Expected: exit 0 and fixture parity passes.

- [ ] **Step 5: Commit both repositories separately**

```bash
git -C extensions/secman_web_check add tests/test_secman.py tests/fixtures/integration-run-v1.json
git -C extensions/secman_web_check commit -m "test: verify SecMan contract fixture parity"
git add docs/EXTENSIONS.md docs/INTEGRATION_RESULTS.md docs/contracts/integration-run-v1.json
git commit -m "docs(integrations): document web security scanner"
```

### Task 14: Full security review and completion gates

**Files:**
- Modify only files required to fix findings from these gates.

**Interfaces:**
- Consumes: all completed extension and parent changes.
- Produces: current verification evidence and an A01-A10 result for the final diff.

- [ ] **Step 1: Run the extension's complete local gate**

Run: `cd extensions/secman_web_check && ./scripts/verify.sh`

Expected: all pytest, Ruff, mypy, lock and build checks exit 0.

- [ ] **Step 2: Run parent static and build gates**

Run: `./gradlew build`

Run: `cd src/frontend && npm ci && npm run build && npm run lint`

Run: `./scripts/owasp-check.sh`

Expected: exit 0. Review the complete extension diff manually against A01-A10 because the parent diff scanner ignores nested repositories.

- [ ] **Step 3: Perform cold-start verification using the mandatory stack lifecycle**

Read `.agents/skills/_shared/stack-lifecycle.md` in full, stop both services unconditionally, then start through `./scripts/startbackenddev.sh` and `./scripts/startfrontenddev.sh` with escalated permissions because `pass-cli` must resolve secrets. Verify port-bind liveness and inspect the documented logs. Stop both services after validation.

Expected: backend and frontend reach their defined liveness checks with no new ERROR stack traces.

- [ ] **Step 4: Run required E2E skills only on a confirmed disposable stack**

Resolve `SECMAN_HOST` through `pass-cli` and prove the target is disposable before continuing. Then read and follow `.agents/skills/e2ejs/SKILL.md` and `.agents/skills/e2evulnexception/SKILL.md` in full.

Expected: `e2ejs` reports zero JavaScript errors for admin and normal user; `e2evulnexception` reports zero failures and completes cleanup. If the resolved host is not confirmed disposable, stop and report this gate as blocked rather than running it.

- [ ] **Step 5: Check repository state and record final security result**

Run: `git status --short && git -C extensions/secman_web_check status --short && git log -5 --oneline && git -C extensions/secman_web_check log -12 --oneline`

Expected: only intentional changes remain. Record A01-A10 as PASS/REVIEW with concrete controls and fix every HIGH-or-above issue before claiming completion.

- [ ] **Step 6: Commit any gate-driven fixes in their owning repository**

Use a narrow Conventional Commit message that names the actual fix. Do not combine parent and nested extension files in one commit, and do not commit unrelated user changes.
