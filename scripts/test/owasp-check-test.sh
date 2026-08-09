#!/usr/bin/env bash
#
# owasp-check-test.sh — prove ./scripts/owasp-check.sh actually fires.
#
# A static gate fails in two directions and only one of them is visible:
#
#   false positive -> loud, someone complains, it gets fixed
#   false negative -> silent, the gate reports OK forever and nobody learns
#                     that the regex stopped matching
#
# So this builds a throwaway git repo, plants one deliberately vulnerable file
# per rule, and asserts the rule id appears in the output. Then it plants the
# *correct* version of the same code and asserts the rule is silent — a rule
# that fires on the repo's own approved pattern is worse than no rule.
#
# Fixtures live only inside the temp repo. Nothing here touches secman.
#
# Usage: ./scripts/test/owasp-check-test.sh [--verbose]

set -uo pipefail

VERBOSE=0
[ "${1:-}" = "--verbose" ] && VERBOSE=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECKER="$REPO_ROOT/scripts/owasp-check.sh"
[ -x "$CHECKER" ] || { echo "FATAL: $CHECKER missing or not executable" >&2; exit 2; }

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

PASS=0
FAIL=0

# --- sandbox repo ------------------------------------------------------------
#
# The checker resolves its repo root from its own path, so it is copied in
# rather than called across repos.

setup_repo() {
    rm -rf "$SANDBOX/repo"
    mkdir -p "$SANDBOX/repo/scripts"
    cp "$CHECKER" "$SANDBOX/repo/scripts/owasp-check.sh"
    cd "$SANDBOX/repo" || exit 2
    git init -q .
    git config user.email t@example.invalid
    git config user.name t
    git config commit.gpgsign false
    mkdir -p src/backendng/src/main/kotlin/com/secman/{controller,service,repository,mcp/tools} \
             src/frontend/src/components src/clinotify
    echo "baseline" > README.md
    git add -A >/dev/null
    git commit -qm baseline
}

plant() { # plant <relpath> <<'EOF' ... EOF
    mkdir -p "$(dirname "$SANDBOX/repo/$1")"
    cat > "$SANDBOX/repo/$1"
}

run_checker() {
    ( cd "$SANDBOX/repo" && ./scripts/owasp-check.sh --base HEAD 2>&1 )
}

# --- assertions --------------------------------------------------------------

expect_fires() { # expect_fires <rule-id> <label>
    local out
    out="$(run_checker)"
    if echo "$out" | grep -q "$1"; then
        PASS=$((PASS + 1))
        [ "$VERBOSE" -eq 1 ] && echo "  ok   $1  ($2)"
    else
        FAIL=$((FAIL + 1))
        echo "  FAIL $1  ($2) — rule did not fire on a vulnerable fixture"
        [ "$VERBOSE" -eq 1 ] && echo "$out" | sed 's/^/       /'
    fi
    return 0
}

# A rule can "fire" and still be useless: file_rule downgrades BLOCK to REVIEW
# when its trigger regex fails the awk half of the severity test, which turns a
# red gate green. That bug shipped once already, so severity is asserted too.
expect_severity() { # expect_severity <SEV> <rule-id> <label>
    local out
    out="$(run_checker)"
    if echo "$out" | grep -A2 "\[$1\]" | grep -q "$2"; then
        PASS=$((PASS + 1))
        [ "$VERBOSE" -eq 1 ] && echo "  ok   $2  ($3, severity $1)"
    else
        FAIL=$((FAIL + 1))
        echo "  FAIL $2  ($3) — expected severity $1"
        [ "$VERBOSE" -eq 1 ] && echo "$out" | sed 's/^/       /'
    fi
    return 0
}

expect_silent() { # expect_silent <rule-id> <label>
    local out
    out="$(run_checker)"
    if echo "$out" | grep -q "$1"; then
        FAIL=$((FAIL + 1))
        echo "  FAIL $1  ($2) — rule fired on the APPROVED pattern (false positive)"
        [ "$VERBOSE" -eq 1 ] && echo "$out" | sed 's/^/       /'
    else
        PASS=$((PASS + 1))
        [ "$VERBOSE" -eq 1 ] && echo "  ok   $1  ($2, silent as expected)"
    fi
    return 0
}

echo "owasp-check-test: planting vulnerable fixtures"

# ============================================================================
# A01 — Broken Access Control
# ============================================================================

setup_repo
plant src/backendng/src/main/kotlin/com/secman/controller/BadController.kt <<'EOF'
package com.secman.controller

@Controller("/api/bad")
class BadController(private val assetRepository: AssetRepository) {
    @Get("/{id}")
    fun get(id: Long): Asset? = assetRepository.findById(id).orElse(null)
}
EOF
expect_fires A01-no-secured "controller with endpoints and no @Secured"
expect_fires A01-findbyid  "findById on a request id"
expect_severity BLOCK A01-no-secured "newly added unsecured controller"

