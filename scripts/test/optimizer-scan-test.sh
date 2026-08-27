#!/usr/bin/env bash
#
# optimizer-scan-test.sh — self-test for scripts/optimizer-scan.sh.
#
# A scanning gate fails in two directions and only one of them is visible. A
# rule that stopped firing looks exactly like a clean repo, so the codebase
# drifts while the gate reports green. Every rule therefore gets both halves:
#
#   FIRES   — code that violates the rule must produce the finding
#   SILENT  — code that respects it must produce nothing
#
# The SILENT cases carry most of the weight. Each one is a false positive this
# scanner actually produced during development: a `findAll()` whose result is
# genuinely all of a small config table, a lock released before the file write,
# a loop whose awaits are already inside Promise.allSettled, an import block
# that every controller in the repo shares. A rule quietly widened back over
# any of them is how a gate becomes noise, and noise is how a gate becomes
# ignored.
#
# Exit codes:  0 = all assertions pass   1 = an assertion failed   2 = environment error
#
# Usage: ./scripts/test/optimizer-scan-test.sh [--verbose]

set -uo pipefail

VERBOSE=0
[ "${1:-}" = "--verbose" ] && VERBOSE=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STRIP="$REPO_ROOT/scripts/lib/source-strip.awk"
RULES="$REPO_ROOT/scripts/lib/optimizer-rules.awk"
CLONES="$REPO_ROOT/scripts/lib/optimizer-clones.awk"
SCAN="$REPO_ROOT/scripts/optimizer-scan.sh"
for f in "$STRIP" "$RULES" "$CLONES"; do
    [ -f "$f" ] || { echo "FATAL: missing $f" >&2; exit 2; }
