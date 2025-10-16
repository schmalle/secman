# WG Vulns - Task Tracking Sheet

**Feature**: 022 - WG Vulns  
**Sprint**: [To be assigned]  
**Start Date**: [To be assigned]  
**Target End Date**: [Start + 6 days]

## 📊 Progress Overview

| Phase | Tasks | Completed | In Progress | Blocked | Not Started |
|-------|-------|-----------|-------------|---------|-------------|
| Phase 1: Backend | 7 | 0 | 0 | 0 | 7 |
| Phase 2: Frontend | 6 | 0 | 0 | 0 | 6 |
| Phase 3: Testing | 4 | 0 | 0 | 0 | 4 |
| Phase 4: Deployment | 5 | 0 | 0 | 0 | 5 |
| **Total** | **22** | **0** | **0** | **0** | **22** |

**Overall Progress**: 0% (0/22 tasks)

---

## Phase 1: Backend Foundation (Days 1-2)

### Task 1.1: Create DTOs ✅ P0
- **Assignee**: [Backend Developer]
- **Est. Duration**: 2 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: None

**Subtasks**:
- [ ] Create `WorkgroupVulnsSummaryDto.kt`
- [ ] Create `WorkgroupGroupDto.kt`
- [ ] Add KDoc comments
- [ ] Verify serialization works

**Notes**: Copy from AccountVulnsSummaryDto.kt and AccountGroupDto.kt

---

### Task 1.2: Update Repositories ✅ P0
- **Assignee**: [Backend Developer]
- **Est. Duration**: 2 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: None

**Subtasks**:
- [ ] Add `findWorkgroupsByUserEmail()` to WorkgroupRepository
- [ ] Add `findByWorkgroupIdIn()` to AssetRepository
- [ ] Add KDoc comments
- [ ] Test queries work

**Notes**: Use JOIN FETCH to avoid lazy loading issues

---

### Task 1.3: Create Service ✅ P0
- **Assignee**: [Backend Developer]
- **Est. Duration**: 4-6 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: Tasks 1.1, 1.2

**Subtasks**:
- [ ] Create WorkgroupVulnsService.kt skeleton
- [ ] Implement getWorkgroupVulnsSummary() method
- [ ] Implement countVulnerabilitiesBySeverity() method
- [ ] Add error handling (admin, no workgroups)
- [ ] Add logging
- [ ] Handle assets in multiple workgroups

**Notes**: Copy AccountVulnsService.kt as starting point

---

### Task 1.4: Create Controller ✅ P0
- **Assignee**: [Backend Developer]
- **Est. Duration**: 2 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: Task 1.3

**Subtasks**:
- [ ] Create WorkgroupVulnsController.kt
- [ ] Add annotations (@Controller, @Secured, @ExecuteOn)
- [ ] Implement GET endpoint
- [ ] Add error handling (403, 404, 500)
- [ ] Add logging

**Notes**: Copy AccountVulnsController.kt as starting point

---

### Task 1.5: Write Backend Unit Tests ✅ P0
- **Assignee**: [Backend Developer]
- **Est. Duration**: 4-6 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: Tasks 1.3, 1.4

**Subtasks**:
- [ ] Create WorkgroupVulnsServiceTest.kt
- [ ] Write 10+ test cases for service
- [ ] Create WorkgroupVulnsControllerTest.kt
- [ ] Write 5+ test cases for controller
- [ ] Verify >80% code coverage

**Notes**: Reference AccountVulnsServiceTest.kt

---

### Task 1.6: Write Backend Integration Tests ✅ P1
- **Assignee**: [Backend Developer]
- **Est. Duration**: 3-4 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: Tasks 1.3, 1.4

**Subtasks**:
- [ ] Create WorkgroupVulnsIntegrationTest.kt
- [ ] Write end-to-end tests with database
- [ ] Test workgroup membership enforcement
- [ ] Test data accuracy

**Notes**: Use test fixtures for setup

---

### Task 1.7: Write Backend Contract Tests ✅ P1
- **Assignee**: [Backend Developer]
- **Est. Duration**: 2 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: Task 1.4

**Subtasks**:
- [ ] Create WgVulnsContractTest.kt
- [ ] Validate against OpenAPI spec
- [ ] Test all status codes
- [ ] Test response schema

**Notes**: Use OpenAPI spec from contracts/wg-vulns-api.yaml

---

## Phase 2: Frontend Foundation (Days 3-4)

### Task 2.1: Create Frontend API Service ✅ P0
- **Assignee**: [Frontend Developer]
- **Est. Duration**: 1-2 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: None

**Subtasks**:
- [ ] Create workgroupVulnsService.ts
- [ ] Define TypeScript interfaces
- [ ] Implement getWorkgroupVulns() function
- [ ] Add error handling

**Notes**: Copy accountVulnsService.ts as starting point

---

### Task 2.2: Create Astro Page ✅ P0
- **Assignee**: [Frontend Developer]
- **Est. Duration**: 30 minutes
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: Task 2.3 (parallel)

