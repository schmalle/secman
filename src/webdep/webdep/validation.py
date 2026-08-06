"""Validate discovered dependencies against the public npm registry.

Like :mod:`webdep.discovery`, this module uses only the Python standard library
so operators can run validation from an administration workstation without
installing additional runtime dependencies. Network access is opt-in: it only
runs when the caller passes ``--validate`` on the command line.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import json
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request

NPM_REGISTRY_URL = "https://registry.npmjs.org"
# Abbreviated metadata document: small payload that still carries dist-tags and
# the full version map. See https://github.com/npm/registry/blob/main/docs/responses/package-metadata.md
_ABBREVIATED_ACCEPT = "application/vnd.npm.install-v1+json"


@dataclass(frozen=True)
class NpmValidation:
    """Result of validating one detected dependency against the npm registry."""

    name: str
    ecosystem: str
    name_found: bool
    version: str | None = None
    version_found: bool | None = None
    latest_version: str | None = None
    error: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


def validate_packages(
    opener: Any,
    names_and_versions: Iterable[tuple[str, str | None]],
    *,
    timeout: float,
) -> dict[tuple[str, str | None], NpmValidation]:
    """Validate each ``(name, version)`` pair against the npm registry.

    npm metadata is cached per package name so a library reused across many
    assets only costs a single request. Lookup failures for one package are
    captured on the result and never abort validation of the others.
    """

    metadata_cache: dict[str, tuple[dict[str, Any] | None, str | None]] = {}
    results: dict[tuple[str, str | None], NpmValidation] = {}

    for name, version in names_and_versions:
        key = (name, version)
        if key in results:
            continue

        metadata, error = _resolve_metadata(opener, name, timeout=timeout, cache=metadata_cache)
        if error is not None:
            results[key] = NpmValidation(
                name=name,
                ecosystem="npm",
                name_found=False,
                version=version,
                version_found=None,
                latest_version=None,
                error=error,
            )
            continue

        if metadata is None:
            results[key] = NpmValidation(
                name=name,
                ecosystem="npm",
                name_found=False,
                version=version,
                version_found=None,
                latest_version=None,
            )
            continue

        versions = metadata.get("versions") or {}
        latest = (metadata.get("dist-tags") or {}).get("latest")
        results[key] = NpmValidation(
            name=name,
            ecosystem="npm",
            name_found=True,
            version=version,
            version_found=(version in versions) if version else None,
            latest_version=latest,
        )

    return results


def _resolve_metadata(
    opener: Any,
    name: str,
    *,
    timeout: float,
    cache: dict[str, tuple[dict[str, Any] | None, str | None]],
) -> tuple[dict[str, Any] | None, str | None]:
    """Return ``(metadata, error)`` for *name*, trying normalized candidates.

    Detected names sometimes carry CDN-specific suffixes that are not the npm
    package name (e.g. cdnjs serves ``lodash.js`` for the ``lodash`` package).
    Only the registry query is normalized; the caller's stored name is untouched.
    """

    if name in cache:
        return cache[name]

    last_error: str | None = None
    for candidate in _query_candidates(name):
        try:
            metadata = _fetch_npm_metadata(opener, candidate, timeout=timeout)
        except (URLError, TimeoutError, OSError) as exc:
            last_error = str(exc)
            continue
        if metadata is not None:
            cache[name] = (metadata, None)
            return cache[name]

    cache[name] = (None, last_error)
    return cache[name]


def _query_candidates(name: str) -> list[str]:
    candidates = [name]
    stripped = name[:-3] if name.lower().endswith(".js") else name
    if stripped and stripped not in candidates:
        candidates.append(stripped)
    return candidates


def _fetch_npm_metadata(opener: Any, package: str, *, timeout: float) -> dict[str, Any] | None:
    """Fetch abbreviated npm metadata; ``None`` on 404, re-raise other HTTP errors."""

    # ``safe="@"`` keeps the scope marker but encodes the "/" in scoped names
    # (e.g. ``@scope/name`` -> ``@scope%2Fname``).
    url = f"{NPM_REGISTRY_URL}/{quote(package, safe='@')}"
    request = Request(url, method="GET", headers={"Accept": _ABBREVIATED_ACCEPT})
    try:
        with opener.open(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        if exc.code == 404:
            return None
        raise