done
[ -x "$SCAN" ] || { echo "FATAL: scanner not executable at $SCAN" >&2; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0

run_rules() {   # run_rules <lang> <file> [isastro]
    awk -v lang="$1" -v isastro="${3:-0}" -f "$STRIP" -f "$RULES" "$2"
}

# Asserts a rule DOES trigger. The failure message prints what came back, so a
# broken fixture is distinguishable from a broken rule.
fires() {       # fires <name> <lang> <file> <rule>
    local out
    out="$(run_rules "$2" "$3")"
    if printf '%s\n' "$out" | grep -q "	$4	"; then
        PASS=$((PASS + 1)); [ "$VERBOSE" -eq 1 ] && echo "  ok   FIRES  $1"
    else
        FAIL=$((FAIL + 1)); echo "  FAIL FIRES  $1 — expected $4, got: ${out:-<nothing>}"
    fi
    return 0
}

# Asserts a rule does NOT trigger. These guard against a widened rule, which is
# the failure mode that hides rather than shouts.
silent() {      # silent <name> <lang> <file> <rule>
    local out
    out="$(run_rules "$2" "$3" | grep "	$4	" || true)"
    if [ -z "$out" ]; then
        PASS=$((PASS + 1)); [ "$VERBOSE" -eq 1 ] && echo "  ok   SILENT $1"
    else
        FAIL=$((FAIL + 1)); echo "  FAIL SILENT $1 — unexpected: $out"
    fi
    return 0
}

# For assertions that are not a single rule lookup — exit codes, window counts,
# batching equivalence. Takes the result of the test command as $3.
ok() {          # ok <name> <condition-description> <0|1>
    if [ "$3" -eq 0 ]; then
        PASS=$((PASS + 1)); [ "$VERBOSE" -eq 1 ] && echo "  ok   $1"
    else
        FAIL=$((FAIL + 1)); echo "  FAIL $1 — $2"
    fi
    return 0
}

echo "optimizer-scan-test: hot-path rules"

# --- FETCH-ALL-FILTER --------------------------------------------------------

cat > "$WORK/fetch.kt" <<'EOF'
class S {
    fun bad(): List<Asset> {
        return assetRepository.findAll().filter { it.owner == "x" }
    }

    fun badMultiline(): List<Long> {
        return assetRepository.findAll()
            .map { it.id }
    }

    fun fine(): List<Asset> {
        return assetRepository.findByOwner("x")
    }

    fun alsoFine(): List<Config> {
        return configRepository.findAll()
    }
}
EOF
fires  "findAll().filter on one line"     kt "$WORK/fetch.kt" FETCH-ALL-FILTER
# A plain findAll() with no in-memory pass over it is how you read a config
# table, and flagging it would put the rule on every repository in the repo.
cat > "$WORK/fetchclean.kt" <<'EOF'
class S {
    fun fine(): List<Config> = configRepository.findAll()
}
EOF
silent "bare findAll() is not a finding"  kt "$WORK/fetchclean.kt" FETCH-ALL-FILTER

# --- UNPAGED -----------------------------------------------------------------

cat > "$WORK/unpaged.kt" <<'EOF'
class S {
    fun bad() = repo.findAll(Pageable.UNPAGED)
    fun good() = repo.findAll(Pageable.from(0, 50))
}
EOF
fires  "Pageable.UNPAGED"                 kt "$WORK/unpaged.kt" UNPAGED

cat > "$WORK/paged.kt" <<'EOF'
class S {
    fun good() = repo.findAll(Pageable.from(0, 50))
}
EOF
silent "a real page size"                 kt "$WORK/paged.kt" UNPAGED

# --- TXN-BLOCKING-IO ---------------------------------------------------------

cat > "$WORK/txn.kt" <<'EOF'
class S {
    @Transactional
    fun bad(id: Long) {
        val r = repo.findById(id)
        val suggestion = callOpenRouter(r)
        repo.save(r)
    }

    @Transactional
    fun good(id: Long) {
        val r = repo.findById(id)
        repo.save(r)
    }

    fun alsoGood(id: Long) {
        val suggestion = callOpenRouter(id)
    }
}
EOF
fires  "HTTP call inside @Transactional"  kt "$WORK/txn.kt" TXN-BLOCKING-IO

cat > "$WORK/txnclean.kt" <<'EOF'
class S {
    fun outsideTransaction(id: Long) {
        val suggestion = callOpenRouter(id)
        mailSender.send(suggestion)
    }

    @Transactional
    fun onlyDb(id: Long) {
        repo.save(repo.findById(id))
    }
}
EOF
silent "the same call with no transaction" kt "$WORK/txnclean.kt" TXN-BLOCKING-IO

# --- QUERY-IN-LOOP -----------------------------------------------------------

cat > "$WORK/nplus1.kt" <<'EOF'
class S {
    fun bad(ids: List<Long>) {
        for (id in ids) {
            val u = userRepository.findByIdWithWorkgroups(id)
            u.touch()
        }
    }

    fun good(ids: List<Long>) {
        val users = userRepository.findByIdIn(ids)
        for (u in users) {
            u.touch()
        }
    }
}
EOF
fires  "repository call inside a for loop" kt "$WORK/nplus1.kt" QUERY-IN-LOOP

cat > "$WORK/batched.kt" <<'EOF'
class S {
    fun good(ids: List<Long>) {
        val users = userRepository.findByIdIn(ids)
        for (u in users) {
            u.touch()
        }
    }
}
EOF
silent "one batch query, then the loop"   kt "$WORK/batched.kt" QUERY-IN-LOOP

# --- AWAIT-IN-LOOP / SERIAL-AWAITS -------------------------------------------

cat > "$WORK/loop.ts" <<'EOF'
async function bad(ids: number[]) {
    for (const id of ids) {
        const r = await fetch(`/api/x/${id}`);
        use(r);
    }
}
EOF
fires  "await inside a for-of loop"       ts "$WORK/loop.ts" AWAIT-IN-LOOP

cat > "$WORK/parallel.ts" <<'EOF'
async function good(ids: number[]) {
    const results = await Promise.allSettled(ids.map((id) => fetch(`/api/x/${id}`)));
    use(results);
}
EOF
silent "Promise.allSettled over the ids"  ts "$WORK/parallel.ts" AWAIT-IN-LOOP

cat > "$WORK/serial.ts" <<'EOF'
async function load() {
    const a = await fetchA();
    const b = await fetchB();
    const c = await fetchC();
    const d = await fetchD();
    render(a, b, c, d);
}
EOF
fires  "four independent serial awaits"   ts "$WORK/serial.ts" SERIAL-AWAITS

cat > "$WORK/settled.ts" <<'EOF'
async function load() {
    const [a, b, c, d] = await Promise.allSettled([
        fetchA(),
        fetchB(),
        fetchC(),
        fetchD(),
    ]);
    render(a, b, c, d);
}
EOF
silent "the same four, already settled"   ts "$WORK/settled.ts" SERIAL-AWAITS

# --- TIMER-LEAK --------------------------------------------------------------

cat > "$WORK/timer.tsx" <<'EOF'
function C() {
    useEffect(() => {
        const id = setInterval(poll, 30000);
    }, []);
    return null;
}
EOF
fires  "interval with no teardown"        ts "$WORK/timer.tsx" TIMER-LEAK

cat > "$WORK/timerok.tsx" <<'EOF'
function C() {
    useEffect(() => {
        const id = setInterval(poll, 30000);
        return () => clearInterval(id);
    }, []);
    return null;
}
EOF
silent "interval cleared in the cleanup"  ts "$WORK/timerok.tsx" TIMER-LEAK

# --- LOCK-IO -----------------------------------------------------------------

cat > "$WORK/lock.go" <<'EOF'
package p

func Bad(r *Registry) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	raw, err := json.Marshal(r.state)
	if err != nil {
		return err
	}
	return os.WriteFile(r.path, raw, 0o600)
}
EOF
fires  "marshal+write under a held lock"  go "$WORK/lock.go" LOCK-IO

