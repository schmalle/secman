# Quickstart: Vulnerability Statistics Domain Filter

**Feature**: 059-vuln-stats-domain-filter
**Date**: 2026-01-04

## Overview

This feature adds a domain selector to the vulnerability statistics page, allowing users to filter statistics by Active Directory domain.

## Prerequisites

- Existing secman development environment set up
- Backend running on port 8080
- Frontend dev server running
- Test user with access to assets in multiple domains

## Key Files to Modify

### Backend

1. **VulnerabilityStatisticsController.kt**
   - Add `@QueryValue domain: String?` parameter to existing endpoints
   - Add new endpoint `GET /available-domains`

2. **VulnerabilityStatisticsService.kt**
   - Add domain filtering to existing query methods
   - Add `getAvailableDomains(authentication)` method

3. **New: AvailableDomainsDto.kt** (in dto package)
   - Simple DTO with `domains: List<String>` and `totalAssetCount: Int`

### Frontend

1. **New: DomainSelector.tsx** (in components/statistics/)
   - Dropdown component with loading/error states
   - Calls `/available-domains` endpoint
   - Stores selection in sessionStorage

2. **vulnerability-statistics.astro**
   - Import and render DomainSelector
   - Manage domain state at page level
   - Pass domain to all statistics components

3. **MostCommonVulnerabilities.tsx, MostVulnerableProducts.tsx, SeverityDistributionChart.tsx**
   - Accept optional `domain?: string` prop
   - Pass domain to API calls

4. **vulnerabilityStatisticsApi.ts**
   - Add optional `domain?: string` parameter to API methods

## Implementation Order

1. **Backend first** (allows testing with curl/Postman):
   - Add DTO
   - Add service methods
   - Add controller endpoint

2. **Frontend second**:
   - Create DomainSelector component
   - Integrate into page
   - Update existing components to accept domain prop

## Quick Test

```bash
# After backend changes, test new endpoint
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/vulnerability-statistics/available-domains

# Test domain filtering
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/vulnerability-statistics/most-common?domain=ms.home"
```

## Session Storage Key

```javascript
// Key used for persistence
const STORAGE_KEY = 'secman.vuln-stats.selectedDomain';

// Read
const domain = sessionStorage.getItem(STORAGE_KEY);

// Write
sessionStorage.setItem(STORAGE_KEY, selectedDomain);

// Clear (on "All Domains")
sessionStorage.removeItem(STORAGE_KEY);
```

## Component Props Pattern

```tsx
// Parent (vulnerability-statistics page)
const [selectedDomain, setSelectedDomain] = useState<string | null>(
  sessionStorage.getItem('secman.vuln-stats.selectedDomain')
);

<DomainSelector
  selectedDomain={selectedDomain}
  onDomainChange={setSelectedDomain}
/>

<MostCommonVulnerabilities domain={selectedDomain} />
<MostVulnerableProducts domain={selectedDomain} />
<SeverityDistributionChart domain={selectedDomain} />
```

## Notes

- Domain values are case-insensitive (normalized to lowercase on backend)
- Null domain = "All Domains" (no filtering)
- Domain filter is additive to existing access control (never bypasses RBAC)
