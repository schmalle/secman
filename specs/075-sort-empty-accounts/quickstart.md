# Quickstart: Sort Empty AWS Accounts to Bottom

**Feature**: 075-sort-empty-accounts
**Scope**: Frontend only — single file change

## What Changes

**File**: `src/frontend/src/components/AccountVulnsView.tsx`

### 1. Split account groups into two arrays

After the API data is loaded, split `summary.accountGroups` into:
- `accountsWithAssets`: groups where `totalAssets > 0`
- `accountsWithoutAssets`: groups where `totalAssets === 0`

Both arrays inherit the existing sort order (by `awsAccountId` ascending) from the backend.

### 2. Add collapse state

Add a `useState<boolean>(false)` for tracking whether the empty accounts section is expanded.

### 3. Render accounts with assets first

Replace the single `.map()` loop over `summary.accountGroups` with two sections:
1. `accountsWithAssets.map(...)` — renders exactly as today (account cards)
2. Conditional section (only if `accountsWithoutAssets.length > 0`):
   - Clickable heading: "Accounts with no assets (N)" with chevron icon
   - Collapsible wrapper around `accountsWithoutAssets.map(...)`
   - Collapsed by default

### 4. Styling

Use existing Bootstrap classes:
- Heading: `h5` with `bi-chevron-down`/`bi-chevron-up` icon, `cursor: pointer`
- Collapse wrapper: conditional rendering or `style={{ display: expanded ? 'block' : 'none' }}`

## What Does NOT Change

- Backend (`AccountVulnsService.kt`) — no changes
- API response shape — no changes
- `AssetVulnTable.tsx` — no changes
- `accountVulnsService.ts` — no changes
- Summary statistics (AWS Accounts, Total Assets, Total Vulnerabilities, By Severity) — unchanged
- Individual account card rendering — unchanged

## How to Verify

1. Run `npm run dev` in `src/frontend/`
2. Navigate to `/account-vulns`
3. Verify accounts with assets appear first
4. Verify a collapsed "Accounts with no assets (N)" section appears at the bottom
5. Click to expand/collapse the section
6. Verify summary stats at top still reflect all accounts
