# Rename hazards in secman

> **Sync policy (two-way, mandatory)**: This file and
> `.agents/skills/humanizer/references/rename-hazards.md` are one document kept
> in two harness trees. Whichever copy an agent edits, the same change is
> ported to the other **in the same commit**. `.claude/skills/` is the
> tie-breaker when the two disagree. Verify with
> `./scripts/check-skill-sync.sh` (exit 0).

Read this before proposing any rename. Every entry below is a way a rename
compiles cleanly, passes the tests, and breaks something at runtime — which is
why the humanizer skill proposes renames instead of applying them.

The pattern to internalise: **a compiler catches renames that cross a call
site. Nothing catches renames that cross a *name lookup*.** Everything here is
a name lookup.

---

## 1. Jackson — the silent one

A field name on a DTO or an API request/response **is** the JSON key.

Rename `cloudAccountId` to `awsAccountId` and every client still sends
`cloudAccountId`. Jackson does not error on an unknown key; it drops it. The
request succeeds, the field is null, and the failure appears as missing data
days later.

The clients are the `extensions/` repos, which are gitignored here and covered
by no build or test in this tree (`CLAUDE.md` §Extension Clients, Principle 5b).
Rediscover the surface — never trust a written list:

```bash
grep -rnE '/api/|"/mcp"|X-MCP-User-Email' extensions --include='*.py' --exclude-dir=.venv
grep -rnE '/api/v1/|/ingest/v1/' extensions/secman_app_ios --include='*.swift'
```

If a rename touches a DTO field, either add `@JsonProperty("<old name>")` to
keep the wire name stable, or update the clients in the same change. There is
no third option.

## 2. JPA / Hibernate — renames that look like data loss

Hibernate derives the column from the property name unless `@Column` pins it.
102 of the 135 files in `domain/` use `@Column`; the rest are derived, and for
those a property rename is a **column** rename.

Schema is Flyway plus Hibernate auto-update (Principle 3), so auto-update
creates the new column empty and leaves the old one populated and orphaned.
Nothing fails. The data is simply gone from the application's point of view.

```bash
grep -n "@Column" src/backendng/src/main/kotlin/com/secman/domain/<Entity>.kt
```

No `@Column` on the property you want to rename means the rename needs a Flyway
migration, and that is no longer a hygiene change.

Entity **class** names matter too: `@Entity` maps to a table name derived from
the class unless `@Table` names it, and repository derived queries
(`findByAssetIdIn`) parse property names out of the method name — rename the
property and the derived query fails at **startup**, not at compile time.

## 3. Micronaut — resolved at startup, not at compile time

- `@Named("...")` qualifiers and bean names
- `@Value("\${secman.eol.base-url}")` — 25 files bind config keys as strings
- `@Property`, `@ConfigurationProperties` prefixes
- `@Requires(property = ...)` guards, which fail *open* by silently not
  registering the bean

A broken one of these does not fail the build. It fails when the context
starts, which is exactly what Principle 5 exists to catch:

```bash
./scripts/startbackenddev.sh   # outside the sandbox; stop it after
```

Moving a class between packages has the same effect on component scanning and
on JPA entity discovery. Treat a package move as a rename, not as a file move.

## 4. MCP tool ids are the wire protocol

A tool name is a string in `McpToolPermissions.LISTING` and `.CALLING` and in
`McpToolGuards`. Rename the Kotlin class and the tool id stays; rename the tool
id and every MCP client breaks.

Worse, the two maps fail differently: a missing `CALLING` entry fails closed and
looks like a bug someone will report; a missing guard fails **open** and looks
like nothing at all (`CLAUDE.md` §A01). Six workgroup tools were listed but
uncallable for a while for exactly this reason.

If a tool id changes, all three places change together.

## 5. Frontend — the Astro/React island boundary

Props crossing from an `.astro` page into a React island are matched by name at
runtime. A renamed prop renders an undefined value rather than failing.

`sessionStorage["user"]`, cookie names (`secman_auth`), SSE query params and
`data-*` attributes are all string keys with no compiler behind them.

```bash
cd src/frontend && npm ci && npm run build   # Principle 5a — non-negotiable
```

## 6. Relay — a separate module with a versioned contract

`src/relay/` is its own Go module; `./gradlew build` does not compile it. The
iOS app reads `RelayDtos`, the section names and `SECTION_POLICIES` in
`RelaySnapshotBuilder`. Both envelopes carry a `schemaVersion` precisely so a
breaking change is announceable — bump it and update
`relaySupportedSnapshotSchemaVersion` in the app.

```bash
cd src/relay && go build ./... && go vet ./... && gofmt -l . && go test ./...
```

## 7. Test names referenced from outside the test

Gradle filters name tests as strings:

```bash
grep -rn "\-\-tests" scripts/ .claude/skills/ .agents/skills/
```

Renaming a test class that a script filters on makes the script select nothing
and report success.

---

## The check before you propose

For each rename, be able to answer all three:

1. **How many call sites?** — `grep -rn '\bOldName\b' src/ extensions/ scripts/`
2. **Is the name read as a string anywhere?** — JSON key, column, bean, config
   key, tool id, prop, test filter
3. **What runs to prove it?** — the build, plus a clean
   `./scripts/startbackenddev.sh` for anything on the backend

If you cannot answer (2), the answer is not "probably fine". Leave the name and
say why.
