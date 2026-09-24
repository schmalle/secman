# Asset overview performance and data-quality plan

## Decision summary

The asset overview should stop downloading and rendering the complete accessible
inventory. On entry it should render the page shell immediately, request the
accessible asset count and the first 50 rows in parallel, and progressively
enable filters and secondary controls. The table should use server-side
filtering, sorting, and pagination, with selectable page sizes of 25, 50, 100,
and 250 rows.

The service must also distinguish valid cloud identifiers from legacy or
mis-mapped values. Only a 12-digit AWS account ID and an EC2 instance ID in the
form `i-` followed by 8 or 17 hexadecimal characters may appear in the overview
or its filter choices. Invalid values remain available to an administrator in a
separate data-quality workflow; replacing a bad value with `-` in the overview
must not silently delete or rewrite source data.

This provides the largest perceived and actual improvement without making the
user wait for every asset, every workgroup relation, and every filter option.

## Why the current page slows down

The current request to `GET /api/assets` materializes every accessible asset,
sorts the full list in Kotlin, and maps every row to the detailed response. The
response mapping also touches workgroups and tags for every asset. In the
browser, `AssetManagement` stores the entire response, derives all filter
options from it, filters it in memory, and renders every matching `<tr>`. That
means database work, JSON size, browser memory, React reconciliation, and DOM
size all grow with the entire inventory even though only a screenful is useful.

The existing `GET /api/assets/count` is a good primitive for progressive
loading, but its count and the list must use identical access and validity
semantics before the number is displayed as the table total.

## Target user experience

### Initial load

1. Render the heading, actions, empty filters, and table frame immediately.
2. Start two independent requests in parallel:
   - the accessible total count; and
   - the first page, defaulting to 50 assets sorted by `createdAt DESC, id DESC`.
3. Show the count as soon as it arrives, for example **2,588 accessible assets**.
   While it is pending, show `Assets (…)`, not a full-page spinner.
4. Show 8–10 skeleton rows only inside the table while the first page is
   pending. The filters and page actions remain usable.
5. Replace the skeleton with the first page. A failure in the count must not
   hide usable rows, and a failure in the page must retain filters plus a
   focused **Retry** action.

The initial response should contain only fields needed by this table: id, name,
IP, URI, validated cloud identifiers, AD domain, OS, and permissions needed for
row actions. Workgroups, tags, descriptions, scan data, and other edit/detail
fields should be fetched only when the detail or edit view opens.

### Filtering and navigation

- Name, IP, account ID, owner, AD domain, and workgroup filters execute on the
  server. Text input is debounced by 300 ms and requests are cancelled when a
  newer value supersedes them.
- Filters, sort, page size, and cursor/page are represented in the URL so a
  result can be bookmarked and browser Back restores it.
- Changing a filter resets to the first page and shows the last successful rows
  with a subtle loading veil instead of blanking the table.
- The heading reads **2,588 accessible assets** without filters and
  **73 matching of 2,588 accessible assets** with filters. Do not infer either
  number from the length of the current page.
- Owner, domain, and workgroup choices are loaded lazily from small, scoped
  facet endpoints. They must never be derived by downloading all asset rows.
  A searchable combobox should replace a select if a facet exceeds 100 values.
- Sorting is server-side. Initially support the columns users actually scan:
  name, IP, owner, account ID, AD domain, OS, created date, and last seen.

### More entries per page

- Default to **50**, offer **25 / 50 / 100 / 250**, and remember the selection
  in local storage while also writing it to the URL.
- Enforce 250 as the API maximum. This gives power users a dense view without
  recreating the current unbounded response.
- Put the page-size control and `Showing 1–50 of 2,588` above and below the
  table. Keep Previous/Next keyboard reachable and preserve the header while
  scrolling.
- Add a **Compact rows** preference. Reduced vertical padding typically doubles
  visible rows without transferring more data and is more valuable than an
  unsafe “show all” option.
- Do not add client-side virtualization initially. Bounded pages of at most 250
  rows keep the DOM manageable and preserve native table accessibility,
  selection, browser find within the page, and predictable row actions. Measure
  rendering after server pagination and virtualize only if the 250-row p95
  render still exceeds the budget below.