**Subtasks**:
- [ ] Create wg-vulns.astro
- [ ] Import Layout and WorkgroupVulnsView
- [ ] Add client:load directive
- [ ] Set page title

**Notes**: Copy account-vulns.astro as starting point

---

### Task 2.3: Create React Component ✅ P0
- **Assignee**: [Frontend Developer]
- **Est. Duration**: 4-6 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: Task 2.1

**Subtasks**:
- [ ] Create WorkgroupVulnsView.tsx skeleton
- [ ] Implement state management
- [ ] Implement API call
- [ ] Implement loading state
- [ ] Implement error states (admin, no workgroups, general)
- [ ] Implement success state with data
- [ ] Update icons and labels
- [ ] Test component renders

**Notes**: Copy AccountVulnsView.tsx and do find/replace

---

### Task 2.4: Update Sidebar Navigation ✅ P0
- **Assignee**: [Frontend Developer]
- **Est. Duration**: 2 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: Task 2.1

**Subtasks**:
- [ ] Add hasWorkgroups state
- [ ] Add workgroup membership check
- [ ] Add menu item in Vuln Management section
- [ ] Implement conditional rendering
- [ ] Add tooltips
- [ ] Test navigation works

**Notes**: Add after "Account vulns" menu item

---

### Task 2.5: Write Frontend Component Tests ✅ P1
- **Assignee**: [Frontend Developer / QA]
- **Est. Duration**: 2-3 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: Tasks 2.1, 2.3

**Subtasks**:
- [ ] Create WorkgroupVulnsView.test.tsx
- [ ] Test loading state
- [ ] Test error states
- [ ] Test success state
- [ ] Mock API responses
- [ ] Verify >80% coverage

**Notes**: Use React Testing Library

---

### Task 2.6: Write E2E Tests ✅ P1
- **Assignee**: [QA Engineer]
- **Est. Duration**: 3-4 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: Tasks 2.2, 2.3, 2.4

**Subtasks**:
- [ ] Create wg-vulns.spec.ts
- [ ] Test navigation from sidebar
- [ ] Test page renders correctly
- [ ] Test admin redirect
- [ ] Test no workgroups flow
- [ ] Test main user flows

**Notes**: Use Playwright

---

## Phase 3: Integration & Testing (Day 5)

### Task 3.1: Manual Testing ✅ P0
- **Assignee**: [QA + Developer]
- **Est. Duration**: 3-4 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: All Phase 1 & 2 tasks

**Test Scenarios**:
- [ ] User with single workgroup
- [ ] User with multiple workgroups
- [ ] User with asset in multiple workgroups
- [ ] User with empty workgroup
- [ ] User with no workgroups
- [ ] Admin user
- [ ] Cross-browser (Chrome, Firefox, Safari, Edge)
- [ ] Responsive design (Desktop, Tablet, Mobile)

**Notes**: Document all bugs found

---

### Task 3.2: Performance Testing ✅ P1
- **Assignee**: [Backend Developer]
- **Est. Duration**: 2 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: All Phase 1 & 2 tasks

**Subtasks**:
- [ ] Test load time with typical data (5 WG, 100 assets)
- [ ] Test load time with large data (20 WG, 1000 assets)
- [ ] Check database query performance
- [ ] Test concurrent users (10 simultaneous)
- [ ] Optimize if needed

**Acceptance**: <2s load time for typical user

---

### Task 3.3: Security Testing ✅ P0
- **Assignee**: [Tech Lead / Security]
- **Est. Duration**: 2 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: All Phase 1 & 2 tasks

