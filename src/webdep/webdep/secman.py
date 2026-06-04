"""Small SecMan HTTP client used by the web dependency importer."""

from __future__ import annotations

from dataclasses import dataclass
import json
from http.cookiejar import CookieJar
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin
import ssl
from urllib.request import HTTPCookieProcessor, HTTPSHandler, Request, build_opener


class SecManClientError(RuntimeError):
    """Raised when the SecMan backend returns an error or cannot be reached."""


@dataclass(frozen=True)
class Asset:
    """SecMan asset fields needed by the importer."""

    id: int | None
    name: str
    uri: str | None


class SecManClient:
    """Cookie-authenticated client for the SecMan REST API."""

    def __init__(self, backend_url: str, *, verify_tls: bool = True, timeout: float = 30.0) -> None:
        self.backend_url = backend_url.rstrip("/") + "/"
        self.timeout = timeout
        context = ssl.create_default_context() if verify_tls else ssl._create_unverified_context()
        self.cookie_jar = CookieJar()
        self.opener = build_opener(HTTPCookieProcessor(self.cookie_jar), HTTPSHandlerWithContext(context))

    def login(self, username: str, password: str) -> None:
        """Authenticate against ``/api/auth/login`` and retain the session cookie."""

        response = self._request(
            "POST",
            "api/auth/login",
            {"username": username, "password": password},
        )
        if response.get("mfaRequired"):
            raise SecManClientError("MFA is required for this account; use a non-MFA automation account.")

    def assets(self) -> list[Asset]:
        """Return assets visible to the authenticated user."""

        data = self._request("GET", "api/assets")
        if not isinstance(data, list):
            raise SecManClientError("Unexpected /api/assets response shape")
        assets: list[Asset] = []
        for item in data:
            if not isinstance(item, dict):
                continue
            assets.append(
                Asset(
                    id=item.get("id"),
                    name=str(item.get("name") or item.get("uri") or item.get("id") or "unknown"),
                    uri=item.get("uri"),
                )
            )
        return assets

    def import_products(self, products: list[dict[str, Any]], *, dry_run: bool) -> dict[str, Any]:
        """Import installed product DTOs through SecMan's existing endpoint."""

        return self._request("POST", "api/installed-products/import", {"products": products, "dryRun": dry_run})

    def _request(self, method: str, path: str, body: dict[str, Any] | None = None) -> Any:
        url = urljoin(self.backend_url, path)
        payload = json.dumps(body).encode("utf-8") if body is not None else None
        request = Request(url, data=payload, method=method)
        request.add_header("Accept", "application/json")
        if payload is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                raw = response.read()
                if not raw:
                    return {}
                return json.loads(raw.decode("utf-8"))
        except HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise SecManClientError(f"{method} {url} failed with HTTP {exc.code}: {detail}") from exc
        except URLError as exc:
            raise SecManClientError(f"{method} {url} failed: {exc.reason}") from exc
        except json.JSONDecodeError as exc:
            raise SecManClientError(f"{method} {url} returned invalid JSON") from exc


class HTTPSHandlerWithContext(HTTPSHandler):
    """HTTPS handler that accepts an explicit SSL context."""

    def __init__(self, context: ssl.SSLContext) -> None:
        super().__init__(context=context)
