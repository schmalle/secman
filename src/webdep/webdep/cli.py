"""Command line entry point for SecMan web dependency discovery."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import getpass
import json
import sys
from typing import Any
from urllib.error import HTTPError, URLError
import ssl
from urllib.request import HTTPSHandler, Request, build_opener

from .discovery import WebDependency, discover_dependencies, normalize_uris
from .secman import SecManClient, SecManClientError
from .validation import validate_packages
from .vulnerabilities import (
    WebComponent,
    components_from_headers,
    fetch_exposed_composer_components,
    find_vulnerabilities,
)


@dataclass(frozen=True)
class ScanTarget:
    """A root web URI tied to a SecMan asset name."""

    asset_name: str
    uri: str


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="secman-webdep",
        description="Discover CSS/JavaScript dependencies from SecMan asset URIs and import them as installed products.",
    )
    parser.add_argument(
        "--backend-url",
        default="https://secman.schmall.io",
        help="SecMan backend base URL (default: %(default)s).",
    )
    parser.add_argument(
        "--username",
        required=True,
        help="SecMan username with permission to list assets and import installed products.",
    )
    parser.add_argument("--password", help="SecMan password. If omitted, a hidden prompt is shown.")
    parser.add_argument("--uri-file", help="Optional file containing one URI per line. Lines beginning with # are ignored.")
    parser.add_argument("--dry-run", action="store_true", help="Scan and call the SecMan import endpoint in dry-run mode.")
    parser.add_argument(
        "--insecure",
        action="store_true",
        help="Allow self-signed or otherwise untrusted TLS certificates for SecMan and scanned HTTPS targets.",
    )
    parser.add_argument("--timeout", type=float, default=30.0, help="HTTP timeout in seconds (default: %(default)s).")
    parser.add_argument(
        "--max-products-per-request",
        type=int,
        default=1000,
        help="Batch size for SecMan installed-product imports (default: %(default)s).",
    )
    parser.add_argument(
        "--validate",
        action="store_true",
        help="Validate each discovered dependency against the public npm registry "
        "(registry.npmjs.org): confirm the package exists and report the latest known version. "
        "Off by default; makes outbound HTTPS calls only when set.",
    )
    parser.add_argument(
        "--vulnerability-scan",
        action="store_true",
        help="Look up discovered JavaScript libraries, exposed PHP Composer packages, PHP runtimes, "
        "and web server banners in NVD and store matching CVEs in SecMan. Off by default.",
    )
    parser.add_argument(
        "--nvd-base-url",
        default="https://services.nvd.nist.gov/rest/json/cves/2.0",
        help="NVD CVE API URL used by --vulnerability-scan (default: %(default)s).",
    )
    parser.add_argument(
        "--max-vulns-per-component",
        type=int,
        default=10,
        help="Maximum NVD findings imported for one detected component (default: %(default)s).",
    )
    parser.add_argument("--json", action="store_true", help="Print a machine-readable JSON summary instead of human-readable output.")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    password = args.password or getpass.getpass("SecMan password: ")
    verify_tls = not args.insecure

    client = SecManClient(args.backend_url, verify_tls=verify_tls, timeout=args.timeout)
    try:
        client.login(args.username, password)
        targets = load_targets(client, args.uri_file)
        products, failures, components_by_asset = scan_targets(targets, verify_tls=verify_tls, timeout=args.timeout)
        validation = validate_products(products, verify_tls=verify_tls, timeout=args.timeout) if args.validate else None
        vulnerability_findings = []
        vulnerability_import = []
        if args.vulnerability_scan:
            vulnerability_findings = scan_vulnerabilities(
                components_by_asset,
                verify_tls=verify_tls,
                timeout=args.timeout,
                nvd_base_url=args.nvd_base_url,
                max_per_component=args.max_vulns_per_component,
            )
            vulnerability_import = import_vulnerabilities(client, vulnerability_findings, dry_run=args.dry_run)
        import_response = import_in_batches(
            client,
            products,
            dry_run=args.dry_run,
            batch_size=args.max_products_per_request,
        )
    except (OSError, SecManClientError, ValueError) as exc:
        print(f"secman-webdep: {exc}", file=sys.stderr)
        return 2

    summary = {
        "targets": len(targets),
        "productsDiscovered": len(products),
        "failures": failures,
        "dryRun": args.dry_run,
        "import": import_response,
        "componentsIdentified": sum(len(v) for v in components_by_asset.values()),
        "vulnerabilitiesDiscovered": len(vulnerability_findings),
        "vulnerabilityImport": vulnerability_import,
    }
    if validation is not None:
        summary["validation"] = validation
    if args.json:
        print(json.dumps(summary, indent=2, sort_keys=True))
    else:
        print_human_summary(summary)
    return 0 if not failures else 1


def load_targets(client: SecManClient, uri_file: str | None) -> list[ScanTarget]:
    if uri_file:
        with open(uri_file, encoding="utf-8") as handle:
            uris = normalize_uris(handle)
        return [ScanTarget(asset_name=_asset_name_from_uri(uri), uri=uri) for uri in uris]

    assets = client.assets()
    targets: list[ScanTarget] = []
    for asset in assets:
        for uri in normalize_uris([asset.uri or ""]):
            targets.append(ScanTarget(asset_name=asset.name, uri=uri))
    return targets


def build_http_opener(verify_tls: bool) -> Any:
    context = ssl.create_default_context() if verify_tls else ssl._create_unverified_context()
    return build_opener(HTTPSHandler(context=context))


def scan_targets(targets: list[ScanTarget], *, verify_tls: bool, timeout: float) -> tuple[list[dict[str, Any]], list[dict[str, str]], dict[str, list[WebComponent]]]:
    opener = build_http_opener(verify_tls)
    products: list[dict[str, Any]] = []
    failures: list[dict[str, str]] = []
    seen_external_ids: set[str] = set()
    components_by_asset: dict[str, list[WebComponent]] = {}

    for target in targets:
        try:
            html, headers = fetch_root_html(opener, target.uri, timeout=timeout)
            dependencies = discover_dependencies(html, target.uri)
            components = [WebComponent.from_dependency(dependency) for dependency in dependencies]
            components.extend(components_from_headers(headers))
            components.extend(fetch_exposed_composer_components(opener, target.uri, timeout=timeout))
            components_by_asset[target.asset_name] = _dedupe_components(components_by_asset.get(target.asset_name, []) + components)
            for dependency in dependencies:
                product = to_installed_product(target, dependency)
                if product["externalId"] in seen_external_ids:
                    continue
                seen_external_ids.add(product["externalId"])
                products.append(product)
        except (HTTPError, URLError, TimeoutError, OSError, UnicodeDecodeError, ValueError) as exc:
            failures.append({"uri": target.uri, "assetName": target.asset_name, "error": str(exc)})
    return products, failures, components_by_asset


def fetch_root_html(opener: Any, uri: str, *, timeout: float) -> tuple[str, Any]:
    request = Request(uri, method="GET", headers={"Accept": "text/html,application/xhtml+xml"})
    with opener.open(request, timeout=timeout) as response:
        content_type = response.headers.get("Content-Type", "")
        if "html" not in content_type.lower() and content_type:
            raise ValueError(f"{uri} did not return HTML (Content-Type: {content_type})")
        charset = response.headers.get_content_charset() or "utf-8"
        return response.read().decode(charset, errors="replace"), response.headers


def to_installed_product(target: ScanTarget, dependency: WebDependency) -> dict[str, Any]:
    return {
        "externalId": dependency.external_id(target.asset_name),
        "hostname": target.asset_name,
        "name": dependency.name,
        "vendor": dependency.vendor,
        "version": dependency.version,
        "category": dependency.category,
        "installationPath": dependency.url,
    }


def validate_products(products: list[dict[str, Any]], *, verify_tls: bool, timeout: float) -> dict[str, Any]:
    """Validate discovered products against the npm registry and roll up the results."""

    opener = build_http_opener(verify_tls)
    pairs = [(product["name"], product.get("version")) for product in products]
    results = validate_packages(opener, pairs, timeout=timeout)

    findings = [results[(name, version)].as_dict() for name, version in dict.fromkeys(pairs)]
    confirmed = sum(1 for finding in findings if finding["name_found"])
    unknown = sum(1 for finding in findings if not finding["name_found"])
    version_not_found = sum(1 for finding in findings if finding["version_found"] is False)
    return {
        "source": "npm",
        "confirmed": confirmed,
        "unknownPackages": unknown,
        "versionNotFound": version_not_found,
        "findings": findings,
    }


def scan_vulnerabilities(
    components_by_asset: dict[str, list[WebComponent]],
    *,
    verify_tls: bool,
    timeout: float,
    nvd_base_url: str,
    max_per_component: int,
) -> list[dict[str, Any]]:
    if max_per_component < 1:
        raise ValueError("--max-vulns-per-component must be at least 1")
    opener = build_http_opener(verify_tls)
    findings = []
    for asset_name, components in components_by_asset.items():
        findings.extend(
            finding.as_dict()
            for finding in find_vulnerabilities(
                opener,
                asset_name,
                components,
                timeout=timeout,
                nvd_base_url=nvd_base_url,
                max_per_component=max_per_component,
            )
        )
    return findings


def import_vulnerabilities(client: SecManClient, findings: list[dict[str, Any]], *, dry_run: bool) -> list[dict[str, Any]]:
    responses: list[dict[str, Any]] = []
    for finding in findings:
        if dry_run:
            responses.append({"dryRun": True, "hostname": finding["asset_name"], "cve": finding["cve"], "criticality": finding["severity"]})
            continue
        responses.append(
            client.add_vulnerability(
                hostname=finding["asset_name"],
                cve=finding["cve"],
                criticality=finding["severity"],
                owner="WEBDEP-IMPORT",
            )
        )
    return responses


def _dedupe_components(components: list[WebComponent]) -> list[WebComponent]:
    seen: set[tuple[str, str | None, str]] = set()
    result: list[WebComponent] = []
    for component in components:
        key = (component.name.lower(), component.version, component.category)
        if key not in seen:
            seen.add(key)
            result.append(component)
    return result


def import_in_batches(client: SecManClient, products: list[dict[str, Any]], *, dry_run: bool, batch_size: int) -> list[dict[str, Any]]:
    if batch_size < 1:
        raise ValueError("--max-products-per-request must be at least 1")
    responses: list[dict[str, Any]] = []
    for start in range(0, len(products), batch_size):
        responses.append(client.import_products(products[start : start + batch_size], dry_run=dry_run))
    if not products:
        responses.append(
            {
                "productsProcessed": 0,
                "productsImported": 0,
                "productsUpdated": 0,
                "productsSkipped": 0,
                "unknownSystems": 0,
                "dryRun": dry_run,
                "errors": [],
            }
        )
    return responses


def print_human_summary(summary: dict[str, Any]) -> None:
    print(f"Targets scanned: {summary['targets']}")
    print(f"Products discovered: {summary['productsDiscovered']}")
    print(f"Components identified for vulnerability matching: {summary['componentsIdentified']}")
    print(f"Vulnerabilities discovered: {summary['vulnerabilitiesDiscovered']}")
    print(f"Dry run: {summary['dryRun']}")
    validation = summary.get("validation")
    if validation is not None:
        print(
            f"npm validation: {validation['confirmed']} confirmed, "
            f"{validation['unknownPackages']} unknown, "
            f"{validation['versionNotFound']} version-not-found"
        )
        for finding in validation["findings"]:
            if not finding["name_found"]:
                detail = f"error: {finding['error']}" if finding["error"] else "not found on npm"
                print(f"  - unverified: {finding['name']} ({detail})")
            elif finding["version_found"] is False:
                print(
                    f"  - version mismatch: {finding['name']} {finding['version']} "
                    f"not on npm (latest {finding['latest_version']})"
                )
    print("Import responses:")
    for response in summary["import"]:
        print(f"  - {response}")
    if summary["failures"]:
        print("Failures:", file=sys.stderr)
        for failure in summary["failures"]:
            print(f"  - {failure['assetName']} {failure['uri']}: {failure['error']}", file=sys.stderr)


def _asset_name_from_uri(uri: str) -> str:
    from urllib.parse import urlparse

    parsed = urlparse(uri)
    return parsed.hostname or uri


if __name__ == "__main__":
    raise SystemExit(main())
