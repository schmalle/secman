# Feature 022: WG Vulns - Workgroup-Based Vulnerability View

## Quick Links
- [SUMMARY.md](./SUMMARY.md) - Executive summary and high-level overview
- [PLAN.md](./PLAN.md) - Detailed technical design and implementation plan
- [contracts/wg-vulns-api.yaml](./contracts/wg-vulns-api.yaml) - OpenAPI specification for the API

## What is this feature?

WG Vulns is a new view under Vuln Management that allows non-admin users to see vulnerabilities grouped by their workgroups. This feature mirrors the existing Account Vulns UI pattern but organizes data by team/workgroup structure instead of AWS accounts.

## User Story

> **As a** non-admin user who is a member of one or more workgroups  
> **I want to** view vulnerabilities organized by my workgroups and their assets  
> **So that** I can manage security issues for resources within my team's scope

## Key Features

✅ **Workgroup-Based Grouping** - Assets organized by workgroups user is a member of  
✅ **Severity Breakdown** - Critical, High, Medium counts at all levels  
✅ **Summary Statistics** - Global totals for workgroups, assets, vulnerabilities  
✅ **Consistent UI** - Reuses Account Vulns visual patterns  
✅ **Access Control** - Enforces workgroup membership, rejects admin users  
✅ **Asset Navigation** - Click asset to view detailed information  

## Architecture Overview

### Backend
```
GET /api/wg-vulns
├── WorkgroupVulnsController (REST endpoint)
├── WorkgroupVulnsService (business logic)
│   ├── Find user's workgroups
│   ├── Find assets in workgroups
│   ├── Count vulnerabilities by severity
│   └── Build response DTO
└── WorkgroupRepository & AssetRepository (data access)
```

### Frontend
```
/wg-vulns (page)
└── WorkgroupVulnsView (React component)
    ├── workgroupVulnsService (API client)
    ├── AssetVulnTable (reused component)
    └── SeverityBadge (reused component)
```

### Data Flow
```
User clicks "WG vulns" in sidebar
    ↓
Frontend calls GET /api/wg-vulns
    ↓
Backend validates authentication & authorization
    ↓
Backend queries workgroups and assets
    ↓
Backend calculates vulnerability counts
    ↓
Frontend displays grouped data with summary stats
```

## Access Control Matrix

| User Type | Has Workgroups | Behavior |
|-----------|----------------|----------|
| Admin | N/A | ❌ Rejected with 403, redirect to System Vulns |
| Non-admin | Yes | ✅ Shows WG Vulns view with their workgroups |
| Non-admin | No | ⚠️ Shows empty state with 404 message |
| Not authenticated | N/A | ❌ Rejected with 401 |

## UI Preview

```
┌─────────────────────────────────────────────────────────┐
│ 🔒 Workgroup Vulnerabilities              [🔄 Refresh]  │
├─────────────────────────────────────────────────────────┤
│ ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐│
│ │Workgroups │ │Total      │ │Total Vulns│ │By Severity││
│ │     3     │ │Assets: 25 │ │    450    │ │🔴 50      ││
│ │           │ │           │ │           │ │🟠 150     ││
│ │           │ │           │ │           │ │🟡 250     ││
│ └───────────┘ └───────────┘ └───────────┘ └───────────┘│
├─────────────────────────────────────────────────────────┤
│ ┌─ 👥 Security Team ─────────────────────────────────┐  │
│ │   10 assets  🔴 20  🟠 60  🟡 120                  │  │
│ ├─────────────────────────────────────────────────────┤ │
│ │ Asset Name        Type    Vulns   🔴  🟠  🟡      │ │
│ │ web-server-01     SERVER    50     5   15  30      │ │
│ │ db-server-01      DATABASE  30     2   10  18      │ │
│ │ app-server-01     SERVER    20     1    5  14      │ │
│ └─────────────────────────────────────────────────────┘ │
│                                                           │
│ ┌─ 👥 Development Team ──────────────────────────────┐  │
│ │   8 assets  🔴 15  🟠 45  🟡 80                    │  │
│ ├─────────────────────────────────────────────────────┤ │
│ │ Asset Name        Type    Vulns   🔴  🟠  🟡      │ │
│ │ dev-server-01     SERVER    40     4   12  24      │ │
│ │ test-server-01    SERVER    25     2    8  15      │ │
│ └─────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

## Implementation Phases

### Phase 1: Backend Foundation (Days 1-2)
- [ ] Create DTOs
- [ ] Add repository methods
- [ ] Implement service logic
- [ ] Write service unit tests

### Phase 2: Backend API (Days 2-3)
- [ ] Implement controller
- [ ] Write controller unit tests
- [ ] Write integration tests
- [ ] Write contract tests

### Phase 3: Frontend Foundation (Days 3-4)
- [ ] Create API service
- [ ] Create page component
- [ ] Create view component
- [ ] Write component tests

### Phase 4: Frontend Integration (Days 4-5)
- [ ] Update sidebar navigation
- [ ] Add workgroup membership check
- [ ] Write E2E tests
- [ ] Manual testing

### Phase 5: Polish & Deploy (Days 5-6)
- [ ] Performance testing
- [ ] Bug fixes
- [ ] Documentation updates
- [ ] Deploy to production

## Files to Create

### Backend (Kotlin)
```
src/backendng/src/main/kotlin/com/secman/
├── dto/
│   ├── WorkgroupVulnsSummaryDto.kt (new)
│   └── WorkgroupGroupDto.kt (new)
├── service/
│   └── WorkgroupVulnsService.kt (new)
├── controller/
│   └── WorkgroupVulnsController.kt (new)
└── repository/
    ├── WorkgroupRepository.kt (update)
    └── AssetRepository.kt (update)

