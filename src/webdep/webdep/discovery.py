"""HTML dependency discovery for SecMan web assets.

The functions in this module intentionally use only the Python standard library
so operators can run the helper from an administration workstation without
installing additional runtime dependencies.
"""

from __future__ import annotations

from dataclasses import dataclass
from html.parser import HTMLParser
import hashlib
import posixpath
import re
from typing import Iterable
from urllib.parse import parse_qs, unquote, urljoin, urlparse

_LIBRARY_ALIASES = {
    "bootstrap.bundle": "bootstrap",
    "bootstrap.min": "bootstrap",
    "jquery.min": "jquery",
    "react-dom": "react-dom",
    "vue.global": "vue",
}

_VENDOR_HINTS = {
    "ajax.googleapis.com": "Google Hosted Libraries",
    "cdnjs.cloudflare.com": "cdnjs",
    "cdn.jsdelivr.net": "jsDelivr",
    "unpkg.com": "unpkg",
    "code.jquery.com": "jQuery CDN",
    "stackpath.bootstrapcdn.com": "BootstrapCDN",
    "maxcdn.bootstrapcdn.com": "BootstrapCDN",
    "fonts.googleapis.com": "Google Fonts",
}

_VERSION_RE = re.compile(r"(?<![a-zA-Z])v?(\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.-]+)?)(?![a-zA-Z])")
_NAME_VERSION_RE = re.compile(
    r"^(?P<name>[A-Za-z][A-Za-z0-9_.@/-]*?)[-_.@]v?(?P<version>\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.-]+)?)"
)


@dataclass(frozen=True)
class WebDependency:
    """A CSS or JavaScript dependency found on a web page."""

    name: str
    url: str
    category: str
    vendor: str | None = None
    version: str | None = None

    def external_id(self, asset_name: str) -> str:
        """Return a stable SecMan external id for this asset/dependency pair."""

        digest = hashlib.sha256(f"{asset_name}\0{self.category}\0{self.name}\0{self.url}".encode()).hexdigest()
        return f"webdep:{digest[:32]}"


class DependencyParser(HTMLParser):
    """Collect dependency-bearing HTML tags from a document."""

    def __init__(self, base_url: str) -> None:
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.urls: list[tuple[str, str]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = {key.lower(): value for key, value in attrs if key}
        if tag.lower() == "script":
            src = values.get("src")
            if src:
                self.urls.append(("JavaScript library", urljoin(self.base_url, src)))
            return

        if tag.lower() == "link":
            href = values.get("href")
            rel = {part.lower() for part in (values.get("rel") or "").split()}
            as_value = (values.get("as") or "").lower()
            if href and ("stylesheet" in rel or as_value in {"style", "script"}):
                category = "CSS library" if as_value != "script" else "JavaScript library"
                self.urls.append((category, urljoin(self.base_url, href)))


def discover_dependencies(html: str, base_url: str) -> list[WebDependency]:
    """Extract unique CSS and JavaScript dependencies from *html*.

    Dependencies are deduplicated by absolute URL and category while preserving
    discovery order.
    """

    parser = DependencyParser(base_url)
    parser.feed(html)

    seen: set[tuple[str, str]] = set()
    dependencies: list[WebDependency] = []
    for category, dependency_url in parser.urls:
        key = (category, dependency_url)
        if key in seen:
            continue
        seen.add(key)
        dependencies.append(describe_dependency(dependency_url, category))
    return dependencies


def describe_dependency(dependency_url: str, category: str) -> WebDependency:
    """Derive a product-like name, vendor, and version from a dependency URL."""

    parsed = urlparse(dependency_url)
    host = parsed.netloc.lower()
    path_parts = [unquote(part) for part in parsed.path.split("/") if part]
    filename = path_parts[-1] if path_parts else host or dependency_url
    stem = _strip_asset_suffix(filename)

    name = _name_from_cdn_path(host, path_parts) or _name_from_filename(stem) or stem or filename
    version = _version_from_path(path_parts) or _version_from_query(parsed.query)
    vendor = _vendor_from_host(host)

    return WebDependency(
        name=name[:512],
        vendor=vendor[:255] if vendor else None,
        version=version[:255] if version else None,
        category=category,
        url=dependency_url,
    )


def normalize_uris(values: Iterable[str]) -> list[str]:
    """Normalize user or SecMan supplied URI values for HTTP probing."""

    normalized: list[str] = []
    seen: set[str] = set()
    for raw in values:
        value = raw.strip()
        if not value or value.startswith("#"):
            continue
        parsed = urlparse(value)
        if not parsed.scheme:
            value = f"https://{value}"
            parsed = urlparse(value)
        if parsed.scheme.lower() not in {"http", "https"} or not parsed.netloc:
            continue
        root = parsed._replace(path="/", params="", query="", fragment="").geturl()
        if root not in seen:
            seen.add(root)
            normalized.append(root)
    return normalized


def _strip_asset_suffix(filename: str) -> str:
    basename = posixpath.basename(filename)
    basename = re.sub(r"\.(?:min|bundle|global|esm|umd|cjs|slim|all)$", "", posixpath.splitext(basename)[0], flags=re.I)
    return basename


def _name_from_filename(stem: str) -> str | None:
    match = _NAME_VERSION_RE.match(stem)
    name = match.group("name") if match else stem
    name = re.sub(r"[._-](?:min|bundle|global|esm|umd|cjs|slim|all)$", "", name, flags=re.I)
    name = _LIBRARY_ALIASES.get(name.lower(), name)
    return name.replace("_", "-").strip(".-/") or None


def _name_from_cdn_path(host: str, path_parts: list[str]) -> str | None:
    if "cdn.jsdelivr.net" in host:
        if len(path_parts) >= 2 and path_parts[0] == "npm":
            package = path_parts[1].split("@")
            if path_parts[1].startswith("@") and len(path_parts) >= 3:
                return f"{path_parts[1]}/{path_parts[2].split('@')[0]}"
            return package[0]
        if len(path_parts) >= 2 and path_parts[0] == "gh":
            return path_parts[1]
    if "unpkg.com" in host and path_parts:
        if path_parts[0].startswith("@") and len(path_parts) >= 2:
            return f"{path_parts[0]}/{path_parts[1].split('@')[0]}"
        return path_parts[0].split("@")[0]
    if "cdnjs.cloudflare.com" in host and len(path_parts) >= 3 and path_parts[0] == "ajax" and path_parts[1] == "libs":
        return path_parts[2]
    if "ajax.googleapis.com" in host and len(path_parts) >= 2 and path_parts[0] == "ajax" and path_parts[1] == "libs" and len(path_parts) >= 3:
        return path_parts[2]
    return None


def _version_from_path(path_parts: list[str]) -> str | None:
    for part in path_parts:
        if "@" in part and not part.startswith("@"):
            maybe_version = part.rsplit("@", 1)[1]
            if _VERSION_RE.search(maybe_version):
                return maybe_version
        match = _VERSION_RE.search(part)
        if match:
            return match.group(1)
    return None


def _version_from_query(query: str) -> str | None:
    values = parse_qs(query)
    for key in ("v", "ver", "version"):
        for value in values.get(key, []):
            match = _VERSION_RE.search(value)
            if match:
                return match.group(1)
    return None


def _vendor_from_host(host: str) -> str | None:
    for suffix, vendor in _VENDOR_HINTS.items():
        if host == suffix or host.endswith(f".{suffix}"):
            return vendor
    return host or None
