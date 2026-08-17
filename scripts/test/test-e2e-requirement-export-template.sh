#!/usr/bin/env bash
#
# E2E: company Word template for requirement exports.
#
# Covers every function the company-template feature adds, end to end:
#
#   1. Seeding        — a fresh installation has an ACTIVE example template, so
#                       `templateMode=LATEST` (the export default) resolves
#   2. Example        — GET  /api/requirement-export-templates/example
#   3. Validation     — POST /api/requirement-export-templates/validate rejects a
#                       macro-enabled package and a template with no insertion point
#   4. Upload         — POST /api/requirement-export-templates stores + activates
#   5. Release export — GET  /api/requirements/export/docx?releaseId=... renders the
#                       release name / version / date / status into the document
#   6. Placement      — requirement content lands *between* the template's front
#                       matter and its back matter, not appended after everything
#   7. Usage audit    — the export writes a usage row and advances lastUsedAt
#   8. Lifecycle      — deactivate, reactivate, delete (even when already used)
#   9. AuthZ negatives — a plain user reaches none of the write verbs
#
# ## Why the assertions look the way they do
#
# The assertions are on *behaviour that is ours*: which placeholders get bound,
# where content lands relative to the template's own paragraphs, and who is
# allowed to do what. Document text is read by unzipping the returned .docx and
# grepping `word/document.xml` — no Word, no POI, no third-party tooling on the
# runner.
#
# Anonymous export endpoints are deliberately NOT exercised as a negative: they
# are IS_ANONYMOUS by design to power the public /requirements/download page.
# See docs/REQUIREMENT_EXPORT_TEMPLATES.md §Access control.
#
# ## Destructiveness
#
# Non-destructive. Everything it creates carries E2E_PREFIX and is removed by the
# cleanup that runs both before (unconditional, so leftovers from a crashed run
# are cleared) and after (trap EXIT). It never deletes a template, release,
# requirement or user it did not create — in particular it leaves the seeded
# example template alone.
#
# ## Required env (resolved via pass-cli)
#   SECMAN_ADMIN_NAME, SECMAN_ADMIN_PASS
#   SECMAN_USER_NAME,  SECMAN_USER_PASS    (plain user, for the authz negatives)
#   BASE_URL or SECMAN_BACKEND_URL         backend URL; never a localhost literal
# ## Optional
#   VERBOSE=true
#
# ## Usage
#   pass-cli run --env-file ./secmanpp.env -- \
#       ./scripts/test/test-e2e-requirement-export-template.sh --verbose

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../../tests/lib/secman-test-tls.sh
source "$REPO_ROOT/tests/lib/secman-test-tls.sh"

# =============================================================================
# Configuration
# =============================================================================

while [[ $# -gt 0 ]]; do
    case "$1" in
        --verbose|-v) VERBOSE=true; shift ;;
        *)            shift ;;
    esac
done

BASE_URL="${BASE_URL:-${SECMAN_BACKEND_URL:-}}"
VERBOSE="${VERBOSE:-false}"

STAMP="$(date +%s)"
SUFFIX="${STAMP: -6}"
E2E_PREFIX="e2e-reqtpl-"

TEMPLATE_NAME="${E2E_PREFIX}template-${SUFFIX}"
RELEASE_NAME="${E2E_PREFIX}release-${SUFFIX}"
# MAJOR.MINOR.PATCH — ReleaseService.SEMANTIC_VERSION_REGEX rejects anything else.
RELEASE_VERSION="99.0.${SUFFIX}"

# Distinctive strings so a grep of the rendered document cannot match by accident.
FRONT_MATTER="E2EFRONTMATTER${SUFFIX}"
BACK_MATTER="E2EBACKMATTER${SUFFIX}"

ADMIN_COOKIE="$(mktemp)"
USER_COOKIE="$(mktemp)"
WORK_DIR="$(mktemp -d)"

CREATED_TEMPLATE_ID=""
CREATED_RELEASE_ID=""

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