## Identifier display policy

The overview is not the place to expose source-system mapping defects as if
they were real cloud identifiers.

| Field | Displayable value | Overview behavior for any other value |
|---|---|---|
| AWS account ID | Exactly 12 ASCII digits: `^\d{12}$` | Render `-`; exclude it from account facets and exact account filtering |
| EC2 instance ID | Case-insensitive input matching `^i-[0-9a-f]{8}([0-9a-f]{9})?$`; normalize the displayed prefix and hex to lowercase | Render `-`; exclude it from instance facets and exact instance filtering |

The API should return `null` for an invalid overview identifier rather than
shipping a malformed value and relying on every client to hide it. Validation
must live in one shared backend mapper/service and have table-driven unit tests
for null, blank, UUID, 11/13-digit account values, valid 12-digit account
values, uppercase `I-`, non-hex instance values, and valid 8/17-hex instance
suffixes.

This is a presentation boundary, not destructive cleanup. Add an admin-only
data-quality summary such as **18 assets have invalid cloud identifiers**,
linked to a downloadable or paged remediation view showing the asset, bad raw
value, source/import run, and expected format. Never include raw invalid values
in the normal overview response, logs, telemetry labels, or facet endpoints.

Imports should apply the same rules before persisting future cloud identifiers:
trim whitespace, normalize valid EC2 IDs, turn blank values into null, and
reject or quarantine invalid nonblank values with a per-row import error. A
separate migration/remediation job should fix existing rows only after their
authoritative source is confirmed; it must not guess which UUID belongs in
which column.

## API contract

Introduce a new endpoint rather than changing the existing unpaged contract in
place, because other clients may depend on it:

```text
GET /api/assets/search?limit=50&cursor=<opaque>&sort=-createdAt
    &name=&ip=&owner=&adDomain=&accountId=&workgroupId=
```

Suggested response:

```json
{
  "items": [],
  "nextCursor": null,
  "previousCursor": null,
  "matchingCount": 0,
  "accessibleCount": 2588,
  "limit": 50
}
```

Use keyset/cursor pagination for stable Next/Previous navigation and consistent
latency at deep positions. The cursor must be opaque, URL-safe, signed or
strictly parsed, and bound to sort/filter state. Always add `id` as the final
sort key. If direct page-number jumping is a hard requirement, offset paging is
acceptable for the first release at the present inventory size, but the UI
should prefer Previous/Next and the implementation should retain a migration
path to cursors.

The endpoint must:

- retain `@Secured(IS_AUTHENTICATED)` and apply `AssetFilterService` semantics
  before pagination, counting, facets, or sorting;
- apply filters and identifier validity in SQL, not after fetching a page;
- use a narrow DTO projection so no lazy collections are touched;
- reject unknown sort fields, malformed cursors, non-numeric limits, and limits
  outside 1–250 with a 400 response;
- escape wildcard characters for literal substring searches and bind every
  value as a query parameter;
- return 404-compatible behavior for inaccessible row actions, preserving the
  existing non-disclosure policy; and
- keep export as a separate asynchronous/all-results workflow rather than
  exporting only the visible page by accident.

`GET /api/assets/count` can remain for dashboard callers. The overview should
prefer counts returned with the paged query so totals and filters describe the
same consistent access scope. Cache only safe global administrative totals;
user-scoped counts and facets must never be shared between principals.

## Query and database plan

1. Add a lightweight `AssetOverviewRow` projection and a paged service beside
   the existing authorization service. Do not replace or bypass
   `AssetFilterService`; reuse its access predicates/accessible IDs at the SQL
   boundary.
2. Run `EXPLAIN` with production-like cardinality for the unfiltered page and
   each supported filter. Capture query count, DB time, transferred bytes, and
   serialized response size before choosing indexes.
3. Prefer indexes that support the default stable ordering and exact filters.
   Likely candidates to validate are `(created_at, id)`, `cloud_account_id`,
   `ad_domain`, and the join-table access columns. Do not add all speculative
   indexes: every CrowdStrike import pays their write cost.
