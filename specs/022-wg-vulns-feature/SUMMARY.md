# Feature 022: WG Vulns - Executive Summary

## Overview
Add a new "WG Vulns" menu item under Vuln Management that displays vulnerabilities grouped by workgroups, mirroring the existing Account Vulns UI pattern.

## Why This Feature?
Users who are members of workgroups need a way to view security vulnerabilities scoped to their team's resources, similar to how Account Vulns shows vulnerabilities scoped to AWS accounts.

## Key Benefits
1. **Team-focused view**: Users see only vulnerabilities relevant to their workgroups
2. **Consistent UX**: Reuses proven UI patterns from Account Vulns
3. **Efficient implementation**: Leverages existing infrastructure and components
4. **Security-first**: Enforces workgroup membership at database level

## High-Level Design

### Backend
- **New Endpoint**: `GET /api/wg-vulns`
- **New Service**: `WorkgroupVulnsService` (similar to `AccountVulnsService`)
- **New DTOs**: `WorkgroupVulnsSummaryDto`, `WorkgroupGroupDto`
- **Reuses**: Existing `AssetVulnCountDto`, severity calculation patterns

### Frontend
- **New Page**: `/wg-vulns`
- **New Component**: `WorkgroupVulnsView` (similar to `AccountVulnsView`)
- **New Service**: `workgroupVulnsService.ts`
- **Reuses**: `AssetVulnTable`, `SeverityBadge` components

### Navigation
- Add "WG vulns" menu item under Vuln Management
- Conditionally displayed based on workgroup membership
- Disabled for admin users (redirect to System Vulns)

## Access Control
- ✅ Authenticated users who are workgroup members
- ❌ Admin users (should use System Vulns)
- ❌ Users with no workgroup memberships

## UI Features
1. Summary cards: Workgroups count, total assets, total vulnerabilities, severity breakdown
2. Workgroup groups: Each workgroup displayed with its assets
3. Severity badges: Critical, High, Medium counts at all levels
4. Asset tables: Sortable by vulnerability count (descending)
5. Asset links: Click to navigate to asset detail page
6. Refresh button: Manual data refresh

## Data Grouping Logic
```
User's Workgroups (sorted alphabetically)
└── Workgroup A
    ├── Asset 1 (50 vulns: 5 critical, 15 high, 30 medium)
    ├── Asset 2 (30 vulns: 2 critical, 10 high, 18 medium)
    └── Asset 3 (10 vulns: 0 critical, 3 high, 7 medium)
└── Workgroup B
    ├── Asset 4 (20 vulns: 1 critical, 5 high, 14 medium)
    └── Asset 5 (5 vulns: 0 critical, 1 high, 4 medium)

Global Totals: 2 workgroups, 5 assets, 115 vulnerabilities
Severity: 8 critical, 34 high, 73 medium
```

## Implementation Effort
- **Estimated**: 5-6 days
- **Backend**: 2-3 days
- **Frontend**: 2-3 days
- **Testing**: 1-2 days (parallel with development)

## Risk Assessment
| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Performance issues with many workgroups | Low | Medium | Efficient SQL queries, tested up to 50 workgroups |
| Asset duplication in multiple workgroups | Medium | Low | Expected behavior, documented clearly |
| Security bypass | Very Low | High | Database-level enforcement, comprehensive tests |
| UI inconsistency | Low | Low | Reuse existing components and patterns |

## Dependencies
- ✅ Existing workgroup infrastructure (Feature 008)
- ✅ Existing Account Vulns patterns (Feature 018/019)
- ✅ Existing severity breakdown logic
- ✅ No database schema changes required

## Testing Coverage
- Unit tests: Service, Controller, Repository methods
- Integration tests: End-to-end with database
- Component tests: React components
- E2E tests: Playwright scenarios
- Contract tests: API specification

## Success Criteria
✅ Non-admin users can view vulnerabilities by workgroup  
✅ Admin users are redirected appropriately  
✅ Page loads in < 2 seconds for typical user  
✅ Zero security vulnerabilities  
✅ 60%+ adoption within 1 month  
✅ User satisfaction rating 4+ stars  

## Future Enhancements (Not in v1)
- Export to CSV/PDF
- Filtering by severity
- Search functionality
- Real-time updates
- Pagination for large datasets
- Email notifications

## Comparison with Account Vulns

| Aspect | Account Vulns | WG Vulns |
|--------|--------------|----------|
| **Grouping** | AWS Accounts | Workgroups |
| **Access Control** | AWS account mapping | Workgroup membership |
| **Sorting** | By AWS account ID | By workgroup name |
| **Admin Access** | Rejected → System Vulns | Rejected → System Vulns |
| **UI Pattern** | Summary + Groups + Assets | Summary + Groups + Assets |
| **Severity Breakdown** | ✅ Critical, High, Medium | ✅ Critical, High, Medium |
| **Asset Links** | ✅ Navigate to detail | ✅ Navigate to detail |
| **Refresh Button** | ✅ Manual refresh | ✅ Manual refresh |

## Questions & Answers

**Q: Why not combine with Account Vulns?**  
A: Different access control models. AWS accounts are infrastructure-based, workgroups are team-based. Users may need both views for different purposes.

**Q: What if an asset is in multiple workgroups?**  
A: Asset appears in ALL applicable workgroup sections. Global totals count each asset/vulnerability only once.

**Q: Why reject admin users?**  
A: Consistency with Account Vulns. Admins have access to all data via System Vulns and don't need workgroup-scoped view.

**Q: Can we reuse Account Vulns code?**  
A: We reuse DTOs (AssetVulnCountDto), UI components (AssetVulnTable, SeverityBadge), and patterns (severity calculation, sorting). The service and controller are separate due to different data sources.

**Q: Performance with 100+ workgroups?**  
A: Current design targets 10-20 workgroups. For 100+, we recommend pagination (future enhancement).

## Approval Checklist
- [ ] Product Owner: Feature scope approved
- [ ] Tech Lead: Architecture reviewed
- [ ] Security: Access control reviewed
- [ ] UX: UI consistency verified
- [ ] DevOps: Deployment plan reviewed

## Next Steps
1. Review and approve this specification
2. Create implementation tasks in project tracker
3. Assign to development team
4. Schedule sprint planning
5. Begin Phase 1: Backend Foundation
