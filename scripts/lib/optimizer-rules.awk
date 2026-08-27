# optimizer-rules.awk — hot-path performance rules for optimizer-scan.sh.
#
# Loaded after scripts/lib/source-strip.awk, which supplies strip() and
# countBraces():
#
#     awk -v lang=kt -f source-strip.awk -f optimizer-rules.awk FILE
#
# Takes many files in one invocation and emits one finding per line:
#
#     path <TAB> RULE <TAB> line <TAB> human-readable message
#
# Severity is decided by the driver, not here. This file only answers "is the
# pattern present"; whether the change introduced it or merely stood next to
# it is a question about git, not about code.
#
# ---------------------------------------------------------------------------
# Why every rule is scope-aware
# ---------------------------------------------------------------------------
#
# The patterns worth finding are all of the form "X inside Y": a repository
# call inside a loop, a mail send inside a transaction, a timer inside an
# effect with no matching teardown. A line grep can see X but never Y, and X
# on its own is almost always fine — `findByAssetId` is the right call until
# it is the four-thousandth one in a loop. So the file is read into memory and
# walked a second time in END, where a brace-depth walk can bound each scope.
# Files here top out around 2,000 lines; the memory is free and the false
# positives it removes are not.
#
# Every rule below is a pattern verified present in this repo at the time of
# writing, and named in docs/SOURCE_REVIEW_COMPLEXITY_SPEED.md. None of them
# is a general-purpose lint: they encode the specific ways THIS codebase has
# been slow, which is why the list is short and why adding to it should
# require an incident or a review finding rather than a hunch.

BEGIN { N = 0 }

# Each file is analysed when the next one starts, and the last one at END.
# The lexer and the line buffers are per file; carrying either across a file
# boundary makes every finding after it point at the wrong line.
FNR == 1 {
    if (N > 0) analyze()
    resetLexer()
    N = 0
    split("", raw); split("", code)
    curfile = FILENAME
}

{
    N++
    raw[N] = $0
    code[N] = strip($0)
}

END { if (N > 0) analyze() }

