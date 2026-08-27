# Comment doctrine

> **Sync policy (two-way, mandatory)**: This file and
> `.claude/skills/humanizer/references/comment-doctrine.md` are one document
> kept in two harness trees. Whichever copy an agent edits, the same change is
> ported to the other **in the same commit**. `.claude/skills/` is the
> tie-breaker when the two disagree. Verify with
> `./scripts/check-skill-sync.sh` (exit 0).

The rule from the skill: **the code says what; a comment says why, or it says
nothing.** This file is the working detail — the form per language, and worked
pairs showing the difference.

---

## The keep-or-delete test

Delete the comment and reread the code. If nothing was lost, it should stay
deleted. A redundant comment is not neutral: it costs reading time forever,
and it silently goes stale, at which point it is actively misleading.

The corollary is the part people skip: **if deleting it does lose something,
that something was load-bearing and probably deserves more than it currently
has.** Most bad comment situations are not too few comments or too many, but
comments in the wrong places.

---

## What a public declaration owes its caller

One line of purpose, plus anything the signature cannot show:

- **Nullability and emptiness** — what a null or empty list means here
- **Units and ranges** — days, bytes, milliseconds; whether a bound is inclusive
- **Side effects** — writes, mail, outbound HTTP, cache invalidation
- **Transaction boundaries** — especially `@Transactional`, and any method that
  must *not* be transactional (dispatch that does per-recipient HTTP)
- **Failure behaviour** — throws, returns empty, retries, logs and continues
- **Ordering and idempotency** — safe to call twice? order-dependent?
- **Thread-safety** — only when it is not obvious, which is most of the time

Not a restatement of the parameter list. If your doc comment is the signature
in prose, delete it and let the signature speak.

---

## Form by language

**Kotlin** — KDoc on public declarations, `//` for reasons inside a body.

Kotlin block comments **nest**, which makes this a real hazard rather than a
style note: a `/api/**` inside KDoc closes the comment early and takes the rest
of the file with it. It has broken this repo before. Write paths as `` `/api/**` ``
in backticks or avoid `*/` sequences entirely.

```kotlin
/**
 * Replaces every vulnerability for one asset in a single transaction.
 *
 * Deliberately deletes then re-inserts rather than merging: a CVE absent from
 * the new batch means it was remediated, and a merge would keep it forever.
 * Callers must not rely on vulnerability ids surviving an import.
 */
@Transactional
fun replaceForAsset(assetId: Long, batch: List<VulnerabilityDto>) { … }
```

**Go** — doc comments start with the identifier, per the language convention.
Unexported helpers get a comment only when the reason is non-obvious.

```go
// Publish sends the snapshot upstream and blocks until the relay acknowledges
// it. It retries once on a 5xx; anything else is returned to the caller, which
// is expected to drop the snapshot rather than queue it.
func Publish(ctx context.Context, snapshot Snapshot) error { … }
```

**TypeScript / React** — TSDoc on exported functions, components and hooks.
Comment the *interaction* — what re-renders, what the effect depends on, why an
effect has the dependency list it has. Types already carry the shape.

```ts
/**
 * Streams exception-badge counts over SSE.
 *
 * The token goes in the query string because EventSource cannot send headers;
 * this is the one documented exception to keeping the JWT out of URLs.
 */
export function useExceptionBadge(token: string) { … }
```

**Python** — a docstring on modules, public classes and public functions.
`src/clinotify` is stdlib-only by contract; say so where an import might tempt
someone.

**Bash** — a header block saying what the script does, what it needs, and its
exit codes; a one-line comment above each function saying why it exists.
`scripts/owasp-check.sh` and `scripts/check-skill-sync.sh` are the house
reference, including their `# --- Section ---` dividers.

---

## Worked pairs

**A comment that restates**

```kotlin
// Loop over the assets and check each one
for (asset in assets) { … }
```
→ delete it.

**A comment that carries the reason**

```kotlin
// Chunked rather than one UPDATE: the unbounded form held a write lock on all
// ~1.8M vulnerability rows for up to three minutes, and every concurrent
// writer queued behind it. Atomicity is given up on purpose — this path is
// already @Async, so a failure leaves a converged prefix.
for (chunk in idRanges) { … }
```
→ keep. Nothing here is visible in the code.

**A doc comment echoing the signature**

```kotlin
/**
 * Gets the asset by id.
 * @param id the id
 * @return the asset
 */
fun getAsset(id: Long): Asset?
```
→ replace with what the caller cannot see:

```kotlin
/** Returns null when the asset exists but the caller cannot reach it — callers
 *  must not distinguish "absent" from "forbidden" in a response. */
fun getAsset(id: Long): Asset?
```

**Scaffolding residue**

```kotlin
/**
 * Service for importing CrowdStrike server vulnerabilities
 *
 * Provides functionality to:
 * - Find or create Asset records from CrowdStrike server data
 * - Import vulnerabilities with transactional replace pattern
 * - Track import statistics
 *
 * Feature: 032-servers-query-import
 * Tasks: T019, T020, T021, T022
 */
```

Three problems, and they are the common ones. The bullet list re-lists the
public methods, which are right below it and cannot go stale. `Feature:` and
`Tasks:` are spec tracking that belongs in the commit message — they rot in
place and are already meaningless to a reader today. What is missing is the
one thing the class actually needs to say:

```kotlin
/**
 * Imports CrowdStrike vulnerabilities, creating assets on first sight.
 *
 * Each server is replaced in its own transaction — delete-then-insert, never
 * merge — so a CVE missing from the new batch reads as remediation. This is
 * also why `Asset.vulnerabilities` must not cascade: JPA cascade fights the
 * manual delete and once cut 166,812 rows to 1,819.
 */
```

---

## Density

There is no target ratio. Comment the parts that are surprising and leave the
parts that are not, which produces uneven density — and that unevenness is
itself what distinguishes maintained code from generated code.

A file with a comment on every method and none inside any of them is the
signature of comments written to satisfy a rule rather than a reader. If the
humanizer scan says `UNDOC-DECL` and the honest answer is "this function is
called `findByAssetIdIn` and does exactly that", the right fix is often to
leave it alone and say so in the Deferred line.
