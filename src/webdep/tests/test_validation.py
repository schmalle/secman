import json
from urllib.error import HTTPError, URLError

from webdep.cli import build_parser
from webdep.validation import NpmValidation, validate_packages


class _FakeResponse:
    def __init__(self, payload: bytes) -> None:
        self._payload = payload

    def read(self) -> bytes:
        return self._payload

    def __enter__(self) -> "_FakeResponse":
        return self

    def __exit__(self, *_args) -> bool:
        return False


class _FakeOpener:
    """Stand-in for a urllib opener returning canned metadata keyed by package name.

    *responses* maps the requested package path segment (the part after the last
    ``/``) to either a dict (serialized to JSON) or an exception instance to raise.
    """

    def __init__(self, responses: dict[str, object]) -> None:
        self._responses = responses
        self.requested_urls: list[str] = []

    def open(self, request, timeout=None):
        url = request.full_url
        self.requested_urls.append(url)
        package = url.rsplit("/", 1)[-1]
        value = self._responses.get(package)
        if isinstance(value, Exception):
            raise value
        if value is None:
            raise HTTPError(url, 404, "Not Found", {}, None)
        return _FakeResponse(json.dumps(value).encode("utf-8"))


def _metadata(latest: str, versions: list[str]) -> dict:
    return {"dist-tags": {"latest": latest}, "versions": {v: {} for v in versions}}


def test_validate_confirms_name_and_version():
    opener = _FakeOpener({"jquery": _metadata("3.7.1", ["3.6.0", "3.7.1"])})

    results = validate_packages(opener, [("jquery", "3.7.1")], timeout=5.0)
    result = results[("jquery", "3.7.1")]

    assert result == NpmValidation(
        name="jquery",
        ecosystem="npm",
        name_found=True,
        version="3.7.1",
        version_found=True,
        latest_version="3.7.1",
    )


def test_validate_enriches_with_latest_when_outdated():
    opener = _FakeOpener({"jquery": _metadata("3.7.1", ["3.6.0", "3.7.1"])})

    result = validate_packages(opener, [("jquery", "3.6.0")], timeout=5.0)[("jquery", "3.6.0")]

    assert result.version_found is True
    assert result.latest_version == "3.7.1"


def test_validate_missing_version_still_reports_latest():
    opener = _FakeOpener({"jquery": _metadata("3.7.1", ["3.7.1"])})

    result = validate_packages(opener, [("jquery", None)], timeout=5.0)[("jquery", None)]

    assert result.name_found is True
    assert result.version_found is None
    assert result.latest_version == "3.7.1"


def test_validate_unknown_package_is_not_found():
    opener = _FakeOpener({})  # everything 404s

    result = validate_packages(opener, [("not-a-real-pkg", "1.0.0")], timeout=5.0)[("not-a-real-pkg", "1.0.0")]

    assert result.name_found is False
    assert result.version_found is None
    assert result.latest_version is None
    assert result.error is None


def test_validate_caches_repeated_package():
    opener = _FakeOpener({"jquery": _metadata("3.7.1", ["3.6.0", "3.7.1"])})

    validate_packages(opener, [("jquery", "3.6.0"), ("jquery", "3.7.1")], timeout=5.0)

    assert len(opener.requested_urls) == 1


def test_validate_network_error_is_captured():
    opener = _FakeOpener({"jquery": URLError("connection refused")})

    result = validate_packages(opener, [("jquery", "3.7.1")], timeout=5.0)[("jquery", "3.7.1")]

    assert result.name_found is False
    assert result.error is not None


def test_validate_normalizes_cdn_js_suffix():
    # cdnjs surfaces the name "lodash.js"; the npm package is "lodash".
    opener = _FakeOpener({"lodash": _metadata("4.17.21", ["4.17.21"])})

    result = validate_packages(opener, [("lodash.js", "4.17.21")], timeout=5.0)[("lodash.js", "4.17.21")]

    assert result.name_found is True
    assert result.name == "lodash.js"  # stored name is preserved
    assert opener.requested_urls[-1].endswith("/lodash")


def test_validate_encodes_scoped_package_name():
    opener = _FakeOpener({"core": _metadata("7.0.0", ["7.0.0"])})

    validate_packages(opener, [("@babel/core", "7.0.0")], timeout=5.0)

    assert "%2F" in opener.requested_urls[0]


def test_parser_accepts_validate_flag():
    args = build_parser().parse_args(["--username", "u", "--validate"])
    assert args.validate is True

    default_args = build_parser().parse_args(["--username", "u"])
    assert default_args.validate is False


def test_parser_accepts_vulnerability_scan_flags():
    args = build_parser().parse_args([
        "--username",
        "u",
        "--vulnerability-scan",
        "--nvd-base-url",
        "https://nvd.test/cves",
        "--max-vulns-per-component",
        "3",
    ])

    assert args.vulnerability_scan is True
    assert args.nvd_base_url == "https://nvd.test/cves"
    assert args.max_vulns_per_component == 3