setup_repo
plant src/backendng/src/main/kotlin/com/secman/controller/GoodController.kt <<'EOF'
package com.secman.controller

@Controller("/api/good")
@Secured(SecurityRule.IS_AUTHENTICATED)
class GoodController(private val assetFilterService: AssetFilterService) {
    @Get("/{id}")
    fun get(id: Long, authentication: Authentication): Asset? =
        if (assetFilterService.canAccessAsset(id, authentication)) load(id) else null
}
EOF
expect_silent A01-no-secured "secured controller using AssetFilterService"

setup_repo
plant src/backendng/src/main/kotlin/com/secman/controller/BadEndpoint.kt <<'EOF'
package com.secman.controller

@Endpoint(id = "memory")
class BadEndpoint {
    @Read
    fun memory(): Map<String, Any> = mapOf("heap" to 1)
}
EOF
expect_fires A01-endpoint-secured "management @Endpoint with no @Secured"
expect_severity BLOCK A01-endpoint-secured "newly added unsecured management endpoint"

setup_repo
plant src/backendng/src/main/kotlin/com/secman/controller/GoodEndpoint.kt <<'EOF'
package com.secman.controller

@Endpoint(id = "memory")
@Secured("ADMIN")
class GoodEndpoint {
    @Read
    fun memory(): Map<String, Any> = mapOf("heap" to 1)
}
EOF
expect_silent A01-endpoint-secured "management @Endpoint gated to ADMIN"

setup_repo
plant src/backendng/src/main/kotlin/com/secman/mcp/McpToolPermissions.kt <<'EOF'
object McpToolPermissions {
    val LISTING = table(setOf(ASSETS_READ) to listOf("existing_tool"))
    val CALLING = table(setOf(ASSETS_READ) to listOf("existing_tool"))
}
EOF
plant src/backendng/src/main/kotlin/com/secman/mcp/tools/RogueTool.kt <<'EOF'
package com.secman.mcp.tools

class RogueTool : McpTool {
    override val name = "rogue_tool"
    override suspend fun execute(args: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        return McpToolResult.success(emptyMap())
    }
}
EOF
expect_fires A01-mcp-perms "MCP tool absent from LISTING/CALLING"
expect_fires A01-mcp-guard "MCP tool with no guard (fails OPEN)"

# ============================================================================
# A02 — Cryptographic Failures
# ============================================================================

setup_repo
plant src/frontend/src/components/BadAuth.tsx <<'EOF'
export function BadAuth() {
  const token = localStorage.getItem('authToken');
  sessionStorage.setItem('jwt', token);
}
EOF
plant src/backendng/src/main/kotlin/com/secman/service/BadHash.kt <<'EOF'
package com.secman.service
class BadHash {
    fun hash(pw: String) = MessageDigest.getInstance("SHA-256").digest(pw.toByteArray())
    private val dbPassword = "hunter2-not-a-real-secret"
}
EOF
expect_fires A02-token-storage "JWT in localStorage/sessionStorage"
expect_fires A02-weak-hash     "SHA-256 used to hash a secret"
expect_fires A02-secret-lit    "hardcoded credential literal"

setup_repo
plant src/backendng/src/main/kotlin/com/secman/service/GoodHash.kt <<'EOF'
package com.secman.service
class GoodHash(private val encoder: BCryptPasswordEncoder) {
    fun hash(pw: String) = encoder.encode(pw)
    private val dbPassword = System.getenv("DB_PASSWORD")
}
EOF
expect_silent A02-weak-hash  "BCryptPasswordEncoder"
expect_silent A02-secret-lit "secret read from the environment"

# ============================================================================
# A03 — Injection
# ============================================================================

setup_repo
plant src/backendng/src/main/kotlin/com/secman/repository/BadRepo.kt <<'EOF'
package com.secman.repository
class BadRepo {
    fun find(name: String) = em.createNativeQuery("SELECT * FROM asset WHERE name = '$name'")
    fun sorted(col: String) = em.createQuery("SELECT a FROM Asset a ORDER BY " + col)
}
EOF
plant src/frontend/src/components/BadHtml.tsx <<'EOF'
export function BadHtml({ html }: { html: string }) {
  return <div dangerouslySetInnerHTML={{ __html: html }} />;
}
EOF
expect_fires A03-sql-interp "interpolated value in a query string"
expect_fires A03-sql-concat "concatenated value in a query string"
expect_fires A03-html       "dangerouslySetInnerHTML without DOMPurify"

setup_repo
plant src/backendng/src/main/kotlin/com/secman/repository/GoodRepo.kt <<'EOF'
package com.secman.repository
interface GoodRepo {
    @Query("SELECT a FROM Asset a WHERE a.name = :name")
    fun find(name: String): List<Asset>
}
EOF
plant src/frontend/src/components/GoodHtml.tsx <<'EOF'
import DOMPurify from 'dompurify';
export function GoodHtml({ html }: { html: string }) {
  return <div dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(html) }} />;
}
EOF
expect_silent A03-sql-interp "bound :name parameter"
expect_silent A03-html       "DOMPurify.sanitize at the assignment site"