log_info()  { echo -e "${BLUE}[INFO]${NC} $1" >&2; }
log_pass()  { echo -e "${GREEN}[PASS]${NC} $1" >&2; PASS_COUNT=$((PASS_COUNT + 1)); }
log_fail()  { echo -e "${RED}[FAIL]${NC} $1" >&2; FAIL_COUNT=$((FAIL_COUNT + 1)); }
log_skip()  { echo -e "${YELLOW}[SKIP]${NC} $1" >&2; SKIP_COUNT=$((SKIP_COUNT + 1)); }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1" >&2; }
log_dbg()   { [[ "$VERBOSE" == "true" ]] && echo -e "${YELLOW}[DEBUG]${NC} $1" >&2 || true; }
phase()     { echo >&2; echo -e "${BLUE}=== $1 ===${NC}" >&2; }

cleanup() {
    local code=$?
    cleanup_testbed || true
    rm -f "$ADMIN_COOKIE" "$USER_COOKIE" 2>/dev/null || true
    rm -rf "$WORK_DIR" 2>/dev/null || true
    exit $code
}

# =============================================================================
# Prerequisites
# =============================================================================

check_prerequisites() {
    phase "Prerequisites"

    local missing=0
    # `zip` builds the .docx fixtures, `unzip` reads the exported one back.
    for cmd in curl jq zip unzip; do
        command -v "$cmd" >/dev/null 2>&1 || { log_fail "Required command not found: $cmd"; missing=1; }
    done
    [[ $missing -eq 1 ]] && exit 1

    for var in SECMAN_ADMIN_NAME SECMAN_ADMIN_PASS SECMAN_USER_NAME SECMAN_USER_PASS; do
        if [[ -z "${!var:-}" ]]; then
            log_fail "$var is not set (source it via pass-cli)"
            exit 1
        fi
    done

    if [[ -z "$BASE_URL" ]]; then
        log_fail "BASE_URL / SECMAN_BACKEND_URL is not set — never hardcode localhost"
        exit 1
    fi
    BASE_URL="${BASE_URL%/}"
    log_info "Backend: $BASE_URL"
}

# =============================================================================
# HTTP helpers
# =============================================================================

