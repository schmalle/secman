# Hot-path fixes — the control that already exists for each rule

> **Sync policy (two-way, mandatory)**: counterpart is
> `.agents/skills/optimizer/references/hot-paths.md`. Edit both in the same
> commit; verify with `./scripts/check-skill-sync.sh`.

Read the entry for the rule you are looking at. Each one names the pattern this
repo already uses, so the fix is a reuse rather than an invention, and the
guardrail that has to survive it.

## FETCH-ALL-FILTER — whole table in, most of it discarded

The Kotlin `.filter` after `findAll()` is doing what the database was going to
do for free, after paying to materialize every entity.

Three fixes, in order of preference:

1. **A derived finder.** `findByRole(role)`, `findByLockedReleaseId(id)`,
   `findByUploadedByInOrderByScanDateDesc(names)`. Spring Data / Micronaut Data
   derives the query from the name; nothing to write but the signature.
2. **A projection**, when only ids are needed. `findAllIds()` returns `Long`s
   without building entities — this is what the `AssetFilterService` admin
   paths use. Look for callers that immediately `.map { it.id }`; they want the
   projection, not the entities.
3. **Nullable-parameter filtering**, when the predicate varies.
   `findLatestVulnerabilitiesPerAssetWithFilters` is the pattern: one query
   where an absent filter binds null, rather than one method per filter
   combination.

**Guardrail.** Everything stays bound (`:name`). The `VulnQuerySql` fragments
interpolate SQL *structure* at compile time and never a request value — any new
shared fragment must keep that property (A03). Column names, sort direction and
table names that cannot be bound go through a closed allowlist or enum.

## UNPAGED — the row count is whatever production grew to

`Pageable.UNPAGED` with a comment explaining that the caller needs everything
is the signature of a filter that could not be expressed in SQL at the time.
For vulnerabilities it usually can be now: the `excepted` flag is materialized
precisely so exception filtering runs in the database
(`VulnQuerySql.NOT_EXCEPTED`).

**Guardrail.** Scoped users must still flow through
`AssetFilterService.getAccessibleAssetIds()`. Consolidating query paths must
not inline or optimize away that call. `ExceptedFlagSqlAgreementIntegrationTest`
pins the `excepted` semantics — run it after any predicate change.

## QUERY-IN-LOOP — one round-trip per element

Two shapes, two fixes:

- **Loop over ids, query each.** Replace with the `In`-suffixed finder:
  `findByIdIn(ids)`, `findByWorkgroupsIdIn(ids)`, `findByAssetIdIn(ids, pageable)`.
- **Loop over rows, look something up per row.** Hoist a map before the loop.
  `AssetImportService` does one `findByNameIgnoreCase` per workgroup name per
  imported row; the whole set of names is known before the loop starts.

**Guardrail.** Batching must not turn a per-element error into a silent skip.
The `ImportResult` pattern is the contract: validate per element, return a
per-element outcome list, and report missing ids explicitly. Never all-or-nothing
where the loop used to report each failure.

## TXN-BLOCKING-IO — a connection held for the length of a timeout

A `@Transactional` method holds a pooled connection until it returns. An SMTP
or HTTP call inside one converts a slow dependency into pool exhaustion for
everybody, which is how a mail server hiccup becomes a site outage.

- **Outbound HTTP.** Hoist the call out. `NormMappingService` already has the
  split — the suggestion phase does the HTTP, `applyMappings` does the
  transactional write. The fix is to stop wrapping both.
- **Email.** Publish an event and let an `@EventListener @Async` listener send
  after commit. `ChatNotificationService` is the model, and its dispatch is
  deliberately non-`@Transactional` for exactly this reason.
- **Self-invocation.** Micronaut AOP does not intercept a call to `this`, so a
  hoisted method called internally is not actually transactional. The blessed
  workaround here is the split bean (`AsyncExceptionRecompute`); `Provider<Self>`
  is the other one already in use.

**Guardrail.** Keep the atomicity the transaction was there for. If applying a
result must be all-or-nothing, the *apply* stays in one transaction — only the
network call moves out. Partial application on a failed call must remain
impossible.

## AWAIT-IN-LOOP and SERIAL-AWAITS — latency is the sum

`Promise.allSettled`, not `Promise.all`. The difference matters here: the home
dashboard wraps each card's fetch in its own `try`/`catch` so one dead endpoint
degrades one card, and `Promise.all` rejects the whole batch on the first
failure, throwing that isolation away.

The shape that preserves it:

```ts
const results = await Promise.allSettled(
    cards.map(async (c) => {
        try {
            return { key: c.key, value: await c.fetch() };
        } catch {
            return { key: c.key, value: null };   // this card only
        }
    }),
);
```

For per-row POSTs (questionnaire submit, assessment save, import rows) the
right answer is usually a bulk endpoint rather than client-side concurrency —
with a per-element result list, per the `ImportResult` guardrail above.

## TIMER-LEAK — the poll that outlives the component

The cleanup must clear the handle the effect created. Closing over a state
variable set later reads correctly and clears nothing, because the cleanup
captured its value at mount:

```tsx
useEffect(() => {
    const id = setInterval(refresh, 30_000);
    return () => clearInterval(id);      // the local const, not the state
}, []);
```

## LOCK-IO — every reader queues behind the fsync

Marshalling and fsyncing under a write lock serializes every reader for the
duration. The fix is to mark dirty under the lock and let an existing
maintenance goroutine persist outside it.

**Guardrail, and it is the whole decision.** Only state whose loss is bounded
*and* harmless may be deferred. In the relay's registry, `lastSeenAt` qualifies
— worst case is one minute of stale liveness metadata. Enrollment, revocation
and control changes do not: durability of security-relevant state before the
request is acknowledged is the point of the synchronous persist, and deferring
it is a security regression wearing a performance costume.

## WIDE-FETCH and EAGER-ISLAND — the frontend's two standing costs

- **WIDE-FETCH.** A `<select>` does not need the asset table. A typeahead
  endpoint (`?q=&limit=20`) is the fix; `ProductAutocomplete` already implements
  the client half. **Guardrail:** the search runs through `AssetFilterService`
  scoping server-side, exactly like every other asset read.
- **EAGER-ISLAND.** Decide per page, not globally. A table the user interacts
  with immediately stays `client:load`; a chart below the fold or an admin panel
  behind a tab becomes `client:visible` or `client:idle`. **Guardrail:** hydration
  timing changes are precisely the failure class `/e2ejs` exists to catch — run
  it for both roles on every page you touch.