4. Plain `%term%` filters on name/IP cannot use ordinary B-tree prefixes well.
   Keep the 300 ms debounce and minimum two-character substring rule initially;
   consider normalized prefix search or a dedicated search index only after
   measurement shows it is necessary.
5. Fetch no workgroup or tag collection in the overview query. Use an `EXISTS`
   predicate for the workgroup filter and fetch edit/detail relations by ID.
6. Ensure the matching count uses the same predicate builder as the row query
   so access or filter rules cannot drift. The unfiltered accessible count may
   be computed in parallel or reused from a short-lived principal-scoped cache
   if measurement proves counting material.

## Delivery sequence

### Phase 0 — baseline and acceptance data

- Record browser timings for current time-to-heading, time-to-first-row,
  response bytes, and DOM row count with roughly 2,500, 25,000, and 250,000
  accessible assets.
- Record backend query count/time and `EXPLAIN` plans for admin and a scoped
  user. Verify that the observed bottleneck matches the unbounded-load theory.
- Count invalid account and instance identifiers without exposing their values.

### Phase 1 — fast first page

- Build the projection, scoped paged endpoint, shared filter predicate, strict
  validation, and unit/integration tests.
- Switch the React overview to the new endpoint, skeleton rows, URL state,
  request cancellation, and 25/50/100/250 page sizes.
- Keep the old endpoint temporarily for compatible consumers and mark it for
  deprecation only after repository-wide and extension-client usage review.

This phase alone removes the unbounded transfer and thousands of DOM nodes and
should produce the dramatic improvement users notice.

### Phase 2 — facets and remediation

- Add scoped lazy facet endpoints/searchable comboboxes.
- Add the admin-only invalid-identifier counter and remediation report.
- Apply validation at import boundaries and plan source-confirmed cleanup of
  existing malformed values.

### Phase 3 — tune from measurements

- Add only indexes justified by Phase 0/1 query plans.
- Consider approximate/cached accessible totals if exact counts dominate the
  latency at very high scale; label estimates as estimates and refresh them in
  the background.
- Consider row virtualization only if measured 250-row browser rendering misses
  the target after pagination and compact mode.

## Acceptance criteria and observability

- p95 page shell is interactive in under 500 ms on the corporate network.
- p95 first 50 rows appear in under 1.5 s for both an administrator and a
  scoped user with a warm database; p99 API latency remains under 2 s.
- The first-page JSON is below 150 KiB and no overview response exceeds 250
  rows.
- The browser never holds or renders the full inventory merely to show a page,
  count, or filter option.
- Rapid typing cannot display an older request over newer filter input.
- Counts, filters, rows, export scope, and row actions all obey the same asset
  access rules; tests cover cross-workgroup and cross-account denial.
- Invalid cloud identifiers never appear in overview rows or facets, while the
  authorized data-quality count reconciles with the remediation result set.
- Telemetry records endpoint latency, DB duration, result count, requested page
  size, response bytes, and cancellation/error rates. It must not record filter
  text, raw account IDs, instance IDs, or opaque cursors.

## Security review (OWASP Top 10:2021)

- **A01:** existing asset authorization remains the mandatory boundary for
  rows, totals, and facets; admin remediation is separately role-gated.
- **A02:** no cryptographic change; any signed cursor uses the existing secret
  management mechanism rather than a new committed key.
- **A03:** bound query parameters, an allowlisted sort map, strict numeric
  limits, and strict cursor parsing prevent query injection.
- **A04:** bounded limits, request cancellation, debouncing, and endpoint rate
  controls reduce abuse and accidental load amplification.
- **A05:** no configuration change is proposed.
- **A06:** no new dependency is required.
- **A07:** all overview and quality endpoints remain authenticated.
- **A08:** cursors are treated as untrusted state; sign them or validate every
  decoded field against the request and allowlists.
- **A09:** log aggregate timings and failures without identifier or filter
  contents.
- **A10:** no outbound request is introduced.

No HIGH or CRITICAL security issue is inherent in this plan. The implementation
must repeat this review against its concrete diff, especially the shared access
predicate, cursor parsing, and administrative remediation endpoint.
