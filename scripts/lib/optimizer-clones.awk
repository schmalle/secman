# optimizer-clones.awk — copy-paste detector for optimizer-scan.sh.
#
# Loaded after scripts/lib/source-strip.awk, which supplies strip():
#
#     awk -v lang=kt -v W=6 -f source-strip.awk -f optimizer-clones.awk FILE
#
# Emits one record per candidate window:
#
#     path <TAB> hash <TAB> firstSourceLine <TAB> lastSourceLine
#
# Takes many files in one invocation — the corpus is the whole repo, and
# spawning an awk per file costs more than the hashing does.
#
# The driver groups windows by hash across every file in scope, merges the
# overlapping ones back into blocks, and reports a block that appears in two
# or more places. Doing the grouping there rather than here is what lets a
# clone be found ACROSS files — this program only ever sees one file.
#
# ---------------------------------------------------------------------------
# What counts as "the same code"
# ---------------------------------------------------------------------------
#
# Byte-identical matching finds almost nothing: real copy-paste is edited
# afterwards, and a fork whose strings and indentation drifted is still a
# fork. So a line is reduced to its shape before hashing — strip() removes
# comments and blanks string literals, then whitespace is collapsed. Two
# blocks that differ only in their labels, messages or formatting therefore
# hash the same, which is the near-miss case worth reporting.
#
# Identifiers are deliberately NOT normalized. Doing that makes every pair of
# structurally similar functions a "clone" and buries the real findings — the
# detector would report similarity, and similarity is not a defect.
#
# Lines that are only punctuation (`}`, `);`, `},`) are dropped rather than
# hashed. A run of six closing braces is identical in every file ever written,
# and including them manufactures clone groups out of indentation.
#
# Import and package lines go the same way. Every controller in this repo opens
# with twenty near-identical `import io.micronaut...` lines; before they were
# dropped, the detector's loudest finding was that Kotlin files import Kotlin.
# That is a real similarity and a useless one — nobody deduplicates an import
# block, and reporting it trains the reader to skim past DUP-BLOCK.
#
# ---------------------------------------------------------------------------
# Hashing
# ---------------------------------------------------------------------------
#
# Two independent polynomial hashes, concatenated. No bitwise operators, so
# this behaves identically under macOS's one-true-awk and GNU awk — `xor()`
# exists only in the latter, and a rule that silently does nothing on the
# maintainer's laptop is the worst failure a gate can have.
#
# 62 bits of key over the ~130k windows this repo produces makes an accidental
# pairing vanishingly unlikely, so the driver does not re-verify group text.

# Hashes one line into the globals HA/HB. Called once per line, never once per
# window: hashing the full window text each time re-reads every line W times,
# which was most of the scan's wall clock. Window hashes are then folded from
# the line hashes, which costs W multiplications instead of W x line-length
# character lookups.
#
# ORD is a lookup table rather than index(CHARS, c) for the same reason — that
# call scans a 95-character string for every character of every line.
function hline(s,   i, a, b) {
    a = 7; b = 17
    for (i = 1; i <= length(s); i++) {
        c = ORD[substr(s, i, 1)]
        a = (a * 31 + c) % 2147483647
        b = (b * 131 + c) % 2147483629
    }
    HA = a; HB = b
}

BEGIN {
    CHARS = " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
    for (i = 1; i <= length(CHARS); i++) ORD[substr(CHARS, i, 1)] = i
    if (W + 0 < 3) W = 6
}

FNR == 1 { resetLexer(); n = 0; split("", la); split("", lb); split("", at) }

{
    if ($0 ~ /^[[:space:]]*(import|package|from|export[[:space:]]+\*)[[:space:]]/) next
    code = strip($0)
    sub(/^[ \t]+/, "", code)
    sub(/[ \t]+$/, "", code)
    gsub(/[ \t]+/, " ", code)
    if (code == "") next
    if (code ~ /^[]{}();,]+$/) next

    n++
    hline(code)
    la[n] = HA; lb[n] = HB
    at[n] = FNR

    if (n >= W) {
        wa = 7; wb = 17
        for (i = n - W + 1; i <= n; i++) {
            wa = (wa * 1000003 + la[i]) % 2147483647
            wb = (wb * 1000033 + lb[i]) % 2147483629
        }
        printf "%s\t%010d%010d\t%d\t%d\n", FILENAME, wa, wb, at[n - W + 1], FNR
    }
}