login() {
    local user="$1" pass="$2" jar="$3"
    local status
    status=$(curl -sS -o "$WORK_DIR/login.json" -w '%{http_code}' -c "$jar" \
        -X POST "$BASE_URL/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "$(jq -n --arg u "$user" --arg p "$pass" '{username:$u,password:$p}')")
    [[ "$status" == "200" ]]
}

# The status of the last api_* call. It is kept in a file rather than a variable
# because most callers wrap these helpers in $( ), and a command substitution
# runs in a subshell — a plain assignment there is discarded, which under
# `set -u` aborts the run on the first read.
http_status() { cat "$WORK_DIR/http_status" 2>/dev/null || echo "000"; }

# Prints the body; records the status for http_status. Callers assert on both.
api_get() {
    local jar="$1" path="$2"
    curl -sS -o "$WORK_DIR/resp.json" -w '%{http_code}' -b "$jar" "$BASE_URL$path" \
        > "$WORK_DIR/http_status"
    cat "$WORK_DIR/resp.json"
}

api_post() {
    local jar="$1" path="$2" body="$3"
    curl -sS -o "$WORK_DIR/resp.json" -w '%{http_code}' -b "$jar" \
        -X POST "$BASE_URL$path" -H 'Content-Type: application/json' -d "$body" \
        > "$WORK_DIR/http_status"
    cat "$WORK_DIR/resp.json"
}

api_delete() {
    local jar="$1" path="$2"
    curl -sS -o "$WORK_DIR/resp.json" -w '%{http_code}' -b "$jar" \
        -X DELETE "$BASE_URL$path" > "$WORK_DIR/http_status"
    cat "$WORK_DIR/resp.json"
}

# Downloads a binary body to $WORK_DIR/$2; records the status for http_status.
api_download() {
    local jar="$1" outfile="$2" path="$3"
    curl -sS -o "$WORK_DIR/$outfile" -w '%{http_code}' -b "$jar" "$BASE_URL$path" \
        > "$WORK_DIR/http_status"
}

# =============================================================================
# .docx fixtures
#
# A .docx is an OOXML ZIP. Building one with `zip` keeps the runner free of Word,
# POI or any Python/Node docx library — the backend is what has to parse it.
# =============================================================================

# build_docx <output.docx> <body-paragraph-text>...
build_docx() {
    local out="$1"; shift
    local dir; dir="$(mktemp -d "$WORK_DIR/docx-XXXXXX")"

    mkdir -p "$dir/_rels" "$dir/word/_rels"

    cat > "$dir/[Content_Types].xml" <<'XML'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>
XML

    cat > "$dir/_rels/.rels" <<'XML'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>
XML

    cat > "$dir/word/_rels/document.xml.rels" <<'XML'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>
XML

    {
        echo '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        echo '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
        local text
        for text in "$@"; do
            # Escape the XML metacharacters; the fixture text is ours, but a stray
            # & would produce an unparseable package and a confusing failure.
            local escaped="${text//&/&amp;}"
            escaped="${escaped//</&lt;}"
            escaped="${escaped//>/&gt;}"
            printf '<w:p><w:r><w:t xml:space="preserve">%s</w:t></w:r></w:p>\n' "$escaped"
        done
        echo '</w:body></w:document>'
    } > "$dir/word/document.xml"

    # -D omits directory entries, matching what Word itself writes.
    (cd "$dir" && zip -q -r -X -D "$out" '[Content_Types].xml' _rels word)
    rm -rf "$dir"
}

# A .docx carrying a macro payload, which validation must reject.
build_macro_docx() {
    local out="$1"
    build_docx "$out" '${requirements}'
    local dir; dir="$(mktemp -d "$WORK_DIR/macro-XXXXXX")"
    mkdir -p "$dir/word"
    printf 'fake vba payload' > "$dir/word/vbaProject.bin"
    (cd "$dir" && zip -q -r -X -D "$out" word)
    rm -rf "$dir"
}

# Prints the concatenated text of a .docx's body, one paragraph per line.
docx_text() {
    local file="$1"
    unzip -p "$file" word/document.xml \
        | sed -e 's|</w:p>|\n|g' \
        | sed -e 's|<[^>]*>||g'
}

# =============================================================================
# Testbed
# =============================================================================

cleanup_testbed() {
    phase "Cleanup"
    login "$SECMAN_ADMIN_NAME" "$SECMAN_ADMIN_PASS" "$ADMIN_COOKIE" || return 0

    # Templates: only ones carrying our prefix. The seeded example is never touched.
    local templates
    templates=$(api_get "$ADMIN_COOKIE" "/api/requirement-export-templates?includeInactive=true" 2>/dev/null || echo '[]')
    echo "$templates" | jq -r --arg p "$E2E_PREFIX" \
        'if type=="array" then . else [] end | .[] | select(.name | startswith($p)) | .id' 2>/dev/null | while read -r id; do
        [[ -z "$id" ]] && continue
        curl -sS -o /dev/null -b "$ADMIN_COOKIE" -X DELETE "$BASE_URL/api/requirement-export-templates/$id" || true
        log_dbg "Deleted template $id"
    done

    # Releases created by this driver, resolved by name prefix.
    local releases
    releases=$(api_get "$ADMIN_COOKIE" "/api/releases" 2>/dev/null || echo '[]')
    echo "$releases" | jq -r --arg p "$E2E_PREFIX" \
        '(if type=="array" then . else (.content // .releases // []) end)
         | .[] | select(.name | startswith($p)) | .id' 2>/dev/null | while read -r id; do
        [[ -z "$id" ]] && continue
        curl -sS -o /dev/null -b "$ADMIN_COOKIE" -X DELETE "$BASE_URL/api/releases/$id" || true
        log_dbg "Deleted release $id"
    done
}

# =============================================================================
# Phases
# =============================================================================

phase_seeded_example() {
    phase "1. Seeded example template"

    local body
    body=$(api_get "$ADMIN_COOKIE" "/api/requirement-export-templates/latest")

    if [[ "$(http_status)" == "200" ]]; then
        local name
        name=$(echo "$body" | jq -r '.name // empty')
        log_pass "An ACTIVE template exists, so templateMode=LATEST resolves (name: $name)"
        return
    fi
    if [[ "$(http_status)" != "204" ]]; then
        log_fail "GET /latest returned $(http_status)"
        return
    fi

    # 204 alone does not distinguish a broken seeder from a long-lived instance
    # whose admin removed the example. The seeder installs only into a completely
    # empty table (docs/REQUIREMENT_EXPORT_TEMPLATES.md §7), so the table's own
    # contents are what separates the two.
    local existing count
    existing=$(api_get "$ADMIN_COOKIE" "/api/requirement-export-templates?includeInactive=true")
    count=$(echo "$existing" | jq 'if type=="array" then length else 0 end' 2>/dev/null || echo 0)
    if [[ "$count" -eq 0 ]]; then
        log_fail "No ACTIVE template and the table is empty — the seeder did not install the shipped example"
    else
        log_skip "No ACTIVE template, but $count template(s) exist — the seeder correctly skipped a non-empty table, so seeding cannot be asserted on this instance"
    fi
}

phase_example_download() {
    phase "2. Example template download"

    api_download "$ADMIN_COOKIE" "example.docx" "/api/requirement-export-templates/example"
    if [[ "$(http_status)" != "200" ]]; then
        log_fail "GET /example returned $(http_status)"
        return
    fi

    if unzip -l "$WORK_DIR/example.docx" >/dev/null 2>&1; then
        log_pass "Example template downloads as a readable OOXML package"
    else
        log_fail "Example template is not a valid ZIP/OOXML package"
        return
    fi

    local text; text=$(docx_text "$WORK_DIR/example.docx")
    local missing=0
    for placeholder in '${requirements}' '${releaseName}' '${releaseVersion}' '${releaseDate}' '${releaseStatus}'; do
        if ! grep -qF "$placeholder" <<<"$text"; then
            log_fail "Example template is missing $placeholder"
            missing=1
        fi
    done
    [[ $missing -eq 0 ]] && log_pass "Example template carries the requirements marker and release metadata"
}

phase_validation_negatives() {
    phase "3. Validation rejects unsafe and unusable templates"

    build_macro_docx "$WORK_DIR/macro.docx"
    local status
    status=$(curl -sS -o "$WORK_DIR/validate.json" -w '%{http_code}' -b "$ADMIN_COOKIE" \
        -X POST "$BASE_URL/api/requirement-export-templates/validate" \
        -F "templateFile=@$WORK_DIR/macro.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document" \
        -F "requireRequirementsPlaceholder=true")
    if [[ "$status" == "400" ]]; then
        log_pass "Macro-carrying template rejected"
    else
        log_fail "Macro-carrying template returned $status, expected 400"
    fi

    build_docx "$WORK_DIR/no-marker.docx" "Cover page with no insertion point"
    status=$(curl -sS -o "$WORK_DIR/validate2.json" -w '%{http_code}' -b "$ADMIN_COOKIE" \
        -X POST "$BASE_URL/api/requirement-export-templates/validate" \
        -F "templateFile=@$WORK_DIR/no-marker.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document" \
        -F "requireRequirementsPlaceholder=true")
    if [[ "$status" == "400" ]]; then
        log_pass "Template without an insertion point rejected in strict mode"
    else
        log_fail "Template without an insertion point returned $status, expected 400"
    fi

    status=$(curl -sS -o "$WORK_DIR/validate3.json" -w '%{http_code}' -b "$ADMIN_COOKIE" \
        -X POST "$BASE_URL/api/requirement-export-templates/validate" \
        -F "templateFile=@$WORK_DIR/no-marker.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document" \
        -F "requireRequirementsPlaceholder=false")
    if [[ "$status" == "200" ]]; then
        log_pass "Same template accepted when append mode is chosen"
    else
        log_fail "Append-mode validation returned $status, expected 200"
    fi
}

phase_upload() {
    phase "4. Upload a company template"

    build_docx "$WORK_DIR/company.docx" \
        "$FRONT_MATTER Title: \${documentTitle}" \
        "Release: \${releaseName} / \${releaseVersion} / \${releaseDate} / \${releaseStatus}" \
        "Classification: \${classification}" \
        '${requirements}' \
        "$BACK_MATTER Approval"

    local status
    status=$(curl -sS -o "$WORK_DIR/upload.json" -w '%{http_code}' -b "$ADMIN_COOKIE" \
        -X POST "$BASE_URL/api/requirement-export-templates" \
        -F "templateFile=@$WORK_DIR/company.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document" \
        -F "name=$TEMPLATE_NAME" \
        -F "description=E2E company template" \
        -F "versionLabel=1.0" \
        -F "activate=true" \
        -F "requireRequirementsPlaceholder=true")

    if [[ "$status" != "201" ]]; then
        log_fail "Upload returned $status, expected 201: $(cat "$WORK_DIR/upload.json")"
        return 1
    fi

    CREATED_TEMPLATE_ID=$(jq -r '.id' < "$WORK_DIR/upload.json")
    log_pass "Template uploaded and activated (id $CREATED_TEMPLATE_ID)"

    # LATEST must now resolve to the template we just uploaded.
    local latest
    latest=$(api_get "$ADMIN_COOKIE" "/api/requirement-export-templates/latest")
    if [[ "$(echo "$latest" | jq -r '.id')" == "$CREATED_TEMPLATE_ID" ]]; then
        log_pass "Newly uploaded template is what templateMode=LATEST resolves to"
    else
        log_fail "LATEST did not resolve to the newly activated template"
    fi
}

phase_release_export() {
    phase "5-7. Release export, placement and usage audit"

    if [[ -z "$CREATED_TEMPLATE_ID" ]]; then
        log_skip "No template uploaded — skipping export phase"
        return
    fi

    # A release is what carries the metadata the cover page renders.
    local body
    body=$(api_post "$ADMIN_COOKIE" "/api/releases" "$(jq -n \
        --arg v "$RELEASE_VERSION" --arg n "$RELEASE_NAME" \
        '{version:$v,name:$n,description:"E2E release for template export"}')")

    if [[ "$(http_status)" != "200" && "$(http_status)" != "201" ]]; then
        log_fail "Could not create a release ($(http_status)): $body"
        return
    fi
    CREATED_RELEASE_ID=$(echo "$body" | jq -r '.id')
    log_pass "Created release $RELEASE_VERSION (id $CREATED_RELEASE_ID)"

    api_download "$ADMIN_COOKIE" "export.docx" \
        "/api/requirements/export/docx?releaseId=$CREATED_RELEASE_ID&templateMode=SAVED&templateId=$CREATED_TEMPLATE_ID&classification=Internal"

    if [[ "$(http_status)" != "200" ]]; then
        log_fail "Release export returned $(http_status)"
        return
    fi
    if ! unzip -l "$WORK_DIR/export.docx" >/dev/null 2>&1; then
        log_fail "Exported document is not a readable OOXML package"
        return
    fi
    log_pass "Release exported through the uploaded template"

    local text; text=$(docx_text "$WORK_DIR/export.docx")
    log_dbg "Exported document text:\n$text"

    # 5. Release metadata bound from the entity, not sliced out of the title.
    if grep -qF "$RELEASE_NAME" <<<"$text" && grep -qF "$RELEASE_VERSION" <<<"$text"; then
        log_pass "Release name and version rendered into the document"
    else
        log_fail "Release name/version missing from the exported document"
    fi
    if grep -qE 'Release:.*(PREPARATION|ALIGNMENT|ACTIVE|ARCHIVED)' <<<"$text"; then
        log_pass "Release status rendered (it used to always be blank)"
    else
        log_fail "Release status was not rendered"
    fi
    if grep -qE 'Release:.*[0-9]{4}-[0-9]{2}-[0-9]{2}' <<<"$text"; then
        log_pass "Release date rendered"
    else
        log_fail "Release date was not rendered"
    fi

    # The marker itself must be gone.
    if grep -qF '${requirements}' <<<"$text"; then
        log_fail "The \${requirements} marker survived into the exported document"
    else
        log_pass "Insertion-point marker consumed"
    fi

    # 6. Placement: front matter, then requirement content, then back matter.
    local front_line back_line
    front_line=$(grep -n "$FRONT_MATTER" <<<"$text" | head -1 | cut -d: -f1)
    back_line=$(grep -n "$BACK_MATTER" <<<"$text" | head -1 | cut -d: -f1)
    if [[ -z "$front_line" || -z "$back_line" ]]; then
        log_fail "Template front/back matter did not survive the export"
    elif [[ "$front_line" -lt "$back_line" ]]; then
        log_pass "Template front matter precedes back matter (structure preserved)"
        local req_line
        req_line=$(grep -n 'REQ-' <<<"$text" | head -1 | cut -d: -f1 || true)
        if [[ -z "$req_line" ]]; then
            log_skip "Release contains no requirements — placement not asserted"
        elif [[ "$req_line" -gt "$front_line" && "$req_line" -lt "$back_line" ]]; then
            log_pass "Requirement content rendered BETWEEN front and back matter (in-place insertion)"
        else
            log_fail "Requirement content landed outside the insertion point (line $req_line, front $front_line, back $back_line)"
        fi
    else
        log_fail "Template paragraphs came back out of order"
    fi

    # 7. Usage audit.
    local usage
    usage=$(api_get "$ADMIN_COOKIE" "/api/requirement-export-templates/$CREATED_TEMPLATE_ID/usage")
    if [[ "$(http_status)" == "200" ]] && [[ "$(echo "$usage" | jq 'length')" -gt 0 ]]; then
        log_pass "Export recorded a usage row"
    else
        log_fail "No usage row recorded for the export"
    fi

    local detail
    detail=$(api_get "$ADMIN_COOKIE" "/api/requirement-export-templates/$CREATED_TEMPLATE_ID")
    if [[ "$(echo "$detail" | jq -r '.summary.lastUsedAt // "null"')" != "null" ]]; then
        log_pass "lastUsedAt advanced on the template"
    else
        log_fail "lastUsedAt was not set after an export"
    fi
}

phase_lifecycle() {
    phase "8. Template lifecycle"

    if [[ -z "$CREATED_TEMPLATE_ID" ]]; then
        log_skip "No template uploaded — skipping lifecycle phase"
        return
    fi

    api_post "$ADMIN_COOKIE" "/api/requirement-export-templates/$CREATED_TEMPLATE_ID/deactivate" '{}' >/dev/null
    local detail
    detail=$(api_get "$ADMIN_COOKIE" "/api/requirement-export-templates/$CREATED_TEMPLATE_ID")
    if [[ "$(echo "$detail" | jq -r '.summary.status')" == "INACTIVE" ]]; then
        log_pass "Template deactivated"
    else
        log_fail "Deactivate did not take effect"
    fi

    api_post "$ADMIN_COOKIE" "/api/requirement-export-templates/$CREATED_TEMPLATE_ID/activate" '{}' >/dev/null
    detail=$(api_get "$ADMIN_COOKIE" "/api/requirement-export-templates/$CREATED_TEMPLATE_ID")
    if [[ "$(echo "$detail" | jq -r '.summary.status')" == "ACTIVE" ]]; then
        log_pass "Template reactivated"
    else
        log_fail "Activate did not take effect"
    fi

    # This template has been used by now (phase 7 asserted its usage row), and it is still
    # deleted. Delete used to degrade to a RETIRED status change for any used template, which
    # made removal unreachable: the usage count only grows, so every later delete took the same
    # branch and the row never left the list. The usage rows are detached, not cascade-deleted.
    api_delete "$ADMIN_COOKIE" "/api/requirement-export-templates/$CREATED_TEMPLATE_ID" >/dev/null
    local delete_status="$(http_status)"
    detail=$(api_get "$ADMIN_COOKIE" "/api/requirement-export-templates/$CREATED_TEMPLATE_ID")
    if [[ "$delete_status" == "204" ]] && [[ "$(http_status)" == "404" ]]; then
        log_pass "A used template is deleted outright and is gone"
    else
        log_fail "Delete left the template in an unexpected state (delete=$delete_status, read-back=$(http_status))"
    fi

    local listed
    listed=$(api_get "$ADMIN_COOKIE" "/api/requirement-export-templates?includeInactive=true")
    if [[ "$(echo "$listed" | jq --arg id "$CREATED_TEMPLATE_ID" '[.[] | select(.id == ($id | tonumber))] | length')" == "0" ]]; then
        log_pass "The deleted template no longer appears in the admin list"
    else
        log_fail "The deleted template is still listed"
    fi
    CREATED_TEMPLATE_ID=""
}

phase_authz_negatives() {
    phase "9. Authorization negatives"

    if ! login "$SECMAN_USER_NAME" "$SECMAN_USER_PASS" "$USER_COOKIE"; then
        log_fail "Could not log in as the plain test user"
        return
    fi

    build_docx "$WORK_DIR/user-attempt.docx" '${requirements}'

    local status
    status=$(curl -sS -o /dev/null -w '%{http_code}' -b "$USER_COOKIE" \
        -X POST "$BASE_URL/api/requirement-export-templates" \
        -F "templateFile=@$WORK_DIR/user-attempt.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document" \
        -F "name=${E2E_PREFIX}should-not-exist" \
        -F "activate=true")
    if [[ "$status" == "403" ]]; then
        log_pass "Plain user cannot upload a template"
    else
        log_fail "Plain user upload returned $status, expected 403"
    fi

    api_get "$USER_COOKIE" "/api/requirement-export-templates/example" >/dev/null
    if [[ "$(http_status)" == "403" ]]; then
        log_pass "Plain user cannot download the example template"
    else
        log_fail "Plain user example download returned $(http_status), expected 403"
    fi

    api_post "$USER_COOKIE" "/api/requirement-export-templates/1/activate" '{}' >/dev/null
    if [[ "$(http_status)" == "403" ]]; then
        log_pass "Plain user cannot activate a template"
    else
        log_fail "Plain user activate returned $(http_status), expected 403"
    fi

    api_delete "$USER_COOKIE" "/api/requirement-export-templates/1" >/dev/null
    if [[ "$(http_status)" == "403" ]]; then
        log_pass "Plain user cannot delete a template"
    else
        log_fail "Plain user delete returned $(http_status), expected 403"
    fi
}

# =============================================================================
# Main
# =============================================================================

main() {
    check_prerequisites

    if ! login "$SECMAN_ADMIN_NAME" "$SECMAN_ADMIN_PASS" "$ADMIN_COOKIE"; then
        log_fail "Admin login failed"
        exit 1
    fi

    # Unconditional pre-run cleanup clears leftovers from a crashed earlier run.
    cleanup_testbed || true
    trap cleanup EXIT

    phase_seeded_example
    phase_example_download
    phase_validation_negatives
    phase_upload || true
    phase_release_export
    phase_lifecycle
    phase_authz_negatives

    phase "Summary"
    echo -e "  ${GREEN}Passed:${NC}  $PASS_COUNT" >&2
    echo -e "  ${YELLOW}Skipped:${NC} $SKIP_COUNT" >&2
    echo -e "  ${RED}Failed:${NC}  $FAIL_COUNT" >&2

    [[ $FAIL_COUNT -eq 0 ]]
}

main "$@"