cat > "$WORK/lockok.go" <<'EOF'
package p

func Good(r *Registry) error {
	r.mu.Lock()
	snap := r.state
	r.mu.Unlock()
	raw, err := json.Marshal(snap)
	if err != nil {
		return err
	}
	return os.WriteFile(r.path, raw, 0o600)
}
EOF
silent "lock released before the write"   go "$WORK/lockok.go" LOCK-IO

# --- WIDE-FETCH / EAGER-ISLAND -----------------------------------------------

cat > "$WORK/wide.ts" <<'EOF'
const r = await get("/api/assets", { pageSize: 10000 });
const s = await get("/api/assets", { pageSize: 50 });
EOF
fires  "pageSize 10000"                   ts "$WORK/wide.ts" WIDE-FETCH

cat > "$WORK/narrow.ts" <<'EOF'
const s = await get("/api/assets", { pageSize: 50 });
EOF
silent "a real page size"                 ts "$WORK/narrow.ts" WIDE-FETCH

cat > "$WORK/island.astro" <<'EOF'
<Management client:load />
EOF
out="$(run_rules ts "$WORK/island.astro" 1)"
printf '%s\n' "$out" | grep -q "	EAGER-ISLAND	"; ok "FIRES  client:load in an astro page" "expected EAGER-ISLAND, got: ${out:-<nothing>}" $?

cat > "$WORK/island2.astro" <<'EOF'
<Sidebar client:visible />
EOF
out="$(run_rules ts "$WORK/island2.astro" 1 | grep "	EAGER-ISLAND	" || true)"
[ -z "$out" ]; ok "SILENT client:visible" "unexpected: $out" $?

# --- Comments and strings are not code ---------------------------------------
#
# The lexer is shared with humanizer-scan.sh; this asserts optimizer-scan.sh
# is actually using it. Without it every prose mention of a pattern is a
# finding, which is the fastest possible way to make a gate untrustworthy.

cat > "$WORK/prose.kt" <<'EOF'
class S {
    // Never call assetRepository.findAll().filter { } on the hot path.
    fun fine(): String {
        return "use assetRepository.findAll().filter { it.x } instead"
    }
}
EOF
silent "the pattern named in a comment"   kt "$WORK/prose.kt" FETCH-ALL-FILTER

echo "optimizer-scan-test: clone detection"

# --- Clone engine ------------------------------------------------------------

run_clones() { awk -v lang="$1" -v W="${3:-6}" -f "$STRIP" -f "$CLONES" "$2"; }

cat > "$WORK/dup.ts" <<'EOF'
function first() {
    const a = compute(1);
    const b = compute(2);
    const c = combine(a, b);
    const d = normalize(c);
    const e = validate(d);
    return persist(e);
}

function second() {
    const a = compute(1);
    const b = compute(2);
    const c = combine(a, b);
    const d = normalize(c);
    const e = validate(d);
    return persist(e);
}
EOF
repeated=$(run_clones ts "$WORK/dup.ts" | cut -f2 | sort | uniq -d | wc -l | tr -d ' ')
[ "$repeated" -ge 1 ]; ok "FIRES  a repeated block hashes to the same window" "no repeated window hash found" $?

cat > "$WORK/nodup.ts" <<'EOF'
function first() {
    const a = compute(1);
    const b = transform(a);
    return persist(b);
}

