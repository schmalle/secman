"""Best-effort vulnerability identification for web dependencies.

The module deliberately stays on the Python standard library. It fingerprints
JavaScript packages, exposed Composer/PHP packages, PHP runtimes, and HTTP
server banners, then looks them up in NVD's CVE 2.0 API when enabled.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import json
import re
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urljoin
from urllib.request import Request

from .discovery import WebDependency

NVD_CVE_API_URL = "https://services.nvd.nist.gov/rest/json/cves/2.0"
_SEVERITY_ORDER = {"LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}
_SERVER_TOKEN_RE = re.compile(r"(?P<name>[A-Za-z][A-Za-z0-9_.+-]*)(?:/(?P<version>\d+(?:\.\d+){0,3}[0-9A-Za-z.+-]*))?")
_PHP_RE = re.compile(r"\bPHP/(?P<version>\d+(?:\.\d+){1,3}[0-9A-Za-z.+-]*)", re.I)


@dataclass(frozen=True)
class WebComponent:
    """A versioned component that may have CVEs."""

    name: str
    version: str | None
    category: str
    evidence: str
    vendor: str | None = None

    @classmethod
    def from_dependency(cls, dependency: WebDependency) -> "WebComponent":
        return cls(
            name=dependency.name,
            version=dependency.version,
            category=dependency.category,
            vendor=dependency.vendor,
            evidence=dependency.url,
        )


@dataclass(frozen=True)
class VulnerabilityFinding:
    """One CVE found for one component on one SecMan asset."""

    asset_name: str
    component: WebComponent
    cve: str
    severity: str
    description: str | None = None
    source_url: str | None = None

    def as_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["component"] = asdict(self.component)
        return data


def components_from_headers(headers: Any) -> list[WebComponent]:
    """Fingerprint web servers and PHP runtimes from HTTP response headers."""

    components: list[WebComponent] = []
    server = _header_value(headers, "Server")
    if server:
        for token in _SERVER_TOKEN_RE.finditer(server):
            name = token.group("name")
            if name.lower() in {"", "http", "https"}:
                continue
            components.append(
                WebComponent(
                    name=_normalize_server_name(name),
                    version=token.group("version"),
                    category="Web server",
                    evidence=f"Server: {server}",
                )
            )
    powered_by = _header_value(headers, "X-Powered-By")
    if powered_by:
        match = _PHP_RE.search(powered_by)
        if match:
            components.append(
                WebComponent(
                    name="PHP",
                    version=match.group("version"),
                    category="PHP runtime",
                    evidence=f"X-Powered-By: {powered_by}",
                )
            )
    return _dedupe_components(components)


def components_from_composer_lock(lock_json: str, evidence_url: str) -> list[WebComponent]:
    """Parse an exposed composer.lock file into PHP library components."""

    try:
        data = json.loads(lock_json)
    except json.JSONDecodeError:
        return []

    components: list[WebComponent] = []
    for section in ("packages", "packages-dev"):
        packages = data.get(section) or []
        if not isinstance(packages, list):
            continue
        for package in packages:
            if not isinstance(package, dict):
                continue
            name = package.get("name")
            if not isinstance(name, str) or not name.strip():
                continue
            version = package.get("version")
            components.append(
                WebComponent(
                    name=name.strip(),
                    version=_clean_php_version(version) if isinstance(version, str) else None,
                    category="PHP library",
                    evidence=evidence_url,
                    vendor=name.split("/", 1)[0] if "/" in name else None,
                )
            )
    return _dedupe_components(components)


def fetch_exposed_composer_components(opener: Any, root_uri: str, *, timeout: float) -> list[WebComponent]:
    """Fetch ``/composer.lock`` if it is publicly exposed; return no components otherwise."""

    url = urljoin(root_uri, "/composer.lock")
    request = Request(url, method="GET", headers={"Accept": "application/json,text/plain,*/*"})
    try:
        with opener.open(request, timeout=timeout) as response:
            raw = response.read(2_000_000)
            return components_from_composer_lock(raw.decode("utf-8", errors="replace"), url)
    except (HTTPError, URLError, TimeoutError, OSError, UnicodeDecodeError):
        return []


def find_vulnerabilities(
    opener: Any,
    asset_name: str,
    components: Iterable[WebComponent],
    *,
    timeout: float,
    nvd_base_url: str = NVD_CVE_API_URL,
    max_per_component: int = 10,
) -> list[VulnerabilityFinding]:
    """Look up component CVEs in NVD and return findings suitable for SecMan."""

    findings: list[VulnerabilityFinding] = []
    seen: set[tuple[str, str]] = set()
    for component in components:
        if not component.version:
            continue
        query = f"{component.name} {component.version}"
        url = f"{nvd_base_url}?keywordSearch={quote(query)}&noRejected"
        request = Request(url, method="GET", headers={"Accept": "application/json"})
        try:
            with opener.open(request, timeout=timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except (HTTPError, URLError, TimeoutError, OSError, UnicodeDecodeError, json.JSONDecodeError):
            continue

        count = 0
        for item in payload.get("vulnerabilities") or []:
            cve = (item.get("cve") or {}) if isinstance(item, dict) else {}
            cve_id = cve.get("id")
            if not isinstance(cve_id, str) or (asset_name, cve_id) in seen:
                continue
            severity = _severity(cve)
            descriptions = cve.get("descriptions") or []
            description = next((d.get("value") for d in descriptions if d.get("lang") == "en"), None)
            references = cve.get("references") or []
            source_url = next((r.get("url") for r in references if isinstance(r, dict) and r.get("url")), url)
            findings.append(VulnerabilityFinding(asset_name, component, cve_id, severity, description, source_url))
            seen.add((asset_name, cve_id))
            count += 1
            if count >= max_per_component:
                break
    return findings


def _severity(cve: dict[str, Any]) -> str:
    severities: list[str] = []
    for metrics in (cve.get("metrics") or {}).values():
        for metric in metrics or []:
            severity = ((metric.get("cvssData") or {}).get("baseSeverity") or metric.get("baseSeverity"))
            if isinstance(severity, str):
                severities.append(severity.upper())
    return max(severities, key=lambda s: _SEVERITY_ORDER.get(s, 0), default="LOW")


def _header_value(headers: Any, name: str) -> str | None:
    getter = getattr(headers, "get", None)
    value = getter(name) if getter else None
    return str(value).strip() if value else None


def _normalize_server_name(name: str) -> str:
    return {"nginx": "nginx", "apache": "Apache HTTP Server", "httpd": "Apache HTTP Server"}.get(name.lower(), name)


def _clean_php_version(version: str) -> str:
    return version.removeprefix("v").lstrip("=").strip()


def _dedupe_components(components: Iterable[WebComponent]) -> list[WebComponent]:
    seen: set[tuple[str, str | None, str]] = set()
    result: list[WebComponent] = []
    for component in components:
        key = (component.name.lower(), component.version, component.category)
        if key not in seen:
            seen.add(key)
            result.append(component)
    return result
