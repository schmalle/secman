# SecMan Web Dependency Importer

`secman-webdep` is a Python helper that discovers browser-side dependencies on
web applications registered in SecMan. It reads asset URIs from SecMan's asset
inventory (or from a plain text file), downloads each root page (`/`), extracts
referenced CSS and JavaScript resources, and imports those resources into SecMan
as installed products via `POST /api/installed-products/import`.

The tool is intentionally implemented with the Python standard library only. It
can be copied to an administration host and run without installing runtime HTTP
or HTML parsing packages.

## What gets discovered

The scanner parses the returned HTML and records:

- `<script src="...">` entries as `JavaScript library` products.
- `<link rel="stylesheet" href="...">` entries as `CSS library` products.
- `<link rel="preload" as="script" href="...">` entries as JavaScript products.
- `<link rel="preload" as="style" href="...">` entries as CSS products.

Each discovered resource becomes a SecMan installed-product DTO:

| SecMan field | Value |
| --- | --- |
| `hostname` | Asset name from SecMan, or the URI host when `--uri-file` is used. |
| `name` | Best-effort package/library name derived from common CDN URL layouts or file names. |
| `vendor` | CDN/vendor host hint, such as `cdnjs`, `jsDelivr`, or the resource hostname. |
| `version` | Best-effort version parsed from URL path, package marker, or `?v=` query parameter. |
| `category` | `CSS library` or `JavaScript library`. |
| `installationPath` | Absolute URL of the dependency resource. |
| `externalId` | Stable `webdep:` hash for idempotent updates. |

## Requirements

- Python 3.10 or newer.
- A SecMan user with permission to read assets (`GET /api/assets`) and import
  installed products (`POST /api/installed-products/import`). In the current
  backend, import requires `ADMIN` or `VULN`.
- For unattended automation, use a non-MFA service account. The helper cannot
  complete interactive MFA challenges.

## Installation

Run directly from the repository:

```bash
cd src/webdep
python3 -m webdep.cli --help
```

Optional editable install:

```bash
cd src/webdep
python3 -m pip install -e .
secman-webdep --help
```

## Basic usage

Scan all HTTP(S) asset URIs visible to the authenticated SecMan user and perform
an import dry run:

```bash
python3 -m webdep.cli \
  --backend-url https://secman.covestro.net \
  --username automation-webdep \
  --dry-run
```

If `--password` is omitted, the helper prompts for it without echoing. To run in
a CI job, pass the password through the environment rather than committing it:

```bash
python3 -m webdep.cli \
  --backend-url "$SECMAN_BACKEND_URL" \
  --username "$SECMAN_WEBDEP_USER" \
  --password "$SECMAN_WEBDEP_PASSWORD" \
  --dry-run \
  --json
```

To write the discovered products for real, remove `--dry-run`:

```bash
python3 -m webdep.cli \
  --backend-url https://secman.covestro.net \
  --username automation-webdep
```

## Reading URIs from a file

Use `--uri-file` to bypass the SecMan asset inventory and scan a supplied list.
The file format is one URI per line. Blank lines and lines starting with `#` are
ignored. Bare hostnames are treated as HTTPS URLs. Only `http://` and `https://`
URIs are scanned.

Example `uris.txt`:

```text
# Production web applications
https://portal.example.com/app
intranet.example.com
http://legacy.example.net:8080/
urn:asset:not-scanned
```

Run:

```bash
python3 -m webdep.cli \
  --backend-url https://secman.covestro.net \
  --username automation-webdep \
  --uri-file uris.txt \
  --dry-run
```

When `--uri-file` is used, products are mapped back to SecMan by hostname. The
hostname must match an existing SecMan asset name or the backend import response
will report it as an unknown system.

## Self-signed certificates

By default, TLS certificates are verified for both SecMan and scanned web
applications. For development systems or internal applications with self-signed
certificates, add `--insecure`:

```bash
python -m webdep.cli \
  --backend-url https://secman-dev.example.test \
  --username automation-webdep \
  --insecure \
  --dry-run
```

`--insecure` disables certificate validation and should not be used for routine
production automation unless certificate trust cannot be fixed.

## Validating dependencies against npm

By default the tool only identifies dependencies by name and version from the
page itself. Add `--validate` to confirm each discovered dependency against the
public npm registry (`https://registry.npmjs.org`) and enrich it with registry
facts:

```bash
python3 -m webdep.cli \
  --backend-url https://secman.covestro.net \
  --username automation-webdep \
  --validate \
  --dry-run
```

For every dependency this reports whether the package name exists on npm,
whether the detected version exists, and the latest published version. Names
carrying CDN-specific suffixes (for example `lodash.js`) are normalized for the
lookup only — the stored/imported name is unchanged. Results appear in the
human-readable summary and, with `--json`, under a `validation` key.

`--validate` is **off by default** and is the only feature that makes outbound
calls to a third-party host, so the tool stays usable on administration hosts
without internet access. Validation findings are reported only; they are not
written to SecMan. Unreachable lookups are recorded per dependency and never
abort the run.

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Scan and import completed without target fetch failures. |
| `1` | SecMan calls completed, but at least one target URI could not be fetched or parsed. |
| `2` | Command-line, authentication, SecMan API, or local file error. |

## Operational notes

- The helper always downloads each target's normalized root URI (`/`) and does
  not crawl deeper pages.
- Discovery is based on static HTML. Dependencies injected only after JavaScript
  execution are not visible to this tool.
- Import calls are batched with `--max-products-per-request` (default `1000`) to
  stay below the backend request limit.
- Re-running the helper is safe: stable `externalId` values let SecMan update
  existing installed-product rows rather than creating duplicates.
- Use `--json` when integrating with schedulers or log collectors.

## Development

Run the unit tests from `src/webdep`:

```bash
python -m pytest
```

If `pytest` is unavailable, the tests are intentionally simple and can be ported
to `unittest`, but repository check-in should use `pytest` when possible.
