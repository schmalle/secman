import json
from email.message import Message

from webdep.vulnerabilities import (
    WebComponent,
    components_from_composer_lock,
    components_from_headers,
    find_vulnerabilities,
)


class _FakeResponse:
    def __init__(self, payload: dict):
        self._payload = json.dumps(payload).encode("utf-8")

    def read(self):
        return self._payload

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class _FakeOpener:
    def __init__(self, payload: dict):
        self.payload = payload
        self.urls = []

    def open(self, request, timeout=None):
        self.urls.append(request.full_url)
        return _FakeResponse(self.payload)


def test_components_from_headers_identifies_web_server_and_php_runtime():
    headers = Message()
    headers["Server"] = "nginx/1.18.0"
    headers["X-Powered-By"] = "PHP/8.1.2"

    components = components_from_headers(headers)

    assert WebComponent("nginx", "1.18.0", "Web server", "Server: nginx/1.18.0") in components
    assert WebComponent("PHP", "8.1.2", "PHP runtime", "X-Powered-By: PHP/8.1.2") in components


def test_components_from_composer_lock_identifies_php_libraries():
    lock = json.dumps(
        {
            "packages": [{"name": "symfony/http-foundation", "version": "v6.4.1"}],
            "packages-dev": [{"name": "phpunit/phpunit", "version": "10.0.0"}],
        }
    )

    components = components_from_composer_lock(lock, "https://example.test/composer.lock")

    assert components[0] == WebComponent(
        "symfony/http-foundation",
        "6.4.1",
        "PHP library",
        "https://example.test/composer.lock",
        "symfony",
    )
    assert components[1].category == "PHP library"


def test_find_vulnerabilities_maps_nvd_results_to_secman_findings():
    payload = {
        "vulnerabilities": [
            {
                "cve": {
                    "id": "CVE-2024-1234",
                    "descriptions": [{"lang": "en", "value": "test vuln"}],
                    "metrics": {"cvssMetricV31": [{"cvssData": {"baseSeverity": "HIGH"}}]},
                    "references": [{"url": "https://nvd.nist.gov/vuln/detail/CVE-2024-1234"}],
                }
            }
        ]
    }
    opener = _FakeOpener(payload)

    findings = find_vulnerabilities(
        opener,
        "portal.example.test",
        [WebComponent("jquery", "1.12.4", "JavaScript library", "https://cdn/jquery.js")],
        timeout=5,
        nvd_base_url="https://nvd.test/cves",
    )

    assert findings[0].asset_name == "portal.example.test"
    assert findings[0].cve == "CVE-2024-1234"
    assert findings[0].severity == "HIGH"
    assert "keywordSearch=jquery%201.12.4" in opener.urls[0]
