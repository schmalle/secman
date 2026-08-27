# humanizer-rules.awk — per-file structure and naming rules for humanizer-scan.sh.
#
# Called once per source file. Emits one finding per line:
#
#     RULE <TAB> line <TAB> human-readable message
#
# Severity is NOT decided here. The driver looks the reported line up in the
# set of lines this change added and turns "you wrote it" into BLOCK and
# "it was already there" into REVIEW. Keeping that split means this file only
# has to answer "is the rule violated", never "whose fault is it".
#
# Inputs (-v):  path, lang, maxfile, maxfunc, warnfunc
# lang is one of: kt java ts go py sh
#
# On parsing: this is a heuristic scanner, not a compiler front end. It strips
# strings and comments before counting braces, which handles the cases that
# actually occur; it will mis-measure genuinely adversarial code. That is an
# accepted trade — a wrong length shows up as a finding the agent can dismiss
# in seconds, whereas a real parser per language is a project of its own.

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

# A declaration counts as documented when the nearest thing above it, ignoring
# annotations and blank lines, is a comment. That is the shape a human writes;
# requiring a specific KDoc/JSDoc form would just push people to write empty
# ones to satisfy the checker.
function isAnnotationOrModifier(s) {
    if (s ~ /^[[:space:]]*@/) return 1
    if (s ~ /^[[:space:]]*(public|private|protected|internal|open|abstract|final|override|suspend|inline|static|async|export|default)[[:space:]]*$/) return 1
    return 0
}