# ============================================================================
# A05 / A06
# ============================================================================

setup_repo
plant src/backendng/src/main/resources/application.yml <<'EOF'
micronaut:
  server:
    cors:
      configurations:
        web:
          allowedOrigins: "*"
EOF
plant src/frontend/package.json <<'EOF'
{ "dependencies": { "left-pad": "latest" } }
EOF
expect_fires A05-cors-wildcard "wildcard CORS origin"
expect_fires A06-floating-npm  "floating npm version"

# ============================================================================
# A08 — Integrity
# ============================================================================

setup_repo
plant src/backendng/src/main/kotlin/com/secman/service/BadXml.kt <<'EOF'
package com.secman.service
class BadXml {
    fun parse(input: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance()
        return factory.newDocumentBuilder().parse(input)
    }
}
EOF
expect_fires A08-xxe "XML parser with no XXE block"

setup_repo
plant src/backendng/src/main/kotlin/com/secman/service/GoodXml.kt <<'EOF'
package com.secman.service
class GoodXml {
    fun parse(input: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        return factory.newDocumentBuilder().parse(input)
    }
}
EOF
expect_silent A08-xxe "XML parser with the approved XXE block"

# ============================================================================
# A09 — Logging
# ============================================================================

setup_repo
plant src/backendng/src/main/kotlin/com/secman/service/BadLog.kt <<'EOF'
package com.secman.service
class BadLog {
    fun login(user: String, password: String) {
        log.debug("login user=$user password=$password")
        try { risky() } catch (e: Exception) { }
    }
}
EOF
expect_fires A09-secret-log  "password interpolated into a log line"
expect_fires A09-empty-catch "silently swallowed exception"

setup_repo
plant src/backendng/src/main/kotlin/com/secman/service/GoodLog.kt <<'EOF'
package com.secman.service
class GoodLog {
    fun login(user: String) {
        log.info("login outcome=success actor={} tokenConfigured={}", user, true)
        try { risky() } catch (e: Exception) { log.warn("risky failed", e) }
    }
}
EOF
expect_silent A09-secret-log  "tokenConfigured boolean, no secret value"
expect_silent A09-empty-catch "exception logged, not swallowed"

# ============================================================================
# Self-exemption — the gate must not flag its own fixtures
# ============================================================================
#
# Without this, every branch that touches this file goes red for fake
# vulnerabilities, and a gate that is red for the wrong reason is a gate people
# stop reading. The exemption is exactly two paths and is printed on every run.

setup_repo
plant scripts/test/owasp-check-test.sh <<'EOF'
# fixtures, not real code
plant BadHash.kt <<'INNER'
    private val dbPassword = "hunter2-not-a-real-secret"
    log.debug("login user=$user password=$password")
    try { risky() } catch (e: Exception) { }
INNER
EOF
expect_silent A02-secret-lit  "the gate's own fixture file is exempt"
expect_silent A09-secret-log  "the gate's own fixture file is exempt"
expect_silent A09-empty-catch "the gate's own fixture file is exempt"

# The exemption must stay narrow: any OTHER test file is still in scope.
setup_repo
plant src/backendng/src/test/kotlin/com/secman/SomeOtherTest.kt <<'EOF'
package com.secman
class SomeOtherTest {
    private val adminPassword = "hardcoded-admin-value-9f2"
}
EOF
expect_fires A02-secret-lit "an ordinary test file is NOT exempt"

# ============================================================================
# Exit-code contract
# ============================================================================

setup_repo
plant src/backendng/src/main/kotlin/com/secman/controller/ExitController.kt <<'EOF'
package com.secman.controller
@Controller("/api/x")
class ExitController { @Get fun x() = "x" }
EOF
( cd "$SANDBOX/repo" && ./scripts/owasp-check.sh --base HEAD >/dev/null 2>&1 )
if [ $? -eq 1 ]; then
    PASS=$((PASS + 1)); [ "$VERBOSE" -eq 1 ] && echo "  ok   exit 1 on BLOCK"
else
    FAIL=$((FAIL + 1)); echo "  FAIL exit code — BLOCK finding must exit 1"
fi

setup_repo
plant src/backendng/src/main/kotlin/com/secman/service/Clean.kt <<'EOF'
package com.secman.service
class Clean { fun ok() = 1 }
EOF
( cd "$SANDBOX/repo" && ./scripts/owasp-check.sh --base HEAD >/dev/null 2>&1 )
if [ $? -eq 0 ]; then
    PASS=$((PASS + 1)); [ "$VERBOSE" -eq 1 ] && echo "  ok   exit 0 when clean"
else
    FAIL=$((FAIL + 1)); echo "  FAIL exit code — clean tree must exit 0"
fi

# --- report ------------------------------------------------------------------

cd "$REPO_ROOT" || exit 2
echo
echo "owasp-check-test: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
echo "owasp-check-test: OK"
exit 0