function second() {
    const x = loadConfig();
    const y = merge(x, defaults);
    return emit(y);
}
EOF
repeated=$(run_clones ts "$WORK/nodup.ts" | cut -f2 | sort | uniq -d | wc -l | tr -d ' ')
[ "$repeated" -eq 0 ]; ok "SILENT two different functions" "reported $repeated duplicate window hashes" $?

# Import blocks are the one similarity nobody acts on, and before they were
# excluded they were the detector's loudest finding.
cat > "$WORK/imports_a.kt" <<'EOF'
package com.secman.controller

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import jakarta.inject.Inject
import org.slf4j.LoggerFactory

class A { fun a() = 1 }
EOF
cat > "$WORK/imports_b.kt" <<'EOF'
package com.secman.controller

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import jakarta.inject.Inject
import org.slf4j.LoggerFactory

class B { fun b() = 2 }
EOF
repeated=$(awk -v lang=kt -v W=6 -f "$STRIP" -f "$CLONES" "$WORK/imports_a.kt" "$WORK/imports_b.kt" \
    | cut -f2 | sort | uniq -d | wc -l | tr -d ' ')
[ "$repeated" -eq 0 ]; ok "SILENT a shared import block" "reported $repeated duplicate window hashes" $?

# Feeding several files to one process must behave like scanning them singly.
alone=$(awk -v lang=ts -v W=6 -f "$STRIP" -f "$CLONES" "$WORK/dup.ts" | wc -l | tr -d ' ')
batched=$(awk -v lang=ts -v W=6 -f "$STRIP" -f "$CLONES" "$WORK/dup.ts" "$WORK/nodup.ts" \
    | awk -F'\t' -v p="$WORK/dup.ts" '$1 == p' | wc -l | tr -d ' ')
[ "$alone" = "$batched" ]; ok "batching a file does not change its windows" "single=$alone batched=$batched" $?

echo "optimizer-scan-test: severity model"

REPO="$WORK/repo"
mkdir -p "$REPO/scripts/lib" "$REPO/src"
cp "$SCAN" "$REPO/scripts/" && cp "$STRIP" "$RULES" "$CLONES" "$REPO/scripts/lib/"
git -C "$REPO" init -q .
git -C "$REPO" -c user.email=t@t -c user.name=t commit -q --allow-empty -m init

cp "$WORK/nplus1.kt" "$REPO/src/New.kt"
out="$("$REPO/scripts/optimizer-scan.sh" --base HEAD --no-clones 2>&1)"; rc=$?
{ [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q "BLOCK"; }
ok "FIRES  new code blocks and exits 1" "rc=$rc out=$out" $?

git -C "$REPO" add -A
git -C "$REPO" -c user.email=t@t -c user.name=t commit -q -m legacy
printf '\n// A new comment on inherited code.\n' >> "$REPO/src/New.kt"

out="$("$REPO/scripts/optimizer-scan.sh" --base HEAD --no-clones 2>&1)"; rc=$?
{ [ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q "REVIEW"; }
ok "SILENT inherited findings review and exit 0" "rc=$rc out=$out" $?

out="$("$REPO/scripts/optimizer-scan.sh" --base HEAD --no-clones --strict 2>&1)"; rc=$?
[ "$rc" -eq 1 ]
ok "--strict makes REVIEW exit 1" "rc=$rc" $?

# Pasting an existing block into a NEW file must be found. This is the case
# that fails if the clone corpus is narrowed to the changed files.
sed 's/function first/function third/' "$WORK/dup.ts" > "$REPO/src/Pasted.ts"
cp "$WORK/dup.ts" "$REPO/src/Origin.ts"
git -C "$REPO" add -A
git -C "$REPO" -c user.email=t@t -c user.name=t commit -q -m origin
sed 's/function first/function fourth/' "$WORK/dup.ts" > "$REPO/src/Fresh.ts"
out="$("$REPO/scripts/optimizer-scan.sh" --base HEAD --only-clones --min-clone 6 2>&1)"
printf '%s' "$out" | grep -q "DUP-BLOCK"
ok "FIRES  a block pasted from elsewhere in the repo" "got: $out" $?

echo

if [ "$FAIL" -gt 0 ]; then
    echo "optimizer-scan-test: FAILED — $FAIL of $((PASS + FAIL)) assertions failed"
    exit 1
fi
echo "optimizer-scan-test: OK — $PASS assertions passed"
exit 0