function funcStart(s,   name) {
    if (s ~ /^[[:space:]]*(\}|\)|\]|else|catch|finally)/) return ""
    if (lang == "kt") {
        if (s ~ /(^|[^A-Za-z0-9_.])fun[[:space:]]+[A-Za-z_`]/) {
            name = s; sub(/^.*[^A-Za-z0-9_.]fun[[:space:]]+/, "", name); sub(/^fun[[:space:]]+/, "", name)
            sub(/[^A-Za-z0-9_].*$/, "", name)
            return name
        }
        return ""
    }
    if (lang == "go") {
        if (s ~ /^func[[:space:]]/) {
            name = s; sub(/^func[[:space:]]+(\([^)]*\)[[:space:]]*)?/, "", name)
            sub(/[^A-Za-z0-9_].*$/, "", name)
            return name
        }
        return ""
    }
    if (lang == "sh") {
        if (s ~ /^[[:space:]]*(function[[:space:]]+)?[A-Za-z_][A-Za-z0-9_-]*[[:space:]]*\(\)[[:space:]]*\{/) {
            name = s; sub(/^[[:space:]]*(function[[:space:]]+)?/, "", name)
            sub(/[[:space:]]*\(\).*$/, "", name)
            return name
        }
        return ""
    }
    if (lang == "py") {
        if (s ~ /^[[:space:]]*(async[[:space:]]+)?def[[:space:]]+/) {
            name = s; sub(/^[[:space:]]*(async[[:space:]]+)?def[[:space:]]+/, "", name)
            sub(/[^A-Za-z0-9_].*$/, "", name)
            return name
        }
        return ""
    }
    # ts / tsx / js / jsx / astro / java
    if (s ~ /(^|[^A-Za-z0-9_$])function[[:space:]]*[A-Za-z_$]/) {
        name = s; sub(/^.*function[[:space:]]*/, "", name); sub(/[^A-Za-z0-9_$].*$/, "", name)
        return (name == "" ? "<anonymous>" : name)
    }
    if (s ~ /(const|let|var)[[:space:]]+[A-Za-z_$][A-Za-z0-9_$]*[^=]*=[[:space:]]*(async[[:space:]]*)?(\(|[A-Za-z_$][A-Za-z0-9_$]*[[:space:]]*=>)/ && s ~ /=>/) {
        name = s; sub(/^[[:space:]]*(export[[:space:]]+)?(default[[:space:]]+)?(const|let|var)[[:space:]]+/, "", name)
        sub(/[^A-Za-z0-9_$].*$/, "", name)
        return name
    }
    # Class method: `name(args) {` but not a control-flow keyword.
    if (s ~ /^[[:space:]]*[A-Za-z_$][A-Za-z0-9_$<>,.\[\][:space:]]*\(.*\)[[:space:]]*(:[^={]*)?\{[[:space:]]*$/) {
        name = s; sub(/^[[:space:]]*/, "", name)
        sub(/^(public|private|protected|static|async|get|set|readonly)[[:space:]]+/, "", name)
        sub(/[^A-Za-z0-9_$].*$/, "", name)
        if (name ~ /^(if|for|while|switch|catch|do|else|try|return|new|typeof|await|with|when|function)$/) return ""
        if (name == "") return ""
        return name
    }
    return ""
}

function typeDecl(s,   name) {
    if (lang == "kt" && s ~ /^[[:space:]]*(public |internal |private |protected |open |abstract |sealed |final |data |enum |annotation |value |inner |companion )*(class|interface|object)[[:space:]]+[A-Za-z_]/) {
        name = s; sub(/^.*(class|interface|object)[[:space:]]+/, "", name); sub(/[^A-Za-z0-9_].*$/, "", name); return name
    }
    if (lang == "go" && s ~ /^type[[:space:]]+[A-Z]/) {
        name = s; sub(/^type[[:space:]]+/, "", name); sub(/[^A-Za-z0-9_].*$/, "", name); return name
    }
    if (lang == "ts" && s ~ /^[[:space:]]*export[[:space:]]+(default[[:space:]]+)?(class|interface|type|enum)[[:space:]]+[A-Za-z_$]/) {
        name = s; sub(/^.*(class|interface|type|enum)[[:space:]]+/, "", name); sub(/[^A-Za-z0-9_$].*$/, "", name); return name
    }
    if (lang == "py" && s ~ /^class[[:space:]]+[A-Za-z_]/) {
        name = s; sub(/^class[[:space:]]+/, "", name); sub(/[^A-Za-z0-9_].*$/, "", name); return name
    }
    if (lang == "java" && s ~ /^[[:space:]]*(public|protected)[[:space:]]+([a-z]+[[:space:]]+)*(class|interface|enum|record)[[:space:]]+[A-Za-z_]/) {
        name = s; sub(/^.*(class|interface|enum|record)[[:space:]]+/, "", name); sub(/[^A-Za-z0-9_].*$/, "", name); return name
    }
    return ""
}

# Names a reader cannot act on. Deliberately short: every entry has to be a
# name that tells you nothing in ANY context, because a list that flags
# plausible names teaches people to ignore the finding.
function vagueVar(n) {
    if (n ~ /^(tmp|temp|foo|bar|baz|qux|thing|stuff|aux|misc|retval|blah|asdf)[0-9]*$/) return 1
    if (n ~ /^(data|result|value|item|str|num|list|res|ret|var|val|out|inp)[0-9]+$/) return 1
    return 0
}

function shortName(n) { return (length(n) <= 2) }

function isTestFunc(n) {
    if (n ~ /^(Test|Benchmark|Fuzz|Example)[A-Z_]/) return 1
    if (n ~ /^(test_|should|it_)/) return 1
    return 0
}

function hasTestAnnotation(lineNo,   p) {
    p = lineNo - 1
    while (p >= 1 && (raw[p] ~ /^[[:space:]]*@/ || raw[p] ~ /^[[:space:]]*$/)) {
        if (raw[p] ~ /@(Test|ParameterizedTest|RepeatedTest|TestFactory|BeforeEach|AfterEach|BeforeAll|AfterAll)([^A-Za-z0-9_]|$)/) return 1
        p--
    }
    return 0
}

function vagueFunc(n) {
    n = tolower(n)
    if (n ~ /^(doit|dostuff|dowork|handleit|handledata|handlestuff|processdata|processit|myfunction|helperfunction|test[0-9]+|foo|bar|baz|tmp|temp)$/) return 1
    if (n ~ /^(handle|process|manage|helper|util|utils|check|compute|convert|transform|execute|perform)$/) return 1
    return 0
}

BEGIN { FS = "\n" }

{
    prevNonBlank = lastNonBlank
    if ($0 ~ /[^[:space:]]/) lastNonBlank = NR
    raw[NR] = $0
    code = strip($0)
    stripped[NR] = code

    # --- Scaffolding residue in comments ------------------------------------
    if (isCommentLine($0)) {
        if ($0 ~ /Feature:[[:space:]]*[0-9]{3}-/ || $0 ~ /Tasks?:[[:space:]]*T[0-9]+/)
            emit("COMMENT-SCAFFOLD", NR, "spec-kit tracking metadata in a doc comment — belongs in the commit message, not the source")
        else if ($0 ~ /[Ss]tep[[:space:]]+[0-9]+[[:space:]]*[:.]/)
            emit("COMMENT-SCAFFOLD", NR, "numbered `Step N:` narration — number the code, not the prose")
        else if ($0 ~ /TODO:?[[:space:]]*(implement|add implementation|fill in)/)
            emit("COMMENT-SCAFFOLD", NR, "placeholder TODO left by generation")
        else if ($0 ~ /(Added|Changed|Modified|Updated|Created)[[:space:]]+(by|on)[[:space:]]+([A-Z][a-z]|[0-9]{4}-|v?[0-9]+\.[0-9])/)
            emit("COMMENT-SCAFFOLD", NR, "changelog comment — git already records who changed what and when")
        else if ($0 ~ /(as an AI|AI-generated|Generated by|auto-generated by)/)
            emit("COMMENT-SCAFFOLD", NR, "generator attribution left in the source")
        else if (lang != "sh" && $0 ~ /(={20,}|\*{20,}|#{20,})/)
            emit("COMMENT-SCAFFOLD", NR, "banner wall comment — section dividers this loud are generator habit, not house style")
    }

    # --- Naming --------------------------------------------------------------
    nm = ""
    if (lang == "kt")      { if (code ~ /(^|[^A-Za-z0-9_.])(val|var)[[:space:]]+[A-Za-z_]/) { nm = code; sub(/^.*[^A-Za-z0-9_.](val|var)[[:space:]]+/, "", nm); sub(/^(val|var)[[:space:]]+/, "", nm); sub(/[^A-Za-z0-9_].*$/, "", nm) } }
    else if (lang == "ts" || lang == "java") { if (code ~ /(^|[^A-Za-z0-9_$])(const|let|var)[[:space:]]+[A-Za-z_$]/) { nm = code; sub(/^.*(const|let|var)[[:space:]]+/, "", nm); sub(/[^A-Za-z0-9_$].*$/, "", nm) } }
    else if (lang == "go") { if (code ~ /^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*[[:space:]]*:=/) { nm = code; sub(/^[[:space:]]*/, "", nm); sub(/[[:space:]]*:=.*$/, "", nm) } }
    else if (lang == "py") { if (code ~ /^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*[[:space:]]*=[^=]/) { nm = code; sub(/^[[:space:]]*/, "", nm); sub(/[[:space:]]*=.*$/, "", nm) } }
    else if (lang == "sh") { if (code ~ /^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*=/) { nm = code; sub(/^[[:space:]]*/, "", nm); sub(/=.*$/, "", nm) } }
    if (nm != "" && vagueVar(nm))
        emit("NAME-VAGUE", NR, "variable `" nm "` does not say what it holds")
    else if (nm != "" && shortName(nm)) {
        if (tracking) { shortBufN++; shortBufName[shortBufN] = nm; shortBufLine[shortBufN] = NR }
        else emit("NAME-VAGUE", NR, "top-level `" nm "` is too short to read at its distance from use")
    }

    # --- Function extents ----------------------------------------------------
    if (!tracking) {
        tryStart(code, NR)
    } else if (tracking == 1) {
        depth += countBraces(code)
        if (depth <= 0) closeFunc(NR)
    } else if (tracking == 2) {
        # Python has no closing token: a function ends at the first line that
        # dedents past it. That same line is very often the next `def`, so the
        # start check has to run again immediately or every sibling function
        # after the first goes unmeasured.
        if (code ~ /[^ \t]/ && (match(code, /[^ \t]/) - 1) <= pyIndent) {
            closeFunc(prevNonBlank)
            tryStart(code, NR)
        }
    }

    if (pending > 0 && !tracking) {
        pending--
        if (index(code, "{") > 0) { depth = countBraces(code); if (depth > 0) tracking = 1; pending = 0 }
        else if (code ~ /;[[:space:]]*$/ || code ~ /=[[:space:]]*[^ ]/) pending = 0
    }
}

function tryStart(code, lineNo,   fn) {
    fn = funcStart(code)
    if (fn == "") return
    fnName = fn; fnLine = lineNo; shortBufN = 0
    fnOverride = (code ~ /(^|[[:space:]])override([[:space:]]|$)/ || raw[lineNo - 1] ~ /@Override/)
    if (vagueFunc(fn) && !fnOverride)
        emit("NAME-VAGUE", lineNo, "function `" fn "` does not say what it does")
    fnPrivate = (code ~ /(^|[[:space:]])private([[:space:]]|$)/ || (lang == "go" && fn ~ /^[a-z]/) || (lang == "py" && fn ~ /^_/) \
                 || isTestFunc(fn) || hasTestAnnotation(lineNo))
    if (lang == "py") { pyIndent = match(code, /[^ \t]/) - 1; tracking = 2; depth = 0; return }
    depth = countBraces(code)
    if (depth > 0) { tracking = 1; return }
    # A signature wrapped across lines: keep looking for the opening brace.
    if (code !~ /=[[:space:]]*[^ ]/ && (code ~ /\($/ || (code ~ /\)/ && code !~ /\{/))) pending = 20
}

function countBraces(s,   i, c, d) {
    d = 0
    for (i = 1; i <= length(s); i++) { c = substr(s, i, 1); if (c == "{") d++; else if (c == "}") d-- }
    return d
}

# Everything a function is judged on is known only once its extent is known,
# so length and missing-doc are both reported here, anchored to the signature
# line — that is the line the driver blames when deciding BLOCK vs REVIEW.
function closeFunc(endLine,   len, p, prev, si) {
    tracking = 0
    len = endLine - fnLine + 1
    for (si = 1; si <= shortBufN; si++) {
        if (len > warnfunc)
            emit("NAME-VAGUE", shortBufLine[si], "`" shortBufName[si] "` names a value that lives in a " len "-line function — too far from its declaration to stay this short")
    }
    shortBufN = 0
    if (len > maxfunc)
        emit("FUNC-MAX", fnLine, "function `" fnName "` is " len " lines (limit " maxfunc ") — split it")
    else if (len > warnfunc)
        emit("FUNC-LONG", fnLine, "function `" fnName "` is " len " lines (target " warnfunc ") — consider splitting")

    if (len > 3 && !fnOverride && (!fnPrivate || len > warnfunc)) {
        p = fnLine - 1
        while (p >= 1 && (raw[p] ~ /^[[:space:]]*$/ || isAnnotationOrModifier(raw[p]))) p--
        prev = (p >= 1 ? raw[p] : "")
        documented = 0
        if (prev ~ /\*\/[[:space:]]*$/ || isCommentLine(prev)) documented = 1
        if (lang == "py" && raw[fnLine + 1] ~ /^[[:space:]]*("""|''')/) documented = 1
        if (!documented)
            emit("UNDOC-DECL", fnLine, "function `" fnName "` (" len " lines) has no comment saying why it exists")
    }
}

END {
    if (tracking) closeFunc(lastNonBlank)
    if (NR > maxfile)
        emit("FILE-LONG", 1, "file is " NR " lines (limit " maxfile ") — split it along a seam that already exists")

    # Types are checked at the end so a type header inside a tracked function
    # (a local class) cannot confuse the extent tracker above.
    for (i = 1; i <= NR; i++) {
        t = typeDecl(stripped[i])
        if (t == "") continue
        p = i - 1
        while (p >= 1 && (raw[p] ~ /^[[:space:]]*$/ || isAnnotationOrModifier(raw[p]))) p--
        prev = (p >= 1 ? raw[p] : "")
        ok = (prev ~ /\*\/[[:space:]]*$/ || isCommentLine(prev))
        if (lang == "py" && raw[i + 1] ~ /^[[:space:]]*("""|''')/) ok = 1
        if (!ok) emit("UNDOC-DECL", i, "type `" t "` has no comment saying what it is for")
    }
}

function emit(rule, line, msg) { printf "%s\t%d\t%s\n", rule, line, msg }
