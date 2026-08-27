# source-strip.awk — the shared source lexer for the repo's scanning gates.
#
# Loaded alongside a rule file, never on its own:
#
#     awk -v lang=kt -f scripts/lib/source-strip.awk -f scripts/lib/<rules>.awk FILE
#
# Both `humanizer-scan.sh` (structure and naming) and `optimizer-scan.sh`
# (hot-path performance and copy-paste blocks) have to answer the same
# question before they can answer their own: which characters on this line are
# actually code? A `findAll()` inside a comment is not a query, and a
# `println("}")` that counts as a closing brace desynchronises every function
# boundary after it. Getting that wrong is the failure mode that makes a
# scanner quietly measure the wrong thing rather than visibly break.
#
# It lives here because it was already hardened once, against real files in
# this repo: bash heredocs, awk programs embedded in single quotes, TypeScript
# template literals, Kotlin triple-quoted strings. A second copy would drift
# from that hardening, and the second copy would then be wrong in ways nobody
# notices — which is exactly the defect optimizer-scan.sh exists to find.
#
# Contract: reads the global `lang` (kt java ts go py sh) set by the driver
# with -v. Carries multi-line literal state across records in globals
# (inblock, inraw, intriple, inhere and their tokens), so a file must be fed
# to one awk process start to finish.
#
# This is a heuristic lexer, not a compiler front end. It handles the cases
# that occur; genuinely adversarial code will mis-measure. That is an accepted
# trade — a wrong finding costs an agent seconds to dismiss, whereas a real
# parser per language is a project of its own.

function isBraceLang() { return lang != "py" }

function hasBlockComments() { return (lang != "py" && lang != "sh") }

# Remove the things that contain braces but are not structure: block comments,
# triple-quoted blocks, string literals, and trailing line comments. Without
# this a single `println("}")` desynchronises every function length after it.
function strip(s,   i, j, rest, n) {
    # A literal that runs past end of line — a heredoc, an embedded awk
    # program in single quotes, a TS template literal — is the thing that
    # silently desynchronises brace counting for every function after it.
    # Each of these blocks closes one such hole.
    if (inhere) {
        if (s ~ ("^[[:space:]]*" hereTok "[[:space:]]*$")) inhere = 0
        return ""
    }
    if (inraw) {
        i = index(s, rawTok)
        if (i == 0) return ""
        inraw = 0
        s = substr(s, i + length(rawTok))
    }
    if (intriple) {
        i = index(s, tripleTok)
        if (i == 0) return ""
        intriple = 0
        s = substr(s, i + length(tripleTok))
    }
    if (inblock && hasBlockComments()) {
        i = index(s, "*/")
        if (i == 0) return ""
        inblock = 0
        s = substr(s, i + 2)
    }
    while (hasBlockComments() && (i = index(s, "/*")) > 0) {
        rest = substr(s, i + 2)
        j = index(rest, "*/")
        if (j == 0) { inblock = 1; s = substr(s, 1, i - 1); break }
        s = substr(s, 1, i - 1) " " substr(rest, j + 2)
    }
    if (lang == "kt" || lang == "py") {
        if ((i = index(s, "\"\"\"")) > 0) {
            rest = substr(s, i + 3)
            if (index(rest, "\"\"\"") == 0) { intriple = 1; tripleTok = "\"\"\""; s = substr(s, 1, i - 1) }
        }
    }
    if (lang == "sh" && match(s, /<<-?[[:space:]]*['"'"'"]?[A-Za-z_][A-Za-z0-9_]*/)) {
        hereTok = substr(s, RSTART, RLENGTH)
        sub(/^<<-?[[:space:]]*['"'"'"]?/, "", hereTok)
        inhere = 1
        s = substr(s, 1, RSTART - 1)
    }

    gsub(/"(\\.|[^"\\])*"/, "\"\"", s)
    gsub(/'(\\.|[^'\\])*'/, "''", s)
    if (lang == "ts" || lang == "java" || lang == "go") gsub(/`(\\.|[^`\\])*`/, "``", s)

    if (lang == "py" || lang == "sh") { if ((i = index(s, "#")) > 0) s = substr(s, 1, i - 1) }
    else { if ((i = index(s, "//")) > 0) s = substr(s, 1, i - 1) }

    # Whatever delimiter is left over unpaired opens a multi-line literal.
    if (lang == "ts" || lang == "go" || lang == "java") { n = gsub(/`/, "`", s); if (n % 2 == 1) { inraw = 1; rawTok = "`"; sub(/`[^`]*$/, "", s) } }
    if (lang == "sh") {
        n = gsub(/'/, "'", s); if (n % 2 == 1) { inraw = 1; rawTok = "'"; sub(/'[^']*$/, "", s) }
        else { n = gsub(/"/, "\"", s); if (n % 2 == 1) { inraw = 1; rawTok = "\""; sub(/"[^"]*$/, "", s) } }
    }
    return s
}

function isCommentLine(s) {
    if (lang == "py" || lang == "sh") return (s ~ /^[[:space:]]*#/)
    return (s ~ /^[[:space:]]*(\/\/|\/\*|\*)/)
}

# Net brace delta of a line. Only meaningful on stripped code — on a raw line a
# brace inside a string or a comment counts, and every function boundary after
# it is wrong. Kept beside strip() for that reason.
function countBraces(s,   i, c, d) {
    d = 0
    for (i = 1; i <= length(s); i++) { c = substr(s, i, 1); if (c == "{") d++; else if (c == "}") d-- }
    return d
}

# Multi-line literal state is per file. A driver that feeds several files to
# one awk process (which it should — 1,500 process spawns is most of a scan's
# wall clock) must call this at FNR == 1, or an unterminated heredoc in one
# file swallows the beginning of the next.
function resetLexer() { inblock = 0; inraw = 0; intriple = 0; inhere = 0; rawTok = ""; tripleTok = ""; hereTok = "" }

