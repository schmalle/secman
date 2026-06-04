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
        default="https://secman.covestro.net",
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
        products, failures = scan_targets(targets, verify_tls=verify_tls, timeout=args.timeout)
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
    }
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


def scan_targets(targets: list[ScanTarget], *, verify_tls: bool, timeout: float) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    context = ssl.create_default_context() if verify_tls else ssl._create_unverified_context()
    opener = build_opener(HTTPSHandler(context=context))
    products: list[dict[str, Any]] = []
    failures: list[dict[str, str]] = []
    seen_external_ids: set[str] = set()

    for target in targets:
        try:
            html = fetch_root_html(opener, target.uri, timeout=timeout)
            dependencies = discover_dependencies(html, target.uri)
            for dependency in dependencies:
                product = to_installed_product(target, dependency)
                if product["externalId"] in seen_external_ids:
                    continue
                seen_external_ids.add(product["externalId"])
                products.append(product)
        except (HTTPError, URLError, TimeoutError, OSError, UnicodeDecodeError, ValueError) as exc:
            failures.append({"uri": target.uri, "assetName": target.asset_name, "error": str(exc)})
    return products, failures


def fetch_root_html(opener: Any, uri: str, *, timeout: float) -> str:
    request = Request(uri, method="GET", headers={"Accept": "text/html,application/xhtml+xml"})
    with opener.open(request, timeout=timeout) as response:
        content_type = response.headers.get("Content-Type", "")
        if "html" not in content_type.lower() and content_type:
            raise ValueError(f"{uri} did not return HTML (Content-Type: {content_type})")
        charset = response.headers.get_content_charset() or "utf-8"
        return response.read().decode(charset, errors="replace")


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
    print(f"Dry run: {summary['dryRun']}")
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