**Subtasks**:
- [ ] Verify access control (admin rejected, non-admin allowed)
- [ ] Test data leakage (no other users' workgroups visible)
- [ ] Test SQL injection
- [ ] Test XSS
- [ ] Verify no security vulnerabilities

**Acceptance**: Zero security issues found

---

### Task 3.4: Bug Fixes ✅ P0
- **Assignee**: [Developers]
- **Est. Duration**: 2-4 hours (buffer)
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: Tasks 3.1, 3.2, 3.3

**Subtasks**:
- [ ] Fix bugs from manual testing
- [ ] Fix bugs from performance testing
- [ ] Fix bugs from security testing
- [ ] Re-test all fixes
- [ ] Update tests if needed

**Notes**: Track bugs in issue tracker

---

## Phase 4: Documentation & Deployment (Day 6)

### Task 4.1: Update Documentation ✅ P1
- **Assignee**: [Backend + Frontend Devs]
- **Est. Duration**: 2-3 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: All previous tasks

**Subtasks**:
- [ ] Update API documentation
- [ ] Update user guide (add WG Vulns section)
- [ ] Update developer documentation
- [ ] Update HISTORY.md
- [ ] Update README.md (optional)
- [ ] Add screenshots

**Notes**: Use Markdown format

---

### Task 4.2: Code Review ✅ P0
- **Assignee**: [Tech Lead]
- **Est. Duration**: 2-3 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: All development tasks

**Review Areas**:
- [ ] Code quality (standards, error handling, logging)
- [ ] Test coverage (>80%, edge cases covered)
- [ ] Performance (no obvious issues)
- [ ] Security (access control, no vulnerabilities)
- [ ] Documentation (code comments, KDoc/JSDoc)

**Acceptance**: Code review approved, all feedback addressed

---

### Task 4.3: Pre-Deployment Checklist ✅ P0
- **Assignee**: [Tech Lead + DevOps]
- **Est. Duration**: 1 hour
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: Task 4.2

**Checklist**:
- [ ] All tests passing
- [ ] Code review approved
- [ ] Documentation updated
- [ ] Performance validated
- [ ] Security reviewed
- [ ] Rollback plan ready
- [ ] Team notified

**Acceptance**: All items checked off

---

### Task 4.4: Deployment ✅ P0
- **Assignee**: [DevOps + Developer]
- **Est. Duration**: 1-2 hours
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: Task 4.3

**Deployment Steps**:
- [ ] Deploy backend to staging
- [ ] Deploy frontend to staging
- [ ] Staging verification
- [ ] Deploy backend to production
- [ ] Deploy frontend to production
- [ ] Production verification
- [ ] Monitor logs

**Rollback**: Hide menu item or revert deployments if issues

---

### Task 4.5: Post-Deployment Monitoring ✅ P0
- **Assignee**: [Developer + DevOps]
- **Est. Duration**: 24-48 hours (ongoing)
- **Status**: ⚪ Not Started
- **Start Date**: -
- **End Date**: -
- **Blockers**: None
- **Dependencies**: Task 4.4

**Monitoring**:
- [ ] Monitor error logs (24 hours)
- [ ] Track user adoption
- [ ] Gather user feedback
- [ ] Monitor performance
- [ ] Fix critical issues immediately

**Acceptance**: Error rate <0.1%, no critical issues

---

## 📝 Daily Standup Template

### Day [X] - [Date]

**Yesterday's Progress**:
- [Completed tasks]

**Today's Plan**:
- [Tasks to work on]

**Blockers**:
- [Any blockers or issues]

**Notes**:
- [Additional notes or observations]

---

## 🐛 Bug Tracking

| Bug ID | Severity | Description | Found By | Assigned To | Status | Notes |
|--------|----------|-------------|----------|-------------|--------|-------|
| - | - | - | - | - | ⚪ New | - |

**Severity Levels**:
- 🔴 Critical: Blocks deployment
- 🟠 High: Impacts main functionality
- 🟡 Medium: Minor functionality issue
- 🟢 Low: Cosmetic or nice-to-have

---

## 📊 Burndown Chart (Manual Update)

| Day | Planned Remaining | Actual Remaining | Notes |
|-----|-------------------|------------------|-------|
| Day 1 | 22 tasks | - | Backend DTOs & Repos start |
| Day 2 | 17 tasks | - | Backend Service & Controller |
| Day 3 | 13 tasks | - | Frontend Service & Page |
| Day 4 | 9 tasks | - | Frontend Component & Sidebar |
| Day 5 | 4 tasks | - | Testing & Bug Fixes |
| Day 6 | 0 tasks | - | Documentation & Deployment |

---

## 🎯 Risk Register

| Risk | Status | Mitigation | Owner | Notes |
|------|--------|------------|-------|-------|
| Performance issues with many workgroups | ⚪ Open | Load test early, optimize queries | Backend Dev | Test with 50+ WG |
| Lazy loading exceptions | ⚪ Open | Use JOIN FETCH | Backend Dev | Test thoroughly |
| Workgroup membership caching | ⚪ Open | Refresh on page load | Frontend Dev | Monitor in prod |

**Status Legend**: ⚪ Open | 🟡 Mitigated | 🟢 Resolved | 🔴 Realized

---

## 📞 Team Contacts

| Role | Name | Email | Slack | Availability |
|------|------|-------|-------|--------------|
| Backend Developer | [Name] | [Email] | @handle | [Hours] |
| Frontend Developer | [Name] | [Email] | @handle | [Hours] |
| QA Engineer | [Name] | [Email] | @handle | [Hours] |
| Tech Lead | [Name] | [Email] | @handle | [Hours] |
| DevOps | [Name] | [Email] | @handle | [Hours] |

---

## 📚 Resources

- **Specification**: `specs/022-wg-vulns-feature/`
- **Implementation Plan**: `IMPLEMENTATION_PLAN.md`
- **API Contract**: `contracts/wg-vulns-api.yaml`
- **UI Mockups**: `UI_MOCKUPS.md`
- **Developer Guide**: `QUICKSTART.md`

---

## ✅ Sign-Off

| Milestone | Completed By | Date | Sign-Off |
|-----------|--------------|------|----------|
| Phase 1: Backend Complete | - | - | - |
| Phase 2: Frontend Complete | - | - | - |
| Phase 3: Testing Complete | - | - | - |
| Phase 4: Deployed to Production | - | - | - |

---

**Last Updated**: [Date]  
**Next Review**: [Date]  
**Status**: 🟡 In Progress / 🟢 Complete / 🔴 Blocked