src/backendng/src/test/kotlin/com/secman/
├── service/
│   └── WorkgroupVulnsServiceTest.kt (new)
├── controller/
│   └── WorkgroupVulnsControllerTest.kt (new)
├── integration/
│   └── WorkgroupVulnsIntegrationTest.kt (new)
└── contract/
    └── WgVulnsContractTest.kt (new)
```

### Frontend (TypeScript/React)
```
src/frontend/src/
├── pages/
│   └── wg-vulns.astro (new)
├── components/
│   └── WorkgroupVulnsView.tsx (new)
├── services/
│   └── workgroupVulnsService.ts (new)
└── tests/e2e/
    └── wg-vulns.spec.ts (new)
```

## Testing Checklist

- [ ] Service unit tests (admin rejection, no workgroups, success cases)
- [ ] Controller unit tests (authentication, authorization, error handling)
- [ ] Integration tests (database queries, data accuracy)
- [ ] Contract tests (API specification compliance)
- [ ] Component tests (React component behavior)
- [ ] E2E tests (user flows, navigation, error states)
- [ ] Manual testing (cross-browser, responsive design)
- [ ] Performance testing (load time, query efficiency)

## Success Metrics

**Technical Metrics**
- Page load time < 2 seconds (95th percentile)
- Error rate < 0.1%
- Zero security incidents
- Code coverage > 80%

**User Adoption**
- 60% of users with workgroups use feature in first month
- Average 5+ visits per active user per week
- User satisfaction rating 4+ stars

## Edge Cases Handled

1. ✅ Asset in multiple workgroups → Shows in all applicable groups
2. ✅ Workgroup with no assets → Shows workgroup with 0 assets
3. ✅ User with no workgroups → Shows empty state with message
4. ✅ Admin user → Redirected to System Vulns
5. ✅ Concurrent data changes → Refresh button updates data
6. ✅ Asset with no vulnerabilities → Shows 0 vulnerabilities

## Dependencies

- Workgroup infrastructure (Feature 008) ✅
- Account Vulns patterns (Feature 018/019) ✅
- Existing asset and vulnerability tables ✅
- Bootstrap Icons (bi-people-fill) ✅

## Related Features

- **Feature 008**: Workgroup-Based Access Control (foundation)
- **Feature 018**: Account Vulns view (UI pattern reference)
- **Feature 019**: Severity breakdown (reused logic)
- **Feature 017**: User mapping management (similar access control)

## Documentation

- User Guide: Section on WG Vulns view
- API Docs: `/api/wg-vulns` endpoint
- Developer Guide: Service and controller patterns
- HISTORY.md: Feature changelog entry

## Support & Feedback

For questions or issues:
1. Check [PLAN.md](./PLAN.md) for detailed technical information
2. Review [wg-vulns-api.yaml](./contracts/wg-vulns-api.yaml) for API spec
3. Contact development team via project channels

## Approval Status

- [ ] Product Owner approved
- [ ] Tech Lead reviewed
- [ ] Security reviewed
- [ ] UX reviewed
- [ ] DevOps reviewed

## Version History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2025-10-16 | Development Team | Initial specification |

---

**Status**: 📋 Specification Ready  
**Priority**: P2  
**Estimated Effort**: 5-6 days  
**Target Release**: TBD