function countOpens(s,   t) { t = s; return gsub(/\{/, "{", t) }

# Index of the line where the scope opened at `s` closes again.
#
# "Opened" is counted from the opening braces themselves, not from the net
# depth going positive. A one-line body — `func A() {}`, `if (x) { return }` —
# has a net delta of zero, and treating that as "never opened" makes the scope
# run to end of file, which silently merges every later declaration into the
# match. That bug reported 151 findings in a module with ten locks in it.
#
# A construct that opens no brace within a few lines is not a block at all
# (a single-line `if (x) return`), so it is its own scope end and callers can
# treat the result as an inclusive range either way.
function scopeEnd(s,   i, d, opened) {
    d = 0; opened = 0
    for (i = s; i <= N; i++) {
        if (countOpens(code[i]) > 0) opened = 1
        d += countBraces(code[i])
        if (opened && d <= 0) return i
        if (!opened && i > s + 2) return s
    }
    return N
}

# True when any line in [a,b] matches re. Used for "does this transaction
# reach an HTTP call", where the answer is yes if it happens anywhere inside.
#
# `re` is a STRING, never a /literal/. awk evaluates a regex literal in an
# argument position as a match against $0 and passes the resulting 0 or 1, so
# `bodyHas(a, b, /\.Lock\(\)/)` silently tests every line against "0" — which
# matches anything containing a zero. It reads correctly and is wrong.
function bodyHas(a, b, re,   i) {
    for (i = a; i <= b; i++) if (code[i] ~ re) return 1
    return 0
}

# First line in [a,b] matching re, so a finding can point at the offending
# statement rather than at the enclosing loop the reader already sees.
function bodyFind(a, b, re,   i) {
    for (i = a; i <= b; i++) if (code[i] ~ re) return i
    return 0
}

function countIn(a, b, re,   i, n) {
    n = 0
    for (i = a; i <= b; i++) if (code[i] ~ re) n++
    return n
}

# An annotation sits above the declaration it applies to, possibly with other
# annotations and modifiers between. Walk back over those, and nothing else.
function annotatedWith(i, re,   p) {
    p = i - 1
    while (p >= 1 && (raw[p] ~ /^[[:space:]]*@/ || raw[p] ~ /^[[:space:]]*$/ || isCommentLine(raw[p]))) {
        if (raw[p] ~ re) return 1
        p--
    }
    return 0
}

function emit(rule, line, msg) { printf "%s\t%s\t%d\t%s\n", curfile, rule, line, msg }

function analyze(   i, c, e, b, n, v, u, span, goFuncEnd, BLOCKING) {
    # Blocking work that must not happen while a DB transaction (and its
    # pooled connection) is held. SMTP and outbound HTTP are the two that have
    # actually bitten here — NormMappingService held a transaction across an
    # OpenRouter call, and eleven services still send mail inside one.
    BLOCKING = "(httpClient|\\.exchange\\(|\\.retrieve\\(|mailSender|sendEmail|sendMail|Thread\\.sleep|HttpRequest\\.|WebClient|okhttp|callOpenRouter|s3Client|restTemplate)"

    for (i = 1; i <= N; i++) {
        c = code[i]
        if (c == "") continue

        # --- Kotlin / Java ---------------------------------------------------

        if (lang == "kt" || lang == "java") {
            # Materializing a whole table to discard most of it. The repo has a
            # projection or a derived query for every one of these; the ones
            # left are the ones nobody measured.
            if (c ~ /\.findAll\(\)[[:space:]]*$/ && (i < N && code[i+1] ~ /^[[:space:]]*\.(filter|map|mapNotNull|count|any|none|firstOrNull|find|sortedBy|groupBy|associate)/))
                emit("FETCH-ALL-FILTER", i, "findAll() materialized then filtered in memory — push the predicate into the query")
            else if (c ~ /\.findAll\(\)[[:space:]]*\.(filter|map|mapNotNull|count|any|none|firstOrNull|find|sortedBy|groupBy|associate)/)
                emit("FETCH-ALL-FILTER", i, "findAll() materialized then filtered in memory — push the predicate into the query")

            if (c ~ /Pageable\.UNPAGED/)
                emit("UNPAGED", i, "unpaged query — the row count is whatever production grew to, not what the test had")

            # A transaction is a lease on a pooled connection. Anything with a
            # network timeout inside one converts a slow dependency into pool
            # exhaustion for everybody.
            if (c ~ /(^|[^A-Za-z0-9_.])fun[[:space:]]+[A-Za-z_`]/ && annotatedWith(i, "@Transactional")) {
                e = scopeEnd(i)
                b = bodyFind(i, e, BLOCKING)
                if (b) emit("TXN-BLOCKING-IO", b, "blocking call inside a @Transactional body — holds a DB connection for the length of the network timeout")
            }

            # N+1: one statement per element, inside the loop that has the
            # elements. The batch query almost always already exists.
            if (c ~ /(^|[^A-Za-z0-9_])(for|while)[[:space:]]*\(/ || c ~ /\.forEach[[:space:]]*[({]/) {
                e = scopeEnd(i)
                if (e > i) {
                    b = bodyFind(i + 1, e, "[Rr]epository\\.(find|count|exists|delete|save)")
                    if (b) emit("QUERY-IN-LOOP", b, "repository call inside a loop — one round-trip per element; batch it")
                }
            }
        }

        # --- Go ---------------------------------------------------------------

        if (lang == "go") {
            if (c ~ /^func[[:space:]]/) goFuncEnd = scopeEnd(i)

            # Only the span the lock is actually held for. Asking merely
            # whether the function contains both a Lock() and a Marshal()
            # reports every function that releases the lock first, which in
            # this module is most of them.
            if (c ~ /\.Lock\(\)/) {
                if (i + 1 <= N && code[i+1] ~ /defer[[:space:]].*\.Unlock\(\)/) {
                    span = goFuncEnd
                } else {
                    u = bodyFind(i + 1, goFuncEnd, "\\.Unlock\\(\\)")
                    span = (u ? u - 1 : goFuncEnd)
                }
                b = bodyFind(i + 1, span, "(json\\.Marshal|os\\.WriteFile|\\.Sync\\(\\)|os\\.Rename|io\\.Copy)")
                if (b) emit("LOCK-IO", b, "serialization or file I/O while a mutex is held — every reader queues behind it")
            }
        }

        # --- TypeScript / React / Astro ---------------------------------------

        if (lang == "ts") {
            # Serial round-trips. The per-call try/catch that usually surrounds
            # these is deliberate isolation and must survive parallelization —
            # Promise.allSettled keeps it, Promise.all does not.
            if (c ~ /(^|[^A-Za-z0-9_$])(for|while)[[:space:]]*\(/ || c ~ /\.(forEach|map)[[:space:]]*\([[:space:]]*async/) {
                e = scopeEnd(i)
                if (e > i) {
                    b = bodyFind(i + 1, e, "(^|[^A-Za-z0-9_$])await[[:space:]]")
                    if (b) emit("AWAIT-IN-LOOP", b, "awaited call inside a loop — latency is the sum, not the max; batch or Promise.allSettled")
                }
            }

            if (c ~ /(function[[:space:]]|=>[[:space:]]*\{|=[[:space:]]*async)/ ) {
                e = scopeEnd(i)
                if (e > i + 2) {
                    n = countIn(i + 1, e, "^[[:space:]]*(const|let|var)?[[:space:]]*[A-Za-z_${[][^;]*=[[:space:]]*await[[:space:]]")
                    if (n >= 4 && !bodyHas(i, e, "Promise\\.(all|allSettled|race)"))
                        emit("SERIAL-AWAITS", i, n " independent awaits in sequence with no Promise.allSettled — total latency is their sum")
                }
            }

            # The cleanup has to clear the handle the effect created. Closing
            # over a state variable that was null at mount does not, and the
            # timer outlives the component — a leak that only shows up as a
            # page that gets slower the longer it is open.
            if (c ~ /useEffect[[:space:]]*\(/) {
                e = scopeEnd(i)
                if (bodyHas(i, e, "set(Interval|Timeout)[[:space:]]*\\(") && !bodyHas(i, e, "clear(Interval|Timeout)[[:space:]]*\\("))
                    emit("TIMER-LEAK", i, "timer started in an effect with no clearInterval/clearTimeout in its cleanup — it survives unmount")
            }

            # Fetching a whole table to fill a <select>.
            if (match(c, /(pageSize|limit|size|perPage|pageLimit)[=:][[:space:]]*[0-9]+/)) {
                v = substr(c, RSTART, RLENGTH)
                sub(/^[^0-9]*/, "", v)
                if (v + 0 >= 500)
                    emit("WIDE-FETCH", i, "requests " v " rows in one call — a typeahead or a real page size belongs here")
            }
        }

        # Islands hydrate in source order before first paint. A management
        # screen the user has not scrolled to yet does not need to.
        if (isastro && c ~ /client:load/)
            emit("EAGER-ISLAND", i, "client:load hydrates before first paint — client:visible or client:idle unless the user interacts immediately")
    }
}
