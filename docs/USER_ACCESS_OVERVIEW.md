# User access overview

`user-access-overview` is a read-only ADMIN diagnostic for a user's effective
asset scope. Domains mean AD domains; accounts mean AWS accounts. The caller
authenticates as themselves and supplies the target's email, never the target's
credentials. Email lookup trims whitespace and ignores case. Unknown users
return an error. Disabled users return their identity and an empty effective scope.

Build once with `./gradlew :cli:shadowJar`. The backend must contain the new
`GET /api/users/access-overview` endpoint.

```bash
./scripts/user-access-overview-macos.sh --email user@example.com
./scripts/user-access-overview-aws.sh --email user@example.com --format json
./scripts/secman user-access-overview --email user@example.com --page-size 250
./scripts/secman help user-access-overview
```

The macOS script reuses `scripts/secman`, `secmanpp.env`, and an authenticated
Proton Pass CLI session. Java and `pass-cli` must be available on PATH. The AWS
script reuses `scripts/secmancliaws.sh` and `scripts/lib/aws-secrets.sh`; see
[AWS deployment](AWS.md) for secret schema, region configuration and IAM access.
Set `SECMAN_AWS_SECRET_ID` to the intended secret. AWS CLI credentials can come
from an instance/task role; static keys are not required. Neither wrapper starts
the backend or falls back to the other secret provider. Both work from any
directory and preserve command arguments and exit status.

Both providers must supply `SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS`, and
`SECMAN_HOST` or `SECMAN_BACKEND_URL`. `--backend-url` overrides the URL.
Passwords have no command-line option. Use a verified HTTPS backend origin;
plain HTTP is accepted only for loopback development. The command keeps TLS
verification enabled and does not follow redirects, including when an existing
environment enables insecure mode for other commands.

The backend uses the target user's roles and live `AssetFilterService` /
`AssetAccessSql` rules. Reasons include personal mappings, enabled workgroup
asset/account/domain grants, incoming selected AWS sharing, and global
ADMIN/SECCHAMPION roles. Sharing is directional and non-transitive. Metadata
ownership and workgroup ancestry do not grant access. Assets with no findings
are included; individual vulnerability records are not exported.

`vulnerabilityAccess` reports permission to open `/api/vulnerabilities/current`
(ADMIN, VULN or SECCHAMPION, with an enabled user). `totalAssets` and `assets`
describe asset visibility separately, even when that feature permission is absent.
Other authenticated vulnerability-related endpoints may have different role gates.

`awsAccounts` and `adDomains` combine represented scopes and configured grants.
Each entry has a stable `value`, optional account `displayName`,
`visibleAssetCount`, `wholeScopeAccess`, and grant `reasons`. An entry with
`wholeScopeAccess: false` represents metadata on visible assets and does not
authorize the entire account/domain. A grant with count zero remains listed.
Global roles have whole-scope authority for represented scopes. Multiple grant
reasons are retained; assets and scopes are deduplicated and sorted.

The endpoint takes `email`, zero-based `page` (default 0), and `size` (default
100, maximum 500), returning `page`, `size`, `totalPages`, and full summary
metadata alongside that asset page. The CLI retrieves all pages before writing
stdout, removes the page fields, and adds `complete: true`. Logs and errors go
to stderr. `--format json` produces one JSON object. The default table is for
humans; scripts should consume JSON.

Pages use live reads, not a frozen database snapshot. The CLI rejects changed
summary/grant metadata, duplicate IDs and incomplete counts during collection.
Concurrent edits that preserve those values may still appear across pages;
rerun after grant or inventory changes for a fresh report. `generatedAt` is
the first page's timestamp. The full CLI result occupies memory proportional
to the reported asset count; backend asset pages are bounded in SQL.

Exit codes: **0** complete report (including empty/disabled scope), **2** invalid
arguments or missing configuration, **3** authentication/authorization failure,
**4** backend, unknown-user, response or report-consistency failure. Authentication
connectivity failures also return 3. Secret-provider/bootstrap failures preserve
the underlying wrapper's nonzero exit status.

Verification:

```bash
./scripts/test/run-isolated-e2e.sh --database-only -- bash -c '
  export SECMAN_E2E_ADMIN_NAME="$SECMAN_ADMIN_NAME"
  export SECMAN_E2E_ADMIN_EMAIL="$SECMAN_ADMIN_EMAIL"
  export SECMAN_E2E_ADMIN_PASS="$SECMAN_ADMIN_PASS"
  ./gradlew --no-configuration-cache :backendng:test --tests "*UserAccessOverview*"
'
./gradlew :cli:test --tests '*UserAccessOverview*'
./scripts/test/user-access-overview-wrapper-test.sh
```

Backend tests must use the runner-owned disposable MariaDB schema. This change
adds no migration, dependency, feature flag, or existing API contract change.
The bootstrap variables above supply the existing test bootstrapper from the
vault credentials. Disabling Gradle's configuration cache prevents reuse of a
previous disposable schema's test-process environment.
