# Research: Sort Empty AWS Accounts to Bottom

**Feature**: 075-sort-empty-accounts
**Date**: 2026-02-05

## R1: Where to apply sorting — frontend vs backend

**Decision**: Frontend (client-side)

**Rationale**: The backend (`AccountVulnsService.kt:238`) currently sorts account groups by `awsAccountId` ascending. The API returns all accounts in a single response including `totalAssets` per group. Sorting by "has assets" is a trivial `Array.sort()` on the existing data — no backend change needed. This avoids API contract changes and keeps the change minimal.

**Alternatives considered**:
- Backend sort: Would require modifying `AccountVulnsService.kt` line 238 to use a composite sort. Rejected because it changes API behavior for all consumers and adds unnecessary backend work for a UI concern.

## R2: Collapsible section implementation

**Decision**: Use React `useState` with Bootstrap 5 collapse classes

**Rationale**: The project already uses React state for UI toggling (e.g., loading states in `AccountVulnsView.tsx`). Bootstrap 5.3 provides `.collapse` and `.show` CSS classes for expand/collapse without additional JS libraries. A simple `useState<boolean>(false)` controls whether the section is expanded.

**Alternatives considered**:
- Bootstrap JS accordion component: Rejected — heavier, requires Bootstrap JS bundle, overkill for a single toggle.
- HTML `<details>/<summary>`: Rejected — inconsistent styling across browsers and doesn't integrate well with Bootstrap card styling.

## R3: Sorting approach

**Decision**: Split `accountGroups` into two arrays using `filter()`, render them sequentially

**Rationale**: Splitting into `accountsWithAssets` and `accountsWithoutAssets` using `group.totalAssets > 0` is straightforward and avoids complex custom sort comparators. Each array preserves the existing `awsAccountId` ascending order from the backend. The collapsible section wraps only the second array.

**Alternatives considered**:
- Custom `sort()` comparator: Rejected — less readable, same result, and doesn't naturally produce the two groups needed for the collapsible section boundary.

## R4: Collapsible section heading content

**Decision**: Show "Accounts with no assets (N)" where N is the count of empty accounts, with a chevron toggle icon

**Rationale**: Including the count in the heading gives users immediate context about how many empty accounts exist without expanding. The chevron (Bootstrap icon `bi-chevron-down`/`bi-chevron-up`) is a universally understood expand/collapse affordance.
